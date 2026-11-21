package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import java.io.IOException
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GuestFollowGroupPageServicesTest {
    class Call
    interface Service { fun create(): Call }
    class Delegate : Service { override fun create() = Call() }
    class View
    class Bundle
    class Inflater
    class ViewGroup
    class Menu
    class MenuInflater
    class Dialog(var marker: String?) {
        @JvmField var service: Service? = null
        fun onCreateView(inflater: Inflater, group: ViewGroup?, bundle: Bundle?): View? = View()
        fun onViewCreated(view: View, bundle: Bundle?) = Unit
    }
    class Editor(var marker: String?) {
        @JvmField var service: Service? = null
        fun onCreate(bundle: Bundle?) = Unit
        fun onCreateView(inflater: Inflater, group: ViewGroup?, bundle: Bundle?): View? = View()
        fun onCreateToolbarMenu(menu: Menu, inflater: MenuInflater) = Unit
        fun onViewCreated(view: View, bundle: Bundle?) = Unit
    }
    class Factory {
        companion object {
            var delegate: Any = Delegate()
            @JvmStatic fun createService(type: Class<*>): Any = delegate
        }
    }

    private val types = GuestFollowGroupPageServices.Types(
        Dialog::class.java, Editor::class.java, Service::class.java, Factory::class.java,
        Call::class.java, View::class.java, Bundle::class.java, Inflater::class.java, ViewGroup::class.java,
        Menu::class.java, MenuInflater::class.java
    )
    private val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
    private val port = PlayerPortTestRegistrar()
    private val policies = mutableListOf<HookExceptionPolicy>()
    private val registrar = object : HookRegistrar by port {
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                             block: ModernMemberHookCreator.() -> Unit) {
            policies += exceptionPolicy
            val method = when (id) {
                "guest.follow_group.service_factory" -> Factory::class.java.getDeclaredMethod("createService", Class::class.java)
                "guest.follow_group.editor_create" -> Editor::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
                "guest.follow_group.editor_create_view" -> Editor::class.java.getDeclaredMethod("onCreateView", Inflater::class.java, ViewGroup::class.java, Bundle::class.java)
                "guest.follow_group.editor_menu" -> Editor::class.java.getDeclaredMethod("onCreateToolbarMenu", Menu::class.java, MenuInflater::class.java)
                "guest.follow_group.dialog_create_view" -> Dialog::class.java.getDeclaredMethod("onCreateView", Inflater::class.java, ViewGroup::class.java, Bundle::class.java)
                "guest.follow_group.editor_view" -> Editor::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
                "guest.follow_group.dialog_view" -> Dialog::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
                else -> error("Unexpected hook $id")
            }
            assertEquals(method.declaringClass.name, point.className)
            port.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
        }
    }

    private fun install() {
        GuestFollowGroupPageServices(bindings) { page ->
            when (page) { is Dialog -> page.marker; is Editor -> page.marker; else -> error("Unexpected page") }
        }.install(registrar, types)
    }
    private fun createService(value: Any, type: Class<*> = Service::class.java): Any? =
        port.invoke("guest.follow_group.service_factory", args = arrayOf(type)) { value }

    fun dialogWrapsItsServiceBeforeTheHostViewCanUseIt() {
        install()
        assertTrue(policies.all { it == HookExceptionPolicy.DELIVER_TO_HOST })
        val token = Any()
        val page = Dialog(bindings.register(token, 42))
        Factory.delegate = Delegate()
        var entered = false
        port.invoke("guest.follow_group.dialog_create_view", page, arrayOf(Inflater(), null, null)) {
            entered = true
            val call = requireNotNull(page.service).create()
            assertSame(token, bindings.tagged(call)?.binding?.token)
            View()
        }
        assertTrue(entered)
        // Recreating the view keeps the same protected service rather than stacking wrappers.
        val original = page.service
        port.invoke("guest.follow_group.dialog_view", page, arrayOf(View(), null)) { null }
        assertSame(original, page.service)
    }

    fun editorFactoryCallsAreScopedAndExpiredPagesStillCreateBlockedCalls() {
        install()
        val token = Any()
        val marker = bindings.register(token, 42)
        val page = Editor(marker)
        port.invoke("guest.follow_group.editor_create", page, arrayOf(null)) {
            val unrelated = Any()
            assertSame(unrelated, createService(unrelated, String::class.java))
            page.service = createService(Delegate()) as Service
            null
        }
        assertSame(token, bindings.tagged(requireNotNull(page.service).create())?.binding?.token)
        var viewCalled = false
        port.invoke("guest.follow_group.editor_view", page, arrayOf(View(), null)) { viewCalled = true; null }
        assertTrue(viewCalled)
        val outside = Delegate()
        assertSame(outside, createService(outside))
        val restored = Editor("marker-from-an-earlier-process")
        port.invoke("guest.follow_group.editor_create", restored, arrayOf(null)) {
            restored.service = createService(Delegate()) as Service
            null
        }
        val call = requireNotNull(restored.service).create()
        assertNotNull(bindings.tagged(call))
        assertNull(bindings.tagged(call)?.binding)
    }

    fun invalidServicesAndUnwrappedEditorsCannotReachTheHostView() {
        install()
        val page = Editor(bindings.register(Any(), 42))
        port.invoke("guest.follow_group.editor_create", page, arrayOf(null)) {
            assertNull(createService(Any()))
            page.service = Delegate() // simulate an unsupported host bypassing its factory
            null
        }
        assertNull(page.service)
        var entered = false
        port.invoke("guest.follow_group.editor_view", page, arrayOf(View(), null)) { entered = true; null }
        assertFalse(entered)
        val outside = Delegate()
        assertSame(outside, createService(outside))
    }

    fun unavailableServicesCannotCreateClickableViewsOrMenus() {
        install()
        val marker = bindings.register(Any(), 42)
        val dialog = Dialog(marker)
        Factory.delegate = Any()
        var dialogCreated = false
        assertNull(port.invoke("guest.follow_group.dialog_create_view", dialog, arrayOf(Inflater(), null, null)) {
            dialogCreated = true
            View()
        })
        assertFalse(dialogCreated)
        val editor = Editor(marker)
        editor.service = Delegate()
        var viewCreated = false
        var menuCreated = false
        port.invoke("guest.follow_group.editor_create_view", editor, arrayOf(Inflater(), null, null)) { viewCreated = true; View() }
        port.invoke("guest.follow_group.editor_menu", editor, arrayOf(Menu(), MenuInflater())) { menuCreated = true; null }
        assertFalse(viewCreated)
        assertFalse(menuCreated)
    }
    fun hostExceptionsReleaseTheScopeWithoutRetryingTheFactory() {
        install()
        val page = Editor(bindings.register(Any(), 42))
        var calls = 0
        assertThrows(IOException::class.java) {
            port.invoke("guest.follow_group.editor_create", page, arrayOf(null)) {
                port.invoke("guest.follow_group.service_factory", args = arrayOf(Service::class.java)) {
                    calls++
                    throw IOException("factory failed")
                }
            }
        }
        assertEquals(1, calls)
        val outside = Delegate()
        assertSame(outside, createService(outside))
    }

    fun nestedPageCreationRestoresThePreviousMarker() {
        install()
        val firstToken = Any()
        val secondToken = Any()
        val first = Editor(bindings.register(firstToken, 42))
        val second = Editor(bindings.register(secondToken, 43))
        port.invoke("guest.follow_group.editor_create", first, arrayOf(null)) {
            port.invoke("guest.follow_group.editor_create", second, arrayOf(null)) {
                second.service = createService(Delegate()) as Service
                null
            }
            first.service = createService(Delegate()) as Service
            null
        }
        assertSame(firstToken, bindings.tagged(requireNotNull(first.service).create())?.binding?.token)
        assertSame(secondToken, bindings.tagged(requireNotNull(second.service).create())?.binding?.token)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupPageServicesTest) -> Unit) {
            val s = GuestFollowGroupPageServicesTest()
            block(s)
        }
        runIsolated { s -> s.dialogWrapsItsServiceBeforeTheHostViewCanUseIt() }
        runIsolated { s -> s.editorFactoryCallsAreScopedAndExpiredPagesStillCreateBlockedCalls() }
        runIsolated { s -> s.invalidServicesAndUnwrappedEditorsCannotReachTheHostView() }
        runIsolated { s -> s.unavailableServicesCannotCreateClickableViewsOrMenus() }
        runIsolated { s -> s.hostExceptionsReleaseTheScopeWithoutRetryingTheFactory() }
        runIsolated { s -> s.nestedPageCreationRestoresThePreviousMarker() }
    
        GuestFollowGroupRouteHooksCases().verifyAllCases()
        GuestFollowGroupServiceProxyCases().verifyAllCases()
        GuestHistoryBannerCases().historyBannerIsTheSingleInstanceFieldOfTheBannerType()
        GuestHostFavoriteDialogReloadCases().verifyAllCases()
        GuestHostFavoriteSnackbarCases().verifyAllCases()
    }
}

