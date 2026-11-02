package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as Keys
import org.junit.Assert.*
import org.junit.Test

class StartupAdapterPlanTest {
    @Test fun defaultsOnlyOmitAuditedOptionalFamiliesAndRetainCopyBindings() {
        val excluded = StartupAdapterPlan.excluded(SnapshotHookConfigSource(emptyMap()))
        assertEquals(13, excluded.size)
        assertNull(StartupAdapterPlan.diagnosticFamily("comment.low"))
        assertNull(StartupAdapterPlan.diagnosticFamily("comment.high"))
        assertFalse("VIDEO_RELATE" in excluded)
        assertNull(StartupAdapterPlan.diagnosticFamily("home.banner"))
    }

    @Test fun eachPurificationSwitchAndSharedTopologyDependencyRetainTheirLocators() {
        for (key in listOf(Keys.REMOVE_COMMENT_SEARCH_LINKS, Keys.REMOVE_COMMENT_EMPTY_GUIDE,
            Keys.REMOVE_COMMENT_VOTE_WIDGETS, Keys.REMOVE_COMMENT_FOLLOW_BUTTONS, Keys.REMOVE_COMMENT_QOE,
            Keys.REMOVE_COMMENT_OPERATIONS, Keys.BLOCK_COMMENT_QUICK_REPLY)) {
            val excluded = StartupAdapterPlan.excluded(SnapshotHookConfigSource(mapOf(key to true)))
            assertFalse("Purification consumer lost: $key", "COMMENT_PURIFY" in excluded)
        }
        val topology = StartupAdapterPlan.excluded(SnapshotHookConfigSource(mapOf(Keys.REPLY_TOPOLOGY_ENABLED to true)))
        assertFalse("COMMENT_TOPOLOGY" in topology)
        assertFalse("COMMENT_FILTER" in topology)
        assertTrue("PLAYER_QUALITY" in topology)
    }

    @Test fun qualityNormalizationAndIndependentFilterConsumersRemainConservative() {
        assertFalse("PLAYER_QUALITY" in StartupAdapterPlan.excluded(SnapshotHookConfigSource(mapOf(Keys.PLAYER_DEFAULT_QUALITY_QN to 80))))
        assertTrue("PLAYER_QUALITY" in StartupAdapterPlan.excluded(SnapshotHookConfigSource(mapOf(Keys.PLAYER_DEFAULT_QUALITY_QN to 999))))
        for (key in listOf(Keys.COMMENT_KEYWORD_FILTER_ENABLED, Keys.COMMENT_MIN_LEVEL_FILTER_ENABLED,
            Keys.COMMENT_USER_FILTER_ENABLED, Keys.REMOVE_AT_ONLY_COMMENTS, Keys.COMMENT_SEMANTIC_FILTER_ENABLED)) {
            assertFalse("Filter consumer lost: $key", "COMMENT_FILTER" in
                StartupAdapterPlan.excluded(SnapshotHookConfigSource(mapOf(key to true))))
        }
    }

    private val noReads = object : HookConfigSource {
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = error("Unexpected config read")
        override fun getInt(key: String, defaultValue: Int): Int = error("Unexpected config read")
        override fun getLong(key: String, defaultValue: Long): Long = error("Unexpected config read")
        override fun getString(key: String, defaultValue: String): String = error("Unexpected config read")
    }

    @Test fun knownChildrenSkipOnlyAuditedMainConsumersWithoutReadingTheirConfiguration() {
        for (process in listOf("tv.danmaku.bili:web", "tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice")) {
            val excluded = StartupAdapterPlan.excludedForProcess(noReads, process, true)
            assertEquals(14, excluded.size)
            assertTrue("VIDEO_RELATE" in excluded)
            assertFalse("COMMENT_LOW" in excluded)
            assertFalse("HOME_BANNER" in excluded)
        }
    }

    @Test fun standardFrameworkAndUnknownProcessesKeepCompleteLookupWithoutConfigReads() {
        for (process in listOf("tv.danmaku.bili", "tv.danmaku.bili:web", "tv.danmaku.bili:future"))
            assertTrue(StartupAdapterPlan.excludedForProcess(noReads, process, false).isEmpty())
        assertTrue(StartupAdapterPlan.excludedForProcess(noReads, "tv.danmaku.bili:future", true).isEmpty())
    }

    @Test fun mainProcessRetainsComplexRelateLookupAndEnabledQuality() {
        val excluded = StartupAdapterPlan.excludedForProcess(SnapshotHookConfigSource(mapOf(Keys.PLAYER_DEFAULT_QUALITY_QN to 80)),
            "tv.danmaku.bili", true)
        assertFalse("VIDEO_RELATE" in excluded)
        assertFalse("PLAYER_QUALITY" in excluded)
        assertTrue("COMMENT_PURIFY" in excluded)
    }
}
