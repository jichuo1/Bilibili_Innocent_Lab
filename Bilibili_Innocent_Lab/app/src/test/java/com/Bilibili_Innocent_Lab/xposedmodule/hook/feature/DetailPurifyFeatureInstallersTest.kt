package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailSpecialTagAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.app.view.v1.Tag
import com.bapis.bilibili.app.view.v1.ViewReply as LegacyViewReply
import com.bapis.bilibili.app.viewunite.common.ActivityGuidanceBar
import com.bapis.bilibili.app.viewunite.common.Module
import com.bapis.bilibili.app.viewunite.common.ModuleType
import com.bapis.bilibili.app.viewunite.common.Owner
import com.bapis.bilibili.app.viewunite.common.Vip
import com.bapis.bilibili.app.viewunite.v1.IntroductionTab
import com.bapis.bilibili.app.viewunite.v1.Tab
import com.bapis.bilibili.app.viewunite.v1.TabModule
import com.bapis.bilibili.app.viewunite.v1.ViewReply as UnitedViewReply
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.*
import org.junit.Test

class DetailPurifyFeatureInstallersTest {

    private class Harness(
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = DetailPurifyFeatureInstallersTest::class.java.classLoader,
        registrar: HookRegistrar = TestHookRegistrar
    ) {
        val statuses = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()
        val env = HookEnvironment(
            processName = process,
            classLoader = loader,
            hookPoints = HookPointRegistry(loader ?: DetailPurifyFeatureInstallersTest::class.java.classLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { key, _ -> errors += key },
            reportStatus = { channel, status -> statuses += channel to status },
            runtimeEvidence = { id, stage, _ -> evidence += id to stage },
            capabilityEvidence = { id, result -> capabilities += id to result }
        )
    }

    private object DummyStrategy : SerializationStrategy<Any>

    class TemplateSpecialTag
    class TemplateSpecialTagCell(@JvmField val uri: String)
    class TemplateSpecialTagModel(@JvmField val a: List<TemplateSpecialTagCell>, @JvmField val b: Boolean)
    object TemplateSpecialTagMapper {
        @JvmStatic fun b(tag: TemplateSpecialTag): TemplateSpecialTagModel = TemplateSpecialTagModel(emptyList(), false)
    }
    object TemplateBapisSpecialTagMapper {
        @JvmStatic fun newBuilder(tag: TemplateSpecialTag): TemplateSpecialTagModel = TemplateSpecialTagModel(emptyList(), false)
    }

    private fun invokeWire(
        registrar: PlayerPortTestRegistrar,
        pkg: String,
        svc: String,
        mtd: String,
        replyBytes: ByteArray
    ): ByteArray {
        var received: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { received = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = Descriptor(pkg, svc, mtd, Any::class, DummyStrategy)
        registrar.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), arrayOf(Service("host"), descriptor, ByteArray(0), handler, null)) { passed ->
            (passed[3] as Handler).onNext(replyBytes)
            null
        }
        return requireNotNull(received)
    }

    private fun wireEnginePoint() = KmpGrpcWireAdapter.point(
        GrpcEngine::class.java.getDeclaredMethod("a", Service::class.java, Descriptor::class.java, ByteArray::class.java, Handler::class.java, Any::class.java)
    )

