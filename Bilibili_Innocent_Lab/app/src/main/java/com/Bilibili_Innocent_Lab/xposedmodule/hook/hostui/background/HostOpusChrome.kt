package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 图文保留宿主栏位和滚动动画；退出中的关注提示必须裁剪在底栏上方。 */
internal class HostOpusChrome private constructor(root: ViewGroup, guide: View, bottom: View,
    top: View, private val owned: MutableMap<View, Drawable>) : AutoCloseable {
    private class Style(night: Boolean) {
        val palette = LumenPalette.neutral(night)
        val header = options(night, header = true)
        val bottom = options(night, header = false)

        private fun options(night: Boolean, header: Boolean) = LumenSurfaceOptions(
            material = if (header) LumenSurfaceMaterial.FROSTED else LumenSurfaceMaterial.STATIC,
            role = SurfaceRole.TOP_BAR, radiusDp = 0f,
            color = if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
            tintOpacity = if (header) .78f else .94f, fallbackTintOpacity = .94f,
            edgeEnabled = false, backdropOpacity = 1f,
            sampling = LumenSurfaceSampling(enabled = header, refractionEnabled = false,
                blurRadiusDp = 16f, softwareBlurRadiusDp = 10f, maxSoftwarePixels = 48_000)
        )
    }

    private val root = WeakReference(root)
    private val guide = WeakReference(guide)
    private val bottom = WeakReference(bottom)
    private val top = WeakReference(top)
    private val originalIndex = root.indexOfChild(guide)
    private val originalParams = guide.layoutParams
    private val viewport = WeakReference(FrameLayout(root.context).apply {
        clipChildren = true
        clipToPadding = true
    })
    private val bindings = WeakHashMap<View, LumenSurfaceBinding>()
    private val stickyTitleIds = IntArray(stickyTitleNames.size) { Int.MIN_VALUE }
    private val stickyTitleViews = Array<WeakReference<View>?>(stickyTitleNames.size) { null }
    private var session: LumenSurfaceSession? = null
    private var dark: Boolean? = null
    private var wallpaper: Drawable? = null
    private var samplingState = Long.MIN_VALUE
    private val statusBarState = StatusBarAppearanceState()
    private var statusBarDecor: WeakReference<View>? = null
    private val source = object : LumenVersionedContentSource {
        override val coordinateView: View get() = requireNotNull(this@HostOpusChrome.root.get())
        override val excludesSurfaces: Boolean = true
        override val observeCoordinateViewInvalidations: Boolean = false
        override val sourceEpoch: Long get() = 0L
        override val availability: LumenSourceAvailability get() = LumenSourceAvailability.READY
        override val contentVersion: Long
            get() {
                val root = this@HostOpusChrome.root.get() ?: return 0L
                val bg = root.background
                val state = (bg as? HostBackgroundDrawable)?.samplingState ?: 0L
                var version = state
                version = version * 31L + System.identityHashCode(bg)
                version = version * 31L + if (dark == true) 1L else 0L
                return version and Long.MAX_VALUE
            }
        override fun drawContent(canvas: Canvas) {
            // 只取独立壁纸，不绘制页面子树，避免采样到栏位自身和封面播放器。
            this@HostOpusChrome.root.get()?.background?.draw(canvas)
        }
    }

    init {
        val clip = requireNotNull(viewport.get())
        root.removeView(guide)
        root.addView(clip, originalIndex, originalParams)
        // 宿主仍持有原 View，并继续按 61dp 平移；外层视口始终位于底栏上方。
        clip.addView(guide, FrameLayout.LayoutParams(-1, -1))
    }

    fun apply(night: Boolean) {
        val root = root.get() ?: return
        val style = if (night) darkStyle else lightStyle
        val session = session ?: LumenSurfaceSession(root.context, style.palette,
            LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false)).also { session = it }
        val changed = dark != night
        if (changed) { session.updatePalette(style.palette); dark = night }
        fun surface(view: View?, header: Boolean = false) {
            view ?: return
            val binding = if (header) session.ensureSource(view, style.header, source) else session.ensure(view, style.bottom)
            if (binding !== bindings[view]) {
                bindings[view] = binding
                HostMemoryPressureBridge.registerBinding(binding)
                view.background?.let { owned[view] = it }
            }
        }
        surface(bottom.get())
        surface(guide.get())
        val top = top.get()
        // 封面阶段沿用原生圆形返回/更多按钮；吸顶文字出现后承托整行标题。
        var sticky = false
        for (i in stickyTitleNames.indices) {
            var id = stickyTitleIds[i]
            if (id == Int.MIN_VALUE) {
                id = root.resources.getIdentifier(stickyTitleNames[i], "id", root.context.packageName)
                stickyTitleIds[i] = id
            }
            if (id == 0) continue
            val cached = stickyTitleViews[i]?.get()?.takeIf { it.isAttachedToWindow && it.id == id }
            val resolved = cached ?: root.findViewById<View>(id)?.also { stickyTitleViews[i] = WeakReference(it) }
            if (resolved?.visibility == View.VISIBLE) {
                sticky = true
                break
            }
        }
        if (sticky) surface(top, header = true)
        else top?.let { bindings.remove(it)?.close(); owned.remove(it) }
        val background = root.background
        val state = (background as? HostBackgroundDrawable)?.samplingState ?: 0L
        if (wallpaper !== background || samplingState != state || changed) {
            wallpaper = background; samplingState = state
            session.notifyContentChanged()
        }
        // 图文封面在状态栏以下，系统文字应按壁纸深浅显示，不跟随封面按钮的白色。
        val decor = root.rootView
        applyStatusBarAppearance(decor, lightStatusBars = !night)
    }

    private fun applyStatusBarAppearance(decor: View, lightStatusBars: Boolean) {
        statusBarDecor = WeakReference(decor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decor.windowInsetsController ?: return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val currentLight = (controller.systemBarsAppearance and mask) != 0
            statusBarState.captureOriginalIfUnset(currentLight)
            val targetAppearance = if (lightStatusBars) mask else 0
            if ((controller.systemBarsAppearance and mask) != targetAppearance) {
                controller.setSystemBarsAppearance(targetAppearance, mask)
            }
            statusBarState.markApplied(lightStatusBars)
        } else {
            @Suppress("DEPRECATION")
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION")
            val current = decor.systemUiVisibility
            val currentLight = (current and mask) != 0
            statusBarState.captureOriginalIfUnset(currentLight)
            @Suppress("DEPRECATION")
            val flags = if (lightStatusBars) current or mask else current and mask.inv()
            @Suppress("DEPRECATION")
            if (current != flags) decor.systemUiVisibility = flags
            statusBarState.markApplied(lightStatusBars)
        }
    }

    private fun restoreStatusBarAppearance() {
        val decor = statusBarDecor?.get() ?: root.get()?.rootView
        statusBarDecor = null
        if (decor == null) {
            statusBarState.clear()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decor.windowInsetsController
            if (controller != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                val currentLight = (controller.systemBarsAppearance and mask) != 0
                val restoreLight = statusBarState.consumeRestoreTarget(currentLight)
                if (restoreLight != null) {
                    val targetAppearance = if (restoreLight) mask else 0
                    if ((controller.systemBarsAppearance and mask) != targetAppearance) {
                        controller.setSystemBarsAppearance(targetAppearance, mask)
                    }
                }
            } else {
                statusBarState.clear()
            }
        } else {
            @Suppress("DEPRECATION")
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION")
            val current = decor.systemUiVisibility
            val currentLight = (current and mask) != 0
            val restoreLight = statusBarState.consumeRestoreTarget(currentLight)
            if (restoreLight != null) {
                @Suppress("DEPRECATION")
                val flags = if (restoreLight) current or mask else current and mask.inv()
                @Suppress("DEPRECATION")
                if (current != flags) decor.systemUiVisibility = flags
            }
        }
    }

    override fun close() {
        restoreStatusBarAppearance()
        session?.close(); session = null
        for (view in bindings.keys) owned.remove(view)
        bindings.clear()
        val root = root.get() ?: return
        val guide = guide.get() ?: return
        val viewport = viewport.get() ?: return
        if (guide.parent === viewport && viewport.parent === root) {
            viewport.removeView(guide)
            root.removeView(viewport)
            root.addView(guide, originalIndex.coerceAtMost(root.childCount), originalParams)
        }
    }

    internal class StatusBarAppearanceState {
        private var capturedOriginal = false
        private var originalLight = false
        private var lastAppliedLight: Boolean? = null

        fun captureOriginalIfUnset(currentLight: Boolean) {
            if (!capturedOriginal) {
                capturedOriginal = true
                originalLight = currentLight
            }
        }

        fun markApplied(light: Boolean) {
            lastAppliedLight = light
        }

        fun consumeRestoreTarget(currentLight: Boolean): Boolean? {
            if (!capturedOriginal) {
                lastAppliedLight = null
                return null
            }
            val applied = lastAppliedLight
            val target = if (applied != null && currentLight == applied && originalLight != applied) {
                originalLight
            } else {
                null
            }
            clear()
            return target
        }

        fun clear() {
            capturedOriginal = false
            originalLight = false
            lastAppliedLight = null
        }
    }

    companion object {
        private val lightStyle = Style(night = false)
        private val darkStyle = Style(night = true)
        private val stickyTitleNames = listOf("opus_author", "opus_top_name")

        fun create(root: ViewGroup, owned: MutableMap<View, Drawable>): HostOpusChrome? {
            fun find(name: String): View? {
                val id = root.resources.getIdentifier(name, "id", root.context.packageName)
                return if (id == 0) null else root.findViewById(id)
            }
            val guide = find("opus_follow_guide") ?: return null
            val bottom = find("opus_bottom_layout") ?: return null
            val top = find("opus_top_bar") ?: return null
            if (guide.parent !== root || bottom.parent !== root || top.parent !== root) return null
            return HostOpusChrome(root, guide, bottom, top, owned)
        }
    }
}
