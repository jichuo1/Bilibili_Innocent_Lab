package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「屏蔽 AI 评论与回复」只是评论过滤的第五条判据：不新增 Hook 点、不新建过滤通道，热路径不编译正则、
 * 不读配置、不碰磁盘和网络。这些是后来者最容易"顺手"破坏的约束，所以钉在源码上。
 */
class CommentFishFilterContractTest {
    private val installer = SourceContract.read("hook/feature/CommentFilterFeatureInstaller.kt")
    private val policy = SourceContract.read("hook/feature/FishScorePolicy.kt")
    private val echo = SourceContract.read("hook/feature/FishRootEcho.kt")

    @Test fun fishJudgementReusesTheSharedGetterHooksAndAddsNoHookPoint() {
        // 注册点仍只有列表、置顶两族 getter 和一条 Kotlin 通道；AI 判据没有自己的注册 id。
        assertFalse(installer.contains("comment.fish"))
        assertFalse(installer.contains("fish.list"))
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.list\\.").findAll(installer).count())
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.top\\.").findAll(installer).count())
        assertTrue(installer.contains("installKotlinChannel(environment, accessors, plan)"))
    }

    @Test fun javaAndKotlinChannelsShareOneDecisionThatKeepsTheDearestCriterionLast() {
        // 单一判定入口：AI 打分只在 shouldRemove 里调用一次，Java 与 Kotlin 两条通道都经过它。
        assertEquals(1, Regex("FishScorePolicy\\.shouldRemove\\(").findAll(installer).count())
        val decision = installer.after("internal fun shouldRemove(\n            signals: Signals,\n            plan: JudgementPlan")
            .before("兼容旧签名的窄入口")
        assertTrue(decision.indexOf("RuleSetCodec.matches(plan.keywords") < decision.indexOf("FishScorePolicy.shouldRemove("))
        assertTrue(decision.indexOf("plan.userRules.matches(") < decision.indexOf("FishScorePolicy.shouldRemove("))
        val kotlin = installer.after("private fun kotlinDecide(").before("回调是本模块创建的动态代理")
        assertTrue(kotlin.contains("shouldRemove(readSignals(reply, accessors, plan, root), plan)"))
        val list = installer.after("environment.registrar.adapted(\"comment.filter.list.\$index\", point)")
            .before("environment.registrar.adapted(\"comment.filter.top.\$index\"")
        assertTrue(list.contains("ruleVerdictMemo.getOrCompute(source)"))
        assertTrue(list.contains("shouldRemove(readSignals(reply, accessors, plan), plan)"))
        // 关闭时完全不走记忆化那一支：既有判据的行为逐字不变。
        assertTrue(list.contains("if (plan.fish != null) {"))
    }

    @Test fun signalsAreReadOnlyForTheCriteriaThatNeedThem() {
        val read = installer.after("private fun readSignals(").before("先读计数")
        assertTrue(read.contains("val needAuthor = !plan.userRules.isEmpty() || fish != null"))
        // UID 只在发布者规则或 AI 白名单里写了 UID 时才读。
        assertTrue(read.contains("fish?.whitelist?.mids?.isNotEmpty() == true"))
        // 白名单读不到的一半无法保护任何人：整条判据不装，而不是在保护失效时静默删。
        assertTrue(installer.contains("fish.whitelist.available(accessors.hasAuthorNamePath, accessors.hasAuthorMidPath) == fish.whitelist"))
    }

    @Test fun capabilityCoverageCountsTheCriterionWhenEnabledButUnreadable() {
        assertTrue(installer.contains("if (fishPlan != null) add(\"comments_fish_filter_enabled\")"))
        assertTrue(installer.contains("\"comments_fish_filter_enabled\" -> plan.fish != null && accessors.hasMessagePath &&"))
        assertTrue(installer.contains("else degraded += \"fish\""))
    }

    @Test fun scorerHotPathCompilesNoRegexAndTouchesNoConfigDiskOrNetwork() {
        // 规则在类加载时预编译一次；打分函数里不许再出现 Regex(。
        val hot = policy.after("fun shouldRemove(")
        assertFalse(hot.contains("Regex("))
        assertFalse(hot.contains(".toRegex()"))
        val echoHot = echo.after("fun points(")
        assertFalse(echoHot.contains("Regex("))
        assertFalse(echoHot.contains(".toRegex()"))
        for (forbidden in listOf(
            "import android", "SharedPreferences", "java.io.", "java.net.", "okhttp", "HttpURLConnection",
            "File(", "System.currentTimeMillis", "Thread", "runBlocking"
        )) {
            assertFalse("scorer must stay a pure function: $forbidden", policy.contains(forbidden))
            assertFalse("echo must stay a pure function: $forbidden", echo.contains(forbidden))
        }
    }

