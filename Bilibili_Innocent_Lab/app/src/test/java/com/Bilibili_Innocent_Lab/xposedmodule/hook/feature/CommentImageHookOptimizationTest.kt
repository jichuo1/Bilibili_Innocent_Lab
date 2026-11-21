package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.app.comment3.ui.widget.RichTextView
import com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.widget.CardExpandableTextView
import com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.handler.a
import com.bilibili.app.comment3.data.model.CommentItem
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class CommentImageHookOptimizationTest {
    private val recorder = PlayerPortTestRegistrar()
    private val loader = javaClass.classLoader!!
    private val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), recorder,
        { _, _ -> }, { _, _ -> }, { _, _ -> })
    private fun body(): CardExpandableTextView {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return unsafe.allocateInstance(CardExpandableTextView::class.java) as CardExpandableTextView
    }

    fun disabledCopyInstallsNoTextOrConstructorCallbacks() {
        val bridge = CommentNativeSupplementBridge({ false }, { _, _, _ -> }, { "raw" })
        assertTrue(bridge.install(environment).isEmpty())
        assertTrue(recorder.hooks.isEmpty())
    }

    fun imageUsesOneClosestHostSetterAndCurrentBinding() {
        var binds = 0
        val bridge = CommentNativeSupplementBridge({ true }, { _, raw, _ -> assertEquals("raw", raw); binds++ }, { "raw" })
        val result = bridge.install(environment).getValue("image")
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        val text = recorder.hooks.getValue("free.copy.image.text")
        assertEquals(RichTextView::class.java, text.member.declaringClass)
        assertFalse(recorder.hooks.containsKey("free.copy.image.spannable"))
        val view = body(); val item = CommentItem(); val owner = a(a.Binding(view), item)
        val ctor = recorder.hooks.keys.single { it.startsWith("free.copy.image.owner") }
        recorder.invoke(ctor, owner)
        recorder.invoke(ctor, owner) // Constructor delegation/retry must not duplicate owner identity.
        recorder.invoke("free.copy.image.text", view, arrayOf("display", null))
        assertEquals(1, binds)
        assertSame(item, bridge.imageContent(view)!!.second)
    }

    fun twoLiveOwnersOfOneBodyRemainAmbiguous() {
        val bridge = CommentNativeSupplementBridge({ true }, { _, _, _ -> }, { "raw" })
        bridge.install(environment)
        val view = body(); val first = a(a.Binding(view), CommentItem()); val second = a(a.Binding(view), CommentItem())
        val ctor = recorder.hooks.keys.single { it.startsWith("free.copy.image.owner") }
        recorder.invoke(ctor, first); recorder.invoke(ctor, second)
        assertNull(bridge.imageContent(view))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentImageHookOptimizationTest) -> Unit) {
            val s = CommentImageHookOptimizationTest()
            block(s)
        }
        runIsolated { s -> s.disabledCopyInstallsNoTextOrConstructorCallbacks() }
        runIsolated { s -> s.imageUsesOneClosestHostSetterAndCurrentBinding() }
        runIsolated { s -> s.twoLiveOwnersOfOneBodyRemainAmbiguous() }
    }
}
