package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 图标锚点形变的纯数学层（无 Android 依赖，可 JVM 直测）。
 *
 * **刻意不复用 [SettingsBackupMotionSpec]。** 那套是"带标题的整行 → 全屏页"，它的正文窗口
 * `TIMED = 0.86..0.985` 是按"共享标题先走完位移、正文再接管"调出来的。图标锚点没有标题可迁移，
 * 沿用那个窄窗口会让正文一直憋到最后一帧才出现。这里用宽得多的窗口，且不做任何标题计算。
 *
 * 与容器形变的另一处结构差异：**不引入 morph surface**。形状由承载层的 outline 裁剪表达，
 * 因此不新增 `LiquidMotionSurfaceFrameProvider` 采样面，实时液态玻璃的逐帧像素预算不受影响。
 */
internal enum class IconAnchoredContentTiming {
    TIMED,
    PREDICTIVE
}

/**
 * 形变几何。两个矩形都取**弹窗窗口内**坐标。
 *
 * [collapsedBounds] 通常完全落在 [expandedBounds] 之外（图标在工具栏、卡片在屏幕中央），
 * 这是正常的：承载层是全屏的，outline 在它自己的坐标系里从图标矩形长到卡片矩形。
 */
internal data class IconAnchoredMotionGeometry(
    val collapsedBounds: SettingsBackupMotionRect,
    val expandedBounds: SettingsBackupMotionRect,
    val collapsedRadiusPx: Float,
    val expandedRadiusPx: Float,
    /** 正文起始位移的上限（每轴）。0 表示关闭位移，只做淡入。 */
    val contentTravelCapPx: Float = 0f
) {
    val isUsable: Boolean
        get() = collapsedBounds.isValid &&
            expandedBounds.isValid &&
            collapsedRadiusPx.isFinite() &&
            expandedRadiusPx.isFinite() &&
            contentTravelCapPx.isFinite() &&
            collapsedRadiusPx >= 0f &&
            expandedRadiusPx >= 0f &&
            contentTravelCapPx >= 0f
}

/** 动画热路径复用的帧缓冲；避免每个 progress 分配对象。 */
internal class IconAnchoredMotionFrameBuffer {
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set
    var radiusPx = 0f
        private set
    var surfaceAlpha = 0f
        private set
    var contentAlpha = 0f
        private set

    /** 卡片自身背景（= 描边所在的那张 drawable）的不透明度，独立于正文。 */
    var strokeAlpha = 0f
        private set
    var contentTranslationXPx = 0f
        private set
    var contentTranslationYPx = 0f
        private set

    internal fun set(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusPx: Float,
        surfaceAlpha: Float,
        contentAlpha: Float,
        strokeAlpha: Float,
        contentTranslationXPx: Float,
        contentTranslationYPx: Float
    ) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        this.radiusPx = radiusPx
        this.surfaceAlpha = surfaceAlpha
        this.contentAlpha = contentAlpha
        this.strokeAlpha = strokeAlpha
        this.contentTranslationXPx = contentTranslationXPx
        this.contentTranslationYPx = contentTranslationYPx
    }
}

internal object IconAnchoredMotionSpec {

    /**
     * 展开：标准 fast-out-slow-in。
     *
     * **不要换成 `(0.05, 0.7, 0.1, 1)`**（备份页入场那条）。真机 logcat 逐帧实测：那条曲线
     * 在**头 2~4 帧（约 26ms）就走掉 41% 行程、67ms 走掉 73%**，剩下 290ms 在爬最后 25%。
     * 对"行 → 全屏"这种起点已接近终点的短行程没问题，但图标 → 居中卡片是长对角线，
     * 结果就是飞行段快到看不见、只剩一段几乎不动的长尾，观感等于"卡片直接出现在终点"。
     *
     * `(0.4, 0, 0.2, 1)` 把行程摊平：约 1/4 时间走 1/5、半程走六成、3/4 时间走九成，
     * 飞行过程真正可见，落位仍然是软的。
     */
    const val ENTER_EASING_X1 = 0.4f
    const val ENTER_EASING_Y1 = 0f
    const val ENTER_EASING_X2 = 0.2f
    const val ENTER_EASING_Y2 = 1f

    /** 普通关闭：中段完成主要位移，末尾稳定减速。 */
    const val CLOSE_EASING_X1 = 0.4f
    const val CLOSE_EASING_Y1 = 0f
    const val CLOSE_EASING_X2 = 0.2f
    const val CLOSE_EASING_Y2 = 1f

    /** 预测式返回松手后只做 Linear-out-slow-in 续播，承接已有手势速度。 */
    const val COMMIT_EASING_X1 = 0f
    const val COMMIT_EASING_Y1 = 0f
    const val COMMIT_EASING_X2 = 0.2f
    const val COMMIT_EASING_Y2 = 1f

    // 图标 → 居中卡片是长对角线行程（27dp 到接近满屏）。曲线摊平后行程真正可见了，
    // 再给 40ms 让它读得清楚；关闭端不需要同样长，否则会拖。
    const val ENTER_DURATION_MS = 400L
    const val CLOSE_DURATION_MS = 280L
    const val CANCEL_DURATION_MS = 260L
    const val COMMIT_DURATION_MS = 200L

