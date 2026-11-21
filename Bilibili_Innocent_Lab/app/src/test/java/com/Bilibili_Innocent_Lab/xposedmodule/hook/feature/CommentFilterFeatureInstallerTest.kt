package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kpurifyfixture.Content
import kpurifyfixture.FoldListResp
import kpurifyfixture.HotspotPageResp
import kpurifyfixture.MainListReply
import kpurifyfixture.ReplyInfo
import kpurifyfixture.ReplyInfoReply
import kpurifyfixture.SubjectControl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterFeatureInstallerTest {

    private fun plan(
        keywords: Set<String> = emptySet(),
        minimumLevel: Int? = null,
        removeAtOnly: Boolean = false,
        userRules: AuthorRuleSet =
            AuthorRuleSet.EMPTY,
        fish: FishScorePolicy.Plan? = null
    ) = CommentFilterFeatureInstaller.JudgementPlan(
        keywords = keywords,
        minimumLevel = minimumLevel,
        removeAtOnly = removeAtOnly,
        userRules = userRules,
        fish = fish
    )

    private fun fishPlan(whitelist: String = "") =
        requireNotNull(FishScorePolicy.Config(enabled = true, rawWhitelist = whitelist).plan())

    fun `filters exact keyword containment case insensitively`() {
        val rules = RuleSetCodec.parse("剧透，Spoiler")

        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("这条含有剧透", 6),
                rules,
                null
            )
        )
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("SPOILER warning", 6),
                rules,
                null
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("正常评论", 6),
                rules,
                null
            )
        )
    }

    fun `removes only known levels below threshold and fails open when unreadable`() {
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 2),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 3),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = null),
                emptySet(),
                3
            )
        )
    }

    fun `returns original list when unchanged and immutable copy when filtered`() {
        val source = listOf("keep", "drop", "keep-2")
        val unchanged = CommentFilterFeatureInstaller.filterComments(source) { false }
        val filtered = CommentFilterFeatureInstaller.filterComments(source) { it == "drop" }

        assertSame(source, unchanged)
        assertEquals(listOf("keep", "keep-2"), filtered)
        assertEquals(source, listOf("keep", "drop", "keep-2"))
    }

    fun `at-only detection needs both the mention list and an empty residue`() {
        assertTrue(
            CommentFilterFeatureInstaller.isAtOnlyComment("@张三 @李四 ", setOf("张三", "李四"))
        )
        assertTrue(CommentFilterFeatureInstaller.isAtOnlyComment("@张三：", setOf("张三")))
        assertFalse(
            CommentFilterFeatureInstaller.isAtOnlyComment("@张三 说得好", setOf("张三"))
        )
        // 读不到 @ 名单时一律保留，不按"正文很短"猜。
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment("@张三", null))
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment("@张三", emptySet()))
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment(null, setOf("张三")))
    }

    fun `at-only detection removes the longest mention first`() {
        // 先删短名字会给"@张三丰"留下一个孤立的"丰"，从而漏删。
        assertTrue(
            CommentFilterFeatureInstaller.isAtOnlyComment(
                "@张三丰 @张三",
                setOf("张三", "张三丰")
            )
        )
    }

    fun `author rules split numeric uids from names and match exactly`() {
        val rules = AuthorRuleSet.parse("12345, 某位UP主, 0")

        assertTrue(rules.matches(null, 12345L))
        assertFalse(rules.matches(null, 1234L))
        assertTrue(rules.matches("某位UP主", null))
        assertTrue(rules.matches("  某位up主  ", null))
        // 全等匹配：包含关系不能命中，否则一条规则会误删一大片。
        assertFalse(rules.matches("某位UP主的粉丝", null))
        // 0 不是合法 UID，只能按名字看待，因此不会匹配 mid=0。
        assertFalse(rules.matches(null, 0L))
        assertTrue(AuthorRuleSet.parse("").isEmpty())
    }

    fun `each judgement removes independently and unknown signals fail open`() {
        val atOnly = plan(removeAtOnly = true)
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "@张三", atNames = setOf("张三")),
                atOnly
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "@张三", atNames = null),
                atOnly
            )
        )

        val byAuthor = plan(userRules = AuthorRuleSet.parse("777"))
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(authorMid = 777L),
                byAuthor
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(authorMid = null, authorName = null),
                byAuthor
            )
        )
    }

    fun `judgement plan reports what is actually active`() {
        assertFalse(plan().hasAnyJudgement)
        assertTrue(plan(keywords = setOf("a")).hasAnyJudgement)
        assertTrue(plan(minimumLevel = 3).hasAnyJudgement)
        assertTrue(plan(removeAtOnly = true).hasAnyJudgement)
        assertTrue(
            plan(userRules = AuthorRuleSet.parse("1")).hasAnyJudgement
        )

        // 只有关键词或 @ 判据才需要读正文；按等级/发布者过滤不该白读一次 message。
        assertFalse(plan(minimumLevel = 3).needsMessage)
        assertTrue(plan(keywords = setOf("a")).needsMessage)
        assertTrue(plan(removeAtOnly = true).needsMessage)
    }

    fun `fish judgement is the fifth criterion and fails open on unreadable signals`() {
        val fish = plan(fish = fishPlan())
        assertTrue(fish.hasAnyJudgement)
        // 打分要读正文，哪怕没开关键词判据。
        assertTrue(fish.needsMessage)
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "本鲸来汇报", level = 2, authorName = "小蓝的大肥鱼"), fish
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "这个视频讲得很清楚", level = 2, authorName = "路人"), fish
            )
        )
        assertFalse(CommentFilterFeatureInstaller.shouldRemove(CommentFilterFeatureInstaller.Signals(), fish))
        assertTrue(fish.describe().contains("fish>=60"))
    }

    fun `fish whitelist protects only against fish scoring and never against the other criteria`() {
        val signals = CommentFilterFeatureInstaller.Signals(
            message = "本鲸来汇报（摇了摇尾巴）", level = 2, authorName = "小蓝的大肥鱼", authorMid = 42L
        )
        assertTrue(CommentFilterFeatureInstaller.shouldRemove(signals, plan(fish = fishPlan())))
        assertFalse(CommentFilterFeatureInstaller.shouldRemove(signals, plan(fish = fishPlan(whitelist = "42"))))
        // 发布者过滤是用户明确写下的名单，不受 AI 判据的放行名单影响。
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                signals, plan(fish = fishPlan(whitelist = "42"), userRules = AuthorRuleSet.parse("42"))
            )
        )
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                signals, plan(fish = fishPlan(whitelist = "42"), keywords = setOf("汇报"))
            )
        )
    }

    fun `reply verdict memo computes once per list instance and size`() {
        val memo = ReplyVerdictMemo()
        var calls = 0
        val list = mutableListOf<Any>("a", "b")
        fun verdicts(source: List<*>) = memo.getOrCompute(source) {
            calls++
            java.util.IdentityHashMap<Any, Boolean>().apply { source.filterNotNull().forEach { put(it, it == "b") } }
        }
        assertSame(verdicts(list), verdicts(list))
        assertEquals(1, calls)
        // 同一引用但长度变了：宿主换了内容，不能复用。
        list += "c"
        verdicts(list)
        assertEquals(2, calls)
        // 不同实例即使内容相同也各算各的。
        verdicts(listOf<Any>("a", "b", "c"))
        assertEquals(3, calls)
        // 容量有限：最旧的被挤掉后重新计算。
        repeat(6) { index -> verdicts(listOf<Any>("x$index")) }
        verdicts(list)
        assertEquals(10, calls)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentFilterFeatureInstallerTest) -> Unit) {
            val s = CommentFilterFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`filters exact keyword containment case insensitively`() }
        runIsolated { s -> s.`removes only known levels below threshold and fails open when unreadable`() }
        runIsolated { s -> s.`returns original list when unchanged and immutable copy when filtered`() }
        runIsolated { s -> s.`at-only detection needs both the mention list and an empty residue`() }
        runIsolated { s -> s.`at-only detection removes the longest mention first`() }
        runIsolated { s -> s.`author rules split numeric uids from names and match exactly`() }
        runIsolated { s -> s.`each judgement removes independently and unknown signals fail open`() }
        runIsolated { s -> s.`judgement plan reports what is actually active`() }
        runIsolated { s -> s.`fish judgement is the fifth criterion and fails open on unreadable signals`() }
        runIsolated { s -> s.`fish whitelist protects only against fish scoring and never against the other criteria`() }
        runIsolated { s -> s.`reply verdict memo computes once per list instance and size`() }
    
        CommentFilterKotlinResponseCases().verifyAllCases()
        CommentFullTextReaderCases().verifyAllCases()
        CommentKotlinPurifierCases().verifyAllCases()
        CommentRawTextReaderCases().verifyAllCases()
        CommentReplyButtonFeatureInstallerCases().verifyAllCases()
        CommentReplyFieldAccessCases().verifyAllCases()
        CommentReplyPreviewRestorerCases().verifyAllCases()
    }
}

