package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import com.bilibili.app.gemini.base.player.SponsorBusiness
import com.bilibili.ship.theseus.keel.player.RenamedSponsorContinuation
import com.bilibili.ship.theseus.keel.player.SponsorPlayable
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.*
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.*
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.coroutines.Continuation

class SponsorPlayerLocatorTest {
    private val loader = javaClass.classLoader!!
    private val run = TheseusKeelPlayer::class.java.declaredMethods.single { it.name == "runPlayable" }
    private val seek = RenamedSponsorWrapper::class.java.getMethod("seekTo", Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType)
    private fun runs() = DexAssistResult.Candidates(listOf(run), relatedClasses = mapOf(run to listOf(RenamedSponsorContinuation::class.java)))
    private fun wrappers(classes: List<Class<*>> = listOf(RenamedSponsorSeek::class.java)) =
        DexAssistResult.Candidates(listOf(seek), relatedClasses = mapOf(seek to classes))
    private val scopes = DexAssistResult.Candidates(emptyList())

    fun renamedPlayerAndGuardAreSelectedTogetherAndCacheIsReverified() {
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

    fun unrelatedOrAmbiguousGuardsAndOverBudgetInputsCannotBeInstalled() {
        assertNull(SponsorPlayerLocator.select(loader, runs(), DexAssistResult.Candidates(listOf(seek)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(String::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(RenamedSponsorSeek::class.java,
            SecondSponsorSeek::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, DexAssistResult.Candidates(List(33) { run }), wrappers(), scopes))
    }

    private interface FixtureDistractor { fun ping(): Int }
    private interface FixtureRootPlayable { fun t(): GeminiCommonPlayableParams }
    @Suppress("unused")
    private class FixtureRootOwner {
        fun e(d: FixtureDistractor, c: Continuation<Any?>): Any? = null
        fun k(): FixtureRootPlayable? = null
        fun r(p: FixtureRootPlayable, c: Continuation<Any?>): Any? = null
    }
    @Suppress("unused")
    private class FixtureRootWrapper(core: IPlayerCoreService, owner: FixtureRootOwner, scope: CoroutineScope) : IPlayerCoreService by core {
        override fun seekTo(position: Int, accurate: Boolean) {}
    }
    @Suppress("unused")
    private class FixtureRootSeek(owner: FixtureRootOwner, position: Int, accurate: Boolean, continuation: Continuation<Any?>?) {
        @JvmField var label = 0
        fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> =
            throw UnsupportedOperationException()
        fun invokeSuspend(value: Any): Any = Unit
    }

    private fun rewriteConstantPool(template: Class<*>, replacements: Map<String, String>): ByteArray {
        val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class")).use { it.readBytes() }
        val input = DataInputStream(ByteArrayInputStream(raw))
        val outBuffer = ByteArrayOutputStream()
        val output = DataOutputStream(outBuffer)
        output.writeInt(input.readInt())
        output.writeShort(input.readUnsignedShort())
        output.writeShort(input.readUnsignedShort())
        val count = input.readUnsignedShort()
        output.writeShort(count)
        val sorted = replacements.entries.sortedByDescending { it.key.length }
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            output.writeByte(tag)
            if (tag == 1) {
                var utf = input.readUTF()
                sorted.forEach { (from, to) -> utf = utf.replace(from, to) }
                output.writeUTF(utf)
            } else {
                val len = when (tag) {
                    3, 4, 9, 10, 11, 12, 17, 18 -> 4
                    5, 6 -> 8
                    7, 8, 16, 19, 20 -> 2
                    15 -> 3
                    else -> error("Unsupported classfile tag: $tag")
                }
                repeat(len) { output.writeByte(input.readUnsignedByte()) }
                if (tag == 5 || tag == 6) index++
            }
            index++
        }
        output.write(input.readBytes())
        output.flush()
        return outBuffer.toByteArray()
    }

    fun relocatedAndFlattenedRootClassesSelectStructurallyAndSurviveCacheRoundTrip() {
        val paramsClass = GeminiCommonPlayableParams::class.java
        assertFalse(SponsorPlayerAccess.isPlayableInterface(paramsClass, paramsClass))
        assertFalse(SponsorPlayerAccess.isPlayableInterface(FixtureDistractor::class.java, paramsClass))
        assertTrue(SponsorPlayerAccess.isPlayableInterface(SponsorPlayable::class.java, paramsClass))

        fun internal(cls: Class<*>) = cls.name.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "l723" to FixtureDistractor::class.java,
            "spq2" to FixtureRootPlayable::class.java,
            "k0" to FixtureRootOwner::class.java,
            "r0" to RenamedSponsorContinuation::class.java,
            "w0" to FixtureRootWrapper::class.java,
            "s0" to FixtureRootSeek::class.java
        )
        val replacements = linkedMapOf(
            internal(FixtureDistractor::class.java) to "l723",
            internal(FixtureRootPlayable::class.java) to "spq2",
            internal(FixtureRootOwner::class.java) to "k0",
            "com/bilibili/ship/theseus/keel/player/TheseusKeelPlayer" to "k0",
            internal(RenamedSponsorContinuation::class.java) to "r0",
            internal(FixtureRootWrapper::class.java) to "w0",
            internal(FixtureRootSeek::class.java) to "s0"
        )
        val hidden = setOf(SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.WRAPPER_CLASS, SponsorSeekAccess.CLASS)
        val flatLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                if (name in hidden) throw ClassNotFoundException(name)
                val tpl = templates[name] ?: return@synchronized super.loadClass(name, resolve)
                val loaded = findLoadedClass(name) ?: rewriteConstantPool(tpl, replacements).let {
                    defineClass(name, it, 0, it.size)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
        assertNull(SponsorPlayerLocator.direct(flatLoader))
        val flatRun = flatLoader.loadClass("k0").declaredMethods.single { it.name == "r" }
        val flatCont = flatLoader.loadClass("r0")
        val flatSeekMethod = flatLoader.loadClass("w0").getMethod("seekTo", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val flatSeekClass = flatLoader.loadClass("s0")
        val selected = requireNotNull(
            SponsorPlayerLocator.select(
                flatLoader,
                DexAssistResult.Candidates(listOf(flatRun), relatedClasses = mapOf(flatRun to listOf(flatCont))),
                DexAssistResult.Candidates(listOf(flatSeekMethod), relatedClasses = mapOf(flatSeekMethod to listOf(flatSeekClass))),
                DexAssistResult.Candidates(emptyList())
            )
        )
        assertEquals(SponsorPlayerClasses("r0", "w0", SponsorPlayerAccess.CONTAINER_SCOPE_CLASS, "s0"), selected.classes)
        val roundTripped = requireNotNull(SponsorPlayerClasses.fromJson(selected.classes.toJson()))
        assertEquals(selected.classes, roundTripped)
        assertNotNull(SponsorPlayerLocator.resolve(flatLoader, roundTripped))
    }

    fun disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan() {
        assertFalse(SponsorPlayerLocator.needsQuery(false, true, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, false, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, true, true))
        assertTrue(SponsorPlayerLocator.needsQuery(true, true, false))
        assertTrue(SponsorPlayerLocator.refreshCache(true, true, false, false, false))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, false, true))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, true, false))
    }

