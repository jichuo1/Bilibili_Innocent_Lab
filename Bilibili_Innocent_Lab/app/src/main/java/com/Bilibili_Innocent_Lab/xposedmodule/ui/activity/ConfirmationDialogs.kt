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
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ShellCommandRunner
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
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
 * 需要用户二次确认的操作类弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 重启宿主、自动亮度确认、适配确认、设置页 UI 兼容回退。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation() /
 * createTermsNeutralRoot()。
 *
 * ## 重启路径的约束没有变
 *
 * `restartBilibili` 与它的 su 探测（findSuPath / execShell）一起搬了过来，
 * 免 Root 分支仍然先经 `shouldUseNoRootRestartFlow` 判定、并在跳系统页之前
 * 调用 `flushNoRootSupportBeforeOpeningDetails` 把待写配置落盘——
 * 这条顺序是 NPatch 免 Root 路径的既有契约，改动前先读
 * docs/development_experience.md 里 2026-08-30 与 2026-09-02 的 NPatch 条目。
 */

/**
 * 二次确认弹窗：圆角半透明模态风格 + Material You 动效。
 * 弹窗采用 scale + alpha 动画（GPU 加速、不触发布局重绘，低功耗），
 * 符合 Material 3 的 emphasized easing 标准。
 */
internal fun MainActivity.showRestartConfirmDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val useNoRootFlow = shouldUseNoRootRestartFlow()
    val container = createModalContainer()

    // 标题
    container.addView(
        NativeTextView(this).apply {
            text = getString(
                if (useNoRootFlow) R.string.no_root_restart_confirm_title
                else R.string.restart_bilibili_confirm_title
            )
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    if (useNoRootFlow) {
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.no_root_restart_confirm_message)
                textColor = getColor(R.color.colorTextGray)
                textSize = 13f
                setLineSpacing(4 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )
    }

    // 按钮行
    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    // 取消按钮（文本按钮 + 标准 ripple）
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

    // 确认按钮（圆角 filled + 跟随圆角的 ripple）
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(
                if (useNoRootFlow) R.string.no_root_restart_open_app_details
                else R.string.dialog_confirm
            )
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
                    if (useNoRootFlow) {
                        flushNoRootSupportBeforeOpeningDetails()
                    } else {
                        restartBilibili()
                    }
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

    presentModalDialog(dialog, container, anchor, AnchorStyle.BUBBLE)
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

/**
 * 一键重启哔哩哔哩：先强制停止 B 站进程，延迟后再重新拉起。
 * 使用 root 的 am 命令（KernelSU 的 su 位于 /system/bin/su，需完整路径）。
 * 注意：不能用 monkey 拉起——monkey 在注入事件前会强制开启系统自动旋转
 * （accelerometer_rotation 0→1），副作用不可接受；改用 am start 指定主 Activity。
 */
private fun MainActivity.restartBilibili() {
    // 提前取出 applicationContext：Thread 与 Toast lambda 只持有它（全局单例），
    // 不再持有 Activity，避免 Activity 销毁后无法回收的内存泄漏。
    val appContext = applicationContext
    // 提前探测 su 路径（不同 root 方案 su 位置不同：KernelSU 在 /system/bin/su、
    // Magisk 新版在 /product/bin/su，硬编码会失败）；null 表示常见路径均无 su
    val suPath = findSuPath()
    Thread {
        // 先做一次幂等的授权探测（su -c id）：首次使用会触发 Root 管理器的授权
        // 弹窗，用户当场允许即继续；失败时区分「无 su 二进制」与「su 被拒绝」，
        // 给出对应指引而不是笼统的「重启失败」。
        // 授权窗给足 2 分钟：用户要解锁、切到管理器、可能还有倒计时确认，
        // 10s 的默认超时会把"还没点允许"误判成"Root 被拒绝"，重启整个流程直接不执行。
        val rootFailureRes = runCatching {
            val probeExit = execShell(suPath ?: "su", "-c", "id", timeoutMs = 120_000L)
            check(probeExit == 0) { "su probe exited with $probeExit" }
            null
        }.getOrElse { throwable ->
            Log.e("BilibiliInnocentLab", "su probe failed: $throwable")
            if (suPath == null) {
                R.string.restart_root_missing
            } else {
                R.string.restart_root_denied
            }
        }
        if (rootFailureRes != null) {
            Handler(Looper.getMainLooper()).post {
                appContext.toast(appContext.getString(rootFailureRes))
            }
            return@Thread
        }
        try {
            // 在同一个 root shell 中保存并恢复旋转策略。部分 ROM 会在 Activity
            // 重建期间临时改写这两个 system setting；只在读到合法值且启动后确实
            // 发生变化时写回，原来关闭/开启的自动旋转状态都保持不变。
            val restartExitCode = execShell(
                suPath ?: "su",
                "-c",
                buildRestartShellCommand()
            )
            check(restartExitCode == 0) { "restart shell exited with $restartExitCode" }
            Handler(Looper.getMainLooper()).post {
                appContext.toast(appContext.getString(R.string.restart_bilibili_done))
            }
        } catch (e: Exception) {
            Log.e("BilibiliInnocentLab", "restart bilibili failed: $e")
            Handler(Looper.getMainLooper()).post {
                appContext.toast(appContext.getString(R.string.restart_bilibili_failed))
            }
        }
    }.start()
}

/** 构造无用户输入的 root 重启命令；包名与 Activity 均为编译期常量。 */
private fun buildRestartShellCommand(): String =
    """
    auto_rotate=${'$'}(settings get system accelerometer_rotation 2>/dev/null);
    user_rotate=${'$'}(settings get system user_rotation 2>/dev/null);
    restore_rotation() {
        current_auto=${'$'}(settings get system accelerometer_rotation 2>/dev/null);
        case "${'$'}auto_rotate" in
            0|1) if [ "${'$'}current_auto" != "${'$'}auto_rotate" ]; then settings put system accelerometer_rotation "${'$'}auto_rotate"; fi ;;
        esac;
        current_user=${'$'}(settings get system user_rotation 2>/dev/null);
        case "${'$'}user_rotate" in
            0|1|2|3) if [ "${'$'}current_user" != "${'$'}user_rotate" ]; then settings put system user_rotation "${'$'}user_rotate"; fi ;;
        esac;
    };
    am force-stop ${HookEntry.TARGET_PACKAGE};
    stop_status=${'$'}?;
    if [ "${'$'}stop_status" -ne 0 ]; then restore_rotation; exit "${'$'}stop_status"; fi;
    sleep 0.8;
    am start -n ${HookEntry.TARGET_PACKAGE}/.MainActivityV2 >/dev/null 2>&1;
    start_status=${'$'}?;
    sleep 1;
    restore_rotation;
    exit "${'$'}start_status"
    """.trimIndent().replace("\n", " ")

/** 重启前确保当前 enabled 快照或关闭 tombstone 已完成一次有界 NPatch 写入。 */
private fun MainActivity.flushNoRootSupportBeforeOpeningDetails() {
    val bridge = noRootPrefsBridge
    if (bridge == null) {
        toast(getString(R.string.no_root_restart_sync_failed))
        openBilibiliAppDetails(this)
        return
    }
    noRootStatusView?.setText(R.string.no_root_status_syncing)
    val appContext = applicationContext
    val activityRef = WeakReference(this)
    NoRootSupportController.flushBeforeRestart(appContext, bridge) { result ->
        Handler(Looper.getMainLooper()).post {
            activityRef.get()?.finishNoRootRestartFlush(result)
        }
    }
}

/** 判据见 [NoRootSupportState.useNoRootRestartFlow]：`capable` 不再等于“有 Root”。 */
private fun MainActivity.shouldUseNoRootRestartFlow(): Boolean {
    val framework = RemoteHookConfigStore.status()
    return NoRootSupportState.useNoRootRestartFlow(
        standardCapable = framework.capable,
        frameworkName = framework.name,
        desiredEnabled = NoRootSupportStore.isDesiredEnabled(applicationContext),
        displayState = currentNoRootDisplayState(),
        frameworkProperties = framework.properties
    )
}

/**
 * 探测可用的 su 路径。不同 root 方案 su 位置不同：
 * KernelSU 通常在 /system/bin/su，Magisk 新版（Android 10+）在 /product/bin/su，
 * 旧版 Magisk/SuperSU 在 /system/xbin/su 或 /sbin/su。按常见顺序探测第一个存在的。
 * @return null 表示所有已知路径均不存在（设备可能未 Root，或 su 仅在非常规 PATH），
 *         调用方仍可用裸 "su" 尝试并由授权探测区分失败原因。
 */
private fun MainActivity.findSuPath(): String? {
    val candidates = arrayOf(
        "/product/bin/su",       // 新版 Magisk（Android 10+，如本次 9.0.0 小米设备）
        "/system/bin/su",        // KernelSU / 旧版 Magisk
        "/system/xbin/su",       // 部分 Magisk / SuperSU
        "/data/adb/ksu/bin/su",  // KernelSU 新版
        "/sbin/su",              // 旧版 SuperSU
        "/su/bin/su",            // 部分定制系统
    )
    for (path in candidates) {
        try {
            if (File(path).exists()) return path
        } catch (_: Throwable) {
            // 忽略无权限读取的路径，继续探测下一个
        }
    }
    return null
}

/**
 * 执行固定的 root shell 命令。输出流由独立读取线程持续排空，且超时后会
 * 终止子进程，避免 stdout/stderr pipe 或异常 root 实现造成设置页后台线程悬挂。
 *
 * [timeoutMs] 默认 10s；授权探测必须传更长的窗：首次使用会弹出 Root 管理器的
 * 授权确认，用户解锁、确认、可能还有倒计时，10s 内点不完就会被当成"Root 被拒绝"。
 */
private fun MainActivity.execShell(vararg cmd: String, timeoutMs: Long = 10_000L): Int =
    ShellCommandRunner.run(cmd.toList(), timeoutMs = timeoutMs)

/** 只有关闭操作的面板统一使用右下角按钮，与 GitHub 面板保持一致。 */
internal fun MainActivity.createPanelCloseRow(onClick: () -> Unit): NativeLinearLayout =
    NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(createPanelCloseButton(onClick))
    }
