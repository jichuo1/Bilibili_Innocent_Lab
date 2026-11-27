package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * wire-format 扫描的纯 Kotlin 实现：native 不可用时的回退层，也是安装期自检与
 * 单测的**差分基准**（oracle）。语义与 `danmaku-wire/src/lib.rs` 逐条对齐：
 * 同字段重复取最后一次、命名字段 wire type 不符按未知字段跳过、解析层级的
 * group(3/4)/非法 wire type 拒绝、改色时将所有 wt=0 彩字字段清零（确保 last-wins 解码见 0）、其余字节逐字节保留。
 * 两边任何不一致都会在安装期自检里暴露并停用 native。
 */
internal object DanmakuWire {

    /** 与 Rust 侧 MAX_REPLY_BYTES/MAX_ELEMS/MAX_SRCS 对齐。 */
    internal const val MAX_REPLY_BYTES = 16 * 1024 * 1024
    internal const val MAX_ELEMS = 65_536
    internal const val MAX_SRCS = 65_536

    private const val HEADER_INTS = 2
    private const val ELEM_STRIDE = 5
    private const val SRC_STRIDE = 2
    private const val FLAG_WEIGHT = 1
    private const val FLAG_COLORFUL = 2
    private const val FLAG_CONTENT = 4
    private const val SRC_FLAG_KIND = 1
    private val EMPTY_INTS = IntArray(0)

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

    /**
     * 扁平 `IntArray` 的零装箱轻量视图（布局与 Rust `flatten` 一致）：
     * 热路径直接读取原始 int，不分配 `List<ElemSignal>`、`List<Int?>` 或装箱 `Int`。
     */
    internal class FlatScan internal constructor(private val flat: IntArray) {
        val elemCount: Int get() = flat[0]
        val srcCount: Int get() = flat[1]

        fun hasWeight(index: Int): Boolean = (elemFlags(index) and FLAG_WEIGHT) != 0
        fun weight(index: Int): Int = flat[elemOffset(index)]

        fun hasColorful(index: Int): Boolean = (elemFlags(index) and FLAG_COLORFUL) != 0
        fun colorful(index: Int): Int = flat[elemOffset(index) + 1]

        fun hasContent(index: Int): Boolean = (elemFlags(index) and FLAG_CONTENT) != 0
        fun contentStart(index: Int): Int = flat[elemOffset(index) + 3]
        fun contentLen(index: Int): Int = flat[elemOffset(index) + 4]

        fun hasSrcKind(index: Int): Boolean = flat[srcOffset(index) + 1] != 0
        fun srcKind(index: Int): Int = flat[srcOffset(index)]

        internal fun toIntArray(): IntArray = flat.copyOf()

        fun toScan(): Scan {
            val eCount = elemCount
            val sCount = srcCount
            val elems = ArrayList<ElemSignal>(eCount)
            var i = HEADER_INTS
            repeat(eCount) {
                val flags = flat[i + 2]
                elems.add(
                    ElemSignal(
                        weight = if (flags and FLAG_WEIGHT != 0) flat[i] else null,
                        colorful = if (flags and FLAG_COLORFUL != 0) flat[i + 1] else null,
                        contentStart = if (flags and FLAG_CONTENT != 0) flat[i + 3] else -1,
                        contentLen = if (flags and FLAG_CONTENT != 0) flat[i + 4] else 0,
                        hasContent = flags and FLAG_CONTENT != 0,
                    )
                )
                i += ELEM_STRIDE
            }
            val srcs = ArrayList<Int?>(sCount)
            repeat(sCount) {
                srcs.add(if (flat[i + 1] != 0) flat[i] else null)
                i += SRC_STRIDE
            }
            return Scan(elems, srcs)
        }

        private fun elemOffset(index: Int): Int {
            if (index !in 0 until elemCount) throw IndexOutOfBoundsException("elem index=$index count=$elemCount")
            return HEADER_INTS + index * ELEM_STRIDE
        }

        private fun elemFlags(index: Int): Int = flat[elemOffset(index) + 2]

        private fun srcOffset(index: Int): Int {
            if (index !in 0 until srcCount) throw IndexOutOfBoundsException("src index=$index count=$srcCount")
            return HEADER_INTS + elemCount * ELEM_STRIDE + index * SRC_STRIDE
        }

