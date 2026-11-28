package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Sensitivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller.Companion.matchesRecommendPath
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedGrouping
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedRow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.bplus.followinglist.module.item.ModuleEnum
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.lumen.coacervation.engine.host.LumenSurfaceFadeCurve
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.model.SurfaceRole
import java.io.IOException
import kotlin.coroutines.Continuation
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FishScorePolicyTest {

    private fun plan(
        sensitivity: Sensitivity = Sensitivity.STANDARD,
        protectHighLevel: Boolean = true,
        nicknameWords: String = "",
        textWords: String = "",
        whitelist: String = "",
        strongMode: Boolean = false
    ) = requireNotNull(
        Config(true, sensitivity.id, nicknameWords, textWords, whitelist, protectHighLevel, strongMode).plan()
    )

    private fun removes(
        name: String?,
        level: Int?,
        message: String?,
        plan: FishScorePolicy.Plan = plan(),
        mid: Long? = null
    ) = FishScorePolicy.shouldRemove(plan, name, mid, level, message)

    fun `a typical fish account is removed even when the text alone looks harmless`() {
        assertTrue(removes("小蓝的大肥鱼", 2, "这个视频讲得挺清楚的"))
        assertTrue(removes("小蓝的大肥鱼", 2, null))
        assertTrue(removes("普通昵称", 3, "本鲸来汇报啦（摇了摇尾巴）@阿离 主人快看"))
    }

    fun `no single weak dimension removes a comment at the standard threshold`() {
        // 名字带鱼的真人、Lv2 的普通新号、问句与短吐槽都不够 60 分。
        assertFalse(removes("咸鱼爱好者", 3, "今天也是摸鱼的一天呢"))
        assertFalse(removes("鲸鱼爱好者", 2, "鲸鱼的叫声真好听，我昨天刚在纪录片里看到"))
        assertFalse(removes("路过的网友", 1, "哈哈哈哈哈哈"))
        assertFalse(removes("路过的网友", 2, "请问这个是什么歌？"))
        assertFalse(removes("肥鱼", 2, "哈哈哈哈"))
        assertFalse(removes("普通昵称", 2, "来打卡"))
        assertFalse(removes("普通昵称", 2, "让我来看看，这个我也想知道"))
        assertFalse(removes("学AI的小李", 2, "我是学AI的，来看看大家怎么说"))
    }

    fun `veterans officials and the whitelist are never scored`() {
        val fish = "本鲸来汇报啦（摇了摇尾巴）@阿离 主人快看"
        assertFalse(removes("小蓝的大肥鱼", 5, fish))
        assertFalse(removes("小蓝的大肥鱼", 6, fish))
        assertTrue(removes("小蓝的大肥鱼", 5, fish, plan(protectHighLevel = false)))
        val allow = plan(whitelist = "小蓝的大肥鱼，10086")
        assertFalse(removes("小蓝的大肥鱼", 2, fish, allow))
        assertFalse(removes("别的名字", 2, fish, allow, mid = 10086))
        assertTrue(removes("别的名字", 2, fish, allow, mid = 10087))
        // 用户名是全等匹配：名字里含白名单名字不算。
        assertTrue(removes("小蓝的大肥鱼二号", 2, fish, allow))
    }

    fun `unreadable signals are never scored`() {
        assertFalse(removes(null, null, null))
        assertFalse(removes("   ", null, "   "))
        // 等级读不到不能当作 Lv0：只剩昵称 45 分，标准档不删。
        assertEquals(45, FishScorePolicy.score(plan(), "肥鱼", null, null))
        assertFalse(removes("肥鱼", null, null))
        assertEquals(45 + 14, FishScorePolicy.score(plan(), "肥鱼", 2, null))
        // 只剩等级时最多 14 分，任何阈值都到不了。
        assertEquals(14, FishScorePolicy.score(plan(), null, 2, null))
    }

    fun `sensitivity moves the threshold and the boundary is inclusive`() {
        // 肥鱼(45) + Lv2(14) = 59。
        assertEquals(59, FishScorePolicy.score(plan(), "肥鱼", 2, null))
        assertFalse(removes("肥鱼", 2, null, plan(Sensitivity.LOOSE)))
        assertFalse(removes("肥鱼", 2, null, plan(Sensitivity.STANDARD)))
        assertTrue(removes("肥鱼", 2, null, plan(Sensitivity.STRICT)))
        // 严格档下昵称单项 45 分就到线，这是该档位的明示代价。
        assertTrue(removes("肥鱼", null, null, plan(Sensitivity.STRICT)))
        assertEquals(60, plan(Sensitivity.STANDARD).threshold)
        assertEquals(Sensitivity.STANDARD, Sensitivity.fromId("nonsense"))
        assertEquals(Sensitivity.STRICT, Sensitivity.fromId(" strict "))
        assertEquals(setOf("loose", "standard", "strict"), Sensitivity.IDS)
    }

    fun `strong mode doubles the completed score and uses the selected threshold`() {
        val regular = plan(Sensitivity.LOOSE)
        val strong = plan(Sensitivity.LOOSE, strongMode = true)
        val below = "哈哈哈哈哈哈这段我刚才又看了好一会儿"
        val above = "这段内容值得一看我已经看完整个过程了"
        assertEquals(75, strong.threshold)
        assertEquals(74, FishScorePolicy.score(strong, "肥鱼", null, below))
        assertEquals(76, FishScorePolicy.score(strong, "赛博", null, above))
        assertFalse(removes("肥鱼", null, below, strong))
        assertFalse(removes("赛博", null, above, regular))
        assertTrue(removes("赛博", null, above, strong))
        assertEquals(198, FishScorePolicy.score(strong, "小蓝的大肥鱼", 2, "本鲸来汇报啦（摇了摇尾巴）"))
        assertFalse(removes("小蓝的大肥鱼", 5, null, strong))
        assertFalse(removes("肥鱼", 2, null, plan(whitelist = "42", strongMode = true), mid = 42L))
    }

    fun `name rules are capped and the generic fish fallback is small`() {
        assertEquals(8, FishScorePolicy.nameScore("咸鱼君"))
        assertEquals(0, FishScorePolicy.nameScore("普通昵称"))
        assertEquals(55, FishScorePolicy.nameScore("深度求索鲸鱼娘"))
        assertEquals(55, FishScorePolicy.nameScore("小蓝的大肥鱼"))
        // ASCII 词边界与 JS 一致：汉字旁边的 AI 算独立词，单词里的 ai 不算。
        assertTrue(FishScorePolicy.nameScore("小AI助手") >= 28)
        assertEquals(0, FishScorePolicy.nameScore("mailbox"))
        assertEquals(0, FishScorePolicy.nameScore("rails"))
    }

    fun `level adds for new accounts and subtracts for veterans`() {
        assertEquals(14, FishScorePolicy.levelScore(0))
        assertEquals(14, FishScorePolicy.levelScore(2))
        assertEquals(6, FishScorePolicy.levelScore(3))
        assertEquals(0, FishScorePolicy.levelScore(4))
        assertEquals(0, FishScorePolicy.levelScore(5))
        assertEquals(-10, FishScorePolicy.levelScore(6))
        assertEquals(0, FishScorePolicy.levelScore(null))
    }

    fun `text rules cap repeated hits and human relief only applies to weak text`() {
        val lowKey = TextNormalizer.forMatching("值得看，码住，适合新手，正好对口")
        assertEquals(20, FishScorePolicy.textScore(lowKey, null)) // 10 × 命中次数，封顶 20
        assertEquals(-20, FishScorePolicy.textScore(TextNormalizer.forMatching("请问这个怎么办？哈哈哈哈"), null))
        // 正文已经很像 AI（≥40）时，一句"哈哈哈哈"不能洗白。
        val aiLike = TextNormalizer.forMatching("本鲸来汇报啦（摇了摇尾巴）哈哈哈哈")
        assertEquals(75, FishScorePolicy.textScore(aiLike, null))
        // 同一条规则重复命中不叠加（只有带封顶的规则按次数累加）：50 + 超长正文 8。
        assertEquals(58, FishScorePolicy.textScore(TextNormalizer.forMatching("本鲸".repeat(400)), null))
    }

    fun `weak generic phrases stay below the strong self-reference weight`() {
        val strong = FishScorePolicy.textScore(TextNormalizer.forMatching("本鲸今天也很开心地来了呢"), null)
        val weak = FishScorePolicy.textScore(TextNormalizer.forMatching("今天来打卡，顺便看看大家都在聊什么内容呢"), null)
        assertEquals(50, strong)
        assertTrue(weak in 0..12)
    }

    fun `mentioning an owner whose name matches the nickname adds score`() {
        assertTrue(FishScorePolicy.ownerLink("阿离的大肥鱼", "阿离酱"))
        assertTrue(FishScorePolicy.ownerLink("相同名字", "相同名字"))
        assertFalse(FishScorePolicy.ownerLink("阿离的大肥鱼", "路人甲"))
        assertFalse(FishScorePolicy.ownerLink("大肥鱼", "大肥鱼的朋友"))
        // 核心只剩一个字：只认汉字，不让字母数字凑巧撞上。
        assertTrue(FishScorePolicy.ownerLink("阿的鱼", "阿离"))
        assertFalse(FishScorePolicy.ownerLink("a的鱼", "banana"))
        val linked = FishScorePolicy.textScore(TextNormalizer.forMatching("这个视频真不错 @阿离酱"), TextNormalizer.forMatching("阿离的大肥鱼"))
        val unlinked = FishScorePolicy.textScore(TextNormalizer.forMatching("这个视频真不错 @路人甲"), TextNormalizer.forMatching("阿离的大肥鱼"))
        assertEquals(25, linked - unlinked)
    }

    fun `a leading mention with no text or a long pitch is scored but a reply prefix is not`() {
        val bare = FishScorePolicy.textScore("@张三", null)
        val longPitch = FishScorePolicy.textScore("@张三 这个视频整理得很完整，建议收藏", null)
        val reply = FishScorePolicy.textScore("回复 @张三 :这个视频整理得很完整，建议收藏", null)
        assertEquals(15 - 8, bare) // 纯 @ 15，短文本减 8
        assertTrue(longPitch >= 20)
        assertTrue(reply < longPitch)
    }

    fun `invisible and fullwidth tricks do not hide a fish`() {
        assertTrue(removes("Ｄｅｅｐ​Ｓｅｅｋ助手", 2, null))
        assertEquals(50, FishScorePolicy.textScore(TextNormalizer.forMatching("本​鲸来了"), null))
        assertEquals(50, FishScorePolicy.textScore(TextNormalizer.forMatching("本鲸来了"), null))
    }

    fun `custom words score as strong evidence but never remove on their own for veterans`() {
        val custom = plan(nicknameWords = "鲨鱼酱", textWords = "主人安好")
        assertTrue(removes("深海的鲨鱼酱", 2, null, custom))
        assertFalse(removes("深海的鲨鱼酱", 4, null, custom)) // 50 + 0 < 60
        assertTrue(removes("路人", 2, "主人安好，今天也在认真刷视频", custom))
        assertFalse(removes("路人", 6, "主人安好，今天也在认真刷视频", custom))
        assertFalse(removes("深海的鲨鱼酱", 2, null, plan()))
    }

    fun `custom words need at least two characters and are bounded`() {
        assertEquals(setOf("鲨鱼", "ab"), FishScorePolicy.customWords("鲸，a，鲨鱼；AB\n"))
        assertTrue(FishScorePolicy.customWords("鲸,a, ").isEmpty())
        val many = (1..200).joinToString(",") { "词语$it" }
        assertEquals(FishScorePolicy.MAX_CUSTOM_WORDS, FishScorePolicy.customWords(many).size)
    }

    fun `settings become an immutable plan only when enabled`() {
        assertNull(Config.DISABLED.plan())
        assertNull(Config(enabled = false, sensitivityId = "strict").plan())
        val strict = requireNotNull(Config(true, "strict", "鲨鱼酱", "主人安好", "10086,某某", false).plan())
        assertEquals(45, strict.threshold)
        assertFalse(strict.protectHighLevel)
        assertEquals(setOf("鲨鱼酱"), strict.nicknameWords)
        assertEquals(setOf("主人安好"), strict.textWords)
        assertEquals(setOf(10086L), strict.whitelist.mids)
        assertEquals(60, requireNotNull(Config(true).plan()).threshold)
        assertEquals(60, requireNotNull(Config(true, "garbage").plan()).threshold)
        assertEquals(requireNotNull(Config(true, "loose").plan()), requireNotNull(Config(true, "loose").plan()))
        assertNotNull(strict.describe())
    }

    fun `long adversarial text stays linear`() {
        val plan = plan()
        val text = "（" + "尾巴".repeat(2_000) + "@" + "a".repeat(5_000) + "：、" + "从到".repeat(1_000)
        repeat(20) { removes("小蓝的大肥鱼", 2, text, plan) }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FishScorePolicyTest) -> Unit) {
            val s = FishScorePolicyTest()
            block(s)
        }
        runIsolated { s -> s.`a typical fish account is removed even when the text alone looks harmless`() }
        runIsolated { s -> s.`no single weak dimension removes a comment at the standard threshold`() }
        runIsolated { s -> s.`veterans officials and the whitelist are never scored`() }
        runIsolated { s -> s.`unreadable signals are never scored`() }
        runIsolated { s -> s.`sensitivity moves the threshold and the boundary is inclusive`() }
        runIsolated { s -> s.`strong mode doubles the completed score and uses the selected threshold`() }
        runIsolated { s -> s.`name rules are capped and the generic fish fallback is small`() }
        runIsolated { s -> s.`level adds for new accounts and subtracts for veterans`() }
        runIsolated { s -> s.`text rules cap repeated hits and human relief only applies to weak text`() }
        runIsolated { s -> s.`weak generic phrases stay below the strong self-reference weight`() }
        runIsolated { s -> s.`mentioning an owner whose name matches the nickname adds score`() }
        runIsolated { s -> s.`a leading mention with no text or a long pitch is scored but a reply prefix is not`() }
        runIsolated { s -> s.`invisible and fullwidth tricks do not hide a fish`() }
        runIsolated { s -> s.`custom words score as strong evidence but never remove on their own for veterans`() }
        runIsolated { s -> s.`custom words need at least two characters and are bounded`() }
        runIsolated { s -> s.`settings become an immutable plan only when enabled`() }
        runIsolated { s -> s.`long adversarial text stays linear`() }
    
        FollowFeedCurrentHostAccessCases().verifyAllCases()
        FollowFeedHostAccessCases().verifyAllCases()
        FollowFeedPersonalCoverageCases().verifyAllCases()
        FollowFeedStyleCases().verifyAllCases()
        FollowFeedViewTypeHostAccessCases().verifyAllCases()
        FollowUpRecommendFeatureInstallerCases().verifyAllCases()
        FreeCopyGestureDetectorCases().verifyAllCases()
    }
}

