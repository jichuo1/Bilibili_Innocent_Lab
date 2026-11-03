package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionService
import com.bilibili.ship.theseus.united.page.intro.module.kingposition.`KingPositionComponent2$c`
import com.bilibili.ship.theseus.united.page.intro.module.kingposition.`KingPositionService$create$1$1$14$a`
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestFavoriteInteractionTest {
    private val continuation = object : Continuation<Any?> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) = Unit
    }

    private class OldCollector {
        fun handle(event: `KingPositionComponent2$c`, continuation: Continuation<*>): Any? = null
    }
    private class AmbiguousOldCollector {
        fun handle(event: `KingPositionComponent2$c`, continuation: Continuation<*>): Any? = null
    }

    @Test fun oldTypedCollectorIsChosenAndAmbiguityFailsClosed() {
        val event = `KingPositionComponent2$c`::class.java
        val continuation = Continuation::class.java
        val service = KingPositionService::class.java
        val single = GuestFavoriteInteraction.locateCollector(listOf(OldCollector::class.java), event, continuation, service)
        assertEquals("handle", single?.name)
        assertNull(GuestFavoriteInteraction.locateCollector(listOf(OldCollector::class.java, AmbiguousOldCollector::class.java),
            event, continuation, service))
    }

    @Test fun eventScopeSeparatesClickAndLongPressAndRestoresNestedHandlers() {
        val registrar = PlayerPortTestRegistrar()
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        val source = GuestFavoriteInteraction()
        assertTrue(source.install(env, loader))
        val collector = `KingPositionService$create$1$1$14$a`(KingPositionService())
        fun invoke(event: Any, run: () -> Unit) = registrar.invoke("guest.video.favorite.interaction", collector,
            arrayOf(event, continuation)) { run(); null }

        assertEquals(GuestVideoFavoriteHooks.Interaction.UNKNOWN, source.current())
        invoke(`KingPositionComponent2$c`.a()) {
            assertEquals(GuestVideoFavoriteHooks.Interaction.CLICK, source.current())
            invoke(`KingPositionComponent2$c`.b()) {
                assertEquals(GuestVideoFavoriteHooks.Interaction.LONG_PRESS, source.current())
            }
            assertEquals(GuestVideoFavoriteHooks.Interaction.CLICK, source.current())
            invoke(`KingPositionComponent2$c`.other()) {
                assertEquals(GuestVideoFavoriteHooks.Interaction.UNKNOWN, source.current())
            }
        }
        assertEquals(GuestVideoFavoriteHooks.Interaction.UNKNOWN, source.current())
    }
}
