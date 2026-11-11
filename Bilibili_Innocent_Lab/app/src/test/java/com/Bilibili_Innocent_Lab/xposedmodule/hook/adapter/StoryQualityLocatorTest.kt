package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatStoryQualityFacade
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.video.story.player.quality.OtherStoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityService
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoryQualityLocatorTest {
    private val loader = javaClass.classLoader!!
    private val integer = StoryQualityFacade::class.java.getDeclaredMethod("integerDefault")
    private val policy = StoryQualityFacade::class.java.getDeclaredMethod("policy")
    private val resource = StoryQualityFacade::class.java.getDeclaredMethod("resource", MediaResource::class.java)
    private val match = StoryQualityService::class.java.getDeclaredMethod("matchPreference", Continuation::class.java)
    private fun point(method: Method) = VersionAdapter.HookPoint(method.declaringClass.name, method.name,
        method.parameterTypes.map(Class<*>::getName))
    private fun complete() = requireNotNull(StoryQualityLocator.direct(loader)).copy(
        integerDefaultMethod = point(integer), policyMethod = point(policy),
        resourceSelectionMethod = point(resource), matchPreferenceMethod = point(match))

    private fun session(defaults: List<Method> = listOf(integer, policy, resource),
                        resources: List<Method> = listOf(resource), matches: List<Method> = listOf(match),
                        matchPolicy: Class<*> = policy.declaringClass,
                        matchRelatedMethods: List<Method> = emptyList()): DexAssistSession {
        val engine = DexAssistEngine { request -> DexAssistResult.Candidates(when (request.query) {
            DexAssistQuery.STORY_QUALITY_DEFAULT -> defaults
            DexAssistQuery.STORY_QUALITY_RESOURCE -> resources
            DexAssistQuery.STORY_QUALITY_MATCH -> matches + matchRelatedMethods
            else -> emptyList()
        }, relatedClasses = if (request.query == DexAssistQuery.STORY_QUALITY_MATCH)
            matches.associateWith { listOf(matchPolicy) } else emptyMap()) }
        return DexAssistSession(engine, listOf("/base.apk"), loader, StoryQualityLocator.queries)
    }

    @Test fun `stable owner resolves roles without claiming semantic completeness`() {
        val points = requireNotNull(StoryQualityLocator.direct(loader))
        assertFalse(points.semanticComplete)
        assertEquals("quality", points.serviceFieldName)
        assertEquals("manual", points.manualMethod.methodName)
        val access = requireNotNull(StoryQualityLocator.resolve(loader, points))
        assertNull(access.policy)
        assertEquals("getInt", access.settingIntReader.name)
    }

    @Test fun `integer only is incomplete and must not skip strategy and match queries`() {
        val points = requireNotNull(StoryQualityLocator.direct(loader)).copy(integerDefaultMethod = point(integer))
        assertFalse(points.semanticComplete)
        assertEquals(StoryQualityLocator.queries, StoryQualityLocator.planned(loader, points, true, false))
    }

    @Test fun `old integer strategy resource and private continuation match coexist`() {
        val points = requireNotNull(StoryQualityLocator.locate(loader, session(), null, StoryQualityLocator.queries))
        assertTrue(points.semanticComplete)
        assertEquals(point(integer), points.integerDefaultMethod)
        val resolved = requireNotNull(StoryQualityLocator.resolve(loader, points))
        assertEquals(match, resolved.matchPreference)
        assertEquals(80, resolved.preference.qualityOf(resolved.preference.fixed(80)))
    }

    @Test fun `modern strategy is complete without an integer boundary`() {
        val points = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = listOf(policy)), null, StoryQualityLocator.queries))
        assertTrue(points.semanticComplete)
        assertNull(points.integerDefaultMethod)
    }

    @Test fun `root package strategy is accepted only as a complete match related family`() {
        val owner = FlatStoryQualityFacade::class.java
        val flatPolicy = owner.getDeclaredMethod("policy")
        val flatResource = owner.getDeclaredMethod("resource", MediaResource::class.java)
        fun locate(related: List<Method>) = requireNotNull(StoryQualityLocator.locate(loader,
            session(defaults = emptyList(), resources = emptyList(), matchPolicy = owner, matchRelatedMethods = related),
            null, StoryQualityLocator.queries))

        val points = locate(listOf(flatPolicy, flatResource))
        assertTrue(points.semanticComplete)
        assertNull(points.integerDefaultMethod)
        assertEquals(points, StoryQualityPoints.fromJson(points.toJson()))
        val resolved = requireNotNull(StoryQualityLocator.resolve(loader, points))
        assertEquals(flatPolicy, resolved.policy)
        assertEquals(flatResource, resolved.resourceSelection)
        assertFalse(locate(listOf(flatPolicy, resource)).semanticComplete)
        assertNull(StoryQualityPoints.fromJson(points.copy(resourceSelectionMethod = null).toJson()))
    }

    @Test fun `ambiguous policies and resource from another owner remain incomplete`() {
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
    }

    @Test fun `disabled and attempted missing families do not query while ordinary quality is independent`() {
        assertTrue(StoryQualityLocator.planned(loader, null, false, false).isEmpty())
        assertTrue(StoryQualityLocator.planned(loader, null, true, true).isEmpty())
        assertTrue(StoryQualityLocator.planned(loader, complete(), true, false).isEmpty())
        val global = VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("ut1.h", "a", emptyList()))
        assertNotNull(global.defaultQualityMethod)
        assertEquals(StoryQualityLocator.queries, StoryQualityLocator.planned(loader, global.story, true, false))
    }

    @Test fun `additive story json round trips and legacy global json remains readable`() {
        val story = complete()
        assertEquals(story, StoryQualityPoints.fromJson(story.toJson()))
        val old = VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("ut1.h", "a", emptyList()))
        assertEquals(old, VersionAdapter.PlayerQualityPoints.fromJson(old.toJson()))
        val storyOnly = VersionAdapter.PlayerQualityPoints(null, story = story)
        assertEquals(storyOnly, VersionAdapter.PlayerQualityPoints.fromJson(storyOnly.toJson()))
        val corrupt = old.toJson().put("story", JSONObject().put("preference", "bad"))
        val fallback = VersionAdapter.PlayerQualityPoints.fromJson(corrupt)
        assertEquals(old.defaultQualityMethod, fallback.defaultQualityMethod)
        assertNull(fallback.story)
    }

    @Test fun `rejects untyped or inconsistent cached roles while preserving the global boundary`() {
        val story = complete()
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
    }

    @Test fun `incomplete cached family plans only its missing semantic role`() {
        val incomplete = complete().copy(matchPreferenceMethod = null)
        assertEquals(setOf(DexAssistQuery.STORY_QUALITY_MATCH), StoryQualityLocator.planned(loader, incomplete, true, false))
        val invalidReturn = OtherStoryQualityFacade::class.java.getDeclaredMethod("wrongInteger")
        assertFalse(StoryQualityLocator.integerAbi(invalidReturn))
        val missing = requireNotNull(StoryQualityLocator.locate(loader, session(matches = emptyList()), null, StoryQualityLocator.queries))
        assertFalse(missing.semanticComplete)
    }

    @Test fun `old continuation match requires the same semantic policy owner without inline settings`() {
        val old = requireNotNull(StoryQualityLocator.locate(loader, session(), null, StoryQualityLocator.queries))
        assertEquals(listOf(StoryQualityLocator.CONTINUATION), old.matchPreferenceMethod?.paramClassNames)
        assertTrue(old.semanticComplete)
        val mismatched = requireNotNull(StoryQualityLocator.locate(loader,
            session(matchPolicy = OtherStoryQualityFacade::class.java), null, StoryQualityLocator.queries))
        assertNull(mismatched.matchPreferenceMethod)
        assertFalse(mismatched.semanticComplete)
    }
}
