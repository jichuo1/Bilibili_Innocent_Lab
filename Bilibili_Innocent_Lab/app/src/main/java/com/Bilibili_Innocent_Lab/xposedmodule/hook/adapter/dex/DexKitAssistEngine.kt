package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * DexKit 后台实现：只在常规 KavaRef 定位缺失时创建桥，并在每个代码 APK 查询后立即关闭。
 */
internal object DexKitAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_MATCHES = 32
    private const val BLOCK_UPDATE_RETURN_TYPE =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"
    private const val CONTEXT_TYPE = "android.content.Context"

    @Volatile
    private var nativeState = NativeState.NOT_TRIED

    override fun resolve(request: DexAssistRequest): DexAssistResult {
        val codePaths = request.codePaths.distinct()
        if (codePaths.isEmpty()) {
            return DexAssistResult.Unavailable(DexAssistResult.Reason.NO_CODE_PATH)
        }
        if (codePaths.size > MAX_CODE_ARCHIVES) {
            return DexAssistResult.Unavailable(DexAssistResult.Reason.TOO_MANY_ARCHIVES)
        }
        if (!ensureNativeLoaded()) {
            return DexAssistResult.Unavailable(DexAssistResult.Reason.NATIVE_UNAVAILABLE)
        }

        return runCatching {
            val methods = mutableListOf<Method>()
            codePaths.forEach { path ->
                DexKitBridge.create(path).use { bridge ->
                    val matches = when (request.query) {
                        DexAssistQuery.BLOCK_UPDATE -> bridge.findMethod {
                            matcher {
                                returnType = BLOCK_UPDATE_RETURN_TYPE
                                paramTypes(CONTEXT_TYPE)
                            }
                        }
                    }
                    if (matches.size + methods.size > MAX_MATCHES) {
                        return DexAssistResult.Unavailable(
                            DexAssistResult.Reason.TOO_MANY_MATCHES
                        )
                    }
                    matches.mapNotNullTo(methods) { data ->
                        runCatching { data.getMethodInstance(request.classLoader) }.getOrNull()
                    }
                }
            }
            methods.distinctBy(Method::toGenericString).takeIf(List<Method>::isNotEmpty)
                ?.let(DexAssistResult::Candidates)
                ?: DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }.getOrElse {
            DexAssistResult.Unavailable(DexAssistResult.Reason.QUERY_FAILED)
        }
    }

    private fun ensureNativeLoaded(): Boolean {
        if (nativeState != NativeState.NOT_TRIED) return nativeState == NativeState.AVAILABLE
        synchronized(this) {
            if (nativeState == NativeState.NOT_TRIED) {
                nativeState = if (runCatching { System.loadLibrary("dexkit") }.isSuccess) {
                    NativeState.AVAILABLE
                } else {
                    NativeState.UNAVAILABLE
                }
            }
        }
        return nativeState == NativeState.AVAILABLE
    }

    private enum class NativeState {
        NOT_TRIED,
        AVAILABLE,
        UNAVAILABLE
    }
}
