package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AssistHistory
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentComposeMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.CommentLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MineAndHomeUiLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/**
 * 适配刷新与 DEX 查询上下文配置开关集合。
 */
internal data class DexFeatureFlags(
    val kotlinDefaultWordsEnabled: Boolean = false,
    val sponsorBlockEnabled: Boolean = false,
    val commentClassicStyleEnabled: Boolean = false,
    val playerPopupPromotionEnabled: Boolean = false,
    val brandSplashCustomEnabled: Boolean = false,
    val followFeedStyleEnabled: Boolean = false,
    val playerQualityEnabled: Boolean = false,
    val playerSpeedMenuEnabled: Boolean = false,
    val playerSpeedSessionEnabled: Boolean = false,
    val playerSpeedCalculatorEnabled: Boolean = false,
    val weChatShareEnabled: Boolean = false,
    val searchHomeRecommendEnabled: Boolean = false,
    val playerEndPageRecommendEnabled: Boolean = false,
    val homeChromeEnabled: Boolean = false,
    val homeChromeIconOnly: Boolean = false,
    val homeTopTabCenteringEnabled: Boolean = false,
    val systemEnvironmentEnabled: Boolean = false,
    val blockUpdateEnabled: Boolean = false,
    val commentTopologyEnabled: Boolean = false,
    val kmpGrpcWireEnabled: Boolean = false,
    val cloudKeyFreezeEnabled: Boolean = false,
    val incognitoPrivacyEnabled: Boolean = false,
    val detailSpecialTagEnabled: Boolean = false,
    val commentReplyButtonEnabled: Boolean = false,
    val guestBrowsingEnabled: Boolean = false,
    val homeRecommendEarlyLoadEnabled: Boolean = false,
    val commentQuickReplyEnabled: Boolean = false,
    val pausedAdEnabled: Boolean = false,
    val brandSplashSkipEnabled: Boolean = false,
    val aiDeclaredVideoEnabled: Boolean = false,
    val followUpRecommendEnabled: Boolean = false,
    val detailRelateGameEnabled: Boolean = false,
    val storyDanmakuToggleHidden: Boolean = false,
    val playerSpeedLongPressEnabled: Boolean = false,
    val splashAutoNightEnabled: Boolean = false,
    val homeTabsRequested: Boolean = true
)

/**
 * 传给 [DexFeatureAdapter.needsRefresh] 的不可变检查上下文。
 */
internal data class DexFeatureRefreshContext(
    val loader: ClassLoader,
    val hostVersionCode: Int,
    val flags: DexFeatureFlags
)

/**
 * 单个 DEX 辅助定位特性描述符与合并/刷新统一契约。
 *
 * 将散落在 `requiresFeatureRefresh`、`adapt` 和 `mergeRuntimeWithCached` 中高度重复的
 * 「是否尝试过（按 [AdaptResult.diagnosticsById] O(1) 查询）→ 是否需要补查 → 定位与诊断 → 缓存合并」
 * 样板统一到注册表 [DexFeatureRegistry]，同时逐项严格保持既有诊断状态与文案口径。
 */
internal interface DexFeatureAdapter<T> {
    val featureId: String
    val diagnosticIds: Set<String>
    val retainMissingOnQuickLocate: Boolean get() = true

    fun readValue(result: AdaptResult): T
    fun mergeValue(live: T, cached: T): T
    fun needsRefresh(context: DexFeatureRefreshContext, value: AdaptResult): Boolean
}

/**
 * 单次标准 DEX 特性定位结果与诊断对。
 */
internal data class DexFeatureOutcome<T>(
    val value: T,
    val diagnostic: AdaptDiagnostic
)

/**
 * 以单条诊断 ID 为主键的通用 DEX 特性适配器模板。
 */
