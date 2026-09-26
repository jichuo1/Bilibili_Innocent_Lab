package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.widget.NestedScrollView
import androidx.core.view.isVisible
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.highcapable.betterandroid.ui.extension.view.child
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 翻页时的文字链式联动：卡片（玻璃表面）随页面刚性平移，页面里的文字相对卡片按"本段翻页进度"做一个
 * 鼓包偏移——sin²(π·u)：起飞与落定斜率都为 0（柔和），翻页中段最明显，到达目标页时恰好归零（与切页同步）。
 * 链式顺序用进度相位表达：离运动锚点（拖动时的手指、点击底栏时的底栏）越远，u 被 u^(1+SKEW·远近) 压得越靠后，
 * 起飞与峰值越晚，形成从手势处向上下扩散的波；页标题最晚。
 *
 * 幅度 = min(到页面边缘的余量, 行 28dp / 标题 40dp) × (近 0.35 → 远 1)：文字可以短暂浮出卡片边缘（像浮在页面上），
 * 但不越过页面边缘、不会叠到隔壁页。行、列、卡片的矩形与内边距裁剪（clipToPadding 独立于 clipChildren 生效）
 * 联动期间临时放开，结束恢复；outline 圆角裁剪、滚动容器与页面本身的裁剪不动。
 *
 * 只用 [View.setAnimationMatrix]（API 29+）：只改绘制，不改 translation 属性、布局、触摸命中与
 * `getLocationOnScreen`，所以不与弹性交互（写 translation/scale 并校验"是否仍是自己写的值"）、手风琴
 * （写 translationY）冲突，也不影响玻璃采样原点。带背景的文字（按钮、胶囊）、输入框和自管触摸的
 * 控件（[ElasticInteractionController.EXCLUDED_TAG]）不参与：它们自带表面，挪动会与表面错位。开关例外——
 * 设置行的标题就画在开关里，拨钮是纯渐变（[com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidChoiceDrawable]），整体随文字联动。
 *
 * 每帧只遍历可见页里落在视口内的文字：几次乘加 + 一次 setAnimationMatrix（与 translation 同属
 * RenderNode 属性，不重录显示列表）；页面静止且偏移归零即清空矩阵、恢复裁剪、停止帧回调。
 */
