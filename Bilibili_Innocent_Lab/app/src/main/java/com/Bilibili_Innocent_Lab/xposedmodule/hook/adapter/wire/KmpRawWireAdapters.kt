package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.wire

/**
 * 零 `bapis` 依赖的纯 KMP Wire 层协议适配器集合，基于 [ProtobufWireCodec] 构建：
 * - [ReplyMainListWireAdapter]：评论区 `MainListReply`/`DetailListReply`/`DialogListReply`
 * - [DynListWireAdapter]：动态流 `DynAllReply`/`DynVideoReply`
 * - [ViewUniteWireAdapter]：详情统一协议 `ViewUnite` / `PlayViewUnite`
 *
 * 所有适配器均保持 copy-on-change 语义（未修改时返回原 `ByteArray` 引用 `===`）并逐字节保留未知字段。
 */

// -----------------------------------------------------------------------------
// 1. ReplyMainListWireAdapter
// -----------------------------------------------------------------------------

internal data class ReplyWireSchema(
    val replyListFields: Set<Int> = DEFAULT_REPLY_LIST_FIELDS,
    val singleReplyFields: Set<Int> = DEFAULT_SINGLE_REPLY_FIELDS,
    val nestedRepliesField: Int = 1,
    val replyIdField: Int = 2,
    val replyOidField: Int = 3,
    val replyTypeField: Int = 4,
    val replyMidField: Int = 5,
    val replyMemberField: Int = 11,
    val replyContentField: Int = 12,
    val replyMemberV2Field: Int = 19,
    val contentMessageField: Int = 1,
    val memberMidField: Int = 1,
    val memberNameField: Int = 2,
    val memberLevelField: Int = 5,
    val memberLevelInfoField: Int = 6,
    val levelInfoCurrentField: Int = 1,
    val memberV2BasicField: Int = 1,
    val memberV2BasicMidField: Int = 1,
    val memberV2BasicNameField: Int = 2,
    val memberV2BasicLevelField: Int = 6
) {
    companion object {
        val DEFAULT_REPLY_LIST_FIELDS = setOf(2, 14) // replies(2), top_replies(14)
        val DEFAULT_SINGLE_REPLY_FIELDS = setOf(3, 4, 5) // up_top(3), admin_top(4), vote_top(5)
        val DEFAULT = ReplyWireSchema()
    }
}

internal data class ReplyWireSignal(
    val id: Long?,
    val oid: Long?,
    val type: Long?,
    val mid: Long?,
    val message: String?,
    val authorName: String?,
    val level: Int?,
    val nestedReplyCount: Int
)

internal object ReplyMainListWireAdapter {

    fun extractTopLevelReplies(
        bytes: ByteArray,
        schema: ReplyWireSchema = ReplyWireSchema.DEFAULT
    ): List<ReplyWireSignal> {
        val result = ArrayList<ReplyWireSignal>()
        ProtobufWireCodec.forEachField(bytes) { slice ->
            if (slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED &&
                (slice.fieldNumber in schema.replyListFields || slice.fieldNumber in schema.singleReplyFields)
            ) {
                extractReplySignal(bytes, slice.payloadStart, slice.payloadEnd, schema)?.let(result::add)
            }
            true
        }
        return result
    }

