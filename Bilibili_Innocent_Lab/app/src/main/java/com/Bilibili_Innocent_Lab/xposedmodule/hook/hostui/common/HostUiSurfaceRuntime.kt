package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import java.lang.ref.WeakReference

/**
 * 宿主 UI 表面内部公共运行时（仅供内部核心与 Tier-A 使用，严禁暴露给外部 `:plugin-api`）。
 *
 * 复用 [HostResourceRegistry] 提供统一可回收资源注册封装，并向外裁剪提供只读 [HostThemeSnapshot]，
 * 隔离 `HostSurfaceScope`、`LumenSurfaceSession` 与宿主 `View`。
 */
internal object HostUiSurfaceRuntime {
    private const val DEFAULT_RESOURCE_TAG = "host_ui_surface"

    /**
     * 向 [HostResourceRegistry] 登记统一裁剪回调并返回幂等 [AutoCloseable] 租约句柄。
     */
    fun registerResource(
        tag: String = DEFAULT_RESOURCE_TAG,
        onTrim: (HostTrimSeverity) -> Unit
    ): AutoCloseable = HostResourceRegistry.register(tag, onTrim)

    /**
     * 按分级裁剪回调登记宿主 UI 资源；未单独指定 [onCritical] 时自动回退至 [onModerate]。
     */
    fun registerResource(
        tag: String = DEFAULT_RESOURCE_TAG,
        onHidden: (() -> Unit)? = null,
        onModerate: (() -> Unit)? = null,
        onCritical: (() -> Unit)? = null
    ): AutoCloseable = HostResourceRegistry.register(
        tag = tag,
        onHidden = onHidden,
        onModerate = onModerate,
        onCritical = onCritical
    )

    /**
     * 弱引用绑定 [HostSurfaceScope] 至 [HostResourceRegistry]，在中度压力时 `detach`，
     * 紧急压力时 `close`，避免反向强持有宿主视图树。
     */
    fun bindSurfaceScope(
        scope: HostSurfaceScope,
        tag: String = "host_surface_scope",
        detachOnHidden: Boolean = false
    ): AutoCloseable {
        val scopeRef = WeakReference(scope)
        val leaseHolder = arrayOfNulls<AutoCloseable>(1)
        val lease = HostResourceRegistry.register(tag) { severity ->
            val target = scopeRef.get()
            if (target == null) {
                leaseHolder[0]?.close()
                return@register
            }
            when (severity) {
                HostTrimSeverity.HIDDEN -> if (detachOnHidden) target.detach()
                HostTrimSeverity.MODERATE -> target.detach()
                HostTrimSeverity.CRITICAL -> target.close()
            }
        }
        leaseHolder[0] = lease
        return lease
    }

    /**
     * 创建受 [HostResourceRegistry] 托管的局部 [HostSurfaceScope] 及对应统一清理句柄。
     */
    fun createManagedSurfaceScope(
        anchor: View,
        colors: HostChromeColors,
        tag: String = "host_surface_scope",
        samplingBackend: LumenSurfaceBackend = LumenSurfaceBackend.AUTO,
        appearance: HostChromeAppearance = HostChromeAppearance(),
        reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames,
        detachOnHidden: Boolean = false
    ): Pair<HostSurfaceScope, AutoCloseable> {
        val scope = HostSurfaceScope(
            anchor = anchor,
            colors = colors,
            samplingBackend = samplingBackend,
            appearance = appearance,
            reuseGpuFrames = reuseGpuFrames
        )
        val lease = bindSurfaceScope(scope, tag = tag, detachOnHidden = detachOnHidden)
        return scope to AutoCloseable {
            try {
                lease.close()
            } finally {
                scope.close()
            }
        }
    }

    /**
     * 当前仍处于活跃状态的宿主资源租约数量。
     */
    fun activeResourceCount(): Int = HostResourceRegistry.activeCount()

    /**
     * 构建只读宿主主题快照，仅向插件与外观配方暴露纯数值配色与密度信息。
     */
    fun snapshotTheme(
        isDark: Boolean,
        surfaceColor: Int,
        accentColor: Int,
        textPrimaryColor: Int,
        textSecondaryColor: Int,
        density: Float,
        cornerRadiusDp: Float = 16f
    ): HostThemeSnapshot = HostThemeSnapshot(
        isDark = isDark,
        surfaceColor = surfaceColor,
        accentColor = accentColor,
        textPrimaryColor = textPrimaryColor,
        textSecondaryColor = textSecondaryColor,
        density = density,
        cornerRadiusDp = cornerRadiusDp
    )
}
