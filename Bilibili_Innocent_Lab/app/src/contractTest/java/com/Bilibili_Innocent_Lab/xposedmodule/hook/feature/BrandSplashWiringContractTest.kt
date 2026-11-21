package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.*
import org.junit.Test

class BrandSplashWiringContractTest {
    fun independentDefaultOffSettingsWireSkipPriorityAndOfficialPicker() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("BrandSplashSkipFeatureInstaller("))
        assertTrue(hook.contains("BrandSplashCustomFeatureInstaller("))
        assertTrue(hook.contains("renderEnabled = !prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false)"))
        val system = SettingsUiSource.function("enhanceSystemCategory")
        val appearance = SettingsUiSource.function("beautificationSettingsCard")
        assertTrue(system.contains("FeaturePreferences.BRAND_SPLASH_SKIP"))
        assertTrue(appearance.contains("FeaturePreferences.BRAND_SPLASH_CUSTOM"))
    }

    fun originalDurationAndCompletionContractsStayUnmodifiedOutsideTheirPage() {
        val skip = SourceContract.read("hook/feature/BrandSplashSkipFeatureInstaller.kt")
        val native = skip.after("private fun installNative").before("private fun installKntr")
        assertTrue(native.contains("instance !== target"))
        assertTrue(native.contains("nativeFrames.leave()"))
        assertFalse(skip.contains("setDuration"))
        assertFalse(skip.contains("DelayKt"))
        assertFalse(skip.contains("finish("))
        assertTrue(skip.contains("result = hostUnit"))
    }

    fun customPaintNeverMutatesTheOriginalExitOrLogoAndNeverReadsFilesOnTheUiThread() {
        val painter = SourceContract.read("hook/feature/BrandSplashKntrRenderer.kt")
        assertFalse(painter.contains("args[1] ="))
        assertTrue(painter.contains("frame.mainConsumed"))
        assertTrue(painter.contains("drawModel::get"))
        assertTrue(painter.contains("WeakHashMap"))
        assertFalse(painter.contains("decodeFile("))
        val cache = SourceContract.read("hook/feature/BrandSplashImageCache.kt")
        assertTrue(cache.contains("removeOnCancelPolicy = true"))
        assertTrue(cache.contains("state.isCurrent(revision)"))
        assertTrue(cache.contains("brandSplashImageReloadAfterWrite(signature, cached?.signature"))
        assertTrue(cache.contains("cached?.exitRequested?.get() == true, cached?.image?.key"))
        assertTrue(cache.contains("request(snapshot, images, signature, reload.afterKey)"))
        assertFalse(cache.contains("bitmap.recycle()"))
        val decoder = SourceContract.read("hook/feature/BrandSplashBitmapDecoder.kt")
        assertTrue(decoder.contains("brandSplashBitmapSampleSize(bounds.outWidth, bounds.outHeight)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BrandSplashWiringContractTest) -> Unit) {
            val s = BrandSplashWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.independentDefaultOffSettingsWireSkipPriorityAndOfficialPicker() }
        runIsolated { s -> s.originalDurationAndCompletionContractsStayUnmodifiedOutsideTheirPage() }
        runIsolated { s -> s.customPaintNeverMutatesTheOriginalExitOrLogoAndNeverReadsFilesOnTheUiThread() }
    }
}
