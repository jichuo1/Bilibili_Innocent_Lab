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
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.startActivityOrElse
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast

/** 向导只展示现有事实；每个会修改配置或启动宿主的动作均由用户点击。 */
internal fun MainActivity.showNPatchSetupDialog(step: Int = 0, anchor: View? = null) {
    val page = step.coerceIn(0, 2)
    val policy = NoRootSetupPolicy.forChannel(HostConfigChannels.read(this))
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(if (policy.lspatch) R.string.lspatch_setup_page else R.string.npatch_setup_page, page + 1)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    val profile = NPatchRuntimeProfiles.read(this)
    val blocked = EmbeddedModePolicy.isSettingsBlocked(this)
    val unknown = getString(R.string.npatch_profile_unknown)
    val mode = getString(when (profile.mode) {
        NPatchMode.MANAGER -> R.string.npatch_profile_manager
        NPatchMode.EMBEDDED -> R.string.npatch_profile_embedded
        NPatchMode.UNKNOWN -> R.string.npatch_profile_unknown
    })
    val body = if (blocked) getString(R.string.embedded_mode_unsupported) else if (policy.lspatch) {
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
    val message = TextView(this).apply {
        text = body
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
        setLineSpacing(5 * density, 1f)
        // 公共皮肤会裁剪滚动容器圆角，正文留出内边距，避免首尾字形被圆角切掉。
        setPadding((12 * density).toInt(), (10 * density).toInt(),
            (12 * density).toInt(), (10 * density).toInt())
    }
    container.addView(ScrollView(this).apply { addView(message) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * density).toInt()
        })
    fun action(label: Int, filled: Boolean = false, click: () -> Unit) {
        container.addView(createTermsActionButton(getString(label), filled = filled) {
            dismissWithAnimation(dialog, container, click)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * density).toInt()
        })
    }
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
        action(R.string.no_root_support_enable, filled = true) {
            enableAndSynchronizeNoRootSupport()
            showNPatchSetupDialog(1)
        }
    }
    if (page == 2) {
        if (!blocked) action(R.string.npatch_setup_restart, filled = true) { showRestartConfirmDialog() }
        action(R.string.npatch_setup_diagnostics) { launchDiagnostics() }
    }
    if (page > 0) action(R.string.npatch_setup_previous) { showNPatchSetupDialog(page - 1) }
    action(if (page == 2) R.string.npatch_setup_done else R.string.npatch_setup_next) {
        if (page < 2) showNPatchSetupDialog(page + 1)
    }
    presentModalDialog(dialog, container, anchor)
}
