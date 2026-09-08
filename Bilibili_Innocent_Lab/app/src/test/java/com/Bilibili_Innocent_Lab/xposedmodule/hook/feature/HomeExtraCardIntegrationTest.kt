package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.pegasus.PegasusHolderData
import org.junit.Assert.*
import org.junit.Test

class HomeExtraCardIntegrationTest {
    private class Card(private val type: String, private val route: String = "bilibili://video/123") : PegasusHolderData() {
        override fun getCardType() = type
        override fun getCardGoto() = "av"
        override fun getGoTo() = "av"
        override fun getUri() = route
        override fun getTitle() = error("New filters must not read titles")
    }
    private val points get() = requireNotNull(VersionAdapter.locateHomeRecommendFeed(javaClass.classLoader!!))
    private fun installer(pgc: Boolean, special: Boolean, adapted: VersionAdapter.HomeRecommendFeedPoints = points) =
        HomeRecommendPurifyFeatureInstaller(false, false, false, false, false, false, "",
            false, false, false, false, 0, 0, adapted, pgc, special)

    private fun environment(recorded: PlayerPortTestRegistrar, evidence: MutableList<Pair<String, FeatureRuntimeStage>>) =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
            registrar = object : HookRegistrar by recorded {
                override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                    recorded.exact(id, Class.forName(point.className), point.methodName,
                        *point.paramClassNames.orEmpty().map { Class.forName(it) }.toTypedArray(), block = block)
                }
            }, logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, _ -> },
            runtimeEvidence = { id, stage, _ -> evidence += id to stage })

    @Test fun bothFlagsFilterIndependentlyWithoutMutatingTheHostList() {
        val ordinary = Card("av")
        val pgc = Card("bangumi")
        val special = Card("special_s")
        val specialFilm = Card("special", "bilibili://bangumi/play/ep123")
        val unknown = Card("special_future")
        val source = listOf(ordinary, pgc, special, specialFilm, unknown)
        for (removePgc in listOf(false, true)) for (removeSpecial in listOf(false, true)) {
            val recorded = PlayerPortTestRegistrar()
            val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
            val result = installer(removePgc, removeSpecial).install(environment(recorded, evidence))
            if (!removePgc && !removeSpecial) {
                assertTrue(result is FeatureInstallResult.Skipped)
                assertTrue(recorded.hooks.isEmpty())
                continue
            }
            assertTrue(result is FeatureInstallResult.Installed && result.complete)
            for (id in recorded.hooks.keys) {
                val filtered = recorded.invoke(id) { source }
                assertEquals(source.filterNot { (removePgc && (it === pgc || it === specialFilm)) ||
                    (removeSpecial && it === special) }, filtered)
            }
            assertEquals(5, source.size)
            assertEquals(removePgc, ("home_recommend_pgc_removed" to FeatureRuntimeStage.APPLIED) in evidence)
            assertEquals(removeSpecial, ("home_recommend_special_cards_removed" to FeatureRuntimeStage.APPLIED) in evidence)
        }
    }

    @Test fun noMatchKeepsIdentityAndHostExceptionsAreNeverConvertedToSuccess() {
        val recorded = PlayerPortTestRegistrar()
        val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        installer(true, true).install(environment(recorded, evidence))
        val source = listOf(Card("av"), Card("bangumi_ugc"), Any())
        val failure = IllegalStateException("host failure")
        for (id in recorded.hooks.keys) {
            assertSame(source, recorded.invoke(id) { source })
            assertSame(failure, assertThrows(IllegalStateException::class.java) { recorded.invoke(id) { throw failure } })
        }
        assertFalse(evidence.any { it.second == FeatureRuntimeStage.APPLIED })
    }

    @Test fun missingSpecialReadersDoNotDisableTheWorkingPgcRoute() {
        val records = mutableListOf<FeatureInstallRecord>()
        val recorded = PlayerPortTestRegistrar()
        val env = environment(recorded, mutableListOf()).copy(installationEvidence = { records += it })
        FeatureInstallCoordinator(env).installAll(listOf(installer(true, true,
            points.copy(cardTypeGetter = null, cardGotoGetter = null, goToGetter = null))))
        assertTrue((records.last { it.id == "home_recommend_pgc_removed" }.result as FeatureInstallResult.Installed).complete)
        assertTrue(records.last { it.id == "home_recommend_special_cards_removed" }.result is FeatureInstallResult.Skipped)
        val source = listOf(Card("av", "bilibili://bangumi/play/ep123"), Card("special"))
        for (id in recorded.hooks.keys) assertEquals(listOf(source[1]), recorded.invoke(id) { source })
    }
}
