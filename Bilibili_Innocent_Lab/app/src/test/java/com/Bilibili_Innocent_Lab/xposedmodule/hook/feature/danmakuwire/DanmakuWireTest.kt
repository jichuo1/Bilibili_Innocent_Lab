package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufReplyTreeRewriter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufReplyTreeVisitor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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

    open class ErasedBuilderBase(var label: String, var preserved: String) {
        open fun build(): Any = error("overridden")
    }

    class ConcreteErasedBuilder(label: String, preserved: String) : ErasedBuilderBase(label, preserved) {
        fun setLabel(value: String): ConcreteErasedBuilder {
            label = value
            return this
        }
        override fun build(): ErasedToBuilderMessage = ErasedToBuilderMessage(label, preserved)
    }

    class ErasedToBuilderMessage(val label: String, val preserved: String) {
        fun toBuilder(): ErasedBuilderBase = ConcreteErasedBuilder(label, preserved)
        companion object {
            private val DEFAULT = ErasedToBuilderMessage("", "")
            @JvmStatic fun getDefaultInstance(): ErasedToBuilderMessage = DEFAULT
        }
    }

    class RawReplyInfoList(initial: List<IndexedReplyItem> = emptyList()) : ArrayList<IndexedReplyItem>(initial)

    class IndexedReplyItem(val id: String, val children: List<IndexedReplyItem> = emptyList()) {
        fun getRepliesList(): RawReplyInfoList = RawReplyInfoList(children)
        fun getReplies(index: Int): IndexedReplyItem = children[index]

        class Builder(source: IndexedReplyItem) {
            private var children = source.children.toList()
            fun clearReplies(): Builder { children = emptyList(); return this }
            fun addAllReplies(items: Iterable<*>): Builder {
                children = items.filterIsInstance<IndexedReplyItem>()
                return this
            }
            fun build() = IndexedReplyItem(sourceId, children)
            private val sourceId = source.id
        }

        companion object {
            @JvmStatic fun newBuilder(source: IndexedReplyItem) = Builder(source)
        }
    }

    @Test
    fun scanAndFlatScanDecodeValidateAndRejectTruncatedOrOverflowInputs() {
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

        // Truncated varint & field collisions / out-of-range fields.
        assertNull(DanmakuWire.scan(byteArrayOf(0x0a, 0x7f), fields))
        assertNull(DanmakuWire.scan(unknownTop(), fields.copy(weightField = fields.colorfulField)))
        assertNull(DanmakuWire.scan(unknownTop(), fields.copy(elemsField = 536_870_912)))

        // 0x8000000000000005 as 10-byte varint & inner truncated/overrun payloads.
        val negativeBypass = byteArrayOf(
            0x0a,
            0x85.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
        )
        assertNull(DanmakuWire.scan(negativeBypass, fields))
        assertNull(DanmakuWire.rewrite(negativeBypass, fields, BooleanArray(0), BooleanArray(0), BooleanArray(0)))

        val truncatedWeightElem = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, fields.elemsField, byteArrayOf(0x40, 0x80.toByte()))
        }.toByteArray()
        assertNull(DanmakuWire.scan(truncatedWeightElem, fields))

        val truncatedSrcType = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, fields.colorfulSrcField, byteArrayOf(0x10, 0x80.toByte()))
        }.toByteArray()
        assertNull(DanmakuWire.scan(truncatedSrcType, fields))

        val overrunElemBody = byteArrayOf(((fields.contentField shl 3) or 2).toByte(), 0x0a, 'a'.code.toByte())
        val overrunReply = concat(
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, fields.elemsField, overrunElemBody) }.toByteArray(),
            unknownTop(),
        )
        assertNull(DanmakuWire.scan(overrunReply, fields))
        assertNull(DanmakuWire.rewrite(overrunReply, fields, booleanArrayOf(true), booleanArrayOf(true), BooleanArray(0)))
    }

    @Test
    fun flatScanBoundsAndMaskLengthChecksRejectOverflows() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "甲"),
            elem(null, 60001, null),
            src(60001),
            src(null),
        )
        val scan = DanmakuWire.scan(reply, fields)!!
        // FlatScan equivalence, overflow guards, bounds checks, and mask length checks.
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

        val roundtripScan = DanmakuWire.flatScanFrom(DanmakuWire.flatten(scan))!!.toScan()
        assertEquals(scan.elems, roundtripScan.elems)
        assertEquals(scan.srcKinds, roundtripScan.srcKinds)

        assertNull(DanmakuWire.FlatScan.from(intArrayOf()))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(-1, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0, -1)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(DanmakuWire.MAX_ELEMS + 1, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(0, DanmakuWire.MAX_SRCS + 1)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(858_993_460, 0, 0, 0, 0, 0)))
        assertNull(DanmakuWire.FlatScan.from(intArrayOf(1, 0, 0, 0, 0, 0)))

        var elemOob = false
        try { flatScan.weight(flatScan.elemCount) } catch (_: IndexOutOfBoundsException) { elemOob = true }
        assertTrue(elemOob)
        var srcOob = false
        try { flatScan.srcKind(flatScan.srcCount) } catch (_: IndexOutOfBoundsException) { srcOob = true }
        assertTrue(srcOob)

        assertNull(DanmakuWire.rewrite(ByteArray(0), fields, BooleanArray(DanmakuWire.MAX_ELEMS + 1), BooleanArray(DanmakuWire.MAX_ELEMS + 1), BooleanArray(0)))
        assertNull(DanmakuWire.rewrite(ByteArray(0), fields, BooleanArray(0), BooleanArray(0), BooleanArray(DanmakuWire.MAX_SRCS + 1)))
    }

    @Test
    fun rewriteFlattenAndCrossMessageRoundtripPreserveUnknownBytesAndBounds() {
        val reply = concat(unknownTop(), elem(7, null, "keep-me"), elem(1, 60001, "drop"), elem(5, 60001, null), src(60001), src(0))
        val rewritten = DanmakuWire.rewrite(reply, fields, booleanArrayOf(true, false, true), booleanArrayOf(false, false, true), booleanArrayOf(false, true))!!
        val rescan = DanmakuWire.scan(rewritten, fields)!!
        assertEquals(2, rescan.elems.size)
        assertEquals(1, rescan.srcKinds.size)
        assertEquals(7, rescan.elems[0].weight)
        assertEquals(5, rescan.elems[1].weight)
        assertEquals(0, rescan.elems[1].colorful)
        assertEquals(0, rescan.srcKinds[0])
        val marker = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertTrue((0..rewritten.size - 4).any { i -> rewritten.copyOfRange(i, i + 4).contentEquals(marker) })
        assertArrayEquals(reply, DanmakuWire.rewrite(reply, fields, BooleanArray(3) { true }, BooleanArray(3) { false }, BooleanArray(2) { true }))

        val single = concat(elem(5, 60001, null))
        assertNull(DanmakuWire.rewrite(single, fields, booleanArrayOf(true, true), BooleanArray(1) { false }, BooleanArray(0)))
        assertNull(DanmakuWire.rewrite(single, fields, booleanArrayOf(true), booleanArrayOf(false, false), BooleanArray(0)))

        val minReply = concat(elem(Int.MIN_VALUE, 60001, "t"), src(null))
        val minScan = DanmakuWire.scan(minReply, fields)!!
        val flat = DanmakuWire.flatten(minScan)
        assertEquals(2 + 5 + 2, flat.size)
        assertEquals(1, flat[0]); assertEquals(1, flat[1]); assertEquals(Int.MIN_VALUE, flat[2]); assertEquals(60001, flat[3]); assertEquals(0b111, flat[4])
        val back = DanmakuWire.fromFlattened(flat)!!
        assertEquals(minScan.elems[0], back.elems[0])
        assertEquals(minScan.srcKinds, back.srcKinds)
    }

    @Test
    fun crossMessageAndDuplicateColorfulPreserveUnknownFields() {
        val marker = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        // Cross-message equality & rejection.
        val realFields = fields.copy(colorfulTypeField = 1)
        assertTrue(realFields.isValid())
        assertTrue(fields.copy(colorfulTypeField = fields.weightField).isValid())
        assertFalse(fields.copy(colorfulSrcField = fields.elemsField).isValid())
        assertFalse(fields.copy(weightField = fields.contentField).isValid())
        assertFalse(fields.copy(colorfulField = fields.contentField).isValid())

        val srcPayload = DanmakuWire.ByteArrayOutput().also { writeVarintField(it, 1, 60001L) }.toByteArray()
        val srcBlock = DanmakuWire.ByteArrayOutput().also { writeBytesField(it, realFields.colorfulSrcField, srcPayload) }.toByteArray()
        val realReply = concat(elem(2, 60001, "drop"), elem(9, 60001, "keep"), srcBlock)
        val realScan = DanmakuWire.scan(realReply, realFields)!!
        assertEquals(2, realScan.elems.size)
        assertEquals(listOf<Int?>(60001), realScan.srcKinds)
        val realRewritten = DanmakuWire.rewrite(realReply, realFields, booleanArrayOf(false, true), booleanArrayOf(false, true), booleanArrayOf(false))!!
        val realRescan = DanmakuWire.scan(realRewritten, realFields)!!
        assertEquals(1, realRescan.elems.size)
        assertEquals(0, realRescan.elems[0].colorful)
        assertEquals(0, realRescan.srcKinds.size)

        // Duplicate colorful field zeroing across occurrences preserving unknown fields.
        val dupElemPayload = DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, 99, "raw-ext".toByteArray())
            writeVarintField(it, fields.colorfulField, 60001L)
            writeVarintField(it, fields.weightField, 7L)
            writeVarintField(it, fields.colorfulField, 60002L)
        }.toByteArray()
        val dupReply = concat(unknownTop(), DanmakuWire.ByteArrayOutput().also { writeBytesField(it, fields.elemsField, dupElemPayload) }.toByteArray())
        val dupScan = DanmakuWire.scan(dupReply, fields)!!
        assertEquals(1, dupScan.elems.size)
        assertEquals(7, dupScan.elems[0].weight)
        assertEquals(60002, dupScan.elems[0].colorful)
        assertArrayEquals(dupReply, DanmakuWire.rewrite(dupReply, fields, booleanArrayOf(true), booleanArrayOf(false), BooleanArray(0))!!)
        val dupRewritten = DanmakuWire.rewrite(dupReply, fields, booleanArrayOf(true), booleanArrayOf(true), BooleanArray(0))!!
        val dupRescan = DanmakuWire.scan(dupRewritten, fields)!!
        assertEquals(1, dupRescan.elems.size)
        assertEquals(7, dupRescan.elems[0].weight)
        assertEquals(0, dupRescan.elems[0].colorful)
        val innerMarker = "raw-ext".toByteArray()
        fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
            (0..haystack.size - needle.size).any { i -> haystack.copyOfRange(i, i + needle.size).contentEquals(needle) }
        assertTrue(containsBytes(dupRewritten, innerMarker))
        assertTrue(containsBytes(dupRewritten, marker))
    }

    @Test
    fun nativePanicMarksUntrustedAndAvoidsFurtherNativeCalls() {
        try {
            DanmakuWireNative.setAvailableForTest(true)
            var invocations = 0
            val declined = DanmakuWireNative.scanWith(ByteArray(0), fields, invokeScan = { _, _ -> invocations++; null }, hasPanicked = { false })
            assertNull(declined)
            assertTrue(DanmakuWireNative.isAvailable)
            assertEquals(1, invocations)

            val panicked = DanmakuWireNative.rewriteWith(
                ByteArray(0), fields, BooleanArray(0), BooleanArray(0), BooleanArray(0),
                invokeRewrite = { _, _, _, _, _ -> invocations++; null }, hasPanicked = { true }
            )
            assertNull(panicked)
            assertFalse(DanmakuWireNative.isAvailable)
            assertEquals(2, invocations)

            val skipped = DanmakuWireNative.scanWith(ByteArray(0), fields, invokeScan = { _, _ -> invocations++; IntArray(0) }, hasPanicked = { false })
            assertNull(skipped)
            assertEquals(2, invocations)
        } finally {
            DanmakuWireNative.resetForTest()
        }
    }

    @Test
    fun danmakuWirePlanAndProtobufTreePlansResolveAndRewriteSafely() {
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
        assertEquals(DanmakuWireFields(1, 8, 11, 10, 4, 2), plan!!.fields)
        val reply = WireReply(listOf(WireElem(9), WireElem(1, 60001, "x")), listOf(WireColorful(60001)), flags = 77)
        val bytes = plan.serialize(reply)!!
        val parsed = plan.parse(bytes) as WireReply
        assertEquals(2, parsed.elems.size)
        assertEquals(77, parsed.getFlags())

        // Missing parseFrom fails closed.
        assertNull(DanmakuWirePlan.resolve(
            replyClass = WireElem::class.java,
            elemClass = WireElem::class.java,
            colorfulClass = WireColorful::class.java,
            replyBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            elemBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            vipValue = 60001,
            listCountOf = { null },
            logError = { _, _ -> },
        ))

        // ProtobufBuilderPlan erased toBuilder() & ProtobufReplyTreeVisitor/Rewriter indexed getter fallback.
        val builderPlan = requireNotNull(ProtobufBuilderPlan.resolve(ErasedToBuilderMessage::class.java))
        val setLabel = requireNotNull(builderPlan.method("setLabel", String::class.java))
        val src = ErasedToBuilderMessage("old", "keep-me")
        val updated = builderPlan.edit(src) { b -> setLabel.invoke(b, "new") } as ErasedToBuilderMessage
        assertNotSame(src, updated)
        assertEquals("new", updated.label)
        assertEquals("keep-me", updated.preserved)

        val root = IndexedReplyItem("root", listOf(IndexedReplyItem("drop"), IndexedReplyItem("keep")))
        val visited = mutableListOf<String>()
        ProtobufReplyTreeVisitor(IndexedReplyItem::class.java) { item -> visited += (item as IndexedReplyItem).id }.visit(root)
        assertEquals(listOf("drop", "keep"), visited)

        val rewrittenTree = ProtobufReplyTreeRewriter(IndexedReplyItem::class.java) { list ->
            list.filter { (it as IndexedReplyItem).id == "drop" }.toSet()
        }.rewrite(root)
        assertEquals(1, rewrittenTree.removed)
        val rewrittenMsg = rewrittenTree.message as IndexedReplyItem
        assertNotSame(root, rewrittenMsg)
        assertEquals(listOf("keep"), rewrittenMsg.getRepliesList().map { it.id })
    }
}
