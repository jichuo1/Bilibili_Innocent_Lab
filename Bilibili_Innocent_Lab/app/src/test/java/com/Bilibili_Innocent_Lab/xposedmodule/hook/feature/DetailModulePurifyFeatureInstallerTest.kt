package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.view.v1.Tag
import com.bapis.bilibili.app.view.v1.ViewReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailModulePurifyFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val errors = mutableListOf<String>()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()

    private fun environment(process: String = "tv.danmaku.bili") = HookEnvironment(
        processName = process,
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { channel, status -> statuses += channel to status },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage },
        capabilityEvidence = { id, result -> capabilities += id to result }
    )

    private val allKeys = DetailModulePurifyPolicy.preferenceKeys.toSet()

    @Test fun `skips without touching the host when every subitem is off`() {
        val result = DetailModulePurifyFeatureInstaller(emptySet()).install(environment())
        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("detail_module_status" to "disabled"), statuses)
        assertTrue(evidence.isEmpty())
    }

    @Test fun `skips in non main processes`() {
        val result = DetailModulePurifyFeatureInstaller(allKeys)
            .install(environment(process = "tv.danmaku.bili:web"))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), result)
        assertTrue(statuses.isEmpty())
    }

    @Test fun `installs both routes and reports every enabled subitem`() {
        val installer = DetailModulePurifyFeatureInstaller(allKeys)
        assertEquals(
            DetailModulePurifyPolicy.targets.map { it.capabilityId } +
                DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID,
            installer.capabilityIds
        )
        val result = installer.install(environment())
        assertEquals(FeatureInstallResult.Installed(2, complete = true), result)
        assertEquals(listOf("detail_module_status" to "success"), statuses)
        assertEquals(
            DetailModulePurifyPolicy.targets.map { it.capabilityId }.toSet() +
                DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID,
            capabilities.map { it.first }.toSet()
        )
        assertTrue(capabilities.all { (_, r) ->
            r is FeatureInstallResult.Installed && r.complete
        })
    }

    @Test fun `only the enabled subitems are reported`() {
        val single = setOf(DetailModulePurifyPolicy.targets.first().preferenceKey)
        DetailModulePurifyFeatureInstaller(single).install(environment())
        assertEquals(
            listOf(DetailModulePurifyPolicy.targets.first().capabilityId),
            capabilities.map { it.first }
        )
    }

    // ---- cleaner ----

    private fun cleaner(topicTags: Boolean = false) = requireNotNull(
        DetailModuleReplyCleaner.resolve(
            requireNotNull(javaClass.classLoader),
            DetailModulePurifyPolicy.targets,
            topicTags
        )
    )

    private fun topic(name: String) =
        Tag(name, "https://m.bilibili.com/topic-detail?topic_id=1&topic_name=$name")

    private fun plain(name: String) = Tag(name, "https://m.bilibili.com/video/BV1xx")

    @Test fun `an untouched reply is returned as the same instance without evidence`() {
        val original = ViewReply(emptySet())
        val updated = cleaner().clean(original, environment())
        assertSame(original, updated)
        assertTrue(evidence.isEmpty())
    }

    @Test fun `populated fields are cleared on a copy and reported as applied`() {
        val original = ViewReply(setOf("Honor", "UgcSeason"))
        val updated = cleaner().clean(original, environment()) as ViewReply
        assertNotSame(original, updated)
        // 原实例不能被改动——改写必须发生在副本上。
        assertTrue(original.hasHonor())
        assertTrue(original.hasUgcSeason())
        assertFalse(updated.hasHonor())
        assertFalse(updated.hasUgcSeason())
        val applied = evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        assertTrue("detail_honor_removed" in applied)
        assertTrue("detail_ugc_season_removed" in applied)
        assertTrue(DetailModulePurifyFeatureInstaller.ID in applied)
        // 只有真的有内容的字段才报 OBSERVED。
        val observed = evidence.filter { it.second == FeatureRuntimeStage.OBSERVED }.map { it.first }
        assertEquals(setOf("detail_honor_removed", "detail_ugc_season_removed"), observed.toSet())
    }

    @Test fun `an unrelated populated field is left alone`() {
        val original = ViewReply(setOf("Label"))
        val updated = cleaner().clean(original, environment()) as ViewReply
        assertFalse(updated.hasLabel())
        assertFalse(updated.hasHonor())
        assertEquals(
            listOf("detail_up_vip_label_removed"),
            evidence.filter { it.second == FeatureRuntimeStage.OBSERVED }.map { it.first }
        )
    }

    /** 默认实例是进程级单例，改写它会污染整个宿主进程。 */
    @Test fun `the default instance is never rewritten`() {
        val default = ViewReply.getDefaultInstance()
        assertSame(default, cleaner().clean(default, environment()))
        assertTrue(evidence.isEmpty())
    }

    @Test fun `a foreign payload passes through untouched`() {
        val foreign = Any()
        assertSame(foreign, cleaner().clean(foreign, environment()))
        assertTrue(evidence.isEmpty())
    }

    /** 改写失败必须 fail-open 到宿主：交付原响应并记错误，不让详情页白屏。 */
    @Test fun `a failing copy keeps the original response and records an error`() {
        val original = ViewReply(setOf("Honor"), true)
        val env = environment()
        assertSame(original, cleaner().clean(original, env))
        assertTrue("detail_module_copy_failed" in errors)
        assertTrue(
            DetailModulePurifyFeatureInstaller.ID to FeatureRuntimeStage.ERROR in evidence
        )
    }

    // ---- 话题标签（列表筛选型） ----

    @Test fun `topic tags are removed while other tags are kept in order`() {
        val original = ViewReply(listOf(plain("普通"), topic("AI IN ALL"), plain("尾部"), topic("第二个")))
        val updated = cleaner(topicTags = true).clean(original, environment()) as ViewReply
        assertNotSame(original, updated)
        // 原实例不变。
        assertEquals(4, original.getTagCount())
        assertEquals(listOf("普通", "尾部"), updated.getTagList().map { it.getName() })
        val applied = evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        assertTrue(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID in applied)
    }

    /** 多个话题标签并排时一次全清（列表级操作天然覆盖）。 */
    @Test fun `every topic tag is removed even when several sit side by side`() {
        val original = ViewReply(listOf(topic("a"), topic("b"), topic("c")))
        val updated = cleaner(topicTags = true).clean(original, environment()) as ViewReply
        assertEquals(0, updated.getTagCount())
    }

    /** 一个话题标签都没有时不分配、不写回、不报证据。 */
    @Test fun `a tag list without topic tags is left untouched`() {
        val original = ViewReply(listOf(plain("一"), plain("二")))
        assertSame(original, cleaner(topicTags = true).clean(original, environment()))
        assertTrue(evidence.isEmpty())
    }

    @Test fun `an empty tag list is not rewritten`() {
        val original = ViewReply(emptyList<Tag>())
        assertSame(original, cleaner(topicTags = true).clean(original, environment()))
        assertTrue(evidence.isEmpty())
    }

    /** 子项没开时连解析都不做，标签一个不动。 */
    @Test fun `tags are ignored when the subitem is off`() {
        val original = ViewReply(listOf(topic("AI IN ALL")))
        assertSame(original, cleaner(topicTags = false).clean(original, environment()))
        assertTrue(evidence.isEmpty())
    }

    /** 字段清除与标签筛选共用同一次副本：一次响应最多复制一次。 */
    @Test fun `clearing fields and filtering tags share a single copy`() {
        val original = ViewReply(setOf("Honor"), listOf(topic("AI IN ALL"), plain("留着")), false)
        val updated = cleaner(topicTags = true).clean(original, environment()) as ViewReply
        assertFalse(updated.hasHonor())
        assertEquals(listOf("留着"), updated.getTagList().map { it.getName() })
        val applied = evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        assertTrue("detail_honor_removed" in applied)
        assertTrue(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID in applied)
    }

    @Test fun `the topic tag subitem is reported as an independent coverage unit`() {
        val installer = DetailModulePurifyFeatureInstaller(
            setOf(DetailModulePurifyPolicy.TopicTags.PREFERENCE_KEY)
        )
        assertEquals(
            listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID),
            installer.capabilityIds
        )
        val result = installer.install(environment())
        assertEquals(FeatureInstallResult.Installed(2, complete = true), result)
        assertEquals(
            listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID),
            capabilities.map { it.first }
        )
    }

    @Test fun `resolution degrades to null when the reply class is absent`() {
        val empty = object : ClassLoader(null) {}
        assertEquals(
            null,
            DetailModuleReplyCleaner.resolve(empty, DetailModulePurifyPolicy.targets)
        )
    }
}
