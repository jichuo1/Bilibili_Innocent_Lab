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
}
