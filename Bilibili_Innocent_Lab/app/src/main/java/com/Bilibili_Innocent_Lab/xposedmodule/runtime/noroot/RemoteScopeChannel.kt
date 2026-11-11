package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** Manager 对 `requestScope` 回调的观察结果；只作信息，成功与否以读回的作用域为准。 */
internal enum class ScopeCallbackOutcome { APPROVED, FAILED, NONE }

/** 作用域申请依赖的最小传输面；生产实现是 Binder，测试实现是内存模型。 */
internal interface RemoteScopeChannel {
    /** 当前模块在 Manager 里记录的作用域包名。 */
    fun scope(): List<String>

    /** 发出申请并在有界时间内等待回调；回调没到返回 [ScopeCallbackOutcome.NONE]。 */
    fun requestScope(packages: List<String>): ScopeCallbackOutcome
}

internal sealed interface ScopeRequestResult {
    data object Added : ScopeRequestResult
    data object AlreadyPresent : ScopeRequestResult
    data object ManagerMissing : ScopeRequestResult
    data object ModuleNotRegistered : ScopeRequestResult
    data object ConnectionTimeout : ScopeRequestResult
    data class Failure(val errorCode: String) : ScopeRequestResult
}

/**
 * 加入作用域：先读、必要时申请、再读回确认。
 *
 * NPatch 的 `requestScope` 不弹确认，直接把"模块 × 目标"写进 Manager 数据库；回调只说明
 * Manager 自己怎么看，不能证明写入结果，所以最终以读回的 [RemoteScopeChannel.scope] 为准。
 * 申请前意图已被取代时返回 null，不写入。
 */
internal fun requestScopeFlow(
    channel: RemoteScopeChannel,
    packages: List<String>,
    stillCurrent: () -> Boolean
): ScopeRequestResult? {
    val wanted = packages.distinct()
    require(wanted.isNotEmpty()) { "at least one package is required" }
    if (!stillCurrent()) return null
    if (channel.scope().containsAll(wanted)) return ScopeRequestResult.AlreadyPresent
    if (!stillCurrent()) return null
    val outcome = channel.requestScope(wanted)
    return when {
        channel.scope().containsAll(wanted) -> ScopeRequestResult.Added
        outcome == ScopeCallbackOutcome.FAILED -> ScopeRequestResult.Failure("scope_request_refused")
        else -> ScopeRequestResult.Failure("scope_not_applied")
    }
}