private class FollowFeedCurrentHostAccessCases {
    class Item(val identity: Long, var j: Item? = null) {
        val n = ArrayList<Module>()
        fun a() = identity
        fun b(): List<Module> = j?.b() ?: n
        fun h() = j != null
    }
    open class Module(val a: Item) {
        init { a.n += this }
        fun M(): Item = a.j ?: a
        fun O() = true
    }
    class Video(item: Item) : Module(item)
    class Holder(var module: Module?)
    class AmbiguousHolder(var first: Module?, var second: Module?)
    class BadItem {
        var j: BadItem? = null
        val n = "not-a-module-list"
        fun a() = 42L
        fun b(): List<Any> = emptyList()
        fun h() = false
    }
    class BadModule(val a: BadItem) {
        fun M() = a
        fun O() = true
    }

    private fun access() = requireNotNull(FollowFeedHostAccess.current(Module::class.java, Item::class.java))

    fun ordinaryRowsUseTheFlattenedOwnerListByIdentity() {
        val item = Item(42)
        val first = Module(item); val last = Module(item)
        val access = access()
        val row = requireNotNull(access.row(Holder(first), 0))
        assertEquals(42L, row.identity)
        assertTrue(row.first); assertFalse(row.last); assertNull(row.reference)
        val end = requireNotNull(access.row(Holder(last), 1))
        assertFalse(end.first); assertTrue(end.last)
    }

    fun quotedRowsUseLocalNWhenBReturnsTheParentsFlattenedList() {
        val parent = Item(42)
        Module(parent)
        val quoted = Item(77, parent)
        val first = Module(quoted); val last = Video(quoted)
        parent.n.addAll(quoted.n)
        Module(parent)
        assertSame(parent.n, quoted.b())
        val access = access()
        for ((module, firstExpected, lastExpected) in listOf(Triple(first, true, false), Triple(last, false, true))) {
            val row = requireNotNull(access.row(Holder(module), 1))
            assertEquals(42L, row.identity); assertFalse(row.first); assertFalse(row.last)
            val reference = requireNotNull(row.reference)
            assertEquals(77L, reference.identity); assertEquals(firstExpected, reference.first)
            assertEquals(lastExpected, reference.last); assertTrue(reference.quoted)
        }
    }

