package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment

/**
 * Keep the host's own 我的 page usable for guests on builds that draw it with the Compose mine page (V2).
 *
 * For a logged-out account the host lays `HomeUserCenterForceLoginFragment` over the finished mine page and sends the
 * user to the login screen. The page underneath is complete: its library rows (offline, history, favorites, watch later)
 * are ordinary routes, and the history screen is already served locally by the KMP boundary. The overlay is switched by
 * one method on `HomeUserCenterFragmentV2`, `(isLogin, fromPageSelected)`, which only creates or removes that fragment.
 * Skipping it for a guest leaves the page as the host built it, with nothing faked.
 *
 * The method name is obfuscated, so it is found by shape: the SINGLE non-static `(boolean, boolean):void` method on the
 * V2 fragment. An absent class or an ambiguous shape installs nothing and reports `absent`, never a guess. Only 9.16.1
 * has been seen to run this path on a device; other builds are not claimed until the version matrix covers them.
 *
 * Two status keys, because the status channel keeps only the first value per key: `guest_mine_overlay` is the install
 * report, `guest_mine_overlay_applied` is set only when a guest call was actually skipped.
 */
internal class GuestMineOverlayHooks(
    private val isGuest: () -> Boolean,
    private val fragmentClass: String = FRAGMENT_CLASS
) {
    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val fragment = PrivacyStructuralResolver.type(loader, fragmentClass)
        val toggle = fragment?.let { type ->
            PrivacyStructuralResolver.uniqueMethod(type) { m ->
                m.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            }
        }
        if (fragment == null || toggle == null) {
            env.reportStatus("guest_mine_overlay", "absent")
            return@runCatching 0
        }
        env.registrar.exact("guest.mine.overlay.v2", fragment, toggle.name, *toggle.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                result = null
                env.reportStatus("guest_mine_overlay_applied", "seen")
            }
        }
        env.reportStatus("guest_mine_overlay", "installed")
        1
    }.getOrElse {
        env.logError("guest_mine_overlay", "[BIL] Guest mine overlay unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_mine_overlay", "failed")
        0
    }

    internal companion object {
        const val FRAGMENT_CLASS = "tv.danmaku.bili.ui.main2.mineV2.HomeUserCenterFragmentV2"
    }
}
