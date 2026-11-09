package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.community.service.dm.v1.*
import org.junit.Assert.*
import org.junit.Test

class DanmakuCopyOnWriteTest {
    private val errors = mutableListOf<String>()
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private fun purify(reply: DmSegMobileReply, installer: DanmakuPurifyFeatureInstaller = DanmakuPurifyFeatureInstaller(true, 5, true)): DmSegMobileReply {
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

    @Test fun liveRulesChangeTheSameInstallerWithoutMutatingSourceMessages() {
        var rules = com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveDanmakuRules(1, false)
        val installer = DanmakuPurifyFeatureInstaller(true, 5, true, liveRules = { rules })
        val original = DmSegMobileReply(listOf(DanmakuElem(2, colorful = 60001), DanmakuElem(9, colorful = 60001)), listOf(DmColorful(60001)))
        assertSame(original, purify(original, installer))
        rules = rules.copy(minimum = 8, colorful = true)
        val updated = purify(original, installer)
        assertEquals(1, updated.elems.size); assertEquals(0, updated.elems.single().getColorfulValue())
        assertTrue(updated.colorful.isEmpty()); assertEquals(2, original.elems.size)
        rules = rules.copy(minimum = 1, colorful = false)
        assertSame(original, purify(original, installer))
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

    /**
     * 删了会员渐变样式定义，引用它的弹幕必须同时改回普通色。
     * 弹幕分段整包交给原生引擎（libchronos）解析，不能留"条目引用一个已不存在的样式"。
     */
    @Test fun `removing the vip gradient style also neutralizes the danmaku that reference it`() {
        val gradient = DanmakuElem(10, colorful = 60001)
        val plain = DanmakuElem(10, colorful = 0)
        val original = DmSegMobileReply(listOf(gradient, plain), listOf(DmColorful(60001), DmColorful(0)))
        val updated = purify(original)
        assertEquals(listOf(0, 0), updated.elems.map { it.getColorfulValue() })
        assertSame(plain, updated.elems[1])
        assertEquals(listOf(0), updated.colorful.map { it.getTypeValue() })
        // 源消息不被改动。
        assertEquals(60001, original.elems[0].getColorfulValue())
    }

    /** 权重过滤与改色在同一份列表上叠加：先删低权重，再给保留下来的渐变弹幕改色。 */
    @Test fun `weight filtering and colour neutralization compose on the same list`() {
        val original = DmSegMobileReply(
            listOf(DanmakuElem(1, colorful = 60001), DanmakuElem(10, colorful = 60001)),
            listOf(DmColorful(60001))
        )
        val updated = purify(original)
        assertEquals(1, updated.elems.size)
        assertEquals(0, updated.elems.single().getColorfulValue())
        assertTrue(updated.colorful.isEmpty())
    }

    @Test fun `no matching rule and absent weight preserve identity and do not report an error`() {
        val original = DmSegMobileReply(listOf(DanmakuElem(0)), listOf(DmColorful(0)))
        assertSame(original, purify(original)); assertTrue(errors.isEmpty())
        assertFalse(FeatureRuntimeStage.ERROR in stages)
        assertSame(DmSegMobileReply.getDefaultInstance(), purify(DmSegMobileReply.getDefaultInstance()))
    }
}
