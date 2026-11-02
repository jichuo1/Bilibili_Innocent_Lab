package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHomeRules
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneId

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
    private val removeComic: Boolean = false,
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
    private val sectionPickEnabled: Boolean = false,
    minPlayCount: Int = 0,
    maxPlayCount: Int = 0,
    /**
     * 「屏蔽 AI 生成声明视频」的首页一档：`uri` 里 `creation_tags` 含 `aigc`，
     * 或 aid 已被详情页确认过（[AiDeclaredVideoRegistry]）。判据见 [AiDeclaredVideoPolicy]。
     */
    private val removeAiDeclared: Boolean = false,
    /**
     * 强力模式开着时，详情页记下的发布者只写进 [AuthorPickSession]；
     * 这里必须据此打开 UP 维度的读取链，否则"本进程立即生效"那半句不成立。
     */
    aiDeclaredStrongMode: Boolean = false,
    /** 发布时间规则，见 [VideoPublishTimeRule]；空串表示关闭。 */
    rawPublishTimeRule: String = "",
    private val clock: () -> Instant = Instant::now,
    private val ruleZone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * 智能过滤推荐视频（JEV，按标题）。列表 getter 可能在主线程：主线程只查缓存并投后台；
     * 后台线程且开了"首屏等待"时在当前线程等结果。
     */
    semanticJudge: SemanticJudge? = null,
    /** debug 构建的观测日志目录；release 为 null。 */
    semanticLogDir: java.io.File? = null,
    isMainThread: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
    private val liveRules: (() -> LiveHomeRules)? = null
) : FeatureInstaller {

    private val semantic = SemanticTitleFilter(semanticJudge, semanticLogDir, isMainThread, "home")

    private val titleKeywords = if (titleFilterEnabled) {
        RuleSetCodec.parse(rawTitleKeywords)
    } else {
        emptySet()
    }
    private val durationRange = VideoDurationRange(minDurationSeconds, maxDurationSeconds)
    private val playCountRange = VideoPlayCountRange(minPlayCount, maxPlayCount)
    private val publishTimeRule = VideoPublishTimeRule.parse(rawPublishTimeRule)

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
    private val authorDimensionEnabled = blockedAuthors.isNotEmpty() || sectionPickEnabled ||
        (removeAiDeclared && aiDeclaredStrongMode)

    /** 只有这些开关才需要把卡片公开字段交给语义分类器；Banner 单独走精确 token 判定。 */
    private val semanticClassificationEnabled =
        removeCmV2 || removeAds || removePictures || removeGamePromotions ||
            removeLive || removeCourses || removeVertical || removeLarge

    /** `shouldRemove` 的其余判定也全部关闭时，直接跳过整段分类/规则工作。 */
    private val itemRemovalEnabled = semanticClassificationEnabled ||
        titleKeywords.isNotEmpty() || durationRange.isEnabled ||
        playCountRange.isEnabled || publishTimeRule.isEnabled || tagDimensionEnabled ||
        authorDimensionEnabled || removeAiDeclared || semantic.enabled

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
        if (removeComic) add("home_recommend_comic_removed")
        if (removeSpecialCards) add("home_recommend_special_cards_removed")
        if (durationRange.isEnabled) add("home_recommend_duration_filter")
        if (playCountRange.isEnabled) add("home_recommend_play_count_filter")
        if (publishTimeRule.isEnabled) add("home_recommend_publish_time_filter")
        if (tagDimensionEnabled) add("home_recommend_tid_block")
        if (authorDimensionEnabled) add("home_recommend_author_block")
        if (removeAiDeclared) add(AiDeclaredVideoPolicy.CAPABILITY_HOME)
        if (semantic.enabled) add(CAPABILITY_SEMANTIC)
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val hasContentFilter = removeAds || removeCmV2 || removeBanner || removePictures || removeGamePromotions ||
            titleKeywords.isNotEmpty() || removeLive || removeCourses || removeVertical ||
            removeLarge || removePgc || removeSpecialCards || removeComic || tagDimensionEnabled ||
            authorDimensionEnabled || removeAiDeclared || semantic.enabled
        if (durationRange.isConfigured && !durationRange.isValid) {
            environment.logError(
                "home_recommend_duration_invalid",
                "[BIL] 推荐视频时长范围无效，已保守放行: " +
                    "min=${durationRange.minSeconds},max=${durationRange.maxSeconds}"
            )
        }
        if (playCountRange.isConfigured && !playCountRange.isValid) {
            environment.logError(
                "home_recommend_play_count_invalid",
                "[BIL] 推荐视频播放量范围无效，已保守放行: " +
                    "min=${playCountRange.minimum},max=${playCountRange.maximum}"
            )
        }
        if (!publishTimeRule.isValid) {
            environment.logError(
                "home_recommend_publish_time_invalid",
                "[BIL] 推荐视频发布时间规则无法解析，已保守放行"
            )
        }
        if (!hasContentFilter && !durationRange.isEnabled && !playCountRange.isEnabled &&
            !publishTimeRule.isEnabled
        ) {
            val reason = when {
                durationRange.isConfigured && !durationRange.isValid -> "invalid-duration-range"
                playCountRange.isConfigured && !playCountRange.isValid -> "invalid-play-count-range"
                !publishTimeRule.isValid -> "invalid-publish-time-rule"
                else -> "disabled"
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
            playCountGate = resolvePlayCountGate(environment, adapted),
            tid = resolveTid(environment, adapted),
            author = resolveAuthor(environment, adapted),
            publishTimeGate = resolvePublishTimeGate(environment, adapted)
        )
        var partialReason: String? = null
        val extraTypesReadable = accessors.cardType != null || accessors.cardGoto != null || accessors.goTo != null
        if (removePgc && !extraTypesReadable && accessors.uri == null) {
            partialReason = partialReason?.let { "$it+missing-pgc-readers" } ?: "missing-pgc-readers"
        }
        if (removeSpecialCards && !extraTypesReadable) {
            partialReason = partialReason?.let { "$it+missing-special-card-readers" } ?: "missing-special-card-readers"
        }
        if (removeComic && !extraTypesReadable && accessors.uri == null) {
            partialReason = partialReason?.let { "$it+missing-comic-readers" } ?: "missing-comic-readers"
        }
        if (durationRange.isEnabled && accessors.duration == null) {
            if (!hasContentFilter && !playCountRange.isEnabled && !publishTimeRule.isEnabled) {
                return missing(environment, "missing-duration-accessor")
            }
            partialReason = "missing-duration-accessor"
            environment.logError(
                "home_recommend_duration_missing",
                "[BIL] 首页推荐时长读取适配不完整，其他推荐过滤继续生效"
            )
        }
        val playCountGate = accessors.playCountGate ?: accessors.duration?.playerArgsGetter
        if (playCountRange.isEnabled && playCountGate == null) {
            if (!hasContentFilter && !durationRange.isEnabled && !publishTimeRule.isEnabled) {
                return missing(environment, "missing-play-count-gate")
            }
            partialReason = if (partialReason == null) {
                "missing-play-count-gate"
            } else {
                "$partialReason+missing-play-count-gate"
            }
            environment.logError(
                "home_recommend_play_count_missing",
                "[BIL] 首页推荐播放量读取门禁适配不完整，其他推荐过滤继续生效"
            )
        }
        // 发布时间读首页卡片描述文案，前提是能确认卡片是普通投稿（PlayerArgs 闸门）。
        if (publishTimeRule.isEnabled && accessors.publishTimeGate == null) {
            if (!hasContentFilter && !durationRange.isEnabled && !playCountRange.isEnabled) {
                return missing(environment, "missing-publish-time-gate")
            }
            partialReason = if (partialReason == null) {
                "missing-publish-time-gate"
            } else {
                "$partialReason+missing-publish-time-gate"
            }
            environment.logError(
                "home_recommend_publish_time_missing",
                "[BIL] 首页推荐发布时间读取门禁适配不完整，其他推荐过滤继续生效"
            )
        }
        // 名单非空却读不到 tid：必须报 partial。否则每张卡都拿 null、静默变成"从不命中"，
        // 用户只会看到"设了标签但没生效"，而状态通道一路 success。
        if (tagDimensionEnabled && accessors.tid == null) {
            partialReason = if (partialReason == null) "missing-tid-accessor"
            else "$partialReason+missing-tid-accessor"
            environment.logError(
                "home_recommend_tid_missing",
                "[BIL] 首页推荐标签读取适配不完整（args/tid 链缺失），" +
                    "标签维度不生效，其他推荐过滤继续"
            )
        }
        // UP 名单非空却读不到读取链：同理必须报 partial，不能静默变成"从不命中"。
        if (authorDimensionEnabled && accessors.author == null) {
            partialReason = if (partialReason == null) "missing-author-accessor"
            else "$partialReason+missing-author-accessor"
            environment.logError(
                "home_recommend_author_missing",
                "[BIL] 首页推荐 UP 读取适配不完整（args/up 链缺失），" +
                    "UP 维度不生效，其他推荐过滤继续"
            )
        }
        // AI 声明这一档两条输入各自独立：uri 读不到只丢标签那半边，param 读不到只丢已知 aid 那半边；
        // 两条都没有才是整档失效，必须报 partial，不能静默变成"从不命中"。
        if (removeAiDeclared && accessors.uri == null && accessors.param == null) {
            partialReason = if (partialReason == null) "missing-ai-declared-readers"
            else "$partialReason+missing-ai-declared-readers"
            environment.logError(
                "home_recommend_ai_declared_missing",
                "[BIL] 首页推荐 uri/param 读取适配不完整，AI 声明这一档不生效，其他推荐过滤继续"
            )
        }
        // 只填了标签名却读不到 tname：同理，名字那半边会静默失效。
        if (blockedTagNames.isNotEmpty() && accessors.tid?.tnameGetter == null) {
            partialReason = if (partialReason == null) "missing-tname-accessor"
            else "$partialReason+missing-tname-accessor"
            environment.logError(
                "home_recommend_tname_missing",
                "[BIL] 首页推荐标签名读取适配不完整（args/tname 链缺失），" +
                    "按名字那半边不生效，按 id 那半边继续"
            )
        }
        // 类型兜底仍能过滤，但广告载体不可读时不能把视频样式的推广覆盖报成完整。
        val adPayloadReadable = accessors.adInfo != null
        if (removeAds && !adPayloadReadable) {
            partialReason = if (partialReason == null) "missing-ad-info-accessor"
            else "$partialReason+missing-ad-info-accessor"
            environment.logError(
                "home_recommend_ad_info_missing",
                "[BIL] 首页广告载体读取适配不完整，类型与路由过滤继续"
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
                        var removedComics = 0
                        val advertisements = if (removeAds || removeCmV2) AdvertisementCounts() else null
                        val semanticVerdicts = semantic.verdicts(source) { item -> invokeString(accessors.title, item) }
                        val publishContext = if (publishTimeRule.isEnabled && accessors.publishTimeGate != null) {
                            PublishTimeContext(clock(), ruleZone())
                        } else {
                            null
                        }
                        publishProbeEnvironment = environment
                        val filtered = CopyOnFilter.list(source) { item ->
                            if (semanticVerdicts?.get(item) == SemanticVerdict.BLOCK) return@list true
                            val signals = signals(item, accessors, advertisements, publishContext)
                            probeTag(signals, environment)
                            val isBanner = removeBanner && isHomeBanner(signals)
                            if (isBanner) removedBanners += 1
                            val kind = if (removePgc || removeSpecialCards) HomeExtraCardPolicy.classify(
                                signals.cardType, signals.cardGoto, signals.goTo, signals.uri
                            ) else HomeExtraCardPolicy.Kind.UNKNOWN
                            val pgc = removePgc && kind == HomeExtraCardPolicy.Kind.PGC
                            val special = removeSpecialCards && kind == HomeExtraCardPolicy.Kind.SPECIAL
                            val comic = removeComic && HomeExtraCardPolicy.isComic(
                                signals.cardType, signals.cardGoto, signals.goTo, signals.uri
                            )
                            if (pgc) { removedPgc++; environment.reportRuntimeEvidence("home_recommend_pgc_removed", FeatureRuntimeStage.OBSERVED) }
                            if (special) { removedSpecial++; environment.reportRuntimeEvidence("home_recommend_special_cards_removed", FeatureRuntimeStage.OBSERVED) }
                            if (comic) removedComics += 1
                            pgc || special || comic || isBanner || shouldRemove(signals, advertisements)
                        }
                        if (filtered !== source) {
                            result = filtered
                            if (removedPgc > 0) environment.reportRuntimeEvidence("home_recommend_pgc_removed", FeatureRuntimeStage.APPLIED)
                            if (removedSpecial > 0) environment.reportRuntimeEvidence("home_recommend_special_cards_removed", FeatureRuntimeStage.APPLIED)
                            if (removedComics > 0) {
                                environment.reportRuntimeEvidence("home_recommend_comic_removed", FeatureRuntimeStage.OBSERVED)
                                environment.reportRuntimeEvidence("home_recommend_comic_removed", FeatureRuntimeStage.APPLIED, removedComics)
                            }
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
                        advertisements?.let { counts ->
                            if (removeAds && counts.reads > 0) {
                                environment.reportRuntimeEvidence("home_recommend_ads_removed", FeatureRuntimeStage.OBSERVED)
                            }
                            if (counts.unreadable > 0) {
                                environment.reportRuntimeEvidence("home_recommend_ads_removed", FeatureRuntimeStage.ERROR, counts.unreadable)
                                environment.logError(
                                    "home_recommend_ad_info_unreadable",
                                    "[BIL] 首页广告载体读取失败 ${counts.unreadable}/${counts.reads} 项，已保守放行未知卡片"
                                )
                            }
                            if (counts.ads > 0) {
                                environment.reportRuntimeEvidence("home_recommend_ads_removed", FeatureRuntimeStage.APPLIED, counts.ads)
                            }
                            if (counts.cmV2 > 0) {
                                environment.reportRuntimeEvidence("home_recommend_cm_v2_removed", FeatureRuntimeStage.OBSERVED)
                                environment.reportRuntimeEvidence("home_recommend_cm_v2_removed", FeatureRuntimeStage.APPLIED, counts.cmV2)
                            }
                            environment.logInfo(
                                "home_recommend_ad_summary",
                                "[BIL] 首页广告过滤计数: reads=${counts.reads},unreadable=${counts.unreadable}," +
                                    "ads=${counts.ads},cmV2=${counts.cmV2}"
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
        for (capability in capabilityIds) {
            val readable = when (capability) {
                "home_recommend_duration_filter" -> accessors.duration != null
                "home_recommend_play_count_filter" ->
                    (accessors.playCountGate ?: accessors.duration?.playerArgsGetter) != null
                "home_recommend_publish_time_filter" -> accessors.publishTimeGate != null
                "home_recommend_title_filter_enabled" -> accessors.title != null
                "home_recommend_ads_removed" -> true // required holderType is a valid advertisement-token source
                "home_recommend_cm_v2_removed" -> accessors.cardType != null // this predicate reads cardType only
                "home_banner_feed" -> true
                "home_recommend_large_removed" -> true // required holderType is resolved above
                "home_recommend_pgc_removed" -> extraTypesReadable || accessors.uri != null
                "home_recommend_special_cards_removed" -> extraTypesReadable
                "home_recommend_comic_removed" -> extraTypesReadable || accessors.uri != null
                "home_recommend_tid_block" -> accessors.tid != null
                // UP 屏蔽的真实前提是 args/up 读取链；落进 else 的 routeReadable 会在
                // "链缺失但路由字段可读"的宿主上把这一维度报成可用，与实际相反。
                "home_recommend_author_block" -> accessors.author != null
                AiDeclaredVideoPolicy.CAPABILITY_HOME -> accessors.uri != null || accessors.param != null
                CAPABILITY_SEMANTIC -> accessors.title != null
                // 其余类型都把必需的 holderType 交给同一分类器，独立开关也有类型兜底。
                else -> true
            }
            val incompleteAdPayload = capability == "home_recommend_ads_removed" && !adPayloadReadable
            environment.reportCapabilityCoverage(capability, readable, installed,
                adapted.responseItemGetters.size + if (incompleteAdPayload) 1 else 0)
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
                "playCount=${playCountRange.isEnabled}," +
                "publishTime=${publishTimeRule.isEnabled}," +
                // 只记条数不记内容；用来区分"名单没传到宿主"和"传到了但不命中"。
                "tagIds=${blockedTids.size},tagNames=${blockedTagNames.size}," +
                "tnameReadable=${accessors.tid?.tnameGetter != null}"
        )
        return FeatureInstallResult.Installed(installed, complete = partialReason == null && installed == adapted.responseItemGetters.size)
    }

    private val publishTimeProbe = PublishTimeParseProbe()

    /** 抽样回报要写日志；安装后由 hook 回填，`signals` 本身不持有环境。 */
    @Volatile
    private var publishProbeEnvironment: HookEnvironment? = null

    private fun recordPublishTimeProbe(parsed: Boolean) {
        val sample = publishTimeProbe.record(parsed) ?: return
        val environment = publishProbeEnvironment ?: return
        if (sample.parsed == 0) {
            environment.logError(
                "home_recommend_publish_time_unparsed",
                "[BIL] 首页前 ${sample.seen} 张投稿卡片的描述里都没有可识别的发布时间，" +
                    "发布时间过滤在此宿主上可能不会命中"
            )
        } else {
            environment.logInfo(
                "home_recommend_publish_time_sample",
                "[BIL] 首页发布时间抽样：${sample.parsed}/${sample.seen} 张卡片读出了发布时间"
            )
        }
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

    private fun shouldRemove(signals: Signals, advertisements: AdvertisementCounts?): Boolean {
        if (!itemRemovalEnabled) return false
        // 提取链仍按启动期完整依赖保留；同一卡片的动态判据只读取一次原子快照。
        val live = liveRules?.invoke()
        val hostSignals = signals.toHostSignals()
        val kinds = if (semanticClassificationEnabled) {
            HostContentSemanticClassifier.classify(hostSignals)
        } else {
            emptySet()
        }
        val cmV2 = removeCmV2 && HostContentSemanticClassifier.isCmV2(hostSignals)
        val advertisement = removeAds && HostContentKind.ADVERTISEMENT in kinds
        if (cmV2 || advertisement) {
            if (cmV2) advertisements?.let { it.cmV2 += 1 }
            if (advertisement) advertisements?.let { it.ads += 1 }
            return true
        }
        return (removePictures && HostContentKind.PICTURE in kinds) ||
            (removeGamePromotions && HostContentKind.GAME in kinds) ||
            RuleSetCodec.matches(live?.titles ?: titleKeywords, signals.title) ||
            (removeLive && HostContentKind.LIVE in kinds) ||
            (removeCourses && HostContentKind.COURSE in kinds) ||
            (removeVertical && HostContentKind.VERTICAL in kinds) ||
            (removeLarge && HostContentKind.LARGE in kinds) ||
            (live?.duration ?: durationRange).shouldRemove(signals.durationSeconds) ||
            (live?.plays ?: playCountRange).shouldRemove(signals.playCount) ||
            signals.publishContext?.let { context ->
                publishTimeRule.shouldRemove(signals.publishTime, context.now, context.zone)
            } == true ||
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
            AuthorPickSession.matches(signals.upName, signals.upId) ||
            signals.aiDeclared
    }

    private fun signals(
        item: Any,
        accessors: Accessors,
        advertisements: AdvertisementCounts?,
        publishContext: PublishTimeContext?
    ): Signals {
        // 播放量与发布时间共用同一道 PlayerArgs 闸门；两档都开时每张卡只走一次。
        val playCountGate = accessors.playCountGate ?: accessors.duration?.playerArgsGetter
        val publishTimeReadable = publishContext != null && accessors.publishTimeGate != null
        val coverGateNeeded = (playCountRange.isEnabled && playCountGate != null) || publishTimeReadable
        val avVod = coverGateNeeded &&
            (playCountGate ?: accessors.publishTimeGate)
                ?.let { gate -> VideoPlayCountReader.isHomeAvVod(item, gate) } == true
        val needsRoute = removeAds || removePictures || removeGamePromotions || removeLive ||
            removeCourses || removeVertical || removeLarge || removePgc || removeSpecialCards || removeComic
        val needsClassification = removeAds || removeCmV2 || needsRoute
        val tidAccessor = accessors.tid
        val tidArgs = tidAccessor?.let { accessor ->
            invokeCompatible(accessor.argsGetter, item)
        }
        val authorAccessor = accessors.author
        val authorArgs = authorAccessor?.let { accessor ->
            // 9.11.0 的 tid/up 链共享同一个 ArgsData getter；只有 Method 签名相同
            // 才复用，避免把跨版本可能不同的两个容器误当成同一个对象。
            if (tidAccessor != null && tidAccessor.argsGetter == accessor.argsGetter) {
                tidArgs
            } else {
                invokeCompatible(accessor.argsGetter, item)
            }
        }
        return Signals(
            holderType = if (needsClassification || removeBanner) {
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
            aiDeclared = removeAiDeclared && aiDeclared(item, accessors),
            title = if (titleKeywords.isNotEmpty() || removeGamePromotions) {
                invokeString(accessors.title, item)
            } else {
                null
            },
            subtitle = if (removeGamePromotions) invokeString(accessors.subtitle, item) else null,
            desc = if (removeGamePromotions) invokeString(accessors.desc, item) else null,
            hasAdInfo = (removeAds || removeGamePromotions) &&
                readAdInfo(accessors.adInfo, item, advertisements.takeIf { removeAds }),
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
            playCount = if (playCountRange.isEnabled && playCountGate != null && avVod) {
                VideoPlayCountReader.coverPlayCount(item)
            } else {
                null
            },
            publishTime = if (publishTimeReadable && avVod) {
                VideoPublishTimeReader.fromDesc(item, publishContext.now).also { estimate ->
                    recordPublishTimeProbe(estimate != null)
                }
            } else {
                null
            },
            publishContext = publishContext,
            // 名单为空且没开面板劫持时 accessors.tid/author 是 null，这里不产生反射调用。
            // 同一张卡的 tid/tname/rid 与 upName/upId 各自共用一次 getArgs()；若两条
            // 适配链确实指向同一个 Method，再额外跨维度复用一次容器读取。
            tid = tidAccessor?.let { accessor ->
                tidArgs?.let { args ->
                    (invokeCompatible(accessor.tidGetter, args) as? Number)?.toLong()
                }
            },
            tname = tidAccessor?.tnameGetter?.let { getter ->
                tidArgs?.let { args -> invokeCompatible(getter, args) as? String }
            },
            // 只喂探针。探针记满就静默，之后这里的反射也随之停掉。
            rid = tidAccessor?.ridGetter?.takeIf { probedTags.size < MAX_PROBED_TAGS }
                ?.let { getter ->
                    tidArgs?.let { args ->
                        (invokeCompatible(getter, args) as? Number)?.toLong()
                    }
                },
            // UP 维度关着时 authorAccessor 是 null，这里不产生任何反射调用。
            upName = authorAccessor?.let { accessor ->
                authorArgs?.let { args -> invokeCompatible(accessor.upNameGetter, args) as? String }
            },
            // mid 以十进制串参与比对，这样用户填名字或填 mid 都能命中。
            upId = authorAccessor?.upIdGetter?.let { getter ->
                authorArgs?.let { args ->
                    (invokeCompatible(getter, args) as? Number)?.takeIf { it.toLong() > 0L }
                        ?.toLong()?.toString()
                }
            }
        )
    }

    /**
     * 先比已知 aid（一次 `toLongOrNull` + 集合查找），再看 uri 标签（原串预判不分配）。
     * 标签命中的 aid 也记进 [AiDeclaredVideoRegistry]，让相关推荐与补位候选同样避开它。
     */
    private fun aiDeclared(item: Any, accessors: Accessors): Boolean {
        val aid = AiDeclaredVideoPolicy.aidFromParam(invokeString(accessors.param, item))
        if (AiDeclaredVideoRegistry.contains(aid)) return true
        if (!AiDeclaredVideoPolicy.feedUriDeclaresAigc(invokeString(accessors.uri, item))) return false
        AiDeclaredVideoRegistry.add(aid)
        return true
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

    private fun resolvePlayCountGate(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): Method? {
        if (!playCountRange.isEnabled) return null
        val getterPoint = points.playerArgsGetter ?: return null
        return resolve(environment, "player_args_play_count", getterPoint)
    }

    private fun resolvePublishTimeGate(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): Method? {
        if (!publishTimeRule.isEnabled) return null
        val getterPoint = points.playerArgsGetter ?: return null
        return resolve(environment, "player_args_publish_time", getterPoint)
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

    /** 每个响应只保存计数；空广告字段和读取失败分别处理，不保存卡片或异常内容。 */
    private class AdvertisementCounts {
        var reads = 0
        var unreadable = 0
        var ads = 0
        var cmV2 = 0
    }

    private fun readAdInfo(method: Method?, target: Any, counts: AdvertisementCounts?): Boolean {
        counts?.let { it.reads += 1 }
        if (method == null || !method.declaringClass.isInstance(target)) {
            counts?.let { it.unreadable += 1 }
            return false
        }
        return try {
            method.invoke(target) != null
        } catch (_: Throwable) {
            counts?.let { it.unreadable += 1 }
            false
        }
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
        val playCount: Long? = null,
        /** 描述文案推出的发布时间区间；读不到或无法解析为 null，null 一律放行。 */
        val publishTime: PublishTimeEstimate? = null,
        val publishContext: PublishTimeContext? = null,
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
        val rid: Long? = null,
        /** AI 生成声明一档的结论；开关关着时恒为 false，不产生任何读取。 */
        val aiDeclared: Boolean = false
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
        val playCountGate: Method?,
        val tid: TidAccessor?,
        val author: AuthorAccessor?,
        val publishTimeGate: Method?
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
        const val CAPABILITY_SEMANTIC = "home_recommend_semantic_filter_enabled"
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
