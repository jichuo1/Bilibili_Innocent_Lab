package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 仅同步默认构造期间覆盖自动偏好读取；不写宿主偏好、不持有播放器实例。 */
internal class NormalQualityFeatureHooks(private val target: () -> Int?) {
    private val depth = ThreadLocal<Int>()
    private val registered = hashSetOf<String>()
    private val ready = AtomicBoolean(false)
    val complete: Boolean get() = ready.get()
    val hookCount: Int get() = registered.size

    fun install(environment: HookEnvironment, roles: NormalQualityLocator.Roles) {
        fun evidence(stage: FeatureRuntimeStage) = runCatching {
            environment.reportRuntimeEvidence(PlayerQualityFeatureInstaller.ID, stage)
        }
        fun register(id: String, method: Method, block: ModernMemberHookCreator.() -> Unit) {
            if (id in registered) return
            environment.registrar.exact(id, method.declaringClass, method.name, *method.parameterTypes, block = block)
            registered += id
        }
        try {
            register("player.quality.normal.auto", roles.preferenceReader) {
                after {
                    if (!hasThrowable && complete && (depth.get() ?: 0) > 0 &&
                        args.firstOrNull() == NormalQualityLocator.AUTO_KEY && result == true && target() != null
                    ) {
                        evidence(FeatureRuntimeStage.OBSERVED)
                        // 原 h() 仍继续执行青少年/课程限制的 OR 分支；不直接强制其最终结果。
                        result = false
                        evidence(FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            roles.scopes.forEachIndexed { index, method ->
                register("player.quality.normal.scope.$index", method) {
                    before {
                        if (complete && target() != null) {
                            val previous = depth.get() ?: 0
                            setObjectExtra(FRAME, previous)
                            depth.set(previous + 1)
                        }
                    }
                    after {
                        // 原方法抛异常也必须退出；边界均同步返回，禁止跨协程保存作用域。
                        (getObjectExtra(FRAME) as? Int)?.let { previous ->
                            if (previous == 0) depth.remove() else depth.set(previous)
                        }
                    }
                }
            }
            ready.set(true)
        } catch (_: Throwable) {
            environment.logError("player_quality_normal_registration", "[BIL] 普通默认画质自动策略边界注册不完整")
        }
    }

    private companion object {
        const val FRAME = "player.quality.normal.frame"
    }
}
