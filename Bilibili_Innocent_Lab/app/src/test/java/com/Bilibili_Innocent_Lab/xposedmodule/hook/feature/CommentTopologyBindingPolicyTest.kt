package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTopologyBindingPolicyTest {

    private class BindingWithRoot {
        @Suppress("unused")
        fun getRoot(): View = throw UnsupportedOperationException()
    }

    private class NotABinding

    private class OtherBindingWithRoot {
        @Suppress("unused")
        fun getRoot(): View = throw UnsupportedOperationException()
    }

    private class MultiBranchHandler {
        @Suppress("UNUSED_PARAMETER")
        fun b(binding: BindingWithRoot, expanded: Boolean) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun c(binding: BindingWithRoot) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun ordinary(value: NotABinding) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun other(binding: OtherBindingWithRoot) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun helper(binding: BindingWithRoot): String = "helper"

        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun h(binding: BindingWithRoot) = Unit
        }
    }

    fun secondaryBindingsNeverMutateTheRootAnchor() {
        assertEquals(
            ReplyTopologyBindingAction.IGNORE,
            replyTopologyBindingAction(ReplyTopologyBindingScope.SECONDARY, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.IGNORE,
            replyTopologyBindingAction(ReplyTopologyBindingScope.SECONDARY, hasSeed = true)
        )
    }

    fun onlyTrustedRootBindingsMayClearWhileUnknownBindingsOnlyRetry() {
        assertEquals(
            ReplyTopologyBindingAction.CLEAR_AND_RETRY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.OWNER, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.CLEAR_AND_RETRY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.PRIMARY, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.RETRY_ONLY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.UNKNOWN, hasSeed = false)
        )
    }

    fun visibleMessageSelectsPrimaryOrSecondaryWhenBothViewsExist() {
        assertEquals(
            ReplyTopologyBindingScope.PRIMARY,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = true,
                secondaryPresent = true,
                secondaryVisible = false
            )
        )
        assertEquals(
            ReplyTopologyBindingScope.SECONDARY,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = false,
                secondaryPresent = true,
                secondaryVisible = true
            )
        )
        assertEquals(
            ReplyTopologyBindingScope.UNKNOWN,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = false,
                secondaryPresent = true,
                secondaryVisible = false
            )
        )
    }

    fun aResolvedRootSeedCanBindFromEveryNonSecondaryScope() {
        listOf(
            ReplyTopologyBindingScope.OWNER,
            ReplyTopologyBindingScope.PRIMARY
        ).forEach { scope ->
            assertEquals(
                ReplyTopologyBindingAction.SHOW,
                replyTopologyBindingAction(scope, hasSeed = true)
            )
        }
        assertEquals(
            ReplyTopologyBindingAction.RETRY_ONLY,
            replyTopologyBindingAction(
                ReplyTopologyBindingScope.UNKNOWN,
                hasSeed = true
            )
        )
    }

    fun highHandlerExpansionKeepsEveryInstanceBindingBranchOnlyOnce() {
        val cached = VersionAdapter.HookPoint(
            className = MultiBranchHandler::class.java.name,
            methodName = "b",
            paramClassNames = listOf(
                BindingWithRoot::class.java.name,
                java.lang.Boolean.TYPE.name
            )
        )

        val points = collectReplyTopologyHighBindPoints(
            MultiBranchHandler::class.java,
            cached
        )

        assertEquals(
            setOf(
                "b(${BindingWithRoot::class.java.name},boolean)",
                "c(${BindingWithRoot::class.java.name})"
            ),
            points.map { point ->
                "${point.methodName}(${point.paramClassNames.orEmpty().joinToString(",")})"
            }.toSet()
        )
        assertEquals(2, points.size)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentTopologyBindingPolicyTest) -> Unit) {
            val s = CommentTopologyBindingPolicyTest()
            block(s)
        }
        runIsolated { s -> s.secondaryBindingsNeverMutateTheRootAnchor() }
        runIsolated { s -> s.onlyTrustedRootBindingsMayClearWhileUnknownBindingsOnlyRetry() }
        runIsolated { s -> s.visibleMessageSelectsPrimaryOrSecondaryWhenBothViewsExist() }
        runIsolated { s -> s.aResolvedRootSeedCanBindFromEveryNonSecondaryScope() }
        runIsolated { s -> s.highHandlerExpansionKeepsEveryInstanceBindingBranchOnlyOnce() }
    
        CopyOnFilterCases().verifyAllCases()
        DanmakuPurifyPolicyCases().verifyAllCases()
        DetailAppPromotionFeatureInstallerCases().verifyAllCases()
        DetailModulePurifyPolicyCases().verifyAllCases()
        DetailUnitedPresentationPurifyPolicyCases().verifyAllCases()
        DynamicPurifyPolicyCases().verifyAllCases()
        DynamicTabsFeatureInstallerCases().verifyAllCases()
    }
}

