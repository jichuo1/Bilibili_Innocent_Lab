package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CloudKeyFreezeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentQuickReplyAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.GuestPlayerCoreAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PegasusLoadMoreShape
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/** 静态查询只匹配当前归档中观察到的 DEX 声明和指令，调用方再验证宿主 ABI。 */
internal object DexMeguvizQueries {
    const val MAX_MATCHES = 32

    fun rules(query: DexAssistQuery): List<ByteArray> = when (query) {
        DexAssistQuery.PLAYER_SPEED_MENU -> speedMenuRules()
        DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY -> listOf(2, 3, 4).map { count ->
            rule {
                addInvoke("com.tencent.mm.opensdk.openapi.WXAPIFactory", "createWXAPI",
                    "com.tencent.mm.opensdk.openapi.IWXAPI") { paramCount(count) }
            }
        }
        DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS -> listOf(
            "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment",
            "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment"
        ).map { fragment ->
            rule {
                owner = fragment
                addInvoke("androidx.recyclerview.widget.RecyclerView", "addItemDecoration") {
                    params("androidx.recyclerview.widget.RecyclerView\$ItemDecoration")
                }
            }
        }
        DexAssistQuery.STORY_QUALITY_DEFAULT -> listOf(
            // 老宿主可能只含 key 或只调用 helper；不限制包以兼容 R8 扁平化到根包。
            rule {
                modifiers(Modifier.STATIC)
                paramCount(0)
                strings(StoryQualityLocator.WIFI_KEY)
            },
            rule {
                within(StoryQualityLocator.QUALITY_PACKAGE)
                modifiers(Modifier.STATIC)
                paramCount(0)
                addInvoke(StoryQualityLocator.DEFAULT_HELPER, "getDefaultQuality", "int") { paramCount(0) }
            }
        )
        DexAssistQuery.COMMENT_COMPOSE_MORE_MENU -> composeMenuRules()
        DexAssistQuery.GUEST_PLAYER_CORE_SERVICE -> listOf(
            rule {
                name = "setMediaResource"
                params(GuestPlayerCoreAdapter.MEDIA_RESOURCE, "boolean", GuestPlayerCoreAdapter.MEDIA_ITEM_PARAMS)
            },
            rule {
                name = "setMediaResource"
                params(
                    GuestPlayerCoreAdapter.MEDIA_ITEM,
                    GuestPlayerCoreAdapter.MEDIA_RESOURCE,
                    "boolean",
                    GuestPlayerCoreAdapter.MEDIA_ITEM_PARAMS
                )
            }
        )
        DexAssistQuery.SPONSOR_RUN_PLAYABLE -> sponsorRunPlayableRules()
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER -> sponsorPlayerWrapperRules()
        DexAssistQuery.COMMENT_REPLY_MAPPER -> listOf(
            rule { configure(query) },
            rule {
                // 9.19.0 的 ReplyInfo 映射器被混淆到根包，以数据读取与实际构造确认方法体。
                returnType = "com.bilibili.app.comment3.data.model.CommentItem"
                modifiers(Modifier.STATIC)
                paramCount(5)
                val reply = "com.bapis.bilibili.main.community.reply.v1.ReplyInfo"
                addInvoke(reply, "getId") { paramCount(0) }
                addInvoke(reply, "getContent", "com.bapis.bilibili.main.community.reply.v1.Content") { paramCount(0) }
                addInvoke("com.bilibili.app.comment3.data.model.CommentItem", "<init>", "void") {}
            }
        )
        DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> homeRecommendEarlyLoadRules()
        DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR,
        DexAssistQuery.PAUSED_AD_COUNTDOWN,
        DexAssistQuery.BRAND_SPLASH_KNTR_EXIT,
        DexAssistQuery.AI_PLAYLIST_DIRECTOR,
        DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY,
        DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT,
        DexAssistQuery.LEGACY_STORY_DANMAKU_BIND,
        DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS -> emptyList()
        else -> listOf(rule { configure(query) }) + fallbackRules(query)
    }

    fun ownerMembers(ownerDescriptor: String): ByteArray = rule { descriptor("owner", ownerDescriptor) }

