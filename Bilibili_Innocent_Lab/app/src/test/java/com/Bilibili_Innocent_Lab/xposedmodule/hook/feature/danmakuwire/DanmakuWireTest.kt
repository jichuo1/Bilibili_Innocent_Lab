package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** wire 引擎（JVM 基准）金样本：向量与 Rust `lib.rs` 的同名测试一一对应，钉死双侧语义一致。 */
class DanmakuWireTest {
    private val fields = DanmakuWireFields(
        elemsField = 1, weightField = 8, colorfulField = 11, contentField = 10,
        colorfulSrcField = 4, colorfulTypeField = 2,
    )

    private fun elem(weight: Int?, colorful: Int?, content: String?) =
        WireElem(weight, colorful, content).toByteArray().let { payload ->
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, 1, payload) }.toByteArray()
        }

    private fun src(type: Int?) =
        WireColorful(type).toByteArray().let { payload ->
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, 4, payload) }.toByteArray()
        }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = DanmakuWire.ByteArrayOutput()
        for (p in parts) out.write(p, 0, p.size)
        return out.toByteArray()
    }

    private fun unknownTop(): ByteArray =
        DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, 99, byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        }.toByteArray()

    @Test
    fun scanReadsMixedElementsAndSources() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "甲"),
            elem(null, 60001, null),
            src(60001),
            src(null),
        )
        val scan = DanmakuWire.scan(reply, fields)!!
        assertEquals(2, scan.elems.size)
        assertEquals(7, scan.elems[0].weight)
        assertEquals(null, scan.elems[0].colorful)
        assertTrue(scan.elems[0].hasContent)
        assertEquals("甲", String(reply, scan.elems[0].contentStart, scan.elems[0].contentLen, Charsets.UTF_8))
        assertEquals(null, scan.elems[1].weight)
        assertEquals(60001, scan.elems[1].colorful)
        assertFalse(scan.elems[1].hasContent)
        assertEquals(listOf<Int?>(60001, null), scan.srcKinds)
    }

    @Test
    fun scanRejectsTruncatedInputAndBadFields() {
        // 截断：只有 tag 没有长度。
        assertNull(DanmakuWire.scan(byteArrayOf(0x0a, 0x7f), fields))
        // 字段号冲突。
        val bad = fields.copy(weightField = fields.colorfulField)
        assertNull(DanmakuWire.scan(unknownTop(), bad))
        // 字段号越界。
        assertNull(DanmakuWire.scan(unknownTop(), fields.copy(elemsField = 536_870_912)))
    }

    @Test
    fun rewriteDropsRecolorsAndPreservesUnknownBytes() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "keep-me"),
            elem(1, 60001, "drop"),
            elem(5, 60001, null),
            src(60001),
            src(0),
        )
        val keep = booleanArrayOf(true, false, true)
        val recolor = booleanArrayOf(false, false, true)
        val keepSrc = booleanArrayOf(false, true)
        val rewritten = DanmakuWire.rewrite(reply, fields, keep, recolor, keepSrc)!!
        val rescan = DanmakuWire.scan(rewritten, fields)!!
        assertEquals(2, rescan.elems.size)
        assertEquals(1, rescan.srcKinds.size)
        assertEquals(7, rescan.elems[0].weight)
        assertEquals(5, rescan.elems[1].weight)
        assertEquals(0, rescan.elems[1].colorful)
        assertEquals(0, rescan.srcKinds[0])
        // 未知字段逐字节保留。
        val marker = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        var found = false
        for (i in 0..rewritten.size - 4) {
            if (rewritten.copyOfRange(i, i + 4).contentEquals(marker)) found = true
        }
        assertTrue(found)
        // 无改动 = 逐字节原样输出。
        val allKeep = BooleanArray(3) { true }
        val noRecolor = BooleanArray(3) { false }
        val allSrc = BooleanArray(2) { true }
        assertArrayEquals(reply, DanmakuWire.rewrite(reply, fields, allKeep, noRecolor, allSrc))
    }

    @Test
    fun maskLengthMismatchIsRejected() {
        val reply = concat(elem(5, 60001, null))
        assertNull(DanmakuWire.rewrite(reply, fields, booleanArrayOf(true, true), BooleanArray(1) { false }, BooleanArray(0)))
        assertNull(DanmakuWire.rewrite(reply, fields, booleanArrayOf(true), booleanArrayOf(false, false), BooleanArray(0)))
    }

    @Test
    fun flattenLayoutMatchesContract() {
        val reply = concat(elem(Int.MIN_VALUE, 60001, "t"), src(null))
        val scan = DanmakuWire.scan(reply, fields)!!
        val flat = DanmakuWire.flatten(scan)
        assertEquals(2 + 5 + 2, flat.size)
        assertEquals(1, flat[0])
        assertEquals(1, flat[1])
        assertEquals(Int.MIN_VALUE, flat[2])
        assertEquals(60001, flat[3])
        assertEquals(0b111, flat[4])
        // roundtrip。
        val back = DanmakuWire.fromFlattened(flat)!!
        assertEquals(scan.elems[0], back.elems[0])
        assertEquals(scan.srcKinds, back.srcKinds)
    }

    @Test
    fun realBilibiliCrossMessageFieldsAreValidAndRoundtrip() {
        // Real Bilibili protocol: DmSegMobileReply.elems = 1 AND DmColorful.type = 1
        val realFields = fields.copy(colorfulTypeField = 1)
        assertTrue(realFields.isValid())
        // Cross-message equality with weightField is also allowed.
        assertTrue(fields.copy(colorfulTypeField = fields.weightField).isValid())
        // Per-message collisions remain rejected.
        assertFalse(fields.copy(colorfulSrcField = fields.elemsField).isValid())
        assertFalse(fields.copy(weightField = fields.contentField).isValid())
        assertFalse(fields.copy(colorfulField = fields.contentField).isValid())

        val srcPayload = DanmakuWire.ByteArrayOutput().also {
            writeVarintField(it, 1, 60001L)
        }.toByteArray()
        val srcBlock = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, realFields.colorfulSrcField, srcPayload)
        }.toByteArray()
        val reply = concat(elem(2, 60001, "drop"), elem(9, 60001, "keep"), srcBlock)
        val scan = DanmakuWire.scan(reply, realFields)!!
        assertEquals(2, scan.elems.size)
        assertEquals(listOf<Int?>(60001), scan.srcKinds)

        val rewritten = DanmakuWire.rewrite(
            reply,
            realFields,
            booleanArrayOf(false, true),
            booleanArrayOf(false, true),
            booleanArrayOf(false),
        )!!
        val rescan = DanmakuWire.scan(rewritten, realFields)!!
        assertEquals(1, rescan.elems.size)
        assertEquals(0, rescan.elems[0].colorful)
        assertEquals(0, rescan.srcKinds.size)
    }

    @Test
    fun signed64BitLengthBypassAndInnerTruncatedOrOverrunInputsAreRejected() {
        // 0x8000000000000005 as 10-byte varint: signed negative Long with low 32 bits == 5.
        val negativeBypass = byteArrayOf(
            0x0a,
            0x85.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
        )
        assertNull(DanmakuWire.scan(negativeBypass, fields))
        assertNull(DanmakuWire.rewrite(negativeBypass, fields, BooleanArray(0), BooleanArray(0), BooleanArray(0)))

        // Truncated varint inside DanmakuElem (weight field 8, continuation byte 0x80 at end).
        val truncatedWeightElem = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, fields.elemsField, byteArrayOf(0x40, 0x80.toByte()))
        }.toByteArray()
        assertNull(DanmakuWire.scan(truncatedWeightElem, fields))

        // Truncated varint inside DmColorful (type field 2, continuation byte 0x80 at end).
        val truncatedSrcType = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, fields.colorfulSrcField, byteArrayOf(0x10, 0x80.toByte()))
        }.toByteArray()
        assertNull(DanmakuWire.scan(truncatedSrcType, fields))

        // Inner length overrun inside elem followed by trailing reply bytes (would overrun without Cursor limit=to).
        val overrunElemBody = byteArrayOf(
            ((fields.contentField shl 3) or 2).toByte(),
            0x0a, // claims 10 bytes inside a 3-byte elem
            'a'.code.toByte(),
        )
        val overrunReply = concat(
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, fields.elemsField, overrunElemBody) }.toByteArray(),
            unknownTop(),
        )
        assertNull(DanmakuWire.scan(overrunReply, fields))
        assertNull(DanmakuWire.rewrite(overrunReply, fields, booleanArrayOf(true), booleanArrayOf(true), BooleanArray(0)))
    }

    @Test
    fun flatScanMatchesScanAndValidatesOverflowAndMaskBounds() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "甲"),
            elem(null, 60001, null),
            src(60001),
            src(null),
        )
        val scan = DanmakuWire.scan(reply, fields)!!
        val flatScan = DanmakuWire.scanFlat(reply, fields)!!
        assertEquals(scan.elemCount, flatScan.elemCount)
        assertEquals(scan.srcCount, flatScan.srcCount)
        assertTrue(flatScan.hasWeight(0))
        assertEquals(7, flatScan.weight(0))
        assertFalse(flatScan.hasColorful(0))
        assertTrue(flatScan.hasContent(0))
        assertEquals("甲", String(reply, flatScan.contentStart(0), flatScan.contentLen(0), Charsets.UTF_8))
        assertFalse(flatScan.hasWeight(1))
        assertTrue(flatScan.hasColorful(1))
        assertEquals(60001, flatScan.colorful(1))
        assertFalse(flatScan.hasContent(1))
        assertTrue(flatScan.hasSrcKind(0))
        assertEquals(60001, flatScan.srcKind(0))
        assertFalse(flatScan.hasSrcKind(1))

        val fromFlat = DanmakuWire.flatScanFrom(DanmakuWire.flatten(scan))!!
        val roundtripScan = fromFlat.toScan()
        assertEquals(scan.elems, roundtripScan.elems)
        assertEquals(scan.srcKinds, roundtripScan.srcKinds)

        // Header / count / overflow validation.
        assertNull(DanmakuWire.FlatScan.from(intArrayOf()))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(-1, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0, -1)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(DanmakuWire.MAX_ELEMS + 1, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0, DanmakuWire.MAX_SRCS + 1)))
        // 32-bit Int overflow guard: 2 + 858_993_460 * 5 wraps to 6 in 32-bit arithmetic.
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(858_993_460, 0, 0, 0, 0, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(1, 0, 0, 0, 0, 0)))

        // Bounds checks on FlatScan accessors.
        var elemOob = false
        try {
            flatScan.weight(flatScan.elemCount)
        } catch (_: IndexOutOfBoundsException) {
            elemOob = true
        }
        assertTrue(elemOob)
        var srcOob = false
        try {
            flatScan.srcKind(flatScan.srcCount)
        } catch (_: IndexOutOfBoundsException) {
            srcOob = true
        }
        assertTrue(srcOob)

        // Rewrite rejects oversized masks even on empty replies.
        assertNull(
            DanmakuWire.rewrite(
                ByteArray(0),
                fields,
                BooleanArray(DanmakuWire.MAX_ELEMS + 1),
                BooleanArray(DanmakuWire.MAX_ELEMS + 1),
                BooleanArray(0),
            )
        )
        assertNull(
            DanmakuWire.rewrite(
                ByteArray(0),
                fields,
                BooleanArray(0),
                BooleanArray(0),
                BooleanArray(DanmakuWire.MAX_SRCS + 1),
            )
        )
    }

    @Test
    fun duplicateColorfulIsZeroedAcrossAllOccurrencesAndPreservesUnknownFields() {
        val elemPayload = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, 99, "raw-ext".toByteArray())
            writeVarintField(it, fields.colorfulField, 60001L)
            writeVarintField(it, fields.weightField, 7L)
            writeVarintField(it, fields.colorfulField, 60002L)
        }.toByteArray()
        val elemTop = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, fields.elemsField, elemPayload)
        }.toByteArray()
        val reply = concat(unknownTop(), elemTop)

        val scan = DanmakuWire.scan(reply, fields)!!
        assertEquals(1, scan.elems.size)
        assertEquals(7, scan.elems[0].weight)
        assertEquals(60002, scan.elems[0].colorful)

        val unchanged = DanmakuWire.rewrite(
            reply, fields, booleanArrayOf(true), booleanArrayOf(false), BooleanArray(0)
        )!!
        assertArrayEquals(reply, unchanged)

        val rewritten = DanmakuWire.rewrite(
            reply, fields, booleanArrayOf(true), booleanArrayOf(true), BooleanArray(0)
        )!!
        val rescan = DanmakuWire.scan(rewritten, fields)!!
        assertEquals(1, rescan.elems.size)
        assertEquals(7, rescan.elems[0].weight)
        assertEquals(0, rescan.elems[0].colorful)

        val innerMarker = "raw-ext".toByteArray()
        val topMarker = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
            (0..haystack.size - needle.size).any { i ->
                haystack.copyOfRange(i, i + needle.size).contentEquals(needle)
            }
        assertTrue(containsBytes(rewritten, innerMarker))
        assertTrue(containsBytes(rewritten, topMarker))
    }

    @Test
    fun nativePanicMarksUntrustedAndAvoidsFurtherNativeCalls() {
        try {
            DanmakuWireNative.setAvailableForTest(true)
            var invocations = 0
            // Non-panic decline keeps native trusted.
            val declined = DanmakuWireNative.scanWith(
                ByteArray(0),
                fields,
                invokeScan = { _, _ -> invocations++; null },
                hasPanicked = { false },
            )
            assertNull(declined)
            assertTrue(DanmakuWireNative.isAvailable)
            assertEquals(1, invocations)

            // DW_ERR_PANIC signal sets untrusted immediately and skips retries.
            val panicked = DanmakuWireNative.rewriteWith(
                ByteArray(0),
                fields,
                BooleanArray(0),
                BooleanArray(0),
                BooleanArray(0),
                invokeRewrite = { _, _, _, _, _ -> invocations++; null },
                hasPanicked = { true },
            )
            assertNull(panicked)
            assertFalse(DanmakuWireNative.isAvailable)
            assertEquals(2, invocations)

            val skipped = DanmakuWireNative.scanWith(
                ByteArray(0),
                fields,
                invokeScan = { _, _ -> invocations++; IntArray(0) },
                hasPanicked = { false },
            )
            assertNull(skipped)
            assertEquals(2, invocations)
        } finally {
            DanmakuWireNative.resetForTest()
        }
    }
}

