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
    val replyMemberField: Int = 13,
    val replyContentField: Int = 12,
    val replyMemberV2Field: Int = 15,
    val contentMessageField: Int = 1,
    val memberMidField: Int = 1,
    val memberNameField: Int = 2,
    val memberLevelField: Int = 5,
    /** 旧 schema 曾误配为 6（Member#6 实为 int official_verify_type_）；0 表示默认关闭嵌套读取。 */
    val memberLevelInfoField: Int = 0,
    val levelInfoCurrentField: Int = 1,
    val memberV2BasicField: Int = 1,
    val memberV2BasicMidField: Int = 1,
    val memberV2BasicNameField: Int = 2,
    val memberV2BasicLevelField: Int = 5
) {
    companion object {
        val DEFAULT_REPLY_LIST_FIELDS = setOf(2, 14) // replies(2), top_replies(14)
        val DEFAULT_SINGLE_REPLY_FIELDS = setOf(4, 5, 6) // up_top(4), admin_top(5), vote_top(6)
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
                schema.memberLevelInfoField > 0 &&
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
    val extendOrigDescField: Int = 6,
    val extendOrigNameField: Int = 4,
    val extendUidField: Int = 14,
    val extendDescField: Int = 7,
    val moduleTypeField: Int = 1,
    val moduleAuthorField: Int = 2,
    val moduleDynamicField: Int = 5,
    val moduleDescField: Int = 4,
    val descriptionTextField: Int = 1,
    val moduleDescTextField: Int = 1,
    val moduleDescPlainTextField: Int = 3,
    val authorDirectMidField: Int = 1,
    val authorUserField: Int = 3,
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
                            (extSlice.fieldNumber == schema.extendOrigDescField ||
                                (schema.extendDescField > 0 && extSlice.fieldNumber == schema.extendDescField)) &&
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
                    var extractedDesc = false
                    var plainFallback: String? = null
                    ProtobufWireCodec.forEachField(bytes, slice.payloadStart, slice.payloadLength) { descContainer ->
                        if (descContainer.fieldNumber == schema.moduleDescTextField &&
                            descContainer.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            extractDescriptionText(
                                bytes,
                                descContainer.payloadStart,
                                descContainer.payloadEnd,
                                schema
                            )?.let {
                                texts.add(it)
                                extractedDesc = true
                            }
                        } else if (!extractedDesc &&
                            schema.moduleDescPlainTextField > 0 &&
                            descContainer.fieldNumber == schema.moduleDescPlainTextField &&
                            descContainer.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED
                        ) {
                            plainFallback = ProtobufWireCodec.decodeUtf8OrNull(
                                bytes,
                                descContainer.payloadStart,
                                descContainer.payloadEnd
                            )
                        }
                        true
                    }
                    if (!extractedDesc) {
                        plainFallback?.let(texts::add)
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
        var directMid: Long? = null
        ProtobufWireCodec.forEachField(bytes, start, end - start) { slice ->
            when {
                schema.authorDirectMidField > 0 &&
                    slice.fieldNumber == schema.authorDirectMidField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_VARINT -> {
                    directMid = slice.varintValue
                }
                slice.fieldNumber == schema.authorUserField &&
                    slice.wireType == ProtobufWireCodec.WIRE_TYPE_LENGTH_DELIMITED -> {
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
            }
            true
        }
        onAuthor(name, uid ?: directMid)
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
    val ownerField: Int = 4,
    val tabField: Int = 5,
    val ecodeField: Int = 8,
    val ecodeConfigField: Int = 9,
    val supplementField: Int = 6,
    val tabModuleListField: Int = 1,
    val tabModuleIntroductionField: Int = 2,
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
        schema: ViewUniteWireSchema = ViewUniteWireSchema.DEFAULT,
        isPlayView: Boolean = false
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
            if (isPlayView) {
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
            } else {
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

// -----------------------------------------------------------------------------
// 4. KmpWireSchemaProber (运行期 *_FIELD_NUMBER 探针，fail-closed 回退已验真默认)
// -----------------------------------------------------------------------------

/**
 * 在接入真实宿主 `com.bapis.*` 类时按静态 `*_FIELD_NUMBER` 常量校准纯 Wire Schema；
 * 任一字段缺失、超出 Protobuf 合法范围 `[1, 2^29 - 1]` 或同消息内字段号碰撞时，整体回退到 [fallback]。
 */
internal object KmpWireSchemaProber {
    private const val MAX_PROTO_FIELD_NUMBER = 536_870_911

    fun probeReplySchema(
        loader: ClassLoader,
        fallback: ReplyWireSchema = ReplyWireSchema.DEFAULT
    ): ReplyWireSchema = runCatching {
        val mainList = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.MainListReply")
        val replyInfo = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.ReplyInfo")
        val content = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.Content")
        val member = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.Member")
        val memberV2 = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.MemberV2")
        val memberV2Basic = loadClass(loader, "com.bapis.bilibili.main.community.reply.v1.MemberV2\$Basic")

        val replies = probeField(mainList, "REPLIES_FIELD_NUMBER", 2) ?: return fallback
        val topReplies = probeField(mainList, "TOP_REPLIES_FIELD_NUMBER", 14) ?: return fallback
        val upTop = probeField(mainList, "UP_TOP_FIELD_NUMBER", 4) ?: return fallback
        val adminTop = probeField(mainList, "ADMIN_TOP_FIELD_NUMBER", 5) ?: return fallback
        val voteTop = probeField(mainList, "VOTE_TOP_FIELD_NUMBER", 6) ?: return fallback
        if (!distinctPositive(replies, topReplies, upTop, adminTop, voteTop)) return fallback

        val nestedReplies = probeField(replyInfo, "REPLIES_FIELD_NUMBER", fallback.nestedRepliesField) ?: return fallback
        val replyId = probeField(replyInfo, "ID_FIELD_NUMBER", fallback.replyIdField) ?: return fallback
        val replyOid = probeField(replyInfo, "OID_FIELD_NUMBER", fallback.replyOidField) ?: return fallback
        val replyType = probeField(replyInfo, "TYPE_FIELD_NUMBER", fallback.replyTypeField) ?: return fallback
        val replyMid = probeField(replyInfo, "MID_FIELD_NUMBER", fallback.replyMidField) ?: return fallback
        val replyMember = probeField(replyInfo, "MEMBER_FIELD_NUMBER", fallback.replyMemberField) ?: return fallback
        val replyContent = probeField(replyInfo, "CONTENT_FIELD_NUMBER", fallback.replyContentField) ?: return fallback
        val replyMemberV2 = probeField(replyInfo, "MEMBER_V2_FIELD_NUMBER", fallback.replyMemberV2Field) ?: return fallback
        if (!distinctPositive(nestedReplies, replyId, replyOid, replyType, replyMid, replyMember, replyContent, replyMemberV2)) {
            return fallback
        }

        val contentMessage = probeField(content, "MESSAGE_FIELD_NUMBER", fallback.contentMessageField) ?: return fallback
        val memberMid = probeField(member, "MID_FIELD_NUMBER", fallback.memberMidField) ?: return fallback
        val memberName = probeField(member, "NAME_FIELD_NUMBER", fallback.memberNameField) ?: return fallback
        val memberLevel = probeField(member, "LEVEL_FIELD_NUMBER", fallback.memberLevelField) ?: return fallback
        if (!distinctPositive(memberMid, memberName, memberLevel)) return fallback
        if (fallback.memberLevelInfoField > 0 && fallback.memberLevelInfoField in setOf(memberMid, memberName, memberLevel)) {
            return fallback
        }

        val v2Basic = probeField(memberV2, "BASIC_FIELD_NUMBER", fallback.memberV2BasicField) ?: return fallback
        val v2Mid = probeField(memberV2Basic, "MID_FIELD_NUMBER", fallback.memberV2BasicMidField) ?: return fallback
        val v2Name = probeField(memberV2Basic, "NAME_FIELD_NUMBER", fallback.memberV2BasicNameField) ?: return fallback
        val v2Level = probeField(memberV2Basic, "LEVEL_FIELD_NUMBER", fallback.memberV2BasicLevelField) ?: return fallback
        if (!distinctPositive(v2Mid, v2Name, v2Level)) return fallback

        fallback.copy(
            replyListFields = setOf(replies, topReplies),
            singleReplyFields = setOf(upTop, adminTop, voteTop),
            nestedRepliesField = nestedReplies,
            replyIdField = replyId,
            replyOidField = replyOid,
            replyTypeField = replyType,
            replyMidField = replyMid,
            replyMemberField = replyMember,
            replyContentField = replyContent,
            replyMemberV2Field = replyMemberV2,
            contentMessageField = contentMessage,
            memberMidField = memberMid,
            memberNameField = memberName,
            memberLevelField = memberLevel,
            memberV2BasicField = v2Basic,
            memberV2BasicMidField = v2Mid,
            memberV2BasicNameField = v2Name,
            memberV2BasicLevelField = v2Level
        )
    }.getOrElse { fallback }

    fun probeDynSchema(
        loader: ClassLoader,
        fallback: DynWireSchema = DynWireSchema.DEFAULT
    ): DynWireSchema = runCatching {
        val dynAll = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.DynAllReply")
        val dynList = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.DynamicList")
        val dynItem = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.DynamicItem")
        val extend = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.Extend")
        val module = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.Module")
        val moduleDesc = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.ModuleDesc")
        val description = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.Description")
        val moduleAuthor = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.ModuleAuthor")
        val userInfo = loadClass(loader, "com.bapis.bilibili.app.dynamic.v2.UserInfo")

        val dynamicList = probeField(dynAll, "DYNAMIC_LIST_FIELD_NUMBER", fallback.dynamicListField) ?: return fallback
        val itemList = probeField(dynList, "LIST_FIELD_NUMBER", fallback.itemListField) ?: return fallback
        val cardType = probeField(dynItem, "CARD_TYPE_FIELD_NUMBER", fallback.itemCardTypeField) ?: return fallback
        val itemType = probeField(dynItem, "ITEM_TYPE_FIELD_NUMBER", fallback.itemItemTypeField) ?: return fallback
        val modules = probeField(dynItem, "MODULES_FIELD_NUMBER", fallback.itemModulesField) ?: return fallback
        val itemExtend = probeField(dynItem, "EXTEND_FIELD_NUMBER", fallback.itemExtendField) ?: return fallback
        if (!distinctPositive(cardType, itemType, modules, itemExtend)) return fallback

        val dynIdStr = probeField(extend, "DYN_ID_STR_FIELD_NUMBER", fallback.extendDynIdStrField) ?: return fallback
        val origDesc = probeField(extend, "ORIG_DESC_FIELD_NUMBER", fallback.extendOrigDescField) ?: return fallback
        val origName = probeField(extend, "ORIG_NAME_FIELD_NUMBER", fallback.extendOrigNameField) ?: return fallback
        val uid = probeField(extend, "UID_FIELD_NUMBER", fallback.extendUidField) ?: return fallback
        val extDesc = if (fallback.extendDescField > 0) {
            probeField(extend, "DESC_FIELD_NUMBER", fallback.extendDescField) ?: return fallback
        } else 0
        if (!distinctPositive(dynIdStr, origDesc, origName, uid) ||
            (extDesc > 0 && extDesc in setOf(dynIdStr, origDesc, origName, uid))
        ) {
            return fallback
        }

        val moduleType = probeField(module, "MODULE_TYPE_FIELD_NUMBER", fallback.moduleTypeField) ?: return fallback
        val moduleAuthorField = probeField(module, "MODULE_AUTHOR_FIELD_NUMBER", fallback.moduleAuthorField) ?: return fallback
        val moduleDynamicField = probeField(module, "MODULE_DYNAMIC_FIELD_NUMBER", fallback.moduleDynamicField) ?: return fallback
        val moduleDescField = probeField(module, "MODULE_DESC_FIELD_NUMBER", fallback.moduleDescField) ?: return fallback
        if (!distinctPositive(moduleType, moduleAuthorField, moduleDynamicField, moduleDescField)) return fallback

        val descText = probeField(description, "TEXT_FIELD_NUMBER", fallback.descriptionTextField) ?: return fallback
        val modDescItems = probeField(moduleDesc, "DESC_FIELD_NUMBER", fallback.moduleDescTextField) ?: return fallback
        val modDescPlain = if (fallback.moduleDescPlainTextField > 0) {
            probeField(moduleDesc, "TEXT_FIELD_NUMBER", fallback.moduleDescPlainTextField) ?: return fallback
        } else 0
        if (modDescPlain > 0 && modDescPlain == modDescItems) return fallback

        val directMid = if (fallback.authorDirectMidField > 0) {
            probeField(moduleAuthor, "MID_FIELD_NUMBER", fallback.authorDirectMidField) ?: return fallback
        } else 0
        val authorUser = probeField(moduleAuthor, "AUTHOR_FIELD_NUMBER", fallback.authorUserField) ?: return fallback
        if (directMid > 0 && directMid == authorUser) return fallback

        val userMid = probeField(userInfo, "MID_FIELD_NUMBER", fallback.authorMidField) ?: return fallback
        val userName = probeField(userInfo, "NAME_FIELD_NUMBER", fallback.authorNameField) ?: return fallback
        if (!distinctPositive(userMid, userName)) return fallback

        fallback.copy(
            dynamicListField = dynamicList,
            itemListField = itemList,
            itemCardTypeField = cardType,
            itemItemTypeField = itemType,
            itemModulesField = modules,
            itemExtendField = itemExtend,
            extendDynIdStrField = dynIdStr,
            extendOrigDescField = origDesc,
            extendOrigNameField = origName,
            extendUidField = uid,
            extendDescField = extDesc,
            moduleTypeField = moduleType,
            moduleAuthorField = moduleAuthorField,
            moduleDynamicField = moduleDynamicField,
            moduleDescField = moduleDescField,
            descriptionTextField = descText,
            moduleDescTextField = modDescItems,
            moduleDescPlainTextField = modDescPlain,
            authorDirectMidField = directMid,
            authorUserField = authorUser,
            authorMidField = userMid,
            authorNameField = userName
        )
    }.getOrElse { fallback }

    fun probeViewUniteSchema(
        loader: ClassLoader,
        fallback: ViewUniteWireSchema = ViewUniteWireSchema.DEFAULT
    ): ViewUniteWireSchema = runCatching {
        val viewReply = loadClass(loader, "com.bapis.bilibili.app.viewunite.v1.ViewReply")
        val tab = loadClass(loader, "com.bapis.bilibili.app.viewunite.v1.Tab")
        val tabModule = loadClass(loader, "com.bapis.bilibili.app.viewunite.v1.TabModule")
        val introTab = loadClass(loader, "com.bapis.bilibili.app.viewunite.v1.IntroductionTab")
        val commonModule = loadClass(loader, "com.bapis.bilibili.app.viewunite.common.Module")
        val playView = loadClass(loader, "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply")

        val viewBase = probeField(viewReply, "VIEW_BASE_FIELD_NUMBER", fallback.viewBaseField) ?: return fallback
        val arc = probeField(viewReply, "ARC_FIELD_NUMBER", fallback.arcField) ?: return fallback
        val owner = probeField(viewReply, "OWNER_FIELD_NUMBER", fallback.ownerField) ?: return fallback
        val tabField = probeField(viewReply, "TAB_FIELD_NUMBER", fallback.tabField) ?: return fallback
        val ecode = probeField(viewReply, "ECODE_FIELD_NUMBER", fallback.ecodeField) ?: return fallback
        val ecodeConfig = probeField(viewReply, "ECODE_CONFIG_FIELD_NUMBER", fallback.ecodeConfigField) ?: return fallback
        val supplement = probeField(viewReply, "SUPPLEMENT_FIELD_NUMBER", fallback.supplementField) ?: return fallback
        if (!distinctPositive(viewBase, arc, owner, tabField, ecode, ecodeConfig, supplement)) return fallback

        val tabModuleList = probeField(tab, "TAB_MODULE_FIELD_NUMBER", fallback.tabModuleListField) ?: return fallback
        val introField = probeField(tabModule, "INTRODUCTION_FIELD_NUMBER", fallback.tabModuleIntroductionField) ?: return fallback
        val introModules = probeField(introTab, "MODULES_FIELD_NUMBER", fallback.introductionModulesField) ?: return fallback
        val modType = probeField(commonModule, "TYPE_FIELD_NUMBER", fallback.moduleTypeField) ?: return fallback
        val playVod = probeField(playView, "VOD_INFO_FIELD_NUMBER", fallback.playVodInfoField) ?: return fallback
        val playSupp = probeField(playView, "SUPPLEMENT_FIELD_NUMBER", fallback.playSupplementField) ?: return fallback
        if (!distinctPositive(playVod, playSupp)) return fallback

        fallback.copy(
            viewBaseField = viewBase,
            arcField = arc,
            ownerField = owner,
            tabField = tabField,
            ecodeField = ecode,
            ecodeConfigField = ecodeConfig,
            supplementField = supplement,
            tabModuleListField = tabModuleList,
            tabModuleIntroductionField = introField,
            introductionModulesField = introModules,
            moduleTypeField = modType,
            playVodInfoField = playVod,
            playSupplementField = playSupp
        )
    }.getOrElse { fallback }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()

    private fun probeField(owner: Class<*>?, fieldName: String, defaultValue: Int): Int? {
        if (owner == null) return defaultValue
        val field = runCatching { owner.getField(fieldName) }.getOrNull() ?: return defaultValue
        val modifiers = field.modifiers
        if (!java.lang.reflect.Modifier.isStatic(modifiers) ||
            !java.lang.reflect.Modifier.isFinal(modifiers) ||
            field.type != Int::class.javaPrimitiveType
        ) {
            return null
        }
        val value = runCatching { field.getInt(null) }.getOrNull() ?: return null
        return value.takeIf { it in 1..MAX_PROTO_FIELD_NUMBER }
    }

    private fun distinctPositive(vararg values: Int): Boolean {
        if (values.any { it !in 1..MAX_PROTO_FIELD_NUMBER }) return false
        return values.distinct().size == values.size
    }
}
