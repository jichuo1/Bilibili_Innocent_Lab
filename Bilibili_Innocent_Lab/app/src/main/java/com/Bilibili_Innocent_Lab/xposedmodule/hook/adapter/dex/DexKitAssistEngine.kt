package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * DexKit 后台实现：只在常规 KavaRef 定位缺失时创建桥，并在每个代码 APK 查询后立即关闭。
 */
internal object DexKitAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_MATCHES = 32
    private const val BLOCK_UPDATE_RETURN_TYPE =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"
    private const val CONTEXT_TYPE = "android.content.Context"
    private const val COMMENT_ITEM_TYPE = "com.bilibili.app.comment3.data.model.CommentItem"

    /**
     * 评论模块根包在 8.63.0–9.10.0 之间从未混淆，用它收窄查询范围。
     *
     * 实测同一条件下的命中量：9.8.0/9.9.0/9.10.0 各 5 个、8.90.2 为 31 个，均在
     * [MAX_MATCHES] 之内；不加包约束时 8.90.2 会达到 59 个而直接触发命中歧义。
     */
    private const val COMMENT3_PACKAGE = "com.bilibili.app.comment3"
    private const val COMMENT_MAPPER_MIN_PARAMS = 1
    private const val COMMENT_MAPPER_MAX_PARAMS = 8

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

                        // 首参类型无法在这里表达（paramTypes 会同时锁死参数个数，而 mapper 的
                        // 参数个数在 2-5 之间漂移），因此只按返回类型 + static + 参数区间收窄，
                        // 首参是否为 ReplyInfo 交给 VersionAdapter 用宿主 ClassLoader 复核。
                        DexAssistQuery.COMMENT_REPLY_MAPPER -> bridge.findMethod {
                            searchPackages(COMMENT3_PACKAGE)
                            matcher {
                                returnType = COMMENT_ITEM_TYPE
                                modifiers(Modifier.STATIC, MatchType.Contains)
                                paramCount(COMMENT_MAPPER_MIN_PARAMS, COMMENT_MAPPER_MAX_PARAMS)
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
