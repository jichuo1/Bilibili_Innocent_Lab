package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bapis.bilibili.app.interfaces.v1.*
import com.bapis.bilibili.main.community.reply.v1.ReplyInfo
import com.bapis.bilibili.polymer.app.search.v1.*
import com.bilibili.lib.moss.api.MossResponseHandler
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.*
import org.junit.Test

class IndependentCoverageRegressionTest {
    fun `Banner parent and leaf remain partial if one lifecycle hook fails`() {
        val point = VersionAdapter.BannerPoint("android.view.View", listOf(
            VersionAdapter.HookPoint("android.view.View", "onAttachedToWindow", emptyList()),
            VersionAdapter.HookPoint("android.view.View", "onVisibilityChanged", emptyList())))
        val failures = object : HookRegistrar by TestHookRegistrar {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                if (id.endsWith(".1")) error("registration failed")
            }
        }
        val result = HomeBannerFeatureInstaller(true, point) { true }.install(env.copy(registrar = failures))
        assertTrue(result is FeatureInstallResult.Installed && !result.complete)
        assertEquals("partial:1/2", status)
    }
    private val recorded = PlayerPortTestRegistrar()
    private var status = ""
    private val registrar = object : HookRegistrar by recorded {
        override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
            recorded.exact(id, Class.forName(point.className), point.methodName,
                *point.paramClassNames.orEmpty().map { Class.forName(it) }.toTypedArray(), block = block)
        }
    }
    private val env = HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
        registrar, { _, _ -> }, { _, _ -> }, { _, s -> status = s })

    fun `default words sync and async both clear text without touching routes`() {
        HomeTopBarFeatureInstaller(false, true, null).install(env)
        assertTrue(recorded.hooks.keys.any { it.endsWith("search_default_words.false") })
        assertTrue(recorded.hooks.keys.any { it.endsWith("search_default_words.true") })
        val original = DefaultWordsReply("show", "word", "value")
        val cleaned = recorded.invoke("home.top_bar.search_default_words.false", args = arrayOf(DefaultWordsReq())) { original } as DefaultWordsReply
        assertEquals("", cleaned.getWord()); assertEquals("", cleaned.getShow()); assertEquals("", cleaned.getValue())
        assertSame(original.route, cleaned.route); assertEquals("word", original.getWord())
        var delivered: Any? = null
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered = reply }
            override fun onError(error: Throwable) = Unit
            override fun onCompleted() = Unit
        }
        recorded.invoke("home.top_bar.search_default_words.true", args = arrayOf(DefaultWordsReq(), handler)) {
            (it[1] as MossResponseHandler).onNext(original); null
        }
        assertEquals("", (delivered as DefaultWordsReply).getWord())
    }

    fun `default word copy failure and empty text preserve original identity`() {
        val cleaner = requireNotNull(SearchDefaultWordsCleaner.resolve(DefaultWordsReply::class.java))
        val original = DefaultWordsReply("show", "word", "value", failAt = "word")
        assertSame(original, cleaner.clean(original, env)); assertEquals("show", original.getShow())
        val empty = DefaultWordsReply()
        assertSame(empty, cleaner.clean(empty, env))
    }

    fun `comment author filter survives missing content and message paths`() {
        val base = requireNotNull(VersionAdapter.locateCommentFilter(javaClass.classLoader!!))
        val points = base.copy(contentGetter = null, messageGetter = null)
        val installer = CommentFilterFeatureInstaller(true, "word", false, 3, userFilterEnabled = true, rawUserRules = "member", points = points)
        assertTrue(installer.install(env) is FeatureInstallResult.Installed)
        assertTrue(status.startsWith("partial:"))
        val source = listOf(ReplyInfo())
        assertTrue((recorded.invoke("comment.filter.list.0") { source } as List<*>).isEmpty())
        assertEquals(1, source.size)
        assertEquals(points, VersionAdapter.CommentFilterPoints.fromJson(points.toJson()))
    }

    fun `comment keywords survive missing both level paths`() {
        val points = requireNotNull(VersionAdapter.locateCommentFilter(javaClass.classLoader!!)).copy(levelGetter = null, memberV2LevelGetter = null)
        CommentFilterFeatureInstaller(true, "unchanged", true, 3, points = points).install(env)
        assertTrue(status.startsWith("partial:"))
        assertTrue((recorded.invoke("comment.filter.list.0") { listOf(ReplyInfo()) } as List<*>).isEmpty())
    }

    fun `search title and ordinary ad remain active without author and special getters`() {
        SearchPurifyFeatureInstaller(true, true, "blocked", true, "author").install(env)
        assertTrue(status.startsWith("partial:"))
        val keep = Item(false, SearchAv("normal"))
        val source = listOf(Item(false, SearchAv("blocked")), Item(true, SearchAv("ad")), keep)
        val result = recorded.invoke("search.purify.item_list") { source } as List<*>
        assertEquals(listOf(keep), result); assertEquals(3, source.size)
    }

    fun `category search reuses judgments without mutating its original list`() {
        SearchPurifyFeatureInstaller(true, true, "blocked", false, "").install(env)
        val keep = Item(false, SearchAv("normal"))
        val source = listOf(Item(false, SearchAv("blocked")), Item(true, SearchAv("ad")), keep)
        val result = recorded.invoke("search.purify.category.items") { source } as List<*>
        assertEquals(listOf(keep), result)
        assertEquals(3, source.size)
        val unchanged = listOf(keep)
        assertSame(unchanged, recorded.invoke("search.purify.category.items") { unchanged })
    }

    class StoryItem(val ad: Boolean) { fun isAd() = ad }
    class StoryResponse { fun getItems(): List<StoryItem> = emptyList() }
    fun `missing Story music reader does not disable working advertisement filter`() {
        val points = VersionAdapter.StoryFeedPoints(
            listOf(VersionAdapter.HookPoint(StoryResponse::class.java.name, "getItems", emptyList())), emptyList(),
            VersionAdapter.HookPoint(StoryItem::class.java.name, "isAd", emptyList()), null, null)
        val installer = StoryPurifyFeatureInstaller(true, false, false, false, false, false, false, false, false, false, false, true, points)
        val installed = installer.install(env)
        assertTrue(installed is FeatureInstallResult.Installed && !installed.complete)
        val keep = StoryItem(false)
        assertEquals(listOf(keep), recorded.invoke("story.response.0") { listOf(StoryItem(true), keep) })
    }

    fun `author paths are independently restricted without changing rule semantics`() {
        val rules = AuthorRuleSet.parse("123\nAlice")
        assertEquals(setOf("alice"), rules.available(true, false).names)
        assertTrue(rules.available(true, false).mids.isEmpty())
        assertEquals(setOf(123L), rules.available(false, true).mids)
        assertSame(rules, rules.available(true, true))
    }

    private object WireDummyStrategy : SerializationStrategy<Any>

    private class WireStrippedDescriptor(
        pkg: String,
        svc: String,
        mtd: String
    ) : Descriptor(pkg, svc, mtd, Any::class, WireDummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    fun `search purify filters SearchAll payload over Wire-First`() {
        val searchReg = PlayerPortTestRegistrar()
        val wireMethod = GrpcEngine::class.java.getDeclaredMethod(
            "a",
            Service::class.java,
            Descriptor::class.java,
            ByteArray::class.java,
            Handler::class.java,
            Any::class.java
        )
        SearchPurifyFeatureInstaller(
            removeCommercial = true,
            keywordFilterEnabled = false,
            rawKeywords = "",
            authorFilterEnabled = false,
            rawAuthorRules = "",
            kmpGrpcWireEngine = KmpGrpcWireAdapter.point(wireMethod)
        ).install(env.copy(registrar = searchReg))
        var searchOut: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { searchOut = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = WireStrippedDescriptor("bilibili.polymer.app.search.v1", "Search", "SearchAll")
        searchReg.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("host"), descriptor, ByteArray(0), handler, null)
        ) { passed ->
            (passed[3] as Handler).onNext(
                SearchAllResponse(listOf(Item(true, SearchAv("ad")), Item(false, SearchAv("normal")))).toByteArray()
            )
            null
        }
        val cleanedSearch = SearchAllResponse.parseFrom(requireNotNull(searchOut))
        assertEquals(listOf("normal"), cleanedSearch.getItemList().map { it.getAv().getTitle() })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (IndependentCoverageRegressionTest) -> Unit) {
            val s = IndependentCoverageRegressionTest()
            block(s)
        }
        runIsolated { s -> s.`Banner parent and leaf remain partial if one lifecycle hook fails`() }
        runIsolated { s -> s.`default words sync and async both clear text without touching routes`() }
        runIsolated { s -> s.`default word copy failure and empty text preserve original identity`() }
        runIsolated { s -> s.`comment author filter survives missing content and message paths`() }
        runIsolated { s -> s.`comment keywords survive missing both level paths`() }
        runIsolated { s -> s.`search title and ordinary ad remain active without author and special getters`() }
        runIsolated { s -> s.`category search reuses judgments without mutating its original list`() }
        runIsolated { s -> s.`missing Story music reader does not disable working advertisement filter`() }
        runIsolated { s -> s.`author paths are independently restricted without changing rule semantics`() }
        runIsolated { s -> s.`search purify filters SearchAll payload over Wire-First`() }
    }
}
