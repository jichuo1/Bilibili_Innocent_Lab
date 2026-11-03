package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.EmbeddedModePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannels
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.startActivityOrElse
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast

/**
 * 向导的三页叠成一摞，与 GitHub 面板的「更新渠道」同一套覆盖式展开：
 * - 「下一步」：下一页从这个按钮长出来、盖住当前页，当前页不关；
 * - 「上一步」或返回：只收起当前页，露出下面那页（露出时刷新其中的状态），不重建；
 * - 「开启免 Root 支持」：就地刷新本页；
 * - 打开管理器、模块应用详情、诊断：向导留在原处，回到本应用时刷新当前页；
 * - 「重启并验证」：重启面板从按钮长出来盖住向导，取消后露出这一页；
 * - 「完成」：整摞一起收。
 */
private class NPatchSetupLayer(val dialog: Dialog, val container: LinearLayout, val refresh: () -> Unit)

/** 向导只展示现有事实；每个会修改配置或启动宿主的动作均由用户点击。 */
internal fun MainActivity.showNPatchSetupDialog(step: Int = 0, anchor: View? = null) {
    val stack = ArrayList<NPatchSetupLayer>()
    // 跳到其他应用再回来时刷新最上面那页；整摞收起后自行注销。
    val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { stack.lastOrNull()?.refresh?.invoke() }
    }
    lifecycle.addObserver(observer)
    showNPatchSetupPage(step, anchor, stack, onStackEmpty = { lifecycle.removeObserver(observer) })
}