private class CopyOnFilterCases {

    fun `returns original list when no item matches`() {
        val source = listOf("one", "two")

        assertSame(source, CopyOnFilter.list(source) { false })
    }

    fun `copies only after first match and preserves null items`() {
        val source = listOf("keep", null, "drop", "keep-2")

        val filtered = CopyOnFilter.list(source) { it == "drop" }

        assertEquals(listOf("keep", null, "keep-2"), filtered)
        assertEquals(listOf("keep", null, "drop", "keep-2"), source)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CopyOnFilterCases) -> Unit) {
            val s = CopyOnFilterCases()
            block(s)
        }
        runIsolated { s -> s.`returns original list when no item matches`() }
        runIsolated { s -> s.`copies only after first match and preserves null items`() }
    }
}

private class DanmakuPurifyPolicyCases {

    fun `retain returns null when nothing is dropped`() {
        val source = listOf("a", "b", "c")
        assertNull(DanmakuPurifyPolicy.retain(source) { true })
    }

    fun `retain keeps order and drops only rejected items`() {
        val source = listOf("keep", "drop", "keep-2", "drop-2")
        val retained = DanmakuPurifyPolicy.retain(source) { it.toString().startsWith("keep") }
        assertEquals(listOf("keep", "keep-2"), retained)
        assertEquals(listOf("keep", "drop", "keep-2", "drop-2"), source)
    }

    fun `retain drops null entries without crashing`() {
        val source = listOf("keep", null, "keep-2")
        val retained = DanmakuPurifyPolicy.retain(source) { true }
        assertEquals(listOf("keep", "keep-2"), retained)
    }

    fun `weight signal counts as unusable when the whole segment reads zero or fails`() {
        val source = listOf(0, 0, 0)
        assertFalse(DanmakuPurifyPolicy.hasUsableWeight(source) { it as Int })
        assertFalse(DanmakuPurifyPolicy.hasUsableWeight(source) { null })
        assertTrue(DanmakuPurifyPolicy.hasUsableWeight(listOf(0, 4)) { it as Int })
    }

    fun `weight threshold is clamped to the supported range`() {
        assertEquals(DanmakuPurifyPolicy.MIN_WEIGHT, DanmakuPurifyPolicy.normalizeWeight(-7))
        assertEquals(DanmakuPurifyPolicy.MAX_WEIGHT, DanmakuPurifyPolicy.normalizeWeight(99))
        assertEquals(5, DanmakuPurifyPolicy.normalizeWeight(5))
        assertTrue(
            DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT in
                DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DanmakuPurifyPolicyCases) -> Unit) {
            val s = DanmakuPurifyPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`retain returns null when nothing is dropped`() }
        runIsolated { s -> s.`retain keeps order and drops only rejected items`() }
        runIsolated { s -> s.`retain drops null entries without crashing`() }
        runIsolated { s -> s.`weight signal counts as unusable when the whole segment reads zero or fails`() }
        runIsolated { s -> s.`weight threshold is clamped to the supported range`() }
    }
}

private class DetailAppPromotionFeatureInstallerCases {

