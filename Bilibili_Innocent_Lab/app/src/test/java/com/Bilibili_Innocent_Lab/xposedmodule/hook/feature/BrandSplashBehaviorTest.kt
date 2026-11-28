package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import FlatWave1BrandStorage
import android.app.Application
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe
import tv.danmaku.bili.ui.splash.brand.model.BrandSplash
import tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo
import tv.danmaku.bili.ui.splash.brand.config.BrandSplashStorage
import tv.danmaku.bili.ui.splash.brand.modelv2.BrandSplashSettingVipConfig
import tv.danmaku.bili.ui.splash.brand.ui.BrandSplashFragment
import tv.danmaku.bili.ui.splash.brand.uiv2.setting.vm.BrandSplashSettingViewModel

class BrandSplashBehaviorTest {
    private val registrar = PlayerPortTestRegistrar()
    private val loader = javaClass.classLoader!!
    private val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
        { _, _ -> }, { _, _ -> }, { _, _ -> }, postToMain = {})
    private fun context(): Application {
        val u = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return u.allocateInstance(tv.danmaku.bili.ui.splash.brand.config.TestApplication::class.java) as Application
    }

    fun nativeDwellChangesOnlyForTheCurrentBrandPageModelAndUnwindsAfterFailure() {
        assertTrue(BrandSplashSkipFeatureInstaller(true) { true }.install(environment) is FeatureInstallResult.Installed)
        val current = BrandShowInfo(); val preview = BrandShowInfo()
        fun duration(value: BrandShowInfo) = registrar.invoke("brand.skip.native.duration", value) { value.duration }
        assertEquals(700L, duration(current))
        registrar.invoke("brand.skip.native.page", BrandSplashFragment(), arrayOf(current)) {
            assertEquals(0L, duration(current)); assertEquals(700L, duration(preview)); null
        }
        assertEquals(700L, current.duration)
        assertEquals(700L, duration(current))
        assertTrue(runCatching {
            registrar.invoke("brand.skip.native.page", BrandSplashFragment(), arrayOf(current)) { error("host failed") }
        }.isFailure)
        assertEquals(700L, duration(current))
    }

    fun kntrExitUsesTheCurrentCallbackExactlyOnceAndLeavesOtherResumeStatesAlone() {
        BrandSplashSkipFeatureInstaller(true) { true }.install(environment)
        var exits = 0; var originals = 0
        val frame = kntr.srcs.app.splash.brand.startup.`BrandSplashPageKt$BrandSplashPage$2$1` { exits++; Unit }
        repeat(2) { assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { originals++; Unit }) }
        assertEquals(1, exits); assertEquals(0, originals)
        frame.label = 2
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { originals++; Unit }
        assertEquals(1, originals)
    }

    fun failedExitRestoresTheInitialCoroutineLabelAndRunsTheOriginalPath() {
        BrandSplashSkipFeatureInstaller(true) { true }.install(environment)
        val frame = kntr.srcs.app.splash.brand.startup.`BrandSplashPageKt$BrandSplashPage$2$1` { error("exit unavailable") }
        var originals = 0
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { originals++; Unit }
        assertEquals(0, frame.label); assertEquals(1, originals)
    }

    fun clientUnlockCannotEscapeTheOfficialActionOrGrantDlcOrForbiddenChoices() {
        val result = BrandSplashCustomFeatureInstaller(true, context(), false).install(environment)
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        val config = BrandSplashSettingVipConfig()
        fun locked() = registrar.invoke("brand.custom.lock", config) { config.locked }
        assertEquals(true, locked())
        for ((source, forbidden, expected) in listOf(Triple("vip", false, false), Triple("dlc", false, true), Triple("vip", true, true))) {
            config.forbidden = forbidden
            registrar.invoke("brand.custom.picker", BrandSplashSettingViewModel(), arrayOf(BrandSplash(1, source), null)) {
                assertEquals(expected, locked()); assertEquals(true, locked()); null
            }
            assertEquals(true, locked())
        }
        assertTrue(config.locked)
    }

    fun previewUsesItsOwnSourceSignalAndCannotReuseAnEarlierVipDecision() {
        BrandSplashCustomFeatureInstaller(true, context(), false).install(environment)
        val config = BrandSplashSettingVipConfig()
        val action = tv.danmaku.bili.ui.splash.brand.uiv2.setting.preview.`BrandSplashPreviewFragment$handleSelectButtonClicked$1`()
        registrar.invoke("brand.custom.preview", action, arrayOf(Unit)) {
            assertEquals(true, registrar.invoke("brand.custom.lock", config) { true })
            registrar.invoke("brand.custom.source", BrandSplash(1, "vip")) { "vip" }
            assertEquals(false, registrar.invoke("brand.custom.lock", config) { true })
            registrar.invoke("brand.custom.source", BrandSplash(2, "dlc")) { "dlc" }
            assertEquals(true, registrar.invoke("brand.custom.lock", config) { true })
            null
        }
        assertEquals(true, registrar.invoke("brand.custom.lock", config) { true })
    }

    fun refreshRetainsOnlyPersistedVipAndAConfirmedRemovalStopsRetention() {
        BrandSplashStorage.mode = true
        val vip = BrandSplash(1, "vip"); val dlc = BrandSplash(2, "dlc")
        BrandSplashStorage.selected = arrayListOf(vip, dlc)
        BrandSplashCustomFeatureInstaller(true, context(), false).install(environment)
        registrar.invoke("brand.custom.storage.read", null, arrayOf(false)) { BrandSplashStorage.selected }
        val original = listOf(BrandSplash(3, "brand"))
        val retained = registrar.invoke("brand.custom.refresh", null) { original } as List<*>
        assertEquals(listOf(original[0], vip), retained)
        assertEquals(1, original.size)
        registrar.invoke("brand.custom.storage.write", null, arrayOf(emptyList<BrandSplash>())) { null }
        assertSame(original, registrar.invoke("brand.custom.refresh", null) { original })
    }

    fun nullServerListCannotClearAPersistedVipSelectionDuringHostReconciliation() {
        BrandSplashStorage.mode = true
        val vip = BrandSplash(1, "vip")
        BrandSplashStorage.selected = arrayListOf(vip)
        BrandSplashCustomFeatureInstaller(true, context(), false).install(environment)
        registrar.invoke("brand.custom.storage.read", null, arrayOf(false)) { BrandSplashStorage.selected }

        // The host treats a null response list as empty, intersects it with the stored choices,
        // and disables custom mode when the intersection is empty (9.15 / 9.16 refresh worker).
        val available = registrar.invoke("brand.custom.refresh", null) { null } as? List<*> ?: emptyList<Any>()
        val storedIds = BrandSplashStorage.selected.map { it.id }.toSet()
        val retained = available.filterIsInstance<BrandSplash>().filter { it.id in storedIds }
        if (retained.isEmpty()) {
            registrar.invoke("brand.custom.storage.mode", null, arrayOf(false)) { BrandSplashStorage.l(false); null }
        } else {
            registrar.invoke("brand.custom.storage.write", null, arrayOf(retained)) { BrandSplashStorage.k(retained); null }
        }
        assertTrue(BrandSplashStorage.mode)
        assertEquals(listOf(vip), BrandSplashStorage.selected)
    }

    fun nullServerListStaysNullWithoutAnActivePersistedVipChoice() {
        BrandSplashStorage.mode = true
        BrandSplashStorage.selected = arrayListOf(BrandSplash(2, "dlc"), BrandSplash(3, "brand"))
        BrandSplashCustomFeatureInstaller(true, context(), false).install(environment)
        registrar.invoke("brand.custom.storage.read", null, arrayOf(false)) { BrandSplashStorage.selected }
        assertNull(registrar.invoke("brand.custom.refresh", null) { null })

        registrar.invoke("brand.custom.storage.write", null, arrayOf(listOf(BrandSplash(1, "vip")))) { null }
        registrar.invoke("brand.custom.storage.mode", null, arrayOf(false)) { null }
        assertNull(registrar.invoke("brand.custom.refresh", null) { null })
    }

    fun freshServerMetadataWinsOverTheOldPersistedInstance() {
        val old = BrandSplash(1, "vip", "old"); val fresh = BrandSplash(1, "vip", "new")
        val server = listOf(fresh)
        val result = BrandSplashPolicy.retainSelectedVip(server, listOf(old), { it.source }, { "${it.source}#${it.id}" })
        assertSame(server, result); assertSame(fresh, result[0])
    }

    fun reclassifiedDlcCannotBeReintroducedAsAnOldVipChoice() {
        val old = BrandSplash(7, "vip", "old")
        val current = BrandSplash(7, "dlc", "new")
        val server = listOf(current)
        val result = BrandSplashPolicy.retainSelectedVip(server, listOf(old), { it.source },
            { "${it.source}#${it.id}" }, { it.id.toString() })
        assertSame(server, result)
    }

    fun modelRecompositionMarksChangedAndPreservesCallbackAndOtherFlags() {
        for (flags in listOf(0, 2, 8, 16, 32, 0b1010101010101)) {
            val changed = brandSplashChangedModelFlags(flags)
            assertEquals(4, changed and 14)
            assertEquals(flags and 14.inv(), changed and 14.inv())
        }
    }

    fun serializedConstructorOffsetCannotGuessAnUnsupportedLayout() {
        assertEquals(0, brandSplashSerializationOffset(9))
        assertEquals(1, brandSplashSerializationOffset(10))
        assertTrue(runCatching { brandSplashSerializationOffset(8) }.isFailure)
        assertTrue(runCatching { brandSplashSerializationOffset(11) }.isFailure)
    }

    fun warmupCannotOverwriteNewerWritesOrAnExplicitModeChange() {
        val state = BrandSplashSelectionState<String>()
        state.observeMode(true)
        state.observeWrite(listOf("current"))
        state.initialize(listOf("stale"), false)
        assertEquals(listOf("current"), state.snapshot().selected)
        assertTrue(state.snapshot().customMode)
        val previous = state.snapshot().revision
        state.observeMode(false)
        assertFalse(state.isCurrent(previous)); assertTrue(state.snapshot().selected.isEmpty())
    }

    fun disabledFeaturesRegisterNothing() {
        assertTrue(BrandSplashSkipFeatureInstaller(false).install(environment) is FeatureInstallResult.Skipped)
        assertTrue(BrandSplashCustomFeatureInstaller(false, context()).install(environment) is FeatureInstallResult.Skipped)
        assertTrue(registrar.hooks.isEmpty())
    }

    fun equivalentWriteResumesRotationFromTheImageThatActuallyExited() {
        val state = BrandSplashSelectionState<String>()
        val loading = state.initialize(listOf("first", "second"), true)
        state.observeWrite(listOf("first", "second"))
        assertFalse(state.isCurrent(loading.revision))
        val reload = brandSplashImageReloadAfterWrite("same-selection", "same-selection", true, "first")
        assertNotNull(reload)
        assertEquals("first", reload?.afterKey)
    }

    fun equivalentWriteRetriesAnEmptyCacheButDoesNotDecodeAnIdleImageAgain() {
        val firstLoad = brandSplashImageReloadAfterWrite("selected", null, false, null)
        assertNotNull(firstLoad)
        assertNull(firstLoad?.afterKey)
        assertNull(brandSplashImageReloadAfterWrite("selected", "selected", false, "first"))
        assertNull(brandSplashImageReloadAfterWrite("", "selected", true, "first"))
    }

    fun bitmapSamplingKeepsNarrowAndRoundedUpDimensionsWithinThePixelBudget() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1,
                4097 to 4097, 2048 to 2048, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = brandSplashBitmapSampleSize(width, height)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            val pixels = ((width.toLong() + sample - 1) / sample) *
                ((height.toLong() + sample - 1) / sample)
            assertTrue(pixels <= BrandSplashBitmapDecoder.MAX_PIXELS)
        }
        assertEquals(512, brandSplashBitmapSampleSize(1, Int.MAX_VALUE))
        assertEquals(4, brandSplashBitmapSampleSize(4097, 4097))
        assertEquals(1, brandSplashBitmapSampleSize(2048, 2048))
    }

    fun compactPageAbiAndStorageResolveByStructureAcrossObfuscatedNames() {
        assertNotNull(brandSplashPageAbi(listOf("x9a0", "kotlin.jvm.functions.Function0",
            "androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function0",
            "androidx.compose.runtime.Composer", "int")))
        assertNull(brandSplashPageAbi(listOf("int", "kotlin.jvm.functions.Function0",
            "androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function0",
            "androidx.compose.runtime.Composer", "int")))

        val renamedStorageLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == BrandSplashStorageAccess.STORAGE) FlatWave1BrandStorage::class.java
                else super.loadClass(name, resolve)
        }
        assertNotNull(BrandSplashStorageAccess.resolve(renamedStorageLoader))
    }

    class TemplateMutableState
    class TemplateState
    object TemplateIntrinsics { @JvmStatic fun getCOROUTINE_SUSPENDED(): Any = Any() }
    class TemplateResultFailure
    class TemplateRenamedExit(@JvmField val `$a`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$b`: TemplateMutableState? = null
        @JvmField var `$c`: TemplateMutableState? = null
        @JvmField var `$d`: TemplateMutableState? = null
        @JvmField var `$e`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }
    class TemplateBrokenSingleMutableExit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customLogoReady$delegate`: TemplateMutableState? = null
        @JvmField var `$currentOnShow`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }

    fun `brand splash constructor and obfuscated exit coroutine resolve safely while keeping broken exit rejected`() {
        val state = "androidx.compose.runtime.MutableState"
        val intState = "androidx.compose.runtime.MutableIntState"
        val prefix = listOf("Img", "Model", "androidx.compose.ui.unit.Density", "Res", state)
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(state, state), "Img", "Model", "Res"))
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(intState, state, state), "Img", "Model", "Res"))
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(intState, state, state, state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state, intState, state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state, "java.lang.String"), "Img", "Model", "Res"))

        val renamingLoader = TestRenamingClassLoader(
            loader,
            templates = mapOf(
                BrandSplashKntrAccess.CURRENT_EXIT to TemplateRenamedExit::class.java,
                BrandSplashKntrAccess.BITMAP_EXIT to TemplateBrokenSingleMutableExit::class.java,
                "kotlin.coroutines.intrinsics.IntrinsicsKt" to TemplateIntrinsics::class.java,
                "kotlin.Result\$Failure" to TemplateResultFailure::class.java,
                "androidx.compose.runtime.MutableState" to TemplateMutableState::class.java,
                "androidx.compose.runtime.State" to TemplateState::class.java
            ),
            replacements = mapOf(
                TemplateMutableState::class.java.name.replace('.', '/') to "androidx/compose/runtime/MutableState",
                TemplateState::class.java.name.replace('.', '/') to "androidx/compose/runtime/State"
            )
        )
        val exit = BrandSplashKntrAccess.exit(renamingLoader)
        assertNotNull(exit)
        assertEquals(BrandSplashKntrAccess.CURRENT_EXIT, exit!!.owner.name)
        assertTrue(exit.waitsForImages)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BrandSplashBehaviorTest) -> Unit) {
            val s = BrandSplashBehaviorTest()
            block(s)
        }
        runIsolated { s -> s.nativeDwellChangesOnlyForTheCurrentBrandPageModelAndUnwindsAfterFailure() }
        runIsolated { s -> s.kntrExitUsesTheCurrentCallbackExactlyOnceAndLeavesOtherResumeStatesAlone() }
        runIsolated { s -> s.failedExitRestoresTheInitialCoroutineLabelAndRunsTheOriginalPath() }
        runIsolated { s -> s.clientUnlockCannotEscapeTheOfficialActionOrGrantDlcOrForbiddenChoices() }
        runIsolated { s -> s.previewUsesItsOwnSourceSignalAndCannotReuseAnEarlierVipDecision() }
        runIsolated { s -> s.refreshRetainsOnlyPersistedVipAndAConfirmedRemovalStopsRetention() }
        runIsolated { s -> s.nullServerListCannotClearAPersistedVipSelectionDuringHostReconciliation() }
        runIsolated { s -> s.nullServerListStaysNullWithoutAnActivePersistedVipChoice() }
        runIsolated { s -> s.freshServerMetadataWinsOverTheOldPersistedInstance() }
        runIsolated { s -> s.reclassifiedDlcCannotBeReintroducedAsAnOldVipChoice() }
        runIsolated { s -> s.modelRecompositionMarksChangedAndPreservesCallbackAndOtherFlags() }
        runIsolated { s -> s.serializedConstructorOffsetCannotGuessAnUnsupportedLayout() }
        runIsolated { s -> s.warmupCannotOverwriteNewerWritesOrAnExplicitModeChange() }
        runIsolated { s -> s.disabledFeaturesRegisterNothing() }
        runIsolated { s -> s.equivalentWriteResumesRotationFromTheImageThatActuallyExited() }
        runIsolated { s -> s.equivalentWriteRetriesAnEmptyCacheButDoesNotDecodeAnIdleImageAgain() }
        runIsolated { s -> s.bitmapSamplingKeepsNarrowAndRoundedUpDimensionsWithinThePixelBudget() }
        runIsolated { s -> s.compactPageAbiAndStorageResolveByStructureAcrossObfuscatedNames() }
        runIsolated { s -> s.`brand splash constructor and obfuscated exit coroutine resolve safely while keeping broken exit rejected`() }
    }
}
