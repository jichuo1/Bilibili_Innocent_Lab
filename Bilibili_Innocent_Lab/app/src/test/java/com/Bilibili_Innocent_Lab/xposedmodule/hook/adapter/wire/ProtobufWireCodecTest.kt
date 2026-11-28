package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtobufWireCodecTest {

    // -------------------------------------------------------------------------
    // Helper builders (pure bytes, zero com.bapis / external proto runtime)
    // -------------------------------------------------------------------------

    private fun buildMessage(block: ProtobufWireCodec.ByteBuf.() -> Unit): ByteArray =
        ProtobufWireCodec.ByteBuf(64).apply(block).toByteArray()

    private fun ProtobufWireCodec.ByteBuf.writeStringField(fieldNumber: Int, value: String) {
        writeLengthDelimited(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun ProtobufWireCodec.ByteBuf.writeFixed32Field(fieldNumber: Int, value: Int) {
        writeVarint(ProtobufWireCodec.makeTag(fieldNumber, ProtobufWireCodec.WIRE_TYPE_FIXED32))
        write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte()
            ),
            0,
            4
        )
    }

    private fun ProtobufWireCodec.ByteBuf.writeFixed64Field(fieldNumber: Int, value: Long) {
        writeVarint(ProtobufWireCodec.makeTag(fieldNumber, ProtobufWireCodec.WIRE_TYPE_FIXED64))
        val bytes = ByteArray(8)
        for (i in 0 until 8) {
            bytes[i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
        write(bytes, 0, 8)
    }

    private fun ProtobufWireCodec.ByteBuf.writeGroupField(
        fieldNumber: Int,
        body: ProtobufWireCodec.ByteBuf.() -> Unit
    ) {
        writeVarint(ProtobufWireCodec.makeTag(fieldNumber, ProtobufWireCodec.WIRE_TYPE_START_GROUP))
        body()
        writeVarint(ProtobufWireCodec.makeTag(fieldNumber, ProtobufWireCodec.WIRE_TYPE_END_GROUP))
    }

    // -------------------------------------------------------------------------
    // 1. Varint edge cases & malformed varints
    // -------------------------------------------------------------------------

    @Test
    fun `varint roundtrips boundary values and rejects empty truncated overlong or overflow inputs`() {
        val cases = listOf(
            0L to 1,
            1L to 1,
            127L to 1,
            128L to 2,
            16383L to 2,
            16384L to 3,
            Int.MAX_VALUE.toLong() to 5,
            Long.MAX_VALUE to 9,
            -1L to 10,
            Long.MIN_VALUE to 10,
            -123456789012345L to 10
        )
        for ((value, expectedSize) in cases) {
            val encoded = ProtobufWireCodec.writeVarint(value)
            assertEquals("size for $value", expectedSize, encoded.size)
            val decoded = ProtobufWireCodec.readVarint(encoded, 0)
            assertNotNull("decode failed for $value", decoded)
            assertEquals(value, decoded!!.value)
            assertEquals(expectedSize, decoded.nextOffset)
        }

        // Empty / invalid offset
        assertNull(ProtobufWireCodec.readVarint(ByteArray(0), 0))
        assertNull(ProtobufWireCodec.readVarint(byteArrayOf(0x01), -1))
        assertNull(ProtobufWireCodec.readVarint(byteArrayOf(0x01), 1))

        // Truncated (continuation bit set on final byte)
        assertNull(ProtobufWireCodec.readVarint(byteArrayOf(0x80.toByte()), 0))
        assertNull(ProtobufWireCodec.readVarint(byteArrayOf(0xFF.toByte(), 0x81.toByte()), 0))

        // Overlong (11 bytes)
        val elevenBytes = ByteArray(11) { 0x80.toByte() }.apply { this[10] = 0x00 }
        assertNull(ProtobufWireCodec.readVarint(elevenBytes, 0))

        // 10th byte with continuation bit or > 1 (overflows 64-bit)
        val tenthOverflow = ByteArray(10) { 0xFF.toByte() }.apply { this[9] = 0x02 }
        assertNull(ProtobufWireCodec.readVarint(tenthOverflow, 0))
        val tenthCont = ByteArray(10) { 0x80.toByte() }
        assertNull(ProtobufWireCodec.readVarint(tenthCont, 0))
    }

    // -------------------------------------------------------------------------
    // 2. Fixed32 / Fixed64 / StartGroup-EndGroup / Copy-on-change identity
    // -------------------------------------------------------------------------

    @Test
    fun `scans fixed and nested groups while preserving unknown fields and copy on change identity`() {
        val msg = buildMessage {
            writeVarintField(1, 42L)
            writeFixed32Field(2, 0x12345678)
            writeFixed64Field(3, 0x0102030405060708L)
            writeGroupField(4) {
                writeVarintField(10, 99L)
                writeGroupField(11) {
                    writeStringField(12, "inner-group")
                }
            }
            writeStringField(5, "tail")
        }

        val slices = ProtobufWireCodec.scanTopLevel(msg)
        assertNotNull(slices)
        assertEquals(5, slices!!.size)

        assertEquals(1, slices[0].fieldNumber)
        assertEquals(ProtobufWireCodec.WIRE_TYPE_VARINT, slices[0].wireType)
        assertEquals(42L, slices[0].varintValue)

        assertEquals(2, slices[1].fieldNumber)
        assertEquals(ProtobufWireCodec.WIRE_TYPE_FIXED32, slices[1].wireType)
        assertEquals(0x12345678, ProtobufWireCodec.readFixed32Le(msg, slices[1].payloadStart, slices[1].payloadEnd))

        assertEquals(3, slices[2].fieldNumber)
        assertEquals(ProtobufWireCodec.WIRE_TYPE_FIXED64, slices[2].wireType)
        assertEquals(0x0102030405060708L, ProtobufWireCodec.readFixed64Le(msg, slices[2].payloadStart, slices[2].payloadEnd))

        assertEquals(4, slices[3].fieldNumber)
        assertEquals(ProtobufWireCodec.WIRE_TYPE_START_GROUP, slices[3].wireType)
        val innerSlices = ProtobufWireCodec.scanTopLevel(msg, slices[3].payloadStart, slices[3].payloadLength)
        assertNotNull(innerSlices)
        assertEquals(2, innerSlices!!.size)
        assertEquals(10, innerSlices[0].fieldNumber)
        assertEquals(11, innerSlices[1].fieldNumber)

        assertEquals(5, slices[4].fieldNumber)
        assertEquals("tail", ProtobufWireCodec.decodeUtf8OrNull(msg, slices[4].payloadStart, slices[4].payloadEnd))

        // Drop only field 1, verify fixed32/fixed64/nested-group/tail remain byte-for-byte identical
        val dropped1 = ProtobufWireCodec.rewriteFields(msg) { slice ->
            if (slice.fieldNumber == 1) WireRewriteAction.Drop else WireRewriteAction.Keep
        }
        assertNotSame(msg, dropped1)
        assertArrayEquals(msg.copyOfRange(slices[0].rawEnd, msg.size), dropped1)

        val original = buildMessage {
            writeVarintField(1, 7L)
            writeStringField(2, "keep-me")
            writeStringField(3, "replace-raw")
            writeStringField(4, "replace-delimited")
            writeFixed32Field(99, 0xCAFEBABE.toInt())
        }
        assertSame(original, ProtobufWireCodec.rewriteFields(original) { WireRewriteAction.Keep })
        val empty = ByteArray(0)
        assertSame(empty, ProtobufWireCodec.rewriteFields(empty) { WireRewriteAction.Drop })

        val replacementRawField = buildMessage { writeVarintField(30, 1234L) }
        val rewritten = ProtobufWireCodec.rewriteFields(original) { slice ->
            when (slice.fieldNumber) {
                1 -> WireRewriteAction.Drop
                3 -> WireRewriteAction.ReplaceRaw(replacementRawField)
                4 -> WireRewriteAction.ReplaceLengthDelimited(4, "updated".toByteArray(Charsets.UTF_8))
                else -> WireRewriteAction.Keep
            }
        }
        assertNotSame(original, rewritten)
        val outSlices = ProtobufWireCodec.scanTopLevel(rewritten)!!
        assertEquals(listOf(2, 30, 4, 99), outSlices.map { it.fieldNumber })
        assertEquals("keep-me", ProtobufWireCodec.decodeUtf8OrNull(rewritten, outSlices[0].payloadStart, outSlices[0].payloadEnd))
        assertEquals(1234L, outSlices[1].varintValue)
        assertEquals("updated", ProtobufWireCodec.decodeUtf8OrNull(rewritten, outSlices[2].payloadStart, outSlices[2].payloadEnd))
        assertEquals(0xCAFEBABE.toInt(), ProtobufWireCodec.readFixed32Le(rewritten, outSlices[3].payloadStart))
    }

    // -------------------------------------------------------------------------
    // 3. Malformed / truncated / oversized / deeply-nested rejection + UTF-8 + packed
    // -------------------------------------------------------------------------

    @Test
    fun `rejects truncated oversized reserved unbalanced or overly deep wire inputs and validates utf8 and packed varints`() {
        val truncatedDelimited = byteArrayOf(((2 shl 3) or 2).toByte(), 10, 0x01, 0x02)
        assertNull(ProtobufWireCodec.scanTopLevel(truncatedDelimited))
        assertNull(ProtobufWireCodec.rewriteFieldsOrNull(truncatedDelimited) { WireRewriteAction.Drop })
        assertSame(truncatedDelimited, ProtobufWireCodec.rewriteFields(truncatedDelimited) { WireRewriteAction.Drop })

        val giantLen = buildMessage {
            writeVarint(ProtobufWireCodec.makeTag(1, ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED))
            writeVarint(Int.MAX_VALUE.toLong())
            write(byteArrayOf(0x01), 0, 1)
        }
        assertNull(ProtobufWireCodec.scanTopLevel(giantLen))

        val negativeLen = buildMessage {
            writeVarint(ProtobufWireCodec.makeTag(1, ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED))
            writeVarint(-1L)
        }
        assertNull(ProtobufWireCodec.scanTopLevel(negativeLen))

        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(0x00, 0x01)))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 6).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 7).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 5).toByte(), 1, 2, 3)))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 1).toByte(), 1, 2, 3, 4, 5, 6, 7)))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 4).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 3).toByte(), ((2 shl 3) or 4).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 3).toByte(), ((1 shl 3) or 0).toByte(), 0x01)))

        fun buildNestedGroup(depth: Int): ByteArray = buildMessage {
            repeat(depth) { d ->
                writeVarint(ProtobufWireCodec.makeTag(d + 1, ProtobufWireCodec.WIRE_TYPE_START_GROUP))
            }
            writeVarintField(100, 1L)
            for (d in depth - 1 downTo 0) {
                writeVarint(ProtobufWireCodec.makeTag(d + 1, ProtobufWireCodec.WIRE_TYPE_END_GROUP))
            }
        }
        assertNotNull(ProtobufWireCodec.scanTopLevel(buildNestedGroup(ProtobufWireCodec.MAX_GROUP_DEPTH)))
        assertNull(ProtobufWireCodec.scanTopLevel(buildNestedGroup(ProtobufWireCodec.MAX_GROUP_DEPTH + 1)))

        val validSmall = buildMessage { writeStringField(1, "hello") }
        assertNull(ProtobufWireCodec.scanTopLevel(validSmall, maxBytes = validSmall.size - 1))

        val validUtf8 = "你好Bilibili🚀".toByteArray(Charsets.UTF_8)
        assertEquals("你好Bilibili🚀", ProtobufWireCodec.decodeUtf8OrNull(validUtf8))
        assertEquals("", ProtobufWireCodec.decodeUtf8OrNull(ByteArray(0)))
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xFF.toByte())))
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xC3.toByte())))
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xC0.toByte(), 0xAF.toByte())))

        val packed = buildMessage {
            writeVarint(0L)
            writeVarint(127L)
            writeVarint(128L)
            writeVarint(-5L)
        }
        assertArrayEquals(longArrayOf(0L, 127L, 128L, -5L), ProtobufWireCodec.readPackedVarints(packed))
        assertArrayEquals(LongArray(0), ProtobufWireCodec.readPackedVarints(ByteArray(0)))
        assertNull(ProtobufWireCodec.readPackedVarints(packed + byteArrayOf(0x80.toByte())))
    }

    // -------------------------------------------------------------------------
    // 4. KmpRawWireAdapters + KmpWireSchemaProber
    // -------------------------------------------------------------------------

    @Test
    fun `kmp raw wire adapters and schema prober preserve unknown fields calibrate shifts and keep identity`() {
        // 4A: ReplyMainListWireAdapter
        fun encodeReply(
            id: Long,
            mid: Long,
            message: String,
            name: String,
            level: Int,
            children: List<ByteArray> = emptyList(),
            useLegacyMember: Boolean = false
        ): ByteArray = buildMessage {
            children.forEach { writeLengthDelimited(1, it) }
            writeVarintField(2, id)
            writeVarintField(5, mid)
            writeVarintField(11, 999L)
            writeLengthDelimited(12, buildMessage { writeStringField(1, message) })
            if (useLegacyMember) {
                writeLengthDelimited(
                    13,
                    buildMessage {
                        writeVarintField(1, mid)
                        writeStringField(2, name)
                        writeVarintField(5, level.toLong())
                        writeVarintField(6, 2L)
                    }
                )
            } else {
                writeLengthDelimited(
                    15,
                    buildMessage {
                        writeLengthDelimited(
                            1,
                            buildMessage {
                                writeVarintField(1, mid)
                                writeStringField(2, name)
                                writeVarintField(5, level.toLong())
                                writeLengthDelimited(6, buildMessage { writeStringField(1, "avatar_item") })
                            }
                        )
                    }
                )
            }
            writeLengthDelimited(19, buildMessage { writeStringField(1, "hot_info_kept") })
        }

        val childSpam = encodeReply(id = 101L, mid = 999L, message = "spam_child_link", name = "Bot", level = 0)
        val childClean = encodeReply(id = 102L, mid = 222L, message = "nice nested comment", name = "Alice", level = 5, useLegacyMember = true)
        val mainKeep = encodeReply(id = 1L, mid = 111L, message = "helpful discussion", name = "Bob", level = 6, children = listOf(childSpam, childClean))
        val mainSpam = encodeReply(id = 2L, mid = 888L, message = "buy_promo_now", name = "AdAccount", level = 1)
        val topSpam = encodeReply(id = 3L, mid = 777L, message = "pinned_ad", name = "Sponsor", level = 4)
        val upTopClean = encodeReply(id = 4L, mid = 333L, message = "author pinned note", name = "UP", level = 6)
        val voteTopSpam = encodeReply(id = 5L, mid = 666L, message = "buy_promo_vote", name = "Spammer", level = 2)
        val subjectControlPayload = buildMessage { writeVarintField(1, 1L); writeVarintField(2, 555L) }
        val unknownCursor = buildMessage { writeStringField(1, "cursor_next_page_token"); writeVarintField(2, 1L) }
        val mainListBytes = buildMessage {
            writeLengthDelimited(1, unknownCursor)
            writeLengthDelimited(2, mainKeep)
            writeLengthDelimited(2, mainSpam)
            writeLengthDelimited(3, subjectControlPayload)
            writeLengthDelimited(4, upTopClean)
            writeLengthDelimited(6, voteTopSpam)
            writeLengthDelimited(14, topSpam)
            writeLengthDelimited(77, unknownCursor)
        }
        assertSame(mainListBytes, ReplyMainListWireAdapter.filterReplies(mainListBytes) { false })
        val filteredReplies = ReplyMainListWireAdapter.filterReplies(mainListBytes) { signal ->
            val msg = signal.message.orEmpty()
            msg.contains("promo") || msg.contains("pinned_ad") || msg.contains("spam_child") || (signal.level ?: 6) < 1
        }
        assertNotSame(mainListBytes, filteredReplies)
        val remainingReplies = ReplyMainListWireAdapter.extractTopLevelReplies(filteredReplies)
        assertEquals(2, remainingReplies.size)
        assertEquals(1L, remainingReplies[0].id)
        assertEquals("Bob", remainingReplies[0].authorName)
        assertEquals(6, remainingReplies[0].level)
        assertEquals(1, remainingReplies[0].nestedReplyCount)
        assertEquals(4L, remainingReplies[1].id)
        val replyTopSlices = ProtobufWireCodec.scanTopLevel(filteredReplies)!!
        val scSlice = replyTopSlices.single { it.fieldNumber == 3 }
        assertArrayEquals(subjectControlPayload, filteredReplies.copyOfRange(scSlice.payloadStart, scSlice.payloadEnd))
        val unknownSlice = replyTopSlices.single { it.fieldNumber == 77 }
        assertArrayEquals(unknownCursor, filteredReplies.copyOfRange(unknownSlice.payloadStart, unknownSlice.payloadEnd))

        // 4B: DynListWireAdapter
        fun encodeDynItem(dynId: String, uid: Long, author: String, text: String, dynText: String = "", cardType: Long = 2L): ByteArray =
            buildMessage {
                writeVarintField(1, cardType)
                writeVarintField(2, 1L)
                writeLengthDelimited(
                    3,
                    buildMessage {
                        writeVarintField(1, 2L)
                        writeLengthDelimited(
                            2,
                            buildMessage {
                                writeVarintField(1, uid)
                                writeLengthDelimited(3, buildMessage { writeVarintField(1, uid); writeStringField(2, author) })
                            }
                        )
                        writeLengthDelimited(3, buildMessage { writeStringField(1, "dispute_ignore") })
                        writeLengthDelimited(4, buildMessage { writeLengthDelimited(1, buildMessage { writeStringField(1, text) }) })
                        if (dynText.isNotEmpty()) {
                            writeLengthDelimited(5, buildMessage { writeLengthDelimited(1, buildMessage { writeStringField(1, dynText) }) })
                        }
                    }
                )
                writeLengthDelimited(
                    4,
                    buildMessage {
                        writeStringField(1, dynId)
                        writeStringField(3, "orig_id_str_999")
                        writeStringField(4, author)
                        writeVarintField(14, uid)
                    }
                )
            }
        val itemKeep = encodeDynItem("dyn_1", 1001L, "TechUP", "Kernel deep dive", dynText = "dynamic_payload_ok")
        val itemAd = encodeDynItem("dyn_2", 9001L, "MallBot", "Limited promo", dynText = "flash_sale_module_dynamic", cardType = 10L)
        val upListPayload = buildMessage { writeStringField(1, "up_list_preserved") }
        val topicPayload = buildMessage { writeStringField(1, "topic_preserved") }
        val cursorPayload = "cursor_v2_opaque".toByteArray(Charsets.UTF_8)
        val dynReply = buildMessage {
            writeLengthDelimited(
                1,
                buildMessage {
                    writeLengthDelimited(1, itemKeep)
                    writeLengthDelimited(1, itemAd)
                    writeLengthDelimited(4, cursorPayload)
                }
            )
            writeLengthDelimited(2, upListPayload)
            writeLengthDelimited(3, topicPayload)
            writeFixed64Field(88, 9876543210L)
        }
        assertSame(dynReply, DynListWireAdapter.filterDynamicList(dynReply) { false })
        val filteredDyn = DynListWireAdapter.filterDynamicList(dynReply) { signal ->
            signal.texts.any { it.contains("flash_sale_module_dynamic") } || signal.cardType == 10L
        }
        assertNotSame(dynReply, filteredDyn)
        val extractedDyn = DynListWireAdapter.extractDynamicItems(filteredDyn)
        assertEquals(1, extractedDyn.size)
        assertEquals("dyn_1", extractedDyn[0].dynamicId)
        assertEquals("TechUP", extractedDyn[0].authorName)
        assertEquals(1001L, extractedDyn[0].authorUid)
        assertEquals(listOf("Kernel deep dive", "dynamic_payload_ok"), extractedDyn[0].texts)
        val dynTopSlices = ProtobufWireCodec.scanTopLevel(filteredDyn)!!
        assertEquals(listOf(1, 2, 3, 88), dynTopSlices.map { it.fieldNumber })
        assertArrayEquals(upListPayload, filteredDyn.copyOfRange(dynTopSlices[1].payloadStart, dynTopSlices[1].payloadEnd))
        assertArrayEquals(topicPayload, filteredDyn.copyOfRange(dynTopSlices[2].payloadStart, dynTopSlices[2].payloadEnd))
        assertEquals(9876543210L, ProtobufWireCodec.readFixed64Le(filteredDyn, dynTopSlices[3].payloadStart))
        val innerListSlices = ProtobufWireCodec.scanTopLevel(filteredDyn, dynTopSlices[0].payloadStart, dynTopSlices[0].payloadLength)!!
        assertEquals(listOf(1, 4), innerListSlices.map { it.fieldNumber })
        assertArrayEquals(cursorPayload, filteredDyn.copyOfRange(innerListSlices[1].payloadStart, innerListSlices[1].payloadEnd))

        // 4C: ViewUniteWireAdapter
        fun encodeModule(type: Int, title: String): ByteArray = buildMessage {
            writeVarintField(1, type.toLong())
            writeStringField(9, title)
        }
        val activityTabPayload = buildMessage { writeVarintField(2, 999L) }
        val viewReply = buildMessage {
            writeLengthDelimited(2, buildMessage { writeStringField(1, "BV1test") })
            writeLengthDelimited(3, buildMessage { writeVarintField(1, 7L) })
            writeLengthDelimited(4, buildMessage { writeVarintField(1, 42L) })
            writeLengthDelimited(
                5,
                buildMessage {
                    writeLengthDelimited(
                        1,
                        buildMessage {
                            writeVarintField(1, 1L)
                            writeLengthDelimited(
                                2,
                                buildMessage {
                                    writeLengthDelimited(2, encodeModule(5, "ugc_intro"))
                                    writeLengthDelimited(2, encodeModule(22, "game_promo"))
                                    writeLengthDelimited(2, encodeModule(10, "relates"))
                                    writeStringField(55, "intro_unknown_keep")
                                }
                            )
                        }
                    )
                    writeLengthDelimited(
                        1,
                        buildMessage {
                            writeVarintField(1, 3L)
                            writeLengthDelimited(4, activityTabPayload)
                        }
                    )
                }
            )
            writeLengthDelimited(6, buildMessage { writeStringField(1, "supplement") })
            writeVarintField(8, 1L)
            writeLengthDelimited(9, buildMessage { writeStringField(1, "ecode_cfg") })
            writeFixed32Field(102, 0x0BADF00D)
        }
        val summary = ViewUniteWireAdapter.extractSummary(viewReply)!!
        assertTrue(summary.hasArc && summary.hasOwner && summary.hasTab && summary.hasSupplement)
        assertEquals(1L, summary.ecode)
        assertEquals(listOf(5, 22, 10), summary.tabModuleTypes)
        assertFalse(summary.hasPlayVodInfo)
        assertSame(viewReply, ViewUniteWireAdapter.filterTabModules(viewReply) { _, _ -> false })
        val filteredModules = ViewUniteWireAdapter.filterTabModules(viewReply) { modType, _ -> modType == 22 }
        assertNotSame(viewReply, filteredModules)
        val afterSummary = ViewUniteWireAdapter.extractSummary(filteredModules)!!
        assertEquals(listOf(5, 10), afterSummary.tabModuleTypes)
        assertTrue(afterSummary.hasArc)
        assertEquals(1L, afterSummary.ecode)
        assertSame(filteredModules, ViewUniteWireAdapter.stripTopLevelFields(filteredModules, setOf(999)))
        val stripped = ViewUniteWireAdapter.stripTopLevelFields(filteredModules, setOf(6, 8))
        val strippedSummary = ViewUniteWireAdapter.extractSummary(stripped)!!
        assertNull(strippedSummary.ecode)
        assertFalse(strippedSummary.hasSupplement)
        assertTrue(strippedSummary.hasTab)
        assertTrue(102 in strippedSummary.topLevelFieldNumbers)
        val viewUnknownSlice = ProtobufWireCodec.scanTopLevel(stripped)!!.single { it.fieldNumber == 102 }
        assertEquals(0x0BADF00D, ProtobufWireCodec.readFixed32Le(stripped, viewUnknownSlice.payloadStart))

        val playViewReply = buildMessage {
            writeLengthDelimited(1, buildMessage { writeVarintField(1, 80L) })
            writeLengthDelimited(5, buildMessage { writeStringField(1, "play_supplement") })
        }
        val playSummary = ViewUniteWireAdapter.extractSummary(playViewReply, isPlayView = true)!!
        assertTrue(playSummary.hasPlayVodInfo && playSummary.hasSupplement && !playSummary.hasTab)

        // 4D: KmpWireSchemaProber
        val emptyLoader = object : ClassLoader(ProtobufWireCodecTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("com.bapis.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertEquals(ReplyWireSchema.DEFAULT, KmpWireSchemaProber.probeReplySchema(emptyLoader))
        assertEquals(DynWireSchema.DEFAULT, KmpWireSchemaProber.probeDynSchema(emptyLoader))
        assertEquals(ViewUniteWireSchema.DEFAULT, KmpWireSchemaProber.probeViewUniteSchema(emptyLoader))

        val shiftedLoader = object : ClassLoader(ProtobufWireCodecTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bapis.bilibili.app.viewunite.v1.ViewReply" -> FakeShiftedViewReply::class.java
                "com.bapis.bilibili.app.viewunite.v1.TabModule" -> FakeShiftedTabModule::class.java
                else -> if (name.startsWith("com.bapis.")) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
            }
        }
        val probedView = KmpWireSchemaProber.probeViewUniteSchema(shiftedLoader)
        assertEquals(12, probedView.tabField)
        assertEquals(15, probedView.ecodeField)
        assertEquals(3, probedView.tabModuleIntroductionField)

        val collidingLoader = object : ClassLoader(ProtobufWireCodecTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bapis.bilibili.app.viewunite.v1.ViewReply" -> FakeCollidingViewReply::class.java
                "com.bapis.bilibili.app.viewunite.v1.TabModule" -> FakeShiftedTabModule::class.java
                else -> if (name.startsWith("com.bapis.")) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
            }
        }
        assertEquals(ViewUniteWireSchema.DEFAULT, KmpWireSchemaProber.probeViewUniteSchema(collidingLoader))

        val zeroFieldLoader = object : ClassLoader(ProtobufWireCodecTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bapis.bilibili.app.dynamic.v2.DynamicList" -> FakeZeroFieldDynamicList::class.java
                else -> if (name.startsWith("com.bapis.")) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
            }
        }
        assertEquals(DynWireSchema.DEFAULT, KmpWireSchemaProber.probeDynSchema(zeroFieldLoader))
    }

    internal object FakeShiftedViewReply {
        const val VIEW_BASE_FIELD_NUMBER = 1
        const val ARC_FIELD_NUMBER = 2
        const val OWNER_FIELD_NUMBER = 4
        const val TAB_FIELD_NUMBER = 12
        const val SUPPLEMENT_FIELD_NUMBER = 6
        const val ECODE_FIELD_NUMBER = 15
        const val ECODE_CONFIG_FIELD_NUMBER = 9
    }

    internal object FakeShiftedTabModule {
        const val INTRODUCTION_FIELD_NUMBER = 3
    }

    internal object FakeCollidingViewReply {
        const val TAB_FIELD_NUMBER = 4
    }

    internal object FakeZeroFieldDynamicList {
        const val LIST_FIELD_NUMBER = 0
    }
}
