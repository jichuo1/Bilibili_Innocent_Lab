package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishScorePolicyTest {

    private fun plan(
        sensitivity: Sensitivity = Sensitivity.STANDARD,
        protectHighLevel: Boolean = true,
        nicknameWords: String = "",
        textWords: String = "",
        whitelist: String = ""
    ) = requireNotNull(
        Config(true, sensitivity.id, nicknameWords, textWords, whitelist, protectHighLevel).plan()
    )

    private fun removes(
        name: String?,
        level: Int?,
        message: String?,
        plan: FishScorePolicy.Plan = plan(),
        mid: Long? = null
    ) = FishScorePolicy.shouldRemove(plan, name, mid, level, message)

    @Test
    fun `a typical fish account is removed even when the text alone looks harmless`() {
        assertTrue(removes("小蓝的大肥鱼", 2, "这个视频讲得挺清楚的"))
        assertTrue(removes("小蓝的大肥鱼", 2, null))
        assertTrue(removes("普通昵称", 3, "本鲸来汇报啦（摇了摇尾巴）@阿离 主人快看"))
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `level adds for new accounts and subtracts for veterans`() {
        assertEquals(14, FishScorePolicy.levelScore(0))
        assertEquals(14, FishScorePolicy.levelScore(2))
        assertEquals(6, FishScorePolicy.levelScore(3))
        assertEquals(0, FishScorePolicy.levelScore(4))
        assertEquals(0, FishScorePolicy.levelScore(5))
        assertEquals(-10, FishScorePolicy.levelScore(6))
        assertEquals(0, FishScorePolicy.levelScore(null))
    }

    @Test
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

    @Test
    fun `weak generic phrases stay below the strong self-reference weight`() {
        val strong = FishScorePolicy.textScore(TextNormalizer.forMatching("本鲸今天也很开心地来了呢"), null)
        val weak = FishScorePolicy.textScore(TextNormalizer.forMatching("今天来打卡，顺便看看大家都在聊什么内容呢"), null)
        assertEquals(50, strong)
        assertTrue(weak in 0..12)
    }

    @Test
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

    @Test
    fun `a leading mention with no text or a long pitch is scored but a reply prefix is not`() {
        val bare = FishScorePolicy.textScore("@张三", null)
        val longPitch = FishScorePolicy.textScore("@张三 这个视频整理得很完整，建议收藏", null)
        val reply = FishScorePolicy.textScore("回复 @张三 :这个视频整理得很完整，建议收藏", null)
        assertEquals(15 - 8, bare) // 纯 @ 15，短文本减 8
        assertTrue(longPitch >= 20)
        assertTrue(reply < longPitch)
    }

    @Test
    fun `invisible and fullwidth tricks do not hide a fish`() {
        assertTrue(removes("Ｄｅｅｐ​Ｓｅｅｋ助手", 2, null))
        assertEquals(50, FishScorePolicy.textScore(TextNormalizer.forMatching("本​鲸来了"), null))
        assertEquals(50, FishScorePolicy.textScore(TextNormalizer.forMatching("本鲸来了"), null))
    }

    @Test
    fun `custom words score as strong evidence but never remove on their own for veterans`() {
        val custom = plan(nicknameWords = "鲨鱼酱", textWords = "主人安好")
        assertTrue(removes("深海的鲨鱼酱", 2, null, custom))
        assertFalse(removes("深海的鲨鱼酱", 4, null, custom)) // 50 + 0 < 60
        assertTrue(removes("路人", 2, "主人安好，今天也在认真刷视频", custom))
        assertFalse(removes("路人", 6, "主人安好，今天也在认真刷视频", custom))
        assertFalse(removes("深海的鲨鱼酱", 2, null, plan()))
    }

    @Test
    fun `custom words need at least two characters and are bounded`() {
        assertEquals(setOf("鲨鱼", "ab"), FishScorePolicy.customWords("鲸，a，鲨鱼；AB\n"))
        assertTrue(FishScorePolicy.customWords("鲸,a, ").isEmpty())
        val many = (1..200).joinToString(",") { "词语$it" }
        assertEquals(FishScorePolicy.MAX_CUSTOM_WORDS, FishScorePolicy.customWords(many).size)
    }

    @Test
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

    @Test(timeout = 10_000)
    fun `long adversarial text stays linear`() {
        val plan = plan()
        val text = "（" + "尾巴".repeat(2_000) + "@" + "a".repeat(5_000) + "：、" + "从到".repeat(1_000)
        repeat(20) { removes("小蓝的大肥鱼", 2, text, plan) }
    }
}
