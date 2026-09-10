package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalTitleHandoffTest {
    @Test fun sourceHandoffKeepsPositionBaselineAndSizeExactlyAtTheSource() {
        for (progress in listOf(-1f, 0f, .03f, .06f, .10f, .12f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(0f, motion, 0f)
            assertEquals(37f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(541f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(16f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun targetHandoffOnlyStartsAfterPositionBaselineAndSizeHaveSettled() {
        for (progress in listOf(.85f, .90f, .925f, .99f, 1f, 2f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(1f, motion, 0f)
            assertEquals(173f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(297f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(19f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun movementBetweenHandoffsIsMonotonicBoundedAndSmoothAtBothStops() {
        var previous = 0f
        for (step in 0..1000) {
            val motion = ModalTitleMotionSpec.motionProgress(step / 1000f)
            assertTrue(motion in 0f..1f)
            assertTrue(motion >= previous)
            previous = motion
        }
        assertEquals(.5f, ModalTitleMotionSpec.motionProgress((.12f + .85f) / 2f), .000001f)
        val epsilon = .0001f
        for (boundary in listOf(.12f, .85f)) {
            val before = ModalTitleMotionSpec.motionProgress(boundary - epsilon)
            val at = ModalTitleMotionSpec.motionProgress(boundary)
            val after = ModalTitleMotionSpec.motionProgress(boundary + epsilon)
            assertTrue(abs(at - before) / epsilon < .01f)
            assertTrue(abs(after - at) / epsilon < .01f)
        }
    }

    /**
     * 任何一帧都必须有一份**满不透明**的标题在画，两端的原生标题永不同时出现。
     *
     * 回归：原来三条权重互补相加为 1，看着"守恒"，但**alpha 合成不是相加**——两份重合且相同
     * 的字各 0.5 叠起来只有 `1-(1-.5)(1-.5) = 0.75` 的覆盖率，而两端都是 1.0，于是每次交接
     * 都变暗约 25%，进出面板各闪一次。现在叠加层全程满不透明地盖在上面，
     * 覆盖率恒为 1，同时也不怕跨窗口晚一帧。
     */
    @Test fun everyFrameHasOneFullyOpaqueTitleAndTheTwoNativeOnesNeverCoexist() {
        for (step in -10..1010) {
            val progress = step / 1000f
            val source = ModalTitleMotionSpec.sourceWeight(progress)
            val target = ModalTitleMotionSpec.targetWeight(progress)
            val overlay = ModalTitleMotionSpec.overlayWeight(progress)
            assertTrue(source in 0f..1f && target in 0f..1f && overlay in 0f..1f)
            assertEquals("No frame may be dimmer than a full title", 1f,
                maxOf(source, target, overlay), 0f)
            assertEquals("Native titles at different locations must not appear together", 0f,
                source * target, 0f)
        }
        // 叠加层恒满：交接区不再出现"两份各半"的合成变暗。
        for (progress in listOf(0f, .06f, .12f, .5f, .85f, .925f, 1f, -1f, 2f, Float.NaN)) {
            assertEquals(1f, ModalTitleMotionSpec.overlayWeight(progress), 0f)
        }
        // 两端的原生标题仍必须走到满，叠加层撤掉的那一刻下面得接得住。
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), weights(0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), weights(1f), 0f)
        assertArrayEquals(floatArrayOf(.5f, 0f, 1f), weights(.06f), .000001f)
        assertArrayEquals(floatArrayOf(0f, .5f, 1f), weights(.925f), .000001f)
    }

    /**
     * 叠加层的颜色必须自己从来源色混到目标色。
     *
     * 它现在全程满不透明地盖在原生标题上，所以交接点两侧必须与下面那一份**同色**；
     * 否则灰色的入口标题与深色的面板标题会在 0.12 / 0.85 处各跳一次明度。
     */
    @Test fun theOverlayBlendsItsOwnColorFromTheSourceToTheTargetTitle() {
        val code = source("ModalTitleMotion")
        // 两端颜色都取 CSL 的**静止态**色：构造发生在点击那一刻，来源行可能正是 pressed 态。
        assertTrue(code.contains("private val overlayTextColor = sourceTextColors.defaultColor"))
        assertTrue(code.contains("private val targetTextColor = target.textColors.defaultColor"))
        assertFalse("must not latch the momentary pressed color",
            code.contains("source.currentTextColor"))
        val draw = code.substringAfter("override fun onDraw(").substringBefore("private fun stableTransform")
        assertTrue(draw.contains("blendARGB(overlayTextColor, targetTextColor, progress)"))
        // 混色用的必须是位移那条 progress：交接区它已被钳在 0 / 1，两端才对得上色。
        assertEquals(0f, ModalTitleMotionSpec.motionProgress(.12f), 0f)
        assertEquals(1f, ModalTitleMotionSpec.motionProgress(.85f), 0f)
    }

    @Test fun travelingTitleHasOneDrawingOwnerAndNativeTitlesStayHidden() {
        for (step in 120..850) {
            assertArrayEquals(floatArrayOf(0f, 0f, 1f), weights(step / 1000f), 0f)
        }
    }

    @Test fun handoffWeightsAreContinuousAndDoNotJumpAtTheTravelBoundaries() {
        for (boundary in listOf(0f, .12f, .85f, 1f)) {
            val before = weights(boundary - .00001f)
            val after = weights(boundary + .00001f)
            for (index in before.indices) assertTrue(abs(before[index] - after[index]) < .0001f)
        }
    }

    @Test fun interruptedEntryCloseAndGestureCancellationReuseTheSameTitleFrame() {
        for (start in listOf(.03f, .12f, .35f, .70f, .85f, .925f, .99f)) {
            for (target in listOf(0f, 1f)) {
                for (velocity in listOf(-4f, 0f, 4f)) {
                    val continuation = NavigationMotionContinuation(start, target, velocity, 240L)
                    assertArrayEquals(frame(start), frame(continuation.value(0f)), 0f)
                    assertArrayEquals(frame(target), frame(continuation.value(1f)), .000001f)
                }
            }
            // Visiting either endpoint must not select a different entry/exit title profile.
            val before = frame(start)
            frame(0f)
            frame(1f)
            assertArrayEquals(before, frame(start), 0f)
        }
    }

    @Test fun nativeLayoutOffsetsIncludePaddingLinePositionAndOwnScrollingExactlyOnce() {
        assertEquals(104f, ModalTitleMotionSpec.layoutOffset(100f, 12f, -3f, 5f), 0f)
        assertEquals(92.25f, ModalTitleMotionSpec.layoutOffset(80f, 8.5f, 7f, 3.25f), 0f)
        assertEquals(-43f, ModalTitleMotionSpec.layoutOffset(-60f, 12f, 9f, 4f), 0f)
        assertEquals(27f, ModalTitleMotionSpec.layoutOffset(0f, 5f, 26f, 4f), 0f)
    }

    @Test fun targetHandoffWaitsUntilBothContentProfilesAreOpaqueAndUntranslated() {
        val geometry = IconAnchoredMotionGeometry(
            collapsedBounds = SettingsBackupMotionRect(24f, 840f, 384f, 900f),
            expandedBounds = SettingsBackupMotionRect(32f, 160f, 368f, 780f),
            collapsedRadiusPx = 30f,
            expandedRadiusPx = 28f,
            contentTravelCapPx = 20f
        )
        val frame = IconAnchoredMotionFrameBuffer()
        for (timing in IconAnchoredContentTiming.entries) {
            for (progress in listOf(.85f, .90f, .925f, 1f)) {
                assertEquals(1f, IconAnchoredMotionSpec.contentFraction(progress, timing), 0f)
                IconAnchoredMotionSpec.fillFrame(frame, progress, geometry, timing)
                assertEquals(1f, frame.contentAlpha, 0f)
                assertEquals(0f, frame.contentTranslationXPx, 0f)
                assertEquals(0f, frame.contentTranslationYPx, 0f)
            }
        }
    }

    /**
     * 来源行把"标题 + \n + 摘要"塞进同一个 TextView 时，首行仍能配对。
     *
     * 回归："推荐标题关键词"这个填写面板的入口行文案与面板标题**完全同名**
     * （`home_recommend_title_rules` == `home_recommend_title_dialog_title`），
     * 但入口是 `标题 + "\n" + 摘要` 的单个 TextView（`ruleSummaryText` /
     * `ComponentPickerSurface.refreshSummary` 都这么拼），整段比较永远配不上，
     * 于是这类填写面板全都拿不到文字平移，只有容器形变。
     */
    @Test fun aMergedTitleAndSummaryRowStillPairsOnItsFirstLine() {
        assertTrue(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词", "推荐标题关键词"))
        assertTrue(ModalTitleMotionSpec.titleLineMatches(
            "推荐标题关键词\n当前未配置关键词，点击编辑", "推荐标题关键词"))
        assertTrue(ModalTitleMotionSpec.titleLineMatches(
            "推荐标题关键词\n当前关键词：竖屏\n第三行", "推荐标题关键词"))
        // 放宽的只是"标题在哪"，不是配对的严格程度。
        assertFalse(ModalTitleMotionSpec.titleLineMatches("评论关键词", "编辑评论关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("自定义首页组件", "首页组件隐藏规则"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词？\n摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches(" 推荐标题关键词\n摘要", "推荐标题关键词"))
        // 首行只是标题的前缀不算：必须紧跟换行。
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词过滤\n摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词 摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("", ""))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("\n摘要", ""))
        // 严格的整段比较保持不变，仍是目标标题那一侧的判据。
        assertFalse(ModalTitleMotionSpec.matches("推荐标题关键词\n摘要", "推荐标题关键词"))
    }

    /** 首行必须取自 Layout 的真实行边界：软换行时首行不等于标题，那种行不该配对。 */
    @Test fun renderedFirstLineComesFromTheLayoutNotFromSplittingTheRawString() {
        val text = "推荐标题关键词\n当前未配置关键词，点击编辑"
        val hardBreak = text.indexOf('\n') + 1
        assertEquals("推荐标题关键词",
            ModalTitleMotionSpec.renderedTitleLine(text, 0, hardBreak))
        // 软换行：Layout 把一行折成两行，首行不含换行符也不等于标题。
        assertEquals("推荐标题关",
            ModalTitleMotionSpec.renderedTitleLine("推荐标题关键词", 0, 5))
        assertFalse(ModalTitleMotionSpec.matches(
            ModalTitleMotionSpec.renderedTitleLine("推荐标题关键词", 0, 5), "推荐标题关键词"))
        // 越界索引不得抛异常，动画降级成只做容器形变即可。
        assertEquals("", ModalTitleMotionSpec.renderedTitleLine("abc", 5, 2))
        assertEquals("abc", ModalTitleMotionSpec.renderedTitleLine("abc", -3, 99))
        assertEquals("", ModalTitleMotionSpec.renderedTitleLine("", 0, 0))
    }

    /**
     * 描边的不透明度必须**恰好在形状停住那一刻**满，且与正文是两条独立通道。
     *
     * 回归：描边原来跟着正文走（`contentFraction` 在 0.78 就收满），之后还有约四成行程在跑，
     * 于是"最终尺寸的描边"被一个仍在生长的裁剪矩形切开，边框与形状看着是分离的。
     * 正文窗口不能顺手延后——`targetWeight` 从 0.85 起把标题交还给卡片里的真实 TextView，
     * 那时卡片若还在淡入，标题会被淡两次。
     */
    @Test fun theCardStrokeFadesOnItsOwnRampThatLandsExactlyWhenTheShapeStops() {
        assertEquals(0f, IconAnchoredMotionSpec.strokeAlpha(0f), 0f)
        assertEquals(1f, IconAnchoredMotionSpec.strokeAlpha(1f), 0f)
        // 形状明显还在动的前半程，描边基本不可见。
        assertTrue(IconAnchoredMotionSpec.strokeAlpha(0.45f) <= 0f)
        assertTrue(IconAnchoredMotionSpec.strokeAlpha(0.5f) < 0.05f)
        // 单调、有界、越界输入按端点钳制。
        var previous = -1f
        for (step in -10..1010) {
            val value = IconAnchoredMotionSpec.strokeAlpha(step / 1000f)
            assertTrue(value in 0f..1f)
            assertTrue(value >= previous)
            previous = value
        }
        assertEquals(0f, IconAnchoredMotionSpec.strokeAlpha(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, IconAnchoredMotionSpec.strokeAlpha(Float.POSITIVE_INFINITY), 0f)
        // 与正文是两条曲线：0.78 处正文已满，描边还没有。
        for (timing in IconAnchoredContentTiming.entries) {
            assertEquals(1f, IconAnchoredMotionSpec.contentFraction(0.78f, timing), 0f)
        }
        assertTrue(IconAnchoredMotionSpec.strokeAlpha(0.78f) < 1f)
        // 标题交还点（0.85）之前正文必须已经稳定，这条纪律不许被描边改动带偏。
        for (timing in IconAnchoredContentTiming.entries) {
            assertEquals(1f, IconAnchoredMotionSpec.contentFraction(0.85f, timing), 0f)
        }
        // fillFrame 必须把这条通道填出来，且与 contentAlpha 不是同一个值。
        val geometry = IconAnchoredMotionGeometry(
            collapsedBounds = SettingsBackupMotionRect(24f, 840f, 384f, 900f),
            expandedBounds = SettingsBackupMotionRect(32f, 160f, 368f, 780f),
            collapsedRadiusPx = 30f,
            expandedRadiusPx = 28f,
            contentTravelCapPx = 20f
        )
        val frame = IconAnchoredMotionFrameBuffer()
        IconAnchoredMotionSpec.fillFrame(frame, 0.78f, geometry)
        assertEquals(1f, frame.contentAlpha, 0f)
        assertTrue(frame.strokeAlpha < 1f)
        IconAnchoredMotionSpec.fillFrame(frame, 1f, geometry)
        assertEquals(1f, frame.strokeAlpha, 0f)
        IconAnchoredMotionSpec.fillFrame(frame, 0f, geometry)
        assertEquals(0f, frame.strokeAlpha, 0f)
    }

    /**
     * 平台级窗口动画必须关掉，且**必须在 `setContentView` 之后**关。
     *
     * 回归：`dialog.dismiss()` 是同步移窗，而 WindowManager 的退出动画搬的是这个 surface
     * **最后一次真正绘制**的那一帧；`apply(0f)` 与 `titleMotion.closed()` 都发生在 animator
     * 回调里、在本帧 TRAVERSAL **之前**，压根没被画出来。于是最后一帧里那份满不透明的飞行标题
     * 被窗口动画拖着往上飘走并淡出——现场就是"返回动画末端，文字上方冒出一个重影往上飞着消失"。
     *
     * 顺序同样是踩过的坑：`PhoneWindow.generateLayout()`（由 `setContentView` 触发）会从主题
     * 重新读 `windowAnimationStyle` 覆盖 `params.windowAnimations`，放在它之前写的 0 会被抹掉，
     * 真机 `dumpsys window windows` 里 `anim=` 依旧非零。
     */
    @Test fun theDialogWindowAnimationIsDisabledAfterSetContentView() {
        val code = source("MainActivity")
        val present = code.substringAfter("private fun presentSizedModalDialog(")
        val setContent = present.indexOf("dialog.setContentView(root)")
        val disable = present.indexOf("dialog.window?.setWindowAnimations(0)")
        assertTrue("setContentView not found", setContent > 0)
        assertTrue("window animation must be disabled", disable > 0)
        assertTrue("must be disabled after setContentView", disable > setContent)
        // 别再写回 apply{} 块里（那个块在 setContentView 之前）。
        val windowBlock = present.substringAfter("dialog.window?.apply {").substringBefore("}")
        assertFalse(windowBlock.contains("setWindowAnimations"))
    }

    /** 只搬首行：摘要不能跟着飞，也不能被整段绘制带出来。 */
    @Test fun onlyTheFirstLineIsDrawnByTheTravelingOverlay() {
        val code = source("ModalTitleMotion")
        val draw = code.substringAfter("override fun onDraw(").substringBefore("private fun stableTransform")
        assertTrue(draw.contains("clipRect("))
        assertTrue(draw.contains("layout.getLineTop(0)"))
        assertTrue(draw.contains("layout.getLineBottom(0)"))
        // 裁剪必须发生在进入 layout 坐标系之后、绘制之前。
        assertTrue(draw.indexOf("-layout.getLineBaseline(0)") < draw.indexOf("clipRect("))
        assertTrue(draw.indexOf("clipRect(") < draw.indexOf("layout.draw(this)"))
        // 目标标题仍必须独占一行，来源才允许多行。
        assertTrue(code.contains("targetLayout.lineCount != 1 || layout.lineCount < 1"))
        assertTrue(code.contains("titleLineMatches(source.textToString(), title)"))
        assertTrue(code.contains("matches(target.textToString(), title)"))
    }

    /**
     * 点击后的高光必须就地播完，不能被形变冻结到最后才补播一次。
     *
     * 回归：`ModalTitleMotion` 原来写 `source.alpha`，把整行连同 ripple 背景一起变透明。
     * `RippleDrawable` 的动画是在 `draw()` 里推进/创建的——View alpha 为 0 时父级直接跳过绘制，
     * ripple 被**冻结**在起始态；形变结束把 alpha 还原时它才第一次 draw 并开始播放，
     * 现场就是"面板都展开完了，一级界面那一行才闪一下高光"。
     */
    @Test fun theSourceRowKeepsDrawingSoItsRippleIsNotFrozenUntilTheMorphEnds() {
        val code = source("ModalTitleMotion")
        assertFalse("must not touch the row's View alpha", code.contains("source.alpha ="))
        assertTrue(code.contains("sourceTextColors.withAlpha("))
        // 还原必须交还原始 CSL，而不是再写一个降过 alpha 的单色副本。
        assertTrue(code.contains("source.setTextColor(sourceTextColors)"))
        // 三条收尾路径都要还原文字颜色。
        for (fn in listOf("fun expanded() {", "fun closed() {", "fun dispose() {")) {
            val body = code.substringAfter(fn).substringBefore("    fun ").substringBefore("    private fun ")
            assertTrue("$fn must restore the source text", body.contains("restoreSourceText()"))
        }
        // 上限取默认色自带的 alpha，半透明文字不会在中途被提亮。
        assertTrue(code.contains("Color.alpha(sourceTextColors.defaultColor)"))
    }

    /**
     * 飞行标题不能被"淡出来源文字"这件事牵连。
     *
     * 回归：叠加层用 `layout.draw()`，而那个 Layout 的画笔**就是来源 TextView 自己的那支**。
     * 上一版为了不冻结 ripple 改成压来源的文字颜色，结果来源控件下一次自绘就把这支画笔刷成
     * 近透明，飞行中的标题跟着一起消失——现场表现是"部分面板的文字平移效果没了"。
     * onDraw 必须每帧自己决定颜色（见 theOverlayBlendsItsOwnColorFromTheSourceToTheTargetTitle），
     * 并在退出时还原借用的值。
     */
    @Test fun theTravelingTitleForcesItsOwnColorInsteadOfInheritingTheFadedSource() {
        val code = source("ModalTitleMotion")
        val draw = code.substringAfter("override fun onDraw(").substringBefore("private fun stableTransform")
        assertTrue(draw.contains("val paint = layout.paint"))
        assertTrue(draw.contains("paint.color = androidx.core.graphics.ColorUtils"))
        // 借用必须还原，否则来源控件的真实颜色会被我们永久改掉。
        assertTrue(draw.contains("val borrowedColor = paint.color"))
        assertTrue(draw.contains("paint.color = borrowedColor"))
        assertTrue(draw.contains("finally {"))
        // 顶色必须发生在绘制之前，还原必须发生在绘制之后。
        assertTrue(draw.indexOf("blendARGB(") < draw.indexOf("layout.draw(this)"))
        assertTrue(draw.indexOf("layout.draw(this)") < draw.lastIndexOf("paint.color = borrowedColor"))
        // 仍然只有一次原生绘制，不重排、不建第二支画笔。
        assertEquals(1, Regex("layout\\.draw\\(this\\)").findAll(draw).count())
        for (forbidden in listOf("StaticLayout", "drawText", "Bitmap", "requestLayout")) {
            assertFalse(forbidden, draw.contains(forbidden))
        }
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(java.io.File(path), java.io.File("app/$path"))
            .first(java.io.File::isFile).readText()
    }

    private fun weights(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )

    private fun frame(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.motionProgress(progress),
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )
}
