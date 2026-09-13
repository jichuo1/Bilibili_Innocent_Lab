package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/** 设置开关与等待页共用入口；不通过动画中途的开关状态写入偏好。 */
internal fun MainActivity.showCommunicationCompatibilityConfirmDialog(anchor: View? = null) {
    if (CommunicationCompatibilityStore.isEnabled(applicationContext) ||
        !CommunicationCompatibilityStore.hasConsent(applicationContext)) return
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.communication_compatibility_mode)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    val warning = NativeTextView(this).apply {
        text = getString(R.string.communication_compatibility_warning)
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
        setLineSpacing(5 * density, 1f)
    }
    container.addView(NativeScrollView(this).apply { addView(warning) },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            minOf((300 * density).toInt(), (resources.displayMetrics.heightPixels * 0.42f).toInt())).apply {
            topMargin = (14 * density).toInt()
            bottomMargin = (16 * density).toInt()
        })
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.communication_compatibility_enable), filled = true) {
        // 先把标题还给来源，再改变通信策略；同步成功可能重建等待页。
        dismissWithAnimation(dialog, container) { setCommunicationCompatibilityEnabled(true) }
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * density).toInt() })
    container.addView(buttons)
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.setCommunicationCompatibilityEnabled(enabled: Boolean) {
    compatibilityPendingRetry?.cancel()
    val saved = CommunicationCompatibilityStore.setEnabled(applicationContext, enabled)
    compatibilityProgrammaticSwitch = true
    compatibilityModeSwitch?.isChecked = CommunicationCompatibilityStore.isEnabled(applicationContext)
    compatibilityProgrammaticSwitch = false
    if (!saved) { toast(getString(R.string.communication_compatibility_save_failed)); return }
    compatibilityRetryTracker.reset()
    updateCommunicationCompatibilityHint()
    toast(getString(if (enabled) R.string.communication_compatibility_enabled else R.string.communication_compatibility_disabled))
    if (enabled && UserTermsAuthorizationCoordinator.snapshot(applicationContext).consentState.isAcceptancePending) {
        retryPendingTermsWithCompatibility(manual = false)
    }
}

internal fun MainActivity.createCommunicationCompatibilityHint(): View {
    val density = resources.displayMetrics.density
    return NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        visibility = View.GONE
        background = selfRippleBackground(12f)
        setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        isClickable = true
        isFocusable = true
        addView(NativeTextView(this@createCommunicationCompatibilityHint).apply {
            text = getString(R.string.communication_compatibility_mode)
            textColor = monetColors.primary
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(NativeTextView(this@createCommunicationCompatibilityHint).apply {
            text = getString(R.string.communication_compatibility_hint)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
        setOnClickListener {
            if (CommunicationCompatibilityStore.isEnabled(applicationContext)) setCommunicationCompatibilityEnabled(false)
            else showCommunicationCompatibilityConfirmDialog(anchor = it)
        }
    }
}

internal fun MainActivity.updateCommunicationCompatibilityHint() {
    val hint = compatibilityRetryHint ?: return
    val state = UserTermsAuthorizationCoordinator.snapshot(applicationContext).consentState
    compatibilityRetryTracker.synchronize(state.pendingAcceptance?.revision)
    val pending = state.isAcceptancePending
    val enabled = CommunicationCompatibilityStore.isEnabled(applicationContext)
    val show = pending && (enabled || compatibilityRetryTracker.shouldOffer(pending, enabled))
    (hint as? NativeLinearLayout)?.let { row ->
        (row.getChildAt(0) as? NativeTextView)?.setText(if (enabled)
            R.string.communication_compatibility_disable else R.string.communication_compatibility_mode)
        (row.getChildAt(1) as? NativeTextView)?.setText(if (enabled)
            R.string.communication_compatibility_disable_tip else R.string.communication_compatibility_hint)
    }
    if (hint.isVisible == show) return
    hint.animate().cancel()
    val root = (hint.parent as? ViewGroup)?.parent as? ViewGroup
    val curve = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    val animated = android.animation.ValueAnimator.areAnimatorsEnabled()
    if (animated && root != null && root.isLaidOut) {
        TransitionManager.beginDelayedTransition(root, TransitionSet()
            .addTransition(ChangeBounds()).addTransition(Fade().addTarget(hint))
            .setDuration(320L).setInterpolator(curve))
    }
    hint.isVisible = show
    if (show) {
        hint.translationY = if (animated) 24 * resources.displayMetrics.density else 0f
        if (animated) hint.animate().translationY(0f).setDuration(320L).setInterpolator(curve).start()
        hint.announceForAccessibility(getString(R.string.communication_compatibility_hint))
    } else hint.translationY = 0f
}
