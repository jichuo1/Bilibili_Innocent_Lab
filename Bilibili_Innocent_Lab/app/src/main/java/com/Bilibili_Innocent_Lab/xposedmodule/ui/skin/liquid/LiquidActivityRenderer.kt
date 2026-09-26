package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowBackdropTarget
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeGlassApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngine
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngineCallbacks
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibility
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowSurfaceOptics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt

private class LiquidWindowRefresh(
    val observer: WeakReference<ViewTreeObserver>,
    val preDraw: ViewTreeObserver.OnPreDrawListener,
    val scroll: ViewTreeObserver.OnScrollChangedListener,
    val batch: LiquidRefreshBatch = LiquidRefreshBatch()
)

private class LiquidCaptureRequest(
    val ticket: LiquidCaptureRequestState.Ticket,
    val source: LiquidBackdropSource,
    val stableBackdrop: LiquidBackdropSource,
    val root: WeakReference<View>,
    val width: Int,
    val height: Int,
    // Exclusively borrowed until this request completes: no next request can rewind the mask meanwhile.
    val mask: Path,
    val maskReady: Boolean,
    /**
     * 发起截图时正绑定给后端的那张实时截图：后台只和它比较。提交时它必须仍是绑定源，
     * 比较结论才可采信——否则一律当"有变化"。
     */
    val baseline: LiquidBackdropSource?
) {
    // 以下三项只在截图线程写入，经 mainHandler 消息队列交接后才在主线程读取。
    var copyCompletedNanos = 0L
    var outcome = LiquidCaptureOutcome.FAILED
    var sameAsBaseline = false
}

/**
 * 截图线程上的后处理：反馈抑制遮罩 + 与基准逐像素比较（截图线程启动失败时退回主线程执行）。
 *
 * 2026-09-23 前这两步在 PixelCopy 的主线程回调里执行（整张约 1,000,000 px 的软件路径填充 +
 * 一次整图 `sameAs`）。单飞从"发起"一直延续到主线程提交：提交前不会有下一次请求改写 [mask]、
 * 轮转到的缓冲或基准，本函数读写的全部对象在此期间只归截图线程使用。
 * 实时缓冲与已发布的稳定底图 `close()` 都不 recycle，过期请求在这里多算一次也不会触碰已释放像素；
 * 结论由主线程按票据丢弃。
 */
@AnyThread
private fun postProcessRealtimeCapture(
    feedback: LiquidFeedbackSuppressor,
    request: LiquidCaptureRequest,
    result: Int
) {
    request.copyCompletedNanos = System.nanoTime()
    if (result != PixelCopy.SUCCESS) return
    val outcome = runCatching {
        feedback.sanitizeRealtimeCapture(request.source, request.stableBackdrop, request.mask, request.maskReady)
    }.getOrDefault(LiquidCaptureOutcome.FAILED)
    request.outcome = outcome
    val baseline = request.baseline ?: return
    if (outcome == LiquidCaptureOutcome.FAILED) return
    // 异常一律当"有变化"处理，不能抛出去。
    request.sameAsBaseline = runCatching { request.source.bitmap.sameAs(baseline.bitmap) }.getOrDefault(false)
}

/**
 * 高级材质的 [GlowEngine] 实现：Activity 级 Liquid renderer。
 *
 * 每个实例持有一个稳定 root underlay；用户启用高负载模式后，Surface Drawable 可改采三缓冲
 * PixelCopy source。Bitmap、RuntimeShader、RenderEffect 都在绑定/切换路径创建，draw 只更新位置
 * 和 uniform。
 */