private class GuestFollowGroupRouteHooksCases {
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

    fun createRouteKeepsExistingArgumentsAndAddsTheOriginalMarker() {
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

    fun expiredMarkersAndAdapterFailuresCancelWithoutStartingTheOriginalRoute() {
        install()
        var starts = 0
        val stale = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100)
        assertNull(route(stale, Dialog("expired")) { starts++; "handled" })
        val invalid = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100, fail = true)
        assertNull(route(invalid, Dialog("session")) { starts++; "handled" })
        assertEquals(0, starts)
    }

    fun unrelatedOrUnmarkedRoutesAreNotChanged() {
        install()
        val unrelated = Request("bilibili://other/page", emptyMap(), 1)
        assertSame(unrelated, route(unrelated, Dialog("session")) { it })
        val request = Request(GuestFollowGroupRouteHooks.CREATE_ROUTE, emptyMap(), 100)
        assertSame(request, route(request, Dialog(null)) { it })
        assertSame(request, route(request, Fragment()) { it })
    }

    fun restoredPageMarkersFailClosedWhenMissingOrConflicting() {
        val choose = GuestFollowGroupRouteHooks.Companion::pageMarker
        assertEquals("a", choose(true, "a", false, null, false))
        assertEquals("a", choose(false, null, true, "a", false))
        assertEquals("a", choose(true, "a", true, "a", false))
        assertEquals("", choose(true, "a", true, "b", false))
        assertEquals("", choose(true, null, false, null, false))
        assertEquals("", choose(false, null, false, null, true))
        assertNull(choose(false, null, false, null, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupRouteHooksCases) -> Unit) {
            val s = GuestFollowGroupRouteHooksCases()
            block(s)
        }
        runIsolated { s -> s.createRouteKeepsExistingArgumentsAndAddsTheOriginalMarker() }
        runIsolated { s -> s.expiredMarkersAndAdapterFailuresCancelWithoutStartingTheOriginalRoute() }
        runIsolated { s -> s.unrelatedOrUnmarkedRoutesAreNotChanged() }
        runIsolated { s -> s.restoredPageMarkersFailClosedWhenMissingOrConflicting() }
    }
}

