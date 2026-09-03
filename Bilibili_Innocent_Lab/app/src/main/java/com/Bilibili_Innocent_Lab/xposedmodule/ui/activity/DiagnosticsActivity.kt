@file:Suppress(
    "SetTextI18n",
    // 本页刻意使用 Android 原生 View/Handler/Toast，避免为只读诊断 UI 引入另一套调用范式。
    "ReplaceWithCoroutinesExtension",
    "ReplaceWithKavaRefExtension",
    "ReplaceWithTextViewExtension",
    "ReplaceWithToastExtension",
    // 诊断页需要完整接收 predictive back 的 start/progress/cancel/commit 生命周期。
    "ReplaceWithBackPressedExtension",
    // API 34 的公开 transition override 需要显式版本守卫，便于 Android Lint 识别。
    "ReplaceWithAndroidVersion"
)

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticActivationState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostFeature
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostQueryState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticItem
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticItemId
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticNoRootState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticRemotePublishState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticSeverity
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 只读、本地优先的统一诊断中心。 */
class DiagnosticsActivity : SkinnedActivity() {
    private companion object {
        const val FRAMEWORK_STATUS_SETTLE_MS = 1_500L
        const val ENTER_DURATION_MS = 370L
        const val CLOSE_DURATION_MS = 320L
        const val BACK_COMMIT_DURATION_MS = 210L
        const val BACK_CANCEL_DURATION_MS = 210L
        const val MIN_CLOSE_DURATION_MS = 80L
        const val CONTENT_TRAVEL_DP = 12f
    }

    private enum class BackTarget {
        NONE,
        FINISH_ACTIVITY,
        BLOCKED
    }

    private enum class MotionState {
        PREPARING_ENTRY,
        ENTERING,
        EXPANDED,
        PREDICTIVE_BACK,
        CANCELLING_BACK,
        CLOSING,
        FINISHED
    }

    private val viewModel by lazy {
        ViewModelProvider(this)[DiagnosticsViewModel::class.java]
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameworkCheckPending = true
    private var frameworkServiceObserved = false
    private var currentSnapshot: ModuleDiagnosticSnapshot? = null
    private var activeDialog: Dialog? = null
    private var stretchViewport: View? = null
    private var stretchScrollTarget: View? = null
    private lateinit var motionHost: SettingsBackupMotionHost
    private var toolbarTitleView: TextView? = null
    private var launchOrigin: DiagnosticsTransitionOrigin? = null
    private var allowLaunchOriginForExit = true
    private var motionGeometry: SettingsBackupMotionGeometry? = null
    private var motionAnimator: ValueAnimator? = null
    private var motionTitleMode = SettingsBackupTransitionTitleMode.SOURCE_TITLE
    private var motionContentTiming = SettingsBackupContentTiming.TIMED
    private var motionState = MotionState.PREPARING_ENTRY
    private var backTarget = BackTarget.NONE
    private var gestureStartExpansion = 1f
    private var predictiveMotionActive = false
    private var finishingAfterMotion = false
    private var pickerOpen = false
    private var exportRunning = false
    private var pendingScreenState: DiagnosticsScreenState? = null
    private lateinit var content: LinearLayout
    private lateinit var refreshButton: TextView
    private lateinit var exportButton: TextView

    private val enterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val closeInterpolator = PathInterpolator(
        SettingsBackupMotionSpec.CLOSE_EASING_X1,
        SettingsBackupMotionSpec.CLOSE_EASING_Y1,
        SettingsBackupMotionSpec.CLOSE_EASING_X2,
        SettingsBackupMotionSpec.CLOSE_EASING_Y2
    )
    private val commitInterpolator = PathInterpolator(
        SettingsBackupMotionSpec.COMMIT_EASING_X1,
        SettingsBackupMotionSpec.COMMIT_EASING_Y1,
        SettingsBackupMotionSpec.COMMIT_EASING_X2,
        SettingsBackupMotionSpec.COMMIT_EASING_Y2
    )
    private val cancelInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val predictiveBackInterpolator = PathInterpolator(0f, 0f, 0f, 1f)
    private val modalExitInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private val frameworkTimeout = Runnable {
        frameworkCheckPending = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshDiagnostics()
    }
    private val frameworkListener = ModernFrameworkStatusListener { status ->
        mainHandler.post {
            if (status.connected) {
                frameworkServiceObserved = true
                frameworkCheckPending = false
                mainHandler.removeCallbacks(frameworkTimeout)
            } else if (frameworkServiceObserved) {
                frameworkCheckPending = false
                mainHandler.removeCallbacks(frameworkTimeout)
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshDiagnostics()
            }
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        pickerOpen = false
        val snapshot = currentSnapshot
        if (uri != null && snapshot != null) viewModel.export(applicationContext, uri, snapshot)
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
        launchOrigin = DiagnosticsTransitionOrigin.from(intent)
        allowLaunchOriginForExit = savedInstanceState == null
        val transitionSurfaceColor = if (isLiquidSkinEffective) {
            ColorUtils.setAlphaComponent(monetColors.surface, 0x28)
        } else monetColors.background
        val sourceNeutralColor = getColor(R.color.colorTextGray)
        val darkTheme = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val collapsedSurfaceColor = ColorUtils.setAlphaComponent(
            sourceNeutralColor,
            DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme)
        )
        motionHost = SettingsBackupMotionHost(
            context = this,
            collapsedSurfaceColor = collapsedSurfaceColor,
            expandedSurfaceColor = transitionSurfaceColor,
            titleColor = sourceNeutralColor,
            sourceTitle = getString(R.string.diagnostics_title),
            surfaceHandoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION,
            collapsedStrokeColor = ColorUtils.setAlphaComponent(
                sourceNeutralColor,
                DiagnosticsEntryVisualSpec.STROKE_ALPHA
            ),
            collapsedStrokeWidthPx = DiagnosticsEntryVisualSpec.STROKE_WIDTH_DP *
                resources.displayMetrics.density
        )
        motionHost.setLiquidMotionSurfaceBackground(
            liquidMotionSurfaceBackgroundOrNull(
                collapsedSurfaceColor,
                DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP
            )
        )
        motionHost.onWindowSizeChangedDuringMotion = ::handleMotionWindowSizeChange
        PredictiveBack.apply(
            window,
            prefs().getBoolean(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, false)
        )
        val root = buildRoot()
        setContentView(motionHost)
        motionHost.replacePage(root, requireNotNull(toolbarTitleView))
        bindPreparedSkinRoot(motionHost.liquidBackdropRoot()) {
            if (!isFinishing && !isDestroyed) recreate()
        }
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
        renderLoading()
        observeState()
        scheduleInitialMotion(savedInstanceState == null)
    }

    override fun onStart() {
        super.onStart()
        val framework = RemoteHookConfigStore.status()
        frameworkServiceObserved = frameworkServiceObserved || framework.connected
        frameworkCheckPending = !framework.connected && !frameworkServiceObserved
        mainHandler.removeCallbacks(frameworkTimeout)
        if (frameworkCheckPending) {
            mainHandler.postDelayed(frameworkTimeout, FRAMEWORK_STATUS_SETTLE_MS)
        }
        RemoteHookConfigStore.addStatusListener(frameworkListener)
        refreshDiagnostics()
    }

    override fun onStop() {
        RemoteHookConfigStore.removeStatusListener(frameworkListener)
        mainHandler.removeCallbacks(frameworkTimeout)
        super.onStop()
    }

    override fun onDestroy() {
        RemoteHookConfigStore.removeStatusListener(frameworkListener)
        mainHandler.removeCallbacksAndMessages(null)
        activeDialog?.dismiss()
        activeDialog = null
        finishPageStretch()
        stretchViewport = null
        stretchScrollTarget = null
        cancelMotionAnimator()
        if (::motionHost.isInitialized) {
            motionHost.onWindowSizeChangedDuringMotion = null
        }
        super.onDestroy()
    }

    private fun handleMotionWindowSizeChange() {
        cancelMotionAnimator()
        backTarget = BackTarget.NONE
        predictiveMotionActive = false
        motionGeometry = null
        completeExpandedMotion()
    }

    private fun beginPredictiveBack() {
        predictiveMotionActive = false
        backTarget = resolveBackTarget()
        if (backTarget != BackTarget.FINISH_ACTIVITY || !ValueAnimator.areAnimatorsEnabled()) {
            return
        }
        cancelMotionAnimator()
        prepareExitMotion(SettingsBackupContentTiming.PREDICTIVE)
        gestureStartExpansion = motionHost.expansion
        predictiveMotionActive = true
        motionState = MotionState.PREDICTIVE_BACK
    }

    private fun progressPredictiveBack(rawProgress: Float) {
        if (backTarget != BackTarget.FINISH_ACTIVITY || !predictiveMotionActive) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            predictiveMotionActive = false
            completeExpandedMotion()
            return
        }
        val progress = predictiveBackInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
        applyMotionExpansion(gestureStartExpansion * (1f - progress))
    }

