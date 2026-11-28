package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论区功能的源码契约护栏：
 * - AI 评论过滤共用现有列表/置顶/KMP 通道，主评论复述兜底最后计算且热路径纯函数无正则/IO；
 * - 评论回复按钮与 Compose 渲染回调不在热路径做类发现、反射字段扫描或 DEX 查询；
 * - 评论 RPC 桥、全文脉络预算、弱持有与静态禁止硬编码清单保持干净。
 */
class CommentFeatureWiringContractTest {

    @Test
    fun `fish filter shares comment hooks, computes root echo last, and keeps hot path pure`() {
        val filterInstaller = SourceContract.read("hook/feature/CommentFilterFeatureInstaller.kt")
        val policy = SourceContract.read("hook/feature/FishScorePolicy.kt")
        val echo = SourceContract.read("hook/feature/FishRootEcho.kt")

        // 视频资料来自独立的详情观察点，评论仍由同一批列表、置顶 getter 和 Kotlin 通道处理。
        assertFalse(filterInstaller.contains("comment.fish"))
        assertFalse(filterInstaller.contains("fish.list"))
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.list\\.").findAll(filterInstaller).count())
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.top\\.").findAll(filterInstaller).count())
        assertTrue(filterInstaller.contains("installKotlinChannel(environment, accessors, plan, videoIdentity, peerSource)"))

        // 单一判定入口：AI 打分只在 shouldRemove 里调用一次，Java 与 Kotlin 两条通道都经过它。
        assertEquals(1, Regex("FishScorePolicy\\.shouldRemove\\(").findAll(filterInstaller).count())
        val decision = filterInstaller.after("internal fun shouldRemove(\n            signals: Signals,\n            plan: JudgementPlan")
            .before("兼容旧签名的窄入口")
        assertTrue(decision.indexOf("RuleSetCodec.matches(plan.keywords") < decision.indexOf("FishScorePolicy.shouldRemove("))
        assertTrue(decision.indexOf("plan.userRules.matches(") < decision.indexOf("FishScorePolicy.shouldRemove("))
        val kotlin = filterInstaller.after("private fun kotlinDecide(").before("回调是本模块创建的动态代理")
        assertTrue(kotlin.contains("shouldRemove(readSignals(reply, accessors, plan, root, videoIdentity, peerSource, snapshots), plan)"))
        val list = filterInstaller.after("environment.registrar.adapted(\"comment.filter.list.\$index\", point)")
            .before("environment.registrar.adapted(\"comment.filter.top.\$index\"")
        assertTrue(list.contains("ruleVerdictMemo.getOrCompute(source, snapshots)"))
        assertTrue(list.contains("shouldRemove(readSignals(reply, accessors, plan), plan)"))
        // 关闭时完全不走记忆化那一支：既有判据的行为逐字不变。
        assertTrue(list.contains("if (plan.fish != null) {"))

        val read = filterInstaller.after("private fun readSignals(").before("先读计数")
        assertTrue(read.contains("val needAuthor = !plan.userRules.isEmpty() || fish != null"))
        assertTrue(read.contains("fish?.whitelist?.mids?.isNotEmpty() == true"))
        // 白名单读不到的一半无法保护任何人：整条判据不装，而不是在保护失效时静默删。
        assertTrue(filterInstaller.contains("fish.whitelist.available(accessors.hasAuthorNamePath, accessors.hasAuthorMidPath) == fish.whitelist"))

        assertTrue(filterInstaller.contains("if (fishPlan != null) add(\"comments_fish_filter_enabled\")"))
        assertTrue(filterInstaller.contains("\"comments_fish_filter_enabled\" -> plan.fish != null && accessors.hasMessagePath &&"))
        assertTrue(filterInstaller.contains("else degraded += \"fish\""))

        // 规则在类加载时预编译一次；打分函数里不许再出现 Regex(。
        val hot = policy.after("fun shouldRemove(")
        assertFalse(hot.contains("Regex("))
        assertFalse(hot.contains(".toRegex()"))
        val echoHot = echo.after("fun points(")
        assertFalse(echoHot.contains("Regex("))
        assertFalse(echoHot.contains(".toRegex()"))
        for (forbidden in listOf(
            "import android", "SharedPreferences", "java.io.", "java.net.", "okhttp", "HttpURLConnection",
            "File(", "System.currentTimeMillis", "runBlocking"
        )) {
            assertFalse("scorer must stay a pure function: $forbidden", policy.contains(forbidden))
            assertFalse("echo must stay a pure function: $forbidden", echo.contains(forbidden))
        }
        val thread = Regex("\\bThread\\b")
        assertFalse(thread.containsMatchIn(policy))
        assertFalse(thread.containsMatchIn(echo))

        // 主评论 = 装着这批回复的那条消息：Java 钩子取接收者，Kotlin 通道由重写器交出 owner。
        assertTrue(list.contains("rootEchoOf(thisObject, accessors)"))
        assertTrue(filterInstaller.contains("val root = if (plan.fish != null) rootEchoOf(owner, accessors) else null"))
        assertTrue(filterInstaller.contains("rpcRewriter.rewrite(javaReply) { replies, owner ->"))
        assertEquals(1, Regex("registrar\\.adapted\\(\"comment\\.filter\\.list\\.").findAll(filterInstaller).count())
        // 单条置顶位没有主评论可对照，不传。
        val top = filterInstaller.after("environment.registrar.adapted(\"comment.filter.top.\$index\"")
            .before("KMP 评论页走 Kotlin KReplyMoss")
        assertFalse(top.contains("rootEchoOf("))

        // 复述是最贵也最弱的一项：只在其余分数差一点时才读主评论、建索引，且最多 24 分。
        assertEquals(1, Regex("FishRootEcho\\.points\\(").findAll(policy).count())
        assertTrue(policy.contains("total < plan.threshold && total + FishRootEcho.MAX_POINTS >= plan.threshold"))
        assertTrue(policy.contains("normalizedName != root.authorName()"))
        assertTrue(policy.contains("total + rootPoints < plan.threshold"))
        assertTrue(policy.contains("total += maxOf(rootPoints, videoPoints, peerPoints)"))
        assertTrue(echo.contains("const val MAX_POINTS = 24"))
        // 复述上限加上"仅 Lv≤2"的 14 分必须低于最严档阈值：否则没有任何其它特征的新号，靠复述一项就能被删。
        val strictThreshold = Regex("STRICT\\(\"strict\", (\\d+)\\)").find(policy)!!.groupValues[1].toInt()
        assertTrue(24 + 14 < strictThreshold)

        // JS 的 \b 只认 ASCII；Android 的 ICU 引擎会把汉字算单词字符。统一用显式断言，不写裸 \b。
        assertFalse(policy.contains("\\\\b"))
        assertTrue(policy.contains("(?<![a-z0-9_])"))
        // 没有无界嵌套量词：每个可变长片段都有上界，避免灾难性回溯。
        assertFalse(Regex("\\([^)]*[*+]\\)[*+]").containsMatchIn(policy))
    }