private class GuestFollowGroupServiceProxyCases {
    class Call(val mid: Long)
    interface Service {
        fun group(mid: Long): Call
        fun unsupported(): String
        fun fail(): Call
    }
    class Delegate : Service {
        var calls = 0
        override fun group(mid: Long): Call { calls++; return Call(mid) }
        override fun unsupported(): String { calls++; return "unsafe" }
        override fun fail(): Call { throw IllegalArgumentException("host failure") }
    }

    fun returnedCallsAreTaggedBeforeTheCallerCanUseThem() {
        val token = Any()
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val marker = bindings.register(token, 42)
        val delegate = Delegate()
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, delegate, Call::class.java, marker) as Service
        val call = service.group(42)
        assertEquals(1, delegate.calls)
        assertEquals(42L, call.mid)
        assertSame(token, bindings.tagged(call)!!.binding!!.token)
        assertEquals(42L, bindings.tagged(call)!!.binding!!.mid)
        assertNull(bindings.tagged(Call(42)))
        assertEquals(service, service)
        assertNotEquals(service, delegate)
    }

    fun unexpectedShapesFailBeforeCallingTheHostAndOriginalErrorsArePreserved() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val marker = bindings.register(Any(), 42)
        val delegate = Delegate()
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, delegate, Call::class.java, marker) as Service
        assertThrows(IllegalStateException::class.java) { service.unsupported() }
        assertEquals(0, delegate.calls)
        assertEquals("host failure", assertThrows(IllegalArgumentException::class.java) { service.fail() }.message)
        assertThrows(IllegalArgumentException::class.java) {
            GuestFollowGroupServiceProxy(bindings).wrap(String::class.java, delegate, Call::class.java, marker)
        }
    }

    fun anEvictedPageStillProducesBlockedCalls() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val marker = bindings.register(Any(), 42)
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, Delegate(), Call::class.java, marker) as Service
        bindings.register(Any(), 43)
        val call = service.group(42)
        assertNotNull(bindings.tagged(call))
        assertNull(bindings.tagged(call)!!.binding)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupServiceProxyCases) -> Unit) {
            val s = GuestFollowGroupServiceProxyCases()
            block(s)
        }
        runIsolated { s -> s.returnedCallsAreTaggedBeforeTheCallerCanUseThem() }
        runIsolated { s -> s.unexpectedShapesFailBeforeCallingTheHostAndOriginalErrorsArePreserved() }
        runIsolated { s -> s.anEvictedPageStillProducesBlockedCalls() }
    }
}

