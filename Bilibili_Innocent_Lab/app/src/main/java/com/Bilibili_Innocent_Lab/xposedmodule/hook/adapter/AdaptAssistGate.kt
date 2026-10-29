package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery

/**
 * 三类历史 DEX 兜底（更新检查、默认画质、评论回复脉络 mapper）的需求与历史门控。
 *
 * 这三类原先只要「直接定位没找到」就会进入后台查询，与对应功能是否开启无关；而每个代码归档的 DexKit 桥建一次
 * 要整份解析宿主 DEX。现在只有同时满足下列条件才入队：
 * 1. 对应功能已开启（脉络、更新拦截、默认画质）；
 * 2. 直接定位没有满足；
 * 3. 缓存里没有可复用的点；
 * 4. 历史上没有「已完成且确认缺失」的结论。短暂失败（原生不可用、查询失败、上次未完成……）仍可重试。
 *
 * 跳过不是缺失：未请求的家族记为 NOT_APPLICABLE + `…:feature-disabled`，保留此前 FOUND / 确认缺失的诊断；
 * 缓存里已有的点原样带入新结果，用户之后开启功能时若缓存没有点则触发一次补查。
 */
internal object AdaptAssistGate {
    /** DEX 兜底诊断 id 前缀；`dex.assist` 保持为 block-update 的历史 id，不改名以兼容既有缓存。 */
    const val ID_PREFIX = "dex.assist"
    const val BLOCK_UPDATE_ID = ID_PREFIX
    const val COMMENT_TOPOLOGY_ID = "$ID_PREFIX.comment_topology"
    const val PLAYER_QUALITY_ID = "$ID_PREFIX.player_quality"

    enum class Family(val diagnosticId: String, val prefix: String, val query: DexAssistQuery) {
        BLOCK_UPDATE(BLOCK_UPDATE_ID, "block-update:", DexAssistQuery.BLOCK_UPDATE),
        PLAYER_QUALITY(PLAYER_QUALITY_ID, "player-quality:", DexAssistQuery.PLAYER_DEFAULT_QUALITY),
        COMMENT_TOPOLOGY(COMMENT_TOPOLOGY_ID, "comment-topology:", DexAssistQuery.COMMENT_REPLY_MAPPER)
    }

    /** 缓存里一个家族的历史结论。 */
    enum class Attempt {
        /** 从未请求（含功能关闭、直接定位已满足、快速定位占位）。 */
        NOT_REQUESTED,
        /** 查询找到并写入了点。 */
        FOUND,
        /** 查询完成但结构缺失或不唯一：同一宿主上重试不会有新信息。 */
        CONCLUSIVE_MISSING,
        /** 没有拿到有效结论（原生不可用、查询失败、上次中断、无 DEX 源……），允许重试。 */
        RETRYABLE
    }

    private val retryableReasons = setOf(
        "no_code_path", "native_unavailable", "query_failed", "too_many_archives",
        "previous_attempt_unfinished", "no-dex-source"
    )

    fun attempt(diagnostics: List<AdaptDiagnostic>?, family: Family): Attempt {
        val diagnostic = diagnostics?.lastOrNull { it.id == family.diagnosticId } ?: return Attempt.NOT_REQUESTED
        return when (diagnostic.state) {
            AdaptState.FOUND -> Attempt.FOUND
            AdaptState.MISSING -> {
                val reason = diagnostic.detail.removePrefix(family.prefix).substringBefore(':')
                if (reason in retryableReasons) Attempt.RETRYABLE else Attempt.CONCLUSIVE_MISSING
            }
            else -> Attempt.NOT_REQUESTED
        }
    }

    /**
     * 是否需要（补）查。[cachedPresent] 为缓存中已有可复用的点（含此前兜底找到的点）。
     * [directFound] 最后才求值：功能关闭、缓存已有点或历史已确认缺失时，不做直接定位反射。
     */
    inline fun needsQuery(enabled: Boolean, cachedPresent: Boolean, attempt: Attempt, directFound: () -> Boolean): Boolean =
        enabled && !cachedPresent && attempt != Attempt.CONCLUSIVE_MISSING && !directFound()

    /**
     * 缓存快路径的补扫判定：比 [needsQuery] 更窄，只对「从未请求过」的家族触发。
     * 历史上已经查过但没有拿到有效结论（短暂失败）时，不在每次冷启动重新适配——它只会在本来就要适配的那一轮里重试；
     * 否则一个持久的环境问题（例如原生库不可用）会让每次启动都跑一遍适配并弹出提示。
     */
    inline fun needsSupplementalScan(enabled: Boolean, cachedPresent: Boolean, attempt: Attempt, directFound: () -> Boolean): Boolean =
        enabled && !cachedPresent && attempt == Attempt.NOT_REQUESTED && !directFound()

    /** 跳过查询时写入的诊断：保留已有的 FOUND / 确认缺失结论，否则记为未请求。 */
    fun skippedDiagnostic(
        family: Family,
        enabled: Boolean,
        previous: AdaptDiagnostic?,
        attempt: Attempt
    ): AdaptDiagnostic = when {
        previous != null && (attempt == Attempt.FOUND || attempt == Attempt.CONCLUSIVE_MISSING) -> previous
        else -> AdaptDiagnostic(family.diagnosticId, AdaptState.NOT_APPLICABLE,
            family.prefix + if (enabled) "not-required" else "feature-disabled")
    }
}