    fun extractReplySignal(
        bytes: ByteArray,
        start: Int = 0,
        end: Int = bytes.size,
        schema: ReplyWireSchema = ReplyWireSchema.DEFAULT
    ): ReplyWireSignal? {
        if (start < 0 || end < start || end > bytes.size) return null
        var id: Long? = null
        var oid: Long? = null
        var type: Long? = null
        var mid: Long? = null
        var message: String? = null
        var memberName: String? = null
        var memberLevel: Int? = null
        var memberMid: Long? = null
        var memberV2Name: String? = null
        var memberV2Level: Int? = null
        var memberV2Mid: Long? = null
        var nestedCount = 0

        val ok = ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            when {
                slice.fieldNumber == schema.replyIdField && slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    id = slice.varintValue
                slice.fieldNumber == schema.replyOidField && slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    oid = slice.varintValue
                slice.fieldNumber == schema.replyTypeField && slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    type = slice.varintValue
                slice.fieldNumber == schema.replyMidField && slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    mid = slice.varintValue
                slice.fieldNumber == schema.nestedRepliesField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    nestedCount++
                slice.fieldNumber == schema.replyContentField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    extractContentMessage(bytes, slice.payloadStart, slice.payloadEnd, schema)?.let {
                        message = it
                    }
                }
                slice.fieldNumber == schema.replyMemberField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    val info = extractMember(bytes, slice.payloadStart, slice.payloadEnd, schema)
                    memberMid = info.mid ?: memberMid
                    memberName = info.name ?: memberName
                    memberLevel = info.level ?: memberLevel
                }
                slice.fieldNumber == schema.replyMemberV2Field &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    val info = extractMemberV2(bytes, slice.payloadStart, slice.payloadEnd, schema)
                    memberV2Mid = info.mid ?: memberV2Mid
                    memberV2Name = info.name ?: memberV2Name
                    memberV2Level = info.level ?: memberV2Level
                }
            }
            true
        }
        if (!ok) return null
        return ReplyWireSignal(
            id = id,
            oid = oid,
            type = type,
            mid = mid ?: memberV2Mid ?: memberMid,
            message = message,
            authorName = memberV2Name ?: memberName,
            level = memberV2Level ?: memberLevel,
            nestedReplyCount = nestedCount
        )
    }

    /**
     * 基于纯 wire 扫描与 copy-on-change 过滤主评论列表、置顶单条评论与嵌套子评论。
     * 没有任何条目被过滤时直接返回原始 [bytes] 引用（`===`）。
     */
    fun filterReplies(
        bytes: ByteArray,
        schema: ReplyWireSchema = ReplyWireSchema.DEFAULT,
        filterNested: Boolean = true,
        maxDepth: Int = 3,
        shouldDrop: (ReplyWireSignal) -> Boolean
    ): ByteArray {
        return ProtobufWireCodec.rewriteFields(bytes) { slice ->
            if (slice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED) {
                return@rewriteFields WireRewriteAction.Keep
            }
            val isTarget = slice.fieldNumber in schema.replyListFields ||
                slice.fieldNumber in schema.singleReplyFields
            if (!isTarget) {
                return@rewriteFields WireRewriteAction.Keep
            }
            val signal = extractReplySignal(bytes, slice.payloadStart, slice.payloadEnd, schema)
                ?: return@rewriteFields WireRewriteAction.Keep
            if (shouldDrop(signal)) {
                return@rewriteFields WireRewriteAction.Drop
            }
            if (!filterNested || maxDepth <= 0 || signal.nestedReplyCount == 0) {
                return@rewriteFields WireRewriteAction.Keep
            }
            val updatedReplyPayload = filterNestedRepliesInRange(
                bytes = bytes,
                start = slice.payloadStart,
                end = slice.payloadEnd,
                schema = schema,
                remainingDepth = maxDepth - 1,
                shouldDrop = shouldDrop
            )
            if (updatedReplyPayload == null) {
                WireRewriteAction.Keep
            } else {
                WireRewriteAction.ReplaceLengthDelimited(slice.fieldNumber, updatedReplyPayload)
            }
        }
    }

    private fun filterNestedRepliesInRange(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: ReplyWireSchema,
        remainingDepth: Int,
        shouldDrop: (ReplyWireSignal) -> Boolean
    ): ByteArray? {
        return ProtobufWireCodec.rewriteSubMessageOrNullIfKeep(
            bytes = bytes,
            offset = start,
            length = end - start
        ) { childSlice ->
            if (childSlice.fieldNumber != schema.nestedRepliesField ||
                childSlice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
            }
            val childSignal = extractReplySignal(
                bytes,
                childSlice.payloadStart,
                childSlice.payloadEnd,
                schema
            ) ?: return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
            if (shouldDrop(childSignal)) {
                return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Drop
            }
            if (remainingDepth <= 0 || childSignal.nestedReplyCount == 0) {
                return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
            }
            val deeper = filterNestedRepliesInRange(
                bytes = bytes,
                start = childSlice.payloadStart,
                end = childSlice.payloadEnd,
                schema = schema,
                remainingDepth = remainingDepth - 1,
                shouldDrop = shouldDrop
            )
            if (deeper == null) {
                WireRewriteAction.Keep
            } else {
                WireRewriteAction.ReplaceLengthDelimited(childSlice.fieldNumber, deeper)
            }
        }
    }

    private data class MemberExtracted(val mid: Long? = null, val name: String? = null, val level: Int? = null)

    private fun extractContentMessage(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: ReplyWireSchema
    ): String? {
        var message: String? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            if (slice.fieldNumber == schema.contentMessageField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                message = ProtobufWireCodec.decodeUtf8OrNull(bytes, slice.payloadStart, slice.payloadEnd)
            }
            true
        }
        return message
    }

    private fun extractMember(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: ReplyWireSchema
    ): MemberExtracted {
        var mid: Long? = null
        var name: String? = null
        var level: Int? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            when {
                slice.fieldNumber == schema.memberMidField -> mid = decodeLongField(bytes, slice) ?: mid
                slice.fieldNumber == schema.memberNameField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    name = ProtobufWireCodec.decodeUtf8OrNull(bytes, slice.payloadStart, slice.payloadEnd) ?: name
                slice.fieldNumber == schema.memberLevelField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    level = slice.varintValue.toInt()
                slice.fieldNumber == schema.memberLevelInfoField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    extractLevelInfo(bytes, slice.payloadStart, slice.payloadEnd, schema)?.let {
                        if (level == null) level = it
                    }
                }
            }
            true
        }
        return MemberExtracted(mid, name, level)
    }

    private fun extractLevelInfo(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: ReplyWireSchema
    ): Int? {
        var currentLevel: Int? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            if (slice.fieldNumber == schema.levelInfoCurrentField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT
            ) {
                currentLevel = slice.varintValue.toInt()
            }
            true
        }
        return currentLevel
    }

    private fun extractMemberV2(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: ReplyWireSchema
    ): MemberExtracted {
        var extracted = MemberExtracted()
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            if (slice.fieldNumber == schema.memberV2BasicField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                var mid: Long? = null
                var name: String? = null
                var level: Int? = null
                ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { basicSlice ->
                    when {
                        basicSlice.fieldNumber == schema.memberV2BasicMidField ->
                            mid = decodeLongField(bytes, basicSlice) ?: mid
                        basicSlice.fieldNumber == schema.memberV2BasicNameField &&
                            basicSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                            name = ProtobufWireCodec.decodeUtf8OrNull(
                                bytes,
                                basicSlice.payloadStart,
                                basicSlice.payloadEnd
                            ) ?: name
                        basicSlice.fieldNumber == schema.memberV2BasicLevelField &&
                            basicSlice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                            level = basicSlice.varintValue.toInt()
                    }
                    true
                }
                extracted = MemberExtracted(mid, name, level)
            }
            true
        }
        return extracted
    }

    private fun decodeLongField(bytes: ByteArray, slice: WireFieldSlice): Long? = when (slice.wireType) {
        ProtobufWireCodec.WIRE_TYPE_VARINT -> slice.varintValue
        ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
            ProtobufWireCodec.decodeUtf8OrNull(bytes, slice.payloadStart, slice.payloadEnd)?.toLongOrNull()
        else -> null
    }
}