    fun `all known renderer routes satisfy the constrained multiversion contract`() {
        listOf("getUpperNestView", "getUpperAdView", "getUpperHDView").forEach { name ->
            assertTrue(matches(method(name)))
        }
    }

    fun `legacy concrete view group subtype remains compatible`() {
        assertTrue(
            matches(
                LegacyFixture::class.java.getDeclaredMethod(
                    "getUpperNestView",
                    FrameLayout::class.java,
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
    }

    fun `unknown and structurally incomplete methods fail open`() {
        assertFalse(matches(method("getUnknownView")))
        assertFalse(
            matches(
                Fixture::class.java.getDeclaredMethod(
                    "wrongReturn",
                    ViewGroup::class.java,
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
        assertFalse(
            matches(
                Fixture::class.java.getDeclaredMethod(
                    "missingContainer",
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
        assertFalse(
            matches(
                IncompleteFixture::class.java.getDeclaredMethod(
                    "getUpperNestView",
                    View::class.java,
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
    }

    fun `ad relate contract only accepts the dedicated reference-returning route`() {
        assertTrue(
            matchesDetailRelateAdRenderMethod(
                RelateFixture::class.java.getDeclaredMethod(
                    "getAdRelateView",
                    String::class.java
                )
            )
        )
        assertFalse(
            matchesDetailRelateAdRenderMethod(
                RelateFixture::class.java.getDeclaredMethod("getRelatedView")
            )
        )
        assertFalse(
            matchesDetailRelateAdRenderMethod(
                RelateFixture::class.java.getDeclaredMethod("getAdRelateView")
            )
        )
    }

    fun `runtime gate locates config without requiring caller service argument`() {
        val expected = Config()

        assertSame(
            expected,
            detailPromotionConfigOrNull(arrayOf(Any(), expected, Any()), Config::class.java)
        )
        assertNull(detailPromotionConfigOrNull(arrayOf(Any()), Config::class.java))
    }

    fun `runtime gate only accepts the unite detail scene`() {
        assertTrue(matchesDetailPromotionScene(Scene.UNITE_DETAIL, Scene.UNITE_DETAIL))
        assertFalse(matchesDetailPromotionScene(Scene.OTHER, Scene.UNITE_DETAIL))
        assertFalse(matchesDetailPromotionScene(null, Scene.UNITE_DETAIL))
    }

    fun `legacy preference key remains stable across the label rename`() {
        assertEquals(
            "hide_video_detail_app_promotion",
            FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION
        )
    }

    fun `warmup never initializes an untouched host lazy`() {
        var initialized = false
        val untouched = lazy {
            initialized = true
            VideoDetail()
        }

        assertNull(initializedLazyValueOfType(listOf(untouched), VideoDetail::class.java))
        assertFalse(initialized)
    }

    fun `warmup selects an already initialized matching host lazy`() {
        val unrelated = lazy { Any() }.also { it.value }
        val expected = VideoDetail()
        val initialized = lazy { expected }.also { it.value }

        assertSame(
            expected,
            initializedLazyValueOfType(
                listOf(unrelated, initialized),
                VideoDetail::class.java
            )
        )
    }

    private fun method(name: String) = Fixture::class.java.getDeclaredMethod(
        name,
        ViewGroup::class.java,
        Bridge::class.java,
        Config::class.java
    )

    private fun matches(method: java.lang.reflect.Method) = matchesDetailPromotionRenderMethod(
        method = method,
        callbackClass = Callback::class.java,
        bridgeClass = Bridge::class.java,
        configClass = Config::class.java
    )

    private interface Callback

    private interface Bridge

    private class Config

    private enum class Scene {
        UNITE_DETAIL,
        OTHER
    }

    private class VideoDetail

    @Suppress("UNUSED_PARAMETER")
    private class RelateFixture {
        fun getAdRelateView(scene: String): Callback = error("signature fixture")

        fun getAdRelateView(): Int = 0

        fun getRelatedView(): Callback = error("signature fixture")
    }

    @Suppress("UNUSED_PARAMETER")
    private class Fixture {
        fun getUpperNestView(
            container: ViewGroup,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUpperAdView(
            container: ViewGroup,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUpperHDView(
            container: ViewGroup,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUnknownView(
            container: ViewGroup,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun wrongReturn(
            container: ViewGroup,
            bridge: Bridge,
            config: Config
        ): String = error("signature fixture")

        fun missingContainer(
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")
    }

    @Suppress("UNUSED_PARAMETER")
    private class LegacyFixture {
        fun getUpperNestView(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")
    }

    @Suppress("UNUSED_PARAMETER")
    private class IncompleteFixture {
        fun getUpperNestView(
            container: View,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DetailAppPromotionFeatureInstallerCases) -> Unit) {
            val s = DetailAppPromotionFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`all known renderer routes satisfy the constrained multiversion contract`() }
        runIsolated { s -> s.`legacy concrete view group subtype remains compatible`() }
        runIsolated { s -> s.`unknown and structurally incomplete methods fail open`() }
        runIsolated { s -> s.`ad relate contract only accepts the dedicated reference-returning route`() }
        runIsolated { s -> s.`runtime gate locates config without requiring caller service argument`() }
        runIsolated { s -> s.`runtime gate only accepts the unite detail scene`() }
        runIsolated { s -> s.`legacy preference key remains stable across the label rename`() }
        runIsolated { s -> s.`warmup never initializes an untouched host lazy`() }
        runIsolated { s -> s.`warmup selects an already initialized matching host lazy`() }
    }
}

private class DetailModulePurifyPolicyCases {

    fun `targets are unique and derive their accessor names from the field name`() {
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
    fun `vipActive is deliberately excluded because it has no presence accessor`() {
        assertFalse(DetailModulePurifyPolicy.targets.any { it.fieldName == "VipActive" })
        val reply = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS)
        assertTrue(reply.methods.any { it.name == "getVipActive" })
        assertFalse(reply.methods.any { it.name == "hasVipActive" })
    }

    fun `every target is wired into the settings catalog and the diagnostic catalog`() {
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
    fun `topic tags are matched by their topic link not by their text`() {
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

    fun `only no-arg clear methods may be invoked`() {
        val builder = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS + "\$Builder")
        val clearHonor = builder.methods.single { it.name == "clearHonor" }
        assertTrue(DetailModulePurifyPolicy.callable(clearHonor))
        val build = builder.methods.single { it.name == "build" }
        assertFalse("build() is not a clear method", DetailModulePurifyPolicy.callable(build))
        val newBuilder = Class.forName(DetailModulePurifyPolicy.REPLY_CLASS)
            .methods.single { it.name == "newBuilder" }
        assertFalse("takes a parameter", DetailModulePurifyPolicy.callable(newBuilder))
    }

    fun `the preference key list is the single source shared with the panel`() {
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

    fun verifyAllCases() {
        fun runIsolated(block: (DetailModulePurifyPolicyCases) -> Unit) {
            val s = DetailModulePurifyPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`targets are unique and derive their accessor names from the field name`() }
        runIsolated { s -> s.`vipActive is deliberately excluded because it has no presence accessor`() }
        runIsolated { s -> s.`every target is wired into the settings catalog and the diagnostic catalog`() }
        runIsolated { s -> s.`topic tags are matched by their topic link not by their text`() }
        runIsolated { s -> s.`only no-arg clear methods may be invoked`() }
        runIsolated { s -> s.`the preference key list is the single source shared with the panel`() }
    }
}

private class DetailUnitedPresentationPurifyPolicyCases {

    fun `activity headline route is removed without matching visible labels or image names`() {
        val uri = "https://www.bilibili.com/blackboard/era/sEUlB5cmkNeH3izK.html?from_spmid=united.player-video-detail.0.0"
        val cleaner = requireNotNull(HeadlineBadgeModelCleaner.resolve(FakeHeadline::class.java))
        val input = FakeHeadline("original title", FakeHeadlineLabel(type = 2, uri = uri))
        val result = cleaner.removeHotSearchBadge(input) as FakeHeadline
        assertEquals(input.content, result.content)
        assertNull(result.label)
        assertNotNull(input.label)
        assertTrue(DetailUnitedPresentationPurifyPolicy.isPromotionalLabelUri("bilibili://search?from=other"))
    }

    fun `activity headline route excludes other destinations and lookalikes`() {
        listOf(
            "bilibili://video/1", "https://www.bilibili.com/video/BV1",
            "https://evilbilibili.com/blackboard/era/id.html",
            "https://www.bilibili.com.evil.test/blackboard/era/id.html",
            "https://www.bilibili.com@evil.test/blackboard/era/id.html",
            "https://www.bilibili.com/blackboard/other/id.html",
            "https://www.bilibili.com/blackboard/era/.html",
            "https://www.bilibili.com/blackboard/era/nested/id.html",
            "https://www.bilibili.com/blackboard/era/../id.html",
            "https://www.bilibili.com/video/1?next=/blackboard/era/id.html",
            "https://i0.hdslb.com/bfs/app/activity.png", "", null
        ).forEach { assertFalse(it, DetailUnitedPresentationPurifyPolicy.isPromotionalLabelUri(it)) }
    }

    fun `special tag mapper candidates stay bounded to known owners`() {
        val candidates = DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses
        val pkg = "com.bilibili.ship.theseus.united.page.intro.module.tags."
        // 已核对的 f、j 优先，其后是有界字母表；9.11.0(9110400) 与 9.14.0 的 `i` 由字母表覆盖。
        assertEquals(listOf(pkg + "f", pkg + "j"), candidates.take(2))
        assertEquals(27, candidates.size)
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.dropLast(1).all { it.startsWith(pkg) && it.removePrefix(pkg).length == 1 })
        assertEquals("cg1", candidates.last())
        assertTrue(pkg + "i" in candidates)
    }

    fun `hot badge source requires the exact search from parameter`() {
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?keyword=OpenAI%20news&from=apphotword_search_huangtiao"
            )
        )
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?from=apphotword_search_huangtiao&keyword=contains spaces"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?keyword=apphotword_search_huangtiao"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?from=apphotword_search_huangtiao_extra"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "https://m.bilibili.com/search?from=apphotword_search_huangtiao"
            )
        )
    }

    /**
     * 标题角标按**类别**判：只要 label 跳的是搜索页，无论运营文案是什么都覆盖。
     *
     * 2026-09-11 实测「热搜」与「活动」是同一套协议结构，只有 `icon_name` 文案和
     * `from` 来源标记不同（`apphotword_search_huangtiao` vs `app_comment_topic_search`）。
     * 一个词一个开关是不可维护的，所以判据收在"跳搜索"这个结构特征上。
     */
    fun `title badge matching is by category so every from variant is covered`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        // 热搜
        assertTrue(policy.isSearchJumpLabelUri(
            "bilibili://search?keyword=OpenAI&from=apphotword_search_huangtiao"))
        // 活动——旧的精确判据漏掉的正是它
        assertTrue(policy.isSearchJumpLabelUri(
            "bilibili://search?keyword=%E6%B4%BB%E5%8A%A8&from=app_comment_topic_search"))
        assertFalse(policy.isHotSearchLabelUri(
            "bilibili://search?keyword=%E6%B4%BB%E5%8A%A8&from=app_comment_topic_search"))
        // 未来任何新来源标记都自动覆盖
        assertTrue(policy.isSearchJumpLabelUri("bilibili://search?from=whatever_comes_next"))
        assertTrue(policy.isSearchJumpLabelUri("bilibili://search"))
    }

    /** 判的是 host，不是子串：正常标识与其它宿主链接都必须放行。 */
    fun `title badge matching never fires on non search destinations`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        // 「互动视频」这类正常徽标跳的不是搜索页
        assertFalse(policy.isSearchJumpLabelUri("bilibili://video/117249805387384"))
        assertFalse(policy.isSearchJumpLabelUri("bilibili://pegasus/channel/v2"))
        // keyword 里出现 search 字样不算
        assertFalse(policy.isSearchJumpLabelUri("bilibili://video/1?keyword=search"))
        // 换 scheme / 换 host 都不算
        assertFalse(policy.isSearchJumpLabelUri("https://m.bilibili.com/search?from=x"))
        assertFalse(policy.isSearchJumpLabelUri("bilibili://searchresult?from=x"))
        assertFalse(policy.isSearchJumpLabelUri(null))
        assertFalse(policy.isSearchJumpLabelUri(""))
    }

    /** 精确判据仍然是类别判据的子集——保留它是为了钉住最初的抓包证据。 */
    fun `the exact hot search predicate is a subset of the category predicate`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        val hot = "bilibili://search?from=apphotword_search_huangtiao"
        assertTrue(policy.isHotSearchLabelUri(hot))
        assertTrue(policy.isSearchJumpLabelUri(hot))
        assertFalse(policy.isHotSearchLabelUri("bilibili://video/1"))
    }

    fun `special topic matcher requires a Bilibili topic detail route`() {
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://m.bilibili.com/topic-detail?topic_id=1255303&topic_name=AI+IN+ALL"
            )
        )
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://www.bilibili.com/topic-detail?topic_id=1"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://evilbilibili.com/topic-detail?topic_id=1"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://m.bilibili.com/search?keyword=topic-detail"
            )
        )
        assertFalse(DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(null))
    }

    fun `headline cleaner only removes a label with the exact hot search source`() {
        val cleaner = requireNotNull(HeadlineBadgeModelCleaner.resolve(FakeHeadline::class.java))
        val hot = FakeHeadline(
            "normal video title",
            FakeHeadlineLabel(uri = "bilibili://search?from=apphotword_search_huangtiao")
        )
        val cleaned = cleaner.removeHotSearchBadge(hot) as? FakeHeadline
        assertNotNull(cleaned)
        assertEquals("normal video title", cleaned!!.content)
        assertNull(cleaned.label)

        val interactive = FakeHeadline(
            "normal video title",
            FakeHeadlineLabel(uri = "bilibili://video/interactive")
        )
        assertNull(cleaner.removeHotSearchBadge(interactive))
    }

    fun `special topic cleaner retains other cells and their original order`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val normalBefore = FakeCell("normal", "bilibili://search?keyword=topic-detail")
        val topic = FakeCell("AI IN ALL", "https://m.bilibili.com/topic-detail?topic_id=1255303")
        val normalAfter = FakeCell("BGM", "bilibili://music/detail/1")
        val input = FakeTagsData(listOf(normalBefore, topic, normalAfter), refresh = true)

        val result = cleaner.removeSpecialTopicCells(input) as FakeTagsData
        assertEquals(listOf(normalBefore, normalAfter), result.cells)
        assertTrue(result.refresh)
    }

    fun `all topic cells disable refresh so TagsService creates no empty component`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val input = FakeTagsData(
            listOf(
                FakeCell("topic one", "https://m.bilibili.com/topic-detail?topic_id=1"),
                FakeCell("topic two", "https://m.bilibili.com/topic-detail?topic_id=2")
            ),
            refresh = true
        )

        val result = cleaner.removeSpecialTopicCells(input) as FakeTagsData
        assertTrue(result.cells.isEmpty())
        assertFalse(result.refresh)
    }

    fun `special topic cleaner returns the original instance when nothing matches`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val input = FakeTagsData(
            listOf(FakeCell("normal", "bilibili://search?keyword=topic-detail")),
            refresh = true
        )
        assertSame(input, cleaner.removeSpecialTopicCells(input))
    }

    fun `model cleaners fail closed when future models gain an unaccounted field`() {
        assertNull(HeadlineBadgeModelCleaner.resolve(ExpandedFakeHeadline::class.java))
        assertNull(HeadlineBadgeModelCleaner.resolve(HeadlineWithExpandedFakeLabel::class.java))
        assertNull(SpecialTopicTagsModelCleaner.resolve(ExpandedFakeTagsData::class.java))
    }

    private data class FakeHeadlineLabel(
        val type: Int = 0,
        val uri: String,
        val icon: String = "",
        val iconNight: String = "",
        val lottie: String = "",
        val lottieNight: String = "",
        val width: Long = 0L,
        val height: Long = 0L,
        val reportMap: Map<String, String> = emptyMap()
    )
    private data class FakeHeadline(val content: String, val label: FakeHeadlineLabel?)
    private data class ExpandedFakeHeadline(
        val content: String,
        val label: FakeHeadlineLabel?,
        val unaccounted: String
    )
    private data class ExpandedFakeHeadlineLabel(
        val type: Int = 0,
        val uri: String,
        val icon: String = "",
        val iconNight: String = "",
        val lottie: String = "",
        val lottieNight: String = "",
        val width: Long = 0L,
        val height: Long = 0L,
        val reportMap: Map<String, String> = emptyMap(),
        val unaccounted: String = ""
    )
    private data class HeadlineWithExpandedFakeLabel(
        val content: String,
        val label: ExpandedFakeHeadlineLabel?
    )
    private data class FakeCell(val text: String, val jumpUrl: String)
    private data class FakeTagsData(val cells: List<FakeCell>, val refresh: Boolean)
    private data class ExpandedFakeTagsData(
        val cells: List<FakeCell>,
        val refresh: Boolean,
        val unaccounted: String
    )

    fun verifyAllCases() {
        fun runIsolated(block: (DetailUnitedPresentationPurifyPolicyCases) -> Unit) {
            val s = DetailUnitedPresentationPurifyPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`activity headline route is removed without matching visible labels or image names`() }
        runIsolated { s -> s.`activity headline route excludes other destinations and lookalikes`() }
        runIsolated { s -> s.`special tag mapper candidates stay bounded to known owners`() }
        runIsolated { s -> s.`hot badge source requires the exact search from parameter`() }
        runIsolated { s -> s.`title badge matching is by category so every from variant is covered`() }
        runIsolated { s -> s.`title badge matching never fires on non search destinations`() }
        runIsolated { s -> s.`the exact hot search predicate is a subset of the category predicate`() }
        runIsolated { s -> s.`special topic matcher requires a Bilibili topic detail route`() }
        runIsolated { s -> s.`headline cleaner only removes a label with the exact hot search source`() }
        runIsolated { s -> s.`special topic cleaner retains other cells and their original order`() }
        runIsolated { s -> s.`all topic cells disable refresh so TagsService creates no empty component`() }
        runIsolated { s -> s.`special topic cleaner returns the original instance when nothing matches`() }
        runIsolated { s -> s.`model cleaners fail closed when future models gain an unaccounted field`() }
    }
}

private class DynamicPurifyPolicyCases {

    private fun plan(
        keywords: Set<String> = emptySet(),
        authorRules: AuthorRuleSet = AuthorRuleSet.EMPTY,
        removePromotion: Boolean = false,
        removeLockedChargeOnly: Boolean = false
    ) = DynamicPurifyPolicy.Plan(keywords, authorRules, removePromotion, removeLockedChargeOnly)

    /** 用固定的几段正文模拟宿主的分段回调；返回值语义与安装器里的实现一致。 */
    private fun fragments(vararg values: String): ((String) -> Boolean) -> Boolean = { matches ->
        values.any(matches)
    }

    fun `keyword matches any single text fragment`() {
        val judgement = plan(keywords = RuleSetCodec.parse("恰饭, Sponsor"))

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("今天恰饭了")),
                judgement
            )
        )
        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("普通正文", "SPONSOR ad")),
                judgement
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("普通正文")),
                judgement
            )
        )
    }

    fun `keywords never match across fragment boundaries`() {
        val judgement = plan(keywords = RuleSetCodec.parse("恰饭"))

        // 拼接会得到"…恰" + "饭…" = 命中；逐段匹配不会。
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("今天恰", "饭好吃")),
                judgement
            )
        )
    }

    fun `text is not read at all when no keyword is configured`() {
        var reads = 0
        val signals = DynamicPurifyPolicy.Signals(
            textFragments = { _ ->
                reads += 1
                true
            }
        )

        assertFalse(DynamicPurifyPolicy.shouldRemove(signals, plan()))
        assertEquals(0, reads)
    }

    fun `author promotion and charge-only judgements are independent`() {
        val byAuthor = plan(authorRules = AuthorRuleSet.parse("777"))
        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(authorMid = 777L),
                byAuthor
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(), byAuthor)
        )

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(promotion = true),
                plan(removePromotion = true)
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(promotion = true),
                plan()
            )
        )

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(lockedChargeOnly = true),
                plan(removeLockedChargeOnly = true)
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(lockedChargeOnly = false),
                plan(removeLockedChargeOnly = true)
            )
        )
    }

    fun `plan reports what is actually active`() {
        assertFalse(plan().hasAnyItemJudgement)
        assertFalse(plan().needsText)
        assertTrue(plan(keywords = setOf("a")).needsText)
        assertTrue(plan(authorRules = AuthorRuleSet.parse("1")).needsAuthor)
        assertTrue(plan(removePromotion = true).hasAnyItemJudgement)
        assertTrue(plan(removeLockedChargeOnly = true).hasAnyItemJudgement)
        // 只按发布者过滤时不该白读一遍正文。
        assertFalse(plan(authorRules = AuthorRuleSet.parse("1")).needsText)
    }

    fun `promotion additional type fallbacks stay aligned with the constant names`() {
        assertEquals(
            DynamicPurifyPolicy.PROMOTION_ADDITIONAL_TYPE_FIELDS.size,
            DynamicPurifyPolicy.FALLBACK_PROMOTION_ADDITIONAL_TYPES.size
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DynamicPurifyPolicyCases) -> Unit) {
            val s = DynamicPurifyPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`keyword matches any single text fragment`() }
        runIsolated { s -> s.`keywords never match across fragment boundaries`() }
        runIsolated { s -> s.`text is not read at all when no keyword is configured`() }
        runIsolated { s -> s.`author promotion and charge-only judgements are independent`() }
        runIsolated { s -> s.`plan reports what is actually active`() }
        runIsolated { s -> s.`promotion additional type fallbacks stay aligned with the constant names`() }
    }
}