    fun reboundHolderReadsNewOwnershipAndRejectsStaleLocalMembership() {
        val access = access()
        val parent = Item(42); val quote = Item(77, parent)
        val module = Module(quote); parent.n.addAll(quote.n)
        val holder = Holder(module)
        assertEquals(77L, access.row(holder, 1)?.reference?.identity)
        holder.module = Module(Item(88))
        assertEquals(88L, access.row(holder, 0)?.identity)
        assertNull(access.row(holder, 0)?.reference)
        holder.module = module
        quote.n.clear()
        assertNull(access.row(holder, 1))
        assertNull(access.row(AmbiguousHolder(module, module), 1))
    }

    fun invalidIdentitiesAndWrongLocalListAbiFailOpen() {
        assertNull(access().row(Holder(Module(Item(-1))), 0))
        val parent = Item(42); val quote = Item(0, parent)
        val module = Module(quote); parent.n.addAll(quote.n)
        assertNull(access().row(Holder(module), 1))
        assertNull(FollowFeedHostAccess.current(BadModule::class.java, BadItem::class.java))
    }

    fun booleanOIsNeverTreatedAsLocalOwnerAndUnknownVideoTypesDoNotGainFrames() {
        val access = access()
        assertFalse(access.embeddedVideoSupported)
        assertNull(FollowFeedHostAccess.legacy(Module::class.java, Item::class.java, Video::class.java))
        val item = Item(42)
        Module(item); val video = Video(item)
        assertNull(access.row(Holder(video), 1)?.reference)
    }

    fun cyclesAndOversizedModuleListsStopBeforeHostRecursionOrUnboundedTraversal() {
        val access = access()
        val cyclic = Item(42); val module = Module(cyclic); cyclic.j = cyclic
        assertNull(access.row(Holder(module), 0))
        assertEquals("parent-chain-unavailable", access.unavailableReason)
        val oversized = Item(77); val first = Module(oversized)
        repeat(FollowFeedHostAccess.MAX_ROW_MODULES) { Module(oversized) }
        assertNull(access.row(Holder(first), 0))
        assertEquals("local-module-budget", access.unavailableReason)
        val valid = Module(Item(88))
        assertEquals(88L, access.row(Holder(valid), 0)?.identity)
        assertNull(access.unavailableReason)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedCurrentHostAccessCases) -> Unit) {
            val s = FollowFeedCurrentHostAccessCases()
            block(s)
        }
        runIsolated { s -> s.ordinaryRowsUseTheFlattenedOwnerListByIdentity() }
        runIsolated { s -> s.quotedRowsUseLocalNWhenBReturnsTheParentsFlattenedList() }
        runIsolated { s -> s.reboundHolderReadsNewOwnershipAndRejectsStaleLocalMembership() }
        runIsolated { s -> s.invalidIdentitiesAndWrongLocalListAbiFailOpen() }
        runIsolated { s -> s.booleanOIsNeverTreatedAsLocalOwnerAndUnknownVideoTypesDoNotGainFrames() }
        runIsolated { s -> s.cyclesAndOversizedModuleListsStopBeforeHostRecursionOrUnboundedTraversal() }
    }
}

private class FollowFeedHostAccessCases {
    enum class Kind { Author, Desc, OpusDesc, OpusDescList, Topic, Archive, Footer }
    class Item(val id: Long, val parent: Item? = null) {
        val children = ArrayList<Module>()
        fun f() = id
        fun i(): List<Module> = children
    }
    open class Module(val item: Item, val kind: Kind) {
        init { item.children += this }
        fun M() = item.parent ?: item
        fun O() = item
        fun f0() = item.parent != null
        fun T() = kind
        fun d0() = M().children.firstOrNull() === this
        fun g0() = M().children.lastOrNull() === this
    }
    class Video(item: Item) : Module(item, Kind.Archive)
    class Holder(var module: Module?)

    private fun access(): FollowFeedHostAccess {
        val module = Module::class.java
        val item = Item::class.java
        return requireNotNull(FollowFeedHostAccess.legacy(module, item, Video::class.java))
    }

    fun quotedVideoAndDynamicUseTheirOwnBoundariesInsideTheParent() {
        for (videoQuote in listOf(false, true)) {
            val parent = Item(42)
            Module(parent, Kind.Author)
            Module(parent, Kind.Desc)
            val quoted = Item(77, parent)
            Module(quoted, Kind.Author)
            if (videoQuote) Video(quoted) else Module(quoted, Kind.Desc)
            parent.children.addAll(quoted.children)
            Module(parent, Kind.Footer)
            val access = access()
            quoted.children.forEachIndexed { index, module ->
                val row = requireNotNull(access.row(Holder(module), index + 2))
                val reference = requireNotNull(row.reference)
                assertEquals(42L, row.identity)
                assertFalse(row.first)
                assertFalse(row.last)
                assertEquals(77L, reference.identity)
                assertEquals(index == 0, reference.first)
                assertEquals(index == quoted.children.lastIndex, reference.last)
                assertTrue(reference.quoted)
            }
            assertNull(access.row(Holder(parent.children.last()), 4)?.reference)
        }
    }

    fun bodyWithAnEmbeddedVideoGetsAFrameButPlainVideoPostsDoNot() {
        for (kind in Kind.entries) {
            val item = Item(42)
            Module(item, Kind.Author)
            Module(item, kind)
            Module(item, Kind.Topic) // 话题等模块可能夹在正文和视频之间。
            val video = Video(item)
            Module(item, Kind.Footer)
            val row = requireNotNull(access().row(Holder(video), 3))
            if (kind in setOf(Kind.Desc, Kind.OpusDesc, Kind.OpusDescList)) {
                val reference = requireNotNull(row.reference)
                assertTrue(reference.first && reference.last)
                assertFalse(reference.quoted)
            } else assertNull(row.reference)
        }
        val item = Item(43)
        val video = Video(item)
        Module(item, Kind.Desc) // 视频之后的正文不把该视频变成正文引用。
        assertNull(access().row(Holder(video), 0)?.reference)
    }

    fun holderReuseRereadsReferenceIdentityAndRejectsStaleMembership() {
        val access = access()
        val parent = Item(42)
        val quoted = Item(77, parent)
        val holder = Holder(Video(quoted))
        parent.children.addAll(quoted.children)
        assertEquals(77L, access.row(holder, 1)?.reference?.identity)
        holder.module = Video(Item(43))
        val rebound = requireNotNull(access.row(holder, 2))
        assertEquals(43L, rebound.identity)
        assertNull(rebound.reference)
        holder.module!!.item.children.clear()
        assertNull(access.row(holder, 2))
        holder.module = null
        assertNull(access.row(holder, 2))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedHostAccessCases) -> Unit) {
            val s = FollowFeedHostAccessCases()
            block(s)
        }
        runIsolated { s -> s.quotedVideoAndDynamicUseTheirOwnBoundariesInsideTheParent() }
        runIsolated { s -> s.bodyWithAnEmbeddedVideoGetsAFrameButPlainVideoPostsDoNot() }
        runIsolated { s -> s.holderReuseRereadsReferenceIdentityAndRejectsStaleMembership() }
    }
}

private class FollowFeedPersonalCoverageCases {
    private val personal = "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment"
    private val decoration = "com.bilibili.bplus.followinglist.quick.consume.E"
    private val separator = "com.bilibili.bplus.followinglist.quick.consume.S"

    private val definitions = mapOf(
        FollowFeedAdapter.FRAGMENT to followtest.roles.MainPage::class.java,
        personal to followtest.roles.PersonalPage::class.java,
        decoration to followtest.roles.PersonalDecoration::class.java,
        separator to followtest.roles.PersonalSeparator::class.java
    )

