package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class NormalQualityFeatureHooksTest {
    private val loader = requireNotNull(javaClass.classLoader)
    private val roles = requireNotNull(NormalQualityLocator.resolve(loader))
    private inner class Harness(var target: Int? = 16) {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val hooks = NormalQualityFeatureHooks { target }
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage })
        init { hooks.install(environment, roles) }
        fun read(key: String = NormalQualityLocator.AUTO_KEY, value: Boolean = true): Boolean =
            registrar.invoke("player.quality.normal.auto", args = arrayOf(key, true)) { value } as Boolean
        fun scope(index: Int = 0, action: () -> Any?) = registrar.invoke("player.quality.normal.scope.$index") { action() }
    }

    fun verifiedProfileRejectsUnverifiedDefaultOwnersAndMissingMembers() {
        assertTrue(NormalQualityLocator.applicable(VersionAdapter.HookPoint("gh6.h", "c", emptyList())))
        assertFalse(NormalQualityLocator.applicable(VersionAdapter.HookPoint("gh6.h", "c")))
        assertFalse(NormalQualityLocator.applicable(VersionAdapter.HookPoint("gh6.h", "d", emptyList())))
        assertFalse(NormalQualityLocator.applicable(VersionAdapter.HookPoint("gh6.h", "c", listOf("int"))))
        assertFalse(NormalQualityLocator.applicable(VersionAdapter.HookPoint("ut1.h", "a", emptyList())))
        val missing = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.ship.theseus.united.player.mediaplay.n") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(NormalQualityLocator.resolve(missing))
    }

    fun allThreeDefaultBoundariesOverrideOnlyTheAutoPreferenceAndRestoreOutside() {
        val h = Harness()
        for (index in 0..2) {
            assertTrue(h.read())
            h.scope(index) {
                assertFalse(h.read())
                assertTrue(h.read("unrelated_preference"))
                assertFalse(h.read(value = false))
            }
            assertTrue(h.read())
        }
        assertEquals(3, h.stages.count { it == FeatureRuntimeStage.APPLIED })
    }

    fun nestedDefaultScopesDoNotClearOuterScopeEarly() {
        val h = Harness()
        h.scope {
            assertFalse(h.read())
            h.scope(1) { assertFalse(h.read()) }
            assertFalse(h.read())
        }
        assertTrue(h.read())
    }

    fun originalFailureCannotLeakAnOverrideIntoLaterManualReads() {
        val h = Harness()
        try {
            h.scope { assertFalse(h.read()); error("original failure") }
            fail("Original failure should propagate")
        } catch (failure: IllegalStateException) { assertEquals("original failure", failure.message) }
        assertTrue(h.read())
    }

    fun concurrentThreadCannotBorrowAnotherThreadsDefaultScope() {
        val h = Harness()
        h.scope {
            assertFalse(h.read())
            val task = FutureTask { h.read() }
            Thread(task).apply { isDaemon = true; start() }
            assertTrue(task.get(5, TimeUnit.SECONDS))
        }
    }

    fun originalPreferenceFailurePropagatesWithoutInventingAnAppliedOverride() {
        val h = Harness()
        val failure = IllegalArgumentException("preference failure")
        try {
            h.scope {
                h.registrar.invoke("player.quality.normal.auto", args = arrayOf(NormalQualityLocator.AUTO_KEY, true)) {
                    throw failure
                }
            }
            fail("Original preference failure should propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
        assertTrue(h.read())
        assertTrue(h.stages.isEmpty())
    }

    fun unavailableStoryTargetNeverAcquiresAnOrdinaryDefaultOverride() {
        val h = Harness(target = null)
        h.scope { assertTrue(h.read()) }
        assertTrue(h.stages.isEmpty())
        h.target = 16
        h.scope { h.target = null; assertTrue(h.read()) }
        h.target = 16
        assertTrue(h.read())
    }

    fun partialRegistrationStaysInactiveAndRetryDoesNotDuplicateInstalledHooks() {
        val h = Harness()
        val another = NormalQualityFeatureHooks { 16 }
        val registrar = PlayerPortTestRegistrar("player.quality.normal.scope.1")
        val environment = h.environment.copy(registrar = registrar)
        another.install(environment, roles)
        assertFalse(another.complete)
        registrar.invoke("player.quality.normal.scope.0") {
            assertEquals(true, registrar.invoke("player.quality.normal.auto", args = arrayOf(NormalQualityLocator.AUTO_KEY, true)) { true })
        }
        registrar.failId = null
        another.install(environment, roles)
        another.install(environment, roles)
        assertTrue(another.complete)
        assertEquals(4, another.hookCount)
        assertEquals(1, registrar.registrationAttempts.count { it.first == "player.quality.normal.auto" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "player.quality.normal.scope.0" })
    }

    object AutoSwitchTemplateA {
        @JvmStatic fun c(): Boolean = true
        @JvmStatic fun helper(x: Int): Boolean = x > 0
    }
    object AutoSwitchTemplateB {
        @JvmStatic fun b(): Boolean = true
    }
    object QualityDefaultTemplate {
        @JvmStatic fun a(): Int = 80
    }
    class EmptyQualityMarkerTemplate

    fun modernAutoSwitchResolvesInstallsAndFlipsTrueToFalse() {
        val methodA = AutoSwitchTemplateA::class.java.getDeclaredMethod("c")
        val methodHelper = AutoSwitchTemplateA::class.java.getDeclaredMethod("helper", Int::class.javaPrimitiveType)
        val methodB = AutoSwitchTemplateB::class.java.getDeclaredMethod("b")
        assertNull(NormalQualityLocator.selectAutoSwitch(listOf(methodHelper)))
        assertNull(NormalQualityLocator.selectAutoSwitch(listOf(methodA, methodB)))
        assertNull(NormalQualityLocator.selectAutoSwitch(emptyList()))

        val theseusName = "com.bilibili.ship.theseus.united.page.videoquality.TheseusPlayerQualityService"
        val modernLoader = TestRenamingClassLoader(
            parent = loader,
            templates = mapOf(
                theseusName to EmptyQualityMarkerTemplate::class.java,
                "rt02" to AutoSwitchTemplateA::class.java,
                "wj12" to QualityDefaultTemplate::class.java
            ),
            hiddenPrefixes = setOf("com.bilibili.video.story.player.quality.StoryQualityService")
        )
        val rt02 = modernLoader.loadClass("rt02")
        assertEquals("c", NormalQualityLocator.selectAutoSwitch(rt02.declaredMethods.toList())?.name)
        val point = VersionAdapter.HookPoint("rt02", "c", emptyList())
        assertNotNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point))
        assertNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point.copy(paramClassNames = listOf("int"))))
        assertNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point.copy(className = "com.bapis.rt02")))

        assertTrue(NormalQualityLocator.applicableModern(modernLoader, VersionAdapter.HookPoint("wj12", "a", emptyList())))
        assertFalse(NormalQualityLocator.applicableModern(modernLoader, VersionAdapter.HookPoint("gh6.h", "c", emptyList())))

        val registrar = PlayerPortTestRegistrar()
        val statuses = linkedMapOf<String, String>()
        val hookStages = mutableListOf<FeatureRuntimeStage>()
        val points = VersionAdapter.PlayerQualityPoints(
            defaultQualityMethod = VersionAdapter.HookPoint("wj12", "a", emptyList()),
            normalAutoSwitch = point
        )
        val installer = PlayerQualityFeatureInstaller(80, points)
        val env = HookEnvironment(
            "tv.danmaku.bili", modernLoader, HookPointRegistry(modernLoader), registrar,
            { _, _ -> }, { _, _ -> }, { k, v -> statuses[k] = v },
            runtimeEvidence = { _, s, _ -> hookStages += s }
        )
        assertEquals(FeatureInstallResult.Installed(2, complete = true), installer.install(env))
        assertEquals("success", statuses["player_quality_status"])
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(false, registrar.invoke("player.quality.normal.auto_switch") { true })
        assertTrue(FeatureRuntimeStage.OBSERVED in hookStages)
        assertTrue(FeatureRuntimeStage.APPLIED in hookStages)

        val noTheseusLoader = TestRenamingClassLoader(
            parent = loader,
            templates = mapOf("wj12" to QualityDefaultTemplate::class.java),
            hiddenPrefixes = setOf(theseusName, "com.bilibili.video.story.player.quality.StoryQualityService")
        )
        val legacyEnv = HookEnvironment(
            "tv.danmaku.bili", noTheseusLoader, HookPointRegistry(noTheseusLoader), PlayerPortTestRegistrar(),
            { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        val legacyResult = PlayerQualityFeatureInstaller(
            80, VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("wj12", "a", emptyList()))
        ).install(legacyEnv)
        assertEquals(FeatureInstallResult.Installed(1, complete = true), legacyResult)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NormalQualityFeatureHooksTest) -> Unit) {
            val s = NormalQualityFeatureHooksTest()
            block(s)
        }
        runIsolated { s -> s.verifiedProfileRejectsUnverifiedDefaultOwnersAndMissingMembers() }
        runIsolated { s -> s.allThreeDefaultBoundariesOverrideOnlyTheAutoPreferenceAndRestoreOutside() }
        runIsolated { s -> s.nestedDefaultScopesDoNotClearOuterScopeEarly() }
        runIsolated { s -> s.originalFailureCannotLeakAnOverrideIntoLaterManualReads() }
        runIsolated { s -> s.concurrentThreadCannotBorrowAnotherThreadsDefaultScope() }
        runIsolated { s -> s.originalPreferenceFailurePropagatesWithoutInventingAnAppliedOverride() }
        runIsolated { s -> s.unavailableStoryTargetNeverAcquiresAnOrdinaryDefaultOverride() }
        runIsolated { s -> s.partialRegistrationStaysInactiveAndRetryDoesNotDuplicateInstalledHooks() }
        runIsolated { s -> s.modernAutoSwitchResolvesInstallsAndFlipsTrueToFalse() }
    }
}
