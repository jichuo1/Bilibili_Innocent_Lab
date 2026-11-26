package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * 带**真实 protobuf wire 编解码**的弹幕 fixture（测试专用，字段号刻意与真实协议一致）：
 * reply: elems=1, colorfulSrc=4, flags=15（透传用未知字段）；elem: weight=8, content=10,
 * colorful=11；colorful: type=2。parseFrom/toByteArray 全部按线格式手工实现，
 * 供 [DanmakuWirePlan] 探针/自检与 [DanmakuWire] 引擎做端到端验证。
 */
internal class WireElem(val weight: Int? = null, val colorful: Int? = null, val content: String? = null) {
    fun getWeight() = weight ?: 0
    fun getColorfulValue() = colorful ?: 0

    fun toByteArray(): ByteArray {
        val out = DanmakuWire.ByteArrayOutput()
        weight?.let { writeVarintField(out, 8, it.toLong()) }
        content?.let { writeBytesField(out, 10, it.toByteArray(Charsets.UTF_8)) }
        colorful?.let { writeVarintField(out, 11, it.toLong()) }
        return out.toByteArray()
    }

    class Builder internal constructor(
        private var weight: Int?, private var colorful: Int?, private var content: String?,
    ) {
        constructor(original: WireElem) : this(original.weight, original.colorful, original.content)
        fun setWeight(value: Int) = apply { weight = value }
        fun setColorfulValue(value: Int) = apply { colorful = value }
        fun setContent(value: String) = apply { content = value }
        fun build() = WireElem(weight, colorful, content)
    }

    companion object {
        @JvmStatic fun newBuilder(original: WireElem) = Builder(original)
        @JvmStatic fun getDefaultInstance() = DEFAULT
        private val DEFAULT = WireElem()
    }
}

internal class WireColorful(val type: Int? = null) {
    fun getTypeValue() = type ?: 0

    fun toByteArray(): ByteArray {
        val out = DanmakuWire.ByteArrayOutput()
        type?.let { writeVarintField(out, 2, it.toLong()) }
        return out.toByteArray()
    }

    class Builder internal constructor(private var type: Int?) {
        constructor(original: WireColorful) : this(original.type)
        fun setTypeValue(value: Int) = apply { type = value }
        fun build() = WireColorful(type)
    }

    companion object {
        @JvmStatic fun newBuilder(original: WireColorful) = Builder(original)
        @JvmStatic fun getDefaultInstance() = DEFAULT
        private val DEFAULT = WireColorful()
    }
}

