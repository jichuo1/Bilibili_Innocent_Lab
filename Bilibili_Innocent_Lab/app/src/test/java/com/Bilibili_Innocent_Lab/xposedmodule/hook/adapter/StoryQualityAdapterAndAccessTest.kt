package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3StoryPlayer
import FlatStoryQualityFacade
import FlatStoryQualityWithInteger
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIconsFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.video.story.action.LegacyToggle
import com.bilibili.video.story.player.quality.OtherStoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.jvm.internal.DefaultConstructorMarker

class StoryQualityAdapterAndAccessTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun storyQualityLocatorPreferenceAndLegacyDanmakuAdapter() {
        runLocatorCases()
        runPreferenceAccessCases()
        runLegacyStoryDanmakuCases()
    }

    private fun runLocatorCases() {
        val integer = StoryQualityFacade::class.java.getDeclaredMethod("integerDefault")
        val policy = StoryQualityFacade::class.java.getDeclaredMethod("policy")
        val resource = StoryQualityFacade::class.java.getDeclaredMethod("resource", MediaResource::class.java)
        val match = StoryQualityService::class.java.getDeclaredMethod("matchPreference", Continuation::class.java)
        fun point(method: Method) = VersionAdapter.HookPoint(method.declaringClass.name, method.name,
            method.parameterTypes.map(Class<*>::getName))
        fun complete() = requireNotNull(StoryQualityLocator.direct(loader)).copy(
            integerDefaultMethod = point(integer), policyMethod = point(policy),
            resourceSelectionMethod = point(resource), matchPreferenceMethod = point(match))

        fun session(
            defaults: List<Method> = listOf(integer, policy, resource),
            resources: List<Method> = listOf(resource),
            matches: List<Method> = listOf(match),
            matchPolicy: Class<*> = policy.declaringClass,
            matchRelatedMethods: List<Method> = emptyList()
        ): DexAssistSession {
            val engine = DexAssistEngine { request ->
                DexAssistResult.Candidates(
                    when (request.query) {
                        DexAssistQuery.STORY_QUALITY_DEFAULT -> defaults
                        DexAssistQuery.STORY_QUALITY_RESOURCE -> resources
                        DexAssistQuery.STORY_QUALITY_MATCH -> matches + matchRelatedMethods
                        else -> emptyList()
                    },
                    relatedClasses = if (request.query == DexAssistQuery.STORY_QUALITY_MATCH)
                        matches.associateWith { listOf(matchPolicy) } else emptyMap()
                )
            }
            return DexAssistSession(engine, listOf("/base.apk"), loader, StoryQualityLocator.queries)
        }

        // stable owner resolves roles without claiming semantic completeness
        val directPoints = requireNotNull(StoryQualityLocator.direct(loader))
        assertFalse(directPoints.semanticComplete)
        assertEquals("quality", directPoints.serviceFieldName)
        assertEquals("manual", directPoints.manualMethod.methodName)
        val directAccess = requireNotNull(StoryQualityLocator.resolve(loader, directPoints))
        assertNull(directAccess.policy)
        assertEquals("getInt", directAccess.settingIntReader.name)

        // integer only is incomplete and must not skip strategy and match queries
        val intOnly = directPoints.copy(integerDefaultMethod = point(integer))
        assertFalse(intOnly.semanticComplete)
        assertEquals(StoryQualityLocator.queries, StoryQualityLocator.planned(loader, intOnly, true, false))

        // old integer strategy resource and private continuation match coexist
        val coexisting = requireNotNull(StoryQualityLocator.locate(loader, session(), null, StoryQualityLocator.queries))
        assertTrue(coexisting.semanticComplete)
        assertEquals(point(integer), coexisting.integerDefaultMethod)
        val resolved = requireNotNull(StoryQualityLocator.resolve(loader, coexisting))
        assertEquals(match, resolved.matchPreference)
        assertEquals(80, resolved.preference.qualityOf(resolved.preference.fixed(80)))

        // modern strategy is complete without an integer boundary
        val modern = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = listOf(policy)), null, StoryQualityLocator.queries))
        assertTrue(modern.semanticComplete)
        assertNull(modern.integerDefaultMethod)

        // root package strategy is accepted only as a complete match related family
        val owner = FlatStoryQualityFacade::class.java
        val flatPolicy = owner.getDeclaredMethod("policy")
        val flatResource = owner.getDeclaredMethod("resource", MediaResource::class.java)
        fun locateFlat(related: List<Method>) = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = emptyList(), resources = emptyList(), matchPolicy = owner, matchRelatedMethods = related),
            null, StoryQualityLocator.queries))

        val flatPoints = locateFlat(listOf(flatPolicy, flatResource))
        assertTrue(flatPoints.semanticComplete)
        assertNull(flatPoints.integerDefaultMethod)
        assertEquals(flatPoints, StoryQualityPoints.fromJson(flatPoints.toJson()))
        val resolvedFlat = requireNotNull(StoryQualityLocator.resolve(loader, flatPoints))
        assertEquals(flatPolicy, resolvedFlat.policy)
        assertEquals(flatResource, resolvedFlat.resourceSelection)
        assertFalse(locateFlat(listOf(flatPolicy, resource)).semanticComplete)
        assertNull(StoryQualityPoints.fromJson(flatPoints.copy(resourceSelectionMethod = null).toJson()))

        val flatWithIntOwner = FlatStoryQualityWithInteger::class.java
        val flatInt = flatWithIntOwner.getDeclaredMethod("integerDefault")
        val flatIntPolicy = flatWithIntOwner.getDeclaredMethod("policy")
        val flatIntResource = flatWithIntOwner.getDeclaredMethod("resource", MediaResource::class.java)
        val combined = complete().copy(
            integerDefaultMethod = point(flatInt),
            policyMethod = point(flatIntPolicy),
            resourceSelectionMethod = point(flatIntResource),
            matchPreferenceMethod = point(match)
        )
        assertTrue(combined.isStructurallyValid())
        assertTrue(combined.semanticComplete)
        assertEquals(combined, StoryQualityPoints.fromJson(combined.toJson()))
        val resolvedCombined = requireNotNull(StoryQualityLocator.resolve(loader, combined))
        assertEquals(flatInt, resolvedCombined.integerDefault)

        // ambiguous policies and resource from another owner remain incomplete
        val other = OtherStoryQualityFacade::class.java.getDeclaredMethod("policy")
        val ambiguous = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = listOf(policy, other)), null, StoryQualityLocator.queries))
        assertNull(ambiguous.policyMethod)
        assertFalse(ambiguous.semanticComplete)
        val otherResource = OtherStoryQualityFacade::class.java.getDeclaredMethod("resource", MediaResource::class.java)
        val mismatch = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = listOf(policy), resources = listOf(otherResource)), null, StoryQualityLocator.queries))
        assertNull(mismatch.resourceSelectionMethod)
        assertFalse(mismatch.semanticComplete)

        // disabled and attempted missing families do not query while ordinary quality is independent
        assertTrue(StoryQualityLocator.planned(loader, null, false, false).isEmpty())
        assertTrue(StoryQualityLocator.planned(loader, null, true, true).isEmpty())
        assertTrue(StoryQualityLocator.planned(loader, complete(), true, false).isEmpty())
        val global = VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("ut1.h", "a", emptyList()))
        assertNotNull(global.defaultQualityMethod)
        assertEquals(StoryQualityLocator.queries, StoryQualityLocator.planned(loader, global.story, true, false))

        // additive story json round trips and legacy global json remains readable
        val story = complete()
        assertEquals(story, StoryQualityPoints.fromJson(story.toJson()))
        assertEquals(global, VersionAdapter.PlayerQualityPoints.fromJson(global.toJson()))
        val storyOnly = VersionAdapter.PlayerQualityPoints(null, story = story)
        assertEquals(storyOnly, VersionAdapter.PlayerQualityPoints.fromJson(storyOnly.toJson()))
        val corrupt = global.toJson().put("story", JSONObject().put("preference", "bad"))
        val fallback = VersionAdapter.PlayerQualityPoints.fromJson(corrupt)
        assertEquals(global.defaultQualityMethod, fallback.defaultQualityMethod)
        assertNull(fallback.story)

        // rejects untyped or inconsistent cached roles while preserving the global boundary
        val badManual = story.toJson().apply { getJSONObject("manual").remove("params") }
        assertNull(StoryQualityPoints.fromJson(badManual))
        assertNull(StoryQualityLocator.validated(loader, story.copy(serviceFieldName = "wrong")))
        val badPolicy = story.copy(policyMethod = point(OtherStoryQualityFacade::class.java.getDeclaredMethod("policy")))
        assertNull(StoryQualityPoints.fromJson(badPolicy.toJson()))
        val changedOwner = requireNotNull(StoryQualityLocator.direct(loader)).copy(
            policyMethod = point(OtherStoryQualityFacade::class.java.getDeclaredMethod("policy")))
        val isolated = requireNotNull(StoryQualityLocator.merge(changedOwner, story))
        assertNull(isolated.resourceSelectionMethod)
        assertNull(isolated.matchPreferenceMethod)
        assertNull(isolated.integerDefaultMethod)
        val merged = VersionAdapter.mergePlayerQuality(
            VersionAdapter.PlayerQualityPoints(null, story = requireNotNull(StoryQualityLocator.direct(loader))),
            VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("ut1.h", "a", emptyList()), story = story))
        assertEquals("ut1.h", merged?.defaultQualityMethod?.className)
        assertEquals(story, merged?.story)

        // incomplete cached family plans only its missing semantic role
        val incomplete = complete().copy(matchPreferenceMethod = null)
        assertEquals(setOf(DexAssistQuery.STORY_QUALITY_MATCH), StoryQualityLocator.planned(loader, incomplete, true, false))
        val invalidReturn = OtherStoryQualityFacade::class.java.getDeclaredMethod("wrongInteger")
        assertFalse(StoryQualityLocator.integerAbi(invalidReturn))
        val missingMatch = requireNotNull(StoryQualityLocator.locate(loader, session(matches = emptyList()), null, StoryQualityLocator.queries))
        assertFalse(missingMatch.semanticComplete)

        // old continuation match requires the same semantic policy owner without inline settings
        val old = requireNotNull(StoryQualityLocator.locate(loader, session(), null, StoryQualityLocator.queries))
        assertEquals(listOf(StoryQualityLocator.CONTINUATION), old.matchPreferenceMethod?.paramClassNames)
        assertTrue(old.semanticComplete)
        val mismatchedMatch = requireNotNull(StoryQualityLocator.locate(loader,
            session(matchPolicy = OtherStoryQualityFacade::class.java), null, StoryQualityLocator.queries))
        assertNull(mismatchedMatch.matchPreferenceMethod)
        assertFalse(mismatchedMatch.semanticComplete)
    }

    private fun runPreferenceAccessCases() {
        fun resolve(base: Class<*>, hostLoader: ClassLoader = loader) =
            StoryQualityPreferenceAccess.resolve(hostLoader, base)

        val realAccess = requireNotNull(resolve(com.bilibili.player.tangram.basic.d::class.java))
        assertSame(com.bilibili.player.tangram.basic.d.a.a, realAccess.auto)
        for (qn in listOf(0, 16, 32, 64, 80, 127)) {
            val fixed = requireNotNull(realAccess.fixed(qn))
            assertEquals(qn, realAccess.qualityOf(fixed))
            assertFalse(realAccess.isAuto(fixed))
        }
        assertNull(realAccess.fixed(-1))

        assertNull(resolve(IndirectInterface::class.java))
        assertNull(resolve(AdditionalInterface::class.java))
        assertNull(resolve(ChangedInterface::class.java))

        val plainAccess = requireNotNull(resolve(Plain::class.java))
        assertSame(Plain.Automatic, plainAccess.auto)
        assertTrue(plainAccess.isAuto(Plain.Automatic))
        for (qn in listOf(0, 16, 32, 64, 74, 80, 112, 116, 120, 127)) {
            val selected = requireNotNull(plainAccess.fixed(qn))
            assertEquals(qn, plainAccess.qualityOf(selected))
            assertFalse(plainAccess.isAuto(selected))
        }
        assertNull(plainAccess.fixed(-1))
        assertNull(plainAccess.qualityOf(plainAccess.auto))
        assertNull(plainAccess.qualityOf(Any()))
        assertNull(plainAccess.qualityOf(null))

        val reversedAccess = requireNotNull(resolve(Reversed::class.java))
        assertSame(Reversed.b, reversedAccess.auto)
        assertEquals(80, reversedAccess.qualityOf(reversedAccess.fixed(80)))

        val bridgedAccess = requireNotNull(resolve(Bridged::class.java))
        assertEquals(116, bridgedAccess.qualityOf(bridgedAccess.fixed(116)))
        assertSame(Bridged.Automatic, bridgedAccess.auto)

        val markerAccess = requireNotNull(resolve(MarkerOnly::class.java))
        assertEquals(120, markerAccess.qualityOf(markerAccess.fixed(120)))
        val hidesMarker = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "kotlin.jvm.internal.DefaultConstructorMarker") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(resolve(MarkerOnly::class.java, hidesMarker))

        val optimizedAccess = requireNotNull(resolve(Optimized::class.java))
        assertSame(Optimized.Automatic, optimizedAccess.auto)
        assertEquals(80, optimizedAccess.qualityOf(optimizedAccess.fixed(80)))
        assertNull(resolve(InheritedState::class.java))

        assertNull(resolve(ChangedQn::class.java))
        assertNull(resolve(InconsistentBridge::class.java))
        assertNull(resolve(MultipleFixed::class.java))
        assertNull(resolve(MultipleAuto::class.java))
        assertNull(resolve(UnknownSubtype::class.java))
        assertNull(resolve(Any::class.java))
        assertNull(resolve(WrongField::class.java))
        assertNull(resolve(WrongFactory::class.java))
        assertNull(resolve(MutableQn::class.java))

        val rejectsBase = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == Plain::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val rejectsChild = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == Plain.Selected::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(resolve(Plain::class.java, rejectsBase))
        assertNull(resolve(Plain::class.java, rejectsChild))
    }

    private fun runLegacyStoryDanmakuCases() {
        val renamedLoader = TestRenamingClassLoader(
            parent = loader,
            templates = mapOf(
                LegacyStoryDanmakuAccess.OWNER to TemplateAmbiguousStoryAbsController::class.java,
                "FlatPhase3StoryPlayer" to FlatPhase3StoryPlayer::class.java
            ),
            replacements = mapOf(
                TemplatePlaceholderStoryPlayer::class.java.name.replace('.', '/') to "FlatPhase3StoryPlayer"
            ),
            hiddenNames = setOf("com.bilibili.video.story.action.widget.StoryDanmakuToggleWidget")
        )

        assertTrue(LegacyStoryDanmakuAdapter.applicable(renamedLoader, LegacyToggle::class.java))
        assertNull(LegacyStoryDanmakuAdapter.direct(renamedLoader, LegacyToggle::class.java))
        assertTrue(LegacyStoryDanmakuAdapter.needsQuery(renamedLoader, null, enabled = true, attempted = false, LegacyToggle::class.java))

        val owner = renamedLoader.loadClass(LegacyStoryDanmakuAccess.OWNER)
        val k0Method = owner.declaredMethods.single { it.name == "k0" }
        val b0Method = owner.declaredMethods.single { it.name == "b0" }

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = listOf(k0Method)) },
            listOf("base.apk"),
            renamedLoader,
            setOf(LegacyStoryDanmakuAdapter.query)
        )
        val located = LegacyStoryDanmakuAdapter.locate(
            renamedLoader, session, enabled = true, cached = null, planned = true, toggleType = LegacyToggle::class.java
        )
        assertEquals(VersionAdapter.HookPoint(LegacyStoryDanmakuAccess.OWNER, "k0", listOf("FlatPhase3StoryPlayer", "int")), located)

        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = listOf(k0Method, b0Method)) },
            listOf("base.apk"),
            renamedLoader,
            setOf(LegacyStoryDanmakuAdapter.query)
        )
        assertNull(
            LegacyStoryDanmakuAdapter.locate(
                renamedLoader, ambigSession, enabled = true, cached = null, planned = true, toggleType = LegacyToggle::class.java
            )
        )

        val baseJson = VersionAdapterTest().result().copy(legacyStoryDanmakuBind = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.legacyStoryDanmakuBind)
        assertNull(
            AdaptResult.fromJson(
                JSONObject(baseJson.toString()).put(
                    "legacy_story_danmaku_bind",
                    VersionAdapter.HookPoint("evil.Owner", "k0", listOf("FlatPhase3StoryPlayer", "int")).toJson()
                )
            )
        )

        val ref = AtomicReference<VersionAdapter.HookPoint?>(null)
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = renamedLoader,
            hookPoints = HookPointRegistry(renamedLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val installer = StoryActionIconsFeatureInstaller(
            hidden = setOf(StoryActionIcon.DANMAKU_TOGGLE),
            legacyResolver = { cl -> LegacyStoryDanmakuAccess.resolve(cl, LegacyToggle::class.java, ref.get()) }
        )
        installer.install(env)
        assertTrue(installer.requiresAdaptationRetry)

        ref.set(located)
        val pass2 = installer.install(env)
        assertTrue(pass2 is FeatureInstallResult.Installed && pass2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(1, registrar.registrationAttempts.count { it.first == "story_action_icons.legacy_danmaku.setter" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "story_action_icons.legacy_danmaku.bind" })
    }

    interface IndirectInterface {
        object Automatic : IndirectParent()
        class Selected(val qn: Int) : IndirectInterface
    }
    open class IndirectParent : IndirectInterface

    interface ExtraMarker
    interface AdditionalInterface {
        object Automatic : AdditionalInterface, ExtraMarker
        class Selected(val qn: Int) : AdditionalInterface
    }

    interface ChangedInterface {
        object Automatic : ChangedInterface
        class Selected(qn: Int) : ChangedInterface { val value = qn + 1 }
    }
    abstract class Plain {
        object Automatic : Plain()
        class Selected(val qn: Int) : Plain()
        class Unrelated
    }

    abstract class Reversed {
        class a(val value: Int) : Reversed()
        object b : Reversed()
    }

    abstract class Bridged {
        object Automatic : Bridged()
        class Selected private constructor(val qn: Int) : Bridged() {
            constructor(qn: Int, marker: DefaultConstructorMarker?) : this(qn)
        }
    }

    abstract class MarkerOnly {
        object Automatic : MarkerOnly()
        class Selected(val qn: Int, marker: DefaultConstructorMarker?) : MarkerOnly()
    }

    sealed interface Optimized {
        object Automatic : Optimized
        class Selected(val qn: Int) : Optimized
    }

    interface InheritedState {
        object Automatic : InheritedState
        open class Parent(val hidden: Int)
        class Selected(val qn: Int) : Parent(1), InheritedState
    }

    abstract class ChangedQn {
        object Automatic : ChangedQn()
        class Selected(qn: Int) : ChangedQn() { val value = qn + 1 }
    }

    abstract class InconsistentBridge {
        object Automatic : InconsistentBridge()
        class Selected private constructor(val qn: Int) : InconsistentBridge() {
            constructor(qn: Int, marker: DefaultConstructorMarker?) : this(qn + 1)
        }
    }

    abstract class MultipleFixed {
        object Automatic : MultipleFixed()
        class First(val qn: Int) : MultipleFixed()
        class Second(val qn: Int) : MultipleFixed()
    }

    abstract class MultipleAuto {
        object First : MultipleAuto()
        object Second : MultipleAuto()
        class Selected(val qn: Int) : MultipleAuto()
    }

    abstract class UnknownSubtype {
        object Automatic : UnknownSubtype()
        class Selected(val qn: Int) : UnknownSubtype()
        class Unknown(val flag: Boolean) : UnknownSubtype()
    }

    abstract class WrongField {
        object Automatic : WrongField()
        class Selected(val qn: Long) : WrongField()
    }

    abstract class WrongFactory {
        object Automatic : WrongFactory()
        class Selected(text: String) : WrongFactory() { val qn = text.length }
    }

    abstract class MutableQn {
        object Automatic : MutableQn()
        class Selected(var qn: Int) : MutableQn()
    }

    class TemplatePlaceholderStoryPlayer

    class TemplateAmbiguousStoryAbsController {
        @JvmField
        var toggle: LegacyToggle? = null

        @JvmField
        var player: TemplatePlaceholderStoryPlayer? = null

        fun getMDanmakuToggle(): LegacyToggle? = toggle
        fun setMDanmakuToggle(value: LegacyToggle?) {
            toggle = value
        }

        fun getMPlayer(): TemplatePlaceholderStoryPlayer? = player

        @Suppress("UNUSED_PARAMETER")
        fun b0(player: TemplatePlaceholderStoryPlayer, pos: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun k0(player: TemplatePlaceholderStoryPlayer, pos: Int) = Unit
    }
}
