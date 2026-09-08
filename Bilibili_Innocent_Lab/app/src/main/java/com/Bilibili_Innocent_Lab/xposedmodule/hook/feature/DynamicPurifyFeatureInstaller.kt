package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

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
    private val removeLiveUpEntries: Boolean
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (keywords.isNotEmpty()) add("dynamic_keyword_filter_enabled")
        if (authorRules.isNotEmpty()) add("dynamic_author_filter_enabled")
        if (removePromotion) add("dynamic_promotions_removed")
        if (removeLockedChargeOnly) add("dynamic_charge_only_removed")
        if (hideTopicList) add("dynamic_topic_list_hidden")
        if (removeLiveUpEntries) add("dynamic_up_list_live_removed")
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

    private val anyRequested: Boolean
        get() = keywords.isNotEmpty() || authorRules.isNotEmpty() || removePromotion ||
            removeLockedChargeOnly || hideTopicList || removeLiveUpEntries

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

        val itemMembers = resolveItemMembers(loader)
        val plan = DynamicPurifyPolicy.Plan(
            keywords = if (itemMembers?.hasTextSource == true) keywords else emptySet(),
            authorRules = authorRules.available(itemMembers?.hasAuthorSource == true && itemMembers.author?.origName != null,
                itemMembers?.hasAuthorSource == true && itemMembers.author?.uid != null),
            removePromotion = removePromotion && itemMembers?.promotion != null,
            removeLockedChargeOnly = removeLockedChargeOnly && itemMembers?.chargeOnly != null
        )

        val feeds = FEED_METHODS.mapNotNull { spec -> resolveFeed(loader, mossClass, spec) }.filter { feed ->
            (plan.hasAnyItemJudgement && feed.listBuilder != null) ||
                (hideTopicList && feed.clearTopicList != null) || (removeLiveUpEntries && feed.upList != null)
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
        if (installed == 0) return missing(environment, "registration-failed")
        val sharedExpected = FEED_METHODS.size * 2
        for (capability in capabilityIds) {
            val compatibleFeeds = feeds.filter { feed -> when (capability) {
                "dynamic_topic_list_hidden" -> feed.clearTopicList != null
                "dynamic_up_list_live_removed" -> feed.upList != null
                else -> feed.listBuilder != null
            } }
            val usable = when (capability) {
                "dynamic_keyword_filter_enabled" -> plan.keywords.isNotEmpty()
                "dynamic_author_filter_enabled" -> plan.authorRules.isNotEmpty()
                "dynamic_promotions_removed" -> plan.removePromotion
                "dynamic_charge_only_removed" -> plan.removeLockedChargeOnly
                "dynamic_topic_list_hidden" -> feeds.any { it.clearTopicList != null }
                "dynamic_up_list_live_removed" -> feeds.any { it.upList != null }
                else -> false
            }
            val completeData = when (capability) {
                "dynamic_topic_list_hidden" -> feeds.size == FEED_METHODS.size && feeds.all { it.clearTopicList != null }
                "dynamic_up_list_live_removed" -> feeds.size == FEED_METHODS.size && feeds.all { it.upList != null }
                else -> feeds.size == FEED_METHODS.size && feeds.all { it.listBuilder != null } &&
                    (capability != "dynamic_author_filter_enabled" || plan.authorRules == authorRules)
            }
            environment.reportCapabilityCoverage(capability, usable && compatibleFeeds.isNotEmpty(), compatibleFeeds.sumOf { pathsByFeed[it] ?: 0 },
                sharedExpected + if (completeData) 0 else 1)
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
        account(removePromotion, plan.removePromotion, "promotion")
        account(removeLockedChargeOnly, plan.removeLockedChargeOnly, "charge-only")
        account(hideTopicList, feeds.any { it.clearTopicList != null }, "topic-list")
        account(removeLiveUpEntries, feeds.any { it.upList != null }, "up-live")
        if (degraded.isNotEmpty()) {
            environment.logError(
                "dynamic_purify_degraded",
                "[BIL] 动态页净化判据缺少可用读取路径: ${degraded.joinToString(",")}"
            )
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
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

    /** 两条链路共用；未改原响应，成功时返回副本；处理已净化副本时保持幂等。 */
    private fun purify(
        environment: HookEnvironment,
        reply: Any,
        feed: FeedMembers,
        itemMembers: ItemMembers?,
        plan: DynamicPurifyPolicy.Plan
    ): Any {
        if (!feed.replyClass.isInstance(reply)) return reply
        // 字段未设置时宿主拿到的是进程级单例，改它会污染整个进程。
        if (feed.defaultReply != null && reply === feed.defaultReply) return reply
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        return runCatching {
            val items = purifyItems(reply, feed, itemMembers, plan)
            val clearTopic = hideTopicList && feed.clearTopicList != null &&
                feed.hasTopicList?.let { invoke(it, reply) as? Boolean } == true
            val upList = purifyUpList(reply, feed)
            if (items == null && !clearTopic && upList == null) return@runCatching reply
            val updated = feed.builder.edit(reply) { builder ->
                if (items != null) feed.setDynamicList!!.invoke(builder, items)
                if (clearTopic) feed.clearTopicList.invoke(builder)
                if (upList != null) feed.upList!!.setter.invoke(builder, upList)
            }
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            updated
        }.getOrElse {
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
        val dynamicList = invoke(feed.dynamicListGetter, reply) ?: return null
        val items = invoke(feed.listGetter, dynamicList) as? List<*> ?: return null
        if (items.isEmpty()) return null
        val retained = ProtobufListRetention.retainOrNull(items) { item ->
            !DynamicPurifyPolicy.shouldRemove(readSignals(item, itemMembers, plan), plan)
        } ?: return null
        val removed = items.size - retained.size
        if (removed <= 0) return null
        return listBuilder.edit(dynamicList) { builder ->
            clearList.invoke(builder)
            addAllList.invoke(builder, retained)
        }
    }

    private fun purifyUpList(reply: Any, feed: FeedMembers): Any? {
        if (!removeLiveUpEntries) return null
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

    private fun readSignals(
        item: Any,
        members: ItemMembers,
        plan: DynamicPurifyPolicy.Plan
    ): DynamicPurifyPolicy.Signals {
        val extend = if (plan.needsText || plan.needsAuthor || plan.removeLockedChargeOnly) {
            members.extendGetter?.let { invoke(it, item) }
        } else {
            null
        }
        val modules = if (plan.needsText || plan.removePromotion) {
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
        val dynamicListGetter = objectGetter(replyClass, "getDynamicList")
        // 容器类名两个页签不同（综合 DynamicList / 视频 CardVideoDynList），所以按返回类型取，
        // 不写死类名。但**必须验证元素类型确实是 DynamicItem**：否则条目判据会因为
        // declaringClass 不匹配而全部读成 null，表现为"装上了却一条都不删"的静默无效。
        val listClass = dynamicListGetter?.returnType
        val listBuilder = listClass?.let(ProtobufBuilderPlan::resolve)
        val setDynamicList = listClass?.let { builder.method("setDynamicList", it) }
        val itemClass = KavaMemberLookup.classOrNull(loader, DYNAMIC_ITEM_CLASS)
        val indexedGetter = listClass?.let { KavaMemberLookup.methodOrNull(it, "getList", classOf<Int>()) }
        val listGetter = listClass?.let { listGetter(it, "getListList") }
        val clearList = listBuilder?.method("clearList")
        val addAllList = listBuilder?.method("addAllList", classOf<Iterable<*>>())
        val itemCarrierReady = itemClass != null && indexedGetter?.returnType == itemClass &&
            setDynamicList != null && listGetter != null && clearList != null && addAllList != null

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

        val hasTopic = booleanNoArg(replyClass, "hasTopicList")
        val clearTopic = builder.method("clearTopicList")
        val upList = if (removeLiveUpEntries) resolveUpList(loader, replyClass, builder) else null

        return FeedMembers(
            replyClass = replyClass,
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
            syncMethod = syncMethod,
            asyncMethod = asyncMethod
        )
    }

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
    )

    private class FeedMembers(
        val replyClass: Class<*>,
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

        /** 综合页与视频页；两页共用同一套判据。 */
        private val FEED_METHODS = listOf(
            FeedSpec("$DYNAMIC_PACKAGE.DynAllReply", "executeDynAll", "dynAll"),
            FeedSpec("$DYNAMIC_PACKAGE.DynVideoReply", "executeDynVideo", "dynVideo")
        )
    }
}
