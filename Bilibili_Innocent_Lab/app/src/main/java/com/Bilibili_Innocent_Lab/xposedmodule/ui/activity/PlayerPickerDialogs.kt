@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameRateTarget
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.textToString
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.EditText as NativeEditText
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 播放/评论/弹幕相关的取值弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation()，
 * 那套东西承载了返回手势接管、图标锚点形变、气泡摆放与背景毛玻璃的完整时序，
 * 不能各自复制一份。只有真正被一级界面调用的入口是 internal，其余文件私有。
 *
 * 这些弹窗仍直接读写 MainActivity 上的镜像字段（playerDefaultQualityQn、
 * commentMinLevel 之类），所以那些字段一并放宽成了 internal。等 UI 树也外移后
 * （第 2 步），它们应当改成从设置快照读取，届时这里的直接引用要跟着收敛。
 */

/** 播放器默认画质选择：只写模块配置，实际 Hook 在 B 站下次主进程启动时安装。 */
internal fun MainActivity.showPlayerQualityDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            // 标题必须与来源行**同一个字符串**，否则 ModalTitleMotion 配不上、文字平移
            // 静默退化成只有容器形变（`ModalTitleMotionSpec.matches` 比的是渲染文本）。
            text = getString(R.string.player_default_quality)
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

    presentModalDialog(dialog, container, anchor)
}

private fun MainActivity.showPlayerChoiceDialog(
    title: String,
    current: Int,
    options: List<Pair<Int, String>>,
    anchor: View?,
    onSelected: (Int) -> Boolean
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = title
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
    val rows = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    options.forEachIndexed { index, (value, label) ->
        rows.addView(
            createGitHubMenuRow(label, "", value == current) {
                if (onSelected(value)) dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = (4 * density).toInt()
            }
        )
    }
    // 按选项数收敛：解码方式只有 3 项、优先视频解码 4 项，且这两处的行都不带副标题，
    // 原先写死的 360dp 会在选项下方留出一大片空白。换成 MaxHeightScrollView：
    // 内容少就按内容高，只有多到超过上限才停住并开始滚动。
    // 上限再与屏高取小，横屏/小屏下不会把下面的关闭按钮顶出面板。
    val listCap = minOf(
        (360 * density).toInt(),
        (resources.displayMetrics.heightPixels * 0.44f).toInt()
    )
    container.addView(MaxHeightScrollView(this, listCap).apply {
        addView(rows, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2))
    container.addView(createPanelCloseRow {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (18 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.showPlayerCodecPreferenceDialog(anchor: View? = null) {
    val options = listOf(
        PlayerCodecPreference.FOLLOW_HOST.value to getString(R.string.player_codec_preference_follow),
        PlayerCodecPreference.H264.value to getString(R.string.player_codec_preference_h264),
        PlayerCodecPreference.H265.value to getString(R.string.player_codec_preference_h265),
        PlayerCodecPreference.AV1.value to getString(R.string.player_codec_preference_av1)
    )
    showPlayerChoiceDialog(getString(R.string.player_codec_preference), playerCodecPreference, options, anchor) { value ->
        val next = PlayerCodecPreference.fromValue(value).value
        if (!savePlayerIntPreference(
                FeaturePreferences.PLAYER_CODEC_PREFERENCE,
                next,
                "write codec preference failed"
            )
        ) {
            toast(getString(R.string.player_codec_setting_save_failed))
            return@showPlayerChoiceDialog false
        }
        playerCodecPreference = next
        updatePlayerCodecSummaries()
        true
    }
}

internal fun MainActivity.showPlayerDecodeModeDialog(anchor: View? = null) {
    val options = listOf(
        PlayerDecodeMode.FOLLOW_HOST.value to getString(R.string.player_decode_mode_follow),
        PlayerDecodeMode.FORCE_HARDWARE.value to getString(R.string.player_decode_mode_hardware),
        PlayerDecodeMode.FORCE_SOFTWARE.value to getString(R.string.player_decode_mode_software)
    )
    showPlayerChoiceDialog(getString(R.string.player_decode_mode), playerDecodeMode, options, anchor) { value ->
        val next = PlayerDecodeMode.fromValue(value).value
        if (!savePlayerIntPreference(
                FeaturePreferences.PLAYER_DECODE_MODE,
                next,
                "write decode mode failed"
            )
        ) {
            toast(getString(R.string.player_codec_setting_save_failed))
            return@showPlayerChoiceDialog false
        }
        playerDecodeMode = next
        updatePlayerCodecSummaries()
        true
    }
}

/** 补帧帧率上限：只是上限，实际目标还受系统实际刷新与本机能力约束。 */
internal fun MainActivity.showFrameInterpolationMaxFpsDialog(current: Int, anchor: View?, onSaved: (Int) -> Unit) {
    val options = FrameRateTarget.CEILINGS.map { it to getString(R.string.player_frame_max_fps_value, it) }
    showPlayerChoiceDialog(getString(R.string.player_frame_max_fps), current, options, anchor) { value ->
        if (!savePlayerIntPreference(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS, value,
                "write frame interpolation max fps failed")) {
            toast(getString(R.string.player_codec_setting_save_failed))
            return@showPlayerChoiceDialog false
        }
        onSaved(value)
        true
    }
}

/** 只在明确确认之后回调；返回、取消或点外部均不改变设置。 */
internal fun MainActivity.showFrameInterpolationThermalConfirmDialog(anchor: View?, onConfirm: () -> Unit) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_frame_thermal_confirm_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_frame_thermal_confirm_message)
        textColor = getColor(R.color.colorTextGray)
        textSize = 14f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (12 * density).toInt(); bottomMargin = (16 * density).toInt()
    })
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.player_frame_thermal_confirm_accept), filled = true) {
        dismissWithAnimation(dialog, container) { onConfirm() }
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * density).toInt() })
    container.addView(buttons)
    presentModalDialog(dialog, container, anchor)
}

