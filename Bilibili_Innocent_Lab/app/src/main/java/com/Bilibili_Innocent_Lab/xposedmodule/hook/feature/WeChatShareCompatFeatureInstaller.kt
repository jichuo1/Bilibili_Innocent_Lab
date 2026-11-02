package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import java.util.concurrent.atomic.AtomicBoolean

/** 只给已审计的分享包装创建/持有的 SDK 实例关闭接收方证书比较；从不伪造 SDK 返回值。 */
internal class WeChatShareCompatFeatureInstaller(
    private val enabled: Boolean,
    private val hostVersionCode: () -> Int
) : FeatureInstaller {
    override val id = ID
    private val creationDepth = ThreadLocal<Int>()
    private val runtimeFailureReported = AtomicBoolean()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val access = WeChatShareCompatAccess.locate(environment.classLoader, hostVersionCode())
            ?: return FeatureInstallResult.Skipped("missing-verified-share-structure")
        val factory = access.factory
        // 先注册工厂。若注册失败，不允许包装进入创建作用域。
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
                    runCatching {
                        if (access.isRelaxed(result)) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }.onFailure { runtimeFailure(environment) }
                }
            }
        } catch (_: Throwable) {
            return FeatureInstallResult.Skipped("registration-failed")
        }
        var installedWrappers = 0
        access.wrappers.forEach { wrapper ->
            try {
                environment.registrar.exact("wechat.share.${wrapper.key}", wrapper.initialize.declaringClass,
                    wrapper.initialize.name, *wrapper.initialize.parameterTypes) {
                    before {
                        val receiver = instance ?: return@before
                        // 兼容在本次初始化前已持有 SDK 的包装；不保存任何宿主实例。
                        runCatching {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            if (access.relaxExisting(wrapper, receiver)) {
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
                installedWrappers++
            } catch (_: Throwable) {
                environment.logError("wechat_share_register_${wrapper.key}", "[BIL] 微信分享兼容包装注册失败")
            }
        }
        if (installedWrappers == 0) return FeatureInstallResult.Skipped("registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val complete = installedWrappers == WeChatShareCompatAccess.EXPECTED_WRAPPERS
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
