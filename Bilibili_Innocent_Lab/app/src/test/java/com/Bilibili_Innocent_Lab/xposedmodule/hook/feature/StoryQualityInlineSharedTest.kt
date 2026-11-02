package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import androidx.lifecycle.LifecycleOwner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.bilibili.app.comm.restrict.RestrictedMode
import com.bilibili.lib.accounts.BiliAccounts
import com.bilibili.player.tangram.basic.StoryPreferenceFixture
import com.bilibili.teenagersmode.TeenagersMode
import com.bilibili.video.story.player.StoryPlayer
import com.bilibili.video.story.player.quality.StoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tv.danmaku.biliplayerv2.IPlayerContainer
import tv.danmaku.biliplayerv2.PlayerSharingBundle
import tv.danmaku.biliplayerv2.service.FixturePlayableParams
import tv.danmaku.biliplayerv2.service.Video
import kotlin.coroutines.Continuation

/** 新版 onCreate 内联接入：使用实际 HostAccess 和 Hook DSL，不伪造共享准入。 */
class StoryQualityInlineSharedTest {
    class InlineOwner {
        val delegate = StoryPlayer()
        val quality = delegate.quality
        var container: IPlayerContainer? = null
        fun getCurrentPlayableParam() = delegate.getCurrentPlayableParam()
        fun onCreate(owner: LifecycleOwner?) = Unit
        fun release() = Unit
        fun onDestroy(owner: LifecycleOwner?) = Unit
    }

    @Before fun reset() {
        BiliAccounts.instance.loggedIn = true
        BiliAccounts.fail = false
        TeenagersMode.getInstance().enabled = false
        RestrictedMode.enabled = false
    }

    private inner class Harness(failId: String? = null) {
        val owner = InlineOwner().also {
            it.delegate.source.values += FixturePlayableParams(Video.DisplayParams(123, 456, "6"))
        }
        val port = PlayerPortTestRegistrar(failId)
        val runtime = StoryQualityRuntime(80)
        val hooks = StoryQualityFeatureHooks(runtime)
        val loader = javaClass.classLoader!!
        private fun point(owner: Class<*>, name: String, vararg args: Class<*>) =
            VersionAdapter.HookPoint(owner.name, name, args.map(Class<*>::getName))
        private val points = requireNotNull(StoryQualityLocator.direct(loader)).copy(
            integerDefaultMethod = point(StoryQualityFacade::class.java, "integerDefault"),
            policyMethod = point(StoryQualityFacade::class.java, "policy"),
            resourceSelectionMethod = point(StoryQualityFacade::class.java, "resource", com.bilibili.lib.media.resource.MediaResource::class.java),
            matchPreferenceMethod = point(StoryQualityService::class.java, "matchPreference", Continuation::class.java))
        private val roles = requireNotNull(StoryQualityLocator.resolve(loader, points)).copy(
            owner = InlineOwner::class.java,
            serviceField = InlineOwner::class.java.getDeclaredField("quality").apply { isAccessible = true },
            onCreate = InlineOwner::class.java.getDeclaredMethod("onCreate", LifecycleOwner::class.java),
            release = InlineOwner::class.java.getDeclaredMethod("release"),
            onDestroy = InlineOwner::class.java.getDeclaredMethod("onDestroy", LifecycleOwner::class.java))
        val access = requireNotNull(StoryQualityHostAccess.resolve(loader, roles))
        val stages = arrayListOf<FeatureRuntimeStage>()
        init {
            assertTrue(access.shared?.usesBuilder == true)
            assertTrue(hooks.install(HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), port,
                logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, _ -> },
                runtimeEvidence = { _, stage, _ -> stages += stage }), access))
        }
        fun build(shared: Boolean = true): IPlayerContainer {
            val builder = IPlayerContainer.Builder()
            builder.parameters.sharingBundle = if (shared) PlayerSharingBundle() else null
            if ("player.story.ready" !in port.hooks) return builder.build()
            return port.invoke("player.story.ready", builder) { builder.build() } as IPlayerContainer
        }
        fun create(block: () -> Unit = { owner.container = build() }) =
            port.invoke("player.story.create", owner, arrayOf(null)) { block(); Unit }
        fun wifi(): Any? = port.invoke("player.story.match", owner.quality, arrayOf(null)) {
            port.invoke("player.story.wifi", null, arrayOf(StoryQualityLocator.WIFI_KEY, 32)) { 32 }
        }
        fun manual(choice: StoryPreferenceFixture) = port.invoke("player.story.manual", owner.quality, arrayOf(choice, null)) { Unit }
    }

    @Test fun `inline build confirms first shared item without a later select`() {
        val h = Harness()
        h.create()
        assertEquals(80, h.wifi())
        assertFalse(FeatureRuntimeStage.ERROR in h.stages)
        assertFalse(h.runtime.inStory())
    }

    @Test fun `unshared foreign and ambiguous builds do not prove shared entry`() {
        for (kind in 0..2) {
            val h = Harness()
            h.create {
                h.owner.container = when (kind) {
                    0 -> h.build(shared = false)
                    1 -> { h.build(); IPlayerContainer.Builder().build() }
                    else -> { h.build(); h.build() }
                }
            }
            assertEquals(32, h.wifi())
        }
    }

    @Test fun `out of lifecycle and other thread builds cannot authorize an owner`() {
        val h = Harness()
        h.build()
        h.create { val thread = Thread { h.owner.container = h.build() }; thread.start(); thread.join() }
        assertEquals(32, h.wifi())
    }

    @Test fun `failed creation clears build proof before later callbacks`() {
        val h = Harness()
        try { h.create { h.owner.container = h.build(); error("fixture creation failed") }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(h.runtime.inStory())
        h.build()
        h.create { }
        assertEquals(32, h.wifi())
    }

    @Test fun `inline proof preserves manual fixed and automatic choices`() {
        for (choice in listOf(StoryPreferenceFixture.Auto, StoryPreferenceFixture.Fixed(64))) {
            val h = Harness()
            h.create(); h.manual(choice); h.build()
            assertEquals(32, h.wifi())
        }
    }

    @Test fun `inline proof retains strict item identity and admission checks`() {
        for (kind in 0..2) {
            reset()
            val h = Harness()
            if (kind == 0) BiliAccounts.instance.loggedIn = false
            if (kind == 1) RestrictedMode.enabled = true
            h.create()
            if (kind == 2) h.owner.delegate.source.values[0] = FixturePlayableParams(Video.DisplayParams(123, 456, "6"))
            assertEquals(32, h.wifi())
        }
    }

    @Test fun `optional builder hook failure keeps base Story hooks available`() {
        val h = Harness("player.story.ready")
        assertTrue(h.hooks.complete)
        h.create()
        assertEquals(32, h.wifi())
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }
}
