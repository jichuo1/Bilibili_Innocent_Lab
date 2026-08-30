@file:Suppress(
    "SetTextI18n",
    // BetterAndroid 的简写 callback 不暴露 predictive progress/cancel 生命周期。
    "ReplaceWithBackPressedExtension",
    // 这里直接守卫 API 34 的公开 Activity transition，便于 Android Lint 识别 NewApi 边界。
    "ReplaceWithAndroidVersion"
)

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.BackupFormatError
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.BackupSource
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportPlan
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportPlanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsApplyResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupCodec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupFactory
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupFormatException
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportPlanner
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToLong

/** SAF 驱动的设置备份、兼容性预览和确认导入页面。 */
class SettingsBackupActivity : SkinnedActivity() {

    private enum class Page {
        HOME,
        WORKING,
        PREVIEW,
        RESULT,
        ERROR
    }

    private enum class BackTarget {
        NONE,
        FINISH_ACTIVITY,
        INTERNAL_HOME,
        BLOCKED
    }

    private val settingsStore by lazy { ModuleSettingsStore(prefs()) }
    private val backupViewModel by lazy {
        ViewModelProvider(this)[SettingsBackupViewModel::class.java]
    }
    private val planner = SettingsImportPlanner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "settings-backup-worker").apply { isDaemon = true }
    }

    private var activeFuture: Future<*>? = null
    private var operationGeneration = 0L
    private var page = Page.HOME
    private var busy = false
    private var pickerOpen = false
    private var importApplied = false
    private lateinit var motionHost: SettingsBackupMotionHost
    private var toolbarTitleView: TextView? = null
    private var currentPageTitle = ""
    private var launchOrigin: SettingsBackupTransitionOrigin? = null
    private var allowLaunchOriginForExit = true
    private var motionGeometry: SettingsBackupMotionGeometry? = null
    private var motionAnimator: ValueAnimator? = null
    private var motionTitleMode = SettingsBackupTransitionTitleMode.SOURCE_TITLE
    private var motionContentTiming = SettingsBackupContentTiming.TIMED
    private var backTarget = BackTarget.NONE
    private var gestureStartExpansion = 1f
    private var predictiveMotionActive = false
    private var finishingAfterMotion = false
    private var pageStretchViewport: View? = null

    private val enterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val closeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    private val cancelInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val predictiveBackInterpolator = PathInterpolator(0f, 0f, 0f, 1f)

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        pickerOpen = false
        if (uri != null) exportTo(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickerOpen = false
        if (uri != null) analyzeImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserTermsConsentStore.readOrInitialize(applicationContext).isAuthorized) {
            finish()
            return
        }
        prepareSkinSession()
        suppressSystemActivityTransitions()
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        launchOrigin = SettingsBackupTransitionOrigin.from(intent)
        allowLaunchOriginForExit = savedInstanceState == null
        motionHost = SettingsBackupMotionHost(
            context = this,
            collapsedSurfaceColor = if (isLiquidSkinEffective) {
                ColorUtils.setAlphaComponent(monetColors.surface, 0x74)
            } else monetColors.surfaceVariant,
            expandedSurfaceColor = if (isLiquidSkinEffective) {
                ColorUtils.setAlphaComponent(monetColors.surface, 0x28)
            } else monetColors.background,
            titleColor = getColor(R.color.colorTextGray),
            sourceTitle = getString(R.string.settings_backup_title)
        )
        motionHost.onWindowSizeChangedDuringMotion = ::handleMotionWindowSizeChange
        setContentView(motionHost)
        bindPreparedSkinRoot(motionHost.liquidBackdropRoot()) {
            if (!isFinishing && !isDestroyed) recreate()
        }
        applyPredictiveBackFromPrefs()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                beginPredictiveBack()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                progressPredictiveBack(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                cancelPredictiveBack()
            }

            override fun handleOnBackPressed() {
                commitBack()
            }
        })
        renderHome()
        scheduleInitialMotion(savedInstanceState == null)
        observeApplyState()
        mainHandler.post {
            if (backupViewModel.applyState.value !is SettingsImportApplyState.Idle) return@post
            when {
                backupViewModel.previewPlan != null ->
                    renderPreview(requireNotNull(backupViewModel.previewPlan))
                backupViewModel.lastImportUri != null ->
                    analyzeImport(requireNotNull(backupViewModel.lastImportUri))
            }
        }
    }

    override fun onDestroy() {
        finishPageStretch()
        pageStretchViewport = null
        cancelMotionAnimator()
        if (::motionHost.isInitialized) {
            motionHost.onWindowSizeChangedDuringMotion = null
        }
        operationGeneration += 1L
        activeFuture?.cancel(true)
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handleMotionWindowSizeChange() {
        cancelMotionAnimator()
        backTarget = BackTarget.NONE
        predictiveMotionActive = false
        motionContentTiming = SettingsBackupContentTiming.TIMED
        motionGeometry = null
        motionHost.showExpandedImmediately()
    }

    private fun handleBack() {
        when (resolveBackTarget()) {
            BackTarget.FINISH_ACTIVITY -> requestClose(interactiveCommit = false)
            BackTarget.INTERNAL_HOME -> returnToHome()
            BackTarget.BLOCKED,
            BackTarget.NONE -> Unit
        }
    }

    private fun beginPredictiveBack() {
        predictiveMotionActive = false
        backTarget = resolveBackTarget()
        if (backTarget != BackTarget.FINISH_ACTIVITY) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        cancelMotionAnimator()
        prepareExitMotion(SettingsBackupContentTiming.PREDICTIVE)
        gestureStartExpansion = motionHost.expansion
        predictiveMotionActive = true
    }

    private fun progressPredictiveBack(rawProgress: Float) {
        if (backTarget != BackTarget.FINISH_ACTIVITY || !predictiveMotionActive) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            predictiveMotionActive = false
            motionContentTiming = SettingsBackupContentTiming.TIMED
            motionGeometry = null
            motionHost.showExpandedImmediately()
            return
        }
        val progress = predictiveBackInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
        applyMotionExpansion(gestureStartExpansion * (1f - progress))
    }

    private fun cancelPredictiveBack() {
        if (backTarget == BackTarget.FINISH_ACTIVITY && predictiveMotionActive) {
            animateMotionTo(
                targetExpansion = 1f,
                durationMs = BACK_CANCEL_DURATION_MS,
                interpolator = cancelInterpolator
            ) {
                motionHost.showExpandedImmediately()
                motionGeometry = null
                motionContentTiming = SettingsBackupContentTiming.TIMED
            }
        }
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
    }

    private fun commitBack() {
        val target = if (backTarget == BackTarget.NONE) resolveBackTarget() else backTarget
        val hadInteractiveStart = predictiveMotionActive
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
        when (target) {
            BackTarget.FINISH_ACTIVITY -> requestClose(interactiveCommit = hadInteractiveStart)
            BackTarget.INTERNAL_HOME -> returnToHome()
            BackTarget.BLOCKED,
            BackTarget.NONE -> Unit
        }
    }

    private fun resolveBackTarget(): BackTarget = when {
        // 形变期间与触摸输入使用同一阻塞语义，避免在中间 expansion 从 TIMED profile
        // 切到 PREDICTIVE（或反向切换）造成正文 alpha 跳帧。
        motionAnimator != null || motionHost.expansion < 0.999f -> BackTarget.BLOCKED
        busy || pickerOpen || page == Page.WORKING -> BackTarget.BLOCKED
        importApplied || page == Page.HOME -> BackTarget.FINISH_ACTIVITY
        else -> BackTarget.INTERNAL_HOME
    }

    private fun returnToHome() {
        cancelMotionAnimator()
        predictiveMotionActive = false
        motionContentTiming = SettingsBackupContentTiming.TIMED
        motionHost.showExpandedImmediately()
        motionGeometry = null
        backupViewModel.clearSelection()
        renderHome()
    }

    private fun suppressSystemActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun scheduleInitialMotion(isFreshLaunch: Boolean) {
        val canResolveOrigin = launchOrigin != null ||
            SettingsBackupTransitionOriginRegistry.snapshot() != null
        val shouldAnimate = isFreshLaunch &&
            canResolveOrigin &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldAnimate) motionHost.prepareFirstFrameForEntry()
        motionHost.doOnPreDraw {
            if (!shouldAnimate) {
                motionHost.showExpandedImmediately()
                return@doOnPreDraw
            }
            val geometry = resolveMotionGeometry()
            if (geometry == null) {
                motionHost.showExpandedImmediately()
                return@doOnPreDraw
            }
            motionGeometry = geometry
            motionContentTiming = SettingsBackupContentTiming.TIMED
            motionTitleMode = if (geometry.titleMotionEnabled) {
                SettingsBackupTransitionTitleMode.SOURCE_TITLE
            } else {
                SettingsBackupTransitionTitleMode.HIDDEN
            }
            finishPageStretch()
            motionHost.beginMotion()
            motionHost.applyExpansion(
                geometry = geometry,
                value = 0f,
                titleMode = motionTitleMode,
                contentTiming = motionContentTiming
            )
            motionHost.post {
                if (isFinishing || isDestroyed) return@post
                animateMotionTo(
                    targetExpansion = 1f,
                    durationMs = ENTER_DURATION_MS,
                    interpolator = enterInterpolator
                ) {
                    motionHost.showExpandedImmediately()
                    motionGeometry = null
                    motionContentTiming = SettingsBackupContentTiming.TIMED
                }
            }
        }
    }

    private fun prepareExitMotion(contentTiming: SettingsBackupContentTiming) {
        finishPageStretch()
        motionGeometry = resolveMotionGeometry()
        motionContentTiming = contentTiming
        motionTitleMode = when {
            motionGeometry?.titleMotionEnabled != true ->
                SettingsBackupTransitionTitleMode.HIDDEN
            currentPageTitle == getString(R.string.settings_backup_title) ->
                SettingsBackupTransitionTitleMode.SOURCE_TITLE
            else -> SettingsBackupTransitionTitleMode.CROSSFADE_FROM_PAGE_TITLE
        }
        motionHost.beginMotion()
        applyMotionExpansion(motionHost.expansion)
    }

    private fun requestClose(interactiveCommit: Boolean) {
        if (finishingAfterMotion) return
        cancelMotionAnimator()
        if (!interactiveCommit) prepareExitMotion(SettingsBackupContentTiming.TIMED)
        val currentExpansion = motionHost.expansion
        if (!ValueAnimator.areAnimatorsEnabled() || currentExpansion <= 0.001f) {
            applyMotionExpansion(0f)
            finishAfterMotion()
            return
        }
        val baseDuration = if (interactiveCommit) {
            BACK_COMMIT_DURATION_MS
        } else {
            CLOSE_DURATION_MS
        }
        val duration = (baseDuration * currentExpansion)
            .roundToLong()
            .coerceIn(MIN_CLOSE_DURATION_MS, baseDuration)
        animateMotionTo(
            targetExpansion = 0f,
            durationMs = duration,
            interpolator = closeInterpolator,
            onEnd = ::finishAfterMotion
        )
    }

    private fun animateMotionTo(
        targetExpansion: Float,
        durationMs: Long,
        interpolator: android.animation.TimeInterpolator,
        onEnd: () -> Unit
    ) {
        cancelMotionAnimator()
        val startExpansion = motionHost.expansion
        if (
            !ValueAnimator.areAnimatorsEnabled() ||
            durationMs <= 0L ||
            abs(startExpansion - targetExpansion) <= 0.001f
        ) {
            applyMotionExpansion(targetExpansion)
            onEnd()
            return
        }
        val animator = ValueAnimator.ofFloat(startExpansion, targetExpansion)
        motionAnimator = animator
        animator.duration = durationMs
        animator.interpolator = interpolator
        animator.addUpdateListener { valueAnimator ->
            applyMotionExpansion(valueAnimator.animatedValue as Float)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (motionAnimator === animator) motionAnimator = null
                if (!cancelled && !isDestroyed) onEnd()
            }
        })
        animator.start()
    }

    private fun applyMotionExpansion(expansion: Float) {
        val geometry = motionGeometry
        if (geometry != null) {
            motionHost.applyExpansion(
                geometry = geometry,
                value = expansion,
                titleMode = motionTitleMode,
                contentTiming = motionContentTiming
            )
        } else {
            motionHost.applyFallbackExpansion(
                value = expansion,
                contentTravelPx = dp(CONTENT_TRAVEL_DP),
                contentTiming = motionContentTiming
            )
            motionHost.blockInteraction(true)
        }
    }

    private fun finishAfterMotion() {
        if (finishingAfterMotion || isFinishing || isDestroyed) return
        finishingAfterMotion = true
        motionHost.blockInteraction(true)
        finish()
        suppressLegacyCloseTransition()
    }

    @Suppress("DEPRECATION")
    private fun suppressLegacyCloseTransition() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            overridePendingTransition(0, 0)
        }
    }

    private fun cancelMotionAnimator() {
        val animator = motionAnimator ?: return
        motionAnimator = null
        animator.removeAllUpdateListeners()
        animator.removeAllListeners()
        animator.cancel()
    }

    private fun resolveMotionGeometry(): SettingsBackupMotionGeometry? {
        if (motionHost.width <= 0 || motionHost.height <= 0) return null
        val display = motionHost.display ?: return null
        val tolerancePx = dp(4)
        val originCandidates = if (allowLaunchOriginForExit) {
            // 新鲜实例的 Intent 与本次 launch 一一对应，不能被另一 MainActivity 实例覆盖。
            sequenceOf(launchOrigin, SettingsBackupTransitionOriginRegistry.snapshot())
        } else {
            // Activity 重建后不再信任旧 Intent 坐标，只接受主界面当前弱引用快照。
            sequenceOf(SettingsBackupTransitionOriginRegistry.snapshot())
        }
        val origin = originCandidates.filterNotNull().firstOrNull { candidate ->
            candidate.displayId == display.displayId &&
                candidate.displayRotation == display.rotation &&
                abs(candidate.sourceWindowWidth - motionHost.width) <= tolerancePx &&
                abs(candidate.sourceWindowHeight - motionHost.height) <= tolerancePx
        } ?: return null

        val hostLocation = IntArray(2)
        motionHost.getLocationOnScreen(hostLocation)
        val collapsedBounds = origin.cardBoundsOnScreen.toLocal(hostLocation)
        val collapsedTitleBounds = origin.titleBoundsOnScreen.toLocal(hostLocation)
        val expandedBounds = SettingsBackupMotionRect(
            left = 0f,
            top = 0f,
            right = motionHost.width.toFloat(),
            bottom = motionHost.height.toFloat()
        )
        val destinationTitle = toolbarTitleView ?: return null
        if (destinationTitle.width <= 0 || destinationTitle.height <= 0) return null
        val expandedTitleBounds = destinationTitle.boundsOnScreen().toLocal(hostLocation)

        val withinWindow = collapsedBounds.left >= -tolerancePx &&
            collapsedBounds.top >= -tolerancePx &&
            collapsedBounds.right <= expandedBounds.right + tolerancePx &&
            collapsedBounds.bottom <= expandedBounds.bottom + tolerancePx
        val titleWithinWindow = collapsedTitleBounds.left >= -tolerancePx &&
            collapsedTitleBounds.top >= -tolerancePx &&
            collapsedTitleBounds.right <= expandedBounds.right + tolerancePx &&
            collapsedTitleBounds.bottom <= expandedBounds.bottom + tolerancePx
        if (
            !collapsedBounds.isValid ||
            !collapsedTitleBounds.isValid ||
            !expandedTitleBounds.isValid ||
            !withinWindow ||
            !titleWithinWindow
        ) {
            return null
        }
        return SettingsBackupMotionGeometry(
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitleBounds,
            expandedTitleBounds = expandedTitleBounds,
            collapsedTitleTextSizePx = origin.titleTextSizePx,
            expandedTitleTextSizePx = destinationTitle.textSize,
            collapsedCornerRadiusPx = dp(SOURCE_CORNER_RADIUS_DP),
            contentTravelPx = dp(CONTENT_TRAVEL_DP),
            titleMotionEnabled = SettingsBackupMotionSpec.canMoveTitle(
                collapsedLineCount = origin.titleLineCount,
                expandedLineCount = destinationTitle.lineCount,
                collapsedIsLeftToRight =
                    origin.titleLayoutDirection == View.LAYOUT_DIRECTION_LTR,
                expandedIsLeftToRight =
                    destinationTitle.layoutDirection == View.LAYOUT_DIRECTION_LTR
            )
        )
    }

    private fun SettingsBackupMotionRect.toLocal(
        hostLocation: IntArray
    ): SettingsBackupMotionRect = SettingsBackupMotionRect(
        left = left - hostLocation[0],
        top = top - hostLocation[1],
        right = right - hostLocation[0],
        bottom = bottom - hostLocation[1]
    )

    private fun View.boundsOnScreen(): SettingsBackupMotionRect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return SettingsBackupMotionRect(
            left = location[0].toFloat(),
            top = location[1].toFloat(),
            right = (location[0] + width).toFloat(),
            bottom = (location[1] + height).toFloat()
        )
    }

    private fun chooseExportLocation() {
        if (busy || pickerOpen) return
        pickerOpen = true
        try {
            createDocumentLauncher.launch(defaultBackupFileName())
        } catch (_: ActivityNotFoundException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        } catch (_: RuntimeException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        }
    }

    private fun chooseImportFile() {
        if (busy || pickerOpen) return
        pickerOpen = true
        try {
            openDocumentLauncher.launch(
                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
            )
        } catch (_: ActivityNotFoundException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        } catch (_: RuntimeException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        }
    }

    private fun exportTo(uri: Uri) {
        runOperation(R.string.settings_backup_working_export, operation = {
            val document = SettingsBackupFactory.createDocument(
                reader = settingsStore,
                source = BackupSource(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    applicationId = BuildConfig.APPLICATION_ID
                )
            )
            val bytes = SettingsBackupCodec.encodeToBytes(document)
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Document provider returned no output stream")
            output.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            val readBack = readLimited(uri)
            val decoded = SettingsBackupCodec.decode(readBack)
            val expected = document.copy(settings = document.settings.sortedBy { it.id })
            if (decoded != expected) {
                throw IOException("Backup read-back differs from the written document")
            }
            Unit
        }) {
            renderHome(R.string.settings_backup_export_success)
        }
    }

    private fun analyzeImport(uri: Uri) {
        backupViewModel.selectImport(uri)
        runOperation(R.string.settings_backup_working_read, operation = {
            val document = SettingsBackupCodec.decode(readLimited(uri))
            planner.plan(document, settingsStore.snapshot())
        }) { plan ->
            backupViewModel.setPreview(plan)
            renderPreview(plan)
        }
    }

    private fun applyImport() {
        val plan = backupViewModel.previewPlan ?: return
        if (!plan.canApply) {
            renderHome(R.string.settings_backup_nothing_to_apply)
            return
        }
        backupViewModel.apply(applicationContext, settingsStore, plan)
    }

    private fun observeApplyState() {
        backupViewModel.applyState.observe(this) { state ->
            when (state) {
                SettingsImportApplyState.Idle -> Unit
                is SettingsImportApplyState.Running -> {
                    busy = true
                    renderWorking(R.string.settings_backup_working_apply)
                }
                is SettingsImportApplyState.Finished -> {
                    busy = false
                    handleApplyResult(state.plan, state.result)
                }
                is SettingsImportApplyState.Failed -> {
                    busy = false
                    renderOperationError(state.throwable)
                }
            }
        }
    }

    private fun handleApplyResult(plan: ImportPlan, result: SettingsApplyResult) {
        when (result) {
            is SettingsApplyResult.Success -> {
                importApplied = true
                val restartRecommended = ImportEffect.RESTART_BILIBILI in result.effects
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_IMPORT_OUTCOME, OUTCOME_VERIFIED)
                        .putExtra(EXTRA_CHANGED_COUNT, result.changedCount)
                        .putExtra(EXTRA_RESTART_RECOMMENDED, restartRecommended)
                )
                if (ImportEffect.REAPPLY_PREDICTIVE_BACK in result.effects) {
                    applyPredictiveBackFromPrefs()
                }
                renderResult(result.changedCount, restartRecommended)
            }
            SettingsApplyResult.NothingToApply -> {
                backupViewModel.clearSelection()
                renderHome(R.string.settings_backup_nothing_to_apply)
            }
            SettingsApplyResult.StalePlan ->
                renderError(R.string.settings_backup_error_stale, allowChooseAgain = true)
            SettingsApplyResult.CommitFailed ->
                renderError(R.string.settings_backup_error_commit, allowChooseAgain = true)
            SettingsApplyResult.ReadBackFailed -> {
                markPossiblyChanged(plan)
                renderError(R.string.settings_backup_error_readback, allowChooseAgain = false)
            }
            SettingsApplyResult.DerivedStatePending -> {
                markPossiblyChanged(plan)
                renderError(R.string.settings_backup_error_derived_pending, allowChooseAgain = false)
            }
        }
    }

    private fun markPossiblyChanged(plan: ImportPlan) {
        importApplied = true
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_IMPORT_OUTCOME, OUTCOME_POSSIBLY_CHANGED)
                .putExtra(EXTRA_CHANGED_COUNT, plan.writes.size)
                .putExtra(
                    EXTRA_RESTART_RECOMMENDED,
                    ImportEffect.RESTART_BILIBILI in plan.effects
                )
        )
    }

    private fun <T> runOperation(
        workingMessageRes: Int,
        operation: () -> T,
        onSuccess: (T) -> Unit
    ) {
        if (busy) return
        busy = true
        val generation = ++operationGeneration
        renderWorking(workingMessageRes)
        activeFuture = worker.submit {
            val result = runCatching(operation)
            mainHandler.post {
                if (generation != operationGeneration || isFinishing || isDestroyed) return@post
                busy = false
                result.fold(
                    onSuccess = onSuccess,
                    onFailure = ::renderOperationError
                )
            }
        }
    }

    private fun renderOperationError(throwable: Throwable) {
        val message = when ((throwable as? SettingsBackupFormatException)?.error) {
            BackupFormatError.TOO_LARGE -> R.string.settings_backup_error_too_large
            BackupFormatError.INVALID_INTEGRITY -> R.string.settings_backup_error_integrity
            BackupFormatError.WRONG_PRODUCT -> R.string.settings_backup_error_product
            BackupFormatError.UNSUPPORTED_FORMAT -> R.string.settings_backup_error_format
            BackupFormatError.INVALID_UTF8,
            BackupFormatError.INVALID_JSON,
            BackupFormatError.INVALID_STRUCTURE -> R.string.settings_backup_error_invalid_file
            else -> R.string.settings_backup_error_generic
        }
        renderError(message, allowChooseAgain = backupViewModel.lastImportUri != null)
    }

    private fun readLimited(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Document provider returned no input stream")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > SettingsBackupCodec.MAX_FILE_BYTES) {
                    throw SettingsBackupFormatException(
                        BackupFormatError.TOO_LARGE,
                        "Backup exceeds the size limit"
                    )
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun applyPredictiveBackFromPrefs() {
        val enabled = runCatching {
            settingsStore.read(requireNotNull(SettingsCatalog.byId["module_ui.predictive_back.enabled"]))
        }.getOrNull()?.value.let { (it as? SettingValue.Bool)?.value ?: false }
        PredictiveBack.apply(window, enabled)
    }

    private fun renderHome(statusRes: Int? = null) {
        page = Page.HOME
        busy = false
        setScaffold(getString(R.string.settings_backup_title)) { content ->
            statusRes?.let { message ->
                content.addView(infoCard(getString(message), monetColors.primary))
                content.addView(verticalSpace(12))
            }
            content.addView(
                operationCard(
                    title = getString(R.string.settings_backup_export_title),
                    summary = getString(R.string.settings_backup_export_summary),
                    action = getString(R.string.settings_backup_export_action),
                    onClick = ::chooseExportLocation
                )
            )
            content.addView(verticalSpace(12))
            content.addView(
                operationCard(
                    title = getString(R.string.settings_backup_import_title),
                    summary = getString(R.string.settings_backup_import_summary),
                    action = getString(R.string.settings_backup_import_action),
                    onClick = ::chooseImportFile
                )
            )
            content.addView(verticalSpace(12))

            val automaticCount = SettingsCatalog.specs.count {
                it.restorePolicy == RestorePolicy.AUTOMATIC
            }
            val scope = card().apply {
                addView(titleText(getString(R.string.settings_backup_scope_title)))
                addView(bodyText(getString(
                    R.string.settings_backup_scope_included,
                    automaticCount,
                    SettingsCatalog.specs.size
                )).withTopMargin(9))
                addView(bodyText(getString(R.string.settings_backup_scope_manual)).withTopMargin(8))
                addView(bodyText(getString(R.string.settings_backup_scope_excluded)).withTopMargin(8))
                addView(bodyText(getString(R.string.settings_backup_integrity_note)).withTopMargin(8).apply {
                    alpha = 0.65f
                })
            }
            content.addView(scope)
        }
    }

    private fun renderWorking(messageRes: Int) {
        page = Page.WORKING
        setScaffold(getString(R.string.settings_backup_title)) { content ->
            val card = card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ProgressBar(this@SettingsBackupActivity).apply {
                    isIndeterminate = true
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(bodyText(getString(messageRes)).withTopMargin(16).apply {
                    gravity = Gravity.CENTER
                })
            }
            content.addView(card)
        }
    }

    private fun renderPreview(plan: ImportPlan) {
        page = Page.PREVIEW
        val restorable = plan.entries.filter(ImportPlanEntry::willWrite)
        val unchanged = plan.entries.filter { it.status == ImportStatus.UNCHANGED }
        val keep = plan.entries.filter {
            it.status in setOf(
                ImportStatus.SOURCE_DEFAULT_SKIPPED,
                ImportStatus.NEW_IN_CURRENT
            )
        }
        val attention = plan.entries.filter {
            !it.willWrite && it.status !in setOf(
                ImportStatus.UNCHANGED,
                ImportStatus.SOURCE_DEFAULT_SKIPPED,
                ImportStatus.NEW_IN_CURRENT
            )
        }

        setScaffold(getString(R.string.settings_backup_preview_title)) { content ->
            content.addView(card().apply {
                addView(titleText(getString(
                    R.string.settings_backup_source_version,
                    previewSafeText(plan.source.source.versionName),
                    plan.source.source.versionCode
                )))
                addView(bodyText(getString(
                    R.string.settings_backup_source_time,
                    DateFormat.getDateTimeInstance().format(Date(plan.source.createdAtEpochMs))
                )).withTopMargin(8))
                addView(bodyText(getString(
                    R.string.settings_backup_catalog_versions,
                    plan.source.catalogVersion,
                    SettingsCatalog.CATALOG_VERSION
                )).withTopMargin(6))
            })
            content.addView(verticalSpace(12))
            content.addView(card().apply {
                addView(titleText(getString(R.string.settings_backup_preview_title)))
                addView(bodyText(getString(R.string.settings_backup_preview_restore_count, restorable.size)).withTopMargin(9))
                addView(bodyText(getString(R.string.settings_backup_preview_unchanged_count, unchanged.size)).withTopMargin(5))
                addView(bodyText(getString(R.string.settings_backup_preview_keep_count, keep.size)).withTopMargin(5))
                addView(bodyText(getString(R.string.settings_backup_preview_attention_count, attention.size)).withTopMargin(5))
                if (plan.blockers.isNotEmpty()) {
                    addView(bodyText(getString(R.string.settings_backup_error_format)).withTopMargin(9).apply {
                        setTextColor(monetColors.tertiary)
                    })
                }
                if (plan.migrationWarnings.isNotEmpty()) {
                    addView(bodyText(getString(R.string.settings_backup_migration_warning)).withTopMargin(9).apply {
                        setTextColor(monetColors.tertiary)
                    })
                }
            })
            addEntryGroup(content, R.string.settings_backup_group_restore, restorable, showChanges = true)
            addEntryGroup(content, R.string.settings_backup_group_keep, keep, showChanges = false)
            addEntryGroup(content, R.string.settings_backup_group_attention, attention, showChanges = true)

            content.addView(verticalSpace(14))
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(secondaryButton(getString(R.string.dialog_cancel)) {
                    backupViewModel.clearSelection()
                    renderHome()
                }.fillWidth())
                if (plan.canApply) {
                    addView(primaryButton(
                        getString(R.string.settings_backup_apply_count, plan.writes.size)
                    ) {
                        applyImport()
                    }.fillWidth().withTopMargin(10))
                }
            }
            content.addView(buttons)
        }
    }

    private fun addEntryGroup(
        content: LinearLayout,
        titleRes: Int,
        entries: List<ImportPlanEntry>,
        showChanges: Boolean
    ) {
        if (entries.isEmpty()) return
        content.addView(verticalSpace(12))
        content.addView(card().apply {
            addView(titleText(getString(titleRes)))
            entries.forEachIndexed { index, entry ->
                addView(entryView(entry, showChanges).withTopMargin(if (index == 0) 10 else 7))
            }
        })
    }

    private fun entryView(entry: ImportPlanEntry, showChanges: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = skinCardBackground(monetColors.surface, 11f)
        }
        val label = entry.spec?.let { getString(it.labelRes) } ?: previewSafeText(entry.id)
        row.addView(bodyText(label).apply {
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.92f
        })
        row.addView(bodyText(getString(statusText(entry.status))).withTopMargin(3).apply {
            alpha = 0.68f
        })
        if (showChanges && entry.proposed != null) {
            row.addView(bodyText(getString(
                R.string.settings_backup_value_change,
                valueSummary(entry.spec, entry.current?.value),
                valueSummary(entry.spec, entry.proposed)
            )).withTopMargin(4).apply {
                alpha = 0.72f
            })
        } else if (
            showChanges &&
            entry.source != null &&
            entry.current != null &&
            entry.status in setOf(
                ImportStatus.MANUAL_REQUIRED,
                ImportStatus.UNKNOWN_FROM_NEWER,
                ImportStatus.INVALID_VALUE
            )
        ) {
            row.addView(bodyText(getString(
                R.string.settings_backup_value_backup_current,
                valueSummary(entry.spec, entry.source.value),
                valueSummary(entry.spec, entry.current.value)
            )).withTopMargin(4).apply {
                alpha = 0.72f
            })
        }
        return row
    }

    private fun renderResult(changedCount: Int, restartRecommended: Boolean) {
        page = Page.RESULT
        setScaffold(getString(R.string.settings_backup_result_title)) { content ->
            content.addView(card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(titleText(getString(R.string.settings_backup_result_title)).apply {
                    gravity = Gravity.CENTER
                })
                addView(bodyText(getString(R.string.settings_backup_result_summary, changedCount)).withTopMargin(10).apply {
                    gravity = Gravity.CENTER
                })
                if (restartRecommended) {
                    addView(bodyText(getString(R.string.settings_backup_restart_tip)).withTopMargin(10).apply {
                        gravity = Gravity.CENTER
                    })
                }
                addView(primaryButton(getString(R.string.settings_backup_finish)) { handleBack() }.withTopMargin(18))
            })
        }
    }

    private fun renderError(messageRes: Int, allowChooseAgain: Boolean) {
        page = Page.ERROR
        setScaffold(getString(R.string.settings_backup_error_title)) { content ->
            content.addView(card().apply {
                addView(titleText(getString(R.string.settings_backup_error_title)))
                addView(bodyText(getString(messageRes)).withTopMargin(10))
                val buttons = LinearLayout(this@SettingsBackupActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(secondaryButton(getString(R.string.dialog_close)) {
                        handleBack()
                    }.fillWidth())
                    if (allowChooseAgain) {
                        addView(primaryButton(getString(R.string.settings_backup_choose_again)) {
                            chooseImportFile()
                        }.fillWidth().withTopMargin(10))
                    }
                }
                addView(buttons.withTopMargin(18))
            })
        }
    }

    private fun setScaffold(title: String, populate: (LinearLayout) -> Unit) {
        finishPageStretch()
        if (motionAnimator != null || motionHost.expansion < 0.999f) {
            cancelMotionAnimator()
            motionHost.showExpandedImmediately()
            motionGeometry = null
        }
        predictiveMotionActive = false
        motionContentTiming = SettingsBackupContentTiming.TIMED
        backTarget = BackTarget.NONE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ViewCompat.setAccessibilityPaneTitle(root, title)
        root.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
            background = skinCardBackground(monetColors.surfaceVariant, 0f)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            contentDescription = getString(R.string.settings_backup_back)
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(getColor(R.color.colorTextGray))
            isClickable = true
            isFocusable = true
            background = ripple(monetColors.surfaceVariant, 24f)
            setOnClickListener { handleBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val toolbarTitle = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(getColor(R.color.colorTextGray))
            setTypeface(typeface, Typeface.BOLD)
        }
        toolbar.addView(
            toolbarTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(toolbar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(22))
        }
        populate(content)
        val scrollView = NestedScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        pageStretchViewport = installPreparedLiquidStretch(
            scrollTarget = scrollView,
            overlayColor = ColorUtils.setAlphaComponent(monetColors.surface, 0x28)
        ) {
            motionAnimator == null &&
                ::motionHost.isInitialized &&
                motionHost.expansion >= 0.999f &&
                !predictiveMotionActive &&
                !finishingAfterMotion
        }
        toolbarTitleView = toolbarTitle
        currentPageTitle = title
        motionHost.replacePage(root, toolbarTitle)
    }

    private fun finishPageStretch() {
        finishPreparedLiquidStretch(pageStretchViewport)
    }

    private fun operationCard(
        title: String,
        summary: String,
        action: String,
        onClick: () -> Unit
    ): View = card().apply {
        addView(titleText(title))
        addView(bodyText(summary).withTopMargin(8))
        addView(primaryButton(action) { onClick() }.withTopMargin(14))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = skinCardBackground(monetColors.surfaceVariant, 15f)
    }

    private fun infoCard(message: String, accent: Int): View = card().apply {
        background = skinCardBackground(
            ColorUtils.blendARGB(monetColors.surfaceVariant, accent, 0.14f),
            15f
        )
        addView(bodyText(message))
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(R.color.colorTextGray))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun bodyText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(getColor(R.color.colorTextDark))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(monetColors.onPrimary)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = ripple(monetColors.primary, 14f)
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(getColor(R.color.colorTextGray))
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = ripple(monetColors.surface, 14f)
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp)
        setColor(color)
    }

    private fun ripple(color: Int, radiusDp: Float): RippleDrawable {
        val mask = rounded(0xFFFFFFFF.toInt(), radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.primary, 0x33)),
            rounded(color, radiusDp),
            mask
        )
    }

    private fun <T : View> T.withTopMargin(marginDp: Int): T = apply {
        val current = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (current ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )).apply { topMargin = dp(marginDp) }
    }

    private fun <T : View> T.fillWidth(): T = apply {
        val current = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (current ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )).apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
    }

    private fun verticalSpace(heightDp: Int): View = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun statusText(status: ImportStatus): Int = when (status) {
        ImportStatus.EXACT -> R.string.settings_backup_status_exact
        ImportStatus.MIGRATED -> R.string.settings_backup_status_migrated
        ImportStatus.UNCHANGED -> R.string.settings_backup_status_unchanged
        ImportStatus.SOURCE_DEFAULT_SKIPPED -> R.string.settings_backup_status_source_default
        ImportStatus.NEW_IN_CURRENT -> R.string.settings_backup_status_new
        ImportStatus.MISSING_FROM_SOURCE -> R.string.settings_backup_status_missing
        ImportStatus.REMOVED -> R.string.settings_backup_status_removed
        ImportStatus.UNKNOWN_FROM_NEWER -> R.string.settings_backup_status_future
        ImportStatus.MANUAL_REQUIRED -> R.string.settings_backup_status_manual
        ImportStatus.INVALID_VALUE -> R.string.settings_backup_status_invalid
        ImportStatus.CONFLICT -> R.string.settings_backup_status_conflict
    }

    private fun valueSummary(spec: SettingSpec?, value: SettingValue?): String = when {
        spec?.id == ID_PLAYER_DEFAULT_QUALITY && value is SettingValue.IntValue ->
            playerQualityLabel(value.value)
        spec?.id == ID_LOG_LEVEL && value is SettingValue.Text -> when (value.value) {
            HookEntry.LOG_LEVEL_MINIMAL -> getString(R.string.log_level_minimal)
            HookEntry.LOG_LEVEL_COMPLETE -> getString(R.string.log_level_complete)
            else -> previewSafeText(value.value)
        }
        else -> rawValueSummary(value)
    }

    private fun rawValueSummary(value: SettingValue?): String = when (value) {
        null -> getString(R.string.settings_backup_value_empty)
        is SettingValue.Bool -> getString(
            if (value.value) R.string.settings_backup_value_on else R.string.settings_backup_value_off
        )
        is SettingValue.IntValue -> value.value.toString()
        is SettingValue.Text -> previewSafeText(value.value)
            .ifEmpty { getString(R.string.settings_backup_value_empty) }
            .let { if (it.length <= 80) it else it.take(79) + "…" }
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

    /** 移除双向/不可见格式控制符，并把可见预览折叠为单行。 */
    private fun previewSafeText(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val type = Character.getType(character)
            when {
                character.isWhitespace() -> append(' ')
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                else -> append(character)
            }
        }
    }.replace(Regex(" +"), " ").trim()

    private fun defaultBackupFileName(): String {
        val version = BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "Bilibili-Innocent-Lab-settings-$version-$timestamp.json"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val EXTRA_IMPORT_OUTCOME = "import_outcome"
        const val EXTRA_CHANGED_COUNT = "changed_count"
        const val EXTRA_RESTART_RECOMMENDED = "restart_recommended"
        const val OUTCOME_VERIFIED = "verified"
        const val OUTCOME_POSSIBLY_CHANGED = "possibly_changed"
        private const val ENTER_DURATION_MS = 370L
        private const val CLOSE_DURATION_MS = 270L
        private const val BACK_COMMIT_DURATION_MS = 160L
        private const val BACK_CANCEL_DURATION_MS = 210L
        private const val MIN_CLOSE_DURATION_MS = 60L
        private const val SOURCE_CORNER_RADIUS_DP = 15f
        private const val CONTENT_TRAVEL_DP = 12f
        private const val ID_PLAYER_DEFAULT_QUALITY = "player.default_quality.qn"
        private const val ID_LOG_LEVEL = "diagnostics.logging.level"
    }
}
