package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.PrivilegedRestartController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.PrivilegedRestartController.State
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.startActivityOrElse
import io.github.jichuo1.meguviz.android.extensions.textColor
import android.widget.Button as NativeButton

/** 真实 Button：同一行操作保持触控高度，文字可随字体大小换行。 */
internal fun MainActivity.createRestartActionButton(@StringRes label: Int): NativeButton = NativeButton(this).apply {
    val density = resources.displayMetrics.density
    setText(label)
    isAllCaps = false
    textSize = 13f
    letterSpacing = 0f
    gravity = Gravity.CENTER
    minWidth = 0
    minHeight = (52 * density).toInt()
    setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())
    stateListAnimator = null
}

internal fun MainActivity.styleRestartActionButton(button: TextView, primary: Boolean) {
    val radius = 20 * resources.displayMetrics.density
    val surface = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (primary) monetColors.primary else Color.TRANSPARENT)
        if (!primary) setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(monetColors.primary, 0x66))
    }
    button.textColor = if (primary) monetColors.onPrimary else monetColors.primary
    button.typeface = Typeface.create(Typeface.DEFAULT, if (primary) Typeface.BOLD else Typeface.NORMAL)
    button.foreground = null
    button.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.primary, 0x33)),
        surface, GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) })
    skinActionButton(button, filled = primary)
}

/**
 * @param origin / @param cover / @param parentDialog / @param parentContainer 与
 *   [showUpdateChannelDialog] 同义：从重启确认面板里打开时，本面板从被点的那一行长出来、盖住确认面板
 *   而不关闭它；「更换方式」或返回只收起本面板、露出确认面板，「取消」两张一起收。
 *   全部为 null 时退回居中缩放入场，「更换方式」重新打开确认面板。
 */
