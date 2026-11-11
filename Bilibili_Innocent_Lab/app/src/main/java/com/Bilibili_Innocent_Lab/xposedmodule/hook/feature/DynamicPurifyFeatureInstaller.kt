package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import android.os.Looper
import java.io.File
import java.lang.reflect.Method
import java.util.IdentityHashMap

/**
 * 在动态页 protobuf 边界过滤动态卡片，并按需清掉话题栏与顶部 UP 栏中的直播条目。
 *
 * **边界选择的依据**：9.11.0 里业务侧（`classes8.dex`）调用的是
 * `DynamicMossKtxKt.suspendDynAll(...)`，而那个协程包装内部就是
 * `DynamicMoss#dynAll(req, MossResponseHandler)` 的匿名回调；两者同在 `classes25.dex`，
 * 所以"跨 dex 引用为零"在这里**不代表没人调**——判断是否有真实调用点必须连生成的
 * `*MossKtxKt` 包装一起看。同步 `executeDynAll` 一并覆盖，构成双保险。
 *
 * 综合页（`dynAll`）与视频页（`dynVideo`）共用同一套判据：用户配置的关键词和发布者名单在
 * 两个标签页里含义一致，分开配置只会让人困惑。
 *
 * 判据（逐条动态）：
 * - 正文关键词：模块正文 + 转发原动态正文 + 本条正文，**逐段匹配**，命中即停。
 * - 发布者：`Extend.origName` / `Extend.uid`，与评论区共用 [AuthorRuleSet] 的全等语义。
 * - 推广附加卡：任一模块带 `ModuleAdditional`，且类型是带货或 UP 主推荐。
 * - 未解锁的充电专属：`Extend.onlyFansProperty.hasPrivilege == false`。
 *
 * 整份响应级：话题栏 `clearTopicList()`；顶部 UP 栏按 `liveStateValue > 0` 剔除直播中条目，
 * 并**重排 `pos`**（宿主按 pos 排序，不重排会留下空洞）。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：四个 Moss 方法各只有一个重载；
 * 综合页容器是 `DynamicList`、视频页是 `CardVideoDynList`，两者元素同为 `DynamicItem`、
 * 读写成员同名，所以按返回类型解析即可；顶部 UP 栏 getter 在综合页叫 `getUpList`、视频页叫
 * `getVideoUpList`，返回类型都是 `CardVideoUpList`。
 *
 * 覆盖单位口径：每个装上的 Moss 边界算一个单位；每条被用户启用、但读取路径缺失的判据计入
 * 分母不计入分子，于是"开了却没生效"表现为 `partial`。
 */