    private fun RolesLoader(absent: Set<String> = emptySet(), missingMethod: String? = null): ClassLoader {
        val rawClasses = if (missingMethod != null && personal !in absent) {
            val tpl = followtest.roles.PersonalPage::class.java
            val raw = requireNotNull(tpl.getResourceAsStream("/${tpl.name.replace('.', '/')}.class")).use { it.readBytes() }
            val renamed = TestRenamingClassLoader.rewriteUtf8Constants(
                raw,
                mapOf(
                    tpl.name.replace('.', '/') to personal.replace('.', '/'),
                    missingMethod to "missingRoleMethod"
                )
            )
            mapOf(personal to renamed)
        } else {
            emptyMap()
        }
        return TestRenamingClassLoader(
            parent = FollowFeedPersonalCoverageCases::class.java.classLoader!!,
            templates = definitions.filterKeys { it !in absent && it !in rawClasses },
            hiddenNames = absent,
            rawClasses = rawClasses
        )
    }

    private fun install(loader: ClassLoader, cached: FollowFeedPoints? = null,
        failId: String? = null): Pair<FeatureInstallResult, PlayerPortTestRegistrar> {
        val registrar = PlayerPortTestRegistrar(failId)
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        return FollowFeedFeatureInstaller(true, cached).install(environment) to registrar
    }

    fun completePersonalRoleRegistersAllSixEntrypoints() {
        val (result, registrar) = install(RolesLoader())
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        assertEquals(6, registrar.hooks.keys.count { it.startsWith("follow_feed.personal.") })
    }

    fun validHomeCacheRefreshesMissingOptionalPersonalRoles() {
        val loader = RolesLoader()
        val complete = requireNotNull(FollowFeedAdapter.direct(loader))
        val old = complete.copy(personalDecorationClass = null, personalSeparatorClass = null)
        val refreshed = requireNotNull(FollowFeedAdapter.resolve(loader, old))
        assertEquals(complete, refreshed.points)
        val (result, registrar) = install(loader, old)
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        assertEquals(6, registrar.hooks.keys.count { it.startsWith("follow_feed.personal.") })
    }

    fun existingPersonalOwnerWithMissingLifecycleOrVisibilityRemainsInTheDenominator() {
        for (method in listOf("onViewCreated", "onDestroyView", "setUserVisibleCompat")) {
            val loader = RolesLoader(missingMethod = method)
            assertEquals(personal, loader.loadClass(personal).name)
            val (result, registrar) = install(loader)
            assertTrue(result is FeatureInstallResult.Installed && !result.complete)
            assertTrue(registrar.hooks.keys.none { it.startsWith("follow_feed.personal.") })
        }
    }

    fun missingSeparatorOrBackgroundCannotExemptAnExistingPersonalPage() {
        for (missing in listOf(separator, decoration)) {
            val (result, registrar) = install(RolesLoader(absent = setOf(missing)))
            assertTrue(result is FeatureInstallResult.Installed && !result.complete)
            assertTrue(registrar.hooks.keys.none { it.startsWith("follow_feed.personal.") })
        }
    }

    fun absentWholeRoleIsNotApplicableButCachedRoleDescriptionStillCounts() {
        val absent = setOf(personal, separator, decoration)
        val (withoutRole, _) = install(RolesLoader(absent))
        assertTrue(withoutRole is FeatureInstallResult.Installed && withoutRole.complete)
        val cached = requireNotNull(FollowFeedAdapter.direct(RolesLoader()))
        val (describedRole, _) = install(RolesLoader(absent), cached)
        assertTrue(describedRole is FeatureInstallResult.Installed && !describedRole.complete)
    }

    fun personalRegistrationFailureCannotProduceACompleteReturn() {
        assertTrue(runCatching { install(RolesLoader(), failId = "follow_feed.personal.visibility") }.isFailure)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedPersonalCoverageCases) -> Unit) {
            val s = FollowFeedPersonalCoverageCases()
            block(s)
        }
        runIsolated { s -> s.completePersonalRoleRegistersAllSixEntrypoints() }
        runIsolated { s -> s.validHomeCacheRefreshesMissingOptionalPersonalRoles() }
        runIsolated { s -> s.existingPersonalOwnerWithMissingLifecycleOrVisibilityRemainsInTheDenominator() }
        runIsolated { s -> s.missingSeparatorOrBackgroundCannotExemptAnExistingPersonalPage() }
        runIsolated { s -> s.absentWholeRoleIsNotApplicableButCachedRoleDescriptionStillCounts() }
        runIsolated { s -> s.personalRegistrationFailureCannotProduceACompleteReturn() }
    }
}

private class FollowFeedStyleCases {
    fun compactHeaderOnlyKeepsUnconsumedStatusBarInset() {
        assertEquals(90, FollowFeedStyle.contentTopPadding(90, 0))
        assertEquals(0, FollowFeedStyle.contentTopPadding(90, 90))
        assertEquals(0, FollowFeedStyle.contentTopPadding(0, 0))
        assertEquals(0, FollowFeedStyle.contentTopPadding(24, 40))
        assertEquals(48, FollowFeedStyle.contentTopPadding(72, 24))
        assertEquals(160, FollowFeedStyle.segmentWidth(1f))
        assertEquals(240, FollowFeedStyle.segmentWidth(2f))
        assertEquals(46, FollowFeedStyle.segmentHeight(1f))
    }
    fun cardUsesAuditedStaticPresetWithOpaqueReadingSupport() {
        val card = FollowFeedStyle.card()
        assertEquals(SurfaceRole.CARD, card.role)
        assertEquals(LumenSurfaceMaterial.STATIC, card.material)
        assertFalse(card.sampling.enabled)
        assertEquals(1f, card.opacity, 0f)
        assertEquals(1f, card.fallbackTintOpacity, 0f)
        assertTrue(card.tintEnabled)
        assertEquals(16f, card.radiusDp, 0f)
        assertEquals(SurfaceRole.SELECTED_ITEM, FollowFeedStyle.selection().role)
        assertFalse(FollowFeedStyle.selection().sampling.enabled)
    }

    fun statusFusionUsesPublicFadingBandWithinTheSharedBudget() {
        val palette = FollowFeedStyle.palette(HostChromeColors(false, 0xFF5566DD.toInt()))
        val band = FollowFeedStyle.statusBand(palette, 90, 123)
        assertEquals(SurfaceRole.TOP_BAR, band.role)
        assertEquals(0f, band.radiusDp, 0f)
        assertFalse(band.edgeEnabled)
        assertTrue(band.sampling.enabled)
        assertTrue(band.sampling.fadeEnabled)
        assertFalse(band.sampling.refractionEnabled)
        assertEquals(0f, band.sampling.fadeHold, 0f)
        assertEquals(LumenSurfaceFadeCurve.LINEAR, band.sampling.fadeCurve)
        assertEquals(112f / 123f, band.sampling.fadeEnd, .0001f)
        assertTrue(band.sampling.fadeEnd < 1f)
        val zeroInset = FollowFeedStyle.statusBand(palette, 0, 12)
        assertEquals(0f, zeroInset.sampling.fadeHold, 0f)
        assertEquals(8f / 12f, zeroInset.sampling.fadeEnd, .0001f)
        for ((inset, height) in listOf(0 to 0, 135 to 168, 24 to 12)) {
            val fade = FollowFeedStyle.statusBand(palette, inset, height).sampling
            assertTrue(fade.fadeHold in 0f..fade.fadeEnd && fade.fadeEnd <= 1f)
        }
        assertEquals(palette.background, band.color)
        assertEquals(1f, band.fallbackTintOpacity, 0f)
        assertEquals(16, FollowFeedStyle.MAX_CARD_SURFACES + 3)
    }

    fun hostThemeFeedsSurfaceAndTextFromOnePalette() {
        for (dark in listOf(false, true)) {
            val palette = FollowFeedStyle.palette(HostChromeColors(dark, 0xFF5566DD.toInt()))
            assertEquals(0xFF5566DD.toInt(), palette.primary)
            assertEquals(255, palette.surface ushr 24)
            assertEquals(255, palette.background ushr 24)
            assertNotEquals(palette.textPrimary, palette.surface)
            assertNotEquals(palette.textSecondary, palette.surface)
            assertEquals(palette.surfaceVariant, FollowFeedStyle.track(palette).color)
            val reference = FollowFeedStyle.reference(palette)
            assertEquals(palette.surfaceVariant, reference.color)
            assertNotEquals(palette.surface, reference.color)
            assertEquals(LumenSurfaceMaterial.STATIC, reference.material)
            assertFalse(reference.sampling.enabled)
            assertTrue(reference.edgeEnabled)
            assertTrue(reference.radiusDp < FollowFeedStyle.card().radiusDp)
        }
    }

