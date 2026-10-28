@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor

internal fun MainActivity.showHostVideoCardRadiusDialog(anchor: View, onSaved: () -> Unit) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.host_video_card_radius)
        textSize = 17f
        textColor = getColor(R.color.colorTextDark)
    })
    container.addView(TextView(this).apply {
        text = getString(R.string.host_video_card_radius_tip)
        textSize = 12f
        textColor = getColor(R.color.colorTextGray)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val value = TextView(this).apply {
        textSize = 16f
        textColor = getColor(R.color.colorTextDark)
        gravity = Gravity.CENTER
    }
    val slider = SeekBar(this).apply {
        contentDescription = getString(R.string.host_video_card_radius)
        max = HostVideoCardStyleSpec.MAX_RADIUS_DP
        progress = hostVideoCardRadiusDp.takeIf { it >= 0 } ?: 20
        value.text = "$progress dp"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "$progress dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
    container.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * density).toInt() })
    container.addView(slider, LinearLayout.LayoutParams(-1, -2))
    fun save(radius: Int) {
        runCatching { prefs().edit { putInt(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP, radius) } }
            .onSuccess {
                hostVideoCardRadiusDp = radius
                onSaved()
                dismissWithAnimation(dialog, container) {}
            }.onFailure {
                Log.e("BilibiliInnocentLab", "write host video card radius failed", it)
            }
    }
    container.addView(createGitHubMenuRow(
        title = getString(R.string.host_video_card_radius_default),
        subtitle = "",
        highlight = hostVideoCardRadiusDp == HostVideoCardStyleSpec.DEFAULT_RADIUS
    ) { save(HostVideoCardStyleSpec.DEFAULT_RADIUS) },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
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
                if (label == R.string.dialog_confirm) save(slider.progress)
                else dismissWithAnimation(dialog, container) {}
            }
        })
    }
    container.addView(buttons, LinearLayout.LayoutParams(-1, -2))
    presentModalDialog(dialog, container, anchor)
}
