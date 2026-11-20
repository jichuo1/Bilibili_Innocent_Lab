package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import io.github.jichuo1.meguviz.android.extensions.updatePadding

private val audioKeys = listOf(FeaturePreferences.PLAYER_AUDIO_PEAK_GUARD, FeaturePreferences.PLAYER_AUDIO_CHANNELS,
    FeaturePreferences.PLAYER_AUDIO_NOISE_REDUCTION, FeaturePreferences.PLAYER_AUDIO_VOICE_FOCUS)

internal fun MainActivity.playerAudioRescueSettingsRows(
    performer: LinearSettingsScope, uiSettings: ModuleUiSettings
) {
    var snapshot = uiSettings
    var summary: NativeTextView? = null
    fun refresh() {
        val count = audioKeys.count { if (it == FeaturePreferences.PLAYER_AUDIO_CHANNELS) snapshot.int(it) in 1..2 else snapshot.bool(it) }
        summary?.text = getString(R.string.player_audio_current, count)
    }
    with(performer) {
        linearLayout(key = "playerAudioRescueRow", lparams = params(widthMatchParent = true) { topMargin = 12.dp }, init = {
            orientation = NativeLinearLayout.VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(vertical = 9.dp)
            isClickable = true
            isFocusable = true
            audioKeys.forEach { bindSettingDestination(this, it) }
            setOnClickListener { anchor ->
                showAudioRescueDialog(anchor) { snapshot = ModuleUiSettings.read(prefs()); refresh() }
            }
        }) {
            textView(key = "playerAudioRescueTitle", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.player_audio_rescue)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "playerAudioRescueSummary", lparams = params(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = .6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                summary = this
                refresh()
            }
        }
    }
}

private fun MainActivity.showAudioRescueDialog(anchor: View, onSaved: () -> Unit) {
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    fun gap(top: Int) = NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    val snapshot = ModuleUiSettings.read(prefs())
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_audio_rescue)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2))
    val body = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        // The prepared Lumen stretch viewport clips its rounded top and bottom corners.
        // Keep the first/last text glyphs inside that contour at both scroll endpoints.
        updatePadding(horizontal = dp(12), vertical = dp(14))
    }
    body.addView(createModalTip(getString(R.string.player_audio_scope)))
    addAudioAssetControls(body)
    // Engine standard controls: MaterialSwitch for each option, the sliding selection for the channel mode,
    // the shared tip line and action buttons. No raw CheckBox / RadioButton / text-as-button rows.
    fun option(key: String, title: Int, tip: Int, top: Int): MaterialSwitch {
        val control = MaterialSwitch(this, null).apply {
            text = getString(title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            isChecked = snapshot.bool(key)
        }
        body.addView(control, gap(top))
        body.addView(createModalTip(getString(tip)), gap(4))
        return control
    }
    val guard = option(audioKeys[0], R.string.player_audio_peak_guard, R.string.player_audio_peak_guard_tip, 18)
    body.addView(NativeTextView(this).apply {
        text = getString(R.string.player_audio_channels)
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
    }, gap(18))
    var channelMode = snapshot.int(audioKeys[1]).takeIf { it in 0..2 } ?: 0
    val channelLabels = listOf(R.string.player_audio_original, R.string.player_audio_mono, R.string.player_audio_auto)
        .map { getString(it) }
    body.addView(createSlidingChoice(labels = channelLabels, selectedIndex = channelMode) { channelMode = it }, gap(8))
    body.addView(createModalTip(getString(R.string.player_audio_channels_tip)), gap(6))
    val noise = option(audioKeys[2], R.string.player_audio_noise_reduction, R.string.player_audio_noise_tip, 14)
    val voice = option(audioKeys[3], R.string.player_audio_voice_focus, R.string.player_audio_voice_tip, 14)
    body.addView(createModalTip(getString(R.string.player_audio_optional_tip)), gap(14))
    container.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * .6f).toInt()).apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }, gap(12))
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, -2, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        val saved = runCatching {
            // Commit the complete draft atomically. Report actual persistence failure.
            check(prefs().edit().putBoolean(audioKeys[0], guard.isChecked).putInt(audioKeys[1], channelMode)
                .putBoolean(audioKeys[2], noise.isChecked).putBoolean(audioKeys[3], voice.isChecked).commit())
        }.onFailure { Log.e("BilibiliInnocentLab", "write audio rescue settings failed", it) }.isSuccess
        if (!saved) { toast(getString(R.string.player_buffer_save_failed)); return@createTermsActionButton }
        onSaved()
        toast(getString(R.string.player_buffer_restart_tip))
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    container.addView(buttons, gap(16))
    presentModalDialog(dialog, container, anchor)
}