        companion object {
            fun from(flat: IntArray): FlatScan? {
                if (flat.size < HEADER_INTS) return null
                val elemCount = flat[0]
                val srcCount = flat[1]
                if (elemCount !in 0..MAX_ELEMS || srcCount !in 0..MAX_SRCS) return null
                if (flat.size != HEADER_INTS + elemCount * ELEM_STRIDE + srcCount * SRC_STRIDE) return null
                return FlatScan(flat)
            }
        }
    }

    /** 拆平布局与 Rust `flatten` 完全一致（flags bit0/1/2 = weight/colorful/content 存在）。 */
    fun flatten(scan: Scan): IntArray {
        val out = IntArray(HEADER_INTS + scan.elems.size * ELEM_STRIDE + scan.srcKinds.size * SRC_STRIDE)
        out[0] = scan.elems.size
        out[1] = scan.srcKinds.size
        var i = HEADER_INTS
        for (e in scan.elems) {
            var flags = 0
            if (e.weight != null) flags = flags or FLAG_WEIGHT
            if (e.colorful != null) flags = flags or FLAG_COLORFUL
            if (e.hasContent) flags = flags or FLAG_CONTENT
            out[i] = e.weight ?: 0
            out[i + 1] = e.colorful ?: 0
            out[i + 2] = flags
            out[i + 3] = e.contentStart
            out[i + 4] = e.contentLen
            i += ELEM_STRIDE
        }
        for (s in scan.srcKinds) {
            out[i] = s ?: 0
            out[i + 1] = if (s != null) SRC_FLAG_KIND else 0
            i += SRC_STRIDE
        }
        return out
    }

    fun flatScanFrom(flat: IntArray): FlatScan? = FlatScan.from(flat)

    fun fromFlattened(flat: IntArray): Scan? = FlatScan.from(flat)?.toScan()

    private class Cursor(val buf: ByteArray, var pos: Int = 0, val limit: Int = buf.size) {
        var lastVarint: Long = 0L
        var lastField: Int = 0
        var lastWireType: Int = 0

        fun remaining() = limit - pos

        fun take(len: Int): Int {
            if (len < 0 || remaining() < len) return -1
            val start = pos
            pos += len
            return start
        }

        fun readVarint(): Boolean {
            var value = 0L
            var shift = 0
            var count = 0
            while (count < 10) {
                if (remaining() < 1) return false
                val byte = buf[pos++].toLong() and 0xff
                if (shift < 64) {
                    value = value or ((byte and 0x7f) shl shift)
                }
                if (byte and 0x80 == 0L) {
                    lastVarint = value
                    return true
                }
                shift += 7
                count++
            }
            return false
        }

        fun readTag(): Boolean {
            if (!readVarint()) return false
            val raw = lastVarint
            val field = (raw ushr 3).toInt()
            val wt = (raw and 7).toInt()
            if (field == 0 || raw ushr 32 != 0L) return false
            lastField = field
            lastWireType = wt
            return true
        }

        /** 长度先比后转：有符号 Long 负值与 32 位 JVM Long→Int 回绕必须先拒绝。 */
        fun readDelimitedLength(): Int {
            if (!readVarint()) return -1
            val len = lastVarint
            if (len < 0L || len > remaining()) return -1
            return len.toInt()
        }

        fun skipPayload(wt: Int): Boolean = when (wt) {
            0 -> readVarint()
            1 -> take(8) >= 0
            2 -> {
                val len = readDelimitedLength()
                len >= 0 && take(len) >= 0
            }
            5 -> take(4) >= 0
            else -> false
        }
    }

