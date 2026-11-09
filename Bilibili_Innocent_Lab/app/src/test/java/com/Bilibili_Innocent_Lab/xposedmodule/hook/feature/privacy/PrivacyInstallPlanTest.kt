package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyInstallPlanTest {
    @Test fun stepsRunInOrderOnlyWhenApplied() {
        val log = mutableListOf<Int>()
        val plan = PrivacyInstallPlan()
        plan.step { log += 1 }
        plan.step { log += 2 }
        plan.step { log += 3 }
        assertEquals(3, plan.size)
        // Nothing runs until apply(): this is the all-or-nothing guarantee — a caller can abandon
        // the plan after a failed preflight and no hook was registered.
        assertTrue(log.isEmpty())
        assertEquals(3, plan.apply())
        assertEquals(listOf(1, 2, 3), log)
    }

    @Test fun emptyPlanAppliesNothing() {
        val plan = PrivacyInstallPlan()
        assertEquals(0, plan.size)
        assertEquals(0, plan.apply())
    }
}