private class DynamicTabsFeatureInstallerCases {

    fun `classifies city and school by title without touching regular tabs`() {
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.CITY,
            DynamicTabsFeatureInstaller.hiddenKind("同城", "unknown", true, true)
        )
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.SCHOOL,
            DynamicTabsFeatureInstaller.hiddenKind("校园", null, true, true)
        )
        assertNull(DynamicTabsFeatureInstaller.hiddenKind("视频", "video", true, true))
    }

    fun `respects independent switches and stable name fallbacks`() {
        assertNull(DynamicTabsFeatureInstaller.hiddenKind("同城", "city", false, true))
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.CITY,
            DynamicTabsFeatureInstaller.hiddenKind(null, "same_city", true, false)
        )
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.SCHOOL,
            DynamicTabsFeatureInstaller.hiddenKind(null, "campus", false, true)
        )
    }

    fun `matches only the exact video tab label`() {
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab("视频", null))
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab(" 视频 ", null))
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab(null, "VIDEO"))
        assertEquals(false, DynamicTabsFeatureInstaller.isVideoTab("视频号", null))
        assertEquals(false, DynamicTabsFeatureInstaller.isVideoTab(null, null))
    }


    fun verifyAllCases() {
        fun runIsolated(block: (DynamicTabsFeatureInstallerCases) -> Unit) {
            val s = DynamicTabsFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`classifies city and school by title without touching regular tabs`() }
        runIsolated { s -> s.`respects independent switches and stable name fallbacks`() }
        runIsolated { s -> s.`matches only the exact video tab label`() }
    }
}
