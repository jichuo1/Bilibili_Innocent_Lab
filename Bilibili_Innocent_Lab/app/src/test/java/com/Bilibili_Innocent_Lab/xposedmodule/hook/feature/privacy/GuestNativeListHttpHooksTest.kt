package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import fixtures.privacy.nativelist.AmbiguousCall
import fixtures.privacy.nativelist.FakeCache
import fixtures.privacy.nativelist.NewCall
import fixtures.privacy.nativelist.OldCall
import fixtures.privacy.nativelist.Resp
import fixtures.privacy.nativelist.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Type

class GuestNativeListHttpHooksTest {
    private companion object {
        const val PKG = "fixtures.privacy.nativelist."
    }

    private class NetworkCall { fun execute(): String = "network" }

    @Test
    fun failingLocalResponsesDeliverAnIoExceptionWithoutExecutingTheHostRequest() {
        val port = PlayerPortTestRegistrar()
        var policy: HookExceptionPolicy? = null
        val registrar = object : HookRegistrar by port {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                policy = exceptionPolicy
                val method = NetworkCall::class.java.getDeclaredMethod(point.methodName)
                port.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
            }
        }
        var localFailure = true
        GuestNativeListHttpHooks.registerExecute(registrar, NetworkCall::class.java) {
            before { if (localFailure) throwable = java.io.IOException("local response unavailable") }
        }
        var networkCalls = 0
        val error = assertThrows(java.io.IOException::class.java) {
            port.invoke("guest.native.lists.http", NetworkCall()) { networkCalls++; "network" }
        }
        assertEquals("local response unavailable", error.message)
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, policy)
        assertEquals(0, networkCalls)
        localFailure = false
        assertEquals("network", port.invoke("guest.native.lists.http", NetworkCall()) { networkCalls++; "network" })
        assertEquals(1, networkCalls)
    }
    @Test
    fun theConverterKeepsItsStableNameWhereTheBuildStillHasIt() {
        // 8.84 to 8.96: `p` and a same-shaped private `q`; the name decides, the shape is never consulted.
        val method = GuestNativeListHttpHooks.locateConverter(OldCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("p", method?.name)
    }

    @Test
    fun theConverterIsFoundByShapeWhenTheNameIsObfuscated() {
        // 8.97+: the single public instance (Response) -> retrofit2.Response method, whatever it is called.
        val method = GuestNativeListHttpHooks.locateConverter(NewCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("a", method?.name)
    }

    @Test
    fun theConverterIsNotGuessedWhenTwoPublicMethodsShareTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(AmbiguousCall::class.java, Resp::class.java, Result::class.java))
    }

    @Test
    fun theConverterIsAbsentWhenNoMethodHasTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(String::class.java, Resp::class.java, Result::class.java))
    }

    @Test
    fun theResponseTypeFieldIsTheOnlyFieldOfThatType() {
        val field = GuestNativeListHttpHooks.locateResponseType(WithType::class.java)
        assertEquals("c", field?.name)
    }

    @Test
    fun theResponseTypeFieldIsNotGuessedWhenTwoFieldsShareTheType() {
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithTwoTypes::class.java))
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithoutType::class.java))
    }

    @Test
    fun onlyTheLazyHoldingTheFallbackCacheIsReplaced() {
        val vm = CacheVm()
        assertEquals(1, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertNull(vm.cache.value)            // the ViewModel's `if (cache != null)` now skips both read and write
        assertTrue(vm.cache.isInitialized())
        assertEquals("key", vm.key.value)     // the key and the switch keep their own values
        assertEquals(true, vm.switch.value)
    }

    @Test
    fun aViewModelWithoutACacheLazyIsLeftAlone() {
        val vm = NoCacheVm()
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertEquals("key", vm.key.value)
    }

    @Test
    fun aLazyThatThrowsOrYieldsNothingIsSkippedNotFatal() {
        val vm = BrokenVm()
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertEquals("key", vm.key.value)
    }

    @Test
    fun theDefaultPackageIsTheHostFallbackCache() {
        assertEquals("com.bilibili.common.fallbackcache.", GuestNativeListHttpHooks.CACHE_PACKAGE)
        // A fixture cache outside that package is not touched when the default is used.
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(CacheVm()))
    }

    @Suppress("unused")
    private class CacheVm {
        val cache: Lazy<Any?> = lazy { FakeCache() }
        val key: Lazy<Any?> = lazy { "key" }
        val switch: Lazy<Any?> = lazy { true }
    }

    @Suppress("unused")
    private class NoCacheVm {
        val key: Lazy<Any?> = lazy { "key" }
    }

    @Suppress("unused")
    private class BrokenVm {
        val boom: Lazy<Any?> = lazy { error("not ready") }
        val empty: Lazy<Any?> = lazy { null }
        val key: Lazy<Any?> = lazy { "key" }
    }

    @Suppress("unused")
    private class WithType {
        // 9.10+ renamed the field and moved the boolean flags: the type field is found by type, not by `b`.
        val m = false
        val c: Type = String::class.java
    }

    @Suppress("unused")
    private class WithTwoTypes {
        val b: Type = String::class.java
        val c: Type = Int::class.java
    }

    @Suppress("unused")
    private class WithoutType {
        val b = 1
    }
}
