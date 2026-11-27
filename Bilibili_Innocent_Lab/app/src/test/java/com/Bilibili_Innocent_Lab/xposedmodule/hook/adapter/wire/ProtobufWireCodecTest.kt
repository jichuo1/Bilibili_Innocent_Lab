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
    fun `varint roundtrips boundary values including 10-byte negative int64`() {
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
    }

    @Test
    fun `varint rejects empty truncated overlong and 64bit-overflow inputs`() {
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
    // 2. Fixed32 / Fixed64 / StartGroup-EndGroup / Unknown preservation
    // -------------------------------------------------------------------------

    @Test
    fun `scans fixed32 fixed64 and nested groups while preserving unknown fields verbatim`() {
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
        val rewritten = ProtobufWireCodec.rewriteFields(msg) { slice ->
            if (slice.fieldNumber == 1) WireRewriteAction.Drop else WireRewriteAction.Keep
        }
        assertNotSame(msg, rewritten)
        val expectedWithoutField1 = msg.copyOfRange(slices[0].rawEnd, msg.size)
        assertArrayEquals(expectedWithoutField1, rewritten)
    }

    // -------------------------------------------------------------------------
    // 3. Copy-on-change identity & actions
    // -------------------------------------------------------------------------

    @Test
    fun `copy on change returns exact same array identity when untouched and supports all rewrite actions`() {
        val original = buildMessage {
            writeVarintField(1, 7L)
            writeStringField(2, "keep-me")
            writeStringField(3, "replace-raw")
            writeStringField(4, "replace-delimited")
            writeFixed32Field(99, 0xCAFEBABE.toInt())
        }

        val untouched = ProtobufWireCodec.rewriteFields(original) { WireRewriteAction.Keep }
        assertSame(original, untouched)

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
    // 4. Malformed / truncated / oversized / deeply-nested rejection + UTF-8 + packed
    // -------------------------------------------------------------------------

    @Test
    fun `rejects truncated oversized reserved wire types unbalanced and overly deep groups without panic`() {
        // Truncated length-delimited payload
        val truncatedDelimited = byteArrayOf(((2 shl 3) or 2).toByte(), 10, 0x01, 0x02)
        assertNull(ProtobufWireCodec.scanTopLevel(truncatedDelimited))
        assertNull(ProtobufWireCodec.rewriteFieldsOrNull(truncatedDelimited) { WireRewriteAction.Drop })
        // rewriteFields safely falls back to returning original reference
        assertSame(truncatedDelimited, ProtobufWireCodec.rewriteFields(truncatedDelimited) { WireRewriteAction.Drop })

        // Huge overflowing length varint (0x7FFFFFFF) -> never attempts huge allocation
        val giantLen = buildMessage {
            writeVarint(ProtobufWireCodec.makeTag(1, ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED))
            writeVarint(Int.MAX_VALUE.toLong())
            write(byteArrayOf(0x01), 0, 1)
        }
        assertNull(ProtobufWireCodec.scanTopLevel(giantLen))

        // Negative length varint (-1L)
        val negativeLen = buildMessage {
            writeVarint(ProtobufWireCodec.makeTag(1, ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED))
            writeVarint(-1L)
        }
        assertNull(ProtobufWireCodec.scanTopLevel(negativeLen))

        // Invalid field number 0
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(0x00, 0x01)))

        // Reserved wire types 6 and 7
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 6).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 7).toByte())))

        // Truncated fixed32 / fixed64
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 5).toByte(), 1, 2, 3)))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 1).toByte(), 1, 2, 3, 4, 5, 6, 7)))

        // Unmatched END_GROUP at top level, mismatched END_GROUP field number, unclosed START_GROUP
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 4).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 3).toByte(), ((2 shl 3) or 4).toByte())))
        assertNull(ProtobufWireCodec.scanTopLevel(byteArrayOf(((1 shl 3) or 3).toByte(), ((1 shl 3) or 0).toByte(), 0x01)))

        // Group nesting exactly MAX_GROUP_DEPTH (32) succeeds; MAX_GROUP_DEPTH + 1 fails safely
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

        // Exceeds custom maxBytes limit
        val validSmall = buildMessage { writeStringField(1, "hello") }
        assertNull(ProtobufWireCodec.scanTopLevel(validSmall, maxBytes = validSmall.size - 1))
    }

    @Test
    fun `validates strict utf8 and packed varints`() {
        val validUtf8 = "你好Bilibili🚀".toByteArray(Charsets.UTF_8)
        assertEquals("你好Bilibili🚀", ProtobufWireCodec.decodeUtf8OrNull(validUtf8))
        assertEquals("", ProtobufWireCodec.decodeUtf8OrNull(ByteArray(0)))

        // Invalid UTF-8 sequences
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xFF.toByte())))
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xC3.toByte()))) // truncated 2-byte
        assertNull(ProtobufWireCodec.decodeUtf8OrNull(byteArrayOf(0xC0.toByte(), 0xAF.toByte()))) // overlong

        // Packed varints
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
    // 5. KmpRawWireAdapters: ReplyMainList / DynList / ViewUnite
    // -------------------------------------------------------------------------

    @Test
    fun `reply main list wire adapter filters main top pinned and nested replies while preserving unknown fields`() {
        fun encodeReply(
            id: Long,
            mid: Long,
            message: String,
            name: String,
            level: Int,
            children: List<ByteArray> = emptyList()
        ): ByteArray = buildMessage {
            children.forEach { writeLengthDelimited(1, it) }
            writeVarintField(2, id)
            writeVarintField(5, mid)
            writeLengthDelimited(
                12,
                buildMessage { writeStringField(1, message) }
            )
            writeLengthDelimited(
                19,
                buildMessage {
                    writeLengthDelimited(
                        1,
                        buildMessage {
                            writeVarintField(1, mid)
                            writeStringField(2, name)
                            writeVarintField(6, level.toLong())
                        }
                    )
                }
            )
        }

        val childSpam = encodeReply(id = 101L, mid = 999L, message = "spam_child_link", name = "Bot", level = 0)
        val childClean = encodeReply(id = 102L, mid = 222L, message = "nice nested comment", name = "Alice", level = 5)
        val mainKeep = encodeReply(
            id = 1L,
            mid = 111L,
            message = "helpful discussion",
            name = "Bob",
            level = 6,
            children = listOf(childSpam, childClean)
        )
        val mainSpam = encodeReply(id = 2L, mid = 888L, message = "buy_promo_now", name = "AdAccount", level = 1)
        val topSpam = encodeReply(id = 3L, mid = 777L, message = "pinned_ad", name = "Sponsor", level = 4)
        val upTopClean = encodeReply(id = 4L, mid = 333L, message = "author pinned note", name = "UP", level = 6)

        val unknownCursor = buildMessage {
            writeStringField(1, "cursor_next_page_token")
            writeVarintField(2, 1L)
        }
        val mainListBytes = buildMessage {
            writeLengthDelimited(2, mainKeep)
            writeLengthDelimited(2, mainSpam)
            writeLengthDelimited(14, topSpam)
            writeLengthDelimited(3, upTopClean)
            writeLengthDelimited(77, unknownCursor) // unknown field must remain intact
        }

        // No-op filter returns exact same ByteArray identity
        val untouched = ReplyMainListWireAdapter.filterReplies(mainListBytes) { false }
        assertSame(mainListBytes, untouched)

        // Filter by keyword and low level
        val filtered = ReplyMainListWireAdapter.filterReplies(mainListBytes) { signal ->
            val msg = signal.message.orEmpty()
            msg.contains("promo") || msg.contains("pinned_ad") || msg.contains("spam_child") || (signal.level ?: 6) < 1
        }
        assertNotSame(mainListBytes, filtered)

        val remainingReplies = ReplyMainListWireAdapter.extractTopLevelReplies(filtered)
        assertEquals(2, remainingReplies.size)
        assertEquals(1L, remainingReplies[0].id)
        assertEquals("Bob", remainingReplies[0].authorName)
        assertEquals(6, remainingReplies[0].level)
        assertEquals(1, remainingReplies[0].nestedReplyCount) // childSpam removed, childClean kept!
        assertEquals(4L, remainingReplies[1].id)

        // Verify unknown field 77 preserved verbatim
        val unknownSlice = ProtobufWireCodec.scanTopLevel(filtered)!!.single { it.fieldNumber == 77 }
        assertArrayEquals(unknownCursor, filtered.copyOfRange(unknownSlice.payloadStart, unknownSlice.payloadEnd))
    }

    @Test
    fun `dyn list wire adapter filters dynamic cards while preserving cursor topic up_list and identity`() {
        fun encodeDynItem(
            dynId: String,
            uid: Long,
            author: String,
            text: String,
            cardType: Long = 2L
        ): ByteArray = buildMessage {
            writeVarintField(1, cardType)
            writeVarintField(2, 1L)
            writeLengthDelimited(
                3,
                buildMessage {
                    writeVarintField(1, 2L)
                    writeLengthDelimited(
                        2,
                        buildMessage {
                            writeLengthDelimited(
                                1,
                                buildMessage {
                                    writeVarintField(1, uid)
                                    writeStringField(2, author)
                                }
                            )
                        }
                    )
                    writeLengthDelimited(
                        4,
                        buildMessage {
                            writeLengthDelimited(
                                1,
                                buildMessage { writeStringField(1, text) }
                            )
                        }
                    )
                }
            )
            writeLengthDelimited(
                4,
                buildMessage {
                    writeStringField(1, dynId)
                    writeStringField(5, author)
                    writeVarintField(6, uid)
                }
            )
        }

        val itemKeep = encodeDynItem("dyn_1", 1001L, "TechUP", "Kernel deep dive")
        val itemAd = encodeDynItem("dyn_2", 9001L, "MallBot", "Limited flash_sale link", cardType = 10L)
        val upListPayload = buildMessage { writeStringField(1, "up_list_preserved") }
        val topicPayload = buildMessage { writeStringField(1, "topic_preserved") }
        val cursorPayload = "cursor_v2_opaque".toByteArray(Charsets.UTF_8)

        val dynReply = buildMessage {
            writeLengthDelimited(
                1,
                buildMessage {
                    writeLengthDelimited(1, itemKeep)
                    writeLengthDelimited(1, itemAd)
                    writeLengthDelimited(4, cursorPayload) // unknown/pagination inside DynamicList
                }
            )
            writeLengthDelimited(2, upListPayload)
            writeLengthDelimited(3, topicPayload)
            writeFixed64Field(88, 9876543210L)
        }

        val untouched = DynListWireAdapter.filterDynamicList(dynReply) { false }
        assertSame(dynReply, untouched)

        val filtered = DynListWireAdapter.filterDynamicList(dynReply) { signal ->
            signal.texts.any { it.contains("flash_sale") } || signal.cardType == 10L
        }
        assertNotSame(dynReply, filtered)

        val extracted = DynListWireAdapter.extractDynamicItems(filtered)
        assertEquals(1, extracted.size)
        assertEquals("dyn_1", extracted[0].dynamicId)
        assertEquals("TechUP", extracted[0].authorName)
        assertEquals(1001L, extracted[0].authorUid)
        assertEquals(listOf("Kernel deep dive"), extracted[0].texts)

        // Top-level up_list, topic, unknown fixed64 & inner cursor field 4 all preserved verbatim
        val topSlices = ProtobufWireCodec.scanTopLevel(filtered)!!
        assertEquals(listOf(1, 2, 3, 88), topSlices.map { it.fieldNumber })
        assertArrayEquals(upListPayload, filtered.copyOfRange(topSlices[1].payloadStart, topSlices[1].payloadEnd))
        assertArrayEquals(topicPayload, filtered.copyOfRange(topSlices[2].payloadStart, topSlices[2].payloadEnd))
        assertEquals(9876543210L, ProtobufWireCodec.readFixed64Le(filtered, topSlices[3].payloadStart))
        val innerListSlices = ProtobufWireCodec.scanTopLevel(
            filtered,
            topSlices[0].payloadStart,
            topSlices[0].payloadLength
        )!!
        assertEquals(listOf(1, 4), innerListSlices.map { it.fieldNumber })
        assertArrayEquals(cursorPayload, filtered.copyOfRange(innerListSlices[1].payloadStart, innerListSlices[1].payloadEnd))
    }

    @Test
    fun `view unite wire adapter extracts summary filters modules and rewrites fields with identity`() {
        fun encodeModule(type: Int, title: String): ByteArray = buildMessage {
            writeVarintField(1, type.toLong())
            writeStringField(9, title)
        }

        val viewReply = buildMessage {
            writeLengthDelimited(2, buildMessage { writeStringField(1, "BV1test") }) // arc
            writeLengthDelimited(3, buildMessage { writeVarintField(1, 42L) }) // owner
            writeLengthDelimited(
                4,
                buildMessage {
                    writeLengthDelimited(
                        1,
                        buildMessage {
                            writeLengthDelimited(
                                4,
                                buildMessage {
                                    writeLengthDelimited(2, encodeModule(5, "ugc_intro"))
                                    writeLengthDelimited(2, encodeModule(22, "game_promo"))
                                    writeLengthDelimited(2, encodeModule(10, "relates"))
                                    writeStringField(55, "intro_unknown_keep")
                                }
                            )
                        }
                    )
                }
            )
            writeVarintField(5, 1L) // ecode
            writeLengthDelimited(8, buildMessage { writeStringField(1, "supplement") })
            writeFixed32Field(102, 0x0BADF00D)
        }

        val summary = ViewUniteWireAdapter.extractSummary(viewReply)
        assertNotNull(summary)
        assertTrue(summary!!.hasArc)
        assertTrue(summary.hasOwner)
        assertTrue(summary.hasTab)
        assertTrue(summary.hasSupplement)
        assertEquals(1L, summary.ecode)
        assertEquals(listOf(5, 22, 10), summary.tabModuleTypes)
        assertFalse(summary.hasPlayVodInfo)

        // Untouched tab filter preserves exact ByteArray identity
        assertSame(viewReply, ViewUniteWireAdapter.filterTabModules(viewReply) { _, _ -> false })

        // Drop module type 22 (game_promo)
        val filteredModules = ViewUniteWireAdapter.filterTabModules(viewReply) { modType, _ ->
            modType == 22
        }
        assertNotSame(viewReply, filteredModules)
        val afterSummary = ViewUniteWireAdapter.extractSummary(filteredModules)!!
        assertEquals(listOf(5, 10), afterSummary.tabModuleTypes)
        assertTrue(afterSummary.hasArc)
        assertEquals(1L, afterSummary.ecode)

        // Strip ecode(5) and supplement(8) while keeping unknown field 102
        assertSame(filteredModules, ViewUniteWireAdapter.stripTopLevelFields(filteredModules, setOf(999)))
        val stripped = ViewUniteWireAdapter.stripTopLevelFields(filteredModules, setOf(5, 8))
        val strippedSummary = ViewUniteWireAdapter.extractSummary(stripped)!!
        assertNull(strippedSummary.ecode)
        assertFalse(strippedSummary.hasSupplement)
        assertTrue(102 in strippedSummary.topLevelFieldNumbers)
        val unknownSlice = ProtobufWireCodec.scanTopLevel(stripped)!!.single { it.fieldNumber == 102 }
        assertEquals(0x0BADF00D, ProtobufWireCodec.readFixed32Le(stripped, unknownSlice.payloadStart))
    }
}