internal class LiquidActivityRenderer(
    private val activity: AppViewsActivity,
    private val palette: MonetColors
) : GlowEngine {
    override val skin: SkinId get() = SkinId.LIQUID
    private val density = activity.resources.displayMetrics.density
    private val darkPalette = ColorUtils.calculateLuminance(palette.surface) < 0.5
    private val hardwareAccelerated = activity.isHardwareAccelerationRequested()
    private val realtimeCaptureRequested = LiquidRealtimeCaptureStore.isEnabled(activity)
    private val realtimeCaptureSupported = LiquidRealtimeCapturePolicy.isSupported(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val effectProfile = if (realtimeCaptureRequested && realtimeCaptureSupported) {
        LiquidEffectProfile.REALTIME_CAPTURE
    } else LiquidEffectProfile.STANDARD

    /** 见 [LiquidStaticBackdropHost]：保留实时档参数，但从不发起截图。 */
    private val staticBackdropHost = activity is LiquidStaticBackdropHost
    private val visualTuning = LiquidVisualTuningPolicy.resolve(
        dark = darkPalette
    )
    private val backgroundConfig = LiquidBackgroundStore.read(activity).config
    private val backgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-loader").apply { isDaemon = true }
    }
    private val parameters: LiquidParameters = LiquidTokenResolver.resolve(
        tuning = visualTuning,
        profile = effectProfile,
        dark = darkPalette
    )
    /** 渲染后端的准备、降级链与底图绑定，见 [LiquidBackendSet]。 */
    private val backends = LiquidBackendSet(
        parameters = parameters,
        density = density,
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightWidthDp * density
    }
    // 模态边框高光：顶沿 WHITE、在固定竖向行程内渐隐到 BASE_RATIO，再经 paint.alpha 整体
    // 缩放——与勾选控件同一套"光从顶沿沉入边框"的语言。渐变建在局部坐标（0..fadePx），
    // 由 localMatrix 平移跟随面板位置，不随面板高度拉伸、也不在 draw 里重建。
    private val modalEdgeShader = LinearGradient(
        0f, 0f, 0f, MODAL_EDGE_FADE_DP * density,
        Color.WHITE,
        ColorUtils.setAlphaComponent(Color.WHITE, (255 * MODAL_EDGE_BASE_RATIO).roundToInt()),
        Shader.TileMode.CLAMP
    )
    private val modalEdgeMatrix = Matrix()
    private val modalEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightWidthDp * density
        shader = modalEdgeShader
    }
    // 廉价路径的光晕带描边：无 shader 的均匀白，模拟折射 rim 的 Fresnel 圈。
    private val edgeBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val rootFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.background
    }
    // 可读性补偿的边缘：细描边 + 内缩渐变带，只在 edgeDefinition > 0 的悬浮栏上画。
    // 深色主题用黑（暗边），浅色主题用白（亮边），见 LiquidLegibilityTuning。
    private val legibilityEdgeColor = if (darkPalette) Color.BLACK else Color.WHITE
    private val legibilityRingAlpha = if (darkPalette) LiquidLegibilityTuning.EDGE_RING_ALPHA
        else LiquidLegibilityTuning.EDGE_RING_ALPHA_LIGHT
    private val legibilityBandPeak = if (darkPalette) LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA
        else LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA_LIGHT
    private val legibilityBandDp = if (darkPalette) LiquidLegibilityTuning.EDGE_BAND_DP
        else LiquidLegibilityTuning.EDGE_BAND_DP_LIGHT
    private val legibilityRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = legibilityEdgeColor
        strokeWidth = density
    }
    private val legibilityBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = legibilityEdgeColor
    }
    /** 悬浮栏宿主 → 可读性补偿；弱键，不延长 View 生命周期。 */
    private val surfaceLegibility = WeakHashMap<View, GlowLegibility>()
    private val backdropHostLocation = IntArray(2)
    /** 窗口底图换代计数，见 [windowBackdropGeneration]。 */
    private var backdropGeneration = 0L

    /** 悬浮栏宿主 → 内容节点玻璃（B 期，API 31+），见 [LiquidChromeBackdropApi31]。drawer 为 GlowChromeGlassApi31，
     *  声明成 Any 以免 API 31 以下加载本类时解析它。 */
    private class ChromeBackdrop(val target: GlowBackdropTarget, val drawer: Any) {
        /**
         * 上一次绘制确实走了内容节点玻璃。只有这时栏才与实时截图无关，截图换代可以不重录它；
         * 某一帧节点不可用而退回窗口玻璃时，它就和别的表面一样跟着截图刷新。
         */
        var drewByNode = false
    }
    private val chromeBackdrops = WeakHashMap<View, ChromeBackdrop>()
    private val chromeOffset = PointF()
    /** 节点路径任一次出错即永久停用：退回窗口玻璃，不牵连主后端的降级链。 */
    private var chromeBackdropBroken = false
    private val surfaceViews = WeakHashMap<View, LiquidSurfaceFootprint>()
    private val refreshWindows = WeakHashMap<View, LiquidWindowRefresh>()
    private val visibilityMatrix = FloatArray(9)
    private val retiredBackdropSources = LinkedHashSet<LiquidBackdropSource>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 实时截图的回调与后处理线程（[postProcessRealtimeCapture]）。只在实时档首次截图时启动；
     * 与 `liquid-background-loader` 分开，自定义底图解码不会堵住逐帧截图。
     * [feedback] 的抑制底图缓存只在这条线程上读写，主线程的释放也投递到这里。
     */
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val choreographer = Choreographer.getInstance()
    private val performanceController =
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
            LiquidPerformanceController(activity, ::onThermalStatusChanged)
        } else null

    /** 窗口刷新率、吞吐降档与 ADPF 目标周期，见 [LiquidRefreshRateController]。 */
    private val refreshRate = LiquidRefreshRateController(activity, performanceController)
    private var realtimeSamplePixelBudget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
    private val realtimeCaptureSourceRect = Rect()
    private val realtimeRootLocation = IntArray(2)
    private val movedSurfaceLocation = IntArray(2)
    private val refreshWindowLocation = IntArray(2)
    private val refreshWindowBounds = RectF()
    // Used only within one refresh pass; never retain a dismissed Dialog's root between callbacks.
    private var refreshWindowRoot: View? = null

    /**
     * 抑制遮罩是否已按**发起截图那一刻**的几何构建完成。
     *
     * `PixelCopy` 读的是最近一次已合成的帧，而回调最快也要下一帧才到。原实现在回调里用**当时**
     * 的 `getLocationOnScreen` 建遮罩，快速滑动时位置已经比截图内容前进了几十像素：
     * 一部分上一帧的玻璃没被抑制、原样留在截图里被再次折射（反馈残影），一部分干净背景反而被
     * 抹成底图。两条错位带每帧随滚动移动，就是"快速滑动仍抖动"。慢速滑动时错位只有几像素，
     * 所以看不出来。改为在发起截图时用当帧已绘制的 footprint 几何构建，工作量不变、时机对齐。
     */
    private var realtimeMaskReady = false


    /**
     * 实时采集的静止门控，见 [LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS]。
     *
     * 静止时不挂逐帧回调、不发 PixelCopy、不重画玻璃；窗口任何一次绘制（[realtimeDrawListener]）
     * 或每秒一次的兜底探测（[realtimeIdleProbe]）把它唤醒。
     */
    private var realtimeIdle = false
    private var identicalCaptureStreak = 0

    private val realtimeDrawListener = ViewTreeObserver.OnDrawListener {
        if (realtimeIdle) leaveRealtimeIdle(LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES)
    }
    /**
     * 主窗口失去焦点（面板、确认框等弹窗盖在上面）期间不发 PixelCopy。
     *
     * 弹窗下的主窗口被压暗层盖住，此时的截图没有可见收益；而弹窗入场收尾时主窗口仍会连着
     * 截 3 张，偶有一张在 RenderThread 上占 12–16ms，正好顶掉面板动画的一帧（2026-09-24
     * atrace：9 次开面板，打开后 500–580ms 处 copySurfaceInto 12.4/16.0/8.5ms）。
     * 重新获得焦点时补排一次采集，玻璃立刻跟上弹窗期间的内容变化。
     */
    private var windowObscured = false
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (closed) return@OnWindowFocusChangeListener
        if (!hasFocus) {
            windowObscured = true
        } else if (windowObscured) {
            windowObscured = false
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
        }
    }
    private val realtimeIdleProbe = Runnable {
        if (!realtimeIdle) return@Runnable
        // 探测只需再确认一张：相同就立刻回到静止，不必重新攒两张。
        identicalCaptureStreak = LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS - 1
        leaveRealtimeIdle(settleFrames = 0)
    }


    /** 实时截图的反馈抑制（遮罩 + 预缩放底图），见 [LiquidFeedbackSuppressor]。 */
    private val feedback = LiquidFeedbackSuppressor(paddingPx = parameters.effectPaddingDp * density)
    private var backdropSource: LiquidBackdropSource? = null
    private var realtimeBackdropSource: LiquidBackdropSource? = null
    private var realtimeCaptureSources: List<LiquidBackdropSource> = emptyList()
    private var realtimeCaptureNextIndex = 0
    private val captureRequests = LiquidCaptureRequestState()
    private var realtimeCaptureInFlight: LiquidCaptureRequest? = null
    private var realtimeCaptureFailureCount = 0
    private var realtimeCaptureSuspended = false
    private var realtimeFrameCallbackPosted = false
    private var realtimeNextCaptureNanos = Long.MAX_VALUE

    /**
     * 内容位移静默判定：滚动回调/显式位移会把已绑定的实时截屏变成过期采样源——PixelCopy
     * 至少滞后一帧，继续折射它会把旧位置的文字透进玻璃，形成沿滑动方向偏移的残影。
     * 位移活跃期间玻璃改采稳定底图，静止 [SCROLL_QUIET_MS] 后由下一次采集自动切回。
     */
    private var lastContentShiftNanos = 0L
    private var realtimeSamplingSuppressed = false

    /**
     * 本轮抑制完全由形变表面触发（二级页展开/收回），期间没有真实滚动。
     *
     * 这种抑制只换采样源、不降级着色：二级页卡片背后只有背景，稳定底图与实时截图几乎一致；
     * 若同时走 lite，动画结束后解除抑制时整组控件一次性补回散射与色散，rim 光影明显"跳变
     * 加载"（2026-09-24 用户报告）。真实滚动一旦发生即清零，恢复原来的 lite 降级。
     */
    private var suppressionFromMorphOnly = false
    private var scrollSettlePending = false
    private val scrollSettleCheck = Runnable { onScrollSettleCheck() }
    private var stretchOpticalIntensity = 1f
    /**
     * 当前回弹方向：-1 = 顶部下拉（表面上边缘发光）、+1 = 底部上拉、0 = 无。
     * 与 [stretchOpticalIntensity] 一起进 shader——方向只投到对应边缘，不再四边等亮。
     */
    private var stretchEdgeDirY = 0f
    private var activityVisible = false
    private var boundRoot: View? = null
    private var rootDrawable: LiquidRootDrawable? = null
    private var rootLayoutListener: View.OnLayoutChangeListener? = null
    private var rootScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var backdropRebuildPosted = false
    private var customBackdropFuture: Future<*>? = null
    private var customBackdropRequest: String? = null
    private var customBackdropLoadGeneration = 0L
    private var customBackdropFailed = false
    private var onFirstVisibleDraw: (() -> Unit)? = null
    private var onFatalFailure: (() -> Unit)? = null
    private var successfulDraw = false
    private var healthPosted = false
    private var fatalPosted = false
    private var closed = false
    private val rootScreenLocation = IntArray(2)
    private val realtimeFrameCallback = Choreographer.FrameCallback(::onRealtimeFrame)

    val backend: LiquidRenderBackend?
        get() = backends.backend

    override val backendName: String?
        get() = backend?.name

    /**
     * 当前后端之前那些更优先候选的失败原因；没有降级时为 null。
     *
     * 供设置页在后端名称旁展示，用户可以直接把它反馈回来，而不是只说"不好看"。
     */
    override val backendDegradeReason: String?
        get() = backends.degradeReason

    override val wantsScrollEdgeDissolve: Boolean
        get() = !closed && !fatalPosted

    override val windowBackdropGeneration: Long
        get() = backdropGeneration

    /**
     * 滚动边缘溶解与可读性探针共用：按宿主相对根的位置取可见根背景。
     * [rootScreenLocation] 由根背景在同一帧更早的 draw 写入，与宿主位置同帧对齐。
     */
    override fun drawWindowBackdrop(
        canvas: Canvas,
        host: View,
        bounds: RectF,
        alphaMask: Shader?,
        alpha: Float
    ): Boolean {
        if (closed || fatalPosted) return false
        val root = boundRoot ?: return false
        if (host.rootView !== root.rootView) return false
        val source = backdropSource?.takeIf { !it.isClosed } ?: return false
        host.getLocationOnScreen(backdropHostLocation)
        source.drawPresentationRegion(
            canvas = canvas,
            bounds = bounds,
            rootOffsetX = (backdropHostLocation[0] - rootScreenLocation[0]).toFloat(),
            rootOffsetY = (backdropHostLocation[1] - rootScreenLocation[1]).toFloat(),
            alphaMask = alphaMask,
            alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        )
        return true
    }

    /**
     * 悬浮栏的光学参数：着色基线与 [drawSurfaceLayers] 同源；直透比例在 GPU 后端是玻璃层之外
     * 的那部分，在 TRANSLUCENT 兜底下没有玻璃层、下方内容只隔着一层着色。
     */
    override fun floatingSurfaceOptics(): GlowSurfaceOptics? {
        if (closed || fatalPosted) return null
        val translucent = backends.backend == LiquidRenderBackend.TRANSLUCENT
        val base = LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.FLOATING, translucent, parameters)
        return GlowSurfaceOptics(
            surfaceColor = palette.surface,
            baseTintAlpha = base,
            maxTintAlpha = LiquidLegibilityTuning.ceiling(base),
            // 内容节点玻璃全不透，但清透档只轻微模糊：下方细节大半仍会透出来。
            seeThrough = when {
                translucent -> 1f
                chromeBackdropActive -> CHROME_DETAIL_SEE_THROUGH
                else -> 1f - LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING)
            }
        )
    }

    private val chromeBackdropActive: Boolean
        get() = supportsSurfaceBackdrop && chromeBackdrops.isNotEmpty()

    override val supportsSurfaceBackdrop: Boolean
        @SuppressLint("ReplaceWithAndroidVersion")
        get() = !closed && !fatalPosted && !chromeBackdropBroken && hardwareAccelerated &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @SuppressLint("ReplaceWithAndroidVersion")
    override fun setSurfaceBackdrop(host: View, target: GlowBackdropTarget?) {
        if (closed) return
        if (target == null || !supportsSurfaceBackdrop || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            chromeBackdrops.remove(host)?.let { entry ->
                closeChromeBackdrop(entry)
                host.invalidate()
            }
            return
        }
        val existing = chromeBackdrops[host]
        if (existing?.target === target) return
        existing?.let(::closeChromeBackdrop)
        // 构造里会编译 AGSL：失败与绘制期失败同等处理，永久停用节点路径，不能抛进调用方的 pre-draw。
        val drawer = runCatching { LiquidChromeBackdropApi31.create(parameters, density) }.getOrElse { error ->
            chromeBackdropBroken = true
            Log.w(TAG, "chrome backdrop unavailable", error)
            chromeBackdrops.remove(host)
            host.invalidate()
            return
        }
        chromeBackdrops[host] = ChromeBackdrop(target, drawer)
        host.invalidate()
    }

    @SuppressLint("ReplaceWithAndroidVersion")
    private fun closeChromeBackdrop(entry: ChromeBackdrop) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (entry.drawer as GlowChromeGlassApi31).close()
    }

    /**
     * 悬浮栏的内容节点玻璃。返回 false 表示本次不可用（内容节点尚未录制、宿主已脱离容器），
     * 调用方照常走窗口玻璃。出错时永久停用节点路径并自行吞掉异常——不能让
     * `drawWithFallback` 把它算成主后端失败、把全窗口的玻璃一起降级。
     */
    @SuppressLint("ReplaceWithAndroidVersion")
    private fun drawChromeBackdrop(
        entry: ChromeBackdrop,
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        host: View
    ): Boolean {
        if (chromeBackdropBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val content = entry.target.recordedCapture() ?: return false
        if (!entry.target.offsetOf(host, chromeOffset)) return false
        val realtime = effectProfile == LiquidEffectProfile.REALTIME_CAPTURE
        return runCatching {
            (entry.drawer as GlowChromeGlassApi31).draw(
                canvas = canvas,
                bounds = bounds,
                radiusPx = radiusPx,
                content = content,
                contentOffsetX = chromeOffset.x,
                contentOffsetY = chromeOffset.y,
                alpha = alpha / 255f,
                // 与窗口玻璃的浮动条同一套强度：常驻凝光下限 + 回弹方向增益。
                opticalIntensity = if (realtime) maxOf(stretchOpticalIntensity, FLOATING_OPTICAL_FLOOR) else 1f,
                stretchDirY = if (realtime) stretchEdgeDirY else 0f
            ) { recording, rect -> drawWindowBackdrop(recording, host, rect, null, 1f) }
        }.onFailure { error ->
            chromeBackdropBroken = true
            Log.w(TAG, "chrome backdrop disabled", error)
            chromeBackdrops.values.forEach(::closeChromeBackdrop)
            chromeBackdrops.clear()
        }.isSuccess
    }

    override fun setSurfaceLegibility(host: View, legibility: GlowLegibility?) {
        if (closed) return
        val next = legibility?.takeUnless { it == GlowLegibility.NEUTRAL }
        val previous = if (next == null) surfaceLegibility.remove(host) else surfaceLegibility.put(host, next)
        if (previous != next) host.invalidate()
    }

    override fun bindRoot(root: View, callbacks: GlowEngineCallbacks): Boolean =
        bindRoot(root, callbacks.onFirstVisibleDraw, callbacks.onFatalFailure)

    @MainThread
    fun bindRoot(
        root: View,
        onFirstVisibleDraw: () -> Unit,
        onFatalFailure: () -> Unit
    ): Boolean {
        if (closed) return false
        val existingRoot = boundRoot
        if (existingRoot != null && existingRoot !== root) return false
        this.onFirstVisibleDraw = onFirstVisibleDraw
        this.onFatalFailure = onFatalFailure
        if (existingRoot === root) return true

        boundRoot = root
        root.getLocationOnScreen(rootScreenLocation)
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = right - left != oldRight - oldLeft
            val heightChanged = bottom - top != oldBottom - oldTop
            if (widthChanged || heightChanged) {
                captureRequests.invalidate()
                scheduleBackdropRebuild(root)
            }
            root.getLocationOnScreen(rootScreenLocation)
        }
        rootLayoutListener = layoutListener
        root.addOnLayoutChangeListener(layoutListener)
        // 滚动监听不能省。`backdropOrigin` 在 draw 时写入并被快照进 display list，滚动只移动
        // RenderNode 而不重录，采样原点因此会滞留在旧位置；实时档的采样回调只有在 PixelCopy
        // 真正完成时才失效表面，而单飞回读的完成节奏远低于 UI 帧率，两者错开就表现为控件内
        // 背景抖动。这里保留监听，但只失效**位置真的变了**的表面，比原来的无条件全量失效更省。
        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            invalidateMovedSurfaces()
        }
        rootScrollListener = scrollListener
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
            root.viewTreeObserver.addOnDrawListener(realtimeDrawListener)
            root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        }

        rebuildBackdrop(root)
        val drawable = LiquidRootDrawable(this, palette.background)
        rootDrawable = drawable
        root.background = drawable

        root.invalidate()
        configureRealtimeRefreshRate(root)
        if (activityVisible) scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
        return true
    }

    override fun onStart() = onActivityStarted()
    override fun onStop() = onActivityStopped()

    @MainThread
    fun onActivityStarted() {
        if (closed) return
        activityVisible = true
        // 新会话重新从设备最高档开始探测；只降不升的策略靠会话边界自愈。
        refreshRate.resetSession()
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE &&
            !realtimeCaptureSuspended
        ) {
            performanceController?.start(refreshRate.frameIntervalNanos)
            boundRoot?.let(::configureRealtimeRefreshRate)
        }
        scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
    }

    @MainThread
    fun onActivityStopped() {
        activityVisible = false
        captureRequests.invalidate()
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        resetRealtimeIdle()
        performanceController?.stop()
        refreshRate.restore()
    }

    override fun surface(fallbackColor: Int, radiusDp: Float, role: SurfaceRole): Drawable =
        LiquidSurfaceDrawable(
            renderer = this,
            fallbackColor = fallbackColor,
            radiusPx = radiusDp.coerceAtLeast(0f) * density,
            role = role
        )

    /**
     * 回弹视口只让滚动前景共享同一个 Android 12+ stretch RenderNode（底层 Activity 背景保持
     * 静止），并按回弹距离提升表面光学强度；不再绘制任何边界采样环。API 31 以下或未开硬件加速
     * 时没有 stretch，保持原层级。安装本身见 `GlowEngine.installStretchViewport`。
     */
    override val wantsStretchViewport: Boolean
        @SuppressLint("ReplaceWithAndroidVersion")
        get() = !closed && Build.VERSION.SDK_INT >= 31 && hardwareAccelerated

    override fun onStretchDistance(distance: Float, edge: LiquidStretchEdge) =
        onStretchDistanceChanged(distance, edge)

    private fun onStretchDistanceChanged(distance: Float, edge: LiquidStretchEdge) {
        if (closed || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val next = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(distance)
        val nextDir = when (edge) {
            LiquidStretchEdge.TOP -> -1f
            LiquidStretchEdge.BOTTOM -> 1f
            LiquidStretchEdge.NONE -> 0f
        }
        // epsilon 挡住回弹尾段的亚感知步进（全程范围 0.85，0.004 ≈ 0.5%）；
        // 归零那帧 edge 变为 NONE、nextDir 必变，终态永远会发布，不会卡在半亮状态。
        if (abs(next - stretchOpticalIntensity) < 0.004f && nextDir == stretchEdgeDirY) return
        stretchOpticalIntensity = next
        stretchEdgeDirY = nextDir
        // 回弹不切换采样路径：玻璃覆盖区在截屏里本就被抑制遮罩换成稳定底图，过期
        // 像素进不了表面；而切到光学直采会让整圈边缘光在两条路径间乒乓闪烁
        // （2026-09-21 真机实证）。保持折射路径，方向性增益照常点亮回弹侧边缘。
        // 位移时间戳照常更新：若页面滑动已使抑制生效，回弹位移会顺延静默窗口。
        lastContentShiftNanos = System.nanoTime()
        invalidateRegisteredSurfaces()
    }

    @MainThread
    override fun onTrimMemory(level: Int) {
        if (closed || !LiquidMemoryPolicy.shouldReleaseGraphics(level)) return
        releaseGraphicsForMemoryPressure()
    }

    @MainThread
    override fun onLowMemory() {
        if (closed) return
        releaseGraphicsForMemoryPressure()
    }

    @SuppressLint("ReplaceWithAndroidVersion")
    private fun releaseGraphicsForMemoryPressure() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chromeBackdrops.values.forEach { (it.drawer as GlowChromeGlassApi31).releaseDisplayList() }
        }
        releaseSuppressionUnderlay()
        suspendRealtimeCapture(releaseBuffers = true)
        // 高阶折射/模糊和实时三缓冲可以在压力下永久降级，但最多 2 MiB 的稳定 underlay
        // 仍是用户可见背景本身。释放它会让当前 Activity 无重建地退回纯色，表现为自定义
        // 图片“过一段时间丢失”；保留稳定 source，同时切到零额外资源的 TRANSLUCENT 表面。
        backends.advanceToTranslucent()
        boundRoot?.invalidate()
        invalidateRegisteredSurfaces()
    }

    internal fun drawRoot(
        canvas: Canvas,
        bounds: Rect,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int
    ) {
        rootScreenLocation[0] = viewX
        rootScreenLocation[1] = viewY
        if (closed || fatalPosted) {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
            return
        }
        val source = backdropSource
        if (source != null && !source.isClosed) {
            source.drawRoot(canvas, bounds, alpha)
        } else {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
        }
    }

    internal fun drawSurface(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int,
        role: SurfaceRole,
        host: View? = null
    ) {
        val effectiveRadiusPx = radiusPx.coerceIn(
            0f,
            minOf(bounds.width(), bounds.height()).coerceAtLeast(0) * 0.5f
        )
        if (closed || fatalPosted) {
            overlayPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                effectiveRadiusPx, effectiveRadiusPx, overlayPaint
            )
            return
        }

        // Bitmap 截图等一次性软件 Canvas 只使用本次 fallback，不得永久销毁窗口的 GPU 后端。
        if (!canvas.isHardwareAccelerated) {
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = true
            )
            return
        }

        // 弹窗等外部窗口里的表面不能折射实时截屏：PixelCopy 只抓 Activity 窗口，
        // 采样到的是未被压暗/模糊的锐利底页，文字会穿透面板与内部控件混排。
        // 改采稳定底图的光学副本（默认渐变或预模糊自定义图），得到干净的磨砂分层。
        // 位移抑制期不走直采路径：驱动层已绑到稳定底图，shader 以 motionLite 单
        // 取样模式跑——折射弯曲对平滑底图无收益，但边缘光/通透全程与静止态一致，
        // 不再出现"切页瞬间高光消失再加载"的路径切换跳变（2026-09-21 真机实证）。
        val foreignWindow = host != null && host.rootView !== boundRoot?.rootView
        var foreignFellBack = false
        val chrome = if (role == SurfaceRole.FLOATING && host != null && !foreignWindow) chromeBackdrops[host] else null
        chrome?.drewByNode = false
        drawWithFallback { driver ->
            if (driver.backend != LiquidRenderBackend.TRANSLUCENT) {
                if (foreignWindow) {
                    // 优先走绑在稳定光学底图上的第二个折射驱动：面板因此拿到与窗内玻璃
                    // 同一条 rim（折射弯曲 + 菲涅尔 + 镜面 + 焦散 + 内阴影），而底图里
                    // 没有锐利内容可泄漏。取不到驱动（非 REFRACTION 后端、编译失败、
                    // 底图缺失）才退回平铺直采，并由 luminousEdge 补那条渐变描边。
                    val panelDriver = backends.foreignRefractionDriver(backdropSource)
                    // ⚠️ 必须自带 try/catch：`drawWithFallback` 把 lambda 里的任何异常都算成
                    // **主后端**失败并整体降级（REFRACTION→BLUR，全窗口的玻璃一起变）。
                    // 面板驱动是附加通道，坏掉只准它自己退回直采。
                    val drewPanel = panelDriver != null && runCatching {
                        panelDriver.drawBackdrop(
                            canvas,
                            bounds,
                            effectiveRadiusPx,
                            viewX - rootScreenLocation[0],
                            viewY - rootScreenLocation[1],
                            // 稳定底图无回弹语义：强度恒 1、无方向，边缘光按静止态渲染。
                            1f,
                            0f,
                            LiquidSurfaceAlphaPolicy.glassContentAlpha(role) * (alpha / 255f),
                            motionLite = false
                        )
                    }.onFailure {
                        backends.markForeignDriverBroken()
                    }.isSuccess
                    if (!drewPanel) {
                        foreignFellBack = true
                        // 填充透明度沿用折射路径的 glassContentAlpha：浮动条透出
                        // 真实下层内容，"对下取色"与主窗口一致。
                        // 必须乘上 drawable 自身 alpha：承载层/卡片交接靠 background.alpha=0
                        // 隐藏被接管的那张 drawable——只消色罩不消光学填充会让两张玻璃层
                        // 在形变中叠画，面板近乎不透明、落定才"加载通透"。
                        backdropSource?.takeIf { !it.isClosed }?.drawOpticalRegion(
                            canvas = canvas,
                            localBounds = bounds,
                            radiusPx = effectiveRadiusPx,
                            rootOffsetX = (viewX - rootScreenLocation[0]).toFloat(),
                            rootOffsetY = (viewY - rootScreenLocation[1]).toFloat(),
                            alpha = (LiquidSurfaceAlphaPolicy.glassContentAlpha(role) * alpha.toFloat())
                                .roundToInt()
                        )
                    }
                } else {
                    // B 期：主窗口里的悬浮栏优先走内容节点玻璃（实时、无截图滞后）；
                    // 节点不可用时照常折射窗口截屏。
                    val drewChrome = chrome != null && host != null &&
                        drawChromeBackdrop(chrome, canvas, bounds, effectiveRadiusPx, alpha, host)
                    if (!drewChrome) {
                        checkNotNull(realtimeBackdropSource ?: backdropSource) {
                            "GPU Liquid backend has no backdrop source"
                        }
                        driver.drawBackdrop(
                            canvas,
                            bounds,
                            effectiveRadiusPx,
                            viewX - rootScreenLocation[0],
                            viewY - rootScreenLocation[1],
                            if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
                                // 浮动条常驻一档折射强度：真实下层透入时折射弯曲可见，
                                // 是"有光感的玻璃"而非磨砂贴片；回弹增益仍可继续叠上去。
                                if (role == SurfaceRole.FLOATING) {
                                    maxOf(stretchOpticalIntensity, FLOATING_OPTICAL_FLOOR)
                                } else stretchOpticalIntensity
                            } else 1f,
                            if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
                                stretchEdgeDirY
                            } else 0f,
                            // 与光学直采路径同理：drawable alpha 是整张表面的主开关，
                            // 折射/模糊填充也必须随它衰减。
                            LiquidSurfaceAlphaPolicy.glassContentAlpha(role) * (alpha / 255f),
                            // 回弹期一并降级为 lite（2026-09-22 真机实证）。
                            // 09-21（六）当时坚持"回弹保持完整折射路径"，针对的是**切换绘制
                            // 路径**（drawOpticalRegion 直采）带来的跳变；而 lite 是**同一条
                            // shader** 少取几次样：焦散（liteCaustic）、菲涅尔、镜面、内阴影、
                            // contentAlpha 通透逐项保留，边缘光与静止态同源，只少了内部那层
                            // 约 6% 的散射混合。短页面里所有玻璃表面都在屏幕上，回弹期跑全量
                            // 散射就是 GPU 墙——用户实测帧间隔 18% 超 12.5ms、`High input
                            // latency` 占 73% 帧，而 UI 线程只占 5.4ms，其余全在 GPU。
                            motionLite = (realtimeSamplingSuppressed && !suppressionFromMorphOnly) ||
                                stretchOpticalIntensity > 1f
                        )
                    }
                    chrome?.drewByNode = drewChrome
                }
            }
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = driver.backend == LiquidRenderBackend.TRANSLUCENT,
                // 折射驱动自带边缘光，只有退回平铺直采的面板才需要那条渐变描边补光；
                // 两者同时上会叠成一圈比真实 rim 亮一个量级的硬边（2026-09-21（八）实证）。
                luminousEdge = foreignWindow && foreignFellBack,
                legibility = if (role == SurfaceRole.FLOATING && host != null) surfaceLegibility[host] else null
            )
        }
        scheduleHealthConfirmationAfterDraw()
    }

    private fun drawSurfaceLayers(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        fallbackColor: Int,
        role: SurfaceRole,
        translucentFallback: Boolean,
        luminousEdge: Boolean = false,
        legibility: GlowLegibility? = null
    ) {
        val baseFraction = LiquidSurfaceAlphaPolicy.resolve(
            role = role,
            translucentFallback = translucentFallback,
            parameters = parameters
        )
        // 可读性补偿只加厚、不减薄；上限与 floatingSurfaceOptics 报给策略的一致。
        val surfaceFraction = if (legibility == null) baseFraction
            else LiquidLegibilityTuning.tintAlpha(baseFraction, legibility.boost)
        val surfaceAlpha = (surfaceFraction * alpha).toInt().coerceIn(0, 255)
        // 高阶玻璃使用中性的 surface 轻染色；fallback 才恢复业务传入的实色以保证可读性。
        val tintColor = when {
            role == SurfaceRole.SELECTED_ITEM -> ColorUtils.blendARGB(palette.surface, palette.primary, .06f)
            translucentFallback -> fallbackColor
            else -> palette.surface
        }
        overlayPaint.color = ColorUtils.setAlphaComponent(tintColor, surfaceAlpha)
        canvas.drawRoundRect(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(),
            radiusPx, radiusPx, overlayPaint
        )
        // Thin neutral separation; floating bars get a clearer edge without a saturated fill.
        val edgeAlpha = (parameters.highlightAlpha *
            LiquidSurfaceEdgePolicy.alphaMultiplier(role) * alpha).toInt().coerceIn(0, 255)
        val inset = outlinePaint.strokeWidth * 0.5f
        // 光学直采路径（位移抑制/外部窗口）不跑折射 shader：菲涅尔/镜面/焦散那条
        // 边缘光晕带整条缺席，只剩细描边——切页瞬间所有控件"边缘高光消失再加载"
        // 的观感正源于此。此路径统一改走顶沿提亮渐变描边，保留"边缘有光"的读感。
        val useLuminousEdge = edgeAlpha > 0 &&
            (luminousEdge || role == SurfaceRole.MODAL || role == SurfaceRole.FLOATING)
        if (useLuminousEdge) {
            // 高光收进边框线条：顶沿提亮、固定行程内落回基础描边色。
            // paint.alpha 对 shader 输出整体缩放，逐帧只改 alpha 与平移。
            // 浮动条共享同一套"光从顶沿沉入边框"的语言，与模态、勾选控件一致。
            // 廉价路径上普通角色的提亮收敛到 OPTICAL_EDGE_TOP_BOOST：真实折射 rim
            // 只有 1~2% 白度，过强的顶沿高光会读成描边而不是光。
            val topBoost = if (role == SurfaceRole.MODAL || role == SurfaceRole.FLOATING)
                MODAL_EDGE_TOP_BOOST else OPTICAL_EDGE_TOP_BOOST
            modalEdgePaint.alpha = (edgeAlpha * topBoost).toInt().coerceIn(0, 255)
            modalEdgeMatrix.setTranslate(0f, bounds.top.toFloat())
            modalEdgeShader.setLocalMatrix(modalEdgeMatrix)
            canvas.drawRoundRect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
                (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f),
                modalEdgePaint
            )
            if (luminousEdge) {
                // 折射 rim 的有效亮度只有 Fresnel≈0.025/specular≈0.06 量级——
                // 光晕带只是一层极淡的内圈辉光，不是亮环。单层 10dp 描边内缩半宽
                // 使外侧与表面边缘齐平（无需 clipPath），alpha 压到同一量级，
                // 只保留"边缘微微泛光"的读感，避免出现硬边描边轮廓。
                val bandW = OPTICAL_EDGE_BAND_DP * density
                edgeBandPaint.strokeWidth = bandW
                edgeBandPaint.alpha =
                    (edgeAlpha * OPTICAL_EDGE_BAND_ALPHA).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    bounds.left + bandW * 0.5f, bounds.top + bandW * 0.5f,
                    bounds.right - bandW * 0.5f, bounds.bottom - bandW * 0.5f,
                    (radiusPx - bandW * 0.5f).coerceAtLeast(0f),
                    (radiusPx - bandW * 0.5f).coerceAtLeast(0f),
                    edgeBandPaint
                )
            }
        } else {
            outlinePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, edgeAlpha)
            canvas.drawRoundRect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
                (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f),
                outlinePaint
            )
        }
        val definition = legibility?.edgeDefinition ?: 0f
        if (definition > 0f) drawLegibilityEdge(canvas, bounds, radiusPx, definition * (alpha / 255f))
    }

    /**
     * 悬浮栏的边缘定义：细描边勾出轮廓，内缩渐变带给出厚度。深色主题画暗边（Apple 称
     * darkened edge），浅色主题画亮边（白描边 + 向内渐隐的白带），颜色见 legibilityEdgeColor。
     * 画在白色高光描边之后，两者叠成"外暗内亮"的玻璃边，而不是互相抵消。
     */
    private fun drawLegibilityEdge(canvas: Canvas, bounds: Rect, radiusPx: Float, strength: Float) {
        val ringInset = legibilityRingPaint.strokeWidth * 0.5f
        legibilityRingPaint.alpha =
            (255f * legibilityRingAlpha * strength).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(
            bounds.left + ringInset, bounds.top + ringInset,
            bounds.right - ringInset, bounds.bottom - ringInset,
            (radiusPx - ringInset).coerceAtLeast(0f),
            (radiusPx - ringInset).coerceAtLeast(0f),
            legibilityRingPaint
        )
        // 渐变暗带：外沿对齐、宽度递增的描边叠加，贴边最深、向内缓出归零，见 LiquidLegibilityTuning。
        val bandWidth = legibilityBandDp * density
        for (step in 1..LiquidLegibilityTuning.EDGE_BAND_STEPS) {
            val stepAlpha = LiquidLegibilityTuning.edgeBandStepAlpha255(step, strength, legibilityBandPeak,
                lightProfile = !darkPalette).coerceIn(0, 255)
            if (stepAlpha == 0) continue
            val width = bandWidth * step / LiquidLegibilityTuning.EDGE_BAND_STEPS
            val bandHalf = width * 0.5f
            legibilityBandPaint.strokeWidth = width
            legibilityBandPaint.alpha = stepAlpha
            canvas.drawRoundRect(
                bounds.left + bandHalf, bounds.top + bandHalf,
                bounds.right - bandHalf, bounds.bottom - bandHalf,
                (radiusPx - bandHalf).coerceAtLeast(0f),
                (radiusPx - bandHalf).coerceAtLeast(0f),
                legibilityBandPaint
            )
        }
    }

    private inline fun drawWithFallback(draw: (LiquidBackendDriver) -> Unit) {
        while (!closed) {
            val driver = backends.current ?: if (backends.selectCurrentPrepared()) backends.current else null
            if (driver == null) {
                dispatchFatalFailure()
                return
            }
            val result = runCatching { draw(driver) }
            if (result.isSuccess) {
                successfulDraw = true
                return
            }
            if (!advanceAfterFailure(driver.backend)) {
                dispatchFatalFailure()
                return
            }
        }
    }

    private fun scheduleBackdropRebuild(root: View) {
        if (closed || backdropRebuildPosted) return
        backdropRebuildPosted = true
        root.postOnAnimation {
            backdropRebuildPosted = false
            if (!closed && boundRoot === root) rebuildBackdrop(root)
        }
    }

    private fun rebuildBackdrop(root: View) {
        if (closed) return
        val width = root.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = root.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val existing = backdropSource
        if (existing != null && existing.fullWidth == width && existing.fullHeight == height) {
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        val targetSize = LiquidBackdropSizingPolicy.resolve(width, height)
        if (existing != null &&
            existing.bitmap.width == targetSize.width &&
            existing.bitmap.height == targetSize.height
        ) {
            captureRequests.invalidate()
            existing.updateFullSize(width, height)
            backdropGeneration++
            bindPreparedBackendsToBackdrop(existing)
            root.invalidate()
            invalidateRegisteredSurfaces()
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        // 先创建并绑定新 source，再让下一次 traversal 接收全部 invalidation 后断开旧 source；
        // close 只释放 Java 所有权而不调用 Bitmap.recycle()，不能把帧回调误当作 GPU fence。
        val created = runCatching {
            LiquidBackdropSource.create(palette, width, height)
        }.getOrNull()
        if (created == null) {
            if (existing == null) backends.advanceToTranslucent()
            return
        }
        captureRequests.invalidate()
        created.markPublished()
        backdropSource = created
        backdropGeneration++
        bindPreparedBackendsToBackdrop(created)
        root.invalidate()
        invalidateRegisteredSurfaces()
        if (existing != null) retireBackdropAfterFrame(root, existing)
        scheduleCustomBackdropIfNeeded(root, width, height)
    }

    /**
     * 外部图片解码永远离开主线程和 Drawable.draw；首帧先使用自动 Monet source，完成后再原子
     * 切换。图片资产失败只保留自动 source，不触发 Liquid renderer 的后端/皮肤回滚。
     */
    private fun scheduleCustomBackdropIfNeeded(root: View, width: Int, height: Int) {
        if (closed || customBackdropFailed || backgroundConfig.mode != LiquidBackgroundMode.CUSTOM) {
            return
        }
        val assetId = requireNotNull(backgroundConfig.assetId)
        val existing = backdropSource
        if (existing?.customAssetId == assetId &&
            existing.fullWidth == width && existing.fullHeight == height
        ) {
            return
        }
        val target = LiquidBackdropSizingPolicy.resolve(width, height)
        val request = "$assetId:${target.width}x${target.height}:$width:$height"
        if (customBackdropRequest == request && customBackdropFuture?.isDone == false) return

        customBackdropFuture?.cancel(true)
        val generation = ++customBackdropLoadGeneration
        customBackdropRequest = request
        customBackdropFuture = backgroundWorker.submit {
            val bitmap = runCatching {
                LiquidBackgroundStore.decodeBackdrop(
                    context = activity.applicationContext,
                    config = backgroundConfig,
                    targetWidth = target.width,
                    targetHeight = target.height,
                    backgroundColor = palette.background,
                    dark = darkPalette
                )
            }.getOrNull()
            if (bitmap == null) {
                mainHandler.post {
                    if (!closed && generation == customBackdropLoadGeneration) {
                        customBackdropRequest = null
                        customBackdropFailed = true
                    }
                }
                return@submit
            }
            if (Thread.currentThread().isInterrupted) { bitmap.recycle(); return@submit }
            val source = runCatching {
                LiquidBackdropSource.fromCustomBitmap(bitmap, assetId, width, height, density)
            }.getOrElse {
                bitmap.recycle()
                if (!Thread.currentThread().isInterrupted) mainHandler.post {
                    if (!closed && generation == customBackdropLoadGeneration) {
                        customBackdropFailed = true
                        customBackdropRequest = null
                    }
                }
                return@submit
            }
            if (Thread.currentThread().isInterrupted) { source.discardUnpublished(); return@submit }
            val posted = mainHandler.post {
                if (closed || generation != customBackdropLoadGeneration || boundRoot !== root) {
                    source.discardUnpublished()
                    return@post
                }
                val currentWidth = root.width.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                val currentHeight = root.height.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
                val currentTarget = LiquidBackdropSizingPolicy.resolve(currentWidth, currentHeight)
                if (currentWidth != width || currentHeight != height || currentTarget != target) {
                    source.discardUnpublished()
                    customBackdropRequest = null
                    scheduleBackdropRebuild(root)
                    return@post
                }
                if (runCatching { source.markPublished() }.isFailure) {
                    source.close() // Publication may have reached HWUI; never recycle this pair.
                    customBackdropRequest = null
                    customBackdropFailed = true
                    return@post
                }
                val previous = backdropSource
                captureRequests.invalidate()
                backdropSource = source
                backdropGeneration++
                customBackdropRequest = null
                bindPreparedBackendsToBackdrop(source)
                root.invalidate()
                invalidateRegisteredSurfaces()
                if (previous != null) retireBackdropAfterFrame(root, previous)
            }
            if (!posted) source.discardUnpublished()
        }
    }

    private fun retireBackdropAfterFrame(root: View, source: LiquidBackdropSource) {
        retiredBackdropSources += source
        root.postOnAnimation {
            if (retiredBackdropSources.remove(source)) source.close()
        }
    }

    /** Surface Drawable 在 draw 时更新真实几何；弱键避免 renderer 反向延长 View 生命周期。 */
    internal fun registerSurfaceView(
        view: View,
        bounds: Rect,
        radiusPx: Float,
        originX: Int,
        originY: Int
    ) {
        if (closed) return
        registerRefreshWindow(view.rootView)
        val existing = surfaceViews[view]
        val footprint = existing ?: LiquidSurfaceFootprint().also {
            surfaceViews[view] = it
        }
        // 形变表面（二级页容器展开/收回、预测式返回）：View 本身不动，只有内部矩形在变，
        // 滚动那条"原点变了就抑制"的判定抓不到。形变期继续折射实时截图，截图里的反馈抑制
        // 遮罩还是之前某一帧的轮廓，玻璃里就会留下一道旧轮廓的圆角缝，上下两块折射的是
        // 背景的不同位置——"两个画面割断"，自定义背景下尤其明显（2026-09-24 真机实证：
        // 关掉实时截图缝即消失）。与滚动同一机制：形变期改采稳定底图，静默后自动回到实时档。
        // 只处理主窗口里的形变（二级页）：弹窗窗口的形变层不在主窗口截图里，截图中的反馈遮罩割不到它，
        // 抑制没有收益，却会让每次开关弹窗都整组失效两次（进入抑制 + 静默后解除）——真机实测解除那次
        // 主页面整屏玻璃重录一帧 37ms、弹窗被拖到 40ms（2026-09-27 framestats + 事件日志）。
        if (existing != null && view is LiquidMotionSurfaceFrameProvider && view.rootView === boundRoot?.rootView && (
                existing.left != bounds.left || existing.top != bounds.top ||
                    existing.right != bounds.right || existing.bottom != bounds.bottom ||
                    existing.radiusPx != radiusPx)
        ) {
            lastContentShiftNanos = System.nanoTime()
            val wasSuppressed = realtimeSamplingSuppressed
            suppressRealtimeSamplingWhileScrolling()
            if (!wasSuppressed && realtimeSamplingSuppressed) suppressionFromMorphOnly = true
        }
        footprint.update(bounds, radiusPx, originX, originY)
    }

    /**
     * 显式变换回调（按下缩放、弹性拖拽、导航条指示器位移等）不等于内容位移：
     * 按下缩放绕中心缩放、表面原点不变，此时抑制只会把底图 real→stable 白闪一下。
     * 抑制交给 [flushSurfaceRefresh] 在确认表面原点真的变化后再触发。
     */
    @MainThread
    override fun notifyPositionChanged() {
        lastContentShiftNanos = System.nanoTime()
        queueSurfaceRefresh(contentChanged = false)
    }

    /**
     * 只失效采样原点已经过期的表面。
     *
     * 每次滚动回调做的是 O(表面数 × 层级) 的 `getLocationOnScreen` 比对，没有移动的表面不会被
     * 重录，也不会重跑折射 shader。
     */
    private fun invalidateMovedSurfaces() {
        lastContentShiftNanos = System.nanoTime()
        // OnScrollChangedListener 只在真实滚动位移时触发：内容已经在某个表面下方
        // 滑动（哪怕表面自身没动，滞后截屏也会把旧位置像素折射进去），立即抑制。
        // 真实滚动撤销形变豁免：移动的表面随后按原点重录、自然落到 lite；不在滚动回调里整组重录。
        suppressionFromMorphOnly = false
        suppressRealtimeSamplingWhileScrolling()
        queueSurfaceRefresh(contentChanged = false)
    }

    /**
     * 位移活跃期把玻璃采样从滞后截屏切到稳定底图：表面立刻按正确原点重录一次，
     * 滚动中不再折射旧位置像素，也不再为每一帧截图触发整组表面重录。
     */
    private fun suppressRealtimeSamplingWhileScrolling() {
        // 只门控效果档位，不门控"是否已有实时缓冲"：首帧采集完成前就开始的滑动同样需要
        // 抑制——否则那一小段手势既折射过期底图又继续触发每帧 PixelCopy。
        if (closed || realtimeSamplingSuppressed ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val stable = backdropSource
        if (stable == null || stable.isClosed) return
        realtimeSamplingSuppressed = true
        bindPreparedBackendsToBackdrop(stable)
        invalidateRegisteredSurfaces()
        if (!scrollSettlePending) {
            scrollSettlePending = true
            mainHandler.postDelayed(scrollSettleCheck, LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS)
        }
    }

    /**
     * 手指按在屏幕上的时段。
     *
     * 抑制解除是一次重同步：整组表面重录回折射路径 + 立刻排一次全屏 PixelCopy，
     * 采集完成后再整组失效一次。它落在**新手势的头几帧**上就是可感知的迟滞——
     * 用户实测最容易复现的姿势正是"回弹刚结束立刻反向滑"：回弹把静默窗口一路顺延，
     * 手一松窗口到点，解除恰好撞上下一次按下（2026-09-22 用户报告）。
     *
     * 按着时把解除往后推，但设上界：长按不动本来就该恢复实时档，不能无限等。
     */
    private var gestureActiveSinceNanos = 0L

    @MainThread
    override fun notifyGestureActive(active: Boolean) {
        if (closed) return
        gestureActiveSinceNanos = if (active) System.nanoTime() else 0L
    }

    private fun gestureHoldsRelease(now: Long): Boolean {
        val since = gestureActiveSinceNanos
        if (since == 0L) return false
        return now - since < GESTURE_RELEASE_HOLD_MS * NANOS_PER_MILLISECOND
    }

    private fun onScrollSettleCheck() {
        scrollSettlePending = false
        if (closed || !realtimeSamplingSuppressed) return
        val now = System.nanoTime()
        if (gestureHoldsRelease(now)) {
            scrollSettlePending = true
            mainHandler.postDelayed(scrollSettleCheck, LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS)
            return
        }
        val quietNanos = System.nanoTime() - lastContentShiftNanos
        // 回弹形变未归零时同样保持抑制：按住不动没有新位移回调，静默窗口会自然
        // 攒满——此时解除会让表面重录回折射路径，下一次位移又切回光学直采，
        // 边缘光在两条路径之间闪烁。形变归零后（intensity 回落 1）才允许解除。
        if (quietNanos < LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS * NANOS_PER_MILLISECOND ||
            stretchOpticalIntensity > 1f
        ) {
            scrollSettlePending = true
            mainHandler.postDelayed(scrollSettleCheck, LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS)
            return
        }
        realtimeSamplingSuppressed = false
        suppressionFromMorphOnly = false
        // 抑制期录制的都是光学直采路径，解除后要重录回折射路径——实时模式下随后的
        // 采集完成会再失效一次；采集已挂起（suspended）时则靠这次失效恢复玻璃观感。
        invalidateRegisteredSurfaces()
        // 排一次新采集；完成时 handleRealtimeCaptureResult 会把实时缓冲绑回去。
        // 与唤醒同一规则，隔 [LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES] 帧再截：上面那次整组重录
        // 的 GPU 约 12ms，同一 vsync 就截的话 PixelCopy 在 RenderThread 上干等它的栅栏 15–18ms，
        // 每次滚动/翻页/切页收尾都掉一帧（2026-09-27 atrace：copySurfaceInto 18.5ms，隔开后只剩拷贝本身）。
        resetRealtimeIdle()
        realtimeNextCaptureNanos = System.nanoTime() +
            LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES * refreshRate.frameIntervalNanos
        postRealtimeFrameCallback()
    }

    private fun clearScrollSuppression() {
        realtimeSamplingSuppressed = false
        suppressionFromMorphOnly = false
        if (scrollSettlePending) {
            scrollSettlePending = false
            mainHandler.removeCallbacks(scrollSettleCheck)
        }
    }

    private fun invalidateRegisteredSurfaces() {
        queueSurfaceRefresh(contentChanged = true)
    }

    /**
     * 实时截图换了一张：只刷新真正读截图的表面。内容节点玻璃栏的输入是内容节点 + 稳定底图，
     * 截图换代不改变它的任何一个像素，重录它（栏的 display list + 效果节点）是白做的。
     */
    private fun invalidateRealtimeCaptureConsumers() {
        queueSurfaceRefresh(contentChanged = true, captureOnly = true)
    }

    /** 已由内容节点玻璃绘制、不读实时截图的表面。 */
    private fun isCaptureIndependent(view: View): Boolean = chromeBackdrops[view]?.drewByNode == true

    private fun registerRefreshWindow(windowRoot: View) {
        val observer = windowRoot.viewTreeObserver
        val existing = refreshWindows[windowRoot]
        if (existing?.observer?.get() === observer && observer.isAlive) return
        existing?.let(::removeRefreshWindow)
        val rootRef = WeakReference(windowRoot)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            rootRef.get()?.let(::flushSurfaceRefresh)
            true
        }
        val scroll = ViewTreeObserver.OnScrollChangedListener {
            rootRef.get()?.let { refreshWindows[it]?.batch?.mark(contentChanged = false) }
        }
        refreshWindows[windowRoot] = LiquidWindowRefresh(WeakReference(observer), preDraw, scroll)
        observer.addOnPreDrawListener(preDraw)
        observer.addOnScrollChangedListener(scroll)
    }

    private fun removeRefreshWindow(state: LiquidWindowRefresh) {
        state.observer.get()?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(state.preDraw)
            it.removeOnScrollChangedListener(state.scroll)
        }
    }

    private fun queueSurfaceRefresh(contentChanged: Boolean, captureOnly: Boolean = false) {
        if (closed) return
        val iterator = refreshWindows.entries.iterator()
        while (iterator.hasNext()) {
            val (root, state) = iterator.next()
            if (!root.isAttachedToWindow) {
                removeRefreshWindow(state)
                iterator.remove()
            } else if (state.batch.mark(contentChanged, captureOnly) && contentChanged && root.isShown) {
                // New source pixels need a draw. Position owners already schedule their own frame.
                triggerSurfaceFrame(root, captureOnly)
            }
        }
    }

    /**
     * 最小损伤域的遍历触发：失效窗口内任一可见表面即可调度一帧，而它本就因
     * contentChanged 需要重录——损伤域只有一个表面的矩形。preDraw 的
     * [flushSurfaceRefresh] 再按 `shouldRefresh` 规则精确补齐其余表面。
     *
     * 旧实现用 `root.invalidate()`：整窗损伤会把页面里每个 View 的 display list 都
     * 标脏重录。滚动期每次 PixelCopy 完成、回弹期每次 stretch 强度步进都会走到这里，
     * 每秒数十次整窗重录就是"高级材质滑动掉帧"的主要链路。
     *
     * 找不到可见表面则本窗口无需这次绘制：CONTENT 标记留在 batch 里，窗口下一次遍历的
     * preDraw 仍会完整 flush（隐藏表面经 `skipped` 在重新可见时补偿刷新）。
     */
    private fun triggerSurfaceFrame(windowRoot: View, captureOnly: Boolean) {
        val iterator = surfaceViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val view = entry.key
            if (!view.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (view.rootView !== windowRoot || !view.isShown) continue
            // 截图换代时挑一个真正读截图的表面来调度这一帧，节点玻璃栏不必重录。
            if (captureOnly && isCaptureIndependent(view)) continue
            view.invalidate()
            return
        }
    }

    /** Flush after all this window's animation callbacks, never on the first partial transform. */
    private fun flushSurfaceRefresh(windowRoot: View) {
        if (closed) return
        val changes = refreshWindows[windowRoot]?.batch?.take() ?: return
        if (changes == 0) return
        val contentChanged = LiquidRefreshBatch.anyContent(changes)
        // 位移门控：只有某个表面真的改了屏幕原点才算"内容位移"。点击、按键或
        // 零位移的滚动回调同样会走这条链路，若在此刻切底图，所有玻璃会在一次
        // 无事发生的回调里 real→stable 闪一下。
        var surfaceMoved = false
        // 只有主窗口里的表面移动才意味着"被截的内容在动"（实时截图至少滞后一帧，会折射出旧位置）。
        // 弹窗窗口里的玻璃移动时，被截的主窗口内容是静止的，实时截图始终有效：照常按新原点重录，
        // 但不切稳定底图、不降级、不排静默后的整组重录——否则每开一次弹窗，动画结束约 250ms 后
        // 主页面整组玻璃重录 + 补截图，真机 framestats 实测为一帧 39ms、弹窗窗口被拖到 42ms（2026-09-27）。
        val mainWindowRoot = boundRoot?.rootView
        try {
            val surfaceIterator = surfaceViews.entries.iterator()
            while (surfaceIterator.hasNext()) {
                val entry = surfaceIterator.next()
                val view = entry.key
                if (!view.isAttachedToWindow) { surfaceIterator.remove(); continue }
                if (view.rootView !== windowRoot) continue
                val visible = isSurfacePotentiallyVisible(view)
                // 不可见表面的 movedSurfaceLocation 还是上一个表面的残留坐标，
                // 原值比对结果无意义；shouldRefresh 对 !visible 本就会忽略该参数。
                val originChanged = visible &&
                    !entry.value.matchesOrigin(movedSurfaceLocation[0], movedSurfaceLocation[1])
                if (originChanged && windowRoot === mainWindowRoot) surfaceMoved = true
                if (entry.value.refreshState.shouldRefresh(visible,
                        originChanged = originChanged,
                        contentChanged = LiquidRefreshBatch.surfaceContentChanged(
                            changes, captureIndependent = isCaptureIndependent(view)
                        ))) view.invalidate()
            }
        } finally { refreshWindowRoot = null }
        // 抑制在 preDraw 内、draw 前生效：本帧被失效的位移表面重录时已经读到
        // motionLite + 稳定底图，不会产生"先按旧底图录一帧再切"的中间态。
        if (surfaceMoved && !contentChanged) suppressRealtimeSamplingWhileScrolling()
    }

    /**
     * Conservative window culling, not ancestor clipping. Keep the optical margin and all uncertain
     * transform/stretch frames. Each Dialog uses its own root; do not compare it to the Activity root.
     * Populates movedSurfaceLocation for the origin check without a second location query.
     */
    private fun isSurfacePotentiallyVisible(view: View): Boolean {
        if (!view.isShown) return false
        view.getLocationOnScreen(movedSurfaceLocation)
        if (stretchOpticalIntensity > 1f) return true
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.animation != null || ancestor is LiquidMotionSurfaceFrameProvider) return true
            if (!ancestor.matrix.isIdentity) {
                ancestor.matrix.getValues(visibilityMatrix)
                if (!LiquidRefreshVisibilityPolicy.isTranslationOnly(visibilityMatrix)) return true
            }
            ancestor = ancestor.parent as? View
        }
        val windowRoot = view.rootView
        if (refreshWindowRoot !== windowRoot) {
            windowRoot.getLocationOnScreen(refreshWindowLocation)
            val left = refreshWindowLocation[0].toFloat()
            val top = refreshWindowLocation[1].toFloat()
            refreshWindowBounds.set(left, top, left + windowRoot.width, top + windowRoot.height)
            refreshWindowRoot = windowRoot
        }
        val left = movedSurfaceLocation[0].toFloat()
        val top = movedSurfaceLocation[1].toFloat()
        return LiquidRefreshVisibilityPolicy.intersectsWindow(left, top, left + view.width, top + view.height,
            refreshWindowBounds.left, refreshWindowBounds.top, refreshWindowBounds.right, refreshWindowBounds.bottom,
            parameters.effectPaddingDp * density)
    }

    /** 只有实时档申请刷新率；具体规则见 [LiquidRefreshRateController.configure]。 */
    private fun configureRealtimeRefreshRate(root: View, thermalStatus: Int? = null) {
        if (effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        if (thermalStatus == null) refreshRate.configure(root) else refreshRate.configure(root, thermalStatus)
    }

    private fun onThermalStatusChanged(status: Int) {
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        val root = boundRoot ?: return
        refreshRate.resetThroughput()
        configureRealtimeRefreshRate(root, status)
        // 只降帧率仅减少"做几次"；同时降采样分辨率才能压住每次的回读与纹理上传量。
        val budget = LiquidPerformancePolicy.samplePixelBudget(thermalStatus = status)
        if (budget != realtimeSamplePixelBudget) {
            realtimeSamplePixelBudget = budget
            releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        }
        realtimeNextCaptureNanos = System.nanoTime() + refreshRate.frameIntervalNanos
    }

    /** 由 VSync 驱动目标最高 120Hz；PixelCopy 始终单飞，慢设备自然按完成速度降频。 */
    private fun scheduleRealtimeCapture(delayMs: Long) {
        if (boundRoot == null) return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        resetRealtimeIdle()
        realtimeNextCaptureNanos = System.nanoTime() +
            delayMs.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        postRealtimeFrameCallback()
    }

    private fun postRealtimeFrameCallback() {
        if (realtimeFrameCallbackPosted || closed || !activityVisible || staticBackdropHost || windowObscured ||
            realtimeCaptureSuspended || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        realtimeFrameCallbackPosted = true
        choreographer.postFrameCallback(realtimeFrameCallback)
    }

    private fun removeRealtimeFrameCallback() {
        if (!realtimeFrameCallbackPosted) return
        realtimeFrameCallbackPosted = false
        choreographer.removeFrameCallback(realtimeFrameCallback)
    }

    /** 本张与当前绑定的截图相同：攒够连续张数就静止，否则隔几帧再确认一次。 */
    private fun onIdenticalCapture() {
        identicalCaptureStreak += 1
        if (LiquidRealtimeCapturePolicy.shouldEnterIdle(identicalCaptureStreak)) {
            enterRealtimeIdle()
        } else {
            realtimeNextCaptureNanos = System.nanoTime() +
                LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES * refreshRate.frameIntervalNanos
        }
    }

    private fun enterRealtimeIdle() {
        if (realtimeIdle) return
        realtimeIdle = true
        removeRealtimeFrameCallback()
        mainHandler.removeCallbacks(realtimeIdleProbe)
        mainHandler.postDelayed(realtimeIdleProbe, LiquidRealtimeCapturePolicy.IDLE_PROBE_MS)
    }

    /**
     * 离开静止并排一次采集。[settleFrames] 帧之后才截：由窗口绘制唤醒时，触发唤醒的那一帧
     * 此刻可能还没合成，立刻截会截到旧帧、被误判为"没变"。
     */
    private fun leaveRealtimeIdle(settleFrames: Int) {
        if (!realtimeIdle) return
        realtimeIdle = false
        mainHandler.removeCallbacks(realtimeIdleProbe)
        realtimeNextCaptureNanos = System.nanoTime() + settleFrames * refreshRate.frameIntervalNanos
        postRealtimeFrameCallback()
    }

    /** 生命周期边界（停止/挂起/关闭）：静止状态与探测一并清掉，恢复时从头采集。 */
    private fun resetRealtimeIdle() {
        realtimeIdle = false
        identicalCaptureStreak = 0
        mainHandler.removeCallbacks(realtimeIdleProbe)
    }

    private fun onRealtimeFrame(frameTimeNanos: Long) {
        realtimeFrameCallbackPosted = false
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE || realtimeIdle
        ) {
            return
        }
        if (realtimeCaptureInFlight == null &&
            LiquidRealtimeCapturePolicy.isFrameDue(frameTimeNanos, realtimeNextCaptureNanos)
        ) {
            requestRealtimeCapture(frameTimeNanos)
        }
        postRealtimeFrameCallback()
    }

    private fun requestRealtimeCapture(frameTimeNanos: Long) {
        val root = boundRoot ?: return
        if (closed || !activityVisible || realtimeCaptureSuspended || staticBackdropHost || windowObscured ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            realtimeCaptureInFlight != null || realtimeSamplingSuppressed
        ) {
            return
        }
        if (!root.isAttachedToWindow || !root.isShown ||
            root.windowVisibility != View.VISIBLE ||
            surfaceViews.isEmpty()
        ) {
            realtimeNextCaptureNanos = frameTimeNanos +
                LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            return
        }
        val captureSources = ensureRealtimeCaptureSources(root) ?: return
        val stable = backdropSource?.takeIf { !it.isClosed } ?: return
        val sourceIndex = realtimeCaptureNextIndex.mod(captureSources.size)
        val captureSource = captureSources[sourceIndex]
        realtimeCaptureNextIndex = (sourceIndex + 1).mod(captureSources.size)

        root.getLocationInWindow(realtimeRootLocation)
        realtimeCaptureSourceRect.set(
            realtimeRootLocation[0],
            realtimeRootLocation[1],
            realtimeRootLocation[0] + root.width,
            realtimeRootLocation[1] + root.height
        )
        // 必须在发起截图前构建：此刻 footprint 里保存的是最近一次绘制的位置，正是 PixelCopy
        // 即将读到的那一帧的几何。放到回调里构建会与截图内容错位。
        realtimeMaskReady = feedback.buildSuppressionMask(
            root, captureSource, rootScreenLocation[0], rootScreenLocation[1], surfaceViews
        )
        val ticket = captureRequests.begin() ?: return
        // 基准在发起时冻结：单飞期间不会有提交改绑，后台比较的正是此刻绑定给后端的那张。
        val baseline = realtimeBackdropSource?.takeIf {
            it !== captureSource && !it.isClosed && backends.lastBoundBackdrop === it
        }
        val request = LiquidCaptureRequest(ticket, captureSource, stable, WeakReference(root), root.width, root.height,
            feedback.mask, realtimeMaskReady, baseline)
        realtimeCaptureInFlight = request
        realtimeNextCaptureNanos = frameTimeNanos + refreshRate.frameIntervalNanos
        val recipient = WeakReference(this)
        val suppressor = feedback
        val callbackHandler = captureWorker() ?: mainHandler
        // 回调在截图线程上：遮罩与逐像素比较不再占 UI 线程，主线程只做提交。
        val pixelCopyFinishedListener = PixelCopy.OnPixelCopyFinishedListener { result ->
            postProcessRealtimeCapture(suppressor, request, result)
            mainHandler.post { recipient.get()?.handleRealtimeCaptureResult(request, result) }
        }
        val requested = runCatching {
            PixelCopy.request(
                activity.window,
                realtimeCaptureSourceRect,
                captureSource.bitmap,
                pixelCopyFinishedListener,
                callbackHandler
            )
        }.isSuccess
        if (!requested) handleRealtimeCaptureResult(request, PixelCopy.ERROR_SOURCE_INVALID)
    }

    private fun handleRealtimeCaptureResult(request: LiquidCaptureRequest, result: Int) {
        if (realtimeCaptureInFlight !== request) return
        realtimeCaptureInFlight = null
        val completion = captureRequests.complete(request.ticket)
        val root = request.root.get()
        if (completion != LiquidCaptureRequestState.Completion.CURRENT || closed || !activityVisible ||
            realtimeCaptureSuspended || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            root == null || boundRoot !== root || root.width != request.width || root.height != request.height ||
            request.source.isClosed || request.stableBackdrop !== backdropSource) return
        val captureSource = request.source
        val workStartedNanos = System.nanoTime()
        try {
            if (result == PixelCopy.SUCCESS) {
                // 抑制与比较已在截图线程完成（postProcessRealtimeCapture）；这里只采信结论。
                val outcome = request.outcome
                if (outcome != LiquidCaptureOutcome.FAILED) {
                    // NO_GLASS_VISIBLE 说明本帧压根没画玻璃，截图里也就不含自身反馈，可直接
                    // 采用；把它计入熔断计数会让长列表滚动 33ms 就永久关掉整个实时效果。
                    realtimeCaptureFailureCount = 0
                    val bound = realtimeBackdropSource
                    val unchanged = LiquidRealtimeCapturePolicy.isUnchanged(
                        comparedAgainstBoundSource = bound != null && bound === request.baseline &&
                            bound !== captureSource && !bound.isClosed && backends.lastBoundBackdrop === bound,
                        samplingSuppressed = realtimeSamplingSuppressed
                    ) { request.sameAsBaseline }
                    if (unchanged) {
                        // 本张只是一次探测，不绑定、不重画。轮转退回这块缓冲：下一次探测继续写它
                        // ——它此刻没有被绑定，也就不会被任何显示列表引用，三缓冲的不变式不破。
                        realtimeCaptureNextIndex = realtimeCaptureSources.indexOf(captureSource)
                            .coerceAtLeast(0)
                        refreshRate.resetThroughput()
                        onIdenticalCapture()
                        return
                    }
                    identicalCaptureStreak = 0
                    // 位图刚被改写，立刻提示 HWUI 预上传纹理；否则上传会推迟到下一帧 draw 中间，
                    // 变成 RenderThread 上的一次同步停顿。每帧一张约 3.81 MiB 的实时缓冲。
                    runCatching { captureSource.bitmap.prepareToDraw() }
                    applyCaptureThroughputSample(request.copyCompletedNanos)
                    realtimeBackdropSource = captureSource
                    // 截图发起后开始的滚动会把这帧变成过期采样：保留缓冲但暂不绑定，
                    // 等位移静默后的下一帧采集再切回实时。
                    if (!realtimeSamplingSuppressed) {
                        bindPreparedBackendsToBackdrop(captureSource)
                    }
                    invalidateRealtimeCaptureConsumers()
                    return
                }
            }

            // 失败会拉长下一次完成间隔，不能算进稳态吞吐。
            refreshRate.resetThroughput()
            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) realtimeCaptureFailureCount += 1
            if (LiquidRealtimeCapturePolicy.shouldSuspend(realtimeCaptureFailureCount)) {
                suspendRealtimeCapture(releaseBuffers = true)
            } else {
                realtimeNextCaptureNanos = System.nanoTime() +
                    LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            }
        } finally {
            performanceController?.reportActualWorkDuration(
                System.nanoTime() - workStartedNanos
            )
        }
    }

    /** 按需启动截图线程；启动失败返回 null，由调用方退回主线程回调（与改造前相同的路径）。 */
    private fun captureWorker(): Handler? {
        captureHandler?.let { return it }
        if (closed) return null
        val thread = runCatching {
            HandlerThread("BIL-LiquidCapture").apply { start() }
        }.getOrNull() ?: return null
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler
        return handler
    }

    /** 抑制器的截图侧状态只在截图线程上改；线程尚未启动（或已退出）时没有并发方，直接执行。 */
    private fun onCaptureWorker(task: () -> Unit) {
        val handler = captureHandler
        if (handler == null || !handler.post(task)) task()
    }

    /** 内存压力/缓冲重建时释放预缩放抑制底图；只断引用，在飞的后处理自己持有到结束。 */
    private fun releaseSuppressionUnderlay() {
        onCaptureWorker(feedback::releaseSuppressionUnderlay)
    }

    /** 吞吐降档见 [LiquidRefreshRateController.onCaptureCompleted]。 */
    private fun applyCaptureThroughputSample(completionNanos: Long) {
        refreshRate.onCaptureCompleted(completionNanos, boundRoot)
    }

    private fun ensureRealtimeCaptureSources(root: View): List<LiquidBackdropSource>? {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.RETRY_DELAY_MS)
            return null
        }
        val target = LiquidRealtimeCapturePolicy.resolveSize(
            fullWidth = width,
            fullHeight = height,
            pixelBudget = realtimeSamplePixelBudget
        )
        val existing = realtimeCaptureSources
        if (existing.size == LiquidRealtimeCapturePolicy.BUFFER_COUNT &&
            existing.all {
                !it.isClosed && it.fullWidth == width && it.fullHeight == height &&
                    it.bitmap.width == target.width && it.bitmap.height == target.height
            }
        ) {
            return existing
        }

        releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        val created = ArrayList<LiquidBackdropSource>(LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val result = runCatching {
            repeat(LiquidRealtimeCapturePolicy.BUFFER_COUNT) {
                val bitmap = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(palette.background)
                created += LiquidBackdropSource.fromRealtimeBitmap(bitmap, width, height)
            }
            created.toList()
        }.getOrElse {
            created.forEach { source ->
                source.close()
                if (!source.bitmap.isRecycled) source.bitmap.recycle()
            }
            realtimeCaptureSuspended = true
            return null
        }
        realtimeCaptureSources = result
        realtimeCaptureNextIndex = 0
        return result
    }

    private fun suspendRealtimeCapture(releaseBuffers: Boolean) {
        realtimeCaptureSuspended = true
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        resetRealtimeIdle()
        performanceController?.stop()
        refreshRate.restore()
        if (releaseBuffers) releaseRealtimeCaptureSources(rebindStableBackdrop = true)
    }

    private fun releaseRealtimeCaptureSources(rebindStableBackdrop: Boolean) {
        captureRequests.invalidate()
        clearScrollSuppression()
        val stableBackdrop = backdropSource
        realtimeBackdropSource = null
        backends.forgetBindings()
        // 缓冲尺寸变化会改变单次回读成本，旧吞吐样本不再代表当前配置。
        refreshRate.resetThroughput()
        releaseSuppressionUnderlay()
        if (rebindStableBackdrop && stableBackdrop != null && !stableBackdrop.isClosed &&
            backends.current?.backend != LiquidRenderBackend.TRANSLUCENT
        ) {
            bindPreparedBackendsToBackdrop(stableBackdrop)
        }
        // 抑制期录制的表面走的是光学直采路径；缓冲释放/挂起后必须重录回折射路径，
        // 否则它们会一直重放旧 display list（玻璃停在磨砂观感直到下次自然失效）。
        invalidateRegisteredSurfaces()
        realtimeCaptureSources.forEach(LiquidBackdropSource::close)
        realtimeCaptureSources = emptyList()
        realtimeCaptureNextIndex = 0
    }

    /** 新底图只绑给当前后端；降级链耗尽即致命。见 [LiquidBackendSet.bindBackdrop]。 */
    private fun bindPreparedBackendsToBackdrop(source: LiquidBackdropSource) {
        if (!backends.bindBackdrop(source)) dispatchFatalFailure()
    }

    /** 运行期绘制失败：降级并补绑当前采样源，切换成功或退回廉价路径时整组表面重录。 */
    private fun advanceAfterFailure(failed: LiquidRenderBackend): Boolean {
        return when (backends.advanceAfterFailure(failed, realtimeBackdropSource ?: backdropSource)) {
            LiquidBackendAdvance.EXHAUSTED -> false
            LiquidBackendAdvance.ACTIVATED -> {
                invalidateRegisteredSurfaces()
                true
            }
            LiquidBackendAdvance.NONE_READY -> {
                invalidateRegisteredSurfaces()
                false
            }
        }
    }

    private fun dispatchFatalFailure() {
        if (closed || fatalPosted) return
        fatalPosted = true
        val root = boundRoot
        val callback = onFatalFailure
        if (root != null) root.post { if (!closed) callback?.invoke() }
        else callback?.invoke()
    }

    /** Drawable.draw 已真实成功返回后才排队确认，避免 OnDrawListener 的绘制前时序。 */
    private fun scheduleHealthConfirmationAfterDraw() {
        val root = boundRoot ?: return
        if (closed || fatalPosted || healthPosted || !successfulDraw || !root.isShown) return
        healthPosted = true
        root.post {
            if (!closed && !fatalPosted) onFirstVisibleDraw?.invoke()
        }
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        activityVisible = false
        realtimeCaptureSuspended = true
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        performanceController?.close()
        captureRequests.invalidate()
        customBackdropLoadGeneration += 1L
        customBackdropFuture?.cancel(true)
        customBackdropFuture = null
        customBackdropRequest = null
        backgroundWorker.shutdownNow()
        val root = boundRoot
        rootLayoutListener?.let { listener -> root?.removeOnLayoutChangeListener(listener) }
        rootLayoutListener = null
        rootScrollListener?.let { listener ->
            root?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnScrollChangedListener(listener)
        }
        rootScrollListener = null
        // 在 onDraw 分发中移除会抛 IllegalStateException；关闭路径不允许因此中断。
        runCatching {
            root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(realtimeDrawListener)
        }
        runCatching {
            root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(windowFocusListener)
        }
        resetRealtimeIdle()
        surfaceViews.clear()
        chromeBackdrops.values.forEach(::closeChromeBackdrop)
        chromeBackdrops.clear()
        refreshWindows.values.forEach(::removeRefreshWindow)
        refreshWindows.clear()
        onFirstVisibleDraw = null
        onFatalFailure = null
        backends.close()
        releaseRealtimeCaptureSources(rebindStableBackdrop = false)
        // 截图线程上可能还有一次在飞的后处理：抑制器的收尾排在它后面，随后线程退出；
        // 之后才到的 PixelCopy 回调投递失败即丢弃，不会再触碰已释放的状态。
        onCaptureWorker(feedback::close)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        backdropSource?.close()
        backdropSource = null
        retiredBackdropSources.forEach(LiquidBackdropSource::close)
        retiredBackdropSources.clear()
        refreshRate.restore()
        boundRoot = null
        rootDrawable = null
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** 模态边框高光的竖向渐隐行程（dp）：顶部提亮只在面板最上方一段可见。 */
private const val MODAL_EDGE_FADE_DP = 64f

/**
 * 顶沿提亮相对基础描边亮度的倍数；底端落回原亮度。2026-09-24 由 2.2 降到 1.8（与 BASE_RATIO
 * 相乘约 0.8）：用户要求悬浮栏与面板边缘高光再薄一点。
 */
private const val MODAL_EDGE_TOP_BOOST = 1.8f
private const val MODAL_EDGE_BASE_RATIO = 0.45f

/**
 * 廉价路径光晕带与提亮：折射 rim 实测只有 Fresnel≈0.025 / specular≈0.06 的
 * 白度提升，光晕带按同一量级取极淡单层（10dp × 0.35×edgeAlpha）；普通角色
 * 的顶沿提亮也收敛到 1.6×——过强会读成描边环而不是光。
 */
private const val OPTICAL_EDGE_TOP_BOOST = 1.6f
private const val OPTICAL_EDGE_BAND_DP = 10f
private const val OPTICAL_EDGE_BAND_ALPHA = 0.35f

/**
 * 手指按着时最多把"抑制解除"这次重同步推迟多久。
 *
 * 上界存在的理由：长按不动本来就该恢复实时档，不能因为手指一直贴着就无限停在磨砂观感。
 * 取 600ms —— 比一次正常的甩动手势长、比"按住发呆"短。
 */
private const val GESTURE_RELEASE_HOLD_MS = 600L

/**
 * 浮动条常驻的折射强度下限（驱动会钳到 1..1.85）：stretchDirY==0 时 shader 把
 * 增益按全向处理，整圈边缘的焦散/菲涅尔/镜面随之下调增量点亮，静止也有凝光；
 * 回弹方向出现后同一增益收拢到对应边缘。
 */
private const val FLOATING_OPTICAL_FLOOR = 1.15f
/** 清透档节点玻璃（3dp 模糊）残留的细节比例，供可读性策略估计漏字。 */
private const val CHROME_DETAIL_SEE_THROUGH = LiquidChromeGlassPolicy.DETAIL_SEE_THROUGH
private const val TAG = "BIL-LiquidRenderer"
