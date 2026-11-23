package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import com.bilibili.app.gemini.base.player.SponsorBusiness
import com.bilibili.ship.theseus.keel.player.SponsorPlayable
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import com.bilibili.ship.theseus.keel.player.`TheseusKeelPlayer$runPlayable$1`
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SponsorPlayerAccessTest {
    private val loader = javaClass.classLoader!!
    private fun playable(cid: Long = 1, avid: Long = 2, bvid: String = "BV14741127BN",
        business: SponsorBusiness = SponsorBusiness.UGC) = object : SponsorPlayable {
        override fun values() = GeminiCommonPlayableParams(bvid, cid, avid, business)
    }

    fun inheritedBusinessGetterAndExactTwoArgumentSeekAreResolved() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        assertEquals(7, requireNotNull(access.scope).parameterCount)
        val container = requireNotNull(access.containerScope)
        assertEquals(2, container.containerIndex); assertEquals(3, container.contextIndex)
        assertEquals("getPlayerCoreService", container.core.name)
        assertEquals(SponsorPlayerAccess.CONTAINER_INTERFACE, container.core.declaringClass.name)
        assertEquals(listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), access.seek.parameterTypes.toList())
        assertEquals(SponsorVideoId("BV14741127BN", 1), access.video(TheseusKeelPlayer(playable())))
        assertEquals(1, access.wrapperOwnerIndex); assertEquals(0, access.wrapperCoreIndex)
    }

    fun identityAlwaysReadsTheActivePartAndRejectsNonUgcOrInvalidMedia() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val owner = TheseusKeelPlayer(playable())
        owner.current = playable(cid = 3)
        assertEquals(SponsorVideoId("BV14741127BN", 3), access.video(owner))
        owner.current = playable(cid = 42_072_543_108L, bvid = "BV1FnhC6qEck")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(owner))
        for (invalid in listOf(playable(business = SponsorBusiness.PGC), playable(cid = 0),
            playable(avid = 0), playable(bvid = "invalid"), null)) {
            owner.current = invalid
            assertNull(access.video(owner))
        }
    }

    fun missingAnyRequiredHostBoundaryDisablesTheFeature() {
        for (missing in listOf(SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.PARAMS_CLASS,
            SponsorPlayerAccess.CORE_CLASS, SponsorPlayerAccess.WRAPPER_CLASS,
            "tv.danmaku.biliplayerv2.service.PlayerSeekObserver")) {
            val incomplete = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == missing) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNull(missing, SponsorPlayerAccess.resolve(incomplete))
        }
    }

    fun emptyUgcBvidUsesTheCurrentAidWithoutCachingThePartOrAcceptingMalformedIdentity() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val owner = TheseusKeelPlayer(playable(avid = 117_312_082_479_704L,
            cid = 42_072_543_108L, bvid = ""))
        val diagnostics = mutableListOf<String>()
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(owner, diagnostics::add))
        assertEquals(listOf("aid-derived"), diagnostics)
        owner.current = playable(avid = 117_312_082_479_704L, cid = 42_072_543_109L, bvid = "")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_109L), access.video(owner))
        owner.current = playable(avid = 170_001L, cid = 1, bvid = "")
        assertEquals(SponsorVideoId("BV17x411w7KC", 1), access.video(owner))
        for (invalid in listOf(playable(avid = 170_001L, bvid = "broken"),
            playable(avid = 170_001L, bvid = " "), playable(avid = 0, bvid = ""),
            playable(avid = 2_251_799_813_685_248L, bvid = ""), playable(cid = 0, bvid = ""),
            playable(business = SponsorBusiness.PGC, bvid = ""))) {
            owner.current = invalid
            assertNull(access.video(owner))
        }
    }

    fun seekGuardRequiresTheConcreteCoroutineCloneAndHostUnit() {
        val player = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val seek = requireNotNull(SponsorSeekAccess.resolve(loader, player))
        assertSame(Unit, seek.unit)
        assertEquals("create", seek.create.name)
        assertEquals("invokeSuspend", seek.invoke.name)
        assertEquals(4, seek.constructor.parameterCount)
    }

    fun activePlaybackCanBeBoundWhileRunPlayableIsSuspendedButNotWhileWaitingForItsMutex() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val first = playable(); val next = playable(cid = 2)
        val owner = TheseusKeelPlayer(first)
        assertTrue(access.bound(owner, first, null))
        assertFalse(access.bound(owner, next, null))
        val resume = `TheseusKeelPlayer$runPlayable$1`(owner)
        resume.label = 1
        assertFalse(access.bound(owner, null, resume))
        owner.current = next; resume.label = 2
        assertTrue(access.bound(owner, null, resume))
        assertFalse(access.bound(TheseusKeelPlayer(next), null, resume))
        owner.current = null
        assertFalse(access.bound(owner, null, resume))
    }

    fun eitherExplicitActivityBindingRouteCanSupportAHostButNeitherCannot() {
        fun hiding(vararg classes: String) = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in classes) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val direct = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS)))
        assertNull(direct.scope); assertNotNull(direct.containerScope)
        val fallback = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))
        assertNotNull(fallback.scope); assertNull(fallback.containerScope)
        assertNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS, SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))
    }

    private class ReorderedWrapper(val owner: TheseusKeelPlayer, val scope: CoroutineScope,
        val core: IPlayerCoreService) : IPlayerCoreService by core
    private class AmbiguousWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
        scope: CoroutineScope) : IPlayerCoreService by core {
        constructor(owner: TheseusKeelPlayer, scope: CoroutineScope, core: IPlayerCoreService) : this(core, owner, scope)
    }
    private class ExtraArgumentWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
        scope: CoroutineScope, extra: Any) : IPlayerCoreService by core

    fun reordered912ConstructorCapturesTheActualOwnerAndCoreAndRejectsAmbiguousShapes() {
        val core = object : IPlayerCoreService {}
        val owner = TheseusKeelPlayer(playable())
        val scope = CoroutineScope(EmptyCoroutineContext)
        val binding = requireNotNull(SponsorPlayerAccess.resolveWrapper(ReorderedWrapper::class.java,
            IPlayerCoreService::class.java, TheseusKeelPlayer::class.java))
        val args = arrayOf(owner, scope, core)
        val constructed = binding.constructor.newInstance(*args) as ReorderedWrapper
        assertSame(constructed.owner, args[binding.ownerIndex])
        assertSame(constructed.core, args[binding.coreIndex])
        for (type in listOf(AmbiguousWrapper::class.java, ExtraArgumentWrapper::class.java, String::class.java)) {
            assertNull(SponsorPlayerAccess.resolveWrapper(type, IPlayerCoreService::class.java, TheseusKeelPlayer::class.java))
        }
    }

    private interface DistractorInterfaceA { fun z(): String }
    private interface DistractorInterfaceB { fun y(): Int }
    private interface FakeContinuationImpl
    private interface Playable920 { fun t(): GeminiCommonPlayableParams }
    private interface SecondPlayable920 { fun u(): GeminiCommonPlayableParams }

    @Suppress("unused")
    private class Owner920(@JvmField var current: Playable920? = null) {
        fun e(a: DistractorInterfaceA, c: Continuation<Any?>): Any? = null
        fun o(b: DistractorInterfaceB, c: Continuation<Any?>): Any? = null
        fun k(): Playable920? = current
        fun r(p: Playable920, c: FakeContinuationImpl): Any? = null
    }

    @Suppress("unused")
    private class AmbiguousPlayableOwner {
        fun a(): Playable920? = null
        fun b(): SecondPlayable920? = null
        fun r(p: Playable920, c: FakeContinuationImpl): Any? = null
        fun s(p: SecondPlayable920, c: FakeContinuationImpl): Any? = null
    }

    @Suppress("unused")
    private class Wrapper920(core: IPlayerCoreService, owner: Owner920, scope: CoroutineScope) : IPlayerCoreService by core

    @Suppress("unused")
    private class Seek920(owner: Owner920, position: Int, accurate: Boolean, continuation: Continuation<Any?>?) {
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
        val pattern = Regex(replacements.keys.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape))
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            output.writeByte(tag)
            if (tag == 1) {
                val utf = pattern.replace(input.readUTF()) { match -> replacements.getValue(match.value) }
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

    private fun loaderWithRelocatedNames(
        ownerTarget: String,
        playableTarget: String,
        continuationTarget: String = SponsorPlayerAccess.RUN_CLASS,
        wrapperTarget: String = SponsorPlayerAccess.WRAPPER_CLASS,
        seekTarget: String = SponsorSeekAccess.CLASS,
        ambiguousOwner: Boolean = false
    ): ClassLoader {
        fun internal(cls: Class<*>) = cls.name.replace('.', '/')
        val ownerTemplate = if (ambiguousOwner) AmbiguousPlayableOwner::class.java else Owner920::class.java
        val ownerInternal = ownerTarget.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "l723" to DistractorInterfaceA::class.java,
            "c723" to DistractorInterfaceB::class.java,
            playableTarget to Playable920::class.java,
            "nmq2" to SecondPlayable920::class.java,
            ownerTarget to ownerTemplate,
            continuationTarget to com.bilibili.ship.theseus.keel.player.RenamedSponsorContinuation::class.java,
            wrapperTarget to Wrapper920::class.java,
            seekTarget to Seek920::class.java
        )
        val replacements = linkedMapOf(
            internal(FakeContinuationImpl::class.java) to "kotlin/coroutines/jvm/internal/ContinuationImpl",
            internal(DistractorInterfaceA::class.java) to "l723",
            internal(DistractorInterfaceB::class.java) to "c723",
            internal(Playable920::class.java) to playableTarget.replace('.', '/'),
            internal(SecondPlayable920::class.java) to "nmq2",
            internal(ownerTemplate) to ownerInternal,
            internal(Owner920::class.java) to ownerInternal,
            "com/bilibili/ship/theseus/keel/player/TheseusKeelPlayer" to ownerInternal,
            internal(com.bilibili.ship.theseus.keel.player.RenamedSponsorContinuation::class.java) to continuationTarget.replace('.', '/'),
            internal(Wrapper920::class.java) to wrapperTarget.replace('.', '/'),
            internal(Seek920::class.java) to seekTarget.replace('.', '/')
        )
        val hidden = setOf(
            SponsorPlayerAccess.RUN_CLASS,
            SponsorPlayerAccess.WRAPPER_CLASS,
            SponsorSeekAccess.CLASS
        ) - templates.keys
        return object : ClassLoader(loader) {
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
    }

    fun resolves920AndArbitraryRootPackagePlayableStructurallyAndRejectsAmbiguousOwners() {
        for ((ownerName, playableName) in listOf(
            "com.bilibili.ship.theseus.keel.player.b" to "spq2",
            "com.bilibili.ship.theseus.keel.player.b" to "bpq2",
            "k0" to "q9z0"
        )) {
            val l = loaderWithRelocatedNames(ownerName, playableName)
            val access = requireNotNull(SponsorPlayerAccess.resolve(l))
            val ownerClass = l.loadClass(ownerName)
            val playableClass = l.loadClass(playableName)
            val playableProxy = java.lang.reflect.Proxy.newProxyInstance(l, arrayOf(playableClass)) { _, method, _ ->
                if (method.name == "t") GeminiCommonPlayableParams("BV14741127BN", 9L, 2L, SponsorBusiness.UGC) else null
            }
            val instance = ownerClass.getDeclaredConstructor(playableClass).apply { isAccessible = true }
                .newInstance(playableProxy)
            assertEquals(SponsorVideoId("BV14741127BN", 9L), access.video(instance))
            assertTrue(access.bound(instance, playableProxy, null))
        }

        val ambigLoader = loaderWithRelocatedNames(
            ownerTarget = "com.bilibili.ship.theseus.keel.player.b",
            playableTarget = "spq2",
            ambiguousOwner = true
        )
        assertNull(SponsorPlayerAccess.resolve(ambigLoader))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorPlayerAccessTest) -> Unit) {
            val s = SponsorPlayerAccessTest()
            block(s)
        }
        runIsolated { s -> s.inheritedBusinessGetterAndExactTwoArgumentSeekAreResolved() }
        runIsolated { s -> s.identityAlwaysReadsTheActivePartAndRejectsNonUgcOrInvalidMedia() }
        runIsolated { s -> s.missingAnyRequiredHostBoundaryDisablesTheFeature() }
        runIsolated { s -> s.emptyUgcBvidUsesTheCurrentAidWithoutCachingThePartOrAcceptingMalformedIdentity() }
        runIsolated { s -> s.seekGuardRequiresTheConcreteCoroutineCloneAndHostUnit() }
        runIsolated { s -> s.activePlaybackCanBeBoundWhileRunPlayableIsSuspendedButNotWhileWaitingForItsMutex() }
        runIsolated { s -> s.eitherExplicitActivityBindingRouteCanSupportAHostButNeitherCannot() }
        runIsolated { s -> s.reordered912ConstructorCapturesTheActualOwnerAndCoreAndRejectsAmbiguousShapes() }
        runIsolated { s -> s.resolves920AndArbitraryRootPackagePlayableStructurallyAndRejectsAmbiguousOwners() }
    }
}
