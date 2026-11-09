package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocatorTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocatorTest.KotlinReaders
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints
import org.junit.Assert.*
import org.junit.Test

class CommentClassicStyleFeatureInstallerTest {
    private fun environment(
        registrar: HookRegistrar = TestHookRegistrar,
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = CommentClassicStyleLocatorTest.loader(),
        evidence: (String, FeatureRuntimeStage, Int) -> Unit = { _, _, _ -> }
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> },
        runtimeEvidence = evidence
    )

    @Test fun `both style experiments are disabled on every supported query overload`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val result = CommentClassicStyleFeatureInstaller(true).install(environment(registrar) { id, stage, _ ->
            assertEquals(CommentClassicStyleFeatureInstaller.ID, id)
            stages += stage
        })
        assertEquals(FeatureInstallResult.Installed(2, complete = false), result)
        assertEquals(2, registrar.hooks.size)
        for ((id, entry) in registrar.hooks) {
            for (key in listOf("comment.next_appearance", "comment.next_appearance_experiment_3")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount)
                args[0] = key
                args[1] = true
                assertEquals(false, registrar.invoke(id, args = args) { error("experiment must be intercepted") })
            }
        }
        assertEquals(4, stages.count { it == FeatureRuntimeStage.OBSERVED })
        assertEquals(4, stages.count { it == FeatureRuntimeStage.APPLIED })
    }

    @Test fun `unrelated decisions and similarly named keys retain original behavior`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        CommentClassicStyleFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        for ((id, entry) in registrar.hooks) {
            for (key in listOf(null, "dd_enable_system_media_control", "comment.next_appearance_experiment_30", "comment.share_opt_enabled")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount)
                args[0] = key
                args[1] = true
                assertEquals(true, registrar.invoke(id, args = args) { forwarded ->
                    // 内容不变即可：SDK 的可写参数形状在入口持有自己的数组，不再把调用方的数组原样交给下游。
                    assertArrayEquals(args, forwarded)
                    true
                })
            }
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
    }

    @Test fun `disabled and secondary process paths do not register hooks`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            CommentClassicStyleFeatureInstaller(false).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            CommentClassicStyleFeatureInstaller(true).install(environment(registrar, process = "tv.danmaku.bili:download")))
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun `missing query boundary fails safely and incomplete registration is reported`() {
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            CommentClassicStyleFeatureInstaller(true).install(environment(loader = null)))
        assertEquals(FeatureInstallResult.Installed(1, complete = false),
            CommentClassicStyleFeatureInstaller(true).install(environment(PlayerPortTestRegistrar("comment.classic.dd.0"))))
    }

    @Test fun `kotlin direct and default calls disable all four containers and forward unrelated keys`() {
        val registrar = PlayerPortTestRegistrar()
        val installer = CommentClassicStyleFeatureInstaller(true)
        installer.install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        assertEquals(4, registrar.hooks.size)
        for ((id, entry) in registrar.hooks.filterKeys { it.startsWith("comment.classic.kotlin.") }) {
            for (key in listOf("comment.kntr.enabled", "comment.kntr.route.enabled", "comment.kntr.landscape.enabled", "comment.kntr.story.enabled")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = key; args[2] = true
                assertEquals(false, registrar.invoke(id, args = args) { error("must disable KMP selection") })
            }
            val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = "comment.kntr.enabled_extra"; args[2] = true
            assertEquals(true, registrar.invoke(id, args = args) { true })
        }
        installer.install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        assertEquals(4, registrar.hooks.size)
    }

    @Test fun `late adaptation fills missing family without replacing registered hooks or previews`() {
        val registrar = PlayerPortTestRegistrar()
        var cache: CommentClassicStylePoints? = null
        val loader = CommentClassicStyleLocatorTest.loader(kotlin = true, missingKotlin = true)
        val installer = CommentClassicStyleFeatureInstaller(true) { cache }
        installer.install(environment(registrar, loader = loader))
        assertTrue(installer.requiresAdaptationRetry)
        val initial = registrar.hooks.toMap()
        cache = CommentClassicStylePoints(emptyList(), listOf(CommentClassicStyleLocatorTest.point(KotlinReaders::class.java, "getBool")))
        installer.install(environment(registrar, loader = loader))
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(3, registrar.hooks.size)
        initial.forEach { (id, hook) -> assertSame(hook, registrar.hooks[id]) }
    }

    @Test fun `kotlin selection stays open when native style registration is incomplete`() {
        val registrar = PlayerPortTestRegistrar("comment.classic.dd.0")
        CommentClassicStyleFeatureInstaller(true).install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        registrar.hooks.filterKeys { it.startsWith("comment.classic.kotlin.") }.forEach { (id, entry) ->
            val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = "comment.kntr.enabled"; args[2] = true
            assertEquals(true, registrar.invoke(id, args = args) { true })
        }
    }
}
