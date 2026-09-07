package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.dynamic.v2.*
import org.junit.Assert.*
import org.junit.Test

class DynamicCopyOnWriteTest {
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private fun purify(reply: DynAllReply): DynAllReply {
        val installer = DynamicPurifyFeatureInstaller(false, "", false, "", false, false, true, true)
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage })
        val specType = installer.javaClass.declaredClasses.single { it.simpleName == "FeedSpec" }
        val spec = specType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(DynAllReply::class.java.name, "executeDynAll", "dynAll")
        val feed = installer.javaClass.declaredMethods.single { it.name == "resolveFeed" }.apply { isAccessible = true }
            .invoke(installer, javaClass.classLoader, DynamicMoss::class.java, spec)
        assertNotNull(feed)
        val plan = DynamicPurifyPolicy.Plan(emptySet(), AuthorRuleSet.EMPTY, false, false)
        return installer.javaClass.declaredMethods.single { it.name == "purify" }.apply { isAccessible = true }
            .invoke(installer, environment, reply, feed, null, plan) as DynAllReply
    }

    @Test fun `UP list failure cannot clear either original list change positions or remove topic`() {
        for (failure in listOf("position", "second", "build")) {
            stages.clear()
            val first = UpListItem(0, 10, failure == "position")
            val second = UpListItem(0, 30)
            val original = DynAllReply(ups = CardVideoUpList(listOf(first, UpListItem(1, 20)), listOf(second), failure == "second"), failBuild = failure == "build")
            assertSame(original, purify(original)); assertTrue(original.topic)
            assertEquals(2, original.ups.first.size); assertEquals(1, original.ups.second.size)
            assertEquals(10L, first.position); assertEquals(30L, second.position)
            assertTrue(FeatureRuntimeStage.ERROR in stages); assertFalse(FeatureRuntimeStage.APPLIED in stages)
        }
    }

    @Test fun `UP list copies preserve two rows and continuously renumber without touching source`() {
        val first = UpListItem(0, 10)
        val second = UpListItem(0, 30)
        val original = DynAllReply(ups = CardVideoUpList(listOf(first, UpListItem(1, 20)), listOf(second)))
        val updated = purify(original)
        assertNotSame(original, updated); assertSame(original.unrelated, updated.unrelated)
        assertFalse(updated.topic); assertTrue(original.topic)
        assertEquals(listOf(1L), updated.ups.first.map { it.position })
        assertEquals(listOf(2L), updated.ups.second.map { it.position })
        assertEquals(10L, first.position); assertEquals(30L, second.position)
        assertSame(original.items, updated.items)
    }

    @Test fun `unchanged and default replies preserve identity`() {
        val original = DynAllReply(topic = false)
        assertSame(original, purify(original))
        assertSame(DynAllReply.getDefaultInstance(), purify(DynAllReply.getDefaultInstance()))
    }
}
