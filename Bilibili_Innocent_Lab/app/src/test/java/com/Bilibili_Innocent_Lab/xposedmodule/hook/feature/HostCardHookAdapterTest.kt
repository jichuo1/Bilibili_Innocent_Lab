package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardHookCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardAdapter
import org.junit.Assert.*
import org.junit.Test

class HostCardHookAdapterTest {
    private fun captured(owner: Class<*> = CapturedCard::class.java) = HostOgvCardAdapter.captured(
        owner, CardModel::class.java, FollowCapture::class.java, CardModifier::class.java, CardComposer::class.java)

    @Test fun `only exact single card capture is accepted and outer page capture is rejected`() {
        val point = requireNotNull(captured())
        val receiver = CapturedCard(CardModel(), FollowCapture())
        val args = arrayOf<Any?>(CardModifier(), CardComposer(), 18)
        assertTrue(point.accepts(receiver, args))
        assertNull(captured(CapturedPage::class.java))
        val single = requireNotNull(captured(SingleModelCapture::class.java))
        assertTrue(single.accepts(SingleModelCapture(CardModel()), args))
        assertNull(captured(WrongConstructor::class.java))
        assertNull(captured(DoubleModel::class.java))
        receiver.model = null
        assertFalse(point.accepts(receiver, args))
        assertFalse(point.accepts(null, args))
        assertFalse(point.accepts(CapturedCard(CardModel(), FollowCapture()), arrayOf(null, CardComposer(), 0)))
        assertFalse(point.accepts(CapturedCard(CardModel(), FollowCapture()), arrayOf(CardModifier(), Any(), 0)))
    }

