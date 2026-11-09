package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupRouteHooksTest {
    open class Fragment
    class Dialog(var marker: String?) : Fragment()
    class Extras(private val values: MutableMap<String, String>, private val fail: Boolean) {
        fun put(key: String, value: String): Extras {
            check(!fail)
            values[key] = value
            return this
        }
    }
    class Request(private val uri: String, val values: Map<String, String>, val requestCode: Int, private val fail: Boolean = false) {
        fun getTargetUri(): String = uri
        fun newBuilder(): Builder = Builder(uri, values.toMutableMap(), requestCode, fail)
    }
    class Builder(private val uri: String, private val values: MutableMap<String, String>, private val requestCode: Int, private val fail: Boolean) {
        fun getExtras(): Extras = Extras(values, fail)
        fun build(): Request = Request(uri, values.toMap(), requestCode)
    }
    class Router {
        companion object { @JvmStatic fun routeTo(request: Request, fragment: Fragment): Any? = null }
    }

    private val port = PlayerPortTestRegistrar()
    private var policy: HookExceptionPolicy? = null
    private val registrar = object : HookRegistrar by port {
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                             block: ModernMemberHookCreator.() -> Unit) {
            policy = exceptionPolicy
            val method = Router::class.java.getDeclaredMethod("routeTo", Request::class.java, Fragment::class.java)
            assertEquals(method.declaringClass.name, point.className)
            port.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
        }
    }
    private val types = GuestFollowGroupRouteHooks.Types(Router::class.java, Request::class.java, Builder::class.java,
        Extras::class.java, Fragment::class.java, Dialog::class.java)
    private fun install(valid: (String) -> Boolean = { it == "session" }) {
        GuestFollowGroupRouteHooks({ (it as Dialog).marker }, valid).install(registrar, types)
    }
    private fun route(request: Request, page: Fragment, callback: (Request) -> Any?): Any? =
        port.invoke("guest.follow_group.create_route", args = arrayOf(request, page)) { callback(it[0] as Request) }

    @Test fun createRouteKeepsExistingArgumentsAndAddsTheOriginalMarker() {
        install()
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, policy)
        val request = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, mapOf("existing" to "value"), 100)
        val result = route(request, Dialog("session")) { routed ->
            assertNotSame(request, routed)
            assertEquals(100, routed.requestCode)
            assertEquals("value", routed.values["existing"])
            assertEquals("session", routed.values[GuestFollowGroupRouteHooks.MARKER])
            "handled"
        }
        assertEquals("handled", result)
        assertNull(request.values[GuestFollowGroupRouteHooks.MARKER])
    }

    @Test fun expiredMarkersAndAdapterFailuresCancelWithoutStartingTheOriginalRoute() {
        install()
        var starts = 0
        val stale = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100)
        assertNull(route(stale, Dialog("expired")) { starts++; "handled" })
        val invalid = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100, fail = true)
        assertNull(route(invalid, Dialog("session")) { starts++; "handled" })
        assertEquals(0, starts)
    }

    @Test fun unrelatedOrUnmarkedRoutesAreNotChanged() {
        install()
        val unrelated = Request("bilibili://other/page", emptyMap(), 1)
        assertSame(unrelated, route(unrelated, Dialog("session")) { it })
        val request = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100)
        assertSame(request, route(request, Dialog(null)) { it })
        assertSame(request, route(request, Fragment()) { it })
    }

    @Test fun restoredPageMarkersFailClosedWhenMissingOrConflicting() {
        val choose = GuestFollowGroupRouteHooks.Companion::pageMarker
        assertEquals("a", choose(true, "a", false, null, false))
        assertEquals("a", choose(false, null, true, "a", false))
        assertEquals("a", choose(true, "a", true, "a", false))
        assertEquals("", choose(true, "a", true, "b", false))
        assertEquals("", choose(true, null, false, null, false))
        assertEquals("", choose(false, null, false, null, true))
        assertNull(choose(false, null, false, null, false))
    }
}