    fun allDynamicModuleCountsAndRecycledIdentitiesKeepTheirBoundaries() {
        // 视频、图文、转发的模块数量不同，统一以身份和首尾角色分组。
        for (count in listOf(3, 5, 9)) {
            val dynamic = (0 until count).map { FollowFeedRow(42, it == 0, it == count - 1, it + 7) }
            dynamic.zipWithNext().forEach { (a, b) -> assertTrue(FollowFeedGrouping.joins(a, b)) }
            assertFalse(FollowFeedGrouping.joins(dynamic.last(), FollowFeedRow(43, true, false, count + 7)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(43, false, false, 8)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(42, false, false, 9)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(42, true, false, 8)))
        }
        assertFalse(FollowFeedGrouping.joins(FollowFeedRow(-1, false, false, 0), FollowFeedRow(-1, false, false, 1)))
    }

    fun dockPaddingUsesCoverageUnionAcrossWindowSizes() {
        assertEquals(176, FollowFeedStyle.bottomPadding(48, 2340, 2196, 32, 32))
        assertEquals(176, FollowFeedStyle.bottomPadding(176, 2340, 2196, 32, 32))
        assertEquals(80, FollowFeedStyle.bottomPadding(80, 2080, 2196, 32, 32))
        assertEquals(144, FollowFeedStyle.bottomPadding(0, 1080, 968, 24, 32))
        assertEquals(32, FollowFeedStyle.bottomPadding(12, 1080, null, 32, 32))
    }

    fun prependingDoesNotConfuseStaleBindPositionsOrMembershipColors() {
        assertTrue(FollowFeedGrouping.joins(FollowFeedRow(42, true, false, 10),
            FollowFeedRow(42, false, true, 30), adjacent = true))
        assertFalse(FollowFeedGrouping.joins(FollowFeedRow(42, true, false, 10),
            FollowFeedRow(42, false, true, 11), adjacent = false))
        assertTrue(FollowFeedStyle.isNeutralText(0xFF212121.toInt()))
        assertFalse(FollowFeedStyle.isNeutralText(0xFFFF6699.toInt()))
        assertTrue(FollowFeedStyle.frequentHeight(2f) > FollowFeedStyle.frequentHeight(1f))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedStyleCases) -> Unit) {
            val s = FollowFeedStyleCases()
            block(s)
        }
        runIsolated { s -> s.compactHeaderOnlyKeepsUnconsumedStatusBarInset() }
        runIsolated { s -> s.cardUsesAuditedStaticPresetWithOpaqueReadingSupport() }
        runIsolated { s -> s.statusFusionUsesPublicFadingBandWithinTheSharedBudget() }
        runIsolated { s -> s.hostThemeFeedsSurfaceAndTextFromOnePalette() }
        runIsolated { s -> s.allDynamicModuleCountsAndRecycledIdentitiesKeepTheirBoundaries() }
        runIsolated { s -> s.dockPaddingUsesCoverageUnionAcrossWindowSizes() }
        runIsolated { s -> s.prependingDoesNotConfuseStaleBindPositionsOrMembershipColors() }
    }
}

private class FollowFeedViewTypeHostAccessCases {
    class Module(item: tS.H, viewType: Int) : tS.G(item, viewType)
    class Holder(var module: tS.G?)
    private fun access() = requireNotNull(FollowFeedHostAccess.current(tS.G::class.java, tS.H::class.java))

    fun eachOfficialDescriptionKindBeforeArchiveCreatesAFrameWithoutGuessingClassNamesOrIds() {
        for (kind in listOf(ModuleEnum.Desc, ModuleEnum.OpusDesc, ModuleEnum.OpusDescList)) {
            val item = tS.H()
            Module(item, kind.viewType())
            val archive = Module(item, ModuleEnum.Archive.viewType())
            val access = access()
            assertTrue(access.embeddedVideoSupported)
            val reference = requireNotNull(access.row(Holder(archive), 1)?.reference)
            assertEquals(42L, reference.identity)
            assertTrue(reference.first && reference.last); assertFalse(reference.quoted)
        }
    }

    fun laterDescriptionsUnrelatedKindsAndUnknownDescriptorsNeverGainVideoFrames() {
        val item = tS.H()
        Module(item, ModuleEnum.Author.viewType())
        val archive = Module(item, ModuleEnum.Archive.viewType())
        Module(item, ModuleEnum.Desc.viewType())
        assertNull(access().row(Holder(archive), 1)?.reference)
        val unknownItem = tS.H(); val unknown = Module(unknownItem, Int.MIN_VALUE)
        val access = access()
        assertNull(access.row(Holder(unknown), 0))
        assertEquals("module-descriptor-unavailable", access.unavailableReason)
    }

    fun conflictingOfficialViewTypesKeepCoreGroupingButDisableTheOptionalVideoFrame() {
        val field = ModuleEnum::class.java.getDeclaredField("type").apply { isAccessible = true }
        val original = field.getInt(ModuleEnum.Footer)
        try {
            field.setInt(ModuleEnum.Footer, ModuleEnum.Archive.viewType())
            val access = access()
            assertFalse(access.embeddedVideoSupported)
            val item = tS.H()
            Module(item, ModuleEnum.Desc.viewType())
            val archive = Module(item, ModuleEnum.Archive.viewType())
            val row = requireNotNull(access.row(Holder(archive), 1))
            assertEquals(42L, row.identity); assertNull(row.reference)
        } finally {
            field.setInt(ModuleEnum.Footer, original)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedViewTypeHostAccessCases) -> Unit) {
            val s = FollowFeedViewTypeHostAccessCases()
            block(s)
        }
        runIsolated { s -> s.eachOfficialDescriptionKindBeforeArchiveCreatesAFrameWithoutGuessingClassNamesOrIds() }
        runIsolated { s -> s.laterDescriptionsUnrelatedKindsAndUnknownDescriptorsNeverGainVideoFrames() }
        runIsolated { s -> s.conflictingOfficialViewTypesKeepCoreGroupingButDisableTheOptionalVideoFrame() }
    }
}

private class FollowUpRecommendFeatureInstallerCases {
    // 两个页面家族各自的仓库与请求协程体：协程体实现 Function2，构造参数为（仓库, Continuation）。
    class Repo2
    class Body2(@JvmField val owner: Repo2, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = error("invoked through Modern callback fixture")
    }
    class Repo1
    class Body1(@JvmField val owner: Repo1, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = error("invoked through Modern callback fixture")
    }
    /** 构造参数属于别的仓库：结构漂移，不能当成本家族的请求体。 */
    class ForeignBody(@JvmField val owner: Repo1, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = Unit
    }
    /** 不是挂起 lambda（未实现 Function2）。 */
    class PlainBody(@JvmField val owner: Repo2, @JvmField val completion: Continuation<*>) {
        fun invokeSuspend(result: Any): Any = Unit
    }

    // Ktor：语句持有唯一的请求构建器字段（混淆名 a），构建器 getUrl() 返回 URLBuilder。
    class UrlBuilder(private val segments: List<String>) { fun getEncodedPathSegments(): List<String> = segments }
    class RequestBuilder(private val url: UrlBuilder) { fun getUrl(): UrlBuilder = url }
    class Statement(@JvmField val a: RequestBuilder?) { fun execute(completion: Continuation<*>): Any? = error("fixture") }
    class StatementWithoutBuilder { fun execute(completion: Continuation<*>): Any? = error("fixture") }

