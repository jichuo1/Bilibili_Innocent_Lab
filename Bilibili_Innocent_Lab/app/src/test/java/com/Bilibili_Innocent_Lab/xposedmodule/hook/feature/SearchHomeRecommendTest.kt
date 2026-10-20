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
        loader: ClassLoader = defaultLocateLoader()) {
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

    @Test fun backgroundRetryKeepsEstablishedHooksAndCoverageCounts() {
        val registrar = PlayerPortTestRegistrar()
        val env = Harness(registrar = registrar).env
        val installer = SearchHomeRecommendFeatureInstaller(true)
        assertEquals(FeatureInstallResult.Installed(4), installer.install(env))
        val count = registrar.hooks.size
        assertEquals(FeatureInstallResult.Installed(4), installer.install(env))
        assertEquals(count, registrar.hooks.size)
        assertFalse(installer.requiresAdaptationRetry)
    }

    @Test fun fullLocatorFindsTypedDeliveryRefreshAndStateWithoutHardcodedMemberNames() {
        val point = requireNotNull(SearchHomeRecommendLocator.locate(defaultLocateLoader()))
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
        // 平铺列出全部屏蔽项，不把一个屏蔽加载器当作另一个的 parent：那样只有双参 loadClass
        // 会被走到，容易被误认为屏蔽已经生效。
        val loader = locateLoaderBlocking(
            i::class.java.name,
            "com.bilibili.search2.discover.y",
            "com.bilibili.search2.discover.z"
        )
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

    @Test fun anonymousInnerCallbackIsFoundOnlyByBoundedNameEnumeration() {
        // 前提：JVM 与 Android 一致，declaredClasses 拿不到匿名类里的投递宿主。
        // 这条断言若失败，说明运行环境变了，下面的结论不再成立。
        assertTrue(y::class.java.declaredClasses.none { SearchHomeRecommendLocator.delivery(it) != null })

        // 真机 8.84.0–8.96.0 的投递宿主是 `discover.r$a`，JVM 上同形为 `discover.y$1`：
        // 只有按 Outer$Suffix 有界枚举才能发现，declaredClasses 一律看不到。
        val point = requireNotNull(
            SearchHomeRecommendLocator.locate(
                locateLoaderBlocking("com.bilibili.search2.discover.q", "com.bilibili.search2.discover.z")
            )
        )
        assertNotNull(point.delivery)
        assertEquals(1, point.deliveryCandidateCount)
    }

    @Test fun namedMemberCallbackIsCountedOnceThoughTwoPathsReachIt() {
        // `z$a` 既是命名成员类（declaredClasses 可见），也落在 a-z 后缀枚举范围内。
        // 候选集未去重时同一个 Class 会出现两次，被 singleOrNull 误判成候选歧义。
        assertTrue(z::class.java.declaredClasses.any { it.name == "com.bilibili.search2.discover.z\$a" })

        val point = requireNotNull(
            SearchHomeRecommendLocator.locate(
                locateLoaderBlocking("com.bilibili.search2.discover.q", "com.bilibili.search2.discover.y")
            )
        )
        assertNotNull(point.delivery)
        assertEquals(1, point.deliveryCandidateCount)
    }

    @Test fun multipleCallbacksAreRejectedAndReportedAsAmbiguous() {
        // 候选不唯一时必须放弃安装而不是任选一个，且诊断要能与「宿主没有该结构」区分开。
        // 同时屏蔽状态点 `i`，让本次安装只剩投递这一条路径，断言才只反映候选歧义。
        val loader = locateLoaderBlocking(
            "com.bilibili.search2.discover.q",
            "com.bilibili.search2.main.data.i"
        )
        val point = requireNotNull(SearchHomeRecommendLocator.locate(loader))
        assertNull(point.delivery)
        assertEquals(2, point.deliveryCandidateCount)

        assertEquals(
            FeatureSkipReason.AMBIGUOUS_HOST_STRUCTURE,
            FeatureSkipReason.fromRaw("ambiguous-search-home-boundary")
        )
        assertEquals(
            FeatureInstallResult.Skipped("ambiguous-search-home-boundary"),
            Harness(loader = loader).result
        )
    }
}

/**
 * 候选集隔离：`y` / `z` 只服务于上面的定点用例。
 *
 * `locate()` 扫描的是整个 `com.bilibili.search2.discover` 包，若默认放行这两个 fixture，
 * 它们会与既有的顶级投递宿主 `q` 同时满足判据，让所有既有用例都撞上候选歧义。
 */
private fun locateLoaderBlocking(vararg blocked: String): ClassLoader =
    object : ClassLoader(SearchHomeRecommendTest::class.java.classLoader) {
        // 只重写双参版本即可覆盖两条路径：ClassLoader.loadClass(String) 的实现就是转调本方法，
        // 而把本加载器当作 parent 使用时走的同样是本方法。若只重写单参，被当作 parent 时屏蔽会失效。
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name in blocked) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

private fun defaultLocateLoader(): ClassLoader = locateLoaderBlocking(
    "com.bilibili.search2.discover.y",
    "com.bilibili.search2.discover.z"
)
