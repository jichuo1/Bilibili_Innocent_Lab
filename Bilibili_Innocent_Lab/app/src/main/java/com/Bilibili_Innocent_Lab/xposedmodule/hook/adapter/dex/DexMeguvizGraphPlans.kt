package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AiPlaylistDirectorAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailRelateGameAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PausedAdDexAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import io.github.jichuo1.dexmeguviz.AdvancedQuery
import io.github.jichuo1.dexmeguviz.DecisionReason
import io.github.jichuo1.dexmeguviz.EntityPredicate
import io.github.jichuo1.dexmeguviz.EntityQuery
import io.github.jichuo1.dexmeguviz.EntityRelation
import io.github.jichuo1.dexmeguviz.EntityTextField
import io.github.jichuo1.dexmeguviz.ExecutionStatus
import io.github.jichuo1.dexmeguviz.FieldTarget
import io.github.jichuo1.dexmeguviz.GraphExpression
import io.github.jichuo1.dexmeguviz.GraphJoinKey
import io.github.jichuo1.dexmeguviz.GraphPredicate
import io.github.jichuo1.dexmeguviz.GraphQuery
import io.github.jichuo1.dexmeguviz.GraphRolePolicy
import io.github.jichuo1.dexmeguviz.InvokeTarget
import io.github.jichuo1.dexmeguviz.MethodPredicate
import io.github.jichuo1.dexmeguviz.MethodTextField
import io.github.jichuo1.dexmeguviz.MethodTypePredicate
import io.github.jichuo1.dexmeguviz.MethodTypeQuery
import io.github.jichuo1.dexmeguviz.MethodTypeTarget
import io.github.jichuo1.dexmeguviz.QueryIntent
import io.github.jichuo1.dexmeguviz.RawDexFlags
import io.github.jichuo1.dexmeguviz.RawFlagOperation
import io.github.jichuo1.dexmeguviz.ReflectionFlags
import io.github.jichuo1.dexmeguviz.ReflectionMethodPredicate
import io.github.jichuo1.dexmeguviz.ReflectionMethodUsesQuery
import io.github.jichuo1.dexmeguviz.StaticTextMatch
import io.github.jichuo1.dexmeguviz.TextOperation
import io.github.jichuo1.dexmeguviz.TypeUseOpcode
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/** DexMeguviz 1.2.0 类型化图查询计划，复用同一 SourceSetSnapshot 并列执行。 */
internal sealed interface DexGraphPlan {
    val tag: String
    val fallbackOnly: Boolean
    fun toQueryIrJson(): String

    data class Entity(
        override val tag: String,
        val query: EntityQuery,
        override val fallbackOnly: Boolean = false
    ) : DexGraphPlan {
        override fun toQueryIrJson(): String = query.toQueryIrJson()
    }

    data class Graph(
        override val tag: String,
        val query: GraphQuery,
        override val fallbackOnly: Boolean = false
    ) : DexGraphPlan {
        override fun toQueryIrJson(): String = query.toQueryIrJson()
    }

    data class MethodType(
        override val tag: String,
        val query: MethodTypeQuery,
        override val fallbackOnly: Boolean = false
    ) : DexGraphPlan {
        override fun toQueryIrJson(): String = query.toQueryIrJson()
    }

    data class MethodUses(
        override val tag: String,
        val query: ReflectionMethodUsesQuery,
        override val fallbackOnly: Boolean = false
    ) : DexGraphPlan {
        override fun toQueryIrJson(): String = query.toQueryIrJson()
    }
}

/**
 * 纯 JVM 可测的图结果完整性门禁。
 *
 * 单归档 [io.github.jichuo1.dexmeguviz.NativeEngine.SourceSetSnapshot] 不声称代表完整运行时 ClassLoader，
 * 因此不能拒绝 `Coverage.PARTIAL` 或 `DecisionReason.SOURCE_SCOPE_INCOMPLETE`。
 */
internal object DexGraphResultPolicy {
    const val MAX_MATCHES = DexMeguvizQueries.MAX_MATCHES

    fun failureForEntity(
        expectedSnapshotId: String,
        resultSnapshotId: String?,
        executionStatus: Int,
        reason: Int,
        unknownRows: Long,
        visitedRows: Int,
        totalRows: Int,
        candidateCount: Int
    ): Reason? = commonFailure(
        expectedSnapshotId = expectedSnapshotId,
        resultSnapshotId = resultSnapshotId,
        executionStatus = executionStatus,
        reason = reason,
        unknownRows = unknownRows,
        count = candidateCount,
        incomplete = visitedRows != totalRows
    )

    fun failureForGraph(
        expectedSnapshotId: String,
        resultSnapshotId: String?,
        executionStatus: Int,
        reason: Int,
        unknownRows: Long,
        rowCount: Int
    ): Reason? = commonFailure(
        expectedSnapshotId = expectedSnapshotId,
        resultSnapshotId = resultSnapshotId,
        executionStatus = executionStatus,
        reason = reason,
        unknownRows = unknownRows,
        count = rowCount,
        incomplete = false
    )

    fun failureForMethodType(
        expectedSnapshotId: String,
        resultSnapshotId: String?,
        executionStatus: Int,
        reason: Int,
        unknownRows: Long,
        visitedMethods: Int,
        totalMethods: Int,
        rowCount: Int
    ): Reason? = commonFailure(
        expectedSnapshotId = expectedSnapshotId,
        resultSnapshotId = resultSnapshotId,
        executionStatus = executionStatus,
        reason = reason,
        unknownRows = unknownRows,
        count = rowCount,
        incomplete = visitedMethods != totalMethods
    )

    fun resolveOutcomeFailure(
        hardFailure: Reason?,
        graphFailure: Reason?,
        hasCandidates: Boolean,
        bindFailures: Int
    ): Reason? {
        hardFailure?.let { return it }
        if (graphFailure == Reason.TOO_MANY_MATCHES) return Reason.TOO_MANY_MATCHES
        if (hasCandidates) return null
        graphFailure?.let { return it }
        return if (bindFailures > 0) Reason.QUERY_FAILED else Reason.NO_MATCH
    }

    private fun commonFailure(
        expectedSnapshotId: String,
        resultSnapshotId: String?,
        executionStatus: Int,
        reason: Int,
        unknownRows: Long,
        count: Int,
        incomplete: Boolean
    ): Reason? = when {
        resultSnapshotId != expectedSnapshotId -> Reason.QUERY_FAILED
        reason == DecisionReason.RESULT_LIMIT.ordinal -> Reason.TOO_MANY_MATCHES
        executionStatus != ExecutionStatus.COMPLETE.ordinal ||
            unknownRows != 0L ||
            incomplete ||
            reason == DecisionReason.UNKNOWN_ATTRIBUTE.ordinal -> Reason.QUERY_FAILED
        count > MAX_MATCHES -> Reason.TOO_MANY_MATCHES
        else -> null
    }
}