    @Test
    fun `fish filter settings, reply button scope, and visibility guard stay isolated and lifecycle-safe`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val wiring = entry.after("fishFilter = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config(")
            .before("featureInstallCoordinator.installAll")
        for (key in listOf(
            "COMMENT_FISH_FILTER_ENABLED", "COMMENT_FISH_SENSITIVITY", "COMMENT_FISH_NICKNAME_WORDS",
            "COMMENT_FISH_TEXT_WORDS", "COMMENT_FISH_WHITELIST", "COMMENT_FISH_PROTECT_HIGH_LEVEL",
            "COMMENT_FISH_STRONG_MODE"
        )) assertTrue(key, wiring.contains("FeaturePreferences.$key"))
        // 保护开关默认开：读不到时站在保守一侧。
        assertTrue(wiring.contains("FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, true"))
        assertTrue(wiring.contains("FeaturePreferences.COMMENT_FISH_STRONG_MODE, false"))

        val ui = SourceContract.read("ui/activity/CommentFishFilterPresenter.kt")
        assertTrue(ui.contains("applyModeledToggle(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, checked, button)"))
        assertTrue(ui.contains("applyModeledToggle(FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, checked, button)"))
        assertTrue(ui.contains("applyModeledToggle(FeaturePreferences.COMMENT_FISH_STRONG_MODE, checked, button)"))
        assertFalse(ui.contains("putBoolean("))

