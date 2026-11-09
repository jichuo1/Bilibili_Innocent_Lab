package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
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

    @Test fun verifiedProfileRejectsUnverifiedDefaultOwnersAndMissingMembers() {
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

    @Test fun allThreeDefaultBoundariesOverrideOnlyTheAutoPreferenceAndRestoreOutside() {
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

    @Test fun nestedDefaultScopesDoNotClearOuterScopeEarly() {
        val h = Harness()
        h.scope {
            assertFalse(h.read())
            h.scope(1) { assertFalse(h.read()) }
            assertFalse(h.read())
        }
        assertTrue(h.read())
    }

    @Test fun originalFailureCannotLeakAnOverrideIntoLaterManualReads() {
        val h = Harness()
        try {
            h.scope { assertFalse(h.read()); error("original failure") }
            fail("Original failure should propagate")
        } catch (failure: IllegalStateException) { assertEquals("original failure", failure.message) }
        assertTrue(h.read())
    }

    @Test fun concurrentThreadCannotBorrowAnotherThreadsDefaultScope() {
        val h = Harness()
        h.scope {
            assertFalse(h.read())
            val task = FutureTask { h.read() }
            Thread(task).apply { isDaemon = true; start() }
            assertTrue(task.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun originalPreferenceFailurePropagatesWithoutInventingAnAppliedOverride() {
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

    @Test fun unavailableStoryTargetNeverAcquiresAnOrdinaryDefaultOverride() {
        val h = Harness(target = null)
        h.scope { assertTrue(h.read()) }
        assertTrue(h.stages.isEmpty())
        h.target = 16
        h.scope { h.target = null; assertTrue(h.read()) }
        h.target = 16
        assertTrue(h.read())
    }

    @Test fun partialRegistrationStaysInactiveAndRetryDoesNotDuplicateInstalledHooks() {
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
}
