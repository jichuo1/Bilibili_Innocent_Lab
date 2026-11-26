package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import org.junit.Assert.*
import org.junit.Test

class WeChatShareDexWiringContractTest {
    @Test fun wechatDiscoveryIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("WeChatShareCompatAccess.assisted"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = VersionAdapterSource.function("adapt") { it.contains("previousPopup:") }
        assertTrue(background.contains("if (weChatSharePlanned) addAll(WeChatShareCompatAccess.queries)"))
        assertTrue(background.contains("WeChatShareCompatAccess.refreshCache(weChatShareEnabled,"))
        val installer = SourceContract.read("hook/feature/WeChatShareCompatFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("WeChatShareCompatAccess.locate(environment.classLoader, hostVersionCode(), cachedClasses)"))
        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY -> listOf(2, 3, 4)")
            .before("DexAssistQuery.STORY_QUALITY_DEFAULT ->")
        assertTrue(queries.contains("createWXAPI"))
    }
}
