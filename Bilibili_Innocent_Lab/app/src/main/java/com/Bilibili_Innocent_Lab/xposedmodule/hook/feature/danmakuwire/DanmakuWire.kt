package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * wire-format 扫描的纯 Kotlin 实现：native 不可用时的回退层，也是安装期自检与
 * 单测的**差分基准**（oracle）。语义与 `danmaku-wire/src/lib.rs` 逐条对齐：
 * 同字段重复取最后一次、命名字段 wire type 不符按未知字段跳过、解析层级的
 * group(3/4)/非法 wire type 拒绝、改色时彩字字段重复拒绝、其余字节逐字节保留。
 * 两边任何不一致都会在安装期自检里暴露并停用 native。
 */
internal object DanmakuWire {

    /** 与 Rust 侧 MAX_REPLY_BYTES/MAX_ELEMS/MAX_SRCS 对齐。 */
    internal const val MAX_REPLY_BYTES = 16 * 1024 * 1024
    internal const val MAX_ELEMS = 65_536
    internal const val MAX_SRCS = 65_536

    internal data class ElemSignal(
        val weight: Int?,
        val colorful: Int?,
        val contentStart: Int,
        val contentLen: Int,
        val hasContent: Boolean,
    )

    internal class Scan(val elems: List<ElemSignal>, val srcKinds: List<Int?>) {
        val elemCount: Int get() = elems.size
        val srcCount: Int get() = srcKinds.size
    }

    /** 拆平布局与 Rust `flatten` 完全一致（flags bit0/1/2 = weight/colorful/content 存在）。 */
    fun flatten(scan: Scan): IntArray {
        val out = IntArray(2 + scan.elems.size * 5 + scan.srcKinds.size * 2)
        out[0] = scan.elems.size
        out[1] = scan.srcKinds.size
        var i = 2
        for (e in scan.elems) {
            var flags = 0
            if (e.weight != null) flags = flags or 1
            if (e.colorful != null) flags = flags or 2
            if (e.hasContent) flags = flags or 4
            out[i] = e.weight ?: 0
            out[i + 1] = e.colorful ?: 0
            out[i + 2] = flags
            out[i + 3] = e.contentStart
            out[i + 4] = e.contentLen
            i += 5
        }
        for (s in scan.srcKinds) {
            out[i] = s ?: 0
            out[i + 1] = if (s != null) 1 else 0
            i += 2
        }
        return out
    }

    fun fromFlattened(flat: IntArray): Scan? {
        if (flat.size < 2) return null
        val elemCount = flat[0]
        val srcCount = flat[1]
        if (elemCount < 0 || srcCount < 0) return null
        if (flat.size != 2 + elemCount * 5 + srcCount * 2) return null
        val elems = ArrayList<ElemSignal>(elemCount)
        var i = 2
        repeat(elemCount) {
            val flags = flat[i + 2]
            elems.add(
                ElemSignal(
                    weight = if (flags and 1 != 0) flat[i] else null,
                    colorful = if (flags and 2 != 0) flat[i + 1] else null,
                    contentStart = if (flags and 4 != 0) flat[i + 3] else -1,
                    contentLen = if (flags and 4 != 0) flat[i + 4] else 0,
                    hasContent = flags and 4 != 0,
                )
            )
            i += 5
        }
        val srcs = ArrayList<Int?>(srcCount)
        repeat(srcCount) {
            srcs.add(if (flat[i + 1] != 0) flat[i] else null)
            i += 2
        }
        return Scan(elems, srcs)
    }

    private class Cursor(val buf: ByteArray, var pos: Int = 0, val limit: Int = buf.size) {
        fun remaining() = limit - pos
        fun take(len: Int): Int? {
            if (len < 0 || remaining() < len) return null
            val start = pos
            pos += len
            return start
        }
        fun varint(): Long? {
            var value = 0L
            var shift = 0
            repeat(10) {
                if (remaining() < 1) return null
                val byte = buf[pos++].toLong() and 0xff
                if (shift < 64) {
                    value = value or ((byte and 0x7f) shl shift)
                }
                if (byte and 0x80 == 0L) return value
                shift += 7
            }
            return null
        }
        fun tag(): Pair<Int, Int>? {
            val raw = varint() ?: return null
            val field = (raw ushr 3).toInt()
            val wt = (raw and 7).toInt()
            if (field == 0 || raw ushr 32 != 0L) return null
            return field to wt
        }
        /** 长度先比后转：有符号 Long 负值与 32 位 JVM Long→Int 回绕必须先拒绝。 */
        fun delimited(): Int? {
            val len = varint() ?: return null
            if (len < 0L || len > remaining()) return null
            return len.toInt()
        }
        fun skipPayload(wt: Int): Boolean = when (wt) {
            0 -> varint() != null
            1 -> take(8) != null
            2 -> delimited()?.let { take(it) != null } == true
            5 -> take(4) != null
            else -> false
        }
    }

