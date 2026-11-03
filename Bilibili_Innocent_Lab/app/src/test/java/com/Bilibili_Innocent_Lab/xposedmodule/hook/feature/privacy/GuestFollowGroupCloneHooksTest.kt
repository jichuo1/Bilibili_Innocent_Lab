package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupCloneHooksTest {
    private class Call : Cloneable {
        public override fun clone(): Call = Call()
    }

    private class UnsupportedCall

    private fun registrar(port: PlayerPortTestRegistrar, type: Class<*>): HookRegistrar =
        object : HookRegistrar by port {
            override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
                assertEquals(type.name, className)
                type.declaredMethods.filter { it.name == methodName }.forEach { method ->
                    port.hooks[method.returnType.name] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
                }
            }
        }

    @Test fun copiesRetainTheOriginalBindingAcrossBothCloneEntrypoints() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val token = Any()
        val marker = bindings.register(token, 42)
        val source = Call()
        bindings.tag(source, marker)
        val port = PlayerPortTestRegistrar()
        assertTrue(GuestFollowGroupCloneHooks.install(registrar(port, Call::class.java), Call::class.java, bindings))
        val first = Call()
        assertSame(first, port.invoke(Call::class.java.name, source) { first })
        assertSame(token, bindings.tagged(first)?.binding?.token)
        val second = Call()
        assertSame(second, port.invoke(Any::class.java.name, first) { second })
        assertEquals(42L, bindings.tagged(second)?.binding?.mid)
        bindings.register(Any(), 43) // evict the original marker while a copied call is still in flight
        val expired = Call()
        assertSame(expired, port.invoke(Call::class.java.name, second) { expired })
        assertNotNull(bindings.tagged(expired))
        assertNull(bindings.tagged(expired)?.binding)
    }

    @Test fun unrelatedClonesRemainUnchangedAndInvalidCopiesAreNotReturned() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val port = PlayerPortTestRegistrar()
        assertTrue(GuestFollowGroupCloneHooks.install(registrar(port, Call::class.java), Call::class.java, bindings))
        val unrelated = Call()
        val copy = Call()
        assertSame(copy, port.invoke(Call::class.java.name, unrelated) { copy })
        assertNull(bindings.tagged(copy))
        bindings.tag(unrelated, bindings.register(Any(), 42))
        assertNull(port.invoke(Any::class.java.name, unrelated) { Any() })
    }

    @Test fun unsupportedCloneShapesAreNotInstalled() {
        val port = PlayerPortTestRegistrar()
        val bindings = GuestFollowGroupBindings<Any>()
        assertFalse(GuestFollowGroupCloneHooks.install(registrar(port, UnsupportedCall::class.java), UnsupportedCall::class.java, bindings))
        assertTrue(port.hooks.isEmpty())
    }
}
