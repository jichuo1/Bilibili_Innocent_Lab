package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jichuo1.meguviz.android.extensions.isUiInNightMode
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.InsetsSlot
import io.github.jichuo1.meguviz.android.window.InsetsSnapshot
import io.github.jichuo1.meguviz.android.window.ViewScope
import io.github.jichuo1.meguviz.android.window.WindowSession

/** Module-window policy formerly inherited from AppViewsActivity; no skin or consent work. */
@Suppress("DEPRECATION")
internal class BilWindowDelegate(activity: Activity, root: View, session: WindowSession) : AutoCloseable {
    private var rootView: View? = root
    private var closed = false
    private val restorations = mutableListOf<() -> Unit>()
    private var insetsScope: ViewScope? = null
    private var insets: InsetsSlot? = null
    private var defaultPadding: InsetsSlot.LocalLayout? = null

    init {
        try {
            require(root is ViewGroup && root.parent != null) { "Content root must be a parented ViewGroup." }
            session.requirePermission(WindowSession.Permission.OWN_WINDOW)
            val target = activity.window
            val controller = WindowCompat.getInsetsController(target, root)
            val darkContent = !activity.resources.configuration.isUiInNightMode
            if (Build.VERSION.SDK_INT < 35) {
                own({ target.statusBarColor }, { target.statusBarColor = it }, Color.TRANSPARENT)
                own({ target.navigationBarColor }, { target.navigationBarColor = it }, Color.TRANSPARENT)
            }
            own({ controller.isAppearanceLightStatusBars }, { controller.isAppearanceLightStatusBars = it }, darkContent)
            own({ controller.isAppearanceLightNavigationBars }, { controller.isAppearanceLightNavigationBars = it }, darkContent)
            own({ controller.systemBarsBehavior }, { controller.systemBarsBehavior = it },
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            if (Build.VERSION.SDK_INT >= 29) {
                own({ target.isStatusBarContrastEnforced }, { target.isStatusBarContrastEnforced = it }, false)
                own({ target.isNavigationBarContrastEnforced }, { target.isNavigationBarContrastEnforced = it }, false)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                if (Build.VERSION.SDK_INT < 35) {
                    own({ target.navigationBarDividerColor }, { target.navigationBarDividerColor = it }, Color.TRANSPARENT)
                }
                val cutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                own({ target.attributes.layoutInDisplayCutoutMode }, { mode ->
                    target.attributes = target.attributes.apply { layoutInDisplayCutoutMode = mode }
                }, cutoutMode)
            }
            val mask = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            own({ target.attributes.flags and mask }, { flags -> target.setFlags(flags, mask) },
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            // The module owns this setter-only window policy until destruction, as the prior base did.
            restorations += { WindowCompat.setDecorFitsSystemWindows(target, true) }
            WindowCompat.setDecorFitsSystemWindows(target, false)
            val scope = session.scope(root).also { insetsScope = it }
            insets = InsetsSlot.claimExclusive(scope).also { slot ->
                defaultPadding = slot.applyPaddingToOwnedContainer(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                slot.enableAnimationUpdatesInOwnedSlot()
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun <T> own(read: () -> T, write: (T) -> Unit, applied: T) {
        val original = read()
        restorations += { if (read() == applied) write(original) }
        write(applied)
    }

    /** Shares the owned [InsetsSlot] via [InsetsSlot.observe] and releases default container padding when taken over by page layout. */
    fun observeContentInsets(root: View, observer: (InsetsSnapshot) -> Unit): Registration? {
        if (closed || rootView !== root) return null
        defaultPadding?.close()
        defaultPadding = null
        return insets?.observe(observer)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        release { defaultPadding?.close() }
        release { insetsScope?.close() }
        restorations.asReversed().forEach { release(it) }
        restorations.clear()
        defaultPadding = null
        insetsScope = null
        insets = null
        rootView = null
        failure?.let { throw it }
    }
}
