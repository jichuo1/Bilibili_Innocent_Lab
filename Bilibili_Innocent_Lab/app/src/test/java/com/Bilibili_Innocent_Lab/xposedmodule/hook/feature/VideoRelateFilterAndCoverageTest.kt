package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.bapis.bilibili.app.archive.v1.Author
import com.bapis.bilibili.app.view.v1.Relate
import com.bapis.bilibili.app.viewunite.common.BadgeInfo
import com.bapis.bilibili.app.viewunite.common.CardBasicInfo
import com.bapis.bilibili.app.viewunite.common.Owner
import com.bapis.bilibili.app.viewunite.common.RelateAVCard
import com.bapis.bilibili.app.viewunite.common.RelateCard
import com.bapis.bilibili.app.viewunite.common.RelateGameCard
import com.bapis.bilibili.app.viewunite.common.RelateThreePoint
import com.bapis.bilibili.app.viewunite.common.Relates
import com.bilibili.app.gemini.ui.RunningUIComponent
import com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.ModernRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType
import com.bilibili.ship.theseus.united.page.intro.module.relate.r2
import com.google.protobuf.Internal
import org.junit.Assert.*
import org.junit.Test

class VideoRelateFilterAndCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private fun hookCount(points: VersionAdapter.VideoRelatePoints): Int =
        points.responseItemGetters.size + if (points.detailRelateService != null) 1 else 0

    private fun environment(
        statuses: MutableList<Pair<String, String>> = mutableListOf(),
        customLoader: ClassLoader = loader,
        registrar: HookRegistrar = TestHookRegistrar
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = customLoader,
        hookPoints = HookPointRegistry(customLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    private class AdaptedForwardingRegistrar(
        private val target: PlayerPortTestRegistrar,
        private val targetLoader: ClassLoader
    ) : HookRegistrar by target {
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val owner = Class.forName(point.className, false, targetLoader)
            val params = point.paramClassNames.orEmpty().map { Class.forName(it, false, targetLoader) }.toTypedArray()
            target.exact(id, owner, point.methodName, *params, block = block)
        }
    }

    private class ResponseFixture {
        var items: List<String> = listOf("original")
    }

    class FixtureRelateCard(private val cardGoto: String) {
        fun getGoto(): String = cardGoto
    }

    class FixtureRelateResponse(@JvmField var items: List<FixtureRelateCard>) {
        fun getItems(): List<FixtureRelateCard> = items
    }

    private open class WritebackCaches {
        @JvmField var memoizedHashCode = 0
        private var serializedSizeCache = -1
        fun getMemoizedSerializedSize(): Int = serializedSizeCache
        open fun setMemoizedSerializedSize(value: Int) { serializedSizeCache = value }
    }

    private open class WritebackReply(aliasedCopy: Boolean = false) : WritebackCaches() {
        @JvmField var items: Internal.ProtobufList<String> = ProtoItems(listOf("drop", "kept"), aliasedCopy)
        fun serializedSize(): Int {
            if (getMemoizedSerializedSize() == -1) setMemoizedSerializedSize(items.sumOf(String::length))
            return getMemoizedSerializedSize()
        }
        override fun hashCode(): Int {
            if (memoizedHashCode == 0) memoizedHashCode = items.hashCode()
            return memoizedHashCode
        }
    }

    private class FailingWritebackReply : WritebackReply() {
        override fun setMemoizedSerializedSize(value: Int) {
            if (value == -1) error("fixture cache reset failure")
            super.setMemoizedSerializedSize(value)
        }
    }

    private class UnknownWritebackReply {
        @JvmField var items: Internal.ProtobufList<String> = ProtoItems(listOf("drop", "kept"))
    }

    private class ProtoItems(
        initial: List<String>,
        private val aliasedCopy: Boolean = false,
        private var writable: Boolean = false
    ) : AbstractMutableList<String>(), Internal.ProtobufList<String> {
        private val values = initial.toMutableList()
        override val size get() = values.size
        override fun get(index: Int) = values[index]
        override fun add(index: Int, element: String) { check(writable); values.add(index, element) }
        override fun removeAt(index: Int): String { check(writable); return values.removeAt(index) }
        override fun set(index: Int, element: String): String { check(writable); return values.set(index, element) }
        override fun isModifiable() = writable
        override fun makeImmutable() { writable = false }
        override fun mutableCopyWithCapacity(capacity: Int): Internal.ProtobufList<String> {
            require(capacity >= size)
            return if (aliasedCopy) this else ProtoItems(values, writable = true)
        }
    }

    private class MaxSentinelReply {
        @JvmField var memoizedHashCode = 42
        private var serializedSizeCache = 16
        @JvmField var items: List<String> = ProtoItems(listOf("drop", "kept"))
        fun getMemoizedSerializedSize(): Int = serializedSizeCache
        fun setMemoizedSerializedSize(value: Int) { serializedSizeCache = value }

        companion object {
            private val DEFAULT = MaxSentinelReply().apply {
                serializedSizeCache = Int.MAX_VALUE
                memoizedHashCode = 0
            }
            @JvmStatic
            fun getDefaultInstance(): MaxSentinelReply = DEFAULT
        }
    }

    private fun writeBack(reply: Any, items: List<*>) = writeBackVideoRelateItems(
        reply,
        requireNotNull(Lookup.fieldOrNull(reply.javaClass, "items", includeSuperclasses = true)),
        items
    )

    private class CommercialCard(
        private val stock: Boolean = false,
        private val feedback: Boolean = false,
        private val failStock: Boolean = false,
        private val case: RelateCard.CardCase = RelateCard.CardCase.AV,
        private val reason: String = "3万点赞"
    ) : RelateCard() {
        override fun getCardCase() = case
        override fun getAv(): RelateAVCard = object : RelateAVCard() {
            override fun getRcmdReason(): BadgeInfo = object : BadgeInfo() {
                override fun getText() = reason
            }
        }
        override fun getBasicInfo(): CardBasicInfo = object : CardBasicInfo() {
            override fun getFromSourceType() = 0L
        }
        override fun hasCmStock(): Boolean {
            check(!failStock)
            return stock
        }
        override fun getThreePoint(): RelateThreePoint = object : RelateThreePoint() {
            override fun hasFeedback() = feedback
        }
    }

    private inner class CommercialHarness(
        hidden: Set<String>,
        enhancement: Boolean = false,
        strong: Boolean = false,
        typeReaders: Boolean = true,
        missingStockReader: Boolean = false,
        minDuration: Int = 0
    ) {
        val registrar = ContentFilterTestRegistrar(loader)
        val statuses = mutableListOf<String>()
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter {
                it.className == Relates::class.java.name
            },
            detailRelateService = null,
            // 本夹具是 AV；其余卡型的默认样本带推广理由，不属于这条反馈证据测试的输入。
            reasonChains = located.reasonChains.filter { it.steps.first().methodName == "getAv" },
            commercialEvidenceChains = located.commercialEvidenceChains.map { chain ->
                if (missingStockReader && chain.steps.singleOrNull()?.methodName == "hasCmStock") {
                    chain.copy(steps = listOf(chain.steps.single().copy(className = "missing.host.RelateCard")))
                } else chain
            },
            cardCaseGetters = if (typeReaders) located.cardCaseGetters else emptyList(),
            gotoGetters = if (typeReaders) located.gotoGetters else emptyList(),
            cardTypeGetters = if (typeReaders) located.cardTypeGetters else emptyList(),
            relateCardTypeGetters = if (typeReaders) located.relateCardTypeGetters else emptyList(),
            fromSourceTypeGetters = if (typeReaders) located.fromSourceTypeGetters else emptyList(),
            fromSourceTypeChains = if (typeReaders) located.fromSourceTypeChains else emptyList(),
            relateCardTypeValueGetters = if (typeReaders) located.relateCardTypeValueGetters else emptyList()
        )
        val result = VideoRelateFilterFeatureInstaller(
            hiddenTypes = hidden, minDurationSeconds = minDuration, maxDurationSeconds = 0,
            matchingEnhancementEnabled = enhancement, strongModeEnabled = strong, points = points
        ).install(HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, status -> statuses += status },
            runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
        ))

        fun filter(source: List<*>, receiver: Any = Relates()) = registrar.items("video.relate.response.0", receiver, source)
        fun count(id: String, stage: FeatureRuntimeStage = FeatureRuntimeStage.APPLIED) =
            runtime.filter { it.first == id && it.second == stage }.sumOf { it.third }
    }

    private inner class ServiceReasonHarness(
        strong: Boolean = true,
        keywords: String = "",
        hidden: Set<String> = emptySet()
    ) {
        val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
        val getter = requireNotNull(VersionAdapter.selectDetailRelateServiceReasonGetter(factory))
        private val service = VersionAdapter.DetailRelateServicePoint(
            VersionAdapter.HookPoint(
                factory.declaringClass.name, factory.name,
                paramClassNames = factory.parameterTypes.map { it.name }
            ),
            typeField = "a",
            typeGetter = VersionAdapter.HookPoint(r2::class.java.name, "y")
        )
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        val points = located.copy(
            responseItemGetters = emptyList(),
            detailRelateService = service,
            reasonChains = located.reasonChains + VersionAdapter.ReasonMethodChain(
                listOf(VersionAdapter.HookPoint(getter.declaringClass.name, getter.name))
            )
        )
        private val registrar = PlayerPortTestRegistrar()
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        init {
            val result = VideoRelateFilterFeatureInstaller(
                hidden, 0, 0,
                matchingEnhancementEnabled = true, strongModeEnabled = strong,
                reasonFilterEnabled = keywords.isNotEmpty(), rawReasonKeywords = keywords, points = points
            ).install(HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
            ))
            assertTrue(result is FeatureInstallResult.Installed)
        }
        var called = false
        val component = RunningUIComponent()
        fun run(item: r2) = registrar.invoke("video.relate.service", ModernRelateService(), arrayOf(item)) {
            called = true
            component
        }
    }

    private fun authorCard(name: String = "", mid: Long = 0L, unreadable: Boolean = false) = object : RelateCard() {
        override fun getBasicInfo() = object : CardBasicInfo() {
            override fun getAuthor() = object : Owner("", null) {
                override fun getTitle() = name
                override fun getMid(): Long {
                    check(!unreadable)
                    return mid
                }
            }
        }
    }

    private inner class AuthorHarness(rules: String) {
        private val registrar = ContentFilterTestRegistrar(loader)
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter { it.className == Relates::class.java.name },
            detailRelateService = null
        )
        init {
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawBlockedAuthors = rules, points = points
            ).install(HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
            ))
        }
        fun filter(source: List<*>) = registrar.items("video.relate.response.0", Relates(), source)
    }

    private fun gameTagCard(tag: String) = object : RelateCard() {
        override fun getCardCase() = CardCase.GAME
        override fun getGame() = object : RelateGameCard() {
            override fun getTagName() = tag
        }
    }

    private inner class TagHarness(rules: String) {
        private val registrar = ContentFilterTestRegistrar(loader)
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter { it.className == Relates::class.java.name },
            detailRelateService = null
        )
        init {
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawBlockedTags = rules, points = points
            ).install(HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
            ))
        }
        fun filter(source: List<*>) = registrar.items("video.relate.response.0", Relates(), source)
    }

    @Test
    fun verifyInstallConfigurationAndWriteback() {
        // normalizedTypeAndMissingDurationPublishSeparateCapabilityOutcomes
        run {
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
                .copy(directDurationGetters = emptyList(), durationChains = emptyList())
            val records = mutableListOf<FeatureInstallRecord>()
            val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
            FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"), minDurationSeconds = 30, maxDurationSeconds = 0, points = points
            )))
            assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
            assertEquals(
                FeatureSkipReason.MISSING_HOST_STRUCTURE,
                (records.last { it.id == "video_related_duration_filter" }.result as FeatureInstallResult.Skipped).reasonCode
            )
        }

        // normalizedTypeAndMissingPlayCountPublishSeparateCapabilityOutcomes
        run {
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
                .copy(playCountChains = emptyList())
            val records = mutableListOf<FeatureInstallRecord>()
            val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
            FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"), minDurationSeconds = 0, maxDurationSeconds = 0,
                minPlayCount = 10_000, maxPlayCount = 0, points = points
            )))
            assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
            assertEquals(
                FeatureSkipReason.MISSING_HOST_STRUCTURE,
                (records.last { it.id == "video_related_play_count_filter" }.result as FeatureInstallResult.Skipped).reasonCode
            )
        }

        // publish text chains cover the basic info desc and the legacy relate desc
        run {
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            val labels = points.publishTextChains.map { chain -> chain.steps.joinToString("->") { it.methodName } }
            assertTrue(labels.toString(), "getBasicInfo->getDesc" in labels)
            assertTrue(labels.toString(), "getDesc" in labels)
            assertEquals(
                points.publishTextChains,
                VersionAdapter.VideoRelatePoints.fromJson(points.toJson()).publishTextChains
            )
            // 老缓存没有这个键：降级成空表，不影响其余维度解析。
            val legacy = points.toJson().apply { remove("publish_text_chains") }
            assertEquals(
                emptyList<VersionAdapter.ReasonMethodChain>(),
                VersionAdapter.VideoRelatePoints.fromJson(legacy).publishTextChains
            )
        }

        // publish time filter alone installs and reports usable coverage
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            val records = mutableListOf<FeatureInstallRecord>()
            val env = environment(statuses).copy(installationEvidence = { records += it })
            FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawPublishTimeRule = "v1|after|r:2d", points = points
            )))
            assertTrue(records.last { it.id == "video_related_publish_time_filter" }.result is FeatureInstallResult.Installed)
            assertEquals(listOf("video_relate_filter_status" to "success"), statuses)
        }

        // missing publish text chains are partial instead of silently never matching
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
                .copy(publishTextChains = emptyList())
            val records = mutableListOf<FeatureInstallRecord>()
            val env = environment(statuses).copy(installationEvidence = { records += it })
            FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawPublishTimeRule = "v1|before|d:2021-06-04", points = points
            )))
            assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
            assertEquals(
                FeatureSkipReason.MISSING_HOST_STRUCTURE,
                (records.last { it.id == "video_related_publish_time_filter" }.result as FeatureInstallResult.Skipped).reasonCode
            )
            assertEquals(
                listOf("video_relate_filter_status" to "partial:missing-publish-time-accessor"),
                statuses
            )
        }

        // corrupt publish time rule alone leaves the feature disabled
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val result = VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawPublishTimeRule = "v1|between|d:2021-09-01|d:2021-07-08",
                points = VersionAdapter.locateVideoRelate(loader)
            ).install(environment(statuses))
            assertEquals(FeatureInstallResult.Skipped("invalid-publish-time-rule"), result)
        }

        // normalizes protobuf card type prefix and matches exact type
        run {
            assertTrue(VideoRelateFilterFeatureInstaller.shouldRemove("CARD_TYPE_GAME", setOf("game")))
            assertFalse(VideoRelateFilterFeatureInstaller.shouldRemove("BANGUMI_AV", setOf("AV")))
        }

        // matches old and new protocol evidence without coupling independent switches
        run {
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = setOf(2L), relateCardTypeValues = emptySet(), hiddenTypes = setOf("CM")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = setOf(2L), relateCardTypeValues = emptySet(), hiddenTypes = setOf("SPECIAL")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(3), hiddenTypes = setOf("ADVERTISEMENT")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(3, 5), hiddenTypes = setOf("SPECIAL")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = emptySet(),
                hiddenTypes = setOf("CM"), relateCardTypes = setOf("RELATE_CARD_TYPE_RESOURCE")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = setOf("RESOURCE"), fromSourceTypes = emptySet(), relateCardTypeValues = emptySet(), hiddenTypes = setOf("SPECIAL")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(4), hiddenTypes = setOf("GAME")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(4), hiddenTypes = setOf("SPECIAL")
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(10), hiddenTypes = setOf("SPECIAL")
            ))
            assertFalse(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = setOf(99L), relateCardTypeValues = setOf(99), hiddenTypes = setOf("CM", "GAME", "SPECIAL")
            ))
            assertFalse(VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(), fromSourceTypes = emptySet(), relateCardTypeValues = setOf(4), hiddenTypes = setOf("CM")
            ))
        }

        // strong mode distinguishes recognized and unknown card types
        run {
            assertTrue(VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(setOf("AV"), emptySet(), emptySet(), emptySet())
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(setOf("AI_CARD"), emptySet(), emptySet(), emptySet())
            ))
            assertTrue(VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(emptySet(), emptySet(), setOf(2L), emptySet())
            ))
            assertFalse(VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(setOf("FUTURE_UNSUPPORTED_CARD"), emptySet(), setOf(99L), setOf(99))
            ))
        }

        // response writeback replaces the verified internal list only
        run {
            val response = ResponseFixture()
            val replacement = arrayListOf("kept")
            val field = ResponseFixture::class.java.getDeclaredField("items").apply { isAccessible = true }
            assertTrue(writeBackVideoRelateItems(response, field, replacement))
            assertSame(replacement, response.items)
            assertFalse(writeBackVideoRelateItems(Any(), field, replacement))
        }

        // duration-only configuration installs while an empty range stays hook free
        run {
            val durationStatuses = mutableListOf<Pair<String, String>>()
            val disabledStatuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points)),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 30, maxDurationSeconds = 0, points = points
                ).install(environment(durationStatuses))
            )
            assertEquals(
                FeatureInstallResult.Skipped("disabled"),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0, points = null
                ).install(environment(disabledStatuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "success"), durationStatuses)
            assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
        }

        // play-count-only configuration installs while an empty range stays hook free
        run {
            val playCountStatuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points)),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                    minPlayCount = 10_000, maxPlayCount = 0, points = points
                ).install(environment(playCountStatuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "success"), playCountStatuses)
        }

        // missing play count paths do not disable an existing type filter
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(playCountChains = emptyList())
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points), complete = false),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = setOf("game"), minDurationSeconds = 0, maxDurationSeconds = 0,
                    minPlayCount = 10_000, maxPlayCount = 0, points = points
                ).install(environment(statuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "partial:missing-play-count-accessor"), statuses)
        }

        // missing duration paths do not disable an existing type filter
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
                .copy(directDurationGetters = emptyList(), durationChains = emptyList())
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points), complete = false),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = setOf("game"), minDurationSeconds = 30, maxDurationSeconds = 0, points = points
                ).install(environment(statuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "partial:missing-duration-accessor"), statuses)
        }

        // missing type getters do not disable an adapted duration filter
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(
                cardCaseGetters = emptyList(),
                gotoGetters = emptyList(),
                cardTypeGetters = emptyList(),
                relateCardTypeGetters = emptyList(),
                fromSourceTypeGetters = emptyList(),
                fromSourceTypeChains = emptyList(),
                relateCardTypeValueGetters = emptyList()
            )
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points), complete = false),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = setOf("game"), minDurationSeconds = 30, maxDurationSeconds = 0, points = points
                ).install(environment(statuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "partial:missing-type-getter"), statuses)
        }

        // custom reason filter requires enhancement and installs without type filters
        run {
            val enabledStatuses = mutableListOf<Pair<String, String>>()
            val disabledStatuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points)),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                    matchingEnhancementEnabled = true, reasonFilterEnabled = true,
                    rawReasonKeywords = "大家都在看", points = points
                ).install(environment(enabledStatuses))
            )
            assertEquals(
                FeatureInstallResult.Skipped("disabled"),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                    matchingEnhancementEnabled = false, reasonFilterEnabled = true,
                    rawReasonKeywords = "大家都在看", points = points
                ).install(environment(disabledStatuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "success"), enabledStatuses)
            assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
        }

        // missing reason paths do not disable existing type filtering
        run {
            val statuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(reasonChains = emptyList())
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points), complete = false),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = setOf("CM"), minDurationSeconds = 0, maxDurationSeconds = 0,
                    matchingEnhancementEnabled = true, reasonFilterEnabled = false, points = points
                ).install(environment(statuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "partial:missing-reason-accessor"), statuses)
        }

        // strong mode requires enhancement and keeps missing reasons as an aggressive signal
        run {
            val enabledStatuses = mutableListOf<Pair<String, String>>()
            val disabledStatuses = mutableListOf<Pair<String, String>>()
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(reasonChains = emptyList())
            assertEquals(
                FeatureInstallResult.Installed(hookCount(points), complete = false),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                    matchingEnhancementEnabled = true, strongModeEnabled = true, points = points
                ).install(environment(enabledStatuses))
            )
            assertEquals(
                FeatureInstallResult.Skipped("disabled"),
                VideoRelateFilterFeatureInstaller(
                    hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                    matchingEnhancementEnabled = false, strongModeEnabled = true, points = points
                ).install(environment(disabledStatuses))
            )
            assertEquals(listOf("video_relate_filter_status" to "partial:missing-reason-accessor"), enabledStatuses)
            assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
        }

        // protobufWritebackPreservesTypedImmutableSourceAndInvalidatesMessageCaches
        run {
            val reply = WritebackReply()
            val original = reply.items
            assertEquals(8, reply.serializedSize())
            val oldHash = reply.hashCode()
            assertTrue(writeBack(reply, listOf(original[1])))
            assertNotSame(original, reply.items)
            assertEquals(listOf("drop", "kept"), original)
            assertEquals(listOf("kept"), reply.items)
            assertFalse(original.isModifiable)
            assertFalse(reply.items.isModifiable)
            assertEquals(4, reply.serializedSize())
            assertEquals(listOf("kept").hashCode(), reply.hashCode())
            assertNotEquals(oldHash, reply.hashCode())
            assertTrue(runCatching { reply.items.clear() }.isFailure)
        }

        // removingEveryItemStillWritesACompatibleImmutableEmptyList
        run {
            val reply = WritebackReply()
            val original = reply.items
            assertEquals(8, reply.serializedSize())
            assertTrue(writeBack(reply, emptyList<String>()))
            assertTrue(reply.items.isEmpty())
            assertFalse(reply.items.isModifiable)
            assertEquals(0, reply.serializedSize())
            assertEquals(emptyList<String>().hashCode(), reply.hashCode())
            assertEquals(listOf("drop", "kept"), original)
        }

        // unknownMessageShapeAndAliasedCopyDoNotMutateTheOriginal
        run {
            val unknown = UnknownWritebackReply()
            val originalUnknown = unknown.items
            assertFalse(writeBack(unknown, emptyList<String>()))
            assertSame(originalUnknown, unknown.items)
            val aliased = WritebackReply(aliasedCopy = true)
            val originalAliased = aliased.items
            assertEquals(8, aliased.serializedSize())
            assertFalse(writeBack(aliased, emptyList<String>()))
            assertSame(originalAliased, aliased.items)
            assertEquals(listOf("drop", "kept"), aliased.items)
            assertEquals(8, aliased.serializedSize())
        }

        // failedCacheResetRollsBackTheOriginalFieldAndCachedValues
        run {
            val reply = FailingWritebackReply()
            val original = reply.items
            val originalSize = reply.serializedSize()
            val originalHash = reply.hashCode()
            assertFalse(writeBack(reply, emptyList<String>()))
            assertSame(original, reply.items)
            assertEquals(originalSize, reply.serializedSize())
            assertEquals(originalHash, reply.hashCode())
        }

        // javaUtilListWithProtobufRuntimeAndMaxValueSentinelWriteback
        run {
            val reply = MaxSentinelReply()
            val original = reply.items
            assertTrue(writeBack(reply, listOf("kept")))
            assertNotSame(original, reply.items)
            assertEquals(listOf("kept"), reply.items)
            assertFalse((reply.items as Internal.ProtobufList<*>).isModifiable)
            assertEquals(Int.MAX_VALUE, reply.getMemoizedSerializedSize())
            assertEquals(0, reply.memoizedHashCode)
            assertFalse(writeBack(MaxSentinelReply.getDefaultInstance(), emptyList<String>()))
        }

        // videoRelate response getter skips filtering under KotlinMossChannel raw and when hasThrowable
        run {
            val basePoints = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            val customPoints = basePoints.copy(
                responseItemGetters = listOf(VersionAdapter.HookPoint(FixtureRelateResponse::class.java.name, "getItems", emptyList())),
                cardCaseGetters = emptyList(),
                gotoGetters = listOf(VersionAdapter.HookPoint(FixtureRelateCard::class.java.name, "getGoto", emptyList())),
                cardTypeGetters = emptyList(),
                relateCardTypeGetters = emptyList(),
                fromSourceTypeGetters = emptyList(),
                fromSourceTypeChains = emptyList(),
                publishTextChains = emptyList()
            )
            val rawReg = PlayerPortTestRegistrar()
            val recording = AdaptedForwardingRegistrar(rawReg, loader)
            val res = VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                points = customPoints
            ).install(environment(registrar = recording))
            assertTrue(res is FeatureInstallResult.Installed)

            val cards = listOf(FixtureRelateCard("game"), FixtureRelateCard("av"))
            val resp = FixtureRelateResponse(cards)
            val rawResult = KotlinMossChannel.raw {
                rawReg.invoke("video.relate.response.0", resp) { resp.getItems() }
            } as List<*>
            assertEquals(2, rawResult.size)

            val normalResult = rawReg.invoke("video.relate.response.0", resp) { resp.getItems() } as List<*>
            assertEquals(1, normalResult.size)

            assertThrows(IllegalStateException::class.java) {
                rawReg.invoke("video.relate.response.0", resp) { throw IllegalStateException("host_boom") }
            }
        }
    }

    @Test
    fun verifyCommercialAndServiceEvidence() {
        // explicitCommercialPayloadWorksWithoutEnhancementOrStrongMode
        for (type in listOf("CM", "SPECIAL")) {
            val h = CommercialHarness(setOf(type))
            val normal = CommercialCard()
            val source = listOf(CommercialCard(stock = true), normal)
            assertEquals(type, listOf(normal), h.filter(source))
            assertEquals(2, source.size)
        }

        // normalFeedbackIsNotPromotedToCommercialEvidenceOutsideStrongMode
        for (enhancement in listOf(false, true)) {
            val h = CommercialHarness(setOf("CM"), enhancement = enhancement)
            val source = listOf(CommercialCard(feedback = true))
            assertSame(source, h.filter(source))
        }

        // strongModeDoesNotTreatOrdinaryFeedbackMenusAsCommercialPayloads
        run {
            val h = CommercialHarness(emptySet(), enhancement = true, strong = true)
            val coinReason = CommercialCard(feedback = true, reason = "10万投币")
            val likeReason = CommercialCard(feedback = true, reason = "5万点赞")
            val unknownReason = CommercialCard(feedback = true, reason = "")
            val namedLikeReason = CommercialCard(feedback = true, reason = "百万点赞")
            assertEquals(listOf(coinReason), h.filter(listOf(coinReason, likeReason, unknownReason, namedLikeReason)))
            assertEquals(3, h.count("video_related_strong_mode_enabled"))
            assertEquals(0, h.count("video_related_commercial_removed"))
        }

        // gameOnlyDoesNotAcquireCommercialRemovalFromAnotherDimension
        run {
            val h = CommercialHarness(setOf("GAME"))
            val source = listOf(CommercialCard(stock = true))
            assertSame(source, h.filter(source))
        }

        // payloadFallbackContinuesWhenTypeReadersAreMissing
        run {
            val h = CommercialHarness(setOf("CM"), typeReaders = false)
            assertTrue(h.result is FeatureInstallResult.Installed)
            val normal = CommercialCard()
            assertEquals(listOf(normal), h.filter(listOf(CommercialCard(stock = true), normal)))
        }

        // failingPayloadReaderDoesNotRemoveUnknownVideo
        run {
            val h = CommercialHarness(setOf("CM"))
            val source = listOf(CommercialCard(failStock = true))
            assertSame(source, h.filter(source))
        }

        // missingPayloadReaderReportsPartialButTypedCommercialFilteringContinues
        run {
            val h = CommercialHarness(setOf("CM"), missingStockReader = true)
            assertTrue(h.statuses.single().contains("missing-commercial-payload-accessor"))
            val normal = CommercialCard()
            assertEquals(listOf(normal), h.filter(listOf(CommercialCard(case = RelateCard.CardCase.CM), normal)))
        }

        // mixedTypedRemovalsReportOnlyTheirActualCauses
        run {
            val h = CommercialHarness(linkedSetOf("CM", "GAME", "COURSE"))
            val normal = CommercialCard()
            assertEquals(
                listOf(normal),
                h.filter(listOf(CommercialCard(case = RelateCard.CardCase.CM), CommercialCard(case = RelateCard.CardCase.GAME), normal))
            )
            assertEquals(1, h.count("video_related_commercial_removed"))
            assertEquals(1, h.count("video_related_game_removed"))
            assertEquals(0, h.count("video_related_course_removed"))
            assertEquals(1, h.count("video_related_commercial_removed", FeatureRuntimeStage.OBSERVED))
        }

        // unmatchedEnabledTypesRemainWithoutRuntimeEvidence
        run {
            val h = CommercialHarness(setOf("CM", "GAME"))
            val source = listOf(CommercialCard())
            assertSame(source, h.filter(source))
            assertFalse(h.runtime.any { it.first.startsWith("video_related_") })
        }

        // commercialPayloadUsesTheEnabledRemovalDimension
        for ((type, capability) in listOf("CM" to "video_related_commercial_removed", "SPECIAL" to "video_related_special_removed")) {
            val h = CommercialHarness(setOf(type))
            assertTrue(h.filter(listOf(CommercialCard(stock = true))).isEmpty())
            assertEquals(1, h.count(capability))
            assertEquals(
                1,
                h.runtime.filter { it.first.startsWith("video_related_") && it.second == FeatureRuntimeStage.APPLIED }
                    .sumOf { it.third }
            )
        }

        // strongOnlyPayloadDoesNotClaimCommercialOrMatchingHits
        run {
            val h = CommercialHarness(emptySet(), enhancement = true, strong = true)
            assertTrue(h.filter(listOf(CommercialCard(stock = true))).isEmpty())
            assertEquals(1, h.count("video_related_strong_mode_enabled"))
            assertEquals(0, h.count("video_related_commercial_removed"))
            assertEquals(0, h.count("video_related_matching_enhancement_enabled"))
        }

        // numericRemovalDoesNotClaimAnEnabledType
        run {
            val h = CommercialHarness(setOf("CM"), minDuration = 10_000)
            assertTrue(h.filter(listOf(CommercialCard())).isEmpty())
            assertEquals(1, h.count("video_related_duration_filter"))
            assertEquals(0, h.count("video_related_commercial_removed"))
        }

        // failedWritebackMarksOnlyMatchedCausesAndPreservesGetterFiltering
        run {
            val h = CommercialHarness(setOf("CM", "GAME"))
            val normal = CommercialCard()
            repeat(2) {
                assertEquals(listOf(normal), h.filter(listOf(CommercialCard(case = RelateCard.CardCase.CM), normal), Any()))
            }
            assertEquals(2, h.count("video_related_commercial_removed", FeatureRuntimeStage.ERROR))
            assertEquals(2, h.count("video_related_commercial_removed"))
            assertEquals(0, h.count("video_related_game_removed", FeatureRuntimeStage.ERROR))
            assertEquals(0, h.count("video_related_game_removed"))
        }

        // exactDtoReasonGetterSurvivesSerializationAndRejectsLegacyGuessing
        run {
            val h = ServiceReasonHarness()
            assertEquals("H", h.getter.name)
            val legacy = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(DetailRelateService::class.java))
            assertNull(VersionAdapter.selectDetailRelateServiceReasonGetter(legacy))
            val restored = VersionAdapter.VideoRelatePoints.fromJson(h.points.toJson())
            assertTrue(restored.reasonChains.any {
                it.steps.singleOrNull()?.className == r2::class.java.name && it.steps.single().methodName == "H"
            })
        }

        // strongServiceKeepsReadableCoinReasonsInsteadOfTreatingThemAsUnknown
        run {
            val h = ServiceReasonHarness()
            assertSame(h.component, h.run(r2("10万投币")))
            assertTrue(h.called)
            assertFalse(h.runtime.any { it.second == FeatureRuntimeStage.APPLIED || it.second == FeatureRuntimeStage.ERROR })
        }

        // strongServiceStillRemovesLikeCountsAndUnknownReasonsBeforeOriginal
        for (reason in listOf("5万点赞", "百万点赞", "")) {
            val h = ServiceReasonHarness()
            assertNull(h.run(r2(reason)))
            assertFalse(h.called)
            assertTrue(h.runtime.contains(Triple("video_related_strong_mode_enabled", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(h.runtime.any { it.first == "video_related_matching_enhancement_enabled" })
        }

        // customServiceRulesUseRealReasonsAndPreserveUnmatchedCards
        run {
            val matching = ServiceReasonHarness(strong = false, keywords = "10")
            assertNull(matching.run(r2("10万投币")))
            assertFalse(matching.called)
            assertTrue(matching.runtime.contains(Triple("video_related_reason_filter_enabled", FeatureRuntimeStage.APPLIED, 1)))
            val unmatched = ServiceReasonHarness(strong = false, keywords = "1000000000")
            assertSame(unmatched.component, unmatched.run(r2("10万投币")))
            assertTrue(unmatched.called)
        }

        // promotionMatchingUsesDtoReasonsWithoutAttributingThemToCommercialType
        run {
            val h = ServiceReasonHarness(strong = false, hidden = setOf("CM"))
            assertNull(h.run(r2("创作推广")))
            assertTrue(h.runtime.contains(Triple("video_related_matching_enhancement_enabled", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(h.runtime.any { it.first == "video_related_commercial_removed" })
        }

        // unreadableReasonsStaySafeInNormalModeAndRemainUnknownInStrongMode
        run {
            val item = object : r2() { override fun H(): String = error("unreadable reason") }
            val normal = ServiceReasonHarness(strong = false, keywords = "10")
            assertSame(normal.component, normal.run(item))
            val strong = ServiceReasonHarness()
            assertNull(strong.run(item))
            assertFalse(strong.called)
        }

        // verifiedDtoServiceBlockAttributesOnlyTheMatchedTypeBeforeCallingOriginal
        run {
            val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
            val points = requireNotNull(VersionAdapter.locateVideoRelate(loader)).copy(
                detailRelateService = VersionAdapter.DetailRelateServicePoint(
                    VersionAdapter.HookPoint(
                        factory.declaringClass.name, factory.name,
                        paramClassNames = factory.parameterTypes.map { it.name }
                    ),
                    typeField = "a", typeGetter = VersionAdapter.HookPoint(r2::class.java.name, "y")
                )
            )
            val registrar = PlayerPortTestRegistrar()
            val evidence = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
            val result = VideoRelateFilterFeatureInstaller(setOf("CM", "GAME"), 0, 0, points = points)
                .install(HookEnvironment(
                    "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                    { _, _ -> }, { _, _ -> }, { _, _ -> },
                    runtimeEvidence = { id, stage, count -> evidence += Triple(id, stage, count) }
                ))
            assertTrue(result is FeatureInstallResult.Installed)
            val item = r2()
            requireNotNull(Lookup.fieldOrNull(r2::class.java, "a")).set(item, RelateCardType.CM)
            var called = false
            assertNull(registrar.invoke("video.relate.service", ModernRelateService(), arrayOf(item)) {
                called = true
                RunningUIComponent()
            })
            assertFalse(called)
            assertTrue(evidence.contains(Triple("video_related_commercial_removed", FeatureRuntimeStage.OBSERVED, 1)))
            assertTrue(evidence.contains(Triple("video_related_commercial_removed", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(evidence.any { it.first == "video_related_game_removed" })
        }
    }

    @Test
    fun verifyAuthorAndTagCoverage() {
        // currentAndLegacyAuthorPathsSurviveSerialization
        run {
            val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            val restored = VersionAdapter.VideoRelatePoints.fromJson(located.toJson())
            assertEquals(
                setOf(listOf("getAuthor", "getName"), listOf("getBasicInfo", "getAuthor", "getTitle")),
                restored.authorNameChains.map { it.steps.map { point -> point.methodName } }.toSet()
            )
            assertEquals(
                setOf(listOf("getAuthor", "getMid"), listOf("getBasicInfo", "getAuthor", "getMid")),
                restored.authorMidChains.map { it.steps.map { point -> point.methodName } }.toSet()
            )
        }

        // nestedOwnerMidRemovesOnlyTheExactPublicUid
        run {
            val target = authorCard("public author", 208259L)
            val prefix = authorCard("another author", 20825L)
            val suffix = authorCard("another author", 1208259L)
            val h = AuthorHarness("208259")
            val source = listOf(target, prefix, suffix)
            assertEquals(listOf(prefix, suffix), h.filter(source))
            assertEquals(3, source.size)
            assertTrue(h.runtime.contains(Triple("video_related_author_block", FeatureRuntimeStage.OBSERVED, 1)))
            assertTrue(h.runtime.contains(Triple("video_related_author_block", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(h.runtime.any { it.first == "video_related_tag_block" || it.second == FeatureRuntimeStage.ERROR })
        }

        // nestedOwnerDisplayNameMatchesExactlyWithoutUsingCardTitles
        run {
            val target = authorCard("  PublicAuthor  ", 208259L)
            val prefix = authorCard("PublicAuthorExtra", 10L)
            val suffix = authorCard("OtherPublicAuthor", 20L)
            assertEquals(listOf(prefix, suffix), AuthorHarness("publicauthor").filter(listOf(target, prefix, suffix)))
            val source = listOf(target)
            val h = AuthorHarness("20825")
            assertSame(source, h.filter(source))
            assertFalse(h.runtime.any { it.first == "video_related_author_block" && it.second == FeatureRuntimeStage.APPLIED })
        }

        // absentAndUnreadableOwnersRemainWhileReadableNamesCanStillMatch
        run {
            val noOwner = RelateCard()
            val unreadableOwner = object : RelateCard() {
                override fun getBasicInfo(): CardBasicInfo = error("unreadable basic info")
            }
            val unavailableMid = authorCard(unreadable = true)
            val zeroMid = authorCard(mid = 0L)
            val negativeMid = authorCard(mid = -1L)
            val source = listOf(noOwner, unreadableOwner, unavailableMid, zeroMid, negativeMid)
            assertSame(source, AuthorHarness("208259,0,-1").filter(source))
            assertEquals(emptyList<Any>(), AuthorHarness("publicauthor").filter(listOf(authorCard("PublicAuthor", unreadable = true))))
        }

        // directLegacyAuthorNameAndMidContinueToFilter
        run {
            val target = object : Relate() {
                override fun getAuthor() = Author(208259L, "LegacyAuthor")
            }
            val other = Relate()
            for (rule in listOf("legacyauthor", "208259")) {
                assertEquals(listOf(other), AuthorHarness(rule).filter(listOf(target, other)))
            }
        }

        // nestedAndLegacyTagPathsSurviveSerialization
        run {
            val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
            val restored = VersionAdapter.VideoRelatePoints.fromJson(located.toJson())
            assertEquals(
                setOf(listOf("getTagName"), listOf("getGame", "getTagName")),
                restored.tagNameChains.map { it.steps.map { point -> point.methodName } }.toSet()
            )
        }

        // nestedGameTagsMatchWholeNamesAndLeaveUntaggedCards
        run {
            val target = gameTagCard("  策略  ")
            val partial = gameTagCard("策略游戏")
            val normal = RelateCard()
            val h = TagHarness("策略")
            val source = listOf(target, partial, normal)
            assertEquals(listOf(partial, normal), h.filter(source))
            assertEquals(3, source.size)
            assertTrue(h.runtime.contains(Triple("video_related_tag_block", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(h.runtime.any { it.first in setOf("video_related_author_block", "video_related_game_removed") })
        }

        // caseFoldingAndMixedRulesDoNotTurnPrefixesIntoMatches
        run {
            val rpg = gameTagCard("RPG")
            val strategy = gameTagCard("策略")
            val longer = gameTagCard("RPG Online")
            assertEquals(listOf(longer), TagHarness("rpg,策略,rpg").filter(listOf(rpg, strategy, longer)))
            val source = listOf(rpg, strategy)
            val h = TagHarness("rp,策")
            assertSame(source, h.filter(source))
            assertFalse(h.runtime.any { it.first == "video_related_tag_block" && it.second == FeatureRuntimeStage.APPLIED })
        }

        // missingAndUnreadableTagsRemainWithoutFallbackToReasons
        run {
            val throwing = object : RelateCard() {
                override fun getGame(): RelateGameCard = error("unreadable game")
            }
            val source = listOf(throwing, RelateCard(), gameTagCard(""), gameTagCard(" "))
            // The fixture's recommendation reason is 预约游戏; it is never a tag fallback.
            val h = TagHarness("预约游戏")
            assertSame(source, h.filter(source))
            assertFalse(h.runtime.any { it.second == FeatureRuntimeStage.ERROR })
        }

        // directLegacyTagsContinueToFilterAlongsideNestedCards
        run {
            val legacy = object : Relate() {
                override fun getTagName() = "策略"
            }
            val nested = gameTagCard("策略")
            val other = Relate()
            assertEquals(listOf(other), TagHarness("策略").filter(listOf(legacy, nested, other)))
        }
    }
}