/** 抗 R8 扁平化与参数个数漂移的 1.2.0 图计划目录。 */
internal object DexMeguvizGraphPlans {
    const val GRAPH_MAX_WORK = 100_000_000L

    /** 为 `group_by_owner`/`join` 声明快照输入槽位 Scope，确保单归档 `AdvancedExecution` 满足 `DMG_COVERAGE_COMPLETE` 且 `unknownRows == 0`。 */
    internal fun scopedAdvancedGraphQuery(query: GraphQuery, slots: List<String>): AdvancedQuery {
        require(slots.isNotEmpty()) { "At least one snapshot input slot is required" }
        require(slots.none { it.isBlank() }) { "Snapshot input slots must not be blank" }
        require(slots.distinct().size == slots.size) { "Snapshot input slots must be distinct" }
        val json = JSONObject(query.toQueryIrJson())
        json.put("scope", JSONObject().put("slots", JSONArray(slots)))
        val caps = json.optJSONArray("required_capabilities") ?: JSONArray()
        val hasInherited = (0 until caps.length()).any { caps.optString(it) == "inherited_fields" }
        if (!hasInherited) caps.put("inherited_fields")
        json.put("required_capabilities", caps)
        return AdvancedQuery(json.toString())
    }

    const val TAG_HOME_EARLY_LOAD = "home_early_load_entity"
    const val TAG_COMMENT_REPLY_MAPPER = "comment_reply_mapper_uses"
    const val TAG_SPONSOR_CONTAINER_SCOPE = "sponsor_container_scope_graph"
    const val TAG_SPONSOR_PLAYER_WRAPPER = "sponsor_player_wrapper_graph"
    const val TAG_SPEED_FACTORY_4PARAM = "speed_factory_4param_uses"
    const val TAG_SPEED_COMPACT_OPEN = "speed_compact_open_type"
    const val TAG_COMPOSE_GROUPED_FALLBACK = "compose_grouped_fallback_entity"
    const val TAG_COMPOSE_GROUPED_ITEM_FALLBACK = "compose_grouped_item_fallback_entity"
    const val TAG_COMPOSE_LEGACY_ROW_FALLBACK = "compose_legacy_row_fallback_entity"
    const val TAG_PEGASUS_DIVIDER = "pegasus_divider_graph"
    const val TAG_COMMENT_REPLY_BINDING = "comment_reply_binding_graph"
    const val TAG_STORY_QUALITY_DEFAULT = "story_quality_default_uses"
    const val TAG_COMMENT_QUICK_REPLY = "comment_quick_reply_entity"
    const val TAG_PAUSED_AD_COUNTDOWN = "paused_ad_countdown_graph"
    const val TAG_PAUSED_AD_COUNTDOWN_USES = "paused_ad_countdown_uses"
    const val TAG_PAUSED_AD_REQUEST_API = "paused_ad_request_api_entity"
    const val TAG_BRAND_SPLASH_KNTR_EXIT_CURRENT = "brand_splash_kntr_exit_current_graph"
    const val TAG_BRAND_SPLASH_KNTR_EXIT_LEGACY = "brand_splash_kntr_exit_legacy_graph"
    const val TAG_BRAND_SPLASH_KNTR_DRAW_FALLBACK = "brand_splash_kntr_draw_fallback_graph"
    const val TAG_BRAND_SPLASH_KNTR_PAGE = "brand_splash_kntr_page_entity"
    const val TAG_BRAND_SPLASH_KNTR_PAGE_FALLBACK = "brand_splash_kntr_page_fallback_uses"
    const val TAG_HOME_CHROME_TOP_FALLBACK = "home_chrome_top_fallback_entity"
    const val TAG_HOME_CHROME_TOP_LAYOUT_FALLBACK = "home_chrome_top_layout_fallback_entity"
    const val TAG_HOME_CHROME_TOP_BACKDROP_FALLBACK = "home_chrome_top_backdrop_fallback_graph"
    const val TAG_HOME_CHROME_TOP_RIGHT_ACTIONS_FALLBACK = "home_chrome_top_right_actions_fallback_graph"
    const val TAG_HOME_CHROME_TOP_CONTENT_CLOSURE_FALLBACK = "home_chrome_top_content_closure_fallback_graph"
    const val TAG_HOME_CHROME_TOP_CONTENT_STATIC_FALLBACK = "home_chrome_top_content_static_fallback_entity"
    const val TAG_HOME_CHROME_BOTTOM_FALLBACK = "home_chrome_bottom_fallback_entity"
    const val TAG_HOME_CHROME_BOTTOM_CONTENT_CLOSURE_FALLBACK = "home_chrome_bottom_content_closure_fallback_graph"
    const val TAG_HOME_CHROME_BOTTOM_CONTENT_STATIC_FALLBACK = "home_chrome_bottom_content_static_fallback_entity"
    const val TAG_HOME_CHROME_BOTTOM_ITEM_FALLBACK = "home_chrome_bottom_item_fallback_entity"
    const val TAG_HOME_TAB_RESOURCE = "home_tab_resource_entity"
    const val TAG_HOME_TAB_RESOURCE_MODEL = "home_tab_resource_model_uses"
    const val TAG_AI_PLAYLIST_DIRECTOR = "ai_playlist_director"
    const val TAG_FOLLOW_UP_RECOMMEND_THESEUS2 = "follow_up_recommend_theseus2"
    const val TAG_FOLLOW_UP_RECOMMEND_THESEUS = "follow_up_recommend_theseus"
    const val TAG_DETAIL_RELATE_GAME_IN_PKG = "detail_relate_game_in_pkg"
    const val TAG_DETAIL_RELATE_GAME_BIND_USES = "detail_relate_game_bind_uses"
    const val TAG_LEGACY_STORY_DANMAKU_BIND = "legacy_story_danmaku_bind"
    const val TAG_THESEUS2_LONG_PRESS_LAUNCH = "theseus2_long_press_launch"
    const val TAG_THESEUS2_LONG_PRESS_PORT = "theseus2_long_press_port"
    const val TAG_KMP_GRPC_WIRE_ENGINE = "kmp_grpc_wire_engine_graph"

    private const val MAX_RESULTS = DexMeguvizQueries.MAX_MATCHES + 1
    private val SYSTEM_DESCRIPTOR_PREFIXES = listOf(
        "Ljava/", "Ljavax/", "Lkotlin/", "Lkotlinx/", "Landroid/", "Landroidx/"
    )
    private val PRIMITIVE_DESCRIPTORS = listOf("Z", "B", "S", "C", "I", "J", "F", "D", "V")

