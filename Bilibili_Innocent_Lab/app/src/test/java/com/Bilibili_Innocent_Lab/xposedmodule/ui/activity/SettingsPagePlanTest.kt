package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagePlanTest {
    private fun plan(log: MutableList<String>, vararg steps: () -> Unit) =
        SettingsPagePlan(steps.toList()) { log += "finish" }

    @Test fun completingRunsEveryStepInOrderThenFinishExactlyOnce() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "shell" }, { log += "body" })
        plan.complete()
        assertEquals(listOf("shell", "body", "finish"), log)
        assertTrue(plan.isComplete)
        plan.complete()
        assertEquals("finish is not repeated", 3, log.size)
    }

    @Test fun eachAdvanceIsOneBoundedUnitAndFinishIsItsOwnUnit() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a" }, { log += "b" }, { log += "c" })
        assertFalse(plan.advance()); assertEquals(listOf("a"), log)
        assertFalse(plan.advance()); assertEquals(listOf("a", "b"), log)
        assertFalse("the last step does not also finish the page", plan.advance()); assertEquals(listOf("a", "b", "c"), log)
        assertTrue(plan.advance()); assertEquals(listOf("a", "b", "c", "finish"), log)
        assertEquals(4, plan.stepNanos.size)
        assertEquals(3, plan.executed)
    }

    @Test fun stepsRegisteredWithDeferStepRunRightAfterTheCurrentStepInRegistrationOrder() {
        val log = ArrayList<String>()
        val plan = plan(log,
            { log += "shell"; deferStep { log += "c1" }; deferStep { log += "c2" }; deferStep { log += "c3" } },
            { log += "tail" })
        plan.complete()
        // 与未拆分前把分类内联在壳后面逐语句一致，且排在其它初始步骤之前。
        assertEquals(listOf("shell", "c1", "c2", "c3", "tail", "finish"), log)
    }

    @Test fun deferredStepsMayDeferAgainAndKeepDepthFirstOrder() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a"; deferStep { log += "b"; deferStep { log += "b1" } }; deferStep { log += "c" } })
        plan.complete()
        assertEquals(listOf("a", "b", "b1", "c", "finish"), log)
    }

    @Test fun deferStepOutsideAPlanRunsInlineSoLegacyCallersKeepWorking() {
        val log = ArrayList<String>()
        deferStep { log += "inline" }
        assertEquals(listOf("inline"), log)
    }

    @Test fun interruptedPlanKeepsItsCursorAndResumesWhereItStopped() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "shell"; deferStep { log += "x" }; deferStep { log += "y" } })
        plan.advance()
        assertEquals(listOf("shell"), log)
        assertEquals(2, plan.remaining)
        // 打断（预建被取消）期间什么都不会发生；之后同步补完只跑剩余部分。
        plan.complete()
        assertEquals(listOf("shell", "x", "y", "finish"), log)
    }

    @Test fun totalStepsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { SettingsPagePlan(emptyList()) {} }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsPagePlan(List(SettingsPagePlan.MAX_STEPS + 1) { {} }) {}
        }
        // 推迟登记也计入上限：16 个初始步骤再登记一个就越界。
        val plan = SettingsPagePlan(List(SettingsPagePlan.MAX_STEPS) { index -> { if (index == 0) deferStep {} } }) {}
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertEquals(SettingsPagePlan.MAX_STEPS + 1, SettingsPagePlan.MAX_UNITS)
    }

    @Test fun aFailingStepPoisonsThePlanAndNeverFinishesAHalfBuiltPage() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a" }, { throw IllegalStateException("boom") }, { log += "never" })
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows("failed plans stay failed", IllegalStateException::class.java) { plan.advance() }
        assertEquals(listOf("a"), log)
    }

    @Test fun aFailingFinishAlsoPoisonsThePlan() {
        val plan = SettingsPagePlan(listOf({})) { throw IllegalStateException("finish failed") }
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows(IllegalStateException::class.java) { plan.complete() }
    }

    @Test fun stepsCannotReenterTheirOwnPlan() {
        lateinit var plan: SettingsPagePlan
        plan = SettingsPagePlan(listOf({ plan.advance() })) {}
        assertThrows(IllegalStateException::class.java) { plan.advance() }
    }

    @Test fun unitTimesAreRecordedForEveryUnit() {
        var now = 0L
        val plan = SettingsPagePlan(listOf({ now += 5 }, { now += 7 })) { now += 3 }
        plan.complete()
        // 注入时钟的 advance 参数用默认值时无法断言具体数值，这里只验证数量与非负。
        assertEquals(3, plan.stepNanos.size)
        assertTrue(plan.stepNanos.all { it >= 0 })
        val timed = SettingsPagePlan(listOf({}, {})) {}
        val ticks = longArrayOf(10, 25, 40, 70, 100, 130).iterator()
        while (!timed.advance { ticks.next() }) Unit
        assertEquals(listOf(15L, 30L, 30L), timed.stepNanos)
    }

    @Test fun nestedDeferralOfTheSplitCardsKeepsTheSourceOrderWhenCompletedOrInterrupted() {
        // 实验性功能卡的形状：壳登记外观两段和兼容性子卡；兼容性子卡在自己的步骤里再登记三段。
        fun build(log: MutableList<String>) = plan(log,
            {
                log += "shell"
                deferStep { log += "appearance-1" }
                deferStep { log += "appearance-2" }
                deferStep {
                    log += "compat-shell"
                    deferStep { log += "compat-1" }
                    deferStep { log += "compat-2" }
                    deferStep { log += "compat-3" }
                }
            },
            { log += "log-card" })
        val expected = listOf("shell", "appearance-1", "appearance-2", "compat-shell", "compat-1", "compat-2", "compat-3", "log-card", "finish")
        val completed = ArrayList<String>()
        val plan = build(completed)
        plan.complete()
        assertEquals(expected, completed)
        assertEquals(8, plan.executed)
        assertEquals("every step plus the finish unit", 9, plan.stepNanos.size)

        // 预建每帧一个单元，任何位置被打断后同步补完，结果与一口气补完相同。
        for (pauseAfter in 0..expected.size) {
            val log = ArrayList<String>()
            val interrupted = build(log)
            var advances = 0
            while (advances < pauseAfter && !interrupted.advance()) advances++
            interrupted.complete()
            assertEquals("interrupted after $pauseAfter units", expected, log)
        }
    }

    @Test fun anyNestedStepTreeRunsInTextualOrderWithOneUnitPerStepPlusFinish() {
        class Tree(val name: String, val children: List<Tree>)
        fun generate(random: java.util.Random, budget: IntArray, prefix: String, depth: Int): Tree {
            val children = ArrayList<Tree>()
            if (depth < 3) repeat(random.nextInt(4)) { if (budget[0] > 0) { budget[0]--; children += generate(random, budget, "$prefix.${children.size}", depth + 1) } }
            return Tree(prefix, children)
        }
        fun preorder(tree: Tree): List<String> = listOf(tree.name) + tree.children.flatMap(::preorder)
        fun count(tree: Tree): Int = 1 + tree.children.sumOf(::count)
        fun step(tree: Tree, log: MutableList<String>): () -> Unit = {
            log += tree.name
            tree.children.forEach { child -> deferStep(step(child, log)) }
        }
        for (seed in 0L until 300L) {
            val random = java.util.Random(seed)
            val rootCount = 1 + random.nextInt(3)
            val budget = intArrayOf(SettingsPagePlan.MAX_STEPS - rootCount)
            val roots = List(rootCount) { generate(random, budget, "s$it", 0) }
            val total = roots.sumOf(::count)
            assertTrue("seed $seed stays within the bound", total <= SettingsPagePlan.MAX_STEPS)
            val expected = roots.flatMap(::preorder)
            for (mode in 0..2) {
                val log = ArrayList<String>()
                val plan = SettingsPagePlan(roots.map { step(it, log) }) { log += "finish" }
                when (mode) {
                    0 -> plan.complete()
                    1 -> while (!plan.advance()) Unit
                    else -> { repeat(random.nextInt(total + 1)) { plan.advance() }; plan.complete() }
                }
                assertEquals("seed $seed mode $mode", expected + "finish", log)
                assertEquals("seed $seed steps", total, plan.executed)
                assertEquals("seed $seed units", total + 1, plan.stepNanos.size)
                assertTrue(plan.isComplete)
            }
        }
    }

    @Test fun deferredStepsReadStateWhenTheyRunNotWhenTheyWereRegistered() {
        var stored = "initial"
        val viaFunction = ArrayList<String>()
        val viaSnapshot = ArrayList<String>()
        // 与 enhancementSettingsCard / experimentalFeaturesCard 的做法相同：壳拿到读取函数，推迟的步骤执行时才调用。
        fun cardTakingReader(uiSettings: () -> String) = deferStep { viaFunction += uiSettings() }
        // 反例：壳构建时读好的快照，推迟的步骤只能看到过期值。
        fun cardTakingSnapshot(uiSettings: String) = deferStep { viaSnapshot += uiSettings }
        val plan = SettingsPagePlan(listOf({ cardTakingReader { stored }; cardTakingSnapshot(stored) })) {}
        plan.advance()
        stored = "written between frames"
        plan.complete()
        assertEquals(listOf("written between frames"), viaFunction)
        assertEquals(listOf("initial"), viaSnapshot)
    }

    @Test fun aFailingDeferredStepDeepInTheTreeStopsEverythingAfterItAndNeverFinishes() {
        val log = ArrayList<String>()
        val plan = plan(log,
            { log += "shell"; deferStep { log += "a"; deferStep { throw IllegalStateException("boom") }; deferStep { log += "a2" } } },
            { log += "tail" })
        plan.advance(); plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows(IllegalStateException::class.java) { plan.complete() }
        assertEquals("siblings, later steps and the finish unit never run", listOf("shell", "a"), log)
    }

    @Test fun nestedRegistrationsCountAgainstTheSameBound() {
        val plan = SettingsPagePlan(listOf({ deferStep { repeat(SettingsPagePlan.MAX_STEPS) { deferStep {} } } })) {}
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        // 恰好填满上限是允许的：1 个初始步骤 + 1 个推迟步骤 + 14 个嵌套步骤。
        val exact = SettingsPagePlan(listOf({ deferStep { repeat(SettingsPagePlan.MAX_STEPS - 2) { deferStep {} } } })) {}
        exact.complete()
        assertEquals(SettingsPagePlan.MAX_STEPS, exact.executed)
        assertEquals(SettingsPagePlan.MAX_UNITS, exact.stepNanos.size)
    }
}
