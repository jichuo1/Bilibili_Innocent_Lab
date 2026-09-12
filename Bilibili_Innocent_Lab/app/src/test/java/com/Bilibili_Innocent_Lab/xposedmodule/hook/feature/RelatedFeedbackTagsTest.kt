package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.feedbackfixture.*
import org.junit.Assert.*
import org.junit.Test

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
}
