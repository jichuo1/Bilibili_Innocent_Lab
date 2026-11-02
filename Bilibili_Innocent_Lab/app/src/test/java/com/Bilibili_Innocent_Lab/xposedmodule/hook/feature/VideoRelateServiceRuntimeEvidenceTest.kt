package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.bilibili.ship.theseus.united.page.intro.module.relate.ModernRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType
import com.bilibili.ship.theseus.united.page.intro.module.relate.r2
import org.junit.Assert.*
import org.junit.Test

class VideoRelateServiceRuntimeEvidenceTest {
    @Test fun verifiedDtoServiceBlockAttributesOnlyTheMatchedTypeBeforeCallingOriginal() {
        val loader = requireNotNull(javaClass.classLoader)
        val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
        val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(
            detailRelateService = VersionAdapter.DetailRelateServicePoint(
                VersionAdapter.HookPoint(factory.declaringClass.name, factory.name,
                    paramClassNames = factory.parameterTypes.map { it.name }),
                typeField = "a", typeGetter = VersionAdapter.HookPoint(r2::class.java.name, "y")
            )
        )
        val registrar = PlayerPortTestRegistrar()
        val evidence = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        val result = VideoRelateFilterFeatureInstaller(setOf("CM", "GAME"), 0, 0, points = points)
            .install(HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> evidence += Triple(id, stage, count) }))
        assertTrue(result is FeatureInstallResult.Installed)
        val item = r2()
        requireNotNull(Lookup.fieldOrNull(r2::class.java, "a")).set(item, RelateCardType.CM)
        var called = false
        assertNull(registrar.invoke("video.relate.service", ModernRelateService(), arrayOf(item)) {
            called = true
            com.bilibili.app.gemini.ui.RunningUIComponent()
        })
        assertFalse(called)
        assertTrue(evidence.contains(Triple("video_related_commercial_removed", FeatureRuntimeStage.OBSERVED, 1)))
        assertTrue(evidence.contains(Triple("video_related_commercial_removed", FeatureRuntimeStage.APPLIED, 1)))
        assertFalse(evidence.any { it.first == "video_related_game_removed" })
    }
}
