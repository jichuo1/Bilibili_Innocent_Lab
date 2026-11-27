package com.bilab.plugin.api

import com.bilab.plugin.api.model.AdaptPatchEntry
import com.bilab.plugin.api.model.AdaptPatchTable
import com.bilab.plugin.api.model.DeclarativeFilterRules
import com.bilab.plugin.api.model.DexQuerySpec
import com.bilab.plugin.api.model.FilterRuleEntry
import com.bilab.plugin.api.model.FilterSurface
import com.bilab.plugin.api.model.HostAppInfo
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginCapabilityDecl
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginSettingField
import com.bilab.plugin.api.model.PluginSettingSchema
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.model.SemanticTemplateRule
import com.bilab.plugin.api.model.ThemeRecipe
import com.bilab.plugin.api.spi.AgentToolExtension
import com.bilab.plugin.api.spi.CommentView
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import com.bilab.plugin.api.spi.DanmakuBatchView
import com.bilab.plugin.api.spi.FeedCardView
import com.bilab.plugin.api.spi.FilterDecision
import com.bilab.plugin.api.spi.HookCallbackContext
import com.bilab.plugin.api.spi.HookHandle
import com.bilab.plugin.api.spi.PluginAgentToolDescriptor
import com.bilab.plugin.api.spi.PluginAgentToolResult
import com.bilab.plugin.api.spi.PluginHookCallback
import com.bilab.plugin.api.spi.PluginHookErrorPolicy
import com.bilab.plugin.api.spi.PluginHookRegistrar
import com.bilab.plugin.api.spi.ResolvedFieldRef
import com.bilab.plugin.api.spi.ResolvedMemberRef
import com.bilab.plugin.api.spi.WireDirection
import com.bilab.plugin.api.spi.WireExtension
import com.bilab.plugin.api.spi.WirePacketInfo
import com.bilab.plugin.api.spi.WireTransformResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginApiModelAndSpiTest {

    @Test
    fun `api version and enums satisfy baseline contract`() {
        assertEquals(1, PluginApiVersion.CURRENT_API_LEVEL)
        assertTrue(PluginApiVersion.isCompatible(1, 1))
        assertFalse(PluginApiVersion.isCompatible(2, 3))
        assertFalse(PluginApiVersion.isCompatible(2, 1))

        assertEquals(0, PluginPermission.L0_DATA_ONLY.level)
        assertEquals(1, PluginPermission.L1_CONTENT_FILTER.level)
        assertEquals(2, PluginPermission.L2_ADAPT_AND_HOOK.level)
        assertEquals(3, PluginPermission.L3_NATIVE_OR_PRIVILEGED.level)
        assertTrue(PluginPermission.L2_ADAPT_AND_HOOK.includes(PluginPermission.L1_CONTENT_FILTER))
        assertFalse(PluginPermission.L1_CONTENT_FILTER.includes(PluginPermission.L2_ADAPT_AND_HOOK))

        assertEquals(
            setOf(
                "INVALID_PACKAGE",
                "SIGNATURE_MISMATCH",
                "PERMISSION_DENIED",
                "HOST_VERSION_INCOMPATIBLE",
                "API_LEVEL_INCOMPATIBLE",
                "ELF_ALIGNMENT_INVALID",
                "BOOT_CRASH_LOOP",
                "CALLBACK_FAULT_STORM"
            ),
            PluginQuarantineReason.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun `manifest validation enforces id capability limit and tier consistency`() {
        val validDeclarative = PluginManifest(
            id = "com.bilab.filter-pack",
            versionCode = 1,
            versionName = "1.0.0",
            displayName = "Filter Pack",
            tier = PluginTier.DECLARATIVE_BUNDLE,
            permissions = setOf(PluginPermission.L1_CONTENT_FILTER),
            capabilities = listOf(
                PluginCapabilityDecl("feed", "Feed Filter", PluginPermission.L1_CONTENT_FILTER)
            ),
            entryClass = null
        )
        assertEquals(emptyList<String>(), validDeclarative.validate())
        assertTrue(validDeclarative.isCompatibleWith(hostVersionCode = 9161100L))

        val validSigned = validDeclarative.copy(
            tier = PluginTier.SIGNED_CODE,
            permissions = setOf(PluginPermission.L3_NATIVE_OR_PRIVILEGED),
            capabilities = listOf(
                PluginCapabilityDecl("wire", "Wire Ext", PluginPermission.L3_NATIVE_OR_PRIVILEGED)
            ),
            entryClass = "com.example.plugin.Entry"
        )
        assertEquals(emptyList<String>(), validSigned.validate())

        assertTrue(validDeclarative.copy(id = "ab").validate().isNotEmpty())
        assertTrue(validDeclarative.copy(id = "BadId").validate().isNotEmpty())
        assertTrue(validDeclarative.copy(id = "bad..id").validate().isNotEmpty())

        val tooManyCaps = (1..9).map {
            PluginCapabilityDecl("cap_$it", "Cap $it", PluginPermission.L1_CONTENT_FILTER)
        }
        assertTrue(validDeclarative.copy(capabilities = tooManyCaps).validate().isNotEmpty())

        assertTrue(
            validDeclarative.copy(
                permissions = setOf(PluginPermission.L3_NATIVE_OR_PRIVILEGED)
            ).validate().isNotEmpty()
        )
        assertTrue(
            validDeclarative.copy(entryClass = "com.example.Unexpected").validate().isNotEmpty()
        )
        assertTrue(
            validSigned.copy(entryClass = null).validate().isNotEmpty()
        )
    }

    @Test
    fun `setting schema validates plugin prefix and field constraints`() {
        val schema = PluginSettingSchema(
            pluginId = "com.bilab.demo",
            fields = listOf(
                PluginSettingField.BoolField("plg.com.bilab.demo.enabled", "Enabled", true),
                PluginSettingField.IntField("plg.com.bilab.demo.threshold", "Threshold", 50, min = 0, max = 100),
                PluginSettingField.TextField("plg.com.bilab.demo.keywords", "Keywords", "", maxLength = 512),
                PluginSettingField.EnumField("plg.com.bilab.demo.mode", "Mode", listOf("auto", "strict"), "auto")
            )
        )
        assertEquals(emptyList<String>(), schema.validate())
        schema.requireValid()

        val badPrefix = schema.copy(
            fields = listOf(PluginSettingField.BoolField("ads.pause.hidden", "Foreign"))
        )
        assertTrue(badPrefix.validate().isNotEmpty())

        val emptyLocalKey = schema.copy(
            fields = listOf(PluginSettingField.BoolField("plg.com.bilab.demo.", "EmptyLocal"))
        )
        assertTrue(emptyLocalKey.validate().isNotEmpty())
    }

    @Test
    fun `dex query spec and payload models preserve bounds and defaults`() {
        assertEquals(8, DexQuerySpec.MAX_QUERIES_PER_PLUGIN)
        assertEquals(32, DexQuerySpec.MAX_TOTAL_PLUGIN_QUERIES)

        val validQuery = DexQuerySpec(
            queryId = "comment_binder",
            withinPackages = listOf("com.bilibili.app.comment3"),
            methodName = "bind"
        )
        assertTrue(validQuery.uniqueOnly)
        assertEquals(emptyList<String>(), validQuery.validate())
        assertTrue(DexQuerySpec(queryId = "empty_anchors").validate().isNotEmpty())

        val theme = HostThemeSnapshot(
            isDark = true,
            surfaceColor = 0xFF121212.toInt(),
            accentColor = 0xFFFB7299.toInt(),
            textPrimaryColor = 0xFFFFFFFF.toInt(),
            textSecondaryColor = 0xFFAAAAAA.toInt(),
            density = 3.0f
        )
        assertEquals(16f, theme.cornerRadiusDp)

        val rules = DeclarativeFilterRules(
            rulesetId = "rs1",
            rules = listOf(FilterRuleEntry("r1", FilterSurface.FEED, keywords = listOf("ad")))
        )
        val patches = AdaptPatchTable(
            tableId = "pt1",
            entries = listOf(AdaptPatchEntry("k1", "a.b.C", "foo", "()V"))
        )
        val semantic = SemanticTemplateRule("t1", FilterSurface.COMMENT, "Spam", "Classify: {{text}}")
        val recipe = ThemeRecipe("rec1", "Pink", preferDark = false)
        val appInfo = HostAppInfo("tv.danmaku.bili", 9161100L, "9.16.1", "tv.danmaku.bili", "arm64-v8a")

        assertEquals(1, rules.rules.size)
        assertEquals(1, patches.entries.size)
        assertEquals(FilterSurface.COMMENT, semantic.surface)
        assertEquals(16f, recipe.cornerRadiusDp)
        assertEquals("arm64-v8a", appInfo.abi)
    }

    @Test
    fun `spi extensions operate cleanly without boxing or unsafe error propagation`() {
        val registrar = object : PluginHookRegistrar {
            override fun register(
                hookId: String,
                member: ResolvedMemberRef,
                callback: PluginHookCallback
            ): HookHandle = object : HookHandle {
                override val hookId: String = hookId
                override val isActive: Boolean = true
                override fun close() = Unit
            }

            override fun registerReplaceConstant(
                hookId: String,
                member: ResolvedMemberRef,
                constantResult: Any?
            ): HookHandle = object : HookHandle {
                override val hookId: String = hookId
                override val isActive: Boolean = true
                override fun close() = Unit
            }
        }
        assertEquals(PluginHookErrorPolicy.PROTECT_HOST, registrar.errorPolicy)

        val batchView = object : DanmakuBatchView {
            override val count: Int = 3
            override fun weightAt(index: Int): Int = listOf(2, 8, 5)[index]
            override fun hasWeightAt(index: Int): Boolean = true
            override fun colorAt(index: Int): Int = 0xFFFFFF
            override fun isColorfulAt(index: Int): Boolean = index == 2
            override fun contentAt(index: Int): String = "msg$index"
            override fun senderHashAt(index: Int): String = "u$index"
        }
        val batchFilter = object : DanmakuBatchFilter {
            override fun filterBatch(
                view: DanmakuBatchView,
                actionsOut: ByteArray,
                colorsOut: IntArray
            ): Boolean {
                var changed = false
                for (i in 0 until view.count) {
                    if (view.weightAt(i) < 4) {
                        actionsOut[i] = DanmakuBatchFilter.ACTION_DROP
                        changed = true
                    } else if (view.isColorfulAt(i)) {
                        actionsOut[i] = DanmakuBatchFilter.ACTION_RECOLOR
                        colorsOut[i] = 0xFF00FF00.toInt()
                        changed = true
                    } else {
                        actionsOut[i] = DanmakuBatchFilter.ACTION_KEEP
                    }
                }
                return changed
            }
        }
        val actions = ByteArray(3)
        val colors = IntArray(3)
        assertTrue(batchFilter.filterBatch(batchView, actions, colors))
        assertArrayEquals(
            byteArrayOf(
                DanmakuBatchFilter.ACTION_DROP,
                DanmakuBatchFilter.ACTION_KEEP,
                DanmakuBatchFilter.ACTION_RECOLOR
            ),
            actions
        )
        assertEquals(0xFF00FF00.toInt(), colors[2])

        val contentFilter = object : ContentFilterExtension {
            override fun filterFeedCard(card: FeedCardView): FilterDecision =
                if (card.isAd) FilterDecision.DROP else FilterDecision.KEEP
        }
        assertEquals(
            FilterDecision.DROP,
            contentFilter.filterFeedCard(object : FeedCardView {
                override val cardType: String = "ad"
                override val title: String = "Promo"
                override val authorName: String = "Sponsor"
                override val authorId: Long = 1L
                override val durationSeconds: Int = 30
                override val playCount: Long = 100L
                override val tid: Int = 1
                override val reasonText: String = ""
                override val isAd: Boolean = true
                override val isAiDeclared: Boolean = false
            })
        )
        assertEquals(
            FilterDecision.KEEP,
            contentFilter.filterComment(object : CommentView {
                override val commentId: Long = 1L
                override val authorName: String = "User"
                override val authorId: Long = 2L
                override val authorLevel: Int = 5
                override val message: String = "Hello"
                override val isPinned: Boolean = false
                override val hasImage: Boolean = false
            })
        )

        val wire = object : WireExtension {
            override val supportedPaths: Set<String> = setOf("/dm/seg")
            override fun onPacket(packet: WirePacketInfo): WireTransformResult =
                WireTransformResult.Replace(byteArrayOf(1, 2, 3))
        }
        val packet = WirePacketInfo("/dm/seg", WireDirection.INBOUND, byteArrayOf(9))
        assertEquals(WireTransformResult.Replace(byteArrayOf(1, 2, 3)), wire.onPacket(packet))

        val tool = object : AgentToolExtension {
            override val tools = listOf(PluginAgentToolDescriptor("echo", "Echo input"))
            override fun invoke(toolName: String, arguments: Map<String, String>): PluginAgentToolResult =
                PluginAgentToolResult.Success(arguments["text"].orEmpty())
        }
        assertEquals(PluginAgentToolResult.Success("ok"), tool.invoke("echo", mapOf("text" to "ok")))

        val member = object : ResolvedMemberRef {
            override val ownerClassName: String = "a.b.C"
            override val memberName: String = "m"
            override val descriptor: String = "()V"
        }
        val field = object : ResolvedFieldRef {
            override val ownerClassName: String = "a.b.C"
            override val fieldName: String = "f"
            override val fieldTypeName: String = "int"
        }
        val cbCtx: HookCallbackContext? = null
        assertEquals("a.b.C", member.ownerClassName)
        assertEquals("int", field.fieldTypeName)
        assertEquals(null, cbCtx)
    }
}
