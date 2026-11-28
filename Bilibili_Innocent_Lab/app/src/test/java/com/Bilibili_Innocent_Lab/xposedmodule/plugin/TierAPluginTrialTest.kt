package com.Bilibili_Innocent_Lab.xposedmodule.plugin

import android.content.ComponentCallbacks2
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureSkipReason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TestHookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FREE_COPY_TARGET_PACKAGE
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyGestureState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyRuntimeConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyTierAModule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.HostPluginResourceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.TierAFreeCopyConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.TierAFeatureModule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostResourceRegistry
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.spi.PluginResourceScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TierAPluginTrialTest {

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
        FreeCopyRuntimeConfig.freeCopyLightCache = null
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
        FreeCopyRuntimeConfig.freeCopyLightCache = null
        FreeCopyGestureState.clearDescTouchSession(resetHandled = true)
        FreeCopyGestureState.clearCommentTouchSession(resetHandled = true)
    }

    private fun sampleDeps(
        comment: Boolean = false,
        description: Boolean = false,
        title: Boolean = true
    ): TierAFreeCopyConfig = TierAFreeCopyConfig(
        initialCommentEnabled = comment,
        initialDescriptionEnabled = description,
        initialTitleEnabled = title,
        initialLightMode = false,
        initialAutoLight = false,
        commentTopologyEnabled = false,
        adaptPlan = AdaptPlan(0L, emptySet(), emptySet()),
        hostVersionCode = { 9_200_100 },
        initialAdaptResult = { null }
    )

    private inline fun runIsolated(block: () -> Unit) {
        HostMemoryPressureBridge.resetForTests()
        FreeCopyRuntimeConfig.freeCopyLightCache = null
        FreeCopyGestureState.clearDescTouchSession(resetHandled = true)
        FreeCopyGestureState.clearCommentTouchSession(resetHandled = true)
        try {
            block()
        } finally {
            HostMemoryPressureBridge.resetForTests()
            FreeCopyRuntimeConfig.freeCopyLightCache = null
            FreeCopyGestureState.clearDescTouchSession(resetHandled = true)
            FreeCopyGestureState.clearCommentTouchSession(resetHandled = true)
        }
    }

    @Test
    fun verifyAllCases() {
        runIsolated { freeCopyTierAModuleManifestPassesValidationAndMatchesDeclarativeHookTier() }
        runIsolated { createInstallerProducesRealFreeCopyFeatureInstallerAndInstallsViaCoordinator() }
        runIsolated { pluginResourceScopeForwardsTrimAndUnregistersLeasesIdempotentlyOnClose() }
    }

    private fun freeCopyTierAModuleManifestPassesValidationAndMatchesDeclarativeHookTier() {
        val module: TierAFeatureModule = FreeCopyTierAModule
        val manifest = module.manifest

        assertEquals(emptyList<String>(), manifest.validate())
        assertEquals(FreeCopyTierAModule.PLUGIN_ID, manifest.id)
        assertEquals(PluginTier.DECLARATIVE_BUNDLE, manifest.tier)
        assertNull(manifest.entryClass)
        assertEquals(setOf(PluginPermission.L2_ADAPT_AND_HOOK), manifest.permissions)
        assertFalse(PluginPermission.L3_NATIVE_OR_PRIVILEGED in manifest.permissions)
        assertTrue(PluginApiVersion.isCompatible(manifest.minApiLevel, manifest.maxApiLevel))
        assertTrue(manifest.isCompatibleWith(hostVersionCode = 9_200_100L))
        assertEquals(
            listOf(
                FreeCopyFeatureInstaller.CAPABILITY_COMMENT,
                FreeCopyFeatureInstaller.CAPABILITY_DESCRIPTION,
                FreeCopyFeatureInstaller.CAPABILITY_TITLE
            ),
            manifest.capabilities.map { it.localId }
        )
    }

    private fun createInstallerProducesRealFreeCopyFeatureInstallerAndInstallsViaCoordinator() {
        val scope = HostPluginResourceScope(FreeCopyTierAModule.manifest.id)
        val installer = FreeCopyTierAModule.createInstaller(
            deps = sampleDeps(comment = false, description = false, title = true),
            resourceScope = scope
        )

        assertTrue(installer is FreeCopyFeatureInstaller)
        assertEquals(FreeCopyFeatureInstaller.ID, installer.id)
        assertEquals(listOf(FreeCopyFeatureInstaller.CAPABILITY_TITLE), installer.capabilityIds)

        val mainEvidence = mutableListOf<FeatureInstallRecord>()
        val mainEnv = HookEnvironment(
            processName = FREE_COPY_TARGET_PACKAGE,
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { mainEvidence += it }
        )
        FeatureInstallCoordinator(mainEnv).installAll(listOf(installer))
        assertEquals(
            FeatureInstallResult.Installed(1),
            mainEvidence.last { it.id == FreeCopyFeatureInstaller.CAPABILITY_TITLE }.result
        )

        val subProcessEvidence = mutableListOf<FeatureInstallRecord>()
        val subEnv = mainEnv.copy(
            processName = "tv.danmaku.bili:web",
            installationEvidence = { subProcessEvidence += it }
        )
        FeatureInstallCoordinator(subEnv).installAll(listOf(installer))
        val skipped = subProcessEvidence.last { it.id == FreeCopyFeatureInstaller.ID }.result
            as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.NOT_APPLICABLE_PROCESS, skipped.reasonCode)

        scope.close()
        assertEquals(0, HostResourceRegistry.activeCount())
    }

    private fun pluginResourceScopeForwardsTrimAndUnregistersLeasesIdempotentlyOnClose() {
        assertEquals(0, HostResourceRegistry.activeCount())

        val scope = HostPluginResourceScope(FreeCopyTierAModule.manifest.id)
        assertEquals(1, HostResourceRegistry.activeCount())

        val session = FreeCopyTierAModule.openSession(sampleDeps(), scope)
        assertTrue(session.isActive)
        assertEquals(FreeCopyTierAModule.manifest.id, session.pluginId)
        assertTrue(session.installer is FreeCopyFeatureInstaller)
        assertEquals(2, HostResourceRegistry.activeCount())

        val observedLevels = mutableListOf<Int>()
        var customClosed = 0
        var earlyClosed = 0
        scope.register("trial-resource") { customClosed++ }
        val earlyHandle = scope.register("early-resource") { earlyClosed++ }
        scope.registerTrimCallback("trial-trim") { level -> observedLevels += level }

        FreeCopyRuntimeConfig.freeCopyLightCache = true
        @Suppress("DEPRECATION")
        HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        HostMemoryPressureBridge.dispatchModerate()
        assertTrue(FreeCopyRuntimeConfig.freeCopyLightCache == true)
        HostMemoryPressureBridge.dispatchCritical()
        assertNull(FreeCopyRuntimeConfig.freeCopyLightCache)

        assertEquals(
            listOf(
                PluginResourceScope.TRIM_HIDDEN,
                PluginResourceScope.TRIM_MODERATE,
                PluginResourceScope.TRIM_CRITICAL
            ),
            observedLevels
        )

        earlyHandle.close()
        earlyHandle.close()
        assertEquals(1, earlyClosed)

        session.close()
        session.close()
        assertFalse(session.isActive)
        assertTrue(scope.isClosed)
        assertEquals(1, customClosed)
        assertEquals(0, scope.registeredResourceCount)
        assertEquals(0, scope.registeredTrimCallbackCount)
        assertEquals(0, HostResourceRegistry.activeCount())

        HostMemoryPressureBridge.dispatchModerate()
        HostMemoryPressureBridge.dispatchCritical()
        assertEquals(3, observedLevels.size)

        var lateClosed = 0
        scope.register("after-close") { lateClosed++ }.close()
        assertEquals(1, lateClosed)
        assertEquals(0, HostResourceRegistry.activeCount())
    }
}
