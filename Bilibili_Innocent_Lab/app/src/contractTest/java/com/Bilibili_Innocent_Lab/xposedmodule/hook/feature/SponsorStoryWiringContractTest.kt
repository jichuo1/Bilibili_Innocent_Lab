package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Story 商单跳过的护栏：不走 DexKit、不碰详情页的 Theseus 名称、宿主调度器上的任务都过 HostThreadGuard，
 * 并且和详情页共用一个查询仓库。任何一条被改掉，真机上的失效方式都是"悄悄不工作"或"拖垮详情页功能"。
 */
class SponsorStoryWiringContractTest {
    private val installer = SourceContract.read("hook/feature/SponsorBlockStoryFeatureInstaller.kt")
    private val sessions = SourceContract.read("hook/feature/sponsor/StorySponsorSessions.kt")
    private val access = SourceContract.read("hook/adapter/SponsorStoryAccess.kt")
    private val core = SourceContract.read("hook/adapter/SponsorCoreApi.kt")
    private val session = SourceContract.read("hook/feature/sponsor/StorySponsorSession.kt")

    @Test fun storyResolvesDirectlyWithoutDexKitAndWithoutTheTheseusFamilyNames() {
        for (source in listOf(installer, sessions, access, core, session)) {
            assertFalse(source.contains("DexKit")); assertFalse(source.contains("DexAssist"))
            assertFalse(source.contains("com.bilibili.ship.theseus"))
            assertFalse(source.contains("getDeclaredMethod")); assertFalse(source.contains("Class.forName"))
        }
        assertTrue(access.contains("KavaMemberLookup as Lookup")); assertTrue(core.contains("KavaMemberLookup as Lookup"))
        assertTrue(installer.contains("registrationStarted.compareAndSet(false, true)"))
        assertTrue(installer.contains("reportStatus(STATUS, MISSING)"))
    }

    @Test fun everySchedulerTaskAndObserverBodyRunsInsideTheHostThreadGuard() {
        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock_story.main\""))
        assertTrue(sessions.contains("HostThreadGuard.runnable(\"sponsorblock_story.timer\""))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock_story.observer\")"))
        assertTrue(sessions.contains("HostThreadGuard.run(\"sponsorblock_story.unregister\")"))
        // 不允许绕过守卫直接把块丢给宿主调度器。
        assertFalse(sessions.contains("handler.post { "))
        assertFalse(sessions.contains("handler.post(Runnable"))
    }

    @Test fun theHostFacingObserverProxyNeverReturnsNullForAPrimitiveReturnType() {
        // 宿主直接调用这个代理，框架的 PROTECTIVE 兜不住；规范见 HostProxyGuardContractTest。
        val proxy = sessions.after("Proxy.newProxyInstance(").before("val target = core.get()")
        assertTrue(proxy.contains("hostProxyDefaultValue(method.returnType)"))
        assertFalse(proxy.contains("else -> null")); assertFalse(proxy.contains("\n                        null\n"))
    }

    @Test fun hookIdsAreUniqueAndTheSecondOnCreateRebindsInsteadOfStacking() {
        val ids = Regex("\"\\\$featureId\\.[a-z.]+(\\\$[a-z]+)?\"").findAll(sessions).map { it.value }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(sessions.contains("\"\$featureId.create\"")); assertTrue(sessions.contains("\"\$featureId.pager\""))
        assertTrue(sessions.contains("unbind(owner)\n        val shell = Shell(owner, core, activity)"))
    }

    @Test fun theStoryInstallerIsRegisteredAndSharesTheDetailPageRepository() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("repository = { sponsorRepository.value }"))
        assertEquals(2, Regex("repository = \\{ sponsorRepository\\.value \\}").findAll(hook).count())
        assertTrue(hook.contains("sponsorInstaller,\n                    sponsorStoryInstaller,"))
        assertTrue(hook.contains("sponsorEnabled && hookConfig.getBoolean(FeaturePreferences.SPONSORBLOCK_STORY, false)"))
    }

    @Test fun storyOnlyBindsAfterTheAdmissionGateAndEveryRebindUsesAFreshController() {
        assertTrue(session.contains("StorySponsorGate.evaluate(host.playable(), host.loadedId(), host.details())"))
        assertTrue(session.contains("SponsorPlaybackController(verdict.video"))
        assertTrue(session.contains("TAIL_GUARD_MS"))
        assertTrue(session.contains("executing()\n            host.seek(targetMs)"))
    }
}
