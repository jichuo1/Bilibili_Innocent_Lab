package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/**
 * 「获取 access_key」授权前的风险二次确认；只有点了确认按钮才写入授权。
 * 已授权时直接返回（关闭授权不需要确认）。[onAuthorized] 在确认并写入之后回调（强力模式面板据此勾上本项）。
 *
 * [cover] 传父面板的可见表面矩形（见 [MainActivity.modalSurfaceBounds]）时按子面板呈现：
 * 父面板留在下面不被硬关。强力模式面板从这里发起授权时必须传，否则共用底座会把父面板
 * dismiss 掉，回调里的勾选写进一个已销毁的窗口，本项第一次勾选必然失效。
 */
internal fun MainActivity.showBiliAccessKeyConfirmDialog(
    anchor: View? = null,
    cover: SettingsBackupMotionRect? = null,
    onAuthorized: (() -> Unit)? = null
) {
    if (biliAccessKeyAuthorized) return
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.bili_access_key_confirm_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    val warning = NativeTextView(this).apply {
        text = getString(R.string.bili_access_key_confirm_message)
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
        setLineSpacing(5 * density, 1f)
    }
    container.addView(NativeScrollView(this).apply { addView(warning) },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            minOf((360 * density).toInt(), (resources.displayMetrics.heightPixels * 0.5f).toInt())).apply {
            topMargin = (14 * density).toInt()
            bottomMargin = (16 * density).toInt()
        })
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.bili_access_key_confirm_accept), filled = true) {
        dismissWithAnimation(dialog, container) {
            setBiliAccessKeyAuthorized(true)
            onAuthorized?.invoke()
        }
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * density).toInt() })
    container.addView(buttons)
    presentModalDialog(dialog, container, anchor, coverBounds = cover)
}

/**
 * 写入授权并同步两处界面：兼容区的授权开关、AI 区强力模式入口的摘要。
 * 撤销授权时强力模式的「获取 access_key」一并写成关闭（屏蔽发布者不受影响），重新授权后需在面板里再勾。
 */
internal fun MainActivity.setBiliAccessKeyAuthorized(authorized: Boolean) {
    val saved = runCatching {
        prefs().edit(commit = true) {
            putBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, authorized)
            if (!authorized) putBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK, false)
        }
    }.onFailure { t ->
        Log.e("BilibiliInnocentLab", "write access key authorization failed", t)
    }.isSuccess
    if (saved) {
        biliAccessKeyAuthorized = authorized
        if (!authorized) blockAiDeclaredVideosPrecheck = false
    }
    biliAccessKeyProgrammaticSwitch = true
    biliAccessKeySwitch?.isChecked = biliAccessKeyAuthorized
    biliAccessKeyProgrammaticSwitch = false
    updateAiStrongModeEntry()
    if (!saved) {
        toast(getString(R.string.bili_access_key_save_failed))
        return
    }
    toast(getString(if (authorized) R.string.bili_access_key_authorized else R.string.bili_access_key_revoked))
}