private class CommentFilterKotlinResponseCases {
    private fun reply(text: String, vararg children: ReplyInfo) = ReplyInfo(Content(text, emptyMap()), *children)

    private fun rewriter(rpc: String) = CommentFilterFeatureInstaller.kotlinRewriter(ReplyInfo::class.java, rpc) { replies ->
        replies.filter { reply ->
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals((reply as ReplyInfo).content.message), setOf("blocked"), null
            )
        }.toSet()
    }

    fun `fold list removes blocked replies while retaining control pagination and originals`() {
        val control = SubjectControl(true)
        val pagination = Any()
        val kept = reply("keep", reply("blocked child"), reply("child keep"))
        val source = FoldListResp(listOf(reply("blocked"), kept), control, pagination)

        val result = rewriter("foldList").rewrite(source)
        val updated = result.message as FoldListResp

        assertEquals(2, result.removed)
        assertEquals(listOf("keep"), updated.repliesList.map { it.content.message })
        assertEquals(listOf("child keep"), updated.repliesList.single().repliesList.map { it.content.message })
        assertSame(control, updated.subjectControl)
        assertSame(pagination, updated.paginationReply)
        assertEquals(2, source.repliesList.size)
        assertEquals(2, kept.repliesList.size)
        assertSame(updated, rewriter("foldList").rewrite(updated).message)
    }

    fun `hotspot list keeps opinions and page and returns untouched responses by identity`() {
        val opinion = Any()
        val page = Any()
        val kept = reply("keep")
        val source = HotspotPageResp(listOf(reply("blocked"), kept), listOf(opinion), page)

        val updated = rewriter("hotspotPage").rewrite(source).message as HotspotPageResp

        assertEquals(listOf(kept), updated.repliesList)
        assertSame(opinion, updated.opinionsList.single())
        assertSame(page, updated.page)
        assertEquals(2, source.repliesList.size)
        assertSame(updated, rewriter("hotspotPage").rewrite(updated).message)
    }

    fun `broadcast reply can be removed only in its rpc and never changes the default singleton`() {
        val source = ReplyInfoReply(reply("blocked"))
        val default = ReplyInfoReply.getDefaultInstance()

        assertSame(source, rewriter("detailList").rewrite(source).message)
        val result = rewriter("replyInfo").rewrite(source)
        assertEquals(1, result.removed)
        assertFalse((result.message as ReplyInfoReply).hasReply())
        assertTrue(source.hasReply())
        assertSame(default, rewriter("replyInfo").rewrite(default).message)
        assertFalse(default.hasReply())

        val root = kmossfixture.ReplyInfo.of("blocked", kmossfixture.ReplyInfo.of("blocked"))
        val detail = kmossfixture.MainListReply(emptyList(), null, root)
        val normal = CommentFilterFeatureInstaller.kotlinRewriter(kmossfixture.ReplyInfo::class.java, "detailList") { replies ->
            replies.filter { (it as kmossfixture.ReplyInfo).text == "blocked" }.toSet()
        }
        val updatedDetail = normal.rewrite(detail).message as kmossfixture.MainListReply
        assertEquals("blocked", updatedDetail.root.text)
        assertTrue(updatedDetail.root.repliesList.isEmpty())
        assertEquals(1, root.repliesList.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentFilterKotlinResponseCases) -> Unit) {
            val s = CommentFilterKotlinResponseCases()
            block(s)
        }
        runIsolated { s -> s.`fold list removes blocked replies while retaining control pagination and originals`() }
        runIsolated { s -> s.`hotspot list keeps opinions and page and returns untouched responses by identity`() }
        runIsolated { s -> s.`broadcast reply can be removed only in its rpc and never changes the default singleton`() }
    }
}

