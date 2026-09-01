package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVerticalDetailFeatureInstallerTest {

    @Test
    fun `rewrites only a valid story route and preserves query`() {
        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=feed#page",
            HomeVerticalDetailFeatureInstaller.rewriteStoryUri(
                "bilibili://story/BV1xx411c7mD?from=feed#page"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.rewriteStoryUri(
                "bilibili://video/BV1xx411c7mD"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.rewriteStoryUri(
                "bilibili://story/not-a-video"
            )
        )
    }

    @Test
    fun `builds a conservative plan for a normal vertical video`() {
        val decision = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                goTo = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD?from=feed",
                param = "BV1xx411c7mD"
            )
        )

        val plan = (decision as HomeVerticalRouteDecision.Rewrite).plan
        assertEquals(CanonicalHomeVideoId.Kind.BV, plan.identity.kind)
        assertEquals("BV1xx411c7mD", plan.identity.value)
        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", plan.detailUri)
        assertTrue(plan.rewriteCardGoto)
        assertTrue(plan.rewriteGoTo)
        assertTrue(plan.rewriteUri)
    }

    @Test
    fun `keeps unsafe or conflicting vertical routes unchanged`() {
        val advertisement = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                bizType = "AD",
                cardGoto = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD"
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.UNSAFE_CONTENT_KIND,
            (advertisement as HomeVerticalRouteDecision.KeepOriginal).reason
        )

        val conflicting = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD",
                param = "BV1Q5411c7mD"
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.CONFLICTING_VIDEO_ID,
            (conflicting as HomeVerticalRouteDecision.KeepOriginal).reason
        )
    }

    @Test
    fun `mutates annotated concrete fields behind abstract getters and clears uri cache`() {
        val card = AnnotatedCard(
            cardGoto = "vertical_av",
            goTo = "vertical_av",
            uri = "bilibili://story/BV1xx411c7mD"
        )
        val snapshot = card.snapshot()
        val plan = (HomeVerticalDetailRoutePolicy.decide(snapshot) as
            HomeVerticalRouteDecision.Rewrite).plan
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            plan,
            readers()
        )

        assertEquals(HomeVerticalMutationResult.APPLIED, result)
        assertEquals("av", card.getCardGoto())
        assertEquals("av", card.getGoTo())
        assertEquals("bilibili://video/BV1xx411c7mD", card.getUri())
    }

    @Test
    fun `rolls back earlier route writes when a later writer fails`() {
        val card = ThrowingUriCard()
        val snapshot = card.snapshot()
        val plan = (HomeVerticalDetailRoutePolicy.decide(snapshot) as
            HomeVerticalRouteDecision.Rewrite).plan
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            plan,
            readers()
        )

        assertEquals(HomeVerticalMutationResult.ROLLED_BACK, result)
        assertEquals("vertical_av", card.getCardGoto())
        assertEquals("bilibili://story/BV1xx411c7mD", card.getUri())
    }

    @Test
    fun `route fallback only rewrites recent home identities and expires them`() {
        var now = 1_000L
        val registry = RecentHomeVideoRegistry(
            maxEntries = 2,
            ttlMillis = 100L,
            nowMillis = { now }
        )
        registry.register(CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.BV, "BV1xx411c7mD"))

        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=feed",
            registry.rewriteIfRegistered("bilibili://story/BV1xx411c7mD?from=feed")
        )
        assertNull(registry.rewriteIfRegistered("bilibili://story/BV1Q5411c7mD"))

        now += 101L
        assertNull(registry.rewriteIfRegistered("bilibili://story/BV1xx411c7mD"))
    }

    private fun AbstractRouteCard.snapshot(): HomeVerticalRouteSnapshot =
        HomeVerticalRouteSnapshot(
            cardGoto = getCardGoto(),
            goTo = getGoTo(),
            uri = getUri(),
            param = getParam()
        )

    private fun readers(): HomeVerticalReadAccessors = HomeVerticalReadAccessors(
        cardGoto = AbstractRouteCard::class.java.getMethod("getCardGoto"),
        goTo = AbstractRouteCard::class.java.getMethod("getGoTo"),
        uri = AbstractRouteCard::class.java.getMethod("getUri")
    )

    private abstract class AbstractRouteCard {
        abstract fun getCardGoto(): String
        abstract fun getGoTo(): String
        abstract fun getUri(): String?
        abstract fun getParam(): String?
    }

    private class AnnotatedCard(
        cardGoto: String,
        goTo: String,
        uri: String
    ) : AbstractRouteCard() {
        @field:SerializedName("card_goto")
        private var storedCardGoto: String = cardGoto

        @field:SerializedName("goto")
        private var storedGoTo: String = goTo

        @field:SerializedName("uri")
        private var storedUri: String = uri

        @Suppress("unused")
        private var stringUriCache: String? = uri

        override fun getCardGoto(): String = storedCardGoto
        override fun getGoTo(): String = storedGoTo
        override fun getUri(): String = stringUriCache ?: storedUri
        override fun getParam(): String = "BV1xx411c7mD"
    }

    private class ThrowingUriCard : AbstractRouteCard() {
        @field:SerializedName("card_goto")
        private var storedCardGoto: String = "vertical_av"

        private val originalUri = "bilibili://story/BV1xx411c7mD"

        override fun getCardGoto(): String = storedCardGoto
        override fun getGoTo(): String = "av"
        override fun getUri(): String = originalUri
        override fun getParam(): String = "BV1xx411c7mD"

        @Suppress("UNUSED_PARAMETER")
        fun setUri(value: String) {
            error("simulated host setter failure")
        }
    }
}
