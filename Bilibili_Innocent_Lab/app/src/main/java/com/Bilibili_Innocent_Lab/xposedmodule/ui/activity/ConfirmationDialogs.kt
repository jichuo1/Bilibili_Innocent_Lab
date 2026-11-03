@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannels
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartConfigBarrier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartProbeState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoutePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RootRestartAccess
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AnchorStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.Companion.openBilibiliAppDetails
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import java.io.File
import java.lang.ref.WeakReference
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 需要用户二次确认的操作类弹窗，从 MainActivity 外移而来：
 * 重启宿主、自动亮度确认、适配确认、设置页 UI 兼容回退。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation() /
 * createTermsNeutralRoot()。
 *
 * 重启执行按 Root → Shizuku → 手动排序；框架判断只决定配置保存通道。
 * 所有方式均须先落盘当前配置，不能把旧回执或命令退出码当成新配置生效。
 */

/**
 * 二次确认弹窗：圆角半透明模态风格 + Material You 动效。
 * 弹窗采用 scale + alpha 动画（GPU 加速、不触发布局重绘，低功耗），
 * 符合 Material 3 的 emphasized easing 标准。
 */
/**
 * @param origin / @param cover 从另一张面板里打开时（例如 NPatch 向导的「重启并验证」），
 *   本面板从被点的按钮长出来、盖住那张面板而不关闭它，取消后露出它；为 null 时按 [anchor] 以气泡弹出。
 */