private class CommentFullTextReaderCases {
    fun readsCurrentFullSourceAfterViewReuseWithoutKeepingOldValue() {
        val reader = CommentFullTextReader()
        val view = NextLikableTextView("昵称: 完整正文[dog]")
        assertEquals("昵称: 完整正文[dog]", reader.read(view))
        view.rebind("另一位: 另一条完整回复")
        assertEquals("另一位: 另一条完整回复", reader.read(view))
        view.rebind("")
        assertNull(reader.read(view))
    }
    fun unknownControlsAndUnboundedContentDoNotBecomeFullSources() {
        val reader = CommentFullTextReader()
        assertNull(reader.read(Any()))
        assertNull(reader.read(NextLikableTextView("x".repeat(6001))))
    }
    fun hostNicknameClickContractIsCheckedOnEveryInteraction() {
        val reader = CommentFullTextReader()
        assertTrue(reader.isNicknameSpan(f00.u(true)))
        assertFalse(reader.isNicknameSpan(f00.u(false)))
        assertFalse(reader.isNicknameSpan(Any()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentFullTextReaderCases) -> Unit) {
            val s = CommentFullTextReaderCases()
            block(s)
        }
        runIsolated { s -> s.readsCurrentFullSourceAfterViewReuseWithoutKeepingOldValue() }
        runIsolated { s -> s.unknownControlsAndUnboundedContentDoNotBecomeFullSources() }
        runIsolated { s -> s.hostNicknameClickContractIsCheckedOnEveryInteraction() }
    }
}

private class CommentKotlinPurifierCases {

    private val search = "bilibili://search?keyword=x&from=appcommentline_search"
    private val events = mutableListOf<Pair<String, FeatureRuntimeStage>>()

    private fun purifier(
        links: Boolean = true,
        emptyPage: Boolean = true,
        payloads: Map<String, String> = mapOf("Qoe" to "comments_qoe_removed", "OperationV2" to "comments_operations_removed")
    ) = CommentKotlinPurifier(
        ReplyInfo::class.java,
        isSearchUrl = if (links) { value -> value == search } else null,
        clearEmptyPage = emptyPage,
        payloads = payloads
    ) { capability, stage, _ -> events += capability to stage }

    private fun content(vararg urls: Pair<String, Any>) = Content(linkedMapOf(*urls))

