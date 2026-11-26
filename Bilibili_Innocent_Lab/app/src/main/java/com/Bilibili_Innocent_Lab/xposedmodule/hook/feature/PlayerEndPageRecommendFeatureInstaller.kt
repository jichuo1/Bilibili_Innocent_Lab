package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 清理结束页回复及合并后的播放器列表；getter 独立后备。任一路解析/注册失败都不会撤掉另一条防线。 */
internal class PlayerEndPageRecommendFeatureInstaller(
    private val enabled: Boolean,
    private val cachedPoints: () -> HookPoint? = { null },
    private val kmpGrpcWireEngine: HookPoint? = null,
    private val kmpGrpcWireEngineProvider: () -> HookPoint? = { kmpGrpcWireEngine },
    private val resolve: (ClassLoader?) -> PlayerEndPageRecommendLocator.Access? = PlayerEndPageRecommendLocator::resolve
) : FeatureInstaller {
    override val id = ID
    private val registered = linkedSetOf<Method>()
    private var kmpRegistered = false
    private var initialRun = false
    private var retryConsumed = false
    @Volatile var requiresAdaptationRetry = false
        private set
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        fun skipped(reason: String): FeatureInstallResult.Skipped {
            requiresAdaptationRetry = false
            environment.reportStatus(STATUS, reason)
            val result = FeatureInstallResult.Skipped(reason)
            environment.reportCapability(ID, result)
            return result
        }
        if (!enabled) return skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return skipped("non-main-process")
        val direct = resolve(environment.classLoader) ?: return skipped("missing-host-structure")
        val access = if (direct.mergedList != null) direct else direct.copy(
            mergedList = PlayerEndPageRecommendLocator.cachedMerger(environment.classLoader, cachedPoints()))
        val loader = environment.classLoader
        val policy = PlayerEndPageRecommendPolicy(access)
        val errorLogged = AtomicBoolean(false)
        var installed = 0
        fun observed() = environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        fun applied(count: Int) = environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, count)
        fun transform(reply: Any): Any {
            if (!access.reply.isInstance(reply)) return reply
            observed()
            return runCatching { policy.clean(reply) }.fold(
                onSuccess = { cleaned -> if (cleaned != null) { applied(cleaned.removed); cleaned.reply } else reply },
                onFailure = {
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                    if (errorLogged.compareAndSet(false, true)) environment.logError(
                        "player_end_page_recommend_copy", "[BIL] 结束页推荐清理失败，保留原响应")
                    reply
                })
        }
        fun register(path: String, method: Method?, block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit) {
            if (method == null) return
            if (method in registered) { installed++; return }
            runCatching {
                environment.registrar.exact("$ID.$path", method.declaringClass, method.name, *method.parameterTypes, block = block)
                registered += method
                installed++
            }.onFailure { environment.logError("${ID}_$path", "[BIL] 结束页推荐入口注册失败") }
        }
        if (access.canCopy) {
            register("sync", access.sync) {
                after { if (!hasThrowable) result?.let { original -> result = transform(original) } }
            }
            register("async", access.async) {
                before {
                    val original = argOrNull(1) ?: return@before
                    val proxy = MossResponseHandlerProxy.wrapTransform(access.handler!!, original, ::transform) ?: return@before
                    args[1] = proxy
                }
            }
            if (!kmpRegistered && loader != null) {
                val wireSurface = KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngineProvider())
                val wireOk = wireSurface != null && KmpGrpcWireChannel.install(
                    environment = environment,
                    surface = wireSurface,
                    javaMossClassName = PlayerEndPageRecommendLocator.MOSS,
                    rpc = "viewEndPage",
                    javaReplyClass = access.reply,
                    what = "结束页推荐",
                    logKey = "player_end_page_kmoss",
                    transformJava = ::transform
                )
                if (wireOk) {
                    kmpRegistered = true
                } else {
                    val kotlinName = KotlinMossBridgeMembers.kotlinMossClassName(PlayerEndPageRecommendLocator.MOSS)
                    if (kotlinName != null && KavaMemberLookup.classOrNull(loader, kotlinName) != null) {
                        val bridge = KotlinMossChannel.prepare(environment, loader, "结束页推荐", "player_end_page_kmoss")
                        if (bridge != null && KotlinMossChannel.install(
                                environment = environment,
                                loader = loader,
                                members = bridge,
                                javaMossClassName = PlayerEndPageRecommendLocator.MOSS,
                                rpc = "viewEndPage",
                                javaReplyClass = access.reply,
                                hookId = "$ID.kmoss.viewEndPage",
                                what = "结束页推荐",
                                logKey = "player_end_page_kmoss",
                                transformJava = ::transform
                            )
                        ) {
                            kmpRegistered = true
                        }
                    }
                }
            }
        }
        register("merged-list", access.mergedList) {
            after {
                if (hasThrowable) return@after
                val original = result as? List<*> ?: return@after
                observed()
                if (original.isNotEmpty()) {
                    result = emptyList<Any>()
                    if ((result as? List<*>)?.isEmpty() == true) applied(original.size)
                }
            }
        }
        register("list", access.list) {
            after {
                if (hasThrowable || policy.transformingResponse) return@after
                val original = result as? List<*> ?: return@after
                observed()
                if (original.isNotEmpty()) {
                    result = emptyList<Any>()
                    if ((result as? List<*>)?.isEmpty() == true) applied(original.size)
                }
            }
        }
        register("count", access.count) {
            after {
                if (hasThrowable || policy.transformingResponse) return@after
                val original = result as? Int ?: return@after
                observed()
                if (original > 0) { result = 0; if (result == 0) applied(original) }
            }
        }
        val kmpRetryNeeded = access.canCopy && !kmpRegistered && loader != null &&
            KmpGrpcWireAdapter.applicable(loader)
        if (initialRun) {
            retryConsumed = true
            requiresAdaptationRetry = false
        } else {
            initialRun = true
            requiresAdaptationRetry = !retryConsumed && (access.mergedList == null || kmpRetryNeeded)
        }
        if (installed == 0 && !kmpRegistered) return skipped("no-safe-hook-point")
        val reportedInstalled = if (installed > 0) installed else 1
        val expected = PlayerEndPageRecommendLocator.EXPECTED_PATHS
        environment.reportCapabilityCoverage(ID, true, reportedInstalled, expected)
        environment.reportStatus(STATUS, if (reportedInstalled == expected) "success" else "partial:$reportedInstalled/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(reportedInstalled, complete = reportedInstalled == expected)
    }

    companion object {
        const val ID = "player_end_page_recommend"
        private const val STATUS = "player_end_page_recommend_status"
    }
}