internal class StandardDexFeatureAdapter<T>(
    override val featureId: String,
    val diagnosticId: String,
    override val retainMissingOnQuickLocate: Boolean = true,
    private val reader: (AdaptResult) -> T,
    private val merger: (live: T, cached: T) -> T = { live, cached -> live ?: cached },
    private val refreshPredicate: (DexFeatureRefreshContext, AdaptResult, Boolean) -> Boolean,
    private val attemptedPredicate: (AdaptDiagnostic) -> Boolean = AdaptAssistGate::isAttemptedInCache
) : DexFeatureAdapter<T> {

    override val diagnosticIds: Set<String> = setOf(diagnosticId)

    override fun readValue(result: AdaptResult): T = reader(result)

    override fun mergeValue(live: T, cached: T): T = merger(live, cached)

    fun isAttempted(result: AdaptResult): Boolean =
        result.diagnosticsById[diagnosticId]?.let(attemptedPredicate) == true

    override fun needsRefresh(context: DexFeatureRefreshContext, value: AdaptResult): Boolean =
        refreshPredicate(context, value, isAttempted(value))

    companion object {
        fun failedDexReason(dexAssist: DexAssistSession?, planned: Iterable<DexAssistQuery>): String? =
            planned.firstNotNullOfOrNull { query ->
                val result = dexAssist?.result(query) as? DexAssistResult.Unavailable
                result?.reason?.takeUnless {
                    it == DexAssistResult.Reason.NO_MATCH || it == DexAssistResult.Reason.TOO_MANY_MATCHES
                }?.name?.lowercase()
            }

        /**
         * 对预先完成定位与解析的特性，按统一规则生成「applicable」诊断条目。
         */
        inline fun buildApplicableDiagnostic(
            diagnosticId: String,
            enabled: Boolean,
            applicable: Boolean,
            directFound: Boolean,
            ready: Boolean,
            planned: Boolean,
            previouslyMissing: Boolean,
            dexAssist: DexAssistSession?,
            query: DexAssistQuery,
            crossinline verifiedDetail: () -> String = { "verified" },
            disabledDetail: String =
                if (previouslyMissing) "feature-disabled:cached-attempt-preserved" else "feature-disabled",
            crossinline candidateFailureDetail: (DexAssistResult.Candidates) -> String = { outcome ->
                "unavailable-or-ambiguous:${outcome.methods.size}"
            }
        ): AdaptDiagnostic {
            val state = when {
                ready -> AdaptState.FOUND
                enabled && applicable && (planned || previouslyMissing) -> AdaptState.MISSING
                !enabled && previouslyMissing -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            }
            val detail = when {
                !enabled -> disabledDetail
                !applicable -> if (previouslyMissing) "cached-attempt-preserved" else "not-applicable-host"
                directFound && ready -> "direct"
                ready -> verifiedDetail()
                !planned -> "cached-attempt-preserved"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> candidateFailureDetail(outcome)
                }
            }
            return AdaptDiagnostic(diagnosticId, state, detail)
        }

        /**
         * 统一执行「有 `applicable(loader)` 前提的标准单诊断 DEX 特性定位 + 诊断生成」。
         */
        inline fun <T> locateAndDiagnoseApplicable(
            loader: ClassLoader,
            dexAssist: DexAssistSession?,
            diagnosticId: String,
            enabled: Boolean,
            planned: Boolean,
            previouslyMissing: Boolean,
            query: DexAssistQuery,
            crossinline applicableCheck: (ClassLoader) -> Boolean,
            crossinline directFoundCheck: (ClassLoader) -> Boolean,
            crossinline locateBlock: () -> T,
            crossinline readyCheck: (T) -> Boolean,
            crossinline verifiedDetail: () -> String = { "verified" },
            disabledDetail: String =
                if (previouslyMissing) "feature-disabled:cached-attempt-preserved" else "feature-disabled",
            crossinline candidateFailureDetail: (DexAssistResult.Candidates) -> String = { outcome ->
                "unavailable-or-ambiguous:${outcome.methods.size}"
            }
        ): DexFeatureOutcome<T> {
            val located = locateBlock()
            val applicable = enabled && applicableCheck(loader)
            val directFound = applicable && directFoundCheck(loader)
            val ready = enabled && (directFound || readyCheck(located))
            val state = when {
                ready -> AdaptState.FOUND
                enabled && applicable && (planned || previouslyMissing) -> AdaptState.MISSING
                !enabled && previouslyMissing -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            }
            val detail = when {
                !enabled -> disabledDetail
                !applicable -> if (previouslyMissing) "cached-attempt-preserved" else "not-applicable-host"
                directFound -> "direct"
                ready -> verifiedDetail()
                !planned -> "cached-attempt-preserved"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> candidateFailureDetail(outcome)
                }
            }
            return DexFeatureOutcome(located, AdaptDiagnostic(diagnosticId, state, detail))
        }

        /**
         * 无独立 `not-applicable-host` 门控的标准单诊断特性（如 FollowFeed / BrandSplashDecoder）。
         */
        inline fun <T> locateAndDiagnoseDirectOrVerified(
            loader: ClassLoader,
            dexAssist: DexAssistSession?,
            diagnosticId: String,
            enabled: Boolean,
            planned: Boolean,
            previouslyMissing: Boolean,
            query: DexAssistQuery,
            plannedFailureOverride: String? = null,
            crossinline directFoundCheck: (ClassLoader) -> Boolean,
            crossinline locateBlock: () -> T,
            crossinline readyCheck: (T) -> Boolean,
            crossinline candidateFailureDetail: (DexAssistResult.Candidates) -> String = { outcome ->
                "unavailable-or-ambiguous:${outcome.methods.size}"
            }
        ): DexFeatureOutcome<T> {
            val located = locateBlock()
            val ready = enabled && readyCheck(located)
            val state = if (ready) {
                AdaptState.FOUND
            } else if (enabled || previouslyMissing) {
                AdaptState.MISSING
            } else {
                AdaptState.NOT_APPLICABLE
            }
            val detail = when {
                !enabled -> if (previouslyMissing) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                directFoundCheck(loader) -> "direct"
                ready -> "verified"
                !planned -> "cached-attempt-preserved"
                dexAssist == null -> "no-dex-source"
                plannedFailureOverride != null -> plannedFailureOverride
                else -> when (val outcome = dexAssist.result(query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> candidateFailureDetail(outcome)
                }
            }
            return DexFeatureOutcome(located, AdaptDiagnostic(diagnosticId, state, detail))
        }
    }
}

