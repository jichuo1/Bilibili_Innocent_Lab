package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import UnrelatedRootFeedbackCard
import bjx1
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.feedbackfixture.*
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType as HostCardType
import hkx1
import iox1
import org.junit.Assert.*
import org.junit.Test
import wmx1

class RelatedFeedbackTagsTest {
    fun tagIdIsCalibratedIndependentlyFromReasonIdAuthorAndRegion() {
        val access = checkNotNull(RelatedFeedbackTags.resolve(Card::class.java))
        val reasons = Reasons(listOf(Reason(10, 20, 30, 40, "tag"), Reason(30, 0, 0, 30, "region")))
        val card = Card(RelateCardType.AV, Menu(reasons, reasons))
        assertEquals(listOf(FeedbackTag(30, "tag")), access.read(card))
        assertTrue(access.matches(card, setOf(30), emptySet()))
        assertTrue(access.matches(card, emptySet(), setOf(30)))
        assertFalse(access.matches(card, setOf(10, 20, 40), emptySet()))
        assertFalse(access.matches(card, emptySet(), emptySet()))
        assertEquals(emptyList<FeedbackTag>(), access.read(Card(RelateCardType.AV, null)))
        assertEquals(emptyList<FeedbackTag>(), access.read(Any()))
    }

    fun unrelatedShapeAndMissingNamesAreRejected() {
        assertNull(RelatedFeedbackTags.resolve(Reasons::class.java))
        val access = checkNotNull(RelatedFeedbackTags.resolve(Card::class.java))
        val reasons = Reasons(listOf(Reason(1, 2, 0, 3, "x"), Reason(1, 2, 4, 3, " ")))
        assertTrue(access.read(Card(RelateCardType.AV, Menu(reasons, null))).isEmpty())
    }

    fun relocatedCardSharesTagSemanticsAcrossItsTwoMenus() {
        val access = requireNotNull(RelatedFeedbackTags.resolve(bjx1::class.java))
        val first = wmx1(11, 12, 13, 14, "first")
        val second = wmx1(21, 22, 23, 24, "second")
        val card = bjx1(HostCardType.AV, iox1(hkx1(listOf(first)), hkx1(listOf(first, second))))
        assertEquals(setOf(FeedbackTag(13, "first"), FeedbackTag(23, "second")), access.read(card).toSet())
        assertTrue(access.matches(card, emptySet(), setOf(23)))
        assertFalse(access.matches(card, setOf(11, 12, 14, 21, 22, 24), emptySet()))
        assertNull(RelatedFeedbackTags.resolve(UnrelatedRootFeedbackCard::class.java))
    }

    class TemplateRelateCard(@JvmField val t: HostCardType, @JvmField val m: TemplateRelateMenu)
    class TemplateRelateMenu(@JvmField val x: TemplateRelateBox, @JvmField val y: TemplateRelateBox)
    class TemplateRelateBox(@JvmField val items: List<TemplateRelateReason>)
    class TemplateRelateReason(
        @JvmField val a: Long, @JvmField val b: Long, @JvmField val tag: Long,
        @JvmField val d: Int, @JvmField val text: String
    )

    fun flattenedRelateCardsResolveDynamically() {
        for (cardName in listOf("vd72", "uh72")) {
            val menuName = "${cardName}m"
            val boxName = "${cardName}b"
            val reasonName = "${cardName}r"
            val loader = TestRenamingClassLoader(
                parent = javaClass.classLoader!!,
                templates = mapOf(
                    cardName to TemplateRelateCard::class.java,
                    menuName to TemplateRelateMenu::class.java,
                    boxName to TemplateRelateBox::class.java,
                    reasonName to TemplateRelateReason::class.java
                ),
                replacements = mapOf(
                    TemplateRelateMenu::class.java.name.replace('.', '/') to menuName,
                    TemplateRelateBox::class.java.name.replace('.', '/') to boxName,
                    TemplateRelateReason::class.java.name.replace('.', '/') to reasonName
                )
            )
            val cardClass = loader.loadClass(cardName)
            val tags = requireNotNull(RelatedFeedbackTags.resolve(cardClass))
            val r1 = loader.loadClass(reasonName).declaredConstructors.single().newInstance(1L, 2L, 777L, 4, "spam")
            val b1 = loader.loadClass(boxName).declaredConstructors.single().newInstance(listOf(r1))
            val m = loader.loadClass(menuName).declaredConstructors.single().newInstance(b1, b1)
            val card = cardClass.declaredConstructors.single().newInstance(HostCardType.AV, m)
            assertEquals(listOf(FeedbackTag(777L, "spam")), tags.read(card))
            assertTrue(tags.matches(card, setOf(777L), emptySet()))
            assertFalse(tags.matches(card, setOf(1L, 2L, 4L), emptySet()))
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (RelatedFeedbackTagsTest) -> Unit) {
            val s = RelatedFeedbackTagsTest()
            block(s)
        }
        runIsolated { s -> s.tagIdIsCalibratedIndependentlyFromReasonIdAuthorAndRegion() }
        runIsolated { s -> s.unrelatedShapeAndMissingNamesAreRejected() }
        runIsolated { s -> s.relocatedCardSharesTagSemanticsAcrossItsTwoMenus() }
        runIsolated { s -> s.flattenedRelateCardsResolveDynamically() }
    }
}