    /**
     * 表面淡入窗口。
     *
     * 起始帧的形状精确压在图标上，若直接不透明出现会像"憋出一个色块"；用一小段淡入让它
     * 从图标位置柔和浮现。收缩端同理反向，最后交还给下层真实图标。
     */
    private const val SURFACE_EDGE_END = 0.10f

    /** 正文起始位移相对"锚点中心 → 卡片中心"向量的比例，再由几何里的上限截断。 */
    private const val CONTENT_TRAVEL_RATIO = 0.10f

    /**
     * 卡片背景（描边所在那张 drawable）的淡入起点，终点固定为 1。
     *
     * 为什么描边要一条**独立**通道，而不是跟着 [contentFraction] 走：
     * 正文窗口必须在 **0.85 之前**收干净——`ModalTitleMotionSpec.targetWeight` 从 0.85 起把
     * 标题交还给卡片里的真实 TextView，那一刻卡片若还在淡入，标题会被淡两次。
     * 但描边如果也在 0.78 就满了，之后还有约四成行程在跑，就会出现"最终尺寸的描边"被
     * 一个仍在生长的裁剪矩形切开——现场表现为边框与形状分离、有破绽。
     *
     * 所以描边单独走一条**收在 1.0 的**斜坡：形状停住的同一刻描边刚好满，收起时反向推进，
     * 描边先淡掉再看到形状缩回。承载层与卡片按纪律用**同一份** `skinModalBackground`
     * （同色同圆角），所以给卡片背景整体降 alpha 在视觉上只淡掉描边，填充无差别。
     */
    private const val STROKE_EDGE_START = 0.45f

    fun fillFrame(
        out: IconAnchoredMotionFrameBuffer,
        expansion: Float,
        geometry: IconAnchoredMotionGeometry,
        contentTiming: IconAnchoredContentTiming = IconAnchoredContentTiming.TIMED
    ) {
        require(geometry.isUsable) { "Icon anchored geometry must be usable" }
        val fraction = expansion.coerceIn(0f, 1f)
        val collapsed = geometry.collapsedBounds
        val expanded = geometry.expandedBounds
        val content = contentFraction(fraction, contentTiming)
        // 正文从锚点方向"飞"到位：残余位移随正文淡入一起收敛到 0，收尾必须精确为 0，
        // 否则卡片会永久偏移几个像素。
        val remaining = 1f - content
        val cap = geometry.contentTravelCapPx
        val towardAnchorX = (collapsed.left + collapsed.right) / 2f -
            (expanded.left + expanded.right) / 2f
        val towardAnchorY = (collapsed.top + collapsed.bottom) / 2f -
            (expanded.top + expanded.bottom) / 2f
        out.set(
            left = lerp(collapsed.left, expanded.left, fraction),
            top = lerp(collapsed.top, expanded.top, fraction),
            right = lerp(collapsed.right, expanded.right, fraction),
            bottom = lerp(collapsed.bottom, expanded.bottom, fraction),
            radiusPx = lerp(geometry.collapsedRadiusPx, geometry.expandedRadiusPx, fraction),
            surfaceAlpha = smoothStep(0f, SURFACE_EDGE_END, fraction),
            contentAlpha = content,
            strokeAlpha = strokeAlpha(fraction),
            contentTranslationXPx =
                (towardAnchorX * CONTENT_TRAVEL_RATIO).coerceIn(-cap, cap) * remaining,
            contentTranslationYPx =
                (towardAnchorY * CONTENT_TRAVEL_RATIO).coerceIn(-cap, cap) * remaining
        )
    }

    /**
     * 正文淡入窗口。
     *
     * `TIMED` 比容器形变那套（0.86..0.985）宽得多：图标锚点没有标题位移要等，正文可以早得多
     * 接管，否则观感是"形状长完了才想起来放内容"。
     *
     * `PREDICTIVE` 更宽，因为 `BackEvent.progress` 已经过强非线性手势映射；沿用定时窗口会把
     * 正文淡出压缩到原始手势最初的极小一段里，表现为一碰就消失。
     */
    internal fun contentFraction(
        expansion: Float,
        timing: IconAnchoredContentTiming
    ): Float = when (timing) {
        // 首版是 0.55..0.90，真机看下来前半程是一整块空白色块在长，观感像"憋出个方块"
        // 而不是"面板飞出来"。提前到 0.30 起、0.78 收：正文在形状还在移动时就已经可读，
        // 剩下的行程是内容跟着容器一起落位。
        IconAnchoredContentTiming.TIMED -> smoothStep(0.30f, 0.78f, expansion)
        IconAnchoredContentTiming.PREDICTIVE -> smoothStep(0.12f, 0.58f, expansion)
    }

    /**
     * 描边（= 卡片背景）的不透明度，**恰好在形状停住的那一刻满**。
     *
     * 与 [contentFraction] 分开的理由见 [STROKE_EDGE_START]。方向无关：收起时同一条曲线
     * 反向推进，描边先淡出、再看到形状缩回，"出现"与"消失"用的是同一段渐变。
     */
    internal fun strokeAlpha(expansion: Float): Float =
        smoothStep(STROKE_EDGE_START, 1f, expansion.coerceIn(0f, 1f))

    internal fun smoothStep(edgeStart: Float, edgeEnd: Float, value: Float): Float {
        if (edgeStart >= edgeEnd) return if (value < edgeStart) 0f else 1f
        val fraction = ((value - edgeStart) / (edgeEnd - edgeStart)).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
