package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import java.lang.reflect.Method

/** 后台 DEX 辅助查询的最小输入；查询目录由 [DexAssistQuery] 统一管理。 */
internal data class DexAssistRequest(
    val query: DexAssistQuery,
    val codePaths: List<String>,
    val classLoader: ClassLoader
)

/** 只有结构特征足够强、且能在运行时二次验真的点才能加入该目录。 */
internal enum class DexAssistQuery {
    BLOCK_UPDATE
}

internal sealed interface DexAssistResult {
    data class Candidates(val methods: List<Method>) : DexAssistResult
    data class Unavailable(val reason: Reason) : DexAssistResult

    enum class Reason {
        NO_CODE_PATH,
        NATIVE_UNAVAILABLE,
        NO_MATCH,
        QUERY_FAILED,
        TOO_MANY_MATCHES
    }
}

/** DexKit 被限制在此接口后方，VersionAdapter 与 Hook 注册层不直接持有桥对象。 */
internal fun interface DexAssistEngine {
    fun resolve(request: DexAssistRequest): DexAssistResult
}

/**
 * 从候选实现中选择唯一叶子方法。
 *
 * 桥接接口与真实实现同时命中时，优先选择未出现在 owner 接口声明中的叶子方法；若仍有
 * 多个 owner 或多个叶子，返回 null，禁止猜测式安装 Hook。
 */
internal object DexAssistCandidateSelector {
    fun selectUniqueLeaf(methods: Collection<Method>): Method? {
        val selectedByOwner = methods
            .distinctBy(Method::toGenericString)
            .groupBy(Method::getDeclaringClass)
            .values
            .mapNotNull { ownerMethods ->
                val owner = ownerMethods.firstOrNull()?.declaringClass ?: return@mapNotNull null
                val interfaceSignatures = owner.interfaces.flatMap { contract ->
                    contract.declaredMethods.map { method ->
                        method.name to method.parameterTypes.toList()
                    }
                }.toSet()
                val leaves = ownerMethods.filter { method ->
                    (method.name to method.parameterTypes.toList()) !in interfaceSignatures
                }
                leaves.singleOrNull() ?: ownerMethods.singleOrNull()
            }
        return selectedByOwner.singleOrNull()
    }
}
