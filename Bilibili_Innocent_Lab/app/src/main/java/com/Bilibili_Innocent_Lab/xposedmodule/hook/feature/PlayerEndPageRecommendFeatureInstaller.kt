package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 同步/异步回复副本为主；getter 独立后备。任一路解析/注册失败都不会撤掉另一条防线。 */
internal class PlayerEndPageRecommendFeatureInstaller(
    private val enabled: Boolean,
    private val resolve: (ClassLoader?) -> PlayerEndPageRecommendLocator.Access? = PlayerEndPageRecommendLocator::resolve
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        fun skipped(reason: String): FeatureInstallResult.Skipped {
            environment.reportStatus(STATUS, reason)
            val result = FeatureInstallResult.Skipped(reason)
            environment.reportCapability(ID, result)
            return result
        }
        if (!enabled) return skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return skipped("non-main-process")
        val access = resolve(environment.classLoader) ?: return skipped("missing-host-structure")
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
            runCatching {
                environment.registrar.exact("$ID.$path", method.declaringClass, method.name, *method.parameterTypes, block = block)
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
        if (installed == 0) return skipped("no-safe-hook-point")
        val expected = PlayerEndPageRecommendLocator.EXPECTED_PATHS
        environment.reportCapabilityCoverage(ID, true, installed, expected)
        environment.reportStatus(STATUS, if (installed == expected) "success" else "partial:$installed/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    companion object {
        const val ID = "player_end_page_recommend"
        private const val STATUS = "player_end_page_recommend_status"
    }
}
