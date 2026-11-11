package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import io.github.jichuo1.meguviz.android.extensions.textColor
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/**
 * "加入 NPatch 作用域"的确认框。
 *
 * NPatch Manager 的 `requestScope` 不弹任何确认，直接把"本模块 × 哔哩哔哩"写进它的数据库，所以
 * 同意只能在这里取得；确认后才会调用 [MainActivity.joinNPatchScope]。样式与适配确认框一致
 * （模态容器 + 取消/确认按钮 + 进出动画）。
 */
internal fun MainActivity.showScopeJoinConfirmDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.no_root_scope_confirm_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.no_root_scope_confirm_message)
            textColor = getColor(R.color.colorTextGray)
            textSize = 14f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * density).toInt()
        }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_cancel)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.no_root_scope_confirm_action)
            textColor = monetColors.onPrimary
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((22 * density).toInt(), (11 * density).toInt(), (22 * density).toInt(), (11 * density).toInt())
            val radius = 20 * density
            val content = GradientDrawable().apply { cornerRadius = radius; setColor(monetColors.primary) }
            val rippleMask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)),
                content,
                rippleMask
            )
            skinActionButton(this, filled = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) { joinNPatchScope() } }
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (16 * density).toInt()
        }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (22 * density).toInt()
        }
    )

    presentModalDialog(dialog, container, anchor)
}
