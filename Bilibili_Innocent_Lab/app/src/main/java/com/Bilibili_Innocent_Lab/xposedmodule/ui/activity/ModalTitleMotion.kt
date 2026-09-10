package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Layout
import android.text.Spanned
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.withSave
import com.highcapable.betterandroid.ui.extension.view.child
import com.highcapable.betterandroid.ui.extension.view.parentOrNull
import com.highcapable.betterandroid.ui.extension.view.textToString
import kotlin.math.roundToInt

internal object ModalTitleMotionSpec {
    /** 不猜测近义标题、不去掉标点；编辑规则与功能名称不同就只做容器动画。 */
    fun matches(source: String, target: String): Boolean =
        source.isNotBlank() && source == target

    /**
     * 来源行**允许把标题与摘要写在同一个 TextView 里、用 `\n` 分行**。
     *
     * 规则编辑与勾选类入口全是这种结构（`ruleSummaryText`、
     * `ComponentPickerSurface.refreshSummary` 拼的就是 `标题 + "\n" + 摘要`），
     * 所以只认整段相等的话，"推荐标题关键词"这种标题与面板**完全同名**的入口
     * 也永远配不上，白白丢掉文字平移。
     *
     * 放宽的只是"标题在哪"，**不是配对的严格程度**：仍然只承认整段相等，
     * 或首行相等且紧跟一个换行；不猜近义、不去标点、不 trim。
     * 首行是否真的只有标题，由 [ModalTitleMotion.prepare] 用渲染后的 Layout 复核。
     */
    fun titleLineMatches(source: String, target: String): Boolean =
        matches(source, target) || (target.isNotBlank() && source.startsWith("$target\n"))

    /**
     * 渲染后的首行文字。
     *
     * 用 Layout 的真实行边界，而不是按 `\n` 切原字符串——软换行（一行太长被折行）时
     * 首行并不等于标题，那种行必须落到只做容器动画的分支。硬换行会把 `\n`
     * 计进 `getLineEnd(0)`，去掉它才是标题本身。
     */
    fun renderedTitleLine(layoutText: String, lineStart: Int, lineEnd: Int): String {
        val from = lineStart.coerceIn(0, layoutText.length)
        val to = lineEnd.coerceIn(from, layoutText.length)
        return layoutText.substring(from, to).removeSuffix("\n")
    }

    fun renderedTextMatches(raw: String, rendered: String): Boolean = raw == rendered