    private class Fixture(
        repo2: Class<*>? = Repo2::class.java,
        body2: Class<*>? = Body2::class.java,
        repo1: Class<*>? = Repo1::class.java,
        body1: Class<*>? = Body1::class.java,
        statement: Class<*>? = Statement::class.java,
        process: String = "tv.danmaku.bili",
        failId: String? = null
    ) {
        var lookups = 0
        private val mapping = mapOf(
            FollowUpRecommendFeatureInstaller.Family.THESEUS2.repository to repo2,
            FollowUpRecommendFeatureInstaller.Family.THESEUS2.requestBody to body2,
            FollowUpRecommendFeatureInstaller.Family.THESEUS.repository to repo1,
            FollowUpRecommendFeatureInstaller.Family.THESEUS.requestBody to body1,
            FollowUpRecommendFeatureInstaller.KTOR_STATEMENT to statement,
            FollowUpRecommendFeatureInstaller.KTOR_REQUEST_BUILDER to RequestBuilder::class.java,
            FollowUpRecommendFeatureInstaller.KTOR_URL_BUILDER to UrlBuilder::class.java
        )
        val loader = object : ClassLoader(Repo2::class.java.classLoader) {
            override fun loadClass(name: String): Class<*> = if (name in mapping) {
                lookups++
                mapping[name] ?: throw ClassNotFoundException(name)
            } else super.loadClass(name)
        }
        val port = PlayerPortTestRegistrar(failId)
        val policies = linkedMapOf<String, HookExceptionPolicy>()
        val points = linkedMapOf<String, VersionAdapter.HookPoint>()
        val registrar = object : HookRegistrar by port {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                policies[id] = exceptionPolicy
                points[id] = point
                val owner = loader.loadClass(point.className)
                val params = point.paramClassNames.orEmpty().map { loader.loadClass(it) }.toTypedArray()
                port.exact(id, owner, point.methodName, *params, block = block)
            }
        }
        val stages = mutableListOf<FeatureRuntimeStage>()
        val errors = mutableListOf<String>()
        val statuses = mutableListOf<Pair<String, String>>()
        val capabilities = linkedMapOf<String, FeatureInstallResult>()
        val environment = HookEnvironment(process, loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { key, _ -> errors += key }, { key, value -> statuses += key to value },
            runtimeEvidence = { id, stage, _ -> assertEquals(FollowUpRecommendFeatureInstaller.ID, id); stages += stage },
            capabilityEvidence = { id, result -> capabilities[id] = result })

        fun install(enabled: Boolean = true) = FollowUpRecommendFeatureInstaller(enabled).install(environment)
        fun request(family: FollowUpRecommendFeatureInstaller.Family, body: Any, original: () -> Any?): Any? =
            port.invoke("${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.${family.key}", body, arrayOf<Any?>(Any())) { original() }
        fun execute(statement: Any, original: () -> Any?): Any? =
            port.invoke(FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR, statement, arrayOf<Any?>(null)) { original() }
    }

    private fun statement(vararg segments: String) = Statement(RequestBuilder(UrlBuilder(segments.toList())))

    fun `disabled or non main process performs no host lookup or registration`() {
        for ((enabled, process) in listOf(false to "tv.danmaku.bili", true to "tv.danmaku.bili:web")) {
            val f = Fixture(process = process)
            assertTrue(f.install(enabled) is FeatureInstallResult.Skipped)
            assertEquals(0, f.lookups)
            assertTrue(f.port.registrationAttempts.isEmpty())
            assertTrue(f.capabilities.isEmpty())
        }
        assertTrue(FollowUpRecommendFeatureInstaller(false).capabilityIds.isEmpty())
    }

    fun `both page families and the ktor transport install as separate counted units`() {
        val f = Fixture()
        assertEquals(FeatureInstallResult.Installed(3, complete = true), f.install())
        assertEquals(FeatureInstallResult.Installed(2, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
        assertEquals(FeatureInstallResult.Installed(1, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK])
        assertEquals(listOf("theseus2", "theseus").map { "${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.$it" } +
            FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR, f.port.registrationAttempts.map { it.first })
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, f.policies[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR])
        assertEquals(listOf(FollowUpRecommendFeatureInstaller.CONTINUATION),
            f.points[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR]?.paramClassNames)
        assertEquals(FollowUpRecommendFeatureInstaller.STATUS to "theseus2:installed,theseus:installed,ktor:installed",
            f.statuses.single())
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    fun `request bodies finish with the host unit and never run the original request`() {
        val f = Fixture(); f.install()
        val completion = object : Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }
        for ((family, body) in listOf(FollowUpRecommendFeatureInstaller.Family.THESEUS2 to Body2(Repo2(), completion),
                FollowUpRecommendFeatureInstaller.Family.THESEUS to Body1(Repo1(), completion))) {
            repeat(3) { assertSame(Unit, f.request(family, body) { throw AssertionError("recommendation request ran") }) }
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
        assertTrue(f.errors.isEmpty())
    }

    fun `the network guard fails only the exact recommendation path before any network work`() {
        val f = Fixture(); f.install()
        var network = 0
        for (target in listOf(statement("", "x", "v2", "view", "upper", "recmd"), statement("x", "v2", "view", "upper", "recmd", ""))) {
            val error = assertThrows(IOException::class.java) { f.execute(target) { network++; "response" } }
            assertEquals(FollowUpRecommendFeatureInstaller.BLOCKED_MESSAGE, error.message)
        }
        assertEquals(0, network)
        // 个人空间同名推荐、详情页其他接口与更深的路径都原样放行，返回值保持同一对象。
        for (target in listOf(statement("", "x", "v2", "space", "upper", "recmd"), statement("", "x", "v2", "view"),
                statement("", "x", "v2", "view", "upper", "recmd", "more"), statement(""), Statement(null))) {
            val response = Any()
            assertSame(response, f.execute(target) { network++; response })
        }
        assertEquals(5, network)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
    }

    fun `path matching is exact on non empty segments`() {
        assertTrue(matchesRecommendPath(listOf("", "x", "v2", "view", "upper", "recmd")))
        assertTrue(matchesRecommendPath(listOf("x", "v2", "view", "upper", "recmd")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "space", "upper", "recmd")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "view", "upper")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "view", "upper", "recmd", "1")))
        assertFalse(matchesRecommendPath(listOf("", "X", "v2", "view", "upper", "recmd")))
        assertFalse(matchesRecommendPath(emptyList<String>()))
        assertFalse(matchesRecommendPath(listOf(null, "x", "v2", "view", "upper", "recmd")))
    }

    fun `a drifted family stays in the denominator while the other units keep working`() {
        for (drifted in listOf(null, PlainBody::class.java, ForeignBody::class.java)) {
            val f = Fixture(body2 = drifted)
            assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
            assertEquals(FeatureInstallResult.Installed(1, complete = false), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
            assertTrue(f.statuses.single().second.startsWith("theseus2:${FollowUpRecommendFeatureInstaller.STATE_MISSING_BODY},"))
        }
    }

    fun `older hosts without the compose family are complete with the view family alone`() {
        val f = Fixture(repo2 = null, body2 = null)
        assertEquals(FeatureInstallResult.Installed(2, complete = true), f.install())
        assertEquals(FeatureInstallResult.Installed(1, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
        assertEquals("theseus:installed,ktor:installed", f.statuses.single().second)
    }

    fun `a ktor statement without a unique request builder is a missing transport not a crash`() {
        val f = Fixture(statement = StatementWithoutBuilder::class.java)
        assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
        val network = f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK] as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE, network.reasonCode)
        assertNull(f.port.hooks[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR])
    }

    fun `hosts without any recommendation family or ktor are not applicable`() {
        val f = Fixture(repo2 = null, body2 = null, repo1 = null, body1 = null, statement = null)
        val result = f.install() as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.NOT_APPLICABLE_HOST, result.reasonCode)
        assertTrue(f.port.registrationAttempts.isEmpty())
        assertTrue(f.stages.isEmpty())
    }

    fun `registration failure of the network layer leaves the request layer installed`() {
        val f = Fixture(failId = FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR)
        assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK])
        assertEquals(listOf("follow_up_recommend_network_ktor"), f.errors)
        assertNotNull(f.port.hooks["${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.theseus2"])
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FollowUpRecommendFeatureInstallerCases) -> Unit) {
            val s = FollowUpRecommendFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`disabled or non main process performs no host lookup or registration`() }
        runIsolated { s -> s.`both page families and the ktor transport install as separate counted units`() }
        runIsolated { s -> s.`request bodies finish with the host unit and never run the original request`() }
        runIsolated { s -> s.`the network guard fails only the exact recommendation path before any network work`() }
        runIsolated { s -> s.`path matching is exact on non empty segments`() }
        runIsolated { s -> s.`a drifted family stays in the denominator while the other units keep working`() }
        runIsolated { s -> s.`older hosts without the compose family are complete with the view family alone`() }
        runIsolated { s -> s.`a ktor statement without a unique request builder is a missing transport not a crash`() }
        runIsolated { s -> s.`hosts without any recommendation family or ktor are not applicable`() }
        runIsolated { s -> s.`registration failure of the network layer leaves the request layer installed`() }
    }
}

private class FreeCopyGestureDetectorCases {
    private val runnable = Runnable {}
    private fun detector(threshold: Long = 400L) = FreeCopyGestureDetector(runnable, threshold)
    private fun gesture(slop: Float = 8f, threshold: Long = 400L) =
        detector(threshold).apply { begin(1_000L, 1_000L, 7, 100f, 200f, slop) }

