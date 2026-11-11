package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.dynamic.v2.*
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf

class DynamicRootReplyTest {
    data class KotlinReply(val message: DynAllPersonalReply)
    interface KotlinHandler { fun onNext(reply: Any?) }
    class KotlinEndpoint {
        @Suppress("UNUSED_PARAMETER")
        fun dynAllPersonal(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>,
            handler: KotlinHandler, protoBuf: ProtoBuf) = Unit
    }
    private class Harness(topicOnly: Boolean = false, loader: ClassLoader = DynamicRootReplyTest::class.java.classLoader!!) {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val records = mutableListOf<FeatureInstallRecord>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage },
            installationEvidence = { records += it })
        init {
            FeatureInstallCoordinator(env).installAll(listOf(
                DynamicPurifyFeatureInstaller(!topicOnly, "blocked", false, "", false, false, topicOnly, false)
            ))
        }
        fun all(reply: DynAllPersonalReply) = registrar.invoke("dynamic.purify.sync.executeDynAllPersonal") { reply } as DynAllPersonalReply
        fun video(reply: DynVideoPersonalReply) = registrar.invoke("dynamic.purify.sync.executeDynVideoPersonal") { reply } as DynVideoPersonalReply
        fun filtered(reply: FeedFilterReply) = registrar.invoke("dynamic.purify.sync.executeFeedFilter") { reply } as FeedFilterReply
        fun friends(reply: DynFriendReply) = registrar.invoke("dynamic.purify.sync.executeDynFriend") { reply } as DynFriendReply
    }

    @Test fun `both personal lists filter root items and retain pagination relation title and source`() {
        val h = Harness()
        val kept = DynamicItem("keep")
        val all = DynAllPersonalReply(listOf(DynamicItem("blocked"), kept))
        val video = DynVideoPersonalReply(listOf(kept, DynamicItem("blocked video")))

        val nextAll = h.all(all)
        val nextVideo = h.video(video)

        assertEquals(listOf(kept), nextAll.items)
        assertEquals(listOf(kept), nextVideo.items)
        assertSame(all.paging, nextAll.paging)
        assertSame(video.paging, nextVideo.paging)
        assertEquals("next", nextAll.getOffset())
        assertEquals("read", nextVideo.getReadOffset())
        assertTrue(nextAll.getHasMore())
        assertEquals(2, all.items.size)
        assertEquals(2, video.items.size)
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun `unchanged empty default and failed builds do not publish replacements`() {
        val h = Harness()
        val kept = DynAllPersonalReply(listOf(DynamicItem("keep")))
        assertSame(kept, h.all(kept))
        val empty = DynVideoPersonalReply()
        assertSame(empty, h.video(empty))
        assertSame(DynAllPersonalReply.getDefaultInstance(), h.all(DynAllPersonalReply.getDefaultInstance()))
        h.stages.clear()
        val failure = DynAllPersonalReply(listOf(DynamicItem("blocked")), failBuild = true)
        assertSame(failure, h.all(failure))
        assertEquals(1, failure.items.size)
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun `topic only installation counts the topic carrier and keeps unrelated endpoints untouched`() {
        val h = Harness(topicOnly = true)
        assertEquals(setOf("dynamic.purify.sync.executeDynAll", "dynamic.purify.async.dynAll"), h.registrar.hooks.keys)
        val topic = h.records.single { it.id == "dynamic_topic_list_hidden" }.result as FeatureInstallResult.Installed
        val parent = h.records.single { it.id == DynamicPurifyFeatureInstaller.ID }.result as FeatureInstallResult.Installed
        assertEquals(2, topic.hookCount)
        assertTrue(topic.complete)
        assertTrue(parent.complete)
    }

    @Test fun `filtered following feed can become empty without changing its cursor and more flag`() {
        val h = Harness()
        val source = FeedFilterReply(listOf(DynamicItem("blocked")))
        val updated = h.filtered(source)
        assertTrue(updated.items.isEmpty())
        assertSame(source.paging, updated.paging)
        assertEquals("next", updated.getOffset())
        assertTrue(updated.getHasMore())
        assertEquals(1, source.items.size)
        assertSame(updated, h.filtered(updated))
    }

    @Test fun `friend feed uses its DynList field and preserves metadata when all cards are blocked`() {
        val h = Harness()
        val source = DynFriendReply(listOf(DynamicItem("blocked")))
        val updated = h.friends(source)
        assertTrue(updated.items.isEmpty())
        assertSame(source.paging, updated.paging)
        assertEquals("next", updated.getOffset())
        assertTrue(updated.getHasMore())
        assertEquals(1, source.items.size)
        assertSame(DynFriendReply.getDefaultInstance(), h.friends(DynFriendReply.getDefaultInstance()))
    }

    @Test fun `a missing personal response remains partial while the other personal list works`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == DynVideoPersonalReply::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val h = Harness(loader = loader)
        assertEquals(0, h.all(DynAllPersonalReply(listOf(DynamicItem("blocked")))).items.size)
        val record = h.records.single { it.id == "dynamic_keyword_filter_enabled" }.result as FeatureInstallResult.Installed
        assertFalse(record.complete)
    }

    @Test fun `personal async responses use the same root transform and preserve null and other callbacks`() {
        val h = Harness()
        val replies = mutableListOf<Any?>()
        val errors = mutableListOf<Throwable>()
        var completed = 0
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { replies += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completed++ }
        }
        val error = IllegalStateException("host")
        val source = DynAllPersonalReply(listOf(DynamicItem("blocked")))
        val args = arrayOf<Any?>(Any(), handler)
        h.registrar.invoke("dynamic.purify.async.dynAllPersonal", args = args) { actual ->
            (actual[1] as MossResponseHandler).apply { onNext(source); onNext(null); onError(error); onCompleted() }
        }
        assertTrue((replies[0] as DynAllPersonalReply).items.isEmpty())
        assertNull(replies[1])
        assertEquals(listOf(error), errors)
        assertEquals(1, completed)
        assertSame(handler, args[1])
        assertEquals(1, source.items.size)
    }

    @Test fun `Kotlin root callback confirms delivery and decode failure never reports applied`() {
        for (failDecode in listOf(false, true)) {
            val h = Harness()
            val specType = DynamicPurifyFeatureInstaller::class.java.declaredClasses.single { it.simpleName == "FeedSpec" }
            val installer = DynamicPurifyFeatureInstaller(true, "blocked", false, "", false, false, false, false)
            val spec = specType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(DynAllPersonalReply::class.java.name, "executeDynAllPersonal", "dynAllPersonal")
            val feed = installer.javaClass.declaredMethods.single { it.name == "resolveFeed" }.apply { isAccessible = true }
                .invoke(installer, javaClass.classLoader, DynamicMoss::class.java, spec)
            val members = installer.javaClass.declaredMethods.single { it.name == "resolveItemMembers" }.apply { isAccessible = true }
                .invoke(installer, javaClass.classLoader)
            val confirm = installer.javaClass.declaredMethods.single { it.name == "confirmedReply" }.apply { isAccessible = true }
            val plan = DynamicPurifyPolicy.Plan(setOf("blocked"), AuthorRuleSet.EMPTY, false, false)
            val original = KotlinReply(DynAllPersonalReply(listOf(DynamicItem("blocked"))))
            val messages = ArrayList<DynAllPersonalReply>()
            fun store(message: DynAllPersonalReply): ByteArray { messages += message; return byteArrayOf((messages.size - 1).toByte()) }
            fun read(bytes: ByteArray) = messages[bytes[0].toInt()]
            val bridge = KotlinMossReplyBridge(
                encode = { store((it as KotlinReply).message) },
                parseJava = { read(it).copy(items = read(it).items.map { item -> item.copy() }, paging = read(it).paging.copy()) },
                toBytes = { store(it as DynAllPersonalReply) },
                decode = { check(!failDecode); KotlinReply(read(it).copy(items = read(it).items.map { item -> item.copy() })) }
            )
            val method = KotlinEndpoint::class.java.getDeclaredMethod("dynAllPersonal", Any::class.java,
                SerializationStrategy::class.java, DeserializationStrategy::class.java, KotlinHandler::class.java, ProtoBuf::class.java)
            val key = KotlinMossResponseHandlerProxy.Key(method, DynAllPersonalReply::class.java, this, method)
            var delivered: Any? = null
            val delegate = object : KotlinHandler {
                override fun onNext(reply: Any?) { assertFalse(FeatureRuntimeStage.APPLIED in h.stages); delivered = reply }
            }
            h.stages.clear()
            val proxy = KotlinMossResponseHandlerProxy.wrap(KotlinHandler::class.java, delegate, key, bridge,
                failed = { h.stages += FeatureRuntimeStage.ERROR }, transform = { it }, confirmedTransform = { source ->
                    confirm.invoke(installer, h.env, source, feed, members, plan) as KotlinMossResponseHandlerProxy.ConfirmedReply
                }) as KotlinHandler
            proxy.onNext(original)
            if (failDecode) {
                assertSame(original, delivered)
                assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
                assertTrue(FeatureRuntimeStage.ERROR in h.stages)
            } else {
                assertTrue((delivered as KotlinReply).message.items.isEmpty())
                assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
                h.stages.clear()
                val unchanged = KotlinReply(DynAllPersonalReply(listOf(DynamicItem("keep"))))
                proxy.onNext(unchanged)
                assertSame(unchanged, delivered)
                assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
            }
        }
    }

    @Test fun `whole UP Kotlin callback confirms only after a successful final delivery`() {
        for (failDecode in listOf(false, true)) {
            val h = Harness()
            val installer = DynamicPurifyFeatureInstaller(false, "", false, "", false, false, false, false, true)
            val specType = installer.javaClass.declaredClasses.single { it.simpleName == "FeedSpec" }
            val spec = specType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(DynAllReply::class.java.name, "executeDynAll", "dynAll")
            val feed = installer.javaClass.declaredMethods.single { it.name == "resolveFeed" }.apply { isAccessible = true }
                .invoke(installer, javaClass.classLoader, DynamicMoss::class.java, spec)
            val confirm = installer.javaClass.declaredMethods.single { it.name == "confirmedReply" }.apply { isAccessible = true }
            val plan = DynamicPurifyPolicy.Plan(emptySet(), AuthorRuleSet.EMPTY, false, false)
            data class KotlinAll(val message: DynAllReply)
            val original = KotlinAll(DynAllReply(ups = CardVideoUpList(listOf(UpListItem(0, 1)))))
            val messages = ArrayList<DynAllReply>()
            fun store(message: DynAllReply): ByteArray { messages += message; return byteArrayOf((messages.size - 1).toByte()) }
            fun read(bytes: ByteArray) = messages[bytes[0].toInt()]
            val bridge = KotlinMossReplyBridge(
                encode = { store((it as KotlinAll).message) }, parseJava = { read(it).copy() },
                toBytes = { store(it as DynAllReply) }, decode = { check(!failDecode); KotlinAll(read(it).copy()) }
            )
            val method = KotlinEndpoint::class.java.getDeclaredMethod("dynAllPersonal", Any::class.java,
                SerializationStrategy::class.java, DeserializationStrategy::class.java, KotlinHandler::class.java, ProtoBuf::class.java)
            val key = KotlinMossResponseHandlerProxy.Key(method, DynAllReply::class.java, this, method)
            var delivered: Any? = null
            val delegate = object : KotlinHandler {
                override fun onNext(reply: Any?) { assertFalse(FeatureRuntimeStage.APPLIED in h.stages); delivered = reply }
            }
            h.stages.clear()
            val proxy = KotlinMossResponseHandlerProxy.wrap(KotlinHandler::class.java, delegate, key, bridge,
                failed = { h.stages += FeatureRuntimeStage.ERROR }, transform = { it }, confirmedTransform = { source ->
                    confirm.invoke(installer, h.env, source, feed, null, plan) as KotlinMossResponseHandlerProxy.ConfirmedReply
                }) as KotlinHandler
            proxy.onNext(original)
            if (failDecode) {
                assertSame(original, delivered)
                assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
            } else {
                val updated = (delivered as KotlinAll).message
                assertFalse(updated.hasUpList())
                assertTrue(original.message.hasUpList())
                assertSame(original.message.items, updated.items)
                assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
            }
        }
    }
}
