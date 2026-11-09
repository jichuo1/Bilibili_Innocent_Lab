package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.host.LumenSourceAvailability

/** A module-owned wallpaper set as a View background; its scene inputs change without invalidating the feed. */
internal interface HostSceneBackground {
    val samplingState: Long
}

/** Supplies the scene behind transparent feed content through the engine's public source API. */
internal class HostChromeContentSource(override val coordinateView: View, var windowBaseColor: Int,
    private val includeViewContent: Boolean = true,
    private val composeWindowBase: Boolean = false) : LumenVersionedContentSource {
    private val ancestors = ArrayList<View>()
    private val matrix = Matrix()
    private val mapping = ViewSamplingMatrix()
    private val values = FloatArray(9)
    private val savedBounds = Rect()

    override val sourceEpoch: Long get() = 0L
    override val excludesSurfaces: Boolean get() = !includeViewContent
    override val availability: LumenSourceAvailability get() = LumenSourceAvailability.READY
    override val contentVersion: Long
        get() = mapping.withAncestorMemo {
            // Backgrounds can change without invalidating the feed. A pure snapshot also catches
            // the source moving relative to the wallpaper and asynchronous artwork completion.
            var signature = 17L
            fun mix(value: Long) { signature = signature * 31L + value }
            mix(windowBaseColor.toLong())
            mix(coordinateView.scrollX.toLong()); mix(coordinateView.scrollY.toLong())
            var view = if (includeViewContent) coordinateView.parent as? View else coordinateView
            var depth = 0
            while (view != null && depth++ < 128) {
                val background = inheritedBackground(view)
                mix(System.identityHashCode(background).toLong())
                if (background != null) {
                    mix(background.alpha.toLong()); mix(background.state.contentHashCode().toLong())
                    val bounds = background.bounds
                    mix(bounds.left.toLong()); mix(bounds.top.toLong())
                    mix(bounds.right.toLong()); mix(bounds.bottom.toLong())
                    if (background is ColorDrawable) mix(background.color.toLong())
                    if (background is HostSceneBackground) mix(background.samplingState)
                    if (mapping.sourceToTarget(view, coordinateView, matrix)) {
                        matrix.getValues(values)
                        for (value in values) mix(value.toBits().toLong())
                    }
                }
                view = view.parent as? View
            }
            signature and Long.MAX_VALUE
        }

    override fun drawContent(canvas: Canvas) = mapping.withAncestorMemo {
        // Window fallback backgrounds are not always exposed as View.background. Complete the
        // input's alpha before compositing the actual backgrounds and translucent feed layers.
        canvas.drawColor(windowBaseColor or 0xFF000000.toInt())
        ancestors.clear()
        var ancestor = if (includeViewContent) coordinateView.parent as? View else coordinateView
        while (ancestor != null && ancestors.size < 128) {
            ancestors.add(ancestor)
            ancestor = ancestor.parent as? View
        }
        try {
            // Draw only ancestor backgrounds, from the window inward. Drawing their children
            // would include the injected bars and feed the glass back into itself.
            for (index in ancestors.indices.reversed()) {
                val view = ancestors[index]
                val background = inheritedBackground(view) ?: continue
                if (!mapping.sourceToTarget(view, coordinateView, matrix)) continue
                val save = canvas.save()
                savedBounds.set(background.bounds)
                val resize = savedBounds.left != 0 || savedBounds.top != 0 ||
                    savedBounds.right != view.width || savedBounds.bottom != view.height
                try {
                    canvas.concat(matrix)
                    // Capture runs in pre-draw, before View.draw has sized a newly replaced
                    // background. Supply its real viewport without changing the host's state.
                    if (resize) background.setBounds(0, 0, view.width, view.height)
                    background.draw(canvas)
                } finally {
                    if (resize) background.bounds = savedBounds
                    canvas.restoreToCount(save)
                }
            }
            if (includeViewContent) {
                val save = canvas.save()
                try {
                    // View.draw expects its parent to have applied the View's scroll offset.
                    canvas.translate(-coordinateView.scrollX.toFloat(), -coordinateView.scrollY.toFloat())
                    coordinateView.draw(canvas)
                } finally { canvas.restoreToCount(save) }
            }
        } finally { ancestors.clear() }
    }

    private fun inheritedBackground(view: View): Drawable? = view.background?.takeIf {
        // Compose paints the visible page base inside its composition, not View.background.
        // Ordinary ancestor/Window colors are hidden behind that base and must not leak into
        // the glass. A module-owned wallpaper is an explicit scene input and remains live.
        !composeWindowBase || it is HostSceneBackground
    }
}