/** apply() 不阻塞 UI；立即读回只用于发现本次写入是否被当前 bridge 拒绝。 */
private fun MainActivity.savePlayerIntPreference(key: String, value: Int, logMessage: String): Boolean =
    runCatching {
        val preferences = prefs()
        preferences.edit { putInt(key, value) }
        check(preferences.getInt(key, Int.MIN_VALUE) == value)
    }.onFailure { throwable ->
        Log.e("BilibiliInnocentLab", logMessage, throwable)
    }.isSuccess

/** 使用百分比整数发布配置；非法输入留在弹窗内，跟随宿主是独立、明确的操作。 */
internal fun MainActivity.showPlayerSpeedDialog(
    longPress: Boolean,
    anchor: View? = null,
    bypassConflictCheck: Boolean = false
) {
    if (!longPress && !bypassConflictCheck && playerSpeedMenuPercents.isNotEmpty()) {
        showPlayerSpeedConflictDialog(openingMenuOptions = false, anchor = anchor)
        return
    }
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val current = if (longPress) playerLongPressSpeedPercent else playerDefaultSpeedPercent
    container.addView(NativeTextView(this).apply {
        text = getString(if (longPress) R.string.player_long_press_speed else R.string.player_default_speed)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_speed_input_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val editor = NativeEditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        hint = getString(R.string.player_speed_input_hint)
        if (current != PlayerSpeedConfig.FOLLOW_HOST) setText(PlayerSpeedConfig.formatMultiplier(current))
        setSelection(text.length)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(getColor(R.color.colorTextGray))
        textSize = 16f
        setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
        }
    }
    container.addView(editor, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
    fun save(percent: Int) {
        val key = if (longPress) FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT
            else FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT
        runCatching {
            prefs().edit {
                putInt(key, percent)
                if (!longPress && percent != PlayerSpeedConfig.FOLLOW_HOST) {
                    putString(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS, "")
                }
            }
        }.onSuccess {
            if (longPress) {
                playerLongPressSpeedPercent = percent
            } else {
                playerDefaultSpeedPercent = percent
                if (percent != PlayerSpeedConfig.FOLLOW_HOST) {
                    playerSpeedMenuPercents = emptyList()
                }
            }
            updatePlayerSpeedSummaries()
            dismissWithAnimation(dialog, container) {}
        }.onFailure {
            Log.e("BilibiliInnocentLab", "write player speed prefs failed", it)
            editor.error = getString(R.string.player_speed_save_failed)
        }
    }
    container.addView(createGitHubMenuRow(
        title = getString(R.string.player_speed_follow_host),
        subtitle = getString(R.string.player_speed_follow_host_tip),
        highlight = current == PlayerSpeedConfig.FOLLOW_HOST
    ) { save(PlayerSpeedConfig.FOLLOW_HOST) },
        NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { label ->
        buttons.addView(NativeTextView(this).apply {
            text = getString(label)
            textColor = if (label == R.string.dialog_confirm) monetColors.primary else getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (label == R.string.dialog_cancel) {
                    dismissWithAnimation(dialog, container) {}
                } else {
                    val percent = PlayerSpeedConfig.parseMultiplier(editor.textToString())
                    if (percent == null || percent == PlayerSpeedConfig.FOLLOW_HOST) {
                        editor.error = getString(R.string.player_speed_invalid)
                    } else save(percent)
                }
            }
        })
    }
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}

/**
 * 宿主倍速菜单档位自定义：支持 0.1～8 倍（最多两位小数）的多个档位快捷切换。
 * 与“默认播放倍速”同属普通播放倍速控制，二者互斥；与“长按倍速自定义”互不影响。
 */
internal fun MainActivity.showPlayerSpeedMenuDialog(
    anchor: View? = null,
    bypassConflictCheck: Boolean = false
) {
    if (!bypassConflictCheck && playerDefaultSpeedPercent != PlayerSpeedConfig.FOLLOW_HOST) {
        showPlayerSpeedConflictDialog(openingMenuOptions = true, anchor = anchor)
        return
    }
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val current = playerSpeedMenuPercents
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_speed_menu)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_speed_menu_input_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val editor = NativeEditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = false
        maxLines = 3
        filters = arrayOf(InputFilter.LengthFilter(PlayerSpeedConfig.MAX_MENU_SPEC_LENGTH))
        hint = getString(R.string.player_speed_menu_input_hint)
        if (current.isNotEmpty()) setText(PlayerSpeedConfig.formatMenuInput(current))
        setSelection(text.length)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(getColor(R.color.colorTextGray))
        textSize = 16f
        setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
        }
    }
    container.addView(editor, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
    fun save(percents: List<Int>) {
        val encoded = PlayerSpeedConfig.encodeMenuPercents(percents)
        runCatching {
            prefs().edit {
                putString(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS, encoded)
                if (percents.isNotEmpty()) {
                    putInt(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, PlayerSpeedConfig.FOLLOW_HOST)
                }
            }
        }.onSuccess {
            playerSpeedMenuPercents = percents
            if (percents.isNotEmpty()) {
                playerDefaultSpeedPercent = PlayerSpeedConfig.FOLLOW_HOST
            }
            updatePlayerSpeedSummaries()
            dismissWithAnimation(dialog, container) {}
        }.onFailure {
            Log.e("BilibiliInnocentLab", "write player speed menu prefs failed", it)
            editor.error = getString(R.string.player_speed_save_failed)
        }
    }
    container.addView(createGitHubMenuRow(
        title = getString(R.string.player_speed_menu_preset_default),
        subtitle = getString(R.string.player_speed_menu_preset_default_tip),
        highlight = false
    ) {
        val template = PlayerSpeedConfig.formatMenuInput(PlayerSpeedConfig.DEFAULT_HOST_MENU_PERCENTS)
        editor.setText(template)
        editor.setSelection(editor.text.length)
        editor.error = null
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    container.addView(createGitHubMenuRow(
        title = getString(R.string.player_speed_follow_host),
        subtitle = getString(R.string.player_speed_follow_host_tip),
        highlight = current.isEmpty()
    ) { save(emptyList()) },
        NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })
    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { label ->
        buttons.addView(NativeTextView(this).apply {
            text = getString(label)
            textColor = if (label == R.string.dialog_confirm) monetColors.primary else getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (label == R.string.dialog_cancel) {
                    dismissWithAnimation(dialog, container) {}
                } else {
                    val parsed = PlayerSpeedConfig.parseMenuMultipliers(editor.textToString())
                    if (parsed.isNullOrEmpty()) {
                        editor.error = getString(R.string.player_speed_menu_invalid)
                    } else save(parsed)
                }
            }
        })
    }
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}

