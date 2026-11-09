package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.CheckBox as NativeCheckBox
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/**
 * 强力模式里真正生效的两项（按面板顺序）。
 * 「获取 access_key」只有在通用授权也在时才算开着——与宿主侧 `AiDeclaredVideoPolicy.effectivePrecheck` 一致。
 */
internal fun aiStrongModeActiveItems(blockAuthor: Boolean, precheck: Boolean, authorized: Boolean): List<Int> =
    buildList {
        if (blockAuthor) add(R.string.ai_declared_block_author)
        if (precheck && authorized) add(R.string.ai_declared_precheck)
    }

internal fun MainActivity.aiStrongModeSummary(): String {
    val items = aiStrongModeActiveItems(
        blockAiDeclaredVideosStrongMode, blockAiDeclaredVideosPrecheck, biliAccessKeyAuthorized
    )
    if (items.isEmpty()) return getString(R.string.ai_declared_strong_mode_summary_off)
    return getString(R.string.ai_declared_strong_mode_summary_on, items.joinToString("、") { getString(it) })
}

/** 总开关与两项勾选变化后刷新入口：总开关关着时整行置灰不可点。 */
internal fun MainActivity.updateAiStrongModeEntry() {
    aiStrongModeEntry?.apply {
        val enabled = isBlockAiDeclaredVideosEnabled()
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }
    aiStrongModeSummaryView?.text = aiStrongModeSummary()
}

/**
 * 强力模式二级勾选面板：屏蔽发布者 / 获取 access_key，各自带说明，保存后一次写入。
 * 勾「获取 access_key」而通用授权还没开时，先弹风险确认；确认后授权立即写入，本项保持勾选等待保存。
 */
internal fun MainActivity.showAiStrongModeDialog(anchor: View? = null) {
    if (!isBlockAiDeclaredVideosEnabled()) return
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.block_ai_declared_videos_strong_mode)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.block_ai_declared_videos_strong_mode_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = (7 * density).toInt() })

    fun option(labelRes: Int, tipRes: Int, checked: Boolean, topMarginDp: Int): NativeCheckBox {
        val box = NativeCheckBox(this).apply {
            text = getString(labelRes)
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            isChecked = checked
            isFocusable = true
        }
        container.addView(box, NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (topMarginDp * density).toInt() })
        container.addView(NativeTextView(this).apply {
            text = getString(tipRes)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(4 * density, 1f)
        }, NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply {
                marginStart = (12 * density).toInt()
                marginEnd = (8 * density).toInt()
                bottomMargin = (4 * density).toInt()
            })
        return box
    }

    val authorBox = option(
        R.string.ai_declared_block_author, R.string.ai_declared_block_author_tip,
        blockAiDeclaredVideosStrongMode, topMarginDp = 12
    )
    val precheckBox = option(
        R.string.ai_declared_precheck, R.string.ai_declared_precheck_tip,
        blockAiDeclaredVideosPrecheck && biliAccessKeyAuthorized, topMarginDp = 8
    )
    var programmatic = false
    precheckBox.setOnCheckedChangeListener { box, checked ->
        if (programmatic || !checked || biliAccessKeyAuthorized) return@setOnCheckedChangeListener
        // 未授权：先弹回未勾选，确认授权后再勾上。
        programmatic = true
        box.isChecked = false
        programmatic = false
        // 授权确认框必须以子面板盖在本面板上（cover 传本面板可见表面）：
        // 不传的话共用底座会硬关本面板，授权回调里的勾选落进已销毁的窗口，
        // "获取 access_key"第一次勾选必然失效。
        showBiliAccessKeyConfirmDialog(
            anchor = box,
            cover = modalSurfaceBounds(dialog, container)
        ) {
            programmatic = true
            box.isChecked = biliAccessKeyAuthorized
            programmatic = false
        }
    }

    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        val author = authorBox.isChecked
        val precheck = precheckBox.isChecked && biliAccessKeyAuthorized
        val saved = runCatching {
            prefs().edit(commit = true) {
                putBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE, author)
                putBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK, precheck)
            }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.ai_declared_strong_mode_save_failed))
            return@createTermsActionButton
        }
        blockAiDeclaredVideosStrongMode = author
        blockAiDeclaredVideosPrecheck = precheck
        updateAiStrongModeEntry()
        toast(getString(R.string.ai_declared_strong_mode_saved))
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = (8 * density).toInt()
    })
    container.addView(buttons, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (14 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}
