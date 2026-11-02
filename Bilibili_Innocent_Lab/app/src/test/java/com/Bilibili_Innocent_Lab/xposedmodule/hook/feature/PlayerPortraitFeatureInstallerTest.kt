package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPortraitFeatureInstallerTest {
    class VisibilityTarget {
        fun setVisibility(value: Int) = Unit
    }

    private val recorder = PlayerPortTestRegistrar()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val point = VersionAdapter.HookPoint(VisibilityTarget::class.java.name, "setVisibility", listOf("int"))
    private val points = VersionAdapter.PlayerPortraitPoints(listOf(point))
    private val registrar = object : HookRegistrar by recorder {
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                             block: ModernMemberHookCreator.() -> Unit) {
            assertEquals(this@PlayerPortraitFeatureInstallerTest.point, point)
            recorder.exact(id, VisibilityTarget::class.java, "setVisibility", Int::class.javaPrimitiveType!!, block = block)
        }
    }
    private fun environment() = HookEnvironment(
        "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
        registrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )
    private fun stages() = evidence.map { (id, stage) ->
        assertEquals(PlayerPortraitFeatureInstaller.ID, id)
        stage
    }

    @Test fun `changed visibility is applied only after original method returns`() {
        PlayerPortraitFeatureInstaller(true, points).install(environment())
        var calls = 0
        recorder.invoke("player.portrait.visibility.0", VisibilityTarget(), arrayOf(View.VISIBLE)) { args ->
            calls++
            assertEquals(View.GONE, args.single())
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED), stages())
            null
        }
        assertEquals(1, calls)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages())
    }

    @Test fun `already hidden and invalid arguments never claim application`() {
        PlayerPortraitFeatureInstaller(true, points).install(environment())
        recorder.invoke("player.portrait.visibility.0", VisibilityTarget(), arrayOf(View.GONE))
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED), stages())
        evidence.clear()
        recorder.invoke("player.portrait.visibility.0", VisibilityTarget(), arrayOf(null)) { args ->
            assertEquals(null, args.single())
            null
        }
        assertTrue(evidence.isEmpty())
    }

    @Test fun `original failure preserves exception and does not claim application`() {
        PlayerPortraitFeatureInstaller(true, points).install(environment())
        val failure = IllegalStateException("host failure")
        val thrown = assertThrows(IllegalStateException::class.java) {
            recorder.invoke("player.portrait.visibility.0", VisibilityTarget(), arrayOf(View.VISIBLE)) { throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.ERROR), stages())
    }

    @Test fun `diagnostic sink failure cannot change visibility or original result`() {
        PlayerPortraitFeatureInstaller(true, points).install(environment().copy(
            runtimeEvidence = { _, _, _ -> error("diagnostics unavailable") }
        ))
        assertEquals("original", recorder.invoke("player.portrait.visibility.0", VisibilityTarget(), arrayOf(View.INVISIBLE)) {
            assertEquals(View.GONE, it.single())
            "original"
        })
    }

    @Test fun `disabled missing and non main process paths install no callbacks`() {
        assertTrue(PlayerPortraitFeatureInstaller(false, points).install(environment()) is FeatureInstallResult.Skipped)
        assertTrue(PlayerPortraitFeatureInstaller(true, null).install(environment()) is FeatureInstallResult.Skipped)
        assertTrue(PlayerPortraitFeatureInstaller(true, points).install(environment().copy(processName = "tv.danmaku.bili:web")) is FeatureInstallResult.Skipped)
        assertTrue(recorder.hooks.isEmpty())
        assertTrue(evidence.isEmpty())
    }

    @Test
    fun `forces every host visibility request to gone`() {
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.VISIBLE))
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.INVISIBLE))
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.GONE))
    }
}
