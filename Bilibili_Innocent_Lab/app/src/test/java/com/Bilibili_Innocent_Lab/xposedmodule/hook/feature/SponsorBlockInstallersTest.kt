package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockInstallersTest {
    private open class LifecycleBase {
        open fun onResume() {}
    }

    private class LifecycleDetail : LifecycleBase() {
        override fun onResume() {}
        fun onResume(state: Boolean) {}
    }

    private fun environment(
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = null,
        status: MutableList<Pair<String, String>> = mutableListOf()
    ) = HookEnvironment(
        process,
        loader,
        HookPointRegistry(loader),
        TestHookRegistrar,
        { _, _ -> },
        { _, _ -> },
        { key, value -> status += key to value }
    )

    private val storyRepository: () -> SponsorSegmentRepository = { throw AssertionError("repository must stay lazy") }

    @Test
    fun verifySponsorBlockInstallers() {
        val unusable = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> = throw AssertionError("Unexpected lookup: $name")
        }

        // Detail installer: disabled and secondary processes never reach host lookup or create network workers
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            SponsorBlockFeatureInstaller(false, true).install(environment(loader = unusable))
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            SponsorBlockFeatureInstaller(true, true).install(environment("tv.danmaku.bili:push", unusable))
        )

        // Detail installer: missing loader and missing detail boundary keep the host unchanged
        assertEquals(
            FeatureInstallResult.Skipped("missing-class-loader"),
            SponsorBlockFeatureInstaller(true, false).install(environment())
        )
        val withoutDetail = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == SponsorBlockFeatureInstaller.DETAIL) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertEquals(
            FeatureInstallResult.Skipped("missing-detail-activity"),
            SponsorBlockFeatureInstaller(true, false).install(environment(loader = withoutDetail))
        )

        // Detail installer: lifecycle lookup targets the actual no-argument override
        val method = KavaMemberLookup.inheritedMethodOrNull(LifecycleDetail::class.java, "onResume")
        assertEquals(LifecycleDetail::class.java, method?.declaringClass)
        assertEquals(0, method?.parameterCount)

        // Detail installer: framework lifecycle dispatchers carry the actual Activity argument
        for (name in listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy")) {
            val fwMethod = KavaMemberLookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java)
            assertNotNull(fwMethod)
            assertEquals(Void.TYPE, fwMethod?.returnType)
            assertEquals(listOf(Activity::class.java), fwMethod?.parameterTypes?.toList())
        }

        // Story installer: disabled and secondary processes never reach host lookup or create repository
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            SponsorBlockStoryFeatureInstaller(false, true, storyRepository).install(environment(loader = unusable))
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            SponsorBlockStoryFeatureInstaller(true, true, storyRepository).install(environment("tv.danmaku.bili:push", unusable))
        )

        // Story installer: missing loader keeps the host unchanged
        assertEquals(
            FeatureInstallResult.Skipped("missing-class-loader"),
            SponsorBlockStoryFeatureInstaller(true, false, storyRepository).install(environment())
        )

        // Story installer: missing story structure is reported on its own channel without touching detail feature
        val withoutPager = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.video.story.player.StoryPagerPlayer") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val status = mutableListOf<Pair<String, String>>()
        val storyResult = SponsorBlockStoryFeatureInstaller(true, false, storyRepository)
            .install(environment(loader = withoutPager, status = status))
        assertEquals(FeatureInstallResult.Skipped("missing-story-structure"), storyResult)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE, (storyResult as FeatureInstallResult.Skipped).reasonCode)
        assertEquals(listOf("sponsorblock_story_status" to "missing-story-structure"), status)
        assertNotEquals("sponsorblock", SponsorBlockStoryFeatureInstaller.ID)

        // Shared repository: shared by reference, not created per installer
        var created = 0
        val shared = lazy { created++; SponsorSegmentRepository(SponsorBlockClient("test")) }
        val detail = SponsorBlockFeatureInstaller(false, false, repository = { shared.value })
        val story = SponsorBlockStoryFeatureInstaller(false, false) { shared.value }
        detail.install(environment())
        story.install(environment())
        assertEquals(0, created)
        assertSame(shared.value, shared.value)
        assertEquals(1, created)
        shared.value.close()
    }
}
