package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailModulePurifyPolicyTest {

    @Test fun `targets are unique and derive their accessor names from the field name`() {
        val targets = DetailModulePurifyPolicy.targets
        assertEquals(4, targets.size)
        assertEquals(targets.size, targets.map { it.capabilityId }.distinct().size)
        assertEquals(targets.size, targets.map { it.preferenceKey }.distinct().size)
        assertEquals(targets.size, targets.map { it.fieldName }.distinct().size)
        targets.forEach {
            assertEquals("has${it.fieldName}", it.presence)
            assertEquals("clear${it.fieldName}", it.clear)
        }
    }

    /**
     * `vipActive` 在真机上**只有 get/clear、没有 has**（26 版一致）。
     * 没有 `has*` 就无法在不猜默认值的前提下判断"原来是否有内容"，
     * 而项目红线是不许拿"clear 没抛"当运行证据——所以它不能进白名单。
     */
    @Test fun `vipActive is deliberately excluded because it has no presence accessor`() {
        assertFalse(DetailModulePurifyPolicy.targets.any { it.fieldName == "VipActive" })
        val reply = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS)
        assertTrue(reply.methods.any { it.name == "getVipActive" })
        assertFalse(reply.methods.any { it.name == "hasVipActive" })
    }

    @Test fun `every target is wired into the settings catalog and the diagnostic catalog`() {
        val pairs = DetailModulePurifyPolicy.targets.map { it.preferenceKey to it.capabilityId } +
            (DetailModulePurifyPolicy.TopicTags.PREFERENCE_KEY to
                DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID)
        pairs.forEach { (preferenceKey, capabilityId) ->
            val spec = SettingsCatalog.byStorageKey[preferenceKey]
            assertTrue("missing catalog entry for $preferenceKey", spec != null)
            val capability = DiagnosticCapabilityCatalog.byId[capabilityId]
            assertTrue("missing capability for $capabilityId", capability != null)
            // 能力必须指回同一个设置 id，否则诊断页会把它算到别的开关头上。
            assertEquals(setOf(spec!!.id), capability!!.settingIds)
        }
    }

    /** 判别只看话题链接，不看标签文字——标签文字每个视频都不同。 */
    @Test fun `topic tags are matched by their topic link not by their text`() {
        val spec = DetailModulePurifyPolicy.TopicTags
        assertTrue(spec.isTopicTag(
            "https://m.bilibili.com/topic-detail?topic_id=1255303&topic_name=AI+IN+ALL"
        ))
        assertTrue(spec.isTopicTag("https://m.bilibili.com/topic-detail?topic_id=1"))
        assertFalse(spec.isTopicTag("https://m.bilibili.com/video/BV1xx"))
        assertFalse(spec.isTopicTag("bilibili://search?keyword=AI+IN+ALL"))
        assertFalse(spec.isTopicTag(null))
        assertFalse(spec.isTopicTag(""))
        // 访问器名必须由字段名派生，不许两处各写一遍。
        assertEquals("getTagCount", spec.presenceCount)
        assertEquals("getTagList", spec.list)
        assertEquals("clearTag", spec.clear)
        assertEquals("addAllTag", spec.addAll)
    }

    @Test fun `only no-arg clear methods may be invoked`() {
        val builder = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS + "\$Builder")
        val clearHonor = builder.methods.single { it.name == "clearHonor" }
        assertTrue(DetailModulePurifyPolicy.callable(clearHonor))
        val build = builder.methods.single { it.name == "build" }
        assertFalse("build() is not a clear method", DetailModulePurifyPolicy.callable(build))
        val newBuilder = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS)
            .methods.single { it.name == "newBuilder" }
        assertFalse("takes a parameter", DetailModulePurifyPolicy.callable(newBuilder))
    }

    @Test fun `the preference key list is the single source shared with the panel`() {
        // 面板顺序 = 四个 clear 型 + 列表筛选型；UI 不关心这个区别。
        assertEquals(
            DetailModulePurifyPolicy.targets.map { it.preferenceKey } +
                DetailModulePurifyPolicy.TopicTags.PREFERENCE_KEY,
            DetailModulePurifyPolicy.preferenceKeys
        )
        assertEquals(5, DetailModulePurifyPolicy.preferenceKeys.size)
        assertEquals(
            DetailModulePurifyPolicy.preferenceKeys.size,
            DetailModulePurifyPolicy.preferenceKeys.distinct().size
        )
        assertEquals(
            listOf(DetailModulePurifyPolicy.targets[1]),
            DetailModulePurifyPolicy.targetsFor(
                setOf(DetailModulePurifyPolicy.targets[1].preferenceKey)
            )
        )
        assertTrue(DetailModulePurifyPolicy.targetsFor(emptySet()).isEmpty())
    }
}
