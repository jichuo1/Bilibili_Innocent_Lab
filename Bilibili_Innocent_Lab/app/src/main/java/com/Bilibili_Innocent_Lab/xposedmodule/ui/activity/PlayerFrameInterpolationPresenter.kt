package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.util.Log
import android.widget.TextView as NativeTextView
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameRateTarget
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.frame.FrameModelDownloader
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

/**
 * 播放页「视频插帧」：开关 + 模型下载行。入口只在 [FrameInterpolationEntryGate.entryOpen] 时渲染；
 * 模型只在用户点击后下载（[FrameModelDownloader]），检查与下载都在后台线程，结果回主线程刷新摘要。
 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.playerFrameInterpolationSettingsRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>,
    uiSettings: ModuleUiSettings
) {
    if (!FrameInterpolationEntryGate.entryOpen()) return
    with(performer) {
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.player_frame_interpolation_title)
            applyAdvancedSubsectionStyle()
        }
        val key = FeaturePreferences.PLAYER_FRAME_INTERPOLATION
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
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
            setOnCheckedChangeListener { _, checked ->
                runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure {
                    Log.e("BilibiliInnocentLab", "write frame interpolation prefs failed", it)
                }
            }
        }
        val maxFpsKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS
        var maxFps = uiSettings.int(maxFpsKey).takeIf { it in FrameRateTarget.CEILINGS } ?: 60
        var maxFpsSummary: NativeTextView? = null
        fun refreshMaxFps() { maxFpsSummary?.text = getString(R.string.player_frame_max_fps_current, maxFps) }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) { topMargin = 8.dp },
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
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(R.string.player_frame_max_fps)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                setLineSpacing(6f, 1f)
                maxFpsSummary = this
                refreshMaxFps()
            }
        }
        val statusKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
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
            setOnCheckedChangeListener { _, checked ->
                runCatching { prefs().edit { putBoolean(statusKey, checked) } }.onFailure {
                    Log.e("BilibiliInnocentLab", "write frame status toast prefs failed", it)
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_frame_status_toast_summary)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        var summary: NativeTextView? = null
        var busy = false
        fun show(text: String) = runOnUiThread { if (!isDestroyed) summary?.text = text }
        fun showState(state: FrameModelDownloader.State) = show(getString(when (state) {
            FrameModelDownloader.State.READY -> R.string.player_frame_model_ready
            FrameModelDownloader.State.MISSING -> R.string.player_frame_model_missing
            FrameModelDownloader.State.FRAMEWORK_UNAVAILABLE -> R.string.player_frame_model_framework_unavailable
        }))
        fun runInBackground(name: String, work: () -> Unit) {
            if (busy) return
            busy = true
            Thread({ try { work() } finally { runOnUiThread { busy = false } } }, name).start()
        }
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) { topMargin = 8.dp },
            init = {
                orientation = NativeLinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                bindSettingDependency(this, SettingsDependencies.FRAME_MODEL)
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    runInBackground("frame-model-download") {
                        if (FrameModelDownloader.state() == FrameModelDownloader.State.READY) {
                            showState(FrameModelDownloader.State.READY); return@runInBackground
                        }
                        var shown = -1
                        FrameModelDownloader.download(applicationContext) { progress ->
                            when (progress) {
                                is FrameModelDownloader.Progress.Downloading -> {
                                    val percent = if (progress.total > 0) (progress.downloaded * 100 / progress.total).toInt() else 0
                                    if (percent != shown) {
                                        shown = percent
                                        show(getString(R.string.player_frame_model_downloading, progress.part, percent))
                                    }
                                }
                                FrameModelDownloader.Progress.Publishing ->
                                    show(getString(R.string.player_frame_model_publishing))
                                FrameModelDownloader.Progress.Done -> showState(FrameModelDownloader.State.READY)
                                is FrameModelDownloader.Progress.Failed ->
                                    show(getString(R.string.player_frame_model_failed, progress.reason))
                            }
                        }
                    }
                }
            }
        ) {
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(R.string.player_frame_model)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                text = stringResource(R.string.player_frame_model_checking)
                summary = this
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_frame_interpolation_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        runInBackground("frame-model-state") { showState(FrameModelDownloader.state()) }
    }
}