    @Test fun `three argument erased bridge changes first modifier flags and preserves original body and exceptions`() {
        val point = requireNotNull(captured())
        val registrar = PlayerPortTestRegistrar()
        val receiver = CapturedCard(CardModel(), FollowCapture())
        val decorated = CardModifier()
        registrar.exact("card", point.method.declaringClass, point.method.name, *point.method.parameterTypes) {
            before { if (point.accepts(instance, args)) point.rewrite(args, decorated, 12f) }
        }
        var calls = 0
        val original = CardModifier()
        val source = arrayOf<Any?>(original, CardComposer(), 0x7ff)
        val result = registrar.invoke("card", receiver, source) { args ->
            calls++
            assertEquals(0x7ff and 14.inv(), args[2])
            receiver.invoke(args[0]!!, args[1]!!, args[2]!!)
        }
        assertSame(decorated, result)
        assertSame(original, source[0])
        assertEquals(1, calls)
        assertEquals(1, receiver.calls)
        val error = IllegalStateException("host render")
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            registrar.invoke("card", receiver, source) { throw error }
        })
    }

    @Test fun `following five parameter card excludes outer page and never writes a default slot`() {
        val point = requireNotNull(HostOgvCardAdapter.following(FollowingUi::class.java, CardItem::class.java,
            Helper::class.java, CardModifier::class.java, CardComposer::class.java))
        assertEquals("card", point.method.name)
        assertNull(point.defaultsIndex)
        val args = arrayOf<Any?>(CardItem(), Helper(), CardModifier(), CardComposer(), 0x7ff)
        val decorated = CardModifier()
        assertTrue(point.accepts(null, args))
        point.rewrite(args, decorated, 12f)
        assertSame(decorated, args[2])
        assertEquals(0x7ff and (14 shl 6).inv(), args[4])
        assertEquals(5, args.size)
        assertNull(HostOgvCardAdapter.following(AmbiguousFollowing::class.java, CardItem::class.java,
            Helper::class.java, CardModifier::class.java, CardComposer::class.java))
    }

    @Test fun `following helper rename is resolved through complete single card ABI`() {
        val names = mapOf("androidx.compose.ui.Modifier" to CardModifier::class.java,
            "androidx.compose.runtime.Composer" to CardComposer::class.java,
            "com.bilibili.ogv.kmm.operation.following.BangumiFollowing\$b" to CardItem::class.java,
            "com.bilibili.ogv.kmm.operation.following.c" to FollowingUi::class.java)
        val family = HostOgvCardAdapter.resolve(names::get).families.single { it.id == "following" }
        assertTrue(family.applicable)
        assertEquals("card", family.points.single().method.name)
        assertNull(HostOgvCardAdapter.following(AmbiguousFollowing::class.java, CardItem::class.java,
            null, CardModifier::class.java, CardComposer::class.java))
    }

    @Test fun `legacy defaults clear only card modifier bits and allow a default null modifier`() {
        val point = requireNotNull(HostOgvCardAdapter.following(LegacyFollowing::class.java, CardItem::class.java,
            Helper::class.java, CardModifier::class.java, CardComposer::class.java))
        val args = arrayOf<Any?>(CardItem(), Helper(), null, CardComposer(), 0x7ff, 0x3f)
        assertTrue(point.accepts(null, args))
        point.rewrite(args, CardModifier(), 12f)
        assertEquals(0x3f and 4.inv(), args[5])
        args[2] = null
        assertFalse(point.accepts(null, args))
    }

    @Test fun `inline lazy card families remain visible partial with no hook to outer page`() {
        val names = mapOf("androidx.compose.ui.Modifier" to CardModifier::class.java,
            "androidx.compose.runtime.Composer" to CardComposer::class.java,
            "com.bilibili.ogv.operation3.module.operablecard.a" to CardModel::class.java,
            "Eo0.l" to InlinePage::class.java)
        val resolution = HostOgvCardAdapter.resolve(names::get)
        val family = resolution.families.single { it.id == "trending" }
        assertTrue(family.applicable)
        assertTrue(family.points.isEmpty())
        assertEquals("inline-without-modifier-input", family.reason)
        val coverage = HostCardHookCoverage(0, resolution.expected, 0, mapOf(family.id to family.reason))
        assertFalse(coverage.complete)
        assertEquals("0/1", coverage.label)
        assertTrue(HostCardHookCoverage.NONE.complete)
    }

    @Test fun `Pegasus resolves only declared bottom line draw over and preserves shadow draw and inherited no op`() {
        fun point(owner: Class<*>) = HostVideoCardDividerHooks.pegasus(owner, Decoration::class.java,
            DrawCanvas::class.java, Recycler::class.java, DrawState::class.java,
            DrawContext::class.java, DrawPaint::class.java, DrawRect::class.java)
        val draw = requireNotNull(point(Separator::class.java))
        assertEquals("onDrawOver", draw.name)
        assertEquals(Separator::class.java, draw.declaringClass)
        assertNull(point(ShadowOnly::class.java))
        assertNull(point(Any::class.java))
        val receiver = Separator(DrawContext())
        receiver.onDraw(DrawCanvas(), Recycler(), DrawState())
        assertEquals(1, receiver.shadows)
        assertEquals(0, receiver.lines)
        draw.invoke(receiver, DrawCanvas(), Recycler(), DrawState())
        assertEquals(1, receiver.shadows)
        assertEquals(1, receiver.lines)
    }

    class CardModel
    class FollowCapture
    class CardModifier
    class CardComposer
    class CardItem
    class Helper
    class CapturedCard(@JvmField var model: CardModel?, follow: FollowCapture) : Function3<Any, Any, Any, Any> {
        var calls = 0
        override fun invoke(modifier: Any, composer: Any, flags: Any): Any { calls++; return modifier }
    }
    class CapturedPage(@JvmField val models: List<CardModel>, follow: FollowCapture) : Function3<Any, Any, Any, Any> {
        override fun invoke(modifier: Any, composer: Any, flags: Any): Any = modifier
    }
    class SingleModelCapture(@JvmField val model: CardModel) : Function3<Any, Any, Any, Any> {
        override fun invoke(modifier: Any, composer: Any, flags: Any): Any = modifier
    }
    class WrongConstructor(@JvmField val model: CardModel, unrelated: String) : Function3<Any, Any, Any, Any> {
        override fun invoke(modifier: Any, composer: Any, flags: Any): Any = modifier
    }
    class DoubleModel(@JvmField val model: CardModel, follow: FollowCapture) : Function3<Any, Any, Any, Any> {
        @JvmField val another = CardModel()
        override fun invoke(modifier: Any, composer: Any, flags: Any): Any = modifier
    }
    class FollowingUi {
        companion object {
            @JvmStatic fun card(item: CardItem, helper: Helper, modifier: CardModifier, composer: CardComposer, flags: Int) {}
            @JvmStatic fun page(items: List<CardItem>, helper: Helper, modifier: CardModifier, composer: CardComposer, flags: Int) {}
        }
    }
    class LegacyFollowing {
        companion object { @JvmStatic fun card(item: CardItem, helper: Helper, modifier: CardModifier?, composer: CardComposer, flags: Int, defaults: Int) {} }
    }
    class AmbiguousFollowing {
        companion object {
            @JvmStatic fun card(item: CardItem, helper: Helper, modifier: CardModifier, composer: CardComposer, flags: Int) {}
            @JvmStatic fun second(item: CardItem, helper: Helper, modifier: CardModifier, composer: CardComposer, flags: Int) {}
        }
    }
    class InlinePage(@JvmField val cards: List<CardModel>) : Function4<Any, Any, Any, Any, Any> {
        override fun invoke(scope: Any, index: Any, composer: Any, flags: Any): Any = scope
    }
    class DrawCanvas
    class Recycler
    class DrawState
    class DrawContext
    class DrawPaint
    class DrawRect
    open class Decoration { open fun onDrawOver(canvas: DrawCanvas, recycler: Recycler, state: DrawState) {} }
    class Separator(@JvmField val context: DrawContext) : Decoration() {
        @JvmField val shadowPaint = DrawPaint()
        @JvmField val linePaint = DrawPaint()
        @JvmField val rect = DrawRect()
        @JvmField val disabled = false
        var shadows = 0
        var lines = 0
        fun onDraw(canvas: DrawCanvas, recycler: Recycler, state: DrawState) { shadows++ }
        override fun onDrawOver(canvas: DrawCanvas, recycler: Recycler, state: DrawState) { lines++ }
    }
    class ShadowOnly(@JvmField val context: DrawContext) : Decoration() {
        @JvmField val shadowPaint = DrawPaint()
        @JvmField val linePaint = DrawPaint()
        @JvmField val rect = DrawRect()
        @JvmField val disabled = false
        fun onDraw(canvas: DrawCanvas, recycler: Recycler, state: DrawState) {}
    }
}
