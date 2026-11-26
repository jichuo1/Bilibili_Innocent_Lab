package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method

/**
 * 在综合搜索结果的 protobuf 读边界过滤广告卡与指定内容。
 *
 * 这里可以用 getter 边界（而不是像弹幕、动态那样改写消息本身）：9.11.0 里
 * `SearchAllResponse#getItemList` 被业务侧 `classes14.dex` 跨 dex 引用，确实由宿主调用。
 * 改返回值不触碰宿主内部集合，无命中时连副本都不创建。
 *
 * 判据：
 * - 推广卡：`Item.hasCm()` 或 `Item.hasSpecial()`，即商业广告卡与特殊运营卡。
 * - 标题关键词 / 发布者：只作用于**视频卡**（`Item.hasAv()`）。番剧、直播、专栏等其它卡型
 *   的字段结构各不相同，本轮不猜测它们的作者字段，宁可少覆盖也不误删。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：`getItemList` 每版只有一个重载
 * 且都有跨 dex 调用方；`hasCm`/`hasSpecial`/`hasAv`/`getAv` 与视频卡的
 * `getTitle`/`getAuthor`/`getMid` 五版齐全。
 *
 * 覆盖单位口径：列表边界算一个单位；每条被用户启用、但读取路径缺失的判据计入分母不计入
 * 分子，于是"开了却没生效"表现为 `partial`。
 */
