package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentEmptyPagePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFilterPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFollowPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentOptionalPayloadPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentPurifyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentSectionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.ListConstructorPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.hasSuperclassNamed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.label
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.rawClassOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * 评论区定位器（评论 holder 低/高路径、净化、筛选、分区构造、快捷回复直接定位）。
 */
internal object CommentLocator {

    /** 内置候选（已适配版本的已知 hook 点；新版本靠特征匹配自动修正） */
    internal val COMMENT_LOW_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.holder.t0",
        // 8.63.0 及同类早期版本：评论 holder 是 h0（含 CommentItem 字段的 ViewHolder）；
        // 绑定方法为内部 CommentContentRichTextHandler 的 G(...)，走高路径更精确，
        // 此低路径仅作为「类存在即成功」的兜底候选（仍以特征方法名自动定位）
        "com.bilibili.app.comment3.ui.holder.h0"
    )

    internal val COMMENT_HIGH_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.nextholderexp3.handle.CommentNextExperiment3ContentRichTextHandler",
        // nextholder 管线（9.13.0 实测生效族）：DeviceDecision 开关
        // `comment.next_appearance` 命中时 adapter.a.onCreateViewHolder 选 il.* 族；
        // 绑定方法 b(Zk.Q)/c(Zk.Q)，字段 h 存 CommentItem——与 exp3 结构同构，
        // 特征定位可覆盖。
        "com.bilibili.app.comment3.ui.nextholder.handle.CommentNextContentRichTextHandler",
        // 8.63.0 及同类早期版本：非混淆 handler（绑定方法 G(CommentItem, jv.u, v0, r, int)，
        // 字段 h 存 CommentItem——特征定位自动覆盖，候选仅提供类名入口）
        "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler"
    )

    internal val COMMENT_CONTENT_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.Content"
    )

    /**
     * 搜索链接的载体消息。
     *
     * 2026-09-15 抓包定死的形状（`Reply/MainList` 响应，字段号即 wire 协议）：
     * `MainListReply.replies(2) → ReplyInfo.content(12) → Content.urls(5)
     * → map value → Url.app_url_schema(4) = bilibili://search?from=appcommentline_search…`。
     * 同一条 `Url` 还带 `title(1)`＝被链接的关键词、`prefix_icon(3)`＝搜索小图标、
     * `pc_url(13)`＝网页版地址。
     *
     * 31 个本地宿主逐版核对：类名恒为 `com.bapis...reply.v1.Url`（`com.bapis` 不混淆），
     * `Url` 恒有 14 个字段号；Wave 3 已移除不存在的 jadx 重定位伪包候选。
     */
    internal val COMMENT_URL_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.Url"
    )
    internal val COMMENT_EMPTY_PAGE_OWNER_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
        "com.bapis.bilibili.main.community.reply.v2.SubjectDescriptionReply"
    )
    internal val COMMENT_VOTE_WIDGET_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comment.ext.widgets.CmtVoteWidget",
        "com.bilibili.app.comment.ext.widgets.CmtMountWidget",
        "com.bilibili.app.comment3.ui.widget.CommentVoteView"
    )
    internal val COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget"
    )
    internal val COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView"
    )
    internal val COMMENT_FOLLOW_BUTTON_CLASS_CANDIDATES = listOf(
        "com.bilibili.relation.widget.FollowButton"
    )
    internal val COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.MainListReply"
    )
    internal val COMMENT_REPLY_INFO_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.ReplyInfo"
    )
    internal val THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES = listOf(
        "com.bilibili.ship.theseus.united.page.tab.TheseusTabPagerService"
    )
    internal val COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES = (4..40).flatMap { index ->
        listOf(
            "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$$index",
            "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$$index\$2"
        )
    }

    /**
     * 低版本评论 holder：候选类存在 + 有绑定方法（方法名漂移自适应：8.90.2 为 o0、
     * 9.8.0 为 q0——按候选方法名列表匹配；参数不限，运行期 hook 全部重载）。
     */
    internal fun locateCommentLow(loader: ClassLoader): HookPoint? {
        val methodCandidates = listOf("o0", "q0")
        for (cn in COMMENT_LOW_CANDIDATES) {
            val c = KavaMemberLookup.classOrNull(loader, cn) ?: continue
            val declaredMethods = KavaMemberLookup.declaredMethods(c)
            for (mn in methodCandidates) {
                if (declaredMethods.any { it.name == mn }) {
                    return HookPoint(cn, mn)
                }
            }
        }
        return null
    }

    /**
     * 高版本评论 handler：候选类存在 + 有 CommentItem 字段 + 绑定方法。
     * 特征（版本无关、字段名/方法名/参数类名全部自适应，历史漂移全覆盖）：
     * - 8.63.0：CommentContentRichTextHandler（字段 h、绑定方法 G(CommentItem, jv.u, ...)）
     * - 9.0.0 ：Pj.J / b、9.8.0：al.J / d/e——三者类名/字段名/方法名均不同
     * 特征 1：存在「声明了 CommentItem 类型字段」的字段（字段名不限 i/h）。
     * 特征 2（绑定方法）：参数中存在「声明了 View 类型字段」的类（ViewBinding 特征，
     *   jv.u / Pj.J / al.J 均命中——含 View 字段即可，不依赖字段名 a），且参数个数 1-5
     *   （G 有 5 参）。候选列表仅含评论 handler 类，双特征已足够精确，不再加额外校验
     *   （9.8.0 的 d(al.J, boolean) 参数无 comment3.* 类，曾因外加校验误判漏定位）。
     */
    internal fun locateCommentHigh(loader: ClassLoader): HookPoint? {
        for (cn in COMMENT_HIGH_CANDIDATES) {
            val c = KavaMemberLookup.classOrNull(loader, cn) ?: continue
            // 特征 1：任一字段声明 CommentItem（字段名不限；8.63.0 为 h、9.x 为 i）
            val hasCommentItemField = KavaMemberLookup.declaredFields(c) {
                it.type.name.endsWith(".CommentItem")
            }.isNotEmpty()
            if (!hasCommentItemField) continue
            // 特征 2：绑定方法 = 非 static + 参数含 ViewBinding（View 字段）+ 参数 1-5。
            // 9.8.0 的 static h(al.J) 只设置字号/颜色，旧定位器会误把它缓存成绑定入口。
            // 候选中优先带 CommentItem 实参的方法（可避免 Handler 可变字段串项），否则
            // 选参数更多的主绑定方法（9.8.0 为 d(al.J, boolean)，而 e(al.J) 是分支布局）。
            val bindingCandidates = ArrayList<Method>()
            for (m in KavaMemberLookup.declaredMethods(c)) {
                if (m.parameterCount < 1 || m.parameterCount > 5) continue
                if (m.isStatic) continue
                var hasViewBinding = false
                for (pt in m.parameterTypes) {
                    if (pt.isPrimitive || pt.isArray || pt.isInterface) continue
                    val hasViewField = KavaMemberLookup.declaredFields(pt) {
                        it.type isSubclassOf classOf<android.view.View>()
                    }.isNotEmpty()
                    if (hasViewField) {
                        hasViewBinding = true
                        break
                    }
                }
                if (!hasViewBinding) continue
                bindingCandidates.add(m)
            }
            val best = bindingCandidates.maxWithOrNull(
                compareBy<Method> {
                    if (it.parameterTypes.any { pt -> pt.name.endsWith(".CommentItem") }) 1 else 0
                }.thenBy { it.parameterCount }
            )
            if (best != null) {
                return HookPoint(cn, best.name, best.parameterTypes.map { it.name }, null)
            }
        }
        return null
    }

    /**
     * 定位评论筛选的公开 protobuf 读取链。
     *
     * 以 ReplyInfo 的真实返回类型反推 Content/Member，避免包重定位后分别选择到不同代
     * 的同名类；列表边界还要求泛型元素精确为同一个 ReplyInfo，拒绝无关 List getter。
     */
    internal fun locateCommentFilter(loader: ClassLoader): CommentFilterPoints? = runCatching {
        val replyInfo = COMMENT_REPLY_INFO_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull() ?: return@runCatching null

        fun publicNoArg(owner: Class<*>?, name: String): Method? =
            owner?.let { KavaMemberLookup.methodOrNull(it, name) }?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 && method.isPublic
            }

        val contentGetter = publicNoArg(replyInfo, "getContent")
            ?.takeIf { !it.returnType.isPrimitive }
        val messageGetter = publicNoArg(contentGetter?.returnType, "getMessage")
            ?.takeIf { it.returnType == classOf<String>() }
        val memberGetter = publicNoArg(replyInfo, "getMember")
            ?.takeIf { !it.returnType.isPrimitive }
        val levelGetter = memberGetter?.let { getter -> publicNoArg(getter.returnType, "getLevel") }
            ?.takeIf { method ->
                method.returnType in setOf(
                    classOf<Int>(),
                    classOf<Long>(),
                    classOf<Int>(primitiveType = false),
                    classOf<Long>(primitiveType = false)
                )
            }
        val memberV2Getter = publicNoArg(replyInfo, "getMemberV2")
            ?.takeIf { !it.returnType.isPrimitive }
        val memberV2BasicGetter = memberV2Getter?.let { getter ->
            publicNoArg(getter.returnType, "getBasic")?.takeIf { !it.returnType.isPrimitive }
        }
        val memberV2LevelGetter = memberV2BasicGetter?.let { getter ->
            publicNoArg(getter.returnType, "getLevel")?.takeIf { method ->
                method.returnType in setOf(
                    classOf<Int>(), classOf<Long>(),
                    classOf<Int>(primitiveType = false),
                    classOf<Long>(primitiveType = false)
                )
            }
        }

        // @ 整条过滤：Content 上的 `atNameToMid` map。计数 getter 是热路径的前置判据，
        // Map getter 只在计数 > 0 时才会被调用。
        val atNameCountGetter = publicNoArg(contentGetter?.returnType, "getAtNameToMidCount")
            ?.takeIf { it.returnType == classOf<Int>() }
        val atNameMapGetter = publicNoArg(contentGetter?.returnType, "getAtNameToMidMap")
            ?.takeIf { it.returnType isSubclassOf classOf<Map<*, *>>() }

        fun nameGetter(owner: Class<*>?): Method? = owner?.let {
            publicNoArg(it, "getName")?.takeIf { method -> method.returnType == classOf<String>() }
        }

        fun midGetter(owner: Class<*>?): Method? = owner?.let {
            publicNoArg(it, "getMid")?.takeIf { method ->
                method.returnType in setOf(
                    classOf<Int>(), classOf<Long>(),
                    classOf<Int>(primitiveType = false),
                    classOf<Long>(primitiveType = false)
                )
            }
        }

        val memberNameGetter = nameGetter(memberGetter?.returnType)
        val memberMidGetter = midGetter(memberGetter?.returnType)
        val memberV2NameGetter = nameGetter(memberV2BasicGetter?.returnType)
        val memberV2MidGetter = midGetter(memberV2BasicGetter?.returnType)

        fun Method.returnsReplyInfoList(): Boolean {
            if (!(returnType isSubclassOf classOf<List<*>>())) return false
            val generic = genericReturnType as? ParameterizedType ?: return false
            val argument = generic.actualTypeArguments.singleOrNull() ?: return false
            val argumentName = when (argument) {
                is Class<*> -> argument.name
                is ParameterizedType -> (argument.rawType as? Class<*>)?.name
                else -> null
            }
            return argumentName == replyInfo.name
        }

        val listGetters = (COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES + replyInfo.name)
            .asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 0 && method.isPublic &&
                        method.name in setOf("getRepliesList", "getTopRepliesList") &&
                        method.returnsReplyInfoList()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (listGetters.isEmpty()) return@runCatching null

        val topReplyGetters = COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                listOf("getUpTop", "getAdminTop", "getVoteTop").asSequence()
                    .mapNotNull { name ->
                        publicNoArg(owner, name)?.takeIf { it.returnType == replyInfo }
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val replyDefaultInstance = KavaMemberLookup.declaredMethods(
            replyInfo,
            makeAccessible = true
        ) { method ->
            method.isStatic && method.isPublic && method.name == "getDefaultInstance" &&
                method.parameterCount == 0 && method.returnType == replyInfo
        }.singleOrNull()

        CommentFilterPoints(
            replyListGetters = listGetters,
            contentGetter = contentGetter?.toHookPoint(),
            messageGetter = messageGetter?.toHookPoint(),
            // 发布者过滤只需要 member 本身，等级链缺失时仍要保留它，否则按 UID/用户名过滤
            // 会连"能不能读到作者"都判断不了。等级路径的完整性另由 hasLevelPath 判定。
            memberGetter = memberGetter?.toHookPoint(),
            levelGetter = levelGetter?.toHookPoint(),
            memberV2Getter = memberV2Getter?.toHookPoint(),
            memberV2BasicGetter = memberV2BasicGetter?.toHookPoint(),
            memberV2LevelGetter = memberV2LevelGetter?.toHookPoint(),
            topReplyGetters = topReplyGetters,
            replyDefaultInstanceGetter = replyDefaultInstance?.takeIf {
                topReplyGetters.isNotEmpty()
            }?.toHookPoint(),
            // 计数与 Map 必须成对出现：只有其中之一时无法既省开销又拿到 @ 名单。
            atNameCountGetter = atNameCountGetter?.takeIf { atNameMapGetter != null }?.toHookPoint(),
            atNameMapGetter = atNameMapGetter?.takeIf { atNameCountGetter != null }?.toHookPoint(),
            memberNameGetter = memberNameGetter?.toHookPoint(),
            memberMidGetter = memberMidGetter?.toHookPoint(),
            memberV2NameGetter = memberV2NameGetter?.toHookPoint(),
            memberV2MidGetter = memberV2MidGetter?.toHookPoint()
        )
    }.getOrNull()

    /**
     * 定位评论内容的公开 URL Map getter。8.90.2、9.1.0 与 9.9.0 均保留 getUrls/
     * getUrlsMap；只接管 Map 返回边界，避免修改 protobuf 内部 MapFieldLite 的可变状态。
     */
    internal fun locateCommentPurify(loader: ClassLoader): CommentPurifyPoints? = runCatching {
        val urlMethods = COMMENT_CONTENT_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.name in setOf("getUrls", "getUrlsMap") &&
                        method.returnType isSubclassOf classOf<Map<*, *>>()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        // 第二道防线：跳转目标字段本身。只认无参、返回 String 的公开读取方法，
        // 不按方法名以外的任何宿主结构猜测；类不在就整条不装（空列表 → partial）。
        val urlSchemaGetters = COMMENT_URL_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.name == "getAppUrlSchema" &&
                        method.returnType == classOf<String>()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val emptyPageGetters = COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { owner ->
                val contentGetter = KavaMemberLookup.methodOrNull(owner, "getEmptyPage")
                    ?.takeIf { method ->
                        !method.isStatic && method.parameterCount == 0 &&
                            method.returnType.simpleName == "EmptyPage"
                    } ?: return@mapNotNull null
                val defaultInstanceGetter = KavaMemberLookup.methodOrNull(
                    contentGetter.returnType,
                    "getDefaultInstance"
                )?.takeIf { method ->
                    method.isStatic && method.parameterCount == 0 &&
                        method.returnType == contentGetter.returnType
                } ?: return@mapNotNull null
                CommentEmptyPagePoint(
                    contentGetter.toHookPoint(),
                    defaultInstanceGetter.toHookPoint()
                )
            }
            .distinctBy { it.contentGetter.label() }
            .toList()
        val voteWidgetMethods = COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    if (method.isStatic || method.returnType != Void.TYPE) return@declaredMethods false
                    val parameterNames = method.parameterTypes.map { it.name }
                    when (owner.simpleName) {
                        "CmtVoteWidget" -> method.parameterCount == 4 &&
                            parameterNames.count { it.endsWith(".CmtThemeStrategy") } == 1 &&
                            parameterNames.count { it == "kotlin.jvm.functions.Function0" } == 1 &&
                            parameterNames.count { it == "kotlin.jvm.functions.Function1" } == 1
                        "CmtMountWidget" -> method.parameterCount == 2 &&
                            parameterNames.count { it.endsWith(".CmtThemeStrategy") } == 1
                        "CommentVoteView" -> method.parameterCount == 1 &&
                            method.name.startsWith("setVoteData") &&
                            !method.parameterTypes[0].isPrimitive
                        else -> false
                    }
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val followWidgetMethods = COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .flatMap { owner ->
                val directMethods = KavaMemberLookup.declaredMethods(
                    owner,
                    makeAccessible = true
                ) { method ->
                    if (method.isStatic || method.returnType != Void.TYPE) {
                        return@declaredMethods false
                    }
                    val binder = method.parameterCount == 1 &&
                        !method.parameterTypes[0].isPrimitive
                    val visibilityState = method.parameterCount == 0 &&
                        method.name != "onDetachedFromWindow"
                    binder || visibilityState
                }.map { it.toHookPoint() }
                val callbackMethods = owner.declaredClasses.flatMap { callbackClass ->
                    val outerField = KavaMemberLookup.declaredFields(
                        callbackClass,
                        makeAccessible = true
                    ) { field -> !field.isStatic && field.type == owner }.singleOrNull()
                        ?: return@flatMap emptyList()
                    KavaMemberLookup.declaredMethods(
                        callbackClass,
                        makeAccessible = true
                    ) { method ->
                        !method.isStatic && method.returnType == Void.TYPE &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[1] == classOf<Int>()
                    }.map { method -> method.toHookPoint().copy(viewField = outerField.name) }
                }
                (directMethods + callbackMethods).asSequence()
            }
            .distinctBy { it.label() }
            .toList()
        val followButtonClass = COMMENT_FOLLOW_BUTTON_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull()
        val headerBindMethods = if (followButtonClass == null) {
            emptyList()
        } else {
            COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .filter { it.hasSuperclassNamed("android.view.ViewGroup") }
                .flatMap { owner ->
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                        !method.isStatic && method.returnType == Void.TYPE &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[0] isSubclassOf classOf<List<*>>() &&
                            !method.parameterTypes[1].isPrimitive
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .map { it.toHookPoint() }
                .toList()
        }
        val followPoints = if (followWidgetMethods.isEmpty() && headerBindMethods.isEmpty()) {
            null
        } else {
            CommentFollowPoints(
                followWidgetMethods,
                headerBindMethods,
                followButtonClass?.name
            )
        }
        val mainListOwner = COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull()
        fun optionalPayloadPoint(
            owner: Class<*>?,
            presenceName: String,
            contentName: String
        ): CommentOptionalPayloadPoint? {
            val messageClass = owner ?: return null
            val presenceGetter = KavaMemberLookup.methodOrNull(messageClass, presenceName)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == classOf<Boolean>()
                } ?: return null
            val contentGetter = KavaMemberLookup.methodOrNull(messageClass, contentName)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        !method.returnType.isPrimitive
                } ?: return null
            val defaultInstanceGetter = KavaMemberLookup.methodOrNull(
                contentGetter.returnType,
                "getDefaultInstance"
            )?.takeIf { method ->
                method.isStatic && method.parameterCount == 0 &&
                    method.returnType == contentGetter.returnType
            } ?: return null
            return CommentOptionalPayloadPoint(
                presenceGetter.toHookPoint(),
                contentGetter.toHookPoint(),
                defaultInstanceGetter.toHookPoint()
            )
        }
        val qoePoint = optionalPayloadPoint(mainListOwner, "hasQoe", "getQoe")
        val operationPoints = listOfNotNull(
            optionalPayloadPoint(mainListOwner, "hasOperation", "getOperation"),
            optionalPayloadPoint(mainListOwner, "hasOperationV2", "getOperationV2")
        )
        val quickReplyDialogMethods = locateCommentQuickReplyDirect(loader)
        // 空判据必须与 isStructurallyValid 的有效口径一致：urlSchemaGetters 是搜索跳转
        // 第二道防线的唯一载体，只算它就足够让整组点成立，漏掉会把可用的防线整条丢弃。
        if (urlMethods.isEmpty() && emptyPageGetters.isEmpty() && voteWidgetMethods.isEmpty() &&
            followPoints == null && qoePoint == null && operationPoints.isEmpty() &&
            quickReplyDialogMethods.isEmpty() && urlSchemaGetters.isEmpty()
        ) {
            null
        } else {
            CommentPurifyPoints(
                urlMethods,
                emptyPageGetters,
                voteWidgetMethods,
                followPoints,
                qoePoint,
                operationPoints,
                quickReplyDialogMethods,
                urlSchemaGetters
            )
        }
    }.getOrNull()

    /** 评论快捷回复收集器的同包候选直连定位（Tier-1 零 I/O 快路径）。 */
    internal fun locateCommentQuickReplyDirect(loader: ClassLoader): List<HookPoint> =
        COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 2 &&
                        method.parameterTypes[0].name.endsWith(".PublishDialogIntent") &&
                        method.parameterTypes[1].name == "kotlin.coroutines.Continuation"
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()

    /**
     * 评论区只在 TheseusTabPagerService 的直接配置依赖中寻找 List<TabPage> 构造器，
     * 并要求 TabPage 存在返回含 Comment 枚举值的唯一无参方法，避免按混淆类名猜测。
     */
    internal fun locateCommentSection(loader: ClassLoader): CommentSectionPoints? = runCatching {
        val constructors = ArrayList<ListConstructorPoint>()
        var tagGetter: HookPoint? = null

        fun inspectConstructor(ownerConstructor: java.lang.reflect.Constructor<*>) {
            if (ownerConstructor.isSynthetic) return
            ownerConstructor.genericParameterTypes.forEachIndexed { index, genericType ->
                if (!(ownerConstructor.parameterTypes[index] isSubclassOf classOf<List<*>>())) {
                    return@forEachIndexed
                }
                val parameterized = genericType as? ParameterizedType ?: return@forEachIndexed
                val itemClass = parameterized.actualTypeArguments.singleOrNull()
                    ?.rawClassOrNull() ?: return@forEachIndexed
                val getter = KavaMemberLookup.methods(
                    itemClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && method.parameterCount == 0 && method.returnType.isEnum &&
                        method.returnType.enumConstants
                            ?.filterIsInstance<Enum<*>>()
                            ?.any { it.name.equals("Comment", ignoreCase = true) } == true
                }.distinctBy(Method::toGenericString).singleOrNull() ?: return@forEachIndexed

                val point = getter.toHookPoint()
                if (tagGetter != null && tagGetter != point) return@forEachIndexed
                tagGetter = point
                constructors += ListConstructorPoint(
                    ownerConstructor.declaringClass.name,
                    ownerConstructor.parameterTypes.map { it.name },
                    index
                )
            }
        }

        THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .forEach { service ->
                KavaMemberLookup.declaredConstructors(service, makeAccessible = true)
                    .forEach { serviceConstructor ->
                        inspectConstructor(serviceConstructor)
                        serviceConstructor.parameterTypes.asSequence()
                            .filter { parameter ->
                                !parameter.isPrimitive && !parameter.isArray &&
                                    parameter.classLoader === service.classLoader
                            }
                            .forEach { parameter ->
                                KavaMemberLookup.declaredConstructors(
                                    parameter,
                                    makeAccessible = true
                                ).forEach(::inspectConstructor)
                            }
                    }
            }

        val distinctConstructors = constructors.distinctBy {
            "${it.className}(${it.paramClassNames.joinToString(",")})#${it.listParameterIndex}"
        }
        CommentSectionPoints(
            listConstructors = distinctConstructors.takeIf { it.isNotEmpty() }
                ?: return@runCatching null,
            locatableTagGetter = tagGetter ?: return@runCatching null
        )
    }.getOrNull()
}
