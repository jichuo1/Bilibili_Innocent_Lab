@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.HighlightKind
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsLayout
import com.highcapable.betterandroid.system.extension.component.disableComponent
import com.highcapable.betterandroid.system.extension.component.enableComponent
import com.highcapable.betterandroid.system.extension.component.isComponentEnabled
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.extension.view.firstChildOrNull
import com.highcapable.betterandroid.ui.extension.view.parentOrNull
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.view.updateMargins
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.Layout
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.extension.setContentView
import com.highcapable.hikage.runtime.attribute.AttributeSetResolver
import com.highcapable.hikage.widget.android.widget.ImageView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.Space
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.RuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AndroidUserSpace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AndroidUserSpaceSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ShellCommandRunner
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateCheckCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ColdStartUpdateSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateChannelStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpecStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.ModalBackdropBlurStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isLspatch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsGateDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsSyncState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.didUserTermsAuthorizationComplete
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryActionResult
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryActionStatus
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseNotesMarkdownRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseNotesScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportFailure
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportResult
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCaptureStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRepository
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.EditText as NativeEditText
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView
import androidx.core.graphics.ColorUtils
import androidx.core.content.edit
import android.R as Android_R
import com.highcapable.kavaref.extension.classOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : SkinnedActivity() {

    // internal：只放常量与无状态帮助函数；外移的弹窗文件需要按名导入其中几个
    // （扩展函数里 companion 成员不在作用域内）。这里没有可变状态，放宽不引入共享风险。
    internal companion object {
        /** 旧版本共用的成功检查时间（升级后作为稳定版渠道的历史时间迁移读取）。 */
        const val PREF_LAST_SUCCESSFUL_UPDATE_CHECK = "last_successful_check_ms"
        /** 各渠道独立的成功检查时间，避免切换渠道后 24 小时节流误跳过新渠道检查。 */
        const val PREF_LAST_CHECK_STABLE = "last_successful_check_ms_stable"
        const val PREF_LAST_CHECK_PREVIEW = "last_successful_check_ms_preview"
        const val FRAMEWORK_STATUS_SETTLE_MS = 1_500L
        const val MINE_COMPONENT_SNAPSHOT_STALE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SETTINGS_SEARCH_HIGHLIGHT_DELAY_MS = 240L
        const val SETTINGS_SEARCH_HIGHLIGHT_DURATION_MS = 560L

        /** 赞助页；与仓库地址一样经 [openExternalUrl] 的 https 白名单跳转。 */
        const val SPONSOR_URL = "https://ifdian.net/a/jichuo1"

        /** 仅允许仍处于前台的设置 Activity 完成用户已确认的系统页跳转。 */
        fun openBilibiliAppDetails(activity: MainActivity): Boolean {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${HookEntry.TARGET_PACKAGE}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                activity.startActivity(intent)
                true
            }.getOrElse { throwable ->
                Log.e("BilibiliInnocentLab", "open Bilibili app details failed", throwable)
                activity.toast(activity.getString(R.string.no_root_restart_open_failed))
                false
            }
        }
    }

    // internal：外移的语言选择弹窗要遍历 entries 并读 labelRes、languageTag。
    internal enum class AppLanguage(
        val languageTag: String?,
        @param:StringRes val labelRes: Int
    ) {
        SYSTEM(null, R.string.app_language_follow_system),
        SIMPLIFIED_CHINESE("zh-CN", R.string.app_language_simplified_chinese),
        TRADITIONAL_CHINESE("zh-Hant", R.string.app_language_traditional_chinese),
        ENGLISH("en", R.string.app_language_english)
    }

    private val homeComponent by lazy { ComponentName(packageName, "${BuildConfig.APPLICATION_ID}.Home") } 

    private val settingsBackupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val messageRes = when (result.data?.getStringExtra(
                SettingsBackupActivity.EXTRA_IMPORT_OUTCOME
            )) {
                SettingsBackupActivity.OUTCOME_VERIFIED ->
                    R.string.settings_backup_import_applied
                else -> R.string.settings_backup_import_needs_review
            }
            toast(getString(messageRes))
            recreate()
        }
    }

    internal val liquidBackgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importLiquidBackground(uri)
    }

    private var adskipEnabled = true
    private var gamecardAdEnabled = true
    private var hideVideoDetailAppPromotion = false
    private var bannerAdEnabled = true
    private var merchAdEnabled = true
    private var hideHomeGameMenu = false
    private var hideHomeSearchDefaultWord = false
    private var homeVerticalOpenDetail = false
    internal var removeHomeRecommendAds = false
    private var removeHomeRecommendCmV2 = false
    internal var removeHomeRecommendPictures = false
    internal var removeHomeRecommendGamePromotions = false
    private var homeRecommendTitleFilterEnabled = false
    private var homeRecommendTitleKeywords = ""
    internal var removeHomeRecommendLive = false
    internal var removeHomeRecommendCourses = false
    internal var removeHomeRecommendVertical = false
    internal var removeHomeRecommendLarge = false
    internal var removeHomeRecommendPgc = false
    internal var removeHomeRecommendSpecialCards = false
    private var homeTabHiddenRules = ""
    private var homeComponentHiddenRules = ""
    private var bottomBarHiddenRules = ""
    internal var recommendVideoMinDurationSeconds = 0
    internal var recommendVideoMaxDurationSeconds = 0
    internal var removeStoryAds = false
    internal var removeStoryLive = false
    internal var removeStoryGames = false
    internal var removeStoryBangumi = false
    internal var removeStoryCourses = false
    internal var removeStoryShortDrama = false
    internal var removeStoryShopping = false
    internal var removeStoryMovies = false
    internal var removeStoryDocumentaries = false
    internal var removeStoryTv = false
    internal var removeStoryVariety = false
    internal var removeStoryMusic = false
    private var hideMineVip = false
    private var keepMineVipSpace = false
    private var mineComponentHiddenRules = ""
    /** 每个面各自节流一次查询；四个面的面板可以互不阻塞地打开。仅在主线程读写。 */
    private val componentSnapshotQueryInFlight = mutableSetOf<String>()
    private var blockAppUpdate = false
    private var hideDynamicCityTab = false
    private var hideDynamicSchoolTab = false
    private var preferDynamicVideoTab = false
    private var showFullNumbers = false
    private var hidePlayerPortraitControl = false
    private var hidePlayerInteractiveOverlays = false
    private var hidePgcAutoActivityPopup = false
    private var transparentPlayerStatusBar = false
    // 详情页模块净化（UGC view.v1 顶层字段）；勾选面板保存后由 applyDetailModuleFilterValue 同步。
    internal var removeDetailHonor = false
    internal var removeDetailLiveOrder = false
    internal var removeDetailUgcSeason = false
    internal var removeDetailUpVipLabel = false
    internal var removeDetailTopicTags = false
    internal var removeDetailStaffFollow = false
    internal var removeDetailHotBanner = false
    internal var removeRelateCommercial = false
    internal var removeRelateGame = false
    internal var removeRelateLive = false
    internal var removeRelateCourse = false
    internal var removeRelateSpecial = false
    internal var videoRelateMatchingEnhancementEnabled = false
    internal var videoRelateStrongModeEnabled = false
    internal var videoRelateReasonFilterEnabled = false
    internal var videoRelateReasonFilterKeywords = ""
    internal var playerDefaultQualityQn = 0
    private var playerDisableLongPress = false
    internal var playerLongPressSpeedPercent = 0
    internal var playerDefaultSpeedPercent = 0
    private var blockTeenagersModePrompt = false
    private var removeCommentSearchLinks = false
    private var removeCommentEmptyGuide = false
    private var removeCommentVoteWidgets = false
    private var removeCommentFollowButtons = false
    private var removeCommentQoe = false
    private var removeCommentOperations = false
    private var blockCommentQuickReply = false
    private var hideCommentSection = false
    private var replyTopologyEnabled = false
    private var commentKeywordFilterEnabled = false
    private var commentFilterKeywords = ""
    private var commentMinLevelFilterEnabled = false
    internal var commentMinLevel = CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL
    private var dynamicKeywordFilterEnabled = false
    private var dynamicFilterKeywords = ""
    private var dynamicAuthorFilterEnabled = false
    private var dynamicAuthorFilterRules = ""
    private var removeDynamicPromotions = false
    private var removeDynamicChargeOnly = false
    private var hideDynamicTopicList = false
    private var hideDynamicFrequentVisits = false
    private var removeDynamicLiveUpEntries = false
    private var removeSearchCommercial = false
    private var hideSearchHomeRecommend = false
    private var searchKeywordFilterEnabled = false
    private var searchFilterKeywords = ""
    private var searchAuthorFilterEnabled = false
    private var searchAuthorFilterRules = ""
    private var removeAtOnlyComments = false
    private var commentUserFilterEnabled = false
    private var commentUserFilterRules = ""
    private var danmakuWeightFilterEnabled = false
    internal var danmakuWeightMinimum = DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT
    private var removeVipColorfulDanmaku = false
    private var blockLiveRoomSwitch = false
    private var liveRoomDoubleTapPause = false
    private var purifyShareContent = false
    private var shareMiniProgramDirectLink = false
    private var forceExternalBrowser = false
    private var systemMediaNotification = false
    private var splashAutoNight = false
    private var showBvAsAv = false
    private var purifySplashAds = false
    private var freeCopyEnabled = true
    private var freeCopyDescEnabled = true
    private var freeCopyLightMode = false
    private var freeCopyAutoLight = false

    /** 亮色开关二次确认进行中标志（防 setOnCheckedChangeListener 重入递归） */
    private var autoLightConfirmInProgress = false

    /** 程序化 setChecked（UI 同步）时抑制开关 listener 回触发 */
    private var programmaticSwitch = false

    /** 手动亮色开关引用（自由复制区） */
    private var manualLightSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null

    /** 自动跟随开关引用（增强栏的复制气泡外观） */
    private var autoLightSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null

    /** 手动亮色开关下方 tip 引用（动态动画切换文本） */
    private var lightModeTipView: NativeTextView? = null
    internal var playerQualitySummaryView: NativeTextView? = null
    private var playerLongPressSpeedSummary: NativeTextView? = null
    private var playerDefaultSpeedSummary: NativeTextView? = null
    private var homeTabRulesSummaryView: NativeTextView? = null
    private var homeRecommendTitleSummaryView: NativeTextView? = null
    private var homeRecommendFilterEntryView: View? = null
    internal var homeRecommendFilterSummaryView: NativeTextView? = null
    private var homeComponentRulesSummaryView: NativeTextView? = null
    private var mineComponentRulesSummaryView: NativeTextView? = null
    private var bottomBarRulesSummaryView: NativeTextView? = null
    internal var recommendVideoDurationSummaryView: NativeTextView? = null
    private var commentKeywordSummaryView: NativeTextView? = null
    internal var commentLevelSummaryView: NativeTextView? = null
    private var commentUserFilterSummaryView: NativeTextView? = null
    private var dynamicKeywordSummaryView: NativeTextView? = null
    private var dynamicAuthorSummaryView: NativeTextView? = null
    private var searchKeywordSummaryView: NativeTextView? = null
    private var searchAuthorSummaryView: NativeTextView? = null
    internal var danmakuWeightSummaryView: NativeTextView? = null
    internal var portraitContentFilterSummaryView: NativeTextView? = null
    internal var videoRelateFilterSummaryView: NativeTextView? = null
    internal var detailModuleFilterSummaryView: NativeTextView? = null
    /** 设置备份入口及标题：用于跨 Activity 容器形变的来源坐标。 */
    private var settingsBackupEntryView: View? = null
    private var settingsBackupEntryTitleView: NativeTextView? = null
    private var roamingCompatEnabled = false
    private var noRootDesiredEnabled = false
    private var predictiveBackEnabled = false
    private var logEnabled = true
    private var logVerbose = true

    /** 实验性功能主栏下的两个独立二级菜单。 */
    private var experimentalSettingsRoot: View? = null
    private var appearanceContent: View? = null
    private var appearanceChevron: View? = null
    private var appearanceExpanded = false
    private var compatibilityContent: View? = null
    private var compatibilityChevron: View? = null
    private var compatibilityExpanded = false

    /** 两个按用途拆分的进阶菜单，沿用同一属性动画。 */
    private var purificationSettingsRoot: View? = null
    private var enhancementSettingsRoot: View? = null
    private var purificationAdvancedContent: View? = null
    private var purificationAdvancedChevron: View? = null
    private var purificationAdvancedExpanded = false
    private var enhancementAdvancedContent: View? = null
    private var enhancementAdvancedChevron: View? = null
    private var enhancementAdvancedExpanded = false

    /** 净化和增强都按区域独立折叠；只有跨页面共享过滤保留直接入口。 */
    private enum class AdvancedSettingsCategory(
        val section: SettingsSearchSection,
        @StringRes val titleRes: Int
    ) {
        PURIFY_HOME(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_home),
        PURIFY_NAVIGATION(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_navigation),
        PURIFY_SEARCH(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_search),
        PURIFY_MINE(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_mine),
        PURIFY_PLAYBACK(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_playback),
        PURIFY_COMMENTS(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_comments),
        PURIFY_STARTUP(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_startup),
        PURIFY_SHARE(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_share),
        PURIFY_SHARED(SettingsSearchSection.PURIFICATION_ADVANCED, R.string.advanced_purify_shared),
        ENHANCE_BROWSING(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.advanced_enhance_browsing),
        ENHANCE_PLAYBACK(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.advanced_enhance_playback),
        ENHANCE_LIVE(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.advanced_enhance_live),
        ENHANCE_COMMENTS(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.advanced_enhance_comments),
        ENHANCE_DISPLAY(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.number_display_settings),
        ENHANCE_SYSTEM(SettingsSearchSection.ENHANCEMENT_ADVANCED, R.string.advanced_enhance_system);

        val collapsible: Boolean
            get() = this != PURIFY_SHARED
    }

    private data class AdvancedCategorySection(
        val header: View,
        val content: View,
        val chevron: View?,
        var expanded: Boolean = false
    )

    private val advancedCategoryMarkers =
        linkedMapOf<AdvancedSettingsCategory, NativeTextView>()
    private val advancedCategorySections =
        linkedMapOf<AdvancedSettingsCategory, AdvancedCategorySection>()

    /** 免 Root 配置只在用户明确开启后同步；回调不得持有 Activity 或 View。 */
    internal var noRootPrefsBridge: SharedPreferences? = null
    private var noRootSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null
    internal var noRootStatusView: NativeTextView? = null
    private var noRootProgrammaticSwitch = false

    /** 激活卡片需要同时聚合 LSPosed 状态与经过版本校验的免 Root heartbeat。 */
    private var activationCardView: View? = null
    private var activationIconView: android.widget.ImageView? = null
    private var activationTitleView: NativeTextView? = null
    private var activationSourceView: NativeTextView? = null
    private var activationVersionView: NativeTextView? = null
    /** 诊断中心入口及标题：用于从点击区域连续形变到全屏页面。 */
    private var diagnosticsEntryView: View? = null
    private var diagnosticsEntryTitleView: NativeTextView? = null
    private var diagnosticsSummaryView: NativeTextView? = null
    private val activationMainHandler = Handler(Looper.getMainLooper())
    private var frameworkStatusCheckPending = true
    private var frameworkServiceObserved = false
    private val lspatchActivationReceiptTracker = LspatchActivationReceiptTracker()

    /**
     * 模块与宿主的 Android 用户空间关系。`renderActivationUi` 会被框架状态回调、皮肤刷新和
     * 免 Root 状态变化反复调用，因此这里缓存 `onStart` 采集的一次 PackageManager 结果，不在
     * 渲染路径里重复查询。未采集前按主用户处理，不产生任何提示。
     */
    private var moduleUserSpace = AndroidUserSpaceSnapshot.PRIMARY
    private val frameworkStatusTimeout = Runnable {
        frameworkStatusCheckPending = false
        if (userTermsDecision.isAuthorized &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            renderActivationUi()
        }
    }
    private val frameworkStatusListener = ModernFrameworkStatusListener { status ->
        activationMainHandler.post {
            if (status.connected) {
                frameworkServiceObserved = true
                frameworkStatusCheckPending = false
                activationMainHandler.removeCallbacks(frameworkStatusTimeout)
            } else if (frameworkServiceObserved) {
                // 已连接服务死亡与首次等待不同：立即显示连接中断，不重新伪装成“确认中”。
                frameworkStatusCheckPending = false
                activationMainHandler.removeCallbacks(frameworkStatusTimeout)
            }
            if (status.isLspatch && status.capable) {
                requestLspatchHostReceipt(status)
            } else {
                lspatchActivationReceiptTracker.clearConnectionEvidence()
            }
            if (userTermsDecision.isAuthorized &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                renderActivationUi(status)
            } else {
                termsAuthorizationSnapshot?.let(::renderPendingTermsUi)
                renderTermsGateDiagnostics()
            }
        }
    }
    private val userTermsAuthorizationListener = UserTermsAuthorizationListener { snapshot ->
        activationMainHandler.post {
            termsAuthorizationSnapshot = snapshot
            val decision = snapshot.consentState.decision
            if (decision.isAuthorized) {
                val authorizationJustCompleted = didUserTermsAuthorizationComplete(
                    previous = userTermsDecision,
                    current = decision
                )
                userTermsDecision = decision
                if (authorizationJustCompleted &&
                    !termsDecisionActionInProgress &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    termsDecisionActionInProgress = true
                    recreate()
                }
            } else if (snapshot.consentState.isAcceptancePending) {
                renderPendingTermsUi(snapshot)
            } else {
                renderTermsGateDiagnostics()
            }
        }
    }

    /** 当前活动的确认弹窗：Activity 销毁时主动 dismiss，避免 WindowLeaked */
    internal var activeConfirmDialog: Dialog? = null
    private val settingsDestinations = SettingsDestinationRegistry<View>()
    internal var pendingHighlightsFrom: Int? = null
    private var pendingHighlightsAutomatic = true
    internal var releaseHighlightsDialog: Dialog? = null
    internal var activeHighlightsFrom: Int? = null
    internal var activeHighlightsAutomatic = true
    private var pendingHighlightDestination: String? = null
    private var highlightNavigationInFlight = false
    private var highlightNavigationGeneration = 0L
    private var highlightsLayoutPending = false
    private var highlightsDisposed = false
    private val showHighlightsWhenIdle = Runnable {
        if (highlightsDisposed || isFinishing || isDestroyed) return@Runnable
        if (highlightNavigationInFlight || (pendingHighlightsFrom == null && pendingHighlightDestination == null)) return@Runnable
        val root = settingsSearchRoot
        val eligible = ReleaseHighlightsPolicy.canShow(updateUiResumed, userTermsDecision.isAuthorized,
            root != null && root.isLaidOut, hasWindowFocus(), activeConfirmDialog?.isShowing == true,
            !telemetryDisclosurePrompted && TelemetryStore.needsDisclosureReview(applicationContext),
            updateCheckCoordinator.isBusy())
        if (!eligible) return@Runnable
        val destination = pendingHighlightDestination
        if (destination != null) {
            pendingHighlightDestination = null
            revealHighlightDestination(destination)
        } else pendingHighlightsFrom?.let { showReleaseHighlights(it, pendingHighlightsAutomatic) }
    }

    /** Liquid 专用共享回弹层；滚动内容和根背景切片在同一 RenderNode 中形变。 */
    private var liquidStretchScrollTarget: View? = null
    private var liquidStretchViewport: View? = null
    /** 设置搜索只持有当前 Activity 的控件树，销毁时与其他 View 引用一起释放。 */
    private var settingsSearchRoot: ViewGroup? = null
    private var settingsSearchScrollView: androidx.core.widget.NestedScrollView? = null
    private var settingsSearchHighlightView: View? = null
    private var settingsSearchHighlightDrawable: GradientDrawable? = null
    private var settingsSearchHighlightAnimator: ValueAnimator? = null
    private var settingsSearchHighlightRunnable: Runnable? = null

    /** 用户条款决定与等待 API 102 同步状态；授权完成后随 recreate 进入主界面。 */
    internal var userTermsDecision = UserTermsDecision.UNDECIDED
    internal var termsConsentState = UserTermsConsentState(UserTermsDecision.UNDECIDED)
    internal var termsAuthorizationSnapshot: UserTermsAuthorizationSnapshot? = null
    internal var termsDecisionActionInProgress = false

    /** 条款弹窗/等待页的提示与框架管理器入口，只持有当前 Activity 的 View。 */
    internal var termsDialogHintView: NativeTextView? = null
    internal var termsManagerLauncher: NativeTextView? = null
    internal var termsPendingStatusView: NativeTextView? = null
    internal var termsDiagnosticsValueView: NativeTextView? = null

    /** Liquid renderer 的同一 Activity 失败只处理一次，避免重复 toast/recreate。 */
    private var skinFailureHandled = false

    /** 皮肤选择的同步写入和退场动画只允许单飞，避免重复动画吞掉 recreate 回调。 */
    internal var skinSelectionActionInProgress = false
    private var skinSummaryView: NativeTextView? = null
    private var materialColorSpecProgrammaticSwitch = false

    /** 自定义背景导入只允许单飞；文件解码、哈希和原子替换全部离开主线程。 */
    internal val liquidBackgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-import").apply { isDaemon = true }
    }
    internal var liquidBackgroundTask: Future<*>? = null
    internal var liquidBackgroundImportInProgress = false
    private var liquidBackgroundSummaryView: NativeTextView? = null
    internal var liquidBackgroundDialog: Dialog? = null
    internal var liquidBackgroundDialogContainer: NativeLinearLayout? = null

    /** GitHub 请求只允许单飞；切换渠道时保留最后一次手动请求并抑制过期结果。 */
    private val updateCheckCoordinator = UpdateCheckCoordinator()
    internal val updateUiHandler = Handler(Looper.getMainLooper())
    private val updateUiOwner = ColdStartUpdateSession.newOwner()
    internal var updateUiResumed = false
    private var githubUpdateBadge: NativeTextView? = null
    private val updateNoticeObserver: () -> Unit = { renderUpdateBadge() }
    private val coldStartUpdateCheck = Runnable {
        if (updateUiResumed && userTermsDecision.isAuthorized &&
            ColdStartUpdateSession.state.claimAutomatic(updateUiOwner, android.os.SystemClock.elapsedRealtime())) {
            checkForUpdates(manual = false)
        }
    }

    private fun scheduleColdStartUpdate() {
        if (!updateUiResumed || !userTermsDecision.isAuthorized || githubUpdateBadge == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        ColdStartUpdateSession.state.resume(updateUiOwner, now)
        updateUiHandler.removeCallbacks(coldStartUpdateCheck)
        ColdStartUpdateSession.state.remainingMs(updateUiOwner, now)?.let {
            updateUiHandler.postDelayed(coldStartUpdateCheck, it)
        }
    }

    internal fun renderUpdateBadge() {
        val badge = githubUpdateBadge ?: return
        val notice = ColdStartUpdateSession.state.noticeFor(UpdateChannelStore.read(applicationContext))
        badge.animate().setListener(null).cancel()
        if (notice == null) {
            if (!updateUiResumed || badge.visibility != View.VISIBLE) {
                badge.visibility = View.INVISIBLE
                return
            }
            badge.animate().alpha(0f).scaleX(0.55f).scaleY(0.7f)
                .setDuration(180L).setInterpolator(emphasizedDecelerate)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        badge.visibility = View.INVISIBLE
                        badge.animate().setListener(null)
                    }
                }).start()
            return
        }
        badge.contentDescription = getString(R.string.github_new_update_description, notice.release.tagName)
        if (!updateUiResumed) return
        if (badge.visibility != View.VISIBLE) {
            badge.alpha = 0f
            badge.scaleX = 0.55f
            badge.scaleY = 0.7f
            badge.translationX = 4f * resources.displayMetrics.density
            badge.translationY = 3f * resources.displayMetrics.density
            badge.visibility = View.VISIBLE
        }
        badge.pivotX = 0f
        badge.pivotY = badge.height.toFloat()
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(320L).setInterpolator(emphasizedDecelerate).start()
    }

    /** 日志详细度档位选择器的两个 pill 控件引用 + 滑动滑块 + 描述 TextView */
    private var logLevelMinimalPill: android.widget.TextView? = null
    private var logLevelCompletePill: android.widget.TextView? = null
    private var logLevelThumb: View? = null
    private var logLevelDesc: android.widget.TextView? = null
    private var logLevelColorAnimator: ValueAnimator? = null
    /** 档位选择器是否已完成首次布局（滑块定位需要测量后执行） */
    private var logLevelLaidOut = false

    // Material You 标准动效插值器
    internal val emphasizedDecelerate = PathInterpolator(0.2f, 0f, 0f, 1f)   // 展开（减速收尾）
    private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 1f, 1f)   // 收起（加速开始）
    // 超长二级菜单使用独立的 Material 3 风格曲线：快速建立反馈，保留更长的柔和收尾。
    // 不复用上方插值器，避免改变弹窗、日志滑块等已有动画的节奏。
    internal val secondaryExpandInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    internal val secondaryCollapseInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** 生成圆角背景（应用 Monet 动态色） */
    private fun roundedColor(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 15f
            setColor(color)
        }

    /** 生成滑动滑块背景（primary 圆角，随选中项滑动） */
    private fun logLevelThumbBg(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(monetColors.primary)
        }

    /**
     * 切换日志详细度档位：滑块平滑滑动到目标项 + 文字颜色/字重渐变 + 描述联动更新。
     * 滑块动画用 translationX（GPU 加速、不触发布局），文字用 ValueAnimator 逐帧插值颜色。
     */
    private fun animateLogLevelTo(verbose: Boolean) {
        if (logVerbose == verbose && logLevelLaidOut) {
            // 已在该档位，仅刷新描述（防御）
            updateLogLevelDesc()
            return
        }
        logVerbose = verbose
        val thumb = logLevelThumb ?: return
        val minimal = logLevelMinimalPill ?: return
        val complete = logLevelCompletePill ?: return
        val gray = getColor(R.color.colorTextGray)
        val onPrimary = skinEmphasisTextColor

        // 目标 X 偏移：按容器宽度的一半计算（滑块已收缩为容器半宽，选中「完整」时右移容器半宽）。
        // 注意不能用 thumb.width/2：滑块收缩后自身宽度已是容器一半，再除 2 会只滑到 1/4 处（卡在中间）。
        val containerWidth = (thumb.parent as? View)?.width ?: 0
        val targetX = if (verbose) containerWidth / 2f else 0f

        // 1. 滑块平滑滑动（emphasized decelerate，Material You 标准）
        thumb.animate().cancel()
        thumb.animate()
            .translationX(targetX)
            .setDuration(260L)
            .setInterpolator(emphasizedDecelerate)
            .start()

        // 2. 文字颜色随进度渐变（精简 pill：onPrimary↔gray；完整 pill：gray↔onPrimary）
        val fromMinimalColor = minimal.currentTextColor
        val toMinimalColor = if (verbose) gray else onPrimary
        val fromCompleteColor = complete.currentTextColor
        val toCompleteColor = if (verbose) onPrimary else gray

        logLevelColorAnimator?.cancel()
        logLevelColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = emphasizedDecelerate
            addUpdateListener { a ->
                val f = a.animatedFraction
                minimal.textColor = argbLerp(fromMinimalColor, toMinimalColor, f)
                complete.textColor = argbLerp(fromCompleteColor, toCompleteColor, f)
            }
            start()
        }

        // 字重：选中项 BOLD（切换即可，无需逐帧）
        minimal.typeface = if (!verbose) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        complete.typeface = if (verbose) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT

        // 3. 描述联动更新
        updateLogLevelDesc()
    }

    /** 在两个 ARGB 颜色间按进度插值 */
    private fun argbLerp(from: Int, to: Int, frac: Float): Int {
        val f = frac.coerceIn(0f, 1f)
        val a = ((from shr 24 and 0xFF) + ((to shr 24 and 0xFF) - (from shr 24 and 0xFF)) * f).toInt()
        val r = ((from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * f).toInt()
        val g = ((from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * f).toInt()
        val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 更新档位描述文本（跟随当前 logVerbose） */
    private fun updateLogLevelDesc() {
        val desc = logLevelDesc ?: return
        desc.text = getString(if (logVerbose) R.string.log_level_complete_desc else R.string.log_level_minimal_desc)
    }

    /** 滑块首次定位：布局完成后按当前档位对齐滑块（不带动画），并把滑块宽度收缩为容器一半 */
    private fun positionLogLevelThumb() {
        val thumb = logLevelThumb ?: return
        val container = thumb.parent as? View ?: return
        if (container.width <= 0 || thumb.width <= 0) return
        logLevelLaidOut = true
        // 滑块宽度 = 容器一半
        val half = container.width / 2
        if (thumb.width != half) {
            thumb.layoutParams = thumb.layoutParams.apply { width = half }
            thumb.requestLayout()
            // 布局参数更新后，下一帧再定位
            thumb.post { positionLogLevelThumb() }
            return
        }
        val targetX = if (logVerbose) half.toFloat() else 0f
        thumb.translationX = targetX
    }

    internal fun currentNoRootDisplayState(): NoRootDisplayState {
        val appContext = applicationContext
        return NoRootSupportState.displayState(
            sdkInt = AndroidVersion.code,
            status = NoRootSupportStore.readStatus(appContext),
            currentSnapshot = NoRootSupportStore.readSnapshot(appContext),
            currentTargetVersionCode = installedBilibiliVersionCode(),
            currentTargetUpdateTime = installedBilibiliLastUpdateTime()
        )
    }

    /** 查询失败时返回 0；状态归并据此拒绝把旧宿主 heartbeat 视为当前激活。 */
    private fun installedBilibiliVersionCode(): Long = runCatching {
        packageManager.getPackageInfo(NoRootSupportState.TARGET_PACKAGE, 0)
            .versionCodeCompat
    }.getOrDefault(0L)

    private fun installedBilibiliLastUpdateTime(): Long = runCatching {
        packageManager.getPackageInfo(NoRootSupportState.TARGET_PACKAGE, 0).lastUpdateTime
    }.getOrDefault(0L)

    @StringRes
    private fun noRootStatusText(state: NoRootDisplayState): Int = when (state) {
        NoRootDisplayState.UNSUPPORTED_OS -> R.string.no_root_status_unsupported_os
        NoRootDisplayState.DISABLED -> R.string.no_root_status_disabled
        NoRootDisplayState.CHECKING -> R.string.no_root_status_checking
        NoRootDisplayState.MANAGER_MISSING -> R.string.no_root_status_manager_missing
        NoRootDisplayState.MODULE_NOT_REGISTERED -> R.string.no_root_status_module_not_registered
        NoRootDisplayState.SYNCING -> R.string.no_root_status_syncing
        NoRootDisplayState.RESTART_REQUIRED -> R.string.no_root_status_restart_required
        NoRootDisplayState.DISABLE_RESTART_REQUIRED ->
            R.string.no_root_status_disable_restart_required
        NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            R.string.no_root_status_disable_restart_required
        NoRootDisplayState.ACTIVE -> R.string.no_root_status_active
        NoRootDisplayState.CONNECTION_TIMEOUT -> R.string.no_root_status_connection_timeout
        NoRootDisplayState.ERROR -> R.string.no_root_status_error
    }

    /** 刷新免 Root 状态；激活卡片使用同一份已校验状态快照单独归并。 */
    private fun renderNoRootUi() {
        val state = currentNoRootDisplayState()
        noRootDesiredEnabled = NoRootSupportStore.isDesiredEnabled(applicationContext)
        noRootProgrammaticSwitch = true
        noRootSwitch?.apply {
            isChecked = noRootDesiredEnabled
            isEnabled = noRootDesiredEnabled ||
                (
                    AndroidVersion.isAtLeast(AndroidVersion.P) &&
                        noRootPrefsBridge != null
                    )
        }
        noRootProgrammaticSwitch = false
        noRootStatusView?.setText(noRootStatusText(state))

        renderActivationUi(noRootState = state)
    }

    /**
     * 单快照渲染激活卡片，避免 Binder 在背景、图标、标题分别读取状态时到达而出现混合 UI。
     */
    private fun renderActivationUi(
        framework: ModernFrameworkStatus = RemoteHookConfigStore.status(),
        noRootState: NoRootDisplayState = currentNoRootDisplayState()
    ) {
        val lspatchReceipt = lspatchActivationReceiptTracker.receiptFor(
            framework.connectionId
        )
        val lspatchHostState = NoRootSupportState.lspatchHostReceiptState(
            configState = lspatchReceipt?.bootstrap?.configState,
            installChainState = lspatchReceipt?.bootstrap?.installChainState
        )
        val displayState = NoRootSupportState.activationDisplayState(
            rootActive = framework.capable,
            frameworkCheckPending = frameworkStatusCheckPending && !framework.connected,
            displayState = noRootState,
            lspatchFramework = framework.isLspatch,
            lspatchHostState = lspatchHostState
        )
        val activated = displayState == ActivationDisplayState.ACTIVE_LSPOSED ||
            displayState == ActivationDisplayState.ACTIVE_LSPATCH ||
            displayState == ActivationDisplayState.ACTIVE_NPATCH
        val liquidCard = isLiquidSkinEffective
        val darkTheme = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accentColor = DiagnosticStatusPalette.color(
            ActivationCardVisualSpec.tone(displayState),
            darkTheme
        )
        activationCardView?.apply {
            background = if (liquidCard) {
                skinCardBackground(
                    monetColors.surfaceVariant,
                    ActivationCardVisualSpec.CORNER_RADIUS_DP
                )
            } else {
                roundedColor(if (activated) monetColors.primary else monetColors.surfaceVariant)
            }
            foreground = if (liquidCard) {
                ActivationCardAccentDrawable(accentColor, resources.displayMetrics.density)
            } else null
        }
        val activationContentColor = getColor(
            if (liquidCard) R.color.colorTextGray else R.color.white
        )
        activationIconView?.apply {
            setImageResource(if (activated) R.mipmap.ic_success else R.mipmap.ic_warn)
            imageTintList = ColorStateList.valueOf(
                if (liquidCard) accentColor else activationContentColor
            )
        }
        activationTitleView?.textColor = activationContentColor
        activationSourceView?.textColor = activationContentColor
        activationVersionView?.textColor = activationContentColor
        activationTitleView?.setText(
            when (displayState) {
                ActivationDisplayState.CHECKING -> R.string.module_activation_checking
                ActivationDisplayState.ACTIVE_LSPOSED,
                ActivationDisplayState.ACTIVE_LSPATCH,
                ActivationDisplayState.ACTIVE_NPATCH -> R.string.module_is_activated
                ActivationDisplayState.LSPATCH_WAITING_FOR_HOST ->
                    R.string.module_activation_lspatch_waiting_host_title
                ActivationDisplayState.LSPATCH_HOST_FAILED ->
                    R.string.module_activation_lspatch_host_failed_title
                ActivationDisplayState.UNAVAILABLE -> R.string.module_activation_not_detected
            }
        )
        // 只有真的要展示多用户提示时才放开换行；判定来自状态而不是文案长度，避免翻译一改
        // 就悄悄退回单行省略。
        val showsSecondaryUserText = displayState == ActivationDisplayState.UNAVAILABLE &&
            !framework.connected &&
            moduleUserSpace.possibleSecondaryOrCloneProfile
        val showsUserSpaceHint = showsSecondaryUserText || moduleUserSpace.sameUser == false
        activationSourceView?.apply {
            val baseText = when (displayState) {
                ActivationDisplayState.ACTIVE_LSPOSED,
                ActivationDisplayState.ACTIVE_LSPATCH -> if (framework.apiVersion > 0) getString(
                    R.string.activated_by,
                    framework.name,
                    framework.apiVersion
                ) else getString(
                    R.string.activated_by_noapi,
                    framework.name
                )
                ActivationDisplayState.ACTIVE_NPATCH ->
                    getString(R.string.no_root_activated_by_npatch)
                ActivationDisplayState.LSPATCH_WAITING_FOR_HOST ->
                    getString(R.string.module_activation_lspatch_waiting_host)
                ActivationDisplayState.LSPATCH_HOST_FAILED ->
                    getString(R.string.module_activation_lspatch_host_failed)
                ActivationDisplayState.CHECKING ->
                    getString(R.string.module_activation_waiting_framework)
                ActivationDisplayState.UNAVAILABLE -> when {
                    framework.connected ->
                        getString(R.string.module_activation_framework_unsupported)
                    // 分身/工作资料用户里"收不到服务"几乎总是"该用户下没启用模块"，而不是
                    // "框架没装"。旧文案把两者压成同一句，用户无从判断该去哪里改。
                    moduleUserSpace.possibleSecondaryOrCloneProfile -> getString(
                        R.string.module_activation_service_unavailable_secondary_user,
                        moduleUserSpace.moduleUserId
                    )
                    else -> getString(R.string.module_activation_service_unavailable)
                }
            }
            val mismatchText = moduleUserSpace.targetUserId
                ?.takeIf { moduleUserSpace.sameUser == false }
                ?.let { targetUserId ->
                    getString(
                        R.string.module_activation_user_space_mismatch,
                        moduleUserSpace.moduleUserId,
                        targetUserId
                    )
                }
            text = listOfNotNull(baseText, mismatchText).joinToString(separator = "\n")
            val showsLspatchAction = displayState == ActivationDisplayState.LSPATCH_WAITING_FOR_HOST ||
                displayState == ActivationDisplayState.LSPATCH_HOST_FAILED
            // 多用户和 LSPatch 操作提示都必须完整可见；正常状态仍保持单行省略。
            if (showsUserSpaceHint || showsLspatchAction) {
                isSingleLine = false
                maxLines = Int.MAX_VALUE
            } else {
                isSingleLine = true
                maxLines = 1
            }
            isVisible = true
        }
        diagnosticsSummaryView?.apply {
            val publishState = RemoteHookConfigStore.diagnostics().state
            val skinFallback = currentSkinDiagnostics()?.fallbackReason != null
            val noRootNeedsAttention = when (noRootState) {
                NoRootDisplayState.MANAGER_MISSING,
                NoRootDisplayState.MODULE_NOT_REGISTERED,
                NoRootDisplayState.RESTART_REQUIRED,
                NoRootDisplayState.DISABLE_RESTART_REQUIRED,
                NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE,
                NoRootDisplayState.CONNECTION_TIMEOUT,
                NoRootDisplayState.ERROR -> true
                else -> false
            }
            val (statusRes, statusTone) = when (
                ActivationCardVisualSpec.diagnosticsSummaryState(
                    displayState = displayState,
                    publishFailed = publishState == RemoteHookConfigPublishState.FAILED,
                    skinFallback = skinFallback,
                    noRootNeedsAttention = noRootNeedsAttention
                )
            ) {
                ActivationSummaryState.ACTION_REQUIRED ->
                    R.string.diagnostics_entry_action_required to
                        DiagnosticStatusTone.ACTION_REQUIRED
                ActivationSummaryState.ATTENTION ->
                    R.string.diagnostics_entry_attention to DiagnosticStatusTone.ATTENTION
                ActivationSummaryState.INFO ->
                    R.string.diagnostics_entry_checking to DiagnosticStatusTone.INFO
                ActivationSummaryState.READY ->
                    R.string.diagnostics_entry_ready to DiagnosticStatusTone.OK
            }
            setText(statusRes)
            alpha = 1f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                DiagnosticStatusPalette.color(
                    statusTone,
                    darkTheme = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                )
            )
            diagnosticsEntryView?.contentDescription = buildString {
                append(getString(R.string.diagnostics_title))
                append(". ")
                append(getString(statusRes))
            }
        }
    }

    /**
     * LSPatch 服务到达只说明 companion 可以发布配置。主页每次进入前台或服务连接改变时最多
     * 发起一次有界查询；有效回执按 connectionId 绑定，服务重连、页面离开和迟到回调都不能
     * 复用旧的“已激活”证据。
     */
    private fun requestLspatchHostReceipt(framework: ModernFrameworkStatus) {
        if (!userTermsDecision.isAuthorized ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            lspatchActivationReceiptTracker.endSession()
            return
        }
        if (!framework.isLspatch || !framework.capable) {
            lspatchActivationReceiptTracker.clearConnectionEvidence()
            return
        }
        val request = lspatchActivationReceiptTracker.begin(framework.connectionId) ?: return
        HostRuntimeDiagnosticsQueryClient.query(applicationContext) { result ->
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@query
            val current = RemoteHookConfigStore.status()
            if (!current.isLspatch || !current.capable) {
                lspatchActivationReceiptTracker.clearConnectionEvidence()
                renderActivationUi(current)
                return@query
            }
            val receipt = result.snapshot.takeIf {
                result.status == HostRuntimeDiagnosticsQueryClient.Status.READY
            }
            if (!lspatchActivationReceiptTracker.accept(
                    request = request,
                    currentConnectionId = current.connectionId,
                    value = receipt
                )
            ) return@query
            renderActivationUi(current)
        }
    }

    /**
     * 用户开启后才在后台构造快照并连接 NPatch；WeakReference 避免异步回调延长
     * Activity 生命周期。关闭分支不调用此方法，因此不会触发任何 NPatch 连接。
     */
    private fun enableAndSynchronizeNoRootSupport() {
        val bridge = noRootPrefsBridge ?: run {
            renderNoRootUi()
            return
        }
        if (AndroidVersion.isLessThan(AndroidVersion.P)) {
            renderNoRootUi()
            return
        }
        noRootStatusView?.setText(R.string.no_root_status_checking)
        val appContext = applicationContext
        if (!NoRootSupportController.setDesiredEnabled(appContext, enabled = true)) {
            renderNoRootUi()
            toast(getString(R.string.no_root_enable_failed))
            return
        }
        val generation = NoRootSupportController.beginSynchronization(appContext) ?: run {
            renderNoRootUi()
            return
        }
        val activityRef = WeakReference(this)
        Thread({
            NoRootSupportController.synchronize(appContext, bridge, generation) {
                val activity = activityRef.get() ?: return@synchronize
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.renderNoRootUi()
                }
            }
        }, "InnocentLab-NoRootSync").apply { isDaemon = true }.start()
    }

    internal fun synchronizeNoRootSupportIfEnabled() {
        if (AndroidVersion.isLessThan(AndroidVersion.P) ||
            !NoRootSupportStore.isDesiredEnabled(applicationContext)
        ) return
        val bridge = noRootPrefsBridge ?: return
        noRootStatusView?.setText(R.string.no_root_status_checking)
        val appContext = applicationContext
        val generation = NoRootSupportController.beginSynchronization(appContext) ?: return
        val activityRef = WeakReference(this)
        Thread({
            NoRootSupportController.synchronize(appContext, bridge, generation) {
                val activity = activityRef.get() ?: return@synchronize
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.renderNoRootUi()
                }
            }
        }, "InnocentLab-NoRootRefresh").apply { isDaemon = true }.start()
    }

    private fun disableNoRootSupport() {
        val accepted = NoRootSupportController.setDesiredEnabled(
            applicationContext,
            enabled = false
        )
        if (!accepted) toast(getString(R.string.no_root_disable_failed))
        renderNoRootUi()
    }

    /** 弹窗退场动画（scale 缩小 + fade out），结束后 dismiss 并回调 */
    /**
     * 已装上锚点动画的弹窗的收起入口。
     *
     * 全仓有 72 处直接调用 [dismissWithAnimation]（关闭按钮、各行点击后关闭等），它们本来会绕过
     * 锚点动画去跑旧的 0.92 缩放淡出——入场是气泡/形变、退场是另一套，观感就是"没有退出动画"。
     * 在这里登记后，那 72 处**一处不改**也全部走对应的收起动画。
     *
     * 签名是 `(interactiveCommit, afterClose) -> handled`：`afterClose` 为空时用弹窗自己的
     * `onBackDismiss`，非空时用调用点传进来的那个（例如"关闭后打开链接"）。
     */
    private val dialogAnchoredClosers =
        java.util.WeakHashMap<Dialog, (Boolean, (() -> Unit)?) -> Boolean>()

    // internal：弹窗正按主题外移到同包的 Dialogs 文件（`internal fun MainActivity.showX()`），
    // 扩展函数拿不到 private 成员。下面几个 create*/present*/dismiss* 是外移弹窗的共用底座。
    internal fun dismissWithAnimation(
        dialog: Dialog,
        container: View,
        onDismissed: () -> Unit
    ) {
        if (dialogAnchoredClosers[dialog]?.invoke(false, onDismissed) == true) return
        container.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(180L)
            .setInterpolator(emphasizedAccelerate)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dialog.dismiss()
                    onDismissed()
                }
            })
            .start()
    }

    /**
     * 自绘点击涟漪背景（统一入口，不解析主题属性 selectableItemBackground*）。
     * 部分客户设备装有全局主题模块（如 Monet-All），会替换/劫持主题属性解析——
     * 若返回的 drawable 为不透明实心色，会盖住宿主内容（表现为「只剩空位但可点击」）。
     * 自绘 RippleDrawable（透明 content + 圆角 mask）视觉与系统 ripple 一致，
     * 且不受任何第三方主题/资源 hook 影响。
     *
     * @param cornerRadiusDp 涟漪 mask 圆角：行级条目用小圆角，圆形图标按钮传半径（宽高一半）
     */
    internal fun selfRippleBackground(cornerRadiusDp: Float = 10f): RippleDrawable {
        val density = resources.displayMetrics.density
        val mask = GradientDrawable().apply {
            cornerRadius = cornerRadiusDp * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x30)),
            ColorDrawable(Color.TRANSPARENT),
            mask
        )
    }

    internal fun renderPendingTermsUi(snapshot: UserTermsAuthorizationSnapshot) {
        if (!snapshot.consentState.isAcceptancePending) return
        val status = RemoteHookConfigStore.status()
        val noRootDesired = NoRootSupportStore.isDesiredEnabled(applicationContext)
        termsPendingStatusView?.text = if (noRootDesired) {
            getString(noRootStatusText(currentNoRootDisplayState()))
        } else {
            when {
                snapshot.failureCode == UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE ->
                    getString(R.string.user_terms_pending_local_failed)
                snapshot.syncState == UserTermsSyncState.SYNCING ->
                    getString(R.string.user_terms_pending_syncing)
                snapshot.syncState == UserTermsSyncState.WAITING_FOR_SERVICE ->
                    getString(R.string.user_terms_pending_waiting)
                snapshot.syncState == UserTermsSyncState.UNSUPPORTED ->
                    getString(
                        R.string.user_terms_pending_unsupported,
                        status.name.ifBlank { "Xposed" },
                        status.apiVersion
                    )
                snapshot.syncState == UserTermsSyncState.FAILED ->
                    getString(R.string.user_terms_pending_failed)
                else -> getString(R.string.user_terms_pending_syncing)
            }
        }
        if (noRootDesired) {
            termsManagerLauncher?.isVisible = false
        } else {
            updateTermsManagerLauncher(status)
        }
        renderTermsGateDiagnostics()
    }

    internal fun renderTermsGateDiagnostics() {
        val valueView = termsDiagnosticsValueView ?: return
        val diagnostics = UserTermsGateDiagnostics.capture(
            applicationContext,
            termsAuthorizationSnapshot
        )
        val profileLine = getString(
            if (diagnostics.possibleSecondaryOrCloneProfile) {
                R.string.user_terms_diagnostics_profile_secondary
            } else {
                R.string.user_terms_diagnostics_profile_primary
            }
        )
        val frameworkLine = if (diagnostics.frameworkConnected) {
            getString(
                R.string.user_terms_diagnostics_framework_connected,
                listOfNotNull(
                    diagnostics.frameworkName.ifBlank { "Xposed" },
                    diagnostics.frameworkVersion
                ).joinToString(" "),
                diagnostics.frameworkApiVersion
            )
        } else {
            getString(R.string.user_terms_diagnostics_framework_disconnected)
        }
        val remoteLine = getString(
            when {
                !diagnostics.frameworkConnected ->
                    R.string.user_terms_diagnostics_remote_unknown
                diagnostics.remoteCapabilityAvailable ->
                    R.string.user_terms_diagnostics_remote_available
                else -> R.string.user_terms_diagnostics_remote_unavailable
            }
        )
        val targetLine = if (diagnostics.targetPackageVisible) {
            val targetUserId = requireNotNull(diagnostics.targetUserId)
            val targetUid = requireNotNull(diagnostics.targetUid)
            getString(
                R.string.user_terms_diagnostics_target_visible,
                targetUserId,
                targetUid
            )
        } else {
            getString(R.string.user_terms_diagnostics_target_not_visible)
        }
        val sameUserLine = getString(
            when (diagnostics.sameAndroidUser) {
                true -> R.string.user_terms_diagnostics_same_user_yes
                false -> R.string.user_terms_diagnostics_same_user_no
                null -> R.string.user_terms_diagnostics_same_user_unknown
            }
        )
        valueView.text = (listOf(
            getString(
                R.string.user_terms_diagnostics_module_identity,
                diagnostics.moduleUserId,
                diagnostics.moduleUid
            ),
            profileLine,
            frameworkLine,
            remoteLine,
            targetLine,
            sameUserLine,
            getString(
                R.string.user_terms_diagnostics_failure,
                diagnostics.failureCode
                    ?: getString(R.string.user_terms_diagnostics_failure_none)
            )
        ) + frameworkDiagnosticsDetails(
            this, diagnostics.frameworkName, diagnostics.frameworkVersion,
            diagnostics.frameworkVersionCode, diagnostics.frameworkProperties,
            diagnostics.frameworkConnectionId, diagnostics.frameworkFailureCode
        )).joinToString("\n")
    }

    internal fun updateTermsManagerLauncher(status: ModernFrameworkStatus) {
        val launcher = termsManagerLauncher ?: return
        launcher.isVisible = FrameworkManagerLauncher.resolve(
            applicationContext,
            status
        ) != null && (!status.connected || !status.capable)
    }

    internal fun createTermsNeutralRoot(): NativeFrameLayout = NativeFrameLayout(this).apply {
        setBackgroundColor(monetColors.background)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    internal fun createTermsActionButton(
        text: CharSequence,
        filled: Boolean,
        onClick: () -> Unit
    ): NativeTextView {
        val density = resources.displayMetrics.density
        return NativeTextView(this).apply {
            this.text = text
            textColor = if (filled) monetColors.onPrimary else getColor(R.color.colorTextGray)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt()
            )
            background = if (filled) {
                val radius = 20 * density
                val content = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(monetColors.primary)
                }
                val mask = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                    ),
                    content,
                    mask
                )
            } else {
                selfRippleBackground(14f)
            }
            skinActionButton(this, filled, if (filled) 20f else 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** AppCompat 的显式应用语言为空时表示跟随系统，不额外维护一份语言偏好。 */
    internal fun currentAppLanguage(): AppLanguage {
        val locale = AppCompatDelegate.getApplicationLocales()[0]
            ?: return AppLanguage.SYSTEM
        if (locale.language.equals("en", ignoreCase = true)) return AppLanguage.ENGLISH
        if (!locale.language.equals("zh", ignoreCase = true)) return AppLanguage.SYSTEM

        val isTraditional = locale.script.equals("Hant", ignoreCase = true) ||
            locale.country.equals("TW", ignoreCase = true) ||
            locale.country.equals("HK", ignoreCase = true) ||
            locale.country.equals("MO", ignoreCase = true)
        return if (isTraditional) {
            AppLanguage.TRADITIONAL_CHINESE
        } else {
            AppLanguage.SIMPLIFIED_CHINESE
        }
    }

    private fun currentAppLanguageSummary(): String {
        val language = currentAppLanguage()
        return getString(R.string.app_language_current, getString(language.labelRes))
    }

    /** 实验性功能区显示实际请求的皮肤；Liquid 同时公开当前降级后端。 */
    private fun currentSkinSummary(): String {
        if (!isLiquidSkinRequested) return getString(R.string.skin_current_material_you)
        val backendLabel = liquidBackendLabelRes(liquidBackendName)?.let { getString(it) }
            ?: getString(R.string.skin_backend_initializing)
        return getString(R.string.skin_current_liquid, backendLabel)
    }

    private fun currentLiquidBackgroundSummary(): String {
        val state = LiquidBackgroundStore.read(applicationContext)
        if (state.config.mode == LiquidBackgroundMode.AUTOMATIC) {
            return getString(R.string.liquid_background_summary_automatic)
        }
        if (!state.assetPresent) {
            return getString(R.string.liquid_background_summary_unavailable)
        }
        return getString(
            if (isLiquidSkinRequested) R.string.liquid_background_summary_active
            else R.string.liquid_background_summary_saved
        )
    }

    internal fun isLiquidRealtimeCaptureSupported(): Boolean = AndroidVersion.code >= 31

    private fun importLiquidBackground(uri: Uri) {
        if (liquidBackgroundImportInProgress) return
        liquidBackgroundImportInProgress = true
        liquidBackgroundDialog?.setCancelable(false)
        toast(getString(R.string.liquid_background_processing))
        liquidBackgroundTask = liquidBackgroundWorker.submit {
            val result = LiquidBackgroundStore.importFromUri(applicationContext, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                liquidBackgroundImportInProgress = false
                liquidBackgroundDialog?.setCancelable(true)
                when (result) {
                    is LiquidBackgroundImportResult.Success -> {
                        toast(getString(R.string.liquid_background_import_success))
                        finishLiquidBackgroundChange()
                    }
                    is LiquidBackgroundImportResult.Failure -> toast(
                        getString(liquidBackgroundFailureText(result.reason))
                    )
                }
            }
        }
    }

    internal fun finishLiquidBackgroundChange() {
        liquidBackgroundSummaryView?.text = currentLiquidBackgroundSummary()
        val dialog = liquidBackgroundDialog
        val container = liquidBackgroundDialogContainer
        if (dialog != null && container != null && dialog.isShowing) {
            dismissWithAnimation(dialog, container) {
                if (!isFinishing && !isDestroyed) recreate()
            }
        } else if (!isFinishing && !isDestroyed) recreate()
    }

    @StringRes
    private fun liquidBackgroundFailureText(reason: LiquidBackgroundImportFailure): Int =
        when (reason) {
            LiquidBackgroundImportFailure.READ_FAILED -> R.string.liquid_background_read_failed
            LiquidBackgroundImportFailure.FILE_TOO_LARGE -> R.string.liquid_background_file_too_large
            LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE -> R.string.liquid_background_unsupported
            LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE ->
                R.string.liquid_background_dimensions_too_large
            LiquidBackgroundImportFailure.ENCODE_FAILED -> R.string.liquid_background_encode_failed
            LiquidBackgroundImportFailure.STORAGE_FAILED -> R.string.liquid_background_storage_failed
        }

    @StringRes
    private fun liquidBackendLabelRes(backendName: String?): Int? = when (backendName) {
        "REFRACTION" -> R.string.skin_backend_refraction
        "BLUR" -> R.string.skin_backend_blur
        "TRANSLUCENT" -> R.string.skin_backend_translucent
        else -> null
    }

    /** renderer 已完成核心侧回退后，当前 Activity 只负责提示并重建 Material 界面。 */
    private fun handleSkinRendererFailure() {
        runOnUiThread {
            if (skinFailureHandled || isFinishing || isDestroyed) return@runOnUiThread
            skinFailureHandled = true
            if (SkinRepository.resolveRequestedSkin(applicationContext) == SkinId.MATERIAL_YOU) {
                toast(getString(R.string.skin_start_failed))
                recreate()
            } else {
                toast(getString(R.string.skin_recovery_save_failed))
                skinSummaryView?.text = currentSkinSummary()
            }
        }
    }

    /** 右上角 GitHub 图标的二级菜单。 */
    private fun scheduleReleaseHighlights() {
        updateUiHandler.removeCallbacks(showHighlightsWhenIdle)
        if (highlightsDisposed || isFinishing || isDestroyed ||
            highlightNavigationInFlight || (pendingHighlightsFrom == null && pendingHighlightDestination == null) || !updateUiResumed) return
        val root = settingsSearchRoot ?: return
        if (!root.isLaidOut) {
            if (!highlightsLayoutPending) {
                highlightsLayoutPending = true
                root.doOnLayout { highlightsLayoutPending = false; scheduleReleaseHighlights() }
            }
            return
        }
        if (activeConfirmDialog?.isShowing == true || !hasWindowFocus()) return
        updateUiHandler.postDelayed(showHighlightsWhenIdle, 450L)
    }

    internal fun revealHighlightDestination(settingId: String) {
        if (!userTermsDecision.isAuthorized || isFinishing || isDestroyed || highlightsDisposed) return
        val destination = ReleaseHighlightsCatalog.destinations.singleOrNull { it.settingId == settingId }
            ?: return
        if (!updateUiResumed) {
            pendingHighlightDestination = settingId
            return
        }
        val target = collectSettingsSearchTargets().singleOrNull { settingId in it.settingIds }
        if (target == null || settingsDestinations.resolve(settingId) { it === target.view && it.isAttachedToWindow } == null) {
            pendingHighlightDestination = null
            toast(getString(R.string.highlights_unavailable))
            return
        }
        pendingHighlightDestination = settingId
        highlightNavigationInFlight = true
        val generation = ++highlightNavigationGeneration
        revealSettingsSearchTarget(target) {
            if (generation != highlightNavigationGeneration || highlightsDisposed) return@revealSettingsSearchTarget
            highlightNavigationInFlight = false
            if (!updateUiResumed || activeConfirmDialog?.isShowing == true || !hasWindowFocus()) {
                scheduleReleaseHighlights()
                return@revealSettingsSearchTarget
            }
            pendingHighlightDestination = null
            if (destination.homeFilterOption) showHomeRecommendFilterDialog(SettingsCatalog.byId.getValue(settingId).storageKey)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleReleaseHighlights()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (releaseHighlightsDialog?.isShowing == true) activeHighlightsFrom?.let {
            outState.putInt("highlights_from",it)
            outState.putBoolean("highlights_automatic",activeHighlightsAutomatic)
        }
        pendingHighlightDestination?.let { outState.putString("highlights_destination",it) }
        super.onSaveInstanceState(outState)
    }

    internal fun createGitHubMenuRow(
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        onClick: () -> Unit
    ): NativeLinearLayout = createGitHubMenuRow(
        title = getString(titleRes),
        subtitle = getString(subtitleRes),
        highlight = false,
        onClick = onClick
    )

    internal fun createGitHubMenuRow(
        title: CharSequence,
        subtitle: CharSequence,
        highlight: Boolean,
        onClick: () -> Unit
    ): NativeLinearLayout {
        val density = resources.displayMetrics.density
        return NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (13 * density).toInt(),
                (16 * density).toInt(),
                (13 * density).toInt()
            )
            background = selfRippleBackground(14f)
            if (highlight) skinSelectionControl(this, 14f, selected = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(
                NativeTextView(this@MainActivity).apply {
                    text = title
                    textColor = if (highlight) monetColors.primary
                    else getColor(R.color.colorTextGray)
                    textSize = 16f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                NativeTextView(this@MainActivity).apply {
                    text = subtitle
                    textColor = getColor(R.color.colorTextDark)
                    textSize = 12f
                    alpha = 0.72f
                    setLineSpacing(3 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            )
        }
    }

    /** 遥测范围变更需用户单独确认，不随详情页入口点击自动授权。 */
    internal var telemetryDisclosurePrompted = false

    internal fun createTelemetryMenuRow(
        dialog: Dialog,
        dialogContainer: NativeLinearLayout,
        showControl: Boolean = false
    ): NativeLinearLayout {
        val density = resources.displayMetrics.density
        val initialEnabled = TelemetryStore.displayState(applicationContext).enabled
        val summary = NativeTextView(this).apply {
            text = getString(
                if (initialEnabled) R.string.telemetry_enabled_summary
                else R.string.telemetry_disabled_summary
            )
            textColor = getColor(R.color.colorTextDark)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(3 * density, 1f)
            // 为两种状态预留同一文本高度，换行差异不再改变弹窗整体高度。
            addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left == oldRight - oldLeft && minimumHeight > 0) return@addOnLayoutChangeListener
                val availableWidth = view.width - compoundPaddingLeft - compoundPaddingRight
                if (availableWidth > 0) {
                    minimumHeight = listOf(R.string.telemetry_enabled_summary, R.string.telemetry_disabled_summary)
                        .maxOf { res ->
                            val label = getString(res)
                            android.text.StaticLayout.Builder.obtain(label, 0, label.length, paint, availableWidth)
                                .setIncludePad(includeFontPadding)
                                .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier).build().height
                        } + compoundPaddingTop + compoundPaddingBottom
                }
            }
        }
        val textColumn = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            addView(
                NativeTextView(this@MainActivity).apply {
                    text = getString(R.string.telemetry_title)
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 16f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                summary,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            )
        }
        val infoButton = NativeTextView(this).apply {
            text = "ⓘ"
            contentDescription = getString(R.string.telemetry_info_button)
            textColor = monetColors.primary
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt())
            background = selfRippleBackground(20f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, dialogContainer) {
                    showTelemetryInfoDialog()
                }
            }
        }
        // GitHub 二级页只导航，不创建开关，也不因整行点击改变遥测选择。
        if (!showControl) {
            return NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (11 * density).toInt(),
                    (10 * density).toInt(), (11 * density).toInt())
                isClickable = false
                isFocusable = false
                addView(textColumn, NativeLinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(infoButton, NativeLinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                ).apply { marginStart = (6 * density).toInt() })
            }
        }
        var programmaticChange = false
        val telemetrySwitch =
            com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch(this, null).apply {
                text = ""
                contentDescription = getString(R.string.telemetry_title)
                isChecked = initialEnabled
                setOnCheckedChangeListener { _, enabled ->
                    if (programmaticChange) return@setOnCheckedChangeListener
                    if (enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)) {
                        programmaticChange = true
                        isChecked = false
                        programmaticChange = false
                        dismissWithAnimation(dialog, dialogContainer) { showTelemetryDisclosureDialog() }
                        return@setOnCheckedChangeListener
                    }
                    val saved = TelemetryStore.writeConsentChoice(applicationContext, enabled)
                    if (!saved) {
                        programmaticChange = true
                        isChecked = !enabled
                        programmaticChange = false
                        toast(getString(R.string.telemetry_choice_save_failed))
                        return@setOnCheckedChangeListener
                    }
                    animateTelemetrySummary(summary, getString(
                        if (enabled) R.string.telemetry_enabled_summary
                        else R.string.telemetry_disabled_summary
                    ))
                    if (enabled) TelemetryCoordinator.maybeUpload(applicationContext)
                }
            }

        return NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(),
                (11 * density).toInt(),
                (10 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { telemetrySwitch.toggle() }
            addView(
                textColumn,
                NativeLinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(
                telemetrySwitch,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (6 * density).toInt() }
            )
        }
    }

    /** 复用气泡亮暗说明的 120ms 淡出 / 180ms 淡入节奏，快速切换只保留最新文案。 */
    private fun animateTelemetrySummary(view: NativeTextView, text: String) {
        view.animate().withEndAction(null).cancel()
        if (!view.isAttachedToWindow) { view.text = text; view.alpha = 0.72f; return }
        if (view.text.toString() == text) {
            view.animate().alpha(0.72f).setDuration(180L).setInterpolator(emphasizedDecelerate).start()
            return
        }
        view.animate().alpha(0f).setDuration(120L).setInterpolator(emphasizedAccelerate)
            .withEndAction {
                view.text = text
                view.animate().alpha(0.72f).setDuration(180L)
                    .setInterpolator(emphasizedDecelerate).start()
            }.start()
    }

    internal fun playerQualityLabel(qn: Int): String = when (qn) {
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> getString(R.string.player_quality_1080p_high_bitrate)
        116 -> "1080P60"
        120 -> "4K"
        127 -> "8K"
        else -> getString(R.string.player_default_quality_follow_host)
    }

    private fun playerSpeedLabel(percent: Int): String = if (percent == PlayerSpeedConfig.FOLLOW_HOST) {
        getString(R.string.player_speed_follow_host)
    } else {
        getString(R.string.player_speed_multiplier, PlayerSpeedConfig.formatMultiplier(percent))
    }

    internal fun updatePlayerSpeedSummaries() {
        playerLongPressSpeedSummary?.text = if (playerDisableLongPress) {
            getString(R.string.player_long_press_speed_paused, playerSpeedLabel(playerLongPressSpeedPercent))
        } else playerSpeedLabel(playerLongPressSpeedPercent)
        playerDefaultSpeedSummary?.text = playerSpeedLabel(playerDefaultSpeedPercent)
    }

    /** 规则入口统一文案：标题在上、当前值在下；四个新过滤入口共用同一结构。 */
    private fun ruleEntryText(
        @StringRes titleRes: Int,
        @StringRes emptyRes: Int,
        @StringRes currentRes: Int,
        value: String
    ): String = getString(titleRes) + "\n" +
        if (value.isBlank()) getString(emptyRes) else getString(currentRes, value)

    /** 评论发布者规则入口文案；与评论关键词入口保持同一行结构。 */
    private fun commentUserFilterSummaryText(value: String): String =
        getString(R.string.comment_user_filter_rules) + "\n" +
            if (value.isBlank()) {
                getString(R.string.comment_user_filter_rules_empty)
            } else {
                getString(R.string.comment_user_filter_rules_current, value)
            }

    /** 读取当前更新渠道（未知/损坏值回退稳定版，兼容旧版本升级）。 */
    internal fun readUpdateChannel(
        prefs: android.content.SharedPreferences
    ): GitHubReleaseChecker.UpdateChannel =
        GitHubReleaseChecker.UpdateChannel.fromStorageValue(
            prefs.getString(UpdateChannelStore.KEY_CHANNEL, null)
        )

    /** 渠道对应的成功检查时间 key；稳定版优先新 key，缺失时迁移读取旧版本共用时间。 */
    private fun lastCheckKey(
        prefs: android.content.SharedPreferences,
        channel: GitHubReleaseChecker.UpdateChannel
    ): String = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE ->
            if (prefs.contains(PREF_LAST_CHECK_STABLE)) PREF_LAST_CHECK_STABLE
            else PREF_LAST_SUCCESSFUL_UPDATE_CHECK
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> PREF_LAST_CHECK_PREVIEW
    }

    private fun checkingToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_checking_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_checking_preview
    }

    private fun latestToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_latest_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_latest_preview
    }

    private fun failedToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_check_failed_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_check_failed_preview
    }

    /**
     * 按当前渠道检查更新。自动检查由进程级首次前台停留门禁触发；
     * 手动检查始终执行并反馈结果。切换渠道后会自动发起一次新渠道检查。
     */
    internal fun checkForUpdates(manual: Boolean) {
        val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        val channel = readUpdateChannel(updatePrefs)
        if (!updateUiResumed) return
        val request = UpdateCheckCoordinator.Request(channel, manual)
        val requestToStart = updateCheckCoordinator.submit(request)
        if (requestToStart == null) {
            if (manual) toast(getString(checkingToastRes(channel)))
            return
        }
        startUpdateCheck(requestToStart, updatePrefs)
    }

    /** 启动协调器已接受的请求；完成后会自动接续渠道切换期间排队的最后一次手动检查。 */
    private fun startUpdateCheck(
        request: UpdateCheckCoordinator.Request,
        updatePrefs: android.content.SharedPreferences
    ) {
        val channel = request.channel
        val sequence = ColdStartUpdateSession.state.beginRequest()
        if (request.manual) {
            toast(getString(checkingToastRes(channel)))
        }
        val activityRef = WeakReference(this)
        Thread({
            val result = runCatching { GitHubReleaseChecker.fetchLatestRelease(channel) }
            Handler(Looper.getMainLooper()).post {
                val selected = GitHubReleaseChecker.UpdateChannel.fromStorageValue(
                    updatePrefs.getString(UpdateChannelStore.KEY_CHANNEL, null)
                )
                result.onSuccess { release ->
                    if (ColdStartUpdateSession.state.accept(sequence, channel, selected, release, BuildConfig.VERSION_NAME)) {
                        ColdStartUpdateSession.notifyChanged()
                    }
                }
                val activity = activityRef.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                val selectedChannel = activity.readUpdateChannel(updatePrefs)
                val completion = activity.updateCheckCoordinator.complete(channel, selectedChannel)
                result.onSuccess {
                    updatePrefs.edit()
                        .putLong(activity.lastCheckKey(updatePrefs, channel), System.currentTimeMillis())
                        .apply()
                    // 网络已确认可用；遥测仍独立执行授权、单飞和 24 小时节流。
                    if (activity.updateUiResumed) TelemetryCoordinator.maybeUpload(activity.applicationContext)
                }
                if (completion.shouldDeliverResult && ColdStartUpdateSession.state.isCurrentRequest(sequence) && activity.updateUiResumed) {
                    result.fold(
                        onSuccess = { release ->
                            activity.handleReleaseCheckResult(channel, release, request.manual)
                        },
                        onFailure = { error ->
                            Log.w("BilibiliInnocentLab", "release check failed", error)
                            if (request.manual) {
                                activity.toast(activity.getString(activity.failedToastRes(channel)))
                            }
                        }
                    )
                } else {
                    result.exceptionOrNull()?.let { error ->
                        Log.w(
                            "BilibiliInnocentLab",
                            "stale release check failed for " + channel.storageValue,
                            error
                        )
                    }
                }
                completion.nextRequest?.let { next ->
                    if (activity.updateUiResumed) activity.startUpdateCheck(next, updatePrefs)
                    else activity.updateCheckCoordinator.complete(next.channel, selectedChannel)
                }
                activity.scheduleReleaseHighlights()
            }
        }, "github-release-check").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 按渠道处理检查结果的三态比较：
     * - 远端更高 → 自动检查展示气泡；手动检查弹更新窗（Alpha 带预发布标识）；
     * - 本地更高：稳定版渠道明确提示"本地高于最新稳定版"，避免误报"已是最新"
     *   或提示降级；预览渠道远端已是最高可选版本，按"无更新"处理；
     * - 相等 → 手动检查时提示该渠道已是最新。
     */
    private fun handleReleaseCheckResult(
        channel: GitHubReleaseChecker.UpdateChannel,
        release: GitHubReleaseChecker.ReleaseInfo,
        manual: Boolean
    ) {
        when (GitHubReleaseChecker.compareVersions(release.tagName, BuildConfig.VERSION_NAME)) {
            GitHubReleaseChecker.VersionRelation.REMOTE_NEWER -> {
                renderUpdateBadge()
                if (manual) showUpdateDialogWhenIdle(channel, release)
            }
            GitHubReleaseChecker.VersionRelation.LOCAL_NEWER -> {
                if (manual) {
                    val resId = if (channel == GitHubReleaseChecker.UpdateChannel.STABLE) {
                        R.string.update_local_ahead_stable
                    } else {
                        latestToastRes(channel)
                    }
                    toast(getString(resId))
                }
            }
            GitHubReleaseChecker.VersionRelation.EQUAL -> {
                if (manual) toast(getString(latestToastRes(channel)))
            }
            null -> {
                // 两侧标签应已被解析器保证合法；异常到达时按无更新处理，不打扰用户。
                if (manual) toast(getString(latestToastRes(channel)))
            }
        }
    }

    /** 弹窗卡片圆角；图标锚点形变的展开端半径必须与它一致，否则末帧会有一次圆角跳变。 */
    private val MODAL_CORNER_RADIUS_DP = 28f

    /** 正文起始位移上限（每轴）。够看出"从锚点方向飞来"，又不至于让长卡片整体晃动。 */
    private val CONTENT_TRAVEL_CAP_DP = 20f

    /** 气泡宽度、边距与小角尺寸。 */
    private val BUBBLE_WIDTH_DP = 320f
    private val BUBBLE_SIDE_MARGIN_DP = 12f
    private val BUBBLE_EDGE_MARGIN_DP = 16f
    private val BUBBLE_GAP_DP = 6f
    private val BUBBLE_TAIL_HEIGHT_DP = 9f
    private val BUBBLE_TAIL_HALF_WIDTH_DP = 11f

    /**
     * 锚点弹窗的两种形态。
     *
     * [CONTAINER]：来源是整行设置项，弹窗形变到屏幕中央的大卡片。
     * [BUBBLE]：来源是工具栏上的小图标，弹窗贴在图标旁边并伸出指向它的小角。
     */
    internal enum class AnchorStyle { CONTAINER, BUBBLE }

    /** 只使用当前可见的点击条目，不能拿整个可滚动父分组作为来源。 */
    private fun modalAnchorBounds(anchor: View): SettingsBackupMotionRect? {
        if (!anchor.isAttachedToWindow || !anchor.isShown) return null
        val visible = android.graphics.Rect()
        if (!anchor.getGlobalVisibleRect(visible) || visible.isEmpty) return null
        val sourceRoot = anchor.rootView
        if (sourceRoot.scaleX != 1f || sourceRoot.scaleY != 1f || sourceRoot.rotation != 0f ||
            sourceRoot.rotationX != 0f || sourceRoot.rotationY != 0f) return null
        // GlobalVisibleRect 属于 rootView 坐标；补上根窗口屏幕位置（含 adjustPan），
        // 后续才可以与 Dialog 的 getLocationOnScreen 相减。
        val rootLocation = IntArray(2)
        sourceRoot.getLocationOnScreen(rootLocation)
        visible.offset(rootLocation[0], rootLocation[1])
        return SettingsBackupMotionRect(visible.left.toFloat(), visible.top.toFloat(),
            visible.right.toFloat(), visible.bottom.toFloat()).takeIf { it.isValid }
    }

    /**
     * 把来源图标（屏幕坐标）与已完成布局的卡片换算成承载层内的形变几何。
     *
     * 卡片是承载层的直接 child，所以展开端直接用 `left/top/right/bottom`；折叠端要扣掉承载层
     * 自己的窗口原点——弹窗与设置页是两个 Window，不能假设两者原点相同。
     */
    private fun resolveIconAnchoredGeometry(
        layer: IconAnchoredMotionLayer,
        card: View,
        anchorOnScreen: SettingsBackupMotionRect,
        density: Float
    ): IconAnchoredMotionGeometry? {
        if (!layer.isAttachedToWindow || layer.width <= 0 || layer.height <= 0) return null
        if (card.width <= 0 || card.height <= 0) return null
        val layerLocation = IntArray(2)
        layer.getLocationOnScreen(layerLocation)
        val collapsed = SettingsBackupMotionRect(
            left = anchorOnScreen.left - layerLocation[0],
            top = anchorOnScreen.top - layerLocation[1],
            right = anchorOnScreen.right - layerLocation[0],
            bottom = anchorOnScreen.bottom - layerLocation[1]
        )
        val expanded = SettingsBackupMotionRect(
            left = card.left.toFloat(),
            top = card.top.toFloat(),
            right = card.right.toFloat(),
            bottom = card.bottom.toFloat()
        )
        // 折叠端圆角取短边一半：27dp 图标收成正圆；设置行这类扁矩形则收成胶囊，
        // 两种来源都不会出现"方角小块"。
        return IconAnchoredMotionGeometry(
            collapsedBounds = collapsed,
            expandedBounds = expanded,
            collapsedRadiusPx = minOf(collapsed.width, collapsed.height) / 2f,
            expandedRadiusPx = MODAL_CORNER_RADIUS_DP * density,
            contentTravelCapPx = CONTENT_TRAVEL_CAP_DP * density
        ).takeIf { it.isUsable }
    }

    internal fun createModalContainer(): NativeLinearLayout {
        val density = resources.displayMetrics.density
        return NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            minimumWidth = (292 * density).toInt()
            setPadding(
                (24 * density).toInt(),
                (26 * density).toInt(),
                (24 * density).toInt(),
                (18 * density).toInt()
            )
            background = skinModalBackground(monetColors.surface, MODAL_CORNER_RADIUS_DP)
            elevation = 12 * density
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0f
        }
    }

    // GestureBackNavigation 抑制说明：targetSdk 37 下手势导航的返回已由上方注册的
    // OnBackInvokedCallback（API 33+）接管并收敛到同一退场动画；此处的 KEYCODE_BACK
    // 拦截仅服务三键/硬件导航（它们仍派发按键事件），lint 的启发式检查不感知该
    // 双路径迁移，故定点抑制。
    internal fun presentModalDialog(
        dialog: Dialog,
        container: NativeLinearLayout,
        morphAnchor: View? = null,
        anchorStyle: AnchorStyle = AnchorStyle.CONTAINER,
        onExpanded: () -> Unit = {},
        onBackDismiss: () -> Unit = {}
    ) = presentSizedModalDialog(dialog, container, null, morphAnchor, anchorStyle, onExpanded, onBackDismiss)

    /**
     * @param morphAnchor 传入无文字的来源图标（如工具栏的搜索/GitHub 按钮）即启用图标锚点形变：
     *   弹窗表面从该图标的位置与圆角长成整张卡片。传 null 保持原有的居中缩放入场，**默认不变**，
     *   30 个既有调用点一个都不受影响。
     */
    // 气泡 margin 与锚点均为物理窗口坐标，LEFT 不可替换成会再镜像一次的 START。
    @Suppress("GestureBackNavigation", "RtlHardcoded")
    internal fun presentSizedModalDialog(
        dialog: Dialog,
        container: NativeLinearLayout,
        preferredWidth: Int?,
        morphAnchor: View? = null,
        anchorStyle: AnchorStyle = AnchorStyle.CONTAINER,
        onExpanded: () -> Unit = {},
        onBackDismiss: () -> Unit = {}
    ) {
        activeConfirmDialog?.dismiss()
        stylePreparedSkinControls(container)
        val density = resources.displayMetrics.density
        // 由 dismissWithAnimation 传进来的一次性收尾回调（例如"关闭后打开链接"）；
        // 为空时用弹窗自己的 onBackDismiss。每次弹窗独立一份，不能提到 Activity 字段上。
        val pendingAnchoredAfterClose =
            java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        // 来源矩形必须在点击那一刻取：形变开始后弹窗窗口会盖住图标，事后查位置不可靠。
        val anchorBounds = morphAnchor?.takeIf {
            it.isAttachedToWindow && it.width > 0 && it.height > 0 &&
                (anchorStyle == AnchorStyle.BUBBLE || ValueAnimator.areAnimatorsEnabled())
        }?.let(::modalAnchorBounds)
        // 工具栏上的 27dp 小图标飞到屏幕正中会显得莫名，改成贴在图标旁边、伸出小角的气泡。
        fun placeBubble(anchor: SettingsBackupMotionRect, width: Float, height: Float) =
            BubblePlacementSpec.place(
                anchor = anchor,
                windowWidth = width,
                windowHeight = height,
                desiredWidth = BUBBLE_WIDTH_DP * density,
                maxWidthPx = BUBBLE_WIDTH_DP * density,
                sideMarginPx = BUBBLE_SIDE_MARGIN_DP * density,
                edgeMarginPx = BUBBLE_EDGE_MARGIN_DP * density,
                gapPx = BUBBLE_GAP_DP * density,
                tailHeightPx = BUBBLE_TAIL_HEIGHT_DP * density,
                tailHalfWidthPx = BUBBLE_TAIL_HALF_WIDTH_DP * density,
                cornerRadiusPx = MODAL_CORNER_RADIUS_DP * density
            )
        val bubblePlacement = if (anchorStyle == AnchorStyle.BUBBLE && anchorBounds != null) {
            placeBubble(anchorBounds, resources.displayMetrics.widthPixels.toFloat(),
                resources.displayMetrics.heightPixels.toFloat())
        } else {
            null
        }
        val originalPaddingTop = container.paddingTop
        val originalPaddingBottom = container.paddingBottom
        val modalBackground = container.background
        fun applyBubbleSurface(placement: BubblePlacement) {
            // 小角占掉整体高度的一条，内容要让开，否则文字会压在尖上。
            val tailPadding = (BUBBLE_TAIL_HEIGHT_DP * density).toInt()
            container.setPadding(
                container.paddingLeft,
                originalPaddingTop +
                    if (placement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
                container.paddingRight,
                originalPaddingBottom +
                    if (placement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
            )
            // 皮肤背景移交独立表面；正文保持原生尺寸，不再用纯色气泡替换 Liquid。
            container.background = null
        }
        if (bubblePlacement != null) {
            applyBubbleSurface(bubblePlacement)
            container.scaleX = 1f
            container.scaleY = 1f
            container.elevation = 0f
        }
        val bubbleLayer = if (bubblePlacement != null) {
            BubblePanelLayer(this, container, morphAnchor as? android.widget.ImageView,
                modalBackground ?: skinModalBackground(monetColors.surface, MODAL_CORNER_RADIUS_DP),
                skinModalBackground(monetColors.surface, 0f), monetColors.surface,
                MODAL_CORNER_RADIUS_DP * density, BUBBLE_TAIL_HEIGHT_DP * density,
                BUBBLE_TAIL_HALF_WIDTH_DP * density).apply { setPlacement(bubblePlacement) }
        } else null
        val morphLayer = anchorBounds?.takeIf { bubblePlacement == null }
            ?.let { IconAnchoredMotionLayer(this) }
        val root = NativeFrameLayout(this).apply {
            val cardParams = if (bubblePlacement != null) {
                NativeFrameLayout.LayoutParams(
                    bubblePlacement.width.toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    // 几何来自物理屏幕坐标，不能在 RTL 下再次镜像。
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = bubblePlacement.left.toInt()
                    topMargin = bubblePlacement.top.toInt()
                }
            } else {
                NativeFrameLayout.LayoutParams(
                    preferredWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
                }
            }
            if (bubbleLayer != null) {
                bubbleLayer.setContentLayoutParams(cardParams)
                addView(bubbleLayer, NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (morphLayer != null) {
                // 承载层必须全屏：outline 要从工具栏里的图标一路长到屏幕中央的卡片，
                // 折叠端矩形本来就落在卡片之外。
                morphLayer.addView(container, cardParams)
                addView(
                    morphLayer,
                    NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                addView(container, cardParams)
            }
        }
        var currentBubblePlacement = bubblePlacement
        var bubbleAnchor = anchorBounds
        fun notifyExpanded() {
            if (dialog.isShowing && !isFinishing && !isDestroyed) onExpanded()
        }
        // 背景浅毛玻璃：跟着弹窗自己的进度渐进，让面板与背景分层。
        // 用户开关 + Material You 美学 + API 31+ + 系统允许跨窗口模糊，四道门都在工厂里判。
        val backdropBlur = ModalBackdropBlur.createOrNull(
            window = dialog.window,
            userEnabled = ModalBackdropBlurStore.read(this),
            materialYouSkin = isMaterialYouSkinEffective,
            density = density
        )
        val bubbleController = if (bubbleLayer != null) {
            BubbleMotionController(
                layer = bubbleLayer,
                onFrame = { progress -> backdropBlur?.apply(progress) },
                onExpanded = ::notifyExpanded,
                onClosed = {
                    dialog.dismiss()
                    (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                }
            )
        } else {
            null
        }
        val titleMotion = if (morphLayer != null) {
            ModalTitleMotion.create(morphAnchor, container.firstChildOrNull<NativeTextView>(), root)
                ?.also { title ->
                    root.addView(title, NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
        } else null
        val morphController = if (morphLayer != null && anchorBounds != null) {
            // 形变不缩放卡片（缩放会把文字压扁），只驱动 outline + alpha，因此先抹掉
            // createModalContainer 为缩放入场准备的 0.85；alpha 0 仍然保留给正文淡入。
            container.scaleX = 1f
            container.scaleY = 1f
            IconAnchoredMotionController(
                layer = morphLayer,
                content = container,
                // 必须和卡片用**同一种**背景（skinModalBackground），不能用
                // skinMotionSurfaceBackground：后者是为全屏容器形变准备的半透明"运动表面"，
                // 用在这里会让飞入过程变成一团能透看底页的鬼影，且抵达展开端时与卡片
                // 交接会出现一次可见的不透明度跳变。
                surfaceDrawable = skinModalBackground(
                    monetColors.surface,
                    MODAL_CORNER_RADIUS_DP
                ),
                resolveGeometry = {
                    morphAnchor?.let(::modalAnchorBounds)?.let { currentAnchor ->
                        resolveIconAnchoredGeometry(morphLayer, container, currentAnchor, density)
                    }
                },
                titleMotion = titleMotion,
                onFrame = { progress -> backdropBlur?.apply(progress) },
                onExpanded = ::notifyExpanded,
                onClosed = {
                    dialog.dismiss()
                    (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                }
            )
        } else {
            null
        }
        // 登记收起入口：全仓 72 处 dismissWithAnimation 由此自动走对应的收起动画。
        val anchoredCloser: ((Boolean, (() -> Unit)?) -> Boolean)? = when {
            bubbleController != null -> { interactive, after ->
                // 关闭按钮后紧接返回键不能覆盖第一次关闭的业务回调。
                if (!bubbleController.isClosing) pendingAnchoredAfterClose.set(after)
                bubbleController.requestClose(interactive)
            }
            morphController != null -> { interactive, after ->
                if (!morphController.isClosing) pendingAnchoredAfterClose.set(after)
                morphController.requestClose(interactive)
            }
            else -> null
        }
        if (anchoredCloser != null) dialogAnchoredClosers[dialog] = anchoredCloser
        val bubbleRootLocation = IntArray(2)
        val bubbleSourceLocation = IntArray(2)
        var bubbleLaidOut = false
        var previousBubbleHeight = 0
        var bubbleGeometryPending = false
        var previousBubbleLeft = Int.MIN_VALUE
        var previousBubbleTop = Int.MIN_VALUE
        var previousBubbleRight = Int.MIN_VALUE
        var previousBubbleBottom = Int.MIN_VALUE
        fun updateBubbleGeometry(): Boolean {
            if (bubbleController == null || bubbleController.isClosing || root.width <= 0 || root.height <= 0) return false
            val source = morphAnchor ?: return false
            if (!source.isAttachedToWindow) return false
            root.getLocationOnScreen(bubbleRootLocation)
            source.getLocationOnScreen(bubbleSourceLocation)
            // Dialog 的内容原点可能位于状态栏下面；统一转换后才计算小角与收回目标。
            val x = (bubbleSourceLocation[0] - bubbleRootLocation[0]).toFloat()
            val y = (bubbleSourceLocation[1] - bubbleRootLocation[1]).toFloat()
            val localAnchor = SettingsBackupMotionRect(x, y, x + source.width, y + source.height)
            val placement = placeBubble(localAnchor, root.width.toFloat(), root.height.toFloat()) ?: return false
            val params = container.layoutParams as NativeFrameLayout.LayoutParams
            val top = BubblePlacementSpec.resolveTop(placement, localAnchor, container.height.toFloat(),
                BUBBLE_GAP_DP * density, BUBBLE_EDGE_MARGIN_DP * density).toInt()
            val layoutChanged = params.width != placement.width.toInt() ||
                params.leftMargin != placement.left.toInt() || params.topMargin != top
            // 键盘只改变可用高度、但卡片本身仍放得下时，不打断仍在进行的展开动画。
            val shapeChanged = placement.tailEdge != currentBubblePlacement?.tailEdge ||
                placement.tailCenterX != currentBubblePlacement?.tailCenterX ||
                placement.tailBaseCenterX != currentBubblePlacement?.tailBaseCenterX
            val geometryChanged = layoutChanged || shapeChanged || localAnchor != bubbleAnchor ||
                previousBubbleHeight != container.height
            val actualBoundsChanged = container.left != previousBubbleLeft ||
                container.top != previousBubbleTop || container.right != previousBubbleRight ||
                container.bottom != previousBubbleBottom
            bubbleGeometryPending = bubbleGeometryPending || geometryChanged || actualBoundsChanged
            bubbleAnchor = localAnchor
            if (shapeChanged) applyBubbleSurface(placement)
            bubbleLayer?.setPlacement(placement)
            bubbleLayer?.setAnchor(localAnchor)
            currentBubblePlacement = placement
            previousBubbleHeight = container.height
            if (layoutChanged) {
                params.width = placement.width.toInt()
                params.leftMargin = placement.left.toInt()
                params.topMargin = top
                container.layoutParams = params
            }
            // 参数写入不是布局完成；等实际四边就绪后再刷新独立背景与正文裁剪。
            if (!layoutChanged && !container.isLayoutRequested) {
                if (bubbleLaidOut && bubbleGeometryPending) bubbleController.handleWindowSizeChange()
                bubbleGeometryPending = false
                previousBubbleLeft = container.left
                previousBubbleTop = container.top
                previousBubbleRight = container.right
                previousBubbleBottom = container.bottom
            }
            return layoutChanged
        }
        val bubbleLayoutListener = if (bubbleController != null) {
            android.view.ViewTreeObserver.OnGlobalLayoutListener { updateBubbleGeometry() }
                .also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        } else null
        morphLayer?.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom ->
            val hadLayout = (oldRight - oldLeft) > 0 && (oldBottom - oldTop) > 0
            val resized = (right - left) != (oldRight - oldLeft) ||
                (bottom - top) != (oldBottom - oldTop)
            // 旋转/分屏/输入法改变窗口后旧矩形失效，直接落到稳定端而不是继续按旧几何插值。
            if (hadLayout && resized && morphController?.isExpanded == false) {
                morphController.handleWindowSizeChange()
            }
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            // 关掉平台级窗口进出动画。本项目所有弹窗动画都是手绘的（形变 / 气泡 / 缩放淡出），
            // 窗口动画只会叠在上面捣乱：`dialog.dismiss()` 是同步移窗，而
            // WindowManager 的退出动画搬的是这个 surface **最后一次真正绘制**的那一帧——
            // 而 `apply(0f)` 与 `titleMotion.closed()` 都发生在 animator 回调里、
            // 在本帧 TRAVERSAL **之前**，所以它们压根没被画出来。
            // 于是最后一帧里那份满不透明的飞行标题被窗口动画拖着向上飘走并淡出，
            // 现场就是"返回动画末端，文字上方冒出一个重影往上飞着消失"。
            // 实测本机这两个窗口的 `anim=` 是非零的（`dumpsys window windows`），确认动画开着。
        }
        dialog.setContentView(root)
        // **必须在 setContentView 之后**：`PhoneWindow.generateLayout()`（由 setContentView 触发）
        // 会从主题里重新读 `windowAnimationStyle` 覆盖 `params.windowAnimations`，
        // 放在前面写的 0 会被原样抹掉——真机 `dumpsys window windows` 里 `anim=` 依旧非零，
        // 实测踩过一次。
        dialog.window?.setWindowAnimations(0)
        // 系统返回键也必须走项目统一的 180ms scale + fade 退场动画。
        // 预测性返回启用（targetSdk 33+ 注册、Android 16+ 系统强制）后，手势导航的
        // 返回不再向 Dialog 派发 KEYCODE_BACK，改走 OnBackInvokedCallback；三键导航
        // 仍派发按键并保留 setOnKeyListener。两条路径都收敛到同一退场动画与回调。
        // API 34+ 进一步注册 OnBackAnimationCallback：手势拖动时弹窗按
        // BackEvent.progress（0..1）预览缩小（对齐退场终值 0.92 的中途态）、取消时
        // 回弹、松手提交时从当前预览状态无缝续接 180ms 退场（ViewPropertyAnimator
        // 天然从当前值起步）。API 33 的普通回调没有进度事件，保持松手才动画。
        // dismissing 防重入：退场动画一旦启动，后续手势/按键回调一律忽略——四个回调
        // 都要判，`onBackStarted` 尤其不能漏，它才是调用 container.animate().cancel()
        // 的那处，取消在途退场会立刻触发收尾监听器（见该回调内注释）。
        var dismissing = false
        fun requestDismiss(interactiveCommit: Boolean = false) {
            if (dismissing) return
            dismissing = true
            // 锚点动画接管失败（几何不可用）时回落到既有 scale 退场，不留半截形状。
            if (anchoredCloser?.invoke(interactiveCommit, null) == true) return
            dismissWithAnimation(dialog, container, onBackDismiss)
        }
        // 类型必须是 `Any?`：一旦写成 API 33 的 `OnBackInvokedCallback?`，这个被 lambda
        // 捕获的 var 就会把那个类型引用带进 dismiss 回调的字节码，ART 在 API 32 及以下
        // 进入那个方法时直接抛 NoClassDefFoundError（下面的 SDK 判断和 runCatching 都
        // 来不及生效）。类型本身只许出现在 PredictiveBackApi33 里，见该文件类注释。
        var predictiveBackCallback: Any? = null
        if (AndroidVersion.isAtLeast(AndroidVersion.U)) {
            predictiveBackCallback = PredictiveBackApi33.animationCallback(
                onStarted = {
                    // 退场动画期间再起手势必须整条忽略：`dismissWithAnimation` 用
                    // AnimatorListenerAdapter 收尾，而 ViewPropertyAnimator 在 cancel()
                    // 后仍会派发 onAnimationEnd，取消在途退场会让 dialog.dismiss() 与
                    // onDismissed() 立刻执行、180ms 退场被截断。
                    if (!dismissing) {
                        // 形变路径由 controller 自己接管在途动画并按当前速度续接，
                        // 不能在这里 cancel 掉 container 的 ViewPropertyAnimator（它根本没在跑）。
                        if (bubbleController != null) {
                            bubbleController.beginPredictiveBack()
                        } else if (morphController != null) {
                            morphController.beginPredictiveBack()
                        } else {
                            // 中断在途动画（入场或回弹），后续属性由手势进度直接驱动
                            container.animate().cancel()
                        }
                    }
                },
                onProgressed = { progress ->
                    if (!dismissing) {
                        if (bubbleController != null) {
                            bubbleController.progressPredictiveBack(progress)
                        } else if (morphController != null) {
                            morphController.progressPredictiveBack(progress)
                        } else {
                            container.scaleX = 1f - 0.05f * progress
                            container.scaleY = 1f - 0.05f * progress
                            container.alpha = 1f - 0.15f * progress
                        }
                    }
                },
                onCancelled = {
                    if (!dismissing) {
                        if (bubbleController != null) {
                            bubbleController.cancelPredictiveBack()
                        } else if (morphController != null) {
                            morphController.cancelPredictiveBack()
                        } else {
                            container.animate()
                                .scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(emphasizedDecelerate)
                                .start()
                        }
                    }
                },
                onInvoked = {
                    // 手势松手：形变按当前值与当前速度续接，controller 内部再判断
                    // 本次返回是否真的从手势开始（三键/按键路径 hadInteractiveStart 为 false）。
                    requestDismiss(interactiveCommit = true)
                }
            )
        } else if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
            predictiveBackCallback = PredictiveBackApi33.plainCallback { requestDismiss() }
        }
        val callbackToRegister = predictiveBackCallback
        // 注册必须发生在 show() **之后**，见下面 registerBackCallback 的说明；
        // 记住当时那个 dispatcher，注销才对得上同一个对象。类型同样必须是 `Any?`：
        // 这个 var 正是 2026-09-11 那次崩溃里被 dismiss 回调捕获、把
        // OnBackInvokedDispatcher 带进字节码的那一个。
        var registeredBackDispatcher: Any? = null
        /**
         * 把系统返回（手势与三键）接到项目自己的退场动画上。
         *
         * 原来在 `dialog.show()` **之前**注册：那时 decor 还没挂上 `ViewRootImpl`，
         * API 33 又没有 API 34 才加的 `ProxyOnBackInvokedDispatcher` 来缓存 attach 前的注册，
         * 于是这次注册被丢掉，而 `Dialog.show()` 自己会注册一个 system 级默认回调
         * （`onBackPressed → cancel → dismiss`，**瞬间消失、没有任何动画**）。
         * 结果就是两条返回路径行为不一致：三键/按键走 `Dialog.dispatchKeyEvent` →
         * 先问 `mOnKeyListener`（下面那个）→ 有动画；而**手势**走 dispatcher →
         * 只剩系统默认回调 → 没动画。
         *
         * 放到 attach 之后注册，我们的 `PRIORITY_DEFAULT` 就压在系统默认回调之上
         * （system 优先级低于 default），两条路径都收敛到 `requestDismiss()`。
         * 失败要留日志：这里原本被 `runCatching` 静默吞掉，正是它让上面那条不一致
         * 长期不可观测。
         */
        fun registerBackCallback() {
            if (callbackToRegister == null || !AndroidVersion.isAtLeast(AndroidVersion.T)) return
            if (registeredBackDispatcher != null) return
            runCatching {
                registeredBackDispatcher = PredictiveBackApi33.register(dialog, callbackToRegister)
            }.onFailure {
                Log.e(
                    "BilibiliInnocentLab",
                    "register OnBackInvokedCallback failed; back gesture loses its animation", it
                )
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    requestDismiss()
                }
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            // 其他入口硬关（presentSizedModalDialog 开头的 activeConfirmDialog?.dismiss()）
            // 也要收掉在途 animator，否则回调会继续驱动一个已经消失的窗口。
            morphController?.cancelMotion()
            bubbleController?.cancelMotion()
            // 硬关会停在半路；窗口撤掉后模糊本来就没了，这里归零只为不把带 FLAG_BLUR_BEHIND
            // 的属性留在一个可能被复用的 window 上。
            backdropBlur?.clear()
            if (bubbleLayoutListener != null && root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(bubbleLayoutListener)
            }
            dialogAnchoredClosers.remove(dialog)
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
            val callback = predictiveBackCallback
            // 注销要冲着**当时注册成功的那个** dispatcher；窗口已 detach 时重新向
            // dialog 取一次，可能拿到换过的对象、也可能直接抛。取的动作本身在
            // PredictiveBackApi33 里，这个函数不许再出现（有单测盯着）。
            val dispatcher = registeredBackDispatcher
            // 两个都非空只可能发生在 API 33+（注册路径本身就压在 SDK 判断后面），
            // 但 SDK 判断仍要显式写出来：它决定 PredictiveBackApi33 这个类会不会被加载。
            if (callback != null && dispatcher != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
                registeredBackDispatcher = null
                runCatching { PredictiveBackApi33.unregister(dispatcher, callback) }
            }
            if (releaseHighlightsDialog === dialog) {
                releaseHighlightsDialog = null
                activeHighlightsFrom = null
            }
            scheduleReleaseHighlights()
        }
        activeConfirmDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // show() 之后：窗口已 attach，dispatcher 是真的那一个，
        // 且我们的回调会压在 Dialog.show() 刚注册的 system 级默认回调之上。
        registerBackCallback()

        if (bubbleController != null) {
            // 在实际 Dialog 坐标和最终测量尺寸就绪后才开始，避免先闪一帧或从错误位置展开。
            root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (updateBubbleGeometry() || container.isLayoutRequested) return false
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!dialog.isShowing || bubbleController.isClosing) return true
                    bubbleLaidOut = true
                    bubbleController.prepareFirstFrame()
                    bubbleController.startEntry()
                    return true
                }
            })
            return
        }
        if (morphController != null && morphLayer != null) {
            // 必须在首帧绘制**之前**压到来源端，否则会先闪一帧完整卡片再跳回图标。
            morphLayer.viewTreeObserver.addOnPreDrawListener(
                object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        morphLayer.viewTreeObserver.removeOnPreDrawListener(this)
                        if (!dialog.isShowing || morphController.isClosing) return true
                        if (!morphController.prepareFirstFrame()) {
                            // 尺寸未就绪或几何非法：直接落到展开端，不留半截形状。
                            morphController.snapToExpanded()
                            return true
                        }
                        morphLayer.post {
                            if (dialog.isShowing && !morphController.isClosing) morphController.startEntry()
                        }
                        return true
                    }
                }
            )
            return
        }
        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                // 无锚点弹窗没有形变时钟，借它自己的入场进度推模糊。退场由
                // 共用的 dismissWithAnimation 负责，窗口撤掉时模糊随之消失（硬切，
                // 与这条路径本来的淡出观感一致），不去改那 72 个调用点。
                .setUpdateListener { backdropBlur?.apply(container.alpha) }
                .withEndAction {
                    backdropBlur?.apply(1f)
                    notifyExpanded()
                }
                .start()
        }
    }

    /**
     * 勾选式屏蔽面板的"面"描述符：把四个面的差异全部收敛到这一处。
     *
     * "我的"页 / 底栏 / 首页顶栏标签 / 首页组件共用同一条
     * 宿主扫描 → 跨进程查询 → 勾选 → 写回 selector 的链路，面板本身完全一致。
     */
    // internal：出现在外移弹窗的参数类型里（showComponentManualRuleEditor 等）。
    internal class ComponentPickerSurface(
        val surface: String,
        @StringRes val titleRes: Int,
        @StringRes val hintRes: Int,
        @StringRes val labelRes: Int,
        val selectorsKey: String,
        val rulesKey: String,
        val status: PickerStatusText,
        val currentRules: () -> String,
        val onRulesSaved: (String) -> Unit,
        val summaryView: () -> NativeTextView?,
        /** 仅"我的"页有历史遗留的 id 名单；其余面为 null。 */
        val legacyIdsKey: String? = null
    )

    /**
     * 面板状态文案。
     *
     * 用现成字符串而不是 `@StringRes`：`"我的"`页沿用它原有的专属文案（措辞不动），
     * 另外三个面共用一套带面名占位符的通用文案，两者格式化参数的形状并不一致。
     */
    // internal：作为 ComponentPickerSurface 的构造参数类型出现在外移代码里。
    internal class PickerStatusText(
        val querying: String,
        val waitingPage: String,
        val unavailable: String,
        val invalid: String,
        val storeFailed: String,
        val stale: String,
        val legacy: String,
        val ready: (Int) -> String
    )

    private fun mineStatusText() = PickerStatusText(
        querying = getString(R.string.custom_mine_component_snapshot_querying),
        waitingPage = getString(R.string.custom_mine_component_snapshot_waiting_page),
        unavailable = getString(R.string.custom_mine_component_snapshot_unavailable),
        invalid = getString(R.string.custom_mine_component_snapshot_invalid),
        storeFailed = getString(R.string.custom_mine_component_snapshot_store_failed),
        stale = getString(R.string.custom_mine_component_snapshot_stale),
        legacy = getString(R.string.custom_mine_component_snapshot_legacy),
        ready = { count -> getString(R.string.custom_mine_component_snapshot_ready, count) }
    )

    private fun genericStatusText(@StringRes nameRes: Int): PickerStatusText {
        val name = getString(nameRes)
        return PickerStatusText(
            querying = getString(R.string.component_picker_snapshot_querying, name),
            waitingPage = getString(R.string.component_picker_snapshot_waiting_page, name),
            unavailable = getString(R.string.component_picker_snapshot_unavailable, name),
            invalid = getString(R.string.component_picker_snapshot_invalid, name),
            storeFailed = getString(R.string.component_picker_snapshot_store_failed, name),
            stale = getString(R.string.component_picker_snapshot_stale, name),
            legacy = getString(R.string.component_picker_snapshot_legacy, name),
            ready = { count ->
                getString(R.string.component_picker_snapshot_ready, name, count)
            }
        )
    }

    private fun mineComponentPickerSurface() = ComponentPickerSurface(
        surface = MineComponentSnapshotCodec.SURFACE_MINE,
        titleRes = R.string.custom_mine_component_hide_dialog_title,
        hintRes = R.string.custom_mine_component_hide_hint,
        labelRes = R.string.custom_mine_component_hide,
        selectorsKey = FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
        rulesKey = FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES,
        status = mineStatusText(),
        currentRules = { mineComponentHiddenRules },
        onRulesSaved = { mineComponentHiddenRules = it },
        summaryView = { mineComponentRulesSummaryView },
        legacyIdsKey = FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS
    )

    private fun bottomBarPickerSurface() = ComponentPickerSurface(
        surface = MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR,
        titleRes = R.string.custom_bottom_bar_hide_dialog_title,
        hintRes = R.string.custom_bottom_bar_hide_hint,
        labelRes = R.string.custom_bottom_bar_hide,
        selectorsKey = FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS,
        rulesKey = FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES,
        status = genericStatusText(R.string.component_picker_surface_bottom_bar),
        currentRules = { bottomBarHiddenRules },
        onRulesSaved = { bottomBarHiddenRules = it },
        summaryView = { bottomBarRulesSummaryView }
    )

    private fun homeTabPickerSurface() = ComponentPickerSurface(
        surface = MineComponentSnapshotCodec.SURFACE_HOME_TABS,
        titleRes = R.string.custom_home_tab_hide_dialog_title,
        hintRes = R.string.custom_home_tab_hide_hint,
        labelRes = R.string.custom_home_tab_hide,
        selectorsKey = FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS,
        rulesKey = FeaturePreferences.HOME_TAB_HIDDEN_RULES,
        status = genericStatusText(R.string.component_picker_surface_home_tabs),
        currentRules = { homeTabHiddenRules },
        onRulesSaved = { homeTabHiddenRules = it },
        summaryView = { homeTabRulesSummaryView }
    )

    private fun homeComponentPickerSurface() = ComponentPickerSurface(
        surface = MineComponentSnapshotCodec.SURFACE_HOME_COMPONENTS,
        titleRes = R.string.custom_home_component_hide_dialog_title,
        hintRes = R.string.custom_home_component_hide_hint,
        labelRes = R.string.custom_home_component_hide,
        selectorsKey = FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS,
        rulesKey = FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES,
        status = genericStatusText(R.string.component_picker_surface_home_components),
        currentRules = { homeComponentHiddenRules },
        onRulesSaved = { homeComponentHiddenRules = it },
        summaryView = { homeComponentRulesSummaryView }
    )

    /** 勾选数 + 手填规则的两行摘要；两者是并集关系，缺一方就只显示另一方。 */
    private fun ComponentPickerSurface.summaryText(): String {
        val selectorCount = MineComponentSelectionCodec.decode(
            prefs().getString(selectorsKey, "").orEmpty()
        ).size
        val selectorSummary = if (selectorCount > 0) {
            getString(R.string.custom_mine_component_selected_count, selectorCount)
        } else {
            ""
        }
        val rules = currentRules()
        val manualSummary = ruleSummary(rules)
        return when {
            selectorSummary.isEmpty() -> manualSummary
            rules.isBlank() -> selectorSummary
            else -> "$selectorSummary\n$manualSummary"
        }
    }

    // internal：外移的组件选择弹窗在保存后要刷新摘要行。
    internal fun ComponentPickerSurface.refreshSummary() {
        summaryView()?.text = getString(labelRes) + "\n" + summaryText()
    }

    private fun queryComponentSnapshotAndOpenPicker(spec: ComponentPickerSurface) {
        if (!componentSnapshotQueryInFlight.add(spec.surface)) return
        toast(spec.status.querying)
        MineComponentSnapshotQueryClient.query(this, spec.surface) { result ->
            componentSnapshotQueryInFlight.remove(spec.surface)
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@query
            when (result.status) {
                MineComponentSnapshotQueryClient.Status.READY -> {
                    val snapshot = result.snapshot
                    if (snapshot != null && snapshot.entries.isNotEmpty()) {
                        spec.refreshSummary()
                        showComponentPickerDialog(spec, snapshot)
                    } else {
                        showComponentSnapshotFallback(spec, spec.status.invalid)
                    }
                }

                MineComponentSnapshotQueryClient.Status.WAITING_PAGE ->
                    showComponentSnapshotFallback(spec, spec.status.waitingPage)

                MineComponentSnapshotQueryClient.Status.TARGET_UNAVAILABLE ->
                    showComponentSnapshotFallback(spec, spec.status.unavailable)

                MineComponentSnapshotQueryClient.Status.INVALID_RESPONSE ->
                    showComponentSnapshotFallback(spec, spec.status.invalid)

                MineComponentSnapshotQueryClient.Status.STORE_FAILED ->
                    showComponentSnapshotFallback(
                        spec,
                        spec.status.storeFailed,
                        result.snapshot
                    )
            }
        }
    }

    internal fun recommendVideoDurationSummary(): String = when {
        recommendVideoMinDurationSeconds <= 0 && recommendVideoMaxDurationSeconds <= 0 ->
            getString(R.string.recommend_video_duration_range_empty)
        recommendVideoMaxDurationSeconds <= 0 -> getString(
            R.string.recommend_video_duration_min_only,
            formatDurationSeconds(recommendVideoMinDurationSeconds)
        )
        recommendVideoMinDurationSeconds <= 0 -> getString(
            R.string.recommend_video_duration_max_only,
            formatDurationSeconds(recommendVideoMaxDurationSeconds)
        )
        else -> getString(
            R.string.recommend_video_duration_both,
            formatDurationSeconds(recommendVideoMinDurationSeconds),
            formatDurationSeconds(recommendVideoMaxDurationSeconds)
        )
    }

    private fun formatDurationSeconds(totalSeconds: Int): String {
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        val paddedMinutes = minutes.toString().padStart(2, '0')
        val paddedSeconds = seconds.toString().padStart(2, '0')
        return if (hours > 0) {
            "$hours:$paddedMinutes:$paddedSeconds"
        } else {
            "${totalSeconds / 60}:$paddedSeconds"
        }
    }

    private fun ruleSummary(value: String): String = if (value.isBlank()) {
        getString(R.string.custom_hide_rules_empty)
    } else {
        getString(R.string.custom_hide_rules_current, value)
    }

    internal fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") {
            toast(getString(R.string.open_link_failed))
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.open_link_failed))
        }
    }

    /**
     * 应用预见式返回（Android 14+）：能力由清单 android:enableOnBackInvokedCallback=true
     * 声明；运行时 per-window 开关是隐藏接口（Window#setEnableOnBackInvokedCallback），
     * 经 [PredictiveBack] 反射调用，失败（hidden API 限制/ROM 无此方法）时保持系统默认。
     * 模块界面无自定义 back 拦截逻辑，启用后由系统直接处理返回，无兼容性问题。
     */
    private fun applyPredictiveBack() {
        PredictiveBack.apply(window, predictiveBackEnabled)
    }

    /** 手动亮色开关生效（写 prefs + 更新状态，供确认对话框确认后调用） */
    private fun onFreeCopyLightModeChanged(value: Boolean) {
        freeCopyLightMode = value
        runCatching {
            prefs().edit { putBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, value) }
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "write free copy light mode prefs failed", t)
        }
    }

    /**
     * 手动亮色开关下方 tip 文本切换动画（淡出 → 换文本 → 淡入）。
     * 自动跟随开/关时文本在「跟随控制」/「手动描述」之间切换——纯文本变化
     * 会在父布局里瞬时重排（割裂感），这里用 alpha 交叉淡化让切换连贯；
     * 不重建界面（recreate 会整页排版跳动，已弃用）。
     */
    private fun animateLightModeTip() {
        val tv = lightModeTipView ?: return
        val newText = getString(
            if (freeCopyAutoLight) R.string.free_copy_light_mode_auto_tip
            else R.string.free_copy_light_mode_tip
        )
        if (tv.textToString() == newText) return
        tv.animate().cancel()
        // 淡出 → 换文本（此时不可见，父布局重排无割裂感）→ 淡入
        tv.animate()
            .alpha(0f)
            .setDuration(120L)
            .setInterpolator(emphasizedAccelerate)
            .withEndAction {
                tv.text = newText
                tv.animate()
                    .alpha(0.6f)
                    .setDuration(180L)
                    .setInterpolator(emphasizedDecelerate)
                    .start()
            }
            .start()
    }

    /**
     * Dialog dismiss 后焦点可能晚一帧回到 Activity；仅在 RESUMED 时短暂复查一次，
     * 真正退到后台或 Activity 已销毁时绝不拉起系统页面。
     */
    internal fun finishNoRootRestartFlush(
        result: NoRootSupportController.FlushResult,
        allowFocusRetry: Boolean = true
    ) {
        if (isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!hasWindowFocus()) {
            if (allowFocusRetry) {
                window.decorView.postDelayed(
                    { finishNoRootRestartFlush(result, allowFocusRetry = false) },
                    100L
                )
            }
            return
        }
        renderNoRootUi()
        val messageRes = when (result) {
            NoRootSupportController.FlushResult.SUCCESS -> null
            NoRootSupportController.FlushResult.FAILED -> R.string.no_root_restart_sync_failed
            NoRootSupportController.FlushResult.TIMED_OUT ->
                R.string.no_root_restart_sync_timeout
        }
        messageRes?.let { toast(getString(it)) }
        openBilibiliAppDetails(this)
    }

    private fun toggleSecondaryMenu(section: SettingsSearchSection) {
        val expanded = when (section) {
            SettingsSearchSection.PURIFICATION_ADVANCED -> purificationAdvancedExpanded
            SettingsSearchSection.ENHANCEMENT_ADVANCED -> enhancementAdvancedExpanded
            SettingsSearchSection.APPEARANCE -> appearanceExpanded
            SettingsSearchSection.COMPATIBILITY -> compatibilityExpanded
            else -> return
        }
        setSecondaryMenuExpanded(section, !expanded)
    }

    /** 返回是否真的展开/收起，供设置搜索确定等待布局的时间。 */
    private fun setSecondaryMenuExpanded(section: SettingsSearchSection, expanded: Boolean): Boolean {
        val content: View
        val chevron: View
        when (section) {
            SettingsSearchSection.PURIFICATION_ADVANCED -> {
                if (purificationAdvancedExpanded == expanded) return false
                content = purificationAdvancedContent ?: return false
                chevron = purificationAdvancedChevron ?: return false
                purificationAdvancedExpanded = expanded
            }
            SettingsSearchSection.ENHANCEMENT_ADVANCED -> {
                if (enhancementAdvancedExpanded == expanded) return false
                content = enhancementAdvancedContent ?: return false
                chevron = enhancementAdvancedChevron ?: return false
                enhancementAdvancedExpanded = expanded
            }
            SettingsSearchSection.APPEARANCE -> {
                if (appearanceExpanded == expanded) return false
                content = appearanceContent ?: return false
                chevron = appearanceChevron ?: return false
                appearanceExpanded = expanded
            }
            SettingsSearchSection.COMPATIBILITY -> {
                if (compatibilityExpanded == expanded) return false
                content = compatibilityContent ?: return false
                chevron = compatibilityChevron ?: return false
                compatibilityExpanded = expanded
            }
            else -> return false
        }
        animateSecondarySection(content, chevron, expanded)
        return true
    }

    /** 只修饰组内标题；不改变分类 marker、点击入口或内容容器的水平留白。 */
    private fun NativeTextView.applyAdvancedSubsectionStyle() {
        textSize = AdvancedSubsectionStyle.TITLE_SP
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textColor = monetColors.primary
        alpha = 1f
        isSingleLine = false
        ellipsize = null
        ViewCompat.setAccessibilityHeading(this, true)
    }

    /** 首帧前用同一套折叠卡片分组净化与增强控件，保留原监听器和独立展开状态。 */
    private fun installAdvancedCategorySections() {
        installAdvancedCategorySections(
            purificationAdvancedContent as? ViewGroup ?: return,
            SettingsSearchSection.PURIFICATION_ADVANCED
        )
        installAdvancedCategorySections(
            enhancementAdvancedContent as? ViewGroup ?: return,
            SettingsSearchSection.ENHANCEMENT_ADVANCED
        )
    }

    private fun installAdvancedCategorySections(root: ViewGroup, section: SettingsSearchSection) {
        val categories = AdvancedSettingsCategory.entries.filter { it.section == section }
        if (categories.any { it in advancedCategorySections }) return

        val originalChildren = List(root.childCount, root::getChildAt)
        // 下面两条失败路径都会让进阶设置**安静地退回平铺**（不再折叠成分类）：
        // 编译不报错、单测不红，只有真机上肉眼能看出来。拆 DSL 树时最容易踩的就是它们，
        // 所以必须留日志。前置条件由 AdvancedCategoryTreeTest 静态钉住。
        val markers = categories.map { category ->
            advancedCategoryMarkers[category] ?: run {
                Log.e(
                    "BilibiliInnocentLab",
                    "advanced category marker missing for $category; " +
                        "the settings tree no longer registers it, falling back to a flat layout"
                )
                return
            }
        }
        val markerIndices = markers.map(originalChildren::indexOf)
        val ranges = AdvancedCategoryLayoutPolicy.resolve(
            markerIndices = markerIndices,
            childCount = originalChildren.size
        ) ?: run {
            Log.e(
                "BilibiliInnocentLab",
                "advanced category layout unresolved for $section (markers=$markerIndices, " +
                    "children=${originalChildren.size}); the first child must be the first " +
                    "category title and markers must follow the enum order"
            )
            return
        }

        val density = resources.displayMetrics.density
        val horizontalPadding = SettingsMenuSpacing.referenceContentPaddingPx(density)
        val headerVerticalPadding = (11f * density).toInt()
        root.removeAllViews()

        categories.forEachIndexed { index, category ->
            val title = markers[index].apply {
                alpha = 0.92f
                textColor = getColor(R.color.colorTextGray)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            if (!category.collapsible) {
                title.textColor = monetColors.primary
                title.textSize = 12f
                root.addView(title, NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = ((if (index == 0) 4f else 18f) * density).toInt()
                    bottomMargin = (6f * density).toInt()
                })
                val content = NativeLinearLayout(this).apply {
                    orientation = NativeLinearLayout.VERTICAL
                    val range = ranges[index]
                    for (childIndex in range.startInclusive until range.endExclusive) {
                        addView(originalChildren[childIndex])
                    }
                }
                root.addView(content)
                advancedCategorySections[category] = AdvancedCategorySection(
                    title, content, chevron = null, expanded = true
                )
                return@forEachIndexed
            }
            val chevron = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = ColorStateList.valueOf(getColor(R.color.colorTextGray))
                alpha = 0.82f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val content = NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(
                    horizontalPadding,
                    0,
                    horizontalPadding,
                    (12f * density).toInt()
                )
                ranges[index].let { range ->
                    for (childIndex in range.startInclusive until range.endExclusive) {
                        addView(originalChildren[childIndex])
                    }
                }
            }
            val header = NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48f * density).toInt()
                setPadding(
                    horizontalPadding,
                    headerVerticalPadding,
                    horizontalPadding,
                    headerVerticalPadding
                )
                foreground = selfRippleBackground(12f)
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = title.text
                addView(
                    View(this@MainActivity).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = 2f * density
                            setColor(monetColors.primary)
                        }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    NativeLinearLayout.LayoutParams(
                        (3f * density).toInt().coerceAtLeast(1),
                        (18f * density).toInt()
                    ).apply { marginEnd = (10f * density).toInt() }
                )
                addView(
                    title,
                    NativeLinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    chevron,
                    NativeLinearLayout.LayoutParams(
                        (18f * density).toInt(),
                        (18f * density).toInt()
                    )
                )
                setOnClickListener { toggleAdvancedCategory(category) }
            }
            val card = NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.VERTICAL
                background = skinCardBackground(monetColors.surface, 12f)
                addView(
                    header,
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    content,
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            root.addView(
                card,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = ((if (index == 0) 4f else 8f) * density).toInt()
                }
            )
            advancedCategorySections[category] = AdvancedCategorySection(
                header = header,
                content = content,
                chevron = chevron
            )
        }
    }

    /** 各分类独立切换；展开一个分类时保留其他分类的当前状态。 */
    private fun toggleAdvancedCategory(category: AdvancedSettingsCategory) {
        val target = advancedCategorySections[category] ?: return
        setAdvancedCategoryExpanded(category, expanded = !target.expanded)
    }

    private fun setAdvancedCategoryExpanded(
        category: AdvancedSettingsCategory,
        expanded: Boolean
    ) {
        val section = advancedCategorySections[category] ?: return
        if (section.expanded == expanded) return
        val chevron = section.chevron ?: return
        section.expanded = expanded
        section.header.isActivated = expanded
        animateSecondarySection(section.content, chevron, expanded)
    }

    /** 设置搜索命中隐藏分类时，先展开其所属卡片再滚动和高亮。 */
    private fun expandAdvancedCategoryContaining(target: View): Boolean {
        val match = advancedCategorySections.entries.firstOrNull { (_, section) ->
            target === section.header || target.isSameOrDescendantOf(section.content)
        } ?: return false
        if (match.value.expanded) return false
        setAdvancedCategoryExpanded(match.key, expanded = true)
        return true
    }

    private fun View.isSameOrDescendantOf(ancestor: View): Boolean {
        var candidate: View? = this
        while (candidate != null) {
            if (candidate === ancestor) return true
            candidate = candidate.parent as? View
        }
        return false
    }

    /**
     * 二级菜单公共动效。
     *
     * 旧实现用 ValueAnimator 每帧写 layoutParams.height，大型进阶菜单会让整棵设置树在
     * 每一帧重新 measure/layout。这里改为一次正常布局后只更新 alpha/translationY；
     * 两者均不触发布局，快速反复点击时先清理旧 listener 再取消动画，避免旧收起回调
     * 把刚展开的内容重新设为 GONE。
     */
    private fun animateSecondarySection(
        content: View,
        chevron: View,
        expanded: Boolean
    ) {
        val contentAnimator = content.animate()
        contentAnimator.setListener(null)
        contentAnimator.cancel()
        chevron.animate().cancel()
        val density = resources.displayMetrics.density

        if (expanded) {
            content.visibility = View.VISIBLE
            content.alpha = 0f
            content.translationY = -8f * density
            contentAnimator
                .alpha(1f)
                .translationY(0f)
                // 让首次 VISIBLE 布局先独占一帧，动画从下一帧稳定起步。
                .setStartDelay(16L)
                .setDuration(260L)
                .setInterpolator(secondaryExpandInterpolator)
                .start()
            chevron.animate()
                .rotation(180f)
                .setStartDelay(16L)
                .setDuration(260L)
                .setInterpolator(secondaryExpandInterpolator)
                .start()
        } else {
            contentAnimator
                .alpha(0f)
                .translationY(-5f * density)
                // ViewPropertyAnimator 会保留上一次 startDelay，收起时必须显式清零。
                .setStartDelay(0L)
                .setDuration(200L)
                .setInterpolator(secondaryCollapseInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        content.visibility = View.GONE
                        content.alpha = 1f
                        content.translationY = 0f
                        content.animate().setListener(null)
                    }
                })
                .start()
            chevron.animate()
                .rotation(0f)
                .setStartDelay(0L)
                .setDuration(200L)
                .setInterpolator(secondaryCollapseInterpolator)
                .start()
        }
    }

    override fun onStart() {
        super.onStart()
        UserTermsAuthorizationCoordinator.addListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.addStatusListener(frameworkStatusListener)
        if (!userTermsDecision.isAuthorized) {
            termsAuthorizationSnapshot =
                UserTermsAuthorizationCoordinator.snapshot(applicationContext)
            termsAuthorizationSnapshot?.let(::renderPendingTermsUi)
            return
        }

        val framework = RemoteHookConfigStore.status()
        frameworkServiceObserved = frameworkServiceObserved || framework.connected
        frameworkStatusCheckPending = !framework.connected && !frameworkServiceObserved
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
        if (frameworkStatusCheckPending) {
            activationMainHandler.postDelayed(
                frameworkStatusTimeout,
                FRAMEWORK_STATUS_SETTLE_MS
            )
        }
        // 用户空间在进程存活期间不会变，但宿主可能被装进/卸出当前用户，所以每次前台重采一次。
        moduleUserSpace = AndroidUserSpace.capture(applicationContext, HookEntry.TARGET_PACKAGE)
        requestLspatchHostReceipt(framework)
        renderActivationUi(framework)
        // 只在模块 App 前台、当前遥测授权有效且本地节流到期时查询有界宿主回执。
        if (!telemetryDisclosurePrompted && TelemetryStore.needsDisclosureReview(applicationContext)) {
            showTelemetryDisclosureDialog()
        }
        TelemetryCoordinator.maybeUpload(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        updateUiResumed = true
        ColdStartUpdateSession.observe(updateUiOwner, updateNoticeObserver)
        scheduleColdStartUpdate()
        renderUpdateBadge()
        if (!userTermsDecision.isAuthorized) return
        val selectionTag = InjectedUiLocale.syncFromAppCompat(applicationContext)
        InjectedUiLocale.setMirrorAndBroadcast(applicationContext, selectionTag)
        val framework = RemoteHookConfigStore.status()
        lspatchActivationReceiptTracker.startSession()
        requestLspatchHostReceipt(framework)
        renderNoRootUi()
        synchronizeNoRootSupportIfEnabled()
        scheduleReleaseHighlights()
    }

    override fun onStop() {
        UserTermsAuthorizationCoordinator.removeListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.removeStatusListener(frameworkStatusListener)
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
        lspatchActivationReceiptTracker.endSession()
        super.onStop()
    }

    override fun onPause() {
        highlightNavigationGeneration++
        highlightNavigationInFlight = false
        updateUiResumed = false
        updateUiHandler.removeCallbacksAndMessages(null)
        ColdStartUpdateSession.state.pause(updateUiOwner)
        ColdStartUpdateSession.stopObserving(updateUiOwner)
        githubUpdateBadge?.animate()?.setListener(null)?.cancel()
        // 诊断中心使用透明窗口，打开它不保证主页收到 onStop；暂停即结束回执会话，
        // 返回时由 onResume 重新查询，避免把旧宿主状态当作当前证据。
        lspatchActivationReceiptTracker.endSession()
        // 用户离开设置页前刷新一次完整快照；开关关闭时直接返回，不连接 NPatch。
        if (userTermsDecision.isAuthorized) synchronizeNoRootSupportIfEnabled()
        super.onPause()
    }

    // 同上：出现在 RuntimeSettingsSearchTarget 的构造参数里，随之放宽。
    internal enum class SettingsSearchSection {
        GENERAL,
        PURIFICATION,
        PURIFICATION_ADVANCED,
        ENHANCEMENT,
        ENHANCEMENT_ADVANCED,
        EXPERIMENTAL,
        APPEARANCE,
        COMPATIBILITY;

        val isAdvanced: Boolean
            get() = this == PURIFICATION_ADVANCED || this == ENHANCEMENT_ADVANCED
    }

    // internal 跟着 collectSettingsSearchTargets() 一起放宽：它出现在那个函数的返回类型里，
    // 留成 private 会报 "internal function exposes its private-in-class return type argument"。
    internal data class RuntimeSettingsSearchTarget(
        val item: SettingsSearchItem,
        val view: View,
        val section: SettingsSearchSection,
        val settingIds: Set<String> = emptySet()
    )

    internal fun homeRecommendFilterValues(): Map<String, Boolean> = mapOf(
        FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS to removeHomeRecommendAds,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES to removeHomeRecommendPictures,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS to removeHomeRecommendGamePromotions,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE to removeHomeRecommendLive,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC to removeHomeRecommendPgc,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS to removeHomeRecommendSpecialCards,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES to removeHomeRecommendCourses,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE to removeHomeRecommendLarge
    )

    internal fun homeRecommendFilterSummary(): String {
        val selected = homeRecommendFilterValues().values.count { it }
        return if (selected == 0) getString(R.string.home_recommend_filter_summary_none) else {
            getString(R.string.home_recommend_filter_summary_selected,
                selected, HomeRecommendFilterCatalog.preferenceKeys.size)
        }
    }

    @StringRes
    internal fun homeRecommendFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
        FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS -> R.string.remove_home_recommend_ads
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES -> R.string.remove_home_recommend_pictures
        FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS ->
            R.string.remove_home_recommend_game_promotions
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE -> R.string.remove_home_recommend_live
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC -> R.string.remove_home_recommend_pgc
        FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS -> R.string.remove_home_recommend_special_cards
        FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES -> R.string.remove_home_recommend_courses
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE -> R.string.remove_home_recommend_large
        else -> error("Unknown home recommendation filter key: $preferenceKey")
    }

    internal fun portraitContentFilterValues(): Map<String, Boolean> = mapOf(
        FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL to removeHomeRecommendVertical,
        FeaturePreferences.REMOVE_STORY_ADS to removeStoryAds,
        FeaturePreferences.REMOVE_STORY_LIVE to removeStoryLive,
        FeaturePreferences.REMOVE_STORY_GAMES to removeStoryGames,
        FeaturePreferences.REMOVE_STORY_COURSES to removeStoryCourses,
        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA to removeStoryShortDrama,
        FeaturePreferences.REMOVE_STORY_SHOPPING to removeStoryShopping,
        FeaturePreferences.REMOVE_STORY_MUSIC to removeStoryMusic,
        FeaturePreferences.REMOVE_STORY_BANGUMI to removeStoryBangumi,
        FeaturePreferences.REMOVE_STORY_MOVIES to removeStoryMovies,
        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES to removeStoryDocumentaries,
        FeaturePreferences.REMOVE_STORY_TV to removeStoryTv,
        FeaturePreferences.REMOVE_STORY_VARIETY to removeStoryVariety
    )

    internal fun portraitContentFilterSummary(): String {
        val selected = portraitContentFilterValues().values.count { it }
        return if (selected == 0) {
            getString(R.string.portrait_content_filter_summary_none)
        } else {
            getString(
                R.string.portrait_content_filter_summary_selected,
                selected,
                PortraitContentFilterCatalog.options.size
            )
        }
    }

    internal fun detailModuleFilterValues(): Map<String, Boolean> = mapOf(
        FeaturePreferences.REMOVE_DETAIL_HONOR to removeDetailHonor,
        FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER to removeDetailLiveOrder,
        FeaturePreferences.REMOVE_DETAIL_UGC_SEASON to removeDetailUgcSeason,
        FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL to removeDetailUpVipLabel,
        FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS to removeDetailTopicTags,
        FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW to removeDetailStaffFollow,
        FeaturePreferences.REMOVE_DETAIL_HOT_BANNER to removeDetailHotBanner
    )

    internal fun detailModuleFilterSummary(): String {
        val selected = detailModuleFilterValues().values.count { it }
        return if (selected == 0) {
            getString(R.string.detail_module_purify_summary_none)
        } else {
            getString(
                R.string.detail_module_purify_summary_selected,
                selected,
                DetailComponentPanelCatalog.preferenceKeys.size
            )
        }
    }

    @StringRes
    internal fun detailModuleFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
        FeaturePreferences.REMOVE_DETAIL_HONOR -> R.string.remove_detail_honor
        FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER -> R.string.remove_detail_live_order
        FeaturePreferences.REMOVE_DETAIL_UGC_SEASON -> R.string.remove_detail_ugc_season
        FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL -> R.string.remove_detail_up_vip_label
        FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS -> R.string.remove_detail_topic_tags
        FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW -> R.string.remove_detail_staff_follow
        FeaturePreferences.REMOVE_DETAIL_HOT_BANNER -> R.string.remove_detail_hot_banner
        else -> error("Unknown detail module filter key: ")
    }

    internal fun videoRelateFilterValues(): Map<String, Boolean> = mapOf(
        FeaturePreferences.REMOVE_RELATE_COMMERCIAL to removeRelateCommercial,
        FeaturePreferences.REMOVE_RELATE_GAME to removeRelateGame,
        FeaturePreferences.REMOVE_RELATE_LIVE to removeRelateLive,
        FeaturePreferences.REMOVE_RELATE_COURSE to removeRelateCourse,
        FeaturePreferences.REMOVE_RELATE_SPECIAL to removeRelateSpecial,
        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED to
            videoRelateMatchingEnhancementEnabled,
        FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED to
            videoRelateStrongModeEnabled,
        FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED to
            videoRelateReasonFilterEnabled
    )

    internal fun videoRelateFilterSummary(): String {
        val selected = VideoRelateFilterCatalog.contentOptions.count {
            videoRelateFilterValues()[it.preferenceKey] == true
        }
        return when {
            videoRelateMatchingEnhancementEnabled && videoRelateStrongModeEnabled -> getString(
                R.string.video_relate_filter_summary_strong,
                selected,
                VideoRelateFilterCatalog.contentOptions.size
            )
            videoRelateMatchingEnhancementEnabled -> getString(
                R.string.video_relate_filter_summary_enhanced,
                selected,
                VideoRelateFilterCatalog.contentOptions.size
            )
            selected == 0 -> getString(R.string.video_relate_filter_summary_none)
            else -> getString(
                R.string.video_relate_filter_summary_selected,
                selected,
                VideoRelateFilterCatalog.contentOptions.size
            )
        }
    }

    private fun settingsSearchSectionLabel(section: SettingsSearchSection): String =
        getString(
            when (section) {
                SettingsSearchSection.GENERAL -> R.string.settings_search_section_general
                SettingsSearchSection.PURIFICATION -> R.string.purify_settings
                SettingsSearchSection.PURIFICATION_ADVANCED -> R.string.purification_advanced_settings
                SettingsSearchSection.ENHANCEMENT -> R.string.enhancement_settings
                SettingsSearchSection.ENHANCEMENT_ADVANCED -> R.string.enhancement_advanced_settings
                SettingsSearchSection.EXPERIMENTAL -> R.string.experimental_features
                SettingsSearchSection.APPEARANCE -> R.string.settings_search_section_appearance
                SettingsSearchSection.COMPATIBILITY -> R.string.settings_search_section_compatibility
            }
        )

    /** 从当前本地化控件树构建索引，避免功能新增后还要维护第二份易失真的搜索目录。 */
    internal fun collectSettingsSearchTargets(): List<RuntimeSettingsSearchTarget> {
        val root = settingsSearchRoot ?: return emptyList()
        val targets = mutableListOf<RuntimeSettingsSearchTarget>()
        var nextKey = 0

        fun collectText(view: View, output: MutableList<String>) {
            if (view is NativeTextView) {
                view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collectText(view.getChildAt(index), output)
            }
        }

        fun addTarget(view: View, section: SettingsSearchSection) {
            val targetSection = when (view) {
                purificationAdvancedChevron?.parent -> SettingsSearchSection.PURIFICATION_ADVANCED
                enhancementAdvancedChevron?.parent -> SettingsSearchSection.ENHANCEMENT_ADVANCED
                appearanceChevron?.parent -> SettingsSearchSection.APPEARANCE
                compatibilityChevron?.parent -> SettingsSearchSection.COMPATIBILITY
                else -> section
            }
            val texts = mutableListOf<String>()
            collectText(view, texts)
            if (view === homeRecommendFilterEntryView) {
                // 原开关移入弹窗后，按六项原名搜索仍定位到这个入口及所属折叠区域。
                HomeRecommendFilterCatalog.preferenceKeys.forEach {
                    texts += getString(homeRecommendFilterLabel(it))
                }
            }
            val title = texts.firstOrNull()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (title.isBlank()) return
            val key = "setting-${nextKey++}"
            targets += RuntimeSettingsSearchTarget(
                item = SettingsSearchItem(
                    key = key,
                    title = title,
                    detail = texts.drop(1).joinToString(" "),
                    section = advancedCategorySections.entries.firstOrNull { (_, group) ->
                        view === group.header || view.isSameOrDescendantOf(group.content)
                    }?.let { (category, _) ->
                        settingsSearchSectionLabel(targetSection) + " · " + getString(category.titleRes)
                    } ?: settingsSearchSectionLabel(targetSection)
                ),
                view = view,
                section = targetSection,
                settingIds = settingsDestinations.idsFor(view)
            )
        }

        fun visit(view: View, inheritedSection: SettingsSearchSection) {
            val section = when (view) {
                purificationSettingsRoot -> SettingsSearchSection.PURIFICATION
                enhancementSettingsRoot -> SettingsSearchSection.ENHANCEMENT
                purificationAdvancedContent -> SettingsSearchSection.PURIFICATION_ADVANCED
                enhancementAdvancedContent -> SettingsSearchSection.ENHANCEMENT_ADVANCED
                experimentalSettingsRoot -> SettingsSearchSection.EXPERIMENTAL
                appearanceContent -> SettingsSearchSection.APPEARANCE
                compatibilityContent -> SettingsSearchSection.COMPATIBILITY
                else -> inheritedSection
            }
            val collapsedSectionRoot = view === purificationAdvancedContent ||
                view === enhancementAdvancedContent || view === appearanceContent ||
                view === compatibilityContent
                || advancedCategorySections.values.any { section ->
                    section.content === view
                }
            if (!collapsedSectionRoot && view.visibility != View.VISIBLE) return

            when {
                view is com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch ->
                    addTarget(view, section)
                view !== root && view is ViewGroup && view.isClickable ->
                    addTarget(view, section)
                view is NativeTextView && view.isClickable -> addTarget(view, section)
                view is ViewGroup -> {
                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index), section)
                    }
                }
            }
        }

        visit(root, SettingsSearchSection.GENERAL)
        return targets
    }

    private fun clearSettingsSearchTargetHighlight() {
        val highlightView = settingsSearchHighlightView
        val highlightDrawable = settingsSearchHighlightDrawable
        val highlightAnimator = settingsSearchHighlightAnimator
        val highlightRunnable = settingsSearchHighlightRunnable
        settingsSearchHighlightView = null
        settingsSearchHighlightDrawable = null
        settingsSearchHighlightAnimator = null
        settingsSearchHighlightRunnable = null
        if (highlightRunnable != null) highlightView?.removeCallbacks(highlightRunnable)
        highlightAnimator?.cancel()
        if (highlightDrawable != null) highlightView?.overlay?.remove(highlightDrawable)
    }

    internal fun scheduleSettingsSearchTargetHighlight(targetView: View) {
        clearSettingsSearchTargetHighlight()
        settingsSearchHighlightView = targetView
        val highlightRunnable = object : Runnable {
            override fun run() {
                if (settingsSearchHighlightRunnable !== this) return
                settingsSearchHighlightRunnable = null
                if (isFinishing || isDestroyed || !targetView.isAttachedToWindow ||
                    targetView.width <= 0 || targetView.height <= 0
                ) {
                    clearSettingsSearchTargetHighlight()
                    return
                }

                val density = resources.displayMetrics.density
                val highlightDrawable = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(ColorUtils.setAlphaComponent(monetColors.primary, 0x42))
                    setStroke(
                        (2f * density).toInt().coerceAtLeast(1),
                        ColorUtils.setAlphaComponent(monetColors.primary, 0xD0)
                    )
                    bounds = Rect(0, 0, targetView.width, targetView.height)
                    alpha = 0
                }
                targetView.overlay.add(highlightDrawable)
                settingsSearchHighlightDrawable = highlightDrawable

                val highlightAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                    duration = SETTINGS_SEARCH_HIGHLIGHT_DURATION_MS
                    interpolator = emphasizedDecelerate
                    addUpdateListener { animator ->
                        highlightDrawable.alpha =
                            (255f * (animator.animatedValue as Float)).toInt()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        private fun finish(animation: Animator) {
                            targetView.overlay.remove(highlightDrawable)
                            if (settingsSearchHighlightAnimator === animation) {
                                settingsSearchHighlightAnimator = null
                                settingsSearchHighlightDrawable = null
                                settingsSearchHighlightView = null
                            }
                        }

                        override fun onAnimationEnd(animation: Animator) = finish(animation)

                        override fun onAnimationCancel(animation: Animator) = finish(animation)
                    })
                }
                settingsSearchHighlightAnimator = highlightAnimator
                highlightAnimator.start()
            }
        }
        settingsSearchHighlightRunnable = highlightRunnable
        targetView.postDelayed(highlightRunnable, SETTINGS_SEARCH_HIGHLIGHT_DELAY_MS)
    }

    internal fun revealSettingsSearchTarget(target: RuntimeSettingsSearchTarget, afterReveal: (() -> Unit)? = null) {
        val primarySectionDelay = when (target.section) {
            SettingsSearchSection.PURIFICATION_ADVANCED,
            SettingsSearchSection.ENHANCEMENT_ADVANCED,
            SettingsSearchSection.APPEARANCE,
            SettingsSearchSection.COMPATIBILITY -> {
                if (setSecondaryMenuExpanded(target.section, expanded = true)) 300L else 0L
            }
            SettingsSearchSection.GENERAL,
            SettingsSearchSection.PURIFICATION,
            SettingsSearchSection.ENHANCEMENT,
            SettingsSearchSection.EXPERIMENTAL -> 0L
        }
        val categoryDelay = if (
            target.section.isAdvanced &&
            expandAdvancedCategoryContaining(target.view)
        ) {
            300L
        } else 0L
        val sectionDelay = maxOf(primarySectionDelay, categoryDelay)
        val scrollView = settingsSearchScrollView ?: return
        scrollView.postDelayed({
            if (isFinishing || isDestroyed || target.view.parent == null) return@postDelayed
            val rect = Rect()
            target.view.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(target.view, rect)
            val topPadding = (28 * resources.displayMetrics.density).toInt()
            scrollView.smoothScrollTo(0, (rect.top - topPadding).coerceAtLeast(0))
            scheduleSettingsSearchTargetHighlight(target.view)
            if (afterReveal != null) target.view.postDelayed({
                if (!isFinishing && !isDestroyed && target.view.isAttachedToWindow) afterReveal()
            },280L)
        }, sectionDelay)
    }

    private fun launchSettingsBackup() {
        val card = settingsBackupEntryView
        val title = settingsBackupEntryTitleView
        val sourceWindow = findViewById<View>(Android_R.id.content)
        if (card != null && title != null) {
            SettingsBackupTransitionOriginRegistry.register(card, title, sourceWindow)
        }
        val launchIntent = Intent(this, classOf<SettingsBackupActivity>())
        SettingsBackupTransitionOriginRegistry.snapshot()?.putInto(launchIntent)
        settingsBackupLauncher.launch(launchIntent)
        suppressLegacyActivityTransition()
    }

    private fun launchDiagnostics() {
        val entry = diagnosticsEntryView
        val title = diagnosticsEntryTitleView
        val sourceWindow = findViewById<View>(Android_R.id.content)
        if (entry != null && title != null) {
            DiagnosticsTransitionOriginRegistry.register(entry, title, sourceWindow)
        }
        val launchIntent = Intent(this, classOf<DiagnosticsActivity>())
        DiagnosticsTransitionOriginRegistry.snapshot()?.putInto(launchIntent)
        startActivity(launchIntent)
        suppressLegacyActivityTransition()
    }

    @Suppress("DEPRECATION")
    private fun suppressLegacyActivityTransition() {
        if (AndroidVersion.isAtMost(AndroidVersion.T)) {
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        highlightsDisposed = true
        settingsDestinations.clear()
        updateUiHandler.removeCallbacksAndMessages(null)
        ColdStartUpdateSession.state.pause(updateUiOwner)
        ColdStartUpdateSession.stopObserving(updateUiOwner)
        githubUpdateBadge?.animate()?.setListener(null)?.cancel()
        githubUpdateBadge = null
        UserTermsAuthorizationCoordinator.removeListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.removeStatusListener(frameworkStatusListener)
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
        lspatchActivationReceiptTracker.endSession()
        finishPreparedLiquidStretch(liquidStretchViewport)
        liquidStretchViewport = null
        liquidStretchScrollTarget = null
        clearSettingsSearchTargetHighlight()
        settingsSearchRoot = null
        settingsSearchScrollView = null
        // Activity 销毁时主动关闭弹窗，避免 WindowLeaked（Activity has leaked window）
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        liquidBackgroundDialog?.dismiss()
        liquidBackgroundDialog = null
        liquidBackgroundDialogContainer = null
        liquidBackgroundTask?.cancel(true)
        liquidBackgroundTask = null
        liquidBackgroundWorker.shutdownNow()
        // 清理 View 引用字段，彻底断开对 hierarchy 的持有
        appearanceContent?.animate()?.setListener(null)
        appearanceContent?.animate()?.cancel()
        appearanceChevron?.animate()?.cancel()
        compatibilityContent?.animate()?.setListener(null)
        compatibilityContent?.animate()?.cancel()
        compatibilityChevron?.animate()?.cancel()
        purificationAdvancedContent?.animate()?.setListener(null)
        purificationAdvancedContent?.animate()?.cancel()
        purificationAdvancedChevron?.animate()?.cancel()
        enhancementAdvancedContent?.animate()?.setListener(null)
        enhancementAdvancedContent?.animate()?.cancel()
        enhancementAdvancedChevron?.animate()?.cancel()
        advancedCategorySections.values.forEach { section ->
            section.content.animate().setListener(null)
            section.content.animate().cancel()
            section.chevron?.animate()?.cancel()
        }
        advancedCategorySections.clear()
        advancedCategoryMarkers.clear()
        experimentalSettingsRoot = null
        appearanceContent = null
        appearanceChevron = null
        compatibilityContent = null
        compatibilityChevron = null
        purificationSettingsRoot = null
        enhancementSettingsRoot = null
        purificationAdvancedContent = null
        purificationAdvancedChevron = null
        enhancementAdvancedContent = null
        enhancementAdvancedChevron = null
        noRootSwitch = null
        noRootStatusView = null
        noRootPrefsBridge = null
        termsDialogHintView = null
        termsManagerLauncher = null
        termsPendingStatusView = null
        termsDiagnosticsValueView = null
        termsAuthorizationSnapshot = null
        activationCardView = null
        activationIconView = null
        activationTitleView = null
        activationSourceView = null
        activationVersionView = null
        DiagnosticsTransitionOriginRegistry.clear(diagnosticsEntryView)
        diagnosticsEntryView = null
        diagnosticsEntryTitleView = null
        diagnosticsSummaryView = null
        logLevelColorAnimator?.cancel()
        logLevelColorAnimator = null
        logLevelThumb?.animate()?.cancel()
        logLevelMinimalPill = null
        logLevelCompletePill = null
        logLevelThumb = null
        logLevelDesc = null
        playerQualitySummaryView = null
        playerLongPressSpeedSummary = null
        playerDefaultSpeedSummary = null
        homeTabRulesSummaryView = null
        homeRecommendTitleSummaryView = null
        homeRecommendFilterEntryView = null
        homeRecommendFilterSummaryView = null
        homeComponentRulesSummaryView = null
        mineComponentRulesSummaryView = null
        bottomBarRulesSummaryView = null
        recommendVideoDurationSummaryView = null
        commentKeywordSummaryView = null
        commentLevelSummaryView = null
        commentUserFilterSummaryView = null
        dynamicKeywordSummaryView = null
        dynamicAuthorSummaryView = null
        searchKeywordSummaryView = null
        searchAuthorSummaryView = null
        danmakuWeightSummaryView = null
        portraitContentFilterSummaryView = null
        videoRelateFilterSummaryView = null
        skinSummaryView = null
        liquidBackgroundSummaryView = null
        SettingsBackupTransitionOriginRegistry.clear(settingsBackupEntryView)
        settingsBackupEntryView = null
        settingsBackupEntryTitleView = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingHighlightsFrom = ReleaseHighlightsStore.prepare(applicationContext)
        if (savedInstanceState?.containsKey("highlights_from") == true) {
            savedInstanceState.getInt("highlights_from",-1)
                .takeIf { it in 0 until ReleaseHighlightsCatalog.currentRevision }?.let {
                    pendingHighlightsFrom = it
                    pendingHighlightsAutomatic = savedInstanceState.getBoolean("highlights_automatic",true)
                }
        }
        pendingHighlightDestination = savedInstanceState?.getString("highlights_destination")
            ?.takeIf { id -> ReleaseHighlightsCatalog.destinations.any { it.settingId == id } }

        // Base activity background（应用 Monet 动态背景色）
        findViewById<View>(Android_R.id.content).setBackgroundColor(monetColors.background)

        // 条款门禁必须先于现有 prefs、跨进程镜像、主布局和自动更新检查。
        termsConsentState = UserTermsConsentStore.readStateOrInitialize(applicationContext)
        userTermsDecision = termsConsentState.decision
        if (!userTermsDecision.isAuthorized) {
            termsAuthorizationSnapshot =
                UserTermsAuthorizationCoordinator.snapshot(applicationContext)
            when {
                termsConsentState.isAcceptancePending -> showPendingTermsPage(
                    requireNotNull(termsAuthorizationSnapshot)
                )
                userTermsDecision == UserTermsDecision.UNDECIDED -> showUserTermsGate()
                userTermsDecision == UserTermsDecision.DECLINED -> showUserTermsDeclinedPage()
                else -> Unit
            }
            return
        }
        val attributeRuntimeCheck = runCatching { classOf<AttributeSetResolver>() }
        if (attributeRuntimeCheck.isFailure) {
            showSettingsUiCompatibilityFallback(
                reason = "attribute-runtime-unavailable",
                failure = attributeRuntimeCheck.exceptionOrNull()
            )
            return
        }
        prepareSkinSession()

        // 读取广告开关配置：prefs() 只创建一次跨进程 bridge，两个开关复用（降低初始化开销）
        val modulePrefs = runCatching { prefs() }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "init prefs failed", t)
        }.getOrNull()
        noRootPrefsBridge = modulePrefs
        // 设置页对模块偏好的一次性只读快照。原来这里往下有 115 段
        // `runCatching { modulePrefs?.getX(KEY, default) }.getOrDefault(default)` 样板，
        // 默认值被抄了第三份（另两份在字段初始值与 SettingsCatalog）。现在默认值只有目录一个
        // 来源，读取只加锁一次。**onCreate 内同步执行的偏好写入只有两处 putLong**
        // （心跳时间戳、自由复制修订号），都不在这些读取键里，所以快照不会读到过期值。
        val uiSettings = ModuleUiSettings.read(modulePrefs)
        noRootDesiredEnabled = NoRootSupportStore.isDesiredEnabled(applicationContext)
        if (
            modulePrefs != null &&
            SettingsImportApplier.hasPendingRecovery(applicationContext)
        ) {
            runCatching {
                check(
                    SettingsImportApplier.recoverPending(
                        applicationContext,
                        ModuleSettingsStore(modulePrefs)
                    )
                ) { "pending settings import is not fully recovered" }
            }.onFailure { throwable ->
                Log.w("BilibiliInnocentLab", "recover pending settings import failed", throwable)
            }
        }
        // 写 prefs 通道哨兵（时间戳）：B 站进程据此判断 YukiHookAPI prefs 跨进程通道
        // 是否可用——可用时开关解析「确定关闭」才删除 hookinfo.pb 还原原生；不可用
        // （部分 LSPosed 版本无 DirectAccessService/路径差异）时保守不删，避免
        // 每次冷启动误删有效缓存导致全量分析重建（冷启动慢的根源）
        runCatching {
            modulePrefs?.edit { putLong(HookEntry.PREF_PREFS_ALIVE_TS, System.currentTimeMillis()) }
        }
        adskipEnabled = uiSettings.bool(HookEntry.PREF_ENABLED)
        gamecardAdEnabled = uiSettings.bool(HookEntry.PREF_GAMECARD_ENABLED)
        hideVideoDetailAppPromotion = uiSettings.bool(FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION)
        bannerAdEnabled = uiSettings.bool(HookEntry.PREF_BANNER_ENABLED)
        hideHomeGameMenu = uiSettings.bool(FeaturePreferences.HIDE_HOME_GAME_MENU)
        hideHomeSearchDefaultWord = uiSettings.bool(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD)
        homeVerticalOpenDetail = uiSettings.bool(FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL)
        removeHomeRecommendAds = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS)
        removeHomeRecommendCmV2 = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2)
        removeHomeRecommendPictures = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES)
        removeHomeRecommendGamePromotions = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS)
        homeRecommendTitleFilterEnabled = uiSettings.bool(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED)
        homeRecommendTitleKeywords = uiSettings.string(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS)
        removeHomeRecommendLive = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE)
        removeHomeRecommendCourses = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES)
        removeHomeRecommendVertical = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL)
        removeHomeRecommendLarge = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE)
        removeHomeRecommendPgc = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC)
        removeHomeRecommendSpecialCards = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS)
        homeTabHiddenRules = uiSettings.string(FeaturePreferences.HOME_TAB_HIDDEN_RULES)
        homeComponentHiddenRules = uiSettings.string(FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES)
        bottomBarHiddenRules = uiSettings.string(FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES)
        recommendVideoMinDurationSeconds =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS)
                .coerceAtLeast(0)
        recommendVideoMaxDurationSeconds =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS)
                .coerceAtLeast(0)
        removeStoryAds = uiSettings.bool(FeaturePreferences.REMOVE_STORY_ADS)
        removeStoryLive = uiSettings.bool(FeaturePreferences.REMOVE_STORY_LIVE)
        removeStoryGames = uiSettings.bool(FeaturePreferences.REMOVE_STORY_GAMES)
        removeStoryBangumi = uiSettings.bool(FeaturePreferences.REMOVE_STORY_BANGUMI)
        removeStoryCourses = uiSettings.bool(FeaturePreferences.REMOVE_STORY_COURSES)
        removeStoryShortDrama = uiSettings.bool(FeaturePreferences.REMOVE_STORY_SHORT_DRAMA)
        removeStoryShopping = uiSettings.bool(FeaturePreferences.REMOVE_STORY_SHOPPING)
        removeStoryMovies = uiSettings.bool(FeaturePreferences.REMOVE_STORY_MOVIES)
        removeStoryDocumentaries = uiSettings.bool(FeaturePreferences.REMOVE_STORY_DOCUMENTARIES)
        removeStoryTv = uiSettings.bool(FeaturePreferences.REMOVE_STORY_TV)
        removeStoryVariety = uiSettings.bool(FeaturePreferences.REMOVE_STORY_VARIETY)
        removeStoryMusic = uiSettings.bool(FeaturePreferences.REMOVE_STORY_MUSIC)
        hideMineVip = uiSettings.bool(FeaturePreferences.HIDE_MINE_VIP)
        keepMineVipSpace = uiSettings.bool(FeaturePreferences.KEEP_MINE_VIP_SPACE)
        mineComponentHiddenRules = uiSettings.string(FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES)
        blockAppUpdate = uiSettings.bool(FeaturePreferences.BLOCK_APP_UPDATE)
        hideDynamicCityTab = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_CITY_TAB)
        hideDynamicSchoolTab = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB)
        preferDynamicVideoTab = uiSettings.bool(FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB)
        showFullNumbers = uiSettings.bool(FeaturePreferences.SHOW_FULL_NUMBERS)
        hidePlayerPortraitControl = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL)
        hidePlayerInteractiveOverlays = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS)
        hidePgcAutoActivityPopup = uiSettings.bool(FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP)
        transparentPlayerStatusBar = uiSettings.bool(FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR)
        removeDetailHonor = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_HONOR)
        removeDetailLiveOrder = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER)
        removeDetailUgcSeason = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_UGC_SEASON)
        removeDetailUpVipLabel = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL)
        removeDetailTopicTags = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS)
        removeDetailStaffFollow = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW)
        removeDetailHotBanner = uiSettings.bool(FeaturePreferences.REMOVE_DETAIL_HOT_BANNER)
        removeRelateCommercial = uiSettings.bool(FeaturePreferences.REMOVE_RELATE_COMMERCIAL)
        removeRelateGame = uiSettings.bool(FeaturePreferences.REMOVE_RELATE_GAME)
        removeRelateLive = uiSettings.bool(FeaturePreferences.REMOVE_RELATE_LIVE)
        removeRelateCourse = uiSettings.bool(FeaturePreferences.REMOVE_RELATE_COURSE)
        removeRelateSpecial = uiSettings.bool(FeaturePreferences.REMOVE_RELATE_SPECIAL)
        videoRelateMatchingEnhancementEnabled = uiSettings.bool(FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED)
        videoRelateStrongModeEnabled = uiSettings.bool(FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED)
        videoRelateReasonFilterEnabled = uiSettings.bool(FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED)
        videoRelateReasonFilterKeywords =
            uiSettings.string(FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS)
                .take(VideoRelateFilterDraft.MAX_KEYWORDS_LENGTH)
        playerDefaultQualityQn = PlayerQualityConfig.normalize(
            uiSettings.int(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN)
        )
        playerDisableLongPress = uiSettings.bool(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS)
        playerLongPressSpeedPercent = PlayerSpeedConfig.normalize(
            uiSettings.int(FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT)
        )
        playerDefaultSpeedPercent = PlayerSpeedConfig.normalize(
            uiSettings.int(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT)
        )
        removeCommentSearchLinks = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS)
        removeCommentEmptyGuide = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE)
        removeCommentVoteWidgets = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS)
        removeCommentFollowButtons = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS)
        removeCommentQoe = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_QOE)
        removeCommentOperations = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_OPERATIONS)
        blockCommentQuickReply = uiSettings.bool(FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY)
        hideCommentSection = uiSettings.bool(FeaturePreferences.HIDE_COMMENT_SECTION)
        replyTopologyEnabled = uiSettings.bool(FeaturePreferences.REPLY_TOPOLOGY_ENABLED)
        commentKeywordFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED)
        commentFilterKeywords = uiSettings.string(FeaturePreferences.COMMENT_FILTER_KEYWORDS)
        commentMinLevelFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED)
        commentMinLevel =
            uiSettings.int(FeaturePreferences.COMMENT_MIN_LEVEL).coerceIn(1, 6)
        dynamicKeywordFilterEnabled = uiSettings.bool(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED)
        dynamicFilterKeywords = uiSettings.string(FeaturePreferences.DYNAMIC_FILTER_KEYWORDS)
        dynamicAuthorFilterEnabled = uiSettings.bool(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED)
        dynamicAuthorFilterRules = uiSettings.string(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES)
        removeDynamicPromotions = uiSettings.bool(FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS)
        removeDynamicChargeOnly = uiSettings.bool(FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY)
        hideDynamicTopicList = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST)
        hideDynamicFrequentVisits = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS)
        removeDynamicLiveUpEntries = uiSettings.bool(FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES)
        removeSearchCommercial = uiSettings.bool(FeaturePreferences.REMOVE_SEARCH_COMMERCIAL)
        hideSearchHomeRecommend = uiSettings.bool(FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND)
        searchKeywordFilterEnabled = uiSettings.bool(FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED)
        searchFilterKeywords = uiSettings.string(FeaturePreferences.SEARCH_FILTER_KEYWORDS)
        searchAuthorFilterEnabled = uiSettings.bool(FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED)
        searchAuthorFilterRules = uiSettings.string(FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES)
        removeAtOnlyComments = uiSettings.bool(FeaturePreferences.REMOVE_AT_ONLY_COMMENTS)
        commentUserFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_USER_FILTER_ENABLED)
        commentUserFilterRules = uiSettings.string(FeaturePreferences.COMMENT_USER_FILTER_RULES)
        danmakuWeightFilterEnabled = uiSettings.bool(FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED)
        danmakuWeightMinimum = DanmakuPurifyPolicy.normalizeWeight(
            uiSettings.int(FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM)
        )
        removeVipColorfulDanmaku = uiSettings.bool(FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU)
        blockLiveRoomSwitch = uiSettings.bool(FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH)
        liveRoomDoubleTapPause = uiSettings.bool(FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE)
        purifyShareContent = uiSettings.bool(FeaturePreferences.PURIFY_SHARE_CONTENT)
        shareMiniProgramDirectLink = uiSettings.bool(FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK)
        forceExternalBrowser = uiSettings.bool(FeaturePreferences.FORCE_EXTERNAL_BROWSER)
        systemMediaNotification = uiSettings.bool(FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION)
        splashAutoNight = uiSettings.bool(FeaturePreferences.SPLASH_AUTO_NIGHT)
        showBvAsAv = uiSettings.bool(FeaturePreferences.SHOW_BV_AS_AV)
        blockTeenagersModePrompt = uiSettings.bool(FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT)
        purifySplashAds = uiSettings.bool(FeaturePreferences.PURIFY_SPLASH_ADS)
        merchAdEnabled = uiSettings.bool(HookEntry.PREF_MERCH_ENABLED)
        freeCopyEnabled = uiSettings.bool(HookEntry.PREF_FREE_COPY_ENABLED)
        freeCopyDescEnabled = uiSettings.bool(HookEntry.PREF_FREE_COPY_DESC_ENABLED)
        // 跨进程权威镜像：避开 LSPosed API 93+ 对默认 SharedPreferences 的重定向。
        // 文件很小且只在模块 UI 启动/切换时写入，不进入 B 站启动或滚动热路径。
        runCatching {
            val revision = System.currentTimeMillis().coerceAtLeast(1L)
            modulePrefs?.edit { putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision) }
            check(
                FreeCopyConfigStore.write(
                    applicationContext,
                    freeCopyEnabled,
                    freeCopyDescEnabled,
                    revision
                )
            ) { "atomic mirror write returned false" }
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "write free copy config mirror failed", t)
        }
        freeCopyLightMode = uiSettings.bool(HookEntry.PREF_FREE_COPY_LIGHT_MODE)
        freeCopyAutoLight = uiSettings.bool(HookEntry.PREF_FREE_COPY_AUTO_LIGHT)
        roamingCompatEnabled = uiSettings.bool(HookEntry.PREF_ROAMING_COMPAT_ENABLED)
        predictiveBackEnabled = uiSettings.bool(HookEntry.PREF_PREDICTIVE_BACK_ENABLED)
        applyPredictiveBack()
        logEnabled = uiSettings.bool(HookEntry.PREF_LOG_ENABLED)
        logVerbose =
            uiSettings.string(HookEntry.PREF_LOG_LEVEL) != HookEntry.LOG_LEVEL_MINIMAL

        // UI view based on Hikage DSL
        // See: https://github.com/BetterAndroid/Hikage
        // Don't like it or want to switch back to XML writing? Can refer to res/layout/activity_main.xml
        // 不喜欢或者想切换回 XML 写法？可以参考 res/layout/activity_main.xml
        try {
            setContentView {
                LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        gravity = Gravity.CENTER or Gravity.START
                        updatePadding(horizontal = 15.dp)
                        updatePadding(top = 13.dp, bottom = 5.dp)
                        clipChildren = false
                        clipToPadding = false
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(27.dp, 27.dp) {
                            marginStart = 5.dp
                        }
                    ) {
                        background = selfRippleBackground(14f)
                        alpha = 0.85f
                        setImageResource(R.drawable.ic_search)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        contentDescription = stringResource(R.string.settings_search_description)
                        setOnClickListener { showSettingsSearchDialog(it) }
                    }
                    Space(lparams = LayoutParams { weight = 1f })
                    ImageView(
                        lparams = LayoutParams(27.dp, 27.dp) {
                            marginEnd = 12.dp
                        }
                    ) {
                        background = selfRippleBackground(14f)
                        alpha = 0.85f
                        setImageResource(R.drawable.ic_restart)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        contentDescription = stringResource(R.string.restart_bilibili)
                        setOnClickListener { showRestartConfirmDialog(it) }
                    }
                    // 只占原图标的空间，角标叠放，不改变工具栏高度或相邻按钮位置。
                    FrameLayout(
                        lparams = LayoutParams(27.dp, 27.dp) { marginEnd = 5.dp },
                        init = {
                            clipChildren = false
                            clipToPadding = false
                        }
                    ) {
                        ImageView(
                            lparams = LayoutParams(27.dp, 27.dp)
                        ) {
                            background = selfRippleBackground(14f)
                            alpha = 0.85f
                            setImageResource(R.mipmap.ic_github)
                            imageTintList = stateColorResource(R.color.colorTextGray)
                            contentDescription = stringResource(R.string.github_menu_description)
                            setOnClickListener { showGitHubMenuDialog(it) }
                        }
                        TextView(
                            lparams = LayoutParams(22.dp, 15.dp) {
                                gravity = Gravity.END or Gravity.TOP
                                marginEnd = -5.dp
                                topMargin = -5.dp
                            }
                        ) {
                            githubUpdateBadge?.animate()?.setListener(null)?.cancel()
                            githubUpdateBadge = this
                            text = stringResource(R.string.github_new_update_badge)
                            textSize = 8f
                            includeFontPadding = false
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textColor = monetColors.onPrimary
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, 3.dp)
                            background = com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.GithubUpdateBadgeDrawable(
                                monetColors.primary, resources.displayMetrics.density)
                            skinUpdateBadge(this)
                            visibility = View.INVISIBLE
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                ColdStartUpdateSession.state.noticeFor(UpdateChannelStore.read(applicationContext))?.let {
                                    showUpdateDialogWhenIdle(it.channel, it.release)
                                }
                            }
                            val badge = this
                            fun updateHitTarget() {
                                val frame = badge.parent as? ViewGroup ?: return
                                val toolbar = frame.parent as? ViewGroup ?: return
                                val hit = android.graphics.Rect(frame.left + badge.left, frame.top + badge.top,
                                    frame.left + badge.right, frame.top + badge.bottom)
                                hit.inset(-2.dp, -2.dp)
                                toolbar.touchDelegate = object : android.view.TouchDelegate(hit, badge) {
                                    override fun onTouchEvent(event: android.view.MotionEvent): Boolean =
                                        badge.visibility == View.VISIBLE && super.onTouchEvent(event)
                                }
                            }
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHitTarget() }
                            post { updateHitTarget(); renderUpdateBadge() }
                        }
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true) {
                        updateMargins(horizontal = 15.dp)
                        updateMargins(top = 10.dp, bottom = 5.dp)
                    },
                    init = {
                        gravity = Gravity.CENTER or Gravity.START
                        // 首次绘制使用中性确认态；布局完成后由单快照 renderer 统一收敛。
                        background = roundedColor(monetColors.surfaceVariant)
                        activationCardView = this
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(25.dp, 25.dp) {
                            marginStart = 25.dp
                            marginEnd = 5.dp
                        }
                    ) {
                        activationIconView = this
                        setImageResource(R.mipmap.ic_warn)
                        imageTintList = stateColorResource(R.color.white)
                    }
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            updatePadding(horizontal = 20.dp, vertical = 10.dp)
                        }
                    ) {
                        LinearLayout(
                            lparams = LayoutParams { weight = 1f },
                            init = {
                                orientation = LinearLayout.VERTICAL
                            }
                        ) {
                            TextView(
                                lparams = LayoutParams {
                                    bottomMargin = 5.dp
                                }
                            ) {
                                activationTitleView = this
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                textColor = colorResource(R.color.white)
                                textSize = 18f
                                text = stringResource(R.string.module_activation_checking)
                            }
                            TextView(
                                lparams = LayoutParams {
                                    bottomMargin = 5.dp
                                }
                            ) {
                                activationSourceView = this
                                alpha = 0.6f
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                textColor = colorResource(R.color.white)
                                textSize = 11f
                                text = stringResource(R.string.module_activation_waiting_framework)
                                isVisible = true
                            }
                            LinearLayout(
                                lparams = LayoutParams {
                                    bottomMargin = 5.dp
                                },
                                init = {
                                    gravity = Gravity.CENTER or Gravity.START
                                }
                            ) {
                                TextView {
                                    activationVersionView = this
                                    alpha = 0.8f
                                    isSingleLine = true
                                    ellipsize = TextUtils.TruncateAt.END
                                    textColor = colorResource(R.color.white)
                                    textSize = 13f
                                    text = stringResource(
                                        R.string.module_version,
                                        BuildConfig.VERSION_NAME
                                    )
                                }
                                TextView(
                                    lparams = LayoutParams {
                                        leftMargin = 5.dp
                                    }
                                ) {
                                    background = roundedColor(monetColors.tertiary)
                                    updatePadding(horizontal = 5.dp, vertical = 2.dp)
                                    isSingleLine = true
                                    ellipsize = TextUtils.TruncateAt.END
                                    textColor = colorResource(R.color.white)
                                    textSize = 11f
                                    isVisible = false
                                }
                            }
                            // 模板遗留占位文本：隐藏（无实际信息，与整体 UI 不一致）
                            TextView {
                                alpha = 0.8f
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                textColor = colorResource(R.color.white)
                                textSize = 13f
                                isVisible = false
                            }
                        }
                        LinearLayout(
                            lparams = LayoutParams(96.dp, 72.dp) {
                                marginStart = 10.dp
                            },
                            init = {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                val density = resources.displayMetrics.density
                                val neutralColor = colorResource(R.color.colorTextGray)
                                val darkTheme = (resources.configuration.uiMode and
                                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                                val surfaceColor = ColorUtils.setAlphaComponent(
                                    neutralColor,
                                    DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme)
                                )
                                background = skinMotionSurfaceBackground(
                                    surfaceColor,
                                    DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP
                                )
                                val outline = GradientDrawable().apply {
                                    cornerRadius = DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP * density
                                    setColor(android.graphics.Color.TRANSPARENT)
                                    setStroke(
                                        (DiagnosticsEntryVisualSpec.STROKE_WIDTH_DP * density)
                                            .toInt()
                                            .coerceAtLeast(2),
                                        ColorUtils.setAlphaComponent(
                                            neutralColor,
                                            DiagnosticsEntryVisualSpec.STROKE_ALPHA
                                        )
                                    )
                                }
                                foreground = android.graphics.drawable.LayerDrawable(
                                    arrayOf(
                                        outline,
                                        selfRippleBackground(
                                            DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP
                                        )
                                    )
                                )
                                updatePadding(horizontal = 9.dp, vertical = 7.dp)
                                isClickable = true
                                isFocusable = true
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                contentDescription = stringResource(R.string.diagnostics_title)
                                diagnosticsEntryView = this
                                setOnClickListener { launchDiagnostics() }
                            }
                        ) {
                            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                                diagnosticsEntryTitleView = this
                                gravity = Gravity.CENTER
                                text = stringResource(R.string.diagnostics_title)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 12f
                                setTypeface(typeface, Typeface.BOLD)
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                post {
                                    val entry = diagnosticsEntryView ?: return@post
                                    DiagnosticsTransitionOriginRegistry.register(
                                        entry,
                                        this,
                                        this@MainActivity.findViewById(Android_R.id.content)
                                    )
                                }
                            }
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 5.dp
                                }
                            ) {
                                diagnosticsSummaryView = this
                                gravity = Gravity.CENTER
                                alpha = 1f
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                textColor = colorResource(R.color.colorTextDark)
                                textSize = 11f
                                setTypeface(typeface, Typeface.BOLD)
                                text = stringResource(R.string.diagnostics_entry_checking)
                            }
                        }
                    }
                }
                NestedScrollView(
                    lparams = LayoutParams(matchParent = true) {
                        updateMargins(vertical = 10.dp)
                    },
                    init = {
                        liquidStretchScrollTarget = this
                        settingsSearchScrollView = this
                        isFillViewport = true
                        isVerticalFadingEdgeEnabled = true
                    }
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.VERTICAL
                            settingsSearchRoot = this
                        }
                    ) {
                        settingsBackupEntryCard()
                        Space(lparams = LayoutParams(height = 10.dp))
                        purifySettingsCard()
                        Space(lparams = LayoutParams(height = 10.dp))
                        enhancementSettingsCard(uiSettings)
                        Space(lparams = LayoutParams(height = 10.dp))
                        experimentalFeaturesCard()
                        Space(lparams = LayoutParams(height = 10.dp))
                        logSettingsCard()
                        Space(lparams = LayoutParams(height = 10.dp))
                        Layout(createPromotionItem(R.string.about_module, R.mipmap.ic_yukihookapi))
                        Space(lparams = LayoutParams(height = 10.dp))
                        Layout(createPromotionItem(R.string.about_module_extension, R.mipmap.ic_kavaref))
                    }
                }
                }
            }
        } catch (throwable: Throwable) {
            if (!SettingsUiCompatibility.isInvalidXmlDocumentFailure(throwable)) throw throwable
            showSettingsUiCompatibilityFallback(
                reason = "stale-attribute-document",
                failure = throwable
            )
            return
        }

        liquidStretchViewport = liquidStretchScrollTarget?.let {
            installPreparedLiquidStretch(it)
        }
        val skinRoot = findViewById<View>(Android_R.id.content)
        bindPreparedSkinRoot(
            skinRoot,
            ::handleSkinRendererFailure
        )
        // 两次 animation callback 跨过首次 traversal，刷新首帧实际降级后的后端名称。
        skinRoot.postOnAnimation {
            skinRoot.postOnAnimation {
                if (!isFinishing && !isDestroyed) skinSummaryView?.text = currentSkinSummary()
            }
        }
        // 两个进阶菜单已在各自大类末尾；首帧前只整理区域分组，不移动顶层卡片。
        installAdvancedCategorySections()
        renderNoRootUi()
        // 布局完成后定位日志档位滑块（宽度收缩为一半 + 对齐当前档位）
        findViewById<View>(Android_R.id.content).post {
            positionLogLevelThumb()
        }
        // 首次连续前台停留十秒后，每进程至多一次自动检查；不在布局重建时重复启动。
        scheduleColdStartUpdate()
    }

    /** 设置备份入口卡片。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.settingsBackupEntryCard() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(horizontal = 15.dp)
            },
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = skinCardBackground(monetColors.surfaceVariant)
                foreground = selfRippleBackground(15f)
                updatePadding(horizontal = 15.dp, vertical = 14.dp)
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                settingsBackupEntryView = this
                contentDescription = buildString {
                    append(stringResource(R.string.settings_backup_entry_title))
                    append(". ")
                    append(stringResource(R.string.settings_backup_entry_summary))
                }
                setOnClickListener {
                    launchSettingsBackup()
                }
            }
        ) {
            ImageView(
                lparams = LayoutParams(22.dp, 22.dp) {
                    marginEnd = 12.dp
                }
            ) {
                setImageResource(R.drawable.ic_backup_restore)
                imageTintList = stateColorResource(R.color.colorTextGray)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            LinearLayout(
                lparams = LayoutParams {
                    weight = 1f
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    settingsBackupEntryTitleView = this
                    text = stringResource(R.string.settings_backup_entry_title)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    post {
                        val card = settingsBackupEntryView ?: return@post
                        val sourceWindow = findViewById<View>(Android_R.id.content)
                        SettingsBackupTransitionOriginRegistry.register(
                            card,
                            this,
                            sourceWindow
                        )
                    }
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = 4.dp
                    }
                ) {
                    alpha = 0.68f
                    text = stringResource(R.string.settings_backup_entry_summary)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
            }
            TextView(lparams = LayoutParams(28.dp, 40.dp)) {
                gravity = Gravity.CENTER
                text = "›"
                textColor = colorResource(R.color.colorTextGray)
                textSize = 24f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
    }

    /** 净化设置卡片；进阶子分区的分类标题在此注册 marker，顺序必须与 AdvancedSettingsCategory 一致。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifySettingsCard() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(horizontal = 15.dp)
            },
            init = {
                purificationSettingsRoot = this
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
            }
        ) {
            // 大类主标题：净化
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 12.dp
                },
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                }
            ) {
                ImageView(
                    lparams = LayoutParams(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_purify)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    alpha = 0.85f
                    isSingleLine = true
                    text = stringResource(R.string.purify_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
            }
            // 子项 1：视频提及游戏广告
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 4.dp
                }
            ) {
                alpha = 0.7f
                isSingleLine = true
                text = stringResource(R.string.gamecard_ad_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 11f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.gamecard_ad_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = gamecardAdEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    gamecardAdEnabled = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_GAMECARD_ENABLED, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write gamecard prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.gamecard_ad_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.hide_video_detail_app_promotion)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = hideVideoDetailAppPromotion
                setOnCheckedChangeListener { _, isChecked ->
                    hideVideoDetailAppPromotion = isChecked
                    runCatching {
                        prefs().edit {
                            putBoolean(
                                FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
                                isChecked
                            )
                        }
                    }.onFailure { t ->
                        Log.e(
                            "BilibiliInnocentLab",
                            "write video detail app promotion prefs failed",
                            t
                        )
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true)
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.hide_video_detail_app_promotion_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 隐藏 UP主分享好物推广（简介区商品广告——归类到视频详细页广告下）
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.merch_ad_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = merchAdEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    merchAdEnabled = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_MERCH_ENABLED, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write merch prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 0.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.merch_ad_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 视频暂停页广告（暂停视频后可能弹出的推广大卡；原实验性功能正式化）
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.paused_page_ad_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = adskipEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    adskipEnabled = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_ENABLED, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write paused page ad prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 0.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.paused_page_ad_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 功能区分隔线
            FrameLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                    topMargin = 14.dp
                    bottomMargin = 14.dp
                },
                init = {
                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                }
            )
            // 子项 2：首页大卡轮播
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 4.dp
                }
            ) {
                alpha = 0.7f
                isSingleLine = true
                text = stringResource(R.string.banner_ad_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 11f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.banner_ad_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = bannerAdEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    bannerAdEnabled = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_BANNER_ENABLED, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write banner prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.banner_ad_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 14.dp
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER or Gravity.START
                    background = skinCardBackground(monetColors.surface, 12f)
                    updatePadding(
                        left = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                        top = 5.dp,
                        right = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                        bottom = 5.dp
                    )
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        gravity = Gravity.CENTER or Gravity.START
                        updatePadding(vertical = 10.dp)
                        setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.PURIFICATION_ADVANCED) }
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(15.dp, 15.dp) {
                            marginEnd = 10.dp
                        }
                    ) {
                        setImageResource(R.drawable.ic_purify)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                    }
                    TextView(
                        lparams = LayoutParams { weight = 1f }
                    ) {
                        alpha = 0.85f
                        maxLines = 2
                        text = stringResource(R.string.purification_advanced_settings)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 12f
                    }
                    ImageView(
                        lparams = LayoutParams(18.dp, 18.dp)
                    ) {
                        purificationAdvancedChevron = this
                        setImageResource(R.drawable.ic_chevron_down)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        alpha = 0.85f
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        purificationAdvancedContent = this
                        updatePadding(bottom = 10.dp)
                    }
                ) {
                    purifyHomeCategory()
                    purifyNavigationCategory()
                    purifySearchCategory()
                    purifyMineCategory()
                    purifyPlaybackCategory()
                    purifyCommentsCategory()
                    purifyStartupCategory()
                    purifyShareCategory()
                    purifySharedCategory()
                }
            }
        }
    }

    /** 增强设置卡片；进阶子分区的分类标题在此注册 marker，顺序必须与 AdvancedSettingsCategory 一致。 */
    /**
     * @param uiSettings 只有播放能力那一组是**按动态 key 循环**建开关的，拿不到对应的镜像字段，
     *   所以要把启动快照传进来。刻意用参数而不是字段：快照只在 `onCreate` 构建期有效，
     *   写回偏好之后它就是过期的（见 AGENTS.md 里 `uiSettings` 那条红线）。
     */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhancementSettingsCard(
        uiSettings: ModuleUiSettings
    ) {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(horizontal = 15.dp)
            },
            init = {
                enhancementSettingsRoot = this
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
            }
        ) {
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 12.dp
                },
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                }
            ) {
                ImageView(
                    lparams = LayoutParams(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_enhancement)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    alpha = 0.85f
                    isSingleLine = true
                    text = stringResource(R.string.enhancement_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
            }
            // 自由复制：内容能力与气泡外观集中在增强主栏。
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 4.dp
                }
            ) {
                alpha = 0.7f
                isSingleLine = true
                text = stringResource(R.string.free_copy_title)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 11f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.free_copy_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = freeCopyEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    freeCopyEnabled = isChecked
                    runCatching {
                        val revision = System.currentTimeMillis().coerceAtLeast(1L)
                        prefs().edit {
                            putBoolean(HookEntry.PREF_FREE_COPY_ENABLED, isChecked)
                            putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision)
                        }
                        check(FreeCopyConfigStore.write(
                            applicationContext, freeCopyEnabled,
                            freeCopyDescEnabled, revision
                        )) { "atomic mirror write returned false" }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write free copy prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.free_copy_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 简介长按自由复制（与评论复制并列，共用同一气泡样式）
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.free_copy_desc_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = freeCopyDescEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    freeCopyDescEnabled = isChecked
                    runCatching {
                        val revision = System.currentTimeMillis().coerceAtLeast(1L)
                        prefs().edit {
                            putBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, isChecked)
                            putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision)
                        }
                        check(FreeCopyConfigStore.write(
                            applicationContext, freeCopyEnabled,
                            freeCopyDescEnabled, revision
                        )) { "atomic mirror write returned false" }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write free copy desc prefs failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 0.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.free_copy_desc_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 亮色模式开关（白底黑字气泡，适配亮色主题；同时控制评论与简介气泡）
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 14.dp
                    bottomMargin = 4.dp
                }
            ) {
                alpha = 0.7f
                text = stringResource(R.string.free_copy_appearance_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 11f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.free_copy_auto_light)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = freeCopyAutoLight
                setOnCheckedChangeListener { _, isChecked ->
                    if (programmaticSwitch) return@setOnCheckedChangeListener
                    freeCopyAutoLight = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write free copy auto light failed", t)
                    }
                    // tip 动画切换（不重建界面）：淡出 → 换文本 → 淡入
                    animateLightModeTip()
                }
                autoLightSwitch = this
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 0.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.free_copy_auto_light_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.free_copy_light_mode)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = freeCopyLightMode
                setOnCheckedChangeListener { _, isChecked ->
                    if (programmaticSwitch) return@setOnCheckedChangeListener
                    // 手动优先：自动跟随开启时切换手动开关 → 二次确认，
                    // 确认后自动关闭跟随（手动接管）；取消则复位开关 UI
                    if (freeCopyAutoLight && !autoLightConfirmInProgress) {
                        val target = isChecked
                        autoLightConfirmInProgress = true
                        // 确认：关闭自动跟随 + 手动值生效 + tip 切回普通描述
                        showAutoLightConfirmDialog(
                            onConfirm = {
                                autoLightConfirmInProgress = false
                                freeCopyAutoLight = false
                                runCatching {
                                    prefs().edit { putBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, false) }
                                }.onFailure { t ->
                                    Log.e("BilibiliInnocentLab", "write free copy auto light prefs failed", t)
                                }
                                onFreeCopyLightModeChanged(target)
                                // 自动跟随开关 UI 同步为关（程序化，防 listener 回触发）
                                programmaticSwitch = true
                                autoLightSwitch?.isChecked = false
                                programmaticSwitch = false
                                // tip 动画：淡出 → 换文本 → 淡入
                                animateLightModeTip()
                            },
                            onCancel = {
                                autoLightConfirmInProgress = false
                                // 取消：复位手动开关 UI（不重建界面，避免整页排版割裂）
                                programmaticSwitch = true
                                manualLightSwitch?.isChecked = freeCopyLightMode
                                programmaticSwitch = false
                            }
                        )
                        return@setOnCheckedChangeListener
                    }
                    onFreeCopyLightModeChanged(isChecked)
                }
                manualLightSwitch = this
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 0.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(
                    if (freeCopyAutoLight) R.string.free_copy_light_mode_auto_tip
                    else R.string.free_copy_light_mode_tip
                )
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                lightModeTipView = this
            }
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 14.dp
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER or Gravity.START
                    background = skinCardBackground(monetColors.surface, 12f)
                    updatePadding(
                        left = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                        top = 5.dp,
                        right = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                        bottom = 5.dp
                    )
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        gravity = Gravity.CENTER or Gravity.START
                        updatePadding(vertical = 10.dp)
                        setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.ENHANCEMENT_ADVANCED) }
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(15.dp, 15.dp) {
                            marginEnd = 10.dp
                        }
                    ) {
                        setImageResource(R.drawable.ic_enhancement)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                    }
                    TextView(
                        lparams = LayoutParams { weight = 1f }
                    ) {
                        alpha = 0.85f
                        maxLines = 2
                        text = stringResource(R.string.enhancement_advanced_settings)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 12f
                    }
                    ImageView(
                        lparams = LayoutParams(18.dp, 18.dp)
                    ) {
                        enhancementAdvancedChevron = this
                        setImageResource(R.drawable.ic_chevron_down)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        alpha = 0.85f
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        enhancementAdvancedContent = this
                        // 12dp 留白由共享分组构建器提供，此处不得重复叠加。
                        updatePadding(bottom = 10.dp)
                    }
                ) {
                    enhanceBrowsingCategory()
                    enhancePlaybackCategory(uiSettings)
                    enhanceLiveCategory()
                    enhanceCommentsCategory()
                    enhanceDisplayCategory()
                    enhanceSystemCategory()
                }
            }
        }
    }

    /** 实验性功能卡片（含外观、风格与配色等子区）。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.experimentalFeaturesCard() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(horizontal = 15.dp)
            },
            init = {
                experimentalSettingsRoot = this
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                updatePadding(horizontal = 15.dp, vertical = 15.dp)
            }
        ) {
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 12.dp
                },
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                }
            ) {
                ImageView(
                    lparams = LayoutParams(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_science)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    alpha = 0.85f
                    isSingleLine = true
                    text = stringResource(R.string.experimental_features)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
            }
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 10.dp
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    background = skinCardBackground(monetColors.surface, 12f)
                    updatePadding(horizontal = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp, vertical = 4.dp)
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = 52.dp
                        updatePadding(vertical = 10.dp)
                        background = selfRippleBackground(10f)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.APPEARANCE) }
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(20.dp, 20.dp) {
                            marginEnd = 10.dp
                        }
                    ) {
                        setImageResource(R.drawable.ic_palette)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    LinearLayout(
                        lparams = LayoutParams { weight = 1f },
                        init = { orientation = LinearLayout.VERTICAL }
                    ) {
                        TextView(lparams = LayoutParams(widthMatchParent = true)) {
                            text = stringResource(R.string.experimental_appearance)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 14f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = 4.dp }
                        ) {
                            text = stringResource(R.string.experimental_appearance_summary)
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                            alpha = 0.68f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }
                    }
                    ImageView(
                        lparams = LayoutParams(18.dp, 18.dp) { marginStart = 8.dp }
                    ) {
                        appearanceChevron = this
                        setImageResource(R.drawable.ic_chevron_down)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        alpha = 0.85f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        appearanceContent = this
                        // 所有行共用内容边距，避免入口容器与 Switch 各自增加水平缩进。
                        updatePadding(left = 4.dp, right = 4.dp, bottom = 10.dp)
                    }
                ) {
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 10.dp
                            bottomMargin = 6.dp
                        }
                    ) {
                        text = stringResource(R.string.appearance_style_and_color)
                        textColor = monetColors.primary
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        },
                        init = {
                            orientation = LinearLayout.VERTICAL
                            background = selfRippleBackground(10f)
                            updatePadding(horizontal = 0.dp, vertical = 9.dp)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { showSkinSelectionDialog() }
                        }
                    ) {
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true)
                        ) {
                            updatePadding(horizontal = 0.dp)
                            text = stringResource(R.string.skin_setting_title)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 15f
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = 4.dp
                            }
                        ) {
                            alpha = 0.72f
                            skinSummaryView = this
                            text = currentSkinSummary()
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.skin_setting_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        },
                        init = {
                            orientation = LinearLayout.VERTICAL
                            background = selfRippleBackground(10f)
                            updatePadding(horizontal = 0.dp, vertical = 9.dp)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { showLiquidBackgroundDialog() }
                        }
                    ) {
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true)
                        ) {
                            updatePadding(horizontal = 0.dp)
                            text = stringResource(R.string.liquid_background_setting_title)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 15f
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = 4.dp
                            }
                        ) {
                            alpha = 0.72f
                            liquidBackgroundSummaryView = this
                            text = currentLiquidBackgroundSummary()
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                        }
                    }
                    MaterialSwitch(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        }
                    ) {
                        updatePadding(horizontal = 0.dp)
                        text = stringResource(R.string.material_color_spec_title)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        isChecked = MaterialColorSpecStore.read(applicationContext) ==
                            MaterialColorSpec.SPEC_2025
                        setOnCheckedChangeListener { button, checked ->
                            if (materialColorSpecProgrammaticSwitch) {
                                return@setOnCheckedChangeListener
                            }
                            val target = if (checked) {
                                MaterialColorSpec.SPEC_2025
                            } else {
                                MaterialColorSpec.SPEC_2021
                            }
                            if (MaterialColorSpecStore.write(applicationContext, target)) {
                                button.post {
                                    if (!isFinishing && !isDestroyed) recreate()
                                }
                            } else {
                                materialColorSpecProgrammaticSwitch = true
                                button.isChecked = !checked
                                materialColorSpecProgrammaticSwitch = false
                                toast(stringResource(R.string.material_color_spec_save_failed))
                            }
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.material_color_spec_summary)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    MaterialSwitch(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        }
                    ) {
                        updatePadding(horizontal = 0.dp)
                        text = stringResource(R.string.panel_window_blur_title)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        // 未设置时默认关闭，见 ModalBackdropBlurStore.DEFAULT。
                        isChecked = ModalBackdropBlurStore.read(applicationContext)
                        setOnCheckedChangeListener { _, checked ->
                            prefs().edit {
                                putBoolean(ModalBackdropBlurStore.PREF_KEY, checked)
                            }
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.panel_window_blur_summary)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 10.dp
                            bottomMargin = 6.dp
                        }
                    ) {
                        text = stringResource(R.string.display_settings)
                        textColor = monetColors.primary
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 8.dp
                        },
                        init = {
                            orientation = LinearLayout.VERTICAL
                            background = selfRippleBackground(10f)
                            updatePadding(horizontal = 0.dp, vertical = 9.dp)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { showAppLanguageDialog(it) }
                        }
                    ) {
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true)
                        ) {
                            updatePadding(horizontal = 0.dp)
                            text = stringResource(R.string.app_language)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 15f
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = 4.dp
                            }
                        ) {
                            alpha = 0.72f
                            text = currentAppLanguageSummary()
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.app_language_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    MaterialSwitch(
                        lparams = LayoutParams(widthMatchParent = true)
                    ) {
                        updatePadding(horizontal = 0.dp)
                        text = stringResource(R.string.hide_app_icon_on_launcher)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        isChecked = !isLauncherIconShowing
                        setOnCheckedChangeListener { button, isChecked ->
                            if (button.isPressed) hideOrShowLauncherIcon(!isChecked)
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.hide_app_icon_on_launcher_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.hide_app_icon_on_launcher_notice)
                        textColor = 0xFFFF5722.toInt()
                        textSize = 12f
                    }
                }
            }
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 10.dp
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    background = skinCardBackground(monetColors.surface, 12f)
                    updatePadding(horizontal = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp, vertical = 4.dp)
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = 52.dp
                        updatePadding(vertical = 10.dp)
                        background = selfRippleBackground(10f)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.COMPATIBILITY) }
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(20.dp, 20.dp) {
                            marginEnd = 10.dp
                        }
                    ) {
                        setImageResource(R.drawable.ic_extension)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    LinearLayout(
                        lparams = LayoutParams { weight = 1f },
                        init = { orientation = LinearLayout.VERTICAL }
                    ) {
                        TextView(lparams = LayoutParams(widthMatchParent = true)) {
                            text = stringResource(R.string.experimental_compatibility)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 14f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = 4.dp }
                        ) {
                            text = stringResource(R.string.experimental_compatibility_summary)
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                            alpha = 0.68f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }
                    }
                    ImageView(
                        lparams = LayoutParams(18.dp, 18.dp) { marginStart = 8.dp }
                    ) {
                        compatibilityChevron = this
                        setImageResource(R.drawable.ic_chevron_down)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        alpha = 0.85f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        compatibilityContent = this
                        val contentPadding = SettingsMenuSpacing.matchingContentPaddingPx(
                            referenceShellPx = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                            ownShellPx = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp,
                            density = resources.displayMetrics.density
                        )
                        updatePadding(left = contentPadding, right = contentPadding, bottom = 10.dp)
                    }
                ) {
                    MaterialSwitch(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        }
                    ) {
                        text = stringResource(R.string.no_root_support_enable)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        isChecked = noRootDesiredEnabled
                        isEnabled = noRootDesiredEnabled ||
                            (
                                AndroidVersion.isAtLeast(AndroidVersion.P) &&
                                    noRootPrefsBridge != null
                                )
                        setOnCheckedChangeListener { _, isChecked ->
                            if (noRootProgrammaticSwitch) return@setOnCheckedChangeListener
                            noRootDesiredEnabled = isChecked
                            if (isChecked) enableAndSynchronizeNoRootSupport()
                            else disableNoRootSupport()
                        }
                        noRootSwitch = this
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true)
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.no_root_support_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 4.dp
                            bottomMargin = 10.dp
                        }
                    ) {
                        alpha = 0.8f
                        setLineSpacing(6f, 1f)
                        text = stringResource(noRootStatusText(currentNoRootDisplayState()))
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 12f
                        noRootStatusView = this
                    }
                    MaterialSwitch(
                        lparams = LayoutParams(widthMatchParent = true) {
                            bottomMargin = 5.dp
                        }
                    ) {
                        text = stringResource(R.string.roaming_compat_enable)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        isChecked = roamingCompatEnabled
                        setOnCheckedChangeListener { _, isChecked ->
                            roamingCompatEnabled = isChecked
                            runCatching {
                                prefs().edit { putBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, isChecked) }
                            }.onFailure { t ->
                                Log.e("BilibiliInnocentLab", "write roaming compat prefs failed", t)
                            }
                            // 同步给正在运行的 B 站进程（HookEntry 在 B 站进程内注册了接收器，
                            // 收到后写入其自身缓存，下次启动即生效）。
                            runCatching {
                                val intent = Intent(RoamingCompatHook.ACTION_SET_ROAMING_COMPAT)
                                    .setPackage(HookEntry.TARGET_PACKAGE)
                                    .putExtra(RoamingCompatHook.EXTRA_ENABLED, isChecked)
                                this@MainActivity.sendBroadcast(intent)
                            }.onFailure { t ->
                                Log.e("BilibiliInnocentLab", "send roaming compat broadcast failed", t)
                            }
                        }
                    }
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 5.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.roaming_compat_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                    // 预见式返回动画（Android 14+）：实验性开关，运行时切换 window 的
                    // OnBackInvokedCallback 体系，返回时显示系统缩放预览动画。
                    // Android 16（API 36）+ 系统对 targetSdk 36+ 的应用强制启用
                    // 预测性返回（隐藏 API 的关闭调用被忽略），开关失去实际效果，
                    // 此时整组隐藏；偏好键与备份 catalog 条目保留以兼容旧备份。
                    if (!PredictiveBack.isSystemEnforced) {
                        MaterialSwitch(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = 5.dp
                                bottomMargin = 5.dp
                            }
                        ) {
                            text = stringResource(R.string.predictive_back_enable)
                            isAllCaps = false
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 15f
                            isChecked = predictiveBackEnabled
                            setOnCheckedChangeListener { _, isChecked ->
                                predictiveBackEnabled = isChecked
                                runCatching {
                                    prefs().edit { putBoolean(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, isChecked) }
                                }.onFailure { t ->
                                    Log.e("BilibiliInnocentLab", "write predictive back prefs failed", t)
                                }
                                // 立即作用于当前 window（无需重启界面）
                                applyPredictiveBack()
                            }
                        }
                        TextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = 0.dp
                            }
                        ) {
                            alpha = 0.6f
                            setLineSpacing(6f, 1f)
                            text = stringResource(R.string.predictive_back_tip)
                            textColor = colorResource(R.color.colorTextDark)
                            textSize = 12f
                        }
                    }
                    // 手动重新适配：清除版本适配缓存，重启哔哩哔哩后自动重新定位 hook 点。
                    // 风格与「实验性功能」分区标题行统一：图标 + 文字 + 水波纹点击反馈；
                    // 点击弹出二级确认菜单（与「重启哔哩哔哩」确认弹窗同规格）
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = 12.dp
                            bottomMargin = 2.dp
                        },
                        init = {
                            gravity = Gravity.CENTER or Gravity.START
                            // 自绘涟漪（不解析主题属性 selectableItemBackground）：
                            // 客户设备上的全局主题模块（Monet-All 等）可能把该主题属性
                            // 解析为不透明实心 drawable，整行盖住内容 →「只剩空位但可点击」
                            background = selfRippleBackground(10f)
                            updatePadding(horizontal = 0.dp, vertical = 9.dp)
                            setOnClickListener { showAdaptConfirmDialog() }
                        }
                    ) {
                        ImageView(
                            lparams = LayoutParams(17.dp, 17.dp) {
                                marginEnd = 9.dp
                            }
                        ) {
                            setImageResource(R.drawable.ic_restart)
                            imageTintList = stateColorResource(R.color.colorTextGray)
                            alpha = 0.8f
                        }
                        TextView(
                            lparams = LayoutParams {
                                weight = 1f
                            }
                        ) {
                            isSingleLine = true
                            text = stringResource(R.string.adapt_manual)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 14f
                        }
                        ImageView(
                            lparams = LayoutParams(16.dp, 16.dp)
                        ) {
                            alpha = 0.55f
                            setImageResource(R.drawable.ic_chevron_down)
                            imageTintList = stateColorResource(R.color.colorTextGray)
                            rotation = -90f
                        }
                    }
                }
            }
        }
    }

    /** 日志设置卡片。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.logSettingsCard() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(horizontal = 15.dp)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
            }
        ) {
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 10.dp
                },
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                }
            ) {
                ImageView(
                    lparams = LayoutParams(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_article)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true)
                ) {
                    alpha = 0.85f
                    isSingleLine = true
                    text = stringResource(R.string.log_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
            }
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(R.string.log_capture_enable)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = logEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    logEnabled = isChecked
                    runCatching {
                        prefs().edit { putBoolean(HookEntry.PREF_LOG_ENABLED, isChecked) }
                    }.onFailure { t ->
                        Log.e("BilibiliInnocentLab", "write log enabled failed", t)
                    }
                }
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.log_capture_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 详细度档位选择器（精简 / 完整）
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 6.dp
                }
            ) {
                alpha = 0.85f
                isSingleLine = true
                text = stringResource(R.string.log_level_label)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 13f
            }
            // 详细度档位选择器：FrameLayout 内叠放「滑动滑块 + 两个透明文字项」
            FrameLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    // 背景槽位（surface 色圆角，作滑块滑动轨道）
                    background = GradientDrawable().apply {
                        cornerRadius = resources.displayMetrics.density * 10f
                        setColor(monetColors.background)
                    }
                    skinSelectionControl(this, 10f, selected = false)
                }
            ) {
                // 滑动滑块（primary 圆角，随选中项平移；宽度在布局后动态设为容器一半）
                FrameLayout(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        logLevelThumb = this
                        background = logLevelThumbBg()
                        skinSelectionControl(this, 10f, selected = true)
                    }
                )
                // 两个等宽文字项（透明背景，仅作点击热区 + 文字显示）
                LinearLayout(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                    }
                ) {
                    TextView(
                        lparams = LayoutParams {
                            weight = 1f
                        }
                    ) {
                        logLevelMinimalPill = this
                        gravity = Gravity.CENTER
                        updatePadding(vertical = 12.dp)
                        text = stringResource(R.string.log_level_minimal)
                        textSize = 14f
                        isClickable = true
                        isFocusable = true
                        textColor = if (!logVerbose) skinEmphasisTextColor else colorResource(R.color.colorTextGray)
                        typeface = if (!logVerbose) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                        setOnClickListener {
                            if (logVerbose) {
                                runCatching {
                                    prefs().edit { putString(HookEntry.PREF_LOG_LEVEL, HookEntry.LOG_LEVEL_MINIMAL) }
                                }.onFailure { t ->
                                    Log.e("BilibiliInnocentLab", "write log level failed", t)
                                }
                                animateLogLevelTo(verbose = false)
                            }
                        }
                    }
                    TextView(
                        lparams = LayoutParams {
                            weight = 1f
                        }
                    ) {
                        logLevelCompletePill = this
                        gravity = Gravity.CENTER
                        updatePadding(vertical = 12.dp)
                        text = stringResource(R.string.log_level_complete)
                        textSize = 14f
                        isClickable = true
                        isFocusable = true
                        textColor = if (logVerbose) skinEmphasisTextColor else colorResource(R.color.colorTextGray)
                        typeface = if (logVerbose) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                        setOnClickListener {
                            if (!logVerbose) {
                                runCatching {
                                    prefs().edit { putString(HookEntry.PREF_LOG_LEVEL, HookEntry.LOG_LEVEL_COMPLETE) }
                                }.onFailure { t ->
                                    Log.e("BilibiliInnocentLab", "write log level failed", t)
                                }
                                animateLogLevelTo(verbose = true)
                            }
                        }
                    }
                }
            }
            // 档位描述（随选中项动态更新）
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 6.dp
                }
            ) {
                logLevelDesc = this
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(if (logVerbose) R.string.log_level_complete_desc else R.string.log_level_minimal_desc)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
    }

    private fun createPromotionItem(
        @StringRes stringResource: Int,
        @DrawableRes imageResource: Int
    ) = Hikagable<MarginLayoutParams> {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(left = 15.dp, right = 15.dp)
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                setPadding(10.dp)
            }
        ) {
            ImageView(
                lparams = LayoutParams(35.dp, 35.dp) {
                    marginEnd = 10.dp
                }
            ) {
                setImageResource(imageResource)
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true)
            ) {
                autoLinkMask = Linkify.WEB_URLS
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 2
                setLineSpacing(6f, 1f)
                text = stringResource(stringResource)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 11f
            }
        }
    }

    /**
     * Hide or show launcher icons
     *
     * - You may need the latest version of LSPosed to enable the function of hiding launcher
     *   icons in higher version systems
     *
     * 隐藏或显示启动器图标
     *
     * - 你可能需要 LSPosed 的最新版本以开启高版本系统中隐藏 APP 桌面图标功能
     * @param isShow whether to display / 是否显示
     */
    private fun hideOrShowLauncherIcon(isShow: Boolean) {
        if (isShow)
            packageManager?.enableComponent(homeComponent, PackageManager.DONT_KILL_APP)
        else packageManager?.disableComponent(homeComponent, PackageManager.DONT_KILL_APP)
    }

    /**
     * Get launcher icon state
     *
     * 获取启动器图标状态
     * @return [Boolean] whether to display / 是否显示
     */
    private val isLauncherIconShowing
        get() = packageManager?.isComponentEnabled(homeComponent) == true
    /** 进阶净化 · PURIFY_SHARED 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifySharedCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SHARED] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_shared)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            }
        ) {
            recommendVideoDurationSummaryView = this
            text = stringResource(R.string.recommend_video_duration_range) +
                "\n" + recommendVideoDurationSummary()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRecommendVideoDurationRangeDialog(it)
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.recommend_video_duration_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_SHARE 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyShareCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SHARE] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_share)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.purify_share_content)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = purifyShareContent
            setOnCheckedChangeListener { _, checked ->
                purifyShareContent = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.PURIFY_SHARE_CONTENT,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write share purify prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.purify_share_content_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.share_mini_program_direct_link)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = shareMiniProgramDirectLink
            setOnCheckedChangeListener { _, checked ->
                shareMiniProgramDirectLink = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write mini program share prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.share_mini_program_direct_link_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_STARTUP 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyStartupCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_STARTUP] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_startup)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.purify_splash_ads)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = purifySplashAds
            setOnCheckedChangeListener { _, checked ->
                purifySplashAds = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.PURIFY_SPLASH_ADS, checked)
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.purify_splash_ads_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.prompt_purify_settings)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.block_teenagers_mode_prompt)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = blockTeenagersModePrompt
            setOnCheckedChangeListener { _, isChecked ->
                blockTeenagersModePrompt = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write teenagers mode prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.block_teenagers_mode_prompt_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.client_update_settings)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.block_app_update)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = blockAppUpdate
            setOnCheckedChangeListener { _, isChecked ->
                blockAppUpdate = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.BLOCK_APP_UPDATE,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write block app update prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.block_app_update_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_COMMENTS 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyCommentsCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_COMMENTS] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_comments)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_comment_section)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideCommentSection
            setOnCheckedChangeListener { _, checked ->
                hideCommentSection = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.HIDE_COMMENT_SECTION, checked)
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_comment_section_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_search_links)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentSearchLinks
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentSearchLinks = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment search prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_search_links_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_empty_guide)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentEmptyGuide
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentEmptyGuide = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment empty guide prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_empty_guide_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_vote_widgets)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentVoteWidgets
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentVoteWidgets = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment vote prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_vote_widgets_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_follow_buttons)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentFollowButtons
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentFollowButtons = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment follow prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_follow_buttons_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_qoe)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentQoe
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentQoe = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_QOE,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment qoe prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_qoe_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_comment_operations)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeCommentOperations
            setOnCheckedChangeListener { _, isChecked ->
                removeCommentOperations = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_COMMENT_OPERATIONS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment operation prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_operations_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.comment_keyword_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentKeywordFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                commentKeywordFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment keyword filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            commentKeywordSummaryView = this
            text = stringResource(R.string.comment_keyword_rules) + "\n" +
                if (commentFilterKeywords.isBlank()) {
                    stringResource(R.string.comment_keyword_rules_empty)
                } else {
                    stringResource(
                        R.string.comment_keyword_rules_current,
                        commentFilterKeywords
                    )
                }
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.comment_keyword_dialog_title,
                    R.string.comment_keyword_dialog_hint,
                    commentFilterKeywords,
                    anchor = it
                ) { value ->
                    commentFilterKeywords = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.COMMENT_FILTER_KEYWORDS,
                            value
                        )
                    }
                    commentKeywordSummaryView?.text =
                        stringResource(R.string.comment_keyword_rules) + "\n" +
                            if (value.isBlank()) {
                                stringResource(R.string.comment_keyword_rules_empty)
                            } else {
                                stringResource(
                                    R.string.comment_keyword_rules_current,
                                    value
                                )
                            }
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_keyword_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.comment_min_level_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentMinLevelFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                commentMinLevelFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment minimum level filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            commentLevelSummaryView = this
            text = stringResource(R.string.comment_min_level_current, commentMinLevel)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { showCommentMinLevelDialog(it) }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_min_level_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // @ 整条与发布者过滤跟随关键词/等级过滤，共用同一批列表边界。
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_at_only_comments)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeAtOnlyComments
            setOnCheckedChangeListener { _, checked ->
                removeAtOnlyComments = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write at-only comment prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_at_only_comments_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.comment_user_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentUserFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                commentUserFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment author filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            commentUserFilterSummaryView = this
            text = commentUserFilterSummaryText(commentUserFilterRules)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.comment_user_filter_dialog_title,
                    R.string.comment_user_filter_dialog_hint,
                    commentUserFilterRules,
                    anchor = it
                ) { value ->
                    commentUserFilterRules = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.COMMENT_USER_FILTER_RULES,
                            value
                        )
                    }
                    commentUserFilterSummaryView?.text =
                        commentUserFilterSummaryText(value)
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_user_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_PLAYBACK 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyPlaybackCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_PLAYBACK] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_playback)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_player_portrait_control)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hidePlayerPortraitControl
            setOnCheckedChangeListener { _, isChecked ->
                hidePlayerPortraitControl = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write player portrait prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_player_portrait_control_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_player_interactive_overlays)
            settingsDestinations.bind("player.interactive_overlays.hidden",this)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hidePlayerInteractiveOverlays
            setOnCheckedChangeListener { _, isChecked ->
                hidePlayerInteractiveOverlays = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write player interactive overlays prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_player_interactive_overlays_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_pgc_auto_activity_popup)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hidePgcAutoActivityPopup
            setOnCheckedChangeListener { _, isChecked ->
                hidePgcAutoActivityPopup = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write PGC auto activity popup prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_pgc_auto_activity_popup_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 14.dp
                bottomMargin = 8.dp
            },
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 4.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                contentDescription = stringResource(R.string.detail_module_purify_settings)
                setOnClickListener { showDetailModuleFilterDialog(anchor = it) }
            }
        ) {
            LinearLayout(
                lparams = LayoutParams { weight = 1f },
                init = { orientation = LinearLayout.VERTICAL }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(R.string.detail_module_purify_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = 4.dp
                    }
                ) {
                    detailModuleFilterSummaryView = this
                    alpha = 0.68f
                    text = detailModuleFilterSummary()
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                // 供设置项搜索命中子项：只做索引，不显示。
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    visibility = View.GONE
                    text = listOf(
                        R.string.remove_detail_honor,
                        R.string.remove_detail_live_order,
                        R.string.remove_detail_ugc_season,
                        R.string.remove_detail_up_vip_label,
                        R.string.remove_detail_topic_tags,
                        R.string.remove_detail_staff_follow,
                        R.string.remove_detail_hot_banner
                    ).joinToString(separator = " ") { stringResource(it) }
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
            }
            ImageView(lparams = LayoutParams(18.dp, 18.dp)) {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                alpha = 0.8f
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 14.dp
                bottomMargin = 8.dp
            },
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 4.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                contentDescription = stringResource(
                    R.string.portrait_content_filter_title
                )
                setOnClickListener { showPortraitContentFilterDialog(it) }
            }
        ) {
            LinearLayout(
                lparams = LayoutParams { weight = 1f },
                init = { orientation = LinearLayout.VERTICAL }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(R.string.portrait_content_filter_title)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = 4.dp
                    }
                ) {
                    portraitContentFilterSummaryView = this
                    alpha = 0.68f
                    text = portraitContentFilterSummary()
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
            }
            ImageView(lparams = LayoutParams(18.dp, 18.dp)) {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                alpha = 0.8f
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_ads)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryAds
            setOnCheckedChangeListener { _, checked ->
                removeStoryAds = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_ADS, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_live)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryLive
            setOnCheckedChangeListener { _, checked ->
                removeStoryLive = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_LIVE, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_games)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryGames
            setOnCheckedChangeListener { _, checked ->
                removeStoryGames = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_GAMES, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_bangumi)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryBangumi
            setOnCheckedChangeListener { _, checked ->
                removeStoryBangumi = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_BANGUMI, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_courses)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryCourses
            setOnCheckedChangeListener { _, checked ->
                removeStoryCourses = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_COURSES, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_short_drama)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryShortDrama
            setOnCheckedChangeListener { _, checked ->
                removeStoryShortDrama = checked
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA,
                        checked
                    )
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_shopping)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryShopping
            setOnCheckedChangeListener { _, checked ->
                removeStoryShopping = checked
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_STORY_SHOPPING,
                        checked
                    )
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_movies)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryMovies
            setOnCheckedChangeListener { _, checked ->
                removeStoryMovies = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_MOVIES, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_documentaries)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryDocumentaries
            setOnCheckedChangeListener { _, checked ->
                removeStoryDocumentaries = checked
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES,
                        checked
                    )
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_tv)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryTv
            setOnCheckedChangeListener { _, checked ->
                removeStoryTv = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_TV, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_variety)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryVariety
            setOnCheckedChangeListener { _, checked ->
                removeStoryVariety = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_VARIETY, checked)
                }
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            visibility = View.GONE
            text = stringResource(R.string.remove_story_music)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeStoryMusic
            setOnCheckedChangeListener { _, checked ->
                removeStoryMusic = checked
                prefs().edit {
                    putBoolean(FeaturePreferences.REMOVE_STORY_MUSIC, checked)
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            visibility = View.GONE
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.story_purify_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 14.dp
                bottomMargin = 8.dp
            },
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 4.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                contentDescription = stringResource(
                    R.string.video_relate_filter_settings
                )
                setOnClickListener { showVideoRelateFilterDialog(it) }
            }
        ) {
            LinearLayout(
                lparams = LayoutParams { weight = 1f },
                init = { orientation = LinearLayout.VERTICAL }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(R.string.video_relate_filter_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = 4.dp
                    }
                ) {
                    videoRelateFilterSummaryView = this
                    alpha = 0.68f
                    text = videoRelateFilterSummary()
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    visibility = View.GONE
                    text = listOf(
                        R.string.remove_relate_commercial,
                        R.string.remove_relate_game,
                        R.string.remove_relate_live,
                        R.string.remove_relate_course,
                        R.string.remove_relate_special,
                        R.string.video_relate_matching_enhancement,
                        R.string.video_relate_strong_mode,
                        R.string.video_relate_reason_filter,
                        R.string.video_relate_reason_filter_keywords
                    ).joinToString(" · ") { stringResource(it) }
                }
            }
            ImageView(lparams = LayoutParams(18.dp, 18.dp)) {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                alpha = 0.8f
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
        }
        // 弹幕净化：内容流（DmSegMobileReply）侧的权重与会员彩字，
        // 与播放器互动组件（指令弹幕/角标）分属两条链路，各自独立开关。
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.danmaku_weight_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = danmakuWeightFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                danmakuWeightFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write danmaku weight filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            danmakuWeightSummaryView = this
            text = stringResource(
                R.string.danmaku_weight_current,
                danmakuWeightMinimum
            )
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { showDanmakuWeightDialog(it) }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.danmaku_weight_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_vip_colorful_danmaku)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeVipColorfulDanmaku
            setOnCheckedChangeListener { _, checked ->
                removeVipColorfulDanmaku = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write vip colorful danmaku prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_vip_colorful_danmaku_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_MINE 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyMineCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_MINE] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_mine)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_mine_vip)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideMineVip
            setOnCheckedChangeListener { _, isChecked ->
                hideMineVip = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_MINE_VIP,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write mine vip prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_mine_vip_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.keep_mine_vip_space)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = keepMineVipSpace
            setOnCheckedChangeListener { _, isChecked ->
                keepMineVipSpace = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.KEEP_MINE_VIP_SPACE,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write mine vip space prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.keep_mine_vip_space_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            }
        ) {
            mineComponentRulesSummaryView = this
            text = stringResource(R.string.custom_mine_component_hide) + "\n" +
                mineComponentPickerSurface().summaryText()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                queryComponentSnapshotAndOpenPicker(
                    mineComponentPickerSurface()
                )
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            text = stringResource(R.string.custom_mine_component_hide_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_SEARCH 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifySearchCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SEARCH] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_search)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_search_home_recommend)
            settingsDestinations.bind("search.home_recommend.hidden",this)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideSearchHomeRecommend
            setOnCheckedChangeListener { _, checked ->
                hideSearchHomeRecommend = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write search home recommend prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_search_home_recommend_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_search_commercial)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeSearchCommercial
            setOnCheckedChangeListener { _, checked ->
                removeSearchCommercial = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write search commercial filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_search_commercial_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.search_keyword_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = searchKeywordFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                searchKeywordFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write search keyword filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            searchKeywordSummaryView = this
            text = ruleEntryText(
                R.string.search_keyword_rules,
                R.string.search_keyword_rules_empty,
                R.string.search_keyword_rules_current,
                searchFilterKeywords
            )
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.search_keyword_dialog_title,
                    R.string.search_keyword_dialog_hint,
                    searchFilterKeywords,
                    anchor = it
                ) { value ->
                    searchFilterKeywords = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.SEARCH_FILTER_KEYWORDS,
                            value
                        )
                    }
                    searchKeywordSummaryView?.text = ruleEntryText(
                        R.string.search_keyword_rules,
                        R.string.search_keyword_rules_empty,
                        R.string.search_keyword_rules_current,
                        value
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.search_keyword_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.search_author_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = searchAuthorFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                searchAuthorFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write search author filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            searchAuthorSummaryView = this
            text = ruleEntryText(
                R.string.search_author_filter_rules,
                R.string.search_author_filter_rules_empty,
                R.string.search_author_filter_rules_current,
                searchAuthorFilterRules
            )
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.search_author_filter_dialog_title,
                    R.string.search_author_filter_dialog_hint,
                    searchAuthorFilterRules,
                    anchor = it
                ) { value ->
                    searchAuthorFilterRules = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
                            value
                        )
                    }
                    searchAuthorSummaryView?.text = ruleEntryText(
                        R.string.search_author_filter_rules,
                        R.string.search_author_filter_rules_empty,
                        R.string.search_author_filter_rules_current,
                        value
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.search_author_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_NAVIGATION 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyNavigationCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_NAVIGATION] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_navigation)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            }
        ) {
            bottomBarRulesSummaryView = this
            text = stringResource(R.string.custom_bottom_bar_hide) + "\n" +
                bottomBarPickerSurface().summaryText()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                queryComponentSnapshotAndOpenPicker(bottomBarPickerSurface())
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            text = stringResource(R.string.custom_bottom_bar_hide_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.dynamic_page_settings)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_dynamic_city_tab)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideDynamicCityTab
            setOnCheckedChangeListener { _, isChecked ->
                hideDynamicCityTab = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_CITY_TAB,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic city tab prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_dynamic_city_tab_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_dynamic_school_tab)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideDynamicSchoolTab
            setOnCheckedChangeListener { _, isChecked ->
                hideDynamicSchoolTab = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic school tab prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_dynamic_school_tab_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 动态内容过滤与上面的页签净化同属动态页，放在同一区域。
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.dynamic_content_settings)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.dynamic_keyword_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = dynamicKeywordFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                dynamicKeywordFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic keyword filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            dynamicKeywordSummaryView = this
            text = ruleEntryText(
                R.string.dynamic_keyword_rules,
                R.string.dynamic_keyword_rules_empty,
                R.string.dynamic_keyword_rules_current,
                dynamicFilterKeywords
            )
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.dynamic_keyword_dialog_title,
                    R.string.dynamic_keyword_dialog_hint,
                    dynamicFilterKeywords,
                    anchor = it
                ) { value ->
                    dynamicFilterKeywords = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
                            value
                        )
                    }
                    dynamicKeywordSummaryView?.text = ruleEntryText(
                        R.string.dynamic_keyword_rules,
                        R.string.dynamic_keyword_rules_empty,
                        R.string.dynamic_keyword_rules_current,
                        value
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.dynamic_keyword_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.dynamic_author_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = dynamicAuthorFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                dynamicAuthorFilterEnabled = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic author filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            dynamicAuthorSummaryView = this
            text = ruleEntryText(
                R.string.dynamic_author_filter_rules,
                R.string.dynamic_author_filter_rules_empty,
                R.string.dynamic_author_filter_rules_current,
                dynamicAuthorFilterRules
            )
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.dynamic_author_filter_dialog_title,
                    R.string.dynamic_author_filter_dialog_hint,
                    dynamicAuthorFilterRules,
                    anchor = it
                ) { value ->
                    dynamicAuthorFilterRules = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
                            value
                        )
                    }
                    dynamicAuthorSummaryView?.text = ruleEntryText(
                        R.string.dynamic_author_filter_rules,
                        R.string.dynamic_author_filter_rules_empty,
                        R.string.dynamic_author_filter_rules_current,
                        value
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.dynamic_author_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_dynamic_promotions)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeDynamicPromotions
            setOnCheckedChangeListener { _, checked ->
                removeDynamicPromotions = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic promotion filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_dynamic_promotions_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_dynamic_charge_only)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeDynamicChargeOnly
            setOnCheckedChangeListener { _, checked ->
                removeDynamicChargeOnly = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic charge only filter prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_dynamic_charge_only_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_dynamic_frequent_visits)
            settingsDestinations.bind("dynamic.frequent_visits.hidden",this)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideDynamicFrequentVisits
            setOnCheckedChangeListener { _, checked ->
                hideDynamicFrequentVisits = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic frequent visits prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_dynamic_frequent_visits_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_dynamic_topic_list)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideDynamicTopicList
            setOnCheckedChangeListener { _, checked ->
                hideDynamicTopicList = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic topic list prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_dynamic_topic_list_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_dynamic_live_up_entries)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeDynamicLiveUpEntries
            setOnCheckedChangeListener { _, checked ->
                removeDynamicLiveUpEntries = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write dynamic live up entries prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_dynamic_live_up_entries_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶净化 · PURIFY_HOME 分类；标题即 marker，必须是本组第一个子控件。 */
    // 注解用全限定名：本文件已经导入了同名的**函数** com.highcapable.hikage.core.base.Hikagable
    // （见 createPromotionItem 的 Hikagable<MarginLayoutParams> { }），不能再按简名导入注解。
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.purifyHomeCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_HOME] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_purify_home)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_home_game_menu)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideHomeGameMenu
            setOnCheckedChangeListener { _, isChecked ->
                hideHomeGameMenu = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_HOME_GAME_MENU,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write home game menu prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_home_game_menu_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.hide_home_search_default_word)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideHomeSearchDefaultWord
            setOnCheckedChangeListener { _, isChecked ->
                hideHomeSearchDefaultWord = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write home search word prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_home_search_default_word_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            }
        ) {
            homeTabRulesSummaryView = this
            text = stringResource(R.string.custom_home_tab_hide) + "\n" +
                homeTabPickerSurface().summaryText()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                queryComponentSnapshotAndOpenPicker(homeTabPickerSurface())
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            text = stringResource(R.string.custom_home_tab_hide_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            }
        ) {
            homeComponentRulesSummaryView = this
            text = stringResource(R.string.custom_home_component_hide) + "\n" +
                homeComponentPickerSurface().summaryText()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                queryComponentSnapshotAndOpenPicker(
                    homeComponentPickerSurface()
                )
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            text = stringResource(R.string.custom_home_component_hide_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.home_recommend_purify_settings)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.remove_home_recommend_cm_v2)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = removeHomeRecommendCmV2
            setOnCheckedChangeListener { _, isChecked ->
                removeHomeRecommendCmV2 = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write home recommend cm_v2 prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 12.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_home_recommend_cm_v2_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 8.dp
            },
            init = {
                homeRecommendFilterEntryView = this
                settingsDestinations.bind("home.recommend.pgc.removed",this)
                settingsDestinations.bind("home.recommend.special_cards.removed",this)
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(vertical = 9.dp)
                isClickable = true
                isFocusable = true
                contentDescription = stringResource(
                    R.string.home_recommend_filter_title
                )
                setOnClickListener { showHomeRecommendFilterDialog(anchor = it) }
            }
        ) {
            LinearLayout(
                lparams = LayoutParams { weight = 1f },
                init = { orientation = LinearLayout.VERTICAL }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(R.string.home_recommend_filter_title)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = 4.dp
                    }
                ) {
                    homeRecommendFilterSummaryView = this
                    alpha = 0.68f
                    text = homeRecommendFilterSummary()
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
            }
            ImageView(lparams = LayoutParams(18.dp, 18.dp)) {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                alpha = 0.8f
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.home_recommend_title_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = homeRecommendTitleFilterEnabled
            setOnCheckedChangeListener { _, checked ->
                homeRecommendTitleFilterEnabled = checked
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                        checked
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            homeRecommendTitleSummaryView = this
            text = stringResource(R.string.home_recommend_title_rules) + "\n" +
                if (homeRecommendTitleKeywords.isBlank()) {
                    stringResource(R.string.home_recommend_title_rules_empty)
                } else {
                    stringResource(
                        R.string.home_recommend_title_rules_current,
                        homeRecommendTitleKeywords
                    )
                }
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRuleEditorDialog(
                    R.string.home_recommend_title_dialog_title,
                    R.string.home_recommend_title_dialog_hint,
                    homeRecommendTitleKeywords,
                    anchor = it
                ) { value ->
                    homeRecommendTitleKeywords = value
                    prefs().edit {
                        putString(
                            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS,
                            value
                        )
                    }
                    homeRecommendTitleSummaryView?.text =
                        stringResource(R.string.home_recommend_title_rules) + "\n" +
                            if (value.isBlank()) {
                                stringResource(R.string.home_recommend_title_rules_empty)
                            } else {
                                stringResource(
                                    R.string.home_recommend_title_rules_current,
                                    value
                                )
                            }
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.home_recommend_purify_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_SYSTEM 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhanceSystemCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_SYSTEM] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_enhance_system)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.splash_auto_night)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = splashAutoNight
            setOnCheckedChangeListener { _, checked ->
                splashAutoNight = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SPLASH_AUTO_NIGHT,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write splash auto night prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.splash_auto_night_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.system_media_notification)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = systemMediaNotification
            setOnCheckedChangeListener { _, checked ->
                systemMediaNotification = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write system media notification prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.system_media_notification_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.force_external_browser)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = forceExternalBrowser
            setOnCheckedChangeListener { _, checked ->
                forceExternalBrowser = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.FORCE_EXTERNAL_BROWSER,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write external browser prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.force_external_browser_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_DISPLAY 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhanceDisplayCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_DISPLAY] = this
            alpha = 0.9f
            text = stringResource(R.string.number_display_settings)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.show_full_numbers)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = showFullNumbers
            setOnCheckedChangeListener { _, isChecked ->
                showFullNumbers = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SHOW_FULL_NUMBERS,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write full number prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.show_full_numbers_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // AV 号显示同样是"同一个数字换一种写法"，与完整播放量归在数字显示。
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.show_bv_as_av)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = showBvAsAv
            setOnCheckedChangeListener { _, checked ->
                showBvAsAv = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.SHOW_BV_AS_AV,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write bv to av prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.show_bv_as_av_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_COMMENTS 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhanceCommentsCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_COMMENTS] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_enhance_comments)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.reply_topology_enabled)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = replyTopologyEnabled
            setOnCheckedChangeListener { _, checked ->
                replyTopologyEnabled = checked
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REPLY_TOPOLOGY_ENABLED,
                        checked
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.reply_topology_enabled_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.block_comment_quick_reply)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = blockCommentQuickReply
            setOnCheckedChangeListener { _, isChecked ->
                blockCommentQuickReply = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment quick reply prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.block_comment_quick_reply_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_LIVE 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhanceLiveCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_LIVE] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_enhance_live)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.block_live_room_switch)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = blockLiveRoomSwitch
            setOnCheckedChangeListener { _, checked ->
                blockLiveRoomSwitch = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write live room switch prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.block_live_room_switch_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.live_room_double_tap_pause)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = liveRoomDoubleTapPause
            setOnCheckedChangeListener { _, checked ->
                liveRoomDoubleTapPause = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write live room double tap prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.live_room_double_tap_pause_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_PLAYBACK 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    /**
     * @param uiSettings 播放能力那一组是**按动态 key 循环**建开关的，拿不到对应的镜像字段，
     *   所以要把启动快照传进来。刻意用参数而不是字段：快照只在 `onCreate` 构建期有效，
     *   写回偏好之后它就是过期的（见 AGENTS.md 里 `uiSettings` 那条红线）。
     */
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhancePlaybackCategory(
        uiSettings: ModuleUiSettings
    ) {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_PLAYBACK] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_enhance_playback)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPlayerQualityDialog(it) }
            }
        ) {
            TextView(
                lparams = LayoutParams(widthMatchParent = true)
            ) {
                text = stringResource(R.string.player_default_quality)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 5.dp
                }
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(
                    R.string.player_default_quality_current,
                    playerQualityLabel(playerDefaultQualityQn)
                )
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                playerQualitySummaryView = this
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 4.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_default_quality_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.transparent_player_status_bar)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = transparentPlayerStatusBar
            setOnCheckedChangeListener { _, isChecked ->
                transparentPlayerStatusBar = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write player status bar prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.transparent_player_status_bar_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.player_capabilities_title)
            applyAdvancedSubsectionStyle()
        }
        listOf(
            FeaturePreferences.PLAYER_UNLOCK_BACKGROUND to R.string.player_unlock_background,
            FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW to R.string.player_unlock_small_window,
            FeaturePreferences.PLAYER_UNLOCK_CAST to R.string.player_unlock_cast
        ).forEach { (key, label) ->
            MaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                text = stringResource(label)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = uiSettings.bool(key)
                setOnCheckedChangeListener { _, checked ->
                    runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure {
                        Log.e("BilibiliInnocentLab", "write player capability prefs failed", it)
                    }
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_capabilities_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.player_speed_title)
            applyAdvancedSubsectionStyle()
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.player_disable_long_press)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = playerDisableLongPress
            setOnCheckedChangeListener { _, checked ->
                playerDisableLongPress = checked
                runCatching {
                    prefs().edit { putBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, checked) }
                }.onFailure {
                    Log.e("BilibiliInnocentLab", "write player long press prefs failed", it)
                }
                updatePlayerSpeedSummaries()
            }
        }
        listOf(true, false).forEach { longPress ->
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true) { topMargin = 8.dp },
                init = {
                    orientation = LinearLayout.VERTICAL
                    background = selfRippleBackground(10f)
                    updatePadding(horizontal = 0.dp, vertical = 9.dp)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showPlayerSpeedDialog(longPress, it) }
                    if (!longPress) settingsDestinations.bind(SettingsCatalog.ID_PLAYER_DEFAULT_SPEED,this)
                }
            ) {
                TextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(if (longPress) R.string.player_long_press_speed else R.string.player_default_speed)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                    alpha = 0.6f
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                    setLineSpacing(6f, 1f)
                    if (longPress) playerLongPressSpeedSummary = this else playerDefaultSpeedSummary = this
                    updatePlayerSpeedSummaries()
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_speed_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

    /** 进阶增强 · ENHANCE_BROWSING 分类；标题即 marker，必须是本组第一个子控件。 */
    @com.highcapable.hikage.annotation.Hikagable
    private fun Hikage.Performer<NativeLinearLayout.LayoutParams>.enhanceBrowsingCategory() {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_BROWSING] = this
            alpha = 0.9f
            text = stringResource(R.string.advanced_enhance_browsing)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.home_vertical_open_detail)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = homeVerticalOpenDetail
            setOnCheckedChangeListener { _, isChecked ->
                homeVerticalOpenDetail = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write home vertical detail prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.home_vertical_open_detail_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 5.dp
            }
        ) {
            text = stringResource(R.string.prefer_dynamic_video_tab)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = preferDynamicVideoTab
            setOnCheckedChangeListener { _, isChecked ->
                preferDynamicVideoTab = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write preferred dynamic video prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.prefer_dynamic_video_tab_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }

}