/**
 * 默认播放倍速与倍速菜单档位互斥二级提示弹窗：
 * 当用户已经开启其中一个、又尝试开启另一个时弹出提示，说明二者不能同时开启（不影响长按倍速）。
 */
private fun MainActivity.showPlayerSpeedConflictDialog(
    openingMenuOptions: Boolean,
    anchor: View? = null
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.player_speed_conflict_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    })
    container.addView(NativeTextView(this).apply {
        text = getString(
            if (openingMenuOptions) R.string.player_speed_conflict_menu_vs_default_message
            else R.string.player_speed_conflict_default_vs_menu_message
        )
        textColor = getColor(R.color.colorTextGray)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    container.addView(createGitHubMenuRow(
        title = getString(
            if (openingMenuOptions) R.string.player_speed_conflict_switch_to_menu
            else R.string.player_speed_conflict_switch_to_default
        ),
        subtitle = getString(
            if (openingMenuOptions) R.string.player_speed_conflict_switch_to_menu_tip
            else R.string.player_speed_conflict_switch_to_default_tip
        ),
        highlight = true
    ) {
        // 确认切换后先编辑，最终保存时再在同一个 Editor 内清除冲突项；取消编辑不丢失旧配置。
        dismissWithAnimation(dialog, container) {
            if (openingMenuOptions) {
                showPlayerSpeedMenuDialog(anchor = anchor, bypassConflictCheck = true)
            } else {
                showPlayerSpeedDialog(longPress = false, anchor = anchor, bypassConflictCheck = true)
            }
        }
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
    container.addView(createPanelCloseRow {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}

/** 评论最低等级选择：沿用播放器画质选择器的模态菜单与进退场动画。 */
internal fun MainActivity.showCommentMinLevelDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            // 复用来源行标题（文字平移要求）；`comment_min_level_dialog_title` 仍留着，
            // 它是 SettingsCatalog 里这一项的 labelRes，两者用途不同。
            text = getString(R.string.comment_min_level_filter)
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
    presentModalDialog(dialog, container, anchor)
}

/** 弹幕权重阈值选择；与评论等级选择共用同一套弹窗结构与关闭动画。 */
internal fun MainActivity.showDanmakuWeightDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            // 同上：复用来源行标题；`danmaku_weight_dialog_title` 是 SettingsCatalog 的 labelRes。
            text = getString(R.string.danmaku_weight_filter)
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
    (DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT)
        .forEachIndexed { index, weight ->
            options.addView(
                createGitHubMenuRow(
                    title = getString(R.string.danmaku_weight_value, weight),
                    subtitle = getString(R.string.danmaku_weight_option_tip, weight),
                    highlight = weight == danmakuWeightMinimum
                ) {
                    danmakuWeightMinimum = weight
                    runCatching {
                        prefs().edit {
                            putInt(
                                FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM,
                                weight
                            )
                        }
                    }.onFailure { throwable ->
                        Log.e(
                            "BilibiliInnocentLab",
                            "write danmaku weight prefs failed",
                            throwable
                        )
                    }
                    danmakuWeightSummaryView?.text = getString(
                        R.string.danmaku_weight_current,
                        weight
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
    presentModalDialog(dialog, container, anchor)
}

private fun MainActivity.updatePlayerQualitySummary() {
    playerQualitySummaryView?.text = getString(
        R.string.player_default_quality_current,
        playerQualityLabel(playerDefaultQualityQn)
    )
}