// -----------------------------------------------------------------------------
// 2. DynListWireAdapter (DynAll / DynVideo)
// -----------------------------------------------------------------------------

internal data class DynWireSchema(
    val dynamicListField: Int = 1,
    val itemListField: Int = 1,
    val directItemFields: Set<Int> = emptySet(),
    val itemCardTypeField: Int = 1,
    val itemItemTypeField: Int = 2,
    val itemModulesField: Int = 3,
    val itemExtendField: Int = 4,
    val extendDynIdStrField: Int = 1,
    val extendOrigDescField: Int = 3,
    val extendOrigNameField: Int = 5,
    val extendUidField: Int = 6,
    val moduleTypeField: Int = 1,
    val moduleAuthorField: Int = 2,
    val moduleDynamicField: Int = 3,
    val moduleDescField: Int = 4,
    val descriptionTextField: Int = 1,
    val moduleDescTextField: Int = 1,
    val authorUserField: Int = 1,
    val authorMidField: Int = 1,
    val authorNameField: Int = 2
) {
    companion object {
        val DEFAULT = DynWireSchema()
    }
}

internal data class DynWireItemSignal(
    val cardType: Long?,
    val itemType: Long?,
    val dynamicId: String?,
    val authorName: String?,
    val authorUid: Long?,
    val moduleTypes: List<Int>,
    val texts: List<String>
)

