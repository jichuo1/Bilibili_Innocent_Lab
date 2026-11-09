package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/**
 * Where a host page the module opened for a guest may see `BiliAccounts.isLogin()` as true: only on the current thread, only
 * while that page's own code runs. Every hook that answers `isLogin()` for a guest (the incognito account boundary and
 * [GuestHostFavoriteDialog]) reads it, since the first hook to set a result is the one that wins.
 */
internal object GuestLoginScope {
    private val depth = ThreadLocal.withInitial { 0 }

    val active: Boolean get() = depth.get() > 0

    fun enter() = depth.set(depth.get() + 1)

    fun exit() = depth.set((depth.get() - 1).coerceAtLeast(0))
}
