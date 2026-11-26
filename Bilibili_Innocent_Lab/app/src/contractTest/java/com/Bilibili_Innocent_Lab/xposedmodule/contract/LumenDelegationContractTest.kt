package com.Bilibili_Innocent_Lab.xposedmodule.contract

import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirms every module compatibility wrapper still delegates to published Lumen sources. */
class LumenDelegationContractTest {
    @Test
    fun allMotionAndLiquidWrappersDelegateToPublishedLumenSources() {
        assertTrue(LceMotionSource.delegates.isNotEmpty())
        for ((hostFile, target) in LceMotionSource.delegates) {
            val relative = when {
                target.first.startsWith("interaction/") -> "ui/interaction/$hostFile"
                else -> "ui/activity/$hostFile"
            }
            val code = LceMotionSource.read(relative)
            assertTrue("Expected non-empty Lumen source for $hostFile (${target.second})", code.isNotEmpty())
        }
        assertTrue(LceMotionSource.engine("core/liquid/LiquidStretchViewport.kt").isNotEmpty())
    }
}