internal fun MainActivity.showRestartConfirmDialog(
    anchor: View? = null,
    resumeNoRoot: Boolean = false,
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    // 这里只决定配置保存通道；恢复手动向导不会把 LSPosed/LSPatch 切成 NPatch 配置。
    fun usesNoRootConfig() = usesNoRootRestartConfig()
    val container = createModalContainer()
    val title = NativeTextView(this).apply {
        setText(if (resumeNoRoot) R.string.no_root_restart_resume_title else R.string.restart_bilibili_confirm_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }
    container.addView(title, NativeLinearLayout.LayoutParams(-1, -2))
    val message = NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextGray)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
    }
    container.addView(message, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val hint = NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(3 * density, 1f)
    }
    container.addView(hint, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
    // 盖在更高的面板上时卡片会被撑到那张面板的高度：空档放在说明与按钮之间，按钮落在底部。
    if (cover != null) container.addView(android.widget.Space(this), NativeLinearLayout.LayoutParams(-1, 0, 1f))
    // 每种重启方式独占一整行（Root 在上、Shizuku 在下），底部一行左右均分「打开应用详情」与「取消」。
    var checking = true
    var rootPath: String? = null
    // 被三级面板盖住时不重排：覆盖矩形是点击那一刻取的，底下的面板变高变矮会从覆盖层边缘露出来。
    // 期间到达的最新状态先存着，三级面板收起时再补渲染。
    var covered = false
    var pending: RestartProbeState.Snapshot? = null
    lateinit var render: (RestartProbeState.Snapshot) -> Unit
    // 三级面板从被点的那一行长出来、盖住本面板而不关闭它，收起时再露出本面板（同 GitHub 面板的更新渠道）。
    // 两张矩形都必须在点击那一刻取。
    fun open(route: RestartRoute, source: View) {
        if (checking) return
        if (route == RestartRoute.ROOT && rootPath == null) {
            RestartCapabilities.warm(applicationContext, force = true)
            return
        }
        val origin = modalAnchorBounds(source)
        val cover = modalSurfaceBounds(dialog, container)
        covered = true
        showPrivilegedRestartDialog(
            usesNoRootConfig(), route,
            origin = origin,
            cover = cover,
            parentDialog = dialog,
            parentContainer = container,
            onClosed = {
                covered = false
                pending?.let { latest ->
                    pending = null
                    if (dialog.isShowing) render(latest)
                }
            }
        )
    }
    val routes = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    val rootAction = createRestartActionButton(R.string.restart_capabilities_checking_short).apply {
        setOnClickListener { open(RestartRoute.ROOT, it) }
    }
    val shizukuAction = createRestartActionButton(R.string.shizuku_restart_button).apply {
        setOnClickListener { open(RestartRoute.SHIZUKU, it) }
    }
    val retry = createRestartActionButton(R.string.restart_capabilities_retry).apply {
        setOnClickListener { RestartCapabilities.warm(applicationContext, force = true) }
    }
    routes.addView(rootAction, NativeLinearLayout.LayoutParams(-1, -2))
    routes.addView(shizukuAction, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    routes.addView(retry, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    container.addView(routes, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * density).toInt() })
    // 两个按钮等高：某一个折行时另一个跟着变高，底边对齐。
    val row = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }
    val manual = createRestartActionButton(if (resumeNoRoot) R.string.no_root_restart_launch_verify else R.string.no_root_restart_open_app_details).apply {
        setOnClickListener {
            dismissWithAnimation(dialog, container) {
                val noRoot = usesNoRootConfig()
                if (resumeNoRoot && noRoot) flushRestartBeforeOpeningDetails(noRoot, openHost = true)
                else if (resumeNoRoot) showPrivilegedRestartDialog(noRoot, RestartRoute.MANUAL)
                else flushRestartBeforeOpeningDetails(noRoot)
            }
        }
    }
    val cancel = createRestartActionButton(R.string.dialog_cancel).apply {
        setOnClickListener { dismissWithAnimation(dialog, container) {} }
    }
    row.addView(manual, NativeLinearLayout.LayoutParams(0, -1, 1f))
    row.addView(cancel, NativeLinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = (8 * density).toInt() })
    container.addView(row, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * density).toInt() })

    var observing = true
    render = { snapshot ->
        checking = snapshot.preferred == null
        rootPath = snapshot.root?.path
        val root = snapshot.root
        val shizuku = snapshot.shizuku
        fun renderRoute(button: android.widget.Button, route: RestartRoute, shown: Boolean) {
            button.visibility = if (shown) View.VISIBLE else View.GONE
            if (!shown) return
            button.setText(when {
                route == RestartRoute.ROOT && rootPath == null -> R.string.restart_root_retry
                route == RestartRoute.ROOT -> R.string.restart_root_button
                else -> R.string.shizuku_restart_button
            })
            button.isEnabled = canOpenRestartControls(false, route, shizuku)
            styleRestartActionButton(button, snapshot.preferred == route)
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
        if (checking) {
            // 检测期间只占一行：Root 行暂作占位，结果出来后再决定两行各自是否出现。
            rootAction.visibility = View.VISIBLE
            rootAction.setText(R.string.restart_capabilities_checking_short)
            rootAction.isEnabled = false
            styleRestartActionButton(rootAction, false)
            rootAction.alpha = 0.38f
            shizukuAction.visibility = View.GONE
        } else {
            renderRoute(rootAction, RestartRoute.ROOT, root?.visible == true || snapshot.preferred == RestartRoute.ROOT)
            renderRoute(shizukuAction, RestartRoute.SHIZUKU,
                shizuku?.visible == true || snapshot.preferred == RestartRoute.SHIZUKU)
        }
        styleRestartActionButton(cancel, false)
        styleRestartActionButton(manual, !checking && snapshot.preferred == RestartRoute.MANUAL)
        message.setText(when {
            resumeNoRoot -> R.string.no_root_restart_resume_message
            checking -> R.string.restart_capabilities_checking
            snapshot.preferred == RestartRoute.ROOT -> R.string.restart_root_first_message
            snapshot.preferred == RestartRoute.SHIZUKU -> R.string.restart_shizuku_fallback_message
            else -> R.string.no_root_restart_confirm_message
        })
        val rootHint = when (root?.status) {
            RootRestartAccess.Status.CANDIDATE -> R.string.restart_root_candidate
            RootRestartAccess.Status.DENIED -> R.string.restart_root_unavailable
            RootRestartAccess.Status.UNKNOWN -> R.string.restart_root_unknown
            else -> null
        }
        val shizukuHint = if (snapshot.preferred == RestartRoute.ROOT) null else when (shizuku?.status) {
            ShizukuAvailability.Status.STOPPED -> R.string.shizuku_stopped
            ShizukuAvailability.Status.UNSUPPORTED -> R.string.shizuku_unsupported
            ShizukuAvailability.Status.DISCONNECTED -> R.string.shizuku_disconnected
            ShizukuAvailability.Status.UNAUTHORIZED -> R.string.shizuku_unauthorized
            ShizukuAvailability.Status.DENIED -> R.string.shizuku_denied
            else -> null
        }
        hint.text = listOfNotNull(rootHint, shizukuHint).joinToString("\n") { getString(it) }
        hint.visibility = if (hint.text.isEmpty() || checking) View.GONE else View.VISIBLE
        retry.visibility = if (!checking && root?.status != RootRestartAccess.Status.UNKNOWN &&
            snapshot.preferred != RestartRoute.ROOT && shizuku?.status == ShizukuAvailability.Status.DISCONNECTED) View.VISIBLE else View.GONE
        styleRestartActionButton(retry, false)
        // 没有可用的特权方式时整组收起，底部一行直接接在说明下面。
        val anyRoute = rootAction.isVisible || shizukuAction.isVisible || retry.isVisible
        routes.isVisible = anyRoute
        (shizukuAction.layoutParams as NativeLinearLayout.LayoutParams).topMargin =
            if (rootAction.isVisible) (8 * density).toInt() else 0
        (row.layoutParams as NativeLinearLayout.LayoutParams).topMargin = ((if (anyRoute) 10 else 20) * density).toInt()
        row.requestLayout()
    }
    val subscription = RestartCapabilities.observe(applicationContext) { snapshot ->
        if (!observing || isFinishing || isDestroyed) return@observe
        if (covered) pending = snapshot else render(snapshot)
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { RestartCapabilities.warm(applicationContext) }
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    lifecycle.addObserver(observer)
    presentModalDialog(dialog, container, anchor.takeIf { cover == null },
        if (cover == null) AnchorStyle.BUBBLE else AnchorStyle.CONTAINER,
        morphAnchorBounds = origin, coverBounds = cover, onDismissed = {
        observing = false
        subscription.close()
        lifecycle.removeObserver(observer)
    })
}
/**
 * 「亮色模式气泡」二级确认（手动优先：自动跟随开启时切换手动开关 → 提示会关闭
 * 自动跟随，确认后关闭跟随并应用手动值，取消保持原样）。样式与 showAdaptConfirmDialog
 * 相同（模态容器 + 取消/确认按钮 + 进出动画）。
 *
 * @param onConfirm 确认回调（自动跟随关闭 + 手动值生效；由调用方负责 UI 动画同步）
 * @param onCancel  取消回调（开关 UI 复位）
 */
internal fun MainActivity.showAutoLightConfirmDialog(
    anchor: View? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.free_copy_light_mode)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.free_copy_light_mode_confirm_title)
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
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
            setOnClickListener {
                // 取消：开关 UI 由 onCancel 复位（不重建界面），保持自动跟随开启
                dismissWithAnimation(dialog, container) { onCancel() }
            }
        }
    )

    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_confirm)
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
            setOnClickListener {
                dismissWithAnimation(dialog, container) { onConfirm() }
            }
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

    presentModalDialog(dialog, container, anchor, onBackDismiss = onCancel)
}

