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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.hikage.core.layout.LayoutParams
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
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.player_default_quality_dialog_title)
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

/** 使用百分比整数发布配置；非法输入留在弹窗内，跟随宿主是独立、明确的操作。 */
internal fun MainActivity.showPlayerSpeedDialog(longPress: Boolean, anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
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
        runCatching { prefs().edit { putInt(key, percent) } }.onSuccess {
            if (longPress) playerLongPressSpeedPercent = percent else playerDefaultSpeedPercent = percent
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

/** 评论最低等级选择：沿用播放器画质选择器的模态菜单与进退场动画。 */
internal fun MainActivity.showCommentMinLevelDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.comment_min_level_dialog_title)
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
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.danmaku_weight_dialog_title)
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
