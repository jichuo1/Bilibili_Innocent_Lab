package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.dynamic.v2.*
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.*
import org.junit.Test

class DynamicFrequentVisitsTest {
    private class Harness(hide: Boolean = true, live: Boolean = false, topic: Boolean = false,
        loader: ClassLoader = DynamicFrequentVisitsTest::class.java.classLoader!!) {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val records = mutableListOf<FeatureInstallRecord>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> },
            runtimeEvidence = { id, stage, _ -> stages += id to stage }, installationEvidence = { records += it })
        val installer = DynamicPurifyFeatureInstaller(false, "", false, "", false, false, topic, live, hide)
        init { FeatureInstallCoordinator(env).installAll(listOf(installer)) }
        fun all(reply: DynAllReply) = registrar.invoke("dynamic.purify.sync.executeDynAll") { reply } as DynAllReply
        fun video(reply: DynVideoReply) = registrar.invoke("dynamic.purify.sync.executeDynVideo") { reply } as DynVideoReply
    }

    @Test fun bothTabsDropWholeContainerWithoutChangingDynamicOrTopicData() {
        val h = Harness()
        val up = CardVideoUpList(listOf(UpListItem(0,1)),listOf(UpListItem(1,2)))
        val source = DynAllReply(ups = up)
        val next = h.all(source)
        assertFalse(next.hasUpList()); assertTrue(source.hasUpList())
        assertSame(source.items,next.items); assertSame(source.unrelated,next.unrelated)
        assertTrue(next.topic); assertSame(up,source.ups)
        // Verified host composition condition: no row is added, so no placeholder remains.
        assertFalse(next.hasUpList() && next.getUpList().getListList().isNotEmpty())
        val video = DynVideoReply(ups = up)
        val nextVideo = h.video(video)
        assertFalse(nextVideo.hasVideoUpList()); assertTrue(video.hasVideoUpList())
        assertSame(video.items,nextVideo.items); assertSame(video.unrelated,nextVideo.unrelated)
        assertEquals(4,h.registrar.hooks.size)
        val result = h.records.single { it.id == "dynamic_frequent_visits_hidden" }.result as FeatureInstallResult.Installed
        assertTrue(result.complete)
        assertTrue("dynamic_frequent_visits_hidden" to FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun absentDefaultAndAlreadyFilteredRepliesKeepIdentity() {
        val h = Harness()
        val absent = DynAllReply(upPresent = false)
        assertSame(absent,h.all(absent))
        assertSame(DynAllReply.getDefaultInstance(),h.all(DynAllReply.getDefaultInstance()))
        val once = h.all(DynAllReply())
        assertSame(once,h.all(once))
    }

    @Test fun failedBuildOrNoOpClearNeverPublishesPartialChanges() {
        for (source in listOf(DynAllReply(failBuild = true),DynAllReply(failClear = true))) {
            val h = Harness(topic = true)
            assertSame(source,h.all(source))
            assertTrue(source.hasUpList()); assertTrue(source.topic)
            assertFalse("dynamic_frequent_visits_hidden" to FeatureRuntimeStage.APPLIED in h.stages)
            assertTrue("dynamic_frequent_visits_hidden" to FeatureRuntimeStage.ERROR in h.stages)
        }
    }

    @Test fun wholeRemovalDoesNotReadLiveItemOrPositionDependencies() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == UpListItem::class.java.name || name == DynamicItem::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val h = Harness(live = true,loader = loader)
        val source = DynAllReply(ups = CardVideoUpList(listOf(UpListItem(1,1,true)),failSecond = true))
        assertFalse(h.all(source).hasUpList())
        assertFalse("dynamic_up_list_live_removed" in h.installer.capabilityIds)
    }

    @Test fun disablingWholeRemovalRestoresExistingLiveFilterAndTopicRemainsIndependent() {
        val up = CardVideoUpList(listOf(UpListItem(0,1),UpListItem(1,2)))
        val liveOnly = Harness(hide = false,live = true).all(DynAllReply(ups = up))
        assertTrue(liveOnly.hasUpList()); assertEquals(1,liveOnly.ups.first.size)
        val both = Harness(topic = true).all(DynAllReply(ups = up))
        assertFalse(both.topic); assertFalse(both.hasUpList())
        assertTrue(Harness(hide = false).registrar.hooks.isEmpty())
    }

    @Test fun missingOneReplyIsPartialAndDoesNotDisableOtherTab() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == DynVideoReply::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val h = Harness(loader = loader)
        assertFalse((h.records.single { it.id == "dynamic_frequent_visits_hidden" }.result as FeatureInstallResult.Installed).complete)
        assertFalse(h.all(DynAllReply()).hasUpList())
    }

    @Test fun asyncBothTabsPreserveCallbackProtocolAndOriginalArguments() {
        val h = Harness()
        val replies = mutableListOf<Any?>(); val errors = mutableListOf<Throwable>(); var completed = 0
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { replies += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completed++ }
        }
        val failure = IllegalStateException("host")
        for ((method,source) in listOf("dynAll" to DynAllReply(),"dynVideo" to DynVideoReply())) {
            val args = arrayOf<Any?>(Any(),handler)
            h.registrar.invoke("dynamic.purify.async.$method",args=args) { actual ->
                (actual[1] as MossResponseHandler).apply { onNext(source); onNext(null); onError(failure); onCompleted() }
            }
            assertSame(handler,args[1])
        }
        assertFalse((replies[0] as DynAllReply).hasUpList())
        assertFalse((replies[2] as DynVideoReply).hasVideoUpList())
        assertNull(replies[1]); assertNull(replies[3])
        assertEquals(listOf(failure,failure),errors); assertEquals(2,completed)
    }

    @Test fun originalHostExceptionsAreNotSwallowedBySyncFilter() {
        val h = Harness(); val failure = IllegalArgumentException("host failure")
        assertSame(failure,assertThrows(IllegalArgumentException::class.java) {
            h.registrar.invoke("dynamic.purify.sync.executeDynAll") { throw failure }
        })
    }
}
