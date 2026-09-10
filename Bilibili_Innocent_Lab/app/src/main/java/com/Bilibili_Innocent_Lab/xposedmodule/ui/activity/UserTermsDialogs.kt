@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryStore
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.extension.setContentView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import java.lang.ref.WeakReference
import android.R as Android_R
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/*
 * 用户条款授权链的界面，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 授权闸门、待确认页、拒绝页、条款弹窗、保存失败提示。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation() /
 * createTermsNeutralRoot() / createTermsActionButton()。
 *
 * ## 授权链的红线没有变，只是换了文件
 *
 * 这次搬迁**只动位置**：判定与落盘仍然是原来那一套
 * （`commitUserTermsDecision` 同步持久化成功后才放行；免 Root 分支仍经
 * `synchronizePendingTermsThroughNoRoot` 走跨应用同步）。
 *
 * 需要记住的边界：**授权链失败必须关闭全部 Hook**，不允许回退到模块私有文件、
 * ContentProvider 或 ordered broadcast。改这里之前先读 docs/architecture.md
 * 的授权链一节，以及 docs/development_experience.md 里 NPatch 相关条目。
 *
 * ## 什么留在 Activity 里
 *
 * 条款页的可变状态与视图引用（termsAuthorizationSnapshot、termsManagerLauncher、
 * termsDecisionActionInProgress、各 View 字段）仍住在 MainActivity 上，只放宽为
 * internal——它们是 per-Activity 的，一旦变成文件级顶层属性就会退化成进程级单例，
 * 跨 Activity 重建仍残留，那是行为改变。第 4 步会把这套状态机收进 Controller。
 */

/** 未决定时主界面不参与构建，仅保留中性背景并展示不可取消的条款窗口。 */
internal fun MainActivity.showUserTermsGate() {
    setContentView(createTermsNeutralRoot())
    runCatching { showUserTermsDialog() }.onFailure { throwable ->
        Log.e("BilibiliInnocentLab", "show user terms dialog failed", throwable)
        finish()
    }
}

/** 用户已经作出同意决定，但 API 102 快照尚未完整发布、读回并确认。 */
internal fun MainActivity.showPendingTermsPage(snapshot: UserTermsAuthorizationSnapshot) {
    val density = resources.displayMetrics.density
    val root = createTermsNeutralRoot()
    val container = createModalContainer().apply {
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.user_terms_pending_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.user_terms_pending_message)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            alpha = 0.82f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )
    val statusView = NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
    }
    termsPendingStatusView = statusView
    container.addView(
        statusView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (18 * density).toInt()
            bottomMargin = (12 * density).toInt()
        }
    )
    container.addView(
        createTermsDiagnosticsCard(),
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * density).toInt() }
    )

    val managerLauncher = createTermsManagerLauncher()
    termsManagerLauncher = managerLauncher
    container.addView(
        managerLauncher,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * density).toInt() }
    )
    container.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_retry_sync),
            filled = true
        ) {
            if (NoRootSupportStore.isDesiredEnabled(applicationContext)) {
                synchronizePendingTermsThroughNoRoot(enableFirst = false)
            } else {
                UserTermsAuthorizationCoordinator.retryPendingAcceptance(applicationContext)
            }
            termsAuthorizationSnapshot =
                UserTermsAuthorizationCoordinator.snapshot(applicationContext)
            termsAuthorizationSnapshot?.let(::renderPendingTermsUi)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_use_npatch_sync),
            filled = false
        ) {
            synchronizePendingTermsThroughNoRoot(enableFirst = true)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() }
    )
    container.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_decline),
            filled = false
        ) {
            if (termsDecisionActionInProgress) return@createTermsActionButton
            termsDecisionActionInProgress = true
            val result = UserTermsAuthorizationCoordinator.decline(applicationContext)
            if (result.succeeded) {
                userTermsDecision = UserTermsDecision.DECLINED
                finish()
            } else {
                termsDecisionActionInProgress = false
                toast(userTermsFailureMessage(result.failureCode))
                recreate()
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() }
    )

    val centeringFrame = NativeFrameLayout(this).apply {
        addView(
            container,
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(
                    (24 * density).toInt(),
                    (36 * density).toInt(),
                    (24 * density).toInt(),
                    (36 * density).toInt()
                )
            }
        )
    }
    root.addView(
        NativeScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(
                centeringFrame,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        },
        NativeFrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    setContentView(root)
    renderPendingTermsUi(snapshot)
}

/** 已明确拒绝时保持锁定，不显示任何模块配置入口。 */
internal fun MainActivity.showUserTermsDeclinedPage() {
    val density = resources.displayMetrics.density
    val root = createTermsNeutralRoot()
    val container = createModalContainer().apply {
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.user_terms_declined_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.user_terms_declined_message)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            alpha = 0.78f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_exit),
            filled = false
        ) { finish() },
        NativeLinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
    )
    buttonRow.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_read_again),
            filled = true
        ) { showUserTermsDialog() },
        NativeLinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (22 * density).toInt() }
    )

    root.addView(
        container,
        NativeFrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            setMargins(
                (24 * density).toInt(),
                (36 * density).toInt(),
                (24 * density).toInt(),
                (36 * density).toInt()
            )
        }
    )
    setContentView(root)
}