internal class SettingsPageTextChain(
    private val pager: SettingsPagePager,
    private val headings: List<View>
) {
    /** [roomLeft]/[roomRight]：文字实际绘制范围到所在卡片（裁剪边界）的余量，偏移不越过它。 */
    private class Item(
        val view: View, val y: Float, val heading: Boolean, val roomLeft: Float, val roomRight: Float
    ) {
        var offset = 0f
        var applied = 0f
    }

    private val density = pager.resources.displayMetrics.density
    // 页面取 pager 的直接子 View：滚动页外面还包着回弹视口，平移作用在视口上。
    private val items = arrayOfNulls<List<Item>>(SettingsPageMotionPolicy.MAX_PAGES)
    /** 为了让文字能偏出行矩形而临时关掉 clipChildren 的中间容器；该页联动结束时恢复。 */
    private val relaxed = arrayOfNulls<List<ViewGroup>>(SettingsPageMotionPolicy.MAX_PAGES)
    /**
     * 临时关掉 clipToPadding 的卡片：ViewGroup 默认把子 View 裁在内边距以内，开关拨钮右缘正贴着内边距线，
     * 一偏就被齐刷刷切掉（2026-09-27 录屏）。卡片自身矩形/圆角裁剪不动，文字仍被余量限在卡片边缘 4dp 以内。
     */
    private val paddingRelaxed = arrayOfNulls<List<ViewGroup>>(SettingsPageMotionPolicy.MAX_PAGES)
    /** 本段运动的起点（页坐标）：静止时记下所在页，运动开始后固定；中途改目标时改为当时的位置。 */
    private var motionFrom = Float.NaN
    private var motionTo = Float.NaN
    private var lastFrameNanos = 0L
    private var framePosted = false
    private var disposed = false
    private val matrix = Matrix()
    private val rect = Rect()
    private val frame = Choreographer.FrameCallback { frameNanos ->
        framePosted = false
        step(frameNanos)
    }

    private val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ValueAnimator.areAnimatorsEnabled()

    /** 页面位置刚变（与 translation 同步调用）：排帧回调，偏移统一在帧回调里按进度算，每帧一次。 */
    fun onPositionChanged() {
        if (disposed || !supported) return
        postFrame()
    }

    private fun step(frameNanos: Long) {
        if (disposed) return
        val height = pager.height.toFloat().coerceAtLeast(1f)
        val anchor = pager.motionAnchorY.takeIf { it.isFinite() } ?: height
        // 按离锚点最远的屏幕边缘归一：点底栏时整页从下到上逐级展开；手在中部时上下两端各自拉满。
        val span = maxOf(anchor, height - anchor, height * REACH_SPAN_MIN)
        val position = pager.pagePosition
        val settled = pager.isSettled
        val dt = if (lastFrameNanos == 0L) 0f else ((frameNanos - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_DT_SECONDS)
        lastFrameNanos = frameNanos
        // 本段运动：起点固定；终点——点击/松手后是已选中的页，拖动中是拖动方向上的下一页（回拖则回到起点）。
        // 起点取本段运动第一帧位置的四舍五入：首帧位移远小于半页；点击底栏时 selectedPage 在动画开始前
        // 就已是目标页，不能拿它当起点。
        if (!motionFrom.isFinite()) motionFrom = position.roundToInt().toFloat()
        val selected = pager.selectedPage.toFloat()
        val target = when {
            abs(selected - motionFrom) >= .5f -> selected
            position > motionFrom -> motionFrom + 1f
            position < motionFrom -> motionFrom - 1f
            else -> motionFrom
        }
        // 途中换了目标（再次点击、反向松手）：以当前位置为新起点，鼓包重新从 0 起，最终平滑去掉跳变。
        if (motionTo.isFinite() && target != motionTo && abs(selected - motionFrom) >= .5f) motionFrom = position
        motionTo = target
        val distance = target - motionFrom
        val progress = if (abs(distance) < .0001f) 0f else ((position - motionFrom) / distance).coerceIn(0f, 1f)
        // 页面往 target 方向走：页面在屏幕上向左（distance>0）时文字落在右侧，反之落在左侧。
        val rtl = if (pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val direction = if (distance > 0f) rtl else if (distance < 0f) -rtl else 0f
        val smoothing = if (dt <= 0f) 1f else 1f - exp(-dt / SMOOTHING_TAU)
        var active = false
        for (index in 0 until minOf(pager.childCount, SettingsPageMotionPolicy.MAX_PAGES)) {
            val page: View = pager.child(index)
            val visible = abs(index - position) < 1f && page.isVisible
            var list = items[index]
            if (list == null) {
                if (!visible || progress <= 0f) continue
                list = collect(index, page)
                items[index] = list
            }
            var pageActive = false
            for (item in list) {
                val reach = (abs(item.y - anchor) / span).coerceIn(0f, 1f)
                var skew = 1f + SKEW * reach
                var share = SHARE_NEAR + (1f - SHARE_NEAR) * reach
                if (item.heading) { skew = 1f + SKEW + HEADING_SKEW_EXTRA; share = 1f }
                val phase = progress.pow(skew)
                val bump = sin(PI.toFloat() * phase).let { it * it }
                // 统一上限（行/标题）并按到卡片边缘的余量收紧：同高度幅度一致、永不出卡片。
                val limit = (if (item.heading) HEADING_LIMIT_DP else ROW_LIMIT_DP) * density
                val room = if (direction >= 0f) item.roomRight else item.roomLeft
                val goal = direction * minOf(room, limit) * share * bump
                var offset = item.offset + (goal - item.offset) * smoothing
                if (abs(offset) < SETTLED_PX && abs(goal) < SETTLED_PX) offset = 0f
                item.offset = offset
                if (offset != 0f) pageActive = true
                if (abs(offset - item.applied) >= APPLY_STEP_PX || (offset == 0f && item.applied != 0f)) {
                    apply(item.view, offset)
                    item.applied = offset
                }
            }
            if (pageActive) active = true
            else if (!visible || settled) release(index)
        }
        if (active || !settled) postFrame()
        else { lastFrameNanos = 0L; motionFrom = Float.NaN; motionTo = Float.NaN }
    }

    private fun postFrame() {
        if (framePosted || disposed) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun apply(view: View, offset: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (offset == 0f) Api29.clear(view)
        else {
            matrix.setTranslate(offset, 0f)
            Api29.set(view, matrix)
        }
    }

    private fun release(index: Int) {
        items[index] = null
        relaxed[index]?.forEach { it.clipChildren = true }
        relaxed[index] = null
        paddingRelaxed[index]?.forEach { it.clipToPadding = true }
        paddingRelaxed[index] = null
    }

    /**
     * 裁剪边界：开了 outline 裁剪的容器（放开会露出直角）与页面本身。卡片的矩形/内边距裁剪在联动期间放开，
     * 文字可以短暂浮出卡片边缘——只留在卡片内边距里时可动空间约 12dp，再按远近分配几乎看不出（2026-09-27
     * 用户："飞动几乎没有了"）。页面边缘不越过，所以不会叠到隔壁页。
     */
    private fun isClipBoundary(view: View): Boolean = view.clipToOutline

    /** 只在该页首次进入可见时走一遍树：记录视口内文字的中心 y 与到裁剪边界的余量（pager 坐标）。 */
    private fun collect(index: Int, page: View): List<Item> {
        val out = ArrayList<Item>()
        val relax = LinkedHashSet<ViewGroup>()
        val relaxPadding = LinkedHashSet<ViewGroup>()
        val height = pager.height
        val margin = BOUNDARY_MARGIN_DP * density
        val boundaryRect = Rect()
        fun walk(view: View, heading: Boolean, boundary: View, between: List<ViewGroup>) {
            if (view.visibility != View.VISIBLE) return
            if (view.tag == ElasticInteractionController.EXCLUDED_TAG) return
            val inHeading = heading || headings.any { it === view }
            // 行内图标与文字一起动，否则分组标题的文字会与它的图标扯开。
            val icon = view is ImageView && view.background == null
            if (view is TextView || icon) {
                // 设置行本身就是开关（标题文字与拨钮画在同一个 View 里）：整体参与，拨钮是纯渐变、不采样背景，
                // 偏移不会与玻璃错位。其余带背景的文字（玻璃按钮、胶囊）与输入框不动。
                if (view is EditText || (view is TextView && view !is CompoundButton && view.background != null)) return
                rect.set(0, 0, view.width, view.height)
                runCatching { pager.offsetDescendantRectToMyCoords(view, rect) }.getOrNull() ?: return
                if (rect.bottom < -VIEWPORT_MARGIN_PX || rect.top > height + VIEWPORT_MARGIN_PX) return
                boundaryRect.set(0, 0, boundary.width, boundary.height)
                runCatching { pager.offsetDescendantRectToMyCoords(boundary, boundaryRect) }.getOrNull() ?: return
                // 实际绘制范围：文字按排版行的左右端；开关的拨钮画到 View 右缘；图标取整个 View。
                var left = rect.left.toFloat()
                var right = rect.right.toFloat()
                val layout = (view as? TextView)?.layout
                if (view is TextView && layout != null && layout.lineCount > 0) {
                    var minLeft = Float.MAX_VALUE
                    var maxRight = 0f
                    for (line in 0 until layout.lineCount) {
                        minLeft = minOf(minLeft, layout.getLineLeft(line))
                        maxRight = maxOf(maxRight, layout.getLineRight(line))
                    }
                    left = rect.left + view.totalPaddingLeft + minLeft
                    if (view !is CompoundButton) right = rect.left + view.totalPaddingLeft + maxRight
                }
                val roomLeft = (left - boundaryRect.left - margin).coerceAtLeast(0f)
                val roomRight = (boundaryRect.right - right - margin).coerceAtLeast(0f)
                out += Item(view, rect.exactCenterY(), inHeading, roomLeft, roomRight)
                // 滚动容器不放开：它的裁剪管的是竖向视口，放开后滚出视口的内容会画出来。
                // clipToPadding 独立于 clipChildren 生效：只关 clipChildren 时，行容器仍把文字裁在自己的内边距线上。
                between.forEach {
                    if (it is ScrollView || it is NestedScrollView) return@forEach
                    if (it.clipChildren) relax += it
                    if (it.clipToPadding) relaxPadding += it
                }
                if (boundary !== page && boundary is ViewGroup && boundary.clipToPadding) relaxPadding += boundary
                return
            }
            if (view is ViewGroup) {
                val nextBoundary = if (view !== page && isClipBoundary(view)) view else boundary
                val nextBetween = if (nextBoundary === view) emptyList() else between + view
                for (i in 0 until view.childCount) walk(view.child(i), inHeading, nextBoundary, nextBetween)
            }
        }
        walk(page, false, page, emptyList())
        // 页面自身与卡片不放开：页与页之间、卡片圆角的裁剪保持原样。
        relax.remove(page as? ViewGroup)
        relaxPadding.remove(page as? ViewGroup)
        relax.forEach { it.clipChildren = false }
        relaxed[index] = relax.toList()
        relaxPadding.forEach { it.clipToPadding = false }
        paddingRelaxed[index] = relaxPadding.toList()
        return out
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frame)
        framePosted = false
        for (list in items) list?.forEach { if (it.applied != 0f) apply(it.view, 0f) }
        for (index in items.indices) release(index)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun set(view: View, matrix: Matrix) { view.animationMatrix = matrix }
        fun clear(view: View) { view.animationMatrix = null }
    }

    internal companion object {
        /** 远近归一的最小尺度（页高比例）：锚点离两端都很近时，避免相邻几行就拉满。 */
        const val REACH_SPAN_MIN = .35f
        /**
         * 相位错开：最远处 u^(1+SKEW)，峰值从 u=0.5 推到约 0.60；标题约 0.63。错开不能大：峰值越靠后，峰后回落越被
         * 压缩在进度末段——曾用 1.2/0.5（标题 u^2.7、峰值 0.77），拖动中标题一路落后像"顿住"，松手后在最后
         * 23% 进度里急追到位（2026-09-27 用户反馈）。链式先后主要靠幅度的远近差拉开。
         */
        const val SKEW = .35f
        const val HEADING_SKEW_EXTRA = .15f
        /** 最近处只用 35% 幅度，最远处用满。 */
        const val SHARE_NEAR = .35f
        /** 行内元素与页标题的最大偏移（再按远近比例与到卡片边缘的余量收紧）。 */
        const val ROW_LIMIT_DP = 28f
        const val HEADING_LIMIT_DP = 32f
        /** 只用于吸收中途改目标时的跳变；正常翻页时鼓包本身已是连续的。 */
        const val SMOOTHING_TAU = .016f
        private const val MAX_DT_SECONDS = .05f
        private const val SETTLED_PX = .5f
        private const val APPLY_STEP_PX = .25f
        private const val VIEWPORT_MARGIN_PX = 64
        /** 文字离裁剪边界（页面边缘）至少留的距离。 */
        private const val BOUNDARY_MARGIN_DP = 4f
    }
}
