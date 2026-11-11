package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 在公开 protobuf 评论列表边界按正文关键词、用户等级、@ 整条、发布者和 AI 账号特征过滤。
 *
 * 五条判据共用同一批列表 getter Hook：多开一个判据不会多挂一个 Hook，只是在同一次遍历里
 * 多读几个字段，而且只读当前真的启用的那几个。
 *
 * 覆盖单位口径：
 * - 列表 / 置顶 getter 各算一个单位。
 * - 关键词、等级、@ 整条、发布者与 AI 账号分别校验依赖：**用户开了就各算一个单位**，读取路径缺失时计入分母、
 *   不计入分子，让"开了却读不到"表现为 `partial` 而不是悄悄失效。
 *
 * AI 账号判据（[FishScorePolicy]）只用已有的昵称、等级、正文三条读取路径，不新增 Hook 点，也不联网。
 * 它比其它判据贵，所以排在 [shouldRemove] 的最后（前面任一判据命中就不再算），列表 getter 路径上
 * 同一个列表实例只算一次（[ReplyVerdictMemo]）。
 */
internal class CommentFilterFeatureInstaller(
    keywordFilterEnabled: Boolean,
    rawKeywords: String,
    minimumLevelFilterEnabled: Boolean,
    minimumLevel: Int,
    removeAtOnlyComments: Boolean = false,
    userFilterEnabled: Boolean = false,
    rawUserRules: String = "",
    private val points: VersionAdapter.CommentFilterPoints?,
    /**
     * 智能过滤评论（JEV）。两层：① `ReplyMoss` 评论列表响应到达时（后台线程）只读正文、提前判定，
     * 开了"首屏等待"就在该回调里等结果，第一屏即生效；② 列表 getter 只查缓存，未命中投后台，
     * 下次加载生效。getter 可能在主线程，所以这一层绝不联网。
     */
    private val semanticJudge: SemanticJudge? = null,
    /** debug 构建的观测日志目录；release 为 null。 */
    private val semanticLogDir: java.io.File? = null,
    /** 屏蔽 AI 评论与回复：离线加权打分，启用时才定型出判据。 */
    fishFilter: FishScorePolicy.Config = FishScorePolicy.Config.DISABLED,
    /** 主线程上不做同步联网；单测可替换。 */
    private val isMainThread: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (keywords.isNotEmpty()) add("comments_keyword_filter_enabled")
        if (minimumLevel != null) add("comments_minimum_level_filter_enabled")
        if (removeAtOnly) add("comments_at_only_removed")
        if (!userRules.isEmpty()) add("comments_user_filter_enabled")
        if (semanticJudge != null) add("comments_semantic_filter_enabled")
        if (fishPlan != null) add("comments_fish_filter_enabled")
    }

    private val keywords = if (keywordFilterEnabled) {
        RuleSetCodec.parse(rawKeywords).take(MAX_KEYWORDS).toCollection(linkedSetOf())
    } else {
        emptySet()
    }
    private val minimumLevel = if (minimumLevelFilterEnabled) {
        minimumLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
    } else {
        null
    }
    private val fishPlan = fishFilter.plan()
    private val removeAtOnly = removeAtOnlyComments
    private val userRules = if (userFilterEnabled) {
        AuthorRuleSet.parse(rawUserRules)
    } else {
        AuthorRuleSet.EMPTY
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (keywords.isEmpty() && minimumLevel == null && !removeAtOnly && userRules.isEmpty() &&
            semanticJudge == null && fishPlan == null
        ) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = Accessors(
            content = adapted.contentGetter?.let { resolve(environment, "content", it) },
            message = adapted.messageGetter?.let { resolve(environment, "message", it) },
            member = adapted.memberGetter?.let { resolve(environment, "member", it) },
            level = adapted.levelGetter?.let { resolve(environment, "level", it) },
            memberV2 = adapted.memberV2Getter?.let {
                resolve(environment, "member_v2", it)
            },
            memberV2Basic = adapted.memberV2BasicGetter?.let {
                resolve(environment, "member_v2_basic", it)
            },
            memberV2Level = adapted.memberV2LevelGetter?.let {
                resolve(environment, "member_v2_level", it)
            },
            atNameCount = adapted.atNameCountGetter?.let {
                resolve(environment, "at_count", it)
            },
            atNameMap = adapted.atNameMapGetter?.let { resolve(environment, "at_map", it) },
            memberName = adapted.memberNameGetter?.let {
                resolve(environment, "member_name", it)
            },
            memberMid = adapted.memberMidGetter?.let { resolve(environment, "member_mid", it) },
            memberV2Name = adapted.memberV2NameGetter?.let {
                resolve(environment, "member_v2_name", it)
            },
            memberV2Mid = adapted.memberV2MidGetter?.let {
                resolve(environment, "member_v2_mid", it)
            }
        )

        // 判据可用性只算一次：热路径只做布尔判断，不再重复检查 Method 是否为 null。
        val plan = JudgementPlan(
            keywords = if (accessors.hasMessagePath) keywords else emptySet(),
            minimumLevel = minimumLevel?.takeIf { accessors.hasLevelPath },
            removeAtOnly = removeAtOnly && accessors.hasAtPath,
            userRules = userRules.available(accessors.hasAuthorNamePath, accessors.hasAuthorMidPath),
            semanticEnabled = semanticJudge != null && accessors.hasMessagePath,
            fish = fishPlan?.takeIf { accessors.hasMessagePath || accessors.hasAuthorNamePath }
                ?.takeIf { fish ->
                    // 白名单读不到的那一半无法保护任何人：宁可整条判据不装，也不在保护失效时静默删。
                    fish.whitelist.available(accessors.hasAuthorNamePath, accessors.hasAuthorMidPath) == fish.whitelist
                }
        )
        if (!plan.hasAnyJudgement) return missing(environment, "missing-judgement-getter")

        var installed = 0
        adapted.replyListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("comment.filter.list.$index", point) {
                    after {
                        // Kotlin 新通道读原始列表时不过滤，见 [KotlinMossChannel.raw]。
                        if (KotlinMossChannel.isRaw()) return@after
                        val source = result as? List<*> ?: return@after
                        if (source.isEmpty()) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val semantic = semanticVerdicts(source, accessors, plan)
                        // 评分比其它判据贵：getter 在滚动、绑定时会被反复调用，同一个列表实例只算一次。
                        val rules = if (plan.fish != null) {
                            ruleVerdictMemo.getOrCompute(source) { ruleVerdicts(source, accessors, plan) }
                        } else {
                            null
                        }
                        val filtered = filterComments(source) { reply ->
                            semantic?.get(reply) == SemanticVerdict.BLOCK ||
                                (rules?.get(reply) ?: shouldRemove(readSignals(reply, accessors, plan), plan))
                        }
                        if (filtered !== source) {
                            result = filtered
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "comment_filter_list_$index",
                    "[BIL] 评论过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        val defaultReply = adapted.replyDefaultInstanceGetter?.let { point ->
            resolve(environment, "reply_default", point)?.let { getter ->
                runCatching { getter.invoke(null) }.getOrNull()
            }
        }
        if (defaultReply != null) {
            adapted.topReplyGetters.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.filter.top.$index", point) {
                        after {
                            if (KotlinMossChannel.isRaw()) return@after
                            val reply = result ?: return@after
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            // 单条置顶：绕过列表 memo（每次都是新包装，缓存它只会挤掉真正的列表条目）。
                            val semanticBlocked = plan.semanticEnabled &&
                                computeSemanticVerdicts(listOf(reply), accessors)?.get(reply) == SemanticVerdict.BLOCK
                            if (semanticBlocked || shouldRemove(readSignals(reply, accessors, plan), plan)) {
                                result = defaultReply
                                environment.reportRuntimeEvidence(
                                    ID,
                                    FeatureRuntimeStage.APPLIED
                                )
                            }
                        }
                    }
                    installed += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_filter_top_$index",
                        "[BIL] 置顶评论过滤 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
        }
        // KMP 评论页走 Kotlin KReplyMoss：兜底通道，不计入覆盖单位；Java getter 一个都装不上时也照装。
        installKotlinChannel(environment, accessors, plan)
        if (installed == 0) return missing(environment, "registration-failed")
        // 提前判定层是加速通道，不计入覆盖单位：装不上时仍按"下次加载生效"工作。
        if (plan.semanticEnabled) installMossPrefetch(environment, accessors)
        val sharedInstalled = installed
        val sharedExpected = adapted.replyListGetters.size + adapted.topReplyGetters.size
        for (capability in capabilityIds) {
            val usable = when (capability) {
                "comments_minimum_level_filter_enabled" -> accessors.hasLevelPath
                "comments_at_only_removed" -> plan.removeAtOnly
                "comments_user_filter_enabled" -> !plan.userRules.isEmpty()
                "comments_semantic_filter_enabled" -> plan.semanticEnabled
                "comments_fish_filter_enabled" -> plan.fish != null && accessors.hasMessagePath &&
                    accessors.hasAuthorNamePath
                else -> plan.keywords.isNotEmpty()
            }
            environment.reportCapabilityCoverage(capability, usable, sharedInstalled,
                sharedExpected + if (capability == "comments_user_filter_enabled" && plan.userRules != userRules) 1 else 0)
        }

        // 判据覆盖：用户开了但适配读不到的判据，要在分母里留下痕迹。
        var expected = sharedExpected
        val degraded = ArrayList<String>(4)
        if (keywords.isNotEmpty()) {
            expected += 1
            if (plan.keywords.isNotEmpty()) installed += 1 else degraded += "keyword"
        }
        if (minimumLevel != null) {
            expected += 1
            if (plan.minimumLevel != null) installed += 1 else degraded += "level"
        }
        if (removeAtOnly) {
            expected += 1
            if (plan.removeAtOnly) installed += 1 else degraded += "at-only"
        }
        if (!userRules.isEmpty()) {
            expected += 1
            if (plan.userRules == userRules) installed += 1 else degraded += "author"
        }
        if (semanticJudge != null) {
            expected += 1
            if (plan.semanticEnabled) installed += 1 else degraded += "semantic"
        }
        if (fishPlan != null) {
            expected += 1
            // 昵称与正文缺一条仍会按剩下的信号打分，但召回大幅下降，要在分母里留下痕迹。
            if (plan.fish != null && accessors.hasMessagePath && accessors.hasAuthorNamePath) installed += 1
            else degraded += "fish"
        }
        if (degraded.isNotEmpty()) {
            environment.logError(
                "comment_filter_degraded",
                "[BIL] 评论过滤判据缺少可用读取路径: ${degraded.joinToString(",")}"
            )
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) {
            "success"
        } else {
            "partial:$installed/$expected"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "comment_filter_ok",
                "[BIL] 评论过滤已安装，hooks=$installed，判据=${plan.describe()}"
            )
        } else {
            environment.logError(
                "comment_filter_partial",
                "[BIL] 评论过滤部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    /**
     * getter 层：整批取正文，缓存命中直接给结论，未命中投后台（不阻塞、不联网）。
     * 正文读取与 [readSignals] 同一路径，保证缓存键一致。
     */
    private fun semanticVerdicts(
        source: List<*>,
        accessors: Accessors,
        plan: JudgementPlan
    ): java.util.IdentityHashMap<Any, SemanticVerdict>? {
        if (semanticJudge == null || !plan.semanticEnabled) return null
        return semanticMemo.getOrCompute(source) { computeSemanticVerdicts(source, accessors) }
    }

    private val semanticMemo = SemanticListMemo()
    private val ruleVerdictMemo = ReplyVerdictMemo()

    /** 整个列表按规则判据一次判完（含 AI 账号打分）；与逐条 [shouldRemove] 同一条判定。 */
    private fun ruleVerdicts(
        source: List<*>,
        accessors: Accessors,
        plan: JudgementPlan
    ): java.util.IdentityHashMap<Any, Boolean> {
        val verdicts = java.util.IdentityHashMap<Any, Boolean>(source.size)
        for (reply in source) {
            if (reply != null) verdicts[reply] = shouldRemove(readSignals(reply, accessors, plan), plan)
        }
        return verdicts
    }

    private fun computeSemanticVerdicts(
        source: List<*>,
        accessors: Accessors
    ): java.util.IdentityHashMap<Any, SemanticVerdict>? {
        val judge = semanticJudge ?: return null
        val replies = source.filterNotNull()
        if (replies.isEmpty()) return null
        val texts = replies.map { messageOf(it, accessors) }
        val verdicts = judge.evaluate(texts, SemanticMode.PREFETCH, onReport = reportTo("comment-getter"))
        return java.util.IdentityHashMap<Any, SemanticVerdict>(replies.size).apply {
            replies.forEachIndexed { index, reply -> put(reply, verdicts[index]) }
        }
    }

    private fun messageOf(reply: Any, accessors: Accessors): String {
        val content = invokeCompatible(accessors.content, reply)
        return invokeCompatible(accessors.message, content)?.toString()?.trim().orEmpty()
    }

    /**
     * 提前判定层：观察 `ReplyMoss` 的评论列表响应（主楼 / 楼中楼 / 对话），只读不改。
     * 回调在后台线程时按设置等待或投后台；在主线程时只投后台。
     */
    private fun installMossPrefetch(environment: HookEnvironment, accessors: Accessors) {
        val judge = semanticJudge ?: return
        val replyInfoClass = accessors.content?.declaringClass ?: return
        val loader = replyInfoClass.classLoader ?: return
        val packageName = replyInfoClass.name.substringBeforeLast('.')
        val moss = KavaMemberLookup.classOrNull(loader, "$packageName.ReplyMoss") ?: run {
            environment.logInfo("comment_semantic_moss_missing", "[BIL] 智能过滤评论：未找到 ReplyMoss，仅用列表缓存层")
            return
        }
        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER_CLASS)
        val readers = ConcurrentHashMap<Class<*>, List<Method>>()
        fun observe(response: Any) {
            // 收正文必须走原始读取：这里的列表 getter 正是本安装器挂钩的那些，嵌套调用
            // 会让 after 回调抢先以 PREFETCH 认领全部 key，observe 自己的 WAIT 拿到空
            // claimedKeys 直接返回全 UNKNOWN，"首屏等待"永远空转。与 prewarmSemantic 同口径。
            val texts = KotlinMossChannel.raw { collectReplyTexts(response, replyInfoClass, accessors, readers) }
            if (texts.isEmpty()) return
            val mode = if (judge.waitFirstScreen && !isMainThread()) SemanticMode.WAIT else SemanticMode.PREFETCH
            judge.evaluate(texts, mode, onReport = reportTo("comment-moss"))
        }
        var hooks = 0
        PREFETCH_RPCS.forEach { rpc ->
            if (handlerClass != null) {
                KavaMemberLookup.declaredMethods(moss, makeAccessible = true) {
                    !it.isStatic && it.name == rpc && it.parameterCount == 2 &&
                        it.parameterTypes[1] == handlerClass && it.returnType == Void.TYPE
                }.singleOrNull()?.let { method ->
                    runCatching {
                        environment.registrar.exact("comment.semantic.async.$rpc", method.declaringClass,
                            method.name, *method.parameterTypes) {
                            before {
                                val delegate = args.getOrNull(1) ?: return@before
                                // 模块自己发起的请求（回复拓扑翻页等）不判定：那不是用户正在看的评论列表，
                                // 判了既额外计费，开"首屏等待"时还会拖慢拓扑面板。
                                if (isModuleOwnedHandler(delegate)) return@before
                                MossResponseHandlerProxy.wrap(handlerClass, delegate) { response -> observe(response) }
                                    ?.let { args[1] = it }
                            }
                        }
                        hooks += 1
                    }
                }
            }
            val syncName = "execute" + rpc.replaceFirstChar(Char::uppercaseChar)
            KavaMemberLookup.declaredMethods(moss, makeAccessible = true) {
                !it.isStatic && it.name == syncName && it.parameterCount == 1
            }.singleOrNull()?.let { method ->
                runCatching {
                    environment.registrar.exact("comment.semantic.sync.$rpc", method.declaringClass,
                        method.name, *method.parameterTypes) {
                        after {
                            if (hasThrowable) return@after
                            result?.let { observe(it) }
                        }
                    }
                    hooks += 1
                }
            }
        }
        environment.logInfo("comment_semantic_moss", "[BIL] 智能过滤评论：提前判定边界 $hooks 个")
    }

    /**
     * KMP 评论页（`kntr.common.comment.page`，9.14.0 的 `PresetListPageRepo` / `PresetDetailPageRepo`）
     * 直接调 `KReplyMoss.mainList/detailList`，拿到的是 Kotlin 数据类，上面的 Java getter 过滤碰不到。
     * 这里经 [KotlinMossChannel] 往返到 Java `MainListReply` 等，用 [ProtobufReplyTreeRewriter] 删评论
     * （主楼、子回复预览、置顶位），判据与 getter 层完全相同；智能过滤在回调里按"首屏等待"设置判定。
     */
    private fun installKotlinChannel(environment: HookEnvironment, accessors: Accessors, plan: JudgementPlan) {
        // 装不上要留下原因，否则末尾那句"N 个"读起来像"不适用"，而不是"类没找到"（按 RPC 有界）。
        fun skip(reason: String) {
            environment.logInfo("comment_filter_kmoss_skip", "[BIL] 评论过滤新通道未接入: $reason")
        }
        val replyInfoClass = accessors.content?.declaringClass ?: return skip("no-reply-info")
        val loader = replyInfoClass.classLoader ?: return skip("no-class-loader")
        val packageName = replyInfoClass.name.substringBeforeLast('.')
        val members = KotlinMossChannel.prepare(environment, loader, "评论过滤", KMOSS_LOG_KEY) ?: return skip("no-bridge")
        val decide: (List<Any>) -> Set<Any> = { replies -> kotlinDecide(environment, replies, accessors, plan) }
        val rewriter = kotlinRewriter(replyInfoClass, "mainList", decide)
        val singleRewriter = kotlinRewriter(replyInfoClass, "replyInfo", decide)
        val readers = ConcurrentHashMap<Class<*>, List<Method>>()
        var hooks = 0
        KMOSS_RPCS.forEach { (rpc, replyName) ->
            val replyClass = KavaMemberLookup.classOrNull(loader, "$packageName.$replyName")
                ?: return@forEach skip("no-reply-class:$rpc")
            val rpcRewriter = if (rpc == "replyInfo") singleRewriter else rewriter
            val installed = KotlinMossChannel.install(
                environment, loader, members,
                javaMossClassName = "$packageName.ReplyMoss",
                rpc = rpc,
                javaReplyClass = replyClass,
                hookId = "comment.filter.kmoss.$rpc",
                what = "评论过滤",
                logKey = KMOSS_LOG_KEY,
                shareUnchangedReply = true,
                confirmedTransform = { javaReply ->
                    prewarmSemantic(javaReply, replyInfoClass, accessors, plan, readers)
                    val result = rpcRewriter.rewrite(javaReply)
                    KotlinMossResponseHandlerProxy.ConfirmedReply(result.message) {
                        if (result.removed > 0) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, result.removed)
                    }
                }
            ) { it }
            if (installed) hooks += 1
        }
        environment.logInfo("comment_filter_kmoss", "[BIL] 评论过滤：Kotlin 新通道 $hooks 个")
    }

    /**
     * 整份响应的正文一次性送判（与 Java 链路的提前判定层同一批口径），回调在后台线程且开了"首屏等待"时在这里等；
     * 之后 [kotlinDecide] 按层只查缓存，不会每层各发一次阻塞请求。
     */
    private fun prewarmSemantic(
        javaReply: Any,
        replyInfoClass: Class<*>,
        accessors: Accessors,
        plan: JudgementPlan,
        readers: ConcurrentHashMap<Class<*>, List<Method>>
    ) {
        val judge = semanticJudge?.takeIf { plan.semanticEnabled } ?: return
        val texts = KotlinMossChannel.raw { collectReplyTexts(javaReply, replyInfoClass, accessors, readers) }
        if (texts.isEmpty()) return
        val mode = if (judge.waitFirstScreen && !isMainThread()) SemanticMode.WAIT else SemanticMode.PREFETCH
        judge.evaluate(texts, mode, onReport = reportTo("comment-kmoss"))
    }

    /** 同一层的一批评论 → 要删的那些（按引用）。在 moss 回调里，可能是后台线程。 */
    private fun kotlinDecide(
        environment: HookEnvironment,
        replies: List<Any>,
        accessors: Accessors,
        plan: JudgementPlan
    ): Set<Any> {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        val judge = semanticJudge?.takeIf { plan.semanticEnabled }
        // 整份响应已在 [prewarmSemantic] 里判过，这里只取缓存结论（未命中投后台，不阻塞）。
        val semantic = judge?.evaluate(replies.map { reply -> messageOf(reply, accessors) }, SemanticMode.PREFETCH)
        val drop = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        replies.forEachIndexed { index, reply ->
            if (semantic?.getOrNull(index) == SemanticVerdict.BLOCK ||
                shouldRemove(readSignals(reply, accessors, plan), plan)
            ) drop += reply
        }
        return drop
    }

    /** 回调是本模块创建的动态代理（`Proxy` 的调用处理器由模块类加载器加载）。 */
    private fun isModuleOwnedHandler(handler: Any): Boolean = runCatching {
        java.lang.reflect.Proxy.isProxyClass(handler.javaClass) &&
            java.lang.reflect.Proxy.getInvocationHandler(handler).javaClass.classLoader ==
            classOf<CommentFilterFeatureInstaller>().classLoader
    }.getOrDefault(false)

    /** 一层反射：响应对象上所有返回 ReplyInfo 或 List<ReplyInfo> 的 getter，外加每条 ReplyInfo 的子回复。 */
    private fun collectReplyTexts(
        response: Any,
        replyInfoClass: Class<*>,
        accessors: Accessors,
        readers: ConcurrentHashMap<Class<*>, List<Method>>
    ): List<String> {
        fun gettersOf(type: Class<*>): List<Method> = readers.getOrPut(type) {
            type.methods.filter { method ->
                !method.isStatic && method.parameterCount == 0 && method.name.startsWith("get") &&
                    (method.returnType == replyInfoClass || method.returnType isSubclassOf classOf<List<*>>())
            }
        }
        val out = LinkedHashSet<String>()
        fun addReply(reply: Any?, depth: Int) {
            if (reply == null || !replyInfoClass.isInstance(reply) || out.size >= MOSS_MAX_TEXTS) return
            messageOf(reply, accessors).takeIf(String::isNotEmpty)?.let(out::add)
            if (depth > 0) return
            gettersOf(replyInfoClass).filter { it.returnType isSubclassOf classOf<List<*>>() }.forEach { getter ->
                (runCatching { getter.invoke(reply) }.getOrNull() as? List<*>)?.forEach { addReply(it, depth + 1) }
            }
        }
        gettersOf(response.javaClass).forEach { getter ->
            when (val value = runCatching { getter.invoke(response) }.getOrNull()) {
                is List<*> -> value.forEach { addReply(it, 0) }
                else -> addReply(value, 0)
            }
        }
        return out.toList()
    }

    private fun reportTo(source: String): ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)? {
        val dir = semanticLogDir ?: return null
        return { report, texts, verdicts ->
            val blocked = texts.indices.filter { verdicts[it] == SemanticVerdict.BLOCK }
                .joinToString(" | ") { texts[it].take(24) }
            SemanticDebugLog.append(
                dir,
                "${System.currentTimeMillis()} $source thread=${Thread.currentThread().name} total=${report.total} " +
                    "requested=${report.requested} blocked=${report.blocked} ms=${report.elapsedMs} " +
                    "outcome=${report.outcome}${report.extras()} :: $blocked"
            )
        }
    }

    /** 只读当前启用判据真正需要的字段；未启用的判据一次反射都不做。 */
    private fun readSignals(
        reply: Any,
        accessors: Accessors,
        plan: JudgementPlan
    ): Signals {
        val needContent = plan.needsMessage || plan.removeAtOnly
        val content = if (needContent) invokeCompatible(accessors.content, reply) else null
        val message = if (plan.needsMessage) {
            invokeCompatible(accessors.message, content)?.toString()
        } else {
            null
        }
        val atNames = if (plan.removeAtOnly) readAtNames(content, accessors) else null

        var level: Int? = null
        var authorName: String? = null
        var authorMid: Long? = null
        val fish = plan.fish
        val needAuthor = !plan.userRules.isEmpty() || fish != null
        if (plan.minimumLevel != null || needAuthor) {
            val member = invokeCompatible(accessors.member, reply)
            val memberV2 = invokeCompatible(accessors.memberV2, reply)
            val memberV2Basic = invokeCompatible(accessors.memberV2Basic, memberV2)
            if (plan.minimumLevel != null || fish != null) {
                level = (invokeCompatible(accessors.level, member) as? Number)?.toInt()
                    ?: (invokeCompatible(accessors.memberV2Level, memberV2Basic) as? Number)
                        ?.toInt()
            }
            if (needAuthor) {
                authorName = (invokeCompatible(accessors.memberName, member) as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: (invokeCompatible(accessors.memberV2Name, memberV2Basic) as? String)
                        ?.takeIf(String::isNotBlank)
                // UID 只有发布者过滤和 AI 账号白名单里写了 UID 时才读。
                if (!plan.userRules.isEmpty() || fish?.whitelist?.mids?.isNotEmpty() == true) {
                    authorMid = (invokeCompatible(accessors.memberMid, member) as? Number)?.toLong()
                        ?.takeIf { it > 0L }
                        ?: (invokeCompatible(accessors.memberV2Mid, memberV2Basic) as? Number)
                            ?.toLong()
                            ?.takeIf { it > 0L }
                }
            }
        }
        return Signals(
            message = message,
            level = level,
            atNames = atNames,
            authorName = authorName,
            authorMid = authorMid
        )
    }

    /** 先读计数：没有 @ 的评论（绝大多数）连 Map 视图都不会构造。 */
    private fun readAtNames(content: Any?, accessors: Accessors): Set<String>? {
        content ?: return null
        val count = (invokeCompatible(accessors.atNameCount, content) as? Number)?.toInt()
            ?: return null
        if (count <= 0) return emptySet()
        val map = invokeCompatible(accessors.atNameMap, content) as? Map<*, *> ?: return null
        return map.keys.asSequence()
            .filterIsInstance<String>()
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    }

    private fun invokeCompatible(method: Method?, target: Any?): Any? {
        if (method == null || target == null || !method.declaringClass.isInstance(target)) {
            return null
        }
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "comment.filter.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "comment_filter_missing",
            "[BIL] 评论过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val message: String? = null,
        val level: Int? = null,
        /** null = 本次没读 @ 名单（判据未启用或读取失败），不能当成"没有 @"。 */
        val atNames: Set<String>? = null,
        val authorName: String? = null,
        val authorMid: Long? = null
    )

    /** 安装期定型的判据集合；热路径只读它，不再回头判断适配是否完整。 */
    internal data class JudgementPlan(
        val keywords: Set<String>,
        val minimumLevel: Int?,
        val removeAtOnly: Boolean,
        val userRules: AuthorRuleSet,
        /** 智能过滤：语义结论由 getter 层单独读正文取得，不走 [needsMessage]。 */
        val semanticEnabled: Boolean = false,
        /** 屏蔽 AI 评论与回复：null = 未启用，或读取路径 / 白名单保护不可用。 */
        val fish: FishScorePolicy.Plan? = null
    ) {
        val needsMessage: Boolean
            get() = keywords.isNotEmpty() || removeAtOnly || fish != null

        val hasAnyJudgement: Boolean
            get() = keywords.isNotEmpty() || minimumLevel != null || removeAtOnly ||
                !userRules.isEmpty() || semanticEnabled || fish != null

        fun describe(): String = buildList {
            if (keywords.isNotEmpty()) add("keyword=${keywords.size}")
            minimumLevel?.let { add("level>=$it") }
            if (removeAtOnly) add("at-only")
            if (!userRules.isEmpty()) {
                add("author=${userRules.mids.size}uid+${userRules.names.size}name")
            }
            if (semanticEnabled) add("semantic")
            fish?.let { add(it.describe()) }
        }.joinToString("/")
    }

    private data class Accessors(
        val content: Method?,
        val message: Method?,
        val member: Method?,
        val level: Method?,
        val memberV2: Method?,
        val memberV2Basic: Method?,
        val memberV2Level: Method?,
        val atNameCount: Method?,
        val atNameMap: Method?,
        val memberName: Method?,
        val memberMid: Method?,
        val memberV2Name: Method?,
        val memberV2Mid: Method?
    ) {
        val hasMessagePath: Boolean get() = content != null && message != null
        val hasLevelPath: Boolean
            get() = (member != null && level != null) ||
                (memberV2 != null && memberV2Basic != null && memberV2Level != null)

        val hasAtPath: Boolean
            get() = hasMessagePath && atNameCount != null && atNameMap != null

        val hasAuthorNamePath: Boolean get() = (member != null && memberName != null) ||
            (memberV2 != null && memberV2Basic != null && memberV2Name != null)
        val hasAuthorMidPath: Boolean get() = (member != null && memberMid != null) ||
            (memberV2 != null && memberV2Basic != null && memberV2Mid != null)
    }

    companion object {
        const val ID = "comment_filter"
        const val DEFAULT_MIN_LEVEL = 3
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_filter_status"
        private const val MAX_KEYWORDS = 64
        private const val MIN_LEVEL = 1
        private const val MAX_LEVEL = 6
        private const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
        /** 评论列表 RPC：主楼、楼中楼、对话。只观察，不改写响应。 */
        private val PREFETCH_RPCS = listOf("mainList", "detailList", "dialogList")
        /** Kotlin 新通道：RPC → 同一 proto 的 Java 响应类简单名（与 `ReplyMoss` 同包）。 */
        private val KMOSS_RPCS = listOf(
            "mainList" to "MainListReply",
            "detailList" to "DetailListReply",
            "dialogList" to "DialogListReply",
            "foldList" to "FoldListResp",
            "hotspotPage" to "HotspotPageResp",
            "replyInfo" to "ReplyInfoReply"
        )
        internal fun kotlinRewriter(replyInfoClass: Class<*>, rpc: String,
            decide: (List<Any>) -> Set<Any>): ProtobufReplyTreeRewriter = ProtobufReplyTreeRewriter(
            replyInfoClass,
            removableSingles = if (rpc == "replyInfo") ProtobufReplyTreeRewriter.DEFAULT_REMOVABLE_SINGLES + "Reply"
                else ProtobufReplyTreeRewriter.DEFAULT_REMOVABLE_SINGLES,
            decide = decide
        )
        private const val KMOSS_LOG_KEY = "comment_filter_kmoss"
        /** 单次响应最多提取的不同正文数（主楼约 20 条 + 子回复预览）。 */
        private const val MOSS_MAX_TEXTS = 80

        /**
         * 去掉 @ 名字后仍算"空正文"的残留标点。
         *
         * 空白另由 Char.isWhitespace 判断，这里只收分隔性标点；表情、汉字、字母数字一律
         * 留给“有正文”那一侧，宁可漏删也不错删。
         */
        private const val AT_ONLY_RESIDUE_PUNCTUATION = ",，.。、;；:：!！?？~～·|/-—_+&*"

        /** 读取失败时保守放行；只有明确命中某条判据才删除。 */
        internal fun shouldRemove(
            signals: Signals,
            plan: JudgementPlan
        ): Boolean = RuleSetCodec.matches(plan.keywords, signals.message) ||
            (plan.minimumLevel != null && signals.level?.let { it < plan.minimumLevel } == true) ||
            (plan.removeAtOnly && isAtOnlyComment(signals.message, signals.atNames)) ||
            (!plan.userRules.isEmpty() &&
                plan.userRules.matches(signals.authorName, signals.authorMid)) ||
            // 最贵的判据放最后：前面任一条命中就不再打分。
            (plan.fish != null && FishScorePolicy.shouldRemove(
                plan.fish, signals.authorName, signals.authorMid, signals.level, signals.message
            ))

        /** 兼容旧签名的窄入口，供只关心关键词/等级两条判据的单测使用。 */
        internal fun shouldRemove(
            signals: Signals,
            keywords: Set<String>,
            minimumLevel: Int?
        ): Boolean = shouldRemove(
            signals,
            JudgementPlan(keywords, minimumLevel, removeAtOnly = false, userRules = AuthorRuleSet.EMPTY)
        )

        /**
         * 判断整条评论是否只由 @ 组成。
         *
         * [atNames] 为 null 表示这次没能读到 @ 名单——此时一律保留，绝不按"正文很短"猜。
         */
        internal fun isAtOnlyComment(message: String?, atNames: Set<String>?): Boolean {
            if (atNames.isNullOrEmpty()) return false
            val text = message ?: return false
            if (text.isBlank()) return false
            var residue = text
            // 长名字先删，避免"@张三"把"@张三丰"的前缀吃掉后留下孤立的"丰"。
            atNames.sortedByDescending(String::length).forEach { name ->
                residue = residue.replace("@$name", " ")
            }
            return residue.all { it.isWhitespace() || it in AT_ONLY_RESIDUE_PUNCTUATION }
        }

        /** 无命中返回原 List；有命中才创建不可变副本，不改写 protobuf 内部集合。 */
        internal fun filterComments(
            source: List<*>,
            shouldRemove: (Any) -> Boolean
        ): List<*> = ProtobufListRetention.filterOrSame(source, shouldRemove)
    }
}