    internal fun isSystemDescriptor(descriptor: String): Boolean =
        SYSTEM_DESCRIPTOR_PREFIXES.any { descriptor.startsWith(it) }

    internal fun requiresArchiveSnapshot(query: DexAssistQuery): Boolean =
        query == DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE || plans(query).any { !it.fallbackOnly }

    internal fun requiresArchiveSnapshot(queries: Set<DexAssistQuery>): Boolean =
        queries.any(::requiresArchiveSnapshot)

    fun plans(query: DexAssistQuery): List<DexGraphPlan> = when (query) {
        DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> listOf(homeEarlyLoadPlan())
        DexAssistQuery.COMMENT_REPLY_MAPPER -> listOf(commentReplyMapperPlan())
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> listOf(sponsorContainerScopePlan())
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER -> listOf(sponsorPlayerWrapperPlan())
        DexAssistQuery.PLAYER_SPEED_MENU -> listOf(speedFactory4ParamPlan(), speedCompactOpenPlan())
        DexAssistQuery.COMMENT_COMPOSE_MORE_MENU -> listOf(
            composeGroupedFallbackPlan(),
            composeGroupedItemFallbackPlan(),
            composeLegacyRowFallbackPlan()
        )
        DexAssistQuery.PEGASUS_DIVIDER -> listOf(pegasusDividerPlan())
        DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE -> listOf(commentReplyBindingPlan())
        DexAssistQuery.STORY_QUALITY_DEFAULT -> listOf(storyQualityDefaultPlan())
        DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR -> listOf(commentQuickReplyPlan())
        DexAssistQuery.PAUSED_AD_COUNTDOWN -> listOf(pausedAdCountdownPlan(), pausedAdCountdownInvokePlan())
        DexAssistQuery.PAUSED_AD_REQUEST_API -> listOf(pausedAdRequestApiPlan())
        DexAssistQuery.BRAND_SPLASH_KNTR_EXIT -> listOf(
            brandSplashKntrExitCurrentPlan(),
            brandSplashKntrExitLegacyPlan()
        )
        DexAssistQuery.BRAND_SPLASH_KNTR_DRAW -> listOf(brandSplashKntrDrawFallbackPlan())
        DexAssistQuery.BRAND_SPLASH_KNTR_PAGE -> listOf(
            brandSplashKntrPagePlan(),
            brandSplashKntrPageFallbackPlan()
        )
        DexAssistQuery.HOME_CHROME_TOP -> listOf(homeTopPlan())
        DexAssistQuery.HOME_CHROME_TOP_LAYOUT -> listOf(homeTopLayoutPlan())
        DexAssistQuery.HOME_CHROME_TOP_BACKDROP -> listOf(homeTopBarPlan(TAG_HOME_CHROME_TOP_BACKDROP_FALLBACK))
        DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS -> listOf(
            homeTopBarPlan(TAG_HOME_CHROME_TOP_RIGHT_ACTIONS_FALLBACK)
        )
        DexAssistQuery.HOME_CHROME_TOP_CONTENT -> listOf(
            homeTopContentClosurePlan(),
            homeTopContentStaticPlan()
        )
        DexAssistQuery.HOME_CHROME_BOTTOM -> listOf(homeBottomPlan())
        DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT -> listOf(
            homeBottomContentClosurePlan(),
            homeBottomContentStaticPlan()
        )
        DexAssistQuery.HOME_CHROME_BOTTOM_ITEM -> listOf(homeBottomItemPlan())
        DexAssistQuery.HOME_TAB_RESOURCE -> listOf(homeTabResourcePlan(), homeTabResourceModelPlan())
        DexAssistQuery.AI_PLAYLIST_DIRECTOR -> listOf(aiPlaylistDirectorPlan())
        DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY -> listOf(
            followUpRecommendPlan(
                TAG_FOLLOW_UP_RECOMMEND_THESEUS2,
                FollowUpRecommendFeatureInstaller.Family.THESEUS2.repository
            ),
            followUpRecommendPlan(
                TAG_FOLLOW_UP_RECOMMEND_THESEUS,
                FollowUpRecommendFeatureInstaller.Family.THESEUS.repository
            )
        )
        DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT -> listOf(
            detailRelateGameInPkgPlan(),
            detailRelateGameBindUsesPlan()
        )
        DexAssistQuery.LEGACY_STORY_DANMAKU_BIND -> listOf(legacyStoryDanmakuBindPlan())
        DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS -> listOf(
            theseus2LongPressLaunchPlan(),
            theseus2LongPressPortPlan()
        )
        DexAssistQuery.KMP_GRPC_WIRE_ENGINE -> listOf(kmpGrpcWireEnginePlan())
        else -> emptyList()
    }