/**
 * 支持多诊断 ID（Family/Query 枚举集合）的 DEX 特性适配器模板。
 */
internal class MultiDiagnosticDexFeatureAdapter<T>(
    override val featureId: String,
    override val diagnosticIds: Set<String>,
    override val retainMissingOnQuickLocate: Boolean = true,
    private val reader: (AdaptResult) -> T,
    private val merger: (live: T, cached: T) -> T,
    private val refreshPredicate: (DexFeatureRefreshContext, AdaptResult) -> Boolean
) : DexFeatureAdapter<T> {
    override fun readValue(result: AdaptResult): T = reader(result)
    override fun mergeValue(live: T, cached: T): T = merger(live, cached)
    override fun needsRefresh(context: DexFeatureRefreshContext, value: AdaptResult): Boolean =
        refreshPredicate(context, value)
}

/**
 * 全量 DEX 特性注册表，负责统一维护：
 * 1. `quickLocate` 阶段可沿用 MISSING 结论的诊断 ID 集合；
 * 2. 缓存快路径 `requiresFeatureRefresh` 的 O(1) 诊断查询与刷新判定；
 * 3. `adapt()` 中同构单诊断特性的批量定位与标准诊断输出；
 * 4. `AdaptResultMerger` 对所有特性的合并策略。
 */
internal object DexFeatureRegistry {

    internal fun AdaptResult.isDiagnosticAttemptedInCache(id: String): Boolean =
        diagnosticsById[id]?.let(AdaptAssistGate::isAttemptedInCache) == true

    internal fun AdaptResult.isDiagnosticFoundOrAttempted(id: String): Boolean =
        diagnosticsById[id]?.let {
            it.state == AdaptState.FOUND || AdaptAssistGate.isAttemptedInCache(it)
        } == true

    private val pageBoundariesAdapter = MultiDiagnosticDexFeatureAdapter<PageBoundaryPoints?>(
        featureId = "page_boundaries",
        diagnosticIds = PageBoundaryAdapter.queries.mapTo(linkedSetOf(), PageBoundaryAdapter::diagnostic),
        reader = AdaptResult::pageBoundaries,
        merger = { live, cached -> live ?: cached },
        refreshPredicate = { ctx, value ->
            val attempted = PageBoundaryAdapter.queries.filterTo(linkedSetOf()) { query ->
                value.isDiagnosticFoundOrAttempted(PageBoundaryAdapter.diagnostic(query))
            }
            PageBoundaryAdapter.planned(
                ctx.loader,
                value.pageBoundaries,
                ctx.flags.searchHomeRecommendEnabled,
                ctx.flags.playerEndPageRecommendEnabled,
                attempted,
                styleEnabled = ctx.flags.followFeedStyleEnabled
            ).isNotEmpty()
        }
    )

    private val playerPopupPromotionAdapter = MultiDiagnosticDexFeatureAdapter<PlayerPopupPromotionPoints?>(
        featureId = "player_popup_promotion",
        diagnosticIds = PlayerPopupPromotionAdapter.Family.entries.mapTo(linkedSetOf()) { it.diagnostic },
        reader = AdaptResult::playerPopupPromotion,
        merger = PlayerPopupPromotionAdapter::merge,
        refreshPredicate = { ctx, value ->
            ctx.flags.playerPopupPromotionEnabled &&
                PlayerPopupPromotionAdapter.missing(ctx.loader, value.playerPopupPromotion).any { family ->
                    !value.isDiagnosticAttemptedInCache(family.diagnostic)
                }
        }
    )

    private val commentClassicStyleAdapter = StandardDexFeatureAdapter<CommentClassicStylePoints?>(
        featureId = "comment_classic_style",
        diagnosticId = CommentClassicStyleLocator.DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::commentClassicStyle,
        merger = CommentClassicStyleLocator::merge,
        refreshPredicate = { ctx, value, attempted ->
            ctx.flags.commentClassicStyleEnabled &&
                CommentClassicStyleLocator.missing(ctx.loader, value.commentClassicStyle).isNotEmpty() &&
                !attempted
        }
    )