internal object DynListWireAdapter {

    fun extractDynamicItems(
        bytes: ByteArray,
        schema: DynWireSchema = DynWireSchema.DEFAULT
    ): List<DynWireItemSignal> {
        val items = ArrayList<DynWireItemSignal>()
        ProtobufWireCodec.forEachField(bytes) { slice ->
            if (slice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED) return@forEachField true
            when {
                slice.fieldNumber == schema.dynamicListField -> {
                    ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { listSlice ->
                        if (listSlice.fieldNumber == schema.itemListField &&
                            listSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            extractItemSignal(bytes, listSlice.payloadStart, listSlice.payloadEnd, schema)
                                ?.let(items::add)
                        }
                        true
                    }
                }
                slice.fieldNumber in schema.directItemFields -> {
                    extractItemSignal(bytes, slice.payloadStart, slice.payloadEnd, schema)?.let(items::add)
                }
            }
            true
        }
        return items
    }

    fun extractItemSignal(
        bytes: ByteArray,
        start: Int = 0,
        end: Int = bytes.size,
        schema: DynWireSchema = DynWireSchema.DEFAULT
    ): DynWireItemSignal? {
        if (start < 0 || end < start || end > bytes.size) return null
        var cardType: Long? = null
        var itemType: Long? = null
        var dynamicId: String? = null
        var authorName: String? = null
        var authorUid: Long? = null
        val moduleTypes = ArrayList<Int>()
        val texts = ArrayList<String>()

        val ok = ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            when {
                slice.fieldNumber == schema.itemCardTypeField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    cardType = slice.varintValue
                slice.fieldNumber == schema.itemItemTypeField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    itemType = slice.varintValue
                slice.fieldNumber == schema.itemModulesField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    extractModule(bytes, slice.payloadStart, slice.payloadEnd, schema, moduleTypes, texts) { name, uid ->
                        if (authorName == null && name != null) authorName = name
                        if (authorUid == null && uid != null) authorUid = uid
                    }
                }
                slice.fieldNumber == schema.itemExtendField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { extSlice ->
                        when {
                            extSlice.fieldNumber == schema.extendDynIdStrField &&
                                extSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                                dynamicId = ProtobufWireCodec.decodeUtf8OrNull(
                                    bytes,
                                    extSlice.payloadStart,
                                    extSlice.payloadEnd
                                ) ?: dynamicId
                            }
                            extSlice.fieldNumber == schema.extendOrigNameField &&
                                extSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                                authorName = ProtobufWireCodec.decodeUtf8OrNull(
                                    bytes,
                                    extSlice.payloadStart,
                                    extSlice.payloadEnd
                                ) ?: authorName
                            }
                            extSlice.fieldNumber == schema.extendUidField -> {
                                if (extSlice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT) {
                                    authorUid = extSlice.varintValue
                                } else if (extSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED) {
                                    ProtobufWireCodec.decodeUtf8OrNull(
                                        bytes,
                                        extSlice.payloadStart,
                                        extSlice.payloadEnd
                                    )?.toLongOrNull()?.let { authorUid = it }
                                }
                            }
                            extSlice.fieldNumber == schema.extendOrigDescField &&
                                extSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                                extractDescriptionText(bytes, extSlice.payloadStart, extSlice.payloadEnd, schema)
                                    ?.let(texts::add)
                            }
                        }
                        true
                    }
                }
            }
            true
        }
        if (!ok) return null
        return DynWireItemSignal(
            cardType = cardType,
            itemType = itemType,
            dynamicId = dynamicId,
            authorName = authorName,
            authorUid = authorUid,
            moduleTypes = moduleTypes,
            texts = texts
        )
    }

    /**
     * 过滤动态响应中的重复 `DynamicItem`，原样保留 `up_list` / `topic` / `cursor` 等全部未知字段；
     * 若无条目被丢弃，返回原始 [bytes] 引用（`===`）。
     */
    fun filterDynamicList(
        bytes: ByteArray,
        schema: DynWireSchema = DynWireSchema.DEFAULT,
        shouldDrop: (DynWireItemSignal) -> Boolean
    ): ByteArray {
        return ProtobufWireCodec.rewriteFields(bytes) { slice ->
            if (slice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED) {
                return@rewriteFields WireRewriteAction.Keep
            }
            when {
                slice.fieldNumber == schema.dynamicListField -> {
                    val updatedList = ProtobufWireCodec.rewriteSubMessageOrNullIfKeep(
                        bytes = bytes,
                        offset = slice.payloadStart,
                        length = slice.payloadLength
                    ) { listSlice ->
                        if (listSlice.fieldNumber != schema.itemListField ||
                            listSlice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
                        }
                        val signal = extractItemSignal(
                            bytes,
                            listSlice.payloadStart,
                            listSlice.payloadEnd,
                            schema
                        ) ?: return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
                        if (shouldDrop(signal)) WireRewriteAction.Drop else WireRewriteAction.Keep
                    }
                    if (updatedList == null) {
                        WireRewriteAction.Keep
                    } else {
                        WireRewriteAction.ReplaceLengthDelimited(slice.fieldNumber, updatedList)
                    }
                }
                slice.fieldNumber in schema.directItemFields -> {
                    val signal = extractItemSignal(bytes, slice.payloadStart, slice.payloadEnd, schema)
                        ?: return@rewriteFields WireRewriteAction.Keep
                    if (shouldDrop(signal)) WireRewriteAction.Drop else WireRewriteAction.Keep
                }
                else -> WireRewriteAction.Keep
            }
        }
    }

    private fun extractModule(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: DynWireSchema,
        moduleTypes: MutableList<Int>,
        texts: MutableList<String>,
        onAuthor: (name: String?, uid: Long?) -> Unit
    ) {
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            when {
                slice.fieldNumber == schema.moduleTypeField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    moduleTypes.add(slice.varintValue.toInt())
                slice.fieldNumber == schema.moduleAuthorField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    extractAuthor(bytes, slice.payloadStart, slice.payloadEnd, schema, onAuthor)
                }
                slice.fieldNumber == schema.moduleDescField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { descContainer ->
                        if (descContainer.fieldNumber == schema.moduleDescTextField &&
                            descContainer.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            extractDescriptionText(
                                bytes,
                                descContainer.payloadStart,
                                descContainer.payloadEnd,
                                schema
                            )?.let(texts::add)
                        }
                        true
                    }
                }
                slice.fieldNumber == schema.moduleDynamicField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { dynSlice ->
                        if (dynSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED) {
                            extractDescriptionText(
                                bytes,
                                dynSlice.payloadStart,
                                dynSlice.payloadEnd,
                                schema
                            )?.let(texts::add)
                        }
                        true
                    }
                }
            }
            true
        }
    }

    private fun extractAuthor(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: DynWireSchema,
        onAuthor: (name: String?, uid: Long?) -> Unit
    ) {
        var name: String? = null
        var uid: Long? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            if (slice.fieldNumber == schema.authorUserField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { userSlice ->
                    when {
                        userSlice.fieldNumber == schema.authorMidField &&
                            userSlice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                            uid = userSlice.varintValue
                        userSlice.fieldNumber == schema.authorNameField &&
                            userSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                            name = ProtobufWireCodec.decodeUtf8OrNull(
                                bytes,
                                userSlice.payloadStart,
                                userSlice.payloadEnd
                            ) ?: name
                    }
                    true
                }
            }
            true
        }
        onAuthor(name, uid)
    }

    private fun extractDescriptionText(
        bytes: ByteArray,
        start: Int,
        end: Int,
        schema: DynWireSchema
    ): String? {
        var text: String? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            if (slice.fieldNumber == schema.descriptionTextField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                text = ProtobufWireCodec.decodeUtf8OrNull(bytes, slice.payloadStart, slice.payloadEnd)
            }
            true
        }
        return text
    }
}

