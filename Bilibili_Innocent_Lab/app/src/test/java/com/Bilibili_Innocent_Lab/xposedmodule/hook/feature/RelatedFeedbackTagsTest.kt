package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import UnrelatedRootFeedbackCard
import bjx1
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.feedbackfixture.*
import com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType as HostCardType
import hkx1
import iox1
import org.junit.Assert.*
import org.junit.Test
import wmx1

class RelatedFeedbackTagsTest {
    @Test fun tagIdIsCalibratedIndependentlyFromReasonIdAuthorAndRegion() {
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

    @Test fun unrelatedShapeAndMissingNamesAreRejected() {
        assertNull(RelatedFeedbackTags.resolve(Reasons::class.java))
        val access = checkNotNull(RelatedFeedbackTags.resolve(Card::class.java))
        val reasons = Reasons(listOf(Reason(1, 2, 0, 3, "x"), Reason(1, 2, 4, 3, " ")))
        assertTrue(access.read(Card(RelateCardType.AV, Menu(reasons, null))).isEmpty())
    }

    @Test fun relocatedCardSharesTagSemanticsAcrossItsTwoMenus() {
        val access = requireNotNull(RelatedFeedbackTags.resolve(bjx1::class.java))
        val first = wmx1(11, 12, 13, 14, "first")
        val second = wmx1(21, 22, 23, 24, "second")
        val card = bjx1(HostCardType.AV, iox1(hkx1(listOf(first)), hkx1(listOf(first, second))))
        assertEquals(setOf(FeedbackTag(13, "first"), FeedbackTag(23, "second")), access.read(card).toSet())
        assertTrue(access.matches(card, emptySet(), setOf(23)))
        assertFalse(access.matches(card, setOf(11, 12, 14, 21, 22, 24), emptySet()))
        assertNull(RelatedFeedbackTags.resolve(UnrelatedRootFeedbackCard::class.java))
    }
}
