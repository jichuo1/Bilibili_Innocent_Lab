package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.GuestPlayerCoreAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import io.github.jichuo1.dexmeguviz.TypeUseOpcode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录完整性护栏：每个 [DexAssistQuery] 枚举都必须产出非空、可解析且带实际 where 条件的规则。
 *
 * 历史上 configure 是语句 when，新增枚举漏写分支只会静默得到空规则（where 为空对象），
 * 运行时与"无命中"无法区分。现在 configure 改表达式 when 由编译器穷尽，本测试再锁运行时形态：
 * 多规则型（PLAYER_SPEED_MENU/STORY_QUALITY_DEFAULT/COMMENT_REPLY_MAPPER）不得退化成单条空规则。
 */
class DexMeguvizQueriesTest {
    fun `every query produces bounded rules with a non-empty where condition`() {
        for (query in DexAssistQuery.entries) {
            val rules = DexMeguvizQueries.rules(query)
            val graphs = DexMeguvizQueries.graphPlans(query)
            assertTrue("$query must produce at least one flat rule or graph plan", rules.isNotEmpty() || graphs.isNotEmpty())
            assertTrue("$query must stay within the batch rule budget", rules.size <= 32)
            for ((index, bytes) in rules.withIndex()) {
                val rule = JSONObject(bytes.toString(Charsets.UTF_8))
                assertEquals("$query#$index schema", 1, rule.getInt("schema_version"))
                assertEquals("$query#$index relation", "method_defs", rule.getString("relation"))
                assertEquals("$query#$index max_results", DexMeguvizQueries.MAX_MATCHES + 1, rule.getInt("max_results"))
                val where = rule.getJSONObject("where")
                assertTrue("$query#$index where must not be empty", where.length() > 0)
                // MethodBatchQuery 与 NativeEngine.queryMethods 仅接受 query:v1:，顶层 composite 键会触发 query:v10: 拒绝。
                for (compositeKey in listOf("any", "all", "not")) {
                    assertFalse("$query#$index top-level where must not contain $compositeKey", where.has(compositeKey))
                }
            }
        }
        assertEquals("catalog size is pinned so additions update this guard deliberately", 60, DexAssistQuery.entries.size)
    }