/** 条款正文可滚动，操作按钮固定在模态容器底部；触外、系统取消均不关闭。 */
internal fun MainActivity.showUserTermsDialog() {
    val activity = this
    activeConfirmDialog?.dismiss()
    termsDecisionActionInProgress = false
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.user_terms_dialog_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val bodyScroll = NativeScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        val bodyContent = NativeLinearLayout(activity).apply {
            orientation = NativeLinearLayout.VERTICAL
            addView(
                NativeTextView(activity).apply {
                    autoLinkMask = Linkify.WEB_URLS
                    text = getString(R.string.user_terms_body)
                    textColor = getColor(R.color.colorTextDark)
                    setLinkTextColor(monetColors.primary)
                    textSize = 14f
                    setLineSpacing(5 * density, 1f)
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                createTermsDiagnosticsCard(),
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (16 * density).toInt() }
            )
        }
        addView(
            bodyContent,
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    container.addView(
        bodyScroll,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = (14 * density).toInt()
            bottomMargin = (14 * density).toInt()
        }
    )

    // 遥测是独立的可选处理场景：固定显示在操作按钮上方，接受条款前即可关闭。
    val telemetryConsentSwitch =
        com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch(this, null).apply {
            text = getString(R.string.telemetry_terms_choice)
            textColor = getColor(R.color.colorTextGray)
            textSize = 14f
            isChecked = TelemetryStore.termsChoice(applicationContext)
        }
    val telemetryChoiceContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(
            (12 * density).toInt(),
            (8 * density).toInt(),
            (12 * density).toInt(),
            (8 * density).toInt()
        )
        background = selfRippleBackground(14f)
        addView(
            telemetryConsentSwitch,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            NativeTextView(activity).apply {
                text = getString(R.string.telemetry_terms_choice_summary)
                textColor = getColor(R.color.colorTextDark)
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(3 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (3 * density).toInt() }
        )
    }
    container.addView(
        telemetryChoiceContainer,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )

    // 保存失败的原因提示与可解析的框架管理器入口：默认隐藏，仅在失败时展示。
    val hintView = NativeTextView(this).apply {
        visibility = View.GONE
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
    }
    termsDialogHintView = hintView
    container.addView(
        hintView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )
    val managerLauncher = createTermsManagerLauncher()
    termsManagerLauncher = managerLauncher
    container.addView(
        managerLauncher,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_decline),
            filled = false
        ) {
            commitUserTermsDecision(
                dialog = dialog,
                container = container,
                accepted = false,
                telemetryEnabled = false
            )
        },
        NativeLinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
    )
    buttonRow.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_accept),
            filled = true
        ) {
            commitUserTermsDecision(
                dialog = dialog,
                container = container,
                accepted = true,
                telemetryEnabled = telemetryConsentSwitch.isChecked
            )
        },
        NativeLinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val root = NativeFrameLayout(this).apply {
        addView(
            container,
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(
                    (20 * density).toInt(),
                    (28 * density).toInt(),
                    (20 * density).toInt(),
                    (28 * density).toInt()
                )
            }
        )
    }

    dialog.setContentView(root)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnCancelListener { finish() }
    dialog.setOnDismissListener {
        if (activeConfirmDialog === dialog) activeConfirmDialog = null
        termsDialogHintView = null
        termsManagerLauncher = null
        termsDiagnosticsValueView = null
    }
    activeConfirmDialog = dialog
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawableResource(Android_R.color.transparent)
        setDimAmount(0f)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    renderTermsGateDiagnostics()
    container.post {
        container.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(260L)
            .setInterpolator(emphasizedDecelerate)
            .start()
    }
}

/**
 * 条款决定失败时按本地写入、框架连接和 API 能力分类，管理器入口只有在当前设备
 * 存在可由普通应用启动的显式 Activity 时才显示。
 */
internal fun MainActivity.showUserTermsSaveFailureHint(failureCode: String?) {
    val status = RemoteHookConfigStore.status()
    val message = userTermsFailureMessage(failureCode)
    val hintView = termsDialogHintView
    if (hintView == null) {
        // 弹窗已不在（理论上不可能：失败分支在 dismiss 前执行），退回 toast 兜底
        toast(message)
        return
    }
    hintView.text = message
    hintView.isVisible = true
    updateTermsManagerLauncher(status)
}

