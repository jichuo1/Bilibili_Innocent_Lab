package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class NativeFeedbackPanelTest {
    enum class Type { DEFAULT, DISLIKE }
    enum class Style { LIST }
    data class Item(val title: String, val type: Type, val onClick: (Any?) -> Any?)
    data class Group(val style: Style, val title: String, val items: List<Item>)
    class Card(val tag: String)
    class Click(val card: Card, val body: () -> Unit) : (Any?) -> Any? {
        override fun invoke(value: Any?): Any? { body(); return null }
    }
    class Wrapper(val child: Any)
    class Ambiguous(val first: Card, val second: Card)
    class StaticHolder { companion object { @JvmField var card: Card? = null } }

    @Test fun legacyItemsResolveTheirOwnCallbackCardWithoutRunningOfficialActions() {
        var official = 0
        val picked = mutableListOf<Card>()
        val card = Card("legacy")
        val result = panel(picked::add).legacyEntries(listOf(Wrapper(Click(card) { official++ })))
        assertEquals(listOf("legacy"), result.map { it.title })
        result.single().click()
        assertEquals(listOf(card), picked)
        assertEquals(0, official)
    }

    private fun injector(): FeedbackPanelInjector {
        val owner = Item::class.java
        return FeedbackPanelInjector(owner.declaredConstructors.single(), owner.getMethod("getTitle"),
            owner.getMethod("getType"), owner.getMethod("getOnClick"), owner,
            kotlin.jvm.functions.Function1::class.java, Type.DEFAULT)
    }

    private fun panel(onPicked: (Card) -> Unit): NativeFeedbackPanel {
        val group = Group::class.java
        val inject = injector()
        assertTrue(inject.isUsable)
        return NativeFeedbackPanel(inject, group.getMethod("getItems"), group.getMethod("getStyle"),
            group.getMethod("getTitle"), group.getMethod("copy", Style::class.java, String::class.java, List::class.java),
            Item::class.java.getMethod("getOnClick"), { it is Card }, { card ->
                listOf(checkNotNull(inject.newEntry((card as Card).tag) { onPicked(card) }))
            })
    }

    @Test fun itemCallbackBindsTheVideoAndOfficialCallbackIsUntouched() {
        var official = 0
        val selected = mutableListOf<Card>()
        val card = Card("tag")
        val originalItem = Item("official", Type.DISLIKE, Click(card) { official++ })
        val original = listOf(Group(Style.LIST, "native", listOf(originalItem)))
        val result = panel(selected::add).merge(original)!!
        val changed = result.single() as Group
        assertEquals(1, original.single().items.size)
        assertSame(originalItem, changed.items.first())
        assertEquals("native", changed.title)
        assertEquals(Type.DEFAULT, changed.items.last().type)
        changed.items.last().onClick(null)
        assertEquals(listOf(card), selected)
        assertEquals(0, official)
        originalItem.onClick(null)
        assertEquals(1, official)
        assertEquals(1, selected.size)
    }

    @Test fun recompositionDoesNotDuplicateEntriesAndANewPanelBindsItsOwnCard() {
        val selected = mutableListOf<Card>()
        val first = Card("one")
        val second = Card("two")
        fun groups(card: Card) = listOf(Group(Style.LIST, "", listOf(Item("official", Type.DISLIKE, Click(card) {}))))
        val panel = panel(selected::add)
        val original = groups(first)
        val result = panel.merge(original)!!
        assertSame(result, panel.merge(original))
        assertNull(panel.merge(result))
        val other = panel.merge(groups(second))!!
        ((result.single() as Group).items.last()).onClick(null)
        ((other.single() as Group).items.last()).onClick(null)
        assertEquals(listOf(first, second), selected)
    }

    @Test fun ambiguousCardsStaticFieldsCyclesAndExcessiveDepthFailClosed() {
        val first = Card("one")
        val second = Card("two")
        assertNull(FeedbackCardGraph.findUnique(listOf(Ambiguous(first, second))) { it is Card })
        StaticHolder.card = first
        assertNull(FeedbackCardGraph.findUnique(listOf(StaticHolder())) { it is Card })
        assertNull(FeedbackCardGraph.findUnique(listOf(Wrapper(Wrapper(Wrapper(Wrapper(Wrapper(first))))))) { it is Card })
        assertSame(first, FeedbackCardGraph.findUnique(listOf(Click(first) {}, Click(first) {})) { it is Card })
        StaticHolder.card = null
    }

    @Test fun mixedListsAndMissingCardPreserveNativePanel() {
        assertFalse(injector().accepts(listOf(Item("", Type.DEFAULT) { null }, "foreign")))
        assertNull(panel {}.merge(listOf(Group(Style.LIST, "", listOf(Item("", Type.DEFAULT) { null })))))
        assertNull(panel {}.merge(emptyList<Any>()))
    }
}
