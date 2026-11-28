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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import io.github.jichuo1.dexmeguviz.DecisionReason
import io.github.jichuo1.dexmeguviz.EntityPredicate
import io.github.jichuo1.dexmeguviz.EntityRelation
import io.github.jichuo1.dexmeguviz.EntityTextField
import io.github.jichuo1.dexmeguviz.ExecutionStatus
import io.github.jichuo1.dexmeguviz.GraphExpression
import io.github.jichuo1.dexmeguviz.GraphPredicate
import io.github.jichuo1.dexmeguviz.GraphQuery
import io.github.jichuo1.dexmeguviz.TextOperation
import io.github.jichuo1.dexmeguviz.TypeUseOpcode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录完整性护栏与图结果完整性门禁：每个 [DexAssistQuery] 必须产出非空且有界的规则或图计划，
 * 并校验 QueryIR 序列化、分块限额、slot 规范化与双通道失败合成逻辑。
 */
class DexMeguvizQueriesTest {
    private fun everyQueryProducesBoundedRules() {
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
                for (compositeKey in listOf("any", "all", "not")) {
                    assertFalse("$query#$index top-level where must not contain $compositeKey", where.has(compositeKey))
                }
            }
        }
        assertEquals("catalog size is pinned so additions update this guard deliberately", 60, DexAssistQuery.entries.size)
    }

    private fun multiRuleAndDescriptorQueries() {
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

        val wireRules = DexMeguvizQueries.rules(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        assertEquals(1, wireRules.size)
        val wireText = wireRules.single().toString(Charsets.UTF_8)
        val wireWhere = JSONObject(wireText).getJSONObject("where")
        assertFalse(wireWhere.has("name"))
        assertFalse(wireWhere.has("owner"))
        KmpGrpcWireAdapter.ENTRY_MARKS.forEach { token -> assertTrue("Missing KMP wire token $token", wireText.contains(token)) }
        val wirePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        assertEquals(1, wirePlans.size)
        assertTrue(wirePlans.single().fallbackOnly)
        assertEquals(DexMeguvizGraphPlans.TAG_KMP_GRPC_WIRE_ENGINE, wirePlans.single().tag)
        val wireGraphIr = decodeQueryIr(wirePlans.single().toQueryIrJson())
        assertTrue(wireGraphIr.contains("onNext") && wireGraphIr.contains("([B)"))

        val composeText = DexMeguvizQueries.rules(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU).joinToString("\n") { it.toString(Charsets.UTF_8) }
        for (mark in listOf(
            CommentComposeMenuAdapter.BUBBLE_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_ITEM_TRACE_MARK,
            CommentComposeMenuAdapter.LEGACY_TRACE_MARK
        )) {
            assertTrue("Missing compose menu trace $mark", composeText.contains(mark))
        }

        val rootRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_ROOT_RESULT)
        val hookRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_HOOK_RESULT)
        assertEquals(1, rootRules.size)
        assertEquals(1, hookRules.size)
        val rootText = rootRules.single().toString(Charsets.UTF_8)
        val hookText = hookRules.single().toString(Charsets.UTF_8)
        val rootWhere = JSONObject(rootText).getJSONObject("where")
        val hookWhere = JSONObject(hookText).getJSONObject("where")
        assertFalse(rootWhere.has("name"))
        assertFalse(hookWhere.has("name"))
        assertFalse(rootText.contains("/system/bin/su"))
        assertFalse(hookText.contains("/system/bin/su"))
        assertTrue(rootText.contains("Ljava/util/Set;"))
        SystemEnvironmentAdapter.ENV_STRINGS.forEach { assertTrue("Missing ENV_STRINGS token $it", rootText.contains(it)) }
        SystemEnvironmentAdapter.HOOK_STRINGS.forEach { assertTrue("Missing HOOK_STRINGS token $it", hookText.contains(it)) }

        val member = JSONObject(DexMeguvizQueries.ownerMembers("Lcom/example/Owner;").toString(Charsets.UTF_8))
        assertEquals("Lcom/example/Owner;", member.getJSONObject("where").getString("owner"))
        val declaration = JSONObject(DexMeguvizQueries.declaration("Lcom/example/Owner;", "run", "(I)V").toString(Charsets.UTF_8)).getJSONObject("where")
        assertEquals("Lcom/example/Owner;", declaration.getString("owner"))
        assertEquals("run", declaration.getString("name"))
        assertEquals("(I)V", declaration.getString("prototype"))
        val byProto = JSONObject(DexMeguvizQueries.methodByNameAndPrototype("q0", "(I)V").toString(Charsets.UTF_8)).getJSONObject("where")
        assertFalse(byProto.has("owner"))
        assertEquals("q0", byProto.getString("name"))
        assertEquals("(I)V", byProto.getString("prototype"))

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

    private fun graphPlansAndQueryIrVerification() {
        for (query in DexAssistQuery.entries) {
            val plans = DexMeguvizQueries.graphPlans(query)
            for (plan in plans) {
                val jsonText = plan.toQueryIrJson()
                assertTrue("${plan.tag} must serialize non-blank QueryIR", jsonText.isNotBlank())
                assertTrue("${plan.tag} QueryIR must stay under 64 KiB", jsonText.toByteArray(Charsets.UTF_8).size <= 65536)
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

        val earlyLoad = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER).single().toQueryIrJson())
        assertTrue(earlyLoad.contains("RecyclerView\$OnScrollListener") && earlyLoad.contains("RecyclerView\$OnChildAttachStateChangeListener"))
        assertFalse(earlyLoad.contains("PegasusFragment"))

        val replyMapper = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_REPLY_MAPPER).single().toQueryIrJson())
        assertTrue(replyMapper.contains("ReplyInfo") && replyMapper.contains("CommentItem") && replyMapper.contains("getId") && replyMapper.contains("getContent"))
        assertTrue(replyMapper.contains("\"min\":1") && replyMapper.contains("\"max\":8"))
        assertFalse(replyMapper.contains("comment3/data/source"))

        val sponsorScope = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.SPONSOR_CONTAINER_SCOPE).single().toQueryIrJson())
        assertTrue(sponsorScope.contains("SuspendLambda") && sponsorScope.contains("Landroid/content/Context;") && sponsorScope.contains("Ltv/danmaku/biliplayerv2/PlayerContainer;") && sponsorScope.contains("group_by_owner") && sponsorScope.contains("semi_join"))

        val sponsorWrapper = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.SPONSOR_PLAYER_WRAPPER).single().toQueryIrJson())
        assertTrue(sponsorWrapper.contains("seekTo") && sponsorWrapper.contains("(IZ)V") && sponsorWrapper.contains("IPlayerCoreService") && sponsorWrapper.contains("CoroutineScope") && sponsorWrapper.contains("group_by_owner") && sponsorWrapper.contains("semi_join"))

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

        val composeJson = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU).first().toQueryIrJson())
        assertTrue(composeJson.contains("PaddingValues"))
        assertFalse(composeJson.contains("kntr/common/comment"))

        val dividerJson = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.PEGASUS_DIVIDER).single().toQueryIrJson())
        assertTrue(dividerJson.contains("ItemDecoration") && dividerJson.contains("Landroid/graphics/Paint;") && dividerJson.contains("Landroid/graphics/RectF;") && dividerJson.contains("Landroid/content/Context;") && dividerJson.contains("onDrawOver") && dividerJson.contains("group_by_owner") && dividerJson.contains("semi_join"))

        assertTrue(DexMeguvizQueries.graphPlans(DexAssistQuery.SEARCH_HOME_STATE).isEmpty())
        assertTrue(DexMeguvizQueries.graphPlans(DexAssistQuery.PLAYER_END_PAGE_MERGER).isEmpty())

        val replyBindingJson = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE).single().toQueryIrJson())
        assertTrue(replyBindingJson.contains("ViewBinding") && replyBindingJson.contains("TintImageView") && replyBindingJson.contains("TintTextView") && replyBindingJson.contains("ConstraintLayout") && replyBindingJson.contains("LinearLayout") && replyBindingJson.contains("group_by_owner"))
        val replyMethodIr = decodeQueryIr(DexMeguvizGraphPlans.commentReplyMethodsForBinding("La/b;").toQueryIrJson())
        assertTrue(replyMethodIr.contains("CommentItem") && replyMethodIr.contains("La/b;"))

        val storyDefaultJson = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.STORY_QUALITY_DEFAULT).single().toQueryIrJson())
        assertTrue(storyDefaultJson.contains("PlayerSettingHelper") && storyDefaultJson.contains("getDefaultQuality"))
        assertFalse(storyDefaultJson.contains("com/bilibili/video/story/player/quality"))
        assertFalse(JSONObject(storyDefaultJson).getJSONObject("where").has("owner"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR).isEmpty())
        val quickReplyJson = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR).single().toQueryIrJson())
        assertTrue(quickReplyJson.contains("PublishDialogIntent;") && quickReplyJson.contains("Lkotlin/coroutines/Continuation;"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.PAUSED_AD_COUNTDOWN).isEmpty())
        val pausedCountdownPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.PAUSED_AD_COUNTDOWN)
        assertEquals(2, pausedCountdownPlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN, pausedCountdownPlans[0].tag)
        val pausedCountdownJson = decodeQueryIr(pausedCountdownPlans[0].toQueryIrJson())
        assertTrue(pausedCountdownJson.contains("PausedPageService") && pausedCountdownJson.contains("KPauseBar") && pausedCountdownJson.contains("\"label\"") && pausedCountdownJson.contains("invokeSuspend") && pausedCountdownJson.contains("group_by_owner"))
        assertEquals(DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN_USES, pausedCountdownPlans[1].tag)
        val pausedCountdownUsesJson = decodeQueryIr(pausedCountdownPlans[1].toQueryIrJson())
        assertTrue(pausedCountdownUsesJson.contains("IVDPausedPage") && pausedCountdownUsesJson.contains("getCountDownView") && pausedCountdownUsesJson.contains("invokeSuspend"))
        assertFalse(pausedCountdownUsesJson.contains("KPauseBar"))

        val pausedApiRule = DexMeguvizQueries.rules(DexAssistQuery.PAUSED_AD_REQUEST_API).single().toString(Charsets.UTF_8)
        assertTrue(pausedApiRule.contains("ContinuationImpl"))
        assertEquals(16, JSONObject(pausedApiRule).getJSONObject("where").getJSONArray("parameters").length())
        val pausedApiGraph = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.PAUSED_AD_REQUEST_API).single().toQueryIrJson())
        assertTrue(pausedApiGraph.contains("(JJLcom/bapis/bilibili/app/viewunite/v1/KTabType;") && pausedApiGraph.contains("ContinuationImpl;)Ljava/lang/Object;"))

        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.BRAND_SPLASH_KNTR_EXIT).isEmpty())
        val exitPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_EXIT)
        assertEquals(2, exitPlans.size)
        val exitCurrent = decodeQueryIr(exitPlans[0].toQueryIrJson())
        assertTrue(exitCurrent.contains("Lkotlin/jvm/functions/Function0;") && exitCurrent.contains("Landroidx/compose/runtime/MutableState;") && exitCurrent.contains("Landroidx/compose/runtime/State;") && exitCurrent.contains("\"I\""))
        assertFalse(exitCurrent.contains("\$onExit"))
        val exitLegacy = decodeQueryIr(exitPlans[1].toQueryIrJson())
        assertTrue(exitLegacy.contains("BrandSplashPageKt\$BrandSplashPage\$") && exitLegacy.contains("invokeSuspend"))

        val pageRule = DexMeguvizQueries.rules(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE).single().toString(Charsets.UTF_8)
        assertTrue(pageRule.contains(BrandSplashKntrAdapter.PAGE_TRACE_MARK))
        val pageGraph = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE).first().toQueryIrJson())
        assertTrue(pageGraph.contains(BrandSplashKntrAdapter.PAGE_TRACE_MARK) && pageGraph.contains("Lkotlin/jvm/functions/Function0;") && pageGraph.contains("Ljava/lang/String;") && pageGraph.contains("Landroidx/compose/ui/Modifier;") && pageGraph.contains("Landroidx/compose/runtime/Composer;"))

        val homeTabRule = DexMeguvizQueries.rules(DexAssistQuery.HOME_TAB_RESOURCE).single().toString(Charsets.UTF_8)
        assertTrue(homeTabRule.contains("HomeFragmentV2"))
        val homeTabPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_TAB_RESOURCE)
        assertEquals(2, homeTabPlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE, homeTabPlans[0].tag)
        assertEquals(DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE_MODEL, homeTabPlans[1].tag)
        val homeTabBuildGraph = decodeQueryIr(homeTabPlans[0].toQueryIrJson())
        assertTrue(homeTabBuildGraph.contains("Ltv/danmaku/bili/ui/main2/HomeFragmentV2;") && homeTabBuildGraph.contains("(Ljava/util/List;)Ljava/util/List;"))
        val homeTabModelGraph = decodeQueryIr(homeTabPlans[1].toQueryIrJson())
        assertTrue(homeTabModelGraph.contains(HomeTabResourceAdapter.EQUALS_ANCHOR))

        for (q in listOf(
            DexAssistQuery.AI_PLAYLIST_DIRECTOR,
            DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY,
            DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT,
            DexAssistQuery.LEGACY_STORY_DANMAKU_BIND,
            DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS
        )) {
            assertTrue("$q flat rules should be empty", DexMeguvizQueries.rules(q).isEmpty())
        }
        val aiDirectorGraph = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.AI_PLAYLIST_DIRECTOR).single().toQueryIrJson())
        assertTrue(aiDirectorGraph.contains("PlaylistDirectorSerialOperationsService") && aiDirectorGraph.contains("switchToNext") && aiDirectorGraph.contains("hasNext"))

        val followUpGraphs = DexMeguvizQueries.graphPlans(DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY).joinToString("\n") { decodeQueryIr(it.toQueryIrJson()) }
        assertTrue(followUpGraphs.contains("RecommendUpRepository") && followUpGraphs.contains("Lkotlin/coroutines/Continuation;") && followUpGraphs.contains("\"kind\":\"constructor\"") && followUpGraphs.contains("\"min\":2") && followUpGraphs.contains("\"max\":4") && followUpGraphs.contains("Lkotlin/coroutines/Continuation;)V"))

        val relatePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT)
        assertEquals(2, relatePlans.size)
        assertEquals(DexMeguvizGraphPlans.TAG_DETAIL_RELATE_GAME_IN_PKG, relatePlans[0].tag)
        val relateInPkg = decodeQueryIr(relatePlans[0].toQueryIrJson())
        assertTrue(relateInPkg.contains("com/bilibili/ship/theseus/united/page/intro/module/relate/game/") && relateInPkg.contains("Landroid/content/Context;") && relateInPkg.contains("Landroid/view/LayoutInflater;") && relateInPkg.contains("Landroid/view/ViewGroup;"))
        assertEquals(DexMeguvizGraphPlans.TAG_DETAIL_RELATE_GAME_BIND_USES, relatePlans[1].tag)
        val relateBindUses = decodeQueryIr(relatePlans[1].toQueryIrJson())
        assertTrue(relateBindUses.contains("Ltv/danmaku/bili/widget/ratingbar/ReviewRatingBar;") && relateBindUses.contains("setRating") && relateBindUses.contains("foreign_link_from_spmid") && relateBindUses.contains("content_id") && relateBindUses.contains("from_spmid") && relateBindUses.contains("Lkotlin/coroutines/Continuation;"))
        assertFalse(relateBindUses.contains("com/bilibili/ship/theseus/united/page/intro/module/relate/game/"))

        val legacyStoryGraph = decodeQueryIr(DexMeguvizQueries.graphPlans(DexAssistQuery.LEGACY_STORY_DANMAKU_BIND).single().toQueryIrJson())
        assertTrue(legacyStoryGraph.contains("StoryAbsController"))

        val longPressGraphs = DexMeguvizQueries.graphPlans(DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS).joinToString("\n") { decodeQueryIr(it.toQueryIrJson()) }
        assertTrue(longPressGraphs.contains("TripleSpeedService") && longPressGraphs.contains("()Ljava/lang/Float;"))

        for (query in DexAssistQuery.entries) {
            val expectedDirect = query == DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE ||
                DexMeguvizQueries.graphPlans(query).any { !it.fallbackOnly }
            assertEquals("$query requiresArchiveSnapshot mismatch", expectedDirect, DexMeguvizGraphPlans.requiresArchiveSnapshot(query))
        }
        assertFalse(DexMeguvizGraphPlans.requiresArchiveSnapshot(emptySet()))
        assertEquals(DexMeguvizAssistEngine.ScanStrategy.TIER1_THEN_OPTIONAL_TIER2, DexMeguvizAssistEngine.scanStrategy(emptySet()))
        assertEquals(
            DexMeguvizAssistEngine.ScanStrategy.TIER1_THEN_OPTIONAL_TIER2,
            DexMeguvizAssistEngine.scanStrategy(setOf(DexAssistQuery.PEGASUS_DIVIDER, DexAssistQuery.COMMENT_COMPOSE_MORE_MENU))
        )
        assertEquals(DexMeguvizAssistEngine.ScanStrategy.DIRECT_TIER2_SNAPSHOT, DexMeguvizAssistEngine.scanStrategy(setOf(DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE)))
        val nonFallbackQuery = DexAssistQuery.entries.first { q -> DexMeguvizQueries.graphPlans(q).any { !it.fallbackOnly } }
        assertEquals(
            DexMeguvizAssistEngine.ScanStrategy.DIRECT_TIER2_SNAPSHOT,
            DexMeguvizAssistEngine.scanStrategy(setOf(DexAssistQuery.PEGASUS_DIVIDER, nonFallbackQuery))
        )
    }

    private fun phase3GraphLaneAndTier1FallbackPolicy() {
        val snapshot = "snap-1"
        assertNull(DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.SOURCE_SCOPE_INCOMPLETE.ordinal, 0L, 100, 100, 3))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForEntity(snapshot, "other", ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10, 1))
        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.RESULT_LIMIT.ordinal, 0L, 10, 10, 1))
        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10, DexGraphResultPolicy.MAX_MATCHES + 1))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.BUDGET_EXCEEDED.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10, 1))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 1L, 10, 10, 1))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForEntity(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 9, 10, 1))

        assertNull(DexGraphResultPolicy.failureForGraph(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.SOURCE_SCOPE_INCOMPLETE.ordinal, 0L, 4))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForGraph(snapshot, snapshot, ExecutionStatus.CORRUPT_INPUT.ordinal, DecisionReason.NONE.ordinal, 0L, 0))
        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.failureForGraph(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, DexGraphResultPolicy.MAX_MATCHES + 1))

        assertNull(DexGraphResultPolicy.failureForMethodType(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 50, 50, 1))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.failureForMethodType(snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 49, 50, 1))

        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.resolveOutcomeFailure(Reason.TOO_MANY_MATCHES, null, hasCandidates = true, bindFailures = 0))
        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.TOO_MANY_MATCHES, hasCandidates = true, bindFailures = 0))
        assertEquals(Reason.TOO_MANY_MATCHES to null, DexMeguvizAssistEngine.applyGraphRejection(null, null, Reason.TOO_MANY_MATCHES))
        assertEquals(null to Reason.NO_MATCH, DexMeguvizAssistEngine.applyGraphRejection(null, null, Reason.NO_MATCH))
        assertNull(DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.QUERY_FAILED, hasCandidates = true, bindFailures = 0))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.QUERY_FAILED, hasCandidates = false, bindFailures = 0))
        assertEquals(Reason.TOO_MANY_MATCHES, DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.TOO_MANY_MATCHES, hasCandidates = false, bindFailures = 0))
        assertEquals(Reason.QUERY_FAILED, DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = false, bindFailures = 1))
        assertEquals(Reason.NO_MATCH, DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = false, bindFailures = 0))
        assertNull(DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = true, bindFailures = 1))

        val sampleQuery = GraphQuery(
            relation = GraphExpression.groupByOwner(
                inputs = listOf(
                    GraphExpression.label(
                        GraphExpression.scan(
                            EntityRelation.FIELD_DEFS,
                            GraphPredicate.from(EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/content/Context;"))
                        ),
                        "ctx"
                    )
                ),
                requiredRoles = listOf("ctx")
            ),
            maxResults = 33,
            maxWork = DexMeguvizGraphPlans.GRAPH_MAX_WORK
        )
        val scoped = DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", "split.feature"))
        val jsonText = scoped.toQueryIrJson()
        assertFalse("scoped QueryIR must stay single-line compact", jsonText.contains('\n'))
        val ir = JSONObject(jsonText)
        val slots = ir.getJSONObject("scope").getJSONArray("slots")
        assertEquals(listOf("base", "split.feature"), (0 until slots.length()).map(slots::getString))
        val caps = ir.getJSONArray("required_capabilities")
        assertTrue((0 until caps.length()).map(caps::getString).contains("inherited_fields"))
        assertEquals(33, ir.getInt("max_results"))
        assertEquals(DexMeguvizGraphPlans.GRAPH_MAX_WORK, ir.getLong("max_work"))
        assertThrows(IllegalArgumentException::class.java) { DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", " ")) }
        assertThrows(IllegalArgumentException::class.java) { DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", "base")) }

        assertEquals(listOf(0..127, 128..149), DexMeguvizAssistEngine.calculateCandidateChunks(List(150) { 32 }))
        assertEquals(listOf(0..1, 2..2), DexMeguvizAssistEngine.calculateCandidateChunks(listOf(4000, 96, 1)))
        assertTrue(DexMeguvizAssistEngine.calculateCandidateChunks(emptyList()).isEmpty())

        val resolvedSlots = DexMeguvizAssistEngine.resolveSplitSlotNames(
            splitPaths = listOf("/data/app/split_feat.apk", "/data/app/split_other.apk", "/data/app/split_other2.apk"),
            splitNames = listOf("feature_video", null, "feature_video")
        )
        assertEquals(3, resolvedSlots.size)
        assertEquals("feature_video", resolvedSlots[0])
        assertEquals("other", resolvedSlots[1])
        assertTrue("split slots must be distinct and printable", resolvedSlots.distinct().size == 3 && resolvedSlots[2] != "feature_video")

        for (redundant in listOf(
            DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER,
            DexAssistQuery.COMMENT_REPLY_MAPPER,
            DexAssistQuery.SPONSOR_CONTAINER_SCOPE,
            DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
            DexAssistQuery.PEGASUS_DIVIDER,
            DexAssistQuery.STORY_QUALITY_DEFAULT,
            DexAssistQuery.PAUSED_AD_REQUEST_API,
            DexAssistQuery.BRAND_SPLASH_KNTR_PAGE,
            DexAssistQuery.COMMENT_COMPOSE_MORE_MENU,
            DexAssistQuery.HOME_CHROME_TOP,
            DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_BACKDROP,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_BOTTOM,
            DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT,
            DexAssistQuery.HOME_CHROME_BOTTOM_ITEM,
            DexAssistQuery.BRAND_SPLASH_KNTR_DRAW,
            DexAssistQuery.PAUSED_AD_COUNTDOWN
        )) {
            val plans = DexMeguvizQueries.graphPlans(redundant)
            assertTrue("$redundant plans should be fallbackOnly", plans.isNotEmpty() && plans.all { it.fallbackOnly })
            plans.forEach { JSONObject(it.toQueryIrJson()) }
        }
        assertFalse(DexMeguvizGraphPlans.requiresArchiveSnapshot(setOf(DexAssistQuery.PAUSED_AD_COUNTDOWN)))
        assertEquals(2, DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE).count { it.fallbackOnly })
        assertEquals(3, DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU).count { it.fallbackOnly })
        val homeTabPlans = DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_TAB_RESOURCE)
        assertTrue(homeTabPlans[0].fallbackOnly)
        assertFalse(homeTabPlans[1].fallbackOnly)

        val backdropIr = DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_CHROME_TOP_BACKDROP).single().toQueryIrJson()
        val rightActionsIr = DexMeguvizQueries.graphPlans(DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS).single().toQueryIrJson()
        assertEquals(backdropIr, rightActionsIr)

        assertThrows(IllegalArgumentException::class.java) { DexMeguvizAssistEngine.withArchiveSnapshot(emptyList()) {} }
        val nativeMissing = DexMeguvizAssistEngine.resolveAll(
            setOf(DexAssistQuery.BLOCK_UPDATE),
            listOf("/nonexistent/base.apk"),
            javaClass.classLoader!!
        )
        assertEquals(DexAssistResult.Unavailable(Reason.NATIVE_UNAVAILABLE), nativeMissing.getValue(DexAssistQuery.BLOCK_UPDATE))
    }

    @Test
    fun verifyAllCases() {
        everyQueryProducesBoundedRules()
        multiRuleAndDescriptorQueries()
        graphPlansAndQueryIrVerification()
        phase3GraphLaneAndTier1FallbackPolicy()
    }
}
