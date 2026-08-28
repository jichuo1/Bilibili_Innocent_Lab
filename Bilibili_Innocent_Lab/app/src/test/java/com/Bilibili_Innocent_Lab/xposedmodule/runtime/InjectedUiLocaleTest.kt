package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class InjectedUiLocaleTest {

    @Test
    fun normalizesOnlySupportedSelectionTags() {
        assertEquals(InjectedUiLocale.TAG_SYSTEM, InjectedUiLocale.normalizeSelectionTag(null))
        assertEquals(InjectedUiLocale.TAG_SYSTEM, InjectedUiLocale.normalizeSelectionTag("fr-FR"))
        assertEquals(InjectedUiLocale.TAG_ENGLISH, InjectedUiLocale.normalizeSelectionTag("en-US"))
        assertEquals(
            InjectedUiLocale.TAG_SIMPLIFIED_CHINESE,
            InjectedUiLocale.normalizeSelectionTag("zh-Hans-SG")
        )
    }

    @Test
    fun mapsTraditionalScriptAndRegionsToOneStableTag() {
        listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO").forEach { rawTag ->
            assertEquals(
                InjectedUiLocale.TAG_TRADITIONAL_CHINESE,
                InjectedUiLocale.normalizeSelectionTag(rawTag)
            )
        }
    }

    @Test
    fun returnsPrebuiltEnglishAndChineseSnapshotsWithoutContext() {
        assertEquals(
            "Trace",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_ENGLISH
            ).replyTopologyEntryLabel
        )
        assertEquals(
            "脉络",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_SIMPLIFIED_CHINESE
            ).replyTopologyEntryLabel
        )
        assertEquals(
            "脈絡",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_TRADITIONAL_CHINESE
            ).replyTopologyEntryLabel
        )
    }
}
