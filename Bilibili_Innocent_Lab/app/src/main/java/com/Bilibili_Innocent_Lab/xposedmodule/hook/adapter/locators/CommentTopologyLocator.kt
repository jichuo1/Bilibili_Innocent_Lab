package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isFinal
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/**
 * 评论回复脉络定位器（ReplyInfo -> CommentItem 身份桥 + 宿主 MOSS 分页边界）。
 */
internal object CommentTopologyLocator {

    internal data class CommentTopologyLocateOutcome(
        val points: CommentTopologyPoints?,
        val failureDetail: String
    )

    internal data class VerifiedCommentReplyMapper(
        val ownerClassName: String,
        val methodName: String,
        val fifthParamClassName: String
    )

    internal const val COMMENT_ITEM_CLASS = "com.bilibili.app.comment3.data.model.CommentItem"
    internal const val COMMENT_REPLY_MAPPER_PACKAGE =
        "com.bilibili.app.comment3.data.source.v1"

    // 9.19–9.21 的五参 ReplyInfo -> CommentItem 根包混淆映射器逐构建验真，禁止跨构建串用。
    internal val VERIFIED_COMMENT_REPLY_MAPPERS: Map<Int, VerifiedCommentReplyMapper> = mapOf(
        9190200 to VerifiedCommentReplyMapper("rg40", "B", "tev"),
        9190400 to VerifiedCommentReplyMapper("rg40", "B", "tev"),
        9200100 to VerifiedCommentReplyMapper("w360", "B", "caw"),
        9200200 to VerifiedCommentReplyMapper("v260", "B", "m9w"),
        9200300 to VerifiedCommentReplyMapper("l260", "B", "z8w"),
        9210100 to VerifiedCommentReplyMapper("ot50", "B", "y1w"),
        9210200 to VerifiedCommentReplyMapper("kt50", "B", "b4w"),
        9210300 to VerifiedCommentReplyMapper("kt50", "B", "b4w")
    )

