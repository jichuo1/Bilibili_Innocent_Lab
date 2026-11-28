package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import androidx.fragment.app.Fragment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import kotlin.coroutines.Continuation
import org.junit.Assert.*
import org.junit.Test

class PrivacyStructuralResolverTest {
    @Suppress("unused")
    open class Base {
        fun parentOnly(): Long = 0L
        open fun shadowed(): String = "base"
    }
    @Suppress("unused")
    class Host : Base() {
        var flag: Boolean = false
        var tag: String = ""
        var other: String = ""
        override fun shadowed(): String = "child"
        fun uniqueLong(): Long = 1L
        fun takesString(value: String): Unit = Unit
        fun ambiguousA(): Boolean = true
        fun ambiguousB(): Boolean = false
    }

    fun stableNameResolvesExactMethodAndField() {
        assertNotNull(PrivacyStructuralResolver.method(Host::class.java, "takesString", String::class.java))
        assertNull(PrivacyStructuralResolver.method(Host::class.java, "takesString"))
        assertNotNull(PrivacyStructuralResolver.field(Host::class.java, "flag"))
        assertNull(PrivacyStructuralResolver.field(Host::class.java, "missing"))
    }

    fun uniqueMethodMatchesTheSingleShapeAndFailsClosedWhenAmbiguous() {
        val only = PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Long.TYPE && it.parameterCount == 0
        }
        assertEquals("uniqueLong", only?.name)
        // Two zero-arg boolean methods -> ambiguous -> null, never a guessed pick.
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Boolean.TYPE && it.parameterCount == 0
        })
        // No match -> null.
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Double.TYPE
        })
    }

    fun uniqueFieldMatchesShapeAndFailsClosedWhenAmbiguous() {
        assertEquals("flag", PrivacyStructuralResolver.uniqueField(Host::class.java) {
            it.type == java.lang.Boolean.TYPE
        }?.name)
        // tag and other are both String -> ambiguous.
        assertNull(PrivacyStructuralResolver.uniqueField(Host::class.java) { it.type == String::class.java })
    }

    fun superclassScopeIsOptIn() {
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java, includeSuperclasses = false) {
            it.name == "parentOnly"
        })
        assertNotNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java, includeSuperclasses = true) {
            it.name == "parentOnly"
        })
    }

    fun typeResolvesByStableName() {
        assertEquals(Host::class.java, PrivacyStructuralResolver.type(javaClass.classLoader!!, Host::class.java.name))
        assertNull(PrivacyStructuralResolver.type(javaClass.classLoader!!, "does.not.Exist"))
    }

    private class FakePlaySet
    private open class FakeEventBase
    private class FakeEventInit(@JvmField var g: Boolean = false) : FakeEventBase()
    private class FakeEventModify : FakeEventBase()
    private class FakeEditorViewModel { @Suppress("UNUSED_PARAMETER") fun a2(e: FakeEventBase) = Unit }
    private class FakeEditorFragment { fun Wo() = Unit }
    private class FakeToast

    private class RenamedStateMachine {
        @Suppress("UNUSED_PARAMETER")
        fun renamedWhitelist(a: String, c: Continuation<*>): Any = 1
        @Suppress("UNUSED_PARAMETER")
        fun renamedCreate(a: String, b: String, c: String, d: String, e: Int, cont: Continuation<*>): Any = Unit
        @Suppress("UNUSED_PARAMETER")
        fun renamedEdit(a: String, b: String, c: String, d: String, e: Int, id: Long, cont: Continuation<*>): Any = Unit
    }

    private class RenamedWatchLaterManager {
        @Suppress("UNUSED_PARAMETER") fun e1(a: Activity, b: String, c: String, d: String, e: Int, f: Boolean, g: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun e2(a: Fragment, b: String, c: String, d: String, e: Int, f: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun e3(a: Activity, b: String, c: String, d: Int, e: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun r1(a: String, b: String, c: String, cont: Continuation<*>): Any = Unit
        @Suppress("UNUSED_PARAMETER") fun r2(a: String, b: String, cont: Continuation<*>): Any = Unit

        companion object {
            @JvmField val singletonInst = RenamedWatchLaterManager()
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c1(a: String, b: Activity, c: String, d: String, e: Int, f: Boolean, g: Int) = Unit
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c2(a: String, b: Fragment, c: String, d: String, e: Int, f: Int) = Unit
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c3(a: String, b: Activity, c: String, d: Int, e: Int) = Unit
        }
    }

    private class RenamedCallback {
        fun onFollowSuccess(): Boolean = true
        fun onUnFollowSuccess(): Boolean = true
    }

    private class RenamedFollowFlowHelper {
        private val renamedMid: Long = 42L
        private val renamedCb: RenamedCallback? = null
    }

    private class RenamedMenuService {
        private val renamedActivity: Activity? = null
    }

    private fun allocateDummyGuestLibraryRuntime(): GuestLibraryRuntime {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val instanceField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = instanceField.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, GuestLibraryRuntime::class.java) as GuestLibraryRuntime
    }

    fun tierBGuestFallbacksAndRenamedHelpers() {
        val loader = javaClass.classLoader!!
        val library = allocateDummyGuestLibraryRuntime()
        val statuses = mutableMapOf<String, String>()
        fun env(customLoader: ClassLoader, registrar: PlayerPortTestRegistrar) = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = customLoader,
            hookPoints = HookPointRegistry(customLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v }
        )

        val watchLaterLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "tv.danmaku.bili.ui.watchlater.widget.WatchLaterManagerV2" -> RenamedWatchLaterManager::class.java
                "tv.danmaku.bili.ui.watchlater.data.WatchLaterAddToast" -> FakeToast::class.java
                "te6.b" -> throw ClassNotFoundException(name)
                else -> super.loadClass(name, resolve)
            }
        }
        val regWatch = PlayerPortTestRegistrar()
        GuestNativeWatchLaterAddHooks(library) { true }.install(env(watchLaterLoader, regWatch))
        assertEquals("adapted", statuses["guest_native_watch_later_add"])
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.j"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.k"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.p"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.add.i"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.add.u"))
        assertFalse(regWatch.hooks.containsKey("guest.native.later.entry.legacy"))

        val editorLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bilibili.playset.editor.PlaySetEditorStateMachine" -> RenamedStateMachine::class.java
                "com.bilibili.playset.editor.PlaySetEditorViewModel" -> FakeEditorViewModel::class.java
                "com.bilibili.playset.editor.PlaySetEditorFragment" -> FakeEditorFragment::class.java
                "com.bilibili.playset.api.PlaySet" -> FakePlaySet::class.java
                "ss3.c" -> FakeEventBase::class.java
                "ss3.c\$c" -> FakeEventInit::class.java
                "ss3.c\$f" -> FakeEventModify::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        val regEditor = PlayerPortTestRegistrar()
        GuestNativeFolderEditorHooks(library) { true }.install(env(editorLoader, regEditor)) {}
        assertEquals("adapted-v2", statuses["guest_native_folder_editor"])
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_whitelist"))
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_create"))
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_edit"))

        assertNotNull(PrivacyStructuralResolver.uniqueField(RenamedFollowFlowHelper::class.java) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType
        })
        assertNotNull(PrivacyStructuralResolver.uniqueField(RenamedFollowFlowHelper::class.java) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                KavaMemberLookup.methodOrNull(it.type, "onFollowSuccess") != null &&
                KavaMemberLookup.methodOrNull(it.type, "onUnFollowSuccess") != null
        })
        assertNotNull(PrivacyStructuralResolver.uniqueField(RenamedMenuService::class.java) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && Activity::class.java.isAssignableFrom(it.type)
        })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PrivacyStructuralResolverTest) -> Unit) {
            val s = PrivacyStructuralResolverTest()
            block(s)
        }
        runIsolated { s -> s.stableNameResolvesExactMethodAndField() }
        runIsolated { s -> s.uniqueMethodMatchesTheSingleShapeAndFailsClosedWhenAmbiguous() }
        runIsolated { s -> s.uniqueFieldMatchesShapeAndFailsClosedWhenAmbiguous() }
        runIsolated { s -> s.superclassScopeIsOptIn() }
        runIsolated { s -> s.typeResolvesByStableName() }
        runIsolated { s -> s.tierBGuestFallbacksAndRenamedHelpers() }
    }
}
