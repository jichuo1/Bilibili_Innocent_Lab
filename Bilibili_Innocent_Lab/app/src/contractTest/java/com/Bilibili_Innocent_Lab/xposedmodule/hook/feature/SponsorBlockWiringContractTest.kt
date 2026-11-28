package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商单跳过（详情页与 Story 共用）的源码契约护栏：
 * - 宿主窗口点击、调度器任务与动态代理必须过 HostThreadGuard 且未处理方法按返回类型给零值；
 * - 详情页与 Story 的 seek 必须共用命名常量 `ACCURATE_SEEK = true`；
 * - Story 不走 DEX 辅助、不碰 Theseus 家族名、共用详情页仓库并按准入门控绑定。
 */
class SponsorBlockWiringContractTest {

    @Test
    fun `detail and story host stack entries stay guarded and proxies return typed defaults`() {
        val overlay = SourceContract.read("hook/feature/sponsor/SponsorHintOverlay.kt")
        assertTrue(overlay.contains("setOnClickListener { HostThreadGuard.run(\"sponsorblock.hint.click\")"))
        assertFalse(overlay.contains("setOnClickListener { if (shown == hint) action(hint) }"))

        for (path in listOf("hook/feature/sponsor/SponsorPlayerSessions.kt", "hook/feature/sponsor/StorySponsorSessions.kt")) {
            val proxy = SourceContract.read(path).after("Proxy.newProxyInstance(").before("val ")
            assertTrue(path, proxy.contains("hostProxyDefaultValue(method.returnType)"))
            assertFalse(path, proxy.contains("else -> null"))
            assertFalse(path, Regex("\\n\\s+null\\n\\s+\\}").containsMatchIn(proxy))
        }

        val sessions = SourceContract.read("hook/feature/sponsor/SponsorPlayerSessions.kt")
        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock.main\""))
        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock.timer\""))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock.observer\")"))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock.unregister\")"))
        assertFalse(sessions.contains("handler.post {"))
        assertFalse(sessions.contains("Runnable(block)"))

        val access = SourceContract.read("hook/adapter/SponsorPlayerAccess.kt")
        assertTrue(access.contains("fun isPlayableInterface("))
        assertTrue(access.contains("fun resolveRunSurface("))
        assertTrue(access.contains("fun resolveContinuationOwnerField("))
        for (hardcoded in listOf("\"hcg2\"", "\"nmq2\"", "\"bpq2\"", "\"spq2\"")) {
            assertFalse("SponsorPlayerAccess must not hardcode $hardcoded", access.contains(hardcoded))
        }
    }

    @Test
    fun `detail and story seeks pass the named accurate seek constant never literal false`() {
        val access = SourceContract.read("hook/adapter/SponsorPlayerAccess.kt")
        val detail = SourceContract.read("hook/feature/sponsor/SponsorPlayerSessions.kt")
        val story = SourceContract.read("hook/feature/sponsor/StorySponsorSessions.kt")

        assertTrue(access.contains("const val ACCURATE_SEEK = true"))
        assertFalse(access.contains("const val ACCURATE_SEEK = false"))

        val detailSeek = detail.after("override fun seek(").before("fun start()")
        assertTrue(detailSeek.contains("access.seek.invoke(wrapper, targetMs, SponsorPlayerAccess.ACCURATE_SEEK)"))
        assertFalse(detailSeek.contains("targetMs, false"))

        val storySeek = story.after("override fun seek(targetMs: Int)").before("fun start()")
        assertTrue(storySeek.contains("access.core.seek.invoke(it, targetMs, SponsorPlayerAccess.ACCURATE_SEEK)"))
        assertFalse(storySeek.contains("targetMs, false"))
    }

    @Test
    fun `story sponsor resolves without dex assist, shares detail repository, and gates item binding`() {
        val installer = SourceContract.read("hook/feature/SponsorBlockStoryFeatureInstaller.kt")
        val sessions = SourceContract.read("hook/feature/sponsor/StorySponsorSessions.kt")
        val access = SourceContract.read("hook/adapter/SponsorStoryAccess.kt")
        val core = SourceContract.read("hook/adapter/SponsorCoreApi.kt")
        val session = SourceContract.read("hook/feature/sponsor/StorySponsorSession.kt")

        for (source in listOf(installer, sessions, access, core, session)) {
            for (forbidden in listOf("DexKit", "DexMeguviz", "DexRefHookBridge", "DexAssist")) {
                assertFalse(forbidden, source.contains(forbidden))
            }
            assertFalse(source.contains("com.bilibili.ship.theseus"))
            assertFalse(source.contains("getDeclaredMethod")); assertFalse(source.contains("Class.forName"))
        }
        assertTrue(access.contains("KavaMemberLookup as Lookup")); assertTrue(core.contains("KavaMemberLookup as Lookup"))
        assertTrue(installer.contains("registrationStarted.compareAndSet(false, true)"))
        assertTrue(installer.contains("reportStatus(STATUS, MISSING)"))

        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock_story.main\""))
        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock_story.timer\""))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock_story.observer\")"))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock_story.unregister\")"))
        assertFalse(sessions.contains("handler.post { "))
        assertFalse(sessions.contains("handler.post(Runnable"))

        // 宿主直接调用这个代理，框架的 PROTECTIVE 兜不住；规范见 FeatureHostGuardAndContainmentContractTest。
        val proxy = sessions.after("Proxy.newProxyInstance(").before("val target = core.get()")
        assertTrue(proxy.contains("hostProxyDefaultValue(method.returnType)"))
        assertFalse(proxy.contains("else -> null")); assertFalse(proxy.contains("\n                        null\n"))

        val ids = Regex("\"\\\$featureId\\.[a-z.]+(\\\$[a-z]+)?\"").findAll(sessions).map { it.value }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(sessions.contains("\"\$featureId.create\"")); assertTrue(sessions.contains("\"\$featureId.pager\""))
        assertTrue(sessions.contains("unbind(owner)\n        val shell = Shell(owner, core, activity)"))

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("repository = { sponsorRepository.value }"))
        assertEquals(2, Regex("repository = \\{ sponsorRepository\\.value \\}").findAll(hook).count())
        assertTrue(hook.contains("sponsorInstaller,\n                    sponsorStoryInstaller,"))
        assertTrue(hook.contains("sponsorEnabled && hookConfig.getBoolean(FeaturePreferences.SPONSORBLOCK_STORY, false)"))

        assertTrue(session.contains("StorySponsorGate.evaluate(host.playable(), loaded, host.details())"))
        assertTrue(session.contains("SponsorPlaybackController(verdict.video"))
        assertTrue(session.contains("TAIL_GUARD_MS"))
        assertTrue(session.contains("executing()\n            host.seek(targetMs)"))

        // 永久拒绝按已装载条目编号记住，暂未就绪的拒绝每次进度回调都要重判。
        val reconcile = session.after("private fun reconcile()").before("private fun bind(")
        assertTrue(reconcile.contains("if (loaded != null && loaded == rejected) return"))
        assertTrue(reconcile.contains("if (verdict.final) rejected = loaded"))
        assertTrue(session.after("fun itemChanged()").before("fun seekStarted(").contains("rejected = null"))
        assertTrue(SourceContract.read("hook/feature/sponsor/StorySponsorGate.kt").contains("\"blocked-detail\""))
    }
}
