package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam

/** Search landing-page sections only; historical provider, search results and default words stay independent. */
internal class SearchHomeRecommendFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val points = environment.classLoader?.let(SearchHomeRecommendLocator::locate)
            ?: return FeatureInstallResult.Skipped("missing-search-home-model")
        var installed = 0
        val expected = 3 + if (points.stateExpected || points.state != null) 1 else 0
        fun attempt(name: String, register: () -> Boolean) {
            if (runCatching(register).getOrElse {
                environment.logError("search_home_register_$name", "[BIL] 搜索首页推荐边界安装失败($name): $it")
                false
            }) installed++
        }
        points.delivery?.let { delivery ->
            attempt("delivery") {
                val method = delivery.method
                environment.registrar.exact("search.home.sections", method.declaringClass, method.name, *method.parameterTypes) {
                    filterCallbacks(environment, points, { param ->
                        param.instance?.let(delivery.owner::get)?.let(delivery.sections::get)
                    }, { param -> clearDiscovery(param.instance, delivery) })
                }
                if (delivery.cache != null) installed++
                true
            }
            delivery.refresh?.let { method ->
                attempt("refresh") {
                    environment.registrar.exact("search.home.refresh", method.declaringClass, method.name, *method.parameterTypes) {
                        before {
                            // This method only publishes discovery words to its page ViewModel.
                            // It is not a network onSuccess/onError/onCompleted callback or history updater.
                            if (argOrNull(0) !is List<*>) return@before
                            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                            result = null
                            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                            runCatching { clearDiscovery(instance, delivery) }
                                .onFailure { environment.logError("search_home_refresh_cache", "[BIL] 搜索发现旧状态清理失败: $it") }
                        }
                    }
                    true
                }
            }
        }
        points.state?.let { state ->
            attempt("state") {
                environment.registrar.constructor("search.home.state", state.constructor) {
                    filterCallbacks(environment, points, readback = { param -> param.instance?.let(state.sections::get) })
                }
                true
            }
        }
        environment.reportStatus("search_home_recommend_status", if (installed == expected) "success" else "partial:$installed/$expected")
        return if (installed == 0) FeatureInstallResult.Skipped("missing-search-home-boundary")
            else FeatureInstallResult.Installed(installed, installed == expected)
    }

    private fun ModernMemberHookCreator.filterCallbacks(
        env: HookEnvironment, points: SearchHomeRecommendLocator.Points, readback: (ModernHookParam) -> Any?,
        cleanup: (ModernHookParam) -> Boolean = { false }
    ) {
        before {
            val source = argOrNull(0) as? List<*> ?: return@before
            runCatching {
                val filtered = SearchHomeRecommendPolicy.filter(source, unsafeEmpty = {
                    env.logInfo("search_home_missing_anchor", "[BIL] 搜索首页分区缺少可保留锚点，保留原响应避免误隐藏历史")
                }) { entry ->
                    if (points.square.isInstance(entry)) points.type.invoke(entry) as? String else null
                }
                env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                if (filtered !== source) {
                    args[0] = filtered
                    setObjectExtra(FILTERED, filtered)
                }
            }.onFailure { env.logError("search_home_filter", "[BIL] 搜索首页分区读取失败，保留原数据: $it") }
        }
        after {
            if (hasThrowable) return@after
            val supplied = argOrNull(0) as? List<*> ?: return@after
            runCatching {
                if (readback(this) === supplied) {
                    val cleanSections = supplied.none { item ->
                        item != null && points.square.isInstance(item) &&
                            SearchHomeRecommendPolicy.blocked(points.type.invoke(item) as? String)
                    }
                    val cleared = cleanSections && cleanup(this)
                    if (getObjectExtra(FILTERED) != null || cleared) env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                } else env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.ERROR)
            }.onFailure { env.logError("search_home_readback", "[BIL] 搜索首页分区写后校验失败: $it") }
        }
    }

    internal fun clearDiscovery(instance: Any?, delivery: SearchHomeRecommendLocator.Delivery): Boolean {
        val cache = delivery.cache ?: return false
        val owner = instance?.let(delivery.owner::get) ?: return false
        val observable = cache.field.get(owner) ?: return false
        val old = cache.read.invoke(observable) ?: return false
        if ((cache.values.get(old) as? List<*>).isNullOrEmpty() && cache.feedback.get(old) == null) return false
        val args = arrayOfNulls<Any>(cache.constructor.parameterCount)
        args[0] = emptyList<Any>(); args[1] = ""
        for (i in 3 until args.size) args[i] = 0L
        val empty = cache.constructor.newInstance(*args)
        cache.write.invoke(observable, empty)
        val current = cache.read.invoke(observable) ?: error("Missing discovery state after publication")
        check((cache.values.get(current) as? List<*>).isNullOrEmpty() && cache.feedback.get(current) == null)
        return true
    }

    companion object {
        const val ID = "search_home_recommend_hidden"
        const val CAPABILITY = "search_home_recommend_hidden"
        private const val FILTERED = "search_home_filtered_sections"
    }
}