    private fun scanElem(buf: ByteArray, from: Int, to: Int, f: DanmakuWireFields): ElemSignal? {
        val cur = Cursor(buf, from, to)
        var weight: Int? = null
        var colorful: Int? = null
        var contentStart = -1
        var contentLen = 0
        var hasContent = false
        while (cur.pos < to) {
            val (field, wt) = cur.tag() ?: return null
            when {
                field == f.weightField && wt == 0 -> weight = (cur.varint() ?: return null).toInt()
                field == f.colorfulField && wt == 0 -> colorful = (cur.varint() ?: return null).toInt()
                field == f.contentField && wt == 2 -> {
                    val len = cur.delimited() ?: return null
                    val start = cur.take(len) ?: return null
                    hasContent = true
                    contentStart = start
                    contentLen = len
                }
                else -> if (!cur.skipPayload(wt)) return null
            }
        }
        if (cur.pos != to) return null
        return ElemSignal(weight, colorful, contentStart, contentLen, hasContent)
    }

    /** 返回 (是否解析成功, type 值)：无 type 字段是合法状态（kind=null），不能与拒绝混叠。 */
    private fun scanSrc(buf: ByteArray, from: Int, to: Int, f: DanmakuWireFields): Pair<Boolean, Int?> {
        val cur = Cursor(buf, from, to)
        var kind: Int? = null
        while (cur.pos < to) {
            val (field, wt) = cur.tag() ?: return false to null
            if (field == f.colorfulTypeField && wt == 0) {
                kind = (cur.varint() ?: return false to null).toInt()
            } else if (!cur.skipPayload(wt)) {
                return false to null
            }
        }
        if (cur.pos != to) return false to null
        return true to kind
    }

    fun scan(bytes: ByteArray, f: DanmakuWireFields): Scan? {
        if (!f.isValid()) return null
        if (bytes.size > MAX_REPLY_BYTES) return null
        val cur = Cursor(bytes)
        val elems = ArrayList<ElemSignal>()
        val srcs = ArrayList<Int?>()
        while (cur.remaining() > 0) {
            val (field, wt) = cur.tag() ?: return null
            val isElem = field == f.elemsField && wt == 2
            val isSrc = field == f.colorfulSrcField && wt == 2
            if (isElem || isSrc) {
                val len = cur.delimited() ?: return null
                val start = cur.take(len) ?: return null
                if (isElem) {
                    if (elems.size >= MAX_ELEMS) return null
                    elems.add(scanElem(bytes, start, start + len, f) ?: return null)
                } else {
                    if (srcs.size >= MAX_SRCS) return null
                    val (srcOk, kind) = scanSrc(bytes, start, start + len, f)
                    if (!srcOk) return null
                    srcs.add(kind)
                }
            } else if (!cur.skipPayload(wt)) {
                return null
            }
        }
        return Scan(elems, srcs)
    }

    private fun putVarint(out: ByteArrayOutput, value: Long) {
        var v = value
        while (true) {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
            if (v == 0L) return
        }
    }

    /** 极简可增长字节输出：热路径复用，零装箱。 */
    internal class ByteArrayOutput(initial: Int = 64) {
        private var buf = ByteArray(initial)
        private var size = 0
        val length: Int get() = size
        fun reset() {
            size = 0
        }
        fun write(b: Int) {
            ensure(1)
            buf[size++] = b.toByte()
        }
        fun write(src: ByteArray, from: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, from, buf, size, len)
            size += len
        }
        fun writeTo(other: ByteArrayOutput) {
            other.write(buf, 0, size)
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
        private fun ensure(extra: Int) {
            if (size + extra > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
            }
        }
    }

    /** 改色：只替换彩字字段的值字节（tag 原样保留）；彩字字段重复出现返回 null（非规范输入）。 */
    private fun rewriteElem(
        buf: ByteArray, from: Int, to: Int, f: DanmakuWireFields, out: ByteArrayOutput,
    ): Boolean {
        val cur = Cursor(buf, from, to)
        var colorfulSeen = 0
        while (cur.pos < to) {
            val start = cur.pos
            val (field, wt) = cur.tag() ?: return false
            val tagEnd = cur.pos
            if (field == f.colorfulField && wt == 0) {
                if (cur.varint() == null) return false
                colorfulSeen++
                if (colorfulSeen > 1) return false
                out.write(buf, start, tagEnd - start)
                out.write(0)
            } else {
                if (!cur.skipPayload(wt)) return false
                out.write(buf, start, cur.pos - start)
            }
        }
        return cur.pos == to
    }

