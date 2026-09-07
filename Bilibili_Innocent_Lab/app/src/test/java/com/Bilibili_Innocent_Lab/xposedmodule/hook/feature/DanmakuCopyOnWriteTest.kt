package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.community.service.dm.v1.*
import org.junit.Assert.*
import org.junit.Test

class DanmakuCopyOnWriteTest {
    private val errors = mutableListOf<String>()
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private fun purify(reply: DmSegMobileReply): DmSegMobileReply {
        val installer = DanmakuPurifyFeatureInstaller(true, 5, true)
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar,
            { _, _ -> }, { key, _ -> errors += key }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        val resolve = installer.javaClass.declaredMethods.single { it.name == "resolveMembers" }.apply { isAccessible = true }
        val members = resolve.invoke(installer, javaClass.classLoader, DmSegMobileReply::class.java)
        assertNotNull(members)
        return installer.javaClass.declaredMethods.single { it.name == "purify" }.apply { isAccessible = true }
            .invoke(installer, environment, reply, members, DmSegMobileReply.getDefaultInstance()) as DmSegMobileReply
    }

    @Test fun `failure at either list or build retains original message and both lists`() {
        for (failure in listOf("elems", "colorful", "build")) {
            stages.clear(); errors.clear()
            val original = DmSegMobileReply(listOf(DanmakuElem(1), DanmakuElem(10)), listOf(DmColorful(60001), DmColorful(0)), failAt = failure)
            assertSame(original, purify(original))
            assertEquals(2, original.elems.size); assertEquals(2, original.colorful.size)
            assertTrue(FeatureRuntimeStage.ERROR in stages)
            assertFalse(FeatureRuntimeStage.APPLIED in stages)
        }
    }

    @Test fun `successful filtering replaces reply without modifying source or unrelated fields`() {
        val kept = DanmakuElem(10)
        val original = DmSegMobileReply(listOf(DanmakuElem(1), kept), listOf(DmColorful(60001), DmColorful(0)))
        val updated = purify(original)
        assertNotSame(original, updated); assertSame(original.unrelated, updated.unrelated)
        assertEquals(listOf(kept), updated.elems); assertEquals(1, updated.colorful.size)
        assertEquals(2, original.elems.size); assertEquals(2, original.colorful.size)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `no matching rule and absent weight preserve identity and do not report an error`() {
        val original = DmSegMobileReply(listOf(DanmakuElem(0)), listOf(DmColorful(0)))
        assertSame(original, purify(original)); assertTrue(errors.isEmpty())
        assertFalse(FeatureRuntimeStage.ERROR in stages)
        assertSame(DmSegMobileReply.getDefaultInstance(), purify(DmSegMobileReply.getDefaultInstance()))
    }
}
