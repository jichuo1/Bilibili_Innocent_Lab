@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.lumen.coacervation.engine.motion.LumenAnimator

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
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
import android.widget.CompoundButton
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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComponentLibraryPoolMatcher
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.RuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AndroidUserSpace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AndroidUserSpaceSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
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
        const val PREF_LAST_CHECK_CANARY = "last_successful_check_ms_canary"
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

    internal var hostBackgroundEditor: HostBackgroundEditor? = null
    internal val hostBackgroundPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // 系统选择器期间 Activity 可能重建，回到新的编辑器继续导入。
            val editor = hostBackgroundEditor ?: HostBackgroundEditor(this, window.decorView) {
                recreate()
            }.also { hostBackgroundEditor = it; it.show() }
            editor.importImage(uri)
        }
    }

    internal val liquidBackgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importLiquidBackground(uri)
    }

    internal val liquidBackgroundPhotoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(::importLiquidBackground)
    }

    internal val liquidBackgroundGalleryPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) importLiquidBackground(uri)
    }

    internal val liquidBackgroundGalleryPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchLiquidBackgroundGallery(afterGrant = true)
        } else {
            toast(getString(R.string.liquid_background_gallery_permission_denied))
            launchLiquidBackgroundDocumentPicker()
        }
    }

    internal var adskipEnabled = true
    internal var gamecardAdEnabled = true
    internal var hideVideoDetailAppPromotion = false
    internal var bannerAdEnabled = true
    internal var merchAdEnabled = true
    internal var hideLiveHomePromotion = false
    internal var hideHomeGameMenu = false
    internal var hideHomeSearchDefaultWord = false
    internal var homeVerticalOpenDetail = false
    internal var removeHomeRecommendAds = false
    internal var removeHomeRecommendCmV2 = false
    internal var removeHomeRecommendPictures = false
    internal var removeHomeRecommendGamePromotions = false
    internal var homeRecommendTitleFilterEnabled = false
    internal var homeRecommendTitleKeywords = ""
    internal var homeRecommendBlockedTids = ""
    internal var homeRecommendBlockedAuthors = ""
    internal var homeRecommendSectionPickEnabled = false
    internal var blockAiDeclaredVideos = false
    internal var blockAiDeclaredVideosStrongMode = false
    /** 强力模式 · 获取 access_key（推荐预检）的用户意图；实际生效还要通用授权。 */
    internal var blockAiDeclaredVideosPrecheck = false
    internal var aiStrongModeEntry: View? = null
    internal var aiStrongModeSummaryView: NativeTextView? = null
    internal fun isBlockAiDeclaredVideosEnabled(): Boolean = blockAiDeclaredVideos
    /** 实验性兼容「获取 access_key」授权；强力模式以它为前提。 */
    internal var biliAccessKeyAuthorized = false
    internal var biliAccessKeySwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null
    internal var biliAccessKeyProgrammaticSwitch = false
    internal var videoRelateBlockedAuthors = ""
    internal var videoRelateBlockedTags = ""
    internal var removeHomeRecommendLive = false
    internal var removeHomeRecommendCourses = false
    internal var removeHomeRecommendVertical = false
    internal var removeHomeRecommendLarge = false
    internal var removeHomeRecommendPgc = false
    internal var removeHomeRecommendComic = false
    internal var removeHomeRecommendSpecialCards = false
    private var homeTabHiddenRules = ""
    private var homeComponentHiddenRules = ""
    private var componentPoolBlockedRules = ""
    private var bottomBarHiddenRules = ""
    internal var recommendVideoMinDurationSeconds = 0
    internal var recommendVideoMaxDurationSeconds = 0
    internal var recommendVideoMinPlayCount = 0
    internal var recommendVideoMaxPlayCount = 0
    internal var recommendVideoPublishTimeRule = ""
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
    internal var hideMineVip = false
    internal var keepMineVipSpace = false
    private var mineComponentHiddenRules = ""
    /** 每个面各自节流一次查询；四个面的面板可以互不阻塞地打开。仅在主线程读写。 */
    private val componentSnapshotQueryInFlight = mutableSetOf<String>()
    /**
     * 「管理推荐屏蔽」要同时拉两个点选观测面，整体节流一次。
     *
     * internal：外移的 `showRecommendationBlocklistDialog` 要用它防重入。
     * 是 Activity 成员而不是文件顶层 `var`——顶层 var 是进程级单例，重建后会残留。
     * 仅在主线程读写（查询回调统一 post 回主线程）。
     */
    internal var recommendationPickQueryInFlight = false
    internal var blockAppUpdate = false
    internal var blockComponentLibraryDownload = false
    internal var hideDynamicCityTab = false
    internal var hideDynamicSchoolTab = false
    internal var preferDynamicVideoTab = false
    internal var showFullNumbers = false
    internal var hidePlayerPortraitControl = false
    internal var hidePlayerInteractiveOverlays = false
    internal var hidePlayerEndPageRecommend = false
    internal var hidePlayerPopupPromotion = false
    internal var hidePgcAutoActivityPopup = false
    internal var transparentPlayerStatusBar = false
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
    internal var playerCodecPreference = PlayerCodecPreference.FOLLOW_HOST.value
    internal var playerDecodeMode = PlayerDecodeMode.FOLLOW_HOST.value
    internal var playerBufferMode = PlayerBufferMode.FOLLOW_HOST.value
    internal var playerBufferSeconds = PlayerBufferConfig.DEFAULT_SECONDS
    internal var playerBufferMemoryMb = PlayerBufferConfig.DEFAULT_MEMORY_MB
    internal var playerBufferDiskMb = PlayerBufferConfig.DEFAULT_DISK_MB
    internal var playerBufferWifiOnly = PlayerBufferConfig.DEFAULT_WIFI_ONLY
    internal var playerDisableLongPress = false
    internal var playerLongPressSpeedPercent = 0
    internal var playerDefaultSpeedPercent = 0
    internal var playerSpeedMenuPercents: List<Int> = emptyList()
    internal var blockTeenagersModePrompt = false
    internal var removeCommentSearchLinks = false
    internal var removeCommentEmptyGuide = false
    internal var removeCommentVoteWidgets = false
    internal var removeCommentFollowButtons = false
    internal var removeCommentQoe = false
    internal var removeCommentOperations = false
    internal var removeCommentReplyButton = false
    internal var blockCommentQuickReply = false
    internal var hideCommentSection = false
    internal var replyTopologyEnabled = false
    internal var commentClassicStyle = false
    internal var commentKeywordFilterEnabled = false
    internal var commentFilterKeywords = ""
    internal var commentMinLevelFilterEnabled = false
    internal var commentMinLevel = CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL
    internal var dynamicKeywordFilterEnabled = false
    internal var semanticJevSummaryView: NativeTextView? = null
    internal var dynamicSemanticFilterEnabled = false
    internal var danmakuSemanticFilterEnabled = false
    internal var commentSemanticFilterEnabled = false
    internal var videoSemanticFilterEnabled = false
    internal var dynamicFilterKeywords = ""
    internal var dynamicAuthorFilterEnabled = false
    internal var dynamicAuthorFilterRules = ""
    internal var removeDynamicPromotions = false
    internal var removeDynamicChargeOnly = false
    internal var hideDynamicTopicList = false
    internal var hideDynamicFrequentVisits = false
    internal var removeDynamicLiveUpEntries = false
    internal var removeSearchCommercial = false
    internal var hideSearchHomeRecommend = false
    internal var searchKeywordFilterEnabled = false
    internal var searchFilterKeywords = ""
    internal var searchAuthorFilterEnabled = false
    internal var searchAuthorFilterRules = ""
    internal var removeAtOnlyComments = false
    internal var commentUserFilterEnabled = false
    internal var commentUserFilterRules = ""
    internal var danmakuWeightFilterEnabled = false
    internal var danmakuWeightMinimum = DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT
    internal var removeVipColorfulDanmaku = false
    internal var blockLiveRoomSwitch = false
    internal var liveRoomDoubleTapPause = false
    internal var purifyShareContent = false
    internal var shareMiniProgramDirectLink = false
    internal var forceExternalBrowser = false
    internal var systemMediaNotification = false
    internal var splashAutoNight = false
    internal var brandSplashSkip = false
    internal var brandSplashCustom = false
    internal var showBvAsAv = false
    internal var purifySplashAds = false
    internal var freeCopyEnabled = true
    internal var freeCopyDescEnabled = true
    internal var freeCopyLightMode = false
    internal var freeCopyAutoLight = false
    internal var refreshCopyAppearanceOptions: () -> Unit = {}
    internal var hostBottomBarLiquidGlass = false
    internal var hostBottomBarGlassAppearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance()
    internal var hostBottomBarTouchGlow = false
    internal var hostVideoCards = false
    internal var hostCardMaterial = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial.ORIGINAL
    internal var hostVideoCardRadiusDp = -1
    internal var hostHomeCardHorizontalDp = -1
    internal var hostHomeCardVerticalDp = -1
    internal var hostBottomBarCompact = false
    internal var hostBottomBarIconOnly = false
    internal var hostTopBarLiquidGlass = false
    internal var hostTopBarGlassAppearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance()
    internal var hostTopBarTouchGlow = false

    /** 亮色开关二次确认进行中标志（防 setOnCheckedChangeListener 重入递归） */
    internal var autoLightConfirmInProgress = false

    /** 程序化 setChecked（UI 同步）时抑制开关 listener 回触发 */
    internal var programmaticSwitch = false

    /** 手动亮色开关引用（自由复制区） */
    internal var manualLightSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null

    /** 自动跟随开关引用（美化栏的复制气泡外观） */
    internal var autoLightSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null

    /** 手动亮色开关下方 tip 引用（动态动画切换文本） */
    internal var lightModeTipView: NativeTextView? = null
    internal var playerQualitySummaryView: NativeTextView? = null
    internal var playerCodecPreferenceSummaryView: NativeTextView? = null
    internal var playerDecodeModeSummaryView: NativeTextView? = null
    internal var playerBufferSummaryView: NativeTextView? = null
    internal var playerLongPressSpeedSummary: NativeTextView? = null
    internal var playerDefaultSpeedSummary: NativeTextView? = null
    internal var playerSpeedMenuSummary: NativeTextView? = null
    internal var homeTabRulesSummaryView: NativeTextView? = null
    internal var homeRecommendTitleSummaryView: NativeTextView? = null
    internal var homeRecommendBlockedTidsSummaryView: NativeTextView? = null
    internal var homeRecommendBlockedAuthorsSummaryView: NativeTextView? = null
    internal var videoRelateBlockedAuthorsSummaryView: NativeTextView? = null
    internal var videoRelateBlockedTagsSummaryView: NativeTextView? = null
    internal var homeRecommendFilterEntryView: View? = null
    internal var homeRecommendFilterSummaryView: NativeTextView? = null
    internal var homeComponentRulesSummaryView: NativeTextView? = null
    internal var componentPoolRulesSummaryView: NativeTextView? = null
    internal var mineComponentRulesSummaryView: NativeTextView? = null
    internal var bottomBarRulesSummaryView: NativeTextView? = null
    internal var recommendVideoDurationSummaryView: NativeTextView? = null
    internal var recommendVideoPlayCountSummaryView: NativeTextView? = null
    internal var recommendVideoPublishTimeSummaryView: NativeTextView? = null
    internal var commentKeywordSummaryView: NativeTextView? = null
    internal var commentLevelSummaryView: NativeTextView? = null
    internal var commentUserFilterSummaryView: NativeTextView? = null
    internal var dynamicKeywordSummaryView: NativeTextView? = null
    internal var dynamicAuthorSummaryView: NativeTextView? = null
    internal var searchKeywordSummaryView: NativeTextView? = null
    internal var searchAuthorSummaryView: NativeTextView? = null
    internal var danmakuWeightSummaryView: NativeTextView? = null
    internal var portraitContentFilterSummaryView: NativeTextView? = null
    internal var videoRelateFilterSummaryView: NativeTextView? = null
    internal var detailModuleFilterSummaryView: NativeTextView? = null
    internal var storyActionIconsSummaryView: NativeTextView? = null
    /** 设置备份入口及标题：用于跨 Activity 容器形变的来源坐标。 */
    internal var settingsBackupEntryView: View? = null
    internal var settingsBackupEntryTitleView: NativeTextView? = null
    internal var roamingCompatEnabled = false
    internal var noRootDesiredEnabled = false
    internal var predictiveBackEnabled = false
    internal var logEnabled = true
    internal var logVerbose = true

    /** 实验性功能主栏下的两个独立二级菜单。 */
    internal var experimentalSettingsRoot: View? = null
    internal var appearanceContent: View? = null
    internal var appearanceChevron: View? = null
    private var appearanceExpanded = false
    internal var compatibilityContent: View? = null
    internal var compatibilityChevron: View? = null
    private var compatibilityExpanded = false

    /** 各可展开分节的形变驱动器，以内容 View 为键；视图树重建时随清场一并释放。 */
    private val sectionExpansionControllers =
        HashMap<View, SectionExpansionController>()

    /** 两个按用途拆分的进阶菜单，沿用同一属性动画。 */
    internal var purificationSettingsRoot: View? = null
    internal var enhancementSettingsRoot: View? = null
    internal var beautificationSettingsRoot: View? = null
    internal var purificationAdvancedContent: View? = null
    internal var purificationAdvancedChevron: View? = null
    private var purificationAdvancedExpanded = false
    internal var enhancementAdvancedContent: View? = null
    internal var enhancementAdvancedChevron: View? = null
    private var enhancementAdvancedExpanded = false

    /** 净化和增强都按区域独立折叠；只有跨页面共享过滤保留直接入口。 */
    internal enum class AdvancedSettingsCategory(
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

    internal val advancedCategoryMarkers =
        linkedMapOf<AdvancedSettingsCategory, NativeTextView>()
    private val advancedCategorySections =
        linkedMapOf<AdvancedSettingsCategory, AdvancedCategorySection>()

    /** 免 Root 配置只在用户明确开启后同步；回调不得持有 Activity 或 View。 */
    internal var noRootPrefsBridge: SharedPreferences? = null
    internal var noRootSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null
    internal var noRootStatusView: NativeTextView? = null
    internal var noRootProgrammaticSwitch = false

    /** 激活卡片需要同时聚合 LSPosed 状态与经过版本校验的免 Root heartbeat。 */
    private var activationCardView: View? = null
    /** 顶部工具栏：由 SettingsHomePresenter 提为悬浮层，盖在滚动内容之上。 */
    private var settingsFloatingToolbar: View? = null
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
    private val settingDependencies = SettingsDependencyBindings()
    internal val settingsDestinations = SettingsDestinationRegistry<View>()
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
    internal var settingsHome: SettingsHomePresenter? = null
    private val settingsRevealRequest = SettingsRevealRequest()
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
    internal var compatibilityModeSwitch: androidx.appcompat.widget.SwitchCompat? = null
    internal var compatibilityProgrammaticSwitch = false
    internal var compatibilityRetryButton: View? = null
    internal var compatibilityRetryHint: View? = null
    internal val compatibilityRetryTracker = CompatibilityRetryTracker()
    internal var compatibilityPendingRetry: PendingCompatibilityRetry? = null
    internal var termsPendingStatusView: NativeTextView? = null
    internal var termsDiagnosticsValueView: NativeTextView? = null

    /** Liquid renderer 的同一 Activity 失败只处理一次，避免重复 toast/recreate。 */
    // internal：外移的 SkinSummaryPresenter 要用；扩展函数看不见 private 成员。
    internal var skinFailureHandled = false

    /** 高级材质开关回退时抑制监听器重入；与 SPEC 开关同一模式。 */
    internal var advancedMaterialProgrammaticSwitch = false
    internal var materialColorSpecProgrammaticSwitch = false

    /** 自定义背景导入只允许单飞；文件解码、哈希和原子替换全部离开主线程。 */
    internal val liquidBackgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-import").apply { isDaemon = true }
    }
    internal var liquidBackgroundTask: Future<*>? = null
    internal var liquidBackgroundImportInProgress = false
    // internal：外移的 SkinSummaryPresenter 要用；扩展函数看不见 private 成员。
    internal var liquidBackgroundSummaryView: NativeTextView? = null
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
        LumenAnimator.property(badge).setListener(null).cancel()
        if (notice == null) {
            if (!updateUiResumed || badge.visibility != View.VISIBLE) {
                badge.visibility = View.INVISIBLE
                return
            }
            LumenAnimator.property(badge).alpha(0f).scaleX(0.55f).scaleY(0.7f)
                .setDuration(180L).setInterpolator(emphasizedDecelerate)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        badge.visibility = View.INVISIBLE
                        LumenAnimator.property(badge).setListener(null)
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
        LumenAnimator.property(badge).alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(320L).setInterpolator(emphasizedDecelerate).start()
    }

    /** 日志详细度档位描述 TextView（档位选择器本体由 LogSegmentScrubBar 自管理） */
    internal var logLevelDesc: android.widget.TextView? = null

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
    internal fun logLevelThumbBg(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(monetColors.primary)
        }

    /**
     * 切换日志详细度档位：写偏好 + 描述联动。
     * 滑块滑动、文字颜色渐变与弹簧回弹均由 LogSegmentScrubBar 内部完成。
     */
    internal fun commitLogLevel(index: Int) {
        val verbose = index == 1
        runCatching {
            prefs().edit {
                putString(
                    HookEntry.PREF_LOG_LEVEL,
                    if (verbose) HookEntry.LOG_LEVEL_COMPLETE else HookEntry.LOG_LEVEL_MINIMAL
                )
            }
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "write log level failed", t)
        }
        logVerbose = verbose
        updateLogLevelDesc()
    }

    /** 更新档位描述文本（跟随当前 logVerbose） */
    private fun updateLogLevelDesc() {
        val desc = logLevelDesc ?: return
        desc.text = getString(if (logVerbose) R.string.log_level_complete_desc else R.string.log_level_minimal_desc)
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
    internal fun noRootStatusText(state: NoRootDisplayState): Int = when (state) {
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
        val darkTheme = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accentColor = DiagnosticStatusPalette.color(
            ActivationCardVisualSpec.tone(displayState),
            darkTheme
        )
        activationCardView?.apply {
            background = skinCardBackground(monetColors.surfaceVariant, ActivationCardVisualSpec.CORNER_RADIUS_DP)
            // 涟漪必须常驻前景：整卡已可点击，长按弹性高光与轻点反馈都靠它。
            // 未激活态的 accent 光晕只画边缘（外发光/内发光/描边三层 stroke），
            // 叠在涟漪之上不遮挡按压反馈。
            val ripple = selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)
            foreground = if (activated) ripple else android.graphics.drawable.LayerDrawable(arrayOf(
                ripple,
                ActivationCardAccentDrawable(accentColor, resources.displayMetrics.density)
            ))
        }
        val activationContentColor = getColor(R.color.colorTextDark)
        activationIconView?.apply {
            setImageResource(if (activated) R.mipmap.ic_success else R.mipmap.ic_warn)
            imageTintList = ColorStateList.valueOf(accentColor)
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
    internal fun enableAndSynchronizeNoRootSupport() {
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

    internal fun disableNoRootSupport() {
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

    /** 弹窗窗口内的背板压暗层（位于卡片之下）；随各路径的动画进度同步 alpha。 */
    private val dialogScrims = java.util.WeakHashMap<Dialog, View>()

    /**
     * 弹窗**卡片矩形**与**可见表面**之间的差。
     *
     * 气泡面板的小角高度是加在 container 的 padding 上的（`applyBubbleSurface`），所以
     * `modalAnchorBounds(container)` 拿到的矩形比真正画出来的表面**大一条**：2026-09-17
     * 真机实测 GitHub 面板容器 top=283，而描边亮线在 y=314，正好差 31px＝9dp＝小角高。
     *
     * 子面板要"严丝合缝盖住父面板"就必须按可见表面对齐，否则上方会多出这条。
     * 只有气泡面板非零；普通弹窗查不到就是全零。
     */
    private val dialogSurfaceInsets = java.util.WeakHashMap<Dialog, android.graphics.Rect>()

    /** 弹窗画出来的那块表面在屏幕上的矩形。拿不到布局位置时返回 null。 */
    internal fun modalSurfaceBounds(dialog: Dialog, container: View): SettingsBackupMotionRect? {
        val bounds = modalAnchorBounds(container) ?: return null
        val insets = dialogSurfaceInsets[dialog] ?: return bounds
        return SettingsBackupMotionRect(
            left = bounds.left + insets.left,
            top = bounds.top + insets.top,
            right = bounds.right - insets.right,
            bottom = bounds.bottom - insets.bottom
        ).takeIf { it.isValid }
    }

    // internal：弹窗正按主题外移到同包的 Dialogs 文件（`internal fun MainActivity.showX()`），
    // 扩展函数拿不到 private 成员。下面几个 create*/present*/dismiss* 是外移弹窗的共用底座。
    /**
     * 收起动画的末帧先按时上屏，下一帧再移窗并执行后续回调。
     *
     * `dialog.dismiss()` 同步移窗，并在进程共享的 RenderThread 上销毁这个窗口的硬件渲染资源
     * （玻璃效果层、纹理）。原来它就跑在动画结束回调里，与收拢到底的最后一帧挤在同一帧：
     * 2026-09-24 atrace（9 次开关）关闭末帧 `notifyAnimEnd` 7–11ms，偶发紧接两帧
     * `dequeueBuffer` 15–18ms。推迟一帧后，这段开销落在画面静止的帧里；末帧已收拢到 0、
     * 窗口动画已关（setWindowAnimations(0)），推迟期间屏幕上没有可见内容。
     */
    private fun dismissAfterFinalFrame(dialog: Dialog, then: () -> Unit) {
        val decor = dialog.window?.decorView
        val finish = {
            if (dialog.isShowing) runCatching { dialog.dismiss() }
            then()
        }
        if (decor == null || !decor.isAttachedToWindow || isFinishing || isDestroyed) {
            finish()
        } else {
            decor.postOnAnimation { finish() }
        }
    }

    internal fun dismissWithAnimation(
        dialog: Dialog,
        container: View,
        onDismissed: () -> Unit
    ) {
        if (dialogAnchoredClosers[dialog]?.invoke(false, onDismissed) == true) return
        LumenAnimator.property(container)
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(180L)
            .setInterpolator(emphasizedAccelerate)
            // 背板压暗层随卡片淡出：锚点路径由 controller 的 onFrame 自己推进度，不走这里。
            // 缩放会移动卡片里的玻璃表面，逐帧通知皮肤按新位置重新采样（见入场处注释）。
            .setUpdateListener {
                dialogScrims[dialog]?.alpha = container.alpha
                notifyPreparedSkinPositionChanged()
            }
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
    /** 浅色主题的白色涟漪不透明度：玻璃表面约 226–230，按下提亮到约 243。 */
    private val LIGHT_THEME_RIPPLE_ALPHA = 0x8C

    internal fun selfRippleBackground(cornerRadiusDp: Float = 10f): CoverableRippleDrawable {
        val density = resources.displayMetrics.density
        // 局部 val 不能命名为 cornerRadius：同名会遮蔽 GradientDrawable 的 setCornerRadius
        // 属性，apply 块里给它赋值会报 "'val' cannot be reassigned"。
        val radiusPx = cornerRadiusDp * density
        // content 必须与 mask 同圆角：RippleDrawable.getOutline() 只取第一个非 mask 层，
        // 透明 ColorDrawable 的 outline 报出的是 radius=0 的**直角**矩形（不是 NaN），
        // 弹性长按高光（ElasticInteractionController.TouchHighlight）据此裁剪，
        // 设计圆角全在 mask 上，于是高光边缘变成方角、与涟漪边缘对不上。
        // content 仍然全透明，视觉与阴影都不变，只是让轮廓说真话。
        val content = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        // 涟漪色随主题：深色沿用浅灰提亮；浅色改用白色提亮。原来两种主题都取 colorTextGray，
        // 浅色下它是深灰 #323B42，展开/收起条目按下时先冒出一团灰黑光斑、再铺成整行灰底，
        // 读作"动画开始/结束时压了一层黑色遮罩"（2026-09-24 用户报告，真机录屏确认是涟漪）。
        val darkTheme = ColorUtils.calculateLuminance(monetColors.surface) < 0.5
        val rippleColor = if (darkTheme) {
            ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x30)
        } else {
            ColorUtils.setAlphaComponent(Color.WHITE, LIGHT_THEME_RIPPLE_ALPHA)
        }
        return CoverableRippleDrawable(rippleColor, content, mask)
    }

    internal fun renderPendingTermsUi(snapshot: UserTermsAuthorizationSnapshot) {
        if (!snapshot.consentState.isAcceptancePending) return
        val status = RemoteHookConfigStore.status()
        val noRootDesired = NoRootSupportStore.isDesiredEnabled(applicationContext)
        termsPendingStatusView?.text = if (snapshot.failureCode == UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE) {
            getString(R.string.user_terms_pending_local_failed)
        } else if (CommunicationCompatibilityStore.isEnabled(applicationContext)) {
            getString(R.string.communication_compatibility_pending)
        } else if (noRootDesired) {
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
        updateCommunicationCompatibilityHint()
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
        background = neutralWindowBackground()
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

    /**
     * 面板右下角那颗"关闭"。
     *
     * GitHub 面板与**叠在它上面**的遥测说明面板必须用同一颗：两张卡片矩形已经完全重合，
     * 按钮样式不同会让"关闭"在切换时左右跳一下——实测 `createTermsActionButton`（14f、
     * 粗体、左右 10dp）比这颗（15f、常规、左右 20dp）窄 38px，右边缘对齐但文字对不上。
     */
    internal fun createPanelCloseButton(onClick: () -> Unit): NativeTextView {
        val density = resources.displayMetrics.density
        return NativeTextView(this).apply {
            text = getString(R.string.dialog_close)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
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
        cancelSettingsReveal()
        pendingHighlightDestination = settingId
        highlightNavigationInFlight = true
        val generation = ++highlightNavigationGeneration
        revealSettingsSearchTarget(target, fromHighlight = true) {
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
        settingsHome?.saveState(outState)
        outState.putLong("compat_retry_revision", compatibilityRetryTracker.pendingRevision ?: 0L)
        outState.putInt("compat_retry_failures", compatibilityRetryTracker.failures)
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

    /**
     * 可滑动的选中面：与 [createGitHubMenuRow] 高亮行同一套皮肤选中背景，但独立成一个 View，
     * 供单选列表把"选中框"从旧项平移到新项（见 JEV 灵敏度面板）。
     */
    internal fun createSelectionIndicator(radiusDp: Float = 14f): View =
        View(this).also { skinSelectionControl(it, radiusDp, selected = true) }

    internal fun createSkinSlidingSelection(radiusDp: Float = 14f):
        com.lumen.coacervation.engine.widget.LumenSlidingSelection {
        val appearance = createSelectionIndicator(radiusDp)
        return com.lumen.coacervation.engine.widget.LumenSlidingSelection(
            this, appearance.background ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            notifyPositionChanged = { notifyPreparedSkinPositionChanged() }
        ).apply { indicator.foreground = appearance.foreground }
    }

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
            if (highlight) {
                // 高亮行走选中面背景；涟漪放前景，避免被皮肤表面整份覆盖后丢失按压反馈。
                skinSelectionControl(this, 14f, selected = true)
                foreground = selfRippleBackground(14f)
            } else {
                background = selfRippleBackground(14f)
            }
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
            // 空副标题不建视图：TextView 即使文本为空也会占满一行行高，加上 4dp 上边距
            // 等于每行凭空多出约 18dp。只有解码方式/优先视频解码那两张纯标题面板会传空串，
            // 其余 30 多个调用点都带副标题，行高不变。
            if (subtitle.isNotBlank()) addView(
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
            setOnClickListener { source ->
                // GitHub 面板**不关**：子面板从 ⓘ 长出来，展开端正好盖住这张卡片，
                // 收起时再露出它。两张矩形都必须在点击这一刻取——形变一开始卡片就会被
                // 改 alpha 和 outline，事后取到的位置不是用户看到的那个。
                showTelemetryInfoDialog(
                    origin = modalAnchorBounds(source),
                    // 按**可见表面**取，不是容器矩形：气泡的小角那条在 padding 里，
                    // 拿容器矩形会让子面板上方多出 9dp。
                    cover = modalSurfaceBounds(dialog, dialogContainer),
                    parentDialog = dialog,
                    parentContainer = dialogContainer
                )
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
        LumenAnimator.property(view).withEndAction(null).cancel()
        if (!view.isAttachedToWindow) { view.text = text; view.alpha = 0.72f; return }
        if (view.text.toString() == text) {
            LumenAnimator.property(view).alpha(0.72f).setDuration(180L).setInterpolator(emphasizedDecelerate).start()
            return
        }
        LumenAnimator.property(view).alpha(0f).setDuration(120L).setInterpolator(emphasizedAccelerate)
            .withEndAction {
                view.text = text
                LumenAnimator.property(view).alpha(0.72f).setDuration(180L)
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

    internal fun playerCodecPreferenceLabel(value: Int): String = when (PlayerCodecPreference.fromValue(value)) {
        PlayerCodecPreference.FOLLOW_HOST -> getString(R.string.player_codec_preference_follow)
        PlayerCodecPreference.H264 -> getString(R.string.player_codec_preference_h264)
        PlayerCodecPreference.H265 -> getString(R.string.player_codec_preference_h265)
        PlayerCodecPreference.AV1 -> getString(R.string.player_codec_preference_av1)
    }

    internal fun playerDecodeModeLabel(value: Int): String = when (PlayerDecodeMode.fromValue(value)) {
        PlayerDecodeMode.FOLLOW_HOST -> getString(R.string.player_decode_mode_follow)
        PlayerDecodeMode.FORCE_HARDWARE -> getString(R.string.player_decode_mode_hardware)
        PlayerDecodeMode.FORCE_SOFTWARE -> getString(R.string.player_decode_mode_software)
    }

    internal fun updatePlayerCodecSummaries() {
        playerCodecPreferenceSummaryView?.text = getString(
            R.string.player_codec_preference_current,
            playerCodecPreferenceLabel(playerCodecPreference)
        )
        playerDecodeModeSummaryView?.text = getString(
            R.string.player_decode_mode_current,
            playerDecodeModeLabel(playerDecodeMode)
        )
    }

    private fun playerSpeedLabel(percent: Int): String = if (percent == PlayerSpeedConfig.FOLLOW_HOST) {
        getString(R.string.player_speed_follow_host)
    } else {
        getString(R.string.player_speed_multiplier, PlayerSpeedConfig.formatMultiplier(percent))
    }

    private fun playerSpeedMenuLabel(percents: List<Int>): String = if (percents.isEmpty()) {
        getString(R.string.player_speed_follow_host)
    } else {
        PlayerSpeedConfig.formatMenuSummary(percents)
    }

    internal fun updatePlayerSpeedSummaries() {
        playerLongPressSpeedSummary?.text = if (playerDisableLongPress) {
            getString(R.string.player_long_press_speed_paused, playerSpeedLabel(playerLongPressSpeedPercent))
        } else playerSpeedLabel(playerLongPressSpeedPercent)
        playerDefaultSpeedSummary?.text = playerSpeedLabel(playerDefaultSpeedPercent)
        playerSpeedMenuSummary?.text = playerSpeedMenuLabel(playerSpeedMenuPercents)
    }

    /** 手动入口只展示已保存规则；宿主点选统一在管理面板确认。 */
    internal fun refreshRecommendationBlocklistSummaries() {
        homeRecommendBlockedTids = prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "").orEmpty()
        homeRecommendBlockedAuthors = prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, "").orEmpty()
        homeRecommendBlockedTidsSummaryView?.text = ruleEntryText(
            R.string.home_recommend_blocked_tids, R.string.home_recommend_blocked_tids_empty,
            R.string.home_recommend_blocked_tids_current, recommendationRuleCount(homeRecommendBlockedTids, tags = true)
        )
        homeRecommendBlockedAuthorsSummaryView?.text = ruleEntryText(
            R.string.home_recommend_blocked_authors, R.string.home_recommend_blocked_authors_empty,
            R.string.home_recommend_blocked_authors_current, recommendationRuleCount(homeRecommendBlockedAuthors, tags = false)
        )
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
        GitHubReleaseChecker.UpdateChannel.CANARY -> PREF_LAST_CHECK_CANARY
    }

    private fun checkingToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_checking_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_checking_preview
        GitHubReleaseChecker.UpdateChannel.CANARY -> R.string.update_checking_canary
    }

    private fun latestToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_latest_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_latest_preview
        GitHubReleaseChecker.UpdateChannel.CANARY -> R.string.update_latest_canary
    }

    private fun failedToastRes(channel: GitHubReleaseChecker.UpdateChannel): Int = when (channel) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_check_failed_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_check_failed_preview
        GitHubReleaseChecker.UpdateChannel.CANARY -> R.string.update_check_failed_canary
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
        when (GitHubReleaseChecker.compareVersions(release.tagName, BuildConfig.VERSION_NAME, channel)) {
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

    /** 弹窗背板压暗色（40% 黑）：比 UiTokens.scrim 略重，底页文字不会透过模态面板与内容混排。 */
    private val MODAL_SCRIM_COLOR = 0x66000000.toInt()

    /** 浅色主题背板：背景色压暗 8% 后 50% 不透明的浅色薄纱。 */
    private val LIGHT_MODAL_SCRIM_DARKEN = 0.08f
    private val LIGHT_MODAL_SCRIM_ALPHA = 0x80

    /**
     * 弹窗背板颜色随主题。深色沿用 40% 黑；浅色改为浅色薄纱。
     *
     * 2026-09-24 用户报告浅色下形变动画"深色"：背板按进度整屏淡入，而面板从按钮/行里逐渐长出，
     * 面板最终区域在没被盖满前透出的是已压暗的底页，比打开前和打开后都暗（真机：该区域
     * 175–190，首尾约 224）。40% 黑是按深色主题定的，深色底上看不出；浅色底上就是一块跟着
     * 形变伸缩的暗区。薄纱把底页文字对比压下来（文字约 50 → 137，背景基本不变），可读性目的
     * 不变，形变过程中未覆盖区域不再变暗。
     */
    private fun modalScrimColor(): Int =
        if (ColorUtils.calculateLuminance(monetColors.surface) < 0.5) MODAL_SCRIM_COLOR
        else ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(monetColors.background, Color.BLACK, LIGHT_MODAL_SCRIM_DARKEN),
            LIGHT_MODAL_SCRIM_ALPHA
        )

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
    internal fun modalAnchorBounds(anchor: View): SettingsBackupMotionRect? {
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
            // 玻璃皮肤下卡片是半透明的，不能用系统 elevation 投影：系统按不透明物体画阴影，
            // 半影有一半落在卡片内侧，透过玻璃显成一圈灰带（浅色下约 100px 宽，中间像套了
            // 一个直角亮框；2026-09-24 用户报告"边缘颜色异常"）。与气泡面板一致不带投影，
            // 面板靠描边与背板区分。无皮肤时卡片不透明，保留原投影。
            elevation = if (isLiquidSkinEffective || isMaterialYouSkinEffective) 0f else 12 * density
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
        onBackDismiss: () -> Unit = {},
        morphAnchorBounds: SettingsBackupMotionRect? = null,
        coverBounds: SettingsBackupMotionRect? = null,
        onDismissed: (() -> Unit)? = null
    ) = presentSizedModalDialog(dialog, container, null, morphAnchor, anchorStyle,
        onExpanded, onBackDismiss, morphAnchorBounds, coverBounds, onDismissed)

    /**
     * @param morphAnchor 传入无文字的来源图标（如工具栏的搜索/GitHub 按钮）即启用图标锚点形变：
     *   弹窗表面从该图标的位置与圆角长成整张卡片。传 null 保持原有的居中缩放入场，**默认不变**，
     *   30 个既有调用点一个都不受影响。
     * @param morphAnchorBounds 来源控件**在另一张弹窗里**时用它：那张弹窗会先收起，来源 View
     *   随之从窗口上摘掉，`modalAnchorBounds` 事后只会拿到 null。调用点在**点击那一刻**抓好的
     *   屏幕矩形从这里传进来，走的仍是同一套 `IconAnchoredMotion*`，不另开动画。
     *   仅在 `morphAnchor == null` 且为 [AnchorStyle.CONTAINER] 时生效：气泡路径要拿来源
     *   ImageView 做图案交接，静态矩形顶不了它的位置。
     * @param coverBounds **叠在父弹窗上**的子面板：把卡片摆到这张屏幕矩形（父面板卡片的位置与
     *   尺寸）上，形变的展开端因此正好盖住父面板，父面板**不关闭**、留在下面。
     *   传了它就意味着"这是子面板"，于是三件事一起变：不硬关当前 `activeConfirmDialog`、
     *   关闭时把它还回去、不再叠第二层背景模糊（父面板那层已经在了）。
     *   传进来的必须是父面板**画出来的表面**（见 [modalSurfaceBounds]），不是它的容器矩形：
     *   气泡面板的小角高度加在 container 的 padding 上，用容器矩形会让子面板上方多出 9dp。
     *   宽度与左上角严格对齐父面板；**高度取"父面板高度"与"内容自然高度"的较大值**——
     *   "完全遮挡"是目的，为了对齐把内容裁掉或塞进滚动区不是。
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
        onBackDismiss: () -> Unit = {},
        morphAnchorBounds: SettingsBackupMotionRect? = null,
        coverBounds: SettingsBackupMotionRect? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        clearElasticInteractions()
        container.tag = com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController.CONTAINER_TAG
        // 子面板要盖在父面板上，父面板就不能被硬关；关闭时再把它还回 activeConfirmDialog，
        // 否则更新检查那几处 `activeConfirmDialog?.isShowing` 会以为没有弹窗开着。
        val cover = coverBounds?.takeIf { it.isValid && anchorStyle == AnchorStyle.CONTAINER }
        val coveredParent = if (cover != null) activeConfirmDialog?.takeIf { it.isShowing } else null
        if (cover == null) activeConfirmDialog?.dismiss()
        stylePreparedSkinControls(container)
        com.lumen.coacervation.engine.motion.modal.LumenPanelScroll.prepare(container) { target ->
            installPreparedLiquidStretch(target) { dialog.isShowing && !isFinishing && !isDestroyed }
        }
        val density = resources.displayMetrics.density
        // 由 dismissWithAnimation 传进来的一次性收尾回调（例如"关闭后打开链接"）；
        // 为空时用弹窗自己的 onBackDismiss。每次弹窗独立一份，不能提到 Activity 字段上。
        val pendingAnchoredAfterClose =
            java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        // 静态来源矩形只服务"来源在另一张弹窗里"这一种情况：气泡要拿来源 ImageView 做图案
        // 交接，拿不到 View 就没有气泡可言，所以这里把它挡在 CONTAINER 之外。
        val capturedAnchorBounds = morphAnchorBounds
            ?.takeIf { morphAnchor == null && anchorStyle == AnchorStyle.CONTAINER && it.isValid }
        // 来源矩形必须在点击那一刻取：形变开始后弹窗窗口会盖住图标，事后查位置不可靠。
        // 实时 View 与静态矩形走同一条几何解析，动画本身没有分支。
        fun resolveAnchorOnScreen(): SettingsBackupMotionRect? =
            morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds
        val anchorBounds = morphAnchor?.takeIf {
            it.isAttachedToWindow && it.width > 0 && it.height > 0 &&
                (anchorStyle == AnchorStyle.BUBBLE || ValueAnimator.areAnimatorsEnabled())
        }?.let(::modalAnchorBounds)
            ?: capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }
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
            // 小角那条只在 container 的 padding 里，不在画出来的表面里；记下来，
            // 子面板要盖住本面板时才有办法按可见表面对齐（见 modalSurfaceBounds）。
            val tailPadding = (BUBBLE_TAIL_HEIGHT_DP * density).toInt()
            dialogSurfaceInsets[dialog] = android.graphics.Rect(
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
            )
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
            ?.let {
                IconAnchoredMotionLayer(
                    this,
                    // 所有锚点弹窗都把卡片表面托管给持久表面 View：形变首尾与落定画的是
                    // 同一个 Drawable，落定瞬间不再有 drawable 交接。覆盖式面板沿用此路径。
                    surfaceBackground = modalBackground
                        ?: skinModalBackground(monetColors.surface, MODAL_CORNER_RADIUS_DP),
                    fallbackColor = monetColors.surface,
                    surfaceRadiusPx = MODAL_CORNER_RADIUS_DP * density,
                    // 卡片阴影移交表面 View，随帧矩形生长/落定；覆盖式面板保持无阴影。
                    surfaceElevation = if (cover == null) container.elevation else 0f
                ).also { layer ->
                    if (layer.usesPersistentSurface) {
                        // 填充、描边与投影始终归承载表面；正文只负责内容动画。
                        container.background = null
                        container.elevation = 0f
                    }
                }
            }
        // 窗口内压暗层：盖在卡片之下、整窗铺开，随卡片/形变进度同步淡入淡出——
        // 平台 dim（FLAG_DIM_BEHIND）不可动画，且会硬切在自绘的形变/气泡入场之前。
        // 叠在父面板上的子面板不再加一层：父面板那层还在，两层 scrim 会叠加得更暗。
        val scrim = if (cover == null) View(this).apply {
            setBackgroundColor(modalScrimColor())
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        } else null
        val root = ModalCardRoot(this).apply {
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
            } else if (cover != null) {
                // 几何来自物理屏幕坐标，和气泡一样不能在 RTL 下再镜像一次；
                // 真正的 margin 要等 root 拿到屏幕位置才算得准，见 applyCoverPlacement()。
                NativeFrameLayout.LayoutParams(
                    cover.width.toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.TOP or Gravity.LEFT }
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
        // 窗口铺满整屏（见下方 dialog.window 配置），压暗层在最外层盖住状态栏与导航栏；
        // root 按系统栏内缩，卡片、气泡、覆盖式面板仍以 root 为坐标原点，几何与原来一致。
        // 2026-09-24 用户报告：原来窗口避开系统栏，打开面板后状态栏一条不被压暗。
        val windowFrame = NativeFrameLayout(this).apply {
            scrim?.let {
                addView(it, NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            addView(root, NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(windowFrame) { _, insets ->
            // 与原来 decor 的默认内缩一致：只让开系统栏与刘海。弹窗是 adjustPan，
            // 输入法由系统平移窗口处理，不在这里内缩。
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val params = root.layoutParams as NativeFrameLayout.LayoutParams
            if (params.leftMargin != safe.left || params.topMargin != safe.top ||
                params.rightMargin != safe.right || params.bottomMargin != safe.bottom
            ) {
                params.setMargins(safe.left, safe.top, safe.right, safe.bottom)
                root.layoutParams = params
            }
            insets
        }
        // 子面板贴到父面板矩形上。两张 Dialog 的窗口原点不保证相同（状态栏、adjustPan），
        // 所以每次都拿 root 的屏幕位置换算，不假设 0。
        val coverLocation = IntArray(2)
        fun applyCoverPlacement(): Boolean {
            val target = cover ?: return false
            if (root.width <= 0 || root.height <= 0) return false
            root.getLocationOnScreen(coverLocation)
            val params = container.layoutParams as? NativeFrameLayout.LayoutParams ?: return false
            val left = (target.left - coverLocation[0]).toInt()
            val top = (target.top - coverLocation[1]).toInt()
            val width = target.width.toInt()
            // 高度必须**算出来写死**，不能用 WRAP_CONTENT + minimumHeight：后者在
            // FrameLayout 里会被剩余空间撑到窗口底部（2026-09-17 实测卡片落到
            // [278,314][1398,3078]，而父面板底边是 2337）。
            // 先按目标宽度量一次自然高度：内容比父面板矮就取父面板高度（完全覆盖，
            // 空档由关闭行上方的弹性占位吸收），更高就取内容高度（不裁内容）。
            container.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val height = maxOf(target.height.toInt(), container.measuredHeight)
            var changed = false
            if (params.width != width) { params.width = width; changed = true }
            if (params.height != height) { params.height = height; changed = true }
            if (params.leftMargin != left) { params.leftMargin = left; changed = true }
            if (params.topMargin != top) { params.topMargin = top; changed = true }
            if (changed) container.requestLayout()
            return changed
        }
        var currentBubblePlacement = bubblePlacement
        var bubbleAnchor = anchorBounds
        fun notifyExpanded() {
            if (dialog.isShowing && !isFinishing && !isDestroyed) onExpanded()
        }
        // 背景浅毛玻璃：跟着弹窗自己的进度渐进，让面板与背景分层。
        // 用户开关 + Material You 美学 + API 31+ + 系统允许跨窗口模糊，四道门都在工厂里判。
        // 子面板不再叠一层：父面板那层还在，两层 blur-behind 会把底页糊到发灰，
        // 而且会把我们特意留在下面的父面板一起糊掉。
        // 被盖住的父面板：形变末段让它整窗淡出，终态只剩子面板那一条描边。
        // 两张卡片矩形完全重合时，两条半透明描边会叠加成更亮的一条（实测左边缘 87 → 103），
        // 这一跳发生在最后一帧，就是"末尾边缘抖动"。
        //
        // **必须淡整个 decorView，不能只淡内容容器**：气泡面板的表面（连同描边）是
        // `BubblePanelLayer` 画的，容器自己 `background = null`，淡容器只会让文字变淡、
        // 描边纹丝不动——这条是实测撞出来的，改回去就白修了。
        // 覆盖式子面板要淡出的是父面板的**卡片层**，绝不能淡整张 decorView——父面板的
        // 压暗层（scrim）就在那张 decorView 里，跟着淡到 0 等于背景压暗整个消失，而子面板
        // 按"父面板那层还在"的前提**故意没有自己的 scrim**，两条假设一撞就是全屏变亮
        // （2026-09-22 真机实测：面板外背景 BGR 25.7/29.3/26.1 → 42.0/48.0/42.7，
        // 底页文字明显透出）。父面板窗口层的孩子是 [scrim, root(卡片层, 飞行标题浮层)]，
        // 取第一个非 scrim 的孩子即内缩后的 root；拿不到就退回旧行为，不让排版异常变成崩溃。
        val coveredContent = if (cover != null) coveredParent?.let { parent ->
            val parentScrim = dialogScrims[parent]
            val parentRoot = parentScrim?.parent as? ViewGroup
            val cardLayer = if (parentRoot == null) null else (0 until parentRoot.childCount)
                .map(parentRoot::getChildAt)
                .firstOrNull { it !== parentScrim }
            cardLayer ?: parent.window?.decorView
        } else null
        val backdropBlur = if (cover != null) null else ModalBackdropBlur.createOrNull(
            window = dialog.window,
            userEnabled = ModalBackdropBlurStore.read(this),
            materialYouSkin = isMaterialYouSkinEffective,
            density = density
        )
        scrim?.let { dialogScrims[dialog] = it }
        val bubbleController = if (bubbleLayer != null) {
            BubbleMotionController(
                layer = bubbleLayer,
                onFrame = { progress ->
                    backdropBlur?.apply(progress)
                    scrim?.alpha = progress
                    coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
                },
                onExpanded = ::notifyExpanded,
                onContentMoved = { notifyPreparedSkinPositionChanged() },
                onClosed = {
                    dismissAfterFinalFrame(dialog) {
                        (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                    }
                }
            )
        } else {
            null
        }
        val titleMotion = if (morphLayer != null) {
            ModalTitleMotion.create(morphAnchor, container.firstChildOrNull<NativeTextView>(), root)
                ?.also { title ->
                    // 承载层带 elevation 后 Z>0，会把 Z=0 的兄弟盖到表面之下；
                    // 飞行标题只抬 Z 序（空 outline，自身不投影），保持在面板之上。
                    title.elevation = morphLayer.elevation + 1f
                    root.addView(title, NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
        } else null
        val morphController = if (morphLayer != null) {
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
                    resolveAnchorOnScreen()?.let { currentAnchor ->
                        resolveIconAnchoredGeometry(morphLayer, container, currentAnchor, density)
                    }
                },
                titleMotion = titleMotion,
                onFrame = { progress ->
                    backdropBlur?.apply(progress)
                    scrim?.alpha = progress
                    coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
                    // 父面板在子面板当前覆盖的区域里按子面板不透明度让位：外轮廓不回缩、
                    // 开头不露底、结尾不叠亮，见 ModalCardRoot。承载层 alpha 已在本帧回调前设好。
                    (coveredContent as? ModalCardRoot)?.excludeMotionSurface(morphLayer, morphLayer.alpha)
                },
                onExpanded = ::notifyExpanded,
                onContentMoved = { notifyPreparedSkinPositionChanged() },
                onClosed = {
                    dismissAfterFinalFrame(dialog) {
                        (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                    }
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
            // 铺到系统栏下面：浮动窗口默认按系统栏缩框，压暗层盖不到状态栏。
            // 内容区的内缩由 windowFrame 的 insets 监听自己做。
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            if (AndroidVersion.isAtLeast(AndroidVersion.R)) {
                attributes = attributes.apply {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } else {
                addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                if (AndroidVersion.isAtLeast(AndroidVersion.P)) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            // 弹窗窗口不带 DRAWS_SYSTEM_BAR_BACKGROUNDS 时，系统按旧语义在它盖住的状态栏上
            // 画一条不透明黑底、图标强制变白（真机实测状态栏整条 0,0,0，诊断确认弹窗自身
            // 在该区域没有任何绘制）。由窗口自己接管系统栏背景并设为透明，压暗层才透得出来。
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            if (AndroidVersion.isAtLeast(AndroidVersion.Q)) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            // 窗口盖住状态栏后由它决定图标明暗；沿用页面的设置，打开面板时图标不跳色。
            val pageBars = androidx.core.view.WindowCompat.getInsetsController(this@MainActivity.window, this@MainActivity.window.decorView)
            androidx.core.view.WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = pageBars.isAppearanceLightStatusBars
                isAppearanceLightNavigationBars = pageBars.isAppearanceLightNavigationBars
            }
        }
        dialog.setContentView(windowFrame)
        // 平台弹窗布局（screen_simple 一类）里有 fitsSystemWindows="true" 的容器，会把整个内容区
        // 连同压暗层按状态栏高度下推，空出的那条由 DecorView 的兜底背景填成黑色（真机实测
        // 状态栏整条 0,0,0）。祖先链一律不吃 insets，内缩只由 windowFrame 的监听做。
        var fitAncestor = windowFrame.parent as? View
        while (fitAncestor != null && fitAncestor !== dialog.window?.decorView) {
            fitAncestor.fitsSystemWindows = false
            fitAncestor.setPadding(0, 0, 0, 0)
            fitAncestor = fitAncestor.parent as? View
        }
        val releaseElasticInteraction = installDialogElasticInteraction(dialog)
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
        // 都要判，`onBackStarted` 尤其不能漏，它才是调用 LumenAnimator.property(container).cancel()
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
                            LumenAnimator.property(container).cancel()
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
                            scrim?.alpha = container.alpha
                            notifyPreparedSkinPositionChanged()
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
                            LumenAnimator.property(container)
                                .scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(emphasizedDecelerate)
                                // ViewPropertyAnimator 的监听器是黏性的：原先这里隐式沿用入场监听器推进
                                // 模糊与压暗，显式设置时要把那两项带上，再加上逐帧位置通知。
                                .setUpdateListener {
                                    backdropBlur?.apply(container.alpha)
                                    scrim?.alpha = container.alpha
                                    notifyPreparedSkinPositionChanged()
                                }
                                .withEndAction { notifyPreparedSkinPositionChanged() }
                                .start()
                            scrim?.let(LumenAnimator::property)?.alpha(1f)
                                ?.setDuration(260L)
                                ?.setInterpolator(emphasizedDecelerate)
                                ?.start()
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
            releaseElasticInteraction()
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
            dialogSurfaceInsets.remove(dialog)
            dialogScrims.remove(dialog)
            // 硬关会把形变停在半路，父面板不能留着半透明的 alpha：它马上就要重新露出来。
            coveredContent?.alpha = 1f
            (coveredContent as? ModalCardRoot)?.clearExclusion()
            // 子面板收起后父面板重新露出来，它必须变回"当前弹窗"：这个字段是更新检查
            // 与激活卡那几处 `activeConfirmDialog?.isShowing` 的唯一依据，留空会让它们
            // 以为没有弹窗开着，从而在父面板脸上再弹一个。
            if (activeConfirmDialog === dialog) {
                activeConfirmDialog = coveredParent?.takeIf { it.isShowing }
            }
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
            // 调用方的收尾在这里跑，而不是让调用方再 setOnDismissListener——
            // 那是单字段覆盖，会把本监听器承担的全部清理整条换掉。
            onDismissed?.invoke()
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
                        // 先把卡片摆到父面板矩形上再压首帧：位置还没定就 prepareFirstFrame，
                        // 展开端会按旧的居中矩形算，形变会往错的地方长。
                        if (applyCoverPlacement() || (cover != null && container.isLayoutRequested)) {
                            return false
                        }
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
            LumenAnimator.property(container)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                // 无锚点弹窗没有形变时钟，借它自己的入场进度推模糊。退场由
                // 共用的 dismissWithAnimation 负责，窗口撤掉时模糊随之消失（硬切，
                // 与这条路径本来的淡出观感一致），不去改那 72 个调用点。
                // 缩放入场会移动卡片里按钮等玻璃表面的屏幕位置，但属性动画既不触发滚动回调，
                // 也不重录子 View 的显示列表：不逐帧通知的话，表面采样会停在动画中途的位置，
                // 要等下一次无关重绘才按真实位置重采，按钮光影就会在打开后整体跳一下。
                // 与锚点形变路径（IconAnchoredMotionController.onContentMoved，2026-09-26 真机
                // 插桩 + 录屏验证）同一机制；本路径未单独做真机录屏。
                .setUpdateListener {
                    backdropBlur?.apply(container.alpha)
                    scrim?.alpha = container.alpha
                    notifyPreparedSkinPositionChanged()
                }
                .withEndAction {
                    backdropBlur?.apply(1f)
                    scrim?.alpha = 1f
                    notifyPreparedSkinPositionChanged()
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
        val legacyIdsKey: String? = null,
        /**
         * 非 null 时，手填规则编辑器上会多一个「全量禁止」开关，写入这个哨兵。
         *
         * 只有组件库资源池用它：那个面的候选要等宿主真的请求过清单才会出现，
         * 扫描为空时逐条手打池名并不现实，得给个"整份都不要"的出口。
         * 其余四个面的候选在页面打开时就齐了，不需要这条。
         */
        val blockAllSentinel: String? = null
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

    internal fun mineComponentPickerSurface() = ComponentPickerSurface(
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

    internal fun bottomBarPickerSurface() = ComponentPickerSurface(
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

    internal fun homeTabPickerSurface() = ComponentPickerSurface(
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

    internal fun homeComponentPickerSurface() = ComponentPickerSurface(
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

    internal fun componentPoolPickerSurface() = ComponentPickerSurface(
        surface = MineComponentSnapshotCodec.SURFACE_COMPONENT_POOLS,
        titleRes = R.string.component_pool_block_dialog_title,
        hintRes = R.string.component_pool_block_hint,
        labelRes = R.string.component_pool_block,
        selectorsKey = FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS,
        rulesKey = FeaturePreferences.COMPONENT_POOL_BLOCKED_RULES,
        status = genericStatusText(R.string.component_picker_surface_component_pools),
        currentRules = { componentPoolBlockedRules },
        onRulesSaved = { componentPoolBlockedRules = it },
        summaryView = { componentPoolRulesSummaryView },
        blockAllSentinel = ComponentLibraryPoolMatcher.MATCH_ALL_POOLS
    )

    /** 勾选数 + 手填规则的两行摘要；两者是并集关系，缺一方就只显示另一方。 */
    internal fun ComponentPickerSurface.summaryText(): String {
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

    internal fun queryComponentSnapshotAndOpenPicker(spec: ComponentPickerSurface) {
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

    // 外移的 recommendVideoDurationSummary() 要用它；扩展函数看不见 private 成员，
    // 按 SettingsUiSource 的既定做法放宽成 internal，而不是把它也搬出去。
    internal fun formatDurationSeconds(totalSeconds: Int): String {
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

    internal fun formatPlayCount(count: Int): String {
        if (count >= 100_000_000) {
            val whole = count / 100_000_000
            val tenths = (count % 100_000_000) / 10_000_000
            return if (tenths == 0) "${whole}亿" else "${whole}.${tenths}亿"
        }
        if (count >= 10_000) {
            val whole = count / 10_000
            val tenths = (count % 10_000) / 1_000
            return if (tenths == 0) "${whole}万" else "${whole}.${tenths}万"
        }
        return count.toString()
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
    internal fun applyPredictiveBack() {
        PredictiveBack.apply(window, predictiveBackEnabled)
    }

    /** 手动亮色开关生效（写 prefs + 更新状态，供确认对话框确认后调用） */
    internal fun onFreeCopyLightModeChanged(value: Boolean) {
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
    internal fun animateLightModeTip() {
        val tv = lightModeTipView ?: return
        val newText = getString(
            if (freeCopyAutoLight) R.string.free_copy_light_mode_auto_tip
            else R.string.free_copy_light_mode_tip
        )
        if (tv.textToString() == newText) return
        LumenAnimator.property(tv).cancel()
        // 淡出 → 换文本（此时不可见，父布局重排无割裂感）→ 淡入
        LumenAnimator.property(tv)
            .alpha(0f)
            .setDuration(120L)
            .setInterpolator(emphasizedAccelerate)
            .withEndAction {
                tv.text = newText
                LumenAnimator.property(tv)
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

    internal fun toggleSecondaryMenu(section: SettingsSearchSection) {
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
    internal fun NativeTextView.applyAdvancedSubsectionStyle() {
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
                // 折叠卡标题参与全局长按弹性：长按后拖动时原生流会收到 CANCEL
                // （涟漪退场、不触发折叠），轻点仍在 UP 前恢复几何后正常折叠。
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
     * 动画由 [SectionExpansionController] 驱动：单一进度弹簧统一控制卡片 clipBounds、
     * 内容行级联显影、兄弟控件滑行与箭头转角；布局全程保持展开态，p→0 收尾时
     * GONE 与同帧 offsets 复位互相抵消——不再出现"占位瞬移 + 内容事后淡入"的割裂。
     * 旧 ValueAnimator 逐帧写 layoutParams.height 的方案会让整棵设置树每帧重新
     * measure/layout，已被放弃。
     */
    private fun animateSecondarySection(
        content: View,
        chevron: View,
        expanded: Boolean
    ) {
        val card = content.parent as? ViewGroup
        val contentGroup = content as? ViewGroup
        if (card == null || contentGroup == null) {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
            return
        }
        val controller = sectionExpansionControllers.getOrPut(content) {
            SectionExpansionController(
                card = card,
                content = contentGroup,
                chevron = chevron,
                density = resources.displayMetrics.density,
                notifyPositionChanged = ::notifyPreparedSkinPositionChanged
            )
        }
        controller.setExpanded(expanded)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        // 开关关着时直接返回，不发任何 IPC；打开时也不唤起宿主，见函数注释。
        autoConfirmRecommendationPicksIfEnabled { refreshRecommendationBlocklistSummaries() }
    }

    override fun onResume() {
        super.onResume()
        updateUiResumed = true
        ColdStartUpdateSession.observe(updateUiOwner, updateNoticeObserver)
        scheduleColdStartUpdate()
        renderUpdateBadge()
        if (!userTermsDecision.isAuthorized) return
        if (intent?.action == com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentTaskNotification.OPEN_LOGS) {
            intent.action = null
            window.decorView.post { if (!isFinishing && !isDestroyed && userTermsDecision.isAuthorized) showAgentLogsDialog() }
        }
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
        compatibilityPendingRetry?.cancel()

        UserTermsAuthorizationCoordinator.removeListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.removeStatusListener(frameworkStatusListener)
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
        lspatchActivationReceiptTracker.endSession()
        super.onStop()
    }

    override fun onPause() {
        cancelSettingsReveal(keepPendingHighlight = true)
        updateUiResumed = false
        updateUiHandler.removeCallbacksAndMessages(null)
        ColdStartUpdateSession.state.pause(updateUiOwner)
        ColdStartUpdateSession.stopObserving(updateUiOwner)
        githubUpdateBadge?.let(LumenAnimator::property)?.setListener(null)?.cancel()
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
        BEAUTIFICATION,
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

    private fun settingsSearchSectionLabel(section: SettingsSearchSection): String =
        getString(
            when (section) {
                SettingsSearchSection.GENERAL -> R.string.settings_search_section_general
                SettingsSearchSection.PURIFICATION -> R.string.purify_settings
                SettingsSearchSection.PURIFICATION_ADVANCED -> R.string.purification_advanced_settings
                SettingsSearchSection.ENHANCEMENT -> R.string.enhancement_settings
                SettingsSearchSection.ENHANCEMENT_ADVANCED -> R.string.enhancement_advanced_settings
                SettingsSearchSection.BEAUTIFICATION -> R.string.settings_home_beautify
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
                beautificationSettingsRoot -> SettingsSearchSection.BEAUTIFICATION
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

        (settingsHome?.searchRoots ?: listOf(root)).forEach { visit(it, SettingsSearchSection.GENERAL) }
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

    /** All navigation sources share cancellation, including a replacement on the same page. */
    private fun cancelSettingsReveal(keepPendingHighlight: Boolean = false) {
        settingsRevealRequest.cancel()
        clearSettingsSearchTargetHighlight()
        highlightNavigationGeneration++
        highlightNavigationInFlight = false
        if (!keepPendingHighlight) pendingHighlightDestination = null
    }

    internal fun scheduleSettingsSearchTargetHighlight(targetView: View) {
        clearSettingsSearchTargetHighlight()
        settingsSearchHighlightView = targetView
        val highlightRunnable = object : Runnable {
            override fun run() {
                if (settingsSearchHighlightRunnable !== this) return
                settingsSearchHighlightRunnable = null
                if (!updateUiResumed || isFinishing || isDestroyed || !targetView.isAttachedToWindow ||
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

                val highlightAnimator = LumenAnimator.ofFloat(0f, 1f, 0f).apply {
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

    internal fun revealSettingsSearchTarget(
        target: RuntimeSettingsSearchTarget,
        fromHighlight: Boolean = false,
        afterReveal: (() -> Unit)? = null
    ) {
        if (fromHighlight) {
            settingsRevealRequest.cancel()
            clearSettingsSearchTargetHighlight()
        } else cancelSettingsReveal()
        if (!updateUiResumed || isFinishing || isDestroyed) {
            cancelSettingsReveal(keepPendingHighlight = fromHighlight)
            return
        }
        val home = settingsHome
        val scrollView = if (home != null) home.revealPageFor(target.view) else settingsSearchScrollView
        if (scrollView == null || !target.view.isSameOrDescendantOf(scrollView)) {
            cancelSettingsReveal()
            return
        }
        when (target.section) {
            SettingsSearchSection.PURIFICATION_ADVANCED,
            SettingsSearchSection.ENHANCEMENT_ADVANCED,
            SettingsSearchSection.APPEARANCE,
            SettingsSearchSection.COMPATIBILITY -> {
                setSecondaryMenuExpanded(target.section, expanded = true)
            }
            SettingsSearchSection.GENERAL,
            SettingsSearchSection.PURIFICATION,
            SettingsSearchSection.ENHANCEMENT,
            SettingsSearchSection.BEAUTIFICATION,
            SettingsSearchSection.EXPERIMENTAL -> Unit
        }
        if (target.section.isAdvanced) expandAdvancedCategoryContaining(target.view)
        val expanding = listOfNotNull(purificationAdvancedContent, enhancementAdvancedContent,
            appearanceContent, compatibilityContent) + advancedCategorySections.values.map { it.content }
        val targetSections = expanding.filter { target.view.isSameOrDescendantOf(it) }
        val rect = Rect()
        var destinationY: Int? = null
        var scrollAnimator: ValueAnimator? = null
        val scrollMotion = SettingsRevealScrollMotion(
            currentPosition = { scrollView.scrollY },
            setPosition = { y -> scrollView.scrollTo(0, y) }
        )
        var token = 0L
        val observer = scrollView.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!settingsRevealRequest.owns(token)) return@OnPreDrawListener true
            if (!updateUiResumed || isFinishing || isDestroyed || !target.view.isAttachedToWindow ||
                !scrollView.isAttachedToWindow || !target.view.isSameOrDescendantOf(scrollView)) {
                cancelSettingsReveal(keepPendingHighlight = fromHighlight && !updateUiResumed)
                return@OnPreDrawListener true
            }
            // Wait for the real expansion geometry and final transform, independent of animation scale.
            if (!scrollView.isLaidOut || scrollView.isLayoutRequested || target.view.isLayoutRequested ||
                target.view.width <= 0 || target.view.height <= 0 ||
                targetSections.any { it.isLayoutRequested || it.alpha != 1f || it.translationY != 0f }) {
                return@OnPreDrawListener true
            }
            if (!target.view.isShown) {
                cancelSettingsReveal()
                return@OnPreDrawListener true
            }
            target.view.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(target.view, rect)
            val child = scrollView.firstChildOrNull<View>()
            if (child == null) {
                cancelSettingsReveal()
                return@OnPreDrawListener true
            }
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            val range = (child.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0) -
                (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom)).coerceAtLeast(0)
            // 目标停靠点在顶部悬浮栏之下：栏高 + 常规留白，避免定位到的控件被栏盖住。
            val topOffset = (home?.currentHeaderInset ?: 0) +
                (28 * resources.displayMetrics.density).toInt()
            val desiredY = (rect.top - topOffset).coerceIn(0, range)
            if (destinationY != desiredY) {
                // Async module status text can change the page height while navigation is in progress.
                scrollAnimator?.cancel()
                if (destinationY == null) {
                    // End any preceding native fling before this request takes scroll ownership.
                    scrollView.smoothScrollTo(scrollView.scrollX, scrollView.scrollY, 0)
                }
                destinationY = desiredY
                val scrollToken = scrollMotion.retarget(desiredY)
                if (!ValueAnimator.areAnimatorsEnabled() || scrollView.scrollY == desiredY) {
                    scrollMotion.frame(scrollToken, 1f)
                } else {
                    scrollAnimator = LumenAnimator.ofFloat(0f, 1f).apply {
                        duration = 250L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            if (settingsRevealRequest.owns(token)) {
                                scrollMotion.frame(scrollToken, it.animatedFraction)
                            }
                        }
                        start()
                    }
                }
            }
            // A clamped end-of-list target is complete at its actual scroll position, not after a timer.
            if (scrollView.scrollY == destinationY && settingsRevealRequest.complete(token)) {
                scheduleSettingsSearchTargetHighlight(target.view)
                afterReveal?.invoke()
            }
            true
        }
        token = settingsRevealRequest.begin {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else scrollView.viewTreeObserver.removeOnPreDrawListener(listener)
            scrollMotion.cancel()
            scrollAnimator?.cancel()
            scrollAnimator = null
        }
        observer.addOnPreDrawListener(listener)
        scrollView.postInvalidateOnAnimation()
    }

    internal fun launchSettingsBackup() {
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
        settingDependencies.dispose()
        settingsHome?.dispose()
        settingsHome = null
        cancelSettingsReveal()
        compatibilityPendingRetry?.cancel()
        compatibilityRetryHint?.let(LumenAnimator::property)?.cancel()
        compatibilityRetryHint = null
        compatibilityRetryButton = null
        compatibilityModeSwitch = null

        highlightsDisposed = true
        settingsDestinations.clear()
        updateUiHandler.removeCallbacksAndMessages(null)
        ColdStartUpdateSession.state.pause(updateUiOwner)
        ColdStartUpdateSession.stopObserving(updateUiOwner)
        githubUpdateBadge?.let(LumenAnimator::property)?.setListener(null)?.cancel()
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
        hostBackgroundEditor?.close(animate = false)
        hostBackgroundEditor = null
        liquidBackgroundDialog?.dismiss()
        liquidBackgroundDialog = null
        liquidBackgroundDialogContainer = null
        liquidBackgroundTask?.cancel(true)
        liquidBackgroundTask = null
        liquidBackgroundWorker.shutdownNow()
        // 清理 View 引用字段，彻底断开对 hierarchy 的持有
        appearanceContent?.let(LumenAnimator::property)?.setListener(null)
        appearanceContent?.let(LumenAnimator::property)?.cancel()
        appearanceChevron?.let(LumenAnimator::property)?.cancel()
        compatibilityContent?.let(LumenAnimator::property)?.setListener(null)
        compatibilityContent?.let(LumenAnimator::property)?.cancel()
        compatibilityChevron?.let(LumenAnimator::property)?.cancel()
        purificationAdvancedContent?.let(LumenAnimator::property)?.setListener(null)
        purificationAdvancedContent?.let(LumenAnimator::property)?.cancel()
        purificationAdvancedChevron?.let(LumenAnimator::property)?.cancel()
        enhancementAdvancedContent?.let(LumenAnimator::property)?.setListener(null)
        enhancementAdvancedContent?.let(LumenAnimator::property)?.cancel()
        enhancementAdvancedChevron?.let(LumenAnimator::property)?.cancel()
        sectionExpansionControllers.values.forEach { it.cancel() }
        sectionExpansionControllers.clear()
        advancedCategorySections.values.forEach { section ->
            LumenAnimator.property(section.content).setListener(null)
            LumenAnimator.property(section.content).cancel()
            section.chevron?.let(LumenAnimator::property)?.cancel()
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
        beautificationSettingsRoot = null
        refreshCopyAppearanceOptions = {}
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
        logLevelDesc = null
        playerQualitySummaryView = null
        playerCodecPreferenceSummaryView = null
        playerDecodeModeSummaryView = null
        playerBufferSummaryView = null
        playerLongPressSpeedSummary = null
        playerDefaultSpeedSummary = null
        playerSpeedMenuSummary = null
        homeTabRulesSummaryView = null
        homeRecommendTitleSummaryView = null
        homeRecommendBlockedTidsSummaryView = null
        homeRecommendBlockedAuthorsSummaryView = null
        videoRelateBlockedAuthorsSummaryView = null
        videoRelateBlockedTagsSummaryView = null
        homeRecommendFilterEntryView = null
        homeRecommendFilterSummaryView = null
        homeComponentRulesSummaryView = null
        mineComponentRulesSummaryView = null
        bottomBarRulesSummaryView = null
        recommendVideoDurationSummaryView = null
        recommendVideoPlayCountSummaryView = null
        recommendVideoPublishTimeSummaryView = null
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

        // Neutral pre-consent backdrop does not initialize preferences or a rendering session.
        findViewById<View>(Android_R.id.content).background = neutralWindowBackground()

        // 条款门禁必须先于现有 prefs、跨进程镜像、主布局和自动更新检查。
        termsConsentState = UserTermsConsentStore.readStateOrInitialize(applicationContext)
        compatibilityRetryTracker.restore(savedInstanceState?.getLong("compat_retry_revision") ?: 0L,
            savedInstanceState?.getInt("compat_retry_failures") ?: 0, termsConsentState.pendingAcceptance?.revision)
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
        hideLiveHomePromotion = uiSettings.bool(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION)
        hideHomeGameMenu = uiSettings.bool(FeaturePreferences.HIDE_HOME_GAME_MENU)
        hideHomeSearchDefaultWord = uiSettings.bool(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD)
        homeVerticalOpenDetail = uiSettings.bool(FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL)
        HomeRecommendFilterCatalog.preferenceKeys.forEach { key ->
            applyHomeRecommendFilterValue(key, uiSettings.bool(key))
        }
        removeHomeRecommendCmV2 = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2)
        homeRecommendTitleFilterEnabled = uiSettings.bool(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED)
        homeRecommendTitleKeywords = uiSettings.string(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS)
        homeRecommendBlockedTids = uiSettings.string(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS)
        homeRecommendBlockedAuthors = uiSettings.string(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS)
        homeRecommendSectionPickEnabled =
            uiSettings.bool(FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED)
        blockAiDeclaredVideos = uiSettings.bool(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS)
        blockAiDeclaredVideosStrongMode =
            uiSettings.bool(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE)
        biliAccessKeyAuthorized = uiSettings.bool(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED)
        blockAiDeclaredVideosPrecheck = uiSettings.bool(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK)
        videoRelateBlockedAuthors = uiSettings.string(FeaturePreferences.VIDEO_RELATE_BLOCKED_AUTHORS)
        videoRelateBlockedTags = uiSettings.string(FeaturePreferences.VIDEO_RELATE_BLOCKED_TAGS)
        removeHomeRecommendVertical = uiSettings.bool(FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL)
        homeTabHiddenRules = uiSettings.string(FeaturePreferences.HOME_TAB_HIDDEN_RULES)
        homeComponentHiddenRules = uiSettings.string(FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES)
        componentPoolBlockedRules = uiSettings.string(FeaturePreferences.COMPONENT_POOL_BLOCKED_RULES)
        bottomBarHiddenRules = uiSettings.string(FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES)
        recommendVideoMinDurationSeconds =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS)
                .coerceAtLeast(0)
        recommendVideoMaxDurationSeconds =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS)
                .coerceAtLeast(0)
        recommendVideoMinPlayCount =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MIN_PLAY_COUNT)
                .coerceAtLeast(0)
        recommendVideoMaxPlayCount =
            uiSettings.int(FeaturePreferences.RECOMMEND_VIDEO_MAX_PLAY_COUNT)
                .coerceAtLeast(0)
        recommendVideoPublishTimeRule =
            uiSettings.string(FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
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
        blockComponentLibraryDownload = uiSettings.bool(FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD)
        hideDynamicCityTab = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_CITY_TAB)
        hideDynamicSchoolTab = uiSettings.bool(FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB)
        preferDynamicVideoTab = uiSettings.bool(FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB)
        showFullNumbers = uiSettings.bool(FeaturePreferences.SHOW_FULL_NUMBERS)
        hidePlayerPortraitControl = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL)
        hidePlayerInteractiveOverlays = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS)
        hidePlayerEndPageRecommend = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND)
        hidePlayerPopupPromotion = uiSettings.bool(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION)
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
        playerCodecPreference = PlayerCodecPreference.fromValue(
            uiSettings.int(FeaturePreferences.PLAYER_CODEC_PREFERENCE)
        ).value
        playerDecodeMode = PlayerDecodeMode.fromValue(
            uiSettings.int(FeaturePreferences.PLAYER_DECODE_MODE)
        ).value
        playerBufferMode = PlayerBufferMode.fromValue(uiSettings.int(FeaturePreferences.PLAYER_BUFFER_MODE)).value
        playerBufferSeconds = PlayerBufferConfig.normalizeSeconds(uiSettings.int(FeaturePreferences.PLAYER_BUFFER_SECONDS))
        playerBufferMemoryMb = PlayerBufferConfig.normalizeMemoryMb(uiSettings.int(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB))
        playerBufferDiskMb = PlayerBufferConfig.normalizeDiskMb(uiSettings.int(FeaturePreferences.PLAYER_BUFFER_DISK_MB))
        playerBufferWifiOnly = uiSettings.bool(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY)
        playerDisableLongPress = uiSettings.bool(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS)
        playerLongPressSpeedPercent = PlayerSpeedConfig.normalize(
            uiSettings.int(FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT)
        )
        playerDefaultSpeedPercent = PlayerSpeedConfig.normalize(
            uiSettings.int(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT)
        )
        playerSpeedMenuPercents = PlayerSpeedConfig.effectiveMenuPercents(
            playerDefaultSpeedPercent,
            uiSettings.string(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS)
        )
        removeCommentSearchLinks = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS)
        removeCommentEmptyGuide = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE)
        removeCommentVoteWidgets = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS)
        removeCommentFollowButtons = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS)
        removeCommentQoe = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_QOE)
        removeCommentOperations = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_OPERATIONS)
        removeCommentReplyButton = uiSettings.bool(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON)
        blockCommentQuickReply = uiSettings.bool(FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY)
        hideCommentSection = uiSettings.bool(FeaturePreferences.HIDE_COMMENT_SECTION)
        replyTopologyEnabled = uiSettings.bool(FeaturePreferences.REPLY_TOPOLOGY_ENABLED)
        commentClassicStyle = uiSettings.bool(FeaturePreferences.COMMENT_CLASSIC_STYLE)
        commentKeywordFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED)
        commentFilterKeywords = uiSettings.string(FeaturePreferences.COMMENT_FILTER_KEYWORDS)
        commentMinLevelFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED)
        commentMinLevel =
            uiSettings.int(FeaturePreferences.COMMENT_MIN_LEVEL).coerceIn(1, 6)
        dynamicKeywordFilterEnabled = uiSettings.bool(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED)
        dynamicSemanticFilterEnabled = uiSettings.bool(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED)
        danmakuSemanticFilterEnabled = uiSettings.bool(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED)
        commentSemanticFilterEnabled = uiSettings.bool(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED)
        videoSemanticFilterEnabled = uiSettings.bool(FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED)
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
        brandSplashSkip = uiSettings.bool(FeaturePreferences.BRAND_SPLASH_SKIP)
        brandSplashCustom = uiSettings.bool(FeaturePreferences.BRAND_SPLASH_CUSTOM)
        showBvAsAv = uiSettings.bool(FeaturePreferences.SHOW_BV_AS_AV)
        blockTeenagersModePrompt = uiSettings.bool(FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT)
        purifySplashAds = uiSettings.bool(FeaturePreferences.PURIFY_SPLASH_ADS)
        hostBottomBarLiquidGlass = uiSettings.bool(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS)
        hostBottomBarGlassAppearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance.read(
            uiSettings.string(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL),
            uiSettings.string(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER),
            bottom = true, enabled = hostBottomBarLiquidGlass)
        hostBottomBarTouchGlow = uiSettings.bool(FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW)
        loadHostCardSettings(uiSettings)
        hostBottomBarCompact = uiSettings.bool(FeaturePreferences.HOST_BOTTOM_BAR_COMPACT)
        hostBottomBarIconOnly = uiSettings.bool(FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY)
        hostTopBarLiquidGlass = uiSettings.bool(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS)
        hostTopBarGlassAppearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance.read(
            uiSettings.string(FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL),
            uiSettings.string(FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER),
            bottom = false, enabled = hostTopBarLiquidGlass)
        hostTopBarTouchGlow = uiSettings.bool(FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW)
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
                        // presenter 把工具栏包进 58dp 高的长胶囊悬浮栏（48dp 按钮 +
                        // 上下各 5dp，29dp 圆角成正胶囊端头）。水平方向不设内边距：
                        // 由首尾按钮各自的 5dp 外边距提供与上下一致的内边距，
                        // 否则左右会比上下宽出一截，胶囊端头与按钮边缘的距离不统一。
                        updatePadding(vertical = 5.dp)
                        clipChildren = false
                        clipToPadding = false
                        // 自身保持透明：弹性手势按"有表面的控件"提升形变组，
                        // 背景加在 presenter 包的外层上，三枚图标各自独立回弹。
                        settingsFloatingToolbar = this
                    }
                ) {
                    ImageView(
                        lparams = LayoutParams(48.dp, 48.dp) {
                            marginStart = 5.dp
                        }
                    ) {
                        background = skinChromeOverlayBackground(monetColors.surface, 24f)
                        foreground = selfRippleBackground(24f)
                        updatePadding(10.dp)
                        setImageResource(R.drawable.ic_search)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        contentDescription = stringResource(R.string.settings_search_description)
                        setOnClickListener { showSettingsSearchDialog(it) }
                    }
                    Space(lparams = LayoutParams { weight = 1f })
                    ImageView(
                        lparams = LayoutParams(48.dp, 48.dp) {
                            marginEnd = 12.dp
                        }
                    ) {
                        background = skinChromeOverlayBackground(monetColors.surface, 24f)
                        foreground = selfRippleBackground(24f)
                        updatePadding(10.dp)
                        setImageResource(R.drawable.ic_restart)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        contentDescription = stringResource(R.string.restart_bilibili)
                        setOnClickListener { showRestartConfirmDialog(it) }
                    }
                    // The badge stays outside the circular button and does not change its anchor geometry.
                    FrameLayout(
                        lparams = LayoutParams(48.dp, 48.dp) { marginEnd = 5.dp },
                        init = {
                            clipChildren = false
                            clipToPadding = false
                        }
                    ) {
                        ImageView(
                            lparams = LayoutParams(48.dp, 48.dp)
                        ) {
                            background = skinChromeOverlayBackground(monetColors.surface, 24f)
                            foreground = selfRippleBackground(24f)
                            updatePadding(10.dp)
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
                            githubUpdateBadge?.let(LumenAnimator::property)?.setListener(null)?.cancel()
                            githubUpdateBadge = this
                            // 角标不独占弹性手势：完整手势交给外层图标帧，长按拖动时
                            // 图标与角标作为一个整体形变（搜索/重启图标同款效果）。
                            tag = com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController.EXCLUDED_TAG
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
                        // 整卡参与全局长按弹性（与「设置备份与恢复」同款）：涟漪放前景，
                        // renderActivationUi 会在未激活态把 accent 光晕叠在它上面
                        // （光晕只画边缘，不挡按压反馈）。
                        // 可点击只为接住按下、成为弹性目标；轻点整卡**不**打开诊断，入口只有
                        // 右侧「统一功能诊断」按钮（2026-09-24 用户要求，原先整卡都会跳转）。
                        foreground = selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)
                        isClickable = true
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        activationCardView = this
                        // 读屏不播报"可激活"：卡片只是状态展示。
                        ViewCompat.replaceAccessibilityAction(this,
                            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                            null, null)
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
                                val surfaceColor = DiagnosticsEntryVisualSpec.surfaceColor(
                                    darkTheme, neutralColor, monetColors.surface
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
                        settingsBackupEntryCard(this)
                        Space(lparams = LayoutParams(height = 10.dp))
                        purifySettingsCard(this)
                        Space(lparams = LayoutParams(height = 10.dp))
                        enhancementSettingsCard(this, uiSettings)
                        Space(lparams = LayoutParams(height = 10.dp))
                        beautificationSettingsCard(this)
                        Space(lparams = LayoutParams(height = 10.dp))
                        experimentalFeaturesCard(this, uiSettings)
                        Space(lparams = LayoutParams(height = 10.dp))
                        logSettingsCard(this)
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

        installSettingsHome(savedInstanceState)
        val skinRoot = findViewById<View>(Android_R.id.content)
        bindPreparedSkinRoot(
            skinRoot,
            ::handleSkinRendererFailure
        )
        // 两个进阶菜单已在各自大类末尾；首帧前只整理区域分组，不移动顶层卡片。
        installAdvancedCategorySections()
        settingsDestinations.forEachTarget { id, view -> settingDependencies.bind(view, id) }
        settingDependencies.start(modulePrefs)
        renderNoRootUi()
        // 首次连续前台停留十秒后，每进程至多一次自动检查；不在布局重建时重复启动。
        scheduleColdStartUpdate()
    }

    private fun installSettingsHome(savedState: Bundle?) {
        val scroll = settingsSearchScrollView ?: return
        val content = settingsSearchRoot ?: return
        val shell = scroll.parent as? NativeLinearLayout ?: return
        settingsHome = SettingsHomePresenter(
            activity = this,
            shell = shell,
            originalScroll = scroll,
            originalContent = content,
            purification = purificationSettingsRoot,
            enhancement = enhancementSettingsRoot,
            beautification = beautificationSettingsRoot,
            activation = activationCardView,
            floatingToolbar = settingsFloatingToolbar,
            savedState = savedState,
            installStretch = { target, allowed -> installPreparedLiquidStretch(target, allowed) },
            finishStretch = { target -> finishPreparedLiquidStretch(target) },
            skinPositionChanged = { notifyPreparedSkinPositionChanged() },
            skinScrollPositionChanged = { scroll -> notifyPreparedSkinScrollPositionChanged(scroll) },
            skinContentSource = { bindPreparedSkinContentSource(it) },
            navigationChanged = {
                cancelSettingsReveal()
            },
            navigationTouched = {
                if (settingsRevealRequest.isActive) cancelSettingsReveal()
            }
        ).also {
            it.install()
            settingsSearchRoot = it.searchRoots.firstOrNull()
        }
        liquidStretchScrollTarget = null
    }

    internal fun styleHomeControls(root: View) = stylePreparedSkinControls(root)

    internal fun bindFavoriteSwitch(
        view: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch,
        storageKey: String,
        directToggle: Boolean = true
    ) {
        val id = SettingsCatalog.byStorageKey[storageKey]?.id ?: return
        view.settingId = id
        settingDependencies.bind(view, id)
        view.supportsFavoriteToggle = directToggle
    }

    internal fun bindSettingDestination(view: View, storageKey: String) {
        SettingsCatalog.byStorageKey[storageKey]?.id?.let { settingsDestinations.bind(it, view) }
    }

    internal fun bindSettingDependency(view: View, id: String) = settingDependencies.bind(view, id)

    /**
     * 高级材质开关：液态玻璃材质已并入柔光美学，开关只决定是否加装这层材质。
     *
     * 打开时默认把全屏实时取样一起打开（renderer 仍按设备支持情况自动降级到标准档）；
     * 关闭时不保留失效的高负载偏好。材质写入失败只回退开关本身。
     */
    internal fun setAdvancedMaterial(button: CompoundButton, enabled: Boolean) {
        val target = if (enabled) SkinId.LIQUID else SkinId.MATERIAL_YOU
        if (SkinRepository.resolveRequestedSkin(applicationContext) == target) return
        val result = runCatching {
            SkinRepository.beginSelection(applicationContext, target)
        }.onFailure { throwable ->
            Log.e("BilibiliInnocentLab", "persist advanced material selection failed", throwable)
        }.getOrNull()
        if (result?.persisted != true) {
            advancedMaterialProgrammaticSwitch = true
            button.isChecked = !enabled
            advancedMaterialProgrammaticSwitch = false
            toast(getString(R.string.skin_save_failed))
            return
        }
        runCatching { LiquidRealtimeCaptureStore.setEnabled(applicationContext, enabled) }
        button.post {
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    private fun createPromotionItem(
        @StringRes stringResource: Int,
        @DrawableRes imageResource: Int
    ) = Hikagable<MarginLayoutParams> {
        var linkLabel: NativeTextView? = null
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                updateMargins(left = 15.dp, right = 15.dp)
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surfaceVariant)
                foreground = selfRippleBackground(15f)
                setPadding(10.dp)
                setOnClickListener {
                    linkLabel?.let { label -> label.urls.singleOrNull()?.onClick(label) }
                }
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
                // 保留自动链接的样式与目标，由整卡处理点击，文字区也能进入全局弹性手势。
                movementMethod = null
                isClickable = false
                isLongClickable = false
                isFocusable = false
                linkLabel = this
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
    internal fun hideOrShowLauncherIcon(isShow: Boolean) {
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
    internal val isLauncherIconShowing
        get() = packageManager?.isComponentEnabled(homeComponent) == true
    internal fun writeSemanticSwitch(key: String, checked: Boolean) {
        runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure { throwable ->
            Log.e("BilibiliInnocentLab", "write semantic filter switch failed", throwable)
        }
    }

}