private class GuestHistoryBannerCases {
    class Banner
    class OneBannerPage { @JvmField var n: Banner? = null; @JvmField var title: String = "" }
    class TwoBannerPage { @JvmField var n: Banner? = null; @JvmField var t: Banner? = null }
    class StaticBannerPage { companion object { @JvmField var n: Banner? = null } }

    fun historyBannerIsTheSingleInstanceFieldOfTheBannerType() {
        assertEquals("n", GuestNativeHistoryHooks.bannerField(OneBannerPage::class.java, Banner::class.java)?.name)
        assertNull(GuestNativeHistoryHooks.bannerField(TwoBannerPage::class.java, Banner::class.java))  // ambiguity is never guessed
        assertNull(GuestNativeHistoryHooks.bannerField(StaticBannerPage::class.java, Banner::class.java))
        assertNull(GuestNativeHistoryHooks.bannerField(OneBannerPage::class.java, null))               // type absent on this build
    }
}

private class GuestHostFavoriteDialogReloadCases {
    private class LegacyDialog {
        fun unrelated() = Unit
        fun refresh() = Unit
        fun refresh(value: Int) = value
    }

    fun learnsTheActualNoArgumentDialogCallerWhenSeveralExist() {
        val name = LegacyDialog::class.java.name
        val stack = arrayOf(
            StackTraceElement("host.UpdateCoroutine", "<init>", "source", 1),
            StackTraceElement(name, "refresh", "source", 2),
            StackTraceElement(name, "unrelated", "source", 3)
        )
        val method = GuestHostFavoriteDialog.resolveReloadFromStack(LegacyDialog::class.java, stack)
        assertEquals("refresh", method?.name)
        assertEquals(0, method?.parameterCount)
    }

    fun ignoresFramesFromOtherClassesAndUnknownMethods() {
        val stack = arrayOf(StackTraceElement("host.Other", "refresh", "source", 1),
            StackTraceElement(LegacyDialog::class.java.name, "missing", "source", 2))
        assertNull(GuestHostFavoriteDialog.resolveReloadFromStack(LegacyDialog::class.java, stack))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestHostFavoriteDialogReloadCases) -> Unit) {
            val s = GuestHostFavoriteDialogReloadCases()
            block(s)
        }
        runIsolated { s -> s.learnsTheActualNoArgumentDialogCallerWhenSeveralExist() }
        runIsolated { s -> s.ignoresFramesFromOtherClassesAndUnknownMethods() }
    }
}

private class GuestHostFavoriteSnackbarCases {
    private class LegacyUtil {
        companion object { @JvmField val INSTANCE = LegacyUtil() }
        fun show(value: String) { last = value }
        @JvmField var last: String? = null
    }

    private class StaticUtil {
        companion object {
            @JvmField var last: String? = null
            @JvmStatic fun show(value: String) { last = value }
        }
    }

    private class AmbiguousUtil {
        fun first(value: String) = Unit
        fun second(value: String) = Unit
    }

    fun locatesOlderSingletonAndNewerStaticMethods() {
        val signature = arrayOf<Class<*>>(String::class.java)
        val legacy = GuestHostFavoriteSnackbar.locateShow(LegacyUtil::class.java, signature)
        assertNotNull(legacy)
        assertSame(LegacyUtil.INSTANCE, legacy!!.receiver)
        legacy.method.invoke(legacy.receiver, "old")
        assertEquals("old", LegacyUtil.INSTANCE.last)
        val modern = GuestHostFavoriteSnackbar.locateShow(StaticUtil::class.java, signature)
        assertNotNull(modern)
        assertNull(modern!!.receiver)
        modern.method.invoke(modern.receiver, "new")
        assertEquals("new", StaticUtil.last)
    }

    fun rejectsAmbiguousMethods() {
        assertNull(GuestHostFavoriteSnackbar.locateShow(AmbiguousUtil::class.java, arrayOf(String::class.java)))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestHostFavoriteSnackbarCases) -> Unit) {
            val s = GuestHostFavoriteSnackbarCases()
            block(s)
        }
        runIsolated { s -> s.locatesOlderSingletonAndNewerStaticMethods() }
        runIsolated { s -> s.rejectsAmbiguousMethods() }
    }
}
