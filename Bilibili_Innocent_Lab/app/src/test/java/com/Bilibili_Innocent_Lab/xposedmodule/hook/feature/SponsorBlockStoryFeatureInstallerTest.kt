package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockStoryFeatureInstallerTest {
    private fun environment(process: String = "tv.danmaku.bili", loader: ClassLoader? = null,
        status: MutableList<Pair<String, String>> = mutableListOf()) = HookEnvironment(
        process, loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> },
        { key, value -> status += key to value })

    private val repository: () -> SponsorSegmentRepository = { throw AssertionError("repository must stay lazy") }

    @Test fun disabledAndSecondaryProcessesNeverReachHostLookupOrCreateTheRepository() {
        val unusable = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> = throw AssertionError("Unexpected lookup: $name")
        }
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            SponsorBlockStoryFeatureInstaller(false, true, repository).install(environment(loader = unusable)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            SponsorBlockStoryFeatureInstaller(true, true, repository).install(environment("tv.danmaku.bili:push", unusable)))
    }

    @Test fun missingLoaderKeepsTheHostUnchanged() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"),
            SponsorBlockStoryFeatureInstaller(true, false, repository).install(environment()))
    }

    @Test fun missingStoryStructureIsReportedOnItsOwnChannelWithoutTouchingTheDetailFeature() {
        val withoutPager = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.video.story.player.StoryPagerPlayer") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val status = mutableListOf<Pair<String, String>>()
        val result = SponsorBlockStoryFeatureInstaller(true, false, repository)
            .install(environment(loader = withoutPager, status = status))
        assertEquals(FeatureInstallResult.Skipped("missing-story-structure"), result)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE, (result as FeatureInstallResult.Skipped).reasonCode)
        assertEquals(listOf("sponsorblock_story_status" to "missing-story-structure"), status)
        assertNotEquals("sponsorblock", SponsorBlockStoryFeatureInstaller.ID)
    }

    @Test fun theRepositoryIsSharedByReferenceNotCreatedPerInstaller() {
        var created = 0
        val shared = lazy { created++; SponsorSegmentRepository(SponsorBlockClient("test")) }
        val detail = SponsorBlockFeatureInstaller(false, false, repository = { shared.value })
        val story = SponsorBlockStoryFeatureInstaller(false, false) { shared.value }
        detail.install(environment()); story.install(environment())
        assertEquals(0, created)
        assertSame(shared.value, shared.value)
        assertEquals(1, created)
        shared.value.close()
    }
}