    fun interpolate(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun smooth(start: Float, end: Float, progress: Float): Float {
        val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // 先到位，再交接；交接区不再移动文字，避免跨窗口晚一帧造成空间重影。
    fun motionProgress(progress: Float): Float = smooth(.12f, .85f, progress)
    fun sourceWeight(progress: Float): Float = 1f - smooth(0f, .12f, progress)
    fun targetWeight(progress: Float): Float = smooth(.85f, 1f, progress)

    /**
     * 叠加层**全程满不透明**，而不是与原生标题互补淡入淡出。
     *
     * 原来是 `(1 - source)(1 - target)`，两端各有一段与原生标题的交叉淡化。但**alpha 合成不是
     * 相加**：两份**重合且相同**的字，各 0.5 叠在一起的覆盖率是 `1-(1-.5)(1-.5) = 0.75`，
     * 而两端都是 1.0 ⇒ 每次交接都有一次约 25% 的**变暗**，就是现场看到的"明度闪动"
     * （交接窗口 0..0.12 与 0.85..1，400ms 入场里各约两三帧）。
     *
     * 交接区里 `motionProgress` 恰好被钳在 0 或 1（见上），也就是叠加层此刻**与原生标题
     * 严格重合、同字号**，颜色又由 [ModalTitleMotion] 按同一 progress 混到目标色——
     * 所以让叠加层始终满不透明地盖在上面，画出来的像素与原生标题一致：
     * 既不会变暗，也不怕跨窗口晚一帧（两侧都是同样的像素）。
     * 原生标题的斜坡仍然保留：叠加层在 `expanded()` / `closed()` 撤掉的那一刻，
     * 下面必须已经是满不透明的那一份。
     */
    fun overlayWeight(progress: Float): Float {
        boundedProgress(progress)
        return 1f
    }

    private fun boundedProgress(progress: Float): Float =
        if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

    fun layoutOffset(viewOrigin: Float, padding: Float, lineOffset: Float, scroll: Float): Float =
        viewOrigin + padding + lineOffset - scroll
}

/**
 * 单行同名标题的临时绘制层。位于形变层之外，避免移动中的标题被容器 outline 切掉。
 * 使用来源 TextView 的原生 Layout 单次绘制；字重差异只在目标端原地交接。
 * 两端提前停位并交还真实文字，避免最后一帧跨窗口恢复造成跳动。无位图、文字重排或双字重叠画。
 */
// 仅由配对的来源/目标创建，不参与 XML inflation。
@SuppressLint("ViewConstructor")
internal class ModalTitleMotion private constructor(
    private val source: TextView,
    private val target: TextView,
    private val root: ViewGroup
) : View(root.context) {
    private val title = target.textToString()
    private var sourceLayout: Layout? = null
    private var sourceSize = 1f
    private var targetSize = 1f
    private val rootLocation = IntArray(2)
    private val location = IntArray(2)
    private val visibleBounds = Rect()
    private val sourceAlpha = source.alpha
    private val targetAlpha = target.alpha

    /**
     * 来源行的原始文字颜色，以及它默认色里自带的 alpha 上限。
     *
     * **不能改 `source.alpha`**（原实现就是这么干的）：那会把整行连同 ripple 背景一起变透明，
     * 而 `RippleDrawable` 的动画是在 `draw()` 里推进/创建的——View alpha 为 0 时父级直接跳过
     * 绘制，ripple 被**冻结**在起始态；等形变结束把 alpha 还原，它才第一次 draw 并开始播放，
     * 现场表现就是"面板都展开完了，原来那一行才闪一下高光"。
     * 只压文字颜色的 alpha，行背景全程照常绘制，高光在点击那一刻就地播完。
     */
    private val sourceTextColors = source.textColors
    private val sourceTextBaseAlpha = android.graphics.Color.alpha(sourceTextColors.defaultColor)
    private var sourceTextFaded = false

    /**
     * 飞行标题两端的颜色，**在任何淡出发生之前**各取一次。
     *
     * 叠加层用的是 `source.layout.paint`，也就是来源控件自己那支文字画笔；
     * [applySourceTextWeight] 一压文字颜色，这支画笔也会被来源控件在下次自绘时刷成透明，
     * 飞行标题就跟着消失了。[onDraw] 每帧临时把颜色顶回来再还原。
     *
     * 取 **CSL 的静止态色**而不是 `currentTextColor`：构造发生在点击那一刻，来源行此时可能
     * 正处于 pressed 态，拿当时的解析色会让飞行标题带上按下态的亮度。
     *
     * 两端都取，是因为叠加层现在全程满不透明（见 `ModalTitleMotionSpec.overlayWeight`），
     * 颜色必须自己从来源色混到目标色——否则在目标端交接时会出现一次生硬的明度跳变
     * （来源行通常是灰色，面板标题是深色）。
     */
    private val overlayTextColor = sourceTextColors.defaultColor
    private val targetTextColor = target.textColors.defaultColor
    private var startX = 0f
    private var startBaseline = 0f
    private var endX = 0f
    private var endBaseline = 0f
    private var progress = 0f
    private var active = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        visibility = INVISIBLE
    }

    // 单份无 Span 的文字内容不需要全窗离屏混合，避免交接 alpha 引入额外整屏图层。
    override fun hasOverlappingRendering(): Boolean = false

    fun prepare(expansion: Float) {
        if (active) return
        val layout = source.layout ?: return
        val targetLayout = target.layout ?: return
        if (!source.isAttachedToWindow || !target.isAttachedToWindow || !source.isShown ||
            !ModalTitleMotionSpec.titleLineMatches(source.textToString(), title) ||
            !ModalTitleMotionSpec.matches(target.textToString(), title) ||
            source.text is Spanned || target.text is Spanned ||
            !ModalTitleMotionSpec.renderedTextMatches(source.textToString(), layout.text.toString()) ||
            !ModalTitleMotionSpec.renderedTextMatches(target.textToString(), targetLayout.text.toString()) ||
            source.ellipsize == TextUtils.TruncateAt.MARQUEE ||
            target.ellipsize == TextUtils.TruncateAt.MARQUEE ||
            !stableTransform(source, checkAncestorAlpha = true) ||
            !stableTransform(target, checkAncestorAlpha = false) ||
            // 目标标题必须独占一行；来源只要求**渲染后的首行**正好是标题，
            // 后面还有摘要行也可以——只搬首行，见 onDraw 的行裁剪。
            targetLayout.lineCount != 1 || layout.lineCount < 1 ||
            !ModalTitleMotionSpec.matches(
                ModalTitleMotionSpec.renderedTitleLine(
                    layout.text.toString(), layout.getLineStart(0), layout.getLineEnd(0)
                ),
                title
            ) ||
            layout.getEllipsisCount(0) != 0 || targetLayout.getEllipsisCount(0) != 0 ||
            layout.getParagraphDirection(0) < 0 || targetLayout.getParagraphDirection(0) < 0 ||
            !source.getGlobalVisibleRect(visibleBounds) ||
            visibleBounds.height() < source.height || visibleBounds.width() < source.width
        ) return
        root.getLocationOnScreen(rootLocation)
        source.getLocationOnScreen(location)
        startX = ModalTitleMotionSpec.layoutOffset((location[0] - rootLocation[0]).toFloat(),
            source.totalPaddingLeft.toFloat(), layout.getLineLeft(0), source.scrollX.toFloat())
        startBaseline = (location[1] - rootLocation[1] + source.baseline - source.scrollY).toFloat()
        target.getLocationOnScreen(location)
        endX = ModalTitleMotionSpec.layoutOffset((location[0] - rootLocation[0]).toFloat(),
            target.totalPaddingLeft.toFloat(), targetLayout.getLineLeft(0), target.scrollX.toFloat())
        endBaseline = (location[1] - rootLocation[1] + target.baseline - target.scrollY).toFloat()
        sourceLayout = layout
        sourceSize = source.textSize.coerceAtLeast(1f)
        targetSize = target.textSize.coerceAtLeast(1f)
        active = true
        visibility = VISIBLE
        apply(expansion)
    }

    fun apply(expansion: Float) {
        if (!active) return
        val p = expansion.coerceIn(0f, 1f)
        progress = ModalTitleMotionSpec.motionProgress(p)
        applySourceTextWeight(ModalTitleMotionSpec.sourceWeight(p))
        target.alpha = targetAlpha * ModalTitleMotionSpec.targetWeight(p)
        alpha = ModalTitleMotionSpec.overlayWeight(p) *
            ModalTitleMotionSpec.interpolate(sourceAlpha, targetAlpha, progress)
        invalidate()
    }

    fun expanded() {
        if (!active) return
        active = false
        visibility = INVISIBLE
        restoreSourceText()
        target.alpha = targetAlpha
        sourceLayout = null
    }

    fun closed() {
        if (!active) return
        // 关闭只交还源标题，不要在尚未撤掉的 Dialog 中复活目标标题。
        active = false
        visibility = INVISIBLE
        restoreSourceText()
        target.alpha = 0f
        sourceLayout = null
    }

    fun dispose() {
        active = false
        visibility = INVISIBLE
        restoreSourceText()
        target.alpha = targetAlpha
        sourceLayout = null
    }

    /**
     * 只淡出来源行的**文字**，行背景（ripple）全程照常绘制。
     *
     * 用 `ColorStateList.withAlpha` 而不是 `setTextColor(单色)`：来源行的颜色可能是随 state
     * 变化的 CSL，整条按比例降 alpha 才不会把按下态压平。上限取默认色自带的 alpha，
     * 半透明文字不会在中途反而被提亮。
     */
    private fun applySourceTextWeight(weight: Float) {
        val w = weight.coerceIn(0f, 1f)
        if (w >= 1f) {
            restoreSourceText()
            return
        }
        sourceTextFaded = true
        source.setTextColor(sourceTextColors.withAlpha((sourceTextBaseAlpha * w).roundToInt()))
    }

    /** 交还原始 CSL（而不是当前那个降过 alpha 的单色副本），按下态等状态色一并回来。 */
    private fun restoreSourceText() {
        if (!sourceTextFaded) return
        sourceTextFaded = false
        source.setTextColor(sourceTextColors)
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val layout = sourceLayout ?: return
        val x = ModalTitleMotionSpec.interpolate(startX, endX, progress)
        val baseline = ModalTitleMotionSpec.interpolate(startBaseline, endBaseline, progress)
        val size = ModalTitleMotionSpec.interpolate(sourceSize, targetSize, progress)
        // `layout.paint` **就是来源控件自己那支文字画笔**，而来源的文字颜色正在被
        // applySourceTextWeight 压向透明（那是为了让行背景继续绘制、ripple 不被冻结）。
        // 不在这里把颜色顶回原值，飞行中的标题会跟着一起透明——文字平移会整个"消失"。
        // 用完立刻还原：来源控件每次自绘都会重设这支画笔的颜色，两个窗口在同一帧里
        // 谁先画都不受影响。
        val paint = layout.paint
        val borrowedColor = paint.color
        // 颜色跟着 progress 从来源色混到目标色：叠加层全程满不透明，交接点两侧必须
        // 与下面那份原生标题**同色**，否则会看到一次明度跳变。
        paint.color = androidx.core.graphics.ColorUtils
            .blendARGB(overlayTextColor, targetTextColor, progress)
        try {
            canvas.withSave {
                translate(x, baseline)
                val scale = size / sourceSize
                scale(scale, scale)
                translate(-layout.getLineLeft(0), -layout.getLineBaseline(0).toFloat())
                // 只搬首行：来源行可能是"标题 \n 摘要"合成的一个 TextView，摘要不该跟着飞。
                // Layout.draw 会按画布裁剪决定绘制哪些行，所以这一句同时挡住像素和后续行。
                clipRect(
                    layout.getLineLeft(0), layout.getLineTop(0).toFloat(),
                    layout.getLineRight(0), layout.getLineBottom(0).toFloat()
                )
                layout.draw(this)
            }
        } finally {
            paint.color = borrowedColor
        }
    }

    private fun stableTransform(view: View, checkAncestorAlpha: Boolean): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.scaleX != 1f || current.scaleY != 1f || current.rotation != 0f ||
                current.rotationX != 0f || current.rotationY != 0f ||
                (checkAncestorAlpha && current !== view && current.alpha != 1f)) return false
            current = current.parentOrNull()
        }
        return true
    }

    companion object {
        fun create(anchor: View?, target: TextView?, root: ViewGroup): ModalTitleMotion? {
            if (anchor == null || target == null) return null
            // 只在点击的来源行内查找，最多检查 64 个节点，不遍历设置页面或按近义词误配。
            var remaining = 64
            fun find(view: View): TextView? {
                if (--remaining < 0 || view.visibility != VISIBLE) return null
                if (view is TextView && view !is android.widget.EditText &&
                    ModalTitleMotionSpec.titleLineMatches(
                        view.textToString(), target.textToString()
                    )
                ) return view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        if (remaining <= 0) break
                        find(view.child(index))?.let { return it }
                    }
                }
                return null
            }
            return find(anchor)?.let { ModalTitleMotion(it, target, root) }
        }
    }
}
