package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.bilibili.search2.api.SearchSquareType
import com.bilibili.search2.api.SearchReferral
import com.bilibili.search2.discover.*
import com.bilibili.search2.main.data.i
import org.junit.Assert.*
import org.junit.Test

class SearchHomeRecommendTest {
    private class Harness(enabled: Boolean = true, val registrar: PlayerPortTestRegistrar = PlayerPortTestRegistrar(),
        loader: ClassLoader = SearchHomeRecommendTest::class.java.classLoader!!) {
        val stages = mutableListOf<FeatureRuntimeStage>()
        val messages = mutableListOf<String>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, text -> messages += text }, { _, text -> messages += text }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        val result = SearchHomeRecommendFeatureInstaller(enabled).install(env)
        val model = r()
        val callback = q(model)
        fun deliver(source: List<SearchSquareType>) {
            registrar.invoke("search.home.sections", callback, arrayOf(source)) { args ->
                @Suppress("UNCHECKED_CAST")
                callback.f(args[0] as List<SearchSquareType>)
            }
        }
    }

    @Test fun fullLocatorFindsTypedDeliveryRefreshAndStateWithoutHardcodedMemberNames() {
        val point = requireNotNull(SearchHomeRecommendLocator.locate(javaClass.classLoader!!))
        assertNotNull(point.delivery); assertNotNull(point.delivery?.refresh); assertNotNull(point.state)
        assertTrue(point.stateExpected)
        val h = Harness()
        assertEquals(FeatureInstallResult.Installed(4), h.result)
        assertEquals(3, h.registrar.hooks.size)
    }

    @Test fun removesCompleteSectionsAndPreservesHistoryUnknownsOrderAndOriginalList() {
        val h = Harness()
        val history = SearchSquareType("history")
        val unknown = SearchSquareType("future_section")
        val source = listOf(SearchSquareType("trending"), history, SearchSquareType("recommend"), unknown)
        h.deliver(source)
        assertEquals(listOf(history,unknown),h.model.sections)
        assertSame(history,h.model.sections!![0])
        assertEquals(4,source.size)
        assertFalse(h.model.hot); assertFalse(h.model.discovery); assertFalse(h.model.feedback)
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun localHistoryReadAddDeleteAndFoldDataAreNotIntercepted() {
        val h = Harness()
        val histories = listOf(History("local fixture"))
        h.callback.e(histories)
        h.deliver(listOf(SearchSquareType("trending"),SearchSquareType("history"),SearchSquareType("recommend")))
        assertSame(histories,h.callback.getHistoryList())
        h.callback.e(emptyList())
        assertTrue(h.callback.getHistoryList().isEmpty())
        assertFalse(h.registrar.hooks.keys.any { it.contains("history") })
    }

    @Test fun asyncDiscoveryRefreshCannotRepopulateWordsOrFeedbackButHistoryStillUpdates() {
        val h = Harness()
        val source = listOf(SearchSquareType("history"),SearchSquareType("recommend"))
        h.deliver(source)
        var calls = 0
        h.registrar.invoke("search.home.refresh",h.callback,arrayOf(listOf(SearchReferral.Guess()))) { calls++ }
        assertEquals(0,calls); assertFalse(h.model.discovery); assertFalse(h.model.feedback)
        h.callback.e(listOf(History("new"))); assertEquals(1,h.callback.getHistoryList().size)
    }

    @Test fun constructorPublishesFilteredStateAndDoesNotMutateOriginal() {
        val h = Harness()
        val history = SearchSquareType("history")
        val source = listOf(SearchSquareType("trending"),history,SearchSquareType("recommend"))
        // A real constructor callback receives an allocated instance before its body.
        val target = i(null)
        val field = i::class.java.getField("sections").apply { isAccessible = true }
        h.registrar.invoke("search.home.state",target,arrayOf(source)) { args -> field.set(target,args[0]) }
        assertEquals(listOf(history),target.sections)
        assertEquals(3,source.size)
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun unchangedEmptyAndUnknownTypesPreserveIdentity() {
        val h = Harness()
        for (source in listOf(emptyList(),listOf(SearchSquareType("history")),listOf(SearchSquareType("TRENDING"),SearchSquareType("trending_extra")))) {
            h.deliver(source)
            assertSame(source,h.model.sections)
        }
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun anomalousResponseWithoutRetainedAnchorFailsOpenAndExplainsWhy() {
        val h = Harness()
        val source = listOf(SearchSquareType("trending"),SearchSquareType("recommend"))
        h.deliver(source)
        assertSame(source,h.model.sections)
        assertTrue(h.messages.any { it.contains("锚点") })
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun failedTypeReadLeavesArgumentsUntouched() {
        val h = Harness()
        val source = listOf(SearchSquareType("history"),SearchSquareType("trending",broken = true))
        h.registrar.invoke("search.home.sections",h.callback,arrayOf(source)) { args -> assertSame(source,args[0]) }
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun hostExceptionsArePreservedAndFailedDeliveryNeverReportsApplied() {
        val h = Harness()
        val failure = IllegalStateException("original")
        val source = listOf(SearchSquareType("history"),SearchSquareType("trending"))
        assertSame(failure,assertThrows(IllegalStateException::class.java) {
            h.registrar.invoke("search.home.sections",h.callback,arrayOf(source)) { throw failure }
        })
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun disabledFeatureRegistersNothing() {
        val h = Harness(false)
        assertTrue(h.registrar.hooks.isEmpty())
        assertEquals(FeatureInstallResult.Skipped("disabled"),h.result)
    }

    @Test fun registrationFailureRemainsInCoverageDenominator() {
        val h = Harness(registrar = PlayerPortTestRegistrar("search.home.refresh"))
        assertEquals(FeatureInstallResult.Installed(3,false),h.result)
    }

    @Test fun missingModernStateIsNotHiddenWhenModernViewModelStillExists() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == i::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertEquals(FeatureInstallResult.Installed(3,false),Harness(loader = loader).result)
    }

    @Test fun staleDiscoveryAndFeedbackAreClearedWithoutMutatingOldModelOrHistory() {
        val h = Harness()
        val old = g(listOf(SearchReferral.Guess()),"fixture",com.bilibili.search2.api.NegativeFeedback())
        h.model.recommendation.setValue(old)
        val history = listOf(History("fixture"))
        h.callback.e(history)
        h.deliver(listOf(SearchSquareType("history")))
        assertFalse(h.model.feedback); assertFalse(h.model.discovery)
        assertNotNull(old.feedback); assertEquals(1,old.values.size)
        assertSame(history,h.model.history)
        val writes = h.model.recommendation.writes
        h.deliver(listOf(SearchSquareType("history")))
        assertEquals(writes,h.model.recommendation.writes)
    }

    @Test fun staleStateWriteFailureDoesNotCrashOrReportApplied() {
        val h = Harness()
        h.model.recommendation.setValue(g(listOf(SearchReferral.Guess()),"",com.bilibili.search2.api.NegativeFeedback()))
        h.model.recommendation.ignore = true
        h.deliver(listOf(SearchSquareType("history")))
        assertTrue(h.model.feedback)
        assertFalse(FeatureRuntimeStage.APPLIED in h.stages)
        assertTrue(h.messages.any { it.contains("校验失败") })
    }

    @Test fun legacyNestedCallbackAndFiveArgumentStateAreStructurallySupported() {
        val vm = LegacyVM()
        val callback = vm.Callback()
        val point = requireNotNull(SearchHomeRecommendLocator.delivery(callback.javaClass))
        assertNotNull(point.refresh)
        assertEquals(5,point.cache?.constructor?.parameterCount)
        val old = LegacyPayload(listOf(SearchReferral.Guess()),"fixture",com.bilibili.search2.api.NegativeFeedback(),50,90)
        vm.recommendation.setValue(old)
        assertTrue(SearchHomeRecommendFeatureInstaller(true).clearDiscovery(callback,point))
        assertTrue(vm.recommendation.getValue()!!.values.isEmpty())
        assertNull(vm.recommendation.getValue()!!.feedback)
        assertEquals(1,old.values.size); assertNotNull(old.feedback)
    }

    @Test fun readOnlyLiveDataAliasIsNeverSelectedEvenIfHostR8MakesItsSetterPublic() {
        assertFalse(SearchHomeRecommendLocator.writableObservable(androidx.lifecycle.LiveData::class.java))
        assertTrue(SearchHomeRecommendLocator.writableObservable(androidx.lifecycle.MutableLiveData::class.java))
    }
}