// -----------------------------------------------------------------------------
// 3. ViewUniteWireAdapter (ViewUnite / PlayViewUnite)
// -----------------------------------------------------------------------------

internal data class ViewUniteWireSchema(
    val viewBaseField: Int = 1,
    val arcField: Int = 2,
    val ownerField: Int = 3,
    val tabField: Int = 4,
    val ecodeField: Int = 5,
    val ecodeConfigField: Int = 6,
    val supplementField: Int = 8,
    val tabModuleListField: Int = 1,
    val tabModuleIntroductionField: Int = 4,
    val introductionModulesField: Int = 2,
    val moduleTypeField: Int = 1,
    val playVodInfoField: Int = 1,
    val playSupplementField: Int = 5
) {
    companion object {
        val DEFAULT = ViewUniteWireSchema()
    }
}

internal data class ViewUniteWireSummary(
    val topLevelFieldNumbers: Set<Int>,
    val hasViewBase: Boolean,
    val hasArc: Boolean,
    val hasOwner: Boolean,
    val hasTab: Boolean,
    val hasSupplement: Boolean,
    val hasPlayVodInfo: Boolean,
    val ecode: Long?,
    val tabModuleTypes: List<Int>
)

internal object ViewUniteWireAdapter {

    fun extractSummary(
        bytes: ByteArray,
        schema: ViewUniteWireSchema = ViewUniteWireSchema.DEFAULT
    ): ViewUniteWireSummary? {
        val fieldNumbers = LinkedHashSet<Int>()
        var hasViewBase = false
        var hasArc = false
        var hasOwner = false
        var hasTab = false
        var hasSupplement = false
        var hasPlayVodInfo = false
        var ecode: Long? = null
        val moduleTypes = ArrayList<Int>()

        val ok = ProtobufWireCodec.forEachField(bytes) { slice ->
            fieldNumbers.add(slice.fieldNumber)
            when {
                slice.fieldNumber == schema.viewBaseField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    hasViewBase = true
                slice.fieldNumber == schema.arcField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    hasArc = true
                slice.fieldNumber == schema.ownerField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    hasOwner = true
                slice.fieldNumber == schema.tabField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
                    hasTab = true
                    collectTabModuleTypes(bytes, slice.payloadStart, slice.payloadLength, schema, moduleTypes)
                }
                slice.fieldNumber == schema.ecodeField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT ->
                    ecode = slice.varintValue
                slice.fieldNumber == schema.supplementField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED ->
                    hasSupplement = true
            }
            if (slice.fieldNumber == schema.playVodInfoField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                hasPlayVodInfo = true
            }
            if (slice.fieldNumber == schema.playSupplementField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                hasSupplement = true
            }
            true
        }
        if (!ok) return null
        return ViewUniteWireSummary(
            topLevelFieldNumbers = fieldNumbers,
            hasViewBase = hasViewBase,
            hasArc = hasArc,
            hasOwner = hasOwner,
            hasTab = hasTab,
            hasSupplement = hasSupplement,
            hasPlayVodInfo = hasPlayVodInfo,
            ecode = ecode,
            tabModuleTypes = moduleTypes
        )
    }

    /**
     * 对 `ViewReply -> Tab -> TabModule -> IntroductionTab -> Module` 执行纯 wire 过滤，
     * 未丢弃任何模块时返回原始 [bytes] 引用（`===`），其余字段（含未知字段）逐字节保留。
     */
    fun filterTabModules(
        bytes: ByteArray,
        schema: ViewUniteWireSchema = ViewUniteWireSchema.DEFAULT,
        shouldDropModule: (moduleType: Int, moduleSlice: WireFieldSlice) -> Boolean
    ): ByteArray {
        return ProtobufWireCodec.rewriteFields(bytes) { slice ->
            if (slice.fieldNumber != schema.tabField ||
                slice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                return@rewriteFields WireRewriteAction.Keep
            }
            val updatedTab = ProtobufWireCodec.rewriteSubMessageOrNullIfKeep(
                bytes = bytes,
                offset = slice.payloadStart,
                length = slice.payloadLength
            ) { tabSlice ->
                if (tabSlice.fieldNumber != schema.tabModuleListField ||
                    tabSlice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                ) {
                    return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
                }
                val updatedTabModule = ProtobufWireCodec.rewriteSubMessageOrNullIfKeep(
                    bytes = bytes,
                    offset = tabSlice.payloadStart,
                    length = tabSlice.payloadLength
                ) { tabModSlice ->
                    if (tabModSlice.fieldNumber != schema.tabModuleIntroductionField ||
                        tabModSlice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                    ) {
                        return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
                    }
                    val updatedIntro = ProtobufWireCodec.rewriteSubMessageOrNullIfKeep(
                        bytes = bytes,
                        offset = tabModSlice.payloadStart,
                        length = tabModSlice.payloadLength
                    ) { modSlice ->
                        if (modSlice.fieldNumber != schema.introductionModulesField ||
                            modSlice.wireType != ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            return@rewriteSubMessageOrNullIfKeep WireRewriteAction.Keep
                        }
                        val modType = readModuleType(bytes, modSlice.payloadStart, modSlice.payloadLength, schema)
                        if (shouldDropModule(modType, modSlice)) {
                            WireRewriteAction.Drop
                        } else {
                            WireRewriteAction.Keep
                        }
                    }
                    if (updatedIntro == null) {
                        WireRewriteAction.Keep
                    } else {
                        WireRewriteAction.ReplaceLengthDelimited(tabModSlice.fieldNumber, updatedIntro)
                    }
                }
                if (updatedTabModule == null) {
                    WireRewriteAction.Keep
                } else {
                    WireRewriteAction.ReplaceLengthDelimited(tabSlice.fieldNumber, updatedTabModule)
                }
            }
            if (updatedTab == null) {
                WireRewriteAction.Keep
            } else {
                WireRewriteAction.ReplaceLengthDelimited(slice.fieldNumber, updatedTab)
            }
        }
    }

    /**
     * 按目标顶层字段号重写/替换/删除，未知字段与未命中字段逐字节保留，无修改返回原始 [bytes] 引用（`===`）。
     */
    fun rewriteTargetFields(
        bytes: ByteArray,
        targetFieldNumbers: Set<Int>,
        transform: (WireFieldSlice) -> WireRewriteAction
    ): ByteArray {
        if (targetFieldNumbers.isEmpty()) return bytes
        return ProtobufWireCodec.rewriteFields(bytes) { slice ->
            if (slice.fieldNumber in targetFieldNumbers) transform(slice) else WireRewriteAction.Keep
        }
    }

    fun stripTopLevelFields(bytes: ByteArray, dropFieldNumbers: Set<Int>): ByteArray {
        if (dropFieldNumbers.isEmpty()) return bytes
        return ProtobufWireCodec.rewriteFields(bytes) { slice ->
            if (slice.fieldNumber in dropFieldNumbers) WireRewriteAction.Drop else WireRewriteAction.Keep
        }
    }

    private fun collectTabModuleTypes(
        bytes: ByteArray,
        tabOffset: Int,
        tabLength: Int,
        schema: ViewUniteWireSchema,
        out: MutableList<Int>
    ) {
        ProtobufWireCodec.forEachField(bytes, tabOffset, tabLength) { tabSlice ->
            if (tabSlice.fieldNumber == schema.tabModuleListField &&
                tabSlice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
            ) {
                ProtobufWireCodec.forEachField(bytes, tabSlice.payloadStart, tabSlice.payloadLength) { tabMod ->
                    if (tabMod.fieldNumber == schema.tabModuleIntroductionField &&
                        tabMod.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                    ) {
                        ProtobufWireCodec.forEachField(bytes, tabMod.payloadStart, tabMod.payloadLength) { mod ->
                            if (mod.fieldNumber == schema.introductionModulesField &&
                                mod.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                            ) {
                                out.add(readModuleType(bytes, mod.payloadStart, mod.payloadLength, schema))
                            }
                            true
                        }
                    }
                    true
                }
            }
            true
        }
    }

    private fun readModuleType(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        schema: ViewUniteWireSchema
    ): Int {
        var type = 0
        ProtobufWireCodec.forEachField(bytes, offset, length) { slice ->
            if (slice.fieldNumber == schema.moduleTypeField &&
                slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT
            ) {
                type = slice.varintValue.toInt()
            }
            true
        }
        return type
    }
}