/**
 * 「重新适配当前版本」二级确认菜单：样式与规格与「重启哔哩哔哩」确认弹窗
 * （showRestartConfirmDialog）完全一致（模态容器 + 取消/确认按钮 + 进出动画）。
 * 确认后清除版本适配缓存（VersionAdapter.clearCache），重启 B 站后自动重新定位。
 */
/**
 * 标题刻意**不**与来源行同名：这是破坏性操作的确认框，"确认清除适配缓存？"这个问句
 * 比复述行标题更重要。按 AGENTS.md 的约定，不同名时自动退化成只做容器形变——
 * 这里要的正是容器形变，文字平移是主动放弃的。
 */
internal fun MainActivity.showAdaptConfirmDialog(anchor: View? = null) {
    val activity = this
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.adapt_clear_confirm_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            text = getString(R.string.dialog_confirm)
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
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    runCatching { VersionAdapter.clearCache(activity, runCatching { prefs() }.getOrNull()) }
                    if (NoRootSupportStore.isDesiredEnabled(applicationContext)) {
                        NoRootSupportStore.markAdapterReset(applicationContext)
                        activity.roamingCacheStatusView?.setText(R.string.roaming_cache_unavailable)
                        activity.roamingCacheRestartView?.visibility = View.GONE
                        synchronizeNoRootSupportIfEnabled()
                    }
                    activity.toast(getString(R.string.adapt_manual_done))
                }
            }
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

/** 界面初始化异常时提供原生可退出页面，并保留全部用户状态。 */
internal fun MainActivity.showSettingsUiCompatibilityFallback(reason: String, failure: Throwable? = null) {
    if (failure == null) {
        Log.e("BilibiliInnocentLab", "settings UI compatibility fallback: $reason")
    } else {
        Log.e("BilibiliInnocentLab", "settings UI compatibility fallback: $reason", failure)
    }
    val density = resources.displayMetrics.density
    val root = createTermsNeutralRoot()
    val container = createModalContainer().apply {
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.settings_ui_compatibility_title)
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
            text = getString(R.string.settings_ui_compatibility_message)
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
    container.addView(
        createTermsActionButton(
            text = getString(R.string.user_terms_exit),
            filled = true
        ) { finish() },
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

/** 手动兜底同样先经过当前配置通道的保存屏障。 */
private fun MainActivity.flushRestartBeforeOpeningDetails(noRoot: Boolean, openHost: Boolean = false) {
    val bridge = noRootPrefsBridge ?: prefs()
    val appContext = applicationContext
    val activityRef = WeakReference(this)
    RestartConfigBarrier.flush(appContext, bridge, noRoot) { saved ->
        Handler(Looper.getMainLooper()).post {
            val activity = activityRef.get() ?: return@post
            // 应用详情是最后的兜底，即使保存失败也允许进入；返回后自动打开仍要求保存成功。
            activity.finishNoRootRestartFlush(if (saved) NoRootSupportController.FlushResult.SUCCESS
                else NoRootSupportController.FlushResult.FAILED, openHost = openHost)
        }
    }
}

/** 与发布权威源、标准发布器旁路保持一致；框架名称和执行权限不改变用户选中的配置通道。 */
private fun MainActivity.usesNoRootRestartConfig(): Boolean {
    return RestartRoutePolicy.usesNoRootConfig(
        desiredEnabled = NoRootSupportStore.isDesiredEnabled(applicationContext),
        displayState = currentNoRootDisplayState(),
        // 已关闭且收到确认的 NPatch 仍须保存停用文档，不能转去标准配置并等待启用回执。
        nPatchCarrier = HostConfigChannels.read(applicationContext) == HostConfigChannel.NPATCH
    )
}

/** 只有关闭操作的面板统一使用右下角按钮，与 GitHub 面板保持一致。 */
internal fun MainActivity.createPanelCloseRow(onClick: () -> Unit): NativeLinearLayout =
    NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(createPanelCloseButton(onClick))
    }