    fun commentReplyMethodsForBinding(bindingDescriptor: String): EntityQuery = EntityQuery(
        EntityRelation.METHOD_DEFS,
        EntityPredicate.all(
            EntityPredicate.normalMethod(),
            EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
            EntityPredicate.parameterCount(2L, 5L),
            EntityPredicate.parameter(
                0L,
                TextOperation.EXACT,
                dexType(CommentReplyButtonAdapter.COMMENT_ITEM)
            ),
            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.CONTAINS, bindingDescriptor)
        ),
        QueryIntent.ENUMERATE,
        MAX_RESULTS,
        GRAPH_MAX_WORK
    )

    private fun homeEarlyLoadPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_EARLY_LOAD,
        EntityQuery(
            EntityRelation.CLASS_DEFS,
            EntityPredicate.all(
                EntityPredicate.text(
                    EntityTextField.SUPERCLASS,
                    TextOperation.EXACT,
                    "Landroidx/recyclerview/widget/RecyclerView\$OnScrollListener;"
                ),
                EntityPredicate.text(
                    EntityTextField.DIRECT_INTERFACE,
                    TextOperation.EXACT,
                    "Landroidx/recyclerview/widget/RecyclerView\$OnChildAttachStateChangeListener;"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun commentReplyMapperPlan(): DexGraphPlan {
        val commentItem = dexType("com.bilibili.app.comment3.data.model.CommentItem")
        val replyInfo = dexType("com.bapis.bilibili.main.community.reply.v1.ReplyInfo")
        val replyContent = dexType("com.bapis.bilibili.main.community.reply.v1.Content")
        return DexGraphPlan.MethodUses(
            TAG_COMMENT_REPLY_MAPPER,
            ReflectionMethodUsesQuery(
                ReflectionMethodPredicate.all(
                    ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag()),
                    ReflectionMethodPredicate.metadata(
                        MethodPredicate.all(
                            MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, commentItem),
                            MethodPredicate.parameterCount(1L, 8L),
                            MethodPredicate.parameter(0L, TextOperation.EXACT, replyInfo)
                        )
                    ),
                    ReflectionMethodPredicate.invokesAll(
                        InvokeTarget(
                            owner = exactText(replyInfo),
                            name = exactText("getId"),
                            minParameters = 0L,
                            maxParameters = 0L
                        ),
                        InvokeTarget(
                            owner = exactText(replyInfo),
                            name = exactText("getContent"),
                            returnType = exactText(replyContent),
                            minParameters = 0L,
                            maxParameters = 0L
                        ),
                        InvokeTarget(
                            owner = exactText(commentItem),
                            name = exactText("<init>"),
                            returnType = exactText("V")
                        )
                    )
                ),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun sponsorContainerScopePlan(): DexGraphPlan {
        val classScan = entityScan(
            EntityRelation.CLASS_DEFS,
            EntityPredicate.text(
                EntityTextField.SUPERCLASS,
                TextOperation.EXACT,
                "Lkotlin/coroutines/jvm/internal/SuspendLambda;"
            )
        )
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/content/Context;")
                    ),
                    "ctx"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            dexType(SponsorPlayerAccess.CONTAINER_CLASS)
                        )
                    ),
                    "container"
                )
            ),
            minimum = 2L,
            maximum = 64L,
            requiredRoles = listOf("ctx", "container"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_SPONSOR_CONTAINER_SCOPE,
            GraphQuery(
                GraphExpression.semiJoin(classScan, grouped, GraphJoinKey.SAME_ENTITY),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun sponsorPlayerWrapperPlan(): DexGraphPlan {
        val seekMethods = entityScan(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "seekTo"),
                EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, "(IZ)V")
            )
        )
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            dexType(SponsorPlayerAccess.CORE_CLASS)
                        )
                    ),
                    "core"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lkotlinx/coroutines/CoroutineScope;"
                        )
                    ),
                    "scope"
                )
            ),
            minimum = 2L,
            maximum = 64L,
            requiredRoles = listOf("core", "scope"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_SPONSOR_PLAYER_WRAPPER,
            GraphQuery(
                GraphExpression.semiJoin(seekMethods, grouped, GraphJoinKey.LOCAL_OWNER),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun speedFactory4ParamPlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_SPEED_FACTORY_4PARAM,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag()),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, "Ljava/util/List;"),
                        MethodPredicate.text(
                            MethodTextField.PROTOTYPE,
                            TextOperation.EXACT,
                            "(${dexType(PlayerSpeedLocator.MEDIA_RESOURCE)}Ljava/lang/Integer;ZI)Ljava/util/List;"
                        )
                    )
                ),
                ReflectionMethodPredicate.invokesAll(
                    InvokeTarget(
                        owner = exactText("Ljava/util/ArrayList;"),
                        name = exactText("add"),
                        prototype = exactText("(Ljava/lang/Object;)Z")
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun speedCompactOpenPlan(): DexGraphPlan = DexGraphPlan.MethodType(
        TAG_SPEED_COMPACT_OPEN,
        MethodTypeQuery(
            MethodTypePredicate.all(
                MethodTypePredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                        MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                        MethodPredicate.parameterCount(1L)
                    )
                ),
                MethodTypePredicate.stringsAll(exactText(PlayerSpeedMenuAdapter.OPEN_MARK)),
                MethodTypePredicate.typesAll(
                    MethodTypeTarget(
                        exactText(dexType(PlayerSpeedLocator.THESEUS_SPEED_WIDGET)),
                        TypeUseOpcode.NEW_INSTANCE
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK,
            emptyList()
        )
    )

    private fun composeGroupedFallbackPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_COMPOSE_GROUPED_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.text(
                    EntityTextField.PROTOTYPE,
                    TextOperation.EXACT,
                    "(Ljava/util/List;Lkotlin/jvm/functions/Function1;Landroidx/compose/ui/Modifier;Landroidx/compose/foundation/layout/PaddingValues;Landroidx/compose/runtime/Composer;II)V"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun composeGroupedItemFallbackPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_COMPOSE_GROUPED_ITEM_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(6L),
                nonSystemParameter(0L),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Landroidx/compose/foundation/layout/PaddingValues;"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "Z"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun composeLegacyRowFallbackPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_COMPOSE_LEGACY_ROW_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(8L, 9L),
                EntityPredicate.parameter(0L, TextOperation.PREFIX, "Lkntr/common/bilitheme/compose/bottomsheet/"),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Ljava/lang/String;"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "Landroidx/compose/ui/graphics/painter/Painter;"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Landroidx/compose/ui/graphics/painter/Painter;"),
                EntityPredicate.any(
                    EntityPredicate.all(
                        EntityPredicate.parameterCount(8L),
                        EntityPredicate.parameter(5L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                        EntityPredicate.parameter(6L, TextOperation.EXACT, "I"),
                        EntityPredicate.parameter(7L, TextOperation.EXACT, "I")
                    ),
                    EntityPredicate.all(
                        EntityPredicate.parameterCount(9L),
                        EntityPredicate.parameter(5L, TextOperation.EXACT, "Z"),
                        EntityPredicate.parameter(6L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                        EntityPredicate.parameter(7L, TextOperation.EXACT, "I"),
                        EntityPredicate.parameter(8L, TextOperation.EXACT, "I")
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun pegasusDividerPlan(): DexGraphPlan {
        val decorationClasses = entityScan(
            EntityRelation.CLASS_DEFS,
            EntityPredicate.text(
                EntityTextField.SUPERCLASS,
                TextOperation.EXACT,
                "Landroidx/recyclerview/widget/RecyclerView\$ItemDecoration;"
            )
        )
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/graphics/Paint;")
                    ),
                    "paint"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/graphics/RectF;")
                    ),
                    "rect"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/content/Context;")
                    ),
                    "ctx"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "onDrawOver"),
                            EntityPredicate.text(
                                EntityTextField.PROTOTYPE,
                                TextOperation.EXACT,
                                "(Landroid/graphics/Canvas;Landroidx/recyclerview/widget/RecyclerView;Landroidx/recyclerview/widget/RecyclerView\$State;)V"
                            )
                        )
                    ),
                    "draw"
                )
            ),
            minimum = 4L,
            maximum = 64L,
            requiredRoles = listOf("paint", "rect", "ctx", "draw"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_PEGASUS_DIVIDER,
            GraphQuery(
                GraphExpression.semiJoin(decorationClasses, grouped, GraphJoinKey.SAME_ENTITY),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun commentReplyBindingPlan(): DexGraphPlan {
        val viewBindings = entityScan(
            EntityRelation.CLASS_DEFS,
            EntityPredicate.text(
                EntityTextField.DIRECT_INTERFACE,
                TextOperation.EXACT,
                "Landroidx/viewbinding/ViewBinding;"
            )
        )
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lcom/bilibili/magicasakura/widgets/TintImageView;"
                        )
                    ),
                    "img"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lcom/bilibili/magicasakura/widgets/TintTextView;"
                        )
                    ),
                    "txt"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.any(
                            EntityPredicate.text(
                                EntityTextField.TYPE,
                                TextOperation.EXACT,
                                "Landroidx/constraintlayout/widget/ConstraintLayout;"
                            ),
                            EntityPredicate.text(
                                EntityTextField.TYPE,
                                TextOperation.EXACT,
                                "Landroid/widget/LinearLayout;"
                            )
                        )
                    ),
                    "layout"
                )
            ),
            minimum = 7L,
            maximum = 12L,
            requiredRoles = listOf("img", "txt", "layout"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_COMMENT_REPLY_BINDING,
            GraphQuery(
                GraphExpression.semiJoin(viewBindings, grouped, GraphJoinKey.SAME_ENTITY),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun storyQualityDefaultPlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_STORY_QUALITY_DEFAULT,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag()),
                ReflectionMethodPredicate.metadata(MethodPredicate.parameterCount(0L)),
                ReflectionMethodPredicate.invokesAll(
                    InvokeTarget(
                        owner = exactText(dexType(StoryQualityLocator.DEFAULT_HELPER)),
                        name = exactText("getDefaultQuality"),
                        returnType = exactText("I"),
                        minParameters = 0L,
                        maxParameters = 0L
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun commentQuickReplyPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_COMMENT_QUICK_REPLY,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.parameterCount(2L),
                EntityPredicate.parameter(0L, TextOperation.CONTAINS, "PublishDialogIntent;"),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Lkotlin/coroutines/Continuation;"),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "Ljava/lang/Object;")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun pausedAdCountdownPlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            dexType(PausedAdDexAdapter.PAUSED_SERVICE)
                        )
                    ),
                    "svc"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lcom/bapis/bilibili/app/viewunite/v1/KPauseBar;"
                        )
                    ),
                    "bar"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "label"),
                            EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "I")
                        )
                    ),
                    "label"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "invokeSuspend"),
                            EntityPredicate.text(
                                EntityTextField.PROTOTYPE,
                                TextOperation.EXACT,
                                "(Ljava/lang/Object;)Ljava/lang/Object;"
                            )
                        )
                    ),
                    "resume"
                )
            ),
            minimum = 4L,
            maximum = 64L,
            requiredRoles = listOf("svc", "bar", "label", "resume"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_PAUSED_AD_COUNTDOWN,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun pausedAdCountdownInvokePlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_PAUSED_AD_COUNTDOWN_USES,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                nonStaticMethod(),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.normalMethod(),
                        MethodPredicate.text(MethodTextField.NAME, TextOperation.EXACT, "invokeSuspend"),
                        MethodPredicate.text(
                            MethodTextField.PROTOTYPE,
                            TextOperation.EXACT,
                            "(Ljava/lang/Object;)Ljava/lang/Object;"
                        )
                    )
                ),
                ReflectionMethodPredicate.invokesAll(
                    InvokeTarget(
                        owner = exactText("Lcom/bilibili/gripper/api/ad/biz/videodetail/pausedpage/IVDPausedPage;"),
                        name = exactText("getCountDownView")
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun pausedAdRequestApiPlan(): DexGraphPlan {
        val proto = buildString {
            append('(')
            PauseRequestCoverage.API_REQUEST_PARAMETERS.forEach { append(dexType(it)) }
            append(")Ljava/lang/Object;")
        }
        return DexGraphPlan.Entity(
            TAG_PAUSED_AD_REQUEST_API,
            EntityQuery(
                EntityRelation.METHOD_DEFS,
                EntityPredicate.all(
                    EntityPredicate.normalMethod(),
                    EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, proto)
                ),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun brandSplashKntrExitCurrentPlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lkotlin/jvm/functions/Function0;"
                        )
                    ),
                    "cb"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/runtime/MutableState;"
                        )
                    ),
                    "mutable_state"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/runtime/State;"
                        )
                    ),
                    "state"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "I")
                    ),
                    "label"
                )
            ),
            minimum = 4L,
            maximum = 64L,
            requiredRoles = listOf("cb", "mutable_state", "state", "label"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_BRAND_SPLASH_KNTR_EXIT_CURRENT,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun brandSplashKntrExitLegacyPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_BRAND_SPLASH_KNTR_EXIT_LEGACY,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.text(
                    EntityTextField.OWNER,
                    TextOperation.PREFIX,
                    "Lkntr/srcs/app/splash/brand/startup/BrandSplashPageKt\$BrandSplashPage\$"
                ),
                EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "invokeSuspend"),
                EntityPredicate.text(
                    EntityTextField.PROTOTYPE,
                    TextOperation.EXACT,
                    "(Ljava/lang/Object;)Ljava/lang/Object;"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun brandSplashKntrPagePlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_BRAND_SPLASH_KNTR_PAGE,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag()),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.normalMethod(),
                        MethodPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                        MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                        MethodPredicate.parameterCount(6L, 7L),
                        MethodPredicate.parameter(1L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function0;"),
                        MethodPredicate.parameter(2L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                        MethodPredicate.any(
                            MethodPredicate.parameter(3L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function0;"),
                            MethodPredicate.parameter(3L, TextOperation.EXACT, "Ljava/lang/String;")
                        ),
                        MethodPredicate.any(
                            MethodPredicate.parameter(4L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                            MethodPredicate.parameter(5L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;")
                        )
                    )
                ),
                ReflectionMethodPredicate.stringsAll(
                    StaticTextMatch(TextOperation.CONTAINS, BrandSplashKntrAdapter.PAGE_TRACE_MARK)
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun brandSplashKntrPageFallbackPlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_BRAND_SPLASH_KNTR_PAGE_FALLBACK,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag()),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.normalMethod(),
                        MethodPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                        MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                        MethodPredicate.parameterCount(6L, 7L),
                        nonSystemMethodParameter(0L),
                        MethodPredicate.parameter(1L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function0;"),
                        MethodPredicate.parameter(2L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                        MethodPredicate.any(
                            MethodPredicate.parameter(3L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function0;"),
                            MethodPredicate.parameter(3L, TextOperation.EXACT, "Ljava/lang/String;")
                        ),
                        MethodPredicate.any(
                            MethodPredicate.parameter(4L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                            MethodPredicate.parameter(5L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;")
                        )
                    )
                ),
                ReflectionMethodPredicate.any(
                    ReflectionMethodPredicate.fieldsAny(
                        FieldTarget(type = exactText("Lkntr/srcs/app/splash/brand/model/BrandSplashMode;")),
                        FieldTarget(type = exactText("Lkntr/srcs/app/splash/brand/model/BrandSplashSource;"))
                    ),
                    ReflectionMethodPredicate.invokesAny(
                        InvokeTarget(returnType = exactText("Lkntr/srcs/app/splash/brand/model/BrandSplashMode;")),
                        InvokeTarget(returnType = exactText("Lkntr/srcs/app/splash/brand/model/BrandSplashSource;"))
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun brandSplashKntrDrawFallbackPlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.not(EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag())),
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "invoke"),
                            EntityPredicate.parameterCount(3L)
                        )
                    ),
                    "invoke"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.constructor(),
                            EntityPredicate.text(
                                EntityTextField.PROTOTYPE,
                                TextOperation.CONTAINS,
                                "Landroidx/compose/ui/unit/Density;Lorg/jetbrains/compose/resources/DrawableResource;Landroidx/compose/runtime/MutableState;"
                            )
                        )
                    ),
                    "ctor"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/ui/graphics/ImageBitmap;"
                        )
                    ),
                    "bitmap"
                )
            ),
            minimum = 3L,
            maximum = 32L,
            requiredRoles = listOf("invoke", "ctor", "bitmap"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_BRAND_SPLASH_KNTR_DRAW_FALLBACK,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun homeTopPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_TOP_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(12L),
                nonSystemParameter(0L),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "J"),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "J"),
                EntityPredicate.any(
                    EntityPredicate.all(
                        EntityPredicate.parameter(7L, TextOperation.EXACT, "Z"),
                        EntityPredicate.parameter(8L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                        EntityPredicate.parameter(9L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                        EntityPredicate.parameter(10L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                        EntityPredicate.parameter(11L, TextOperation.EXACT, "I")
                    ),
                    EntityPredicate.all(
                        EntityPredicate.parameter(7L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                        EntityPredicate.parameter(8L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                        EntityPredicate.parameter(9L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                        EntityPredicate.parameter(10L, TextOperation.EXACT, "I"),
                        EntityPredicate.parameter(11L, TextOperation.EXACT, "I")
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeTopLayoutPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_TOP_LAYOUT_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(12L, 13L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, "I"),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "J"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "J"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(7L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function3;"),
                EntityPredicate.parameter(8L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function2;"),
                EntityPredicate.any(
                    EntityPredicate.parameter(9L, TextOperation.EXACT, "Landroidx/compose/runtime/internal/ComposableLambda;"),
                    EntityPredicate.parameter(9L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function2;")
                ),
                EntityPredicate.parameter(10L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(11L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeTopBarPlan(tag: String): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                            EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                            nonSystemParameter(0L),
                            EntityPredicate.parameter(1L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                            EntityPredicate.parameter(2L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                            EntityPredicate.parameter(3L, TextOperation.EXACT, "I"),
                            EntityPredicate.any(
                                EntityPredicate.parameterCount(4L),
                                EntityPredicate.all(
                                    EntityPredicate.parameterCount(5L),
                                    EntityPredicate.parameter(4L, TextOperation.EXACT, "I")
                                )
                            )
                        )
                    ),
                    "backdrop"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                            EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                            nonSystemParameter(0L),
                            EntityPredicate.parameter(1L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                            EntityPredicate.parameter(2L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                            EntityPredicate.parameter(3L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                            EntityPredicate.parameter(4L, TextOperation.EXACT, "I"),
                            EntityPredicate.any(
                                EntityPredicate.parameterCount(5L),
                                EntityPredicate.all(
                                    EntityPredicate.parameterCount(6L),
                                    EntityPredicate.parameter(5L, TextOperation.EXACT, "I")
                                )
                            )
                        )
                    ),
                    "actions"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                            EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                            nonSystemParameter(0L),
                            EntityPredicate.parameter(1L, TextOperation.EXACT, "F"),
                            EntityPredicate.parameter(2L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                            EntityPredicate.parameter(3L, TextOperation.EXACT, "Z"),
                            EntityPredicate.parameter(4L, TextOperation.EXACT, "F"),
                            EntityPredicate.parameter(5L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                            EntityPredicate.parameter(6L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                            EntityPredicate.parameter(7L, TextOperation.EXACT, "I"),
                            EntityPredicate.any(
                                EntityPredicate.parameterCount(8L),
                                EntityPredicate.all(
                                    EntityPredicate.parameterCount(9L),
                                    EntityPredicate.parameter(8L, TextOperation.EXACT, "I")
                                )
                            )
                        )
                    ),
                    "foreground"
                )
            ),
            minimum = 3L,
            maximum = 32L,
            requiredRoles = listOf("backdrop", "actions", "foreground"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            tag,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun homeTopContentClosurePlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/foundation/ScrollState;"
                        )
                    ),
                    "scroll"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lkotlin/jvm/functions/Function3;"
                        )
                    ),
                    "f3"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.any(
                            EntityPredicate.text(
                                EntityTextField.TYPE,
                                TextOperation.EXACT,
                                "Landroidx/compose/runtime/internal/ComposableLambda;"
                            ),
                            EntityPredicate.text(
                                EntityTextField.TYPE,
                                TextOperation.EXACT,
                                "Landroidx/compose/runtime/MutableIntState;"
                            )
                        )
                    ),
                    "state_anchor"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "invoke"),
                            EntityPredicate.any(
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                                ),
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Landroidx/compose/runtime/Composer;I)V"
                                )
                            )
                        )
                    ),
                    "invoke"
                )
            ),
            minimum = 4L,
            maximum = 64L,
            requiredRoles = listOf("scroll", "f3", "state_anchor", "invoke"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_HOME_CHROME_TOP_CONTENT_CLOSURE_FALLBACK,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun homeTopContentStaticPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_TOP_CONTENT_STATIC_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.any(
                    EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                    EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "Lkotlin/Unit;")
                ),
                EntityPredicate.parameterCount(11L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, "Landroidx/compose/foundation/ScrollState;"),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function2;"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "F"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Landroidx/compose/runtime/State;"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function2;"),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function3;"),
                EntityPredicate.parameter(7L, TextOperation.EXACT, "I"),
                EntityPredicate.parameter(8L, TextOperation.EXACT, "Landroidx/compose/runtime/MutableIntState;"),
                EntityPredicate.parameter(9L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(10L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeBottomPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_BOTTOM_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(11L, 12L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "Ljava/util/List;"),
                EntityPredicate.parameter(2L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function4;"),
                nonSystemParameter(5L),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "Z"),
                EntityPredicate.parameter(7L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                nonSystemParameter(8L),
                EntityPredicate.parameter(9L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(10L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeBottomContentClosurePlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Ljava/util/List;")
                    ),
                    "list"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Lkotlin/jvm/functions/Function4;"
                        )
                    ),
                    "f4"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/runtime/MutableState;"
                        )
                    ),
                    "state"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            "Landroidx/compose/runtime/snapshots/SnapshotStateMap;"
                        )
                    ),
                    "map"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "invoke"),
                            EntityPredicate.any(
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                                ),
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                                ),
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Landroidx/compose/foundation/layout/BoxWithConstraintsScope;Landroidx/compose/runtime/Composer;I)V"
                                ),
                                EntityPredicate.text(
                                    EntityTextField.PROTOTYPE,
                                    TextOperation.EXACT,
                                    "(Landroidx/compose/runtime/Composer;I)V"
                                )
                            )
                        )
                    ),
                    "invoke"
                )
            ),
            minimum = 5L,
            maximum = 64L,
            requiredRoles = listOf("list", "f4", "state", "map", "invoke"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_HOME_CHROME_BOTTOM_CONTENT_CLOSURE_FALLBACK,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    private fun homeBottomContentStaticPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_BOTTOM_CONTENT_STATIC_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.any(
                    EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                    EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "Lkotlin/Unit;")
                ),
                EntityPredicate.parameterCount(12L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, "Ljava/util/List;"),
                nonSystemParameter(1L),
                nonSystemParameter(2L),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "Z"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function1;"),
                EntityPredicate.parameter(7L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function4;"),
                EntityPredicate.parameter(8L, TextOperation.EXACT, "Landroidx/compose/runtime/MutableState;"),
                EntityPredicate.parameter(9L, TextOperation.EXACT, "Landroidx/compose/runtime/snapshots/SnapshotStateMap;"),
                EntityPredicate.parameter(10L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(11L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeBottomItemPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_CHROME_BOTTOM_ITEM_FALLBACK,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag()),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(8L, 9L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, "I"),
                nonSystemParameter(1L),
                nonSystemParameter(2L),
                EntityPredicate.parameter(3L, TextOperation.EXACT, "Landroidx/compose/ui/Modifier;"),
                EntityPredicate.parameter(4L, TextOperation.EXACT, "Z"),
                EntityPredicate.parameter(5L, TextOperation.EXACT, "Lkotlin/jvm/functions/Function3;"),
                EntityPredicate.parameter(6L, TextOperation.EXACT, "Landroidx/compose/runtime/Composer;"),
                EntityPredicate.parameter(7L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeTabResourcePlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_HOME_TAB_RESOURCE,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.text(
                    EntityTextField.OWNER,
                    TextOperation.EXACT,
                    dexType(HomeTabResourceAdapter.HOME_FRAGMENT_V2)
                ),
                EntityPredicate.text(
                    EntityTextField.PROTOTYPE,
                    TextOperation.EXACT,
                    "(Ljava/util/List;)Ljava/util/List;"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        ),
        fallbackOnly = true
    )

    private fun homeTabResourceModelPlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_HOME_TAB_RESOURCE_MODEL,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionPublicFlag()),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.normalMethod(),
                        MethodPredicate.text(MethodTextField.NAME, TextOperation.EXACT, "equals"),
                        MethodPredicate.text(MethodTextField.PROTOTYPE, TextOperation.EXACT, "(Ljava/lang/Object;)Z")
                    )
                ),
                ReflectionMethodPredicate.stringsAll(exactText(HomeTabResourceAdapter.EQUALS_ANCHOR))
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun aiPlaylistDirectorPlan(): DexGraphPlan {
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(
                            EntityTextField.TYPE,
                            TextOperation.EXACT,
                            dexType(AiPlaylistDirectorAdapter.SERVICE_CLASS)
                        )
                    ),
                    "svc"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "switchToNext"),
                            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, "(Z)V")
                        )
                    ),
                    "switch"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "hasNext"),
                            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, "(Z)Z")
                        )
                    ),
                    "has_next"
                )
            ),
            minimum = 3L,
            maximum = 64L,
            requiredRoles = listOf("svc", "switch", "has_next"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_AI_PLAYLIST_DIRECTOR,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun followUpRecommendPlan(tag: String, repository: String): DexGraphPlan = DexGraphPlan.Entity(
        tag,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.constructor(),
                EntityPredicate.parameterCount(2L, 4L),
                EntityPredicate.parameter(0L, TextOperation.EXACT, dexType(repository)),
                EntityPredicate.text(
                    EntityTextField.PROTOTYPE,
                    TextOperation.CONTAINS,
                    "Lkotlin/coroutines/Continuation;)V"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun detailRelateGameInPkgPlan(): DexGraphPlan {
        val pkgPrefix = "L${DetailRelateGameAdapter.RELATE_GAME_PACKAGE.replace('.', '/')}/"
        return DexGraphPlan.Entity(
            TAG_DETAIL_RELATE_GAME_IN_PKG,
            EntityQuery(
                EntityRelation.METHOD_DEFS,
                EntityPredicate.all(
                    EntityPredicate.normalMethod(),
                    EntityPredicate.text(EntityTextField.OWNER, TextOperation.PREFIX, pkgPrefix),
                    EntityPredicate.parameterCount(3L),
                    EntityPredicate.parameter(0L, TextOperation.EXACT, "Landroid/content/Context;"),
                    EntityPredicate.parameter(1L, TextOperation.EXACT, "Landroid/view/LayoutInflater;"),
                    EntityPredicate.parameter(2L, TextOperation.EXACT, "Landroid/view/ViewGroup;")
                ),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun detailRelateGameBindUsesPlan(): DexGraphPlan = DexGraphPlan.MethodUses(
        TAG_DETAIL_RELATE_GAME_BIND_USES,
        ReflectionMethodUsesQuery(
            ReflectionMethodPredicate.all(
                nonStaticMethod(),
                ReflectionMethodPredicate.metadata(
                    MethodPredicate.all(
                        MethodPredicate.normalMethod(),
                        MethodPredicate.parameterCount(2L),
                        MethodPredicate.parameter(1L, TextOperation.EXACT, "Lkotlin/coroutines/Continuation;"),
                        MethodPredicate.text(MethodTextField.RETURN_TYPE, TextOperation.EXACT, "Ljava/lang/Object;")
                    )
                ),
                ReflectionMethodPredicate.any(
                    ReflectionMethodPredicate.invokesAll(
                        InvokeTarget(
                            owner = exactText("Ltv/danmaku/bili/widget/ratingbar/ReviewRatingBar;"),
                            name = exactText("setRating"),
                            prototype = exactText("(F)V")
                        )
                    ),
                    ReflectionMethodPredicate.stringsAll(
                        exactText("foreign_link_from_spmid"),
                        exactText("content_id"),
                        exactText("from_spmid")
                    )
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun nonStaticMethod(): ReflectionMethodPredicate = ReflectionMethodPredicate.not(
        ReflectionMethodPredicate.reflectionFlags(RawFlagOperation.CONTAINS, reflectionStaticFlag())
    )

    private fun legacyStoryDanmakuBindPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_LEGACY_STORY_DANMAKU_BIND,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.normalMethod(),
                EntityPredicate.text(
                    EntityTextField.OWNER,
                    TextOperation.EXACT,
                    dexType(LegacyStoryDanmakuAccess.OWNER)
                ),
                EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
                EntityPredicate.parameterCount(2L),
                EntityPredicate.parameter(1L, TextOperation.EXACT, "I")
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun theseus2LongPressLaunchPlan(): DexGraphPlan = DexGraphPlan.Entity(
        TAG_THESEUS2_LONG_PRESS_LAUNCH,
        EntityQuery(
            EntityRelation.METHOD_DEFS,
            EntityPredicate.all(
                EntityPredicate.constructor(),
                EntityPredicate.text(
                    EntityTextField.PROTOTYPE,
                    TextOperation.EXACT,
                    "(${dexType(PlayerSpeedLocator.THESEUS2_SPEED_SERVICE)}FLjava/lang/Object;Lkotlin/coroutines/Continuation;)V"
                )
            ),
            QueryIntent.ENUMERATE,
            MAX_RESULTS,
            GRAPH_MAX_WORK
        )
    )

    private fun theseus2LongPressPortPlan(): DexGraphPlan {
        val serviceDesc = dexType(PlayerSpeedLocator.THESEUS2_SPEED_SERVICE)
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.FIELD_DEFS,
                        EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, serviceDesc)
                    ),
                    "svc"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.constructor(),
                            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, "($serviceDesc)V")
                        )
                    ),
                    "ctor"
                ),
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.EXACT, "()Ljava/lang/Float;")
                        )
                    ),
                    "start"
                )
            ),
            minimum = 3L,
            maximum = 16L,
            requiredRoles = listOf("svc", "ctor", "start"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_THESEUS2_LONG_PRESS_PORT,
            GraphQuery(
                grouped,
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            )
        )
    }

    private fun kmpGrpcWireEnginePlan(): DexGraphPlan {
        val handlerInterfaces = entityScan(
            EntityRelation.CLASS_DEFS,
            EntityPredicate.flags(RawFlagOperation.CONTAINS, interfaceFlag())
        )
        val grouped = GraphExpression.groupByOwner(
            inputs = listOf(
                GraphExpression.label(
                    entityScan(
                        EntityRelation.METHOD_DEFS,
                        EntityPredicate.all(
                            EntityPredicate.normalMethod(),
                            EntityPredicate.text(EntityTextField.NAME, TextOperation.EXACT, "onNext"),
                            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.PREFIX, "([B)")
                        )
                    ),
                    "on_next"
                )
            ),
            minimum = 1L,
            maximum = 16L,
            requiredRoles = listOf("on_next"),
            rolePolicy = GraphRolePolicy.DISTINCT
        )
        return DexGraphPlan.Graph(
            TAG_KMP_GRPC_WIRE_ENGINE,
            GraphQuery(
                GraphExpression.semiJoin(handlerInterfaces, grouped, GraphJoinKey.SAME_ENTITY),
                QueryIntent.ENUMERATE,
                MAX_RESULTS,
                GRAPH_MAX_WORK
            ),
            fallbackOnly = true
        )
    }

    fun kmpGrpcWireMethodsForHandler(handlerDescriptor: String): EntityQuery = EntityQuery(
        EntityRelation.METHOD_DEFS,
        EntityPredicate.all(
            EntityPredicate.normalMethod(),
            EntityPredicate.flags(RawFlagOperation.CONTAINS, publicFlag()),
            EntityPredicate.not(EntityPredicate.flags(RawFlagOperation.CONTAINS, staticFlag())),
            EntityPredicate.text(EntityTextField.RETURN_TYPE, TextOperation.EXACT, "V"),
            EntityPredicate.parameterCount(4L, 6L),
            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.CONTAINS, "[B"),
            EntityPredicate.text(EntityTextField.PROTOTYPE, TextOperation.CONTAINS, handlerDescriptor)
        ),
        QueryIntent.ENUMERATE,
        MAX_RESULTS,
        GRAPH_MAX_WORK
    )

    private fun entityScan(relation: EntityRelation, predicate: EntityPredicate): GraphExpression =
        GraphExpression.scan(relation, GraphPredicate.from(predicate))

    private fun nonSystemParameter(index: Long): EntityPredicate = EntityPredicate.not(
        EntityPredicate.any(
            *(SYSTEM_DESCRIPTOR_PREFIXES + "[").map { prefix ->
                EntityPredicate.parameter(index, TextOperation.PREFIX, prefix)
            }.toTypedArray(),
            *PRIMITIVE_DESCRIPTORS.map { desc ->
                EntityPredicate.parameter(index, TextOperation.EXACT, desc)
            }.toTypedArray()
        )
    )

    private fun nonSystemMethodParameter(index: Long): MethodPredicate = MethodPredicate.not(
        MethodPredicate.any(
            *(SYSTEM_DESCRIPTOR_PREFIXES + "[").map { prefix ->
                MethodPredicate.parameter(index, TextOperation.PREFIX, prefix)
            }.toTypedArray(),
            *PRIMITIVE_DESCRIPTORS.map { desc ->
                MethodPredicate.parameter(index, TextOperation.EXACT, desc)
            }.toTypedArray()
        )
    )

    private fun exactText(value: String): StaticTextMatch = StaticTextMatch(TextOperation.EXACT, value)

    private fun staticFlag(): RawDexFlags = RawDexFlags(Modifier.STATIC.toLong())

    private fun publicFlag(): RawDexFlags = RawDexFlags(Modifier.PUBLIC.toLong())

    private fun interfaceFlag(): RawDexFlags = RawDexFlags(Modifier.INTERFACE.toLong())

    private fun reflectionStaticFlag(): ReflectionFlags = ReflectionFlags(Modifier.STATIC.toLong())

    private fun reflectionPublicFlag(): ReflectionFlags = ReflectionFlags(Modifier.PUBLIC.toLong())

    private fun dexType(type: String): String = when (type) {
        "void" -> "V"
        "boolean" -> "Z"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
}