    private val sponsorPlayerAdapter = StandardDexFeatureAdapter<SponsorPlayerClasses?>(
        featureId = "sponsor_player",
        diagnosticId = SponsorPlayerLocator.DIAGNOSTIC,
        reader = AdaptResult::sponsorPlayer,
        refreshPredicate = { ctx, value, attempted ->
            ctx.flags.sponsorBlockEnabled && SponsorPlayerLocator.refreshCache(
                ctx.flags.sponsorBlockEnabled,
                SponsorPlayerLocator.applicable(ctx.loader),
                SponsorPlayerLocator.direct(ctx.loader) != null,
                value.sponsorPlayer?.let { SponsorPlayerLocator.resolve(ctx.loader, it) } != null,
                attempted
            )
        }
    )

    private val playerSpeedSessionAdapter = StandardDexFeatureAdapter<PlayerSpeedSessionClasses?>(
        featureId = "player_speed_session",
        diagnosticId = PlayerSpeedSessionLocator.DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::playerSpeedSession,
        refreshPredicate = { ctx, value, attempted ->
            PlayerSpeedSessionLocator.refreshCache(
                ctx.flags.playerSpeedSessionEnabled,
                { PlayerSpeedSessionLocator.applicable(ctx.loader) },
                { PlayerSpeedSessionLocator.modern(ctx.loader) != null },
                { value.playerSpeedSession?.let { PlayerSpeedSessionLocator.resolve(ctx.loader, it) } != null },
                { attempted }
            )
        }
    )

    private val playerSpeedCalculatorAdapter = StandardDexFeatureAdapter<LongPressCalculatorPoint?>(
        featureId = "player_speed_calculator",
        diagnosticId = PlayerSpeedLocator.CALCULATOR_DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::playerSpeedCalculator,
        refreshPredicate = { ctx, value, attempted ->
            PlayerSpeedLocator.calculatorRefreshCache(
                ctx.flags.playerSpeedCalculatorEnabled,
                { PlayerSpeedLocator.calculatorApplicable(ctx.loader) },
                { PlayerSpeedLocator.theseus2LongPressCalculator(ctx.loader, ctx.hostVersionCode) != null },
                { value.playerSpeedCalculator?.let { PlayerSpeedLocator.resolveCalculator(ctx.loader, it) } != null },
                { attempted }
            )
        }
    )

    private val weChatShareAdapter = StandardDexFeatureAdapter<WeChatShareClasses?>(
        featureId = "wechat_share",
        diagnosticId = WeChatShareCompatAccess.DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::weChatShare,
        refreshPredicate = { ctx, value, attempted ->
            WeChatShareCompatAccess.refreshCache(
                ctx.flags.weChatShareEnabled,
                { WeChatShareCompatAccess.applicable(ctx.loader) },
                {
                    WeChatShareCompatAccess.locate(ctx.loader, ctx.hostVersionCode)
                        ?.let { it.wrappers.size == it.expectedWrappers } == true
                },
                {
                    value.weChatShare?.let { WeChatShareCompatAccess.resolve(ctx.loader, it) }
                        ?.let { it.wrappers.size == it.expectedWrappers } == true
                },
                { attempted }
            )
        }
    )

    private val systemEnvironmentAdapter = MultiDiagnosticDexFeatureAdapter<SystemEnvironmentPoints?>(
        featureId = "system_environment",
        diagnosticIds = SystemEnvironmentAdapter.Family.entries.mapTo(linkedSetOf()) { it.diagnostic },
        reader = AdaptResult::systemEnvironment,
        merger = SystemEnvironmentAdapter::merge,
        refreshPredicate = { ctx, value ->
            ctx.flags.systemEnvironmentEnabled &&
                SystemEnvironmentAdapter.missing(ctx.loader, value.systemEnvironment).any { family ->
                    !value.isDiagnosticAttemptedInCache(family.diagnostic)
                }
        }
    )

    private val playerSpeedMenuAdapter = StandardDexFeatureAdapter<PlayerSpeedMenuPoints?>(
        featureId = "player_speed_menu",
        diagnosticId = PlayerSpeedMenuAdapter.DIAGNOSTIC,
        reader = AdaptResult::playerSpeedMenu,
        attemptedPredicate = { it.state == AdaptState.FOUND || AdaptAssistGate.isAttemptedInCache(it) },
        refreshPredicate = { ctx, value, attempted ->
            PlayerSpeedMenuAdapter.needsQuery(
                ctx.loader,
                value.playerSpeedMenu,
                ctx.flags.playerSpeedMenuEnabled,
                attempted
            )
        }
    )