/** 探针 + 端到端自检：在带真实 wire 编解码的 fixture 上过全链路（含宿主 parseFrom 核条数）。 */
class DanmakuWirePlanTest {
    @Test
    fun probeDiscoversFieldsAndSelfTestPassesEndToEnd() {
        val plan = DanmakuWirePlan.resolve(
            replyClass = WireReply::class.java,
            elemClass = WireElem::class.java,
            colorfulClass = WireColorful::class.java,
            replyBuilder = ProtobufBuilderPlan.resolve(WireReply::class.java)!!,
            elemBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            vipValue = 60001,
            listCountOf = { parsed -> (parsed as WireReply).elems.size },
            logError = { _, _ -> },
        )
        assertNotNull(plan)
        assertEquals(
            DanmakuWireFields(1, 8, 11, 10, 4, 2),
            plan!!.fields,
        )
        // 序列化/解析可用（供运行期 wire 路径使用）。
        val reply = WireReply(
            listOf(WireElem(9), WireElem(1, 60001, "x")),
            listOf(WireColorful(60001)),
            flags = 77,
        )
        val bytes = plan.serialize(reply)!!
        val parsed = plan.parse(bytes) as WireReply
        assertEquals(2, parsed.elems.size)
        assertEquals(77, parsed.getFlags())
    }

    @Test
    fun probeFailsGracefullyWithoutCodecMembers() {
        // WireElem 没有静态 parseFrom：reply 侧必须整体拒绝（返回 null），绝不猜。
        val plan = DanmakuWirePlan.resolve(
            replyClass = WireElem::class.java,
            elemClass = WireElem::class.java,
            colorfulClass = WireColorful::class.java,
            replyBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            elemBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            vipValue = 60001,
            listCountOf = { null },
            logError = { _, _ -> },
        )
        assertNull(plan)
    }
}