internal class WireReply(
    val elems: List<WireElem> = emptyList(),
    val colorfulSrc: List<WireColorful> = emptyList(),
    val flags: Long? = null,
) {
    fun getElemsList() = elems
    fun getColorfulSrcList() = colorfulSrc
    fun getFlags() = flags ?: 0L

    fun toByteArray(): ByteArray {
        val out = DanmakuWire.ByteArrayOutput()
        for (elem in elems) writeBytesField(out, 1, elem.toByteArray())
        for (src in colorfulSrc) writeBytesField(out, 4, src.toByteArray())
        flags?.let { writeVarintField(out, 15, it) }
        return out.toByteArray()
    }

    class Builder internal constructor(
        private var elems: List<WireElem>,
        private var colorfulSrc: List<WireColorful>,
        private val flags: Long?,
    ) {
        constructor(original: WireReply) : this(original.elems, original.colorfulSrc, original.flags)
        fun clearElems() = apply { elems = emptyList() }
        fun addAllElems(values: Iterable<WireElem>) = apply { elems = values.toList() }
        fun clearColorfulSrc() = apply { colorfulSrc = emptyList() }
        fun addAllColorfulSrc(values: Iterable<WireColorful>) = apply { colorfulSrc = values.toList() }
        fun build() = WireReply(elems, colorfulSrc, flags)
    }

    companion object {
        @JvmStatic fun newBuilder(original: WireReply) = Builder(original)
        @JvmStatic fun getDefaultInstance() = DEFAULT

        /** 解码只覆盖本 fixture 编码过的形态（varint / length-delimited），坏输入抛异常。 */
        @JvmStatic
        fun parseFrom(bytes: ByteArray): WireReply {
            val elems = ArrayList<WireElem>()
            val srcs = ArrayList<WireColorful>()
            var flags: Long? = null
            var pos = 0
            while (pos < bytes.size) {
                var v = 0L
                var shift = 0
                var tag = 0L
                while (true) {
                    val b = bytes[pos++].toLong() and 0xff
                    v = v or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0L) break
                    shift += 7
                }
                tag = v
                val field = (tag ushr 3).toInt()
                when (val wt = (tag and 7).toInt()) {
                    0 -> {
                        var value = 0L
                        var s2 = 0
                        while (true) {
                            val b = bytes[pos++].toLong() and 0xff
                            value = value or ((b and 0x7f) shl s2)
                            if (b and 0x80 == 0L) break
                            s2 += 7
                        }
                        if (field == 15) flags = value
                    }
                    2 -> {
                        var len = 0
                        var s3 = 0
                        while (true) {
                            val b = bytes[pos++].toLong() and 0xff
                            len = len or (((b and 0x7f) shl s3).toInt())
                            if (b and 0x80 == 0L) break
                            s3 += 7
                        }
                        val payload = bytes.copyOfRange(pos, pos + len)
                        pos += len
                        when (field) {
                            1 -> elems.add(decodeElem(payload))
                            4 -> srcs.add(decodeColorful(payload))
                        }
                    }
                    else -> error("unsupported wire type $wt in fixture")
                }
            }
            return WireReply(elems, srcs, flags)
        }

        private fun decodeElem(payload: ByteArray): WireElem {
            var weight: Int? = null
            var colorful: Int? = null
            var content: String? = null
            var pos = 0
            while (pos < payload.size) {
                var v = 0L
                var shift = 0
                while (true) {
                    val b = payload[pos++].toLong() and 0xff
                    v = v or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0L) break
                    shift += 7
                }
                val field = (v ushr 3).toInt()
                when (val wt = (v and 7).toInt()) {
                    0 -> {
                        var value = 0L
                        var s2 = 0
                        while (true) {
                            val b = payload[pos++].toLong() and 0xff
                            value = value or ((b and 0x7f) shl s2)
                            if (b and 0x80 == 0L) break
                            s2 += 7
                        }
                        when (field) {
                            8 -> weight = value.toInt()
                            11 -> colorful = value.toInt()
                        }
                    }
                    2 -> {
                        var len = 0
                        var s3 = 0
                        while (true) {
                            val b = payload[pos++].toLong() and 0xff
                            len = len or (((b and 0x7f) shl s3).toInt())
                            if (b and 0x80 == 0L) break
                            s3 += 7
                        }
                        if (field == 10) content = String(payload, pos, len, Charsets.UTF_8)
                        pos += len
                    }
                    else -> error("unsupported wire type in fixture elem")
                }
            }
            return WireElem(weight, colorful, content)
        }

        private fun decodeColorful(payload: ByteArray): WireColorful {
            var type: Int? = null
            var pos = 0
            while (pos < payload.size) {
                var v = 0L
                var shift = 0
                while (true) {
                    val b = payload[pos++].toLong() and 0xff
                    v = v or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0L) break
                    shift += 7
                }
                val field = (v ushr 3).toInt()
                when (val wt = (v and 7).toInt()) {
                    0 -> {
                        var value = 0L
                        var s2 = 0
                        while (true) {
                            val b = payload[pos++].toLong() and 0xff
                            value = value or ((b and 0x7f) shl s2)
                            if (b and 0x80 == 0L) break
                            s2 += 7
                        }
                        if (field == 2) type = value.toInt()
                    }
                    2 -> {
                        var len = 0
                        var s3 = 0
                        while (true) {
                            val b = payload[pos++].toLong() and 0xff
                            len = len or (((b and 0x7f) shl s3).toInt())
                            if (b and 0x80 == 0L) break
                            s3 += 7
                        }
                        pos += len
                    }
                    else -> error("unsupported wire type $wt in fixture colorful")
                }
            }
            return WireColorful(type)
        }

        private val DEFAULT = WireReply()
    }
}

internal fun writeVarint(out: DanmakuWire.ByteArrayOutput, value: Long) {
    var v = value
    while (true) {
        var b = (v and 0x7f).toInt()
        v = v ushr 7
        if (v != 0L) b = b or 0x80
        out.write(b)
        if (v == 0L) return
    }
}

internal fun writeVarintField(out: DanmakuWire.ByteArrayOutput, field: Int, value: Long) {
    writeVarint(out, (field.toLong() shl 3) or 0)
    writeVarint(out, value)
}

internal fun writeBytesField(out: DanmakuWire.ByteArrayOutput, field: Int, payload: ByteArray) {
    writeVarint(out, (field.toLong() shl 3) or 2)
    writeVarint(out, payload.size.toLong())
    out.write(payload, 0, payload.size)
}