    private val storyQualityAdapter = StandardDexFeatureAdapter<StoryQualityPoints?>(
        featureId = "story_quality",
        diagnosticId = StoryQualityLocator.DIAGNOSTIC,
        reader = { it.playerQuality?.story },
        merger = StoryQualityLocator::merge,
        refreshPredicate = { ctx, value, attempted ->
            StoryQualityLocator.planned(
                ctx.loader,
                value.playerQuality?.story,
                ctx.flags.playerQualityEnabled,
                attempted
            ).isNotEmpty()
        }
    )

    private val normalAutoSwitchAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "normal_auto_switch",
        diagnosticId = NormalQualityLocator.DIAGNOSTIC,
        reader = { it.playerQuality?.normalAutoSwitch },
        refreshPredicate = { ctx, value, attempted ->
            NormalQualityLocator.needsQuery(
                ctx.loader,
                value.playerQuality?.defaultQualityMethod,
                value.playerQuality?.normalAutoSwitch,
                ctx.flags.playerQualityEnabled,
                attempted
            )
        }
    )

    private val detailSpecialTagAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "detail_special_tag_mapper",
        diagnosticId = DetailSpecialTagAdapter.DIAGNOSTIC,
        reader = AdaptResult::detailSpecialTagMapper,
        refreshPredicate = { ctx, value, attempted ->
            DetailSpecialTagAdapter.needsQuery(
                ctx.loader,
                value.detailSpecialTagMapper,
                ctx.flags.detailSpecialTagEnabled,
                attempted
            )
        }
    )

    private val commentReplyButtonAdapter = StandardDexFeatureAdapter<Map<String, String>>(
        featureId = "comment_reply_button_owners",
        diagnosticId = CommentReplyButtonAdapter.DIAGNOSTIC,
        reader = AdaptResult::commentReplyButtonOwners,
        merger = { live, cached -> cached + live },
        refreshPredicate = { ctx, value, attempted ->
            CommentReplyButtonAdapter.needsQuery(
                ctx.loader,
                value.commentReplyButtonOwners,
                ctx.flags.commentReplyButtonEnabled,
                attempted
            )
        }
    )

    private val followFeedAdapter = StandardDexFeatureAdapter<FollowFeedPoints?>(
        featureId = "follow_feed_style",
        diagnosticId = FollowFeedAdapter.DIAGNOSTIC,
        reader = AdaptResult::followFeedStyle,
        refreshPredicate = { ctx, value, attempted ->
            FollowFeedAdapter.needsQuery(
                ctx.loader,
                value.followFeedStyle,
                ctx.flags.followFeedStyleEnabled,
                attempted
            )
        }
    )

    private val brandSplashDecoderAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "brand_splash_decoder",
        diagnosticId = BrandSplashDecoderAdapter.DIAGNOSTIC,
        reader = AdaptResult::brandSplashDecoder,
        refreshPredicate = { ctx, value, attempted ->
            BrandSplashDecoderAdapter.needsQuery(
                ctx.loader,
                value.brandSplashDecoder,
                ctx.flags.brandSplashCustomEnabled,
                attempted
            )
        }
    )

    private val brandSplashKntrDrawAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "brand_splash_kntr_draw",
        diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC,
        reader = AdaptResult::brandSplashKntrDraw,
        refreshPredicate = { ctx, value, attempted ->
            BrandSplashKntrAdapter.needsQuery(
                ctx.loader,
                value.brandSplashKntrDraw,
                ctx.flags.brandSplashCustomEnabled,
                attempted
            )
        }
    )

    private val brandSplashKntrExitAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "brand_splash_kntr_exit",
        diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC_EXIT,
        reader = AdaptResult::brandSplashKntrExit,
        refreshPredicate = { ctx, value, attempted ->
            BrandSplashKntrAdapter.needsExitQuery(
                ctx.loader,
                value.brandSplashKntrExit,
                ctx.flags.brandSplashCustomEnabled || ctx.flags.brandSplashSkipEnabled,
                attempted
            )
        }
    )

    private val brandSplashKntrPageAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "brand_splash_kntr_page",
        diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC_PAGE,
        reader = AdaptResult::brandSplashKntrPage,
        refreshPredicate = { ctx, value, attempted ->
            BrandSplashKntrAdapter.needsPageQuery(
                ctx.loader,
                value.brandSplashKntrPage,
                ctx.flags.brandSplashCustomEnabled || ctx.flags.splashAutoNightEnabled,
                attempted
            )
        }
    )

    private val commentQuickReplyAdapter = StandardDexFeatureAdapter<List<HookPoint>>(
        featureId = "comment_quick_reply",
        diagnosticId = CommentQuickReplyAdapter.DIAGNOSTIC,
        reader = { it.commentPurify?.quickReplyDialogMethods.orEmpty() },
        merger = { live, cached -> live.ifEmpty { cached } },
        refreshPredicate = { ctx, value, attempted ->
            val cachedQuickReply = value.commentPurify?.quickReplyDialogMethods.orEmpty()
            ctx.flags.commentQuickReplyEnabled && !attempted &&
                CommentQuickReplyAdapter.applicable(ctx.loader) &&
                CommentQuickReplyAdapter.resolve(ctx.loader, cachedQuickReply).isEmpty() &&
                CommentQuickReplyAdapter.needsQuery(
                    ctx.loader,
                    CommentLocator.locateCommentQuickReplyDirect(ctx.loader),
                    cachedQuickReply,
                    ctx.flags.commentQuickReplyEnabled,
                    attempted
                )
        }
    )

    private val pausedCountdownAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "paused_countdown",
        diagnosticId = PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN,
        reader = { it.pause.countdown },
        refreshPredicate = { ctx, value, attempted ->
            PausedAdDexAdapter.needsCountdownQuery(
                ctx.loader,
                direct = null,
                cached = value.pause.countdown,
                enabled = ctx.flags.pausedAdEnabled,
                attempted = attempted
            )
        }
    )

    private val pausedRequestApiAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "paused_request_api",
        diagnosticId = PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API,
        reader = { it.pause.apiEntry },
        refreshPredicate = { ctx, value, attempted ->
            PausedAdDexAdapter.needsRequestApiQuery(
                ctx.loader,
                ctx.hostVersionCode,
                value.pause.apiEntry,
                ctx.flags.pausedAdEnabled,
                attempted
            )
        }
    )

    private val homeTabResourceAdapter = StandardDexFeatureAdapter<VersionAdapter.HomeTabPoints?>(
        featureId = "home_tab_resource",
        diagnosticId = HomeTabResourceAdapter.DIAGNOSTIC,
        reader = AdaptResult::homeTabs,
        refreshPredicate = { ctx, value, attempted ->
            ctx.flags.homeTabsRequested && !attempted &&
                HomeTabResourceAdapter.applicable(ctx.loader) &&
                HomeTabResourceAdapter.resolve(ctx.loader, value.homeTabs) == null &&
                HomeTabResourceAdapter.needsQuery(
                    ctx.loader,
                    directFound = MineAndHomeUiLocator.locateHomeTabs(ctx.loader) != null,
                    cached = value.homeTabs,
                    enabled = true,
                    attempted = attempted
                )
        }
    )

    private val cloudKeyFreezeAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "cloud_key_freeze_contract",
        diagnosticId = CloudKeyFreezeAdapter.DIAGNOSTIC,
        reader = AdaptResult::cloudKeyFreezeContract,
        refreshPredicate = { ctx, value, attempted ->
            CloudKeyFreezeAdapter.needsQuery(
                ctx.loader,
                value.cloudKeyFreezeContract,
                ctx.flags.cloudKeyFreezeEnabled,
                attempted
            )
        }
    )

    private val privacyIgnetAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "privacy_ignet_http",
        diagnosticId = PrivacyChannelAdapter.DIAGNOSTIC_IGNET,
        reader = AdaptResult::privacyIgnetHttp,
        refreshPredicate = { ctx, value, attempted ->
            PrivacyChannelAdapter.needsIgnetQuery(
                ctx.loader,
                value.privacyIgnetHttp,
                ctx.flags.incognitoPrivacyEnabled || ctx.flags.guestBrowsingEnabled,
                attempted
            )
        }
    )

    private val privacyKNeuronAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "privacy_kneuron",
        diagnosticId = PrivacyChannelAdapter.DIAGNOSTIC_KNEURON,
        reader = AdaptResult::privacyKNeuron,
        refreshPredicate = { ctx, value, attempted ->
            PrivacyChannelAdapter.needsKNeuronQuery(
                ctx.loader,
                value.privacyKNeuron,
                ctx.flags.incognitoPrivacyEnabled,
                attempted
            )
        }
    )

    private val guestPlayerCoreAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "guest_player_core",
        diagnosticId = GuestPlayerCoreAdapter.DIAGNOSTIC,
        reader = AdaptResult::guestPlayerCore,
        refreshPredicate = { ctx, value, attempted ->
            GuestPlayerCoreAdapter.needsQuery(
                ctx.loader,
                value.guestPlayerCore,
                ctx.flags.guestBrowsingEnabled,
                attempted
            )
        }
    )

    private val homeRecommendEarlyLoadAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "home_recommend_early_load_listener",
        diagnosticId = HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
        reader = AdaptResult::homeRecommendEarlyLoadListener,
        refreshPredicate = { ctx, value, attempted ->
            HomeRecommendEarlyLoadAdapter.needsQuery(
                ctx.loader,
                value.homeRecommendEarlyLoadListener,
                ctx.flags.homeRecommendEarlyLoadEnabled,
                attempted
            )
        }
    )

    private val aiPlaylistDirectorAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "ai_playlist_director",
        diagnosticId = AiPlaylistDirectorAdapter.DIAGNOSTIC,
        reader = AdaptResult::aiPlaylistDirector,
        refreshPredicate = { ctx, value, attempted ->
            AiPlaylistDirectorAdapter.needsQuery(
                ctx.loader,
                value.aiPlaylistDirector,
                ctx.flags.aiDeclaredVideoEnabled,
                attempted
            )
        }
    )

    private val followUpRecommendAdapter = StandardDexFeatureAdapter<Map<String, String>>(
        featureId = "follow_up_recommend_bodies",
        diagnosticId = FollowUpRecommendAdapter.DIAGNOSTIC,
        reader = AdaptResult::followUpRecommendBodies,
        merger = { live, cached -> cached + live },
        refreshPredicate = { ctx, value, attempted ->
            FollowUpRecommendAdapter.needsQuery(
                ctx.loader,
                value.followUpRecommendBodies,
                ctx.flags.followUpRecommendEnabled,
                attempted
            )
        }
    )

    private val detailRelateGameAdapter = StandardDexFeatureAdapter<String?>(
        featureId = "detail_relate_game_component",
        diagnosticId = DetailRelateGameAdapter.DIAGNOSTIC,
        reader = AdaptResult::detailRelateGameComponent,
        refreshPredicate = { ctx, value, attempted ->
            DetailRelateGameAdapter.needsQuery(
                ctx.loader,
                value.detailRelateGameComponent,
                ctx.flags.detailRelateGameEnabled,
                attempted
            )
        }
    )

    private val legacyStoryDanmakuAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "legacy_story_danmaku_bind",
        diagnosticId = LegacyStoryDanmakuAdapter.DIAGNOSTIC,
        reader = AdaptResult::legacyStoryDanmakuBind,
        refreshPredicate = { ctx, value, attempted ->
            LegacyStoryDanmakuAdapter.needsQuery(
                ctx.loader,
                value.legacyStoryDanmakuBind,
                ctx.flags.storyDanmakuToggleHidden,
                attempted
            )
        }
    )

    private val theseus2LongPressAdapter = StandardDexFeatureAdapter<Theseus2LongPressPoint?>(
        featureId = "player_speed_theseus2_long_press",
        diagnosticId = PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC,
        reader = AdaptResult::playerSpeedTheseus2LongPress,
        refreshPredicate = { ctx, value, attempted ->
            PlayerSpeedLocator.theseus2LongPressNeedsQuery(
                ctx.loader,
                value.playerSpeedTheseus2LongPress,
                ctx.flags.playerSpeedLongPressEnabled,
                attempted
            )
        }
    )

    private val homeChromeAdapter = MultiDiagnosticDexFeatureAdapter<Map<String, HookPoint>>(
        featureId = "home_chrome",
        diagnosticIds = HomeChromeAdapter.families.keys.mapTo(linkedSetOf(), HomeChromeAdapter::diagnostic),
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::homeChrome,
        merger = { live, cached -> cached + live },
        refreshPredicate = { ctx, value ->
            val attempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
                value.isDiagnosticAttemptedInCache(HomeChromeAdapter.diagnostic(query))
            }
            HomeChromeAdapter.planned(
                ctx.loader,
                value.homeChrome,
                ctx.flags.homeChromeEnabled,
                attempted,
                ctx.flags.homeChromeIconOnly,
                ctx.flags.homeTopTabCenteringEnabled
            ).isNotEmpty()
        }
    )

    private val homeChromeSemanticsAdapter = StandardDexFeatureAdapter<HomeChromeSemanticsPoint?>(
        featureId = "home_chrome_semantics",
        diagnosticId = HomeChromeAdapter.SEMANTICS_DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = AdaptResult::homeChromeSemantics,
        refreshPredicate = { ctx, value, attempted ->
            HomeChromeAdapter.semanticsNeedsQuery(
                ctx.loader,
                value.homeChromeSemantics,
                ctx.flags.homeChromeEnabled,
                attempted,
                ctx.flags.homeChromeIconOnly
            )
        }
    )

    private val commentComposeMenuAdapter = StandardDexFeatureAdapter<CommentComposeMenuPoints?>(
        featureId = "comment_compose_menu",
        diagnosticId = CommentComposeMenuAdapter.DIAGNOSTIC,
        reader = AdaptResult::commentComposeMenu,
        merger = CommentComposeMenuAdapter::merge,
        refreshPredicate = { ctx, value, attempted ->
            CommentComposeMenuAdapter.needsQuery(
                ctx.loader,
                value.commentComposeMenu,
                ctx.flags.commentTopologyEnabled,
                attempted
            )
        }
    )

    private val kmpGrpcWireAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "kmp_grpc_wire_engine",
        diagnosticId = KmpGrpcWireAdapter.DIAGNOSTIC,
        reader = AdaptResult::kmpGrpcWireEngine,
        refreshPredicate = { ctx, value, attempted ->
            KmpGrpcWireAdapter.needsQuery(
                ctx.loader,
                value.kmpGrpcWireEngine,
                ctx.flags.kmpGrpcWireEnabled || ctx.flags.commentTopologyEnabled,
                attempted
            )
        }
    )

    private val kotlinDefaultWordsAdapter = StandardDexFeatureAdapter<HookPoint?>(
        featureId = "kotlin_default_words",
        diagnosticId = KotlinDefaultWordsLocator.DIAGNOSTIC,
        retainMissingOnQuickLocate = false,
        reader = { it.homeTopBar?.kotlinDefaultWords },
        refreshPredicate = { ctx, value, attempted ->
            ctx.flags.kotlinDefaultWordsEnabled && KotlinDefaultWordsLocator.refreshCache(
                enabled = true,
                applicable = KotlinDefaultWordsLocator.applicable(ctx.loader),
                directFound = KotlinDefaultWordsLocator.direct(ctx.loader) != null,
                cachedFound = value.homeTopBar?.kotlinDefaultWords != null,
                attempted = attempted
            )
        }
    )

    private val assistGateAdapter = MultiDiagnosticDexFeatureAdapter<AssistHistory>(
        featureId = "adapt_assist_gate",
        diagnosticIds = setOf(
            AdaptAssistGate.BLOCK_UPDATE_ID,
            AdaptAssistGate.PLAYER_QUALITY_ID,
            AdaptAssistGate.COMMENT_TOPOLOGY_ID
        ),
        reader = { AssistHistory.of(it) },
        merger = { live, _ -> live },
        refreshPredicate = { ctx, value ->
            VersionAdapter.assistRefreshNeeded(
                ctx.loader,
                value,
                ctx.flags.blockUpdateEnabled,
                ctx.flags.playerQualityEnabled,
                ctx.flags.commentTopologyEnabled
            )
        }
    )

    internal val adapters: List<DexFeatureAdapter<*>> = listOf(
        pageBoundariesAdapter,
        playerPopupPromotionAdapter,
        commentClassicStyleAdapter,
        sponsorPlayerAdapter,
        playerSpeedSessionAdapter,
        playerSpeedCalculatorAdapter,
        weChatShareAdapter,
        systemEnvironmentAdapter,
        playerSpeedMenuAdapter,
        storyQualityAdapter,
        normalAutoSwitchAdapter,
        detailSpecialTagAdapter,
        commentReplyButtonAdapter,
        followFeedAdapter,
        brandSplashDecoderAdapter,
        brandSplashKntrDrawAdapter,
        brandSplashKntrExitAdapter,
        brandSplashKntrPageAdapter,
        commentQuickReplyAdapter,
        pausedCountdownAdapter,
        pausedRequestApiAdapter,
        homeTabResourceAdapter,
        cloudKeyFreezeAdapter,
        privacyIgnetAdapter,
        privacyKNeuronAdapter,
        guestPlayerCoreAdapter,
        homeRecommendEarlyLoadAdapter,
        aiPlaylistDirectorAdapter,
        followUpRecommendAdapter,
        detailRelateGameAdapter,
        legacyStoryDanmakuAdapter,
        theseus2LongPressAdapter,
        homeChromeAdapter,
        homeChromeSemanticsAdapter,
        commentComposeMenuAdapter,
        kmpGrpcWireAdapter,
        assistGateAdapter,
        kotlinDefaultWordsAdapter
    )

    /** `quickLocate` 期间若遇到占位 NOT_APPLICABLE("quick-locate") 但缓存已确认 MISSING，需保留该结论的诊断 ID 集合。 */
    internal val quickLocateMissingRetainedDiagnosticIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        adapters
            .asSequence()
            .filter { it.retainMissingOnQuickLocate }
            .flatMap { it.diagnosticIds.asSequence() }
            .toSet()
    }

    internal fun requiresFeatureRefresh(
        context: DexFeatureRefreshContext,
        value: AdaptResult
    ): Boolean = KavaMemberLookup.withLookupBatch(context.loader) {
        adapters.any { it.needsRefresh(context, value) }
    }
}
