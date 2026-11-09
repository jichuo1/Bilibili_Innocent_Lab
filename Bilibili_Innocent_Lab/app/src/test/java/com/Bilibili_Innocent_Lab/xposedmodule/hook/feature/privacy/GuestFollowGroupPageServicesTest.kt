package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

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

    @Test fun dialogWrapsItsServiceBeforeTheHostViewCanUseIt() {
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

    @Test fun editorFactoryCallsAreScopedAndExpiredPagesStillCreateBlockedCalls() {
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

    @Test fun invalidServicesAndUnwrappedEditorsCannotReachTheHostView() {
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

    @Test fun unavailableServicesCannotCreateClickableViewsOrMenus() {
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
    @Test fun hostExceptionsReleaseTheScopeWithoutRetryingTheFactory() {
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

    @Test fun nestedPageCreationRestoresThePreviousMarker() {
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
}
