@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
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
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.highcapable.betterandroid.system.extension.component.disableComponent
import com.highcapable.betterandroid.system.extension.component.enableComponent
import com.highcapable.betterandroid.system.extension.component.isComponentEnabled
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.highcapable.betterandroid.ui.extension.view.parentOrNull
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.view.updateMargins
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.betterandroid.ui.extension.view.updateTypeface
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
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.factory.prefs
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ShellCommandRunner
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateCheckCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.YukiModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
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
import android.R as Android_R
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : AppViewsActivity() {

    private companion object {
        const val UPDATE_PREFS_NAME = "github_release_updates"
        /** 旧版本共用的成功检查时间（升级后作为稳定版渠道的历史时间迁移读取）。 */
        const val PREF_LAST_SUCCESSFUL_UPDATE_CHECK = "last_successful_check_ms"
        /** 各渠道独立的成功检查时间，避免切换渠道后 24 小时节流误跳过新渠道检查。 */
        const val PREF_LAST_CHECK_STABLE = "last_successful_check_ms_stable"
        const val PREF_LAST_CHECK_PREVIEW = "last_successful_check_ms_preview"
        const val PREF_UPDATE_CHANNEL = "update_channel"
        const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
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

    private var adskipEnabled = true
    private var gamecardAdEnabled = true
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
    private var commentKeywordSummaryView: NativeTextView? = null
    private var commentLevelSummaryView: NativeTextView? = null
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

    /** 免 Root 配置只在用户明确开启后同步；回调不得持有 Activity 或 View。 */
    private var noRootPrefsBridge: YukiHookPrefsBridge? = null
    private var noRootSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch? = null
    private var noRootStatusView: NativeTextView? = null
    private var noRootProgrammaticSwitch = false

    /** 激活卡片需要同时聚合 LSPosed 状态与经过版本校验的免 Root heartbeat。 */
    private var activationCardView: View? = null
    private var activationIconView: android.widget.ImageView? = null
    private var activationTitleView: NativeTextView? = null
    private var activationSourceView: NativeTextView? = null

    /** 当前活动的确认弹窗：Activity 销毁时主动 dismiss，避免 WindowLeaked */
    private var activeConfirmDialog: Dialog? = null

    /** 用户条款状态只在 Activity 创建时解析；决定落盘后随 recreate/finish 更新生命周期。 */
    private var userTermsDecision = UserTermsDecision.UNDECIDED
    private var termsDecisionActionInProgress = false

    /** GitHub 请求只允许单飞；切换渠道时保留最后一次手动请求并抑制过期结果。 */
    private val updateCheckCoordinator = UpdateCheckCoordinator()

    /** 日志详细度档位选择器的两个 pill 控件引用 + 滑动滑块 + 描述 TextView */
    private var logLevelMinimalPill: android.widget.TextView? = null
    private var logLevelCompletePill: android.widget.TextView? = null
    private var logLevelThumb: View? = null
    private var logLevelDesc: android.widget.TextView? = null
    /** 档位选择器是否已完成首次布局（滑块定位需要测量后执行） */
    private var logLevelLaidOut = false

    // Material You 标准动效插值器
    private val emphasizedDecelerate = PathInterpolator(0.2f, 0f, 0f, 1f)   // 展开（减速收尾）
    private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 1f, 1f)   // 收起（加速开始）
    // 超长二级菜单使用独立的 Material 3 风格曲线：快速建立反馈，保留更长的柔和收尾。
    // 不复用上方插值器，避免改变弹窗、日志滑块等已有动画的节奏。
    private val secondaryExpandInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val secondaryCollapseInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** Material You 动态取色调色板（从壁纸提取种子色） */
    private val monetColors by lazy { MonetColors.fromWallpaper(this) }

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
        thumb.animate()
            .translationX(targetX)
            .setDuration(260L)
            .setInterpolator(emphasizedDecelerate)
            .start()

        // 2. 文字颜色随进度渐变（精简 pill：onPrimary↔gray；完整 pill：gray↔onPrimary）
        val fromMinimalColor = if (verbose) onPrimary else gray
        val toMinimalColor = if (verbose) gray else onPrimary
        val fromCompleteColor = if (verbose) gray else onPrimary
        val toCompleteColor = if (verbose) onPrimary else gray

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = emphasizedDecelerate
            addUpdateListener { a ->
                val f = a.animatedValue as Float
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

    /** 刷新免 Root 状态与激活卡片；只有有效 heartbeat 才能计为激活。 */
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

        val rootActive = YukiHookAPI.Status.isXposedModuleActive
        val activation = NoRootSupportState.activationDecision(rootActive, state)
        activationCardView?.background = roundedColor(
            if (activation.activated) monetColors.primary else monetColors.surfaceVariant
        )
        activationIconView?.setImageResource(
            if (activation.activated) R.mipmap.ic_success else R.mipmap.ic_warn
        )
        activationTitleView?.setText(
            if (activation.activated) R.string.module_is_activated else R.string.module_not_activated
        )
        activationSourceView?.apply {
            text = when {
                rootActive && YukiHookAPI.Status.Executor.apiLevel > 0 -> getString(
                    R.string.activated_by,
                    YukiHookAPI.Status.Executor.name,
                    YukiHookAPI.Status.Executor.apiLevel
                )
                rootActive -> getString(
                    R.string.activated_by_noapi,
                    YukiHookAPI.Status.Executor.name
                )
                activation.byNoRoot -> getString(R.string.no_root_activated_by_npatch)
                else -> ""
            }
            isVisible = activation.activated
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
        if (YukiHookAPI.Status.isXposedModuleActive) return false
        if (NoRootSupportStore.isDesiredEnabled(applicationContext)) return true
        return when (currentNoRootDisplayState()) {
            NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE -> true
            else -> false
        }
    }

    /**
     * 二次确认弹窗：液态玻璃风格 + Material You 动效。
     * 弹窗采用 scale + alpha 动画（GPU 加速、不触发布局重绘，低功耗），
     * 符合 Material 3 的 emphasized easing 标准。
     */
    private fun showRestartConfirmDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val useNoRootFlow = shouldUseNoRootRestartFlow()

        // 液态玻璃容器：surface 色 + 大圆角 + 细白描边模拟玻璃高光
        val container = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (26 * density).toInt(), (24 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(monetColors.surface)
                setStroke((1 * density).toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 0x18))
            }
            // 无遮罩后，用柔和阴影增强弹窗与背景的层次区分
            elevation = 12 * density
        }

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
                        if (useNoRootFlow) openBilibiliAppDetails() else restartBilibili()
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

        // 根布局（透明、无遮罩）：居中容器
        val root = NativeFrameLayout(this)
        root.addView(container, NativeFrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
        })

        // 初始状态（show 前设置，避免闪烁）
        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.alpha = 0f

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f) // 去除系统默认的背景变暗
        }
        dialog.setContentView(root)
        // 记录当前弹窗，供 onDestroy 主动 dismiss（防 WindowLeaked）；dismiss 后清空引用
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
        }
        activeConfirmDialog = dialog
        dialog.show()

        // 入场动画（scale + fade，GPU 加速）
        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                .start()
        }
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

    /** 已明确拒绝时保持锁定，不显示任何模块配置入口。 */
    private fun showUserTermsDeclinedPage() {
        val density = resources.displayMetrics.density
        val root = createTermsNeutralRoot()
        val container = createGlassContainer().apply {
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

    /** 条款正文可滚动，操作按钮固定在玻璃容器底部；触外、系统取消均不关闭。 */
    private fun showUserTermsDialog() {
        activeConfirmDialog?.dismiss()
        termsDecisionActionInProgress = false
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()

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
        }
        activeConfirmDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(Android_R.color.transparent)
            setDimAmount(0f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
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
        val hookPrefs = runCatching { prefs() }.getOrNull()
        val persisted = if (accepted) {
            UserTermsConsentStore.accept(applicationContext, hookPrefs)
        } else {
            UserTermsConsentStore.decline(applicationContext, hookPrefs)
        }
        if (!persisted) {
            termsDecisionActionInProgress = false
            toast(getString(R.string.user_terms_save_failed))
            return
        }

        userTermsDecision = if (accepted) {
            UserTermsDecision.ACCEPTED
        } else {
            UserTermsDecision.DECLINED
        }
        dismissWithAnimation(dialog, container) {
            if (accepted) recreate() else finish()
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

    /** 应用语言单选弹窗：沿用现有玻璃容器、选中强调色和统一进退场动画。 */
    private fun showAppLanguageDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()
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

        presentGlassDialog(dialog, container)
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

    /** 右上角 GitHub 图标的二级菜单。 */
    private fun showGitHubMenuDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()

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

        presentGlassDialog(dialog, container)
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
        val container = createGlassContainer()
        val updatePrefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
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

        presentGlassDialog(dialog, container)
    }

    /** 播放器默认画质选择：只写模块配置，实际 Hook 在 B 站下次主进程启动时安装。 */
    private fun showPlayerQualityDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()

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

        presentGlassDialog(dialog, container)
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

    /** 评论最低等级选择：沿用播放器画质选择器的玻璃菜单与进退场动画。 */
    private fun showCommentMinLevelDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()

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
        presentGlassDialog(dialog, container)
    }

    /** 保存渠道选择并立即按新渠道检查一次；检查失败保留渠道，下次可继续。 */
    private fun applyUpdateChannel(channel: GitHubReleaseChecker.UpdateChannel) {
        val updatePrefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
        if (readUpdateChannel(updatePrefs) == channel) return
        updatePrefs.edit()
            .putString(PREF_UPDATE_CHANNEL, channel.storageValue)
            .apply()
        checkForUpdates(manual = true)
    }

    /** 读取当前更新渠道（未知/损坏值回退稳定版，兼容旧版本升级）。 */
    private fun readUpdateChannel(
        prefs: android.content.SharedPreferences
    ): GitHubReleaseChecker.UpdateChannel =
        GitHubReleaseChecker.UpdateChannel.fromStorageValue(prefs.getString(PREF_UPDATE_CHANNEL, null))

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
        val prefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
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
        val updatePrefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
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
        val updatePrefs = applicationContext.getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
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
        val container = createGlassContainer()

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

        presentGlassDialog(dialog, container)
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

    private fun createGlassContainer(): NativeLinearLayout {
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
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(monetColors.surface)
                setStroke(
                    (1 * density).toInt(),
                    ColorUtils.setAlphaComponent(Color.WHITE, 0x18)
                )
            }
            elevation = 12 * density
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0f
        }
    }

    private fun presentGlassDialog(dialog: Dialog, container: NativeLinearLayout) {
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
                    dismissWithAnimation(dialog, container) {}
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

    /** 自定义隐藏规则编辑器：沿用项目玻璃弹窗与统一退场动画。 */
    private fun showRuleEditorDialog(
        @StringRes titleRes: Int,
        @StringRes hintRes: Int,
        initialValue: String,
        onConfirm: (String) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = createGlassContainer()

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
        presentGlassDialog(dialog, container)
    }

    private fun ruleSummary(value: String): String = if (value.isBlank()) {
        getString(R.string.custom_hide_rules_empty)
    } else {
        getString(R.string.custom_hide_rules_current, value)
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
     * 相同（液态玻璃容器 + 取消/确认按钮 + 进出动画）。
     *
     * @param onConfirm 确认回调（自动跟随关闭 + 手动值生效；由调用方负责 UI 动画同步）
     * @param onCancel  取消回调（开关 UI 复位）
     */
    private fun showAutoLightConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
        activeConfirmDialog?.dismiss()
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)

        val container = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (26 * density).toInt(), (24 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(monetColors.surface)
                setStroke((1 * density).toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 0x18))
            }
            elevation = 12 * density
        }

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

        val root = NativeFrameLayout(this)
        root.addView(container, NativeFrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
        })

        // 初始状态（show 前设置，避免闪烁）
        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.alpha = 0f

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
        }
        dialog.setContentView(root)
        // 记录当前弹窗，供 onDestroy 主动 dismiss（防 WindowLeaked）；dismiss 后清空引用
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
        }
        activeConfirmDialog = dialog
        dialog.show()

        // 入场动画（scale + fade，GPU 加速）
        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180L)
                .setInterpolator(emphasizedDecelerate)
                .start()
        }
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
     * （showRestartConfirmDialog）完全一致（液态玻璃容器 + 取消/确认按钮 + 进出动画）。
     * 确认后清除版本适配缓存（VersionAdapter.clearCache），重启 B 站后自动重新定位。
     */
    private fun showAdaptConfirmDialog() {
        // 防御：若已有确认弹窗未关闭（理论上模态互斥），先关掉，保证引用唯一
        activeConfirmDialog?.dismiss()
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)

        val container = NativeLinearLayout(this).apply {
            orientation = NativeLinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (26 * density).toInt(), (24 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(monetColors.surface)
                setStroke((1 * density).toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 0x18))
            }
            elevation = 12 * density
        }

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

        val root = NativeFrameLayout(this)
        root.addView(container, NativeFrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
        })

        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.alpha = 0f

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (activeConfirmDialog === dialog) activeConfirmDialog = null
        }
        activeConfirmDialog = dialog
        dialog.show()

        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                .start()
        }
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
        // Magisk 新版在 /product/bin/su，硬编码会失败）
        val suPath = findSuPath()
        Thread {
            try {
                // 1. 杀死 B 站（root）；execShell 消费输出流，防止 buffer 满导致 waitFor 死锁
                val stopExitCode = execShell(
                    suPath,
                    "-c",
                    "am force-stop ${HookEntry.TARGET_PACKAGE}"
                )
                check(stopExitCode == 0) { "force-stop exited with $stopExitCode" }
                // 2. 延迟后重新拉起（am start 指定主 Activity；不用 monkey，避免误开自动旋转）
                Thread.sleep(800)
                val startExitCode = execShell(
                    suPath,
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

    /** 免 Root 模式没有 force-stop 权限，只引导用户进入系统应用详情手动停止并重开。 */
    private fun openBilibiliAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${HookEntry.TARGET_PACKAGE}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { throwable ->
            Log.e("BilibiliInnocentLab", "open Bilibili app details failed", throwable)
            toast(getString(R.string.no_root_restart_open_failed))
        }
    }

    /**
     * 探测可用的 su 路径。不同 root 方案 su 位置不同：
     * KernelSU 通常在 /system/bin/su，Magisk 新版（Android 10+）在 /product/bin/su，
     * 旧版 Magisk/SuperSU 在 /system/xbin/su 或 /sbin/su。按常见顺序探测第一个存在的，
     * 都不存在则兜底返回 "su"（依赖 shell PATH）。
     */
    private fun findSuPath(): String {
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
        return "su"
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

    override fun onResume() {
        super.onResume()
        if (!userTermsDecision.isAuthorized) return
        val selectionTag = InjectedUiLocale.syncFromAppCompat(applicationContext)
        InjectedUiLocale.setMirrorAndBroadcast(applicationContext, selectionTag)
        renderNoRootUi()
        synchronizeNoRootSupportIfEnabled()
    }

    override fun onPause() {
        // 用户离开设置页前刷新一次完整快照；开关关闭时直接返回，不连接 NPatch。
        if (userTermsDecision.isAuthorized) synchronizeNoRootSupportIfEnabled()
        super.onPause()
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

    @Suppress("DEPRECATION")
    private fun suppressLegacyActivityTransition() {
        if (AndroidVersion.isAtMost(AndroidVersion.T)) {
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        // Activity 销毁时主动关闭弹窗，避免 WindowLeaked（Activity has leaked window）
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        // 清理 View 引用字段，彻底断开对 hierarchy 的持有
        experimentalContent?.animate()?.setListener(null)
        experimentalContent?.animate()?.cancel()
        experimentalChevron?.animate()?.cancel()
        advancedContent?.animate()?.setListener(null)
        advancedContent?.animate()?.cancel()
        advancedChevron?.animate()?.cancel()
        experimentalContent = null
        experimentalChevron = null
        advancedContent = null
        advancedChevron = null
        noRootSwitch = null
        noRootStatusView = null
        noRootPrefsBridge = null
        activationCardView = null
        activationIconView = null
        activationTitleView = null
        activationSourceView = null
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
        commentKeywordSummaryView = null
        commentLevelSummaryView = null
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
        userTermsDecision = UserTermsConsentStore.readOrInitialize(applicationContext)
        if (!userTermsDecision.isAuthorized) {
            when (userTermsDecision) {
                UserTermsDecision.UNDECIDED -> showUserTermsGate()
                UserTermsDecision.DECLINED -> showUserTermsDeclinedPage()
                else -> Unit
            }
            return
        }

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
                        YukiModuleSettingsStore(modulePrefs)
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
                    TextView(
                        lparams = LayoutParams {
                            weight = 1f
                        }
                    ) {
                        isSingleLine = true
                        text = stringResource(R.string.app_name)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 25f
                        updateTypeface(Typeface.BOLD)
                    }
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
                        background = roundedColor(if (YukiHookAPI.Status.isXposedModuleActive) monetColors.primary else monetColors.surfaceVariant)
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
                        setImageResource(when {
                            YukiHookAPI.Status.isXposedModuleActive -> R.mipmap.ic_success
                            else -> R.mipmap.ic_warn
                        })
                        imageTintList = stateColorResource(R.color.white)
                    }
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.VERTICAL
                            updatePadding(horizontal = 20.dp, vertical = 10.dp)
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
                            text = stringResource(when {
                                YukiHookAPI.Status.isXposedModuleActive -> R.string.module_is_activated
                                else -> R.string.module_not_activated
                            })
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
                                alpha = 0.8f
                                isSingleLine = true
                                ellipsize = TextUtils.TruncateAt.END
                                textColor = colorResource(R.color.white)
                                textSize = 13f
                                text = stringResource(R.string.module_version, BuildConfig.VERSION_NAME)
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
                        TextView(
                            lparams = LayoutParams { 
                                topMargin = 5.dp
                            }
                        ) {
                            activationSourceView = this
                            alpha = 0.6f
                            isSingleLine = true
                            ellipsize = TextUtils.TruncateAt.END
                            textColor = colorResource(R.color.white)
                            textSize = 11f
                            text = if (YukiHookAPI.Status.Executor.apiLevel > 0)
                                stringResource(R.string.activated_by, YukiHookAPI.Status.Executor.name, YukiHookAPI.Status.Executor.apiLevel)
                            else stringResource(R.string.activated_by_noapi, YukiHookAPI.Status.Executor.name)
                            isVisible = YukiHookAPI.Status.isXposedModuleActive
                        }
                    }
                }
                NestedScrollView(
                    lparams = LayoutParams(matchParent = true) {
                        updateMargins(vertical = 10.dp)
                    },
                    init = {
                        isFillViewport = true
                        isVerticalFadingEdgeEnabled = true
                    }
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.VERTICAL
                        }
                    ) {
                        LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true) {
                                updateMargins(horizontal = 15.dp)
                            },
                            init = {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER or Gravity.START
                                background = roundedColor(monetColors.surfaceVariant)
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
                                background = roundedColor(monetColors.surfaceVariant)
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
                                background = roundedColor(monetColors.surfaceVariant)
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
                                background = roundedColor(monetColors.surfaceVariant)
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
                                text = stringResource(R.string.remove_home_recommend_vertical)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeHomeRecommendVertical
                                setOnCheckedChangeListener { _, checked ->
                                    removeHomeRecommendVertical = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL,
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
                            FrameLayout(
                                lparams = LayoutParams(widthMatchParent = true, height = 1.dp) {
                                    topMargin = 14.dp
                                    bottomMargin = 14.dp
                                },
                                init = {
                                    setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
                                }
                            )
                            // 分类：界面与提示
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 4.dp
                                }
                            ) {
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
                                    ruleSummary(mineComponentHiddenRules)
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
                                        R.string.custom_mine_component_hide_dialog_title,
                                        R.string.custom_mine_component_hide_hint,
                                        mineComponentHiddenRules
                                    ) { value ->
                                        mineComponentHiddenRules = value
                                        prefs().edit {
                                            putString(
                                                FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES,
                                                value
                                            )
                                        }
                                        mineComponentRulesSummaryView?.text =
                                            stringResource(R.string.custom_mine_component_hide) + "\n" +
                                                ruleSummary(value)
                                    }
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
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 14.dp
                                    bottomMargin = 4.dp
                                }
                            ) {
                                alpha = 0.7f
                                text = stringResource(R.string.story_purify_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    bottomMargin = 5.dp
                                }
                            ) {
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
                                alpha = 0.6f
                                setLineSpacing(6f, 1f)
                                text = stringResource(R.string.story_purify_tip)
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
                                text = stringResource(R.string.video_relate_filter_settings)
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 11f
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) { bottomMargin = 5.dp }
                            ) {
                                text = stringResource(R.string.remove_relate_commercial)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeRelateCommercial
                                setOnCheckedChangeListener { _, checked ->
                                    removeRelateCommercial = checked
                                    prefs().edit {
                                        putBoolean(
                                            FeaturePreferences.REMOVE_RELATE_COMMERCIAL,
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
                                text = stringResource(R.string.remove_relate_game)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeRelateGame
                                setOnCheckedChangeListener { _, checked ->
                                    removeRelateGame = checked
                                    prefs().edit {
                                        putBoolean(FeaturePreferences.REMOVE_RELATE_GAME, checked)
                                    }
                                }
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 8.dp
                                    bottomMargin = 5.dp
                                }
                            ) {
                                text = stringResource(R.string.remove_relate_live)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeRelateLive
                                setOnCheckedChangeListener { _, checked ->
                                    removeRelateLive = checked
                                    prefs().edit {
                                        putBoolean(FeaturePreferences.REMOVE_RELATE_LIVE, checked)
                                    }
                                }
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 8.dp
                                    bottomMargin = 5.dp
                                }
                            ) {
                                text = stringResource(R.string.remove_relate_course)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeRelateCourse
                                setOnCheckedChangeListener { _, checked ->
                                    removeRelateCourse = checked
                                    prefs().edit {
                                        putBoolean(FeaturePreferences.REMOVE_RELATE_COURSE, checked)
                                    }
                                }
                            }
                            MaterialSwitch(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = 8.dp
                                    bottomMargin = 5.dp
                                }
                            ) {
                                text = stringResource(R.string.remove_relate_special)
                                isAllCaps = false
                                textColor = colorResource(R.color.colorTextGray)
                                textSize = 15f
                                isChecked = removeRelateSpecial
                                setOnCheckedChangeListener { _, checked ->
                                    removeRelateSpecial = checked
                                    prefs().edit {
                                        putBoolean(FeaturePreferences.REMOVE_RELATE_SPECIAL, checked)
                                    }
                                }
                            }
                            TextView(
                                lparams = LayoutParams(widthMatchParent = true)
                            ) {
                                alpha = 0.6f
                                setLineSpacing(6f, 1f)
                                text = stringResource(R.string.video_relate_filter_tip)
                                textColor = colorResource(R.color.colorTextDark)
                                textSize = 12f
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
                                background = roundedColor(monetColors.surfaceVariant)
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
                                background = roundedColor(monetColors.surfaceVariant)
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
                                background = roundedColor(monetColors.surfaceVariant)
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

        // setContentView 返回后尚未进入首帧绘制，此时重排不会产生界面跳动。
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
                background = roundedColor(monetColors.surfaceVariant)
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
