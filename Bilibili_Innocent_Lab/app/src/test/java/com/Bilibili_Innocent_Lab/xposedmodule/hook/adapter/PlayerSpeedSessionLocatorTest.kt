package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.bilibili.ship.theseus.keel.player.RenamedSpeedSessionOwner
import com.bilibili.ship.theseus.keel.player.SecondSpeedSessionOwner
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.RenamedSpeedSessionWrapper
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedSessionLocatorTest {
    private val loader = javaClass.classLoader!!
    private val run = RenamedSpeedSessionOwner::class.java.declaredMethods.single { it.name == "runPlayable" }
    private val secondRun = SecondSpeedSessionOwner::class.java.declaredMethods.single { it.name == "runPlayable" }
    private val seek = RenamedSpeedSessionWrapper::class.java.getMethod("seekTo", Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType)
    private fun runs() = DexAssistResult.Candidates(listOf(run))
    private fun wrappers() = DexAssistResult.Candidates(listOf(seek))

    fun renamedOwnerAndWrapperAreSelectedTogetherAndCacheIsReverified() {
        val selected = requireNotNull(PlayerSpeedSessionLocator.select(loader, runs(), wrappers()))
        assertEquals(RenamedSpeedSessionOwner::class.java.name, selected.owner)
        assertEquals(RenamedSpeedSessionWrapper::class.java.name, selected.wrapper)
        val decoded = requireNotNull(PlayerSpeedSessionClasses.fromJson(selected.toJson()))
        assertEquals(selected, decoded)
        assertNotNull(PlayerSpeedSessionLocator.resolve(loader, decoded))
        val missingOwner = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == decoded.owner) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(PlayerSpeedSessionLocator.resolve(missingOwner, decoded))
    }

    fun twoVerifiedOwnersOrOverBudgetInputsCannotBeInstalled() {
        assertNull(PlayerSpeedSessionLocator.select(loader,
            DexAssistResult.Candidates(listOf(run, secondRun)), wrappers()))
        assertNull(PlayerSpeedSessionLocator.select(loader, DexAssistResult.Candidates(List(33) { run }), wrappers()))
        assertNull(PlayerSpeedSessionLocator.select(loader, runs(), DexAssistResult.Candidates(List(33) { seek })))
    }

    fun disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan() {
        assertFalse(PlayerSpeedSessionLocator.needsQuery(false, true, false))
        assertFalse(PlayerSpeedSessionLocator.needsQuery(true, false, false))
        assertFalse(PlayerSpeedSessionLocator.needsQuery(true, true, true))
        assertTrue(PlayerSpeedSessionLocator.needsQuery(true, true, false))
        assertTrue(PlayerSpeedSessionLocator.refreshCache(true, true, false, false, false))
        assertFalse(PlayerSpeedSessionLocator.refreshCache(true, true, false, false, true))
        assertFalse(PlayerSpeedSessionLocator.refreshCache(true, true, false, true, false))
    }

    fun sessionQueriesShareOnePlannedBatchWithSponsor() {
        var batches = 0
        val engine = object : DexAssistEngine {
            override fun resolve(request: DexAssistRequest): DexAssistResult = error("unexpected separate scan")
            override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>,
                classLoader: ClassLoader): Map<DexAssistQuery, DexAssistResult> {
                batches++; assertEquals(PlayerSpeedSessionLocator.queries, queries)
                return mapOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE to runs(),
                    DexAssistQuery.SPONSOR_PLAYER_WRAPPER to wrappers())
            }
        }
        val session = DexAssistSession(engine, listOf("base.apk"), loader, PlayerSpeedSessionLocator.queries)
        val selected = requireNotNull(PlayerSpeedSessionLocator.assisted(loader, session))
        assertEquals(RenamedSpeedSessionOwner::class.java.name, selected.owner)
        assertEquals(1, batches)
    }

    fun classesJsonRejectsMalformedNames() {
        assertNull(PlayerSpeedSessionClasses.fromJson(org.json.JSONObject().put("owner", "").put("wrapper", org.json.JSONObject.NULL)))
        assertNull(PlayerSpeedSessionClasses.fromJson(org.json.JSONObject().put("owner", "bad name!").put("wrapper", org.json.JSONObject.NULL)))
        assertNull(PlayerSpeedSessionClasses.fromJson(org.json.JSONObject().put("owner", "a.B").put("wrapper", "bad/name")))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerSpeedSessionLocatorTest) -> Unit) {
            val s = PlayerSpeedSessionLocatorTest()
            block(s)
        }
        runIsolated { s -> s.renamedOwnerAndWrapperAreSelectedTogetherAndCacheIsReverified() }
        runIsolated { s -> s.twoVerifiedOwnersOrOverBudgetInputsCannotBeInstalled() }
        runIsolated { s -> s.disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan() }
        runIsolated { s -> s.sessionQueriesShareOnePlannedBatchWithSponsor() }
        runIsolated { s -> s.classesJsonRejectsMalformedNames() }
    }
}
