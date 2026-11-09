package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix

/**
 * 悬浮栏下方的**内容容器**：只装会从栏下穿过的内容（设置页 pager）与滚动边缘溶解层，
 * 悬浮栏本身是它的兄弟而不是子 View。
 *
 * 这条边界是可读性改造的前提：探针（[GlowContentProbe]）录的是"栏下方有什么"，把栏自己录进去
 * 会形成反馈；溶解层必须恰好夹在内容与栏之间；B 期的内容节点也只能装内容。
 *
 * **内容节点（B 期，API 31+）**：[contentCaptureEnabled] 打开后，[dispatchDraw] 先把子 View
 * 录进一个内容 RenderNode，再把它画到自己的画布上——屏幕上仍然只画一次，悬浮栏的玻璃
 * 可以在自己的 display list 里引用同一个节点实时取样（见 `LiquidChromeBackdropApi31`）。
 * 子 View 滚动只重录它们自己的节点，内容节点引用的是节点本身，不需要随之重录。
 */
internal class GlowBackdropTarget(context: Context) : FrameLayout(context) {
    private var capture: GlowContentCaptureApi31? = null
    private val samplingMatrices = ViewSamplingMatrix()

    var contentCaptureEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) releaseCapture()
            invalidate()
        }

    init {
        // 与原来直接承载 pager 的 pageLayer 一致：内容可以画出自身边界（回弹、弹性形变）。
        clipChildren = false
        clipToPadding = false
    }

    private fun releaseCapture() {
        // capture 只会在 API 31+ 上被创建；这里的判定让 lint 看得见。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) capture?.release()
        capture = null
    }

    /**
     * 已录制的内容节点（包在隔离类里）；未开启或尚未录过为 null。
     * 不直接返回 `RenderNode`：高于 minSdk 的平台类型不出现在非隔离类的签名里（AGENTS.md 2026-09-11）。
     */
    @RequiresApi(31)
    fun recordedCapture(): GlowContentCaptureApi31? = capture?.takeIf { it.recorded }

    /** 与本容器同父的 [host]，其原点在本容器坐标中的位置（含两者的平移）。 */
    fun offsetOf(host: View, out: PointF): Boolean {
        if (host.parent == null || host.parent !== parent) return false
        out.set(
            host.left + host.translationX - left - translationX,
            host.top + host.translationY - top - translationY
        )
        return true
    }

    /** 内容局部坐标 → 栏或栏内按钮局部坐标，包含按压缩放、pivot、祖先平移与滚动。 */
    fun matrixTo(host: View, out: Matrix): Boolean {
        val commonParent = parent ?: return false
        if (host.rootView !== rootView) return false
        var branch: View? = host
        var depth = 0
        while (branch != null && depth++ < 128) {
            // 不能把内容自己的后代当成玻璃宿主，否则会录回自己的输出。
            if (branch === this) return false
            if (branch.parent === commonParent) return samplingMatrices.sourceToTarget(this, host, out)
            branch = branch.parent as? View
        }
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!contentCaptureEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !canvas.isHardwareAccelerated || width <= 0 || height <= 0
        ) {
            super.dispatchDraw(canvas)
            return
        }
        val active = capture ?: GlowContentCaptureApi31().also { capture = it }
        val recording = active.begin(width, height)
        try {
            super.dispatchDraw(recording)
        } finally {
            active.end()
        }
        active.drawInto(canvas)
    }
}

@RequiresApi(31)
internal class GlowContentCaptureApi31 {
    // RenderNode 默认裁剪到自身边界；容器本身 clipChildren=false，内容画出边界（回弹、
    // 弹性形变）的行为必须与不录节点时一致。
    val node = RenderNode("BIL-GlowContent").apply { setClipToBounds(false) }
    var recorded = false
        private set

    fun begin(width: Int, height: Int): Canvas {
        node.setPosition(0, 0, width, height)
        return node.beginRecording(width, height)
    }

    fun end() {
        node.endRecording()
        recorded = true
    }

    fun drawInto(canvas: Canvas) {
        canvas.drawRenderNode(node)
    }

    fun release() {
        node.discardDisplayList()
        recorded = false
    }
}