    fun `multi-rule queries keep their fan-out rules`() {
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.PLAYER_SPEED_MENU).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.STORY_QUALITY_DEFAULT).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.COMMENT_REPLY_MAPPER).size > 1)
        assertEquals(3, DexMeguvizQueries.rules(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY).size)
        assertEquals(2, DexMeguvizQueries.rules(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS).size)
        assertEquals(5, DexMeguvizQueries.rules(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU).size)
        for (query in listOf(
            DexAssistQuery.PEGASUS_DIVIDER,
            DexAssistQuery.SEARCH_HOME_STATE,
            DexAssistQuery.PLAYER_END_PAGE_MERGER,
            DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE,
            DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT,
            DexAssistQuery.SPONSOR_CONTAINER_SCOPE
        )) {
            assertEquals("$query must include cross-package or caller-edge fallback", 2, DexMeguvizQueries.rules(query).size)
        }
        val sponsorRun = DexMeguvizQueries.rules(DexAssistQuery.SPONSOR_RUN_PLAYABLE)
        val sponsorWrapper = DexMeguvizQueries.rules(DexAssistQuery.SPONSOR_PLAYER_WRAPPER)
        assertEquals(2, sponsorRun.size)
        assertEquals(2, sponsorWrapper.size)
        assertTrue(JSONObject(sponsorRun[0].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertFalse(JSONObject(sponsorRun[1].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertTrue(sponsorRun[1].toString(Charsets.UTF_8).contains(SponsorPlayerLocator.COROUTINE_ERROR))
        assertTrue(JSONObject(sponsorWrapper[0].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertFalse(JSONObject(sponsorWrapper[1].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertTrue(sponsorWrapper[1].toString(Charsets.UTF_8).contains("seekTo"))

        for (chrome in listOf(
            DexAssistQuery.HOME_CHROME_TOP, DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT, DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT,
            DexAssistQuery.STORY_QUALITY_RESOURCE
        )) {
            val where = JSONObject(DexMeguvizQueries.rules(chrome).single().toString(Charsets.UTF_8)).getJSONObject("where")
            assertFalse("$chrome must not lock package/owner", where.has("owner"))
            assertFalse("$chrome must not use hardcoded repackaged any list", where.has("any"))
        }

        val kntrRule = DexMeguvizQueries.rules(DexAssistQuery.BRAND_SPLASH_KNTR_DRAW).single().toString(Charsets.UTF_8)
        assertTrue(kntrRule.contains(BrandSplashKntrAdapter.TRACE_MARK))
        val cloudKeyWhere = JSONObject(DexMeguvizQueries.rules(DexAssistQuery.CLOUD_KEY_FREEZE_CONTRACT).single().toString(Charsets.UTF_8)).getJSONObject("where")
        assertEquals("getWithDefault", cloudKeyWhere.getString("name"))
        assertEquals("Z", cloudKeyWhere.getString("return_type"))

        val ignetWhere = JSONObject(DexMeguvizQueries.rules(DexAssistQuery.PRIVACY_IGNET_HTTP).single().toString(Charsets.UTF_8)).getJSONObject("where")
        assertEquals("execute", ignetWhere.getString("name"))
        assertEquals(0, ignetWhere.getInt("param_count"))
        assertEquals("Lcom/bilibili/lib/ighttp/IgHttpResponse;", ignetWhere.getString("return_type"))

        val kneuronWhere = JSONObject(DexMeguvizQueries.rules(DexAssistQuery.PRIVACY_KNEURON).single().toString(Charsets.UTF_8)).getJSONObject("where")
        assertEquals("simpleTrackT", kneuronWhere.getString("name"))
        assertEquals("V", kneuronWhere.getString("return_type"))

        val normalAutoText = DexMeguvizQueries.rules(DexAssistQuery.NORMAL_QUALITY_AUTO_SWITCH).single().toString(Charsets.UTF_8)
        val normalAutoWhere = JSONObject(normalAutoText).getJSONObject("where")
        assertEquals(0, normalAutoWhere.getInt("param_count"))
        assertEquals("Z", normalAutoWhere.getString("return_type"))
        assertTrue(normalAutoText.contains(NormalQualityLocator.AUTO_KEY))

        val specialTagText = DexMeguvizQueries.rules(DexAssistQuery.DETAIL_SPECIAL_TAG_MAPPER).single().toString(Charsets.UTF_8)
        val specialTagWhere = JSONObject(specialTagText).getJSONObject("where")
        assertFalse(specialTagWhere.has("owner"))
        assertFalse(specialTagWhere.has("name"))
        assertTrue(specialTagText.contains(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS.replace('.', '/')))

        val replyBtnRules = DexMeguvizQueries.rules(DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE)
        assertEquals(2, replyBtnRules.size)
        assertTrue(replyBtnRules.all { it.toString(Charsets.UTF_8).contains(CommentReplyButtonAdapter.PLANT_SEEDS_VIEW.replace('.', '/')) })
        assertFalse(JSONObject(replyBtnRules[1].toString(Charsets.UTF_8)).getJSONObject("where").has("package"))

        val defaultWordsRules = DexMeguvizQueries.rules(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)
        assertEquals(2, defaultWordsRules.size)
        assertEquals(5, JSONObject(defaultWordsRules[0].toString(Charsets.UTF_8)).getJSONObject("where").getInt("param_count"))
        assertEquals(4, JSONObject(defaultWordsRules[1].toString(Charsets.UTF_8)).getJSONObject("where").getInt("param_count"))
        assertTrue(defaultWordsRules[1].toString(Charsets.UTF_8).contains(KotlinDefaultWordsLocator.DESCRIPTOR_GETTER))

        val guestCoreRules = DexMeguvizQueries.rules(DexAssistQuery.GUEST_PLAYER_CORE_SERVICE)
        assertEquals(2, guestCoreRules.size)
        val guestRule0Where = JSONObject(guestCoreRules[0].toString(Charsets.UTF_8)).getJSONObject("where")
        val guestRule1Where = JSONObject(guestCoreRules[1].toString(Charsets.UTF_8)).getJSONObject("where")
        assertEquals("setMediaResource", guestRule0Where.getString("name"))
        assertEquals("setMediaResource", guestRule1Where.getString("name"))
        assertEquals(3, guestRule0Where.getJSONArray("parameters").length())
        assertEquals(4, guestRule1Where.getJSONArray("parameters").length())
        assertTrue(guestCoreRules[0].toString(Charsets.UTF_8).contains(GuestPlayerCoreAdapter.MEDIA_ITEM_PARAMS.replace('.', '/')))
        assertTrue(guestCoreRules[1].toString(Charsets.UTF_8).contains(GuestPlayerCoreAdapter.MEDIA_ITEM.replace('.', '/')))
    }

    fun `compose menu and grpc wire queries avoid obfuscated method names`() {
        val wireRules = DexMeguvizQueries.rules(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        assertEquals(1, wireRules.size)
        val wireText = wireRules.single().toString(Charsets.UTF_8)
        val wireWhere = JSONObject(wireText).getJSONObject("where")
        assertFalse(wireWhere.has("name"))
        assertFalse(wireWhere.has("owner"))
        KmpGrpcWireAdapter.ENTRY_MARKS.forEach { token ->
            assertTrue("Missing KMP wire token $token", wireText.contains(token))
        }

        val composeText = DexMeguvizQueries.rules(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
            .joinToString("\n") { it.toString(Charsets.UTF_8) }
        for (mark in listOf(
            CommentComposeMenuAdapter.BUBBLE_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_ITEM_TRACE_MARK,
            CommentComposeMenuAdapter.LEGACY_TRACE_MARK
        )) {
            assertTrue("Missing compose menu trace $mark", composeText.contains(mark))
        }
    }

    fun `system environment queries match static descriptors and semantic constants without method names`() {
        val rootRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_ROOT_RESULT)
        val hookRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_HOOK_RESULT)
        assertEquals(1, rootRules.size)
        assertEquals(1, hookRules.size)

        val rootText = rootRules.single().toString(Charsets.UTF_8)
        val hookText = hookRules.single().toString(Charsets.UTF_8)
        val rootWhere = JSONObject(rootText).getJSONObject("where")
        val hookWhere = JSONObject(hookText).getJSONObject("where")

        assertFalse("SYSTEM_ENV_ROOT_RESULT must not pin obfuscated method name", rootWhere.has("name"))
        assertFalse("SYSTEM_ENV_HOOK_RESULT must not pin obfuscated method name", hookWhere.has("name"))
        assertFalse("SYSTEM_ENV_ROOT_RESULT must not match shared /system/bin/su helper", rootText.contains("/system/bin/su"))
        assertFalse("SYSTEM_ENV_HOOK_RESULT must not match shared /system/bin/su helper", hookText.contains("/system/bin/su"))
        assertTrue(rootText.contains("Ljava/util/Set;"))
        SystemEnvironmentAdapter.ENV_STRINGS.forEach { token ->
            assertTrue("Missing ENV_STRINGS token $token", rootText.contains(token))
        }
        SystemEnvironmentAdapter.HOOK_STRINGS.forEach { token ->
            assertTrue("Missing HOOK_STRINGS token $token", hookText.contains(token))
        }
    }

    fun `owner member and declaration rules address exact descriptors`() {
        val member = JSONObject(DexMeguvizQueries.ownerMembers("Lcom/example/Owner;").toString(Charsets.UTF_8))
        assertEquals("Lcom/example/Owner;", member.getJSONObject("where").getString("owner"))
        val declaration = JSONObject(
            DexMeguvizQueries.declaration("Lcom/example/Owner;", "run", "(I)V").toString(Charsets.UTF_8)
        ).getJSONObject("where")
        assertEquals("Lcom/example/Owner;", declaration.getString("owner"))
        assertEquals("run", declaration.getString("name"))
        assertEquals("(I)V", declaration.getString("prototype"))
        val byProto = JSONObject(
            DexMeguvizQueries.methodByNameAndPrototype("q0", "(I)V").toString(Charsets.UTF_8)
        ).getJSONObject("where")
        assertFalse(byProto.has("owner"))
        assertEquals("q0", byProto.getString("name"))
        assertEquals("(I)V", byProto.getString("prototype"))
    }

    fun `home recommend early load listener query covers PegasusFragment scroll+attach fanout and 3-arg continuation lambda`() {
        val earlyLoadRules = DexMeguvizQueries.rules(DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER)
        assertEquals(2, earlyLoadRules.size)
        val texts = earlyLoadRules.map { it.toString(Charsets.UTF_8) }
        assertTrue(texts[0].contains("Lcom/bilibili/pegasus/PegasusFragment;"))
        assertTrue(texts[0].contains("addOnScrollListener"))
        assertTrue(texts[0].contains("addOnChildAttachStateChangeListener"))
        assertTrue(texts[1].contains("Lcom/bilibili/pegasus/PegasusFragment$"))
        assertTrue(texts[1].contains("SuspendLambda"))
    }

    private fun decodeQueryIr(jsonText: String): String =
        Regex("\"u16\":\\[([0-9,]+)]").replace(jsonText) { match ->
            val text = match.groupValues[1].split(',').joinToString("") { it.toInt().toChar().toString() }
            "\"value\":\"$text\""
        }

    fun `graph plans serialize bounded valid QueryIR JSON without fragile package or arity locks`() {

        for (query in DexAssistQuery.entries) {
            val plans = DexMeguvizQueries.graphPlans(query)
            for (plan in plans) {
                val jsonText = plan.toQueryIrJson()
                assertTrue("${plan.tag} must serialize non-blank QueryIR", jsonText.isNotBlank())
                assertTrue(
                    "${plan.tag} QueryIR must stay under 64 KiB",
                    jsonText.toByteArray(Charsets.UTF_8).size <= 65536
                )
                val ir = JSONObject(jsonText)
                assertTrue("${plan.tag} max_results bounded", ir.getInt("max_results") <= DexMeguvizQueries.MAX_MATCHES + 1)
                assertTrue("${plan.tag} max_work bounded", ir.getLong("max_work") <= DexMeguvizGraphPlans.GRAPH_MAX_WORK)
                if (plan is DexGraphPlan.Graph) {
                    val scoped = DexMeguvizGraphPlans.scopedAdvancedGraphQuery(plan.query, listOf("base"))
                    val scopedJsonText = scoped.toQueryIrJson()
                    assertTrue("${plan.tag} scoped QueryIR must stay under 64 KiB", scopedJsonText.toByteArray(Charsets.UTF_8).size <= 65536)
                    assertTrue("${plan.tag} scoped QueryIR must declare base slot", scopedJsonText.contains("\"scope\":{\"slots\":[\"base\"]}"))
                    assertTrue("${plan.tag} scoped QueryIR must request inherited_fields", scopedJsonText.contains("inherited_fields"))
                }
            }
        }

        val earlyLoad = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER).single().toQueryIrJson()
        )
        assertTrue(earlyLoad.contains("RecyclerView\$OnScrollListener"))
        assertTrue(earlyLoad.contains("RecyclerView\$OnChildAttachStateChangeListener"))
        assertFalse(earlyLoad.contains("PegasusFragment"))

        val replyMapper = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_REPLY_MAPPER).single().toQueryIrJson()
        )
        assertTrue(replyMapper.contains("ReplyInfo"))
        assertTrue(replyMapper.contains("CommentItem"))
        assertTrue(replyMapper.contains("getId"))
        assertTrue(replyMapper.contains("getContent"))
        assertTrue(replyMapper.contains("\"min\":1"))
        assertTrue(replyMapper.contains("\"max\":8"))
        assertFalse(replyMapper.contains("comment3/data/source"))

        val sponsorScope = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.SPONSOR_CONTAINER_SCOPE).single().toQueryIrJson()
        )
        assertTrue(sponsorScope.contains("SuspendLambda"))
        assertTrue(sponsorScope.contains("Landroid/content/Context;"))
        assertTrue(sponsorScope.contains("Ltv/danmaku/biliplayerv2/PlayerContainer;"))
        assertTrue(sponsorScope.contains("group_by_owner"))
        assertTrue(sponsorScope.contains("semi_join"))

        val sponsorWrapper = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.SPONSOR_PLAYER_WRAPPER).single().toQueryIrJson()
        )
        assertTrue(sponsorWrapper.contains("seekTo"))
        assertTrue(sponsorWrapper.contains("(IZ)V"))
        assertTrue(sponsorWrapper.contains("IPlayerCoreService"))
        assertTrue(sponsorWrapper.contains("CoroutineScope"))
        assertTrue(sponsorWrapper.contains("group_by_owner"))
        assertTrue(sponsorWrapper.contains("semi_join"))

        val speedPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.PLAYER_SPEED_MENU)
        assertEquals(2, speedPlans.size)
        val speedFactoryJson = decodeQueryIr(speedPlans[0].toQueryIrJson())
        assertFalse(JSONObject(speedFactoryJson).getJSONObject("where").has("owner"))
        assertFalse(speedFactoryJson.contains("com/bilibili/playerbizcommonv2/utils"))
        assertTrue(speedFactoryJson.contains("java/lang/Integer;ZI)Ljava/util/List;"))
        val speedOpenJson = decodeQueryIr(speedPlans[1].toQueryIrJson())
        assertTrue(speedOpenJson.contains(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter.OPEN_MARK))
        assertTrue(speedOpenJson.contains("TheseusSpeedFunctionWidget"))
        assertTrue(speedOpenJson.contains("\"opcode\":${TypeUseOpcode.NEW_INSTANCE.code}"))
        assertFalse(speedOpenJson.contains("\"param_count\":9"))

        val composePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
        val composeJson = decodeQueryIr(composePlans.first().toQueryIrJson())
        assertTrue(composeJson.contains("PaddingValues"))
        assertFalse(composeJson.contains("kntr/common/comment"))

        val dividerJson = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.PEGASUS_DIVIDER).single().toQueryIrJson()
        )
        assertTrue(dividerJson.contains("ItemDecoration"))
        assertTrue(dividerJson.contains("Landroid/graphics/Paint;"))
        assertTrue(dividerJson.contains("Landroid/graphics/RectF;"))
        assertTrue(dividerJson.contains("Landroid/content/Context;"))
        assertTrue(dividerJson.contains("onDrawOver"))
        assertTrue(dividerJson.contains("group_by_owner"))
        assertTrue(dividerJson.contains("semi_join"))

        assertTrue(DexMeguvizQueries.graphPlans(DexAssistQuery.SEARCH_HOME_STATE).isEmpty())
        assertTrue(DexMeguvizQueries.graphPlans(DexAssistQuery.PLAYER_END_PAGE_MERGER).isEmpty())

        val replyBindingJson = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE).single().toQueryIrJson()
        )
        assertTrue(replyBindingJson.contains("ViewBinding"))
        assertTrue(replyBindingJson.contains("TintImageView"))
        assertTrue(replyBindingJson.contains("TintTextView"))
        assertTrue(replyBindingJson.contains("ConstraintLayout"))
        assertTrue(replyBindingJson.contains("LinearLayout"))
        assertTrue(replyBindingJson.contains("group_by_owner"))
        val replyMethodIr = decodeQueryIr(DexMeguvizGraphPlans.commentReplyMethodsForBinding("La/b;").toQueryIrJson())
        assertTrue(replyMethodIr.contains("CommentItem"))
        assertTrue(replyMethodIr.contains("La/b;"))

        val storyDefaultJson = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.STORY_QUALITY_DEFAULT).single().toQueryIrJson()
        )
        assertTrue(storyDefaultJson.contains("PlayerSettingHelper"))
        assertTrue(storyDefaultJson.contains("getDefaultQuality"))
        assertFalse(storyDefaultJson.contains("com/bilibili/video/story/player/quality"))
        assertFalse(JSONObject(storyDefaultJson).getJSONObject("where").has("owner"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR).isEmpty())
        val quickReplyJson = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR).single().toQueryIrJson()
        )
        assertTrue(quickReplyJson.contains("PublishDialogIntent;"))
        assertTrue(quickReplyJson.contains("Lkotlin/coroutines/Continuation;"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.PAUSED_AD_COUNTDOWN).isEmpty())
        val pausedCountdownPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.PAUSED_AD_COUNTDOWN)
        assertEquals(2, pausedCountdownPlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN, pausedCountdownPlans[0].tag)
        val pausedCountdownJson = decodeQueryIr(pausedCountdownPlans[0].toQueryIrJson())
        assertTrue(pausedCountdownJson.contains("PausedPageService"))
        assertTrue(pausedCountdownJson.contains("KPauseBar"))
        assertTrue(pausedCountdownJson.contains("\"label\""))
        assertTrue(pausedCountdownJson.contains("invokeSuspend"))
        assertTrue(pausedCountdownJson.contains("group_by_owner"))
        assertEquals(DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN_USES, pausedCountdownPlans[1].tag)
        val pausedCountdownUsesJson = decodeQueryIr(pausedCountdownPlans[1].toQueryIrJson())
        assertTrue(pausedCountdownUsesJson.contains("IVDPausedPage"))
        assertTrue(pausedCountdownUsesJson.contains("getCountDownView"))
        assertTrue(pausedCountdownUsesJson.contains("invokeSuspend"))
        assertFalse(pausedCountdownUsesJson.contains("KPauseBar"))

        val pausedApiRule = DexMeguvizQueries.rules(DexAssistQuery.PAUSED_AD_REQUEST_API).single().toString(Charsets.UTF_8)
        assertTrue(pausedApiRule.contains("ContinuationImpl"))
        assertEquals(16, JSONObject(pausedApiRule).getJSONObject("where").getJSONArray("parameters").length())
        val pausedApiGraph = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.PAUSED_AD_REQUEST_API).single().toQueryIrJson()
        )
        assertTrue(pausedApiGraph.contains("(JJLcom/bapis/bilibili/app/viewunite/v1/KTabType;"))
        assertTrue(pausedApiGraph.contains("ContinuationImpl;)Ljava/lang/Object;"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.BRAND_SPLASH_KNTR_EXIT).isEmpty())
        val exitPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_EXIT)
        assertEquals(2, exitPlans.size)
        val exitCurrent = decodeQueryIr(exitPlans[0].toQueryIrJson())
        assertTrue(exitCurrent.contains("Lkotlin/jvm/functions/Function0;"))
        assertTrue(exitCurrent.contains("Landroidx/compose/runtime/MutableState;"))
        assertTrue(exitCurrent.contains("Landroidx/compose/runtime/State;"))
        assertTrue(exitCurrent.contains("\"I\""))
        assertFalse(exitCurrent.contains("\$onExit"))
        val exitLegacy = decodeQueryIr(exitPlans[1].toQueryIrJson())
        assertTrue(exitLegacy.contains("BrandSplashPageKt\$BrandSplashPage\$"))
        assertTrue(exitLegacy.contains("invokeSuspend"))

        val pageRule = DexMeguvizQueries.rules(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE).single().toString(Charsets.UTF_8)
        assertTrue(pageRule.contains(BrandSplashKntrAdapter.PAGE_TRACE_MARK))
        val pagePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE)
        val pageGraph = decodeQueryIr(pagePlans.first().toQueryIrJson())
        assertTrue(pageGraph.contains(BrandSplashKntrAdapter.PAGE_TRACE_MARK))
        assertTrue(pageGraph.contains("Lkotlin/jvm/functions/Function0;"))
        assertTrue(pageGraph.contains("Ljava/lang/String;"))
        assertTrue(pageGraph.contains("Landroidx/compose/ui/Modifier;"))
        assertTrue(pageGraph.contains("Landroidx/compose/runtime/Composer;"))

        val homeTabRule = DexMeguvizQueries.rules(DexAssistQuery.HOME_TAB_RESOURCE).single().toString(Charsets.UTF_8)
        assertTrue(homeTabRule.contains("HomeFragmentV2"))
        val homeTabPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_TAB_RESOURCE)
        assertEquals(2, homeTabPlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE, homeTabPlans[0].tag)
        assertEquals(DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE_MODEL, homeTabPlans[1].tag)
        val homeTabBuildGraph = decodeQueryIr(homeTabPlans[0].toQueryIrJson())
        assertTrue(homeTabBuildGraph.contains("Ltv/danmaku/bili/ui/main2/HomeFragmentV2;"))
        assertTrue(homeTabBuildGraph.contains("(Ljava/util/List;)Ljava/util/List;"))
        val homeTabModelGraph = decodeQueryIr(homeTabPlans[1].toQueryIrJson())
        assertTrue(homeTabModelGraph.contains(HomeTabResourceAdapter.EQUALS_ANCHOR))
    }

    fun `batch 3 graph plans serialize bounded QueryIR JSON with structural anchors`() {
        for (q in listOf(
            DexAssistQuery.AI_PLAYLIST_DIRECTOR,
            DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY,
            DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT,
            DexAssistQuery.LEGACY_STORY_DANMAKU_BIND,
            DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS
        )) {
            assertTrue("$q flat rules should be empty", DexMeguvizQueries.rules(q).isEmpty())
        }

        val aiDirectorGraph = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.AI_PLAYLIST_DIRECTOR).single().toQueryIrJson()
        )
        assertTrue(aiDirectorGraph.contains("PlaylistDirectorSerialOperationsService"))
        assertTrue(aiDirectorGraph.contains("switchToNext"))
        assertTrue(aiDirectorGraph.contains("hasNext"))

        val followUpPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY)
        assertEquals(2, followUpPlans.size)
        val followUpGraphs = followUpPlans.joinToString("\n") { decodeQueryIr(it.toQueryIrJson()) }
        assertTrue(followUpGraphs.contains("RecommendUpRepository"))
        assertTrue(followUpGraphs.contains("Lkotlin/coroutines/Continuation;"))
        assertTrue(followUpGraphs.contains("\"kind\":\"constructor\""))

        val relatePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT)
        assertEquals(2, relatePlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_DETAIL_RELATE_GAME_IN_PKG, relatePlans[0].tag)
        val relateInPkg = decodeQueryIr(relatePlans[0].toQueryIrJson())
        assertTrue(relateInPkg.contains("com/bilibili/ship/theseus/united/page/intro/module/relate/game/"))
        assertTrue(relateInPkg.contains("Landroid/content/Context;"))
        assertTrue(relateInPkg.contains("Landroid/view/LayoutInflater;"))
        assertTrue(relateInPkg.contains("Landroid/view/ViewGroup;"))
        assertEquals(DexMeguvizGraphPlans.TAG_DETAIL_RELATE_GAME_BIND_USES, relatePlans[1].tag)
        val relateBindUses = decodeQueryIr(relatePlans[1].toQueryIrJson())
        assertTrue(relateBindUses.contains("Ltv/danmaku/bili/widget/ratingbar/ReviewRatingBar;"))
        assertTrue(relateBindUses.contains("setRating"))
        assertTrue(relateBindUses.contains("foreign_link_from_spmid"))
        assertTrue(relateBindUses.contains("content_id"))
        assertTrue(relateBindUses.contains("from_spmid"))
        assertTrue(relateBindUses.contains("Lkotlin/coroutines/Continuation;"))
        assertFalse(relateBindUses.contains("com/bilibili/ship/theseus/united/page/intro/module/relate/game/"))

        val legacyStoryGraph = decodeQueryIr(
            DexMeguvizQueries.graphPlans(DexAssistQuery.LEGACY_STORY_DANMAKU_BIND).single().toQueryIrJson()
        )
        assertTrue(legacyStoryGraph.contains("StoryAbsController"))

        val longPressPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS)
        assertEquals(2, longPressPlans.size)
        val longPressGraphs = longPressPlans.joinToString("\n") { decodeQueryIr(it.toQueryIrJson()) }
        assertTrue(longPressGraphs.contains("TripleSpeedService"))
        assertTrue(longPressGraphs.contains("()Ljava/lang/Float;"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DexMeguvizQueriesTest) -> Unit) {
            val s = DexMeguvizQueriesTest()
            block(s)
        }
        runIsolated { s -> s.`every query produces bounded rules with a non-empty where condition`() }
        runIsolated { s -> s.`multi-rule queries keep their fan-out rules`() }
        runIsolated { s -> s.`compose menu and grpc wire queries avoid obfuscated method names`() }
        runIsolated { s -> s.`system environment queries match static descriptors and semantic constants without method names`() }
        runIsolated { s -> s.`owner member and declaration rules address exact descriptors`() }
        runIsolated { s -> s.`home recommend early load listener query covers PegasusFragment scroll+attach fanout and 3-arg continuation lambda`() }
        runIsolated { s -> s.`graph plans serialize bounded valid QueryIR JSON without fragile package or arity locks`() }
        runIsolated { s -> s.`batch 3 graph plans serialize bounded QueryIR JSON with structural anchors`() }
    }
}
