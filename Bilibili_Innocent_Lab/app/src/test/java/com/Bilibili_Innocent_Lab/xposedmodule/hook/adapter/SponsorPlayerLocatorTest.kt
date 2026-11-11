package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.bilibili.ship.theseus.keel.player.RenamedSponsorContinuation
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SponsorPlayerLocatorTest {
    private val loader = javaClass.classLoader!!
    private val run = TheseusKeelPlayer::class.java.declaredMethods.single { it.name == "runPlayable" }
    private val seek = RenamedSponsorWrapper::class.java.getMethod("seekTo", Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType)
    private fun runs() = DexAssistResult.Candidates(listOf(run), relatedClasses = mapOf(run to listOf(RenamedSponsorContinuation::class.java)))
    private fun wrappers(classes: List<Class<*>> = listOf(RenamedSponsorSeek::class.java)) =
        DexAssistResult.Candidates(listOf(seek), relatedClasses = mapOf(seek to classes))
    private val scopes = DexAssistResult.Candidates(emptyList())

    @Test fun renamedPlayerAndGuardAreSelectedTogetherAndCacheIsReverified() {
        val selected = requireNotNull(SponsorPlayerLocator.select(loader, runs(), wrappers(), scopes))
        assertEquals(RenamedSponsorContinuation::class.java.name, selected.classes.continuation)
        assertEquals(RenamedSponsorWrapper::class.java, selected.player.wrapper.declaringClass)
        assertEquals(RenamedSponsorSeek::class.java, selected.seek.constructor.declaringClass)
        val decoded = requireNotNull(SponsorPlayerClasses.fromJson(selected.classes.toJson()))
        assertEquals(selected.classes, SponsorPlayerLocator.resolve(loader, decoded)?.classes)
        val missingGuard = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == decoded.seek) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(SponsorPlayerLocator.resolve(missingGuard, decoded))
    }

    @Test fun unrelatedOrAmbiguousGuardsAndOverBudgetInputsCannotBeInstalled() {
        assertNull(SponsorPlayerLocator.select(loader, runs(), DexAssistResult.Candidates(listOf(seek)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(String::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(RenamedSponsorSeek::class.java,
            SecondSponsorSeek::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, DexAssistResult.Candidates(List(33) { run }), wrappers(), scopes))
    }

    @Test fun repackagedPlayableIsLimitedToTheAuditedRun() {
        val owner = "com.bilibili.ship.theseus.keel.player.b"
        assertTrue(SponsorPlayerAccess.acceptsPlayableClass(owner, "r", "hcg2"))
        assertFalse(SponsorPlayerAccess.acceptsPlayableClass(owner, "s", "hcg2"))
        assertFalse(SponsorPlayerAccess.acceptsPlayableClass("com.bilibili.ship.theseus.keel.player.c", "r", "hcg2"))
        assertFalse(SponsorPlayerAccess.acceptsPlayableClass(owner, "r", "unrelated0"))
    }

    @Test fun disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan() {
        assertFalse(SponsorPlayerLocator.needsQuery(false, true, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, false, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, true, true))
        assertTrue(SponsorPlayerLocator.needsQuery(true, true, false))
        assertTrue(SponsorPlayerLocator.refreshCache(true, true, false, false, false))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, false, true))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, true, false))
    }

    @Test fun sponsorQueriesShareOnePlannedBatchAndPreserveCallerAssociations() {
        var batches = 0
        val engine = object : DexAssistEngine {
            override fun resolve(request: DexAssistRequest): DexAssistResult = error("unexpected separate scan")
            override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>, classLoader: ClassLoader): Map<DexAssistQuery, DexAssistResult> {
                batches++; assertEquals(SponsorPlayerLocator.queries, queries)
                return mapOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE to runs(),
                    DexAssistQuery.SPONSOR_PLAYER_WRAPPER to wrappers(), DexAssistQuery.SPONSOR_CONTAINER_SCOPE to scopes)
            }
        }
        val session = DexAssistSession(engine, listOf("/base.apk"), loader, SponsorPlayerLocator.queries)
        assertNotNull(SponsorPlayerLocator.assisted(loader, session))
        assertNotNull(SponsorPlayerLocator.assisted(loader, session))
        assertEquals(1, batches)
    }

    @Test fun cacheRejectsUnboundedAndForeignNames() {
        val classes = SponsorPlayerClasses(RenamedSponsorContinuation::class.java.name,
            RenamedSponsorWrapper::class.java.name, null, RenamedSponsorSeek::class.java.name)
        assertEquals(classes, SponsorPlayerClasses.fromJson(classes.toJson()))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("seek", "java.lang.String")))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("wrapper", "x".repeat(513))))
        assertNull(SponsorPlayerClasses.fromJson(JSONObject()))
    }
}
