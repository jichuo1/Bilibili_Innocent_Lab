package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.os.Build
import androidx.annotation.MainThread
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend

/** [LiquidBackendSet.advanceAfterFailure] 的结局；调用方据此决定是否重录表面、是否致命。 */
internal enum class LiquidBackendAdvance {
    /** 降级链耗尽，或新后端连底图都绑不上：调用方直接判致命，不必重录。 */
    EXHAUSTED,

    /** 已切到下一个后端并补绑好底图：重录表面后继续画。 */
    ACTIVATED,

    /** 降级链还有候选但都没准备好：重录表面（退回廉价路径），本次判失败。 */
    NONE_READY
}

/**
 * 高级材质的渲染后端集合：候选准备、按降级链选择、绑定底图、运行期失败降级，以及外部窗口
 * （Dialog）专用的第二折射驱动。
 *
 * 所有图形资源（RuntimeShader、RenderEffect）只在构造期创建；[selectCurrentPrepared] 允许从
 * draw 调用，但绝不创建资源。
 *
 * 2026-09-23 凝光视效引擎重构时从 [LiquidActivityRenderer] 拆出，逻辑逐行不变。
 */
@MainThread
internal class LiquidBackendSet(
    private val parameters: LiquidParameters,
    private val density: Float,
    sdkInt: Int,
    hardwareAccelerated: Boolean
) : AutoCloseable {
    private val candidates = LiquidCapabilityPolicy.candidateOrder(
        sdkInt = sdkInt,
        hardwareAccelerated = hardwareAccelerated
    )
    private val fallbackPlan = LiquidBackendFallbackPlan(candidates)
    private val preparedDrivers = linkedMapOf<LiquidRenderBackend, LiquidBackendDriver>()
    private val failures = linkedMapOf<LiquidRenderBackend, String>()
    private var closed = false

    /** 当前选中的驱动；未选中或已全部失败时为 null。 */
    var current: LiquidBackendDriver? = null
        private set

    /**
     * 当前 backdrop 只绑定到**正在使用**的后端。
     *
     * 旧实现每帧遍历 `preparedDrivers` 全量绑定：API 33 上 BLUR 后端永远不会被绘制，却仍在
     * 每帧 `discardDisplayList()` + `beginRecording()` 重录一个引用整张截图的 display list。
     * 现在改为记录"已绑定的 source"，切换后端时再补绑。
     */
    private val boundSources = HashMap<LiquidRenderBackend, LiquidBackdropSource>()

    /** 最近一次真正绑定给后端的底图；实时采集的静止判定只和它比。 */
    var lastBoundBackdrop: LiquidBackdropSource? = null
        private set

    /**
     * 外部窗口（Dialog）表面专用的第二个折射驱动，**只绑稳定光学底图**。
     *
     * 主驱动在实时档绑的是 Activity 窗口截屏，而 Dialog 是独立窗口——截屏里对应位置是
     * 未压暗的锐利底页，折射它会把底页文字透进面板（2026-09-21 的透字故障就是这么来的）。
     * 因此外部窗口此前只能走 `drawOpticalRegion` 平铺直采，整条折射 rim（菲涅尔/镜面/
     * 焦散/内阴影）缺席，面板边缘只有一条渐变描边。
     *
     * 绑稳定底图则两者兼得：没有锐利内容可泄漏，同时拿到与窗内玻璃同一条边缘光。
     * 一个 Activity 至多多一份 RuntimeShader 程序，只在首次需要时编译。
     */
    private var foreignDriver: LiquidBackendDriver? = null
    private var foreignDriverSource: LiquidBackdropSource? = null
    private var foreignDriverUnavailable = false

    init {
        candidates.forEach { candidate ->
            runCatching { createBackend(candidate) }
                .onSuccess { preparedDrivers[candidate] = it }
                .onFailure { throwable -> recordFailure(candidate, throwable) }
        }
        selectCurrentPrepared()
    }

    val backend: LiquidRenderBackend?
        get() = current?.backend ?: fallbackPlan.current

    /**
     * 当前后端之前那些更优先候选的失败原因；没有降级时为 null。
     *
     * 供设置页在后端名称旁展示，用户可以直接把它反馈回来，而不是只说"不好看"。
     */
    val degradeReason: String?
        get() {
            val active = current?.backend ?: fallbackPlan.current ?: return null
            if (failures.isEmpty()) return null
            return candidates
                .takeWhile { it != active }
                .firstNotNullOfOrNull { candidate ->
                    failures[candidate]?.let { "${candidate.name}: $it" }
                }
        }

    /**
     * 记录某个后端为什么用不了。
     *
     * `RuntimeShader` 在构造期由厂商驱动编译 AGSL，失败会直接抛异常。原实现把它整个吞掉，
     * 于是 Adreno 能跑、Mali 被拒这类跨驱动问题在用户侧只表现为"效果变朴素了"，没有任何可上报的
     * 线索。这里只保留异常类型与截断后的 message，不含任何用户数据。
     */
    private fun recordFailure(backend: LiquidRenderBackend, throwable: Throwable) {
        val message = throwable.message
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_BACKEND_FAILURE_MESSAGE)
        failures[backend] = if (message == null) {
            throwable.javaClass.simpleName
        } else {
            "${throwable.javaClass.simpleName}: $message"
        }
    }

    /** 只选择 bind 阶段已准备的实例；该方法允许从 draw 调用，但绝不创建图形资源。 */
    fun selectCurrentPrepared(): Boolean {
        while (!closed) {
            val candidate = fallbackPlan.current ?: return false
            val prepared = preparedDrivers[candidate]
            if (prepared == null) {
                fallbackPlan.advanceAfterFailure(candidate)
                continue
            }
            current = prepared
            return true
        }
        return false
    }

    /**
     * 只把新 backdrop 绑定到当前后端；其余后备驱动在真正被选中时再补绑。
     *
     * 逐帧全量绑定是纯浪费：`LiquidBlurBackendApi31.bindBackdrop` 每次都会丢弃并重录一个引用
     * 整张实时截图的 RenderNode display list，而 API 33 设备上它永远不会被绘制。
     *
     * @return false 表示降级链已耗尽，调用方应判致命。
     */
    fun bindBackdrop(source: LiquidBackdropSource): Boolean {
        boundSources.clear()
        lastBoundBackdrop = source
        return ensureCurrentBound(source)
    }

    /** 实时缓冲释放/挂起：之前的绑定全部作废，下一次 [bindBackdrop] 重新绑。 */
    fun forgetBindings() {
        lastBoundBackdrop = null
        boundSources.clear()
    }

    /** 绑定失败按后端失败处理并降级；成功后记录已绑定的 source，避免重复绑定。 */
    private fun ensureCurrentBound(source: LiquidBackdropSource): Boolean {
        while (!closed) {
            val driver = current ?: if (selectCurrentPrepared()) current else null
            if (driver == null) return false
            if (!driver.requiresBackdrop) return true
            if (boundSources[driver.backend] === source) return true
            if (runCatching { driver.bindBackdrop(source) }.isSuccess) {
                boundSources[driver.backend] = source
                return true
            }
            boundSources.remove(driver.backend)
            preparedDrivers.remove(driver.backend)?.close()
            current = null
            if (fallbackPlan.advanceAfterFailure(driver.backend) == null) return false
        }
        return false
    }

    /** 运行期绘制失败：记下原因、淘汰该后端、切到下一个并补绑 [rebindSource]。 */
    fun advanceAfterFailure(
        failed: LiquidRenderBackend,
        rebindSource: LiquidBackdropSource?
    ): LiquidBackendAdvance {
        failures.getOrPut(failed) { "runtime-draw-failed" }
        // 主折射后端都退场了，面板那份第二实例没有存在意义，跟着释放。
        if (failed == LiquidRenderBackend.REFRACTION) releaseForeignDriver()
        boundSources.remove(failed)
        preparedDrivers.remove(failed)?.close()
        if (current?.backend == failed) current = null
        fallbackPlan.advanceAfterFailure(failed) ?: return LiquidBackendAdvance.EXHAUSTED
        val activated = selectCurrentPrepared()
        if (activated && rebindSource != null && !rebindSource.isClosed) {
            if (!ensureCurrentBound(rebindSource)) return LiquidBackendAdvance.EXHAUSTED
            lastBoundBackdrop = rebindSource
        }
        return if (activated) LiquidBackendAdvance.ACTIVATED else LiquidBackendAdvance.NONE_READY
    }

    /** 内存压力/底图缺失：一路降到零额外资源的 TRANSLUCENT。 */
    fun advanceToTranslucent() {
        while (fallbackPlan.current != null &&
            fallbackPlan.current != LiquidRenderBackend.TRANSLUCENT
        ) {
            val failed = requireNotNull(fallbackPlan.current)
            preparedDrivers.remove(failed)?.close()
            if (current?.backend == failed) current = null
            fallbackPlan.advanceAfterFailure(failed)
        }
        if (current?.backend != LiquidRenderBackend.TRANSLUCENT) {
            current = null
            selectCurrentPrepared()
        }
    }

    /**
     * 外部窗口面板用的折射驱动：只在当前后端本来就是 REFRACTION 时建立，且**恒绑稳定底图**。
     *
     * 失败一次就永久记不可用，不在绘制路径上反复重试编译 shader；稳定底图被替换（换自定义图/
     * 尺寸变化）时按 source 身份重绑。
     */
    fun foreignRefractionDriver(stableBackdrop: LiquidBackdropSource?): LiquidBackendDriver? {
        if (closed || foreignDriverUnavailable) return null
        if (current?.backend != LiquidRenderBackend.REFRACTION) return null
        if (Build.VERSION.SDK_INT < 33) return null
        val stable = stableBackdrop?.takeIf { !it.isClosed } ?: return null
        val existing = foreignDriver
        if (existing != null && foreignDriverSource === stable) return existing
        val driver = existing ?: runCatching { LiquidRefractionBackendApi33(parameters, density) }
            .getOrElse {
                foreignDriverUnavailable = true
                return null
            }
        if (!runCatching { driver.bindBackdrop(stable) }.isSuccess) {
            driver.close()
            foreignDriver = null
            foreignDriverSource = null
            foreignDriverUnavailable = true
            return null
        }
        foreignDriver = driver
        foreignDriverSource = stable
        return driver
    }

    /** 面板驱动是附加通道：它坏了只准它自己退场，永久不再重试。 */
    fun markForeignDriverBroken() {
        releaseForeignDriver()
        foreignDriverUnavailable = true
    }

    private fun releaseForeignDriver() {
        foreignDriver?.close()
        foreignDriver = null
        foreignDriverSource = null
    }

    /** 直接 SDK guard 让 Android Lint 能静态证明下面两个 @RequiresApi 构造调用。 */
    private fun createBackend(backend: LiquidRenderBackend): LiquidBackendDriver = when (backend) {
        LiquidRenderBackend.REFRACTION -> if (Build.VERSION.SDK_INT >= 33) {
            LiquidRefractionBackendApi33(parameters, density)
        } else error("RuntimeShader requires API 33")
        LiquidRenderBackend.BLUR -> if (Build.VERSION.SDK_INT >= 31) {
            LiquidBlurBackendApi31(parameters.blurRadiusDp * density)
        } else error("RenderEffect requires API 31")
        LiquidRenderBackend.TRANSLUCENT -> LiquidTranslucentBackend()
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseForeignDriver()
        preparedDrivers.values.forEach(LiquidBackendDriver::close)
        preparedDrivers.clear()
        current = null
    }

    private companion object {
        /** 降级原因只保留有界长度，避免把驱动的长堆栈文本带进界面。 */
        const val MAX_BACKEND_FAILURE_MESSAGE = 160
    }
}