internal class DynamicPurifyFeatureInstaller(
    keywordFilterEnabled: Boolean,
    rawKeywords: String,
    authorFilterEnabled: Boolean,
    rawAuthorRules: String,
    private val removePromotion: Boolean,
    private val removeLockedChargeOnly: Boolean,
    private val hideTopicList: Boolean,
    private val removeLiveUpEntries: Boolean,
    private val hideFrequentVisits: Boolean = false,
    /**
     * 「智能过滤动态」：开关打开且 JEV 配置有效时非空（见 [SemanticJudge.fromSettings]）。
     * 与其它设置一样只在宿主 attach 时确定；缓存只在内存，关闭开关重启后不留任何判定。
     */
    private val semanticJudge: SemanticJudge? = null,
    /** debug 构建的观测日志目录（宿主私有 files）；release 为 null，不写任何文件。 */
    private val semanticLogDir: File? = null,
    /** 主线程上只查缓存、绝不联网；单测可替换。 */
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (keywords.isNotEmpty()) add("dynamic_keyword_filter_enabled")
        if (authorRules.isNotEmpty()) add("dynamic_author_filter_enabled")
        if (semanticJudge != null) add("dynamic_semantic_filter_enabled")
        if (removePromotion) add("dynamic_promotions_removed")
        if (removeLockedChargeOnly) add("dynamic_charge_only_removed")
        if (hideTopicList) add("dynamic_topic_list_hidden")
        if (removeLiveUpEntries && !hideFrequentVisits) add("dynamic_up_list_live_removed")
        if (hideFrequentVisits) add("dynamic_frequent_visits_hidden")
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

    /** 只用于新通道观测的日志：Java 通道是否见过响应（两条通道是否并存）。 */
    private val javaPathObserved = java.util.concurrent.atomic.AtomicBoolean(false)

    private val anyRequested: Boolean
        get() = keywords.isNotEmpty() || authorRules.isNotEmpty() || removePromotion ||
            removeLockedChargeOnly || hideTopicList || removeLiveUpEntries || hideFrequentVisits ||
            semanticJudge != null

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!anyRequested) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val mossClass = environment.hookPoints.resolveClass("dynamic.purify.moss", DYNAMIC_MOSS_CLASS)
            ?: return missing(environment, "missing-moss-class")

        val itemMembers = if (hideFrequentVisits && keywords.isEmpty() && authorRules.isEmpty() &&
            !removePromotion && !removeLockedChargeOnly && semanticJudge == null) null else resolveItemMembers(loader)
        val plan = DynamicPurifyPolicy.Plan(
            keywords = if (itemMembers?.hasTextSource == true) keywords else emptySet(),
            authorRules = authorRules.available(itemMembers?.hasAuthorSource == true && itemMembers.author?.origName != null,
                itemMembers?.hasAuthorSource == true && itemMembers.author?.uid != null),
            removePromotion = removePromotion && itemMembers?.promotion != null,
            removeLockedChargeOnly = removeLockedChargeOnly && itemMembers?.chargeOnly != null,
            semanticEnabled = semanticJudge != null && itemMembers?.hasTextSource == true
        )
        if (semanticJudge != null) {
            val state = if (plan.semanticEnabled) "active rules=${semanticJudge.rules.joinToString(",") { it.id }} " +
                "host=${semanticJudge.endpoint.let { runCatching { java.net.URL(it).host }.getOrNull() }} " +
                "threshold=${semanticJudge.blockThreshold} mode=${if (semanticJudge.waitFirstScreen) "wait" else "pass"}"
            else "missing-text-source"
            environment.logInfo("dynamic_semantic_filter", "[BIL] 智能过滤动态: $state")
            semanticLogDir?.let { SemanticDebugLog.append(it, "${System.currentTimeMillis()} install $state") }
        }

        val kotlinMoss = KavaMemberLookup.classOrNull(loader, KotlinDynamicMossWatch.K_MOSS_CLASS)
        val kotlinNames = kotlinMoss?.let { owner -> KavaMemberLookup.declaredMethods(owner).map { it.name }.toSet() }.orEmpty()
        val topicSpecs = if (hideTopicList) FEED_METHODS.filter { spec ->
            spec.replyClassName == TOPIC_REPLY_CLASS || KavaMemberLookup.classOrNull(loader, spec.replyClassName)?.let { reply ->
                KavaMemberLookup.firstDeclaredMethodOrNull(reply) {
                    !it.isStatic && it.parameterCount == 0 && (it.name == "hasTopicList" || it.name == "getTopicList")
                } != null
            } == true
        } else emptyList()
        val topicOnly = hideTopicList && keywords.isEmpty() && authorRules.isEmpty() &&
            !removePromotion && !removeLockedChargeOnly && semanticJudge == null &&
            !removeLiveUpEntries && !hideFrequentVisits
        val mainSpecs = if (topicOnly) topicSpecs else FEED_METHODS
        val rootApplicable = plan.hasAnyItemJudgement && ROOT_FEED_METHODS.any { spec ->
            KavaMemberLookup.classOrNull(loader, spec.replyClassName) != null || spec.asyncName in kotlinNames
        }
        val specs = mainSpecs + if (rootApplicable) ROOT_FEED_METHODS else emptyList()
        val kotlinExpected = specs.filter { it.asyncName in kotlinNames }.map { it.asyncName }.toSet()
        val feeds = specs.mapNotNull { spec -> resolveFeed(loader, mossClass, spec) }.filter { feed ->
            (plan.hasAnyItemJudgement && feed.listBuilder != null) ||
                (hideTopicList && feed.clearTopicList != null) || (removeLiveUpEntries && feed.upList != null) ||
                (hideFrequentVisits && feed.wholeUpList != null)
        }
        if (feeds.isEmpty()) return missing(environment, "no-dynamic-hook-point")

        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER_CLASS)
        var installed = 0
        var expected = 0
        val pathsByFeed = hashMapOf<FeedMembers, Int>()

        feeds.forEach { feed ->
            val before = installed
            feed.syncMethod?.let { method ->
                expected += 1
                if (installSync(environment, method, feed, itemMembers, plan)) installed += 1
            }
            val async = feed.asyncMethod
            if (async != null && handlerClass != null) {
                expected += 1
                if (installAsync(environment, async, handlerClass, feed, itemMembers, plan)) {
                    installed += 1
                }
            }
            pathsByFeed[feed] = installed - before
        }
        val covered = installKotlinMoss(environment, loader, feeds, itemMembers, plan, specs)
        feeds.forEach { feed ->
            val rpc = specs.first { it.replyClassName == feed.replyClass.name }.asyncName
            if (rpc in covered) {
                installed++
                pathsByFeed[feed] = (pathsByFeed[feed] ?: 0) + 1
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        val sharedExpected = specs.size * 2 + kotlinExpected.size
        val mainFeeds = feeds.filterNot { it.rootList }
        val mainExpected = FEED_METHODS.size * 2 + FEED_METHODS.count { it.asyncName in kotlinExpected }
        val topicReplyClasses = topicSpecs.mapTo(hashSetOf()) { it.replyClassName }
        val topicFeeds = mainFeeds.filter { it.replyClass.name in topicReplyClasses && it.clearTopicList != null }
        val topicDataComplete = topicFeeds.size == topicSpecs.size
        val topicExpected = topicSpecs.size * 2 + topicSpecs.count { it.asyncName in kotlinExpected }
        for (capability in capabilityIds) {
            val compatibleFeeds = if (capability == "dynamic_topic_list_hidden") topicFeeds else feeds.filter { feed -> when (capability) {
                "dynamic_frequent_visits_hidden" -> feed.wholeUpList != null
                "dynamic_up_list_live_removed" -> feed.upList != null
                else -> feed.listBuilder != null
            } }
            val usable = when (capability) {
                "dynamic_keyword_filter_enabled" -> plan.keywords.isNotEmpty()
                "dynamic_author_filter_enabled" -> plan.authorRules.isNotEmpty()
                "dynamic_semantic_filter_enabled" -> plan.semanticEnabled
                "dynamic_promotions_removed" -> plan.removePromotion
                "dynamic_charge_only_removed" -> plan.removeLockedChargeOnly
                "dynamic_frequent_visits_hidden" -> feeds.any { it.wholeUpList != null }
                "dynamic_topic_list_hidden" -> topicFeeds.isNotEmpty()
                "dynamic_up_list_live_removed" -> feeds.any { it.upList != null }
                else -> false
            }
            val completeData = when (capability) {
                "dynamic_frequent_visits_hidden" -> mainFeeds.size == FEED_METHODS.size && mainFeeds.all { it.wholeUpList != null }
                "dynamic_topic_list_hidden" -> topicDataComplete
                "dynamic_up_list_live_removed" -> mainFeeds.size == FEED_METHODS.size && mainFeeds.all { it.upList != null }
                else -> feeds.size == specs.size && feeds.all { it.listBuilder != null } &&
                    (capability != "dynamic_author_filter_enabled" || plan.authorRules == authorRules)
            }
            val capabilityExpected = when (capability) {
                "dynamic_topic_list_hidden" -> topicExpected
                in TOP_CAPABILITIES -> mainExpected
                else -> sharedExpected
            }
            environment.reportCapabilityCoverage(capability, usable && compatibleFeeds.isNotEmpty(), compatibleFeeds.sumOf { pathsByFeed[it] ?: 0 },
                capabilityExpected + if (completeData) 0 else 1)
        }
        expected = sharedExpected

        // 用户开了但读不到的判据必须留在分母里。
        val degraded = ArrayList<String>(4)
        if (plan.hasAnyItemJudgement && feeds.any { it.listBuilder == null }) {
            expected++; degraded += "item-carrier"
        }
        fun account(requested: Boolean, usable: Boolean, label: String) {
            if (!requested) return
            expected += 1
            if (usable) installed += 1 else degraded += label
        }
        account(keywords.isNotEmpty(), plan.keywords.isNotEmpty(), "keyword")
        account(authorRules.isNotEmpty(), plan.authorRules == authorRules, "author")
        account(semanticJudge != null, plan.semanticEnabled, "semantic")
        account(removePromotion, plan.removePromotion, "promotion")
        account(removeLockedChargeOnly, plan.removeLockedChargeOnly, "charge-only")
        account(hideTopicList, topicDataComplete, "topic-list")
        account(removeLiveUpEntries && !hideFrequentVisits, feeds.any { it.upList != null }, "up-live")
        account(hideFrequentVisits, mainFeeds.size == FEED_METHODS.size && mainFeeds.all { it.wholeUpList != null }, "frequent-visits")
        if (degraded.isNotEmpty()) {
            environment.logError(
                "dynamic_purify_degraded",
                "[BIL] 动态页净化判据缺少可用读取路径: ${degraded.joinToString(",")}"
            )
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        // 9.14.0 新增 Compose 动态列表，走 Kotlin KDynamicMoss。优先直接在它的响应上复用同一套过滤
        // （经 protobuf 线格式往返到 Java 响应）；装不上时退回"只观测并留证据"，不再静默失效。
        // 普通两页仍观察未覆盖入口；所有适用的 Kotlin 入口失败均保留在安装分母。
        if (kotlinExpected.isNotEmpty() && covered.containsAll(kotlinExpected)) {
            environment.logInfo("dynamic_kmoss_filter", "[BIL] 已接入动态页新通道 KDynamicMoss 过滤，entries=${covered.size}")
        } else {
            val kotlinWatch = KotlinDynamicMossWatch.install(
                environment, loader, capabilityIds + ID, { javaPathObserved.get() }, covered
            )
            if (kotlinWatch > 0) {
                environment.logInfo("dynamic_kmoss_watch", "[BIL] 已观测动态页新通道 KDynamicMoss，entries=$kotlinWatch")
            }
        }
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "dynamic_purify_ok",
                "[BIL] 动态页净化已安装，hooks=$installed，判据=${plan.describe()}"
            )
        } else {
            environment.logError(
                "dynamic_purify_partial",
                "[BIL] 动态页净化部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun installSync(
        environment: HookEnvironment,
        method: Method,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan
    ): Boolean = runCatching {
        environment.registrar.exact(
            "dynamic.purify.sync.${method.name}",
            method.declaringClass,
            method.name,
            *method.parameterTypes
        ) {
            after {
                if (hasThrowable) return@after
                val reply = result ?: return@after
                result = purify(environment, reply, feed, itemMembers, plan)
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "dynamic_purify_sync_${method.name}",
            "[BIL] 动态页净化同步边界注册失败(${method.name}): $throwable"
        )
        false
    }

    private fun installAsync(
        environment: HookEnvironment,
        method: Method,
        handlerClass: Class<*>,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan
    ): Boolean = runCatching {
        environment.registrar.exact(
            "dynamic.purify.async.${method.name}",
            method.declaringClass,
            method.name,
            *method.parameterTypes
        ) {
            before {
                val delegate = args.getOrNull(1) ?: return@before
                val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) {
                    purify(environment, it, feed, itemMembers, plan)
                } ?: return@before
                args[1] = proxy
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "dynamic_purify_async_${method.name}",
            "[BIL] 动态页净化异步边界注册失败(${method.name}): $throwable"
        )
        false
    }

    /**
     * 9.14.0 起动态页新列表的数据通道 `KDynamicMoss`：响应是 Kotlin 序列化数据类（字段被混淆），
     * 这里不解析它，而是经 protobuf 线格式往返到同一 proto 的 Java 响应上，**原样复用 [purify]**，
     * 见 [KotlinMossReplyBridge]。已核实的 suspend 消费者汇入同一个回调形态泛型入口，
     * 每个页签挂一处即可。任何一步装不上都不进这个集合，调用方据此退回观测措施；运行期任何异常都放行原响应。
     *
     * @return 已经接住过滤的入口名（[KotlinDynamicMossWatch] 据此只观测没接上的那些）。
     */
    private fun installKotlinMoss(
        environment: HookEnvironment,
        loader: ClassLoader,
        feeds: List<FeedMembers>,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan,
        specs: List<FeedSpec>
    ): Set<String> {
        val kotlinMoss = KavaMemberLookup.classOrNull(loader, KotlinDynamicMossWatch.K_MOSS_CLASS) ?: return emptySet()
        val members = KotlinMossBridgeMembers.resolve(loader)
        if (members == null) {
            environment.logInfo("dynamic_kmoss_filter_skip", "[BIL] 动态页新通道过滤未安装: kotlinx.serialization 成员缺失")
            return emptySet()
        }
        if (!KotlinMossBridgeSelfTest.allows(environment, loader, members, "动态页")) return emptySet()
        val installed = mutableSetOf<String>()
        specs.forEach { spec ->
            // 装不上不能再静默：每个页签只在缺东西时记一条原因（有界：页签个数）。
            fun skip(reason: String) = environment.logInfo(
                "dynamic_kmoss_filter_skip_${spec.asyncName}",
                "[BIL] 动态页新通道过滤跳过 ${spec.asyncName}: $reason"
            )
            val feed = feeds.firstOrNull { it.replyClass.name == spec.replyClassName } ?: return@forEach skip("no-java-feed")
            val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(feed.replyClass) ?: return@forEach skip("no-java-codec")
            val entry = KotlinMossBridgeMembers.callbackEntry(kotlinMoss, spec.asyncName) ?: return@forEach skip("no-callback-entry")
            val handlerClass = entry.parameterTypes[3]
            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            val called = java.util.concurrent.atomic.AtomicBoolean(false)
            runCatching {
                environment.registrar.exact(
                    "dynamic.purify.kmoss.${spec.asyncName}",
                    entry.declaringClass,
                    entry.name,
                    *entry.parameterTypes
                ) {
                    before {
                        if (called.compareAndSet(false, true)) {
                            environment.logInfo(
                                "dynamic_kmoss_call",
                                "[BIL] 动态页新通道请求已进入 KDynamicMoss#${entry.name}"
                            )
                        }
                        val delegate = args.getOrNull(3) ?: return@before
                        val bridge = members.bridgeFor(args.getOrNull(4), args.getOrNull(2), codec)
                            ?: return@before
                        val key = KotlinMossResponseHandlerProxy.Key(entry, feed.replyClass, args[4]!!, args[2]!!)
                        val proxy = KotlinMossResponseHandlerProxy.wrap(handlerClass, delegate, key, bridge,
                            failed = { throwable ->
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                                environment.logError(
                                    "dynamic_kmoss_filter_failed",
                                    "[BIL] 动态页新通道过滤失败，已放行原响应(${entry.name}): $throwable"
                                )
                            }, transform = { it }, confirmedTransform = { javaReply ->
                                if (reported.compareAndSet(false, true)) {
                                    environment.logInfo("dynamic_kmoss_active",
                                        "[BIL] 动态页新通道 KDynamicMoss#${entry.name} 已收到响应并进入过滤" +
                                            "（javaPathObserved=${javaPathObserved.get()}）")
                                }
                                confirmedReply(environment, javaReply, feed, itemMembers, plan)
                            }) ?: return@before
                        args[3] = proxy
                    }
                }
                installed += spec.asyncName
            }.onFailure { throwable ->
                environment.logError(
                    "dynamic_kmoss_filter_${spec.asyncName}",
                    "[BIL] 动态页新通道过滤注册失败(${spec.asyncName}): $throwable"
                )
            }
        }
        return installed
    }

    /** 两条链路共用；未改原响应，成功时返回副本；处理已净化副本时保持幂等。 */
    private fun purify(
        environment: HookEnvironment,
        reply: Any,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan
    ): Any = purifyDeferred(environment, reply, feed, itemMembers, plan) { capability ->
        environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.APPLIED)
    }

    private fun confirmedReply(environment: HookEnvironment, reply: Any, feed: FeedMembers,
        itemMembers: ItemMembers?, plan: DynamicPurifyPolicy.Plan): KotlinMossResponseHandlerProxy.ConfirmedReply {
        val pending = ArrayList<String>(2)
        val updated = purifyDeferred(environment, reply, feed, itemMembers, plan) { pending += it }
        return KotlinMossResponseHandlerProxy.ConfirmedReply(updated) {
            pending.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.APPLIED) }
        }
    }

    private fun purifyDeferred(
        environment: HookEnvironment,
        reply: Any,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan,
        applied: (String) -> Unit
    ): Any {
        if (!feed.replyClass.isInstance(reply)) return reply
        // 字段未设置时宿主拿到的是进程级单例，改它会污染整个进程。
        if (feed.defaultReply != null && reply === feed.defaultReply) return reply
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        javaPathObserved.set(true)
        var removingWhole = false
        return runCatching {
            val items = purifyItems(reply, feed, itemMembers, plan)
            if (feed.rootList) {
                if (items != null) applied(ID)
                return@runCatching items ?: reply
            }
            val clearTopic = hideTopicList && feed.clearTopicList != null &&
                feed.hasTopicList?.let { invoke(it, reply) as? Boolean } == true
            val whole = feed.wholeUpList
            val clearWhole = hideFrequentVisits && whole != null && invoke(whole.has, reply) == true
            removingWhole = clearWhole
            if (clearWhole) environment.reportRuntimeEvidence("dynamic_frequent_visits_hidden", FeatureRuntimeStage.OBSERVED)
            val upList = purifyUpList(reply, feed)
            if (items == null && !clearTopic && !clearWhole && upList == null) return@runCatching reply
            val updated = feed.builder.edit(reply) { builder ->
                if (items != null) feed.setDynamicList!!.invoke(builder, items)
                if (clearTopic) feed.clearTopicList.invoke(builder)
                if (clearWhole) whole.clear.invoke(builder)
                if (upList != null) feed.upList!!.setter.invoke(builder, upList)
            }
            if (clearWhole) {
                check(invoke(whole.has, updated) == false) { "UP container clear verification failed" }
                applied("dynamic_frequent_visits_hidden")
            }
            applied(ID)
            updated
        }.getOrElse {
            if (removingWhole) environment.reportRuntimeEvidence("dynamic_frequent_visits_hidden", FeatureRuntimeStage.ERROR)
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
            environment.logError("dynamic_purify_writeback", "[BIL] 动态净化副本构建失败，保留原响应: $it")
            reply
        }
    }

    private fun purifyItems(
        reply: Any,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan
    ): Any? {
        if (itemMembers == null || !plan.hasAnyItemJudgement) return null
        val listBuilder = feed.listBuilder ?: return null
        val clearList = feed.clearList ?: return null
        val addAllList = feed.addAllList ?: return null
        val dynamicList = if (feed.rootList) reply else invoke(feed.dynamicListGetter, reply) ?: return null
        val items = invoke(feed.listGetter, dynamicList) as? List<*> ?: return null
        if (items.isEmpty()) return null
        val semantic = if (plan.semanticEnabled) semanticVerdicts(items, itemMembers) else null
        val retained = ProtobufListRetention.retainOrNull(items) { item ->
            val semanticBlocked = semantic?.get(item) == SemanticVerdict.BLOCK
            !DynamicPurifyPolicy.shouldRemove(readSignals(item, itemMembers, plan, semanticBlocked), plan)
        } ?: return null
        val removed = items.size - retained.size
        if (removed <= 0) return null
        return listBuilder.edit(dynamicList) { builder ->
            clearList.invoke(builder)
            addAllList.invoke(builder, retained)
        }
    }

    private fun purifyUpList(reply: Any, feed: FeedMembers): Any? {
        if (!removeLiveUpEntries || hideFrequentVisits) return null
        val up = feed.upList ?: return null
        val container = invoke(up.getter, reply) ?: return null
        // 两个列表必须都读到才动手：只读到一个就写回，会把读不到的那个直接清空。
        val first = invoke(up.listGetter, container) as? List<*> ?: return null
        val second = invoke(up.listSecondGetter, container) as? List<*> ?: return null
        if (first.isEmpty() && second.isEmpty()) return null

        val keep: (Any) -> Boolean = { item ->
            // 读不出直播状态时保留：只删明确在直播的条目。
            val state = (invoke(up.liveStateValue, item) as? Number)?.toInt()
            state == null || state <= 0
        }
        val retainedFirst = ProtobufListRetention.retainOrNull(first, keep)
        val retainedSecond = ProtobufListRetention.retainOrNull(second, keep)
        if (retainedFirst == null && retainedSecond == null) return null

        val newFirst = retainedFirst ?: first.filterNotNull()
        val newSecond = retainedSecond ?: second.filterNotNull()
        val removed = (first.size - newFirst.size) + (second.size - newSecond.size)
        if (removed <= 0) return null
        // 子项也复制：容器 builder 的列表可能共享原始元素，不能直接改原元素的 pos。
        var position = 1L
        fun positioned(items: List<Any>): List<Any> = items.map { item ->
            up.itemBuilder.edit(item) { builder -> up.setPos.invoke(builder, position++) }
        }
        val positionedFirst = positioned(newFirst)
        val positionedSecond = positioned(newSecond)
        return up.builder.edit(container) { builder ->
            up.clearList.invoke(builder)
            up.addAllList.invoke(builder, positionedFirst)
            up.clearListSecond.invoke(builder)
            up.addAllListSecond.invoke(builder, positionedSecond)
        }
    }

    /**
     * 语义判定，三种路径：
     * - 主线程：只查缓存（绝不联网）。
     * - 首屏等待（`waitFirstScreen`）：在当前后台线程（Moss 回调）同步联网，带超时与冷却。
     * - 首屏放行（默认）：先用缓存结果放行，未命中投递到模块后台线程判定，结果进缓存，下次加载生效。
     * 任何失败都是 UNKNOWN（规则照常，fail-open）。
     *
     * 结果按列表 memo：列表 getter 在滚动与绑定时会被反复调用，没有 memo 就会每调一次把整页
     * 动态的正文重新反射一遍再拼一遍。
     */
    private fun semanticVerdicts(items: List<*>, members: ItemMembers): IdentityHashMap<Any, SemanticVerdict>? {
        val judge = semanticJudge ?: return null
        return semanticMemo.getOrCompute(items) { computeSemanticVerdicts(items, members) }
    }

    private val semanticMemo = SemanticListMemo()

    private fun computeSemanticVerdicts(
        items: List<*>,
        members: ItemMembers
    ): IdentityHashMap<Any, SemanticVerdict>? {
        val judge = semanticJudge ?: return null
        val present = items.filterNotNull()
        val texts = present.map { semanticText(it, members) }
        val mode = when {
            isMainThread() -> SemanticMode.CACHE_ONLY
            judge.waitFirstScreen -> SemanticMode.WAIT
            else -> SemanticMode.PREFETCH
        }
        val logDir = semanticLogDir
        val verdicts = judge.evaluate(
            texts,
            mode,
            onReport = logDir?.let { dir -> { report, batch, result -> logSemanticBatch(dir, report, batch, result) } }
        )
        return IdentityHashMap<Any, SemanticVerdict>(present.size).apply {
            present.forEachIndexed { index, item -> put(item, verdicts[index]) }
        }
    }

    /** 送判文本：同一条动态的各段正文按行拼接（这里是给模型读的，不做关键词匹配，拼接无假命中问题）。 */
    private fun semanticText(item: Any, members: ItemMembers): String {
        val extend = members.extendGetter?.let { invoke(it, item) }
        val modules = members.modulesGetter?.let { invoke(it, item) as? List<*> }
        val parts = LinkedHashSet<String>()
        streamText(members, extend, modules) { fragment -> parts += fragment.trim(); false }
        return parts.filter(String::isNotEmpty).joinToString("\n")
    }

    private fun logSemanticBatch(
        directory: File,
        report: SemanticBatchReport,
        texts: List<String>,
        verdicts: List<SemanticVerdict>
    ) {
        val preview = texts.indices.joinToString(" | ") { index ->
            "${verdicts[index].name.first()}:${texts[index].replace('\n', ' ').take(24)}"
        }
        SemanticDebugLog.append(
            directory,
            "${System.currentTimeMillis()} batch thread=${Thread.currentThread().name} total=${report.total} " +
                "hits=${report.cacheHits} requested=${report.requested} blocked=${report.blocked} " +
                "ms=${report.elapsedMs} outcome=${report.outcome}${report.extras()} :: $preview"
        )
    }

    private fun readSignals(
        item: Any,
        members: ItemMembers,
        plan: DynamicPurifyPolicy.Plan,
        semanticBlocked: Boolean = false
    ): DynamicPurifyPolicy.Signals {
        // 语义判定自己读正文（semanticText），这里只在配了关键词时读，避免每条多几次反射。
        val needsKeywordText = plan.keywords.isNotEmpty()
        val extend = if (needsKeywordText || plan.needsAuthor || plan.removeLockedChargeOnly) {
            members.extendGetter?.let { invoke(it, item) }
        } else {
            null
        }
        val modules = if (needsKeywordText || plan.removePromotion) {
            members.modulesGetter?.let { invoke(it, item) as? List<*> }
        } else {
            null
        }

        var authorName: String? = null
        var authorMid: Long? = null
        if (plan.needsAuthor && extend != null) {
            authorName = (invoke(members.author?.origName, extend) as? String)
                ?.takeIf(String::isNotBlank)
            authorMid = (invoke(members.author?.uid, extend) as? Number)?.toLong()
                ?.takeIf { it > 0L }
        }

        val promotion = plan.removePromotion && modules != null &&
            members.promotion?.let { promo -> modules.any { isPromotionModule(it, promo) } } == true

        val lockedChargeOnly = plan.removeLockedChargeOnly && extend != null &&
            members.chargeOnly?.let { isLockedChargeOnly(extend, it) } == true

        return DynamicPurifyPolicy.Signals(
            authorName = authorName,
            authorMid = authorMid,
            promotion = promotion,
            lockedChargeOnly = lockedChargeOnly,
            semanticBlocked = semanticBlocked,
            textFragments = { matches -> streamText(members, extend, modules, matches) }
        )
    }

    /** 逐段回调正文；任一段命中立即返回 true，不再继续读后面的段落。 */
    private fun streamText(
        members: ItemMembers,
        extend: Any?,
        modules: List<*>?,
        matches: (String) -> Boolean
    ): Boolean {
        val text = members.text ?: return false
        if (modules != null && text.moduleDescText != null) {
            modules.forEach { module ->
                module ?: return@forEach
                if (text.hasModuleDesc != null &&
                    invoke(text.hasModuleDesc, module) as? Boolean != true
                ) {
                    return@forEach
                }
                val desc = invoke(text.moduleDescGetter, module) ?: return@forEach
                val value = invoke(text.moduleDescText, desc) as? String
                if (!value.isNullOrEmpty() && matches(value)) return true
            }
        }
        if (extend == null || text.descOrigText == null) return false
        listOfNotNull(text.origDescList, text.descList).forEach { getter ->
            val descriptions = invoke(getter, extend) as? List<*> ?: return@forEach
            descriptions.forEach { description ->
                description ?: return@forEach
                val value = invoke(text.descOrigText, description) as? String
                if (!value.isNullOrEmpty() && matches(value)) return true
            }
        }
        return false
    }

    private fun isPromotionModule(module: Any?, promotion: PromotionMembers): Boolean {
        module ?: return false
        if (invoke(promotion.hasAdditional, module) as? Boolean != true) return false
        val additional = invoke(promotion.additionalGetter, module) ?: return false
        val type = (invoke(promotion.typeValue, additional) as? Number)?.toInt() ?: return false
        return type in promotion.promotedTypes
    }

    private fun isLockedChargeOnly(extend: Any, charge: ChargeOnlyMembers): Boolean {
        if (invoke(charge.hasProperty, extend) as? Boolean != true) return false
        val property = invoke(charge.propertyGetter, extend) ?: return false
        // 只删明确"没有观看权限"的；读不出来时保留。
        return invoke(charge.hasPrivilege, property) as? Boolean == false
    }

    private fun invoke(method: Method?, target: Any?): Any? {
        if (method == null || target == null || !method.declaringClass.isInstance(target)) {
            return null
        }
        return runCatching { method.invoke(target) }.getOrNull()
    }

    // ---- 安装期结构定位 ----------------------------------------------------

    private fun resolveFeed(
        loader: ClassLoader,
        mossClass: Class<*>,
        spec: FeedSpec
    ): FeedMembers? {
        val replyClass = KavaMemberLookup.classOrNull(loader, spec.replyClassName) ?: return null
        val builder = ProtobufBuilderPlan.resolve(replyClass) ?: return null
        val dynamicListGetter = if (spec.rootList) null else objectGetter(replyClass, "getDynamicList")
        // 容器类名两个页签不同（综合 DynamicList / 视频 CardVideoDynList），所以按返回类型取，
        // 不写死类名。但**必须验证元素类型确实是 DynamicItem**：否则条目判据会因为
        // declaringClass 不匹配而全部读成 null，表现为"装上了却一条都不删"的静默无效。
        val listClass = if (spec.rootList) replyClass else dynamicListGetter?.returnType
        val listBuilder = listClass?.let(ProtobufBuilderPlan::resolve)
        val setDynamicList = if (spec.rootList) null else listClass?.let { builder.method("setDynamicList", it) }
        val itemClass = KavaMemberLookup.classOrNull(loader, DYNAMIC_ITEM_CLASS)
        val indexedGetter = listClass?.let { KavaMemberLookup.methodOrNull(it, "get${spec.listField}", classOf<Int>()) }
        val listGetter = listClass?.let { listGetter(it, "get${spec.listField}List") }
        val clearList = listBuilder?.method("clear${spec.listField}")
        val addAllList = listBuilder?.method("addAll${spec.listField}", classOf<Iterable<*>>())
        val itemCarrierReady = itemClass != null && indexedGetter?.returnType == itemClass &&
            (spec.rootList || setDynamicList != null) && listGetter != null && clearList != null && addAllList != null

        val syncMethod = KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) {
            !it.isStatic && it.name == spec.syncName && it.parameterCount == 1 &&
                it.returnType == replyClass
        }.singleOrNull()
        val asyncMethod = KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) {
            !it.isStatic && it.name == spec.asyncName && it.parameterCount == 2 &&
                it.returnType == Void.TYPE &&
                it.parameterTypes[1].name == MOSS_HANDLER_CLASS
        }.singleOrNull()
        if (syncMethod == null && asyncMethod == null) return null

        val hasTopic = if (spec.rootList) null else booleanNoArg(replyClass, "hasTopicList")
        val clearTopic = builder.method("clearTopicList")
        val upList = if (!spec.rootList && removeLiveUpEntries && !hideFrequentVisits) resolveUpList(loader, replyClass, builder) else null
        val wholeUpList = if (!spec.rootList && hideFrequentVisits) resolveWholeUpList(replyClass, builder) else null

        return FeedMembers(
            replyClass = replyClass,
            rootList = spec.rootList,
            builder = builder,
            listBuilder = listBuilder?.takeIf { itemCarrierReady },
            setDynamicList = setDynamicList,
            dynamicListGetter = dynamicListGetter,
            listGetter = listGetter,
            clearList = clearList,
            addAllList = addAllList,
            defaultReply = defaultInstance(replyClass),
            hasTopicList = hasTopic?.takeIf { clearTopic != null },
            clearTopicList = clearTopic?.takeIf { hasTopic != null },
            upList = upList,
            wholeUpList = wholeUpList,
            syncMethod = syncMethod,
            asyncMethod = asyncMethod
        )
    }

    /** Resolve only the whole carrier; avatar/live/position fields are deliberately not dependencies. */
    private fun resolveWholeUpList(reply: Class<*>, builder: ProtobufBuilderPlan): WholeUpListMembers? =
        UP_LIST_GETTERS.mapNotNull { getterName ->
            val getter = objectGetter(reply, getterName) ?: return@mapNotNull null
            if (getter.returnType.name != "$DYNAMIC_PACKAGE.CardVideoUpList") return@mapNotNull null
            val suffix = getterName.removePrefix("get")
            val has = booleanNoArg(reply, "has$suffix") ?: return@mapNotNull null
            val clear = builder.method("clear$suffix") ?: return@mapNotNull null
            WholeUpListMembers(has, clear)
        }.singleOrNull()

    private class WholeUpListMembers(val has: Method, val clear: Method)

    private fun resolveUpList(loader: ClassLoader, replyClass: Class<*>, replyBuilder: ProtobufBuilderPlan): UpListMembers? {
        // 综合页叫 getUpList、视频页叫 getVideoUpList，但返回的都是同一个 CardVideoUpList。
        // 9.7.0–9.11.0 五个版本都是这两个名字之一，按顺序取第一个能解析到的。
        val containerGetter = UP_LIST_GETTERS.firstNotNullOfOrNull { name ->
            objectGetter(replyClass, name)
        } ?: return null
        val container = containerGetter.returnType
        val builder = ProtobufBuilderPlan.resolve(container) ?: return null
        val setter = replyBuilder.method("set" + containerGetter.name.removePrefix("get"), container) ?: return null
        val primaryList = listGetter(container, "getListList") ?: return null
        val secondaryList = listGetter(container, "getListSecondList") ?: return null
        val clearPrimary = builder.method("clearList") ?: return null
        val clearSecondary = builder.method("clearListSecond") ?: return null
        val addAllPrimary = builder.method("addAllList", classOf<Iterable<*>>()) ?: return null
        val addAllSecondary = builder.method("addAllListSecond", classOf<Iterable<*>>()) ?: return null
        val itemClass = KavaMemberLookup.classOrNull(loader, UP_LIST_ITEM_CLASS) ?: return null
        val itemBuilder = ProtobufBuilderPlan.resolve(itemClass) ?: return null
        val liveState = intNoArg(itemClass, "getLiveStateValue") ?: return null
        val positionSetter = itemBuilder.method("setPos", classOf<Long>()) ?: return null
        return UpListMembers(
            getter = containerGetter,
            setter = setter,
            builder = builder,
            itemBuilder = itemBuilder,
            listGetter = primaryList,
            listSecondGetter = secondaryList,
            clearList = clearPrimary,
            clearListSecond = clearSecondary,
            addAllList = addAllPrimary,
            addAllListSecond = addAllSecondary,
            liveStateValue = liveState,
            setPos = positionSetter
        )
    }

    private fun resolveItemMembers(loader: ClassLoader): ItemMembers? {
        val itemClass = KavaMemberLookup.classOrNull(loader, DYNAMIC_ITEM_CLASS) ?: return null
        val extendGetter = objectGetter(itemClass, "getExtend")
        val modulesGetter = listGetter(itemClass, "getModulesList")
        val moduleClass = KavaMemberLookup.classOrNull(loader, MODULE_CLASS)
        val descriptionClass = KavaMemberLookup.classOrNull(loader, DESCRIPTION_CLASS)

        val text = run {
            val moduleDescGetter = moduleClass?.let { objectGetter(it, "getModuleDesc") }
            val moduleDescText = moduleDescGetter?.returnType?.let { stringNoArg(it, "getText") }
            val descOrigText = descriptionClass?.let { stringNoArg(it, "getOrigText") }
            val origDescList = extendGetter?.returnType?.let { listGetter(it, "getOrigDescList") }
            val descList = extendGetter?.returnType?.let { listGetter(it, "getDescList") }
            val hasModuleSource = modulesGetter != null && moduleDescGetter != null &&
                moduleDescText != null
            val hasExtendSource = descOrigText != null && (origDescList != null || descList != null)
            if (!hasModuleSource && !hasExtendSource) {
                null
            } else {
                TextMembers(
                    hasModuleDesc = moduleClass?.let { booleanNoArg(it, "hasModuleDesc") },
                    moduleDescGetter = moduleDescGetter?.takeIf { hasModuleSource },
                    moduleDescText = moduleDescText?.takeIf { hasModuleSource },
                    origDescList = origDescList?.takeIf { hasExtendSource },
                    descList = descList?.takeIf { hasExtendSource },
                    descOrigText = descOrigText?.takeIf { hasExtendSource }
                )
            }
        }

        val author = extendGetter?.returnType?.let { extendClass ->
            val origName = stringNoArg(extendClass, "getOrigName")
            val uid = longNoArg(extendClass, "getUid")
            if (origName == null && uid == null) null else AuthorMembers(origName, uid)
        }

        val promotion = run {
            val hasAdditional = moduleClass?.let { booleanNoArg(it, "hasModuleAdditional") }
            val additionalGetter = moduleClass?.let { objectGetter(it, "getModuleAdditional") }
            val typeValue = additionalGetter?.returnType?.let { intNoArg(it, "getTypeValue") }
            if (modulesGetter == null || hasAdditional == null || additionalGetter == null ||
                typeValue == null
            ) {
                null
            } else {
                PromotionMembers(
                    hasAdditional = hasAdditional,
                    additionalGetter = additionalGetter,
                    typeValue = typeValue,
                    promotedTypes = resolvePromotedTypes(loader)
                )
            }
        }

        val chargeOnly = extendGetter?.returnType?.let { extendClass ->
            val hasProperty = booleanNoArg(extendClass, "hasOnlyFansProperty")
            val propertyGetter = objectGetter(extendClass, "getOnlyFansProperty")
            val hasPrivilege = propertyGetter?.returnType?.let {
                booleanNoArg(it, "getHasPrivilege")
            }
            if (hasProperty == null || propertyGetter == null || hasPrivilege == null) {
                null
            } else {
                ChargeOnlyMembers(hasProperty, propertyGetter, hasPrivilege)
            }
        }

        return ItemMembers(
            extendGetter = extendGetter,
            modulesGetter = modulesGetter,
            text = text,
            author = author,
            promotion = promotion,
            chargeOnly = chargeOnly
        )
    }

    /** 优先读宿主 `AdditionalType` 上的具名常量，读不到才退回文档值。 */
    private fun resolvePromotedTypes(loader: ClassLoader): Set<Int> {
        val typeClass = KavaMemberLookup.classOrNull(loader, ADDITIONAL_TYPE_CLASS)
            ?: return DynamicPurifyPolicy.FALLBACK_PROMOTION_ADDITIONAL_TYPES
        val resolved = DynamicPurifyPolicy.PROMOTION_ADDITIONAL_TYPE_FIELDS.mapNotNull { name ->
            KavaMemberLookup.fieldOrNull(typeClass, name)
                ?.takeIf { it.isStatic && it.type == classOf<Int>() }
                ?.let { runCatching { it.getInt(null) }.getOrNull() }
        }.toSet()
        return resolved.takeIf {
            it.size == DynamicPurifyPolicy.PROMOTION_ADDITIONAL_TYPE_FIELDS.size
        } ?: DynamicPurifyPolicy.FALLBACK_PROMOTION_ADDITIONAL_TYPES
    }

    private fun defaultInstance(owner: Class<*>): Any? =
        KavaMemberLookup.methodOrNull(owner, "getDefaultInstance")
            ?.takeIf { it.isStatic && it.parameterCount == 0 && it.returnType == owner }
            ?.let { runCatching { it.invoke(null) }.getOrNull() }

    private fun objectGetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive
        }

    private fun listGetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType isSubclassOf classOf<List<*>>()
        }

    private fun stringNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<String>()
        }

    private fun booleanNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Boolean>()
        }

    private fun intNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Int>()
        }

    private fun longNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Long>()
        }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "dynamic_purify_missing",
            "[BIL] 动态页净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    // ---- 安装期解析出来的运行期成员（只持有 Class/Method，不持有宿主实例） -------

    private class FeedSpec(
        val replyClassName: String,
        val syncName: String,
        val asyncName: String
    ) {
        val rootList: Boolean = asyncName == "dynAllPersonal" || asyncName == "dynVideoPersonal" ||
            asyncName == "feedFilter" || asyncName == "dynFriend"
        val listField: String = if (asyncName == "dynFriend") "DynList" else "List"
    }

    private class FeedMembers(
        val replyClass: Class<*>,
        val rootList: Boolean,
        val builder: ProtobufBuilderPlan,
        val listBuilder: ProtobufBuilderPlan?,
        val setDynamicList: Method?,
        val dynamicListGetter: Method?,
        val listGetter: Method?,
        val clearList: Method?,
        val addAllList: Method?,
        val defaultReply: Any?,
        val hasTopicList: Method?,
        val clearTopicList: Method?,
        val upList: UpListMembers?,
        val wholeUpList: WholeUpListMembers?,
        val syncMethod: Method?,
        val asyncMethod: Method?
    )

    private class UpListMembers(
        val getter: Method,
        val setter: Method,
        val builder: ProtobufBuilderPlan,
        val itemBuilder: ProtobufBuilderPlan,
        val listGetter: Method,
        val listSecondGetter: Method,
        val clearList: Method,
        val clearListSecond: Method,
        val addAllList: Method,
        val addAllListSecond: Method,
        val liveStateValue: Method,
        val setPos: Method
    )

    private class ItemMembers(
        val extendGetter: Method?,
        val modulesGetter: Method?,
        val text: TextMembers?,
        val author: AuthorMembers?,
        val promotion: PromotionMembers?,
        val chargeOnly: ChargeOnlyMembers?
    ) {
        val hasTextSource: Boolean get() = text != null
        val hasAuthorSource: Boolean get() = extendGetter != null && author != null
    }

    private class TextMembers(
        val hasModuleDesc: Method?,
        val moduleDescGetter: Method?,
        val moduleDescText: Method?,
        val origDescList: Method?,
        val descList: Method?,
        val descOrigText: Method?
    )

    private class AuthorMembers(val origName: Method?, val uid: Method?)

    private class PromotionMembers(
        val hasAdditional: Method,
        val additionalGetter: Method,
        val typeValue: Method,
        val promotedTypes: Set<Int>
    )

    private class ChargeOnlyMembers(
        val hasProperty: Method,
        val propertyGetter: Method,
        val hasPrivilege: Method
    )

    companion object {
        const val ID = "dynamic_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "dynamic_purify_status"
        private const val MAX_KEYWORDS = 64
        private const val DYNAMIC_PACKAGE = "com.bapis.bilibili.app.dynamic.v2"
        private const val DYNAMIC_MOSS_CLASS = "$DYNAMIC_PACKAGE.DynamicMoss"
        private const val DYNAMIC_ITEM_CLASS = "$DYNAMIC_PACKAGE.DynamicItem"
        private const val MODULE_CLASS = "$DYNAMIC_PACKAGE.Module"
        private const val DESCRIPTION_CLASS = "$DYNAMIC_PACKAGE.Description"
        private const val ADDITIONAL_TYPE_CLASS = "$DYNAMIC_PACKAGE.AdditionalType"
        private const val UP_LIST_ITEM_CLASS = "$DYNAMIC_PACKAGE.UpListItem"
        private const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"

        /** 顶部 UP 栏容器的 getter 名：综合页与视频页不同名，返回类型一致。 */
        private val UP_LIST_GETTERS = listOf("getUpList", "getVideoUpList")

        /** 综合页明确承载话题栏；视频页只有实际声明话题字段时才计入该判据。 */
        private const val TOPIC_REPLY_CLASS = "$DYNAMIC_PACKAGE.DynAllReply"
        private val FEED_METHODS = listOf(
            FeedSpec(TOPIC_REPLY_CLASS, "executeDynAll", "dynAll"),
            FeedSpec("$DYNAMIC_PACKAGE.DynVideoReply", "executeDynVideo", "dynVideo")
        )
        private val ROOT_FEED_METHODS = listOf(
            FeedSpec("$DYNAMIC_PACKAGE.DynAllPersonalReply", "executeDynAllPersonal", "dynAllPersonal"),
            FeedSpec("$DYNAMIC_PACKAGE.DynVideoPersonalReply", "executeDynVideoPersonal", "dynVideoPersonal"),
            FeedSpec("$DYNAMIC_PACKAGE.FeedFilterReply", "executeFeedFilter", "feedFilter"),
            FeedSpec("$DYNAMIC_PACKAGE.DynFriendReply", "executeDynFriend", "dynFriend")
        )
        private val TOP_CAPABILITIES = setOf("dynamic_frequent_visits_hidden", "dynamic_up_list_live_removed")
    }
}
