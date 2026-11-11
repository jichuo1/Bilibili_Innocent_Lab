package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/** 静态查询只匹配当前归档中观察到的 DEX 声明和指令，调用方再验证宿主 ABI。 */
internal object DexMeguvizQueries {
    const val MAX_MATCHES = 32

    fun rules(query: DexAssistQuery): List<ByteArray> = when (query) {
        DexAssistQuery.PLAYER_SPEED_MENU -> speedMenuRules()
        DexAssistQuery.STORY_QUALITY_DEFAULT -> listOf(
            // 老宿主可能只含 key 或只调用 helper。保留两个分支的候选及各自的上限检查。
            rule {
                within(StoryQualityLocator.QUALITY_PACKAGE)
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
        else -> listOf(rule { configure(query) })
    }

    fun ownerMembers(ownerDescriptor: String): ByteArray = rule { descriptor("owner", ownerDescriptor) }

    fun declaration(ownerDescriptor: String, name: String, prototype: String): ByteArray = rule {
        descriptor("owner", ownerDescriptor)
        descriptor("name", name)
        descriptor("prototype", prototype)
    }

    private fun MethodRule.configure(query: DexAssistQuery) {
        when (query) {
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
                chromeOwners(query)
                paramCount(2..12)
                strings(HomeChromeAdapter.marks.getValue(query), contains = true)
            }
            DexAssistQuery.HOME_CHROME_TOP, DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_BACKDROP, DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS,
            DexAssistQuery.HOME_CHROME_BOTTOM, DexAssistQuery.HOME_CHROME_BOTTOM_ITEM -> {
                chromeOwners(query)
                modifiers(Modifier.STATIC)
                returnType = "void"
                paramCount(4..20)
                strings(HomeChromeAdapter.marks.getValue(query), contains = true)
            }
            DexAssistQuery.PEGASUS_DIVIDER -> {
                within("com.bilibili.pegasus.widget", setOf(HostCardSemanticAdapter.REPACKAGED_PEGASUS_DIVIDER))
                name = "onDrawOver"
                returnType = "void"
                params("android.graphics.Canvas", "androidx.recyclerview.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView\$State")
            }
            DexAssistQuery.SEARCH_HOME_DELIVERY -> {
                within(SearchHomeRecommendLocator.DISCOVER)
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
                within(StoryQualityLocator.QUALITY_PACKAGE)
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
            DexAssistQuery.SPONSOR_RUN_PLAYABLE -> {
                within(SponsorPlayerLocator.FAMILY)
                returnType = "java.lang.Object"
                paramCount(2)
                strings(SponsorPlayerLocator.COROUTINE_ERROR)
                addInvoke("kotlinx.coroutines.sync.Mutex", "lock") { paramCount(2) }
                addInvoke("kotlinx.coroutines.flow.MutableStateFlow", "setValue") { paramCount(1) }
            }
            DexAssistQuery.SPONSOR_PLAYER_WRAPPER -> {
                within(SponsorPlayerLocator.CONTAINER_PACKAGE)
                name = "seekTo"
                returnType = "void"
                params("int", "boolean")
                addInvoke(name = "<init>") { paramCount(4) }
            }
            DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> {
                within(SponsorPlayerLocator.CONTAINER_PACKAGE)
                name = "<init>"
                paramCount(3..16)
                addInvoke("kotlin.coroutines.jvm.internal.SuspendLambda", "<init>") {
                    params("int", "kotlin.coroutines.Continuation")
                }
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
            DexAssistQuery.PLAYER_SPEED_MENU, DexAssistQuery.STORY_QUALITY_DEFAULT -> error("Expected a multi-rule query")
        }
    }

    private fun MethodRule.chromeOwners(query: DexAssistQuery) {
        val family = HomeChromeAdapter.families.getValue(query)
        within(HomeChromeAdapter.ownerPackage(family), HomeChromeAdapter.repackagedOwners(family))
    }

    private fun speedMenuRules(): List<ByteArray> =
        listOf("java.util.List", "java.util.ArrayList").flatMap { clearOwner ->
            listOf(listOf("float"), listOf("float", "java.util.List")).map { parameters ->
                rule {
                    within(PlayerSpeedLocator.SPEED_WIDGET_PKG)
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

        fun descriptor(field: String, value: String) { fields.put(field, value) }
        fun within(packageName: String, exactOwners: Set<String> = emptySet()) {
            val prefix = text("prefix", "L${packageName.trimEnd('.').replace('.', '/')}/")
            if (exactOwners.isEmpty()) fields.put("owner", prefix)
            else fields.put("any", JSONArray(listOf(JSONObject().put("owner", prefix)) +
                exactOwners.map { JSONObject().put("owner", dexType(it)) }))
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
        fun addInvoke(owner: String? = null, name: String, returnType: String? = null, configure: MethodRule.() -> Unit) {
            val target = MethodRule()
            target.name = name
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
        "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
}
