package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P

/**
 * 「功能 → 定位家族 → 共享依赖 → 最晚安装时机」映射，是同步定位计划的唯一依据。
 *
 * 一个家族只有在**它的每个读取者**都由同一组开关控制、且这些开关都关闭时才会被裁剪；
 * 只要有一个读取者是常驻/扫描面/运行期可翻转的，或者家族没有登记，它就一直常开（失效安全）。
 *
 * 读取者 = `HookEntry.installAuthorizedHooks` 里把 `hostAdaptResult?.<字段>` 交给某个安装器的位置。
 * 开关信号 [Signal] 取的是安装器**构造参数里出现的全部配置键**，并且故意取超集：任何一个键处于
 * 非默认关闭状态就算「有人要」，宁可多定位也不会少定位。契约测试
 * （`AdaptPlanCatalogContractTest`）对照 HookEntry 源码检查这些键没有漂移，行为测试
 * （`AdaptPlanCatalogTest`）对照真实安装器检查「开关全关 ⇒ 安装器不读定位点」。
 */
internal object AdaptPlanCatalog {
    /** 读取者最晚在什么时候消费定位结果。 */
    enum class Latest {
        /** `installAuthorizedHooks` 构造安装器时即取走（Application.attach 同步阶段）。 */
        INSTALL_CHAIN,

        /** 无条件常驻的扫描面（设置页勾选列表依赖其快照），不受任何开关控制。 */
        RESIDENT_SCAN,

        /** 配置在 attach 之后还可能被同步翻转的运行期开关；不能按安装时的值裁剪。 */
        RUNTIME_FLAG
    }

    sealed interface Signal {
        val keys: Set<String>
        fun active(config: HookConfigSource): Boolean
    }

    /** 布尔开关；[defaultOn] 与 HookEntry 读取时给的默认值一致。 */
    class Flag(val key: String, val defaultOn: Boolean = false) : Signal {
        override val keys = setOf(key)
        override fun active(config: HookConfigSource) = config.getBoolean(key, defaultOn)
    }

    /** 规则/名单类字符串：非空白即视为配置了。 */
    class Text(val key: String) : Signal {
        override val keys = setOf(key)
        override fun active(config: HookConfigSource) = config.getString(key, "").isNotBlank()
    }

    /** 数值类：不等于「关闭值」即视为配置了。 */
    class NonZero(val key: String, val off: Int = 0) : Signal {
        override val keys = setOf(key)
        override fun active(config: HookConfigSource) = config.getInt(key, off) != off
    }

    sealed interface Gate {
        /** 无条件读取：入口、扫描面、常驻能力。 */
        data object Always : Gate

        /** 任一信号有效即读取（取超集）。 */
        class AnyOf(val signals: List<Signal>) : Gate {
            init { require(signals.isNotEmpty()) { "AnyOf 至少要有一个信号；无条件读取请用 Always" } }
        }
    }

    class Reader(
        val installer: String,
        val gate: Gate,
        val latest: Latest = Latest.INSTALL_CHAIN,
        val note: String = "",
        /** 安装器构造参数里出现、但只在某个已列出的开关开启后才起作用的从属键（关键词、规则、阈值……）。 */
        val refines: Set<String> = emptySet()
    ) {
        fun active(config: HookConfigSource): Boolean = when (gate) {
            Gate.Always -> true
            is Gate.AnyOf -> gate.signals.any { it.active(config) }
        }

        val keys: Set<String> get() = (gate as? Gate.AnyOf)?.signals?.flatMapTo(linkedSetOf()) { it.keys }.orEmpty()
    }

    class Spec(
        val family: PointFamily,
        val readers: List<Reader>,
        val dependsOn: Set<PointFamily> = emptySet(),
        val evidence: String
    ) {
        /** 常开：任一读取者无条件读取。 */
        val always: Boolean get() = readers.any { it.gate == Gate.Always }
    }

    private fun gated(installer: String, vararg signals: Signal, note: String = "", refines: Set<String> = emptySet()) =
        Reader(installer, Gate.AnyOf(signals.toList()), note = note, refines = refines)

    private fun resident(installer: String, note: String) = Reader(installer, Gate.Always, Latest.RESIDENT_SCAN, note)

