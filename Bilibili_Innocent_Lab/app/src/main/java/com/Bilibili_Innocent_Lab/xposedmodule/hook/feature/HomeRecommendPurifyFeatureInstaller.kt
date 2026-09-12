package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 在首页推荐响应的公开 List 边界过滤广告、图文和游戏推广。 */
internal class HomeRecommendPurifyFeatureInstaller(
    private val removeAds: Boolean,
    private val removeCmV2: Boolean,
    private val removeBanner: Boolean,
    private val removePictures: Boolean,
    private val removeGamePromotions: Boolean,
    titleFilterEnabled: Boolean,
    rawTitleKeywords: String,
    private val removeLive: Boolean,
    private val removeCourses: Boolean,
    private val removeVertical: Boolean,
    private val removeLarge: Boolean,
    minDurationSeconds: Int,
    maxDurationSeconds: Int,
    private val points: VersionAdapter.HomeRecommendFeedPoints?,
    private val removePgc: Boolean = false,
    private val removeSpecialCards: Boolean = false,
    /**
     * 标签黑名单的原始存储串；空串就是整个维度关闭。
     *
     * 同一条串里数字按标签 id 比、非数字按标签名整串比，见 [TidBlocklistCodec]。
     */
    rawBlockedTids: String = "",
    /**
     * UP 主黑名单的原始存储串；空串就是整个维度关闭。
     *
     * 判据与详情页那档一致：整串相等，同时承认 UP 名与 mid。
     */
    rawBlockedAuthors: String = "",
    /**
     * 三点面板劫持是否开着。
     *
     * 开着时即使黑名单为空也要解析 tid / UP 读取链——用户可能这一场才从面板点第一个，
     * 那些选择只活在 [SectionPickSession] / [AuthorPickSession] 的内存里，
     * 没有 accessor 就当场失效。
     */
    private val sectionPickEnabled: Boolean = false
) : FeatureInstaller {

    private val titleKeywords = if (titleFilterEnabled) {
        RuleSetCodec.parse(rawTitleKeywords)
    } else {
        emptySet()
    }
    private val durationRange = VideoDurationRange(minDurationSeconds, maxDurationSeconds)

    /** 没有开关，名单非空即启用——与标题关键词同模式，避免"开着但名单为空"的无意义状态。 */
    private val blockedTids = TidBlocklistCodec.parse(rawBlockedTids)

    /** 同一条串里的非数字项按标签名整串比，判据与详情页那档共用 [ExactRuleSetCodec]。 */
    private val blockedTagNames = TidBlocklistCodec.parseNames(rawBlockedTids)

    /** 这个维度是否需要解析读取链；名字和 id 任一非空都要。 */
    private val tagDimensionEnabled =
        blockedTids.isNotEmpty() || blockedTagNames.isNotEmpty() || sectionPickEnabled

    /** UP 主名单：整串相等，名字与 mid 共用一份，见 [ExactRuleSetCodec]。 */
    private val blockedAuthors = ExactRuleSetCodec.parse(rawBlockedAuthors)

    /** UP 维度是否需要解析读取链；面板劫持开着时即使名单为空也要。 */
    private val authorDimensionEnabled = blockedAuthors.isNotEmpty() || sectionPickEnabled

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (removeBanner) add("home_banner_feed")
        if (removeAds) add("home_recommend_ads_removed")
        if (removeCmV2) add("home_recommend_cm_v2_removed")
        if (removePictures) add("home_recommend_pictures_removed")
        if (removeGamePromotions) add("home_recommend_game_promotions_removed")
        if (titleKeywords.isNotEmpty()) add("home_recommend_title_filter_enabled")
        if (removeLive) add("home_recommend_live_removed")
        if (removeCourses) add("home_recommend_courses_removed")
        if (removeVertical) add("home_recommend_vertical_removed")
        if (removeLarge) add("home_recommend_large_removed")
        if (removePgc) add("home_recommend_pgc_removed")
        if (removeSpecialCards) add("home_recommend_special_cards_removed")
        if (durationRange.isEnabled) add("home_recommend_duration_filter")
        if (tagDimensionEnabled) add("home_recommend_tid_block")
        if (authorDimensionEnabled) add("home_recommend_author_block")
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val hasContentFilter = removeAds || removeCmV2 || removeBanner || removePictures || removeGamePromotions ||
            titleKeywords.isNotEmpty() || removeLive || removeCourses || removeVertical ||
            removeLarge || removePgc || removeSpecialCards || tagDimensionEnabled ||
            authorDimensionEnabled
        if (durationRange.isConfigured && !durationRange.isValid) {
            environment.logError(
                "home_recommend_duration_invalid",
                "[BIL] 推荐视频时长范围无效，已保守放行: " +
                    "min=${durationRange.minSeconds},max=${durationRange.maxSeconds}"
            )
        }
        if (!hasContentFilter && !durationRange.isEnabled) {
            val reason = if (durationRange.isConfigured && !durationRange.isValid) {
                "invalid-duration-range"
            } else {
                "disabled"
            }
            environment.reportStatus(CHANNEL_STATUS, reason)
            return FeatureInstallResult.Skipped(reason)
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = Accessors(
            holderType = resolve(environment, "holder", adapted.holderTypeGetter)
                ?: return missing(environment, "missing-holder-getter"),
            bizType = resolveOptional(environment, "biz", adapted.bizTypeGetter),
            adInfo = resolveOptional(environment, "ad_info", adapted.adInfoGetter),
            cardType = resolveOptional(environment, "card_type", adapted.cardTypeGetter),
            cardGoto = resolveOptional(environment, "card_goto", adapted.cardGotoGetter),
            goTo = resolveOptional(environment, "goto", adapted.goToGetter),
            uri = resolveOptional(environment, "uri", adapted.uriGetter),
            param = resolveOptional(environment, "param", adapted.paramGetter),
            title = resolveOptional(environment, "title", adapted.titleGetter),
            subtitle = resolveOptional(environment, "subtitle", adapted.subtitleGetter),
            desc = resolveOptional(environment, "desc", adapted.descGetter),
            duration = resolveDuration(environment, adapted),
            tid = resolveTid(environment, adapted),
            author = resolveAuthor(environment, adapted)
        )
        var partialReason: String? = null
        val extraTypesReadable = accessors.cardType != null || accessors.cardGoto != null || accessors.goTo != null
        if (removePgc && !extraTypesReadable && accessors.uri == null) partialReason = "missing-pgc-readers"
        if (removeSpecialCards && !extraTypesReadable) partialReason = "missing-special-card-readers"
        if (durationRange.isEnabled && accessors.duration == null) {
            if (!hasContentFilter) return missing(environment, "missing-duration-accessor")
            partialReason = "missing-duration-accessor"
            environment.logError(
                "home_recommend_duration_missing",
                "[BIL] 首页推荐时长读取适配不完整，其他推荐过滤继续生效"
            )
        }
        // 名单非空却读不到 tid：必须报 partial。否则每张卡都拿 null、静默变成"从不命中"，
        // 用户只会看到"设了标签但没生效"，而状态通道一路 success。
        if (tagDimensionEnabled && accessors.tid == null) {
            partialReason = "missing-tid-accessor"
            environment.logError(
                "home_recommend_tid_missing",
                "[BIL] 首页推荐标签读取适配不完整（args/tid 链缺失），" +
                    "标签维度不生效，其他推荐过滤继续"
            )
        }
        // UP 名单非空却读不到读取链：同理必须报 partial，不能静默变成"从不命中"。
        if (authorDimensionEnabled && accessors.author == null) {
            partialReason = "missing-author-accessor"
            environment.logError(
                "home_recommend_author_missing",
                "[BIL] 首页推荐 UP 读取适配不完整（args/up 链缺失），" +
                    "UP 维度不生效，其他推荐过滤继续"
            )
        }
        // 只填了标签名却读不到 tname：同理，名字那半边会静默失效。
        if (blockedTagNames.isNotEmpty() && accessors.tid?.tnameGetter == null) {
            partialReason = "missing-tname-accessor"
            environment.logError(
                "home_recommend_tname_missing",
                "[BIL] 首页推荐标签名读取适配不完整（args/tname 链缺失），" +
                    "按名字那半边不生效，按 id 那半边继续"
            )
        }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.recommend.purify.$index", point) {
                    after {
                        if (hasThrowable) return@after
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        var removedBanners = 0
                        var removedPgc = 0
                        var removedSpecial = 0
                        val filtered = CopyOnFilter.list(source) { item ->
                            val signals = signals(item, accessors)
                            probeTag(signals, environment)
                            val isBanner = removeBanner && isHomeBanner(signals)
                            if (isBanner) removedBanners += 1
                            val kind = if (removePgc || removeSpecialCards) HomeExtraCardPolicy.classify(
                                signals.cardType, signals.cardGoto, signals.goTo, signals.uri
                            ) else HomeExtraCardPolicy.Kind.UNKNOWN
                            val pgc = removePgc && kind == HomeExtraCardPolicy.Kind.PGC
                            val special = removeSpecialCards && kind == HomeExtraCardPolicy.Kind.SPECIAL
                            if (pgc) { removedPgc++; environment.reportRuntimeEvidence("home_recommend_pgc_removed", FeatureRuntimeStage.OBSERVED) }
                            if (special) { removedSpecial++; environment.reportRuntimeEvidence("home_recommend_special_cards_removed", FeatureRuntimeStage.OBSERVED) }
                            pgc || special || isBanner || shouldRemove(signals)
                        }
                        if (filtered !== source) {
                            result = filtered
                            if (removedPgc > 0) environment.reportRuntimeEvidence("home_recommend_pgc_removed", FeatureRuntimeStage.APPLIED)
                            if (removedSpecial > 0) environment.reportRuntimeEvidence("home_recommend_special_cards_removed", FeatureRuntimeStage.APPLIED)
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                            environment.logInfo(
                                "home_recommend_removed",
                                "[BIL] 首页推荐服务端数据已过滤 ${source.size - filtered.size} 项"
                            )
                        }
                        if (removedBanners > 0) {
                            environment.reportRuntimeEvidence(
                                "home_banner_feed",
                                FeatureRuntimeStage.OBSERVED
                            )
                            environment.reportRuntimeEvidence(
                                "home_banner_feed",
                                FeatureRuntimeStage.APPLIED,
                                removedBanners
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_recommend_purify_$index",
                    "[BIL] 首页推荐服务端过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        val routeReadable = accessors.cardType != null || accessors.cardGoto != null ||
            accessors.goTo != null || accessors.uri != null
        for (capability in capabilityIds) {
            val readable = when (capability) {
                "home_recommend_duration_filter" -> accessors.duration != null
                "home_recommend_title_filter_enabled" -> accessors.title != null
                "home_recommend_ads_removed" -> true // required holderType is a valid advertisement-token source
                "home_recommend_cm_v2_removed" -> accessors.cardType != null // this predicate reads cardType only
                "home_banner_feed" -> true
                "home_recommend_large_removed" -> true // required holderType is resolved above
                "home_recommend_pgc_removed" -> extraTypesReadable || accessors.uri != null
                "home_recommend_special_cards_removed" -> extraTypesReadable
                "home_recommend_tid_block" -> accessors.tid != null
                else -> routeReadable
            }
            environment.reportCapabilityCoverage(capability, readable, installed, adapted.responseItemGetters.size)
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(
            CHANNEL_STATUS,
            partialReason?.let { "partial:$it" } ?:
                if (installed == adapted.responseItemGetters.size) "success" else "partial:$installed/${adapted.responseItemGetters.size}"
        )
        environment.logInfo(
            "home_recommend_purify_ok",
            "[BIL] 首页推荐服务端过滤已安装，hooks=$installed," +
                "duration=${durationRange.isEnabled}," +
                // 只记条数不记内容；用来区分"名单没传到宿主"和"传到了但不命中"。
                "tagIds=${blockedTids.size},tagNames=${blockedTagNames.size}," +
                "tnameReadable=${accessors.tid?.tnameGetter != null}"
        )
        return FeatureInstallResult.Installed(installed, complete = partialReason == null && installed == adapted.responseItemGetters.size)
    }

    /** 已经记过的 `tid:tname`，每种只记一条。 */
    private val probedTags = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 把首页卡片实际带的 `tid` / `tname` 记进日志，**只在这个维度开着时**才做。
     *
     * 为什么需要：一个视频有很多标签，`args.tname` 只是其中**某一个**，未必是用户在
     * 卡片上看到、或者心里想的那个。没有这条日志，用户填了名字不生效时无从下手——
     * 只能反复猜。有了它，照着日志里的值填就一定命中。
     *
     * `tname` 本来就会经观测快照通道呈现给用户（`SURFACE_SECTION_PICKS` 的条目标题），
     * 记进日志不越界。有界：只记 [MAX_PROBED_TAGS] 种，之后彻底静默。
     */
    private fun probeTag(signals: Signals, environment: HookEnvironment) {
        if (!tagDimensionEnabled || probedTags.size >= MAX_PROBED_TAGS) return
        val tid = signals.tid ?: return
        if (tid <= 0L) return
        val key = "$tid:${signals.tname}"
        if (!probedTags.add(key)) return
        // 日志 key 必须带上这条观测的身份，宿主桥按 key 全局去重。
        environment.logInfo(
            "home_recommend_tag_seen:$key",
            "[BIL] 首页卡片标签 tid=$tid, tname=${signals.tname}, rid=${signals.rid}"
        )
    }

    private fun shouldRemove(signals: Signals): Boolean {
        val hostSignals = signals.toHostSignals()
        val kinds = HostContentSemanticClassifier.classify(hostSignals)
        return (removeCmV2 && HostContentSemanticClassifier.isCmV2(hostSignals)) ||
            (removeAds && HostContentKind.ADVERTISEMENT in kinds) ||
            (removePictures && HostContentKind.PICTURE in kinds) ||
            (removeGamePromotions && HostContentKind.GAME in kinds) ||
            RuleSetCodec.matches(titleKeywords, signals.title) ||
            (removeLive && HostContentKind.LIVE in kinds) ||
            (removeCourses && HostContentKind.COURSE in kinds) ||
            (removeVertical && HostContentKind.VERTICAL in kinds) ||
            (removeLarge && HostContentKind.LARGE in kinds) ||
            durationRange.shouldRemove(signals.durationSeconds) ||
            // 标签是独立维度：精确相等，读不到 tid 放行。
            // **只比 tid**。2026-09-12 实测：宿主把 `args.tid` 写进不感兴趣请求时用的键是
            // `tag_id`，`args.rid` 才是分区——两者不是同一个 id 空间。拿分区 id 去比这份
            // 标签名单会误删：分区 id 都是两三位数，撞上一个小标签 id 是迟早的事。
            // 持久名单与本场会话选择是两个来源，任一命中即删；
            // 会话那份不落盘，见 SectionPickSession。
            TidBlocklistCodec.matches(blockedTids, signals.tid) ||
            SectionPickSession.contains(signals.tid) ||
            // 标签名走整串相等，和详情页那档同一个 codec：contains 语义会让"科技"
            // 命中"科技美学"，用在标签上一定误伤。
            ExactRuleSetCodec.matches(blockedTagNames, signals.tname) ||
            // UP 是独立维度：名字与 mid 任一整串相等即删，读不到一律放行。
            // 持久名单与本场会话选择是两个来源，见 AuthorPickSession。
            ExactRuleSetCodec.matches(blockedAuthors, signals.upName, signals.upId) ||
            AuthorPickSession.matches(signals.upName, signals.upId)
    }

    private fun signals(item: Any, accessors: Accessors): Signals {
        val needsRoute = removePictures || removeGamePromotions || removeLive ||
            removeCourses || removeVertical || removeLarge || removePgc || removeSpecialCards
        val needsClassification = removeAds || removeCmV2 || needsRoute
        return Signals(
            holderType = if (removeAds || removeBanner || removeLarge) {
                invokeString(accessors.holderType, item)
            } else {
                null
            },
            bizType = if (removeAds || removeBanner) {
                invokeString(accessors.bizType, item)
            } else {
                null
            },
            cardType = if (needsClassification || removeBanner) {
                invokeString(accessors.cardType, item)
            } else null,
            cardGoto = if (removeAds || removeBanner || needsRoute) {
                invokeString(accessors.cardGoto, item)
            } else {
                null
            },
            goTo = if (needsRoute || removeBanner) {
                invokeString(accessors.goTo, item)
            } else {
                null
            },
            uri = if (needsRoute) invokeString(accessors.uri, item) else null,
            param = if (removeGamePromotions) invokeString(accessors.param, item) else null,
            title = if (titleKeywords.isNotEmpty() || removeGamePromotions) {
                invokeString(accessors.title, item)
            } else {
                null
            },
            subtitle = if (removeGamePromotions) invokeString(accessors.subtitle, item) else null,
            desc = if (removeGamePromotions) invokeString(accessors.desc, item) else null,
            hasAdInfo = (removeAds || removeGamePromotions) &&
                invokeCompatible(accessors.adInfo, item) != null,
            durationSeconds = if (durationRange.isEnabled) {
                accessors.duration?.let { duration ->
                    VideoDurationReader.fromContainer(
                        item,
                        duration.playerArgsGetter,
                        duration.durationGetter,
                        duration.durationField
                    )
                }
            } else {
                null
            },
            // 名单为空且没开面板劫持时 accessors.tid 是 null，这里不产生任何反射调用。
            // id 与名字共用同一次 getArgs()，热路径上不会多一次容器反射。
            tid = accessors.tid?.let { chain ->
                invokeCompatible(chain.argsGetter, item)?.let { args ->
                    (invokeCompatible(chain.tidGetter, args) as? Number)?.toLong()
                }
            },
            tname = accessors.tid?.tnameGetter?.let { getter ->
                invokeCompatible(accessors.tid.argsGetter, item)?.let { args ->
                    invokeCompatible(getter, args) as? String
                }
            },
            // 只喂探针。探针记满就静默，之后这里的反射也随之停掉。
            rid = accessors.tid?.ridGetter?.takeIf { probedTags.size < MAX_PROBED_TAGS }
                ?.let { getter ->
                    invokeCompatible(accessors.tid.argsGetter, item)?.let { args ->
                        (invokeCompatible(getter, args) as? Number)?.toLong()
                    }
                },
            // UP 维度关着时 accessors.author 是 null，这里不产生任何反射调用。
            upName = accessors.author?.let { chain ->
                invokeCompatible(chain.argsGetter, item)?.let { args ->
                    invokeCompatible(chain.upNameGetter, args) as? String
                }
            },
            // mid 以十进制串参与比对，这样用户填名字或填 mid 都能命中。
            upId = accessors.author?.upIdGetter?.let { getter ->
                invokeCompatible(accessors.author.argsGetter, item)?.let { args ->
                    (invokeCompatible(getter, args) as? Number)?.takeIf { it.toLong() > 0L }
                        ?.toLong()?.toString()
                }
            }
        )
    }

    /** 名单为空时**不解析**，免得在热路径上白做两次反射查找。 */
    private fun resolveTid(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): TidAccessor? {
        if (!tagDimensionEnabled) return null
        val argsPoint = points.argsGetter ?: return null
        val tidPoint = points.argsTidGetter ?: return null
        val argsGetter = resolve(environment, "args", argsPoint) ?: return null
        val tidGetter = environment.hookPoints.resolveAdapted(
            "home.recommend.resolve.args_tid",
            tidPoint.className,
            tidPoint.methodName,
            tidPoint.paramClassNames
        ) ?: return null
        // 这个维度开着就解析：除了按名字比，探针也要用它把卡片实际的标签名记进日志
        // ——没有那条日志，用户填了名字不生效时只能反复猜。
        val tnameGetter = points.argsTnameGetter
            ?.let { point ->
                environment.hookPoints.resolveAdapted(
                    "home.recommend.resolve.args_tname",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )
            }
        // 分区 id 只喂探针，不参与判定；缺了什么都不影响。
        val ridGetter = points.argsRidGetter?.let { point ->
            environment.hookPoints.resolveAdapted(
                "home.recommend.resolve.args_rid",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }
        return TidAccessor(argsGetter, tidGetter, tnameGetter, ridGetter)
    }

    /** 名单为空且没开面板劫持时**不解析**，免得在热路径上白做反射查找。 */
    private fun resolveAuthor(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): AuthorAccessor? {
        if (!authorDimensionEnabled) return null
        val argsPoint = points.argsGetter ?: return null
        val namePoint = points.argsUpNameGetter ?: return null
        val argsGetter = resolve(environment, "args", argsPoint) ?: return null
        val upNameGetter = environment.hookPoints.resolveAdapted(
            "home.recommend.resolve.args_up_name",
            namePoint.className,
            namePoint.methodName,
            namePoint.paramClassNames
        ) ?: return null
        val upIdGetter = points.argsUpIdGetter?.let { point ->
            environment.hookPoints.resolveAdapted(
                "home.recommend.resolve.args_up_id",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }
        return AuthorAccessor(argsGetter, upNameGetter, upIdGetter)
    }

    private fun resolveDuration(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): DurationAccessor? {
        if (!durationRange.isEnabled) return null
        val getterPoint = points.playerArgsGetter ?: return null
        val getter = resolve(environment, "player_args", getterPoint) ?: return null
        val durationGetter = points.playerArgsDurationGetter?.let { point ->
            resolve(environment, "player_args_duration", point)
        }
        val durationField = points.playerArgsDurationField?.let { fieldName ->
            environment.hookPoints.resolveField(
                "home.recommend.resolve.player_args_duration_field",
                getter.returnType,
                fieldName
            )
        }
        if (durationGetter == null && durationField == null) return null
        return DurationAccessor(getter, durationGetter, durationField)
    }

    private fun resolveOptional(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint?
    ): Method? = point?.let { resolve(environment, suffix, it) }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "home.recommend.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun invokeString(method: Method?, target: Any): String? =
        invokeCompatible(method, target)?.toString()

    private fun invokeCompatible(method: Method?, target: Any): Any? {
        if (method == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "home_recommend_purify_missing",
            "[BIL] 首页推荐服务端过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val holderType: String? = null,
        val bizType: String? = null,
        val cardType: String? = null,
        val cardGoto: String? = null,
        val goTo: String? = null,
        val uri: String? = null,
        val param: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val desc: String? = null,
        val hasAdInfo: Boolean = false,
        val durationSeconds: Long? = null,
        /**
         * `args.tid`——**标签 id**（宿主自己按 `tag_id` 上报），不是分区。
         *
         * 读不到就是 null，**null 一律放行**（番剧/广告卡本来就没有标签）。
         */
        val tid: Long? = null,
        /** `args.tname`——标签名；同上，读不到放行。 */
        val tname: String? = null,
        /** `args.upName`——UP 主名；读不到放行。 */
        val upName: String? = null,
        /** `args.upId`——UP 主 mid 的十进制串；读不到放行。 */
        val upId: String? = null,
        /**
         * `args.rid`——**分区 id**（宿主按 `rid` 上报），**只进探针日志、不参与任何判定**。
         *
         * 2026-09-12 实测 30 张卡：**27 张是 0**，非 0 的三个（3/3/11）与卡片内容也对不上
         * 标准分区表。**结论：新版 feed 协议的卡片 args 里没有可用的分区，"按分区过滤"
         * 这条路不通。** 留着这条探针是为了宿主哪天把它填上时能第一时间发现，
         * 不是留着给判定用的。
         */
        val rid: Long? = null
    )

    private data class Accessors(
        val holderType: Method,
        val bizType: Method?,
        val adInfo: Method?,
        val cardType: Method?,
        val cardGoto: Method?,
        val goTo: Method?,
        val uri: Method?,
        val param: Method?,
        val title: Method?,
        val subtitle: Method?,
        val desc: Method?,
        val duration: DurationAccessor?,
        val tid: TidAccessor?,
        val author: AuthorAccessor?
    )

    /**
     * UP 读取链：容器 getter + 名字 getter，缺任一级整个维度不可用。
     *
     * [upIdGetter] 可缺——缺了只是不能按 mid 命中，按名字那半边照常。
     */
    private data class AuthorAccessor(
        val argsGetter: Method,
        val upNameGetter: Method,
        val upIdGetter: Method? = null
    )

    /**
     * 两级链：容器 getter + 数值 getter，缺任一级整个维度不可用。
     *
     * [tnameGetter] 是按名字比那半边，可缺——缺了只影响名字，id 那半边照常。
     * [ridGetter] 只喂探针，缺了什么都不影响。
     */
    private data class TidAccessor(
        val argsGetter: Method,
        val tidGetter: Method,
        val tnameGetter: Method? = null,
        val ridGetter: Method? = null
    )

    private data class DurationAccessor(
        val playerArgsGetter: Method,
        val durationGetter: Method?,
        val durationField: Field?
    )

    companion object {
        const val ID = "home_recommend_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_recommend_purify_status"

        /** 标签探针上限：记满这么多种就彻底静默，不会随刷新无限增长。 */
        private const val MAX_PROBED_TAGS = 30

        internal fun isAdvertisement(value: Signals): Boolean {
            return HostContentKind.ADVERTISEMENT in
                HostContentSemanticClassifier.classify(value.toHostSignals())
        }

        internal fun isHomeBanner(value: Signals): Boolean =
            HostContentSemanticClassifier.isHomeBanner(value.toHostSignals())

        internal fun isCmV2(value: Signals): Boolean =
            HostContentSemanticClassifier.isCmV2(value.toHostSignals())

        internal fun isPicture(value: Signals): Boolean =
            HostContentKind.PICTURE in HostContentSemanticClassifier.classify(
                value.toHostSignals()
            )

        internal fun isGamePromotion(value: Signals): Boolean =
            HostContentKind.GAME in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isLive(value: Signals): Boolean =
            HostContentKind.LIVE in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isCourse(value: Signals): Boolean =
            HostContentKind.COURSE in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isVertical(value: Signals): Boolean =
            HostContentKind.VERTICAL in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isLarge(value: Signals): Boolean =
            HostContentKind.LARGE in HostContentSemanticClassifier.classify(value.toHostSignals())

        private fun Signals.toHostSignals(): HostContentSignals = HostContentSignals(
            holderType = holderType,
            bizType = bizType,
            cardType = cardType,
            cardGoto = cardGoto,
            goTo = goTo,
            uri = uri,
            param = param,
            title = title,
            subtitle = subtitle,
            desc = desc,
            hasAdInfo = hasAdInfo
        )
    }
}
