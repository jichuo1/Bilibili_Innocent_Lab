@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferMode
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast

/** 保存完整策略后再更新镜像；实际播放器选项在宿主下次启动时安装。 */
internal fun MainActivity.showPlayerBufferDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.player_buffer_strategy)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    }, LinearLayout.LayoutParams(-1, -2))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(TextView(this).apply {
        text = getString(R.string.player_buffer_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })

    var draftMode = PlayerBufferMode.fromValue(playerBufferMode)
    val modes = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
    val modeIds = PlayerBufferMode.entries.associateWith { View.generateViewId() }
    PlayerBufferMode.entries.forEach { mode ->
        modes.addView(RadioButton(this).apply {
            id = modeIds.getValue(mode)
            text = playerBufferModeLabel(mode.value)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
        }, RadioGroup.LayoutParams(-1, -2))
    }
    modes.check(modeIds.getValue(draftMode))
    body.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    val modeTip = TextView(this).apply {
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }
    body.addView(modeTip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })

    fun numberField(labelRes: Int, value: Int, range: IntRange): EditText {
        body.addView(TextView(this).apply {
            text = getString(labelRes)
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(5))
            setText(value.toString())
            hint = getString(R.string.player_buffer_range, range.first, range.last)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(getColor(R.color.colorTextGray))
            textSize = 15f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(monetColors.surfaceVariant)
            }
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
            body.addView(TextView(this@showPlayerBufferDialog).apply {
                text = getString(R.string.player_buffer_range, range.first, range.last)
                textColor = getColor(R.color.colorTextGray)
                textSize = 11f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * density).toInt() })
        }
    }
    val seconds = numberField(R.string.player_buffer_seconds, playerBufferSeconds,
        PlayerBufferConfig.MIN_SECONDS..PlayerBufferConfig.MAX_SECONDS)
    val memory = numberField(R.string.player_buffer_memory_mb, playerBufferMemoryMb,
        PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB)
    body.addView(TextView(this).apply {
        text = getString(R.string.player_buffer_memory_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })
    val disk = numberField(R.string.player_buffer_disk_mb, playerBufferDiskMb,
        PlayerBufferConfig.MIN_DISK_MB..PlayerBufferConfig.MAX_DISK_MB)
    val wifiOnly = CheckBox(this).apply {
        text = getString(R.string.player_buffer_wifi_only)
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        isChecked = playerBufferWifiOnly
    }
    body.addView(wifiOnly, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    body.addView(TextView(this).apply {
        text = getString(R.string.player_buffer_wifi_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })

    fun refreshMode() {
        seconds.isEnabled = draftMode == PlayerBufferMode.CUSTOM
        memory.isEnabled = draftMode != PlayerBufferMode.FOLLOW_HOST
        disk.isEnabled = draftMode == PlayerBufferMode.WHOLE
        wifiOnly.isEnabled = draftMode == PlayerBufferMode.WHOLE
        modeTip.text = getString(when (draftMode) {
            PlayerBufferMode.FOLLOW_HOST -> R.string.player_buffer_follow_tip
            PlayerBufferMode.LOW -> R.string.player_buffer_low_tip
            PlayerBufferMode.CUSTOM -> R.string.player_buffer_custom_tip
            PlayerBufferMode.WHOLE -> R.string.player_buffer_whole_tip
        })
    }
    modes.setOnCheckedChangeListener { _, checkedId ->
        draftMode = modeIds.entries.firstOrNull { it.value == checkedId }?.key ?: PlayerBufferMode.FOLLOW_HOST
        refreshMode()
    }
    refreshMode()
    val contentCap = minOf((520 * density).toInt(), (resources.displayMetrics.heightPixels * 0.55f).toInt())
    container.addView(MaxHeightScrollView(this, contentCap).apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }, LinearLayout.LayoutParams(-1, -2))

    fun number(editor: EditText, current: Int, range: IntRange): Int? {
        if (!editor.isEnabled) return current
        val value = editor.text.toString().trim().toIntOrNull()
        if (value == null || value !in range) {
            editor.error = getString(R.string.player_buffer_invalid, range.first, range.last)
            return null
        }
        editor.error = null
        return value
    }
    fun save() {
        val nextSeconds = number(seconds, playerBufferSeconds,
            PlayerBufferConfig.MIN_SECONDS..PlayerBufferConfig.MAX_SECONDS) ?: return
        val nextMemory = number(memory, playerBufferMemoryMb,
            PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB) ?: return
        val nextDisk = number(disk, playerBufferDiskMb,
            PlayerBufferConfig.MIN_DISK_MB..PlayerBufferConfig.MAX_DISK_MB) ?: return
        val nextWifiOnly = wifiOnly.isChecked
        val saved = runCatching {
            val preferences = prefs()
            preferences.edit {
                putInt(FeaturePreferences.PLAYER_BUFFER_MODE, draftMode.value)
                putInt(FeaturePreferences.PLAYER_BUFFER_SECONDS, nextSeconds)
                putInt(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, nextMemory)
                putInt(FeaturePreferences.PLAYER_BUFFER_DISK_MB, nextDisk)
                putBoolean(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY, nextWifiOnly)
            }
            check(preferences.getInt(FeaturePreferences.PLAYER_BUFFER_MODE, -1) == draftMode.value)
            check(preferences.getInt(FeaturePreferences.PLAYER_BUFFER_SECONDS, -1) == nextSeconds)
            check(preferences.getInt(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, -1) == nextMemory)
            check(preferences.getInt(FeaturePreferences.PLAYER_BUFFER_DISK_MB, -1) == nextDisk)
            check(preferences.getBoolean(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY, !nextWifiOnly) == nextWifiOnly)
        }.onFailure { Log.e("BilibiliInnocentLab", "write player buffer strategy failed", it) }.isSuccess
        if (!saved) {
            toast(getString(R.string.player_buffer_save_failed))
            return
        }
        playerBufferMode = draftMode.value
        playerBufferSeconds = nextSeconds
        playerBufferMemoryMb = nextMemory
        playerBufferDiskMb = nextDisk
        playerBufferWifiOnly = nextWifiOnly
        updatePlayerBufferSummary()
        toast(getString(R.string.player_buffer_restart_tip))
        dismissWithAnimation(dialog, container) {}
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { label ->
        buttons.addView(TextView(this).apply {
            text = getString(label)
            textColor = if (label == R.string.dialog_confirm) monetColors.primary else getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (label == R.string.dialog_confirm) save() else dismissWithAnimation(dialog, container) {}
            }
        }, LinearLayout.LayoutParams(-2, -2))
    }
    container.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}
