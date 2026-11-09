package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor

internal fun MainActivity.showHostHomeCardSpacingDialog(anchor: View, onSaved: () -> Unit) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.host_home_card_spacing)
        textSize = 17f
        textColor = getColor(R.color.colorTextDark)
    })
    container.addView(TextView(this).apply {
        text = getString(R.string.host_home_card_spacing_tip)
        textSize = 12f
        textColor = getColor(R.color.colorTextGray)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })

    fun control(title: Int, initial: Int): EditText {
        container.addView(TextView(this).apply {
            text = getString(title)
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * density).toInt() })
        val input = EditText(this).apply {
            contentDescription = getString(title)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            hint = getString(R.string.host_video_card_radius_default)
            textSize = 16f
            textColor = getColor(R.color.colorTextDark)
            if (initial >= 0) setText(initial.toString())
        }
        val slider = SeekBar(this).apply {
            contentDescription = getString(title)
            max = HostHomeCardSpacingSpec.MAX_DP
            progress = initial.coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) input.setText(progress.toString())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { input.setText(seekBar.progress.toString()) }
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        input.doAfterTextChanged { value ->
            val parsed = value?.toString()?.toIntOrNull()
            input.error = if (!value.isNullOrEmpty() && (parsed == null || parsed !in 0..HostHomeCardSpacingSpec.MAX_DP))
                getString(R.string.host_home_card_spacing_invalid) else null
            if (parsed != null && parsed in 0..HostHomeCardSpacingSpec.MAX_DP) slider.progress = parsed
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))
        container.addView(slider, LinearLayout.LayoutParams(-1, -2))
        return input
    }
    val horizontal = control(R.string.host_home_card_horizontal, hostHomeCardHorizontalDp)
    val vertical = control(R.string.host_home_card_vertical, hostHomeCardVerticalDp)
    fun save(horizontalDp: Int, verticalDp: Int) {
        runCatching { prefs().edit {
            putInt(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP, horizontalDp)
            putInt(FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP, verticalDp)
        } }.onSuccess {
            hostHomeCardHorizontalDp = horizontalDp
            hostHomeCardVerticalDp = verticalDp
            onSaved()
            dismissWithAnimation(dialog, container) {}
        }.onFailure { Log.e("BilibiliInnocentLab", "write home card spacing failed", it) }
    }
    container.addView(createGitHubMenuRow(
        title = getString(R.string.host_home_card_spacing_default), subtitle = "",
        highlight = hostHomeCardHorizontalDp < 0 && hostHomeCardVerticalDp < 0
    ) { save(-1, -1) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val buttons = LinearLayout(this).apply { gravity = Gravity.END }
    listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { label ->
        buttons.addView(TextView(this).apply {
            text = getString(label)
            textSize = 15f
            textColor = if (label == R.string.dialog_confirm) monetColors.primary else getColor(R.color.colorTextGray)
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (label == R.string.dialog_cancel) dismissWithAnimation(dialog, container) {}
                else {
                    fun read(input: EditText): Int? = if (input.text.isNullOrEmpty()) -1
                        else input.text.toString().toIntOrNull()?.takeIf { it in 0..HostHomeCardSpacingSpec.MAX_DP }
                    val h = read(horizontal)
                    val v = read(vertical)
                    if (h != null && v != null) save(h, v)
                }
            }
        })
    }
    container.addView(buttons, LinearLayout.LayoutParams(-1, -2))
    presentModalDialog(dialog, container, anchor)
}
