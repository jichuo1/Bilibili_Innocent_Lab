package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine

import android.animation.ValueAnimator
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.annotation.MainThread
import java.util.Locale

/**
 * 悬浮栏可读性的协调者（2026-09-23 悬浮栏可读性改造 A 期）。
 *
 * 把三件事接到同一组悬浮栏上，业务 presenter 只需声明"哪个 View 是栏、在哪条边、前景怎么改色"：
 * 1. **滚动边缘溶解**：在 [target] 里加两层 [GlowScrollEdgeView]，覆盖度由 [coverage] 给出。
 * 2. **内容探针**：滚动/翻页/布局变化后按 ≤[PROBE_INTERVAL_MS] 的节奏录一次栏下方画面，
 *    后台统计亮度与细节（[GlowContentProbe]）。只有引擎给出 [GlowEngine.floatingSurfaceOptics]
 *    时才启动——不做自适应的引擎连录制都不付。
 * 3. **补偿过渡**：采样经 [GlowLegibilityPolicy] 得到目标补偿，迟滞后用 [TRANSITION_MS] 的
 *    过渡交给引擎（表面着色/边缘）与前景回调（图标/标签）。
 * 4. **内容节点玻璃**（B 期）：引擎支持时打开 [GlowBackdropTarget.contentCaptureEnabled]，
 *    把各栏登记为节点取样（[GlowEngine.setSurfaceBackdrop]）；引擎停用节点路径时随之撤销。
 *
 * 引擎每次现取（[engine]）：高级材质中途失败会切到柔光，这里在下一帧撤掉旧补偿、重新探测。
 */