    // ===== 相位机：事件时钟认领 =====
    fun stationaryPressClaimsOnceAtThreshold() {
        val g = gesture()
        assertFalse(g.claim(1_000L, 1_399L))
        assertTrue(g.claim(1_000L, 1_400L))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun thresholdIsConfigurableAndBoundariesAreExact() {
        val g = gesture(threshold = 500L)
        assertFalse(g.claim(1_000L, 1_499L))
        assertTrue(g.claim(1_000L, 1_500L))
    }

    fun upClaimUsesTheSameLineAsHeldClaimWithNoExtraMargin() {
        // 按住中与 UP 共用同一条线：阈值时刻气泡已弹，UP 不再把 [T, T+150ms) 改判成点按。
        val g = gesture()
        assertFalse(g.resolveTerminal(1_000L, 1_399L, isUp = true, alreadyHandled = false) ==
            FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME)
        assertTrue(g.isPending)
        assertEquals(FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME,
            g.resolveTerminal(1_000L, 1_400L, isUp = true, alreadyHandled = false))
        assertTrue(g.isHandled)
    }

    fun resolveTerminalHandsEverythingExceptALongHoldBackToTheHost() {
        val host = FreeCopyGestureDetector.Terminal.HAND_TO_HOST
        // 短按（含 60ms 快点按与刚好差 1ms）：不认领、不消费，会话保持 PENDING 等宿主清理。
        for (held in listOf(30L, 60L, 120L, 399L)) {
            val g = gesture()
            assertEquals("held=$held", host, g.resolveTerminal(1_000L, 1_000L + held, true, false))
            assertTrue(g.isPending)
        }
        // CANCEL 永不消费：即使按住已超线也一样（父容器拦截/系统取消不能被当成长按）。
        val cancel = gesture()
        assertEquals(host, cancel.resolveTerminal(1_000L, 2_000L, isUp = false, alreadyHandled = false))
        assertTrue(cancel.isPending)
        // 会话已取消（滑动超坡度/多指）：UP 超线也交还宿主。
        val moved = gesture()
        assertTrue(moved.sample(1_000L, 7, 1, 100f, 230f))
        assertEquals(host, moved.resolveTerminal(1_000L, 2_000L, isUp = true, alreadyHandled = false))
        // 另一条触摸流的 UP 不匹配当前会话：不认领。
        val other = gesture()
        assertEquals(host, other.resolveTerminal(999L, 2_000L, isUp = true, alreadyHandled = false))
        assertTrue(other.isPending)
        // 事件时间早于按下时间（乱序/伪造）不认领。
        val skew = gesture()
        assertEquals(host, skew.resolveTerminal(1_000L, 900L, isUp = true, alreadyHandled = false))
    }

    fun alreadyShownBubbleConsumesOnlyTheUpAndLetsCancelThrough() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        assertEquals(FreeCopyGestureDetector.Terminal.CONSUME,
            g.resolveTerminal(1_000L, 1_500L, isUp = true, alreadyHandled = true))
        assertEquals(FreeCopyGestureDetector.Terminal.HAND_TO_HOST,
            g.resolveTerminal(1_000L, 1_500L, isUp = false, alreadyHandled = true))
    }

    fun heldPressStillClaimsAtPlainThresholdWhileFingerIsDown() {
        // 按住中（到期 Runnable/宿主监听器）走 claim，即时反馈不延迟。
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
    }

    fun shortTapAndSmallJitterStayWithHost() {
        val g = gesture()
        assertFalse(g.sample(1_000L, 7, 1, 103f, 204f))
        assertFalse(g.claim(1_000L, 1_100L))
        assertTrue(g.isPending)
    }