        val action = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("fun textViewAction(")
            .before("fun imageView(")
        assertTrue(action.contains("session.click(ref)"))
        val row = ui
            .after("private fun MainActivity.fishSettingRow(")
            .before("private fun MainActivity.writeFishText(")
        assertTrue(row.contains("textViewAction("))
        assertTrue(row.contains("bindSettingDestination(this, preferenceKey)"))
        assertTrue(row.before("openEditor(anchor)").contains("ref.token()"))
        val save = row.after("openEditor(anchor)").before("if (token.isCurrent())")
        assertTrue(save.contains("mirror(value)"))
        assertTrue(save.contains("writeFishText(preferenceKey, value)"))
        assertTrue(row.contains("if (token.isCurrent()) ref.get().text = summary(value)"))

        // Reply button scope
        val replyInstaller = SourceContract.read("hook/feature/CommentReplyButtonFeatureInstaller.kt")
        val replyAdapter = SourceContract.read("hook/adapter/CommentReplyButtonAdapter.kt")
        for (owner in listOf("dut", "bxu", "hzu")) {
            assertFalse("Must not hardcode $owner in installer", replyInstaller.contains("\"$owner\""))
            assertFalse("Must not hardcode $owner in adapter", replyAdapter.contains("\"$owner\""))
        }
        assertTrue(replyInstaller.contains("CommentReplyButtonAdapter.resolvePlans("))
        assertTrue(replyInstaller.contains("plans.map { it.binding }.distinct().size != 1"))
        assertTrue(replyInstaller.contains("plans.map { it.method.declaringClass }.distinct().size != 1"))
        assertTrue(replyAdapter.contains("plans.map { it.binding }.distinct().size != 1"))
        assertTrue(replyInstaller.contains("getIdentifier(\"reply_button\", \"id\", TARGET_PACKAGE)"))

        val callback = replyInstaller.after("after {").before("hooks++")
        for (operation in HOT_PATH_FORBIDDEN_OPS) {
            assertFalse(operation, callback.contains(operation))
        }
        assertFalse(replyInstaller.contains("findViewById"))
        assertTrue(callback.contains("hasThrowable"))
        assertTrue(callback.contains("Looper.myLooper() != Looper.getMainLooper()"))

        val visibility = SourceContract.read("hook/feature/CommentReplyButtonVisibility.kt")
        val guard = visibility.after("private class Guard(").before("private companion object")
        assertTrue(visibility.contains("WeakHashMap<View, Guard>"))
        assertTrue(guard.contains("target = WeakReference(view)"))
        assertTrue(guard.contains("WeakReference<ViewTreeObserver>"))
        for (cb in listOf("attach", "detach", "layout")) {
            assertTrue(guard.contains("HostThreadGuard.run(\"comment.reply_button.$cb\")"))
        }
        assertTrue(
            guard.after("override fun onViewDetachedFromWindow").before("override fun onGlobalLayout")
                .contains("stop()")
        )
        for (operation in listOf("postOnAnimation", "postDelayed", "Choreographer", "ValueAnimator", "findViewById")) {
            assertFalse(operation, visibility.contains(operation))
        }

