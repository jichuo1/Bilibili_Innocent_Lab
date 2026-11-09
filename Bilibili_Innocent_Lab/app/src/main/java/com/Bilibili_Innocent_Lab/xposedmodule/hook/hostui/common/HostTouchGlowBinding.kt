package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 独立光晕只占用覆盖层；宿主的布局、背景和触摸监听器继续由宿主管理。 */
internal class HostTouchGlowBinding private constructor(
    private val host: ViewGroup
) : View.OnAttachStateChangeListener {
    private val theme = HostChromeTheme(host.context)
    private val density = host.resources.displayMetrics.density
    private val glow = HostGlowView(host.context, theme.read().accent, density, 4f * density)
    private var observer: ViewTreeObserver? = null
    private var touching = false
    private var x = 0f
    private var y = 0f
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        HostThreadGuard.call("host.touch_glow.predraw", true) {
            glow.layout(0, 0, host.width, host.height)
            glow.recolor(theme.read().accent)
            if (touching) updateGlow()
            true
        }
    }

    private fun connect() {
        bindings[host] = WeakReference(this)
        host.overlay.add(glow)
        glow.layout(0, 0, host.width, host.height)
        val current = host.viewTreeObserver
        if (observer === current) return
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        current.addOnPreDrawListener(preDraw)
        observer = current
    }

    private fun updateGlow() {
        glow.updateGesture(1f, 0f, 0f, x, y, host.width, host.height, cornerRadius = 0f)
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = event.pointerCount == 1
                x = event.x
                y = event.y
                if (touching) updateGlow()
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                x = event.x
                y = event.y
                updateGlow()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                touching = false
                glow.resetGestureState()
            }
        }
    }

    override fun onViewAttachedToWindow(v: View) = HostThreadGuard.run("host.touch_glow.attach") { connect() }

    override fun onViewDetachedFromWindow(v: View) = HostThreadGuard.run("host.touch_glow.detach") {
        bindings.remove(host)
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        touching = false
        glow.resetGestureState()
        host.overlay.remove(glow)
    }

    companion object {
        // 两端都用弱引用：全局触摸 Hook 不持有已离开的宿主页或 Activity。
        private val bindings = WeakHashMap<ViewGroup, WeakReference<HostTouchGlowBinding>>()

        fun attach(host: ViewGroup) {
            val binding = HostTouchGlowBinding(host)
            host.addOnAttachStateChangeListener(binding)
            if (host.isAttachedToWindow) binding.connect()
        }

        fun observeTouch(host: ViewGroup, event: MotionEvent) {
            bindings[host]?.get()?.observe(event)
        }
    }
}