    @Test
    fun verifiesDetailModuleViewAndPresentationInstallers() {
        val loader = requireNotNull(javaClass.classLoader)

        // DetailModulePurifyFeatureInstaller & DetailModuleReplyCleaner.
        run {
            val allKeys = DetailModulePurifyPolicy.preferenceKeys.toSet()
            fun cleaner(topicTags: Boolean = false) = requireNotNull(
                DetailModuleReplyCleaner.resolve(loader, DetailModulePurifyPolicy.targets, topicTags)
            )
            fun topic(name: String) = Tag(name, "https://m.bilibili.com/topic-detail?topic_id=1&topic_name=$name")
            fun plain(name: String) = Tag(name, "https://m.bilibili.com/video/BV1xx")

            Harness().also { h ->
                assertEquals(FeatureInstallResult.Skipped("disabled"), DetailModulePurifyFeatureInstaller(emptySet()).install(h.env))
                assertEquals(listOf("detail_module_status" to "disabled"), h.statuses)
                assertTrue(h.evidence.isEmpty())
            }
            Harness(process = "tv.danmaku.bili:web").also { h ->
                assertEquals(FeatureInstallResult.Skipped("non-main-process"), DetailModulePurifyFeatureInstaller(allKeys).install(h.env))
                assertTrue(h.statuses.isEmpty())
            }
            Harness().also { h ->
                val installer = DetailModulePurifyFeatureInstaller(allKeys)
                assertEquals(DetailModulePurifyPolicy.targets.map { it.capabilityId } + DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID, installer.capabilityIds)
                assertEquals(FeatureInstallResult.Installed(2, complete = true), installer.install(h.env))
                assertEquals(listOf("detail_module_status" to "success"), h.statuses)
                assertEquals(DetailModulePurifyPolicy.targets.map { it.capabilityId }.toSet() + DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID, h.capabilities.map { it.first }.toSet())
                assertTrue(h.capabilities.all { (_, r) -> r is FeatureInstallResult.Installed && r.complete })
            }
            Harness().also { h ->
                val single = setOf(DetailModulePurifyPolicy.targets.first().preferenceKey)
                DetailModulePurifyFeatureInstaller(single).install(h.env)
                assertEquals(listOf(DetailModulePurifyPolicy.targets.first().capabilityId), h.capabilities.map { it.first })
            }
            Harness().also { h ->
                val untouched = LegacyViewReply(emptySet())
                assertSame(untouched, cleaner().clean(untouched, h.env))
                assertTrue(h.evidence.isEmpty())
            }
            Harness().also { h ->
                val original = LegacyViewReply(setOf("Honor", "UgcSeason"))
                val updated = cleaner().clean(original, h.env) as LegacyViewReply
                assertNotSame(original, updated)
                assertTrue(original.hasHonor())
                assertTrue(original.hasUgcSeason())
                assertFalse(updated.hasHonor())
                assertFalse(updated.hasUgcSeason())
                val applied = h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
                assertTrue("detail_honor_removed" in applied)
                assertTrue("detail_ugc_season_removed" in applied)
                assertTrue(DetailModulePurifyFeatureInstaller.ID in applied)
                assertEquals(setOf("detail_honor_removed", "detail_ugc_season_removed"),
                    h.evidence.filter { it.second == FeatureRuntimeStage.OBSERVED }.map { it.first }.toSet())
            }
            Harness().also { h ->
                val updated = cleaner().clean(LegacyViewReply(setOf("Label")), h.env) as LegacyViewReply
                assertFalse(updated.hasLabel())
                assertFalse(updated.hasHonor())
                assertEquals(listOf("detail_up_vip_label_removed"), h.evidence.filter { it.second == FeatureRuntimeStage.OBSERVED }.map { it.first })
            }
            Harness().also { h ->
                val def = LegacyViewReply.getDefaultInstance()
                assertSame(def, cleaner().clean(def, h.env))
                assertTrue(h.evidence.isEmpty())
                val foreign = Any()
                assertSame(foreign, cleaner().clean(foreign, h.env))
                assertTrue(h.evidence.isEmpty())
            }
            Harness().also { h ->
                val original = LegacyViewReply(setOf("Honor"), true)
                assertSame(original, cleaner().clean(original, h.env))
                assertTrue("detail_module_copy_failed" in h.errors)
                assertTrue(DetailModulePurifyFeatureInstaller.ID to FeatureRuntimeStage.ERROR in h.evidence)
            }
            Harness().also { h ->
                val original = LegacyViewReply(listOf(plain("普通"), topic("AI IN ALL"), plain("尾部"), topic("第二个")))
                val updated = cleaner(topicTags = true).clean(original, h.env) as LegacyViewReply
                assertNotSame(original, updated)
                assertEquals(4, original.getTagCount())
                assertEquals(listOf("普通", "尾部"), updated.getTagList().map { it.getName() })
                assertTrue(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID in h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first })

                assertEquals(0, (cleaner(topicTags = true).clean(LegacyViewReply(listOf(topic("a"), topic("b"), topic("c"))), h.env) as LegacyViewReply).getTagCount())
                val plainList = LegacyViewReply(listOf(plain("一"), plain("二")))
                assertSame(plainList, cleaner(topicTags = true).clean(plainList, h.env))
                val emptyList = LegacyViewReply(emptyList<Tag>())
                assertSame(emptyList, cleaner(topicTags = true).clean(emptyList, h.env))
                val ignored = LegacyViewReply(listOf(topic("AI IN ALL")))
                assertSame(ignored, cleaner(topicTags = false).clean(ignored, h.env))
            }
            Harness().also { h ->
                val original = LegacyViewReply(setOf("Honor"), listOf(topic("AI IN ALL"), plain("留着")), false)
                val updated = cleaner(topicTags = true).clean(original, h.env) as LegacyViewReply
                assertFalse(updated.hasHonor())
                assertEquals(listOf("留着"), updated.getTagList().map { it.getName() })
                val applied = h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
                assertTrue("detail_honor_removed" in applied)
                assertTrue(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID in applied)
            }
            Harness().also { h ->
                val installer = DetailModulePurifyFeatureInstaller(setOf(DetailModulePurifyPolicy.TopicTags.PREFERENCE_KEY))
                assertEquals(listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID), installer.capabilityIds)
                assertEquals(FeatureInstallResult.Installed(2, complete = true), installer.install(h.env))
                assertEquals(listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID), h.capabilities.map { it.first })
            }
            assertNull(DetailModuleReplyCleaner.resolve(object : ClassLoader(null) {}, DetailModulePurifyPolicy.targets))

            // Wire-First without Java Moss.
            val modReg = PlayerPortTestRegistrar()
            val modLoader = TestRenamingClassLoader(parent = loader, hiddenNames = setOf("com.bapis.bilibili.app.view.v1.ViewMoss"))
            val modRes = DetailModulePurifyFeatureInstaller(
                enabledKeys = setOf(DetailModulePurifyPolicy.targets.first().preferenceKey),
                kmpGrpcWireEngine = wireEnginePoint()
            ).install(Harness(loader = modLoader, registrar = modReg).env)
            assertEquals(FeatureInstallResult.Installed(1, complete = false), modRes)
            val cleanedLegacyView = LegacyViewReply.parseFrom(
                invokeWire(modReg, "bilibili.app.view.v1", "View", "View", LegacyViewReply(setOf("Honor", "Label")).toByteArray())
            )
            assertFalse(cleanedLegacyView.hasHonor())
            assertTrue(cleanedLegacyView.hasLabel())
        }

        // DetailViewPurifyFeatureInstaller.
        run {
            val allKeys = DetailViewPurifyPolicy.preferenceKeys.toSet()
            Harness().also { h ->
                assertEquals(FeatureInstallResult.Skipped("disabled"), DetailViewPurifyFeatureInstaller(emptySet()).install(h.env))
                assertEquals(listOf("detail_view_purify_status" to "disabled"), h.statuses)
                assertTrue(h.capabilities.isEmpty())
            }
            Harness(process = "tv.danmaku.bili:web").also { h ->
                assertEquals(FeatureInstallResult.Skipped("non-main-process"), DetailViewPurifyFeatureInstaller(allKeys).install(h.env))
                assertTrue(h.statuses.isEmpty())
            }
            Harness(loader = object : ClassLoader(null) {}).also { h ->
                assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), DetailViewPurifyFeatureInstaller(allKeys).install(h.env))
                assertTrue("detail_view_purify_missing" in h.errors)
            }
            Harness(loader = null).also { h ->
                assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), DetailViewPurifyFeatureInstaller(allKeys).install(h.env))
            }
            assertEquals(DetailViewPurifyPolicy.rules.map { it.capabilityId }, DetailViewPurifyFeatureInstaller(allKeys).capabilityIds)
            assertEquals(
                listOf(DetailViewPurifyPolicy.HOT_BANNER.capabilityId),
                DetailViewPurifyFeatureInstaller(setOf(DetailViewPurifyPolicy.HOT_BANNER.preferenceKey)).capabilityIds
            )
            assertTrue(DetailViewPurifyFeatureInstaller(emptySet()).capabilityIds.isEmpty())

            val rule = DetailViewPurifyPolicy.STAFF_FOLLOW
            val all = rule.idNames.associateWith { 100 }
            assertTrue(DetailViewPurifyPolicy.usable(rule, all))
            rule.idNames.forEach { missing -> assertFalse(missing, DetailViewPurifyPolicy.usable(rule, all + (missing to 0))) }
            assertFalse(DetailViewPurifyPolicy.usable(rule, emptyMap()))

            assertEquals("vfl_avatar", DetailViewPurifyPolicy.STAFF_FOLLOW.itemIdName)
            assertEquals("fl_follow_container", DetailViewPurifyPolicy.STAFF_FOLLOW.hideIdName)
            assertNull(DetailViewPurifyPolicy.HOT_BANNER.itemIdName)
            assertNull(DetailViewPurifyPolicy.HOT_BANNER.hideIdName)
            assertEquals(listOf("tvTitle", "ivIcon", "endIconContainer", "card_view_background", "ivEndIcon", "tvSubtitle"), DetailViewPurifyPolicy.HOT_BANNER.requiredIdNames)

            val required = DetailViewPurifyPolicy.HOT_BANNER.requiredIdNames
            DetailViewPurifyPolicy.HOT_BANNER_MUST_NOT_MATCH.forEach { (component, missing) ->
                val blocking = missing.filter { it in required }
                assertTrue("$component blocking=$blocking", blocking.size >= 2)
            }
            assertFalse(DetailViewPurifyPolicy.STAFF_FOLLOW.hidesItemRoot)
            assertTrue(DetailViewPurifyPolicy.HOT_BANNER.hidesItemRoot)
            DetailViewPurifyPolicy.rules.forEach { r ->
                assertEquals(r.capabilityId, r.hideIdName == null, r.hidesItemRoot)
                r.idNames.forEach { name ->
                    assertTrue(name, name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                    assertFalse(name, name.contains("热"))
                }
                val spec = SettingsCatalog.byStorageKey[r.preferenceKey]
                assertNotNull(spec)
                val cap = DiagnosticCapabilityCatalog.byId[r.capabilityId]
                assertNotNull(cap)
                assertEquals(setOf(spec!!.id), cap!!.settingIds)
                assertEquals(DetailViewPurifyFeatureInstaller.ID, cap.parentId)
            }
            assertEquals("androidx.recyclerview.widget.RecyclerView", DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS)
            assertTrue(DetailViewPurifyPolicy.CHILD_ATTACH_LISTENER_CLASS.startsWith("androidx.recyclerview.widget.RecyclerView\$"))
            assertFalse(DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS.contains("com.bilibili"))
            assertFalse(DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS.contains("tv.danmaku"))
            assertTrue(DetailModulePurifyPolicy.preferenceKeys.toSet().intersect(DetailViewPurifyPolicy.preferenceKeys.toSet()).isEmpty())
        }

        // DetailUnitedPresentationPurifyFeatureInstaller + DetailSpecialTagAdapter flattened mapper retry.
        run {
            Harness().also { h ->
                assertEquals(FeatureInstallResult.Skipped("disabled"), DetailUnitedPresentationPurifyFeatureInstaller(false, false).install(h.env))
                assertEquals(listOf("detail_united_presentation_purify_status" to "disabled"), h.statuses)
                assertTrue(h.capabilities.isEmpty())
            }
            Harness(process = "tv.danmaku.bili:web").also { h ->
                assertEquals(FeatureInstallResult.Skipped("non-main-process"), DetailUnitedPresentationPurifyFeatureInstaller(true, true).install(h.env))
                assertTrue(h.statuses.isEmpty())
            }
            Harness(loader = object : ClassLoader(null) {}).also { h ->
                assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), DetailUnitedPresentationPurifyFeatureInstaller(true, true).install(h.env))
                assertTrue("detail_united_presentation_missing" in h.errors)
                assertTrue(h.statuses.single().second.startsWith("partial:0/2:"))
            }
            assertEquals(listOf(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID), DetailUnitedPresentationPurifyFeatureInstaller(true, false).capabilityIds)
            assertEquals(listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID), DetailUnitedPresentationPurifyFeatureInstaller(false, true).capabilityIds)
            val hot = DiagnosticCapabilityCatalog.byId.getValue(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID)
            val topic = DiagnosticCapabilityCatalog.byId.getValue(DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID)
            assertEquals(DetailUnitedPresentationPurifyPolicy.ID, hot.parentId)
            assertEquals(DetailUnitedPresentationPurifyPolicy.ID, topic.parentId)
            assertEquals(setOf(SettingsCatalog.byStorageKey.getValue(FeaturePreferences.REMOVE_DETAIL_HOT_BANNER).id), hot.settingIds)
            assertEquals(setOf(SettingsCatalog.byStorageKey.getValue(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS).id), topic.settingIds)

            val specialTagName = DetailSpecialTagAdapter.SPECIAL_TAG_CLASS
            val replacements = mapOf(
                TemplateSpecialTag::class.java.name.replace('.', '/') to specialTagName.replace('.', '/'),
                TemplateSpecialTagModel::class.java.name.replace('.', '/') to "v2p2"
            )
            val tagLoader = TestRenamingClassLoader(
                parent = loader,
                templates = mapOf(
                    specialTagName to TemplateSpecialTag::class.java,
                    "v2p2" to TemplateSpecialTagModel::class.java,
                    "kp12" to TemplateSpecialTagMapper::class.java,
                    "com.bapis.bilibili.FakeTagMapper" to TemplateBapisSpecialTagMapper::class.java
                ),
                replacements = replacements,
                hiddenPrefixes = setOf(DetailUnitedPresentationPurifyPolicy.HEADLINE_SERVICE_CLASS)
            )
            val kp12 = tagLoader.loadClass("kp12")
            val fakeBapis = tagLoader.loadClass("com.bapis.bilibili.FakeTagMapper")
            assertNotNull(DetailSpecialTagAdapter.inspectMapper(tagLoader, kp12))
            assertNull(DetailSpecialTagAdapter.inspectMapper(tagLoader, fakeBapis))
            val cached = VersionAdapter.HookPoint("kp12", "b", listOf(specialTagName))
            assertNotNull(DetailSpecialTagAdapter.resolve(tagLoader, cached))
            assertNull(DetailSpecialTagAdapter.resolve(tagLoader, cached.copy(className = "com.bapis.bilibili.FakeTagMapper")))

            var cachedRef: VersionAdapter.HookPoint? = null
            val tagRegistrar = PlayerPortTestRegistrar()
            val tagInstaller = DetailUnitedPresentationPurifyFeatureInstaller(
                hideHotSearchBadge = false, hideSpecialTopicTags = true, cachedSpecialTagMapperProvider = { cachedRef }
            )
            assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), tagInstaller.install(Harness(loader = tagLoader, registrar = tagRegistrar).env))
            assertTrue(tagInstaller.requiresAdaptationRetry)
            cachedRef = cached
            assertEquals(FeatureInstallResult.Installed(1, complete = true), tagInstaller.install(Harness(loader = tagLoader, registrar = tagRegistrar).env))
            assertFalse(tagInstaller.requiresAdaptationRetry)
            assertEquals(listOf("detail_united_presentation.special_topic"), tagRegistrar.hooks.keys.toList())
        }
    }

    @Test
    fun verifiesDetailUnitedModulePurifyCleanerTargetsAndWireFirst() {
        val loader = requireNotNull(javaClass.classLoader)
        val policy = DetailUnitedModulePurifyPolicy
        val allKeys = policy.preferenceKeys.toSet()

        fun cleaner(
            targets: Collection<DetailUnitedModulePurifyPolicy.ModuleTarget> = policy.moduleTargets,
            upVipLabel: Boolean = true
        ) = requireNotNull(UnitedModuleReplyCleaner.resolve(loader, targets, upVipLabel))

        fun reply(
            modules: List<Module> = emptyList(),
            vip: Vip? = null,
            extraTabs: List<TabModule> = emptyList(),
            failBuild: Boolean = false
        ): UnitedViewReply {
            val intro = TabModule("introduction", IntroductionTab("简介", modules))
            return UnitedViewReply(Tab("bg", listOf(intro) + extraTabs), Owner("UP", vip), failBuild)
        }

        fun modulesOf(reply: UnitedViewReply): List<String> =
            reply.tab.tabModuleList.filter { it.hasIntroduction() }.flatMap { it.introduction.modulesList }.map { it.label() }

        Harness().also { h ->
            assertEquals(FeatureInstallResult.Skipped("disabled"), DetailUnitedModulePurifyFeatureInstaller(emptySet()).install(h.env))
            assertEquals(listOf("detail_united_module_status" to "disabled"), h.statuses)
            assertTrue(h.evidence.isEmpty())
        }
        Harness(process = "tv.danmaku.bili:web").also { h ->
            assertEquals(FeatureInstallResult.Skipped("non-main-process"), DetailUnitedModulePurifyFeatureInstaller(allKeys).install(h.env))
            assertTrue(h.statuses.isEmpty())
        }
        Harness(loader = null).also { h ->
            assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), DetailUnitedModulePurifyFeatureInstaller(allKeys).install(h.env))
            assertTrue("detail_united_module_missing" in h.errors)
        }
        Harness(loader = object : ClassLoader(null) {}).also { h ->
            assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), DetailUnitedModulePurifyFeatureInstaller(allKeys).install(h.env))
            assertTrue("detail_united_module_missing" in h.errors)
        }
        Harness().also { h ->
            val installer = DetailUnitedModulePurifyFeatureInstaller(allKeys)
            assertEquals(policy.capabilityIds, installer.capabilityIds)
            assertEquals(FeatureInstallResult.Installed(2, complete = true), installer.install(h.env))
            assertEquals(listOf("detail_united_module_status" to "success"), h.statuses)
            assertEquals(policy.capabilityIds.toSet(), h.capabilities.map { it.first }.toSet())
        }
        Harness().also { h ->
            val installer = DetailUnitedModulePurifyFeatureInstaller(setOf(policy.HONOR.preferenceKey))
            assertEquals(listOf(policy.HONOR.capabilityId), installer.capabilityIds)
            installer.install(h.env)
            assertEquals(listOf(policy.HONOR.capabilityId), h.capabilities.map { it.first })
        }

        // Module removal & copy-on-change semantics.
        Harness().also { h ->
            val original = reply(listOf(
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.HONOR_VALUE, "荣誉"),
                Module(ModuleType.SPECIALTAG_VALUE, "话题"),
                Module(ModuleType.OWNER_VALUE, "UP行")
            ))
            val updated = cleaner(targets = listOf(policy.HONOR), upVipLabel = false).clean(original, h.env) as UnitedViewReply
            assertNotSame(original, updated)
            assertEquals(listOf("简介", "话题", "UP行"), modulesOf(updated))
            assertEquals(listOf("简介", "荣誉", "话题", "UP行"), modulesOf(original))
        }
        Harness().also { h ->
            val original = reply(listOf(
                Module(ModuleType.HONOR_VALUE, "荣誉"),
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.LIVE_ORDER_VALUE, "直播预约"),
                Module(ModuleType.UGC_SEASON_VALUE, "合辑"),
                Module(ModuleType.OWNER_VALUE, "UP行"),
                Module(ModuleType.SPECIALTAG_VALUE, "话题")
            ))
            assertEquals(listOf("简介", "UP行"), modulesOf(cleaner(upVipLabel = false).clean(original, h.env) as UnitedViewReply))
        }
        Harness().also { h ->
            val untouched = reply(listOf(Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介")))
            assertSame(untouched, cleaner().clean(untouched, h.env))
            assertTrue(h.evidence.none { it.second == FeatureRuntimeStage.APPLIED })
        }
        Harness().also { h ->
            cleaner(upVipLabel = false).clean(reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉"))), h.env)
            val applied = h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
            assertTrue(policy.HONOR.capabilityId in applied)
            assertFalse(policy.UGC_SEASON.capabilityId in applied)
            assertFalse(policy.TOPIC_TAGS.capabilityId in applied)
        }
        Harness().also { h ->
            val multiTab = reply(
                modules = listOf(Module(ModuleType.HONOR_VALUE, "荣誉")),
                extraTabs = listOf(TabModule("reply", null), TabModule("catalog", null))
            )
            val updated = cleaner(upVipLabel = false).clean(multiTab, h.env) as UnitedViewReply
            assertEquals(listOf("introduction", "reply", "catalog"), updated.tab.tabModuleList.map { it.name })
            assertFalse(updated.tab.tabModuleList[1].hasIntroduction())
        }
        Harness().also { h ->
            val noTab = UnitedViewReply(null, Owner("UP", null))
            assertSame(noTab, cleaner().clean(noTab, h.env))
            val def = UnitedViewReply.getDefaultInstance()
            assertSame(def, cleaner().clean(def, h.env))
            assertSame("not a reply", cleaner().clean("not a reply", h.env))
            assertTrue(h.evidence.isEmpty())
        }
        Harness().also { h ->
            val failOriginal = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")), failBuild = true)
            assertSame(failOriginal, cleaner(upVipLabel = false).clean(failOriginal, h.env))
            assertTrue("detail_united_module_copy_failed" in h.errors)
            assertTrue(h.evidence.any { it.second == FeatureRuntimeStage.ERROR })
        }

        // Guidance bar, video mentions, merchandise, UP VIP badge.
        run {
            val hotSearchUrl = "bilibili://search?keyword=%E6%B5%8B%E8%AF%95&from=apphotword_search_huangtiao"
            fun bannerCleaner(unmatched: MutableList<String> = mutableListOf()) =
                requireNotNull(UnitedModuleReplyCleaner.resolve(loader, emptyList(), upVipLabel = false, hotBanner = true) { unmatched += it })

            Harness().also { h ->
                val original = reply(listOf(Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"), Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "热搜横条", ActivityGuidanceBar(hotSearchUrl))))
                val updated = bannerCleaner().clean(original, h.env) as UnitedViewReply
                assertEquals(listOf("简介"), modulesOf(updated))
                assertTrue(DetailUnitedModulePurifyPolicy.HotBannerGuidanceBar.CAPABILITY_ID in h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first })

                val ordinary = reply(listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "活动条", ActivityGuidanceBar("https://www.bilibili.com/blackboard/activity.html"))))
                assertSame(ordinary, bannerCleaner().clean(ordinary, h.env))

                val unmatched = mutableListOf<String>()
                val c = bannerCleaner(unmatched)
                val unmatchedReply = reply(listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "活动条", ActivityGuidanceBar("bilibili://pegasus/channel/v2"))))
                repeat(3) { c.clean(unmatchedReply, h.env) }
                assertEquals(listOf("bilibili://pegasus/channel/v2"), unmatched)

                listOf(
                    "bilibili://search?from=apphotword_search_huangtiao",
                    "bilibili://search?from=app_comment_topic_search",
                    "bilibili://search?from=whatever_comes_next"
                ).forEach { url ->
                    val r = reply(listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "条", ActivityGuidanceBar(url))))
                    val u = bannerCleaner().clean(r, h.env) as UnitedViewReply
                    assertNotSame(url, r, u)
                    assertEquals(url, emptyList<String>(), modulesOf(u))
                }
                val emptyPayload = reply(listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "空载荷")))
                assertSame(emptyPayload, bannerCleaner().clean(emptyPayload, h.env))
            }

            Harness().also { h ->
                val mentionsCleaner = requireNotNull(UnitedModuleReplyCleaner.resolve(loader, listOf(DetailUnitedModulePurifyPolicy.VideoMentions.TARGET), upVipLabel = false))
                val original = reply(listOf(Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"), Module(ModuleType.VIDEO_MENTIONS_VALUE, "游戏推广卡")))
                assertEquals(listOf("简介"), modulesOf(mentionsCleaner.clean(original, h.env) as UnitedViewReply))

                DetailUnitedModulePurifyPolicy.defaultOnTargets.forEach { target ->
                    assertFalse(target.preferenceKey in DetailUnitedModulePurifyPolicy.preferenceKeys)
                    assertFalse(target.capabilityId in DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys))
                }
                assertTrue(DetailUnitedModulePurifyPolicy.VideoMentions.CAPABILITY_ID in DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys, videoMentions = true))
                assertTrue(DetailUnitedModulePurifyPolicy.Merchandise.CAPABILITY_ID in DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys, merchandise = true))

                val merchCleaner = requireNotNull(UnitedModuleReplyCleaner.resolve(loader, listOf(DetailUnitedModulePurifyPolicy.Merchandise.TARGET), upVipLabel = false))
                val merchOriginal = reply(listOf(Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"), Module(ModuleType.MERCHANDISE_VALUE, "好物商品卡"), Module(ModuleType.OWNER_VALUE, "UP行")))
                assertEquals(listOf("简介", "UP行"), modulesOf(merchCleaner.clean(merchOriginal, h.env) as UnitedViewReply))

                val bothModules = listOf(Module(ModuleType.VIDEO_MENTIONS_VALUE, "提及"), Module(ModuleType.MERCHANDISE_VALUE, "好物"), Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"))
                assertEquals(listOf("好物", "简介"), modulesOf(mentionsCleaner.clean(reply(bothModules), h.env) as UnitedViewReply))
                assertEquals(listOf("提及", "简介"), modulesOf(merchCleaner.clean(reply(bothModules), h.env) as UnitedViewReply))
            }

            Harness().also { h ->
                val vipOrig = reply(vip = Vip("年度大会员"))
                val vipUpdated = cleaner(targets = emptyList()).clean(vipOrig, h.env) as UnitedViewReply
                assertNotSame(vipOrig, vipUpdated)
                assertFalse(vipUpdated.owner.hasVip())
                assertNull(vipUpdated.owner.vip)
                assertEquals("UP", vipUpdated.owner.name)
                assertTrue(vipOrig.owner.hasVip())

                val noVip = reply(vip = null)
                assertSame(noVip, cleaner(targets = emptyList()).clean(noVip, h.env))

                val combinedOrig = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")), vip = Vip("大会员"))
                val combinedUpdated = cleaner().clean(combinedOrig, h.env) as UnitedViewReply
                assertEquals(emptyList<String>(), modulesOf(combinedUpdated))
                assertFalse(combinedUpdated.owner.hasVip())
                val applied = h.evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
                assertTrue(policy.HONOR.capabilityId in applied)
                assertTrue(DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID in applied)
            }
        }

        // Constants, fallback degradation, layer isolation, catalogs, and Wire-First.
        run {
            val values = policy.moduleTargets.map { target ->
                val field = ModuleType::class.java.getDeclaredField(target.typeConstant)
                assertEquals(target.typeConstant, Int::class.javaPrimitiveType, field.type)
                field.getInt(null)
            }
            assertEquals(values.size, values.toSet().size)
            assertEquals("SPECIALTAG_VALUE", policy.TOPIC_TAGS.typeConstant)

            Harness().also { h ->
                val bogus = DetailUnitedModulePurifyPolicy.ModuleTarget("detail_united_bogus", "bogus_key", "NO_SUCH_VALUE")
                val resolved = requireNotNull(UnitedModuleReplyCleaner.resolve(loader, listOf(policy.HONOR, bogus), upVipLabel = false))
                assertEquals(setOf(policy.HONOR.capabilityId), resolved.capabilityIds)
                assertEquals(emptyList<String>(), modulesOf(resolved.clean(reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉"))), h.env) as UnitedViewReply))
            }
            val drifted = DetailUnitedModulePurifyPolicy.ModuleTarget("detail_united_drifted", "drifted_key", "NOT_AN_INT_VALUE")
            assertNull(UnitedModuleReplyCleaner.resolve(loader, listOf(drifted), upVipLabel = false))
            assertNull(UnitedModuleReplyCleaner.resolve(object : ClassLoader(null) {}, policy.moduleTargets, upVipLabel = true))

            val unitedCaps = policy.capabilityIds.toSet()
            val legacyCaps = DetailModulePurifyPolicy.targets.map { it.capabilityId }.toSet() + DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID
            val viewCaps = DetailViewPurifyPolicy.rules.map { it.capabilityId }.toSet()
            val presCaps = setOf(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID, DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID)
            val allCaps = listOf(unitedCaps, legacyCaps, viewCaps, presCaps)
            assertEquals(allCaps.sumOf { it.size }, allCaps.flatten().toSet().size)
            assertTrue(policy.preferenceKeys.containsAll(DetailModulePurifyPolicy.preferenceKeys))
            assertTrue(DetailViewPurifyPolicy.HOT_BANNER.preferenceKey in policy.preferenceKeys)

            val primary = policy.TOPIC_TAGS
            assertEquals(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS, primary.preferenceKey)
            assertEquals("SPECIALTAG_VALUE", primary.typeConstant)
            listOf(policy.MODULE_CLASS, policy.MODULE_TYPE_CLASS, policy.INTRODUCTION_TAB_CLASS,
                policy.TAB_CLASS, policy.TAB_MODULE_CLASS, policy.OWNER_CLASS).forEach { name ->
                assertTrue("$name simple name too short", name.substringAfterLast('.').length > 2)
            }
            assertTrue(DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses.any { it.substringAfterLast('.').length <= 2 })

            (policy.moduleTargets.map { it.capabilityId to it.preferenceKey } +
                (DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID to DetailUnitedModulePurifyPolicy.UpVipLabel.PREFERENCE_KEY)).forEach { (cid, pkey) ->
                val spec = SettingsCatalog.byStorageKey[pkey]
                assertNotNull(spec)
                val cap = DiagnosticCapabilityCatalog.byId[cid]
                assertNotNull(cap)
                assertEquals(setOf(spec!!.id), cap!!.settingIds)
                assertEquals(DetailUnitedModulePurifyPolicy.ID, cap.parentId)
            }
            assertTrue(policy.MOSS_CLASS.startsWith("com.bapis.bilibili.app.viewunite.v1."))
            assertTrue(policy.REPLY_CLASS.startsWith("com.bapis.bilibili.app.viewunite."))
            listOf(policy.TAB_CLASS, policy.TAB_MODULE_CLASS, policy.INTRODUCTION_TAB_CLASS,
                policy.MODULE_CLASS, policy.MODULE_TYPE_CLASS, policy.OWNER_CLASS).forEach {
                assertTrue(it, it.startsWith("com.bapis.bilibili.app.viewunite."))
                assertFalse(it, it.contains(".view.v1."))
            }

            // Wire-First without Java Moss.
            val unitedReg = PlayerPortTestRegistrar()
            val unitedLoader = TestRenamingClassLoader(parent = loader, hiddenNames = setOf("com.bapis.bilibili.app.viewunite.v1.ViewMoss"))
            val unitedRes = DetailUnitedModulePurifyFeatureInstaller(
                enabledKeys = setOf(DetailUnitedModulePurifyPolicy.UpVipLabel.PREFERENCE_KEY),
                kmpGrpcWireEngine = wireEnginePoint()
            ).install(Harness(loader = unitedLoader, registrar = unitedReg).env)
            assertEquals(FeatureInstallResult.Installed(1, complete = false), unitedRes)
            val cleanedUnited = UnitedViewReply.parseFrom(
                invokeWire(unitedReg, "bilibili.app.viewunite.v1", "View", "View", UnitedViewReply(null, Owner("up-name", Vip("vip"))).toByteArray())
            )
            assertFalse(cleanedUnited.getOwner().hasVip())
            assertEquals("up-name", cleanedUnited.getOwner().getName())
        }
    }
}