    @Test fun rootEchoReusesTheOwnerOfTheListAndAddsNoHookPoint() {
        // 主评论 = 装着这批回复的那条消息：Java 钩子取接收者，Kotlin 通道由重写器交出 owner。
        val list = installer.after("environment.registrar.adapted(\"comment.filter.list.\$index\", point)")
            .before("environment.registrar.adapted(\"comment.filter.top.\$index\"")
        assertTrue(list.contains("rootEchoOf(thisObject, accessors)"))
        assertTrue(installer.contains("val root = if (plan.fish != null) rootEchoOf(owner, accessors) else null"))
        assertTrue(installer.contains("kotlinRewriter(replyInfoClass, \"mainList\", decideOwned, decide)"))
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.list\\.").findAll(installer).count())
        // 单条置顶位没有主评论可对照，不传。
        val top = installer.after("environment.registrar.adapted(\"comment.filter.top.\$index\"")
            .before("KMP 评论页走 Kotlin KReplyMoss")
        assertFalse(top.contains("rootEchoOf("))
        // 关闭 AI 账号判据时一次主评论都不读。
        assertTrue(list.contains("if (plan.fish != null) {"))
    }

    @Test fun rootEchoIsComputedLastAndOnlyWithinReachOfTheThreshold() {
        // 复述是最贵也最弱的一项：只在其余分数差一点时才读主评论、建索引，且最多 24 分。
        assertEquals(1, Regex("FishRootEcho\\.points\\(").findAll(policy).count())
        assertTrue(policy.contains("total < plan.threshold && total + FishRootEcho.MAX_POINTS >= plan.threshold"))
        assertTrue(policy.contains("normalizedName != root.authorName()"))
        assertTrue(echo.contains("const val MAX_POINTS = 24"))
        // 复述上限加上"仅 Lv≤2"的 14 分必须低于最严档阈值：否则没有任何其它特征的新号，靠复述一项就能被删。
        val strictThreshold = Regex("STRICT\\(\"strict\", (\\d+)\\)").find(policy)!!.groupValues[1].toInt()
        assertTrue(24 + 14 < strictThreshold)
    }

    @Test fun matchingBoundariesDoNotDependOnTheRegexEnginesUnicodeWordClass() {
        // JS 的 \b 只认 ASCII；Android 的 ICU 引擎会把汉字算单词字符。统一用显式断言，不写裸 \b。
        assertFalse(policy.contains("\\\\b"))
        assertTrue(policy.contains("(?<![a-z0-9_])"))
        // 没有无界嵌套量词：每个可变长片段都有上界，避免灾难性回溯。
        assertFalse(Regex("\\([^)]*[*+]\\)[*+]").containsMatchIn(policy))
    }

    @Test fun settingsReachTheInstallerThroughThePrefsKeysOnly() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val wiring = entry.after("fishFilter = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config(")
            .before("featureInstallCoordinator.installAll")
        for (key in listOf(
            "COMMENT_FISH_FILTER_ENABLED", "COMMENT_FISH_SENSITIVITY", "COMMENT_FISH_NICKNAME_WORDS",
            "COMMENT_FISH_TEXT_WORDS", "COMMENT_FISH_WHITELIST", "COMMENT_FISH_PROTECT_HIGH_LEVEL"
        )) assertTrue(key, wiring.contains("FeaturePreferences.$key"))
        // 保护开关默认开：读不到时站在保守一侧。
        assertTrue(wiring.contains("FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, true"))
    }

    @Test fun settingsPageDoesNotWriteTheModeledSwitchesDirectly() {
        val ui = SourceContract.read("ui/activity/CommentFishFilterPresenter.kt")
        assertTrue(ui.contains("applyModeledToggle(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, checked, button)"))
        assertTrue(ui.contains("applyModeledToggle(FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, checked, button)"))
        assertFalse(ui.contains("putBoolean("))
    }

    @Test fun modalSettingsSaveBeforeCheckingTheOriginalViewBinding() {
        val action = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("fun textViewAction(")
            .before("fun imageView(")
        assertTrue(action.contains("session.click(ref)"))
        val row = SourceContract.read("ui/activity/CommentFishFilterPresenter.kt")
            .after("private fun MainActivity.fishSettingRow(")
            .before("private fun MainActivity.writeFishText(")
        assertTrue(row.contains("textViewAction("))
        assertTrue(row.contains("bindSettingDestination(this, preferenceKey)"))
        assertTrue(row.before("openEditor(anchor)").contains("ref.token()"))
        val save = row.after("openEditor(anchor)").before("if (token.isCurrent())")
        assertTrue(save.contains("mirror(value)"))
        assertTrue(save.contains("writeFishText(preferenceKey, value)"))
        assertTrue(row.contains("if (token.isCurrent()) ref.get().text = summary(value)"))
    }
}
