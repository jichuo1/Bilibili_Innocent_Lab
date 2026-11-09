package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState

internal enum class RestartRoute { ROOT, SHIZUKU, MANUAL }

/** 加载/配置通道不参与特权优先级：NPatch、LSPatch 与 Root 可以共存。 */
internal object RestartRoutePolicy {
    fun preferred(root: RootRestartAccess.Snapshot?, shizuku: ShizukuAvailability.Snapshot?): RestartRoute? {
        if (root == null) return null
        if (root.preferred) return RestartRoute.ROOT
        if (shizuku == null) return null
        if (shizuku.status in setOf(ShizukuAvailability.Status.READY, ShizukuAvailability.Status.UNAUTHORIZED)) return RestartRoute.SHIZUKU
        return RestartRoute.MANUAL
    }

    fun usesNoRootConfig(desiredEnabled: Boolean, displayState: NoRootDisplayState, nPatchCarrier: Boolean = false): Boolean =
        desiredEnabled || nPatchCarrier || displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED ||
            displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
}

/** 有界只读探测会话：单飞、短缓存、超时仍为未知；拒绝晚到和上一轮的结果。 */
internal class RestartProbeState {
    data class Snapshot(val root: RootRestartAccess.Snapshot? = null,
                        val shizuku: ShizukuAvailability.Snapshot? = null,
                        val checkedAt: Long? = null) {
        val checking: Boolean get() = root == null || shizuku == null
        val preferred: RestartRoute? get() = RestartRoutePolicy.preferred(root, shizuku)
    }
    var snapshot = Snapshot()
        private set
    private var serial = 0L
    private var pending: Long? = null

    fun invalidate() { pending = null; snapshot = Snapshot(); serial++ }

    fun begin(now: Long, force: Boolean = false): Long? {
        if (pending != null) return null
        if (!force && snapshot.checkedAt?.let { now - it in 0 until 5_000 } == true) return null
        pending = ++serial
        snapshot = Snapshot()
        return pending
    }
    fun root(token: Long, value: RootRestartAccess.Snapshot, now: Long): Boolean {
        if (pending != token || snapshot.root != null) return false
        snapshot = snapshot.copy(root = value)
        settle(now)
        return true
    }
    fun shizuku(token: Long, value: ShizukuAvailability.Snapshot, now: Long): Boolean {
        if (pending != token || snapshot.shizuku != null) return false
        snapshot = snapshot.copy(shizuku = value)
        settle(now)
        return true
    }
    fun timeout(token: Long, now: Long): Boolean {
        if (pending != token) return false
        snapshot = snapshot.copy(root = snapshot.root ?: RootRestartAccess.Snapshot(RootRestartAccess.Status.UNKNOWN),
            shizuku = snapshot.shizuku ?: ShizukuAvailability.Snapshot(ShizukuAvailability.Status.DISCONNECTED))
        settle(now)
        return true
    }
    private fun settle(now: Long) {
        if (!snapshot.checking) { pending = null; snapshot = snapshot.copy(checkedAt = now) }
    }
}