    fun declaration(ownerDescriptor: String, name: String, prototype: String): ByteArray = rule {
        descriptor("owner", ownerDescriptor)
        descriptor("name", name)
        descriptor("prototype", prototype)
    }

    fun methodByNameAndPrototype(name: String, prototype: String): ByteArray = rule {
        descriptor("name", name)
        descriptor("prototype", prototype)
    }

    private fun MethodRule.configure(query: DexAssistQuery) {
        // 表达式 when 强制编译期穷尽：新增 DexAssistQuery 枚举而漏写分支会直接编译失败，
        // 而不是静默产出空规则（空规则与无命中无法区分，会伪装成缺失）。
        val exhaustive: Unit = when (query) {
            DexAssistQuery.OGV_TRENDING_CARD, DexAssistQuery.OGV_DOUBLE_CARD, DexAssistQuery.OGV_UPCOMING_CARD -> {
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(5..6)
                strings(HostCardSemanticAdapter.marks.getValue(query), contains = true)
            }
            DexAssistQuery.HOME_CHROME_SEMANTICS -> {
                name = "<clinit>"
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(0)
                strings(*HomeChromeAdapter.SEMANTICS_MARKS.toTypedArray())
            }
            DexAssistQuery.HOME_CHROME_TOP_CONTENT, DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT -> {
                paramCount(2..12)
                strings(HomeChromeAdapter.marks.getValue(query), contains = true)
            }
            DexAssistQuery.HOME_CHROME_TOP, DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_BACKDROP, DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS,
            DexAssistQuery.HOME_CHROME_BOTTOM, DexAssistQuery.HOME_CHROME_BOTTOM_ITEM -> {
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(4..20)
                strings(HomeChromeAdapter.marks.getValue(query), contains = true)
            }
            DexAssistQuery.PEGASUS_DIVIDER -> {
                within("com.bilibili.pegasus.widget")
                name = "onDrawOver"
                returnType = "void"
                params("android.graphics.Canvas", "androidx.recyclerview.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView\$State")
            }
            DexAssistQuery.SEARCH_HOME_DELIVERY -> {
                // 不限包：云端 dexq 对 12 个宿主样本（8.84–9.20）枚举 getHistoryList 声明每版至多 7 个
                //（直播 proto 与 LiveRoom 占 4 个），远低于 MAX_MATCHES；9.19 起投递 owner 扁平到根包，
                // within DISCOVER 会整版漏检。唯一性由运行时 delivery() 全图验真 + singleOrNull 保证。
                name = "getHistoryList"
                paramCount(0)
                returnType = "java.util.List"
            }
            DexAssistQuery.SEARCH_HOME_STATE -> {
                within(SearchHomeRecommendLocator.STATE)
                name = "<init>"
                params("java.util.List")
            }
            DexAssistQuery.PLAYER_END_PAGE_MERGER -> {
                within(PlayerEndPageRecommendLocator.PACKAGE)
                params("java.util.List", "java.util.List")
                returnType = "java.util.List"
            }
            DexAssistQuery.STORY_QUALITY_RESOURCE -> {
                storyResource()
            }
            DexAssistQuery.STORY_QUALITY_MATCH -> {
                owner = StoryQualityLocator.SERVICE
                returnType = "java.lang.Object"
                paramCount(1)
                strings(StoryQualityLocator.MATCH_MARK, contains = true)
            }
            DexAssistQuery.FOLLOW_FEED_STYLE -> {
                name = "getItemOffsets"
                returnType = "void"
                params("android.graphics.Rect", "android.view.View", "androidx.recyclerview.widget.RecyclerView",
                    "androidx.recyclerview.widget.RecyclerView\$State")
                strings(FollowFeedAdapter.DECORATION_SOURCE, FollowFeedAdapter.OFFSETS_MARK)
            }
            DexAssistQuery.BRAND_SPLASH_DECODER -> {
                returnType = BrandSplashDecoderAdapter.BITMAP
                params(BrandSplashDecoderAdapter.INFO)
                modifiers(Modifier.STATIC)
                strings(BrandSplashDecoderAdapter.MARK)
                addInvoke(BrandSplashDecoderAdapter.INFO, "getThumbHash") { paramCount(0) }
                addInvoke("android.graphics.BitmapFactory", "decodeFile") { params("java.lang.String") }
            }
            DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE -> {
                within(PlayerPopupPromotionAdapter.NETWORK_PACKAGE)
                returnType = "java.lang.Object"
                paramCount(1)
                // 旧代 BLog 将业务句与前缀合并。
                strings(PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK, contains = true)
            }
            DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT -> {
                within(PlayerPopupPromotionAdapter.MODEL_PACKAGE)
                returnType = "java.util.List"
                paramCount(0)
                addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidCommon") { paramCount(0) }
                addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidCommerce") { paramCount(0) }
                addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidGuide") { paramCount(0) }
            }
            DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN -> {
                strings(if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                    CommentClassicStyleLocator.NATIVE_ANCHOR else CommentClassicStyleLocator.KOTLIN_ANCHOR)
            }
            DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> {
                within(SponsorPlayerLocator.CONTAINER_PACKAGE)
                name = "<init>"
                paramCount(3..16)
                addInvoke("kotlin.coroutines.jvm.internal.SuspendLambda", "<init>") {
                    params("int", "kotlin.coroutines.Continuation")
                }
            }
            DexAssistQuery.SYSTEM_ENV_ROOT_RESULT -> {
                modifiers(Modifier.STATIC)
                returnType = "java.util.Map"
                params(SystemEnvironmentAdapter.ENV_PARAM_SET)
                strings(*SystemEnvironmentAdapter.ENV_STRINGS.toTypedArray())
            }
            DexAssistQuery.SYSTEM_ENV_HOOK_RESULT -> {
                modifiers(Modifier.STATIC)
                returnType = "java.util.Map"
                paramCount(0)
                strings(*SystemEnvironmentAdapter.HOOK_STRINGS.toTypedArray())
            }
            DexAssistQuery.BLOCK_UPDATE -> {
                returnType = "tv.danmaku.bili.update.model.BiliUpgradeInfo"
                params("android.content.Context")
                strings("Do sync http request.")
            }
            DexAssistQuery.PLAYER_DEFAULT_QUALITY -> {
                returnType = "int"
                paramCount(0)
                strings("quality settings:")
            }
            DexAssistQuery.COMMENT_REPLY_MAPPER -> {
                within("com.bilibili.app.comment3")
                returnType = "com.bilibili.app.comment3.data.model.CommentItem"
                modifiers(Modifier.STATIC)
                // 保留旧候选范围。首参 ReplyInfo 由宿主 ABI 二次复核。
                paramCount(1..8)
            }
            DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN -> {
                returnType = "void"
                paramCount(5)
                addInvoke(name = KotlinDefaultWordsLocator.DESCRIPTOR_GETTER) { paramCount(0) }
            }
            DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR -> {
                modifiers(Modifier.STATIC)
                returnType = "float"
                paramCount(2)
                addInvoke(name = "getScriptPace", returnType = "float") { paramCount(0) }
            }
            DexAssistQuery.KMP_GRPC_WIRE_ENGINE -> {
                returnType = "void"
                paramCount(4..6)
                strings(*KmpGrpcWireAdapter.ENTRY_MARKS.toTypedArray())
            }
            DexAssistQuery.BRAND_SPLASH_KNTR_DRAW -> {
                name = "invoke"
                paramCount(3)
                strings(BrandSplashKntrAdapter.TRACE_MARK, contains = true)
            }
            DexAssistQuery.CLOUD_KEY_FREEZE_CONTRACT -> {
                name = "getWithDefault"
                returnType = "boolean"
                params("java.lang.String", "boolean")
            }
            DexAssistQuery.PRIVACY_IGNET_HTTP -> {
                name = "execute"
                paramCount(0)
                returnType = "com.bilibili.lib.ighttp.IgHttpResponse"
            }
            DexAssistQuery.PRIVACY_KNEURON -> {
                name = "simpleTrackT"
                returnType = "void"
            }
            DexAssistQuery.NORMAL_QUALITY_AUTO_SWITCH -> {
                modifiers(Modifier.STATIC)
                paramCount(0)
                returnType = "boolean"
                strings(NormalQualityLocator.AUTO_KEY)
            }
            DexAssistQuery.DETAIL_SPECIAL_TAG_MAPPER -> {
                modifiers(Modifier.STATIC)
                params(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS)
            }
            DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE -> {
                within(CommentReplyButtonAdapter.HOLDER_PACKAGE)
                returnType = "void"
                paramCount(5)
                addInvoke(CommentReplyButtonAdapter.PLANT_SEEDS_VIEW, returnType = "void") { paramCount(2) }
            }
            DexAssistQuery.PAUSED_AD_REQUEST_API -> {
                returnType = "java.lang.Object"
                params(*PauseRequestCoverage.API_REQUEST_PARAMETERS.toTypedArray())
            }
            DexAssistQuery.BRAND_SPLASH_KNTR_PAGE -> {
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(6..7)
                strings(BrandSplashKntrAdapter.PAGE_TRACE_MARK, contains = true)
            }
            DexAssistQuery.HOME_TAB_RESOURCE -> {
                owner = HomeTabResourceAdapter.HOME_FRAGMENT_V2
                params("java.util.List")
                returnType = "java.util.List"
            }
            DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR,
            DexAssistQuery.PAUSED_AD_COUNTDOWN,
            DexAssistQuery.BRAND_SPLASH_KNTR_EXIT,
            DexAssistQuery.AI_PLAYLIST_DIRECTOR,
            DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY,
            DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT,
            DexAssistQuery.LEGACY_STORY_DANMAKU_BIND,
            DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS -> error("Expected a graph-only query: $query")
            DexAssistQuery.PLAYER_SPEED_MENU, DexAssistQuery.STORY_QUALITY_DEFAULT,
            DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY,
            DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS,
            DexAssistQuery.SPONSOR_RUN_PLAYABLE,
            DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
            DexAssistQuery.COMMENT_COMPOSE_MORE_MENU,
            DexAssistQuery.GUEST_PLAYER_CORE_SERVICE,
            DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> error("Expected a multi-rule query")
        }
    }