internal fun MainActivity.showPrivilegedRestartDialog(
    noRoot: Boolean,
    route: RestartRoute,
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null,
    parentDialog: Dialog? = null,
    parentContainer: LinearLayout? = null,
    onClosed: (() -> Unit)? = null
) {
    fun coveredParentShowing() = parentDialog?.isShowing == true && parentContainer != null
    // 与本面板的退场并行，不串行等：两张卡片一起收。
    fun closeCoveredParent() {
        val parent = parentDialog ?: return
        val parentView = parentContainer ?: return
        if (parent.isShowing) dismissWithAnimation(parent, parentView) {}
    }
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val density = resources.displayMetrics.density
    fun label(size: Float) = TextView(this).apply {
        textSize = size
        textColor = getColor(R.color.colorTextDark)
        setLineSpacing(4 * density, 1f)
    }.also { container.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() }) }
    label(17f).setText(when (route) {
        RestartRoute.ROOT -> R.string.restart_root_button
        RestartRoute.SHIZUKU -> R.string.shizuku_restart_entry
        RestartRoute.MANUAL -> R.string.no_root_restart_resume_title
    })
    label(13f).setText(if (route == RestartRoute.MANUAL) R.string.no_root_restart_resume_message else R.string.shizuku_restart_description)
    val status = label(14f)
    val action = createRestartActionButton(R.string.shizuku_save_restart).also {
        styleRestartActionButton(it, true)
        container.addView(it, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (8 * density).toInt()
            bottomMargin = (10 * density).toInt()
        })
    }
    var lastState = State.STOPPED
    val controller = PrivilegedRestartController(applicationContext, noRootPrefsBridge ?: prefs(), noRoot,
        foreground = { !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && dialog.isShowing },
        changed = { state, uid ->
            lastState = state
            val message = getString(when (state) {
                State.CHECKING -> R.string.restart_capabilities_checking
                State.ROOT_AVAILABLE -> R.string.restart_root_candidate
                State.ROOT_READY -> R.string.restart_root_ready
                State.ROOT_REQUESTING -> R.string.restart_root_requesting
                State.ROOT_UNAVAILABLE -> R.string.restart_root_unavailable
                State.ROOT_MISSING -> R.string.restart_root_not_found
                State.MISSING -> R.string.shizuku_missing
                State.STOPPED -> R.string.shizuku_stopped
                State.UNSUPPORTED -> R.string.shizuku_unsupported
                State.UNAUTHORIZED -> R.string.shizuku_unauthorized
                State.DENIED -> R.string.shizuku_denied
                State.READY, State.AUTHORIZED -> if (route == RestartRoute.MANUAL) R.string.no_root_restart_resume_message else R.string.shizuku_ready
                State.DISCONNECTED -> R.string.shizuku_disconnected
                State.PERMISSION_REQUESTED -> R.string.shizuku_requesting
                State.SAVING -> R.string.shizuku_saving
                State.BINDING -> R.string.shizuku_binding
                State.STOPPING -> R.string.shizuku_stopping
                State.LAUNCHING -> R.string.shizuku_launching
                State.VERIFYING -> R.string.shizuku_verifying
                State.SAVE_FAILED -> R.string.shizuku_save_failed
                State.STOP_FAILED -> R.string.shizuku_stop_failed
                State.LAUNCH_FAILED -> R.string.shizuku_launch_failed
                State.RECEIPT_TIMEOUT -> R.string.shizuku_receipt_timeout
                State.CONFIG_CHANGED -> R.string.shizuku_config_changed
                State.CANCELLED -> R.string.shizuku_cancelled
                State.CONFIRMED -> R.string.shizuku_confirmed
                State.DISABLED_CONFIRMED -> R.string.shizuku_disabled_confirmed
                State.BUSY -> R.string.shizuku_busy
            })
            status.text = if (uid != null) getString(R.string.shizuku_status_uid, message, uid) else message
            status.isVisible = route != RestartRoute.MANUAL || uid != null ||
                (state != State.READY && state != State.AUTHORIZED)
            action.setText(when (state) {
                State.CHECKING -> R.string.restart_capabilities_checking_short
                State.ROOT_AVAILABLE, State.ROOT_UNAVAILABLE -> R.string.restart_root_authorize
                State.ROOT_REQUESTING -> R.string.restart_root_requesting
                State.ROOT_MISSING -> R.string.restart_capabilities_retry
                State.MISSING -> R.string.shizuku_install
                State.STOPPED, State.DENIED, State.UNSUPPORTED -> R.string.shizuku_open_manager
                State.UNAUTHORIZED -> R.string.shizuku_authorize
                else -> if (route == RestartRoute.MANUAL) R.string.no_root_restart_launch_verify else R.string.shizuku_save_restart
            })
            action.isEnabled = state !in setOf(State.CHECKING, State.ROOT_REQUESTING, State.PERMISSION_REQUESTED,
                State.SAVING, State.BINDING, State.STOPPING, State.LAUNCHING, State.VERIFYING)
        }, route = route)
    action.setOnClickListener {
        when (lastState) {
            State.ROOT_MISSING -> RestartCapabilities.warm(applicationContext, force = true)
            State.MISSING -> startActivityOrElse(Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri()))
            State.STOPPED, State.DENIED, State.UNSUPPORTED -> packageManager.getLaunchIntentForPackage(PrivilegedRestartController.MANAGER)?.let { startActivityOrElse(it) }
            else -> controller.act()
        }
    }
    // 底部一行左右均分「更换方式」与「取消」，与确认面板的底部一行同一排法。
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    createRestartActionButton(R.string.restart_change_method).also { other ->
        styleRestartActionButton(other, false)
        other.setOnClickListener {
            controller.close()
            if (coveredParentShowing()) dismissWithAnimation(dialog, container) {}
            else dismissWithAnimation(dialog, container) { showRestartConfirmDialog() }
        }
        footer.addView(other, LinearLayout.LayoutParams(0, -2, 1f))
    }
    createRestartActionButton(R.string.dialog_cancel).also { cancel ->
        styleRestartActionButton(cancel, false)
        cancel.setOnClickListener {
            // 用户点击时立即取消；不能等退场动画结束后才撤销在途的特权请求。
            controller.close()
            closeCoveredParent()
            dismissWithAnimation(dialog, container) {}
        }
        footer.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * density).toInt() })
    }
    container.addView(footer, LinearLayout.LayoutParams(-1, -2))
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (lastState !in setOf(State.CONFIRMED, State.DISABLED_CONFIRMED, State.RECEIPT_TIMEOUT)) controller.refresh()
        }
        override fun onStop(owner: LifecycleOwner) = controller.onBackground()
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    lifecycle.addObserver(observer)
    // 与现有重启确认共用模态容器、皮肤及动画；有来源与覆盖矩形时走同一套覆盖式展开。
    presentModalDialog(dialog, container, morphAnchorBounds = origin, coverBounds = cover, onDismissed = {
        lifecycle.removeObserver(observer)
        controller.close()
        onClosed?.invoke()
    })
    controller.refresh()
}
