package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.wire

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 单个顶层或子消息 protobuf wire 字段的零拷贝切片视图。
 *
 * `[rawStart, rawEnd)` 覆盖含 tag、length（如有）或 group end tag 在内的完整原始字节，
 * `[payloadStart, payloadEnd)` 覆盖负载字节；当 [wireType] 为 [ProtobufWireCodec.WIRE_TYPE_VARINT]
 * 时 [varintValue] 保存解码后的 64 位整数，为 [ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED]
 * 时保存负载长度。
 */
internal data class WireFieldSlice(
    val fieldNumber: Int,
    val wireType: Int,
    val rawStart: Int,
    val rawEnd: Int,
    val payloadStart: Int,
    val payloadEnd: Int,
    val varintValue: Long = 0L
) {
    val rawLength: Int get() = rawEnd - rawStart
    val payloadLength: Int get() = payloadEnd - payloadStart
}

internal data class VarintRead(
    val value: Long,
    val nextOffset: Int
)

/**
 * Copy-on-change wire 重写动作。
 */
internal sealed interface WireRewriteAction {
    /** 原字节片段保持不变（所有字段均 Keep 时直接返回原 ByteArray 引用）。 */
    data object Keep : WireRewriteAction

    /** 删除该字段。 */
    data object Drop : WireRewriteAction

    /** 用完整的已编码原始 wire 字节（含 tag）替换当前字段。 */
    class ReplaceRaw(val newRawBytes: ByteArray) : WireRewriteAction

    /** 用新的 length-delimited 负载替换当前字段，codec 自动写入 tag 与长度 varint。 */
    class ReplaceLengthDelimited(
        val fieldNumber: Int,
        val newPayloadBytes: ByteArray
    ) : WireRewriteAction
}

/**
 * 零依赖（无 `com.bapis.*` / protobuf runtime）纯 Kotlin Protobuf Wire 编解码器。
 *
 * 特性：
 * - 严格校验 varint 截断/超长（>10 字节或第 10 字节溢出 64 位）、非法 tag、负/越界长度与未配对 group；
 * - 支持有界嵌套 group（depth <= [MAX_GROUP_DEPTH]）及未知 wire 字段逐字节无损保留；
 * - Copy-on-change 重写：未发生任何改动时零拷贝返回原始 `ByteArray` 引用（`===`）。
 */
internal object ProtobufWireCodec {

    const val WIRE_TYPE_VARINT = 0
    const val WIRE_TYPE_FIXED64 = 1
    const val WIRE_TYPE_LENGTH_DELIMITED = 2
    const val WIRE_TYPE_START_GROUP = 3
    const val WIRE_TYPE_END_GROUP = 4
    const val WIRE_TYPE_FIXED32 = 5

    const val MAX_FIELD_NUMBER = 536_870_911 // (1 shl 29) - 1
    const val MAX_GROUP_DEPTH = 32
    const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024

    fun makeTag(fieldNumber: Int, wireType: Int): Long {
        require(fieldNumber in 1..MAX_FIELD_NUMBER) { "Invalid fieldNumber: $fieldNumber" }
        require(wireType in WIRE_TYPE_VARINT..WIRE_TYPE_FIXED32) { "Invalid wireType: $wireType" }
        return (fieldNumber.toLong() shl 3) or wireType.toLong()
    }

    fun varintSize(value: Long): Int {
        var v = value
        var count = 1
        while (v and 0x7FL.inv() != 0L) {
            v = v ushr 7
            count++
        }
        return count
    }

    fun writeVarint(value: Long, out: ByteArray, offset: Int): Int {
        var v = value
        var pos = offset
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out[pos++] = v.toByte()
                return pos
            }
            out[pos++] = ((v.toInt() and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
    }

    fun writeVarint(value: Long): ByteArray {
        val out = ByteArray(varintSize(value))
        writeVarint(value, out, 0)
        return out
    }

    /**
     * 从 `[offset, limit)` 安全读取最多 10 字节的 64 位 varint。
     * 截断、超过 10 字节、或第 10 字节高 6 位非零（>64 位溢出）均返回 `null`。
     */
    fun readVarint(bytes: ByteArray, offset: Int, limit: Int = bytes.size): VarintRead? {
        if (offset < 0 || limit > bytes.size || offset >= limit) return null
        var value = 0L
        var shift = 0
        var pos = offset
        var count = 0
        while (count < 10) {
            if (pos >= limit) return null
            val b = bytes[pos++].toInt() and 0xFF
            if (count == 9 && b > 1) {
                // 第 10 字节只允许承载 bit 63（0x00 或 0x01），>1 说明超过 64 位或持续位未结束。
                return null
            }
            value = value or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) {
                return VarintRead(value, pos)
            }
            shift += 7
            count++
        }
        return null
    }

