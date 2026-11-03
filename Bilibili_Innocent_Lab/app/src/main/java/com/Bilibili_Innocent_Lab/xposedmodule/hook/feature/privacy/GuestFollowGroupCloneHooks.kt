package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/** Preserve the local session when BiliCall is copied, including an expired or unknown session. */
internal object GuestFollowGroupCloneHooks {
    private const val TAGGED = "guest.follow_group.tagged_clone"

    fun <T : Any> install(registrar: HookRegistrar, call: Class<*>, bindings: GuestFollowGroupBindings<T>): Boolean {
        // All 38 sampled versions expose both the covariant clone and its Object bridge. Refuse unverified shapes.
        val clones = Lookup.declaredMethods(call, makeAccessible = true) { it.name == "clone" }
        if (clones.size != 2 || clones.any { Modifier.isStatic(it.modifiers) || it.parameterCount != 0 } ||
            clones.map { it.returnType }.toSet() != setOf(call, Any::class.java)) return false
        registrar.all("guest.native.follow_group.clone", call.name, "clone") {
            before {
                try {
                    val source = instance
                    if (source != null && bindings.tagged(source) != null) setObjectExtra(TAGGED, true)
                } catch (_: Throwable) {
                    // Even a classification failure must not return an untagged copy of a local request.
                    result = null
                }
            }
            after {
                if (hasThrowable || getObjectExtra(TAGGED) != true) return@after
                val clone = result ?: return@after
                // Set failure first: no exceptional path may leak the copied request without its session.
                result = null
                try {
                    require(call.isInstance(clone))
                    bindings.forward(requireNotNull(instance), clone)
                    result = clone
                } catch (_: Throwable) {
                    // Null aborts this clone; the original host request is never returned to the caller.
                }
            }
        }
        return true
    }
}