internal class SearchPurifyFeatureInstaller(
    private val removeCommercial: Boolean,
    keywordFilterEnabled: Boolean,
    rawKeywords: String,
    authorFilterEnabled: Boolean,
    rawAuthorRules: String,
    private val kmpGrpcWireEngine: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (removeCommercial) add("search_commercial_removed")
        if (keywords.isNotEmpty()) add("search_keyword_filter_enabled")
        if (authorRules.isNotEmpty()) add("search_author_filter_enabled")
    }

    private val keywords = if (keywordFilterEnabled) {
        RuleSetCodec.parse(rawKeywords).take(MAX_KEYWORDS).toCollection(linkedSetOf())
    } else {
        emptySet()
    }
    private val authorRules = if (authorFilterEnabled) {
        AuthorRuleSet.parse(rawAuthorRules)
    } else {
        AuthorRuleSet.EMPTY
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeCommercial && keywords.isEmpty() && authorRules.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val responseClass = environment.hookPoints.resolveClass(
            "search.purify.response",
            SEARCH_ALL_RESPONSE_CLASS
        ) ?: return missing(environment, "missing-search-response-class")
        val itemListGetter = KavaMemberLookup.methodOrNull(responseClass, "getItemList")
            ?.takeIf {
                !it.isStatic && it.parameterCount == 0 &&
                    it.returnType isSubclassOf classOf<List<*>>()
            } ?: return missing(environment, "missing-item-list-getter")

        val resolved = resolveMembers(loader)
        val plan = Plan(
            removeCommercial = removeCommercial && resolved?.commercial != null,
            keywords = if (resolved?.videoCard?.title != null) keywords else emptySet(),
            authorRules = authorRules.available(resolved?.videoCard?.author != null, resolved?.videoCard?.mid != null)
        )
        // 判据全部不可用时不注册：留一个永远不删东西的 Hook 只会让状态好看。
        val members = resolved.takeIf { plan.hasAnyJudgement }
            ?: return missing(environment, "missing-judgement-getter")

        val installed = runCatching {
            environment.registrar.exact(
                "search.purify.item_list",
                itemListGetter.declaringClass,
                itemListGetter.name
            ) {
                after {
                    if (hasThrowable || KotlinMossChannel.isRaw()) return@after
                    val source = result as? List<*> ?: return@after
                    if (source.isEmpty()) return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val filtered = ProtobufListRetention.filterOrSame(source) { item ->
                        shouldRemove(item, members, plan)
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
            1
        }.getOrElse { throwable ->
            environment.logError(
                "search_purify_register",
                "[BIL] 搜索结果过滤 Hook 注册失败: $throwable"
            )
            0
        }
        if (installed == 0) return missing(environment, "registration-failed")
        // 9.12.0 起新版搜索（kntr.app.search）走 Kotlin KSearchMoss.searchAll，Java getter 碰不到；兜底通道不计入覆盖单位。
        val kotlinAll = installKotlinChannel(environment, loader, responseClass, itemListGetter, members, plan)
        val category = installCategory(environment, loader, members, plan)
        val boundaryInstalled = installed + (if (kotlinAll == true) 1 else 0) + category.first
        val boundaryExpected = 1 + (if (kotlinAll != null) 1 else 0) + category.second
        val commercialComplete = resolved?.commercial?.hasCm != null && resolved.commercial.hasSpecial != null
        if (removeCommercial) environment.reportCapabilityCoverage("search_commercial_removed", plan.removeCommercial,
            boundaryInstalled, boundaryExpected + if (commercialComplete) 0 else 1)
        if (keywords.isNotEmpty()) environment.reportCapabilityCoverage("search_keyword_filter_enabled", plan.keywords.isNotEmpty(),
            boundaryInstalled, boundaryExpected)
        if (authorRules.isNotEmpty()) environment.reportCapabilityCoverage("search_author_filter_enabled", plan.authorRules.isNotEmpty(),
            boundaryInstalled, boundaryExpected + if (plan.authorRules == authorRules) 0 else 1)

        var total = boundaryInstalled
        var expected = boundaryExpected
        val degraded = ArrayList<String>(3)
        fun account(requested: Boolean, usable: Boolean, label: String) {
            if (!requested) return
            expected += 1
            if (usable) total += 1 else degraded += label
        }
        account(removeCommercial, plan.removeCommercial && commercialComplete, "commercial")
        account(keywords.isNotEmpty(), plan.keywords.isNotEmpty(), "keyword")
        account(authorRules.isNotEmpty(), plan.authorRules == authorRules, "author")
        if (degraded.isNotEmpty()) {
            environment.logError(
                "search_purify_degraded",
                "[BIL] 搜索结果过滤判据缺少可用读取路径: ${degraded.joinToString(",")}"
            )
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (total == expected) "success" else "partial:$total/$expected"
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "search_purify_ok",
                "[BIL] 搜索结果过滤已安装，判据=${plan.describe()}"
            )
        } else {
            environment.logError(
                "search_purify_partial",
                "[BIL] 搜索结果过滤部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(total, complete = total == expected)
    }

    /**
     * 新版搜索的 Kotlin 通道：经 [KotlinMossChannel] 往返到同一 proto 的 Java `SearchAllResponse`，
     * 用与 getter 层相同的判据删卡片，再以 builder 的 `clearItem` / `addAllItem` 重建。
     */
    private fun installKotlinChannel(
        environment: HookEnvironment,
        loader: ClassLoader,
        responseClass: Class<*>,
        itemListGetter: Method,
        members: Members,
        plan: Plan,
        rpc: String = "searchAll",
        stem: String = "Item"
    ): Boolean? {
        val wireSurface = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngine)
        val kotlinMossName = SEARCH_MOSS_CLASS.replace(".SearchMoss", ".KSearchMoss")
        val hasKotlinMoss = KavaMemberLookup.classOrNull(loader, kotlinMossName) != null
        if (wireSurface == null && !hasKotlinMoss) return null
        // 装不上要留下原因，否则诊断输出与"压根没尝试接入"完全无法区分（有界：三条）。
        fun skip(reason: String): Boolean {
            environment.logInfo("search_purify_kmoss_skip_$rpc", "[BIL] 搜索结果过滤 $rpc 新通道未接入: $reason")
            return false
        }
        val builder = ProtobufBuilderPlan.resolve(responseClass) ?: return skip("no-response-builder")
        val clearItem = builder.method("clear$stem") ?: return skip("no-clear-item")
        val addAllItem = builder.method("addAll$stem", classOf<Iterable<*>>()) ?: return skip("no-add-all-item")
        val transformJava: (Any) -> Any = transform@{ javaReply ->
            val source = KotlinMossChannel.raw { itemListGetter.invoke(javaReply) as? List<*> }
            if (source.isNullOrEmpty()) return@transform javaReply
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            val filtered = ProtobufListRetention.filterOrSame(source) { item -> shouldRemove(item, members, plan) }
            if (filtered === source) return@transform javaReply
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, source.size - filtered.size)
            builder.edit(javaReply) { target ->
                clearItem.invoke(target)
                addAllItem.invoke(target, filtered)
            }
        }
        if (wireSurface != null && KmpGrpcWireChannel.install(
                environment = environment,
                surface = wireSurface,
                javaMossClassName = SEARCH_MOSS_CLASS,
                rpc = rpc,
                javaReplyClass = responseClass,
                what = "搜索结果过滤",
                logKey = KMOSS_LOG_KEY,
                transformJava = transformJava
            )
        ) {
            environment.logInfo("search_purify_kmoss_$rpc", "[BIL] 搜索结果过滤 $rpc：线格式新通道已接入")
            return true
        }
        if (!hasKotlinMoss) return false
        val bridge = KotlinMossChannel.prepare(environment, loader, "搜索结果过滤", KMOSS_LOG_KEY) ?: return skip("no-bridge")
        val installed = KotlinMossChannel.install(
            environment, loader, bridge,
            javaMossClassName = SEARCH_MOSS_CLASS,
            rpc = rpc,
            javaReplyClass = responseClass,
            hookId = "search.purify.kmoss.$rpc",
            what = "搜索结果过滤",
            logKey = KMOSS_LOG_KEY,
            transformJava = transformJava
        )
        environment.logInfo("search_purify_kmoss_$rpc", "[BIL] 搜索结果过滤 $rpc：Kotlin 新通道${if (installed) "已接入" else "未接入"}")
        return installed
    }

    /** 分类结果复用同一 Item 判据；不把番剧／直播等不同模型当作视频读取。 */
    private fun installCategory(environment: HookEnvironment, loader: ClassLoader, members: Members, plan: Plan): Pair<Int, Int> {
        val response = KavaMemberLookup.classOrNull(loader, "$SEARCH_PACKAGE.SearchByTypeResponse")
            ?: return 0 to 0
        val getter = KavaMemberLookup.methodOrNull(response, "getItemsList")
            ?.takeIf { !it.isStatic && it.returnType isSubclassOf classOf<List<*>>() }
        if (getter == null) {
            environment.logError("search_category_shape", "[BIL] 分类搜索列表边界缺失")
            return 0 to 1
        }
        var installed = 0
        runCatching {
            environment.registrar.exact("search.purify.category.items", response, getter.name) {
                after {
                    if (hasThrowable || KotlinMossChannel.isRaw()) return@after
                    val source = result as? List<*> ?: return@after
                    if (source.isEmpty()) return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val filtered = ProtobufListRetention.filterOrSame(source) { shouldRemove(it, members, plan) }
                    if (filtered !== source) {
                        result = filtered
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, source.size - filtered.size)
                    }
                }
            }
            installed++
        }.onFailure { environment.logError("search_category_registration", "[BIL] 分类搜索过滤注册失败") }
        val kotlin = installKotlinChannel(environment, loader, response, getter, members, plan, "searchByType", "Items")
        val expected = 1 + if (kotlin != null) 1 else 0
        if (kotlin == true) installed++
        environment.reportStatus("search_category_layers", "$installed/$expected")
        return installed to expected
    }

    private fun shouldRemove(item: Any, members: Members, plan: Plan): Boolean {
        if (plan.removeCommercial && members.commercial != null) {
            val commercial = members.commercial
            if (invoke(commercial.hasCm, item) as? Boolean == true) return true
            if (invoke(commercial.hasSpecial, item) as? Boolean == true) return true
        }
        val video = members.videoCard ?: return false
        if (plan.keywords.isEmpty() && plan.authorRules.isEmpty()) return false
        // 只有视频卡才有确定的标题/作者字段；其它卡型直接放行。
        if (invoke(video.hasAv, item) as? Boolean != true) return false
        val card = invoke(video.avGetter, item) ?: return false
        if (plan.keywords.isNotEmpty()) {
            val title = invoke(video.title, card) as? String
            if (RuleSetCodec.matches(plan.keywords, title)) return true
        }
        if (plan.authorRules.isNotEmpty()) {
            val author = (invoke(video.author, card) as? String)?.takeIf(String::isNotBlank)
            val mid = (invoke(video.mid, card) as? Number)?.toLong()?.takeIf { it > 0L }
            if (plan.authorRules.matches(author, mid)) return true
        }
        return false
    }

    private fun resolveMembers(loader: ClassLoader): Members? {
        val itemClass = KavaMemberLookup.classOrNull(loader, SEARCH_ITEM_CLASS) ?: return null
        val commercial = run {
            val hasCm = booleanNoArg(itemClass, "hasCm")
            val hasSpecial = booleanNoArg(itemClass, "hasSpecial")
            if (hasCm == null && hasSpecial == null) null else CommercialMembers(hasCm, hasSpecial)
        }
        val videoCard = run {
            val hasAv = booleanNoArg(itemClass, "hasAv")
            val avGetter = KavaMemberLookup.methodOrNull(itemClass, "getAv")?.takeIf {
                !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive
            }
            if (hasAv == null || avGetter == null) return@run null
            val cardClass = avGetter.returnType
            val title = stringNoArg(cardClass, "getTitle")
            val author = stringNoArg(cardClass, "getAuthor")
            val mid = KavaMemberLookup.methodOrNull(cardClass, "getMid")?.takeIf {
                !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Long>()
            }
            if (title == null && author == null && mid == null) {
                null
            } else {
                VideoCardMembers(hasAv, avGetter, title, author, mid)
            }
        }
        if (commercial == null && videoCard == null) return null
        return Members(commercial, videoCard)
    }

    private fun invoke(method: Method?, target: Any?): Any? {
        if (method == null || target == null || !method.declaringClass.isInstance(target)) {
            return null
        }
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun booleanNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Boolean>()
        }

    private fun stringNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<String>()
        }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "search_purify_missing",
            "[BIL] 搜索结果过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    /** 安装期定型的判据集合；热路径只读它。 */
    private data class Plan(
        val removeCommercial: Boolean,
        val keywords: Set<String>,
        val authorRules: AuthorRuleSet
    ) {
        val hasAnyJudgement: Boolean
            get() = removeCommercial || keywords.isNotEmpty() || authorRules.isNotEmpty()

        fun describe(): String = buildList {
            if (removeCommercial) add("commercial")
            if (keywords.isNotEmpty()) add("keyword=${keywords.size}")
            if (authorRules.isNotEmpty()) {
                add("author=${authorRules.mids.size}uid+${authorRules.names.size}name")
            }
        }.joinToString("/")
    }

    private class Members(
        val commercial: CommercialMembers?,
        val videoCard: VideoCardMembers?
    )

    private class CommercialMembers(val hasCm: Method?, val hasSpecial: Method?)

    private class VideoCardMembers(
        val hasAv: Method,
        val avGetter: Method,
        val title: Method?,
        val author: Method?,
        val mid: Method?
    )

    companion object {
        const val ID = "search_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "search_purify_status"
        private const val MAX_KEYWORDS = 64
        private const val SEARCH_PACKAGE = "com.bapis.bilibili.polymer.app.search.v1"
        private const val SEARCH_MOSS_CLASS = "$SEARCH_PACKAGE.SearchMoss"
        private const val KMOSS_LOG_KEY = "search_purify_kmoss"
        private const val SEARCH_ALL_RESPONSE_CLASS = "$SEARCH_PACKAGE.SearchAllResponse"
        private const val SEARCH_ITEM_CLASS = "$SEARCH_PACKAGE.Item"
    }
}
