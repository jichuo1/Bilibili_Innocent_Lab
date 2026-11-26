package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 只给已审计的分享包装创建/持有的 SDK 实例关闭接收方证书比较；从不伪造 SDK 返回值。 */
internal class WeChatShareCompatFeatureInstaller(
    private val enabled: Boolean,
    private val cachedClasses: WeChatShareClasses? = null,
    private val hostVersionCode: () -> Int
) : FeatureInstaller {
    override val id = ID
    private val creationDepth = ThreadLocal<Int>()
    private val runtimeFailureReported = AtomicBoolean()
    private val cachedClassesRef = AtomicReference(cachedClasses)
    private val activeAccessRef = AtomicReference<WeChatShareCompatAccess?>(null)

    @Volatile
    var requiresAdaptationRetry: Boolean = false
        private set

    private var initialInstallRun = false
    private var retryConsumed = false
    private var factoryInstalled = false
    private val installedWrapperKeys = linkedSetOf<String>()
    private var lastExpectedWrappers = 0

    fun updateAdaptedClasses(classes: WeChatShareClasses?) {
        cachedClassesRef.set(classes)
    }

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != "tv.danmaku.bili") {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val cachedClasses = cachedClassesRef.get()
        val access = WeChatShareCompatAccess.locate(environment.classLoader, hostVersionCode(), cachedClasses)
        if (access == null) {
            if (initialInstallRun) {
                retryConsumed = true
                requiresAdaptationRetry = false
            } else {
                initialInstallRun = true
                requiresAdaptationRetry = !retryConsumed &&
                    environment.classLoader?.let(WeChatShareCompatAccess::applicable) == true
            }
            return FeatureInstallResult.Skipped("missing-verified-share-structure")
        }
        activeAccessRef.set(access)
        lastExpectedWrappers = access.expectedWrappers
        val factory = access.factory
        // 先注册工厂。若注册失败，不允许包装进入创建作用域。
        if (!factoryInstalled) {
            try {
                environment.registrar.exact("wechat.share.factory", factory.declaringClass,
                    factory.name, *factory.parameterTypes) {
                    before {
                        if ((creationDepth.get() ?: 0) <= 0) return@before
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        args[2] = false
                    }
                    after {
                        if ((creationDepth.get() ?: 0) <= 0 || hasThrowable) return@after
                        val currentAccess = activeAccessRef.get() ?: access
                        runCatching {
                            if (currentAccess.isRelaxed(result)) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }.onFailure { runtimeFailure(environment) }
                    }
                }
                factoryInstalled = true
            } catch (_: Throwable) {
                requiresAdaptationRetry = false
                return FeatureInstallResult.Skipped("registration-failed")
            }
        }
        access.wrappers.forEach { wrapper ->
            if (wrapper.key in installedWrapperKeys) return@forEach
            try {
                environment.registrar.exact("wechat.share.${wrapper.key}", wrapper.initialize.declaringClass,
                    wrapper.initialize.name, *wrapper.initialize.parameterTypes) {
                    before {
                        val receiver = instance ?: return@before
                        val currentAccess = activeAccessRef.get() ?: access
                        // 兼容在本次初始化前已持有 SDK 的包装；不保存任何宿主实例。
                        runCatching {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            if (currentAccess.relaxExisting(wrapper, receiver)) {
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                            }
                        }.onFailure { runtimeFailure(environment) }
                        creationDepth.set((creationDepth.get() ?: 0) + 1)
                        setObjectExtra(SCOPE_KEY, true)
                    }
                    after {
                        // Modern after 同样运行于原方法抛异常的路径；嵌套调用精确恢复深度。
                        if (getObjectExtra(SCOPE_KEY) != true) return@after
                        val remaining = (creationDepth.get() ?: 1) - 1
                        if (remaining <= 0) creationDepth.remove() else creationDepth.set(remaining)
                    }
                }
                installedWrapperKeys += wrapper.key
            } catch (_: Throwable) {
                environment.logError("wechat_share_register_${wrapper.key}", "[BIL] 微信分享兼容包装注册失败")
            }
        }
        if (installedWrapperKeys.isEmpty()) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("registration-failed")
        }
        val isRetryRun = initialInstallRun
        if (isRetryRun) retryConsumed = true
        initialInstallRun = true
        val installedWrappers = installedWrapperKeys.size
        val complete = installedWrappers == lastExpectedWrappers
        requiresAdaptationRetry = !isRetryRun && !retryConsumed && !complete &&
            environment.classLoader?.let(WeChatShareCompatAccess::applicable) == true
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus("wechat_share_compat_status", if (complete) "success" else "partial")
        return FeatureInstallResult.Installed(installedWrappers + 1, complete)
    }

    private fun runtimeFailure(environment: HookEnvironment) {
        if (runtimeFailureReported.compareAndSet(false, true)) {
            environment.logError("wechat_share_runtime", "[BIL] 微信分享兼容实例访问失败，保留 SDK 实际结果")
        }
    }

    companion object {
        const val ID = "wechat_share_compat"
        private const val SCOPE_KEY = "bil.wechat.share.creation"
    }
}
