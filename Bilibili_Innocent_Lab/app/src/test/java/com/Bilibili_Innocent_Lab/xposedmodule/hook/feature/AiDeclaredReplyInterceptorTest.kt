package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import aistub.common.CardBasicInfo
import aistub.common.Module
import aistub.common.Neutral
import aistub.common.Owner
import aistub.common.RelateCard
import aistub.common.RelateCardType
import aistub.common.Relates
import aistub.common.UgcIntroduction
import aistub.v1.Arc
import aistub.v1.ECode
import aistub.v1.IntroductionTab
import aistub.v1.Tab
import aistub.v1.TabModule
import aistub.v1.ViewReply
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiDeclaredReplyInterceptorTest {

    private val errors = mutableListOf<String>()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { _, _ -> },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )
    private val interceptor = requireNotNull(
        AiDeclaredReplyInterceptor.resolve(javaClass.classLoader!!, "aistub.v1.", "aistub.common.")
    )

    fun reset() {
        AiDeclaredVideoRegistry.resetForTest()
        AuthorPickSession.resetForTest()
    }

    fun everyReaderAndBothWriteChainsResolveAgainstBapisShapedClasses() {
        assertTrue(interceptor.relateStripReady)
    }

    fun declaredVideoRedirectsToTheFirstUsableRelatedVideo() {
        val authors = mutableListOf<Pair<String?, Long>>()
        val original = reply(
            aid = 100, ownerMid = 1, declaration = "含AI生成内容",
            cards = listOf(
                live(300),
                video(101, mid = 1),                  // 同一发布者：AI 系列常成串推荐
                video(102, mid = 5).also { AiDeclaredVideoRegistry.add(102) },
                video(103, mid = 6)
            )
        )
        val updated = process(original, onAuthor = { name, mid -> authors += name to mid }) as ViewReply

        assertNotSame(original, updated)
        assertEquals(ECode.CODE_404_VALUE, updated.ecodeValue)
        assertEquals("bilibili://video/103?cid=1", updated.ecodeConfig.redirectUrl)
        assertEquals("", updated.ecodeConfig.msg)
        // 原响应不被改动（副本改写）。
        assertEquals(0, original.ecodeValue)
        assertTrue(AiDeclaredVideoRegistry.contains(100))
        assertEquals(listOf<Pair<String?, Long>>("owner" to 1L), authors)
        assertTrue(AiDeclaredVideoPolicy.CAPABILITY_DETAIL to FeatureRuntimeStage.APPLIED in evidence)
    }

    fun declaredVideoWithoutAUsableReplacementShowsTheHostPrivacyPageWithOurHint() {
        val original = reply(aid = 100, ownerMid = 1, declaration = "含AI生成内容", cards = listOf(video(101, mid = 1)))
        val updated = process(original) as ViewReply
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, updated.ecodeValue)
        assertEquals("", updated.ecodeConfig.redirectUrl)
        assertEquals(InjectedUiLocale.messages().aiDeclaredBlockedHint, updated.ecodeConfig.msg)
    }

    fun exhaustedRedirectGuardFallsBackToThePrivacyPage() {
        val guard = AiRedirectGuard(maxRedirects = 1, windowMillis = 60_000L)
        val first = process(reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6))), guard) as ViewReply
        val second = process(reply(103, 6, "含AI生成内容", listOf(video(104, mid = 7))), guard) as ViewReply
        assertEquals(ECode.CODE_404_VALUE, first.ecodeValue)
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, second.ecodeValue)
    }

    fun undeclaredVideoIsReturnedAsTheSameInstance() {
        val original = reply(100, 1, "个人观点，仅供参考", listOf(video(103, mid = 6)))
        assertSame(original, process(original))
        val plain = reply(100, 1, null, listOf(video(103, mid = 6)))
        assertSame(plain, process(plain))
        assertTrue(errors.isEmpty())
    }

    fun undeclaredVideoDropsOnlyKnownAiRelatedCards() {
        AiDeclaredVideoRegistry.add(102)
        val original = reply(100, 1, null, listOf(video(101, mid = 2), video(102, mid = 3), live(102)))
        val updated = process(original) as ViewReply
        assertNotSame(original, updated)
        assertEquals(0, updated.ecodeValue)
        val cards = relatesOf(updated).cardsList
        // 直播卡碰巧同号也不删：只认视频卡。
        assertEquals(listOf(101L, 102L), cards.map { it.basicInfo.id })
        assertEquals(RelateCardType.LIVE_VALUE, cards[1].relateCardTypeValue)
        assertEquals(3, relatesOf(original).cardsList.size)
    }

    /** 9.13.0 历史记录页的后台 View 请求：只记 aid，不改写、不记发布者、不占连锁保险。 */
    fun passiveHistoryRequestOnlyRecordsTheAid() {
        val guard = AiRedirectGuard(maxRedirects = 1, windowMillis = 60_000L)
        val authors = mutableListOf<String?>()
        val original = reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6)))
        assertSame(original, process(original, guard, passive = true, onAuthor = { name, _ -> authors += name }))
        assertTrue(AiDeclaredVideoRegistry.contains(100))
        assertTrue(authors.isEmpty())
        // 保险额度没被占：随后真正的详情页请求仍然跳转补位。
        val detail = process(reply(200, 1, "含AI生成内容", listOf(video(103, mid = 6))), guard) as ViewReply
        assertEquals(ECode.CODE_404_VALUE, detail.ecodeValue)
    }

    /** 已有错误码（服务端报错，或同一份响应第二次经过）就不再改写，也不重复提示与占额度。 */
    fun alreadyErroredReplyIsLeftToTheHost() {
        val guard = AiRedirectGuard(maxRedirects = 1, windowMillis = 60_000L)
        val once = process(reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6))), guard)
        assertSame(once, process(once, guard))
    }

    fun authorLedgerIsIndependentOfTheSharedSessionCapacity() {
        val ledger = AiAuthorLedger(max = 3)
        assertTrue(ledger.claim("UP A"))
        assertFalse(ledger.claim(" up a "))
        assertTrue(ledger.claim("UP B"))
        assertTrue(ledger.claim("UP C"))
        assertFalse(ledger.claim("UP D"))
        assertFalse(ledger.claim(""))
        // 共享会话集合写满不影响账本：早先的实现在这里会静默丢掉第 33 位之后的发布者。
        repeat(64) { AuthorPickSession.add("filler-$it") }
        assertTrue(AiAuthorLedger(max = 256).claim("UP E"))
    }

    fun defaultInstanceAndForeignObjectsAreNeverTouched() {
        assertSame(ViewReply.getDefaultInstance(), process(ViewReply.getDefaultInstance()))
        val foreign = Any()
        assertSame(foreign, process(foreign))
    }

    private fun process(
        reply: Any,
        guard: AiRedirectGuard = AiRedirectGuard(),
        passive: Boolean = false,
        playlist: AiPlaylistRoute? = null,
        playlistGuard: AiRedirectGuard = AiRedirectGuard(),
        onAuthor: (String?, Long) -> Unit = { _, _ -> }
    ): Any = interceptor.process(
        reply, environment, guard, passive,
        playlist = playlist, playlistGuard = playlistGuard, onDeclaredAuthor = onAuthor
    )

    private class FakeRoute(private val owned: Set<Long>, private val hasNext: Boolean) : AiPlaylistRoute {
        var skips = 0
        override fun ownsPlaylistItem(aid: Long) = aid in owned
        override fun skipToNext(): Boolean {
            if (hasNext) skips++
            return hasNext
        }
    }

    /** 连播里的条目：不改写跳转（否则离开整个列表），交给宿主切下一集，响应原样交付。 */
    fun playlistItemSkipsToTheNextEpisodeInsteadOfLeavingThePlaylist() {
        val route = FakeRoute(owned = setOf(100L), hasNext = true)
        val authors = mutableListOf<String?>()
        val original = reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6)))
        assertSame(original, process(original, playlist = route, onAuthor = { name, _ -> authors += name }))
        assertEquals(1, route.skips)
        assertTrue(AiDeclaredVideoRegistry.contains(100))
        // 强力模式照常记发布者：连播只改变"怎么离开这一集"。
        assertEquals(listOf<String?>("owner"), authors)
    }

    fun lastPlaylistItemShowsTheHintPageAndStaysInThePlaylist() {
        val route = FakeRoute(owned = setOf(100L), hasNext = false)
        val updated = process(reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6))), playlist = route) as ViewReply
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, updated.ecodeValue)
        assertEquals("", updated.ecodeConfig.redirectUrl)
    }

    fun exhaustedPlaylistGuardStopsSkippingAndShowsTheHint() {
        val route = FakeRoute(owned = setOf(100L, 200L), hasNext = true)
        val playlistGuard = AiRedirectGuard(maxRedirects = 1, windowMillis = 60_000L)
        process(reply(100, 1, "含AI生成内容", emptyList()), playlist = route, playlistGuard = playlistGuard)
        val second = process(reply(200, 2, "含AI生成内容", emptyList()), playlist = route, playlistGuard = playlistGuard) as ViewReply
        assertEquals(1, route.skips)
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, second.ecodeValue)
    }

    /** 不属于当前连播的 View（例如从连播页里点开的另一个视频）仍走详情页补位。 */
    fun nonPlaylistItemKeepsTheDetailRedirect() {
        val route = FakeRoute(owned = setOf(999L), hasNext = true)
        val updated = process(reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6))), playlist = route) as ViewReply
        assertEquals(ECode.CODE_404_VALUE, updated.ecodeValue)
        assertEquals(0, route.skips)
    }

    private fun reply(aid: Long, ownerMid: Long, declaration: String?, cards: List<RelateCard>): ViewReply {
        val neutral = declaration?.let { Neutral("warning-report-circle-line@500", it) }
        val modules = listOf(
            Module(UgcIntroduction(neutral), null),
            Module(null, Relates(cards))
        )
        return ViewReply(Arc(aid), Owner("owner", ownerMid), Tab(listOf(TabModule(IntroductionTab(modules)))))
    }

    private fun video(aid: Long, mid: Long) =
        RelateCard(RelateCardType.AV_VALUE, CardBasicInfo(aid, "bilibili://video/$aid?cid=1", Owner("up$mid", mid)))

    private fun live(id: Long) =
        RelateCard(RelateCardType.LIVE_VALUE, CardBasicInfo(id, "https://live.bilibili.com/$id", null))

    private fun relatesOf(reply: ViewReply): Relates =
        reply.tab.tabModuleList.single().introduction.modulesList.single { it.hasRelates() }.relates

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AiDeclaredReplyInterceptorTest) -> Unit) {
            val s = AiDeclaredReplyInterceptorTest()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
        }
        runIsolated { s -> s.everyReaderAndBothWriteChainsResolveAgainstBapisShapedClasses() }
        runIsolated { s -> s.declaredVideoRedirectsToTheFirstUsableRelatedVideo() }
        runIsolated { s -> s.declaredVideoWithoutAUsableReplacementShowsTheHostPrivacyPageWithOurHint() }
        runIsolated { s -> s.exhaustedRedirectGuardFallsBackToThePrivacyPage() }
        runIsolated { s -> s.undeclaredVideoIsReturnedAsTheSameInstance() }
        runIsolated { s -> s.undeclaredVideoDropsOnlyKnownAiRelatedCards() }
        runIsolated { s -> s.passiveHistoryRequestOnlyRecordsTheAid() }
        runIsolated { s -> s.alreadyErroredReplyIsLeftToTheHost() }
        runIsolated { s -> s.authorLedgerIsIndependentOfTheSharedSessionCapacity() }
        runIsolated { s -> s.defaultInstanceAndForeignObjectsAreNeverTouched() }
        runIsolated { s -> s.playlistItemSkipsToTheNextEpisodeInsteadOfLeavingThePlaylist() }
        runIsolated { s -> s.lastPlaylistItemShowsTheHintPageAndStaysInThePlaylist() }
        runIsolated { s -> s.exhaustedPlaylistGuardStopsSkippingAndShowsTheHint() }
        runIsolated { s -> s.nonPlaylistItemKeepsTheDetailRedirect() }
    }
}
