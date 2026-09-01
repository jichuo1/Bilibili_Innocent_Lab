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
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.highcapable.betterandroid.system.extension.component.disableComponent
import com.highcapable.betterandroid.system.extension.component.enableComponent
import com.highcapable.betterandroid.system.extension.component.isComponentEnabled
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
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
import com.highcapable.hikage.widget.android.widget.ImageView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.Space
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.RuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ShellCommandRunner
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateCheckCoordinator
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
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsGateDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsSyncState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.didUserTermsAuthorizationComplete
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : SkinnedActivity() {

    private companion object {
        /** 旧版本共用的成功检查时间（升级后作为稳定版渠道的历史时间迁移读取）。 */
        const val PREF_LAST_SUCCESSFUL_UPDATE_CHECK = "last_successful_check_ms"
        /** 各渠道独立的成功检查时间，避免切换渠道后 24 小时节流误跳过新渠道检查。 */
        const val PREF_LAST_CHECK_STABLE = "last_successful_check_ms_stable"
        const val PREF_LAST_CHECK_PREVIEW = "last_successful_check_ms_preview"
        const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
        const val FRAMEWORK_STATUS_SETTLE_MS = 1_500L
        const val MINE_COMPONENT_SNAPSHOT_STALE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SETTINGS_SEARCH_HIGHLIGHT_DELAY_MS = 240L
        const val SETTINGS_SEARCH_HIGHLIGHT_DURATION_MS = 560L

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

    private enum class AppLanguage(
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

    private val liquidBackgroundPicker = registerForActivityResult(
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
    private var removeHomeRecommendAds = false
    private var removeHomeRecommendPictures = false
    private var removeHomeRecommendGamePromotions = false
    private var homeRecommendTitleFilterEnabled = false
    private var homeRecommendTitleKeywords = ""
    private var removeHomeRecommendLive = false
    private var removeHomeRecommendCourses = false
    private var removeHomeRecommendVertical = false
    private var removeHomeRecommendLarge = false
    private var homeTabHiddenRules = ""
    private var homeComponentHiddenRules = ""
    private var bottomBarHiddenRules = ""
    private var recommendVideoMinDurationSeconds = 0
    private var recommendVideoMaxDurationSeconds = 0
    private var removeStoryAds = false
    private var removeStoryLive = false
    private var removeStoryGames = false
    private var removeStoryBangumi = false
    private var removeStoryCourses = false
    private var removeStoryShortDrama = false
    private var removeStoryShopping = false
    private var removeStoryMovies = false
    private var removeStoryDocumentaries = false
    private var removeStoryTv = false
    private var removeStoryVariety = false
    private var removeStoryMusic = false
    private var hideMineVip = false
    private var keepMineVipSpace = false
    private var mineComponentHiddenRules = ""
    private var mineComponentSnapshotQueryInFlight = false
    private var blockAppUpdate = false
    private var hideDynamicCityTab = false
    private var hideDynamicSchoolTab = false
    private var preferDynamicVideoTab = false
    private var showFullNumbers = false
    private var hidePlayerPortraitControl = false
    private var transparentPlayerStatusBar = false
    private var removeRelateCommercial = false
    private var removeRelateGame = false
    private var removeRelateLive = false
    private var removeRelateCourse = false
    private var removeRelateSpecial = false
    private var videoRelateMatchingEnhancementEnabled = false
    private var videoRelateReasonFilterEnabled = false
    private var videoRelateReasonFilterKeywords = ""
    private var playerDefaultQualityQn = 0
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
    private var commentMinLevel = CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL
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

    /** 自动跟随开关引用（实验性功能区） */
    private var autoLightSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null

    /** 手动亮色开关下方 tip 引用（动态动画切换文本） */
    private var lightModeTipView: NativeTextView? = null
    private var playerQualitySummaryView: NativeTextView? = null
    private var homeTabRulesSummaryView: NativeTextView? = null
    private var homeRecommendTitleSummaryView: NativeTextView? = null
    private var homeComponentRulesSummaryView: NativeTextView? = null
    private var mineComponentRulesSummaryView: NativeTextView? = null
    private var bottomBarRulesSummaryView: NativeTextView? = null
    private var recommendVideoDurationSummaryView: NativeTextView? = null
    private var commentKeywordSummaryView: NativeTextView? = null
    private var commentLevelSummaryView: NativeTextView? = null
    private var portraitContentFilterSummaryView: NativeTextView? = null
    private var videoRelateFilterSummaryView: NativeTextView? = null
    /** 设置备份入口及标题：用于跨 Activity 容器形变的来源坐标。 */
    private var settingsBackupEntryView: View? = null
    private var settingsBackupEntryTitleView: NativeTextView? = null
    private var roamingCompatEnabled = false
    private var noRootDesiredEnabled = false
    private var predictiveBackEnabled = false
    private var logEnabled = true
    private var logVerbose = true

    /** "实验性功能"二级菜单：内容容器、箭头指示器、展开状态 */
    private var experimentalContent: View? = null
    private var experimentalChevron: View? = null
    private var experimentalExpanded = false

    /** "进阶设置"二级菜单：与实验性功能共用同一套展开/收起动效。 */
    private var advancedContent: View? = null
    private var advancedChevron: View? = null
    private var advancedExpanded = false

    /** 进阶设置内部的四个折叠分类；只重组 View 层级，不复制或重建业务控件。 */
    private enum class AdvancedSettingsCategory {
        HOME_NAVIGATION,
        INTERFACE,
        PLAYBACK,
        COMMENT
    }

    private data class AdvancedCategorySection(
        val header: View,
        val content: View,
        val chevron: View,
        var expanded: Boolean = false
    )

    private val advancedCategoryMarkers =
        linkedMapOf<AdvancedSettingsCategory, NativeTextView>()
    private val advancedCategorySections =
        linkedMapOf<AdvancedSettingsCategory, AdvancedCategorySection>()

    /** 免 Root 配置只在用户明确开启后同步；回调不得持有 Activity 或 View。 */
    private var noRootPrefsBridge: SharedPreferences? = null
    private var noRootSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null
    private var noRootStatusView: NativeTextView? = null
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
    private var activeConfirmDialog: Dialog? = null

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
    private var userTermsDecision = UserTermsDecision.UNDECIDED
    private var termsConsentState = UserTermsConsentState(UserTermsDecision.UNDECIDED)
    private var termsAuthorizationSnapshot: UserTermsAuthorizationSnapshot? = null
    private var termsDecisionActionInProgress = false

    /** 条款弹窗/等待页的提示与框架管理器入口，只持有当前 Activity 的 View。 */
    private var termsDialogHintView: NativeTextView? = null
    private var termsManagerLauncher: NativeTextView? = null
    private var termsPendingStatusView: NativeTextView? = null
    private var termsDiagnosticsValueView: NativeTextView? = null

    /** Liquid renderer 的同一 Activity 失败只处理一次，避免重复 toast/recreate。 */
    private var skinFailureHandled = false

    /** 皮肤选择的同步写入和退场动画只允许单飞，避免重复动画吞掉 recreate 回调。 */
    private var skinSelectionActionInProgress = false
    private var skinSummaryView: NativeTextView? = null
    private var materialColorSpecProgrammaticSwitch = false

    /** 自定义背景导入只允许单飞；文件解码、哈希和原子替换全部离开主线程。 */
    private val liquidBackgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-import").apply { isDaemon = true }
    }
    private var liquidBackgroundTask: Future<*>? = null
    private var liquidBackgroundImportInProgress = false
    private var liquidBackgroundSummaryView: NativeTextView? = null
    private var liquidBackgroundDialog: Dialog? = null
    private var liquidBackgroundDialogContainer: NativeLinearLayout? = null

    /** GitHub 请求只允许单飞；切换渠道时保留最后一次手动请求并抑制过期结果。 */
    private val updateCheckCoordinator = UpdateCheckCoordinator()

    /** 日志详细度档位选择器的两个 pill 控件引用 + 滑动滑块 + 描述 TextView */
    private var logLevelMinimalPill: android.widget.TextView? = null
    private var logLevelCompletePill: android.widget.TextView? = null
    private var logLevelThumb: View? = null
    private var logLevelDesc: android.widget.TextView? = null
    private var logLevelColorAnimator: ValueAnimator? = null
    /** 档位选择器是否已完成首次布局（滑块定位需要测量后执行） */
    private var logLevelLaidOut = false

    // Material You 标准动效插值器
    private val emphasizedDecelerate = PathInterpolator(0.2f, 0f, 0f, 1f)   // 展开（减速收尾）
    private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 1f, 1f)   // 收起（加速开始）
    // 超长二级菜单使用独立的 Material 3 风格曲线：快速建立反馈，保留更长的柔和收尾。
    // 不复用上方插值器，避免改变弹窗、日志滑块等已有动画的节奏。
    private val secondaryExpandInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val secondaryCollapseInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

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
        val onPrimary = monetColors.onPrimary

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

    private fun currentNoRootDisplayState(): NoRootDisplayState {
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
        val displayState = NoRootSupportState.activationDisplayState(
            rootActive = framework.capable,
            frameworkCheckPending = frameworkStatusCheckPending && !framework.connected,
            displayState = noRootState
        )
        val activated = displayState == ActivationDisplayState.ACTIVE_LSPOSED ||
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
                ActivationDisplayState.ACTIVE_NPATCH -> R.string.module_is_activated
                ActivationDisplayState.UNAVAILABLE -> R.string.module_activation_not_detected
            }
        )
        activationSourceView?.apply {
            text = when (displayState) {
                ActivationDisplayState.ACTIVE_LSPOSED -> if (framework.apiVersion > 0) getString(
                    R.string.activated_by,
                    framework.name,
                    framework.apiVersion
                ) else getString(
                    R.string.activated_by_noapi,
                    framework.name
                )
                ActivationDisplayState.ACTIVE_NPATCH ->
                    getString(R.string.no_root_activated_by_npatch)
                ActivationDisplayState.CHECKING ->
                    getString(R.string.module_activation_waiting_framework)
                ActivationDisplayState.UNAVAILABLE ->
                    getString(
                        if (framework.connected) {
                            R.string.module_activation_framework_unsupported
                        } else {
                            R.string.module_activation_service_unavailable
                        }
                    )
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
            val (statusRes, statusTone) = when {
                displayState == ActivationDisplayState.CHECKING ->
                    R.string.diagnostics_entry_checking to DiagnosticStatusTone.INFO
                displayState == ActivationDisplayState.UNAVAILABLE ->
                    R.string.diagnostics_entry_action_required to
                        DiagnosticStatusTone.ACTION_REQUIRED
                publishState == RemoteHookConfigPublishState.FAILED ||
                    skinFallback || noRootNeedsAttention ->
                    R.string.diagnostics_entry_attention to DiagnosticStatusTone.ATTENTION
                else -> R.string.diagnostics_entry_ready to DiagnosticStatusTone.OK
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

    private fun synchronizeNoRootSupportIfEnabled() {
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

    private fun shouldUseNoRootRestartFlow(): Boolean {
        if (RemoteHookConfigStore.status().capable) return false
        if (NoRootSupportStore.isDesiredEnabled(applicationContext)) return true
        return when (currentNoRootDisplayState()) {
            NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE -> true
            else -> false
        }
    }

    /**
     * 二次确认弹窗：圆角半透明模态风格 + Material You 动效。
     * 弹窗采用 scale + alpha 动画（GPU 加速、不触发布局重绘，低功耗），
     * 符合 Material 3 的 emphasized easing 标准。
     */
    private fun showRestartConfirmDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val useNoRootFlow = shouldUseNoRootRestartFlow()
        val container = createModalContainer()

        // 标题
        container.addView(
            NativeTextView(this).apply {
                text = getString(
                    if (useNoRootFlow) R.string.no_root_restart_confirm_title
                    else R.string.restart_bilibili_confirm_title
                )
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        if (useNoRootFlow) {
            container.addView(
                NativeTextView(this).apply {
                    text = getString(R.string.no_root_restart_confirm_message)
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 13f
                    setLineSpacing(4 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * density).toInt() }
            )
        }

        // 按钮行
        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        // 取消按钮（文本按钮 + 标准 ripple）
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
                textColor = getColor(R.color.colorTextGray)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )

        // 确认按钮（圆角 filled + 跟随圆角的 ripple）
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(
                    if (useNoRootFlow) R.string.no_root_restart_open_app_details
                    else R.string.dialog_confirm
                )
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((22 * density).toInt(), (11 * density).toInt(), (22 * density).toInt(), (11 * density).toInt())
                val radius = 20 * density
                val content = GradientDrawable().apply { cornerRadius = radius; setColor(monetColors.primary) }
                val rippleMask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
                background = RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        if (useNoRootFlow) {
                            flushNoRootSupportBeforeOpeningDetails()
                        } else {
                            restartBilibili()
                        }
                    }
                }
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (16 * density).toInt()
            }
        )

        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (22 * density).toInt()
            }
        )

        presentModalDialog(dialog, container)
    }

    /** 弹窗退场动画（scale 缩小 + fade out），结束后 dismiss 并回调 */
    private fun dismissWithAnimation(
        dialog: Dialog,
        container: View,
        onDismissed: () -> Unit
    ) {
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
    private fun selfRippleBackground(cornerRadiusDp: Float = 10f): RippleDrawable {
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

    /** 未决定时主界面不参与构建，仅保留中性背景并展示不可取消的条款窗口。 */
    private fun showUserTermsGate() {
        setContentView(createTermsNeutralRoot())
        runCatching { showUserTermsDialog() }.onFailure { throwable ->
            Log.e("BilibiliInnocentLab", "show user terms dialog failed", throwable)
            finish()
        }
    }

    /** 用户已经作出同意决定，但 API 102 快照尚未完整发布、读回并确认。 */
    private fun showPendingTermsPage(snapshot: UserTermsAuthorizationSnapshot) {
        val density = resources.displayMetrics.density
        val root = createTermsNeutralRoot()
        val container = createModalContainer().apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.user_terms_pending_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.user_terms_pending_message)
                textColor = getColor(R.color.colorTextDark)
                textSize = 14f
                alpha = 0.82f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )
        val statusView = NativeTextView(this).apply {
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
            setLineSpacing(4 * density, 1f)
        }
        termsPendingStatusView = statusView
        container.addView(
            statusView,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        )
        container.addView(
            createTermsDiagnosticsCard(),
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).toInt() }
        )

        val managerLauncher = createTermsManagerLauncher()
        termsManagerLauncher = managerLauncher
        container.addView(
            managerLauncher,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).toInt() }
        )
        container.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_retry_sync),
                filled = true
            ) {
                UserTermsAuthorizationCoordinator.retryPendingAcceptance(applicationContext)
                termsAuthorizationSnapshot =
                    UserTermsAuthorizationCoordinator.snapshot(applicationContext)
                termsAuthorizationSnapshot?.let(::renderPendingTermsUi)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_decline),
                filled = false
            ) {
                if (termsDecisionActionInProgress) return@createTermsActionButton
                termsDecisionActionInProgress = true
                val result = UserTermsAuthorizationCoordinator.decline(applicationContext)
                if (result.succeeded) {
                    userTermsDecision = UserTermsDecision.DECLINED
                    finish()
                } else {
                    termsDecisionActionInProgress = false
                    toast(userTermsFailureMessage(result.failureCode))
                    recreate()
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )

        val centeringFrame = NativeFrameLayout(this).apply {
            addView(
                container,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins(
                        (24 * density).toInt(),
                        (36 * density).toInt(),
                        (24 * density).toInt(),
                        (36 * density).toInt()
                    )
                }
            )
        }
        root.addView(
            NativeScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                addView(
                    centeringFrame,
                    NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        renderPendingTermsUi(snapshot)
    }

    private fun renderPendingTermsUi(snapshot: UserTermsAuthorizationSnapshot) {
        if (!snapshot.consentState.isAcceptancePending) return
        val status = RemoteHookConfigStore.status()
        termsPendingStatusView?.text = when {
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
        updateTermsManagerLauncher(status)
        renderTermsGateDiagnostics()
    }

    /** 条款门禁内的只读环境摘要；不提供开关、跳转或宿主进程查询能力。 */
    private fun createTermsDiagnosticsCard(): NativeLinearLayout {
        val density = resources.displayMetrics.density
        return NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            isClickable = false
            isFocusable = false
            setPadding(
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(
                    ColorUtils.blendARGB(
                        monetColors.surfaceVariant,
                        monetColors.background,
                        0.14f
                    )
                )
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(monetColors.primary, 0x66)
                )
            }
            addView(
                NativeTextView(this@MainActivity).apply {
                    text = getString(R.string.user_terms_diagnostics_title)
                    textColor = getColor(R.color.colorTextDark)
                    textSize = 13f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                NativeTextView(this@MainActivity).apply {
                    textColor = getColor(R.color.colorTextDark)
                    textSize = 12f
                    alpha = 0.86f
                    setLineSpacing(3 * density, 1f)
                    termsDiagnosticsValueView = this
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            )
        }
    }

    private fun renderTermsGateDiagnostics() {
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
                diagnostics.frameworkName.ifBlank { "Xposed" },
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
        valueView.text = listOf(
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
        ).joinToString("\n")
    }

    private fun createTermsManagerLauncher(): NativeTextView {
        val density = resources.displayMetrics.density
        return NativeTextView(this).apply {
            visibility = View.GONE
            text = getString(R.string.user_terms_open_framework_manager)
            textColor = monetColors.primary
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(monetColors.surfaceVariant)
            }
            setPadding(
                (14 * density).toInt(),
                (9 * density).toInt(),
                (14 * density).toInt(),
                (9 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val intent = FrameworkManagerLauncher.resolve(
                    applicationContext,
                    RemoteHookConfigStore.status()
                ) ?: run {
                    isVisible = false
                    return@setOnClickListener
                }
                runCatching { startActivity(intent) }
                    .onFailure { isVisible = false }
            }
        }
    }

    private fun updateTermsManagerLauncher(status: ModernFrameworkStatus) {
        val launcher = termsManagerLauncher ?: return
        launcher.isVisible = FrameworkManagerLauncher.resolve(
            applicationContext,
            status
        ) != null && (!status.connected || !status.capable)
    }

    /** 已明确拒绝时保持锁定，不显示任何模块配置入口。 */
    private fun showUserTermsDeclinedPage() {
        val density = resources.displayMetrics.density
        val root = createTermsNeutralRoot()
        val container = createModalContainer().apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.user_terms_declined_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.user_terms_declined_message)
                textColor = getColor(R.color.colorTextDark)
                textSize = 14f
                alpha = 0.78f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_exit),
                filled = false
            ) { finish() },
            NativeLinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        buttonRow.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_read_again),
                filled = true
            ) { showUserTermsDialog() },
            NativeLinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = (8 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * density).toInt() }
        )

        root.addView(
            container,
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(
                    (24 * density).toInt(),
                    (36 * density).toInt(),
                    (24 * density).toInt(),
                    (36 * density).toInt()
                )
            }
        )
        setContentView(root)
    }

    private fun createTermsNeutralRoot(): NativeFrameLayout = NativeFrameLayout(this).apply {
        setBackgroundColor(monetColors.background)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** 条款正文可滚动，操作按钮固定在模态容器底部；触外、系统取消均不关闭。 */
    private fun showUserTermsDialog() {
        activeConfirmDialog?.dismiss()
        termsDecisionActionInProgress = false
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.user_terms_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val bodyScroll = NativeScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            val bodyContent = NativeLinearLayout(this@MainActivity).apply {
                orientation = NativeLinearLayout.VERTICAL
                addView(
                    NativeTextView(this@MainActivity).apply {
                        autoLinkMask = Linkify.WEB_URLS
                        text = getString(R.string.user_terms_body)
                        textColor = getColor(R.color.colorTextDark)
                        setLinkTextColor(monetColors.primary)
                        textSize = 14f
                        setLineSpacing(5 * density, 1f)
                        linksClickable = true
                        movementMethod = LinkMovementMethod.getInstance()
                    },
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    createTermsDiagnosticsCard(),
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (16 * density).toInt() }
                )
            }
            addView(
                bodyContent,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        container.addView(
            bodyScroll,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        )

        // 保存失败的原因提示与可解析的框架管理器入口：默认隐藏，仅在失败时展示。
        val hintView = NativeTextView(this).apply {
            visibility = View.GONE
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
            setLineSpacing(4 * density, 1f)
        }
        termsDialogHintView = hintView
        container.addView(
            hintView,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )
        val managerLauncher = createTermsManagerLauncher()
        termsManagerLauncher = managerLauncher
        container.addView(
            managerLauncher,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_decline),
                filled = false
            ) {
                commitUserTermsDecision(
                    dialog = dialog,
                    container = container,
                    accepted = false
                )
            },
            NativeLinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        buttonRow.addView(
            createTermsActionButton(
                text = getString(R.string.user_terms_accept),
                filled = true
            ) {
                commitUserTermsDecision(
                    dialog = dialog,
                    container = container,
                    accepted = true
                )
            },
            NativeLinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = (8 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val root = NativeFrameLayout(this).apply {
            addView(
                container,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins(
                        (20 * density).toInt(),
                        (28 * density).toInt(),
                        (20 * density).toInt(),
                        (28 * density).toInt()
                    )
                }
            )
        }

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { finish() }
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
            termsDialogHintView = null
            termsManagerLauncher = null
            termsDiagnosticsValueView = null
        }
        activeConfirmDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(Android_R.color.transparent)
            setDimAmount(0f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        renderTermsGateDiagnostics()
        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                .start()
        }
    }

    private fun createTermsActionButton(
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
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun commitUserTermsDecision(
        dialog: Dialog,
        container: View,
        accepted: Boolean
    ) {
        if (termsDecisionActionInProgress) return
        termsDecisionActionInProgress = true
        val result = if (accepted) {
            UserTermsAuthorizationCoordinator.beginAcceptance(applicationContext)
        } else {
            UserTermsAuthorizationCoordinator.decline(applicationContext)
        }
        if (!result.succeeded) {
            termsDecisionActionInProgress = false
            showUserTermsSaveFailureHint(result.failureCode)
            return
        }

        termsConsentState = result.state
        userTermsDecision = result.state.decision
        dismissWithAnimation(dialog, container) {
            if (accepted) {
                recreate()
            } else {
                finish()
            }
        }
    }

    /**
     * 条款决定失败时按本地写入、框架连接和 API 能力分类，管理器入口只有在当前设备
     * 存在可由普通应用启动的显式 Activity 时才显示。
     */
    private fun showUserTermsSaveFailureHint(failureCode: String?) {
        val status = RemoteHookConfigStore.status()
        val message = userTermsFailureMessage(failureCode)
        val hintView = termsDialogHintView
        if (hintView == null) {
            // 弹窗已不在（理论上不可能：失败分支在 dismiss 前执行），退回 toast 兜底
            toast(message)
            return
        }
        hintView.text = message
        hintView.isVisible = true
        updateTermsManagerLauncher(status)
    }

    private fun userTermsFailureMessage(failureCode: String?): String {
        val status = RemoteHookConfigStore.status()
        return when {
            failureCode == UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE ->
                getString(R.string.user_terms_save_failed)
            !status.connected -> getString(R.string.user_terms_need_framework_enable)
            !status.capable && status.name.isNotBlank() -> getString(
                R.string.user_terms_need_api102_named,
                status.name,
                status.apiVersion
            )
            !status.capable -> getString(R.string.user_terms_need_api102)
            else -> getString(R.string.user_terms_publish_failed)
        }
    }

    /** AppCompat 的显式应用语言为空时表示跟随系统，不额外维护一份语言偏好。 */
    private fun currentAppLanguage(): AppLanguage {
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

    /** 仅在弹窗完成退场后调用；AppCompat 会按需重建 Activity。 */
    private fun applyAppLanguage(language: AppLanguage) {
        val locales = language.languageTag?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        InjectedUiLocale.setMirrorAndBroadcast(
            applicationContext,
            language.languageTag ?: InjectedUiLocale.TAG_SYSTEM
        )
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == locales.toLanguageTags()) return
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 应用语言单选弹窗：沿用现有模态容器、选中强调色和统一进退场动画。 */
    private fun showAppLanguageDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val current = currentAppLanguage()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.app_language_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.app_language_tip)
                textColor = getColor(R.color.colorTextDark)
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(3 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (5 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        )

        AppLanguage.entries.forEachIndexed { index, language ->
            container.addView(
                createAppLanguageRow(
                    title = getString(language.labelRes),
                    selected = language == current
                ) {
                    dismissWithAnimation(dialog, container) {
                        applyAppLanguage(language)
                    }
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = (6 * density).toInt()
                }
            )
        }

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun createAppLanguageRow(
        title: CharSequence,
        selected: Boolean,
        onClick: () -> Unit
    ): NativeTextView {
        val density = resources.displayMetrics.density
        return NativeTextView(this).apply {
            text = title
            textColor = if (selected) monetColors.primary else getColor(R.color.colorTextGray)
            textSize = 16f
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
            setPadding(
                (16 * density).toInt(),
                (13 * density).toInt(),
                (16 * density).toInt(),
                (13 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isSelected = selected
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
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

    private fun isLiquidRealtimeCaptureSupported(): Boolean = AndroidVersion.code >= 31

    private fun liquidRealtimeCaptureSummary(enabled: Boolean): String = getString(
        when {
            !isLiquidRealtimeCaptureSupported() ->
                R.string.liquid_realtime_capture_summary_unsupported
            enabled -> R.string.liquid_realtime_capture_summary_enabled
            else -> R.string.liquid_realtime_capture_summary_disabled
        }
    )

    /** 实验性功能中的自定义背景配置；选择器只授权单个 image URI，不申请媒体库权限。 */
    private fun showLiquidBackgroundDialog() {
        if (liquidBackgroundImportInProgress) return
        val density = resources.displayMetrics.density
        val state = LiquidBackgroundStore.read(applicationContext)
        val dialog = Dialog(this)
        val container = createModalContainer()
        liquidBackgroundDialog = dialog
        liquidBackgroundDialogContainer = container
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_background_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_background_dialog_description)
                textColor = getColor(R.color.colorTextGray)
                textSize = 13f
                alpha = 0.78f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )

        if (state.config.mode == LiquidBackgroundMode.CUSTOM && state.assetPresent) {
            val preview = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.liquid_background_preview_description)
                background = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(monetColors.surfaceVariant)
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            container.addView(
                preview,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (150 * density).toInt()
                ).apply { topMargin = (14 * density).toInt() }
            )
            loadLiquidBackgroundPreview(dialog, preview, state.config)
        }

        val chooseTitle = getString(
            if (state.config.mode == LiquidBackgroundMode.CUSTOM) {
                R.string.liquid_background_replace
            } else R.string.liquid_background_choose
        )
        container.addView(
            createGitHubMenuRow(
                title = chooseTitle,
                subtitle = getString(R.string.liquid_background_choose_description),
                highlight = false
            ) {
                if (!liquidBackgroundImportInProgress) {
                    liquidBackgroundPicker.launch(arrayOf("image/*"))
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * density).toInt() }
        )

        if (state.config.mode == LiquidBackgroundMode.CUSTOM) {
            container.addView(
                createGitHubMenuRow(
                    title = getString(R.string.liquid_background_restore_automatic),
                    subtitle = getString(R.string.liquid_background_restore_description),
                    highlight = false
                ) { restoreAutomaticLiquidBackground() },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            )
        }

        val realtimeCaptureEnabled = LiquidRealtimeCaptureStore.isEnabled(applicationContext)
        container.addView(
            createGitHubMenuRow(
                title = getString(R.string.liquid_realtime_capture_title),
                subtitle = liquidRealtimeCaptureSummary(realtimeCaptureEnabled),
                highlight = realtimeCaptureEnabled
            ) {
                when {
                    !isLiquidRealtimeCaptureSupported() ->
                        toast(getString(R.string.liquid_realtime_capture_unsupported))
                    realtimeCaptureEnabled -> applyLiquidRealtimeCaptureEnabled(false)
                    else -> beginLiquidRealtimeCaptureConfirmation()
                }
            }.apply {
                contentDescription = buildString {
                    append(getString(R.string.liquid_realtime_capture_title))
                    append('，')
                    append(liquidRealtimeCaptureSummary(realtimeCaptureEnabled))
                }
                if (!isLiquidRealtimeCaptureSupported()) alpha = 0.55f
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_background_backup_notice)
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
                alpha = 0.64f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val closeRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(NativeTextView(this@MainActivity).apply {
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
                setOnClickListener {
                    if (!liquidBackgroundImportInProgress) {
                        dismissWithAnimation(dialog, container) {}
                    }
                }
            })
        }
        container.addView(
            closeRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        )
        presentModalDialog(dialog, container)
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
            if (liquidBackgroundDialog === dialog) {
                liquidBackgroundDialog = null
                liquidBackgroundDialogContainer = null
            }
        }
    }

    private fun beginLiquidRealtimeCaptureConfirmation() {
        val backgroundDialog = liquidBackgroundDialog
        val backgroundContainer = liquidBackgroundDialogContainer
        if (backgroundDialog != null && backgroundContainer != null && backgroundDialog.isShowing) {
            dismissWithAnimation(backgroundDialog, backgroundContainer) {
                if (!isFinishing && !isDestroyed) showLiquidRealtimeCaptureConfirmDialog()
            }
        } else showLiquidRealtimeCaptureConfirmDialog()
    }

    /** 高负载模式首次开启必须由用户显式确认；关闭保持一键可逆。 */
    private fun showLiquidRealtimeCaptureConfirmDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_realtime_capture_confirm_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_realtime_capture_confirm_message)
                textColor = getColor(R.color.colorTextGray)
                textSize = 13f
                alpha = 0.82f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
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
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        if (!isFinishing && !isDestroyed) showLiquidBackgroundDialog()
                    }
                }
            }
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.liquid_realtime_capture_confirm_enable)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (22 * density).toInt(),
                    (11 * density).toInt(),
                    (22 * density).toInt(),
                    (11 * density).toInt()
                )
                val radius = 20 * density
                val content = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(monetColors.primary)
                }
                val rippleMask = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                    ),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        applyLiquidRealtimeCaptureEnabled(true)
                    }
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun applyLiquidRealtimeCaptureEnabled(enabled: Boolean) {
        if (!LiquidRealtimeCaptureStore.setEnabled(applicationContext, enabled)) {
            toast(getString(R.string.liquid_realtime_capture_save_failed))
            if (liquidBackgroundDialog == null && !isFinishing && !isDestroyed) {
                showLiquidBackgroundDialog()
            }
            return
        }
        toast(
            getString(
                if (enabled) R.string.liquid_realtime_capture_enabled
                else R.string.liquid_realtime_capture_disabled
            )
        )
        finishLiquidBackgroundChange()
    }

    private fun loadLiquidBackgroundPreview(
        dialog: Dialog,
        preview: android.widget.ImageView,
        config: LiquidBackgroundConfig
    ) {
        val backgroundColor = monetColors.background
        val dark = ColorUtils.calculateLuminance(monetColors.surface) < 0.5
        liquidBackgroundWorker.execute {
            val bitmap = LiquidBackgroundStore.decodeBackdrop(
                context = applicationContext,
                config = config,
                targetWidth = 640,
                targetHeight = 360,
                backgroundColor = backgroundColor,
                dark = dark
            ) ?: return@execute
            runOnUiThread {
                if (!isFinishing && !isDestroyed && dialog.isShowing &&
                    liquidBackgroundDialog === dialog
                ) {
                    preview.setImageBitmap(bitmap)
                } else bitmap.recycle()
            }
        }
    }

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

    private fun restoreAutomaticLiquidBackground() {
        if (liquidBackgroundImportInProgress) return
        liquidBackgroundImportInProgress = true
        liquidBackgroundDialog?.setCancelable(false)
        liquidBackgroundTask = liquidBackgroundWorker.submit {
            val restored = LiquidBackgroundStore.restoreAutomatic(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                liquidBackgroundImportInProgress = false
                liquidBackgroundDialog?.setCancelable(true)
                if (restored) {
                    toast(getString(R.string.liquid_background_restore_success))
                    finishLiquidBackgroundChange()
                } else toast(getString(R.string.liquid_background_storage_failed))
            }
        }
    }

    private fun finishLiquidBackgroundChange() {
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

    @StringRes
    private fun skinTitleRes(skin: SkinId): Int = when (skin) {
        SkinId.MATERIAL_YOU -> R.string.skin_material_title
        SkinId.LIQUID -> R.string.skin_liquid_title
    }

    @StringRes
    private fun skinDescriptionRes(skin: SkinId): Int = when (skin) {
        SkinId.MATERIAL_YOU -> R.string.skin_material_desc
        SkinId.LIQUID -> R.string.skin_liquid_desc
    }

    /** 界面皮肤单选弹窗；只在同步持久化成功后退场并重建 Activity。 */
    private fun showSkinSelectionDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val current = SkinRepository.resolveRequestedSkin(applicationContext)
        skinSelectionActionInProgress = false

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.skin_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        SkinId.entries.forEachIndexed { index, skin ->
            val title = getString(skinTitleRes(skin))
            val description = getString(skinDescriptionRes(skin))
            val selected = current == skin
            val row = createGitHubMenuRow(
                title = title,
                subtitle = description,
                highlight = selected
            ) {
                selectSkinFromDialog(dialog, container, skin)
            }.apply {
                isSelected = selected
                contentDescription = getString(
                    if (selected) R.string.skin_option_selected
                    else R.string.skin_option_not_selected,
                    title,
                    description
                )
            }
            container.addView(
                row,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = (6 * density).toInt()
                }
            )
        }

        val closeRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        closeRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener {
                    if (!skinSelectionActionInProgress) {
                        dismissWithAnimation(dialog, container) {}
                    }
                }
            }
        )
        container.addView(
            closeRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun selectSkinFromDialog(
        dialog: Dialog,
        container: NativeLinearLayout,
        target: SkinId
    ) {
        if (skinSelectionActionInProgress) return
        skinSelectionActionInProgress = true
        if (SkinRepository.resolveRequestedSkin(applicationContext) == target) {
            dialog.setCancelable(false)
            dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
            dismissWithAnimation(dialog, container) {
                skinSelectionActionInProgress = false
            }
            return
        }
        val result = runCatching {
            SkinRepository.beginSelection(applicationContext, target)
        }.onFailure { throwable ->
            Log.e("BilibiliInnocentLab", "persist skin selection failed", throwable)
        }.getOrNull()
        if (result?.persisted != true) {
            skinSelectionActionInProgress = false
            toast(getString(R.string.skin_save_failed))
            return
        }
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dismissWithAnimation(dialog, container) {
            if (!isFinishing && !isDestroyed) recreate()
        }
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
    private fun showGitHubMenuDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.github_menu_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        container.addView(
            createGitHubMenuRow(
                titleRes = R.string.github_repository,
                subtitleRes = R.string.github_repository_tip
            ) {
                dismissWithAnimation(dialog, container) {
                    openExternalUrl(GitHubReleaseChecker.REPOSITORY_URL)
                }
            }
        )
        container.addView(
            createGitHubMenuRow(
                title = getString(R.string.update_channel),
                subtitle = getString(channelSubtitleRes()),
                highlight = false
            ) {
                dismissWithAnimation(dialog, container) {
                    showUpdateChannelDialog()
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )
        container.addView(
            createGitHubMenuRow(
                titleRes = R.string.check_updates,
                subtitleRes = R.string.check_updates_tip
            ) {
                dismissWithAnimation(dialog, container) {
                    checkForUpdates(manual = true)
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )

        // 与“重新启动哔哩哔哩”确认弹窗一致的右下角文本按钮。
        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun createGitHubMenuRow(
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        onClick: () -> Unit
    ): NativeLinearLayout = createGitHubMenuRow(
        title = getString(titleRes),
        subtitle = getString(subtitleRes),
        highlight = false,
        onClick = onClick
    )

    private fun createGitHubMenuRow(
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

    /** 「更新渠道」选择弹窗：稳定版 / 预览版（含 Alpha），风格与 GitHub 二级界面统一。 */
    private fun showUpdateChannelDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        val current = readUpdateChannel(updatePrefs)

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.update_channel)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        container.addView(
            createGitHubMenuRow(
                title = getString(R.string.update_channel_stable_title),
                subtitle = getString(R.string.update_channel_stable_desc),
                highlight = current == GitHubReleaseChecker.UpdateChannel.STABLE
            ) {
                dismissWithAnimation(dialog, container) {
                    applyUpdateChannel(GitHubReleaseChecker.UpdateChannel.STABLE)
                }
            }
        )
        container.addView(
            createGitHubMenuRow(
                title = getString(R.string.update_channel_preview_title),
                subtitle = getString(R.string.update_channel_preview_desc) + "\n" +
                    getString(R.string.update_channel_preview_warning),
                highlight = current == GitHubReleaseChecker.UpdateChannel.PREVIEW
            ) {
                dismissWithAnimation(dialog, container) {
                    applyUpdateChannel(GitHubReleaseChecker.UpdateChannel.PREVIEW)
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )

        // 与 GitHub 二级界面一致的关闭按钮。
        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    /** 播放器默认画质选择：只写模块配置，实际 Hook 在 B 站下次主进程启动时安装。 */
    private fun showPlayerQualityDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.player_default_quality_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        val optionsContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
        }
        PlayerQualityConfig.supportedQns.forEachIndexed { index, qn ->
            optionsContainer.addView(
                createGitHubMenuRow(
                    title = playerQualityLabel(qn),
                    subtitle = getString(
                        if (qn == 0) R.string.player_default_quality_follow_host_tip
                        else R.string.player_default_quality_override_tip
                    ),
                    highlight = qn == playerDefaultQualityQn
                ) {
                    playerDefaultQualityQn = qn
                    runCatching {
                        prefs().edit {
                            putInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, qn)
                        }
                    }.onFailure { throwable ->
                        Log.e(
                            "BilibiliInnocentLab",
                            "write player default quality prefs failed",
                            throwable
                        )
                    }
                    updatePlayerQualitySummary()
                    dismissWithAnimation(dialog, container) {}
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = (4 * density).toInt()
                }
            )
        }
        container.addView(
            android.widget.ScrollView(this).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    optionsContainer,
                    NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (420 * density).toInt()
            )
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun playerQualityLabel(qn: Int): String = when (qn) {
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

    private fun updatePlayerQualitySummary() {
        playerQualitySummaryView?.text = getString(
            R.string.player_default_quality_current,
            playerQualityLabel(playerDefaultQualityQn)
        )
    }

    /** 评论最低等级选择：沿用播放器画质选择器的模态菜单与进退场动画。 */
    private fun showCommentMinLevelDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.comment_min_level_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        )

        val options = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
        }
        (1..6).forEachIndexed { index, level ->
            options.addView(
                createGitHubMenuRow(
                    title = getString(R.string.comment_level_value, level),
                    subtitle = getString(R.string.comment_min_level_option_tip, level),
                    highlight = level == commentMinLevel
                ) {
                    commentMinLevel = level
                    runCatching {
                        prefs().edit {
                            putInt(FeaturePreferences.COMMENT_MIN_LEVEL, level)
                        }
                    }.onFailure { throwable ->
                        Log.e(
                            "BilibiliInnocentLab",
                            "write comment minimum level prefs failed",
                            throwable
                        )
                    }
                    commentLevelSummaryView?.text = getString(
                        R.string.comment_min_level_current,
                        level
                    )
                    dismissWithAnimation(dialog, container) {}
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = (4 * density).toInt() }
            )
        }
        container.addView(
            android.widget.ScrollView(this).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    options,
                    NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (380 * density).toInt()
            )
        )

        val closeRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        closeRow.addView(
            NativeTextView(this).apply {
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        container.addView(
            closeRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )
        presentModalDialog(dialog, container)
    }

    /** 保存渠道选择并立即按新渠道检查一次；检查失败保留渠道，下次可继续。 */
    private fun applyUpdateChannel(channel: GitHubReleaseChecker.UpdateChannel) {
        val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        if (readUpdateChannel(updatePrefs) == channel) return
        UpdateChannelStore.write(applicationContext, channel)
        checkForUpdates(manual = true)
    }

    /** 读取当前更新渠道（未知/损坏值回退稳定版，兼容旧版本升级）。 */
    private fun readUpdateChannel(
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

    /** GitHub 二级菜单中「更新渠道」行下方动态显示的当前选择。 */
    private fun channelSubtitleRes(): Int {
        val prefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        return when (readUpdateChannel(prefs)) {
            GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_channel_current_stable
            GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_channel_current_preview
        }
    }

    /**
     * 按当前渠道检查更新。自动检查按渠道各自节流（24 小时窗口，仅成功后计时）；
     * 手动检查始终执行并反馈结果。切换渠道后会自动发起一次新渠道检查。
     */
    private fun checkForUpdates(manual: Boolean) {
        val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        val channel = readUpdateChannel(updatePrefs)
        if (!manual) {
            val now = System.currentTimeMillis()
            val lastCheck = updatePrefs.getLong(lastCheckKey(updatePrefs, channel), 0L)
            val elapsed = now - lastCheck
            if (elapsed in 0 until AUTOMATIC_UPDATE_CHECK_INTERVAL_MS) return
        }
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
        if (request.manual) {
            toast(getString(checkingToastRes(channel)))
        }
        val activityRef = WeakReference(this)
        Thread({
            val result = runCatching { GitHubReleaseChecker.fetchLatestRelease(channel) }
            Handler(Looper.getMainLooper()).post {
                val activity = activityRef.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                val selectedChannel = activity.readUpdateChannel(updatePrefs)
                val completion = activity.updateCheckCoordinator.complete(channel, selectedChannel)
                result.onSuccess {
                    updatePrefs.edit()
                        .putLong(activity.lastCheckKey(updatePrefs, channel), System.currentTimeMillis())
                        .apply()
                }
                if (completion.shouldDeliverResult) {
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
                    activity.startUpdateCheck(next, updatePrefs)
                }
            }
        }, "github-release-check").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 按渠道处理检查结果的三态比较：
     * - 远端更高 → 弹更新窗（Alpha 带预发布标识）；
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
            GitHubReleaseChecker.VersionRelation.REMOTE_NEWER ->
                showUpdateDialogWhenIdle(channel, release)
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

    /** Avoids replacing a confirmation dialog the user is already interacting with. */
    private fun showUpdateDialogWhenIdle(
        channel: GitHubReleaseChecker.UpdateChannel,
        release: GitHubReleaseChecker.ReleaseInfo,
        retryCount: Int = 0
    ) {
        if (isFinishing || isDestroyed) return
        val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
        if (readUpdateChannel(updatePrefs) != channel) return
        if (activeConfirmDialog?.isShowing == true) {
            if (retryCount < 20) {
                findViewById<View>(Android_R.id.content).postDelayed(
                    { showUpdateDialogWhenIdle(channel, release, retryCount + 1) },
                    500L
                )
            }
            return
        }
        showUpdateAvailableDialog(release)
    }

    private fun showUpdateAvailableDialog(release: GitHubReleaseChecker.ReleaseInfo) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                // Alpha 预发布使用独立标题，明确标识"预览版本"。
                text = if (release.prerelease) {
                    getString(R.string.update_available_prerelease_title, release.displayName)
                } else {
                    getString(R.string.update_available_title, release.displayName)
                }
                textColor = getColor(R.color.colorTextDark)
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (release.prerelease) {
            container.addView(
                NativeTextView(this).apply {
                    text = getString(R.string.update_available_prerelease_note)
                    textColor = 0xFFFF5722.toInt()
                    textSize = 12f
                    setLineSpacing(3 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            )
        }

        container.addView(
            NativeTextView(this).apply {
                text = getString(
                    R.string.update_available_message,
                    BuildConfig.VERSION_NAME,
                    release.tagName
                )
                textColor = getColor(R.color.colorTextGray)
                textSize = 14f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        if (release.releaseNotes.isNotEmpty()) {
            container.addView(
                NativeTextView(this).apply {
                    text = release.releaseNotes
                    textColor = getColor(R.color.colorTextDark)
                    textSize = 12f
                    alpha = 0.78f
                    maxLines = 7
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(3 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            )
        }

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.update_later)
                textColor = getColor(R.color.colorTextGray)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(
                    (12 * density).toInt(),
                    (11 * density).toInt(),
                    (12 * density).toInt(),
                    (11 * density).toInt()
                )
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.update_details)
                textColor = monetColors.primary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (10 * density).toInt(),
                    (11 * density).toInt(),
                    (10 * density).toInt(),
                    (11 * density).toInt()
                )
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        openReleaseDetailsWithFallback(release.htmlUrl)
                    }
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (4 * density).toInt() }
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.update_now)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (14 * density).toInt(),
                    (11 * density).toInt(),
                    (14 * density).toInt(),
                    (11 * density).toInt()
                )
                val radius = 20 * density
                val content = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(monetColors.primary)
                }
                val rippleMask = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                    ),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        openExternalUrl(release.apkDownloadUrl ?: release.htmlUrl)
                    }
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    private fun openReleaseDetailsWithFallback(officialUrl: String) {
        toast(getString(R.string.update_details_opening))
        val activityRef = WeakReference(this)
        Thread({
            val result = runCatching {
                GitHubReleaseChecker.resolveReleaseDetailsDestination(officialUrl)
            }
            Handler(Looper.getMainLooper()).post {
                val activity = activityRef.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.fold(
                    onSuccess = { destination ->
                        if (destination.usesMirror) {
                            activity.toast(activity.getString(R.string.update_details_using_mirror))
                        }
                        activity.openExternalUrl(destination.url)
                    },
                    onFailure = { error ->
                        Log.w("BilibiliInnocentLab", "release details resolution failed", error)
                        activity.toast(activity.getString(R.string.open_link_failed))
                    }
                )
            }
        }, "github-release-details-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun createModalContainer(): NativeLinearLayout {
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
            background = skinModalBackground(monetColors.surface)
            elevation = 12 * density
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0f
        }
    }

    private fun presentModalDialog(
        dialog: Dialog,
        container: NativeLinearLayout,
        onBackDismiss: () -> Unit = {}
    ) {
        activeConfirmDialog?.dismiss()
        val density = resources.displayMetrics.density
        val root = NativeFrameLayout(this).apply {
            addView(
                container,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
                }
            )
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
        }
        dialog.setContentView(root)
        // 系统返回键也必须走项目统一的 180ms scale + fade 退场动画。
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    dismissWithAnimation(dialog, container, onBackDismiss)
                }
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
        }
        activeConfirmDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                .start()
        }
    }

    /** 新协议快照同时校验来源版本；旧快照仍按原有协议兼容读取。 */
    private fun readMineComponentSnapshot(): MineComponentSnapshot? =
        MineComponentSnapshotStore.read(this)

    private fun queryMineComponentSnapshotAndOpenPicker() {
        if (mineComponentSnapshotQueryInFlight) return
        mineComponentSnapshotQueryInFlight = true
        toast(getString(R.string.custom_mine_component_snapshot_querying))
        MineComponentSnapshotQueryClient.query(this) { result ->
            mineComponentSnapshotQueryInFlight = false
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@query
            when (result.status) {
                MineComponentSnapshotQueryClient.Status.READY -> {
                    val snapshot = result.snapshot
                    if (snapshot != null && snapshot.entries.isNotEmpty()) {
                        showMineComponentPickerDialog(snapshot)
                    } else {
                        showMineSnapshotFallback(R.string.custom_mine_component_snapshot_invalid)
                    }
                }

                MineComponentSnapshotQueryClient.Status.WAITING_PAGE ->
                    showMineSnapshotFallback(R.string.custom_mine_component_snapshot_waiting_page)

                MineComponentSnapshotQueryClient.Status.TARGET_UNAVAILABLE ->
                    showMineSnapshotFallback(R.string.custom_mine_component_snapshot_unavailable)

                MineComponentSnapshotQueryClient.Status.INVALID_RESPONSE ->
                    showMineSnapshotFallback(R.string.custom_mine_component_snapshot_invalid)

                MineComponentSnapshotQueryClient.Status.STORE_FAILED ->
                    showMineSnapshotFallback(
                        R.string.custom_mine_component_snapshot_store_failed,
                        result.snapshot
                    )
            }
        }
    }

    private fun showMineSnapshotFallback(
        @StringRes messageRes: Int,
        transientSnapshot: MineComponentSnapshot? = null
    ) {
        toast(getString(messageRes))
        val snapshot = transientSnapshot ?: readMineComponentSnapshot()
        if (snapshot != null && snapshot.entries.isNotEmpty()) {
            showMineComponentPickerDialog(snapshot)
        } else {
            showMineManualRuleEditor()
        }
    }

    private fun showMineManualRuleEditor() {
        showRuleEditorDialog(
            R.string.custom_mine_component_hide_dialog_title,
            R.string.custom_mine_component_hide_hint,
            mineComponentHiddenRules
        ) { value ->
            mineComponentHiddenRules = value
            prefs().edit {
                putString(FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES, value)
            }
            mineComponentRulesSummaryView?.text =
                getString(R.string.custom_mine_component_hide) + "\n" + mineComponentSummary()
        }
    }

    /**
     * 动态勾选只写稳定 selector；旧 id 和用户手写标题规则仅作为兼容输入，不会被覆盖。
     */
    private fun showMineComponentPickerDialog(snapshot: MineComponentSnapshot) {
        val entries = snapshot.entries
        if (entries.isEmpty()) return

        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.custom_mine_component_hide_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val snapshotAge = (System.currentTimeMillis() - snapshot.generatedAt)
            .coerceAtLeast(0L)
        container.addView(
            NativeTextView(this).apply {
                text = when {
                    snapshot.generatedAt <= 0L ->
                        getString(R.string.custom_mine_component_snapshot_legacy)
                    snapshotAge > MINE_COMPONENT_SNAPSHOT_STALE_MS ->
                        getString(R.string.custom_mine_component_snapshot_stale)
                    else -> getString(
                        R.string.custom_mine_component_snapshot_ready,
                        entries.count(MineComponentScanEntry::selectable)
                    )
                }
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
                setPadding(0, (8 * density).toInt(), 0, 0)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val initialSelectors = MineComponentSelectionCodec.decode(
            prefs().getString(
                FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
                ""
            ).orEmpty()
        )
        val initialHiddenIds = prefs().getString(
            FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS, ""
        ).orEmpty().split(Regex("[,，;；\\r\\n]+")).filter { it.isNotBlank() }.toSet()
        val initialHiddenRules = RuleSetCodec.parse(
            prefs().getString(FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES, "").orEmpty()
        )
        fun legacyHidden(e: MineComponentScanEntry): Boolean =
            (e.id != null && e.id in initialHiddenIds) ||
                (e.title != null && RuleSetCodec.matches(initialHiddenRules, e.title))

        // 勾选行（标题 + 副标 uri/id）
        val listBody = NativeScrollView(this).apply {
            isFillViewport = true
        }
        val rowContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding(
                (4 * density).toInt(), (2 * density).toInt(),
                (4 * density).toInt(), (2 * density).toInt()
            )
        }
        val checkboxes = ArrayList<android.widget.CheckBox>()
        entries.forEach { entry ->
            val legacyLocked = legacyHidden(entry)
            val box = android.widget.CheckBox(this).apply {
                text = buildString {
                    append(entry.title ?: entry.id ?: "(未命名)")
                    entry.uri?.let { append("  ·  ").append(it) }
                    if (legacyLocked) append(getString(R.string.custom_mine_component_legacy_locked))
                    else if (!entry.selectable) {
                        append(getString(R.string.custom_mine_component_not_selectable))
                    }
                }
                textSize = 14f
                setTextColor(getColor(R.color.colorTextDark))
                isChecked = legacyLocked || entry.key in initialSelectors
                isEnabled = entry.selectable && !legacyLocked
            }
            checkboxes.add(box)
            rowContainer.addView(
                box,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
            )
        }
        listBody.addView(rowContainer)
        container.addView(
            listBody,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.custom_mine_component_manual_rules)
                textColor = getColor(R.color.colorTextGray)
                textSize = 14f
                setPadding(
                    (16 * density).toInt(), (11 * density).toInt(),
                    (16 * density).toInt(), (11 * density).toInt()
                )
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container, ::showMineManualRuleEditor)
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
                textColor = getColor(R.color.colorTextGray)
                textSize = 14f
                setPadding(
                    (20 * density).toInt(), (11 * density).toInt(),
                    (20 * density).toInt(), (11 * density).toInt()
                )
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        buttonRow.addView(
            createTermsActionButton(
                text = getString(R.string.dialog_confirm),
                filled = true
            ) {
                val editableKeys = entries.mapIndexedNotNull { index, entry ->
                    if (entry.selectable && checkboxes.getOrNull(index)?.isEnabled == true) {
                        entry.key
                    } else {
                        null
                    }
                }.toSet()
                val checkedSelectors = entries.mapIndexedNotNull { index, entry ->
                    if (entry.selectable &&
                        checkboxes.getOrNull(index)?.isEnabled == true &&
                        checkboxes[index].isChecked
                    ) entry.key else null
                }.toSet()
                val hiddenSelectors = (initialSelectors - editableKeys) + checkedSelectors
                prefs().edit {
                    putString(
                        FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
                        MineComponentSelectionCodec.encode(hiddenSelectors)
                    )
                }
                mineComponentRulesSummaryView?.text =
                    getString(R.string.custom_mine_component_hide) + "\n" + mineComponentSummary()
                toast(getString(R.string.custom_mine_component_restart_required))
                dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        )

        presentModalDialog(dialog, container)
    }

    /** 自定义隐藏规则编辑器：沿用项目模态弹窗与统一退场动画。 */
    private fun showRuleEditorDialog(
        @StringRes titleRes: Int,
        @StringRes hintRes: Int,
        initialValue: String,
        onConfirm: (String) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(titleRes)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val editor = NativeEditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
            hint = getString(hintRes)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 3
            maxLines = 6
            setHorizontallyScrolling(false)
            setPadding(
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(monetColors.surfaceVariant)
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
                )
            }
        }
        container.addView(
            editor,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * density).toInt() }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_confirm)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (22 * density).toInt(),
                    (11 * density).toInt(),
                    (22 * density).toInt(),
                    (11 * density).toInt()
                )
                val radius = 20 * density
                val content = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(monetColors.primary)
                }
                val rippleMask = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                    ),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val value = editor.textToString().trim()
                    dismissWithAnimation(dialog, container) { onConfirm(value) }
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )
        presentModalDialog(dialog, container)
    }

    /** 推荐视频时长范围编辑器：空输入表示不限制，非法区间保持弹窗等待修正。 */
    private fun showRecommendVideoDurationRangeDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.recommend_video_duration_dialog_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun addDurationEditor(
            @StringRes labelRes: Int,
            initialValue: Int
        ): NativeEditText {
            val editorId = View.generateViewId()
            container.addView(
                NativeTextView(this).apply {
                    text = getString(labelRes)
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 13f
                    labelFor = editorId
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (14 * density).toInt() }
            )
            return NativeEditText(this).apply {
                id = editorId
                setText(initialValue.takeIf { it > 0 }?.toString().orEmpty())
                setSelection(text.length)
                hint = getString(R.string.recommend_video_duration_input_hint)
                textColor = getColor(R.color.colorTextDark)
                setHintTextColor(
                    ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99)
                )
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(10))
                setPadding(
                    (14 * density).toInt(),
                    (12 * density).toInt(),
                    (14 * density).toInt(),
                    (12 * density).toInt()
                )
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(monetColors.surfaceVariant)
                    setStroke(
                        density.toInt().coerceAtLeast(1),
                        ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
                    )
                }
                container.addView(
                    this,
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * density).toInt() }
                )
            }
        }

        val minEditor = addDurationEditor(
            R.string.recommend_video_min_duration,
            recommendVideoMinDurationSeconds
        ).apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        val maxEditor = addDurationEditor(
            R.string.recommend_video_max_duration,
            recommendVideoMaxDurationSeconds
        ).apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        val errorView = NativeTextView(this).apply {
            visibility = View.GONE
            textColor = if (ColorUtils.calculateLuminance(monetColors.surface) < 0.5) {
                0xFFFFB4AB.toInt()
            } else {
                0xFFBA1A1A.toInt()
            }
            textSize = 12f
            setLineSpacing(4 * density, 1f)
        }
        container.addView(
            errorView,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        )

        fun showError(@StringRes messageRes: Int, target: NativeEditText) {
            errorView.text = getString(messageRes)
            errorView.visibility = View.VISIBLE
            errorView.announceForAccessibility(errorView.text)
            target.requestFocus()
            target.setSelection(target.text.length)
        }

        fun parseDuration(editor: NativeEditText): Int? {
            val raw = editor.textToString().trim()
            if (raw.isEmpty()) return 0
            val parsed = raw.toLongOrNull() ?: return null
            return parsed.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
        }

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
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
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )
        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_confirm)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (22 * density).toInt(),
                    (11 * density).toInt(),
                    (22 * density).toInt(),
                    (11 * density).toInt()
                )
                val radius = 20 * density
                val content = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(monetColors.primary)
                }
                val rippleMask = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                    ),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    errorView.visibility = View.GONE
                    val minSeconds = parseDuration(minEditor)
                    if (minSeconds == null) {
                        showError(R.string.recommend_video_duration_invalid_number, minEditor)
                        return@setOnClickListener
                    }
                    val maxSeconds = parseDuration(maxEditor)
                    if (maxSeconds == null) {
                        showError(R.string.recommend_video_duration_invalid_number, maxEditor)
                        return@setOnClickListener
                    }
                    if (minSeconds > 0 && maxSeconds > 0 && minSeconds > maxSeconds) {
                        showError(R.string.recommend_video_duration_invalid_range, maxEditor)
                        return@setOnClickListener
                    }

                    recommendVideoMinDurationSeconds = minSeconds
                    recommendVideoMaxDurationSeconds = maxSeconds
                    runCatching {
                        prefs().edit {
                            putInt(
                                FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                                minSeconds
                            )
                            putInt(
                                FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
                                maxSeconds
                            )
                        }
                    }.onFailure { throwable ->
                        Log.e(
                            "BilibiliInnocentLab",
                            "write recommended video duration prefs failed",
                            throwable
                        )
                    }
                    updateRecommendVideoDurationSummary()
                    dismissWithAnimation(dialog, container) {}
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * density).toInt() }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        )
        presentModalDialog(dialog, container)
    }

    private fun updateRecommendVideoDurationSummary() {
        recommendVideoDurationSummaryView?.text =
            getString(R.string.recommend_video_duration_range) + "\n" +
                recommendVideoDurationSummary()
    }

    private fun recommendVideoDurationSummary(): String = when {
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

    private fun mineComponentSummary(): String {
        val selectorCount = MineComponentSelectionCodec.decode(
            prefs().getString(
                FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
                ""
            ).orEmpty()
        ).size
        val selectorSummary = if (selectorCount > 0) {
            getString(R.string.custom_mine_component_selected_count, selectorCount)
        } else {
            ""
        }
        val manualSummary = ruleSummary(mineComponentHiddenRules)
        return if (selectorSummary.isEmpty()) manualSummary
        else if (mineComponentHiddenRules.isBlank()) selectorSummary
        else "$selectorSummary\n$manualSummary"
    }

    private fun openExternalUrl(url: String) {
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

    /**
     * 「亮色模式气泡」二级确认（手动优先：自动跟随开启时切换手动开关 → 提示会关闭
     * 自动跟随，确认后关闭跟随并应用手动值，取消保持原样）。样式与 showAdaptConfirmDialog
     * 相同（模态容器 + 取消/确认按钮 + 进出动画）。
     *
     * @param onConfirm 确认回调（自动跟随关闭 + 手动值生效；由调用方负责 UI 动画同步）
     * @param onCancel  取消回调（开关 UI 复位）
     */
    private fun showAutoLightConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.free_copy_light_mode_confirm_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
                textColor = getColor(R.color.colorTextGray)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    // 取消：开关 UI 由 onCancel 复位（不重建界面），保持自动跟随开启
                    dismissWithAnimation(dialog, container) { onCancel() }
                }
            }
        )

        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_confirm)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((22 * density).toInt(), (11 * density).toInt(), (22 * density).toInt(), (11 * density).toInt())
                val radius = 20 * density
                val content = GradientDrawable().apply { cornerRadius = radius; setColor(monetColors.primary) }
                val rippleMask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
                background = RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) { onConfirm() }
                }
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (16 * density).toInt()
            }
        )

        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (22 * density).toInt()
            }
        )

        presentModalDialog(dialog, container, onCancel)
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
     * 「重新适配当前版本」二级确认菜单：样式与规格与「重启哔哩哔哩」确认弹窗
     * （showRestartConfirmDialog）完全一致（模态容器 + 取消/确认按钮 + 进出动画）。
     * 确认后清除版本适配缓存（VersionAdapter.clearCache），重启 B 站后自动重新定位。
     */
    private fun showAdaptConfirmDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.adapt_clear_confirm_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 17f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_cancel)
                textColor = getColor(R.color.colorTextGray)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
                background = selfRippleBackground(14f)
                isClickable = true
                isFocusable = true
                setOnClickListener { dismissWithAnimation(dialog, container) {} }
            }
        )

        buttonRow.addView(
            NativeTextView(this).apply {
                text = getString(R.string.dialog_confirm)
                textColor = monetColors.onPrimary
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((22 * density).toInt(), (11 * density).toInt(), (22 * density).toInt(), (11 * density).toInt())
                val radius = 20 * density
                val content = GradientDrawable().apply { cornerRadius = radius; setColor(monetColors.primary) }
                val rippleMask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
                background = RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)),
                    content,
                    rippleMask
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissWithAnimation(dialog, container) {
                        runCatching { VersionAdapter.clearCache(this@MainActivity, runCatching { prefs() }.getOrNull()) }
                        if (NoRootSupportStore.isDesiredEnabled(applicationContext)) {
                            NoRootSupportStore.markAdapterReset(applicationContext)
                            synchronizeNoRootSupportIfEnabled()
                        }
                        this@MainActivity.toast(getString(R.string.adapt_manual_done))
                    }
                }
            },
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (16 * density).toInt()
            }
        )

        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (22 * density).toInt()
            }
        )

        presentModalDialog(dialog, container)
    }

    /**
     * 一键重启哔哩哔哩：先强制停止 B 站进程，延迟后再重新拉起。
     * 使用 root 的 am 命令（KernelSU 的 su 位于 /system/bin/su，需完整路径）。
     * 注意：不能用 monkey 拉起——monkey 在注入事件前会强制开启系统自动旋转
     * （accelerometer_rotation 0→1），副作用不可接受；改用 am start 指定主 Activity。
     */
    private fun restartBilibili() {
        // 提前取出 applicationContext：Thread 与 Toast lambda 只持有它（全局单例），
        // 不再持有 Activity，避免 Activity 销毁后无法回收的内存泄漏。
        val appContext = applicationContext
        // 提前探测 su 路径（不同 root 方案 su 位置不同：KernelSU 在 /system/bin/su、
        // Magisk 新版在 /product/bin/su，硬编码会失败）；null 表示常见路径均无 su
        val suPath = findSuPath()
        Thread {
            // 先做一次幂等的授权探测（su -c id）：首次使用会触发 Root 管理器的授权
            // 弹窗，用户当场允许即继续；失败时区分「无 su 二进制」与「su 被拒绝」，
            // 给出对应指引而不是笼统的「重启失败」。
            val rootFailureRes = runCatching {
                val probeExit = execShell(suPath ?: "su", "-c", "id")
                check(probeExit == 0) { "su probe exited with $probeExit" }
                null
            }.getOrElse { throwable ->
                Log.e("BilibiliInnocentLab", "su probe failed: $throwable")
                if (suPath == null) {
                    R.string.restart_root_missing
                } else {
                    R.string.restart_root_denied
                }
            }
            if (rootFailureRes != null) {
                Handler(Looper.getMainLooper()).post {
                    appContext.toast(appContext.getString(rootFailureRes))
                }
                return@Thread
            }
            try {
                // 1. 杀死 B 站（root）；execShell 消费输出流，防止 buffer 满导致 waitFor 死锁
                val stopExitCode = execShell(
                    suPath ?: "su",
                    "-c",
                    "am force-stop ${HookEntry.TARGET_PACKAGE}"
                )
                check(stopExitCode == 0) { "force-stop exited with $stopExitCode" }
                // 2. 延迟后重新拉起（am start 指定主 Activity；不用 monkey，避免误开自动旋转）
                Thread.sleep(800)
                val startExitCode = execShell(
                    suPath ?: "su",
                    "-c",
                    "am start -n ${HookEntry.TARGET_PACKAGE}/.MainActivityV2"
                )
                check(startExitCode == 0) { "am start exited with $startExitCode" }
                Handler(Looper.getMainLooper()).post {
                    appContext.toast(appContext.getString(R.string.restart_bilibili_done))
                }
            } catch (e: Exception) {
                Log.e("BilibiliInnocentLab", "restart bilibili failed: $e")
                Handler(Looper.getMainLooper()).post {
                    appContext.toast(appContext.getString(R.string.restart_bilibili_failed))
                }
            }
        }.start()
    }

    /** 重启前确保当前 enabled 快照或关闭 tombstone 已完成一次有界 NPatch 写入。 */
    private fun flushNoRootSupportBeforeOpeningDetails() {
        val bridge = noRootPrefsBridge
        if (bridge == null) {
            toast(getString(R.string.no_root_restart_sync_failed))
            openBilibiliAppDetails(this)
            return
        }
        noRootStatusView?.setText(R.string.no_root_status_syncing)
        val appContext = applicationContext
        val activityRef = WeakReference(this)
        NoRootSupportController.flushBeforeRestart(appContext, bridge) { result ->
            Handler(Looper.getMainLooper()).post {
                activityRef.get()?.finishNoRootRestartFlush(result)
            }
        }
    }

    /**
     * Dialog dismiss 后焦点可能晚一帧回到 Activity；仅在 RESUMED 时短暂复查一次，
     * 真正退到后台或 Activity 已销毁时绝不拉起系统页面。
     */
    private fun finishNoRootRestartFlush(
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

    /**
     * 探测可用的 su 路径。不同 root 方案 su 位置不同：
     * KernelSU 通常在 /system/bin/su，Magisk 新版（Android 10+）在 /product/bin/su，
     * 旧版 Magisk/SuperSU 在 /system/xbin/su 或 /sbin/su。按常见顺序探测第一个存在的。
     * @return null 表示所有已知路径均不存在（设备可能未 Root，或 su 仅在非常规 PATH），
     *         调用方仍可用裸 "su" 尝试并由授权探测区分失败原因。
     */
    private fun findSuPath(): String? {
        val candidates = arrayOf(
            "/product/bin/su",       // 新版 Magisk（Android 10+，如本次 9.0.0 小米设备）
            "/system/bin/su",        // KernelSU / 旧版 Magisk
            "/system/xbin/su",       // 部分 Magisk / SuperSU
            "/data/adb/ksu/bin/su",  // KernelSU 新版
            "/sbin/su",              // 旧版 SuperSU
            "/su/bin/su",            // 部分定制系统
        )
        for (path in candidates) {
            try {
                if (File(path).exists()) return path
            } catch (_: Throwable) {
                // 忽略无权限读取的路径，继续探测下一个
            }
        }
        return null
    }

    /**
     * 执行固定的 root shell 命令。输出流由独立读取线程持续排空，且超时后会
     * 终止子进程，避免 stdout/stderr pipe 或异常 root 实现造成设置页后台线程悬挂。
     */
    private fun execShell(vararg cmd: String): Int =
        ShellCommandRunner.run(cmd.toList(), timeoutMs = 10_000L)

    /** 切换“实验性功能”二级菜单；内容与箭头只做属性动画，不逐帧触发布局。 */
    private fun toggleExperimental() {
        val content = experimentalContent ?: return
        val chevron = experimentalChevron ?: return
        experimentalExpanded = !experimentalExpanded
        animateSecondarySection(content, chevron, experimentalExpanded)
    }

    /** 切换“进阶设置”二级菜单；与实验性功能共用完全相同的轻量动效。 */
    private fun toggleAdvanced() {
        val content = advancedContent ?: return
        val chevron = advancedChevron ?: return
        advancedExpanded = !advancedExpanded
        animateSecondarySection(content, chevron, advancedExpanded)
    }

    /** 把原有四段进阶设置在首帧前重组为默认折叠的协调式卡片。 */
    private fun installAdvancedCategorySections() {
        val root = advancedContent as? ViewGroup ?: return
        if (advancedCategorySections.isNotEmpty()) return
        val categories = AdvancedSettingsCategory.entries
        if (advancedCategoryMarkers.keys.toList() != categories.toList()) return

        val originalChildren = List(root.childCount, root::getChildAt)
        val markers = categories.map { category ->
            advancedCategoryMarkers[category] ?: return
        }
        val markerIndices = markers.map(originalChildren::indexOf)
        val ranges = AdvancedCategoryLayoutPolicy.resolve(
            markerIndices = markerIndices,
            childCount = originalChildren.size
        ) ?: return

        val density = resources.displayMetrics.density
        val horizontalPadding = (12f * density).toInt()
        val headerVerticalPadding = (11f * density).toInt()
        root.removeAllViews()

        categories.forEachIndexed { index, category ->
            val title = markers[index].apply {
                alpha = 0.92f
                textColor = getColor(R.color.colorTextGray)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
        section.expanded = expanded
        section.header.isActivated = expanded
        animateSecondarySection(section.content, section.chevron, expanded)
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

    /** 首次绘制前把“进阶设置”卡片调整到“实验性功能”正下方，避免可见重排。 */
    private fun placeAdvancedBelowExperimental() {
        val advancedCard = advancedContent?.parent as? View ?: return
        val experimentalCard = experimentalContent?.parent as? View ?: return
        val parent = advancedCard.parentOrNull() ?: return
        if (experimentalCard.parent !== parent) return
        parent.removeView(advancedCard)
        parent.addView(advancedCard, parent.indexOfChild(experimentalCard) + 1)
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
        renderActivationUi(framework)
    }

    override fun onResume() {
        super.onResume()
        if (!userTermsDecision.isAuthorized) return
        val selectionTag = InjectedUiLocale.syncFromAppCompat(applicationContext)
        InjectedUiLocale.setMirrorAndBroadcast(applicationContext, selectionTag)
        renderNoRootUi()
        synchronizeNoRootSupportIfEnabled()
    }

    override fun onStop() {
        UserTermsAuthorizationCoordinator.removeListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.removeStatusListener(frameworkStatusListener)
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
        super.onStop()
    }

    override fun onPause() {
        // 用户离开设置页前刷新一次完整快照；开关关闭时直接返回，不连接 NPatch。
        if (userTermsDecision.isAuthorized) synchronizeNoRootSupportIfEnabled()
        super.onPause()
    }

    private enum class SettingsSearchSection {
        GENERAL,
        ADVANCED,
        EXPERIMENTAL
    }

    private data class RuntimeSettingsSearchTarget(
        val item: SettingsSearchItem,
        val view: View,
        val section: SettingsSearchSection
    )

    private fun portraitContentFilterValues(): Map<String, Boolean> = mapOf(
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

    private fun portraitContentFilterSummary(): String {
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

    @StringRes
    private fun portraitContentFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
        FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL ->
            R.string.remove_home_recommend_vertical
        FeaturePreferences.REMOVE_STORY_ADS -> R.string.remove_story_ads
        FeaturePreferences.REMOVE_STORY_LIVE -> R.string.remove_story_live
        FeaturePreferences.REMOVE_STORY_GAMES -> R.string.remove_story_games
        FeaturePreferences.REMOVE_STORY_COURSES -> R.string.remove_story_courses
        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> R.string.remove_story_short_drama
        FeaturePreferences.REMOVE_STORY_SHOPPING -> R.string.remove_story_shopping
        FeaturePreferences.REMOVE_STORY_MUSIC -> R.string.remove_story_music
        FeaturePreferences.REMOVE_STORY_BANGUMI -> R.string.remove_story_bangumi
        FeaturePreferences.REMOVE_STORY_MOVIES -> R.string.remove_story_movies
        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> R.string.remove_story_documentaries
        FeaturePreferences.REMOVE_STORY_TV -> R.string.remove_story_tv
        FeaturePreferences.REMOVE_STORY_VARIETY -> R.string.remove_story_variety
        else -> error("Unknown portrait filter key: $preferenceKey")
    }

    @StringRes
    private fun portraitContentFilterGroupLabel(group: PortraitContentFilterGroup): Int =
        when (group) {
            PortraitContentFilterGroup.HOME -> R.string.portrait_content_filter_group_home
            PortraitContentFilterGroup.STORY -> R.string.portrait_content_filter_group_story
            PortraitContentFilterGroup.SERIES -> R.string.portrait_content_filter_group_series
        }

    private fun applyPortraitContentFilterValue(preferenceKey: String, enabled: Boolean) {
        when (preferenceKey) {
            FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL ->
                removeHomeRecommendVertical = enabled
            FeaturePreferences.REMOVE_STORY_ADS -> removeStoryAds = enabled
            FeaturePreferences.REMOVE_STORY_LIVE -> removeStoryLive = enabled
            FeaturePreferences.REMOVE_STORY_GAMES -> removeStoryGames = enabled
            FeaturePreferences.REMOVE_STORY_COURSES -> removeStoryCourses = enabled
            FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> removeStoryShortDrama = enabled
            FeaturePreferences.REMOVE_STORY_SHOPPING -> removeStoryShopping = enabled
            FeaturePreferences.REMOVE_STORY_MUSIC -> removeStoryMusic = enabled
            FeaturePreferences.REMOVE_STORY_BANGUMI -> removeStoryBangumi = enabled
            FeaturePreferences.REMOVE_STORY_MOVIES -> removeStoryMovies = enabled
            FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> removeStoryDocumentaries = enabled
            FeaturePreferences.REMOVE_STORY_TV -> removeStoryTv = enabled
            FeaturePreferences.REMOVE_STORY_VARIETY -> removeStoryVariety = enabled
            else -> error("Unknown portrait filter key: $preferenceKey")
        }
    }

    /**
     * 13 个既有开关使用草稿式编辑：返回/取消不落盘，保存时只在同一个 Editor 中写变化项。
     * “全部番剧影视”只改变子项的可编辑状态，不改写子项原值。
     */
    private fun showPortraitContentFilterDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val draft = PortraitContentFilterDraft(portraitContentFilterValues())

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.portrait_content_filter_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.portrait_content_filter_dialog_description)
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (7 * density).toInt() }
        )

        val quickActions = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        quickActions.addView(
            createTermsActionButton(
                getString(R.string.portrait_content_filter_select_all),
                filled = false
            ) {},
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        quickActions.addView(
            createTermsActionButton(
                getString(R.string.portrait_content_filter_clear),
                filled = false
            ) {},
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        )
        container.addView(
            quickActions,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val listBody = NativeScrollView(this).apply {
            isFillViewport = true
        }
        val rowContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
        var currentGroup: PortraitContentFilterGroup? = null
        PortraitContentFilterCatalog.options.forEach { option ->
            if (currentGroup != option.group) {
                currentGroup = option.group
                rowContainer.addView(
                    NativeTextView(this).apply {
                        text = getString(portraitContentFilterGroupLabel(option.group))
                        textColor = monetColors.primary
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    },
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = ((if (checkboxes.isEmpty()) 8 else 15) * density).toInt()
                        bottomMargin = (4 * density).toInt()
                    }
                )
            }
            val box = android.widget.CheckBox(this).apply {
                textSize = 14f
                setTextColor(getColor(R.color.colorTextDark))
                isChecked = draft[option.preferenceKey]
                isFocusable = true
            }
            checkboxes[option.preferenceKey] = box
            rowContainer.addView(
                box,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
            )
        }
        listBody.addView(rowContainer)
        val listHeight = minOf(
            (390 * density).toInt(),
            (resources.displayMetrics.heightPixels * 0.48f).toInt()
        )
        container.addView(
            listBody,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight).apply {
                topMargin = (7 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = createTermsActionButton(
            getString(R.string.dialog_cancel),
            filled = false
        ) { dismissWithAnimation(dialog, container) {} }
        lateinit var refreshUi: () -> Unit
        lateinit var saveButton: NativeTextView
        var updating = false

        refreshUi = {
            updating = true
            PortraitContentFilterCatalog.options.forEach { option ->
                checkboxes.getValue(option.preferenceKey).apply {
                    val covered = draft.isCovered(option)
                    isChecked = draft[option.preferenceKey]
                    isEnabled = !covered
                    alpha = if (covered) 0.55f else 1f
                    text = buildString {
                        append(getString(portraitContentFilterLabel(option.preferenceKey)))
                        if (covered) {
                            append(getString(R.string.portrait_content_filter_covered_suffix))
                        }
                    }
                }
            }
            saveButton.text = getString(
                R.string.portrait_content_filter_save,
                draft.selectedCount()
            )
            updating = false
        }
        checkboxes.forEach { (key, box) ->
            box.setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    draft[key] = checked
                    refreshUi()
                }
            }
        }
        quickActions.getChildAt(0).setOnClickListener {
            draft.selectAll()
            refreshUi()
        }
        quickActions.getChildAt(1).setOnClickListener {
            draft.clear()
            refreshUi()
        }

        saveButton = createTermsActionButton("", filled = true) {
            val changed = draft.changedValues()
            if (changed.isEmpty()) {
                dismissWithAnimation(dialog, container) {}
                return@createTermsActionButton
            }
            val saved = runCatching {
                prefs().edit {
                    changed.forEach { (key, value) -> putBoolean(key, value) }
                }
            }.isSuccess
            if (!saved) {
                toast(getString(R.string.portrait_content_filter_save_failed))
                return@createTermsActionButton
            }
            changed.forEach { (key, value) -> applyPortraitContentFilterValue(key, value) }
            portraitContentFilterSummaryView?.text = portraitContentFilterSummary()
            toast(getString(R.string.portrait_content_filter_applied))
            dismissWithAnimation(dialog, container) {}
        }
        buttonRow.addView(
            cancelButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            saveButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        refreshUi()
        presentModalDialog(dialog, container)
    }

    private fun videoRelateFilterValues(): Map<String, Boolean> = mapOf(
        FeaturePreferences.REMOVE_RELATE_COMMERCIAL to removeRelateCommercial,
        FeaturePreferences.REMOVE_RELATE_GAME to removeRelateGame,
        FeaturePreferences.REMOVE_RELATE_LIVE to removeRelateLive,
        FeaturePreferences.REMOVE_RELATE_COURSE to removeRelateCourse,
        FeaturePreferences.REMOVE_RELATE_SPECIAL to removeRelateSpecial,
        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED to
            videoRelateMatchingEnhancementEnabled,
        FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED to
            videoRelateReasonFilterEnabled
    )

    private fun videoRelateFilterSummary(): String {
        val selected = VideoRelateFilterCatalog.contentOptions.count {
            videoRelateFilterValues()[it.preferenceKey] == true
        }
        return when {
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

    @StringRes
    private fun videoRelateFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
        FeaturePreferences.REMOVE_RELATE_COMMERCIAL -> R.string.remove_relate_commercial
        FeaturePreferences.REMOVE_RELATE_GAME -> R.string.remove_relate_game
        FeaturePreferences.REMOVE_RELATE_LIVE -> R.string.remove_relate_live
        FeaturePreferences.REMOVE_RELATE_COURSE -> R.string.remove_relate_course
        FeaturePreferences.REMOVE_RELATE_SPECIAL -> R.string.remove_relate_special
        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED ->
            R.string.video_relate_matching_enhancement
        else -> error("Unknown video relate filter key: $preferenceKey")
    }

    private fun applyVideoRelateFilterValue(preferenceKey: String, enabled: Boolean) {
        when (preferenceKey) {
            FeaturePreferences.REMOVE_RELATE_COMMERCIAL -> removeRelateCommercial = enabled
            FeaturePreferences.REMOVE_RELATE_GAME -> removeRelateGame = enabled
            FeaturePreferences.REMOVE_RELATE_LIVE -> removeRelateLive = enabled
            FeaturePreferences.REMOVE_RELATE_COURSE -> removeRelateCourse = enabled
            FeaturePreferences.REMOVE_RELATE_SPECIAL -> removeRelateSpecial = enabled
            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED ->
                videoRelateMatchingEnhancementEnabled = enabled
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED ->
                videoRelateReasonFilterEnabled = enabled
            else -> error("Unknown video relate filter key: $preferenceKey")
        }
    }

    /** 小范围模态内容使用系统 Transition；不把逐帧布局传播到外层设置滚动树。 */
    private fun setModalSectionVisible(
        parent: ViewGroup,
        child: View,
        visible: Boolean,
        animate: Boolean
    ) {
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (child.visibility == targetVisibility) return
        if (!animate || !parent.isLaidOut || !child.isAttachedToWindow) {
            child.visibility = targetVisibility
            return
        }
        TransitionManager.endTransitions(parent)
        TransitionManager.beginDelayedTransition(
            parent,
            TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = if (visible) 260L else 220L
                interpolator = if (visible) {
                    secondaryExpandInterpolator
                } else {
                    secondaryCollapseInterpolator
                }
            }
        )
        child.visibility = targetVisibility
    }

    /**
     * 相关推荐沿用既有五个布尔开关，以草稿式二级勾选面板集中编辑。
     * 匹配增强和理由关键词同批落盘，取消弹窗不会改变现有运行时配置。
     */
    private fun showVideoRelateFilterDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val draft = VideoRelateFilterDraft(
            videoRelateFilterValues(),
            videoRelateReasonFilterKeywords
        )

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.video_relate_filter_settings)
                textColor = getColor(R.color.colorTextDark)
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.video_relate_filter_dialog_description)
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (7 * density).toInt() }
        )

        val quickActions = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val selectAllButton = createTermsActionButton(
            getString(R.string.video_relate_filter_select_all),
            filled = false
        ) {}
        quickActions.addView(
            selectAllButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val clearButton = createTermsActionButton(
            getString(R.string.video_relate_filter_clear),
            filled = false
        ) {}
        quickActions.addView(
            clearButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        )
        container.addView(
            quickActions,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val listBody = NativeScrollView(this).apply { isFillViewport = true }
        val rowContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
        VideoRelateFilterCatalog.panelOptions.forEach { option ->
            val box = android.widget.CheckBox(this).apply {
                text = getString(videoRelateFilterLabel(option.preferenceKey))
                textSize = 14f
                textColor = getColor(R.color.colorTextDark)
                isChecked = draft[option.preferenceKey]
                isFocusable = true
            }
            checkboxes[option.preferenceKey] = box
            rowContainer.addView(
                box,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = ((if (option.isMatchingEnhancement) 13 else 4) * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
            )
            if (option.isMatchingEnhancement) {
                rowContainer.addView(
                    NativeTextView(this).apply {
                        text = getString(R.string.video_relate_matching_enhancement_tip)
                        textColor = getColor(R.color.colorTextGray)
                        textSize = 12f
                        alpha = 0.72f
                        setLineSpacing(4 * density, 1f)
                    },
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (12 * density).toInt()
                        marginEnd = (8 * density).toInt()
                        bottomMargin = (8 * density).toInt()
                    }
                )
            }
        }

        val reasonGroup = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            background = skinCardBackground(monetColors.surfaceVariant)
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }
        val reasonCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.video_relate_reason_filter)
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            isChecked = draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED]
            isFocusable = true
        }
        reasonGroup.addView(
            reasonCheckbox,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        reasonGroup.addView(
            NativeTextView(this).apply {
                text = getString(R.string.video_relate_reason_filter_tip)
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (12 * density).toInt()
                marginEnd = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        )
        val keywordContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
        }
        keywordContainer.addView(
            NativeTextView(this).apply {
                text = getString(R.string.video_relate_reason_filter_keywords)
                textColor = monetColors.primary
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        val keywordsEditor = NativeEditText(this).apply {
            hint = getString(R.string.video_relate_reason_filter_keywords_hint)
            setText(draft.reasonKeywords)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0xA0))
            textSize = 13f
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(VideoRelateFilterDraft.MAX_KEYWORDS_LENGTH))
            background = skinCardBackground(monetColors.surface)
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt()
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft.reasonKeywords = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        keywordContainer.addView(
            keywordsEditor,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )
        reasonGroup.addView(
            keywordContainer,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        rowContainer.addView(
            reasonGroup,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (5 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
        )
        listBody.addView(rowContainer)
        val listHeight = minOf(
            (440 * density).toInt(),
            (resources.displayMetrics.heightPixels * 0.54f).toInt()
        )
        container.addView(
            listBody,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight).apply {
                topMargin = (7 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        )

        val buttonRow = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = createTermsActionButton(
            getString(R.string.dialog_cancel),
            filled = false
        ) { dismissWithAnimation(dialog, container) {} }
        lateinit var refreshUi: () -> Unit
        lateinit var saveButton: NativeTextView
        var updating = false
        var initialRefresh = true

        refreshUi = {
            updating = true
            VideoRelateFilterCatalog.panelOptions.forEach { option ->
                checkboxes.getValue(option.preferenceKey).isChecked =
                    draft[option.preferenceKey]
            }
            reasonCheckbox.isChecked =
                draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED]
            saveButton.text = getString(
                R.string.video_relate_filter_save,
                draft.selectedContentCount()
            )
            val reasonGroupWasVisible = reasonGroup.isVisible
            if (!draft.reasonFilterVisible) {
                keywordContainer.visibility = View.GONE
                setModalSectionVisible(
                    rowContainer,
                    reasonGroup,
                    visible = false,
                    animate = !initialRefresh
                )
            } else {
                if (!reasonGroupWasVisible) {
                    keywordContainer.visibility = if (draft.keywordEditorVisible) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
                setModalSectionVisible(
                    rowContainer,
                    reasonGroup,
                    visible = true,
                    animate = !initialRefresh
                )
                if (reasonGroupWasVisible) {
                    setModalSectionVisible(
                        reasonGroup,
                        keywordContainer,
                        visible = draft.keywordEditorVisible,
                        animate = !initialRefresh
                    )
                }
            }
            updating = false
            initialRefresh = false
        }
        checkboxes.forEach { (key, box) ->
            box.setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    draft[key] = checked
                    refreshUi()
                }
            }
        }
        reasonCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED] = checked
                refreshUi()
            }
        }
        selectAllButton.setOnClickListener {
            draft.selectAll()
            refreshUi()
        }
        clearButton.setOnClickListener {
            draft.clear()
            refreshUi()
        }

        saveButton = createTermsActionButton("", filled = true) {
            val changed = draft.changedValues()
            if (changed.isEmpty() && !draft.keywordsChanged()) {
                dismissWithAnimation(dialog, container) {}
                return@createTermsActionButton
            }
            val saved = runCatching {
                prefs().edit {
                    changed.forEach { (key, value) -> putBoolean(key, value) }
                    if (draft.keywordsChanged()) {
                        putString(
                            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
                            draft.reasonKeywords
                        )
                    }
                }
            }.isSuccess
            if (!saved) {
                toast(getString(R.string.video_relate_filter_save_failed))
                return@createTermsActionButton
            }
            changed.forEach { (key, value) -> applyVideoRelateFilterValue(key, value) }
            videoRelateReasonFilterKeywords = draft.reasonKeywords
            videoRelateFilterSummaryView?.text = videoRelateFilterSummary()
            toast(getString(R.string.video_relate_filter_applied))
            dismissWithAnimation(dialog, container) {}
        }
        buttonRow.addView(
            cancelButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            saveButton,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        )
        container.addView(
            buttonRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        refreshUi()
        presentModalDialog(dialog, container)
    }

    private fun settingsSearchSectionLabel(section: SettingsSearchSection): String =
        getString(
            when (section) {
                SettingsSearchSection.GENERAL -> R.string.settings_search_section_general
                SettingsSearchSection.ADVANCED -> R.string.advanced_settings
                SettingsSearchSection.EXPERIMENTAL -> R.string.experimental_features
            }
        )

    private fun highlightedSettingsSearchText(
        value: String,
        ranges: List<IntRange>
    ): CharSequence {
        if (value.isEmpty() || ranges.isEmpty()) return value
        return SpannableString(value).apply {
            ranges.forEach { range ->
                val start = range.first.coerceIn(0, value.length)
                val end = (range.last + 1).coerceIn(start, value.length)
                if (start >= end) return@forEach
                setSpan(
                    BackgroundColorSpan(ColorUtils.setAlphaComponent(monetColors.primary, 0x32)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    ForegroundColorSpan(monetColors.primary),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /** 从当前本地化控件树构建索引，避免功能新增后还要维护第二份易失真的搜索目录。 */
    private fun collectSettingsSearchTargets(): List<RuntimeSettingsSearchTarget> {
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
            val texts = mutableListOf<String>()
            collectText(view, texts)
            val title = texts.firstOrNull()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (title.isBlank()) return
            val key = "setting-${nextKey++}"
            targets += RuntimeSettingsSearchTarget(
                item = SettingsSearchItem(
                    key = key,
                    title = title,
                    detail = texts.drop(1).joinToString(" "),
                    section = settingsSearchSectionLabel(section)
                ),
                view = view,
                section = section
            )
        }

        fun visit(view: View, inheritedSection: SettingsSearchSection) {
            val section = when (view) {
                advancedContent -> SettingsSearchSection.ADVANCED
                experimentalContent -> SettingsSearchSection.EXPERIMENTAL
                else -> inheritedSection
            }
            val collapsedSectionRoot = view === advancedContent || view === experimentalContent
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

    private fun showSettingsSearchDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createModalContainer()
        val runtimeTargets = collectSettingsSearchTargets()
        val targetByKey = runtimeTargets.associateBy { it.item.key }

        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.settings_search_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        val editor = NativeEditText(this).apply {
            hint = getString(R.string.settings_search_hint)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
            textSize = 15f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(
                (14 * density).toInt(),
                (11 * density).toInt(),
                (14 * density).toInt(),
                (11 * density).toInt()
            )
            background = skinCardBackground(monetColors.surfaceVariant)
        }
        container.addView(
            editor,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val resultContainer = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
        }
        val resultScroll = NativeScrollView(this).apply {
            isFillViewport = true
            addView(resultContainer)
        }
        val resultHeight = minOf(
            (360 * density).toInt(),
            (resources.displayMetrics.heightPixels * 0.44f).toInt()
        )
        container.addView(
            resultScroll,
            NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resultHeight).apply {
                topMargin = (10 * density).toInt()
            }
        )

        fun renderResults(query: String) {
            resultContainer.removeAllViews()
            val results = SettingsSearchMatcher.searchMatches(
                query = query,
                items = runtimeTargets.map(RuntimeSettingsSearchTarget::item)
            )
            if (query.isBlank() || results.isEmpty()) {
                resultContainer.addView(
                    NativeTextView(this).apply {
                        text = getString(
                            if (query.isBlank()) R.string.settings_search_prompt
                            else R.string.settings_search_empty
                        )
                        gravity = Gravity.CENTER
                        textColor = getColor(R.color.colorTextGray)
                        textSize = 13f
                        alpha = 0.72f
                        setPadding(
                            (12 * density).toInt(),
                            (36 * density).toInt(),
                            (12 * density).toInt(),
                            (36 * density).toInt()
                        )
                    },
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                return
            }

            results.forEach { match ->
                val item = match.item
                val target = targetByKey[item.key] ?: return@forEach
                resultContainer.addView(
                    NativeLinearLayout(this).apply {
                        orientation = NativeLinearLayout.VERTICAL
                        background = selfRippleBackground(10f)
                        setPadding(
                            (12 * density).toInt(),
                            (10 * density).toInt(),
                            (12 * density).toInt(),
                            (10 * density).toInt()
                        )
                        isClickable = true
                        isFocusable = true
                        contentDescription = "${item.title}. ${item.section}"
                        addView(
                            NativeTextView(this@MainActivity).apply {
                                text = highlightedSettingsSearchText(item.title, match.titleRanges)
                                textColor = getColor(R.color.colorTextDark)
                                textSize = 15f
                            }
                        )
                        if (item.detail.isNotBlank()) {
                            addView(
                                NativeTextView(this@MainActivity).apply {
                                    text = highlightedSettingsSearchText(item.detail, match.detailRanges)
                                    textColor = getColor(R.color.colorTextGray)
                                    textSize = 12f
                                    alpha = 0.82f
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.END
                                },
                                NativeLinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { topMargin = (3 * density).toInt() }
                            )
                        }
                        addView(
                            NativeTextView(this@MainActivity).apply {
                                text = highlightedSettingsSearchText(item.section, match.sectionRanges)
                                textColor = getColor(R.color.colorTextGray)
                                textSize = 11f
                                alpha = 0.68f
                            },
                            NativeLinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (3 * density).toInt() }
                        )
                        setOnClickListener {
                            dismissWithAnimation(dialog, container) {
                                revealSettingsSearchTarget(target)
                            }
                        }
                    },
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * density).toInt() }
                )
            }
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                renderResults(editable?.toString().orEmpty())
            }
        })
        renderResults("")
        container.addView(
            createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
                dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )

        presentModalDialog(dialog, container)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor.requestFocus()
        editor.postDelayed({
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 220L)
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

    private fun scheduleSettingsSearchTargetHighlight(targetView: View) {
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

    private fun revealSettingsSearchTarget(target: RuntimeSettingsSearchTarget) {
        val primarySectionDelay = when (target.section) {
            SettingsSearchSection.ADVANCED -> {
                if (!advancedExpanded) {
                    advancedExpanded = true
                    val content = advancedContent
                    val chevron = advancedChevron
                    if (content != null && chevron != null) {
                        animateSecondarySection(content, chevron, expanded = true)
                    }
                    300L
                } else 0L
            }
            SettingsSearchSection.EXPERIMENTAL -> {
                if (!experimentalExpanded) {
                    experimentalExpanded = true
                    val content = experimentalContent
                    val chevron = experimentalChevron
                    if (content != null && chevron != null) {
                        animateSecondarySection(content, chevron, expanded = true)
                    }
                    300L
                } else 0L
            }
            SettingsSearchSection.GENERAL -> 0L
        }
        val categoryDelay = if (
            target.section == SettingsSearchSection.ADVANCED &&
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
        }, sectionDelay)
    }

    private fun launchSettingsBackup() {
        val card = settingsBackupEntryView
        val title = settingsBackupEntryTitleView
        val sourceWindow = findViewById<View>(Android_R.id.content)
        if (card != null && title != null) {
            SettingsBackupTransitionOriginRegistry.register(card, title, sourceWindow)
        }
        val launchIntent = Intent(this, SettingsBackupActivity::class.java)
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
        val launchIntent = Intent(this, DiagnosticsActivity::class.java)
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
        UserTermsAuthorizationCoordinator.removeListener(userTermsAuthorizationListener)
        RemoteHookConfigStore.removeStatusListener(frameworkStatusListener)
        activationMainHandler.removeCallbacks(frameworkStatusTimeout)
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
        experimentalContent?.animate()?.setListener(null)
        experimentalContent?.animate()?.cancel()
        experimentalChevron?.animate()?.cancel()
        advancedContent?.animate()?.setListener(null)
        advancedContent?.animate()?.cancel()
        advancedChevron?.animate()?.cancel()
        advancedCategorySections.values.forEach { section ->
            section.content.animate().setListener(null)
            section.content.animate().cancel()
            section.chevron.animate().cancel()
        }
        advancedCategorySections.clear()
        advancedCategoryMarkers.clear()
        experimentalContent = null
        experimentalChevron = null
        advancedContent = null
        advancedChevron = null
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
        homeTabRulesSummaryView = null
        homeRecommendTitleSummaryView = null
        homeComponentRulesSummaryView = null
        mineComponentRulesSummaryView = null
        bottomBarRulesSummaryView = null
        recommendVideoDurationSummaryView = null
        commentKeywordSummaryView = null
        commentLevelSummaryView = null
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
        prepareSkinSession()

        // 读取广告开关配置：prefs() 只创建一次跨进程 bridge，两个开关复用（降低初始化开销）
        val modulePrefs = runCatching { prefs() }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "init prefs failed", t)
        }.getOrNull()
        noRootPrefsBridge = modulePrefs
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
        adskipEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read prefs failed", t)
        }.getOrDefault(true)
        gamecardAdEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_GAMECARD_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read gamecard prefs failed", t)
        }.getOrDefault(true)
        hideVideoDetailAppPromotion = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read video detail app promotion prefs failed", t)
        }.getOrDefault(false)
        bannerAdEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_BANNER_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read banner prefs failed", t)
        }.getOrDefault(true)
        hideHomeGameMenu = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.HIDE_HOME_GAME_MENU, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read home game menu prefs failed", t)
        }.getOrDefault(false)
        hideHomeSearchDefaultWord = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read home search word prefs failed", t)
        }.getOrDefault(false)
        homeVerticalOpenDetail = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read home vertical detail prefs failed", t)
        }.getOrDefault(false)
        removeHomeRecommendAds = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS, false) ?: false
        }.getOrDefault(false)
        removeHomeRecommendPictures = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES, false)
                ?: false
        }.getOrDefault(false)
        removeHomeRecommendGamePromotions = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
                false
            ) ?: false
        }.getOrDefault(false)
        homeRecommendTitleFilterEnabled = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                false
            ) ?: false
        }.getOrDefault(false)
        homeRecommendTitleKeywords = runCatching {
            modulePrefs?.getString(
                FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS,
                ""
            ).orEmpty()
        }.getOrDefault("")
        removeHomeRecommendLive = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE, false) ?: false
        }.getOrDefault(false)
        removeHomeRecommendCourses = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES, false)
                ?: false
        }.getOrDefault(false)
        removeHomeRecommendVertical = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL, false)
                ?: false
        }.getOrDefault(false)
        removeHomeRecommendLarge = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE, false) ?: false
        }.getOrDefault(false)
        homeTabHiddenRules = runCatching {
            modulePrefs?.getString(FeaturePreferences.HOME_TAB_HIDDEN_RULES, "").orEmpty()
        }.getOrDefault("")
        homeComponentHiddenRules = runCatching {
            modulePrefs?.getString(FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES, "").orEmpty()
        }.getOrDefault("")
        bottomBarHiddenRules = runCatching {
            modulePrefs?.getString(FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES, "").orEmpty()
        }.getOrDefault("")
        recommendVideoMinDurationSeconds = runCatching {
            (modulePrefs?.getInt(
                FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                0
            ) ?: 0).coerceAtLeast(0)
        }.onFailure { throwable ->
            Log.e(
                "BilibiliInnocentLab",
                "read recommended video minimum duration prefs failed",
                throwable
            )
        }.getOrDefault(0)
        recommendVideoMaxDurationSeconds = runCatching {
            (modulePrefs?.getInt(
                FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
                0
            ) ?: 0).coerceAtLeast(0)
        }.onFailure { throwable ->
            Log.e(
                "BilibiliInnocentLab",
                "read recommended video maximum duration prefs failed",
                throwable
            )
        }.getOrDefault(0)
        removeStoryAds = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_ADS, false) ?: false
        }.getOrDefault(false)
        removeStoryLive = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_LIVE, false) ?: false
        }.getOrDefault(false)
        removeStoryGames = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_GAMES, false) ?: false
        }.getOrDefault(false)
        removeStoryBangumi = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_BANGUMI, false) ?: false
        }.getOrDefault(false)
        removeStoryCourses = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_COURSES, false) ?: false
        }.getOrDefault(false)
        removeStoryShortDrama = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, false) ?: false
        }.getOrDefault(false)
        removeStoryShopping = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_SHOPPING, false) ?: false
        }.getOrDefault(false)
        removeStoryMovies = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_MOVIES, false) ?: false
        }.getOrDefault(false)
        removeStoryDocumentaries = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, false) ?: false
        }.getOrDefault(false)
        removeStoryTv = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_TV, false) ?: false
        }.getOrDefault(false)
        removeStoryVariety = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_VARIETY, false) ?: false
        }.getOrDefault(false)
        removeStoryMusic = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_STORY_MUSIC, false) ?: false
        }.getOrDefault(false)
        hideMineVip = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.HIDE_MINE_VIP, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read mine vip prefs failed", t)
        }.getOrDefault(false)
        keepMineVipSpace = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.KEEP_MINE_VIP_SPACE, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read mine vip space prefs failed", t)
        }.getOrDefault(false)
        mineComponentHiddenRules = runCatching {
            modulePrefs?.getString(FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES, "").orEmpty()
        }.getOrDefault("")
        blockAppUpdate = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read block app update prefs failed", t)
        }.getOrDefault(false)
        hideDynamicCityTab = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read dynamic city tab prefs failed", t)
        }.getOrDefault(false)
        hideDynamicSchoolTab = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read dynamic school tab prefs failed", t)
        }.getOrDefault(false)
        preferDynamicVideoTab = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read preferred dynamic video prefs failed", t)
        }.getOrDefault(false)
        showFullNumbers = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.SHOW_FULL_NUMBERS, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read full number prefs failed", t)
        }.getOrDefault(false)
        hidePlayerPortraitControl = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read player portrait prefs failed", t)
        }.getOrDefault(false)
        transparentPlayerStatusBar = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read player status bar prefs failed", t)
        }.getOrDefault(false)
        removeRelateCommercial = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_RELATE_COMMERCIAL, false) ?: false
        }.getOrDefault(false)
        removeRelateGame = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_RELATE_GAME, false) ?: false
        }.getOrDefault(false)
        removeRelateLive = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_RELATE_LIVE, false) ?: false
        }.getOrDefault(false)
        removeRelateCourse = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_RELATE_COURSE, false) ?: false
        }.getOrDefault(false)
        removeRelateSpecial = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REMOVE_RELATE_SPECIAL, false) ?: false
        }.getOrDefault(false)
        videoRelateMatchingEnhancementEnabled = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                false
            ) ?: false
        }.getOrDefault(false)
        videoRelateReasonFilterEnabled = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED,
                false
            ) ?: false
        }.getOrDefault(false)
        videoRelateReasonFilterKeywords = runCatching {
            modulePrefs?.getString(
                FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
                ""
            ).orEmpty().take(VideoRelateFilterDraft.MAX_KEYWORDS_LENGTH)
        }.getOrDefault("")
        playerDefaultQualityQn = runCatching {
            PlayerQualityConfig.normalize(
                modulePrefs?.getInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, 0) ?: 0
            )
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read player default quality prefs failed", t)
        }.getOrDefault(0)
        removeCommentSearchLinks = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment search prefs failed", t)
        }.getOrDefault(false)
        removeCommentEmptyGuide = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment empty guide prefs failed", t)
        }.getOrDefault(false)
        removeCommentVoteWidgets = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment vote prefs failed", t)
        }.getOrDefault(false)
        removeCommentFollowButtons = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment follow prefs failed", t)
        }.getOrDefault(false)
        removeCommentQoe = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_QOE,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment qoe prefs failed", t)
        }.getOrDefault(false)
        removeCommentOperations = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.REMOVE_COMMENT_OPERATIONS,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment operation prefs failed", t)
        }.getOrDefault(false)
        blockCommentQuickReply = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read comment quick reply prefs failed", t)
        }.getOrDefault(false)
        hideCommentSection = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.HIDE_COMMENT_SECTION, false) ?: false
        }.getOrDefault(false)
        replyTopologyEnabled = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false) ?: false
        }.getOrDefault(false)
        commentKeywordFilterEnabled = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED,
                false
            ) ?: false
        }.getOrDefault(false)
        commentFilterKeywords = runCatching {
            modulePrefs?.getString(FeaturePreferences.COMMENT_FILTER_KEYWORDS, "").orEmpty()
        }.getOrDefault("")
        commentMinLevelFilterEnabled = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED,
                false
            ) ?: false
        }.getOrDefault(false)
        commentMinLevel = runCatching {
            (modulePrefs?.getInt(
                FeaturePreferences.COMMENT_MIN_LEVEL,
                CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL
            ) ?: CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL).coerceIn(1, 6)
        }.getOrDefault(CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL)
        blockTeenagersModePrompt = runCatching {
            modulePrefs?.getBoolean(
                FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT,
                false
            ) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read teenagers mode prefs failed", t)
        }.getOrDefault(false)
        purifySplashAds = runCatching {
            modulePrefs?.getBoolean(FeaturePreferences.PURIFY_SPLASH_ADS, false) ?: false
        }.getOrDefault(false)
        merchAdEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_MERCH_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read merch prefs failed", t)
        }.getOrDefault(true)
        freeCopyEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_FREE_COPY_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read free copy prefs failed", t)
        }.getOrDefault(true)
        freeCopyDescEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read free copy desc prefs failed", t)
        }.getOrDefault(true)
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
        freeCopyLightMode = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read free copy light mode failed", t)
        }.getOrDefault(false)
        freeCopyAutoLight = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read free copy auto light failed", t)
        }.getOrDefault(false)
        roamingCompatEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read roaming compat prefs failed", t)
        }.getOrDefault(false)
        predictiveBackEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, false) ?: false
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read predictive back prefs failed", t)
        }.getOrDefault(false)
        applyPredictiveBack()
        logEnabled = runCatching {
            modulePrefs?.getBoolean(HookEntry.PREF_LOG_ENABLED, true) ?: true
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read log enabled failed", t)
        }.getOrDefault(true)
        logVerbose = runCatching {
            modulePrefs?.getString(HookEntry.PREF_LOG_LEVEL, HookEntry.LOG_LEVEL_COMPLETE) != HookEntry.LOG_LEVEL_MINIMAL
        }.onFailure { t ->
            Log.e("BilibiliInnocentLab", "read log level failed", t)
        }.getOrDefault(true)

        // UI view based on Hikage DSL
        // See: https://github.com/BetterAndroid/Hikage
        // Don't like it or want to switch back to XML writing? Can refer to res/layout/activity_main.xml
        // 不喜欢或者想切换回 XML 写法？可以参考 res/layout/activity_main.xml
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
                        setOnClickListener { showSettingsSearchDialog() }
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
                        setOnClickListener {
                            showRestartConfirmDialog()
                        }
                    }
                    ImageView(
                        lparams = LayoutParams(27.dp, 27.dp) {
                            marginEnd = 5.dp
                        }
                    ) {
                        background = selfRippleBackground(14f)
                        alpha = 0.85f
                        setImageResource(R.mipmap.ic_github)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        contentDescription = stringResource(R.string.github_menu_description)
                        setOnClickListener { showGitHubMenuDialog() }
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
                        LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true) {
                                updateMargins(horizontal = 15.dp)
                            },
                            init = {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER or Gravity.START
                                background = skinCardBackground(monetColors.surfaceVariant)
                                updatePadding(left = 15.dp, top = 15.dp, right = 15.dp)
                            }
                        ) {
                            LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true),
                                init = {
                                    gravity = Gravity.CENTER or Gravity.START
                                }
                            ) {
                                ImageView(
                                    lparams = LayoutParams(15.dp, 15.dp) {
                                        marginEnd = 10.dp
                                    }
                                ) {
                                    setImageResource(R.drawable.ic_tune)
                                    imageTintList = stateColorResource(R.color.colorTextGray)
                                }
                                TextView(
                                    lparams = LayoutParams(widthMatchParent = true)
                                ) {
                                    alpha = 0.85f
                                    isSingleLine = true
                                    text = stringResource(R.string.display_settings)
                                    textColor = colorResource(R.color.colorTextGray)
                                    textSize = 12f
                                }
                            }
                            LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 8.dp
                                },
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    background = selfRippleBackground(10f)
                                    updatePadding(horizontal = 4.dp, vertical = 9.dp)
                                    isClickable = true
                                    isFocusable = true
                                    setOnClickListener { showAppLanguageDialog() }
                                }
                            ) {
                                TextView(
                                    lparams = LayoutParams(widthMatchParent = true)
                                ) {
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
                        Space(lparams = LayoutParams(height = 10.dp))
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
                        Space(lparams = LayoutParams(height = 10.dp))
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
                        }
                        // Advanced 分支新增功能统一收纳到默认折叠的“进阶设置”。
                        LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true) {
                                updateMargins(horizontal = 15.dp)
                                topMargin = 10.dp
                            },
                            init = {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER or Gravity.START
                                background = skinCardBackground(monetColors.surfaceVariant)
                                updatePadding(left = 15.dp, top = 5.dp, right = 15.dp, bottom = 5.dp)
                            }
                        ) {
                            LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true),
                                init = {
                                    gravity = Gravity.CENTER or Gravity.START
                                    updatePadding(vertical = 10.dp)
                                    setOnClickListener { toggleAdvanced() }
                                }
                            ) {
                                ImageView(
                                    lparams = LayoutParams(15.dp, 15.dp) {
                                        marginEnd = 10.dp
                                    }
                                ) {
                                    setImageResource(R.drawable.ic_tune)
                                    imageTintList = stateColorResource(R.color.colorTextGray)
                                }
                                TextView(
                                    lparams = LayoutParams { weight = 1f }
                                ) {
                                    alpha = 0.85f
                                    isSingleLine = true
                                    text = stringResource(R.string.advanced_settings)
                                    textColor = colorResource(R.color.colorTextGray)
                                    textSize = 12f
                                }
                                ImageView(
                                    lparams = LayoutParams(18.dp, 18.dp)
                                ) {
                                    advancedChevron = this
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
                                    advancedContent = this
                                    updatePadding(bottom = 10.dp)
                                }
                            ) {
                            // 分类：首页与导航
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                advancedCategoryMarkers[
                                    AdvancedSettingsCategory.HOME_NAVIGATION
                                ] = this
                                alpha = 0.9f
                                isSingleLine = true
                                text = stringResource(R.string.advanced_home_navigation_category)
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
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 14.dp
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                text = stringResource(R.string.home_recommend_purify_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 5.dp
                                }
                            ) {
                                text = stringResource(R.string.remove_home_recommend_ads)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendAds
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendAds = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS,
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
                                text = stringResource(R.string.remove_home_recommend_pictures)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendPictures
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendPictures = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES,
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
                                text = stringResource(R.string.remove_home_recommend_game_promotions)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendGamePromotions
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendGamePromotions = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
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
                                        homeRecommendTitleKeywords
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
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 8.dp
                                    bottomMargin = 5.dp
                                }
                            ) {
                                text = stringResource(R.string.remove_home_recommend_live)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendLive
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendLive = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE,
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
                                text = stringResource(R.string.remove_home_recommend_courses)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendCourses
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendCourses = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES,
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
                                text = stringResource(R.string.remove_home_recommend_large)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendLarge
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendLarge = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE,
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
                                text = stringResource(R.string.home_recommend_purify_tip)
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
                                    ruleSummary(homeTabHiddenRules)
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
                                        R.string.custom_home_tab_hide_dialog_title,
                                        R.string.custom_home_tab_hide_hint,
                                        homeTabHiddenRules
                                    ) { value ->
                                        homeTabHiddenRules = value
                                        prefs().edit {
                                            putString(
                                                FeaturePreferences.HOME_TAB_HIDDEN_RULES,
                                                value
                                            )
                                        }
                                        homeTabRulesSummaryView?.text =
                                            stringResource(R.string.custom_home_tab_hide) + "\n" +
                                                ruleSummary(value)
                                    }
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
                                    ruleSummary(homeComponentHiddenRules)
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
                                        R.string.custom_home_component_hide_dialog_title,
                                        R.string.custom_home_component_hide_hint,
                                        homeComponentHiddenRules
                                    ) { value ->
                                        homeComponentHiddenRules = value
                                        prefs().edit {
                                            putString(
                                                FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES,
                                                value
                                            )
                                        }
                                        homeComponentRulesSummaryView?.text =
                                            stringResource(R.string.custom_home_component_hide) + "\n" +
                                                ruleSummary(value)
                                    }
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
                                    topMargin = 12.dp
                                }
                            ) {
                                bottomBarRulesSummaryView = this
                                text = stringResource(R.string.custom_bottom_bar_hide) + "\n" +
                                    ruleSummary(bottomBarHiddenRules)
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
                                        R.string.custom_bottom_bar_hide_dialog_title,
                                        R.string.custom_bottom_bar_hide_hint,
                                        bottomBarHiddenRules
                                    ) { value ->
                                        bottomBarHiddenRules = value
                                        prefs().edit {
                                            putString(
                                                FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES,
                                                value
                                            )
                                        }
                                        bottomBarRulesSummaryView?.text =
                                            stringResource(R.string.custom_bottom_bar_hide) + "\n" +
                                                ruleSummary(value)
                                    }
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
                                    showRecommendVideoDurationRangeDialog()
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                                }
                            )
                            // 分类：页面与显示
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                advancedCategoryMarkers[
                                    AdvancedSettingsCategory.INTERFACE
                                ] = this
                                alpha = 0.9f
                                isSingleLine = true
                                text = stringResource(R.string.advanced_interface_category)
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
                                    mineComponentSummary()
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
                                    queryMineComponentSnapshotAndOpenPicker()
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                                }
                            )
                            // 子项 5：客户端更新（新功能默认关闭）
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                isSingleLine = true
                                text = stringResource(R.string.client_update_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                                }
                            )
                            // 子项 6：动态页标签净化（新功能默认关闭）
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                isSingleLine = true
                                text = stringResource(R.string.dynamic_page_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                                }
                            )
                            // 子项 7：完整数字显示（新功能默认关闭）
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                isSingleLine = true
                                text = stringResource(R.string.number_display_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(
                                        ColorUtils.setAlphaComponent(
                                            colorResource(R.color.colorTextGray),
                                            0x40
                                        )
                                    )
                                }
                            )
                            // 子项 8：青少年模式提示页（新功能默认关闭）
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                isSingleLine = true
                                text = stringResource(R.string.prompt_purify_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(
                                        ColorUtils.setAlphaComponent(
                                            colorResource(R.color.colorTextGray),
                                            0x40
                                        )
                                    )
                                }
                            )
                            // 分类：播放与视频
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                advancedCategoryMarkers[
                                    AdvancedSettingsCategory.PLAYBACK
                                ] = this
                                alpha = 0.9f
                                isSingleLine = true
                                text = stringResource(R.string.advanced_playback_category)
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
                                    setOnClickListener { showPortraitContentFilterDialog() }
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
                                    setOnClickListener { showVideoRelateFilterDialog() }
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
                            LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 12.dp
                                },
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    background = selfRippleBackground(10f)
                                    updatePadding(horizontal = 4.dp, vertical = 9.dp)
                                    isClickable = true
                                    isFocusable = true
                                    setOnClickListener { showPlayerQualityDialog() }
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(
                                        ColorUtils.setAlphaComponent(
                                            colorResource(R.color.colorTextGray),
                                            0x40
                                        )
                                    )
                                }
                            )
                            // 分类：评论区
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
                                advancedCategoryMarkers[
                                    AdvancedSettingsCategory.COMMENT
                                ] = this
                                alpha = 0.9f
                                isSingleLine = true
                                text = stringResource(R.string.advanced_comment_category)
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
                                        commentFilterKeywords
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
                                setOnClickListener { showCommentMinLevelDialog() }
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
                            }
                        }
                        Space(lparams = LayoutParams(height = 10.dp))
                        // 分支前已有的自由复制功能保持独立，不归入进阶设置。
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
                            // 子项 10：评论区长按自由复制
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
                        }
                        Space(lparams = LayoutParams(height = 10.dp))
                        LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true) {
                                updateMargins(horizontal = 15.dp)
                            },
                            init = {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER or Gravity.START
                                background = skinCardBackground(monetColors.surfaceVariant)
                                updatePadding(left = 15.dp, top = 5.dp, right = 15.dp, bottom = 5.dp)
                            }
                        ) {
                            LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true),
                                init = {
                                    gravity = Gravity.CENTER or Gravity.START
                                    updatePadding(vertical = 10.dp)
                                    setOnClickListener { toggleExperimental() }
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
                                    lparams = LayoutParams {
                                        weight = 1f
                                    }
                                ) {
                                    alpha = 0.85f
                                    isSingleLine = true
                                    text = stringResource(R.string.experimental_features)
                                    textColor = colorResource(R.color.colorTextGray)
                                    textSize = 12f
                                }
                                ImageView(
                                    lparams = LayoutParams(18.dp, 18.dp)
                                ) {
                                    experimentalChevron = this
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
                                    experimentalContent = this
                                    updatePadding(bottom = 10.dp)
                                }
                            ) {
                                LinearLayout(
                                    lparams = LayoutParams(widthMatchParent = true) {
                                        bottomMargin = 5.dp
                                    },
                                    init = {
                                        orientation = LinearLayout.VERTICAL
                                        background = selfRippleBackground(10f)
                                        updatePadding(horizontal = 4.dp, vertical = 9.dp)
                                        isClickable = true
                                        isFocusable = true
                                        setOnClickListener { showSkinSelectionDialog() }
                                    }
                                ) {
                                    TextView(
                                        lparams = LayoutParams(widthMatchParent = true)
                                    ) {
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
                                        updatePadding(horizontal = 4.dp, vertical = 9.dp)
                                        isClickable = true
                                        isFocusable = true
                                        setOnClickListener { showLiquidBackgroundDialog() }
                                    }
                                ) {
                                    TextView(
                                        lparams = LayoutParams(widthMatchParent = true)
                                    ) {
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
                                // OnBackInvokedCallback 体系，返回时显示系统缩放预览动画
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
                                // 气泡亮暗色自动跟随（实验性功能）：开启后气泡颜色自动跟随
                                // B 站亮暗主题（进入视频详情页时判定缓存，弹泡零反射）；
                                // 手动「亮色模式气泡」开关被覆盖，切换时弹二级确认
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
                                        updatePadding(horizontal = 4.dp, vertical = 9.dp)
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
                        Space(lparams = LayoutParams(height = 10.dp))
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
                                }
                            ) {
                                // 滑动滑块（primary 圆角，随选中项平移；宽度在布局后动态设为容器一半）
                                FrameLayout(
                                    lparams = LayoutParams(matchParent = true),
                                    init = {
                                        logLevelThumb = this
                                        background = logLevelThumbBg()
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
                                        textColor = if (!logVerbose) monetColors.onPrimary else colorResource(R.color.colorTextGray)
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
                                        textColor = if (logVerbose) monetColors.onPrimary else colorResource(R.color.colorTextGray)
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
                        Space(lparams = LayoutParams(height = 10.dp))
                        Layout(createPromotionItem(R.string.about_module, R.mipmap.ic_yukihookapi))
                        Space(lparams = LayoutParams(height = 10.dp))
                        Layout(createPromotionItem(R.string.about_module_extension, R.mipmap.ic_kavaref))
                    }
                }
            }
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
        // setContentView 返回后尚未进入首帧绘制，此时重组/重排不会产生界面跳动。
        installAdvancedCategorySections()
        placeAdvancedBelowExperimental()
        renderNoRootUi()
        // 布局完成后定位日志档位滑块（宽度收缩为一半 + 对齐当前档位）
        findViewById<View>(Android_R.id.content).post {
            positionLogLevelThumb()
        }
        // 进入模块界面后低频检查稳定 Release；失败静默，避免网络异常打扰用户。
        findViewById<View>(Android_R.id.content).postDelayed(
            { checkForUpdates(manual = false) },
            800L
        )
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
}
