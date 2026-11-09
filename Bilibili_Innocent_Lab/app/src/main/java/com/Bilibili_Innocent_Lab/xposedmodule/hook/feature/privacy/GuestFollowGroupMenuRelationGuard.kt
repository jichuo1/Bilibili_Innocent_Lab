package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Answers the host menu's relation lookup locally, including callbacks delayed past a session change. */
internal class GuestFollowGroupMenuRelationGuard {
    internal data class Types(val manager: Class<*>, val callback: Class<*>, val attention: Class<*>, val menu: Class<*>)
    private class Frame(val uid: Long, val special: Boolean, val valid: () -> Boolean) {
        val answered = AtomicBoolean()
    }
    private val pending = WeakHashMap<Any, Frame>()
    private val current = ThreadLocal<Frame?>()

    fun <T> within(menu: Any, uid: Long, special: Boolean, valid: () -> Boolean, action: () -> T): T {
        val frame = Frame(uid, special, valid)
        synchronized(pending) { pending[menu] = frame }
        val previous = current.get()
        current.set(frame)
        try { return action() } finally { current.set(previous) }
    }

    fun install(registrar: HookRegistrar, types: Types) {
        require(types.menu.isInterface)
        val relation = requireNotNull(Lookup.methodOrNull(types.manager, "getRelation",
            String::class.java, String::class.java, types.callback))
        require(Modifier.isStatic(relation.modifiers) && relation.returnType == Void.TYPE)
        val success = requireNotNull(Lookup.methodOrNull(types.callback, "onDataSuccess", Any::class.java))
        val construct = requireNotNull(Lookup.constructorOrNull(types.attention))
        val special = requireNotNull(Lookup.fieldOrNull(types.attention, "special"))
        require(special.type == Int::class.javaPrimitiveType && !Modifier.isStatic(special.modifiers))
        registrar.adapted("guest.follow_group.menu_relation",
            VersionAdapter.HookPoint(types.manager.name, relation.name, relation.parameterTypes.map { it.name }),
            HookExceptionPolicy.DELIVER_TO_HOST) {
            before {
                // While a local menu is alive, even an unrecognised callback cannot authorize a cloud lookup.
                val active = try { current.get() != null || synchronized(pending) { pending.isNotEmpty() } }
                    catch (_: Throwable) { true }
                if (!active) return@before
                result = null
                try {
                    val callback = requireNotNull(argOrNull(2))
                    val frame = current.get() ?: run {
                        val fields = Lookup.fields(callback.javaClass, includeSuperclasses = true, makeAccessible = true) {
                            !Modifier.isStatic(it.modifiers) && it.type == types.menu
                        }
                        val menu = fields.singleOrNull()?.get(callback) ?: return@before
                        synchronized(pending) { pending[menu] } ?: return@before
                    }
                    if (argOrNull(1) != frame.uid.toString() || !frame.valid() || !frame.answered.compareAndSet(false, true)) return@before
                    val data = construct.newInstance()
                    special.setInt(data, if (frame.special) 1 else 0)
                    success.invoke(callback, data)
                } catch (_: Throwable) {
                    // This request remains blocked if the host callback or local session cannot be resolved.
                }
            }
        }
    }
}