    private fun fallbackRules(query: DexAssistQuery): List<ByteArray> = when (query) {
        DexAssistQuery.PEGASUS_DIVIDER -> listOf(rule {
            owner = "com.bilibili.pegasus.PegasusFragment"
            addInvoke("androidx.recyclerview.widget.RecyclerView", "addItemDecoration") {
                params("androidx.recyclerview.widget.RecyclerView\$ItemDecoration")
            }
        })
        DexAssistQuery.SEARCH_HOME_STATE -> listOf(rule {
            owner = "com.bilibili.search2.main.MainSearchViewModel"
            name = "<init>"
            addInvoke(name = "<init>", returnType = "void") { params("java.util.List") }
        })
        DexAssistQuery.PLAYER_END_PAGE_MERGER -> listOf(rule {
            owner = PlayerEndPageRecommendLocator.SERVICE
            addInvoke(returnType = "java.util.List") { params("java.util.List", "java.util.List") }
        })
        DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE -> listOf(rule {
            returnType = "java.lang.Object"
            paramCount(1)
            strings(PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK, contains = true)
        })
        DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT -> listOf(rule {
            returnType = "java.util.List"
            paramCount(0)
            addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidCommon") { paramCount(0) }
            addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidCommerce") { paramCount(0) }
            addInvoke(PlayerPopupPromotionAdapter.CARD_CLASS, "isValidGuide") { paramCount(0) }
        })
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> listOf(rule {
            owner = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider"
            addInvoke(name = "<init>", returnType = "void") { paramCount(3..16) }
        })
        DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN -> listOf(rule {
            returnType = "void"
            paramCount(4)
            addInvoke(name = KotlinDefaultWordsLocator.DESCRIPTOR_GETTER) { paramCount(0) }
        })
        DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE -> listOf(
            rule {
                returnType = "void"
                paramCount(5)
                addInvoke(CommentReplyButtonAdapter.PLANT_SEEDS_VIEW, returnType = "void") { paramCount(2) }
            }
        )
        else -> emptyList()
    }

