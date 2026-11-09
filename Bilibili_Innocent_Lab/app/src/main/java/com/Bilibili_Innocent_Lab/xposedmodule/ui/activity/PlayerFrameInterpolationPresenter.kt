package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.util.Log
import android.view.View
import android.widget.TextView as NativeTextView
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameRateTarget
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.frame.FrameModelDownloader
import com.Bilibili_Innocent_Lab.xposedmodule.settings.frame.FrameModelCheckGate
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.updatePadding

/**
 * 播放页「视频插帧」：开关 + 模型下载行。入口只在 [FrameInterpolationEntryGate.entryOpen] 时渲染；
 * 模型只在用户点击后下载（[FrameModelDownloader]），检查与下载都在后台线程，结果回主线程刷新摘要。
 */
internal fun MainActivity.playerFrameInterpolationSettingsRows(
    performer: LinearSettingsScope,
    uiSettings: ModuleUiSettings
) {
    if (!FrameInterpolationEntryGate.entryOpen()) return
    with(performer) {
        textView(key = "PlayerFrameInterpolationPresenter:39",
            lparams = params(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.player_frame_interpolation_title)
            applyAdvancedSubsectionStyle()
        }
        val key = FeaturePreferences.PLAYER_FRAME_INTERPOLATION
        materialSwitch(key = "PlayerFrameInterpolationPresenter:49",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, key)
            bindSettingDestination(this, key)
            text = stringResource(R.string.player_frame_interpolation)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(key)
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(key, checked, button) }
        }
        val maxFpsKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS
        var maxFps = uiSettings.int(maxFpsKey).takeIf { it in FrameRateTarget.CEILINGS } ?: 60
        var maxFpsSummary: NativeTextView? = null
        fun refreshMaxFps() { maxFpsSummary?.text = getString(R.string.player_frame_max_fps_current, maxFps) }
        linearLayout(key = "PlayerFrameInterpolationPresenter:72",
            lparams = params(widthMatchParent = true) { topMargin = 8.dp },
            init = {
                orientation = NativeLinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                bindSettingDestination(this, maxFpsKey)
                setOnClickListener { anchor ->
                    showFrameInterpolationMaxFpsDialog(maxFps, anchor) { value -> maxFps = value; refreshMaxFps() }
                }
            }
        ) {
            textView(key = "PlayerFrameInterpolationPresenter:86", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.player_frame_max_fps)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PlayerFrameInterpolationPresenter:91", lparams = params(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                setLineSpacing(6f, 1f)
                maxFpsSummary = this
                refreshMaxFps()
            }
        }
        val thermalKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL
        materialSwitch(key = "PlayerFrameInterpolationPresenter:101",
            lparams = params(widthMatchParent = true) { topMargin = 8.dp; bottomMargin = 2.dp }
        ) {
            bindFavoriteSwitch(this, thermalKey)
            bindSettingDestination(this, thermalKey)
            text = stringResource(R.string.player_frame_disable_thermal_control)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(thermalKey)
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(thermalKey, checked, button) }
        }
        textView(key = "PlayerFrameInterpolationPresenter:132", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_frame_disable_thermal_control_summary)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        val statusKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST
        materialSwitch(key = "PlayerFrameInterpolationPresenter:140",
            lparams = params(widthMatchParent = true) {
                topMargin = 8.dp
                bottomMargin = 2.dp
            }
        ) {
            bindFavoriteSwitch(this, statusKey)
            bindSettingDestination(this, statusKey)
            text = stringResource(R.string.player_frame_status_toast)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(statusKey)
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(statusKey, checked, button) }
        }
        textView(key = "PlayerFrameInterpolationPresenter:159", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_frame_status_toast_summary)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        var summary: NativeTextView? = null
        val checks = FrameModelCheckGate()
        fun show(token: Long, text: String) = runOnUiThread {
            if (!isDestroyed && checks.current(token)) summary?.text = text
        }
        fun showState(token: Long, state: FrameModelDownloader.State) = show(token, getString(when (state) {
            FrameModelDownloader.State.READY -> R.string.player_frame_model_ready
            FrameModelDownloader.State.MISSING -> R.string.player_frame_model_missing
            FrameModelDownloader.State.FRAMEWORK_UNAVAILABLE -> R.string.player_frame_model_framework_unavailable
            FrameModelDownloader.State.FRAMEWORK_PENDING -> R.string.player_frame_model_framework_pending
            FrameModelDownloader.State.FRAMEWORK_UNSUPPORTED -> R.string.player_frame_model_framework_unsupported
            FrameModelDownloader.State.INVALID -> R.string.player_frame_model_invalid
            FrameModelDownloader.State.REMOTE_FAILED -> R.string.player_frame_model_remote_failed
            FrameModelDownloader.State.LOCAL_FAILED -> R.string.player_frame_model_local_failed
        }))
        var refresh: () -> Unit = {}
        var queueRetries = 0
        val retryRefresh = Runnable { refresh() }
        fun runInBackground(work: (Long) -> Unit) {
            val token = checks.begin() ?: return
            val accepted = FrameModelDownloader.execute {
                try { work(token) }
                catch (_: Exception) { showState(token, FrameModelDownloader.State.LOCAL_FAILED) }
                finally { runOnUiThread { if (checks.finish(token)) refresh() } }
            }
            if (!accepted) {
                showState(token, FrameModelDownloader.State.FRAMEWORK_PENDING)
                checks.finish(token)
                if (checks.attached && queueRetries++ < 3) summary?.postDelayed(retryRefresh, 500L)
            } else {
                queueRetries = 0
            }
        }
        refresh = { runInBackground { token -> showState(token, FrameModelDownloader.refresh(applicationContext)) } }
        val frameworkListener = ModernFrameworkStatusListener {
            runOnUiThread {
                if (checks.attached && !isDestroyed) { checks.invalidate(); refresh() }
            }
        }
        fun stopObserving() {
            if (!checks.attached) return
            checks.attach(false)
            summary?.removeCallbacks(retryRefresh)
            RemoteHookConfigStore.removeStatusListener(frameworkListener)
        }
        fun startObserving() {
            if (checks.attached || summary?.isAttachedToWindow != true ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            checks.attach(true)
            queueRetries = 0
            RemoteHookConfigStore.addStatusListener(frameworkListener)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startObserving()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stopObserving()
                else -> Unit
            }
        }
        linearLayout(key = "PlayerFrameInterpolationPresenter:225",
            lparams = params(widthMatchParent = true) { topMargin = 8.dp },
            init = {
                orientation = NativeLinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                bindSettingDependency(this, SettingsDependencies.FRAME_MODEL)
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    runInBackground { token ->
                        if (FrameModelDownloader.state() == FrameModelDownloader.State.READY) {
                            showState(token, FrameModelDownloader.State.READY); return@runInBackground
                        }
                        var shown = -1
                        FrameModelDownloader.download(applicationContext) { progress ->
                            when (progress) {
                                is FrameModelDownloader.Progress.Downloading -> {
                                    val percent = if (progress.total > 0) (progress.downloaded * 100 / progress.total).toInt() else 0
                                    if (percent != shown) {
                                        shown = percent
                                        show(token, getString(R.string.player_frame_model_downloading, progress.part, percent))
                                    }
                                }
                                FrameModelDownloader.Progress.Publishing ->
                                    show(token, getString(R.string.player_frame_model_publishing))
                                FrameModelDownloader.Progress.Done -> showState(token, FrameModelDownloader.State.READY)
                                is FrameModelDownloader.Progress.Failed ->
                                    progress.state?.let { showState(token, it) }
                                        ?: show(token, getString(R.string.player_frame_model_download_failed))
                            }
                        }
                    }
                }
            }
        ) {
            textView(key = "PlayerFrameInterpolationPresenter:262", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.player_frame_model)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PlayerFrameInterpolationPresenter:267", lparams = params(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                text = stringResource(R.string.player_frame_model_checking)
                summary = this
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        lifecycle.addObserver(lifecycleObserver)
                        startObserving()
                    }
                    override fun onViewDetachedFromWindow(view: View) {
                        stopObserving()
                        lifecycle.removeObserver(lifecycleObserver)
                    }
                })
            }
        }
        textView(key = "PlayerFrameInterpolationPresenter:285", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_frame_interpolation_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
