package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostComposeSemanticsAccessTest {
    // The real receiver is stable while the key type, holder, field and generic setter may be R8-renamed.
    class X(private val q: String, @Suppress("unused") private val d: String? = null,
            @Suppress("unused") private val merge: (Any?, Any?) -> Any? = { a, _ -> a }) {
        override fun toString() = "AccessibilityKey: $q"
    }
    interface Receiver { fun q(key: X, value: Any) }
    class Configuration : Receiver {
        val values = linkedMapOf<X, Any>()
        override fun q(key: X, value: Any) { values[key] = value }
    }
    class P private constructor() {
        companion object {
            @JvmField val z = X("ContentDescription")
            @JvmField val a = X("StateDescription")
            @JvmField val c = X("TestTag", "ContentDescription")
            @JvmField val click = X("OnClick")
            @JvmField val selected = X("Selected")
        }
    }
    class DifferentCanonical private constructor() {
        companion object {
            @JvmField val a = X("ContentDescription")
            @JvmField val b = X("StateDescription")
            @JvmField val c = X("TestTag", "ContentDescription")
        }
    }
    class Lookalike private constructor() {
        companion object { @JvmField val sameName = X("ContentDescription") }
    }
    object Direct {
        @JvmStatic fun setContentDescription(target: Receiver, title: String) { target.q(P.z, listOf(title)) }
    }
    interface Ambiguous { fun set(key: X, value: Any); fun other(key: OtherKey, value: Any) }
    class OtherKey(private val name: String)
    class AmbiguousNameKey(private val a: String, private val b: String)
    interface AmbiguousNameReceiver { fun set(key: AmbiguousNameKey, value: Any) }

    @Test fun renamedHolderUsesCanonicalKeyAndLeavesNativeClickAndSelectedUntouched() {
        val point = HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java, listOf(P::class.java))
        assertEquals(HomeChromeSemanticsPoint(P::class.java.name, "z"), point)
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, point, listOf(P::class.java))
        assertNotNull(access)
        val node = Configuration()
        val click = { "native click" }
        node.values[P.click] = click
        node.values[P.selected] = true
        access!!.describe(node, "动态")
        assertEquals(listOf("动态"), node.values[P.z])
        assertSame(P.z, node.values.keys.single { it !== P.click && it !== P.selected })
        assertSame(click, node.values[P.click])
        assertEquals(true, node.values[P.selected])
        assertEquals(3, node.values.size)
    }

    @Test fun directHostSetterWorksWithoutCanonicalHolderMetadata() {
        val setter = Direct::class.java.getDeclaredMethod("setContentDescription", Receiver::class.java, String::class.java)
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, setter, null, emptyList())
        val node = Configuration()
        access!!.describe(node, "首页")
        assertEquals(mapOf(P.z to listOf("首页")), node.values)
    }

    @Test fun ambiguousAndOneOffSameNamedKeysCannotAuthorizeLabelRemoval() {
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java,
            listOf(P::class.java, DifferentCanonical::class.java)))
        assertNull(HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, null,
            listOf(P::class.java, DifferentCanonical::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java, listOf(Lookalike::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Ambiguous::class.java, listOf(P::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(AmbiguousNameReceiver::class.java, listOf(P::class.java)))
    }

    @Test fun cachedFieldMustStillBeTheActualDescriptionKey() {
        listOf("missing", "a", "c").forEach { name ->
            assertNull(HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null,
                HomeChromeSemanticsPoint(P::class.java.name, name), listOf(P::class.java)))
        }
        val node = Configuration()
        assertTrue(node.values.isEmpty())
    }

    @Test fun cachedMetadataRoundTripsWithoutKeyObjectsOrTitles() {
        val point = HomeChromeSemanticsPoint(P::class.java.name, "z")
        assertEquals(point, HomeChromeSemanticsPoint.fromJson(point.toJson()))
        assertEquals(setOf("holder", "key"), point.toJson().keys().asSequence().toSet())
        listOf(JSONObject(), JSONObject().put("holder", "java.lang.String").put("key", "value"),
            JSONObject().put("holder", P::class.java.name).put("key", "bad/key"),
            JSONObject().put("holder", "[Lb1.P;").put("key", "z")).forEach {
            assertNull(HomeChromeSemanticsPoint.fromJson(it))
        }
    }

    @Test fun failedWriterAttachmentKeepsNativeLabelAndCanRetryWithoutStaleTitle() {
        val state = HostBottomLabelState()
        assertFalse(state.captureForRemoval("首页"))
        assertTrue(runCatching { state.attachSemantics { error("native semantics rejected") } }.isFailure)
        assertFalse(state.captureForRemoval("动态"))
        assertEquals("", state.value)
        var attached = false
        state.attachSemantics { attached = true }
        assertTrue(attached)
        assertTrue(state.captureForRemoval("我的"))
        assertEquals("我的", state.value)
    }

    @Test fun unrelatedTargetIsRejectedBeforeAnyPropertyWrite() {
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, null, listOf(P::class.java))!!
        val node = Configuration()
        assertTrue(runCatching { access.describe(Any(), "wrong target") }.isFailure)
        assertTrue(node.values.isEmpty())
    }
}