    private fun composeMenuRules(): List<ByteArray> = listOf(
        rule {
            modifiers(Modifier.STATIC)
            returnType = "void"
            params("java.util.List", "kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int")
            strings(CommentComposeMenuAdapter.BUBBLE_TRACE_MARK, contains = true)
        },
        rule {
            modifiers(Modifier.STATIC)
            returnType = "void"
            params(
                "java.util.List",
                "kotlin.jvm.functions.Function1",
                "androidx.compose.ui.Modifier",
                "androidx.compose.foundation.layout.PaddingValues",
                "androidx.compose.runtime.Composer",
                "int",
                "int"
            )
            strings(CommentComposeMenuAdapter.GROUPED_TRACE_MARK, contains = true)
        },
        rule {
            within("kntr.common.comment")
            modifiers(Modifier.STATIC)
            returnType = "void"
            params(
                "java.util.List",
                "kotlin.jvm.functions.Function1",
                "androidx.compose.ui.Modifier",
                "androidx.compose.foundation.layout.PaddingValues",
                "androidx.compose.runtime.Composer",
                "int",
                "int"
            )
        },
        rule {
            modifiers(Modifier.STATIC)
            returnType = "void"
            paramCount(6)
            strings(CommentComposeMenuAdapter.GROUPED_ITEM_TRACE_MARK, contains = true)
        },
        rule {
            modifiers(Modifier.STATIC)
            returnType = "void"
            paramCount(8..9)
            strings(CommentComposeMenuAdapter.LEGACY_TRACE_MARK, contains = true)
        }
    )