    private fun flags(vararg keys: String) = keys.map { Flag(it) }.toTypedArray()

    /** 首页推荐与相关推荐共用的区间/规则键：时长、播放量、发布时间。 */
    private val sharedRangeSignals = arrayOf<Signal>(
        NonZero(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS), NonZero(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS),
        NonZero(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT), NonZero(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT),
        Text(P.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
    )

    val specs: List<Spec> = listOf(
        Spec(
            PointFamily.COMMENT_LOW,
            listOf(
                Reader("free_copy.comment", Gate.Always, Latest.RUNTIME_FLAG,
                    "评论自由复制默认开启，runtimeCommentFreeCopyEnabled 在 attach 之后还会随权威配置同步；低版本绑定点必须随时可用"),
                gated("CommentTopologyFeatureInstaller.lowBindPoint", Flag(P.REPLY_TOPOLOGY_ENABLED))
            ),
            evidence = "HookEntry 自由复制安装段 adaptResult.commentLow；CommentTopologyFeatureInstaller(lowBindPoint = hostAdaptResult?.commentLow)"
        ),
        Spec(
            PointFamily.COMMENT_HIGH,
            listOf(
                Reader("free_copy.comment", Gate.Always, Latest.RUNTIME_FLAG, "同 COMMENT_LOW；usableCache 还要求 high 点存在"),
                gated("CommentTopologyFeatureInstaller.highBindPoint", Flag(P.REPLY_TOPOLOGY_ENABLED))
            ),
            evidence = "HookEntry 自由复制安装段 adaptResult.commentHigh；CommentTopologyFeatureInstaller(highBindPoint = hostAdaptResult?.commentHigh)"
        ),
        Spec(
            PointFamily.MINE_ENTRY,
            listOf(resident("MineComponentFilterFeatureInstaller", "没有安装器直接读 mineEntry；它是 MINE_COMPONENTS 的定位依赖，保持常开")),
            evidence = "locateMineComponents(loader, mine) 的入参；HookEntry 不直接引用 hostAdaptResult.mineEntry"
        ),
        Spec(
            PointFamily.PAUSE,
            listOf(gated("PausedAdFeatureInstaller", Flag(HookEntry.PREF_ENABLED, defaultOn = true))),
            evidence = "PausedAdFeatureInstaller(enabled = prefs.getBoolean(PREF_ENABLED, true), points = hostAdaptResult?.pause)"
        ),
        Spec(
            PointFamily.BANNER,
            listOf(gated("HomeBannerFeatureInstaller", Flag(HookEntry.PREF_BANNER_ENABLED, defaultOn = true))),
            evidence = "HomeBannerFeatureInstaller(enabled = prefs.getBoolean(PREF_BANNER_ENABLED, true), point = hostAdaptResult?.banner)"
        ),
        Spec(
            PointFamily.HOME_TOP_BAR,
            listOf(
                Reader("HomeTabFilterFeatureInstaller.centering", Gate.Always, Latest.RESIDENT_SCAN,
                    "HomeTopTabCenteringHooks 无任何开关地读取 homeTopBar.baseOnViewCreated（材质/光效开关不控制标签居中）"),
                gated("HomeTopBarFeatureInstaller", Flag(P.HIDE_HOME_GAME_MENU), Flag(P.HIDE_HOME_SEARCH_DEFAULT_WORD)),
                resident("HostTopBarFxFeatureInstaller", "启用条件（液态玻璃/光效/背景/材质）未逐项核对；家族已被标签居中常开，按未核对处理为无条件读取")
            ),
            evidence = "HomeTopTabCenteringHooks(hostAdaptResult?.homeTopBar, …)；HomeTopBarFeatureInstaller / HostTopBarFxFeatureInstaller(points = hostAdaptResult?.homeTopBar)"
        ),
        Spec(
            PointFamily.MINE_VIP,
            listOf(gated("MineVipFeatureInstaller", Flag(P.HIDE_MINE_VIP), refines = setOf(P.KEEP_MINE_VIP_SPACE))),
            evidence = "MineVipFeatureInstaller(enabled = HIDE_MINE_VIP, point = hostAdaptResult?.mineVip)"
        ),
        Spec(
            PointFamily.BLOCK_UPDATE,
            listOf(gated("BlockUpdateFeatureInstaller", Flag(P.BLOCK_APP_UPDATE),
                note = "与 AdaptAssistGate.Family.BLOCK_UPDATE 的 blockUpdateEnabled 同一开关")),
            evidence = "BlockUpdateFeatureInstaller(enabled = BLOCK_APP_UPDATE, point = hostAdaptResult?.blockUpdate)"
        ),
        Spec(
            PointFamily.DYNAMIC_TABS,
            listOf(gated("DynamicTabsFeatureInstaller", *flags(P.HIDE_DYNAMIC_CITY_TAB, P.HIDE_DYNAMIC_SCHOOL_TAB, P.PREFER_DYNAMIC_VIDEO_TAB))),
            evidence = "DynamicTabsFeatureInstaller(hideCity/hideSchool/preferVideo, point = hostAdaptResult?.dynamicTabs)"
        ),
        Spec(
            PointFamily.FULL_NUMBERS,
            listOf(gated("FullNumberFeatureInstaller", Flag(P.SHOW_FULL_NUMBERS))),
            evidence = "FullNumberFeatureInstaller(enabled = SHOW_FULL_NUMBERS, points = hostAdaptResult?.fullNumbers)"
        ),
        Spec(
            PointFamily.PLAYER_PORTRAIT,
            listOf(gated("PlayerPortraitFeatureInstaller", Flag(P.HIDE_PLAYER_PORTRAIT_CONTROL))),
            evidence = "PlayerPortraitFeatureInstaller(enabled = HIDE_PLAYER_PORTRAIT_CONTROL, points = hostAdaptResult?.playerPortrait)"
        ),
        Spec(
            PointFamily.PLAYER_INTERACTIVE_OVERLAYS,
            listOf(gated("PlayerInteractiveOverlayFeatureInstaller", Flag(P.HIDE_PLAYER_INTERACTIVE_OVERLAYS))),
            evidence = "PlayerInteractiveOverlayFeatureInstaller(enabled = HIDE_PLAYER_INTERACTIVE_OVERLAYS, points = hostAdaptResult?.playerInteractiveOverlays)"
        ),
        Spec(
            PointFamily.PGC_AUTO_ACTIVITY_POPUP,
            listOf(gated("PgcAutoActivityPopupFeatureInstaller", Flag(P.HIDE_PGC_AUTO_ACTIVITY_POPUP))),
            evidence = "PgcAutoActivityPopupFeatureInstaller(enabled = HIDE_PGC_AUTO_ACTIVITY_POPUP, points = hostAdaptResult?.pgcAutoActivityPopup)"
        ),
        Spec(
            PointFamily.PLAYER_STATUS_BAR,
            listOf(gated("PlayerStatusBarFeatureInstaller", Flag(P.TRANSPARENT_PLAYER_STATUS_BAR))),
            evidence = "PlayerStatusBarFeatureInstaller(enabled = TRANSPARENT_PLAYER_STATUS_BAR, points = hostAdaptResult?.playerStatusBar)"
        ),
        Spec(
            PointFamily.HOME_RECOMMEND_FEED,
            listOf(
                gated("HomeVerticalDetailFeatureInstaller", Flag(P.HOME_VERTICAL_OPEN_DETAIL)),
                gated(
                    "HomeRecommendPurifyFeatureInstaller",
                    *flags(
                        P.REMOVE_HOME_RECOMMEND_ADS, P.REMOVE_HOME_RECOMMEND_CM_V2, P.REMOVE_HOME_RECOMMEND_PICTURES,
                        P.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, P.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                        P.REMOVE_HOME_RECOMMEND_LIVE, P.REMOVE_HOME_RECOMMEND_PGC, P.REMOVE_HOME_RECOMMEND_COMIC,
                        P.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS, P.REMOVE_HOME_RECOMMEND_COURSES,
                        P.REMOVE_HOME_RECOMMEND_VERTICAL, P.REMOVE_HOME_RECOMMEND_LARGE,
                        P.HOME_RECOMMEND_SECTION_PICK_ENABLED, P.BLOCK_AI_DECLARED_VIDEOS,
                        P.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE, P.VIDEO_SEMANTIC_FILTER_ENABLED
                    ),
                    Flag(HookEntry.PREF_BANNER_ENABLED, defaultOn = true),
                    Text(P.HOME_RECOMMEND_BLOCKED_TIDS), Text(P.HOME_RECOMMEND_BLOCKED_AUTHORS),
                    *sharedRangeSignals,
                    note = "removeBanner 读 PREF_BANNER_ENABLED（默认开），所以默认配置下首页推荐净化也在读这个家族",
                    refines = setOf(P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, P.VIDEO_SEMANTIC_FILTER_RULES)
                ),
                gated("SectionPickFeatureInstaller", Flag(P.HOME_RECOMMEND_SECTION_PICK_ENABLED),
                    note = "NativeFeedbackPanel.install(points, relatedPoints) 同时读 HOME_RECOMMEND_FEED 与 VIDEO_RELATE")
            ),
            evidence = "HomeVerticalDetail / HomeRecommendPurify / SectionPick 三个安装器共用 hostAdaptResult?.homeRecommendFeed"
        ),
        Spec(
            PointFamily.VIDEO_RELATE,
            listOf(
                gated(
                    "VideoRelateFilterFeatureInstaller",
                    *flags(
                        P.REMOVE_RELATE_COMMERCIAL, P.REMOVE_RELATE_GAME, P.REMOVE_RELATE_LIVE, P.REMOVE_RELATE_COURSE,
                        P.REMOVE_RELATE_SPECIAL, P.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                        P.VIDEO_RELATE_STRONG_MODE_ENABLED, P.VIDEO_RELATE_REASON_FILTER_ENABLED,
                        P.HOME_RECOMMEND_SECTION_PICK_ENABLED, P.VIDEO_SEMANTIC_FILTER_ENABLED
                    ),
                    Text(P.VIDEO_RELATE_BLOCKED_AUTHORS), Text(P.VIDEO_RELATE_BLOCKED_TAGS),
                    *sharedRangeSignals,
                    refines = setOf(
                        P.HOME_RECOMMEND_BLOCKED_TIDS, P.VIDEO_RELATE_REASON_FILTER_KEYWORDS, P.VIDEO_SEMANTIC_FILTER_RULES
                    )
                ),
                gated("SectionPickFeatureInstaller", Flag(P.HOME_RECOMMEND_SECTION_PICK_ENABLED),
                    note = "relatedPoints = hostAdaptResult?.videoRelate")
            ),
            evidence = "VideoRelateFilterFeatureInstaller(points = hostAdaptResult?.videoRelate)；SectionPickFeatureInstaller(relatedPoints = …)"
        ),
        Spec(
            PointFamily.HOME_TABS,
            listOf(resident("HomeTabFilterFeatureInstaller", "空配置也要装：不扫描就产不出设置页的勾选列表")),
            evidence = "HomeTabFilterFeatureInstaller(points = hostAdaptResult?.homeTabs) 无启用开关"
        ),
        Spec(
            PointFamily.HOME_COMPONENTS,
            listOf(resident("HomeComponentFilterFeatureInstaller", "空配置也要装：不扫描就产不出设置页的勾选列表")),
            evidence = "HomeComponentFilterFeatureInstaller(points = hostAdaptResult?.homeComponents) 无启用开关"
        ),
        Spec(
            PointFamily.MINE_COMPONENTS,
            listOf(resident("MineComponentFilterFeatureInstaller", "install 无启用开关，scan / filter+scan 两种模式")),
            dependsOn = setOf(PointFamily.MINE_ENTRY),
            evidence = "MineComponentFilterFeatureInstaller(points = hostAdaptResult?.mineComponents)；locateMineComponents(loader, mine)"
        ),
        Spec(
            PointFamily.MINE_ACCOUNT_MINE,
            listOf(resident("MineComponentFilterFeatureInstaller", "accountMinePoints 同上无启用开关")),
            evidence = "MineComponentFilterFeatureInstaller(accountMinePoints = hostAdaptResult?.mineAccountMine)"
        ),
        Spec(
            PointFamily.STORY_FEED,
            listOf(gated("StoryPurifyFeatureInstaller", *flags(
                P.REMOVE_STORY_ADS, P.REMOVE_STORY_LIVE, P.REMOVE_STORY_GAMES, P.REMOVE_STORY_BANGUMI,
                P.REMOVE_STORY_COURSES, P.REMOVE_STORY_SHORT_DRAMA, P.REMOVE_STORY_SHOPPING, P.REMOVE_STORY_MOVIES,
                P.REMOVE_STORY_DOCUMENTARIES, P.REMOVE_STORY_TV, P.REMOVE_STORY_VARIETY, P.REMOVE_STORY_MUSIC
            ))),
            evidence = "StoryPurifyFeatureInstaller(removeAds…removeMusic, points = hostAdaptResult?.storyFeed)"
        ),
        Spec(
            PointFamily.BOTTOM_BAR,
            listOf(
                resident("BottomBarFeatureInstaller", "空配置也要装：不扫描就产不出设置页的勾选列表"),
                resident("HostBottomBarFxFeatureInstaller", "启用条件（液态玻璃/光效/紧凑/仅图标）未逐项核对；家族已被底栏扫描面常开，按未核对处理为无条件读取")
            ),
            evidence = "BottomBarFeatureInstaller / HostBottomBarFxFeatureInstaller(points = hostAdaptResult?.bottomBar)"
        ),
        Spec(
            PointFamily.PLAYER_QUALITY,
            listOf(gated("PlayerQualityFeatureInstaller", NonZero(P.PLAYER_DEFAULT_QUALITY_QN),
                note = "安装器按 PlayerQualityConfig.normalize(qn) 判定；原始值非 0 取超集，与 AdaptAssistGate.Family.PLAYER_QUALITY 同一开关")),
            evidence = "PlayerQualityFeatureInstaller(qualityQn, points = hostAdaptResult?.playerQuality, pointsProvider = qualityPointsRef)；capabilitySignals 只进入协议指纹与诊断，没有读取者"
        ),
        Spec(
            PointFamily.TEENAGERS_MODE,
            listOf(gated("TeenagersModeFeatureInstaller", Flag(P.BLOCK_TEENAGERS_MODE_PROMPT))),
            evidence = "TeenagersModeFeatureInstaller(enabled = BLOCK_TEENAGERS_MODE_PROMPT, points = hostAdaptResult?.teenagersMode)"
        ),
        Spec(
            PointFamily.COMMENT_PURIFY,
            listOf(gated("CommentPurifyFeatureInstaller", *flags(
                P.REMOVE_COMMENT_SEARCH_LINKS, P.REMOVE_COMMENT_EMPTY_GUIDE, P.REMOVE_COMMENT_VOTE_WIDGETS,
                P.REMOVE_COMMENT_FOLLOW_BUTTONS, P.REMOVE_COMMENT_QOE, P.REMOVE_COMMENT_OPERATIONS,
                P.BLOCK_COMMENT_QUICK_REPLY
            ))),
            evidence = "CommentPurifyFeatureInstaller(removeSearchLinks…blockQuickReply, points = hostAdaptResult?.commentPurify)"
        ),
        Spec(
            PointFamily.COMMENT_FILTER,
            listOf(gated("CommentFilterFeatureInstaller", *flags(
                P.COMMENT_KEYWORD_FILTER_ENABLED, P.COMMENT_MIN_LEVEL_FILTER_ENABLED, P.REMOVE_AT_ONLY_COMMENTS,
                P.COMMENT_USER_FILTER_ENABLED, P.COMMENT_SEMANTIC_FILTER_ENABLED
            ), refines = setOf(
                P.COMMENT_FILTER_KEYWORDS, P.COMMENT_MIN_LEVEL, P.COMMENT_USER_FILTER_RULES, P.COMMENT_SEMANTIC_FILTER_RULES
            ))),
            evidence = "CommentFilterFeatureInstaller(keyword/level/at-only/user/semantic, points = hostAdaptResult?.commentFilter)；评论净化与回复脉络各读自己的点，不读这个家族"
        ),
        Spec(
            PointFamily.COMMENT_TOPOLOGY,
            listOf(gated("CommentTopologyFeatureInstaller", Flag(P.REPLY_TOPOLOGY_ENABLED),
                note = "与 AdaptAssistGate.Family.COMMENT_TOPOLOGY 的 commentTopologyEnabled 同一开关")),
            evidence = "CommentTopologyFeatureInstaller(enabled = REPLY_TOPOLOGY_ENABLED, points = hostAdaptResult?.commentTopology)"
        ),
        Spec(
            PointFamily.COMMENT_SECTION,
            listOf(gated("CommentSectionFeatureInstaller", Flag(P.HIDE_COMMENT_SECTION))),
            evidence = "CommentSectionFeatureInstaller(enabled = HIDE_COMMENT_SECTION, points = hostAdaptResult?.commentSection)"
        ),
        Spec(
            PointFamily.SPLASH_ADS,
            listOf(gated("SplashAdFeatureInstaller", Flag(P.PURIFY_SPLASH_ADS))),
            evidence = "SplashAdFeatureInstaller(enabled = PURIFY_SPLASH_ADS, points = hostAdaptResult?.splashAds)"
        )
    )

    private val byFamily: Map<PointFamily, Spec> = specs.associateBy(Spec::family)

    fun spec(family: PointFamily): Spec? = byFamily[family]

    /** 永远不裁剪的家族：任一读取者无条件读取，或者根本没有登记（失效安全）。 */
    val alwaysFamilies: Set<PointFamily> = PointFamily.entries.filterTo(linkedSetOf()) { family ->
        byFamily[family]?.always != false
    }.let { direct -> closure(direct) }

    /** 把定位依赖补进来。[specOf] 可注入，用来验证「没有登记的家族」这条失效安全路径。 */
    internal fun closure(
        seed: Set<PointFamily>,
        specOf: (PointFamily) -> Spec? = ::spec
    ): Set<PointFamily> {
        val result = linkedSetOf<PointFamily>()
        val pending = ArrayDeque(seed)
        while (pending.isNotEmpty()) {
            val family = pending.removeFirst()
            if (!result.add(family)) continue
            specOf(family)?.dependsOn?.forEach(pending::add)
        }
        return result
    }
}

/** 从配置快照生成计划，并判断缓存覆盖范围是否满足计划。 */
internal object AdaptPlanner {
    fun build(
        config: HookConfigSource,
        specOf: (PointFamily) -> AdaptPlanCatalog.Spec? = AdaptPlanCatalog::spec
    ): AdaptPlan {
        val requesters = linkedMapOf<PointFamily, MutableList<String>>()
        for (family in PointFamily.entries) {
            val spec = specOf(family)
            if (spec == null) {
                requesters.getOrPut(family, ::mutableListOf) += "unmapped(fail-open)"
                continue
            }
            spec.readers.filter { it.active(config) }.forEach { requesters.getOrPut(family, ::mutableListOf) += it.installer }
        }
        val required = AdaptPlanCatalog.closure(requesters.keys, specOf)
        // 依赖补进来的家族也要有请求者记录，便于诊断。
        required.filter { it !in requesters }.forEach { requesters[it] = mutableListOf("dependency") }
        val always = PointFamily.entries.filterTo(linkedSetOf()) { specOf(it)?.always != false }
        return AdaptPlan(config.revision, required, AdaptPlanCatalog.closure(always, specOf), requesters)
    }

    /**
     * 计划要求、但覆盖范围里从未尝试过的家族。覆盖范围未知（旧缓存没有 `cov`）视为全部未尝试。
     *
     * 只看 [Attempt.NOT_REQUESTED]：[Attempt.RETRYABLE] 与 [Attempt.CONCLUSIVE_MISSING] 都不触发快路径补扫，
     * 否则一个持久的环境问题会让每次冷启动都重新适配；它们只会在本来就要适配的那一轮里被重试。
     */
    fun missing(coverage: AdaptCoverage?, plan: AdaptPlan): Set<PointFamily> =
        plan.required.filterTo(linkedSetOf()) { (coverage?.attempt(it) ?: Attempt.NOT_REQUESTED) == Attempt.NOT_REQUESTED }
}
