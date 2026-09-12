package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.adcommon.data.model.Card
import com.bilibili.adcommon.data.model.Dm
import com.bilibili.adcommon.data.model.DmAdvert
import org.junit.Assert.*
import org.junit.Test

class PlayerPopupPromotionTest {
    private val loader = javaClass.classLoader!!
    private val id = PlayerPopupPromotionFeatureInstaller.ID

    private fun environment(registrar: PlayerPortTestRegistrar,
                            events: MutableList<FeatureRuntimeStage> = mutableListOf(),
                            logs: MutableList<String> = mutableListOf()) =
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { key, _ -> logs += key }, { _, _ -> },
            runtimeEvidence = { feature, stage, _ -> assertEquals(id, feature); events += stage })

    @Test fun `real callbacks remove only common promotion without mutating host lists`() {
        val registrar = PlayerPortTestRegistrar()
        val events = mutableListOf<FeatureRuntimeStage>()
        assertEquals(FeatureInstallResult.Installed(3), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events)))
        val popup = Dm(Card(80)); val commerce = Dm(Card(79)); val guide = Dm(Card(31))
        val danmaku = Dm(Card(21)); val unknown = Dm(null)
        val source = listOf(popup, commerce, guide, danmaku, unknown)
        for (reader in PlayerPopupPromotionLocator.READERS) {
            events.clear()
            val actual = registrar.invoke("player.popup.promotion.$reader", receiver = DmAdvert(source)) { source } as List<*>
            assertEquals(listOf(commerce, guide, danmaku, unknown), actual)
            assertEquals(5, source.size); assertSame(popup, source.first())
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        }
        // 普通弹幕路径不注册，VideoGuide/章节点也不属于这个安装器。
        assertEquals(3, registrar.hooks.size)
        assertTrue(registrar.hooks.values.all { it.member.declaringClass == DmAdvert::class.java })
        assertFalse(registrar.hooks.values.any { it.member.name == "getDms" })
    }

    @Test fun `empty null unknown and no hit preserve identity and never claim applied`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val hook = "player.popup.promotion.getFloatLayers"
        for (source in listOf(emptyList<Any>(), listOf(null, "去小程序", Dm(Card(79)), Dm(Card(999))))) {
            events.clear()
            assertSame(source, registrar.invoke(hook, receiver = DmAdvert()) { source })
            assertFalse(FeatureRuntimeStage.APPLIED in events)
        }
        events.clear(); assertNull(registrar.invoke(hook) { null }); assertTrue(events.isEmpty())
    }

    @Test fun `host exception and a late field read failure never publish partial filtering`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>(); val logs = mutableListOf<String>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events, logs))
        val hook = "player.popup.promotion.getFloatLayers"
        val failure = IllegalStateException("original failure")
        events.clear()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { registrar.invoke(hook) { throw failure } })
        assertTrue(events.isEmpty())
        val source = listOf(Dm(Card(80)), Dm(null, broken = true))
        repeat(2) { assertSame(source, registrar.invoke(hook) { source }) }
        assertFalse(FeatureRuntimeStage.APPLIED in events); assertEquals(1, logs.size)
    }

    @Test fun `failed registration remains in coverage denominator and other readers survive`() {
        val registrar = PlayerPortTestRegistrar("player.popup.promotion.getFloatLayers")
        assertEquals(FeatureInstallResult.Installed(2, false), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar)))
        assertEquals(2, registrar.hooks.size)
    }

    @Test fun `default off and non main processes never install hooks`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"), PlayerPopupPromotionFeatureInstaller(false).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar).copy(processName = "tv.danmaku.bili:web")))
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun `missing or wrongly typed host members fail open`() {
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionLocator.CARD_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertNull(PlayerPopupPromotionLocator.resolve(blocked))
        assertNull(PlayerPopupPromotionLocator.resolve(DmAdvert::class.java, WrongDm::class.java, Card::class.java))
        val partial = requireNotNull(PlayerPopupPromotionLocator.resolve(OnlyAds::class.java, Dm::class.java, Card::class.java))
        assertEquals(listOf("getAds"), partial.readers.map { it.name })
    }

    class WrongDm { fun getCard(): String = "not a card" }
    class OnlyAds {
        fun getAds(): List<Dm> = emptyList()
        fun getFloatLayers(): String = "not a list"
    }
}