    fun slowScrollBelowOldSixtyPixelsCancelsAllLongPressSources() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 100f, 209f))
        assertFalse(g.claim(1_000L, 1_400L)) // 定时器
        assertFalse(g.claim(1_000L, 1_500L)) // 系统监听器
        assertFalse(g.claim(1_000L, 2_000L)) // UP 兜底
    }

    fun returningToDownPointDoesNotRearmAfterScroll() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 120f, 200f))
        assertFalse(g.sample(1_000L, 7, 1, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_600L))
    }

    fun historicalExcursionCancelsEvenWhenLatestPointIsInsideSlop() {
        val g = gesture()
        for (y in listOf(202f, 215f, 200f)) g.sample(1_000L, 7, 1, 100f, y)
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun upCoordinatesAlsoRejectUndeliveredMovement() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 100f, 230f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun diagonalMovementUsesSameRadialBudgetAsAxialMovement() {
        val g = gesture()
        assertFalse(g.sample(1_000L, 7, 1, 104f, 206f))
        assertTrue(g.sample(1_000L, 7, 1, 106f, 206f))
    }

    fun deviceSlopControlsCancellationRatherThanPixelMagicNumber() {
        val small = gesture(8f)
        val large = gesture(24f)
        assertTrue(small.sample(1_000L, 7, 1, 100f, 216f))
        assertFalse(large.sample(1_000L, 7, 1, 100f, 216f))
        assertTrue(large.claim(1_000L, 1_400L))
    }

    fun parentCancellationIsIrreversibleWithinSameStream() {
        val g = gesture()
        assertTrue(g.cancel(1_000L))
        assertFalse(g.cancel(1_000L))
        assertTrue(g.matches(1_000L))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun secondPointerCancelsCandidateWithoutChoosingAnotherFinger() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 2, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun missingOriginalPointerFailsClosed() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 9, 1, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    fun eventsFromAnotherStreamDoNotCancelOrClaimCurrentGesture() {
        val g = gesture()
        assertFalse(g.sample(999L, 7, 1, 1_000f, 1_000f))
        assertFalse(g.cancel(999L))
        assertFalse(g.claim(999L, 1_500L))
        assertTrue(g.claim(1_000L, 1_400L))
    }

    fun replacementGestureRejectsOldCallbackAndOldTerminalEvent() {
        val g = gesture()
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        assertFalse(g.claim(1_000L, 3_000L))
        assertFalse(g.cancel(1_000L))
        assertFalse(g.claim(2_000L, 2_100L))
        assertTrue(g.claim(2_000L, 2_400L))
    }

    fun completedLongPressCannotBeClaimedAgainByMovementOrAnotherEntry() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        assertFalse(g.sample(1_000L, 7, 1, 100f, 500f))
        assertFalse(g.cancel(1_000L))
        assertFalse(g.claim(1_000L, 1_600L))
    }

    fun terminationRestoresNonTouchAndNextGestureEligibility() {
        val g = gesture()
        g.cancel(1_000L)
        g.reset()
        assertFalse(g.isTracking)
        assertFalse(g.claim(1_000L, 2_000L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f)
        assertTrue(g.claim(2_000L, 2_400L))
    }

    fun invalidSamplesCannotOpenPopup() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, Float.NaN, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, Float.POSITIVE_INFINITY)
        assertFalse(g.isPending)
        assertFalse(g.claim(2_000L, 2_500L))
    }

    fun bubbleDismissRestoresNonTouchEligibilityAfterHandledGesture() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        g.finishHandled()
        assertFalse(g.isTracking)
    }

    fun oldBubbleDismissCannotDiscardNewGestureDuringExitAnimation() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        g.finishHandled()
        assertTrue(g.isPending)
        assertTrue(g.claim(2_000L, 2_400L))
    }

    // ===== 拥塞回归（本次修复核心）：事件时钟权威，短按 UP 不被迟到 Runnable 误判 =====
    fun congestedShortTapUpReturnsToHostEvenIfRunnableFiresLate() {
        val g = gesture()
        // DOWN 观测后主线程拥塞：UP 事件时长仅 120ms（真实短按），但 Runnable 迟到至 600ms 才执行。
        // UP 先派发：事件时钟复核失败 → 不认领，会话取消，交还宿主点击（标题展开/评论三点）。
        assertFalse("短按 UP 事件时长 < 阈值，绝不认领", g.claim(1_000L, 1_120L))
        g.cancel(1_000L)
        // 迟到的 Runnable 再尝试认领：会话已 CANCELLED，仍失败 → 不弹泡。
        assertFalse("迟到 Runnable 不得把短按误判成长按", g.claim(1_000L, 1_600L))
        assertFalse(g.isHandled)
    }

    fun genuineHoldClaimsEvenWhenRunnableIsCongested() {
        val g = gesture()
        // 真实按住 600ms（事件时长 >= 阈值），即使 Runnable 迟到，UP 复核仍认领。
        assertTrue(g.claim(1_000L, 1_600L))
        assertTrue(g.isHandled)
    }

    fun armedOnlyAfterArmWindowWhilePending() {
        val g = gesture()
        assertFalse(g.isArmed(1_000L + 299L))
        assertTrue(g.isArmed(1_000L + 300L))
        g.claim(1_000L, 1_400L)
        assertFalse("已认领不再是候选武装", g.isArmed(1_000L + 400L))
        g.reset()
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f)
        g.cancel(2_000L)
        assertFalse("已让出滑动不再武装", g.isArmed(2_000L + 400L))
    }

    fun sessionIdentityIsolatesStaleRunnableAcrossGestures() {
        val g = gesture()
        val first = g.sessionId
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        val second = g.sessionId
        assertTrue("新手势会话序号递增", second != first)
        // 旧会话的到期回调按旧 sessionId 找不到当前会话 → 不弹。
        assertFalse(g.matches(1_000L))
        assertTrue(g.matches(2_000L))
    }

    // ===== 单次调度（平移自 CommentLongPressTimerTest）=====
    private class FakeHandler(var accepts: Boolean = true) : FreeCopyGestureDetector.Poster {
        val queue = ArrayList<Pair<Runnable, Long>>()
        var removes = 0
        var posts = 0
        override fun remove(runnable: Runnable) { removes++; queue.removeAll { it.first === runnable } }
        override fun post(runnable: Runnable, delayMillis: Long): Boolean {
            posts++
            if (!accepts) return false
            queue += runnable to delayMillis
            return true
        }
    }

    fun oneDownThroughManyDispatchLevelsPostsOnce() {
        val g = detector(); val handler = FakeHandler()
        val levels = 12
        val posted = (1..levels).count { g.schedule(sessionId = 7, delayMillis = 400, poster = handler) }
        assertEquals(1, posted)
        assertEquals(1, handler.queue.size)
        assertEquals(1, handler.posts)
        assertEquals(1, handler.removes)
        assertEquals(400L, handler.queue.single().second)
        assertTrue(g.isScheduled)
    }

    fun aNewGestureSessionSchedulesAgain() {
        val g = detector(); val handler = FakeHandler()
        assertTrue(g.schedule(1, 400, handler))
        g.cancelSchedule(handler)
        assertFalse(g.isScheduled)
        assertTrue(handler.queue.isEmpty())
        assertTrue(g.schedule(2, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    fun cancelRemovesThePendingMessageAndAllowsReschedulingTheSameSession() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(3, 400, handler)
        g.cancelSchedule(handler)
        assertTrue(handler.queue.isEmpty())
        assertTrue(g.schedule(3, 400, handler))
    }

    fun postFailureDoesNotMarkTheSessionSoALaterLevelCanRetry() {
        val g = detector(); val handler = FakeHandler(accepts = false)
        assertFalse(g.schedule(5, 400, handler))
        assertFalse(g.isScheduled)
        handler.accepts = true
        assertTrue(g.schedule(5, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    fun afterFiringTheSameSessionMayBeRescheduledByALaterLevel() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(9, 400, handler)
        handler.queue.clear()
        g.onFired()
        assertFalse(g.isScheduled)
        assertTrue(g.schedule(9, 100, handler))
    }

    fun sessionZeroIsNeverScheduledAndNegativeDelayIsClamped() {
        val g = detector(); val handler = FakeHandler()
        assertFalse(g.schedule(0, 400, handler))
        assertEquals(0, handler.posts)
        assertTrue(g.schedule(1, -50, handler))
        assertEquals(0L, handler.queue.single().second)
    }

    fun cancelWithoutAHandlerStillResetsTheState() {
        val g = detector()
        g.schedule(4, 400, FakeHandler())
        g.cancelSchedule(null)
        assertFalse(g.isScheduled)
    }

    fun resetAlsoClearsScheduleSoSameIdNewSessionIsNotMistakenAsScheduled() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(4, 400, handler)
        assertTrue(g.isScheduled)
        // 只 reset 未 cancelSchedule 的路径：调度标记必须一并复位，同号新会话仍可投递。
        g.reset()
        assertFalse(g.isScheduled)
        assertTrue(g.schedule(4, 400, handler))
    }

    fun beginThresholdOverridesDefaultAndFallsBackOnNonPositive() {
        val g = detector(threshold = 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, 8f, thresholdMs = 500L)
        assertEquals(500L, g.thresholdMs)
        assertFalse(g.claim(1_000L, 1_400L))
        assertTrue(g.claim(1_000L, 1_500L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f, thresholdMs = 0L)
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, g.thresholdMs)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FreeCopyGestureDetectorCases) -> Unit) {
            val s = FreeCopyGestureDetectorCases()
            block(s)
        }
        runIsolated { s -> s.stationaryPressClaimsOnceAtThreshold() }
        runIsolated { s -> s.thresholdIsConfigurableAndBoundariesAreExact() }
        runIsolated { s -> s.upClaimUsesTheSameLineAsHeldClaimWithNoExtraMargin() }
        runIsolated { s -> s.resolveTerminalHandsEverythingExceptALongHoldBackToTheHost() }
        runIsolated { s -> s.alreadyShownBubbleConsumesOnlyTheUpAndLetsCancelThrough() }
        runIsolated { s -> s.heldPressStillClaimsAtPlainThresholdWhileFingerIsDown() }
        runIsolated { s -> s.shortTapAndSmallJitterStayWithHost() }
        runIsolated { s -> s.slowScrollBelowOldSixtyPixelsCancelsAllLongPressSources() }
        runIsolated { s -> s.returningToDownPointDoesNotRearmAfterScroll() }
        runIsolated { s -> s.historicalExcursionCancelsEvenWhenLatestPointIsInsideSlop() }
        runIsolated { s -> s.upCoordinatesAlsoRejectUndeliveredMovement() }
        runIsolated { s -> s.diagonalMovementUsesSameRadialBudgetAsAxialMovement() }
        runIsolated { s -> s.deviceSlopControlsCancellationRatherThanPixelMagicNumber() }
        runIsolated { s -> s.parentCancellationIsIrreversibleWithinSameStream() }
        runIsolated { s -> s.secondPointerCancelsCandidateWithoutChoosingAnotherFinger() }
        runIsolated { s -> s.missingOriginalPointerFailsClosed() }
        runIsolated { s -> s.eventsFromAnotherStreamDoNotCancelOrClaimCurrentGesture() }
        runIsolated { s -> s.replacementGestureRejectsOldCallbackAndOldTerminalEvent() }
        runIsolated { s -> s.completedLongPressCannotBeClaimedAgainByMovementOrAnotherEntry() }
        runIsolated { s -> s.terminationRestoresNonTouchAndNextGestureEligibility() }
        runIsolated { s -> s.invalidSamplesCannotOpenPopup() }
        runIsolated { s -> s.bubbleDismissRestoresNonTouchEligibilityAfterHandledGesture() }
        runIsolated { s -> s.oldBubbleDismissCannotDiscardNewGestureDuringExitAnimation() }
        runIsolated { s -> s.congestedShortTapUpReturnsToHostEvenIfRunnableFiresLate() }
        runIsolated { s -> s.genuineHoldClaimsEvenWhenRunnableIsCongested() }
        runIsolated { s -> s.armedOnlyAfterArmWindowWhilePending() }
        runIsolated { s -> s.sessionIdentityIsolatesStaleRunnableAcrossGestures() }
        runIsolated { s -> s.oneDownThroughManyDispatchLevelsPostsOnce() }
        runIsolated { s -> s.aNewGestureSessionSchedulesAgain() }
        runIsolated { s -> s.cancelRemovesThePendingMessageAndAllowsReschedulingTheSameSession() }
        runIsolated { s -> s.postFailureDoesNotMarkTheSessionSoALaterLevelCanRetry() }
        runIsolated { s -> s.afterFiringTheSameSessionMayBeRescheduledByALaterLevel() }
        runIsolated { s -> s.sessionZeroIsNeverScheduledAndNegativeDelayIsClamped() }
        runIsolated { s -> s.cancelWithoutAHandlerStillResetsTheState() }
        runIsolated { s -> s.resetAlsoClearsScheduleSoSameIdNewSessionIsNotMistakenAsScheduled() }
        runIsolated { s -> s.beginThresholdOverridesDefaultAndFallsBackOnNonPositive() }
    }
}
