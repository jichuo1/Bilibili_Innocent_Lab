package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDurationRangeTest {

    @Test
    fun `empty range is disabled and unknown duration is always preserved`() {
        val range = VideoDurationRange(minSeconds = 0, maxSeconds = 0)

        assertFalse(range.isEnabled)
        assertFalse(range.shouldRemove(null))
        assertFalse(range.shouldRemove(0))
        assertFalse(range.shouldRemove(-1))
        assertFalse(range.shouldRemove(1))
    }

    @Test
    fun `minimum and maximum are exclusive removal boundaries`() {
        val range = VideoDurationRange(minSeconds = 30, maxSeconds = 600)

        assertTrue(range.isEnabled)
        assertTrue(range.shouldRemove(29))
        assertFalse(range.shouldRemove(30))
        assertFalse(range.shouldRemove(600))
        assertTrue(range.shouldRemove(601))
    }

    @Test
    fun `either boundary can be omitted`() {
        val minimumOnly = VideoDurationRange(minSeconds = 30, maxSeconds = 0)
        val maximumOnly = VideoDurationRange(minSeconds = 0, maxSeconds = 600)

        assertTrue(minimumOnly.shouldRemove(29))
        assertFalse(minimumOnly.shouldRemove(Long.MAX_VALUE))
        assertFalse(maximumOnly.shouldRemove(1))
        assertTrue(maximumOnly.shouldRemove(601))
    }

    @Test
    fun `negative or reversed configuration fails open`() {
        listOf(
            VideoDurationRange(minSeconds = -1, maxSeconds = 600),
            VideoDurationRange(minSeconds = 30, maxSeconds = -1),
            VideoDurationRange(minSeconds = 601, maxSeconds = 600)
        ).forEach { range ->
            assertFalse(range.isValid)
            assertFalse(range.isEnabled)
            assertFalse(range.shouldRemove(1))
            assertFalse(range.shouldRemove(10_000))
        }
    }

    @Test
    fun `home field reader follows only the pre-resolved getter and field`() {
        val getter = HomeItem::class.java.getMethod("getPlayerArgs")
        val field = PlayerArgs::class.java.getField("duration")

        assertEquals(
            90L,
            VideoDurationReader.fromField(HomeItem(PlayerArgs(90)), getter, field)
        )
        assertNull(VideoDurationReader.fromField(HomeItem(PlayerArgs(0)), getter, field))
    }

    @Test
    fun `home container reader prefers getter and falls back to annotated field`() {
        val containerGetter = HomeItem::class.java.getMethod("getPlayerArgs")
        val durationGetter = PlayerArgs::class.java.getMethod("getDuration")
        val durationField = PlayerArgs::class.java.getField("duration")

        assertEquals(
            120L,
            VideoDurationReader.fromContainer(
                HomeItem(PlayerArgs(duration = 90, getterDuration = 120)),
                containerGetter,
                durationGetter,
                durationField
            )
        )
        assertEquals(
            90L,
            VideoDurationReader.fromContainer(
                HomeItem(PlayerArgs(duration = 90, getterDuration = 0)),
                containerGetter,
                durationGetter,
                durationField
            )
        )
    }

    @Test
    fun `detail reader supports direct and explicit nested duration methods`() {
        val direct = OldRelate::class.java.getMethod("getDuration")
        val itemGetter = NewRelate::class.java.getMethod("getAv")
        val durationGetter = AvCard::class.java.getMethod("getDuration")
        val paths = VideoDurationReader.buildMethodPaths(
            directDurationGetters = listOf(direct),
            nestedChains = listOf(itemGetter to durationGetter)
        )

        assertEquals(120L, VideoDurationReader.fromMethods(OldRelate(120), paths))
        assertEquals(240L, VideoDurationReader.fromMethods(NewRelate(AvCard(240)), paths))
        assertNull(VideoDurationReader.fromMethods(NewRelate(null), paths))
    }

    private class HomeItem(private val playerArgs: PlayerArgs) {
        fun getPlayerArgs(): PlayerArgs = playerArgs
    }

    private class PlayerArgs(
        @JvmField val duration: Int,
        private val getterDuration: Int = duration
    ) {
        fun getDuration(): Int = getterDuration
    }

    private class OldRelate(private val duration: Long) {
        fun getDuration(): Long = duration
    }

    private class NewRelate(private val av: AvCard?) {
        fun getAv(): AvCard? = av
    }

    private class AvCard(private val duration: Long) {
        fun getDuration(): Long = duration
    }
}
