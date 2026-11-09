package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import fixtures.privacy.videofavorite.pagerepo.Provider
import fixtures.privacy.videofavorite.pagerepo.Repo5
import fixtures.privacy.videofavorite.pagerepo.Repo6
import fixtures.privacy.videofavorite.pagerepo.Repo8
import fixtures.privacy.videofavorite.pagerepo.RepoTwoProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestPageFavoriteTest {
    private val registrar = PlayerPortTestRegistrar()
    private val queued = mutableListOf<Runnable>()

    @Before fun reset() {
        Repo5.calls.clear()
        Repo8.calls.clear()
        Repo5.fail = false
        Repo8.fail = false
        queued.clear()
    }

    private fun environment() =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader!!, HookPointRegistry(javaClass.classLoader!!), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })

    private fun source(repository: Class<*>, immediate: Boolean = true) =
        GuestPageFavorite(repository.name) { task -> if (immediate) task.run() else queued += task }

    /** What the host does when a page opens: builds a repository around a video-id provider; the hook sees the new instance. */
    private fun construct(repository: Any, aid: Long) {
        registrar.invoke("guest.video.favorite.page.repository.0", repository, arrayOf(null, null, null, Provider(aid), null, null))
    }

    @Test fun onBuildsWithFiveToggleMethodsTheFavoriteToggleIsTheFirstByName() {
        val page = source(Repo5::class.java)
        assertTrue(page.install(environment(), javaClass.classLoader!!))
        construct(Repo5(null, null, null, Provider(100L), null, null), 100L)

        assertTrue(page.apply(100L, true) {})

        assertEquals(listOf("n:100:true"), Repo5.calls)
    }

    @Test fun onBuildsWithEightToggleMethodsTheFavoriteToggleIsTheSecondByName() {
        val page = source(Repo8::class.java)
        assertTrue(page.install(environment(), javaClass.classLoader!!))
        construct(Repo8(null, null, null, Provider(100L), null, null), 100L)

        assertTrue(page.apply(100L, false) {})

        assertEquals(listOf("H:100:false"), Repo8.calls)
    }

    @Test fun onlyTheRepositoryOfTheTappedVideoIsTouched() {
        val page = source(Repo5::class.java)
        page.install(environment(), javaClass.classLoader!!)
        construct(Repo5(null, null, null, Provider(100L), null, null), 100L)
        construct(Repo5(null, null, null, Provider(200L), null, null), 200L)

        assertTrue(page.apply(200L, true) {})

        assertEquals(listOf("n:200:true"), Repo5.calls)
    }

    @Test fun aVideoWithoutAKnownRepositoryIsLeftToTheCaller() {
        val page = source(Repo5::class.java)
        page.install(environment(), javaClass.classLoader!!)
        construct(Repo5(null, null, null, Provider(100L), null, null), 100L)

        assertFalse(page.apply(999L, true) {})

        assertTrue(Repo5.calls.isEmpty())
    }

    @Test fun nothingIsCalledBeforeTheMainThreadRunsTheScheduledTask() {
        val page = source(Repo5::class.java, immediate = false)
        page.install(environment(), javaClass.classLoader!!)
        construct(Repo5(null, null, null, Provider(100L), null, null), 100L)

        assertTrue(page.apply(100L, true) {})
        assertTrue(Repo5.calls.isEmpty())
        queued.single().run()

        assertEquals(listOf("n:100:true"), Repo5.calls)
    }

    @Test fun aToggleThatThrowsHandsTheCallerItsFallbackExactlyOnce() {
        val page = source(Repo5::class.java)
        page.install(environment(), javaClass.classLoader!!)
        construct(Repo5(null, null, null, Provider(100L), null, null), 100L)
        Repo5.fail = true
        var failures = 0

        assertTrue(page.apply(100L, true) { failures++ })

        assertEquals(1, failures)
    }

    @Test fun aShapeWithAnUnknownNumberOfToggleMethodsIsNotRecognisedSoNoMethodIsGuessed() {
        assertNull(GuestPageFavorite.resolveShape(Repo6::class.java))
        val page = source(Repo6::class.java)
        assertFalse(page.install(environment(), javaClass.classLoader!!))
        assertFalse(page.apply(100L, true) {})
    }

    @Test fun twoFieldsOfTheProviderTypeMakeTheShapeAmbiguousSoItIsNotRecognised() {
        assertNull(GuestPageFavorite.resolveShape(RepoTwoProviders::class.java))
    }

    @Test fun theRecognisedShapeNamesTheMembersForBothBuildFamilies() {
        val five = GuestPageFavorite.resolveShape(Repo5::class.java)
        val eight = GuestPageFavorite.resolveShape(Repo8::class.java)
        assertNotNull(five)
        assertNotNull(eight)
        assertEquals("n", five!!.favorite.name)
        assertEquals("H", eight!!.favorite.name)
        assertEquals("a", five.aid.name)
        assertEquals("c", five.provider.name)
    }

    @Test fun aMissingRepositoryClassInstallsNothing() {
        val page = GuestPageFavorite("no.such.Repository") { it.run() }
        assertFalse(page.install(environment(), javaClass.classLoader!!))
        assertFalse(page.apply(100L, true) {})
    }
}