    fun rewrite(
        bytes: ByteArray,
        f: DanmakuWireFields,
        keepElem: BooleanArray,
        recolorElem: BooleanArray,
        keepSrc: BooleanArray,
    ): ByteArray? {
        if (!f.isValid()) return null
        if (bytes.size > MAX_REPLY_BYTES) return null
        val cur = Cursor(bytes)
        val out = ByteArrayOutput(bytes.size)
        var elemIndex = 0
        var srcIndex = 0
        val elemBody = ByteArrayOutput(64)
        while (cur.remaining() > 0) {
            val start = cur.pos
            val (field, wt) = cur.tag() ?: return null
            val isElem = field == f.elemsField && wt == 2
            val isSrc = field == f.colorfulSrcField && wt == 2
            if (isElem || isSrc) {
                val len = cur.delimited() ?: return null
                val payloadStart = cur.take(len) ?: return null
                if (isElem) {
                    if (elemIndex >= keepElem.size || elemIndex >= recolorElem.size) return null
                    val keep = keepElem[elemIndex]
                    val recolor = recolorElem[elemIndex]
                    elemIndex++
                    if (!keep) continue
                    if (recolor) {
                        elemBody.reset()
                        if (!rewriteElem(bytes, payloadStart, payloadStart + len, f, elemBody)) return null
                        putVarint(out, ((field.toLong() shl 3) or 2))
                        putVarint(out, elemBody.length.toLong())
                        elemBody.writeTo(out)
                    } else {
                        out.write(bytes, start, cur.pos - start)
                    }
                } else {
                    if (srcIndex >= keepSrc.size) return null
                    val keep = keepSrc[srcIndex]
                    srcIndex++
                    if (keep) out.write(bytes, start, cur.pos - start)
                }
            } else {
                if (!cur.skipPayload(wt)) return null
                out.write(bytes, start, cur.pos - start)
            }
        }
        if (elemIndex != keepElem.size || srcIndex != keepSrc.size || elemIndex != recolorElem.size) return null
        return out.toByteArray()
    }

    // ---------------- 探针辅助：安装期从真实宿主类的序列化字节反推字段号 ----------------

    /** 整段字节恰好是单个 varint 字段（值等于 [expected]）时返回其字段号。 */
    internal fun singleVarintField(bytes: ByteArray, expected: Long): Int? {
        val cur = Cursor(bytes)
        val (field, wt) = cur.tag() ?: return null
        if (wt != 0) return null
        val value = cur.varint() ?: return null
        if (cur.remaining() != 0 || value != expected) return null
        return field
    }

    /** 整段字节恰好是单个 wt2 字段且负载逐字节等于 [expected] 时返回其字段号。 */
    internal fun singleDelimitedField(bytes: ByteArray, expected: ByteArray): Int? {
        val cur = Cursor(bytes)
        val (field, wt) = cur.tag() ?: return null
        if (wt != 2) return null
        val len = cur.delimited() ?: return null
        val start = cur.take(len) ?: return null
        if (cur.remaining() != 0 || len != expected.size) return null
        for (i in expected.indices) {
            if (bytes[start + i] != expected[i]) return null
        }
        return field
    }

    /** 顶层全部 wt2 字段号中，负载逐字节等于 [expected] 的那些（用于定位 reply 的列表字段）。 */
    internal fun delimitedFieldsWithPayload(bytes: ByteArray, expected: ByteArray): IntArray {
        val cur = Cursor(bytes)
        val found = ArrayList<Int>(4)
        while (cur.remaining() > 0) {
            val tagStart = cur.pos
            val (field, wt) = cur.tag() ?: break
            if (wt == 2) {
                val len = cur.delimited()
                val start = if (len != null) cur.take(len) else null
                if (start != null && len == expected.size) {
                    var equal = true
                    for (i in expected.indices) {
                        if (bytes[start + i] != expected[i]) {
                            equal = false
                            break
                        }
                    }
                    if (equal) found.add(field)
                }
                if (start == null) break
            } else {
                if (!cur.skipPayload(wt)) break
            }
            if (cur.pos <= tagStart) break
        }
        return found.toIntArray()
    }
}
