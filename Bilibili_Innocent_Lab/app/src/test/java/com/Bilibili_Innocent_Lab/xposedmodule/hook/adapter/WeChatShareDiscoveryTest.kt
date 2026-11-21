package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import org.junit.Assert.*
import org.junit.Test

class WeChatShareDiscoveryTest {
    private val loader = javaClass.classLoader!!
    private val legacyInit = kz3.a::class.java.getMethod("t")
    private val modernInit = kntr.common.share.common.handler.wx.k::class.java.getMethod("j")

    fun shareWrappersAreDiscoveredFromCreateWxApiCallersAndCacheRevalidates() {
        val decoy = String::class.java.getMethod("length")
        val discovered = requireNotNull(WeChatShareCompatAccess.discover(loader,
            DexAssistResult.Candidates(listOf(legacyInit, modernInit, decoy))))
        assertEquals("kz3.a", discovered.legacyOwner)
        assertEquals("t", discovered.legacyInitialize)
        assertEquals("kntr.common.share.common.handler.wx.k", discovered.modernOwner)
        assertEquals("j", discovered.modernInitialize)
        assertTrue(discovered.usesLegacyConstructors)
        val decoded = requireNotNull(WeChatShareClasses.fromJson(discovered.toJson()))
        assertEquals(discovered, decoded)
        assertNotNull(WeChatShareCompatAccess.resolve(loader, decoded))
    }

    fun nonShareCallersAloneCannotProduceWrappers() {
        val decoy = String::class.java.getMethod("length")
        assertNull(WeChatShareCompatAccess.discover(loader, DexAssistResult.Candidates(listOf(decoy))))
        assertNull(WeChatShareCompatAccess.discover(loader, DexAssistResult.Candidates(List(33) { legacyInit })))
    }

    fun missingModernWrapperFailsClosedWhenWxCallersExist() {
        // kntr 包内出现调用者但无一通过全量验真时不得只装 legacy 一半。
        val brokenModern = kntr.common.share.common.handler.wx.i::class.java.getMethod("b")
        // i 的构造是 (WeChatShareActivity, type)，与 legacy 的构造代次不一致，整体必须缺失。
        assertNull(WeChatShareCompatAccess.discover(loader,
            DexAssistResult.Candidates(listOf(legacyInit, brokenModern))))
    }

    fun gatesNeverScanWhenDisabledDirectOrAlreadyAttempted() {
        assertFalse(WeChatShareCompatAccess.needsQuery(false, true, false))
        assertFalse(WeChatShareCompatAccess.needsQuery(true, false, false))
        assertFalse(WeChatShareCompatAccess.needsQuery(true, true, true))
        assertTrue(WeChatShareCompatAccess.needsQuery(true, true, false))
        assertTrue(WeChatShareCompatAccess.refreshCache(true, true, false, false, false))
        assertFalse(WeChatShareCompatAccess.refreshCache(true, true, false, false, true))
        assertFalse(WeChatShareCompatAccess.refreshCache(true, true, false, true, false))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (WeChatShareDiscoveryTest) -> Unit) {
            val s = WeChatShareDiscoveryTest()
            block(s)
        }
        runIsolated { s -> s.shareWrappersAreDiscoveredFromCreateWxApiCallersAndCacheRevalidates() }
        runIsolated { s -> s.nonShareCallersAloneCannotProduceWrappers() }
        runIsolated { s -> s.missingModernWrapperFailsClosedWhenWxCallersExist() }
        runIsolated { s -> s.gatesNeverScanWhenDisabledDirectOrAlreadyAttempted() }
    }
}
