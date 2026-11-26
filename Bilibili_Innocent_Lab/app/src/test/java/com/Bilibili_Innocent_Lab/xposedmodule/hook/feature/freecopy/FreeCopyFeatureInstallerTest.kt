package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureSkipReason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TestHookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class FreeCopyFeatureInstallerTest {

    private class CapturingRegistrar(
        private val delegate: HookRegistrar = TestHookRegistrar
    ) : HookRegistrar {
        val exactOwners = mutableListOf<Pair<String, String?>>()
        val firstOwners = mutableListOf<Pair<String, String?>>()

        override fun first(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val sample = String::class.java.getDeclaredMethod("isEmpty")
            val creator = ModernMemberHookCreator(sample).apply(block)
            firstOwners += id to creator.faultOwner
            delegate.first(id, className, methodName, block)
        }

        override fun all(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) = delegate.all(id, className, methodName, block)

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val method: Method = owner.getDeclaredMethod(methodName, *parameterTypes)
            val creator = ModernMemberHookCreator(method).apply(block)
            exactOwners += id to creator.faultOwner
            delegate.exact(id, owner, methodName, *parameterTypes, block = block)
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = delegate.adapted(id, point, exceptionPolicy, block)

        override fun constructor(
            id: String,
            constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) = delegate.constructor(id, constructor, block)
    }

    private fun createInstaller(
        comment: Boolean = true,
        desc: Boolean = true,
        title: Boolean = false,
        lightMode: Boolean = false,
        autoLight: Boolean = false,
        topology: Boolean = false
    ) = FreeCopyFeatureInstaller(
        initialCommentEnabled = comment,
        initialDescriptionEnabled = desc,
        initialTitleEnabled = title,
        initialLightMode = lightMode,
        initialAutoLight = autoLight,
        commentTopologyEnabled = topology,
        adaptPlan = AdaptPlan(0L, emptySet(), emptySet()),
        hostVersionCode = { 9200100 },
        initialAdaptResult = { null },
        onComposeMore = { _, _, _ -> },
        onComposeResetMore = {}
    )

    fun `capabilityIds reflect initial switches before install and skip non main process`() {
        val installer = createInstaller(comment = false, desc = false, title = true)
        assertEquals(listOf(FreeCopyFeatureInstaller.CAPABILITY_TITLE), installer.capabilityIds)

        val records = mutableListOf<FeatureInstallRecord>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili:web",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { records += it }
        )
        FeatureInstallCoordinator(env).installAll(listOf(installer))
        val parentResult = records.last { it.id == FreeCopyFeatureInstaller.ID }.result as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.NOT_APPLICABLE_PROCESS, parentResult.reasonCode)
    }

    fun `all FreeCopy hooks register via FeatureScopedRegistrar with capability attribution`() {
        val registrar = CapturingRegistrar()
        val evidence = mutableListOf<FeatureInstallRecord>()
        val env = HookEnvironment(
            processName = FREE_COPY_TARGET_PACKAGE,
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { evidence += it }
        )
        val installer = createInstaller(comment = false, desc = false, title = true)
        FeatureInstallCoordinator(env).installAll(listOf(installer))

        assertTrue(registrar.exactOwners.isNotEmpty())
        assertTrue(registrar.exactOwners.any { (id, owner) ->
            id.startsWith("free-copy:description:set-text:") && owner == FreeCopyFeatureInstaller.CAPABILITY_TITLE
        })
        assertTrue(registrar.exactOwners.any { (id, owner) ->
            id == "free-copy:shared:dispatch-touch" && owner == FreeCopyFeatureInstaller.ID
        })
        assertEquals(
            FeatureInstallResult.Installed(1),
            evidence.last { it.id == FreeCopyFeatureInstaller.CAPABILITY_TITLE }.result
        )
    }

    fun `live config updates never mutate startup only title switch`() {
        val installer = createInstaller(comment = true, desc = false, title = true, lightMode = false)
        val env = HookEnvironment(
            processName = "tv.danmaku.bili:web",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        FeatureInstallCoordinator(env).installAll(listOf(installer))

        assertTrue(FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled)
        assertFalse(FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled)
        assertTrue(FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled)
        assertFalse(FreeCopyRuntimeConfig.freeCopyLightMode)

        installer.updateLiveConfig(
            commentEnabled = false,
            descriptionEnabled = true,
            lightMode = true
        )

        assertFalse(FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled)
        assertTrue(FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled)
        assertTrue("Title switch must remain immutable after startup", FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled)
        assertTrue(FreeCopyRuntimeConfig.freeCopyLightMode)
    }

    fun `clearTouchSessions release transient weak references cleanly`() {
        FreeCopyGestureState.descLongPressHandled = true
        FreeCopyGestureState.commentLongPressHandled = true
        FreeCopyGestureState.clearDescTouchSession(resetHandled = true)
        FreeCopyGestureState.clearCommentTouchSession(resetHandled = true)

        assertNull(FreeCopyGestureState.descTouchedView)
        assertNull(FreeCopyGestureState.commentTouchedView)
        assertNull(FreeCopyGestureState.commentTouchTargetRef)
        assertNull(FreeCopyGestureState.commentTouchRootRef)
        assertFalse(FreeCopyGestureState.descLongPressHandled)
        assertFalse(FreeCopyGestureState.commentLongPressHandled)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (FreeCopyFeatureInstallerTest) -> Unit) {
            val s = FreeCopyFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`capabilityIds reflect initial switches before install and skip non main process`() }
        runIsolated { s -> s.`all FreeCopy hooks register via FeatureScopedRegistrar with capability attribution`() }
        runIsolated { s -> s.`live config updates never mutate startup only title switch`() }
        runIsolated { s -> s.`clearTouchSessions release transient weak references cleanly`() }
    }
}