    fun `an unresolvable shape is reported once, and only when the top level needs it`() {
        val skipped = mutableListOf<String>()
        fun build(payloads: Map<String, String>, links: ((Any?) -> Boolean)? = null) =
            CommentKotlinPurifier(
                MainListReply::class.java,
                isSearchUrl = links,
                clearEmptyPage = false,
                payloads = payloads,
                logSkip = { reason -> skipped += reason }
            ) { _, _, _ -> }

        val message = MainListReply(listOf(ReplyInfo(content())), false, false, null)

        // 顶层确实有事要做（要清 Qoe），却读不到字段：净化空转，必须留痕，且每个类只报一次。
        val topLevel = build(mapOf("NoSuchField" to "comments_qoe_removed"))
        assertSame(message, topLevel.purify(message))
        topLevel.purify(message)
        assertEquals(1, skipped.size)
        assertTrue(skipped.single().startsWith("top-shape:"))

        // 只开着"摘搜索跳转"时，顶层形状用不上（摘链接走 rewriter）：解析不出来是正常的，
        // 不能报成"读不到"，否则是一条假警报。
        skipped.clear()
        build(emptyMap(), links = { it == search }).purify(message)
        assertTrue("skipped=$skipped", skipped.isEmpty())
    }

    fun `search links are stripped from replies and sub replies only`() {
        val child = ReplyInfo(content("词" to search))
        val reply = ReplyInfo(content("关键词" to search, "视频" to "bilibili://video/1"), child)
        val clean = ReplyInfo(content("视频" to "bilibili://video/2"))
        val message = MainListReply(listOf(reply, clean), false, false, null)

        val rebuilt = purifier().purify(message) as MainListReply

        val first = rebuilt.repliesList[0]
        assertEquals(setOf("视频"), first.content.urlsMap.keys)
        assertTrue(first.repliesList.single().content.urlsMap.isEmpty())
        assertSame(clean, rebuilt.repliesList[1]) // 没有搜索跳转的评论不复制
        assertEquals(2, message.repliesList[0].content.urlsMap.size) // 原响应不变
        assertTrue("comments_search_links_removed" to FeatureRuntimeStage.APPLIED in events)
    }

    fun `optional payloads and the empty page guide are cleared`() {
        val message = MainListReply(emptyList(), true, true, SubjectControl(true))

        val rebuilt = purifier().purify(message) as MainListReply

        assertFalse(rebuilt.hasQoe())
        assertTrue(rebuilt.hasOperation()) // 没开的项不动（这里只开了 OperationV2，夹具没有它，跳过）
        assertFalse(rebuilt.subjectControl.hasEmptyPage())
        assertTrue("comments_qoe_removed" to FeatureRuntimeStage.APPLIED in events)
        assertTrue("comments_empty_guide_removed" to FeatureRuntimeStage.APPLIED in events)
    }

    fun `nothing to purify keeps the very same message`() {
        val message = MainListReply(listOf(ReplyInfo(content("视频" to "bilibili://video/1"))), false, false, SubjectControl(false))
        assertSame(message, purifier().purify(message))
    }

    fun `disabled sub features are left alone`() {
        val message = MainListReply(listOf(ReplyInfo(content("词" to search))), true, false, SubjectControl(true))
        val rebuilt = purifier(links = false, emptyPage = false, payloads = emptyMap()).purify(message)
        assertSame(message, rebuilt)
    }

    fun `fold and hotspot purification keeps pagination opinions and unrelated links`() {
        val pagination = Any()
        val control = SubjectControl(true)
        val reply = ReplyInfo(content("词" to search, "视频" to "bilibili://video/1"))
        val fold = FoldListResp(listOf(reply), control, pagination)

        val updatedFold = purifier(payloads = emptyMap()).purify(fold) as FoldListResp

        assertEquals(setOf("视频"), updatedFold.repliesList.single().content.urlsMap.keys)
        assertFalse(updatedFold.subjectControl.hasEmptyPage())
        assertTrue(control.hasEmptyPage())
        assertSame(pagination, updatedFold.paginationReply)
        assertEquals(2, reply.content.urlsMap.size)

        val opinion = Any()
        val page = Any()
        val hotspot = HotspotPageResp(listOf(reply), listOf(opinion), page)
        val cleaner = purifier(emptyPage = false, payloads = emptyMap())
        val updatedHotspot = cleaner.purify(hotspot) as HotspotPageResp
        assertEquals(setOf("视频"), updatedHotspot.repliesList.single().content.urlsMap.keys)
        assertSame(opinion, updatedHotspot.opinionsList.single())
        assertSame(page, updatedHotspot.page)
        assertSame(updatedHotspot, cleaner.purify(updatedHotspot))
    }

    fun `broadcast purification edits a present reply and preserves absent default and originals`() {
        val child = ReplyInfo(content("子词" to search))
        val reply = ReplyInfo(content("词" to search, "视频" to "bilibili://video/2"), child)
        val source = ReplyInfoReply(reply)
        val default = ReplyInfoReply.getDefaultInstance()
        val cleaner = purifier(emptyPage = false, payloads = emptyMap())

        val updated = cleaner.purify(source) as ReplyInfoReply

        assertTrue(updated.hasReply())
        assertEquals(setOf("视频"), updated.reply.content.urlsMap.keys)
        assertTrue(updated.reply.repliesList.single().content.urlsMap.isEmpty())
        assertEquals(2, reply.content.urlsMap.size)
        assertEquals(1, child.content.urlsMap.size)
        assertSame(default, cleaner.purify(default))
        assertFalse(default.hasReply())
        assertSame(updated, cleaner.purify(updated))
    }