    private fun cancelPredictiveBack() {
        if (backTarget == BackTarget.FINISH_ACTIVITY && predictiveMotionActive) {
            predictiveMotionActive = false
            motionState = MotionState.CANCELLING_BACK
            animateMotionTo(
                targetExpansion = 1f,
                durationMs = BACK_CANCEL_DURATION_MS,
                interpolator = cancelInterpolator,
                onEnd = ::completeExpandedMotion
            )
        }
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
    }

    private fun commitBack() {
        val target = if (backTarget == BackTarget.NONE) resolveBackTarget() else backTarget
        val hadInteractiveStart = predictiveMotionActive
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
        if (target == BackTarget.FINISH_ACTIVITY) {
            requestClose(interactiveCommit = hadInteractiveStart)
        }
    }

    private fun resolveBackTarget(): BackTarget = when {
        motionState != MotionState.EXPANDED ||
            motionAnimator != null ||
            motionHost.expansion < 0.999f -> BackTarget.BLOCKED
        exportRunning || pickerOpen || activeDialog != null -> BackTarget.BLOCKED
        else -> BackTarget.FINISH_ACTIVITY
    }

    private fun suppressSystemActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun scheduleInitialMotion(isFreshLaunch: Boolean) {
        val canResolveOrigin = launchOrigin != null ||
            DiagnosticsTransitionOriginRegistry.snapshot() != null
        val shouldAnimate = isFreshLaunch &&
            canResolveOrigin &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldAnimate) motionHost.prepareFirstFrameForEntry()
        motionHost.doOnPreDraw {
            if (!shouldAnimate) {
                completeExpandedMotion()
                return@doOnPreDraw
            }
            val geometry = resolveMotionGeometry()
            if (geometry == null) {
                completeExpandedMotion()
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
            motionState = MotionState.ENTERING
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
                    interpolator = enterInterpolator,
                    onEnd = ::completeExpandedMotion
                )
            }
        }
    }

    private fun prepareExitMotion(contentTiming: SettingsBackupContentTiming) {
        finishPageStretch()
        motionGeometry = resolveMotionGeometry(preferLiveOrigin = true)
        motionContentTiming = contentTiming
        motionTitleMode = if (motionGeometry?.titleMotionEnabled == true) {
            SettingsBackupTransitionTitleMode.SOURCE_TITLE
        } else {
            SettingsBackupTransitionTitleMode.HIDDEN
        }
        motionHost.beginMotion()
        applyMotionExpansion(motionHost.expansion)
    }

    private fun requestClose(interactiveCommit: Boolean) {
        if (finishingAfterMotion || motionState == MotionState.FINISHED) return
        cancelMotionAnimator()
        if (!interactiveCommit) prepareExitMotion(SettingsBackupContentTiming.TIMED)
        motionState = MotionState.CLOSING
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
        val duration = SettingsBackupMotionSpec.closeDurationMs(
            baseDurationMs = baseDuration,
            currentExpansion = currentExpansion,
            minimumDurationMs = MIN_CLOSE_DURATION_MS
        )
        animateMotionTo(
            targetExpansion = 0f,
            durationMs = duration,
            interpolator = if (interactiveCommit) commitInterpolator else closeInterpolator,
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
        val expansionDelta = targetExpansion - startExpansion
        motionAnimator = animator
        animator.duration = durationMs
        animator.interpolator = interpolator
        animator.addUpdateListener { valueAnimator ->
            applyMotionExpansion(startExpansion + expansionDelta * valueAnimator.animatedFraction)
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
                contentTravelPx = CONTENT_TRAVEL_DP * resources.displayMetrics.density,
                contentTiming = motionContentTiming
            )
            motionHost.blockInteraction(true)
        }
    }

    private fun completeExpandedMotion() {
        if (isFinishing || isDestroyed || finishingAfterMotion) return
        motionHost.showExpandedImmediately()
        motionGeometry = null
        motionContentTiming = SettingsBackupContentTiming.TIMED
        motionState = MotionState.EXPANDED
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
        installPageStretch()
        pendingScreenState?.let { state ->
            pendingScreenState = null
            renderScreenState(state)
        }
    }

    private fun finishAfterMotion() {
        if (finishingAfterMotion || isFinishing || isDestroyed) return
        finishingAfterMotion = true
        motionState = MotionState.FINISHED
        motionHost.blockInteraction(true)
        // Animator 的最终 update 与 onEnd 发生在同一帧；延后一帧 finish，确保 expansion=0
        // 已提交给合成器，让下层真实入口自然接管，避免关闭末尾闪现。
        motionHost.postOnAnimation {
            if (isFinishing || isDestroyed) return@postOnAnimation
            finish()
            suppressLegacyCloseTransition()
        }
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

    private fun resolveMotionGeometry(
        preferLiveOrigin: Boolean = false
    ): SettingsBackupMotionGeometry? {
        if (motionHost.width <= 0 || motionHost.height <= 0) return null
        val display = motionHost.display ?: return null
        val tolerancePx = 4.dp
        val liveOrigin = DiagnosticsTransitionOriginRegistry.snapshot(
            allowHidden = preferLiveOrigin
        )
        val originCandidates = if (allowLaunchOriginForExit) {
            if (preferLiveOrigin) {
                sequenceOf(liveOrigin, launchOrigin)
            } else {
                sequenceOf(launchOrigin, liveOrigin)
            }
        } else {
            sequenceOf(
                DiagnosticsTransitionOriginRegistry.snapshot(
                    allowHidden = preferLiveOrigin
                )
            )
        }
        val origin = originCandidates.filterNotNull().firstOrNull { candidate ->
            candidate.displayId == display.displayId &&
                candidate.displayRotation == display.rotation
        } ?: return null

        val hostLocation = IntArray(2)
        motionHost.getLocationOnScreen(hostLocation)
        val mappedOrigin = DiagnosticsTransitionCoordinateMapper.map(
            origin = origin,
            destinationWindowWidth = motionHost.width,
            destinationWindowHeight = motionHost.height,
            destinationWindowLeftOnScreen = hostLocation[0].toFloat(),
            destinationWindowTopOnScreen = hostLocation[1].toFloat(),
            tolerancePx = tolerancePx.toFloat()
        ) ?: return null
        val collapsedBounds = mappedOrigin.entryBounds
        val collapsedTitleBounds = mappedOrigin.titleBounds
        val expandedBounds = SettingsBackupMotionRect(
            left = 0f,
            top = 0f,
            right = motionHost.width.toFloat(),
            bottom = motionHost.height.toFloat()
        )
        val destinationTitle = toolbarTitleView ?: return null
        if (destinationTitle.width <= 0 || destinationTitle.height <= 0) return null
        val expandedTitleBounds = destinationTitle.boundsWithin(motionHost) ?: return null
        if (
            !collapsedBounds.isValid ||
            !collapsedTitleBounds.isValid ||
            !expandedTitleBounds.isValid
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
            collapsedCornerRadiusPx = DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP *
                resources.displayMetrics.density,
            contentTravelPx = CONTENT_TRAVEL_DP * resources.displayMetrics.density,
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

    private fun installPageStretch() {
        if (stretchViewport != null) return
        val scrollTarget = stretchScrollTarget ?: return
        stretchViewport = installPreparedLiquidStretch(scrollTarget) {
            motionState == MotionState.EXPANDED &&
                motionAnimator == null &&
                motionHost.expansion >= 0.999f &&
                !predictiveMotionActive &&
                !finishingAfterMotion
        }
    }

    private fun finishPageStretch() {
        finishPreparedLiquidStretch(stretchViewport)
        stretchViewport = null
    }

    private fun renderOrDefer(state: DiagnosticsScreenState) {
        if (motionState != MotionState.EXPANDED || motionAnimator != null) {
            pendingScreenState = state
            return
        }
        renderScreenState(state)
    }

    private fun renderScreenState(state: DiagnosticsScreenState) {
        when (state) {
            DiagnosticsScreenState.Loading -> renderLoading()
            is DiagnosticsScreenState.Ready -> {
                currentSnapshot = state.snapshot
                exportButton.isEnabled = !exportRunning
                renderSnapshot(state.snapshot)
            }
            is DiagnosticsScreenState.Failed -> renderFailure()
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(buildToolbar(), linearMatch(height = 60.dp))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalFadingEdgeEnabled = false
            clipToPadding = false
            setPadding(15.dp, 8.dp, 15.dp, 28.dp)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(matchParent, wrapContent))
        root.addView(scroll, LinearLayout.LayoutParams(matchParent, 0, 1f))
        stretchScrollTarget = scroll
        return root
    }

    private fun buildToolbar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10.dp, 0, 8.dp, 0)
        addView(actionButton("‹", getString(R.string.diagnostics_title)) {
            onBackPressedDispatcher.onBackPressed()
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(TextView(this@DiagnosticsActivity).apply {
            toolbarTitleView = this
            text = getString(R.string.diagnostics_title)
            textSize = 17f
            setTextColor(getColor(R.color.colorTextGray))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, linearWeight())
        refreshButton = actionButton(getString(R.string.diagnostics_refresh), getString(R.string.diagnostics_refresh)) {
            beginManualRefresh()
        }
        addView(refreshButton, LinearLayout.LayoutParams(wrapContent, 44.dp))
        exportButton = actionButton(getString(R.string.diagnostics_export), getString(R.string.diagnostics_export)) {
            showExportPreview()
        }.apply { isEnabled = false }
        addView(exportButton, LinearLayout.LayoutParams(wrapContent, 44.dp))
    }

    private fun actionButton(label: String, description: String, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (label == "‹") 34f else 14f
            setTextColor(getColor(R.color.colorTextGray))
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            contentDescription = description
            background = rippleBackground(14f)
            setOnClickListener { action() }
        }

    private fun observeState() {
        viewModel.screenState.observe(this) { state ->
            renderOrDefer(state)
        }
        viewModel.exportState.observe(this) { state ->
            val running = state is DiagnosticsExportState.Running
            exportRunning = running
            refreshButton.isEnabled = !running
            exportButton.isEnabled = !running && currentSnapshot != null
            when (state) {
                DiagnosticsExportState.Running -> Toast.makeText(
                    this,
                    R.string.diagnostics_export_running,
                    Toast.LENGTH_SHORT
                ).show()
                is DiagnosticsExportState.Finished -> {
                    Toast.makeText(
                        this,
                        R.string.diagnostics_export_success,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.consumeExportResult()
                }
                is DiagnosticsExportState.Failed -> {
                    Toast.makeText(
                        this,
                        R.string.diagnostics_export_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.consumeExportResult()
                }
                DiagnosticsExportState.Idle -> Unit
            }
        }
    }

    private fun beginManualRefresh() {
        val framework = RemoteHookConfigStore.status()
        frameworkCheckPending = !framework.connected && !frameworkServiceObserved
        mainHandler.removeCallbacks(frameworkTimeout)
        if (frameworkCheckPending) {
            mainHandler.postDelayed(frameworkTimeout, FRAMEWORK_STATUS_SETTLE_MS)
        }
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        viewModel.refresh(
            context = applicationContext,
            skin = currentSkinDiagnostics(),
            frameworkCheckPending = frameworkCheckPending
        )
    }

    private fun renderLoading() {
        if (currentSnapshot != null) return
        exportButton.isEnabled = false
        content.removeAllViews()
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20.dp, 72.dp, 20.dp, 40.dp)
            addView(ProgressBar(this@DiagnosticsActivity), linearWrap())
            addView(bodyText(getString(R.string.diagnostics_loading)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 18.dp, 0, 0)
            }, linearMatch())
        }, linearMatch())
    }

    private fun renderFailure() {
        currentSnapshot = null
        exportButton.isEnabled = false
        content.removeAllViews()
        content.addView(card().apply {
            addView(titleText(getString(R.string.diagnostics_collection_failed)), linearMatch())
            addView(actionButton(
                getString(R.string.diagnostics_refresh),
                getString(R.string.diagnostics_refresh),
                ::beginManualRefresh
            ), linearWrap().apply { topMargin = 12.dp })
        }, cardParams())
    }

    private fun renderSnapshot(snapshot: ModuleDiagnosticSnapshot) {
        content.removeAllViews()
        content.addView(overallCard(snapshot), cardParams())
        addSection(
            R.string.diagnostics_section_environment,
            listOf(DiagnosticItemId.MODULE_BUILD, DiagnosticItemId.TARGET_APP),
            snapshot
        )
        addSection(
            R.string.diagnostics_section_runtime,
            listOf(
                DiagnosticItemId.ACTIVATION,
                DiagnosticItemId.FRAMEWORK_SERVICE,
                DiagnosticItemId.REMOTE_CONFIG,
                DiagnosticItemId.NO_ROOT,
                DiagnosticItemId.HOST_BOOTSTRAP,
                DiagnosticItemId.FEATURE_COVERAGE,
                DiagnosticItemId.HOST_ADAPTATION
            ),
            snapshot
        )
        addSection(
            R.string.diagnostics_section_interface,
            listOf(
                DiagnosticItemId.INTERFACE_SKIN,
                DiagnosticItemId.SETTINGS_CATALOG,
                DiagnosticItemId.LOGGING
            ),
            snapshot
        )
    }

    private fun overallCard(snapshot: ModuleDiagnosticSnapshot) = card().apply {
        val severity = snapshot.overallSeverity
        addView(LinearLayout(this@DiagnosticsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@DiagnosticsActivity).apply {
                background = circle(severityColor(severity))
            }, LinearLayout.LayoutParams(12.dp, 12.dp).apply { marginEnd = 12.dp })
            addView(titleText(overallText(severity)).apply { textSize = 19f }, linearWeight())
        }, linearMatch())
        addView(bodyText(getString(R.string.diagnostics_overall_tip)).apply {
            setPadding(24.dp, 8.dp, 0, 0)
        }, linearMatch())
    }

    private fun addSection(
        titleRes: Int,
        ids: List<DiagnosticItemId>,
        snapshot: ModuleDiagnosticSnapshot
    ) {
        content.addView(titleText(getString(titleRes)).apply {
            textSize = 16f
            setPadding(5.dp, 18.dp, 5.dp, 8.dp)
        }, linearMatch())
        val section = card(padding = 0)
        ids.forEachIndexed { index, id ->
            val item = requireNotNull(snapshot.items.firstOrNull { it.id == id })
            if (index > 0) section.addView(divider(), linearMatch(height = 1.dp))
            section.addView(itemRow(item, snapshot), linearMatch())
        }
        content.addView(section, cardParams(top = 0))
    }

    private fun itemRow(item: DiagnosticItem, snapshot: ModuleDiagnosticSnapshot) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(15.dp, 14.dp, 15.dp, 14.dp)
            addView(View(this@DiagnosticsActivity).apply {
                background = circle(severityColor(item.severity))
            }, LinearLayout.LayoutParams(10.dp, 10.dp).apply {
                topMargin = 6.dp
                marginEnd = 12.dp
            })
            addView(LinearLayout(this@DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@DiagnosticsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(titleText(itemTitle(item.id)).apply { textSize = 15f }, linearWeight())
                    addView(evidenceChip(item), linearWrap().apply { marginStart = 8.dp })
                }, linearMatch())
                addView(bodyText(itemDetail(item.id, snapshot)).apply {
                    setPadding(0, 5.dp, 0, 0)
                }, linearMatch())
            }, linearWeight())
            contentDescription = "${itemTitle(item.id)}. ${severityText(item.severity)}. " +
                "${evidenceText(item.evidence)}. ${itemDetail(item.id, snapshot)}"
        }

    private fun evidenceChip(item: DiagnosticItem) = TextView(this).apply {
        text = evidenceText(item.evidence)
        textSize = 10f
        setTextColor(getColor(R.color.colorTextGray))
        setPadding(7.dp, 3.dp, 7.dp, 3.dp)
        background = GradientDrawable().apply {
            cornerRadius = 9.dp.toFloat()
            setColor(ColorUtils.setAlphaComponent(severityColor(item.severity), 0x28))
        }
    }

    private fun itemTitle(id: DiagnosticItemId): String = getString(
        when (id) {
            DiagnosticItemId.MODULE_BUILD -> R.string.diagnostics_item_module
            DiagnosticItemId.TARGET_APP -> R.string.diagnostics_item_target
            DiagnosticItemId.FRAMEWORK_SERVICE -> R.string.diagnostics_item_framework
            DiagnosticItemId.REMOTE_CONFIG -> R.string.diagnostics_item_remote_config
            DiagnosticItemId.ACTIVATION -> R.string.diagnostics_item_activation
            DiagnosticItemId.NO_ROOT -> R.string.diagnostics_item_no_root
            DiagnosticItemId.HOST_BOOTSTRAP -> R.string.diagnostics_item_host_bootstrap
            DiagnosticItemId.FEATURE_COVERAGE -> R.string.diagnostics_item_feature_coverage
            DiagnosticItemId.HOST_ADAPTATION -> R.string.diagnostics_item_adaptation
            DiagnosticItemId.INTERFACE_SKIN -> R.string.diagnostics_item_skin
            DiagnosticItemId.SETTINGS_CATALOG -> R.string.diagnostics_item_catalog
            DiagnosticItemId.LOGGING -> R.string.diagnostics_item_logging
        }
    )

    private fun itemDetail(id: DiagnosticItemId, snapshot: ModuleDiagnosticSnapshot): String {
        val input = snapshot.inputs
        return when (id) {
            DiagnosticItemId.MODULE_BUILD -> getString(
                R.string.diagnostics_module_detail,
                input.moduleVersionName,
                input.moduleVersionCode,
                getString(
                    if (input.debugBuild) R.string.diagnostics_build_debug
                    else R.string.diagnostics_build_release
                )
            )
            DiagnosticItemId.TARGET_APP -> if (input.targetInstalled) getString(
                R.string.diagnostics_target_installed,
                input.targetVersionName.orEmpty(),
                input.targetVersionCode
            ) else getString(R.string.diagnostics_target_missing)
            DiagnosticItemId.FRAMEWORK_SERVICE -> when {
                input.frameworkCapable -> getString(
                    R.string.diagnostics_framework_ready,
                    input.frameworkName.ifBlank { "LSPosed" },
                    input.frameworkApiVersion
                )
                input.frameworkConnected -> getString(
                    R.string.diagnostics_framework_unsupported,
                    input.frameworkName.ifBlank { "Xposed" },
                    input.frameworkApiVersion
                )
                else -> getString(R.string.diagnostics_framework_waiting)
            }
            DiagnosticItemId.REMOTE_CONFIG -> when {
                input.remotePublishPending &&
                    input.remotePublishState != DiagnosticRemotePublishState.FAILED ->
                    getString(R.string.diagnostics_remote_publishing)
                input.remotePublishState == DiagnosticRemotePublishState.READY -> getString(
                    R.string.diagnostics_remote_ready,
                    input.remoteGeneration
                )
                input.remotePublishState == DiagnosticRemotePublishState.PUBLISHING ->
                    getString(R.string.diagnostics_remote_publishing)
                input.remotePublishState == DiagnosticRemotePublishState.WAITING_FOR_SERVICE ->
                    getString(R.string.diagnostics_remote_waiting)
                input.remotePublishState == DiagnosticRemotePublishState.FAILED -> getString(
                    R.string.diagnostics_remote_failed,
                    input.remoteFailureCode ?: "publish_failed"
                )
                else -> getString(R.string.diagnostics_remote_not_initialized)
            }
            DiagnosticItemId.ACTIVATION -> getString(
                when (input.activationState) {
                    DiagnosticActivationState.ACTIVE_LSPOSED ->
                        R.string.diagnostics_activation_lsposed
                    DiagnosticActivationState.ACTIVE_NPATCH ->
                        R.string.diagnostics_activation_npatch
                    DiagnosticActivationState.CHECKING ->
                        R.string.diagnostics_activation_checking
                    DiagnosticActivationState.UNAVAILABLE ->
                        R.string.diagnostics_activation_unavailable
                }
            )
            DiagnosticItemId.NO_ROOT -> getString(noRootText(input.noRootState))
            DiagnosticItemId.HOST_BOOTSTRAP -> hostBootstrapDetail(snapshot)
            DiagnosticItemId.FEATURE_COVERAGE -> featureCoverageDetail(snapshot)
            DiagnosticItemId.HOST_ADAPTATION -> if (input.hostRuntimeReceiptAvailable) {
                val summary = getString(
                    R.string.diagnostics_adaptation_receipt,
                    input.hostAdaptedFeatureCount,
                    input.hostObservedFeatureCount,
                    input.hostAppliedFeatureCount
                )
                val features = input.hostFeatures
                    .filter {
                        it.evidence != DiagnosticEvidence.NOT_AVAILABLE ||
                            it.runtimeEvidenceExpected &&
                            it.installState == DiagnosticFeatureInstallState.INSTALLED
                    }
                    .joinToString(separator = "\n") { feature ->
                    "${hostFeatureTitle(feature.featureId)}：${evidenceText(feature.evidence)}"
                }
                if (features.isBlank()) summary else "$summary\n$features"
            } else {
                getString(R.string.diagnostics_adaptation_unknown)
            }
            DiagnosticItemId.INTERFACE_SKIN -> if (input.skinFallbackCode != null) {
                getString(
                    R.string.diagnostics_skin_fallback,
                    input.skinFallbackCode,
                    input.effectiveSkin
                )
            } else {
                val backend = input.liquidBackendName?.let {
                    getString(R.string.diagnostics_skin_backend, it)
                }.orEmpty()
                // 降级原因直接展示在后端旁边：AGSL 被驱动拒绝时用户侧只会看到"效果变朴素"，
                // 没有这一行就无法把跨 GPU 的兼容问题反馈回来。
                val degrade = input.liquidBackendDegradeReason?.let {
                    getString(R.string.diagnostics_skin_backend_degraded, it)
                }.orEmpty()
                getString(
                    R.string.diagnostics_skin_ready,
                    input.requestedSkin,
                    input.effectiveSkin,
                    backend
                ) + degrade
            }
            DiagnosticItemId.SETTINGS_CATALOG -> getString(
                R.string.diagnostics_catalog_detail,
                input.settingsCatalogVersion,
                input.settingsTotalCount,
                input.settingsAutomaticCount,
                input.settingsManualCount
            )
            DiagnosticItemId.LOGGING -> getString(
                when {
                    !input.loggingEnabled -> R.string.diagnostics_logging_off
                    input.verboseLogging -> R.string.diagnostics_logging_complete
                    else -> R.string.diagnostics_logging_minimal
                }
            )
        }
    }

    private fun hostBootstrapDetail(snapshot: ModuleDiagnosticSnapshot): String {
        val input = snapshot.inputs
        if (!input.hostRuntimeReceiptAvailable) {
            return getString(
                if (input.hostQueryState == DiagnosticHostQueryState.INVALID_RESPONSE) {
                    R.string.diagnostics_bootstrap_invalid
                } else {
                    R.string.diagnostics_bootstrap_unavailable
                }
            )
        }
        return when {
            input.hostConfigState == DiagnosticHostConfigState.REJECTED -> getString(
                R.string.diagnostics_bootstrap_config_rejected,
                input.hostConfigReasonCode ?: "unknown"
            )
            input.hostConfigState == DiagnosticHostConfigState.NOT_AUTHORIZED ->
                getString(R.string.diagnostics_bootstrap_not_authorized)
            input.hostInstallChainState == DiagnosticHostInstallChainState.FAILED ->
                getString(R.string.diagnostics_bootstrap_install_failed)
            input.hostConfigState == DiagnosticHostConfigState.ACCEPTED &&
                input.hostInstallChainState == DiagnosticHostInstallChainState.COMPLETED ->
                getString(
                    R.string.diagnostics_bootstrap_ready,
                    input.hostConfigGeneration,
                    input.hostHookPointInstalledCount,
                    input.hostHookPointResolvedCount,
                    input.hostHookPointMissingCount,
                    input.hostHookPointFailedCount
                )
            else -> getString(
                R.string.diagnostics_bootstrap_waiting,
                input.hostConfigState.name,
                input.hostInstallChainState.name
            )
        }
    }

    private fun featureCoverageDetail(snapshot: ModuleDiagnosticSnapshot): String {
        val input = snapshot.inputs
        if (!input.hostRuntimeReceiptAvailable) {
            return getString(R.string.diagnostics_feature_coverage_unavailable)
        }
        val summary = getString(
            R.string.diagnostics_feature_coverage_summary,
            input.hostInstalledFeatureCount,
            input.hostFailedFeatureCount,
            input.hostFeatures.size
        )
        val lines = input.hostFeatures
            .sortedWith(
                compareBy<DiagnosticHostFeature> {
                    featureInstallPriority(it.installState)
                }.thenBy { it.featureId }
            )
            .joinToString(separator = "\n") { feature ->
                val runtime = if (feature.runtimeEvidenceExpected ||
                    feature.evidence != DiagnosticEvidence.NOT_AVAILABLE
                ) {
                    " · ${evidenceText(feature.evidence)}"
                } else {
                    ""
                }
                "${hostFeatureTitle(feature.featureId)}：${featureInstallText(feature)}$runtime"
            }
        return if (lines.isBlank()) summary else "$summary\n$lines"
    }

    private fun featureInstallPriority(state: DiagnosticFeatureInstallState): Int = when (state) {
        DiagnosticFeatureInstallState.FAILED,
        DiagnosticFeatureInstallState.SKIPPED -> 0
        DiagnosticFeatureInstallState.NOT_REPORTED -> 1
        DiagnosticFeatureInstallState.INSTALLED -> 2
        DiagnosticFeatureInstallState.DISABLED,
        DiagnosticFeatureInstallState.NOT_APPLICABLE -> 3
    }

    private fun featureInstallText(
        feature: DiagnosticHostFeature
    ): String = when (feature.installState) {
        DiagnosticFeatureInstallState.INSTALLED -> getString(
            R.string.diagnostics_feature_install_installed,
            feature.installedHookCount
        )
        DiagnosticFeatureInstallState.DISABLED ->
            getString(R.string.diagnostics_feature_install_disabled)
        DiagnosticFeatureInstallState.NOT_APPLICABLE ->
            getString(R.string.diagnostics_feature_install_not_applicable)
        DiagnosticFeatureInstallState.NOT_REPORTED ->
            getString(R.string.diagnostics_feature_install_not_reported)
        DiagnosticFeatureInstallState.SKIPPED -> getString(
            R.string.diagnostics_feature_install_skipped,
            feature.installReasonCode ?: "OTHER"
        )
        DiagnosticFeatureInstallState.FAILED -> getString(
            R.string.diagnostics_feature_install_failed,
            feature.installReasonCode ?: "INSTALLER_EXCEPTION"
        )
    }

    private fun noRootText(state: DiagnosticNoRootState): Int = when (state) {
        DiagnosticNoRootState.UNSUPPORTED_OS -> R.string.no_root_status_unsupported_os
        DiagnosticNoRootState.DISABLED -> R.string.no_root_status_disabled
        DiagnosticNoRootState.CHECKING -> R.string.no_root_status_checking
        DiagnosticNoRootState.MANAGER_MISSING -> R.string.no_root_status_manager_missing
        DiagnosticNoRootState.MODULE_NOT_REGISTERED -> R.string.no_root_status_module_not_registered
        DiagnosticNoRootState.SYNCING -> R.string.no_root_status_syncing
        DiagnosticNoRootState.RESTART_REQUIRED -> R.string.no_root_status_restart_required
        DiagnosticNoRootState.DISABLE_RESTART_REQUIRED,
        DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            R.string.no_root_status_disable_restart_required
        DiagnosticNoRootState.ACTIVE -> R.string.no_root_status_active
        DiagnosticNoRootState.CONNECTION_TIMEOUT -> R.string.no_root_status_connection_timeout
        DiagnosticNoRootState.ERROR -> R.string.no_root_status_error
    }

    private fun hostFeatureTitle(featureId: String): String = getString(
        when (featureId) {
            "paused_ad" -> R.string.paused_page_ad_enable
            "game_mentioned_promotion" -> R.string.gamecard_ad_enable
            "detail_app_promotion" -> R.string.hide_video_detail_app_promotion
            "home_banner" -> R.string.banner_ad_enable
            "merchandise" -> R.string.merch_ad_enable
            "home_top_bar_purify" -> R.string.home_top_bar_settings
            "home_vertical_detail" -> R.string.home_vertical_open_detail
            "home_recommend_purify" -> R.string.diagnostics_host_feature_home_feed
            "home_tab_filter" -> R.string.custom_home_tab_hide
            "home_component_filter" -> R.string.custom_home_component_hide
            "bottom_bar" -> R.string.custom_bottom_bar_hide
            "story_purify" -> R.string.story_purify_settings
            "dynamic_tabs_purify" -> R.string.dynamic_page_settings
            "mine_vip_purify" -> R.string.hide_mine_vip
            "video_relate_filter" -> R.string.diagnostics_host_feature_relate
            "player_portrait_control" -> R.string.hide_player_portrait_control
            "player_status_bar" -> R.string.transparent_player_status_bar
            "comment_filter" -> R.string.diagnostics_host_feature_comment_filter
            "comment_purify" -> R.string.diagnostics_host_feature_comment_purify
            "comment_section" -> R.string.hide_comment_section
            "comment_topology" -> R.string.reply_topology_enabled
            "free_copy" -> R.string.free_copy_enable
            "player_default_quality" -> R.string.diagnostics_host_feature_quality
            "splash_ad_purify" -> R.string.diagnostics_host_feature_splash
            "mine_component_filter" -> R.string.diagnostics_host_feature_mine
            "block_app_update" -> R.string.block_app_update
            "full_number_display" -> R.string.show_full_numbers
            "teenagers_mode_prompt" -> R.string.block_teenagers_mode_prompt
            "roaming_compat" -> R.string.roaming_compat_enable
            else -> R.string.diagnostics_host_feature_unknown
        }
    )

    // Dialog Window 自己消费返回并播放统一退场；底层 Activity 的 predictive back 仍保持 BLOCKED。
    @SuppressLint("GestureBackNavigation")
    private fun showExportPreview() {
        if (currentSnapshot == null || viewModel.exportState.value is DiagnosticsExportState.Running) return
        activeDialog?.dismiss()
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        container.addView(
            TextView(this).apply {
                setText(R.string.diagnostics_report_preview_title)
                setTextColor(getColor(R.color.colorTextGray))
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            TextView(this).apply {
                setText(R.string.diagnostics_report_preview_body)
                setTextColor(getColor(R.color.colorTextDark))
                textSize = 14f
                setLineSpacing(4 * density, 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            modalButton(
                textRes = R.string.diagnostics_report_cancel,
                filled = false
            ) {
                dismissExportPreviewDialog(dialog, container)
            }
        )
        buttonRow.addView(
            modalButton(
                textRes = R.string.diagnostics_report_choose_location,
                filled = true
            ) {
                dismissExportPreviewDialog(dialog, container, ::launchDiagnosticsExportPicker)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() }
        )
        container.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * density).toInt() }
        )
        val root = FrameLayout(this).apply {
            addView(
                container,
                FrameLayout.LayoutParams(
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
            setDimAmount(0f)
        }
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    dismissExportPreviewDialog(dialog, container)
                }
                true
            } else false
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.post {
            container.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(enterInterpolator)
                .start()
        }
    }

    private fun modalButton(
        textRes: Int,
        filled: Boolean,
        onClick: () -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        val radius = 20 * density
        return TextView(this).apply {
            setText(textRes)
            setTextColor(if (filled) monetColors.onPrimary else getColor(R.color.colorTextGray))
            textSize = 14f
            if (filled) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (18 * density).toInt(),
                (10 * density).toInt(),
                (18 * density).toInt(),
                (10 * density).toInt()
            )
            val mask = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            val content = if (filled) GradientDrawable().apply {
                cornerRadius = radius
                setColor(monetColors.primary)
            } else null
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(
                        if (filled) monetColors.onPrimary else getColor(R.color.colorTextGray),
                        0x33
                    )
                ),
                content,
                mask
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dismissExportPreviewDialog(
        dialog: Dialog,
        container: View,
        afterDismiss: () -> Unit = {}
    ) {
        if (!dialog.isShowing || container.hasTransientState()) return
        container.setHasTransientState(true)
        container.animate().cancel()
        container.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(modalExitInterpolator)
            .withEndAction {
                container.setHasTransientState(false)
                if (dialog.isShowing) dialog.dismiss()
                afterDismiss()
            }
            .start()
    }

    private fun launchDiagnosticsExportPicker() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pickerOpen = true
        try {
            createDocumentLauncher.launch("BILab_Diagnostics_$timestamp.json")
        } catch (_: ActivityNotFoundException) {
            pickerOpen = false
            Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show()
        } catch (_: RuntimeException) {
            pickerOpen = false
            Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun overallText(severity: DiagnosticSeverity): String = getString(
        when (severity) {
            DiagnosticSeverity.ATTENTION -> R.string.diagnostics_overall_attention
            DiagnosticSeverity.ACTION_REQUIRED -> R.string.diagnostics_overall_action_required
            else -> R.string.diagnostics_overall_ok
        }
    )

    private fun severityText(severity: DiagnosticSeverity): String = getString(
        when (severity) {
            DiagnosticSeverity.OK -> R.string.diagnostics_state_ok
            DiagnosticSeverity.INFO -> R.string.diagnostics_state_info
            DiagnosticSeverity.ATTENTION -> R.string.diagnostics_state_attention
            DiagnosticSeverity.ACTION_REQUIRED -> R.string.diagnostics_state_action_required
            DiagnosticSeverity.UNKNOWN -> R.string.diagnostics_state_unknown
        }
    )

    private fun evidenceText(evidence: DiagnosticEvidence): String = getString(
        when (evidence) {
            DiagnosticEvidence.CONFIGURED -> R.string.diagnostics_evidence_configured
            DiagnosticEvidence.PUBLISHED -> R.string.diagnostics_evidence_published
            DiagnosticEvidence.ADAPTED -> R.string.diagnostics_evidence_adapted
            DiagnosticEvidence.OBSERVED -> R.string.diagnostics_evidence_observed
            DiagnosticEvidence.APPLIED -> R.string.diagnostics_evidence_applied
            DiagnosticEvidence.NOT_AVAILABLE -> R.string.diagnostics_evidence_unavailable
        }
    )

    private fun severityColor(severity: DiagnosticSeverity): Int = when (severity) {
        DiagnosticSeverity.OK -> DiagnosticStatusTone.OK
        DiagnosticSeverity.INFO -> DiagnosticStatusTone.INFO
        DiagnosticSeverity.ATTENTION -> DiagnosticStatusTone.ATTENTION
        DiagnosticSeverity.ACTION_REQUIRED -> DiagnosticStatusTone.ACTION_REQUIRED
        DiagnosticSeverity.UNKNOWN -> DiagnosticStatusTone.UNKNOWN
    }.let { tone ->
        DiagnosticStatusPalette.color(
            tone,
            darkTheme = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        )
    }

    private fun card(padding: Int = 16.dp) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        background = skinCardBackground(monetColors.surfaceVariant)
    }

    private fun titleText(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(R.color.colorTextGray))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(getColor(R.color.colorTextGray))
        alpha = 0.78f
        setLineSpacing(0f, 1.12f)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x18))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rippleBackground(radiusDp: Float) = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.primary, 0x28)),
        Color.TRANSPARENT.toDrawable(),
        GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
    )

    private fun Int.toDrawable() = GradientDrawable().apply { setColor(this@toDrawable) }

    private fun cardParams(top: Int = 8.dp) = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
        topMargin = top
    }

    private fun linearMatch(height: Int = wrapContent) =
        LinearLayout.LayoutParams(matchParent, height)

    private fun linearWeight() = LinearLayout.LayoutParams(0, wrapContent, 1f)

    private fun linearWrap() = LinearLayout.LayoutParams(wrapContent, wrapContent)

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
}