    fun readFixed32Le(bytes: ByteArray, offset: Int, limit: Int = bytes.size): Int? {
        if (offset < 0 || limit > bytes.size || limit - offset < 4) return null
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readFixed64Le(bytes: ByteArray, offset: Int, limit: Int = bytes.size): Long? {
        if (offset < 0 || limit > bytes.size || limit - offset < 8) return null
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    /**
     * 严格校验并解码 UTF-8 切片；非法编码序列返回 `null`，绝不用 U+FFFD 掩盖畸形输入。
     */
    fun decodeUtf8OrNull(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): String? {
        if (start < 0 || end < start || end > bytes.size) return null
        val len = end - start
        if (len == 0) return ""
        var allAscii = true
        for (i in start until end) {
            if (bytes[i] < 0) {
                allAscii = false
                break
            }
        }
        if (allAscii) {
            return String(bytes, start, len, StandardCharsets.US_ASCII)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, len))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * 解码 packed varint 序列；任一元素截断/溢出则返回 `null`。
     */
    fun readPackedVarints(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): LongArray? {
        if (start < 0 || end < start || end > bytes.size) return null
        if (start == end) return LongArray(0)
        var pos = start
        var buf = LongArray(minOf(16, end - start))
        var count = 0
        while (pos < end) {
            val read = readVarint(bytes, pos, end) ?: return null
            if (count == buf.size) {
                buf = buf.copyOf(buf.size * 2)
            }
            buf[count++] = read.value
            pos = read.nextOffset
        }
        return if (count == buf.size) buf else buf.copyOf(count)
    }

    /**
     * 扫描指定区间的顶层字段，返回切片列表；遇畸形/越界/深层嵌套溢出返回 `null`。
     */
    fun scanTopLevel(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = if (offset in 0..bytes.size) bytes.size - offset else -1,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): List<WireFieldSlice>? {
        if (!isValidBounds(bytes, offset, length, maxBytes)) return null
        if (length == 0) return emptyList()
        val slices = ArrayList<WireFieldSlice>()
        val ok = visitFieldsInternal(bytes, offset, offset + length) { slice ->
            slices.add(slice)
            true
        }
        return if (ok) slices else null
    }

    /**
     * 流式扫描指定区间的所有顶层字段，不分配 `List<WireFieldSlice>`。
     * 若 [visitor] 返回 `false` 可提前中止扫描（不校验后续未扫描字节）；扫描完整且合法返回 `true`。
     */
    inline fun forEachField(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = if (offset in 0..bytes.size) bytes.size - offset else -1,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        crossinline visitor: (WireFieldSlice) -> Boolean
    ): Boolean {
        if (!isValidBounds(bytes, offset, length, maxBytes)) return false
        if (length == 0) return true
        return visitFieldsInternal(bytes, offset, offset + length) { slice -> visitor(slice) }
    }

    @PublishedApi
    internal fun isValidBounds(bytes: ByteArray, offset: Int, length: Int, maxBytes: Int): Boolean =
        offset >= 0 && length >= 0 && maxBytes >= 0 && length <= maxBytes && offset <= bytes.size - length

    @PublishedApi
    internal inline fun visitFieldsInternal(
        bytes: ByteArray,
        start: Int,
        limit: Int,
        visitor: (WireFieldSlice) -> Boolean
    ): Boolean {
        var pos = start
        while (pos < limit) {
            val rawStart = pos
            val tagRead = readVarint(bytes, pos, limit) ?: return false
            val rawTag = tagRead.value
            pos = tagRead.nextOffset
            if (rawTag <= 0L || (rawTag ushr 32) != 0L) return false
            val fieldNumber = (rawTag ushr 3).toInt()
            val wireType = (rawTag and 7L).toInt()
            if (fieldNumber <= 0) return false
            val slice = when (wireType) {
                WIRE_TYPE_VARINT -> {
                    val valueRead = readVarint(bytes, pos, limit) ?: return false
                    pos = valueRead.nextOffset
                    WireFieldSlice(fieldNumber, wireType, rawStart, pos, tagRead.nextOffset, pos, valueRead.value)
                }
                WIRE_TYPE_FIXED64 -> {
                    if (limit - pos < 8) return false
                    val payloadStart = pos
                    pos += 8
                    WireFieldSlice(fieldNumber, wireType, rawStart, pos, payloadStart, pos)
                }
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val lenRead = readVarint(bytes, pos, limit) ?: return false
                    val lenLong = lenRead.value
                    val payloadStart = lenRead.nextOffset
                    if (lenLong < 0L || lenLong > (limit - payloadStart).toLong()) return false
                    val len = lenLong.toInt()
                    pos = payloadStart + len
                    WireFieldSlice(fieldNumber, wireType, rawStart, pos, payloadStart, pos, lenLong)
                }
                WIRE_TYPE_START_GROUP -> {
                    val payloadStart = pos
                    val groupEnd = skipGroupBody(bytes, pos, limit, fieldNumber) ?: return false
                    val payloadEnd = groupEnd.payloadEnd
                    pos = groupEnd.rawEnd
                    WireFieldSlice(fieldNumber, wireType, rawStart, pos, payloadStart, payloadEnd)
                }
                WIRE_TYPE_FIXED32 -> {
                    if (limit - pos < 4) return false
                    val payloadStart = pos
                    pos += 4
                    WireFieldSlice(fieldNumber, wireType, rawStart, pos, payloadStart, pos)
                }
                else -> return false // 含未配对 END_GROUP(4) 与保留类型 6/7
            }
            if (!visitor(slice)) return true
        }
        return pos == limit
    }

    internal data class GroupBounds(val payloadEnd: Int, val rawEnd: Int)

    /**
     * 迭代跳过嵌套 group 体（有界深度 <= [MAX_GROUP_DEPTH]），防止深层畸形包栈溢出。
     */
    internal fun skipGroupBody(
        bytes: ByteArray,
        startPos: Int,
        limit: Int,
        initialFieldNumber: Int
    ): GroupBounds? {
        val groupStack = IntArray(MAX_GROUP_DEPTH)
        var depth = 1
        groupStack[0] = initialFieldNumber
        var pos = startPos
        while (pos < limit) {
            val tagStart = pos
            val tagRead = readVarint(bytes, pos, limit) ?: return null
            val rawTag = tagRead.value
            pos = tagRead.nextOffset
            if (rawTag <= 0L || (rawTag ushr 32) != 0L) return null
            val fieldNumber = (rawTag ushr 3).toInt()
            val wireType = (rawTag and 7L).toInt()
            if (fieldNumber <= 0) return null
            when (wireType) {
                WIRE_TYPE_VARINT -> {
                    val v = readVarint(bytes, pos, limit) ?: return null
                    pos = v.nextOffset
                }
                WIRE_TYPE_FIXED64 -> {
                    if (limit - pos < 8) return null
                    pos += 8
                }
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val lenRead = readVarint(bytes, pos, limit) ?: return null
                    val lenLong = lenRead.value
                    pos = lenRead.nextOffset
                    if (lenLong < 0L || lenLong > (limit - pos).toLong()) return null
                    pos += lenLong.toInt()
                }
                WIRE_TYPE_START_GROUP -> {
                    if (depth >= MAX_GROUP_DEPTH) return null
                    groupStack[depth++] = fieldNumber
                }
                WIRE_TYPE_END_GROUP -> {
                    if (depth <= 0 || groupStack[depth - 1] != fieldNumber) return null
                    depth--
                    if (depth == 0) {
                        return GroupBounds(payloadEnd = tagStart, rawEnd = pos)
                    }
                }
                WIRE_TYPE_FIXED32 -> {
                    if (limit - pos < 4) return null
                    pos += 4
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * Copy-on-change 重写整个 protobuf 字节数组。
     *
     * - 所有字段都返回 [WireRewriteAction.Keep] 时，**直接返回原始 [bytes] 引用 (`===`)**，零内存分配；
     * - 遇到畸形或超限输入时安全原样返回 [bytes]（不破坏非预期载荷）；如需要显式失败信号请使用 [rewriteFieldsOrNull]；
     * - 未知字段默认逐字节保留。
     */
    fun rewriteFields(
        bytes: ByteArray,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        transform: (WireFieldSlice) -> WireRewriteAction
    ): ByteArray = rewriteFieldsOrNull(bytes, maxBytes, transform) ?: bytes

    /**
     * 语义同 [rewriteFields]，但在输入格式非法或越界时返回 `null`。
     * 当所有字段均为 [WireRewriteAction.Keep] 时，依然返回原始 [bytes] 引用 (`===`)。
     */
    fun rewriteFieldsOrNull(
        bytes: ByteArray,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        transform: (WireFieldSlice) -> WireRewriteAction
    ): ByteArray? {
        if (bytes.size > maxBytes) return null
        if (bytes.isEmpty()) return bytes
        val slices = scanTopLevel(bytes, 0, bytes.size, maxBytes) ?: return null
        val out = rewriteSlicesToBufOrNull(bytes, 0, bytes.size, slices, transform) ?: return bytes
        return out.toByteArray()
    }

    /**
     * 对子区间 `[offset, offset + length)` 执行 copy-on-change 重写。
     *
     * 若未发生任何变更返回 `null`（便于外层直接返回 [WireRewriteAction.Keep] 而免去子消息切片拷贝），
     * 若发生变更则返回重写后的紧凑新 `ByteArray`，若格式非法则回调 `onMalformed`（默认返回 `null`）。
     */
    fun rewriteSubMessageOrNullIfKeep(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        onMalformed: () -> ByteArray? = { null },
        transform: (WireFieldSlice) -> WireRewriteAction
    ): ByteArray? {
        if (!isValidBounds(bytes, offset, length, maxBytes)) return onMalformed()
        if (length == 0) return null
        val slices = scanTopLevel(bytes, offset, length, maxBytes) ?: return onMalformed()
        return rewriteSlicesToBufOrNull(bytes, offset, length, slices, transform)?.toByteArray()
    }

    private fun rewriteSlicesToBufOrNull(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        slices: List<WireFieldSlice>,
        transform: (WireFieldSlice) -> WireRewriteAction
    ): ByteBuf? {
        var out: ByteBuf? = null
        for (slice in slices) {
            when (val action = transform(slice)) {
                WireRewriteAction.Keep -> {
                    out?.write(bytes, slice.rawStart, slice.rawLength)
                }
                WireRewriteAction.Drop -> {
                    if (out == null) {
                        out = ByteBuf(length).apply {
                            write(bytes, offset, slice.rawStart - offset)
                        }
                    }
                }
                is WireRewriteAction.ReplaceRaw -> {
                    if (out == null) {
                        out = ByteBuf(length).apply {
                            write(bytes, offset, slice.rawStart - offset)
                        }
                    }
                    out.write(action.newRawBytes, 0, action.newRawBytes.size)
                }
                is WireRewriteAction.ReplaceLengthDelimited -> {
                    require(action.fieldNumber in 1..MAX_FIELD_NUMBER) {
                        "Invalid fieldNumber: ${action.fieldNumber}"
                    }
                    if (out == null) {
                        out = ByteBuf(length).apply {
                            write(bytes, offset, slice.rawStart - offset)
                        }
                    }
                    out.writeVarint(makeTag(action.fieldNumber, WIRE_TYPE_LENGTH_DELIMITED))
                    out.writeVarint(action.newPayloadBytes.size.toLong())
                    out.write(action.newPayloadBytes, 0, action.newPayloadBytes.size)
                }
            }
        }
        return out
    }

    internal class ByteBuf(initialCapacity: Int = 64) {
        private var buf = ByteArray(maxOf(16, initialCapacity))
        private var size = 0

        fun write(src: ByteArray, srcPos: Int, len: Int) {
            if (len <= 0) return
            ensure(len)
            System.arraycopy(src, srcPos, buf, size, len)
            size += len
        }

        fun writeVarint(value: Long) {
            val needed = varintSize(value)
            ensure(needed)
            size = writeVarint(value, buf, size)
        }

        fun writeLengthDelimited(fieldNumber: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
            writeVarint(makeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED))
            writeVarint(length.toLong())
            write(payload, offset, length)
        }

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeVarint(makeTag(fieldNumber, WIRE_TYPE_VARINT))
            writeVarint(value)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(extra: Int) {
            val required = size + extra
            if (required > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, required))
            }
        }
    }
}