    fun `request extra gains disable underline without overriding the host`() {
        assertEquals(true, JSONObject(CommentKotlinPurifier.withDisableUnderline(null)!!).getBoolean("disable_underline"))
        val merged = JSONObject(CommentKotlinPurifier.withDisableUnderline("""{"spmid":"a.b"}""")!!)
        assertEquals("a.b", merged.getString("spmid"))
        assertTrue(merged.getBoolean("disable_underline"))
        // 宿主自己设过（哪怕是 false）就不覆盖；不是 JSON 对象就不动。
        assertNull(CommentKotlinPurifier.withDisableUnderline("""{"disable_underline":false}"""))
        assertNull(CommentKotlinPurifier.withDisableUnderline("not json"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentKotlinPurifierCases) -> Unit) {
            val s = CommentKotlinPurifierCases()
            block(s)
        }
        runIsolated { s -> s.`an unresolvable shape is reported once, and only when the top level needs it`() }
        runIsolated { s -> s.`search links are stripped from replies and sub replies only`() }
        runIsolated { s -> s.`optional payloads and the empty page guide are cleared`() }
        runIsolated { s -> s.`nothing to purify keeps the very same message`() }
        runIsolated { s -> s.`disabled sub features are left alone`() }
        runIsolated { s -> s.`fold and hotspot purification keeps pagination opinions and unrelated links`() }
        runIsolated { s -> s.`broadcast purification edits a present reply and preserves absent default and originals`() }
        runIsolated { s -> s.`request extra gains disable underline without overriding the host`() }
    }
}

private class CommentRawTextReaderCases {
    private class Header { fun z() = HeaderRich() }
    private class HeaderRich { fun e() = 42 }
    private class Legacy(val raw: String) { fun z() = LegacyRich(raw) }
    private class LegacyRich(private val text: String) { fun e() = text }
    private class Modern(val raw: String) { fun f() = ModernRich(raw) }
    private class ModernRich(@JvmField val a: String)
    private class OtherModern(val raw: String) { fun f() = OtherRich(raw) }
    private class OtherRich(private val text: String) { fun getRaw() = text }
    private class Fallback { fun f() = HeaderRich(); fun z() = LegacyRich("原文[大哭]") }

    fun mixedHeaderCannotPoisonFollowingCommentMembers() {
        val reader = CommentRawTextReader()
        assertNull(reader.read(Header(), modern = false))
        assertEquals("评论[大哭][大哭][大哭]", reader.read(Legacy("评论[大哭][大哭][大哭]"), modern = false))
        assertNull(reader.read(Header(), modern = false))
        assertEquals("另一条[dog]", reader.read(Legacy("另一条[dog]"), modern = false))
    }

    fun modernAndLegacyModelsKeepIndependentPlans() {
        val reader = CommentRawTextReader()
        repeat(2) {
            assertEquals("新版[dog]", reader.read(Modern("新版[dog]")))
            assertEquals("旧版[大哭]", reader.read(Legacy("旧版[大哭]")))
            assertEquals("另一模型[cat]", reader.read(OtherModern("另一模型[cat]")))
        }
    }

    fun invalidModernPayloadFallsBackToLegacyWithoutTruncation() {
        val reader = CommentRawTextReader()
        assertEquals("原文[大哭]", reader.read(Fallback()))
        val raw = "多行\n😀[dog]".repeat(300)
        assertEquals(raw, reader.read(Legacy(raw)))
        assertNull(reader.read(null))
        assertNull(reader.read(Any()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentRawTextReaderCases) -> Unit) {
            val s = CommentRawTextReaderCases()
            block(s)
        }
        runIsolated { s -> s.mixedHeaderCannotPoisonFollowingCommentMembers() }
        runIsolated { s -> s.modernAndLegacyModelsKeepIndependentPlans() }
        runIsolated { s -> s.invalidModernPayloadFallsBackToLegacyWithoutTruncation() }
    }
}