    private fun scanElemInto(
        buf: ByteArray,
        from: Int,
        to: Int,
        f: DanmakuWireFields,
        out: IntArray,
        outBase: Int,
    ): Boolean {
        val cur = Cursor(buf, from, to)
        var weight = 0
        var colorful = 0
        var flags = 0
        var contentStart = 0
        var contentLen = 0
        while (cur.pos < to) {
            if (!cur.readTag()) return false
            val field = cur.lastField
            val wt = cur.lastWireType
            when {
                field == f.weightField && wt == 0 -> {
                    if (!cur.readVarint()) return false
                    weight = cur.lastVarint.toInt()
                    flags = flags or FLAG_WEIGHT
                }
                field == f.colorfulField && wt == 0 -> {
                    if (!cur.readVarint()) return false
                    colorful = cur.lastVarint.toInt()
                    flags = flags or FLAG_COLORFUL
                }
                field == f.contentField && wt == 2 -> {
                    val len = cur.readDelimitedLength()
                    if (len < 0) return false
                    val start = cur.take(len)
                    if (start < 0) return false
                    flags = flags or FLAG_CONTENT
                    contentStart = start
                    contentLen = len
                }
                else -> if (!cur.skipPayload(wt)) return false
            }
        }
        if (cur.pos != to) return false
        out[outBase] = weight
        out[outBase + 1] = colorful
        out[outBase + 2] = flags
        out[outBase + 3] = contentStart
        out[outBase + 4] = contentLen
        return true
    }

    /** 写一条 src 信号：无 type 字段是合法状态（flags=0），不能与拒绝混叠。 */
    private fun scanSrcInto(
        buf: ByteArray,
        from: Int,
        to: Int,
        f: DanmakuWireFields,
        out: IntArray,
        outBase: Int,
    ): Boolean {
        val cur = Cursor(buf, from, to)
        var kind = 0
        var hasKind = false
        while (cur.pos < to) {
            if (!cur.readTag()) return false
            val field = cur.lastField
            val wt = cur.lastWireType
            if (field == f.colorfulTypeField && wt == 0) {
                if (!cur.readVarint()) return false
                kind = cur.lastVarint.toInt()
                hasKind = true
            } else if (!cur.skipPayload(wt)) {
                return false
            }
        }
        if (cur.pos != to) return false
        out[outBase] = kind
        out[outBase + 1] = if (hasKind) SRC_FLAG_KIND else 0
        return true
    }

    private fun growIntArray(buf: IntArray, needed: Int, maxLen: Int, initial: Int): IntArray {
        if (needed <= buf.size) return buf
        val nextCap = if (buf.isEmpty()) initial else minOf(maxLen, maxOf(buf.size * 2, needed))
        return buf.copyOf(nextCap)
    }

    fun scanFlat(bytes: ByteArray, f: DanmakuWireFields): FlatScan? {
        if (!f.isValid()) return null
        if (bytes.size > MAX_REPLY_BYTES) return null
        val cur = Cursor(bytes)
        var elemBuf = EMPTY_INTS
        var elemCount = 0
        var srcBuf = EMPTY_INTS
        var srcCount = 0
        while (cur.remaining() > 0) {
            if (!cur.readTag()) return null
            val field = cur.lastField
            val wt = cur.lastWireType
            val isElem = field == f.elemsField && wt == 2
            val isSrc = field == f.colorfulSrcField && wt == 2
            if (isElem || isSrc) {
                val len = cur.readDelimitedLength()
                if (len < 0) return null
                val start = cur.take(len)
                if (start < 0) return null
                if (isElem) {
                    if (elemCount >= MAX_ELEMS) return null
                    val base = elemCount * ELEM_STRIDE
                    elemBuf = growIntArray(elemBuf, base + ELEM_STRIDE, MAX_ELEMS * ELEM_STRIDE, 16 * ELEM_STRIDE)
                    if (!scanElemInto(bytes, start, start + len, f, elemBuf, base)) return null
                    elemCount++
                } else {
                    if (srcCount >= MAX_SRCS) return null
                    val base = srcCount * SRC_STRIDE
                    srcBuf = growIntArray(srcBuf, base + SRC_STRIDE, MAX_SRCS * SRC_STRIDE, 4 * SRC_STRIDE)
                    if (!scanSrcInto(bytes, start, start + len, f, srcBuf, base)) return null
                    srcCount++
                }
            } else if (!cur.skipPayload(wt)) {
                return null
            }
        }
        val elemsInts = elemCount * ELEM_STRIDE
        val srcsInts = srcCount * SRC_STRIDE
        val flat = IntArray(HEADER_INTS + elemsInts + srcsInts)
        flat[0] = elemCount
        flat[1] = srcCount
        if (elemsInts > 0) System.arraycopy(elemBuf, 0, flat, HEADER_INTS, elemsInts)
        if (srcsInts > 0) System.arraycopy(srcBuf, 0, flat, HEADER_INTS + elemsInts, srcsInts)
        return FlatScan(flat)
    }

