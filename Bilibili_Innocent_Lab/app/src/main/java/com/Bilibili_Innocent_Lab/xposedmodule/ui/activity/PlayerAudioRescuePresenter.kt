package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView as NativeTextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope
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
    val snapshot = ModuleUiSettings.read(prefs())
    fun label(textRes: Int, size: Float = 12f) = NativeTextView(this).apply {
        text = getString(textRes)
        textColor = getColor(R.color.colorTextDark)
        textSize = size
        setLineSpacing(4 * density, 1f)
    }
    container.addView(label(R.string.player_audio_rescue, 17f), NativeLinearLayout.LayoutParams(-1, -2))
    val body = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        // The prepared Lumen stretch viewport clips its rounded top and bottom corners.
        // Keep the first/last text glyphs inside that contour at both scroll endpoints.
        updatePadding(horizontal = (12 * density).toInt(), vertical = (14 * density).toInt())
    }
    body.addView(label(R.string.player_audio_scope))
    addAudioAssetControls(body)
    fun check(key: String, title: Int, tip: Int): CheckBox {
        val control = CheckBox(this).apply {
            text = getString(title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            isChecked = snapshot.bool(key)
        }
        body.addView(control, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
        body.addView(label(tip))
        return control
    }
    val guard = check(audioKeys[0], R.string.player_audio_peak_guard, R.string.player_audio_peak_guard_tip)
    body.addView(label(R.string.player_audio_channels, 14f), NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val channels = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    val choices = listOf(R.string.player_audio_original, R.string.player_audio_mono, R.string.player_audio_auto)
    val ids = choices.map { View.generateViewId() }
    choices.forEachIndexed { index, title ->
        channels.addView(RadioButton(this).apply {
            id = ids[index]; text = getString(title)
            textColor = getColor(R.color.colorTextDark); textSize = 14f
        })
    }
    channels.check(ids[snapshot.int(audioKeys[1]).takeIf { it in 0..2 } ?: 0])
    body.addView(channels)
    body.addView(label(R.string.player_audio_channels_tip))
    val noise = check(audioKeys[2], R.string.player_audio_noise_reduction, R.string.player_audio_noise_tip)
    val voice = check(audioKeys[3], R.string.player_audio_voice_focus, R.string.player_audio_voice_tip)
    body.addView(label(R.string.player_audio_optional_tip), NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    container.addView(MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * .6f).toInt()).apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val buttons = NativeLinearLayout(this).apply { gravity = Gravity.END }
    for (title in listOf(R.string.dialog_cancel, R.string.dialog_confirm)) {
        buttons.addView(label(title, 15f).apply {
            updatePadding(horizontal = (18 * density).toInt(), vertical = (12 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (title == R.string.dialog_confirm) {
                    val mode = ids.indexOf(channels.checkedRadioButtonId).takeIf { it in 0..2 } ?: return@setOnClickListener
                    val saved = runCatching {
                        // Commit the complete draft atomically. Report actual persistence failure.
                        check(prefs().edit().putBoolean(audioKeys[0], guard.isChecked).putInt(audioKeys[1], mode)
                            .putBoolean(audioKeys[2], noise.isChecked).putBoolean(audioKeys[3], voice.isChecked).commit())
                    }.onFailure { Log.e("BilibiliInnocentLab", "write audio rescue settings failed", it) }.isSuccess
                    if (!saved) { toast(getString(R.string.player_buffer_save_failed)); return@setOnClickListener }
                    onSaved()
                    toast(getString(R.string.player_buffer_restart_tip))
                }
                dismissWithAnimation(dialog, container) {}
            }
        }, NativeLinearLayout.LayoutParams(-2, -2))
    }
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2))
    presentModalDialog(dialog, container, anchor)
}