    private fun speedMenuRules(): List<ByteArray> =
        listOf("java.util.List", "java.util.ArrayList").flatMap { clearOwner ->
            listOf(listOf("float"), listOf("float", "java.util.List")).map { parameters ->
                rule {
                    returnType = "void"
                    params(*parameters.toTypedArray())
                    addInvoke(clearOwner, "clear") { paramCount(0) }
                    addInvoke("java.lang.Math", "abs", "float") { params("float") }
                }
            }
        } + listOf(
            rule {
                within(PlayerSpeedLocator.SPEED_UTILS_PKG)
                modifiers(Modifier.STATIC)
                returnType = "java.util.List"
                params(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int")
                addInvoke("java.util.ArrayList", "add") { params("java.lang.Object") }
            },
            rule {
                // 9.19 的工厂移至根包并内联布尔参数；调用侧再核对实际 Option 构造。
                modifiers(Modifier.STATIC)
                returnType = "java.util.List"
                params(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "int")
                addInvoke("java.util.ArrayList", "add") { params("java.lang.Object") }
            },
            rule {
                within(PlayerSpeedLocator.THESEUS_SPEED_PKG)
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(1)
                strings(PlayerSpeedMenuAdapter.OPEN_MARK)
            },
            rule {
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(1)
                strings(PlayerSpeedMenuAdapter.OPEN_MARK)
                addInvoke(PlayerSpeedLocator.THESEUS_SPEED_WIDGET, "<init>", "void") { paramCount(9) }
            }
        )

    fun storyResourceForOwner(ownerDescriptor: String): ByteArray = rule {
        descriptor("owner", ownerDescriptor)
        storyResource()
    }

    private fun MethodRule.storyResource() {
        modifiers(Modifier.STATIC)
        returnType = "void"
        params(StoryQualityLocator.MEDIA_RESOURCE)
        strings(StoryQualityLocator.RESOURCE_MARK)
        addInvoke(StoryQualityLocator.MEDIA_RESOURCE, "findMaxAvailableQuality") { params("int") }
        addInvoke(StoryQualityLocator.MEDIA_RESOURCE, "setResolvedIndex") { params("int") }
    }

    private fun MethodRule.sponsorRunPlayable() {
        returnType = "java.lang.Object"
        paramCount(2)
        strings(SponsorPlayerLocator.COROUTINE_ERROR)
        addInvoke("kotlinx.coroutines.sync.Mutex", "lock") { paramCount(2) }
        addInvoke("kotlinx.coroutines.flow.MutableStateFlow", "setValue") { paramCount(1) }
    }

    private fun sponsorRunPlayableRules(): List<ByteArray> = listOf(
        rule {
            within(SponsorPlayerLocator.FAMILY)
            sponsorRunPlayable()
        },
        // 兼容未来 R8 将 TheseusKeelPlayer 扁平化至根包，沿用强三锚点做跨包兜底。
        rule { sponsorRunPlayable() }
    )

    private fun MethodRule.sponsorPlayerWrapper() {
        name = "seekTo"
        returnType = "void"
        params("int", "boolean")
        addInvoke(name = "<init>") { paramCount(4) }
    }

    private fun sponsorPlayerWrapperRules(): List<ByteArray> = listOf(
        rule {
            within(SponsorPlayerLocator.CONTAINER_PACKAGE)
            sponsorPlayerWrapper()
        },
        rule { sponsorPlayerWrapper() }
    )

    private fun homeRecommendEarlyLoadRules(): List<ByteArray> = listOf(
        rule {
            owner = PegasusLoadMoreShape.PEGASUS_FRAGMENT
            addInvoke("androidx.recyclerview.widget.RecyclerView", "addOnScrollListener") {
                params("androidx.recyclerview.widget.RecyclerView\$OnScrollListener")
            }
            addInvoke("androidx.recyclerview.widget.RecyclerView", "addOnChildAttachStateChangeListener") {
                params("androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener")
            }
        },
        rule {
            descriptor("owner", text("prefix", "Lcom/bilibili/pegasus/PegasusFragment\$"))
            name = "<init>"
            paramCount(3)
            addInvoke("kotlin.coroutines.jvm.internal.SuspendLambda", "<init>") {
                params("int", "kotlin.coroutines.Continuation")
            }
        }
    )

    private fun rule(configure: MethodRule.() -> Unit): ByteArray =
        JSONObject()
            .put("schema_version", 1)
            .put("relation", "method_defs")
            .put("where", MethodRule().apply(configure).where())
            .put("intent", "enumerate")
            .put("max_results", MAX_MATCHES + 1)
            .toString().toByteArray(Charsets.UTF_8)

    // QueryIR 的调用目标条件超出方法查询 Kotlin builder 的基础子集。
    private class MethodRule {
        private val fields = JSONObject()
        private val invokes = JSONArray()

        var name: String
            get() = fields.getString("name")
            set(value) { fields.put("name", value) }
        var owner: String
            get() = fields.getString("owner")
            set(value) { descriptor("owner", dexType(value)) }
        var returnType: String
            get() = fields.getString("return_type")
            set(value) { descriptor("return_type", dexType(value)) }

        fun descriptor(field: String, value: Any) { fields.put(field, value) }
        // MethodBatchQuery 与 NativeEngine.queryMethods 仅接受 query:v1:；顶层 where 不得发 any/all/not（composite-v10）。
        fun within(packageName: String) {
            fields.put("owner", text("prefix", "L${packageName.trimEnd('.').replace('.', '/')}/"))
        }
        fun modifiers(flags: Int) { fields.put("flags_contains", flags) }
        fun paramCount(count: Int) { fields.put("param_count", count) }
        fun paramCount(count: IntRange) {
            fields.put("param_count", JSONObject().put("min", count.first).put("max", count.last))
        }
        fun params(vararg names: String) { fields.put("parameters", JSONArray(names.map(::dexType))) }
        fun strings(vararg marks: String, contains: Boolean = false) {
            fields.put("using_strings", all(JSONArray(marks.map { if (contains) text("contains", it) else it })))
        }
        fun addInvoke(owner: String? = null, name: String? = null, returnType: String? = null, configure: MethodRule.() -> Unit) {
            val target = MethodRule()
            if (name != null) target.name = name
            if (owner != null) target.owner = owner
            if (returnType != null) target.returnType = returnType
            invokes.put(target.apply(configure).where())
        }
        fun where(): JSONObject {
            if (invokes.length() != 0) fields.put("invokes", all(invokes))
            return fields
        }
    }

    private fun all(values: JSONArray) = JSONObject().put("all", values)
    private fun text(operation: String, value: String) = JSONObject().put("op", operation).put("value", value)
    private fun dexType(type: String): String = when (type) {
        "void" -> "V"
        "boolean" -> "Z"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }

    fun graphPlans(query: DexAssistQuery): List<DexGraphPlan> = DexMeguvizGraphPlans.plans(query)
}