        assertEquals(1, Regex("CommentReplyButtonFeatureInstaller\\(").findAll(entry).count())
        val call = entry.after("CommentReplyButtonFeatureInstaller(").before("CommentPurifyFeatureInstaller(")
        assertTrue(call.contains("prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, false)"))
        assertFalse(call.contains("REPLY_TOPOLOGY_ENABLED"))
        assertFalse(call.contains("runtimeCommentFreeCopyEnabled"))
    }

    @Test
    fun `comment rpc responses, full tree loading, story panel, and compose menu maintain scope and weak ownership`() {
        for (file in listOf("CommentFilterFeatureInstaller.kt", "CommentPurifyFeatureInstaller.kt")) {
            val source = SourceContract.read("hook/feature/$file")
            for (mapping in listOf(
                "\"foldList\" to \"FoldListResp\"",
                "\"hotspotPage\" to \"HotspotPageResp\"",
                "\"replyInfo\" to \"ReplyInfoReply\""
            )) assertTrue(mapping, source.contains(mapping))
            assertTrue(source.contains("shareUnchangedReply = true"))
        }
        val filter = SourceContract.read("hook/feature/CommentFilterFeatureInstaller.kt")
            .after("private fun installKotlinChannel").before("private fun prewarmSemantic")
        assertTrue(filter.contains("if (rpc == \"replyInfo\") singleRewriter else rewriter"))
        assertTrue(filter.contains("val result = rpcRewriter.rewrite(javaReply)"))
        assertTrue(filter.contains("confirmedTransform ="))
        val purify = SourceContract.read("hook/feature/CommentPurifyFeatureInstaller.kt")
            .after("private fun installKotlinChannel").before("private fun installAbsentPayload")
        assertTrue(purify.contains("rpc == \"mainList\" && requestTransform != null"))

        val open = HookEntrySource.function("onFullTreeRequested", HookEntrySource.commentTopologyFiles())
        assertTrue(open.before("if (!state.loadingRequest)").contains("if (!state.loadAll) state.guard.beginBatch"))
        val host = SourceContract.read("hook/feature/ReplyTopologyHostAccess.kt")
        assertTrue(host.contains("MAX_MAPPER_SEED_NODES, requireReplies = true"))
        assertTrue(host.contains("if (requireReplies &&"))
        val page = host.after("private fun parsePage").before("private fun snapshotTree")
        assertTrue(page.contains("error(\"DetailListReply pagination is unavailable\")"))
        assertTrue(page.contains("error(\"DetailListReply next offset is unavailable\")"))
        assertFalse(page.contains("pagination?.let"))
        assertTrue(
            host.after("private fun snapshotTree").before("private fun snapshotNode")
                .contains("?: error(\"ReplyInfo replies is unavailable\")")
        )

        val binder = HookEntrySource.file("hook/feature/comment/ReplyTopologyAnchorBinder.kt")
        val pager = HookEntrySource.file("hook/feature/comment/ReplyTopologyPager.kt")
        assertTrue(binder.contains("coordinator?.open(activity, currentSeed, this@ReplyTopologyAnchorView)"))
        assertTrue(pager.contains("state.sourceAnchor?.get()"))
        val geometry = binder.after("private class AnchorGeometry(").before("fun toTarget(")
        assertTrue(geometry.contains("val include: WeakReference<ViewGroup>"))
        assertTrue(geometry.contains("val row: WeakReference<ViewGroup>"))
        assertTrue(geometry.contains("val referenceView: WeakReference<View>"))
        assertTrue(geometry.contains("val primaryMessage: WeakReference<View>?"))
        assertTrue(geometry.contains("val secondaryMessage: WeakReference<View>?"))
        val controller = SourceContract.read("ui/overlay/ReplyTopologyPanelController.kt")
        assertTrue(controller.contains("sourceAnchor.rootView"))
        assertTrue(controller.contains("parentRef = WeakReference(parent)"))
        assertTrue(controller.contains("parent.windowToken == panel.windowToken"))
        assertFalse(controller.contains("SYSTEM_ALERT_WINDOW"))

        val ids = HookEntrySource.function("isCommentBodyTextView", HookEntrySource.freeCopyScopeFiles())
        assertFalse(ids.contains("getIdentifier(\"content\""))
        val bridge = SourceContract.read("hook/feature/CommentNativeSupplementBridge.kt")
        assertTrue(bridge.contains("entry.plan.body.get(binding) !== view"))
        assertTrue(bridge.contains("sample.resources.getIdentifier(\"sub_content\""))

        val composeCopyAfter = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("after {").before("dispatchers += dispatch")
        for (operation in HOT_PATH_FORBIDDEN_OPS) {
            assertFalse(operation, composeCopyAfter.contains(operation))
        }
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
        assertTrue(menu.contains("HostThreadGuard.run(\"reply_topology.compose_menu_click\")"))
        assertTrue(menu.contains("and 0b1110.inv()"))
        assertTrue(menu.contains("reportRuntimeEvidence(CommentTopologyFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)"))
        assertFalse(menu.contains("reportRuntimeEvidence(\"reply_topology_enabled\""))

        val hook = HookEntrySource.hookEntry()
        assertTrue(hook.contains("onComposeResetMore = commentTopologyInstaller::clearComposeMenu"))
        assertTrue(
            HookEntrySource.file("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
                .contains("resetMore = onComposeResetMore")
        )
        val dispatch = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("val request = argOrNull(0)?.takeIf(action::isInstance)")
            .before("actionInstalled++")
        assertTrue(dispatch.before("bindings.get(store)").contains("resetMore()"))
        assertTrue(dispatch.after("if (open(anchor, raw))").contains("resetMore()"))
        val remember = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
            .after("fun rememberComposeMenu").before("override val id")
        assertFalse(remember.contains("?: return"))
        assertTrue(remember.contains("composeMenu?.remember(anchor, seed, sourceCurrent)"))
        assertTrue(menu.contains("context.acceptsSource"))
        assertTrue(menu.contains("current.menuRoot.get() !== root"))
        assertTrue(menu.contains("if (!acceptsClick(current, menu, menuCanBeDismissed = true)) return@run"))
        assertTrue(menu.contains("takeIf { acceptsClick(current, it) }"))

        assertTrue(bridge.after("fun install(environment").before("val loader").contains("if (!enabled()) return emptyMap()"))
        assertTrue(bridge.contains("setter.declaringClass"))
        assertFalse(bridge.contains("free.copy.image.spannable"))
        val installComment = HookEntrySource.function("installCommentFreeCopyHooks", HookEntrySource.freeCopyScopeFiles())
            .before("val isInitialAttempt =")
        assertTrue(installComment.contains("if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled)"))
        assertTrue(installComment.contains("composeCommentCopy.install(commentEnv)"))
        assertTrue(installComment.contains("return"))
        val seedBridge = HookEntrySource.file("hook/feature/comment/ReplyTopologySeedBridge.kt")
        assertTrue(seedBridge.contains("ProtobufReplyTreeVisitor(host.replyInfoClass)"))
        assertTrue(seedBridge.contains("reader.visit(reply); reply"))
        assertTrue(seedBridge.contains("shareUnchangedReply = true"))
        for (file in listOf("CommentFilterFeatureInstaller.kt", "CommentPurifyFeatureInstaller.kt")) {
            assertTrue(SourceContract.read("hook/feature/$file").contains("shareUnchangedReply = true"))
        }
        assertFalse(SourceContract.read("hook/feature/SearchPurifyFeatureInstaller.kt").contains("shareUnchangedReply = true"))
    }

    @Test
    fun `production sources retain zero jadx fake packages and zero cleaned hardcodings`() {
        val forbidden = Regex(""""[^"]*(p\d{4}[a-z]|xm3\.d|BottomGameBenefitWidgetKt|yx3\.a|hv2\.a|cg1|dut|bxu|hzu|bjx1|iox1|hkx1|wmx1|rop0)""")
        val hits = SourceContract.allMainKotlinSources().flatMap { (path, text) ->
            text.lines().mapIndexedNotNull { idx, line ->
                if (forbidden.containsMatchIn(line)) "$path:${idx + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Unexpected forbidden literals in main sources: $hits", hits.isEmpty())
    }

    private companion object {
        val HOT_PATH_FORBIDDEN_OPS = listOf(
            "classOrNull", "declaredFields", "declaredMethods", "getBoolean",
            "DexKit", "DexMeguviz", "DexRefHookBridge", "DexAssist"
        )
    }
}
