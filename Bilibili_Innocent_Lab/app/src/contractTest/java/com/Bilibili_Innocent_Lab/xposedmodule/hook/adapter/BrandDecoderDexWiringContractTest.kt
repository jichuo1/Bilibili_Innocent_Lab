package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class BrandDecoderDexWiringContractTest {
    @Test fun `new decoder uses shared background session and cached descriptor reaches worker`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexRefHookBridge"))
        assertFalse(quick.contains("BrandSplashDecoderAdapter.locate("))
        assertTrue(adapter.contains("if (brandPlanned) add(BrandSplashDecoderAdapter.query)"))
        assertTrue(adapter.contains("BrandSplashDecoderAdapter.locate(loader, dexAssist, brandSplashCustomEnabled, previousBrand, brandPlanned)"))
        val query = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.BRAND_SPLASH_DECODER ->").before("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->")
        for (mark in listOf("BrandSplashDecoderAdapter.INFO", "BrandSplashDecoderAdapter.MARK", "getThumbHash", "decodeFile", "Modifier.STATIC"))
            assertTrue(mark, query.contains(mark))
        assertTrue(SourceContract.read("hook/HookEntry.kt").contains("decoderPoint = hostAdaptResult?.brandSplashDecoder"))
        assertTrue(SourceContract.read("hook/feature/BrandSplashImageCache.kt").contains("BrandSplashBitmapDecoder(environment, decoderPoint)"))
        assertFalse(SourceContract.read("hook/feature/BrandSplashBitmapDecoder.kt").contains("DexRefHookBridge"))
    }
}