@MainThread
internal class GlowFloatingChrome(
    private val target: GlowBackdropTarget,
    private val engine: () -> GlowEngine?,
    private val coverage: (GlowScrollEdge) -> Float
) {
    private class Surface(
        val host: View,
        val companions: List<View>,
        val edge: GlowScrollEdge,
        val foregroundColor: Int,
        val thickenHost: Boolean,
        val onForeground: (Float) -> Unit
    ) {
        val region = RectF()
        var regionValid = false
        var applied = GlowLegibility.NEUTRAL
        var goal = GlowLegibility.NEUTRAL
        var animator: ValueAnimator? = null
    }

    private val density = target.resources.displayMetrics.density
    private val surfaces = ArrayList<Surface>(2)
    private val edgeViews: Map<GlowScrollEdge, GlowScrollEdgeView>
    private val probe = GlowContentProbe(::onSamples)
    private var probedSurfaces: List<Surface> = emptyList()
    private var probeFailed = false
    private var probeDirty = false
    private var probePosted = false
    private var lastProbeNanos = 0L
    private var lastEngine: GlowEngine? = null
    private var lastGeneration = Long.MIN_VALUE
    private var lastDissolve = false
    private var lastNodeBackdrop = false
    private var lastDescendantBackdrop = false
    /** 新登记的栏需要在下一帧同步节点取样登记。 */
    private var backdropsDirty = false
    private var disposed = false
    private var observed: ViewTreeObserver? = null

    private val probeRunnable = Runnable {
        probePosted = false
        runProbe()
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { onContentMoved() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateGeometry()
        onContentMoved()
    }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        checkEngine()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = observe()
        override fun onViewDetachedFromWindow(v: View) = unobserve()
    }

    init {
        val tailPx = GlowScrollEdgePolicy.TAIL_DP * density
        edgeViews = GlowScrollEdge.values().associateWith { edge ->
            GlowScrollEdgeView(target.context, edge, tailPx, engine).also { view ->
                target.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        target.addOnAttachStateChangeListener(attachListener)
        if (target.isAttachedToWindow) observe()
    }

    /**
     * 登记一条悬浮栏。[host] 必须与 [target] 同一个父容器（坐标直接相减）。
     * [companions] 是栏内自带玻璃表面的子控件（顶栏的三枚圆按钮），与栏共用同一份补偿。
     * [onForeground] 收到 0..1 的前景加强量；0 表示恢复原色。
     * [thickenHost] 为 false 时栏本体始终保持中性（清透），补偿只加在 [companions] 上：栏上没有直接压在
     * 玻璃上的文字/图标时（顶栏的图标都在圆按钮里），加厚本体只会把背景洗灰，对可读性没有贡献。
     */
    fun attach(
        host: View,
        edge: GlowScrollEdge,
        foregroundColor: Int,
        companions: List<View> = emptyList(),
        thickenHost: Boolean = true,
        onForeground: (Float) -> Unit
    ) {
        if (disposed) return
        surfaces += Surface(host, companions, edge, foregroundColor, thickenHost, onForeground)
        backdropsDirty = true
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!disposed) updateGeometry() }
        updateGeometry()
        onContentMoved()
    }

    /** 内容位移（滚动、翻页、展开收起）：刷新覆盖度并请求一次探测。滚动由自身监听，翻页需调用方通知。 */
    fun onContentMoved() {
        if (disposed) return
        updateCoverage()
        requestProbe()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        target.removeCallbacks(probeRunnable)
        target.removeOnAttachStateChangeListener(attachListener)
        unobserve()
        probe.close()
        val glow = engine()
        for (surface in surfaces) {
            surface.animator?.cancel()
            surface.animator = null
            glow?.setSurfaceLegibility(surface.host, null)
            surface.companions.forEach { glow?.setSurfaceLegibility(it, null) }
            glow?.setSurfaceBackdrop(surface.host, null)
            surface.companions.forEach { companion -> glow?.setSurfaceBackdrop(companion, null) }
        }
        if (lastEngine !== glow) clearBackdrops(lastEngine)
        target.contentCaptureEnabled = false
    }

    private fun observe() {
        if (disposed) return
        val observer = target.viewTreeObserver
        if (observed === observer && observer.isAlive) return
        unobserve()
        observer.addOnScrollChangedListener(scrollListener)
        observer.addOnGlobalLayoutListener(layoutListener)
        observer.addOnPreDrawListener(preDrawListener)
        observed = observer
    }

    private fun unobserve() {
        val observer = observed ?: return
        observed = null
        if (!observer.isAlive) return
        observer.removeOnScrollChangedListener(scrollListener)
        observer.removeOnGlobalLayoutListener(layoutListener)
        observer.removeOnPreDrawListener(preDrawListener)
    }

    /** 栏的位置（含平移）换算到 [target] 局部坐标，同步给溶解层。 */
    private fun updateGeometry() {
        for (surface in surfaces) {
            val host = surface.host
            val valid = host.parent === target.parent && host.width > 0 && host.height > 0 && host.isShown
            surface.regionValid = valid
            if (!valid) {
                edgeViews[surface.edge]?.setCapsule(Float.NaN, Float.NaN)
                continue
            }
            val left = host.left + host.translationX - target.left - target.translationX
            val top = host.top + host.translationY - target.top - target.translationY
            surface.region.set(left, top, left + host.width, top + host.height)
            edgeViews[surface.edge]?.setCapsule(surface.region.top, surface.region.bottom)
        }
    }

    private fun updateCoverage() {
        val dissolve = engine()?.wantsScrollEdgeDissolve == true
        for ((edge, view) in edgeViews) view.coverage = if (dissolve) coverage(edge) else 0f
    }

    /**
     * 每帧 pre-draw 的廉价比对：引擎换了（高级材质失败回退）或底图换代（自定义图片加载完成、
     * 窗口尺寸变化）时，溶解层要按新底图重录，可读性要重新探测。
     */
    private fun checkEngine() {
        if (disposed) return
        val glow = engine()
        val generation = glow?.windowBackdropGeneration ?: Long.MIN_VALUE
        val dissolve = glow?.wantsScrollEdgeDissolve == true
        val nodeBackdrop = glow?.supportsSurfaceBackdrop == true
        val descendantBackdrop = nodeBackdrop && glow.supportsDescendantSurfaceBackdrop
        if (glow === lastEngine && generation == lastGeneration && dissolve == lastDissolve &&
            nodeBackdrop == lastNodeBackdrop && descendantBackdrop == lastDescendantBackdrop && !backdropsDirty
        ) return
        val engineChanged = glow !== lastEngine
        if (engineChanged) clearBackdrops(lastEngine)
        if (engineChanged || nodeBackdrop != lastNodeBackdrop ||
            descendantBackdrop != lastDescendantBackdrop || backdropsDirty) syncBackdrops(glow, nodeBackdrop)
        lastEngine = glow
        lastGeneration = generation
        lastDissolve = dissolve
        lastNodeBackdrop = nodeBackdrop
        lastDescendantBackdrop = descendantBackdrop
        if (engineChanged) {
            // 新引擎不认识旧补偿：先整体撤到中性，再由新一轮探测决定。
            for (surface in surfaces) {
                surface.animator?.cancel()
                surface.animator = null
                surface.goal = GlowLegibility.NEUTRAL
                apply(surface, GlowLegibility.NEUTRAL, force = true)
            }
        }
        edgeViews.values.forEach(View::invalidate)
        updateCoverage()
        requestProbe()
    }

    /**
     * 节点取样登记与内容录制同进同退。撤销时先撤栏再停录制：栏的背景节点引用着内容节点，
     * 先停录制会让栏在重画前的那一帧引用一个已清空的节点。
     */
    private fun syncBackdrops(glow: GlowEngine?, enabled: Boolean) {
        backdropsDirty = false
        if (enabled) {
            target.contentCaptureEnabled = true
            for (surface in surfaces) glow?.setSurfaceBackdrop(surface.host, target)
            val companionTarget = target.takeIf { glow?.supportsDescendantSurfaceBackdrop == true }
            for (surface in surfaces) for (companion in surface.companions) {
                glow?.setSurfaceBackdrop(companion, companionTarget)
            }
        } else {
            for (surface in surfaces) glow?.setSurfaceBackdrop(surface.host, null)
            for (surface in surfaces) for (companion in surface.companions) {
                glow?.setSurfaceBackdrop(companion, null)
            }
            target.contentCaptureEnabled = false
        }
    }

    private fun clearBackdrops(glow: GlowEngine?) {
        for (surface in surfaces) {
            glow?.setSurfaceBackdrop(surface.host, null)
            for (companion in surface.companions) glow?.setSurfaceBackdrop(companion, null)
        }
    }

    private fun requestProbe() {
        if (disposed || probeFailed || surfaces.isEmpty()) return
        probeDirty = true
        if (probePosted || probe.inFlight) return
        val elapsedMs = (System.nanoTime() - lastProbeNanos) / NANOS_PER_MILLISECOND
        val delay = if (lastProbeNanos == 0L) 0L else (PROBE_INTERVAL_MS - elapsedMs).coerceAtLeast(0L)
        probePosted = true
        target.postDelayed(probeRunnable, delay)
    }

    private fun runProbe() {
        if (disposed || probeFailed || !probeDirty) return
        val glow = engine()
        val optics = glow?.floatingSurfaceOptics()
        if (glow == null || optics == null) {
            probeDirty = false
            return
        }
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) return
        updateGeometry()
        val batch = surfaces.filter { it.regionValid }
        if (batch.isEmpty()) {
            probeDirty = false
            return
        }
        probeDirty = false
        lastProbeNanos = System.nanoTime()
        val sent = runCatching {
            probe.probe(target, batch.map { it.region }) { canvas, region ->
                glow.drawWindowBackdrop(canvas, target, region, null, 1f)
            }
        }.getOrElse { error ->
            // 某个 View 不支持软件绘制：探针永久停用，补偿撤回中性，其余效果照常。
            probeFailed = true
            Log.w(TAG, "content probe disabled", error)
            for (surface in surfaces) transitionTo(surface, GlowLegibility.NEUTRAL)
            return
        }
        if (sent) probedSurfaces = batch else probeDirty = true
    }

    private fun onSamples(samples: List<GlowContentSample?>) {
        if (disposed) return
        val batch = probedSurfaces
        probedSurfaces = emptyList()
        val glow = engine()
        val optics = glow?.floatingSurfaceOptics()
        if (glow != null && optics != null) {
            for ((index, surface) in batch.withIndex()) {
                val sample = samples.getOrNull(index) ?: continue
                val goal = GlowLegibilityPolicy.target(sample, surface.foregroundColor, optics)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format(Locale.ROOT, "%s luma=%.3f busy=%.4f -> boost=%.3f edge=%.3f",
                        surface.edge, sample.luma, sample.busyness, goal.boost, goal.edgeDefinition))
                }
                transitionTo(surface, goal)
            }
        }
        if (probeDirty) requestProbe()
    }

    private fun transitionTo(surface: Surface, goal: GlowLegibility) {
        val next = GlowLegibilityPolicy.settle(surface.goal, goal) ?: return
        surface.goal = next
        surface.animator?.cancel()
        val from = surface.applied
        surface.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (!disposed) apply(surface, GlowLegibilityPolicy.lerp(from, next, animator.animatedFraction))
            }
            start()
        }
    }

    private fun apply(surface: Surface, value: GlowLegibility, force: Boolean = false) {
        if (!force && value == surface.applied) return
        surface.applied = value
        val glow = engine()
        glow?.setSurfaceLegibility(surface.host, value.takeIf { surface.thickenHost })
        surface.companions.forEach { glow?.setSurfaceLegibility(it, value) }
        surface.onForeground(value.boost)
    }

    internal companion object {
        /** 探测节奏上限：约 8 Hz。补偿本身有 [TRANSITION_MS] 过渡，更快的采样看不出区别。 */
        const val PROBE_INTERVAL_MS = 125L
        const val TRANSITION_MS = 240L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TAG = "BIL-GlowLegibility"
    }
}