    /**
     * `ReplyInfo -> CommentItem` 的旧映射落在同一个包的 Kotlin file facade 上，但类名随每次
     * 混淆漂移，实测：8.63.0–8.96.0=e、8.97.0=c、8.98.0/8.99.0=d、9.1.0–9.2.0=c、
     * 9.3.0–9.5.0=d、9.6.0=b、9.7.0–9.9.0=c、9.10.0=b。写死名单必然过期——9.6.0 与 9.10.0 已各
     * 静默失效一次，因此改为在稳定包内按有界字母表列出候选，真正的判定仍由
     * [locateWithDiagnostic] 的结构过滤完成
     *（static + 非 abstract + 非 synthetic + 首参 ReplyInfo + 返回 CommentItem）。
     */
    internal val COMMENT_REPLY_MAPPER_CLASS_CANDIDATES: List<String> =
        ('a'..'z').map { letter -> "$COMMENT_REPLY_MAPPER_PACKAGE.$letter" }
    internal val FEED_PAGINATION_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.pagination.FeedPagination"
    )

    /**
     * 定位回复脉络的 ReplyInfo 身份桥和宿主 MOSS 公开分页边界。
     *
     * protobuf 类型和方法名在 8.90.2—9.9.0 样本中保持公开稳定；唯一可能混淆的
     * ReplyInfo -> CommentItem 映射方法按首参/返回类型结构定位，拒绝猜测 a..g 字段。
     */
    internal fun locate(
        loader: ClassLoader,
        hostVersionCode: Int = 0
    ): CommentTopologyPoints? =
        locateWithDiagnostic(loader, hostVersionCode = hostVersionCode).points

    /**
     * @param extraMapperOwners DEX 兜底补充的 mapper owner。它只扩大候选 owner 集合，不放宽
     *   任何结构判定——所有候选仍走下面同一段过滤，避免兜底路径出现第二份判定规则。
     */
    internal fun locateWithDiagnostic(
        loader: ClassLoader,
        extraMapperOwners: List<Class<*>> = emptyList(),
        hostVersionCode: Int = 0
    ): CommentTopologyLocateOutcome {
        var stage = "reply-info-class"
        val attempt = runCatching {
            val replyInfo = CommentLocator.COMMENT_REPLY_INFO_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .firstOrNull() ?: return@runCatching null
            val replyPackage = replyInfo.name.substringBeforeLast('.')
            stage = "comment-item-class"
            val commentItem = KavaMemberLookup.classOrNull(loader, COMMENT_ITEM_CLASS)
                ?: return@runCatching null
            stage = "reply-mapper-methods"
            // 9.19/9.20 builds move the five-parameter mapper to the root package; keep direct resolution exact per build.
            val relocatedSpecs = when {
                hostVersionCode > 0 -> listOfNotNull(VERIFIED_COMMENT_REPLY_MAPPERS[hostVersionCode])
                else -> VERIFIED_COMMENT_REPLY_MAPPERS.values.distinct()
            }
            val relocatedMappers = relocatedSpecs.mapNotNull { spec ->
                KavaMemberLookup.classOrNull(loader, spec.ownerClassName)?.let { owner ->
                    val parameters = listOf(
                        replyInfo.name,
                        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
                        "boolean",
                        "java.lang.String",
                        spec.fifthParamClassName
                    )
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                        method.name == spec.methodName && method.isPublic && method.isStatic &&
                            method.isFinal && !method.isSynthetic &&
                            method.returnType == commentItem &&
                            method.parameterTypes.firstOrNull() == replyInfo &&
                            method.parameterTypes.map(Class<*>::getName) == parameters
                    }.singleOrNull()
                }
            }
            val packageMappers = (
                COMMENT_REPLY_MAPPER_CLASS_CANDIDATES.asSequence()
                    .mapNotNull { KavaMemberLookup.classOrNull(loader, it) } +
                    extraMapperOwners.asSequence()
                )
                .distinct()
                .flatMap { mapperOwner ->
                    KavaMemberLookup.declaredMethods(mapperOwner, makeAccessible = true) { method ->
                        method.isStatic && !method.isAbstract && !method.isSynthetic &&
                            method.returnType == commentItem && method.parameterCount >= 1 &&
                            method.parameterTypes.firstOrNull() == replyInfo
                    }.asSequence()
                }
            val mapperMethods = (packageMappers + relocatedMappers)
                .distinctBy(Method::toGenericString)
                .sortedBy(Method::toGenericString)
                .take(CommentTopologyPoints.MAX_MAPPER_METHODS + 1)
                .toList()
            if (mapperMethods.isEmpty()) return@runCatching null
            if (mapperMethods.size > CommentTopologyPoints.MAX_MAPPER_METHODS) {
                error("too-many-reply-mappers:${mapperMethods.size}")
            }

            fun publicNoArg(owner: Class<*>, name: String): Method? =
                KavaMemberLookup.inheritedMethodOrNull(owner, name)?.takeIf { method ->
                    !method.isStatic && method.isPublic && method.parameterCount == 0
                }

            fun publicStaticNoArg(owner: Class<*>, name: String): Method? =
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.isStatic && method.isPublic && method.name == name &&
                        method.parameterCount == 0
                }.singleOrNull()

            fun publicExact(owner: Class<*>, name: String, vararg params: Class<*>): Method? =
                KavaMemberLookup.inheritedMethodOrNull(owner, name, *params)?.takeIf { method ->
                    !method.isStatic && method.isPublic &&
                        method.parameterTypes.contentEquals(params)
                }

            val methods = linkedMapOf<String, HookPoint>()
            fun add(key: String, method: Method?): Method {
                val resolved = method ?: throw NoSuchMethodException(key)
                methods[key] = resolved.toHookPoint()
                return resolved
            }

            add(CommentTopologyPoints.REPLY_ID, publicNoArg(replyInfo, "getId"))
            add(CommentTopologyPoints.COMMENT_ITEM_ID, publicNoArg(commentItem, "getId"))
            add(CommentTopologyPoints.REPLY_OID, publicNoArg(replyInfo, "getOid"))
            add(CommentTopologyPoints.REPLY_TYPE, publicNoArg(replyInfo, "getType"))
            add(CommentTopologyPoints.REPLY_ROOT, publicNoArg(replyInfo, "getRoot"))
            add(CommentTopologyPoints.REPLY_PARENT, publicNoArg(replyInfo, "getParent"))
            add(CommentTopologyPoints.REPLY_DIALOG, publicNoArg(replyInfo, "getDialog"))
            add(CommentTopologyPoints.REPLY_CTIME, publicNoArg(replyInfo, "getCtime"))
            add(CommentTopologyPoints.REPLY_COUNT, publicNoArg(replyInfo, "getCount"))
            add(CommentTopologyPoints.REPLY_MID, publicNoArg(replyInfo, "getMid"))
            val contentGetter = add(
                CommentTopologyPoints.REPLY_CONTENT,
                publicNoArg(replyInfo, "getContent")
            )
            val memberGetter = publicNoArg(replyInfo, "getMember")
            memberGetter?.let { methods[CommentTopologyPoints.REPLY_MEMBER] = it.toHookPoint() }
            val memberV2Getter = add(
                CommentTopologyPoints.REPLY_MEMBER_V2,
                publicNoArg(replyInfo, "getMemberV2")
            )
            val parentMemberGetter = publicNoArg(replyInfo, "getParentReplyMember")
            parentMemberGetter?.let {
                methods[CommentTopologyPoints.REPLY_PARENT_MEMBER] = it.toHookPoint()
            }
            add(CommentTopologyPoints.REPLY_CHILDREN, publicNoArg(replyInfo, "getRepliesList"))
            add(
                CommentTopologyPoints.CONTENT_MESSAGE,
                publicNoArg(contentGetter.returnType, "getMessage")
            )
            memberGetter?.let { getter ->
                publicNoArg(getter.returnType, "getName")?.let { nameGetter ->
                    methods[CommentTopologyPoints.MEMBER_NAME] = nameGetter.toHookPoint()
                }
            }
            val memberBasicGetter = add(
                CommentTopologyPoints.MEMBER_V2_BASIC,
                publicNoArg(memberV2Getter.returnType, "getBasic")
            )
            add(
                CommentTopologyPoints.MEMBER_BASIC_NAME,
                publicNoArg(memberBasicGetter.returnType, "getName")
            )
            parentMemberGetter?.let { getter ->
                publicNoArg(getter.returnType, "getName")?.let { nameGetter ->
                    methods[CommentTopologyPoints.PARENT_MEMBER_NAME] = nameGetter.toHookPoint()
                }
            }

            stage = "feed-pagination-class"
            val pagination = FEED_PAGINATION_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .firstOrNull() ?: return@runCatching null
            val paginationNewBuilder = add(
                CommentTopologyPoints.PAGINATION_NEW_BUILDER,
                publicStaticNoArg(pagination, "newBuilder")
            )
            val paginationBuilder = paginationNewBuilder.returnType
            add(
                CommentTopologyPoints.PAGINATION_SET_OFFSET,
                publicExact(paginationBuilder, "setOffset", classOf<String>())
            )
            add(CommentTopologyPoints.PAGINATION_BUILD, publicNoArg(paginationBuilder, "build"))

            stage = "detail-list-request-class"
            val detailReq = KavaMemberLookup.classOrNull(loader, "$replyPackage.DetailListReq")
                ?: return@runCatching null
            val detailNewBuilder = add(
                CommentTopologyPoints.DETAIL_NEW_BUILDER,
                publicStaticNoArg(detailReq, "newBuilder")
            )
            val detailBuilder = detailNewBuilder.returnType
            add(CommentTopologyPoints.DETAIL_SET_OID, publicExact(detailBuilder, "setOid", classOf<Long>()))
            add(CommentTopologyPoints.DETAIL_SET_TYPE, publicExact(detailBuilder, "setType", classOf<Long>()))
            add(CommentTopologyPoints.DETAIL_SET_MODE, publicExact(detailBuilder, "setModeValue", classOf<Int>()))
            add(
                CommentTopologyPoints.DETAIL_SET_PAGINATION,
                publicExact(detailBuilder, "setPagination", pagination)
            )
            add(CommentTopologyPoints.DETAIL_SET_ROOT, publicExact(detailBuilder, "setRoot", classOf<Long>()))
            add(CommentTopologyPoints.DETAIL_SET_RPID, publicExact(detailBuilder, "setRpid", classOf<Long>()))
            add(CommentTopologyPoints.DETAIL_BUILD, publicNoArg(detailBuilder, "build"))

            stage = "reply-moss-class"
            val replyMoss = KavaMemberLookup.classOrNull(loader, "$replyPackage.ReplyMoss")
                ?: return@runCatching null
            stage = "reply-moss-constructor"
            if (KavaMemberLookup.constructorOrNull(replyMoss) == null) return@runCatching null
            stage = "reply-moss-detail-list"
            val detailCall = KavaMemberLookup.methods(
                replyMoss,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                !method.isStatic && method.isPublic && method.name == "detailList" &&
                    method.parameterCount == 2 && method.parameterTypes[0] == detailReq &&
                    method.returnType == Void.TYPE
            }.distinctBy(Method::toGenericString).singleOrNull() ?: return@runCatching null
            add(CommentTopologyPoints.MOSS_DETAIL, detailCall)

            stage = "detail-list-reply-class"
            val detailReply = KavaMemberLookup.classOrNull(loader, "$replyPackage.DetailListReply")
                ?: return@runCatching null
            val detailRoot = add(
                CommentTopologyPoints.DETAIL_ROOT,
                publicNoArg(detailReply, "getRoot")
            )
            stage = "detail-root-return-type"
            if (detailRoot.returnType != replyInfo) return@runCatching null
            val detailPagination = add(
                CommentTopologyPoints.DETAIL_PAGINATION,
                publicNoArg(detailReply, "getPaginationReply")
            )
            add(
                CommentTopologyPoints.PAGINATION_NEXT_OFFSET,
                publicNoArg(detailPagination.returnType, "getNextOffset")
            )

            stage = "required-method-validation"
            CommentTopologyPoints(
                mapperMethods = mapperMethods.map { it.toHookPoint() },
                replyMossClassName = replyMoss.name,
                methods = methods
            ).takeIf(CommentTopologyPoints::hasRequiredMethods)
        }
        val points = attempt.getOrNull()
        val detail = if (points != null) {
            ""
        } else {
            attempt.exceptionOrNull()?.let { throwable ->
                val message = throwable.message?.takeIf(String::isNotBlank)
                "${throwable.javaClass.simpleName}:${message ?: stage}"
            } ?: "unresolved:$stage"
        }
        return CommentTopologyLocateOutcome(points, detail)
    }
}
