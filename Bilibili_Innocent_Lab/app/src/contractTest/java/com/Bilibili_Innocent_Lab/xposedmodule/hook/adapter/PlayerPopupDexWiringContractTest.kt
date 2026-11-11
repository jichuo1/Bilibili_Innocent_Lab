package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class PlayerPopupDexWiringContractTest {
    @Test fun `popup dex queries remain gated in the shared background session and cached per family`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("PlayerPopupPromotionAdapter.locate("))
        assertFalse(quick.contains("PlayerPopupPromotionAdapter.direct("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (playerPopupPromotionEnabled) PlayerPopupPromotionAdapter.missing(loader)"))
        assertTrue(background.contains("popupPlanned.forEach { add(it.query) }"))
        assertTrue(background.contains("PlayerPopupPromotionAdapter.planned(loader, previousPopup, previouslyMissingPopup, playerPopupPromotionEnabled)"))
        assertTrue(background.contains("PlayerPopupPromotionAdapter.locate(loader, dexAssist, true, previousPopup, popupPlanned)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(adapter.contains("PlayerPopupPromotionAdapter.merge(runtime.playerPopupPromotion, cached.playerPopupPromotion)"))
        val refresh = adapter.after("val popupRefresh =").before("val classicRefresh =")
        assertTrue(refresh.contains("it.id == family.diagnostic && it.state == AdaptState.MISSING"))
        val inheritance = adapter.after("val usable = startup.usableCache(highCandidateExists)").before("val cached = usable?.takeUnless")
        assertTrue(inheritance.contains("val popupHistory = startup.usableCache(highCandidateExists = false)"))
        assertTrue(inheritance.contains("previousPopup = popupHistory?.playerPopupPromotion"))
        assertTrue(inheritance.contains("popupHistory?.diagnostics.orEmpty()"))
        val installer = SourceContract.read("hook/feature/PlayerPopupPromotionFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("playerPopupPromotionEnabled = prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, false)"))
        assertTrue(hook.contains("cachedPoints = { popupPointsRef.get() }"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(popupInstaller))"))
    }

    @Test fun `skipped cache families cannot trigger the sessions unplanned fallback query`() {
        val locator = SourceContract.read("hook/adapter/PlayerPopupPromotionAdapter.kt")
        val assisted = locator.after("fun assisted(family: Family)").before("return PlayerPopupPromotionPoints(direct.freeDataGuide")
        assertTrue(assisted.before("session?.result(family.query)").contains("if (!enabled || family !in planned) return null"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val diagnostics = adapter.after("val popupDiagnostics =").before("val classicUnresolved =")
        assertTrue(diagnostics.contains("cached-attempt-preserved"))
        assertTrue(diagnostics.contains("feature-disabled:cached-attempt-preserved"))
        assertTrue(diagnostics.contains("playerPopupPromotionEnabled || family in previouslyMissingPopup"))
        assertTrue(diagnostics.before("dexAssist.result(family.query)").contains("family in previouslyMissingPopup && family in popupUnresolved && family !in popupPlanned"))
        val locatorEntry = locator.after("fun locate(").before("val direct = direct(loader)")
        assertTrue(locatorEntry.contains("if (!enabled) return cached ?: PlayerPopupPromotionPoints()"))
        val disabledCarry = adapter.after("val popup = if (playerPopupPromotionEnabled)").before("val popupDiagnostics =")
        assertTrue(disabledCarry.contains("else previousPopup"))
        assertTrue(disabledCarry.contains("if (playerPopupPromotionEnabled && popup != null)"))
    }

    @Test fun `dex signatures require promotion semantics and exclude ordinary danmaku panel candidates`() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val guide = engine.after("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->").before("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->")
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK"))
        assertTrue(guide.contains("contains = true"))
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.NETWORK_PACKAGE"))
        assertTrue(guide.contains("returnType = \"java.lang.Object\""))
        assertTrue(guide.contains("paramCount(1)"))
        val locator = SourceContract.read("hook/adapter/PlayerPopupPromotionAdapter.kt")
        assertTrue(locator.contains("const val GUIDE_FLOW_MARK = \"already show free data guide toast in this cold start\""))
        // 8.84.0 的次数键、配置读取及曝光事件都在 helper，不能作为入口的必要 AND 条件。
        assertFalse(guide.contains("GUIDE_COUNTER")); assertFalse(guide.contains("GUIDE_SHOW_EVENT"))
        assertFalse(guide.contains("FREQUENCY_KEY")); assertFalse(guide.contains("addInvoke"))
        val float = engine.after("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->").before("DexAssistQuery.COMMENT_CLASSIC_NATIVE,")
        for (name in listOf("isValidCommon", "isValidCommerce", "isValidGuide")) assertTrue(float.contains(name))
        val collector = SourceContract.read("hook/adapter/dex/DexMeguvizAssistEngine.kt")
        val collection = collector.after("if (query == DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT)")
            .before("if (query == DexAssistQuery.STORY_QUALITY_MATCH &&")
        assertTrue(collection.contains("invokes(data)"))
        assertTrue(collector.contains("inspected.rowCount() > MAX_INVOKES"))
        assertTrue(collector.contains("MAX_INVOKES = 128"))
        assertTrue(collection.contains("isValidDanmaku"))
        assertTrue(collection.contains("PlayerPopupPromotionAdapter.verifiedAdvertFloat(method)"))
    }
}