/** 条款门禁内的只读环境摘要；不提供开关、跳转或宿主进程查询能力。 */
private fun MainActivity.createTermsDiagnosticsCard(): NativeLinearLayout {
    val activity = this
    val density = resources.displayMetrics.density
    return NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        isClickable = false
        isFocusable = false
        setPadding(
            (14 * density).toInt(),
            (12 * density).toInt(),
            (14 * density).toInt(),
            (12 * density).toInt()
        )
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(
                ColorUtils.blendARGB(
                    monetColors.surfaceVariant,
                    monetColors.background,
                    0.14f
                )
            )
            setStroke(
                density.toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(monetColors.primary, 0x66)
            )
        }
        addView(
            NativeTextView(activity).apply {
                text = getString(R.string.user_terms_diagnostics_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            NativeTextView(activity).apply {
                textColor = getColor(R.color.colorTextDark)
                textSize = 12f
                alpha = 0.86f
                setLineSpacing(3 * density, 1f)
                termsDiagnosticsValueView = this
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )
    }
}

private fun MainActivity.commitUserTermsDecision(
    dialog: Dialog,
    container: View,
    accepted: Boolean,
    telemetryEnabled: Boolean
) {
    if (termsDecisionActionInProgress) return
    termsDecisionActionInProgress = true
    val result = if (accepted) {
        UserTermsAuthorizationCoordinator.beginAcceptance(applicationContext)
    } else {
        UserTermsAuthorizationCoordinator.decline(applicationContext)
    }
    if (!result.succeeded) {
        termsDecisionActionInProgress = false
        showUserTermsSaveFailureHint(result.failureCode)
        return
    }

    val telemetryChoiceSaved = TelemetryStore.writeConsentChoice(
        applicationContext,
        enabled = accepted && telemetryEnabled
    )
    if (accepted && !telemetryChoiceSaved) {
        toast(getString(R.string.telemetry_choice_save_failed))
    }

    termsConsentState = result.state
    userTermsDecision = result.state.decision
    dismissWithAnimation(dialog, container) {
        if (accepted) {
            recreate()
        } else {
            finish()
        }
    }
}

/**
 * 条款等待页尚未构建主设置树，只能由用户在此显式选择 NPatch 后建立免 Root 意图。
 * 同步成功会由 Controller 在远端完整读回后推进待接受条款并触发界面重建。
 */
private fun MainActivity.synchronizePendingTermsThroughNoRoot(enableFirst: Boolean) {
    if (AndroidVersion.isLessThan(AndroidVersion.P)) {
        toast(getString(R.string.no_root_status_unsupported_os))
        return
    }
    val bridge = runCatching { prefs() }.getOrNull() ?: run {
        toast(getString(R.string.no_root_enable_failed))
        return
    }
    val appContext = applicationContext
    if (enableFirst &&
        !NoRootSupportController.setDesiredEnabled(appContext, enabled = true)
    ) {
        toast(getString(R.string.no_root_enable_failed))
        return
    }
    val generation = NoRootSupportController.beginSynchronization(appContext) ?: run {
        toast(getString(R.string.no_root_enable_failed))
        return
    }
    termsPendingStatusView?.setText(R.string.no_root_status_checking)
    val activityRef = WeakReference(this)
    Thread({
        NoRootSupportController.synchronize(appContext, bridge, generation) {
            val activity = activityRef.get() ?: return@synchronize
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.termsAuthorizationSnapshot =
                    UserTermsAuthorizationCoordinator.snapshot(appContext)
                activity.termsAuthorizationSnapshot?.let(activity::renderPendingTermsUi)
            }
        }
    }, "InnocentLab-NoRootTermsSync").apply { isDaemon = true }.start()
}

private fun MainActivity.createTermsManagerLauncher(): NativeTextView {
    val density = resources.displayMetrics.density
    return NativeTextView(this).apply {
        visibility = View.GONE
        text = getString(R.string.user_terms_open_framework_manager)
        textColor = monetColors.primary
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(monetColors.surfaceVariant)
        }
        setPadding(
            (14 * density).toInt(),
            (9 * density).toInt(),
            (14 * density).toInt(),
            (9 * density).toInt()
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val intent = FrameworkManagerLauncher.resolve(
                applicationContext,
                RemoteHookConfigStore.status()
            ) ?: run {
                isVisible = false
                return@setOnClickListener
            }
            runCatching { startActivity(intent) }
                .onFailure { isVisible = false }
        }
    }
}

private fun MainActivity.userTermsFailureMessage(failureCode: String?): String {
    val status = RemoteHookConfigStore.status()
    return when {
        failureCode == UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE ->
            getString(R.string.user_terms_save_failed)
        !status.connected -> getString(R.string.user_terms_need_framework_enable)
        !status.capable && status.name.isNotBlank() -> getString(
            R.string.user_terms_need_api102_named,
            status.name,
            status.apiVersion
        )
        !status.capable -> getString(R.string.user_terms_need_api102)
        else -> getString(R.string.user_terms_publish_failed)
    }
}