    fun scan(bytes: ByteArray, f: DanmakuWireFields): Scan? = scanFlat(bytes, f)?.toScan()

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

    /** 改色：将所有 wt=0 彩字字段的值字节清零（tag 原样保留），确保按 Protobuf last-wins 及任何读口径解码均见 0。 */
    private fun rewriteElem(
        buf: ByteArray, from: Int, to: Int, f: DanmakuWireFields, out: ByteArrayOutput,
    ): Boolean {
        val cur = Cursor(buf, from, to)
        while (cur.pos < to) {
            val start = cur.pos
            if (!cur.readTag()) return false
            val field = cur.lastField
            val wt = cur.lastWireType
            val tagEnd = cur.pos
            if (field == f.colorfulField && wt == 0) {
                if (!cur.readVarint()) return false
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
        if (keepElem.size != recolorElem.size || keepElem.size > MAX_ELEMS || keepSrc.size > MAX_SRCS) return null
        val cur = Cursor(bytes)
        val out = ByteArrayOutput(bytes.size)
        var elemIndex = 0
        var srcIndex = 0
        val elemBody = ByteArrayOutput(64)
        while (cur.remaining() > 0) {
            val start = cur.pos
            if (!cur.readTag()) return null
            val field = cur.lastField
            val wt = cur.lastWireType
            val isElem = field == f.elemsField && wt == 2
            val isSrc = field == f.colorfulSrcField && wt == 2
            if (isElem || isSrc) {
                val len = cur.readDelimitedLength()
                if (len < 0) return null
                val payloadStart = cur.take(len)
                if (payloadStart < 0) return null
                if (isElem) {
                    if (elemIndex >= keepElem.size) return null
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
        if (elemIndex != keepElem.size || srcIndex != keepSrc.size) return null
        return out.toByteArray()
    }

    // ---------------- 探针辅助：安装期从真实宿主类的序列化字节反推字段号 ----------------

    /** 整段字节恰好是单个 varint 字段（值等于 [expected]）时返回其字段号。 */
    internal fun singleVarintField(bytes: ByteArray, expected: Long): Int? {
        val cur = Cursor(bytes)
        if (!cur.readTag()) return null
        if (cur.lastWireType != 0) return null
        if (!cur.readVarint()) return null
        if (cur.remaining() != 0 || cur.lastVarint != expected) return null
        return cur.lastField
    }

    /** 整段字节恰好是单个 wt2 字段且负载逐字节等于 [expected] 时返回其字段号。 */
    internal fun singleDelimitedField(bytes: ByteArray, expected: ByteArray): Int? {
        val cur = Cursor(bytes)
        if (!cur.readTag()) return null
        if (cur.lastWireType != 2) return null
        val len = cur.readDelimitedLength()
        if (len < 0) return null
        val start = cur.take(len)
        if (start < 0 || cur.remaining() != 0 || len != expected.size) return null
        for (i in expected.indices) {
            if (bytes[start + i] != expected[i]) return null
        }
        return cur.lastField
    }

    /** 顶层全部 wt2 字段号中，负载逐字节等于 [expected] 的那些（用于定位 reply 的列表字段）。 */
    internal fun delimitedFieldsWithPayload(bytes: ByteArray, expected: ByteArray): IntArray {
        val cur = Cursor(bytes)
        val found = ArrayList<Int>(4)
        while (cur.remaining() > 0) {
            val tagStart = cur.pos
            if (!cur.readTag()) break
            val field = cur.lastField
            val wt = cur.lastWireType
            if (wt == 2) {
                val len = cur.readDelimitedLength()
                val start = if (len >= 0) cur.take(len) else -1
                if (start >= 0 && len == expected.size) {
                    var equal = true
                    for (i in expected.indices) {
                        if (bytes[start + i] != expected[i]) {
                            equal = false
                            break
                        }
                    }
                    if (equal) found.add(field)
                }
                if (start < 0) break
            } else {
                if (!cur.skipPayload(wt)) break
            }
            if (cur.pos <= tagStart) break
        }
        return found.toIntArray()
    }
}