    fun sponsorQueriesShareOnePlannedBatchAndPreserveCallerAssociations() {
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

    fun cacheRejectsUnboundedAndForeignNames() {
        val classes = SponsorPlayerClasses(RenamedSponsorContinuation::class.java.name,
            RenamedSponsorWrapper::class.java.name, null, RenamedSponsorSeek::class.java.name)
        assertEquals(classes, SponsorPlayerClasses.fromJson(classes.toJson()))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("seek", "java.lang.String")))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("wrapper", "x".repeat(513))))
        assertNull(SponsorPlayerClasses.fromJson(JSONObject()))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorPlayerLocatorTest) -> Unit) {
            val s = SponsorPlayerLocatorTest()
            block(s)
        }
        runIsolated { s -> s.renamedPlayerAndGuardAreSelectedTogetherAndCacheIsReverified() }
        runIsolated { s -> s.unrelatedOrAmbiguousGuardsAndOverBudgetInputsCannotBeInstalled() }
        runIsolated { s -> s.relocatedAndFlattenedRootClassesSelectStructurallyAndSurviveCacheRoundTrip() }
        runIsolated { s -> s.disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan() }
        runIsolated { s -> s.sponsorQueriesShareOnePlannedBatchAndPreserveCallerAssociations() }
        runIsolated { s -> s.cacheRejectsUnboundedAndForeignNames() }
    }
}
