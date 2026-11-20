package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.widget.TextView as NativeTextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import io.github.jichuo1.meguviz.android.extensions.textColor

/**
 * 弹窗正文的标准控件：说明行与状态卡。
 * 单选用 [createSlidingChoice]，开关用 MaterialSwitch，操作按钮用 [createTermsActionButton]，
 * 与其它弹窗共用同一套引擎控件和皮肤表面，不再在面板里各画一份。
 */

/** 灰色说明行：12sp、行距 4dp，与缓冲策略、组件拦截等弹窗的说明一致。 */
internal fun MainActivity.createModalTip(text: CharSequence): NativeTextView = NativeTextView(this).apply {
    this.text = text
    textColor = getColor(R.color.colorTextGray)
    textSize = 12f
    setLineSpacing(4 * resources.displayMetrics.density, 1f)
}

/** 状态卡：皮肤卡片表面承载一段会变化的状态文字，与发布时间面板的预览卡同一外观。 */
internal fun MainActivity.createModalStatusCard(): NativeTextView {
    val density = resources.displayMetrics.density
    return NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        background = skinCardBackground(monetColors.surfaceVariant, 14f)
    }
}
