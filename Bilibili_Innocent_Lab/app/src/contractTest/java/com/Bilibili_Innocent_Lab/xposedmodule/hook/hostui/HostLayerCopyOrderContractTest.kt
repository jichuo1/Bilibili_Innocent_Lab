package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-04：宿主 LayerDrawable 只在确有要改的层时才复制。
 * 行为等价由 HostLayerNeutralPlanTest 覆盖；这里锁定调用顺序，防止复制又被挪回判定之前。
 */
class HostLayerCopyOrderContractTest {
    private fun layerBranch() = SourceContract.read("hook/hostui/background/HostBackgroundPages.kt")
        .after("is LayerDrawable -> {\n                if (!nativeContainers")
        .before("is GradientDrawable -> {\n                // 宿主的纯色矩形入口")

    @Test fun planIsComputedOnTheOriginalBeforeAnyCopy() {
        val branch = layerBranch()
        assertEquals(1, Regex("""newDrawable\(\)""").findAll(branch).count())
        assertTrue(branch.indexOf("HostLayerNeutralPlan.of(") in 0 until branch.indexOf("newDrawable()"))
        // 无计划即返回：返回发生在复制之前。
        assertTrue(branch.indexOf("} ?: return") in 0 until branch.indexOf("newDrawable()"))
    }

    @Test fun copyBranchNoLongerReadsLayerGeometryDirectly() {
        val branch = layerBranch()
        for (read in listOf("getLayerHeight", "getLayerWidth", "getLayerInset", "numberOfLayers")) {
            assertFalse("复制分支不应再自行读取 $read", branch.contains(read))
        }
        assertTrue(branch.contains("applyNeutralPlan(copy, plan)"))
        assertTrue(branch.contains("view.background = copy"))
    }

    @Test fun planningViewDoesNotRetainTheHostDrawable() {
        val source = SourceContract.read("hook/hostui/background/HostBackgroundPages.kt")
        val branch = layerBranch()
        assertTrue(branch.contains("finally { reads.release() }"))
        assertTrue(source.after("fun release()").before("private fun layers()").contains("layers = null"))
    }
}
