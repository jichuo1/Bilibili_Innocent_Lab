package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy as Policy
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class LiquidFeedbackIsolationTest {
    fun ownedOutputHasZeroRecursiveContributionAndUnmaskedPixelsStayLive() {
        val alpha = Policy.BASE_SUPPRESSION_ALPHA
        // The cached stable backdrop is opaque. Test every possible captured channel and several backgrounds.
        for (clean in listOf(0, 19, 128, 240, 255)) for (captured in 0..255) {
            val sanitized = (clean * alpha + captured * (255 - alpha)) / 255
            assertEquals("Own labels must not survive input sanitation", clean, sanitized)
            val outsideMask = (clean * 0 + captured * (255 - 0)) / 255
            assertEquals("No mask must preserve the actual live capture", captured, outsideMask)
        }
        assertEquals(3, Policy.BUFFER_COUNT)
        assertEquals(1_000_000L, Policy.TARGET_SAMPLE_PIXELS)
        assertEquals(120f, Policy.MAX_TARGET_FRAMES_PER_SECOND, 0f)
    }

    fun sourceWiringFreezesMasksBeforeCaptureAndSanitationReplacesOwnedOutput() {
        val renderer = source("LiquidActivityRenderer")
        val request = renderer.after("private fun requestRealtimeCapture(").before("private fun handleRealtimeCaptureResult")
        assertTrue(request.indexOf("buildSuppressionMask") < request.indexOf("PixelCopy.request"))
        val result = renderer.after("private fun handleRealtimeCaptureResult").before("private fun applyCaptureThroughputSample")
        // 2026-09-23 抑制在截图线程完成；提交端必须先看它的结论再绑定。
        assertTrue(result.indexOf("request.outcome") in 0 until result.indexOf("bindPreparedBackendsToBackdrop"))
        assertTrue(renderer.after("private fun postProcessRealtimeCapture(").contains("sanitizeRealtimeCapture"))
        // 2026-09-23 遮罩构建随抑制器拆到 LiquidFeedbackSuppressor（凝光视效引擎重构）。
        val mask = source("LiquidFeedbackSuppressor").after("fun buildSuppressionMask(")
            .before("fun sanitizeRealtimeCapture")
        assertTrue(mask.contains("mask.addRoundRect"))
        // 未绘制边界环的 viewport 不进入抑制遮罩：遮罩只覆盖真实玻璃表面。
        assertFalse(mask.contains("stretchViewports"))
    }

    /**
     * 超出回弹不再绘制边界采样环（2026-09-20 用户要求去掉红圈中的那一圈模糊描边）。
     *
     * 这条钉住"移除"本身：环的绘制、记账与 retired 机制都不许静默复活；而系统 stretch 与
     * 回弹光学强度提升是不同机制，必须保留。
     */
    fun overscrollKeepsTheSystemStretchButPaintsNoBoundaryRing() {
        val renderer = source("LiquidActivityRenderer")
        listOf("drawStretchBoundary", "stretchBoundaryFootprints", "stretchViewports",
            "LiquidStretchBoundaryFootprint", "LiquidBoundaryCaptureState", "LiquidBoundaryMaskGeometry",
            "STRETCH_EDGE").forEach {
            assertFalse("renderer must not resurrect the boundary ring: $it", renderer.contains(it))
        }
        assertTrue(renderer.contains("onStretchDistanceChanged"))
        val viewport = source("LiquidStretchViewport")
        assertFalse("viewport must not draw any boundary ring", viewport.contains("drawBoundary"))
        assertTrue(viewport.contains("onStretchDistance("))
        assertTrue(viewport.contains("LiquidStretchOverscrollPolicy.dominantEdge"))
        assertTrue(viewport.contains("bottomEffect.draw(this)"))
        val policy = source("LiquidRealtimeCapturePolicy")
        listOf("stretchBoundaryVisibility", "stretchBoundaryBandPx", "stretchPaintedBandPx",
            "stretchFeedbackBandDp").forEach {
            assertFalse("policy must not resurrect the boundary ring: $it", policy.contains(it))
        }
        assertTrue(policy.contains("fun stretchOpticalIntensity"))
    }

    /**
     * 回弹光学增益必须沿方向投射到表面边缘（2026-09-21 用户实证：四边等亮的高光描边
     * 违反方向直觉）。方向从 viewport 的主导边出发，经 renderer 的 stretchDirY 进
     * shader，再按边缘外法线点积无极分配——对侧边缘保持基准强度。
     */
    fun stretchOpticsFollowTheActiveEdgeDirection() {
        val renderer = source("LiquidActivityRenderer")
        val handler = renderer.after("private fun onStretchDistanceChanged(")
            .substringBefore("@MainThread", "MISSING")
        assertTrue(handler.contains("LiquidStretchEdge.TOP"))
        assertTrue(handler.contains("LiquidStretchEdge.BOTTOM"))
        assertTrue(renderer.contains("stretchEdgeDirY"))
        val driver = source("LiquidBackendDriver")
        assertTrue(driver.contains("stretchDirY"))
        val refraction = source("LiquidRefractionBackendApi33")
        assertTrue(refraction.contains("stretchDirY"))
        // shader 内必须有"法线投影 → 方向性增益"两步，缺一则退回四边等亮。
        assertTrue(refraction.contains("stretchFacing"))
        assertTrue(refraction.contains("edgeBoost"))
        assertTrue(refraction.contains("dot("))
    }

    /**
     * 滚动/位移活跃期玻璃必须改采稳定底图（2026-09-21 用户实证：快速滑动时滞后一帧的
     * 实时截屏把旧位置文字折射进表面，形成沿滑动方向偏移的残影）。链路要求：位移回调
     * 触发抑制 → 抑制期间不再发起新采集、迟到的回读不绑定 → 静默窗口后才放行。
     */
    fun scrollingSuppressesStaleRealtimeSampling() {
        val renderer = source("LiquidActivityRenderer")
        val moved = renderer.after("private fun invalidateMovedSurfaces()")
            .before("private fun invalidateRegisteredSurfaces()")
        assertTrue(moved.contains("lastContentShiftNanos"))
        assertTrue(moved.contains("suppressRealtimeSamplingWhileScrolling()"))

        // 显式变换回调（按下缩放/弹性拖拽）不等于内容位移：必须先经
        // flushSurfaceRefresh 的"表面原点真的变化"门控，否则点击/按压
        // 也会在无事发生时把底图 real→stable 闪一下（2026-09-21 真机实证）。
        val notify = renderer.after("fun notifyPositionChanged()")
            .substringBefore("private fun invalidateMovedSurfaces()", "MISSING")
        assertNotEquals("MISSING", notify)
        assertFalse("transform callbacks must not suppress unconditionally",
            notify.contains("suppressRealtimeSamplingWhileScrolling()"))
        val flush = renderer.after("private fun flushSurfaceRefresh(")
        assertTrue(flush.contains("surfaceMoved"))
        assertTrue(flush.contains("suppressRealtimeSamplingWhileScrolling()"))

        val suppress = renderer.after("private fun suppressRealtimeSamplingWhileScrolling()")
            .before("private fun onScrollSettleCheck()")
        assertTrue(suppress.contains("bindPreparedBackendsToBackdrop(stable)"))

        val request = renderer.after("private fun requestRealtimeCapture(")
            .before("private fun handleRealtimeCaptureResult")
        assertTrue(request.contains("realtimeSamplingSuppressed"))

        val result = renderer.after("private fun handleRealtimeCaptureResult")
            .before("private fun applyCaptureThroughputSample")
        assertTrue(result.contains("if (!realtimeSamplingSuppressed)"))

        assertTrue(renderer.contains("SCROLL_QUIET_MS"))
        assertTrue(Policy.SCROLL_QUIET_MS in 48L..240L)
    }

    /**
     * 位移抑制期的玻璃留在折射路径、只降级为 motionLite 单取样（2026-09-21 真机实证：
     * 直采路径缺折射 rim 与通透填充，切页/滚动瞬间"高光消失再加载"）。契约：
     * 外部窗口仍走 drawOpticalRegion；窗口内表面抑制期以 motionLite 跑同一 shader——
     * 驱动层已绑稳定底图，跳过散射多抽样但保留边缘光与 contentAlpha 通透。
     */
    fun suppressedSurfacesStayRefractiveInLiteMode() {
        val renderer = source("LiquidActivityRenderer")
        val draw = renderer.after("internal fun drawSurface(")
            .before("private fun drawSurfaceLayers(")
        val optical = draw.after("if (foreignWindow) {")
            .substringBefore("} else {", "MISSING")
        assertNotEquals("MISSING", optical)
        assertTrue(optical.contains("drawOpticalRegion("))
        assertTrue(draw.contains("motionLite = (realtimeSamplingSuppressed && !suppressionFromMorphOnly)"))
        // 回弹期一并降级：lite 是同一条 shader 少取样，边缘光逐项保留；只有**切换绘制
        // 路径**才会被看成跳变（2026-09-21（九）），这里没有切路径。
        assertTrue(draw.contains("stretchOpticalIntensity > 1f"))

        val backend = source("LiquidRefractionBackendApi33")
        assertTrue(backend.contains("uniform float motionLite"))
        // lite 分支必须走单次取样而非多抽样散射
        assertTrue(backend.contains("if (motionLite > 0.5)"))
        val lite = backend.after("if (motionLite > 0.5)")
        assertTrue(lite.contains("sampleContent("))
        // lite 必须保留与完整路径同一条内容感知焦散——缺了它，lite/full
        // 切换瞬间边缘高光亮度差一档，表现为滑动起止处的轻微闪动。
        assertTrue(lite.contains("liteCaustic"))
        assertTrue(lite.contains("causticLuminanceGain"))
    }

    /**
     * 深内部早退的三条前提缺一不可（2026-09-22）。
     *
     * `interiorDistortion` 驱动 interiorOffset；`scatteringStrength` 的散射项在
     * edgeWeight=0 时仍由 interiorLens 供权（**不是** 0）；`chromaticShift` 在限域之前
     * 无视 edgeWeight。三者同时为零，下面整段才逐项含 edgeWeight 因子而恒等于原始采样。
     * 还必须保留 `saturateColor`——chromaMultiplier 是 0.98，直接返回原样本会让内面
     * 少掉 2% 去饱和、与边缘带接不上。
     */
    fun theInteriorFastPathOnlyFiresWhereItIsProvablyEquivalent() {
        val backend = source("LiquidRefractionBackendApi33")
        val main = backend.substringAfter("half4 main(float2 coord)", "MISSING")
            .before("float smoothRadius")
        assertNotEquals("MISSING", main)
        val guard = main.substringAfter("if (interiorDistortion <= 0.001", "MISSING")
        assertNotEquals("MISSING", guard)
        assertTrue("判据要用 edgeWidthBoost 的上界（stretchFacing≤1），只准少退出",
            main.contains("edgeWidthBoostMax") && main.contains("bool deepInterior"))
        assertTrue("散射在 edgeWeight=0 时仍由 interiorLens 供权，必须一并要求为 0",
            guard.contains("scatteringStrength <= 0.001"))
        assertTrue("色散在限域前无视 edgeWeight，必须一并要求为 0",
            guard.contains("chromaticShift <= 0.001"))
        assertTrue("早退必须保留 saturateColor，否则内面少掉 chromaMultiplier 那一档",
            guard.contains("return saturateColor(sampleContent(coord), chromaMultiplier)"))
    }

    /**
     * 运动降级档的深内部快路径（2026-09-22 性能整改）。
     *
     * lite 本来就只取一次样，而 edgeWeight==0 让折射位移/焦散/内阴影/菲涅尔/镜面逐项归零，
     * 结果恒等于"按 interiorOffset 取一次样再调饱和"。省掉 SDF 梯度与三段边缘光的 ALU——
     * 卡片内面占玻璃像素的绝大多数，而运动期正是 GPU 最紧的时候（真机：高级材质手风琴
     * GPU 50th 7ms / 90th 9ms，Slow issue draw commands 占掉帧 46/47）。
     */
    fun theMotionLiteInteriorSkipsTheEdgeMath() {
        val backend = source("LiquidRefractionBackendApi33")
        val fast = backend.substringAfter("if (motionLite > 0.5 && deepInterior)", "MISSING")
            .before("float smoothRadius")
        assertNotEquals("MISSING", fast)
        assertTrue("必须仍按 interiorOffset 取样（realtime 档 interiorDistortion≠0）",
            fast.contains("interiorOffset * liteReach"))
        assertTrue("必须保留饱和调整", fast.contains("saturateColor("))
        assertTrue("越界收敛不能丢：贴页面边缘时位移仍要收敛，否则平铺模式会横向抹开",
            fast.contains("backdropExtent"))
    }

    /**
     * 色散必须限域在 rim 带内（2026-09-22）。旧实现全域等量位移，内部高对比文字
     * 也会裂成红蓝边——那正是它当初被整条关闭的原因。限域后才允许重新开启。
     */
    fun chromaticDispersionIsConfinedToTheRimBand() {
        val backend = source("LiquidRefractionBackendApi33")
        val fn = backend.substringAfter("half4 sampleRefracted(", "MISSING")
            .before("half4 sampleScattered(")
        assertNotEquals("MISSING", fn)
        assertTrue("位移必须乘 edgeWeight", fn.contains("chromaticShift * edgeBoost * edgeWeight"))
        assertTrue("亚像素位移直接跳过两次取样", fn.contains("if (shift < 0.02) return center;"))
        // 抖动与双叶镜面都必须被 uniform / edgeWeight 门掉，标准档零额外开销。
        assertTrue(backend.contains("uniform float dither"))
        assertTrue(backend.contains("if (dither > 0.0)"))
        assertTrue(backend.contains("facingBack"))
    }

    /**
     * 回弹期间不切换采样路径（2026-09-21 真机实证：按住回弹不动时静默窗口会解除
     * 抑制、表面重录回折射路径，下一次位移又切回光学直采——整圈边缘光在两条路径
     * 之间乒乓闪烁；同时静止时 stretchDirY==0 令 edgeBoost 钉死在 1，浮动条的常驻
     * 折射下限完全不生效）。契约：回弹回调不触发抑制；静默检查在形变未归零前不解除；
     * shader 按 |stretchDirY| 在全向与定向投影间连续混合。
     */
    fun stretchKeepsTheRefractivePathAndTheRestingGlow() {
        val renderer = source("LiquidActivityRenderer")
        val handler = renderer.after("private fun onStretchDistanceChanged(")
            .substringBefore("@MainThread", "MISSING")
        assertNotEquals("MISSING", handler)
        assertTrue(handler.contains("stretchWindows.updateForScrollHost(scrollHost, distance, next, nextDir)"))
        assertTrue(handler.contains("if (state.distance > 0f) suppressRealtimeSamplingWhileScrolling()"))
        assertFalse(handler.contains("drawOpticalRegion("))

        val settle = renderer.after("private fun onScrollSettleCheck()")
            .substringBefore("private fun clearScrollSuppression()", "MISSING")
        assertNotEquals("MISSING", settle)
        assertTrue("settle must not lift suppression while stretched",
            settle.contains("stretchOpticalIntensity > 1f"))

        val refraction = source("LiquidRefractionBackendApi33")
        assertTrue("resting state must keep the edge gain omnidirectional",
            refraction.contains("mix(1.0, dirFacing, abs(stretchDirY))"))
    }

    /**
     * 位移抑制/外部窗口的光学直采路径必须给全部角色发光渐变描边（2026-09-21 真机实证：
     * 切页时折射 shader 的菲涅尔/镜面/焦散边缘光晕整条缺席，只剩细描边——所有控件
     * "边缘高光先消失再加载"）。廉价路径用顶沿提亮渐变保住"边缘有光"的读感。
     */
    fun cheapOpticalPathKeepsALuminousEdgeOnEveryRole() {
        val renderer = source("LiquidActivityRenderer")
        val layers = renderer.after("private fun drawSurfaceLayers(")
            .substringBefore("private inline fun drawWithFallback", "MISSING")
        assertNotEquals("MISSING", layers)
        assertTrue(layers.contains("luminousEdge"))
        assertTrue(layers.contains("modalEdgePaint"))
        assertTrue(layers.contains("edgeBandPaint"))
        val draw = renderer.after("internal fun drawSurface(")
            .before("private fun drawSurfaceLayers(")
        // 窗口内表面抑制期留在折射 lite 路径——发光边缘只需补外部窗口的直采表面。
        assertTrue(draw.contains("luminousEdge = foreignWindow"))
        // 直采路径的填充透明度必须与折射路径同源：浮动条透出真实下层内容。
        assertTrue(draw.contains("LiquidSurfaceAlphaPolicy.glassContentAlpha(role)"))
    }

    /**
     * EdgeEffect.draw 只能落在硬件画布上（2026-09-21 真机实证）：Material 皮肤的
     * LiveBackdropSampler 每帧把内容根重绘进软件 Canvas 做透镜采样，平台 stretch
     * EdgeEffect 在非 RecordingCanvas 上 draw() 会直接 mDistance=0 并置 STATE_IDLE，
     * 任何正在累积的回弹形变都被取样帧抹掉——表现为完全没有回弹动画。
     */
    fun stretchEffectsOnlyDrawOnHardwareCanvases() {
        val viewport = source("LiquidStretchViewport")
        val draw = viewport.after("override fun draw(canvas: Canvas)")
            .substringBefore("override fun onStartNestedScroll", "MISSING")
        assertNotEquals("MISSING", draw)
        assertTrue(draw.contains("canvas.isHardwareAccelerated"))
        val guarded = draw.after("isHardwareAccelerated")
        assertTrue(guarded.indexOf("topEffect.draw(canvas)") < guarded.indexOf("topEffect.draw(canvas)") + 400)
    }

    /**
     * 采样原点只取 View 屏幕原点（2026-09-22 真机实证）。
     *
     * 承载层的 provider 返回的 motionBounds 是**层画布内的绝对矩形**（如 left=127,
     * top=654），不是 0 基局部矩形。两条采样链都把 `viewX/viewY` 当画布原点解算
     * 根坐标：`drawOpticalRegion` 的 `uv=(p+off)·(bitmap/full)`（local matrix 逆变换）
     * 与折射 shader 的 `rootCoord=canvasCoord+backdropOrigin`。若把 motionBounds 的
     * left/top 再叠进 drawX/drawY，采样窗会二次偏移到卡片右下方——形变全程显示
     * 的是偏离真实位置的底图区域，落定换回卡片 0 基 drawable 时采样区瞬移，现场
     * 就是"面板跳变加载通透背景"（rec.mp4 f259 实测：rim +4、内衬亮度重分布）。
     */
    fun motionSurfaceSamplingOriginStaysAtViewOrigin() {
        // 2026-09-23 表面 Drawable 拆到 LiquidSurfaceDrawables（凝光视效引擎重构）。
        val renderer = source("LiquidSurfaceDrawables")
        val provider = renderer.after("val motionProvider = view as? LiquidMotionSurfaceFrameProvider")
            .substringBefore("if (view != null) {", "MISSING")
        assertNotEquals("MISSING", provider)
        assertFalse(provider.contains("drawX += motionBounds"))
        assertFalse(provider.contains("drawY += motionBounds"))
        assertFalse(provider.contains("drawX += "))
        assertFalse(provider.contains("drawY += "))
    }

    /**
     * 玻璃填充必须随 drawable 自身 alpha 衰减（2026-09-22 真机实证）。
     *
     * `contentBackground.alpha = 0` 是承载层在场期间隐藏卡片自身背景的唯一手段，
     * 但原来只有 `drawSurfaceLayers` 的色罩/描边读 alpha——`drawOpticalRegion` 按
     * `glassContentAlpha*255` 恒强度直采、`drawBackdrop` 的 `contentAlpha` 也只取
     * `glassContentAlpha`。结果：形变全程卡片那张 drawable 的光学填充仍在画，
     * 与承载层填充叠成 ~86% 覆盖，面板近乎实心；落定摘层后回到单层才透出底页——
     * 现场就是"呼出动画没有通透，播完突然加载"（g_1 ghost_std≈1 → g_2 ≈7.5）。
     */
    fun drawableAlphaScalesTheGlassFillOnBothPaths() {
        val renderer = source("LiquidActivityRenderer")
        val draw = renderer.after("internal fun drawSurface(")
            .substringBefore("private fun drawSurfaceLayers(", "MISSING")
        assertNotEquals("MISSING", draw)
        assertTrue("optical region must scale with drawable alpha",
            draw.contains("glassContentAlpha(role) * alpha.toFloat()"))
        assertTrue("backdrop shader fill must scale with drawable alpha",
            draw.contains("glassContentAlpha(role) * (alpha / 255f)"))
    }

    /**
     * 抑制解除不许落在手指按着的时段（2026-09-22 用户报告 + 脚本 A/B）。
     *
     * 解除是一次重同步：整组表面重录回折射路径 + 立刻排一次全屏 PixelCopy，采集完成后
     * 再整组失效一次。它撞上新手势的头几帧就是可感知的迟滞，最容易复现的姿势是
     * "回弹刚结束立刻反向滑"——回弹把静默窗口一路顺延，手一松窗口到点，解除正好撞上
     * 下一次按下。脚本对比：掉帧 1.80% → 1.09%，95 分位 17ms → 13ms。
     *
     * 上界必须存在：长按不动本来就该恢复实时档，不能因为手指贴着就无限停在磨砂观感。
     */
    fun theSuppressionReleaseWaitsOutAnActiveGesture() {
        val renderer = source("LiquidActivityRenderer")
        val settle = renderer.substringAfter("private fun onScrollSettleCheck()", "MISSING")
            .before("private fun clearScrollSuppression()")
        assertNotEquals("MISSING", settle)
        assertTrue("按着时必须推迟解除", settle.contains("gestureHoldsRelease("))
        assertTrue("推迟后要继续排查，不能丢掉这次解除", settle.contains("scrollSettlePending = true"))
        val hold = renderer.after("private fun gestureHoldsRelease(")
            .before("private fun onScrollSettleCheck()")
        assertTrue("推迟必须有上界", hold.contains("GESTURE_RELEASE_HOLD_MS"))
        assertTrue(renderer.contains("private const val GESTURE_RELEASE_HOLD_MS"))

        // 手势起止由 Activity 的 dispatchTouchEvent 统一转发，覆盖所有滚动容器。
        val activity = source2("ui/skin/activity/SkinnedActivity.kt")
        val dispatch = activity.after("override fun dispatchTouchEvent(")
            .before("protected fun clearElasticInteractions()")
        assertTrue(dispatch.contains("MotionEvent.ACTION_DOWN -> skinSessionOrNull?.notifyGestureActive(true)"))
        assertTrue(dispatch.contains("notifyGestureActive(false)"))
    }

    private fun source2(relative: String): String = LceMotionSource.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative")

    private fun source(name: String): String = LceMotionSource.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$name.kt")

    /**
     * 形变表面（二级页容器展开/收回、预测式返回）只改内部矩形、View 不动，必须同样触发抑制：
     * 否则玻璃折射的实时截图里留着旧帧的反馈遮罩轮廓，框内出现一道圆角缝（2026-09-24 真机：
     * 关实时截图缝即消失）。这种抑制只换底图、不降级着色，动画结束解除时光影不跳变；
     * 一旦有真实滚动立即回到 lite。
     */
    fun morphingSurfacesSampleTheStableBackdropWithoutLiteShading() {
        val renderer = source("LiquidActivityRenderer")
        val register = renderer.after("internal fun registerSurfaceView(")
            .before("override fun notifyPositionChanged()")
        assertTrue(register.contains("view is LiquidMotionSurfaceFrameProvider"))
        assertTrue(register.indexOf("suppressRealtimeSamplingWhileScrolling()") in
            0 until register.indexOf("footprint.update(bounds, radiusPx, originX, originY)"))
        assertTrue(register.contains("if (!wasSuppressed && realtimeSamplingSuppressed) suppressionFromMorphOnly = true"))
        val moved = renderer.after("private fun invalidateMovedSurfaces()")
            .before("private fun invalidateRegisteredSurfaces()")
        assertTrue("真实滚动撤销形变豁免", moved.contains("suppressionFromMorphOnly = false"))
        val settle = renderer.after("private fun onScrollSettleCheck()")
            .before("private fun invalidateRegisteredSurfaces()")
        assertTrue(Regex("""realtimeSamplingSuppressed = false\s+suppressionFromMorphOnly = false""")
            .findAll(settle).count() == 2)
    }

    /**
     * 二级页（设置备份、统一诊断）从不发起实时截图（2026-09-24 用户报告：动画结束约 0.5s
     * 后控件光影跳变，正是采样源从稳定底图换成截图的时刻）。卡片背后只有背景，截图无收益。
     */
    fun staticBackdropHostsNeverStartRealtimeCapture() {
        val renderer = source("LiquidActivityRenderer")
        assertTrue(renderer.contains("private val staticBackdropHost = activity is LiquidStaticBackdropHost"))
        val post = renderer.after("private fun postRealtimeFrameCallback()").take(400)
        assertTrue(post.contains("staticBackdropHost"))
        val request = renderer.after("private fun requestRealtimeCapture(").take(400)
        assertTrue(request.contains("staticBackdropHost"))
        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
            val activity = LceMotionSource.read(path)
            assertTrue(name, activity.contains("LiquidStaticBackdropHost {"))
        }
        // 主界面必须保留实时截图：悬浮栏与卡片背后有滚动内容。
        val mainPath = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"
        val main = LceMotionSource.read(mainPath)
        assertFalse(main.contains("LiquidStaticBackdropHost"))
    }

    /**
     * 主窗口失焦（弹窗盖在上面）期间不发 PixelCopy（2026-09-24 atrace：面板入场收尾时主窗口
     * 连截 3 张、偶有 12–16ms 一张顶掉一帧）。重新获焦补排一次采集，监听随关闭摘除。
     */
    fun unfocusedWindowSkipsRealtimeCapture() {
        val renderer = source("LiquidActivityRenderer")
        val post = renderer.after("private fun postRealtimeFrameCallback()").take(300)
        assertTrue(post.contains("windowObscured"))
        val request = renderer.after("private fun requestRealtimeCapture(").take(400)
        assertTrue(request.contains("windowObscured"))
        val listener = renderer.after("private val windowFocusListener").take(500)
        assertTrue(listener.contains("windowObscured = true"))
        assertTrue(listener.contains("scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)"))
        assertTrue(renderer.contains("addOnWindowFocusChangeListener(windowFocusListener)"))
        assertTrue(renderer.contains("removeOnWindowFocusChangeListener(windowFocusListener)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidFeedbackIsolationTest) -> Unit) {
            val s = LiquidFeedbackIsolationTest()
            block(s)
        }
        runIsolated { s -> s.ownedOutputHasZeroRecursiveContributionAndUnmaskedPixelsStayLive() }
        runIsolated { s -> s.sourceWiringFreezesMasksBeforeCaptureAndSanitationReplacesOwnedOutput() }
        runIsolated { s -> s.overscrollKeepsTheSystemStretchButPaintsNoBoundaryRing() }
        runIsolated { s -> s.stretchOpticsFollowTheActiveEdgeDirection() }
        runIsolated { s -> s.scrollingSuppressesStaleRealtimeSampling() }
        runIsolated { s -> s.suppressedSurfacesStayRefractiveInLiteMode() }
        runIsolated { s -> s.theInteriorFastPathOnlyFiresWhereItIsProvablyEquivalent() }
        runIsolated { s -> s.theMotionLiteInteriorSkipsTheEdgeMath() }
        runIsolated { s -> s.chromaticDispersionIsConfinedToTheRimBand() }
        runIsolated { s -> s.stretchKeepsTheRefractivePathAndTheRestingGlow() }
        runIsolated { s -> s.cheapOpticalPathKeepsALuminousEdgeOnEveryRole() }
        runIsolated { s -> s.stretchEffectsOnlyDrawOnHardwareCanvases() }
        runIsolated { s -> s.motionSurfaceSamplingOriginStaysAtViewOrigin() }
        runIsolated { s -> s.drawableAlphaScalesTheGlassFillOnBothPaths() }
        runIsolated { s -> s.theSuppressionReleaseWaitsOutAnActiveGesture() }
        runIsolated { s -> s.morphingSurfacesSampleTheStableBackdropWithoutLiteShading() }
        runIsolated { s -> s.staticBackdropHostsNeverStartRealtimeCapture() }
        runIsolated { s -> s.unfocusedWindowSkipsRealtimeCapture() }
    }
}