private fun MainActivity.showNPatchSetupPage(
    step: Int,
    anchor: View?,
    stack: MutableList<NPatchSetupLayer>,
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null,
    onStackEmpty: () -> Unit
) {
    val page = step.coerceIn(0, 2)
    val policy = NoRootSetupPolicy.forChannel(HostConfigChannels.read(this))
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    // 盖在上一页上时，下面那页就是返回的去处；没有它（直接打开某页）时退回旧的重建方式。
    val below = stack.lastOrNull()?.takeIf { cover != null && it.dialog.isShowing }
    container.addView(TextView(this).apply {
        text = getString(if (policy.lspatch) R.string.lspatch_setup_page else R.string.npatch_setup_page, page + 1)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    val blocked = EmbeddedModePolicy.isSettingsBlocked(this)
    fun body(): String {
        val profile = NPatchRuntimeProfiles.read(this)
        val unknown = getString(R.string.npatch_profile_unknown)
        val mode = getString(when (profile.mode) {
            NPatchMode.MANAGER -> R.string.npatch_profile_manager
            NPatchMode.EMBEDDED -> R.string.npatch_profile_embedded
            NPatchMode.UNKNOWN -> R.string.npatch_profile_unknown
        })
        return if (blocked) getString(R.string.embedded_mode_unsupported) else if (policy.lspatch) {
            val lspatch = LspatchRuntimeProfiles.read(this)
            when (page) {
                0 -> getString(R.string.lspatch_setup_profile,
                    lspatch.loaderVersion.takeIf { it > 0 }?.toString() ?: unknown,
                    lspatch.loaderApi.takeIf { it > 0 }?.toString() ?: unknown) +
                    "\n\n" + getString(R.string.lspatch_setup_manager)
                1 -> getString(R.string.lspatch_setup_direct) + "\n\n" + getString(R.string.no_root_setup_background)
                else -> getString(R.string.npatch_setup_verify)
            }
        } else when (page) {
            0 -> getString(R.string.npatch_profile_installed, mode,
                profile.signatureLevel?.toString() ?: unknown,
                profile.loaderVersionCode?.toString() ?: unknown, profile.loaderApi?.toString() ?: unknown) +
                "\n\n" + getString(if (profile.mode == NPatchMode.EMBEDDED)
                    R.string.npatch_setup_embed else R.string.npatch_setup_manager)
            1 -> getString(R.string.npatch_setup_enable) + "\n\n" +
                getString(noRootStatusText(currentNoRootDisplayState()))
            else -> getString(R.string.npatch_setup_verify) + "\n\n" +
                getString(noRootStatusText(currentNoRootDisplayState()))
        }
    }
    val message = TextView(this).apply {
        text = body()
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
        setLineSpacing(5 * density, 1f)
        // 公共皮肤会裁剪滚动容器圆角，正文留出内边距，避免首尾字形被圆角切掉。
        setPadding((12 * density).toInt(), (10 * density).toInt(),
            (12 * density).toInt(), (10 * density).toInt())
    }
    // 先为底部操作预留空间；窗口较矮或字体较大时由正文滚动，不能把按钮挤到零高度。
    container.addView(ScrollView(this).apply { addView(message) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = (16 * density).toInt()
        })
    // 整摞一起收：下面各页与本页的退场并行，不串行等。
    fun closeAll(then: () -> Unit = {}) {
        stack.filter { it.dialog !== dialog && it.dialog.isShowing }
            .forEach { dismissWithAnimation(it.dialog, it.container) {} }
        dismissWithAnimation(dialog, container, then)
    }
    fun action(label: Int, filled: Boolean = false, click: (View) -> Unit): View {
        lateinit var button: View
        button = createTermsActionButton(getString(label), filled = filled) { click(button) }
        container.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        return button
    }
    var enable: View? = null
    fun refresh() {
        message.text = body()
        enable?.visibility = if (NoRootSupportStore.isDesiredEnabled(this)) View.GONE else View.VISIBLE
    }
    // 子面板从 [source] 长出来、盖住本页；两张矩形都在点击那一刻取。
    fun coverFrom(source: View) = modalAnchorBounds(source) to modalSurfaceBounds(dialog, container)
    if (page == 0) {
        action(R.string.no_root_setup_open_manager) {
            val intent = FrameworkManagerLauncher.resolvePackage(this, policy.managerPackage)
            if (intent == null || !startActivityOrElse(intent)) toast(getString(R.string.no_root_setup_manager_unavailable))
        }
    }
    if (page == 1) {
        action(R.string.no_root_setup_module_details) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            if (!startActivityOrElse(intent)) toast(getString(R.string.no_root_restart_open_failed))
        }
    }
    if (page == 1 && !blocked && policy.needsOptIn && !NoRootSupportStore.isDesiredEnabled(this)) {
        enable = action(R.string.no_root_support_enable, filled = true) {
            enableAndSynchronizeNoRootSupport()
            refresh()
        }
    }
    if (page == 2) {
        if (!blocked) action(R.string.npatch_setup_restart, filled = true) { source ->
            val (from, over) = coverFrom(source)
            showRestartConfirmDialog(origin = from, cover = over)
        }
        action(R.string.npatch_setup_diagnostics) { launchDiagnostics() }
    }
    if (page > 0) action(R.string.npatch_setup_previous) {
        if (below != null) dismissWithAnimation(dialog, container) {}
        else dismissWithAnimation(dialog, container) { showNPatchSetupDialog(page - 1) }
    }
    action(if (page == 2) R.string.npatch_setup_done else R.string.npatch_setup_next) { source ->
        if (page < 2) {
            val (from, over) = coverFrom(source)
            showNPatchSetupPage(page + 1, null, stack, from, over, onStackEmpty)
        } else closeAll()
    }
    val layer = NPatchSetupLayer(dialog, container, ::refresh)
    stack += layer
    presentModalDialog(dialog, container, anchor, morphAnchorBounds = origin, coverBounds = cover, onDismissed = {
        stack.remove(layer)
        if (stack.isEmpty()) onStackEmpty()
        else window.decorView.post {
            // 被别的面板硬关（不是自己收起露出下一页）时，下面那几页不能孤零零留在新面板后面。
            val active = activeConfirmDialog
            if (stack.none { it.dialog === active } && active?.isShowing == true &&
                active !== below?.dialog) {
                stack.toList().forEach { if (it.dialog.isShowing) dismissWithAnimation(it.dialog, it.container) {} }
            } else below?.takeIf { it.dialog.isShowing }?.refresh?.invoke()
        }
    })
}