private class CommentReplyButtonFeatureInstallerCases {
    fun identifiesLegacyAndExperimentThreeBindingsFromTheVerifiedFieldTuples() {
        assertEquals("legacy", commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3)))
        assertEquals("exp3", commentReplyBindingShape(shape(linear = 2, image = 4, text = 4, frame = 1)))
    }

    fun identifiesAllThreeVerifiedNextBindingTuples() {
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 4, text = 3, frame = 3, linear = 1)))
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 4, text = 3, frame = 3, linear = 2)))
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 2, text = 3, frame = 3, linear = 1)))
    }

    fun fieldDeclarationOrderDoesNotChangeTheBindingFamily() {
        val fields = shape(linear = 2, image = 4, text = 4, frame = 1)
        assertEquals("exp3", commentReplyBindingShape(fields.reversed()))
    }

    fun rejectsUnknownCountsMissingFieldsAndAdditionalFieldTypes() {
        assertNull(commentReplyBindingShape(emptyList()))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 3, text = 3, frame = 3, linear = 1)))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 2)))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3) + "android.view.View"))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3) + "int"))
    }

    fun disabledReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() {
        val registrar = CountingRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = false)

        assertEquals(FeatureInstallResult.Skipped("disabled"), installer.install(environment("tv.danmaku.bili", registrar)))
        assertEquals(emptyList<String>(), installer.capabilityIds)
        assertEquals(0, registrar.registrations)
    }

    fun nonMainProcessReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() {
        val registrar = CountingRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = true)

        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            installer.install(environment("tv.danmaku.bili:download", registrar)))
        assertEquals(listOf(CommentReplyButtonFeatureInstaller.CAPABILITY), installer.capabilityIds)
        assertEquals(0, registrar.registrations)
    }

    private fun environment(process: String, registrar: HookRegistrar) = HookEnvironment(
        processName = process,
        classLoader = null,
        hookPoints = HookPointRegistry(null),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    private fun shape(
        constraint: Int = 0,
        image: Int = 0,
        text: Int = 0,
        frame: Int = 0,
        linear: Int = 0
    ): List<String> = buildList {
        repeat(constraint) { add("androidx.constraintlayout.widget.ConstraintLayout") }
        repeat(image) { add("com.bilibili.magicasakura.widgets.TintImageView") }
        repeat(text) { add("com.bilibili.magicasakura.widgets.TintTextView") }
        repeat(frame) { add("android.widget.FrameLayout") }
        repeat(linear) { add("android.widget.LinearLayout") }
    }

    private class CountingRegistrar : HookRegistrar {
        var registrations = 0
            private set

        private fun registered() {
            registrations++
        }

        override fun first(id: String, className: String, methodName: String,
            block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun all(id: String, className: String, methodName: String,
            block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun exact(id: String, owner: Class<*>, methodName: String,
            vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun adapted(id: String, point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun constructor(id: String, constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit) = registered()
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyButtonFeatureInstallerCases) -> Unit) {
            val s = CommentReplyButtonFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.identifiesLegacyAndExperimentThreeBindingsFromTheVerifiedFieldTuples() }
        runIsolated { s -> s.identifiesAllThreeVerifiedNextBindingTuples() }
        runIsolated { s -> s.fieldDeclarationOrderDoesNotChangeTheBindingFamily() }
        runIsolated { s -> s.rejectsUnknownCountsMissingFieldsAndAdditionalFieldTypes() }
        runIsolated { s -> s.disabledReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() }
        runIsolated { s -> s.nonMainProcessReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() }
    }
}

private class CommentReplyFieldAccessCases {
    fun selectsOnlyTheExactReplyIdAndIgnoresOtherActionFields() {
        val reply = Button(REPLY_ID)
        val binding = Binding(Button(REPLY_ID + 1), reply, Button(REPLY_ID + 2))

        val field = uniqueCommentReplyField(fields, binding, ::isReply)

        assertSame(reply, field?.get(binding))
    }

    fun permitsAliasedFieldsOnlyWhenTheyReferToTheSameReplyObject() {
        val reply = Button(REPLY_ID)
        val binding = Binding(reply, reply)

        val field = uniqueCommentReplyField(fields, binding, ::isReply)

        assertSame(reply, field?.get(binding))
        assertSame(reply, access().read(binding))
    }

    fun rejectsDifferentReplyObjectsWithTheSameResourceId() {
        val binding = Binding(Button(REPLY_ID), Button(REPLY_ID))

        assertNull(uniqueCommentReplyField(fields, binding, ::isReply))
        assertNull(access().read(binding))
    }

    fun anEmptyFirstBindingDoesNotPreventResolvingALaterOne() {
        val access = access()
        assertNull(access.read(Binding()))
        val reply = Button(REPLY_ID)

        assertSame(reply, access.read(Binding(b = reply)))
    }

    fun anAmbiguousFirstBindingDoesNotPreventResolvingALaterOne() {
        val access = access()
        assertNull(access.read(Binding(Button(REPLY_ID), Button(REPLY_ID))))
        val reply = Button(REPLY_ID)

        assertSame(reply, access.read(Binding(a = reply)))
    }

    fun rebindingReadsTheCurrentInstanceInsteadOfThePreviousReplyObject() {
        val access = access()
        val previous = Button(REPLY_ID)
        val current = Button(REPLY_ID)
        assertSame(previous, access.read(Binding(a = previous)))

        assertSame(current, access.read(Binding(a = current)))
    }

    fun aNewBindingMustBeCheckedForAmbiguityAfterAUniqueBindingWasCached() {
        val access = access()
        assertSame(firstReply, access.read(Binding(a = firstReply)))

        assertNull(access.read(Binding(Button(REPLY_ID), Button(REPLY_ID))))
    }

    fun anInvalidatedCachedFieldIsReplacedByTheCurrentMatchingField() {
        val old = Button(REPLY_ID)
        val current = Button(REPLY_ID + 1)
        val binding = Binding(old, current)
        val access = access()
        assertSame(old, access.read(binding))
        old.id = REPLY_ID + 2
        current.id = REPLY_ID

        assertSame(current, access.read(binding))
    }

    fun aChangedResourceIdentityRecalibratesTheCachedField() {
        var expectedId = REPLY_ID
        val first = Button(REPLY_ID)
        val second = Button(REPLY_ID + 1)
        val binding = Binding(first, second)
        val access = CommentReplyFieldAccess(fields) { (it as? Button)?.id == expectedId }
        assertSame(first, access.read(binding))
        expectedId = REPLY_ID + 1

        assertSame(second, access.read(binding))
    }

    private val fields: List<Field> = listOf("a", "b", "c").map { name ->
        Binding::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private val firstReply = Button(REPLY_ID)

    private fun access() = CommentReplyFieldAccess(fields, ::isReply)
    private fun isReply(value: Any) = (value as? Button)?.id == REPLY_ID

    private class Button(var id: Int)
    private class Binding(var a: Button? = null, var b: Button? = null, var c: Button? = null)

    private companion object {
        const val REPLY_ID = 41
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyFieldAccessCases) -> Unit) {
            val s = CommentReplyFieldAccessCases()
            block(s)
        }
        runIsolated { s -> s.selectsOnlyTheExactReplyIdAndIgnoresOtherActionFields() }
        runIsolated { s -> s.permitsAliasedFieldsOnlyWhenTheyReferToTheSameReplyObject() }
        runIsolated { s -> s.rejectsDifferentReplyObjectsWithTheSameResourceId() }
        runIsolated { s -> s.anEmptyFirstBindingDoesNotPreventResolvingALaterOne() }
        runIsolated { s -> s.anAmbiguousFirstBindingDoesNotPreventResolvingALaterOne() }
        runIsolated { s -> s.rebindingReadsTheCurrentInstanceInsteadOfThePreviousReplyObject() }
        runIsolated { s -> s.aNewBindingMustBeCheckedForAmbiguityAfterAUniqueBindingWasCached() }
        runIsolated { s -> s.anInvalidatedCachedFieldIsReplacedByTheCurrentMatchingField() }
        runIsolated { s -> s.aChangedResourceIdentityRecalibratesTheCachedField() }
    }
}

private class CommentReplyPreviewRestorerCases {
    class Reply(
        val id: Long,
        val count: Long = 0,
        val root: Long = 0,
        val text: String = "content",
        private val replies: List<Reply> = emptyList()
    ) {
        fun getRepliesList(): List<Reply> = replies
        companion object { @JvmStatic fun newBuilder(source: Reply) = Builder(source) }
        class Builder(private val source: Reply) {
            private val replies = source.replies.toMutableList()
            fun clearReplies() = apply { replies.clear() }
            fun addAllReplies(values: Iterable<Reply>) = apply { replies.addAll(values) }
            fun build() = Reply(source.id, source.count, source.root, source.text, replies.toList())
        }
    }
    class Page(private val replies: List<Reply>, private val top: Reply? = null, val title: String = "page") {
        fun getRepliesList(): List<Reply> = replies
        fun getUpTop() = top
        fun hasUpTop() = top != null
        companion object { @JvmStatic fun newBuilder(source: Page) = Builder(source) }
        class Builder(private val source: Page) {
            private val replies = source.replies.toMutableList()
            private var top = source.top
            fun clearReplies() = apply { replies.clear() }
            fun addAllReplies(values: Iterable<Reply>) = apply { replies.addAll(values) }
            fun setUpTop(value: Reply) = apply { top = value }
            fun clearUpTop() = apply { top = null }
            fun build() = Page(replies.toList(), top, source.title)
        }
    }
    private class Port(
        val fetch: (CommentReplyPreviewKey, (Result<List<Any>>) -> Unit) -> Unit
    ) : CommentReplyPreviewPort {
        override val replyInfoClass = Reply::class.java
        val requests = mutableListOf<CommentReplyPreviewKey>()
        override fun missingPreviewKey(reply: Any): CommentReplyPreviewKey? {
            val value = reply as Reply
            return if (value.root == 0L && value.count > 0 && value.getRepliesList().isEmpty())
                CommentReplyPreviewKey(10, 1, value.id, value.count) else null
        }
        override fun request(key: CommentReplyPreviewKey, complete: (Result<List<Any>>) -> Unit) {
            synchronized(requests) { requests += key }
            fetch(key, complete)
        }
        override fun withReplies(reply: Any, replies: List<Any>): Any = Reply.newBuilder(reply as Reply)
            .addAllReplies(replies.map { it as Reply }).build()
    }

    fun singleReplyIsInsertedWithoutChangingTheParentOrPage() {
        val parent = Reply(1, count = 1, text = "parent")
        val child = Reply(2, root = 1, text = "reply")
        val page = Page(listOf(parent), title = "original title")
        val port = Port { _, done -> done(Result.success(listOf(child))) }
        val changed = mutableListOf<Int>()
        val restored = CommentReplyPreviewRestorer(port, applied = changed::add).restore(page) as Page
        assertEquals(listOf(child), restored.getRepliesList().single().getRepliesList())
        assertEquals("parent", restored.getRepliesList().single().text)
        assertEquals("original title", restored.title)
        assertEquals(1L, restored.getRepliesList().single().count)
        assertTrue(parent.getRepliesList().isEmpty())
        assertEquals(listOf(1), changed)
        // 与宿主生成“更多回复”入口的条件相同，只有一条回复时不再生成计数入口。
        assertFalse(restored.getRepliesList().single().count > restored.getRepliesList().single().getRepliesList().size)
    }

    fun previewIsLimitedToThreeAndTheMoreRepliesCountIsKept() {
        val port = Port { key, done -> done(Result.success((1L..8L).map { Reply(100 + it, root = key.root) })) }
        val restored = CommentReplyPreviewRestorer(port).restore(Page(listOf(Reply(1, 8)))) as Page
        val parent = restored.getRepliesList().single()
        assertEquals(3, parent.getRepliesList().size)
        assertEquals(8L, parent.count)
        assertTrue(parent.count > parent.getRepliesList().size)
    }

    fun existingPreviewsAndCommentsWithoutRepliesDoNotFetchOrCopy() {
        val page = Page(listOf(Reply(1), Reply(2, 1, replies = listOf(Reply(3, root = 2))), Reply(4, 1, root = 2)))
        val port = Port { _, _ -> error("must not request") }
        assertSame(page, CommentReplyPreviewRestorer(port).restore(page))
        assertTrue(port.requests.isEmpty())
    }

    fun pinnedCommentsAndDuplicateRootEntriesShareOneRequest() {
        val root = Reply(1, 1)
        val child = Reply(2, root = 1)
        val port = Port { _, done -> done(Result.success(listOf(child))) }
        val restored = CommentReplyPreviewRestorer(port).restore(Page(listOf(root), root)) as Page
        assertEquals(listOf(child), restored.getUpTop()!!.getRepliesList())
        assertEquals(listOf(child), restored.getRepliesList().single().getRepliesList())
        assertEquals(1, port.requests.size)
    }

    fun failuresKeepTheOriginalResponseAndCanBeRetried() {
        var fail = true
        val port = Port { _, done ->
            if (fail) done(Result.failure(IllegalStateException("offline"))) else done(Result.success(listOf(Reply(2, root = 1))))
        }
        val restorer = CommentReplyPreviewRestorer(port)
        val page = Page(listOf(Reply(1, 1)))
        assertSame(page, restorer.restore(page))
        fail = false
        assertNotSame(page, restorer.restore(page))
        assertEquals(2, port.requests.size)
    }

    fun timeoutDoesNotDelayThePageIndefinitelyAndLateRepliesOnlyFillTheCache() {
        var complete: ((Result<List<Any>>) -> Unit)? = null
        val port = Port { _, done -> complete = done }
        val restorer = CommentReplyPreviewRestorer(port, waitMillis = 10)
        val page = Page(listOf(Reply(1, 1)))
        assertSame(page, restorer.restore(page))
        complete!!(Result.success(listOf(Reply(2, root = 1))))
        assertNotSame(page, restorer.restore(page))
        assertTrue(page.getRepliesList().single().getRepliesList().isEmpty())
        assertEquals(1, port.requests.size)
    }

    fun cacheExpiresAndReplyCountChangesUseANewRequest() {
        var now = 1L
        val port = Port { key, done -> done(Result.success(listOf(Reply(2, root = key.root)))) }
        val restorer = CommentReplyPreviewRestorer(port, nowMillis = { now })
        val page = Page(listOf(Reply(1, 1)))
        restorer.restore(page)
        restorer.restore(page)
        assertEquals(1, port.requests.size)
        restorer.restore(Page(listOf(Reply(1, 2))))
        assertEquals(2, port.requests.size)
        now += 60_001
        restorer.restore(page)
        assertEquals(3, port.requests.size)
    }

    fun requestsRunInFourSlotsAndEachFinishedRequestStartsTheNextRoot() {
        val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Pair<CommentReplyPreviewKey, (Result<List<Any>>) -> Unit>>()
        val firstFour = CountDownLatch(4)
        val port = Port { key, done -> callbacks.add(key to done); firstFour.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val restorer = CommentReplyPreviewRestorer(port)
            val result = worker.submit<Page> { restorer.restore(Page((1L..9L).map { Reply(it, 1) })) as Page }
            assertTrue(firstFour.await(1, TimeUnit.SECONDS))
            assertEquals(4, port.requests.size)
            repeat(9) {
                val (key, done) = checkNotNull(callbacks.poll())
                done(Result.success(listOf(Reply(100 + key.root, root = key.root))))
                assertTrue(callbacks.size <= 4)
            }
            assertTrue(result.get(1, TimeUnit.SECONDS).getRepliesList().all { it.getRepliesList().size == 1 })
            assertEquals(9, port.requests.size)
        } finally {
            worker.shutdownNow()
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyPreviewRestorerCases) -> Unit) {
            val s = CommentReplyPreviewRestorerCases()
            block(s)
        }
        runIsolated { s -> s.singleReplyIsInsertedWithoutChangingTheParentOrPage() }
        runIsolated { s -> s.previewIsLimitedToThreeAndTheMoreRepliesCountIsKept() }
        runIsolated { s -> s.existingPreviewsAndCommentsWithoutRepliesDoNotFetchOrCopy() }
        runIsolated { s -> s.pinnedCommentsAndDuplicateRootEntriesShareOneRequest() }
        runIsolated { s -> s.failuresKeepTheOriginalResponseAndCanBeRetried() }
        runIsolated { s -> s.timeoutDoesNotDelayThePageIndefinitelyAndLateRepliesOnlyFillTheCache() }
        runIsolated { s -> s.cacheExpiresAndReplyCountChangesUseANewRequest() }
        runIsolated { s -> s.requestsRunInFourSlotsAndEachFinishedRequestStartsTheNextRoot() }
    }
}
