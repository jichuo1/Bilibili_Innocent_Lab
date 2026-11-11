package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class FollowFeedDexWiringContractTest {
    @Test fun `new style query runs only through planned background session and installs revalidated descriptors`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexRefHookBridge"))
        assertFalse(quick.contains("FollowFeedAdapter.locate("))
        assertTrue(adapter.contains("if (followPlanned) add(DexAssistQuery.FOLLOW_FEED_STYLE)"))
        assertTrue(adapter.contains("FollowFeedAdapter.locate(loader, dexAssist, followFeedStyleEnabled, previousFollow, followPlanned)"))
        val query = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.FOLLOW_FEED_STYLE ->").before("DexAssistQuery.BRAND_SPLASH_DECODER ->")
        for (mark in listOf("getItemOffsets", "FollowFeedAdapter.DECORATION_SOURCE", "FollowFeedAdapter.OFFSETS_MARK", "RecyclerView"))
            assertTrue(mark, query.contains(mark))
        assertTrue(SourceContract.read("hook/HookEntry.kt").contains("points = hostAdaptResult?.followFeedStyle"))
        val installer = SourceContract.read("hook/feature/FollowFeedFeatureInstaller.kt")
        assertTrue(installer.contains("FollowFeedAdapter.resolve(loader, points)"))
        assertFalse(installer.contains("DexRefHookBridge"))
    }
}
