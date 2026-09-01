package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isPublic
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.security.MessageDigest

/**
 * 哔哩哔哩版本适配器（学 BiliRoaming 的 hook 点自动定位思路，轻量实现）。
 *
 * 设计目标：
 * - **前置适配**：B 站版本变化 / 全新版本 / 模块首次安装时，在 B 站启动阶段一次性
 *   自动定位各功能 hook 点并缓存；适配完成后每次启动走快路径（仅读缓存），不影响
 *   冷启动与运行期性能。
 * - **智能定位**：不在手机上反编译（性能/时间不允许），而是运行时对「内置候选类名」
 *   通过 KavaRef 验证类 + 匹配方法签名特征（如 ViewBinding 参数类含 View 字段 a），
 *   自动适配 hook 点漂移（方法重载变化、签名变化）。
 * - **手动重适配**：UI 提供「重新适配」按钮，清除缓存后重启即重新定位。
 *
 * 缓存位置：模块 prefs（LSPosed 托管，跨进程可读）：
 *   adapted_bili_version : Int     已适配的 B 站 versionCode
 *   adapt_result         : String  适配结果 JSON（各功能 hook 点类名/方法/签名）
 */
object VersionAdapter {

    private const val PREF_FILE = "innocent_lab_version_adapter"
    private const val KEY_ADAPTED_VERSION = "adapted_bili_version"
    private const val KEY_ADAPT_RESULT = "adapt_result"
    private const val KEY_RESET_TS = RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP

    @Volatile
    private var lastCacheStatus = "not-read"

    /**
     * 二级缓存文件（B 站自身 cache 目录，loadApp 阶段无 Context 也可同步读；
     * 模块 prefs 在部分设备（官方 LSPosed 无 DirectAccessService）不可用时，
     * 此文件承担「适配完成后快路径」的载体）。
     */
    private fun cacheFile(): java.io.File = TargetAppStorage.cacheFile("innocent_lab_adapt.json")

    /** 适配状态回调（toast 提示用） */
    interface AdaptCallback {
        /** 适配开始（主线程，弹提示） */
        fun onAdaptStarted()

        /** 适配完成（主线程） */
        fun onAdaptFinished(ok: Boolean)
    }

    /** 单个 hook 点定位结果 */
    data class HookPoint(
        val className: String,
        val methodName: String,
        /** 参数类型类名列表（精确签名用；null = 不限制参数） */
        val paramClassNames: List<String>? = null,
        /** Hook 点关联的已验证字段名（无关联字段时为 null）。 */
        val viewField: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("cls", className)
            put("m", methodName)
            paramClassNames?.let {
                put("params", JSONArray(it))
            }
            viewField?.let { put("vf", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): HookPoint {
                val params = if (o.has("params")) {
                    val arr = o.getJSONArray("params")
                    (0 until arr.length()).map { arr.getString(it) }
                } else {
                    null
                }
                return HookPoint(
                    o.getString("cls"), o.getString("m"), params,
                    if (o.has("vf")) o.getString("vf") else null
                )
            }
        }
    }

    /** 构造器适配结果；[listParameterIndex] 只用于以 List 为输入边界的功能。 */
    data class ListConstructorPoint(
        val className: String,
        val paramClassNames: List<String>,
        val listParameterIndex: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("cls", className)
            put("params", JSONArray(paramClassNames))
            put("list_index", listParameterIndex)
        }

        companion object {
            fun fromJson(o: JSONObject): ListConstructorPoint {
                val params = o.getJSONArray("params")
                return ListConstructorPoint(
                    className = o.getString("cls"),
                    paramClassNames = (0 until params.length()).map(params::getString),
                    listParameterIndex = o.getInt("list_index")
                )
            }
        }
    }

    /** 适配结果 JSON 结构版本（结构变化时强制重新适配，防止旧结构缓存误用） */
    private const val SCHEMA_VERSION = 43
    private const val ADAPTER_RULE_VERSION = 35

    enum class AdaptState {
        FOUND,
        MISSING,
        NOT_APPLICABLE
    }

    data class AdaptDiagnostic(
        val id: String,
        val state: AdaptState,
        val detail: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("state", state.name)
            if (detail.isNotBlank()) put("detail", detail)
        }

        companion object {
            fun fromJson(o: JSONObject): AdaptDiagnostic? = runCatching {
                AdaptDiagnostic(
                    id = o.getString("id").takeIf { it.isNotBlank() }
                        ?: return@runCatching null,
                    state = AdaptState.valueOf(o.getString("state")),
                    detail = o.optString("detail")
                )
            }.getOrNull()
        }
    }

    /** “我的”页菜单注入所需的整组结构化入口。字段名会随 R8 漂移，必须和方法一起缓存。 */
    data class MineEntryPoint(
        val buildMethods: List<HookPoint>,
        val groupListField: String,
        val adapterField: String?,
        val clickMethod: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("build", JSONArray().apply { buildMethods.forEach { put(it.toJson()) } })
            put("groups", groupListField)
            adapterField?.let { put("adapter", it) }
            put("click", clickMethod.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): MineEntryPoint {
                val build = o.getJSONArray("build")
                return MineEntryPoint(
                    buildMethods = (0 until build.length()).map {
                        HookPoint.fromJson(build.getJSONObject(it))
                    },
                    groupListField = o.getString("groups"),
                    adapterField = o.optString("adapter").takeIf { it.isNotEmpty() },
                    clickMethod = HookPoint.fromJson(o.getJSONObject("click"))
                )
            }
        }
    }

    /** 暂停页采用多入口并行注册；类存在不代表当前版本真的走该链路。 */
    data class PausePoints(
        val requestMethods: List<HookPoint>,
        val legacyCallback: HookPoint?,
        val panelShow: HookPoint?,
        val countdown: HookPoint?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("requests", JSONArray().apply { requestMethods.forEach { put(it.toJson()) } })
            legacyCallback?.let { put("legacy", it.toJson()) }
            panelShow?.let { put("panel", it.toJson()) }
            countdown?.let { put("countdown", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): PausePoints {
                val requests = o.optJSONArray("requests")
                return PausePoints(
                    requestMethods = if (requests == null) emptyList() else
                        (0 until requests.length()).map { HookPoint.fromJson(requests.getJSONObject(it)) },
                    legacyCallback = o.optJSONObject("legacy")?.let(HookPoint::fromJson),
                    panelShow = o.optJSONObject("panel")?.let(HookPoint::fromJson),
                    countdown = o.optJSONObject("countdown")?.let(HookPoint::fromJson)
                )
            }
        }
    }

    /** 首页 V8Banner 的稳定视图类型与其父类低频生命周期入口。 */
    data class BannerPoint(
        val bannerClassName: String,
        val lifecycleMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("cls", bannerClassName)
            put("hooks", JSONArray().apply { lifecycleMethods.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): BannerPoint {
                val hooks = o.getJSONArray("hooks")
                return BannerPoint(
                    bannerClassName = o.getString("cls"),
                    lifecycleMethods = (0 until hooks.length()).map {
                        HookPoint.fromJson(hooks.getJSONObject(it))
                    }
                )
            }
        }
    }

    /** 首页顶部栏游戏入口与搜索默认词的结构化入口。 */
    data class HomeTopBarPoints(
        val gameMenu: HookPoint?,
        val baseOnViewCreated: HookPoint?,
        val defaultWordMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            gameMenu?.let { put("game", it.toJson()) }
            baseOnViewCreated?.let { put("view", it.toJson()) }
            put(
                "words",
                JSONArray().apply { defaultWordMethods.forEach { put(it.toJson()) } }
            )
        }

        companion object {
            fun fromJson(o: JSONObject): HomeTopBarPoints {
                val words = o.optJSONArray("words")
                return HomeTopBarPoints(
                    gameMenu = o.optJSONObject("game")?.let(HookPoint::fromJson),
                    baseOnViewCreated = o.optJSONObject("view")?.let(HookPoint::fromJson),
                    defaultWordMethods = if (words == null) emptyList() else
                        (0 until words.length()).map {
                            HookPoint.fromJson(words.getJSONObject(it))
                        }
                )
            }
        }
    }

    /** “我的”页 VIP 卡片：Fragment → 管理器 → ViewBinding → 根视图的稳定成员链。 */
    data class MineVipPoint(
        /** onResume 的 [HookPoint.viewField] 保存 Fragment 中的管理器字段名。 */
        val onResume: HookPoint,
        val bindingField: String,
        val rootGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("resume", onResume.toJson())
            put("binding", bindingField)
            put("root", rootGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): MineVipPoint = MineVipPoint(
                onResume = HookPoint.fromJson(o.getJSONObject("resume")),
                bindingField = o.getString("binding"),
                rootGetter = HookPoint.fromJson(o.getJSONObject("root"))
            )
        }
    }

    /** 动态页筛选标签：宿主列表位置映射 + Material Tab 添加入口。 */
    data class DynamicTabsPoint(
        val listGetter: HookPoint,
        val addTab: HookPoint,
        val tabCustomViewGetter: HookPoint,
        val mediatorTabClassName: String,
        val itemClassName: String,
        val itemTitleField: String,
        val itemNameField: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("list", listGetter.toJson())
            put("add", addTab.toJson())
            put("custom", tabCustomViewGetter.toJson())
            put("tab", mediatorTabClassName)
            put("item", itemClassName)
            put("title", itemTitleField)
            put("name", itemNameField)
        }

        companion object {
            fun fromJson(o: JSONObject): DynamicTabsPoint = DynamicTabsPoint(
                listGetter = HookPoint.fromJson(o.getJSONObject("list")),
                addTab = HookPoint.fromJson(o.getJSONObject("add")),
                tabCustomViewGetter = HookPoint.fromJson(o.getJSONObject("custom")),
                mediatorTabClassName = o.getString("tab"),
                itemClassName = o.getString("item"),
                itemTitleField = o.getString("title"),
                itemNameField = o.getString("name")
            )
        }
    }

    /** 原生 Java 与 Kotlin 本地化层的数字缩写格式化入口。 */
    data class FullNumberPoints(
        val formatterMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("methods", JSONArray().apply { formatterMethods.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): FullNumberPoints {
                val methods = o.getJSONArray("methods")
                return FullNumberPoints(
                    (0 until methods.length()).map {
                        HookPoint.fromJson(methods.getJSONObject(it))
                    }
                )
            }
        }
    }

    /** 播放器竖屏切换控件自身的可见性入口；不使用全局 View Hook。 */
    data class PlayerPortraitPoints(
        val visibilityMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("methods", JSONArray().apply { visibilityMethods.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerPortraitPoints {
                val methods = o.getJSONArray("methods")
                return PlayerPortraitPoints(
                    (0 until methods.length()).map {
                        HookPoint.fromJson(methods.getJSONObject(it))
                    }
                )
            }
        }
    }

    /** 视频详情页 Activity 的稳定生命周期入口；只在精确目标 Activity 上修改系统栏。 */
    data class PlayerStatusBarPoints(
        val onCreateMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("create", JSONArray().apply { onCreateMethods.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerStatusBarPoints = PlayerStatusBarPoints(
                o.getJSONArray("create").let { methods ->
                    (0 until methods.length()).map {
                        HookPoint.fromJson(methods.getJSONObject(it))
                    }
                }
            )
        }
    }

    /** 首页推荐服务端响应及卡片公开读取边界；不依赖混淆字段名或 View 树扫描。 */
    data class HomeRecommendFeedPoints(
        val responseItemGetters: List<HookPoint>,
        val holderTypeGetter: HookPoint,
        val bizTypeGetter: HookPoint?,
        val adInfoGetter: HookPoint?,
        val cardGotoGetter: HookPoint?,
        val goToGetter: HookPoint?,
        val uriGetter: HookPoint?,
        val paramGetter: HookPoint?,
        val titleGetter: HookPoint?,
        val subtitleGetter: HookPoint?,
        val descGetter: HookPoint?,
        val playerArgsGetter: HookPoint?,
        val playerArgsDurationField: String?,
        /** app-card Base.card_type；旧模型缺失时继续使用 holder/card_goto/goto。 */
        val cardTypeGetter: HookPoint? = null,
        /** PlayerArgs.getDuration()；字段注解路径继续作为旧模型兜底。 */
        val playerArgsDurationGetter: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("responses", JSONArray().apply { responseItemGetters.forEach { put(it.toJson()) } })
            put("holder", holderTypeGetter.toJson())
            bizTypeGetter?.let { put("biz", it.toJson()) }
            adInfoGetter?.let { put("ad_info", it.toJson()) }
            cardGotoGetter?.let { put("card_goto", it.toJson()) }
            goToGetter?.let { put("goto", it.toJson()) }
            uriGetter?.let { put("uri", it.toJson()) }
            paramGetter?.let { put("param", it.toJson()) }
            titleGetter?.let { put("title", it.toJson()) }
            subtitleGetter?.let { put("subtitle", it.toJson()) }
            descGetter?.let { put("desc", it.toJson()) }
            playerArgsGetter?.let { put("player_args", it.toJson()) }
            playerArgsDurationField?.let { put("player_args_duration", it) }
            cardTypeGetter?.let { put("card_type", it.toJson()) }
            playerArgsDurationGetter?.let { put("player_args_duration_getter", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): HomeRecommendFeedPoints = HomeRecommendFeedPoints(
                responseItemGetters = o.getJSONArray("responses").let { methods ->
                    (0 until methods.length()).map {
                        HookPoint.fromJson(methods.getJSONObject(it))
                    }
                },
                holderTypeGetter = HookPoint.fromJson(o.getJSONObject("holder")),
                bizTypeGetter = o.optJSONObject("biz")?.let(HookPoint::fromJson),
                adInfoGetter = o.optJSONObject("ad_info")?.let(HookPoint::fromJson),
                cardGotoGetter = o.optJSONObject("card_goto")?.let(HookPoint::fromJson),
                goToGetter = o.optJSONObject("goto")?.let(HookPoint::fromJson),
                uriGetter = o.optJSONObject("uri")?.let(HookPoint::fromJson),
                paramGetter = o.optJSONObject("param")?.let(HookPoint::fromJson),
                titleGetter = o.optJSONObject("title")?.let(HookPoint::fromJson),
                subtitleGetter = o.optJSONObject("subtitle")?.let(HookPoint::fromJson),
                descGetter = o.optJSONObject("desc")?.let(HookPoint::fromJson),
                playerArgsGetter = o.optJSONObject("player_args")?.let(HookPoint::fromJson),
                playerArgsDurationField = o.optString("player_args_duration")
                    .takeIf { it.isNotBlank() },
                cardTypeGetter = o.optJSONObject("card_type")?.let(HookPoint::fromJson),
                playerArgsDurationGetter = o.optJSONObject("player_args_duration_getter")
                    ?.let(HookPoint::fromJson)
            )
        }
    }

    /** 详情页推荐项的“外层卡片 getter -> 数值时长 getter”公开方法链。 */
    data class DurationMethodChain(
        val itemGetter: HookPoint,
        val durationGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("item", itemGetter.toJson())
            put("duration", durationGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): DurationMethodChain = DurationMethodChain(
                itemGetter = HookPoint.fromJson(o.getJSONObject("item")),
                durationGetter = HookPoint.fromJson(o.getJSONObject("duration"))
            )
        }
    }

    /** 相关推荐卡片嵌套对象中的来源类型读取链。 */
    data class SourceTypeMethodChain(
        val itemGetter: HookPoint,
        val sourceTypeGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("item", itemGetter.toJson())
            put("source", sourceTypeGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): SourceTypeMethodChain = SourceTypeMethodChain(
                itemGetter = HookPoint.fromJson(o.getJSONObject("item")),
                sourceTypeGetter = HookPoint.fromJson(o.getJSONObject("source"))
            )
        }
    }

    /** 视频详情相关推荐响应与直接/嵌套类型公开读取边界。 */
    data class VideoRelatePoints(
        val responseItemGetters: List<HookPoint>,
        val cardCaseGetters: List<HookPoint>,
        val gotoGetters: List<HookPoint>,
        val cardTypeGetters: List<HookPoint>,
        val relateCardTypeGetters: List<HookPoint>,
        val fromSourceTypeGetters: List<HookPoint>,
        val fromSourceTypeChains: List<SourceTypeMethodChain>,
        val relateCardTypeValueGetters: List<HookPoint>,
        val directDurationGetters: List<HookPoint>,
        val durationChains: List<DurationMethodChain>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("responses", JSONArray().apply { responseItemGetters.forEach { put(it.toJson()) } })
            put("case", JSONArray().apply { cardCaseGetters.forEach { put(it.toJson()) } })
            put("goto", JSONArray().apply { gotoGetters.forEach { put(it.toJson()) } })
            put("type", JSONArray().apply { cardTypeGetters.forEach { put(it.toJson()) } })
            put(
                "relate_type",
                JSONArray().apply { relateCardTypeGetters.forEach { put(it.toJson()) } }
            )
            put(
                "source_type",
                JSONArray().apply { fromSourceTypeGetters.forEach { put(it.toJson()) } }
            )
            put(
                "source_type_chains",
                JSONArray().apply { fromSourceTypeChains.forEach { put(it.toJson()) } }
            )
            put(
                "relate_type_value",
                JSONArray().apply { relateCardTypeValueGetters.forEach { put(it.toJson()) } }
            )
            put(
                "direct_duration",
                JSONArray().apply { directDurationGetters.forEach { put(it.toJson()) } }
            )
            put(
                "duration_chains",
                JSONArray().apply { durationChains.forEach { put(it.toJson()) } }
            )
        }

        companion object {
            fun fromJson(o: JSONObject): VideoRelatePoints = VideoRelatePoints(
                responseItemGetters = o.getJSONArray("responses").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                cardCaseGetters = o.getJSONArray("case").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                gotoGetters = o.getJSONArray("goto").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                cardTypeGetters = o.getJSONArray("type").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                relateCardTypeGetters = o.getJSONArray("relate_type").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                fromSourceTypeGetters = o.getJSONArray("source_type").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                fromSourceTypeChains = o.getJSONArray("source_type_chains").let { values ->
                    (0 until values.length()).map {
                        SourceTypeMethodChain.fromJson(values.getJSONObject(it))
                    }
                },
                relateCardTypeValueGetters = o.getJSONArray("relate_type_value").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                directDurationGetters = o.optJSONArray("direct_duration")?.let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                }.orEmpty(),
                durationChains = o.optJSONArray("duration_chains")?.let { values ->
                    (0 until values.length()).map {
                        DurationMethodChain.fromJson(values.getJSONObject(it))
                    }
                }.orEmpty()
            )
        }
    }

    /** 首页 Tab 构建入口与资源对象的稳定 String 字段。 */
    data class HomeTabPoints(
        val buildMethod: HookPoint,
        val resourceClassName: String,
        val idField: String,
        val titleField: String,
        val uriField: String,
        val reporterIdField: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("build", buildMethod.toJson())
            put("resource", resourceClassName)
            put("id", idField)
            put("title", titleField)
            put("uri", uriField)
            reporterIdField?.let { put("reporter", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): HomeTabPoints = HomeTabPoints(
                buildMethod = HookPoint.fromJson(o.getJSONObject("build")),
                resourceClassName = o.getString("resource"),
                idField = o.getString("id"),
                titleField = o.getString("title"),
                uriField = o.getString("uri"),
                reporterIdField = o.optString("reporter").takeIf { it.isNotBlank() }
            )
        }
    }

    /** 首页子 Fragment 的低频生命周期与父 Fragment 公开读取边界。 */
    data class HomeComponentPoints(
        val onViewCreated: HookPoint,
        val parentFragmentGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("view", onViewCreated.toJson())
            put("parent", parentFragmentGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): HomeComponentPoints = HomeComponentPoints(
                HookPoint.fromJson(o.getJSONObject("view")),
                HookPoint.fromJson(o.getJSONObject("parent"))
            )
        }
    }

    /**
     * “我的”页菜单组过滤边界。
     *
     * 8.92.1+ 优先使用公开 getter；8.84.0–8.91.0 的同名模型只有公开字段，
     * 因此回退到“菜单构建方法 + 精确字段链”，仍不扫描 View 树或缓存宿主实例。
     */
    data class MineComponentPoints(
        val itemListGetters: List<HookPoint>,
        val itemTitleGetters: List<HookPoint>,
        val itemIdGetters: List<HookPoint> = emptyList(),
        val itemUriGetters: List<HookPoint> = emptyList(),
        val groupTitleGetters: List<HookPoint> = emptyList(),
        val legacyBuildMethods: List<HookPoint> = emptyList(),
        val legacyGroupListField: String? = null,
        val legacyAdapterField: String? = null,
        val legacyGroupClassName: String? = null,
        val legacyItemListField: String? = null,
        val legacyItemClassName: String? = null,
        val legacyItemTitleField: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("lists", JSONArray().apply { itemListGetters.forEach { put(it.toJson()) } })
            put("titles", JSONArray().apply { itemTitleGetters.forEach { put(it.toJson()) } })
            put("ids", JSONArray().apply { itemIdGetters.forEach { put(it.toJson()) } })
            put("uris", JSONArray().apply { itemUriGetters.forEach { put(it.toJson()) } })
            put("group_titles", JSONArray().apply { groupTitleGetters.forEach { put(it.toJson()) } })
            if (legacyBuildMethods.isNotEmpty()) {
                put("legacy_build", JSONArray().apply {
                    legacyBuildMethods.forEach { put(it.toJson()) }
                })
                legacyGroupListField?.let { put("legacy_groups", it) }
                legacyAdapterField?.let { put("legacy_adapter", it) }
                legacyGroupClassName?.let { put("legacy_group_class", it) }
                legacyItemListField?.let { put("legacy_items", it) }
                legacyItemClassName?.let { put("legacy_item_class", it) }
                legacyItemTitleField?.let { put("legacy_title", it) }
            }
        }

        companion object {
            fun fromJson(o: JSONObject): MineComponentPoints {
                fun points(name: String): List<HookPoint> = o.optJSONArray(name)?.let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                }.orEmpty()
                return MineComponentPoints(
                    itemListGetters = points("lists"),
                    itemTitleGetters = points("titles"),
                    itemIdGetters = points("ids"),
                    itemUriGetters = points("uris"),
                    groupTitleGetters = points("group_titles"),
                    legacyBuildMethods = points("legacy_build"),
                    legacyGroupListField = o.optString("legacy_groups").takeIf { it.isNotBlank() },
                    legacyAdapterField = o.optString("legacy_adapter").takeIf { it.isNotBlank() },
                    legacyGroupClassName = o.optString("legacy_group_class")
                        .takeIf { it.isNotBlank() },
                    legacyItemListField = o.optString("legacy_items").takeIf { it.isNotBlank() },
                    legacyItemClassName = o.optString("legacy_item_class")
                        .takeIf { it.isNotBlank() },
                    legacyItemTitleField = o.optString("legacy_title").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /**
     * “我的”页数据层剪枝边界（9.9.x 的 V2 动态流 / 部分 8.x 走 AccountMine 静态模型）。
     *
     * 锚定在 HomeUserCenterFragment 消费 AccountMine 的静态构建方法
     * (Fragment, AccountMine) -> Unit 上：在该方法 after 钩子内、后续 UI 渲染前，
     * 对 sectionListV2[].itemList 做原地剪枝（对哔哩漫游“数据层剪枝”思路的框架化移植——
     * 不引入 FastJSON 全局 hook，而是复用项目已发布的 mine 入口结构化定位）。
     * 字段名在适配期探测并缓存为 HookPoint/字段名快照，运行期直接 Field 读写，不扫描 View 树。
     */
    data class MineAccountMinePoints(
        val buildMethods: List<HookPoint>,
        val accountMineClass: String,
        val sectionListV2Field: String,
        val groupClass: String,
        val groupTitleField: String?,
        val groupItemListField: String,
        val itemClass: String,
        val itemTitleField: String,
        val itemIdField: String?,
        val itemUriField: String?,
        val itemVisibleField: String?,
        val itemLocalShowField: String?,
        val liveTipField: String?,
        val vipSectionRightField: String?,
        val sectionButtonField: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("build", JSONArray().apply { buildMethods.forEach { put(it.toJson()) } })
            put("account_mine", accountMineClass)
            put("sections", sectionListV2Field)
            put("group", groupClass)
            groupTitleField?.let { put("group_title", it) }
            put("group_items", groupItemListField)
            put("item", itemClass)
            put("item_title", itemTitleField)
            itemIdField?.let { put("item_id", it) }
            itemUriField?.let { put("item_uri", it) }
            itemVisibleField?.let { put("item_visible", it) }
            itemLocalShowField?.let { put("item_local_show", it) }
            liveTipField?.let { put("live_tip", it) }
            vipSectionRightField?.let { put("vip_section_right", it) }
            sectionButtonField?.let { put("section_button", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): MineAccountMinePoints {
                val build = o.getJSONArray("build")
                return MineAccountMinePoints(
                    buildMethods = (0 until build.length()).map {
                        HookPoint.fromJson(build.getJSONObject(it))
                    },
                    accountMineClass = o.getString("account_mine"),
                    sectionListV2Field = o.getString("sections"),
                    groupClass = o.getString("group"),
                    groupTitleField = o.optString("group_title").takeIf { it.isNotBlank() },
                    groupItemListField = o.getString("group_items"),
                    itemClass = o.getString("item"),
                    itemTitleField = o.getString("item_title"),
                    itemIdField = o.optString("item_id").takeIf { it.isNotBlank() },
                    itemUriField = o.optString("item_uri").takeIf { it.isNotBlank() },
                    itemVisibleField = o.optString("item_visible").takeIf { it.isNotBlank() },
                    itemLocalShowField = o.optString("item_local_show").takeIf { it.isNotBlank() },
                    liveTipField = o.optString("live_tip").takeIf { it.isNotBlank() },
                    vipSectionRightField = o.optString("vip_section_right")
                        .takeIf { it.isNotBlank() },
                    sectionButtonField = o.optString("section_button").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /** Story 响应/播放器列表边界与 StoryDetail 精确类型判定方法。 */
    data class StoryFeedPoints(
        val responseItemGetters: List<HookPoint>,
        val pagerListMethods: List<HookPoint>,
        val adGetter: HookPoint?,
        val liveGetter: HookPoint?,
        val gameGetter: HookPoint?,
        val bangumiGetter: HookPoint? = null,
        val courseGetter: HookPoint? = null,
        val musicGetter: HookPoint? = null,
        val cartInfoGetter: HookPoint? = null,
        val dramaPromptGetter: HookPoint? = null,
        val seasonInfoGetter: HookPoint? = null,
        val seasonTypeGetter: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("responses", JSONArray().apply { responseItemGetters.forEach { put(it.toJson()) } })
            put("pager", JSONArray().apply { pagerListMethods.forEach { put(it.toJson()) } })
            adGetter?.let { put("ad", it.toJson()) }
            liveGetter?.let { put("live", it.toJson()) }
            gameGetter?.let { put("game", it.toJson()) }
            bangumiGetter?.let { put("bangumi", it.toJson()) }
            courseGetter?.let { put("course", it.toJson()) }
            musicGetter?.let { put("music", it.toJson()) }
            cartInfoGetter?.let { put("cart_info", it.toJson()) }
            dramaPromptGetter?.let { put("drama_prompt", it.toJson()) }
            seasonInfoGetter?.let { put("season_info", it.toJson()) }
            seasonTypeGetter?.let { put("season_type", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): StoryFeedPoints = StoryFeedPoints(
                responseItemGetters = o.getJSONArray("responses").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                pagerListMethods = o.getJSONArray("pager").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                adGetter = o.optJSONObject("ad")?.let(HookPoint::fromJson),
                liveGetter = o.optJSONObject("live")?.let(HookPoint::fromJson),
                gameGetter = o.optJSONObject("game")?.let(HookPoint::fromJson),
                bangumiGetter = o.optJSONObject("bangumi")?.let(HookPoint::fromJson),
                courseGetter = o.optJSONObject("course")?.let(HookPoint::fromJson),
                musicGetter = o.optJSONObject("music")?.let(HookPoint::fromJson),
                cartInfoGetter = o.optJSONObject("cart_info")?.let(HookPoint::fromJson),
                dramaPromptGetter = o.optJSONObject("drama_prompt")?.let(HookPoint::fromJson),
                seasonInfoGetter = o.optJSONObject("season_info")?.let(HookPoint::fromJson),
                seasonTypeGetter = o.optJSONObject("season_type")?.let(HookPoint::fromJson)
            )
        }
    }

    /** 详情页 Tab 配置构造边界及 TabPage 的公开定位标签。 */
    data class CommentSectionPoints(
        val listConstructors: List<ListConstructorPoint>,
        val locatableTagGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("constructors", JSONArray().apply {
                listConstructors.forEach { put(it.toJson()) }
            })
            put("tag", locatableTagGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): CommentSectionPoints = CommentSectionPoints(
                listConstructors = o.getJSONArray("constructors").let { values ->
                    (0 until values.length()).map {
                        ListConstructorPoint.fromJson(values.getJSONObject(it))
                    }
                },
                locatableTagGetter = HookPoint.fromJson(o.getJSONObject("tag"))
            )
        }
    }

    data class SplashItemSignalPoint(val role: String, val getter: HookPoint) {
        fun toJson(): JSONObject = JSONObject().put("role", role).put("getter", getter.toJson())

        companion object {
            fun fromJson(o: JSONObject): SplashItemSignalPoint = SplashItemSignalPoint(
                role = o.getString("role"),
                getter = HookPoint.fromJson(o.getJSONObject("getter"))
            )
        }
    }

    /** 开屏响应对象的精确广告/策略 List getter，以及可选的 SplashItem 语义 getter。 */
    data class SplashAdPoints(
        val listGetters: List<HookPoint>,
        val itemSignalGetters: List<SplashItemSignalPoint> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("getters", JSONArray().apply { listGetters.forEach { put(it.toJson()) } })
            put("signals", JSONArray().apply {
                itemSignalGetters.forEach { put(it.toJson()) }
            })
        }

        companion object {
            fun fromJson(o: JSONObject): SplashAdPoints = SplashAdPoints(
                listGetters = o.getJSONArray("getters").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                itemSignalGetters = o.optJSONArray("signals")?.let { values ->
                    (0 until values.length()).map {
                        SplashItemSignalPoint.fromJson(values.getJSONObject(it))
                    }
                }.orEmpty()
            )
        }
    }

    /** 底栏 TabHost 公开列表读取边界、单项绑定入口与条目 String 字段。 */
    data class BottomBarPoints(
        val tabsGetter: HookPoint,
        val bindTabMethod: HookPoint,
        val itemClassName: String,
        val itemStringFields: List<String>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tabs", tabsGetter.toJson())
            put("bind", bindTabMethod.toJson())
            put("item", itemClassName)
            put("strings", JSONArray(itemStringFields))
        }

        companion object {
            fun fromJson(o: JSONObject): BottomBarPoints = BottomBarPoints(
                tabsGetter = HookPoint.fromJson(o.getJSONObject("tabs")),
                bindTabMethod = HookPoint.fromJson(o.getJSONObject("bind")),
                itemClassName = o.getString("item"),
                itemStringFields = o.getJSONArray("strings").let { values ->
                    (0 until values.length()).map(values::getString)
                }
            )
        }
    }

    /**
     * 评论关键词/等级过滤的公开 protobuf 读取边界。
     *
     * 列表 getter 只负责提供待筛选的 ReplyInfo；正文和等级 getter 只读取判定信号，
     * 不写 protobuf 私有字段，也不接触评论富文本、emoji 或 View 绑定链路。
     */
    data class CommentFilterPoints(
        val replyListGetters: List<HookPoint>,
        val contentGetter: HookPoint,
        val messageGetter: HookPoint,
        val memberGetter: HookPoint?,
        val levelGetter: HookPoint?,
        val memberV2Getter: HookPoint? = null,
        val memberV2BasicGetter: HookPoint? = null,
        val memberV2LevelGetter: HookPoint? = null,
        val topReplyGetters: List<HookPoint> = emptyList(),
        val replyDefaultInstanceGetter: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("lists", JSONArray().apply { replyListGetters.forEach { put(it.toJson()) } })
            put("content", contentGetter.toJson())
            put("message", messageGetter.toJson())
            memberGetter?.let { put("member", it.toJson()) }
            levelGetter?.let { put("level", it.toJson()) }
            memberV2Getter?.let { put("member_v2", it.toJson()) }
            memberV2BasicGetter?.let { put("member_v2_basic", it.toJson()) }
            memberV2LevelGetter?.let { put("member_v2_level", it.toJson()) }
            put("top_replies", JSONArray().apply { topReplyGetters.forEach { put(it.toJson()) } })
            replyDefaultInstanceGetter?.let { put("reply_default", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): CommentFilterPoints = CommentFilterPoints(
                replyListGetters = o.getJSONArray("lists").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                contentGetter = HookPoint.fromJson(o.getJSONObject("content")),
                messageGetter = HookPoint.fromJson(o.getJSONObject("message")),
                memberGetter = o.optJSONObject("member")?.let(HookPoint::fromJson),
                levelGetter = o.optJSONObject("level")?.let(HookPoint::fromJson),
                memberV2Getter = o.optJSONObject("member_v2")?.let(HookPoint::fromJson),
                memberV2BasicGetter = o.optJSONObject("member_v2_basic")
                    ?.let(HookPoint::fromJson),
                memberV2LevelGetter = o.optJSONObject("member_v2_level")
                    ?.let(HookPoint::fromJson),
                topReplyGetters = o.optJSONArray("top_replies")?.let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                }.orEmpty(),
                replyDefaultInstanceGetter = o.optJSONObject("reply_default")
                    ?.let(HookPoint::fromJson)
            )
        }
    }

    /**
     * 回复脉络所需的宿主公开数据边界。
     *
     * [mapperMethods] 只负责在 ReplyInfo 转换成 CommentItem 时建立弱身份桥；[methods]
     * 保存构图和宿主 MOSS 分页所需的已验证成员。运行时只缓存 Class/Method/Constructor，
     * 不缓存 ReplyInfo、CommentItem、Activity 或 View。
     */
    data class CommentTopologyPoints(
        val mapperMethods: List<HookPoint>,
        val replyMossClassName: String,
        val methods: Map<String, HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("mappers", JSONArray().apply {
                mapperMethods.forEach { put(it.toJson()) }
            })
            put("moss", replyMossClassName)
            put("methods", JSONObject().apply {
                methods.forEach { (key, point) -> put(key, point.toJson()) }
            })
        }

        fun hasRequiredMethods(): Boolean =
            mapperMethods.isNotEmpty() && mapperMethods.size <= MAX_MAPPER_METHODS &&
                REQUIRED_METHOD_KEYS.all(methods::containsKey)

        companion object {
            const val REPLY_ID = "reply.id"
            const val COMMENT_ITEM_ID = "comment_item.id"
            const val REPLY_OID = "reply.oid"
            const val REPLY_TYPE = "reply.type"
            const val REPLY_ROOT = "reply.root"
            const val REPLY_PARENT = "reply.parent"
            const val REPLY_DIALOG = "reply.dialog"
            const val REPLY_CTIME = "reply.ctime"
            const val REPLY_COUNT = "reply.count"
            const val REPLY_MID = "reply.mid"
            const val REPLY_CONTENT = "reply.content"
            const val REPLY_MEMBER = "reply.member"
            const val REPLY_MEMBER_V2 = "reply.member_v2"
            const val REPLY_PARENT_MEMBER = "reply.parent_member"
            const val REPLY_CHILDREN = "reply.children"
            const val CONTENT_MESSAGE = "content.message"
            const val MEMBER_NAME = "member.name"
            const val MEMBER_V2_BASIC = "member_v2.basic"
            const val MEMBER_BASIC_NAME = "member_basic.name"
            const val PARENT_MEMBER_NAME = "parent_member.name"
            const val PAGINATION_NEW_BUILDER = "pagination.new_builder"
            const val PAGINATION_SET_OFFSET = "pagination.set_offset"
            const val PAGINATION_BUILD = "pagination.build"
            const val DETAIL_NEW_BUILDER = "detail.new_builder"
            const val DETAIL_SET_OID = "detail.set_oid"
            const val DETAIL_SET_TYPE = "detail.set_type"
            const val DETAIL_SET_MODE = "detail.set_mode"
            const val DETAIL_SET_PAGINATION = "detail.set_pagination"
            const val DETAIL_SET_ROOT = "detail.set_root"
            const val DETAIL_SET_RPID = "detail.set_rpid"
            const val DETAIL_BUILD = "detail.build"
            const val MOSS_DETAIL = "moss.detail"
            const val DETAIL_ROOT = "detail_reply.root"
            const val DETAIL_PAGINATION = "detail_reply.pagination"
            const val PAGINATION_NEXT_OFFSET = "pagination_reply.next_offset"
            const val MAX_MAPPER_METHODS = 24

            val REQUIRED_METHOD_KEYS: Set<String> = linkedSetOf(
                REPLY_ID, COMMENT_ITEM_ID, REPLY_OID, REPLY_TYPE, REPLY_ROOT, REPLY_PARENT,
                REPLY_DIALOG, REPLY_CTIME, REPLY_COUNT, REPLY_MID, REPLY_CONTENT,
                REPLY_MEMBER_V2, REPLY_CHILDREN, CONTENT_MESSAGE,
                MEMBER_V2_BASIC, MEMBER_BASIC_NAME,
                PAGINATION_NEW_BUILDER,
                PAGINATION_SET_OFFSET, PAGINATION_BUILD, DETAIL_NEW_BUILDER,
                DETAIL_SET_OID, DETAIL_SET_TYPE, DETAIL_SET_MODE,
                DETAIL_SET_PAGINATION, DETAIL_SET_ROOT, DETAIL_SET_RPID, DETAIL_BUILD,
                MOSS_DETAIL, DETAIL_ROOT, DETAIL_PAGINATION,
                PAGINATION_NEXT_OFFSET
            )

            fun fromJson(o: JSONObject): CommentTopologyPoints {
                val methodObject = o.getJSONObject("methods")
                val methods = linkedMapOf<String, HookPoint>()
                val keys = methodObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    methods[key] = HookPoint.fromJson(methodObject.getJSONObject(key))
                }
                return CommentTopologyPoints(
                    mapperMethods = o.getJSONArray("mappers").let { values ->
                        (0 until values.length()).map {
                            HookPoint.fromJson(values.getJSONObject(it))
                        }
                    },
                    replyMossClassName = o.getString("moss"),
                    methods = methods
                )
            }
        }
    }

    /** 播放器默认画质计算边界；仅保存经逐版本核验的唯一无参 Int 方法。 */
    data class PlayerQualityPoints(
        val defaultQualityMethod: HookPoint,
        /** 只读结构证据，不注册额外播放响应 Hook。 */
        val capabilitySignals: List<String> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("default", defaultQualityMethod.toJson())
            put("capabilities", JSONArray(capabilitySignals))
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerQualityPoints = PlayerQualityPoints(
                defaultQualityMethod = HookPoint.fromJson(o.getJSONObject("default")),
                capabilitySignals = o.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).map(values::getString)
                }.orEmpty()
            )
        }
    }

    /** 青少年模式提示页自身的创建入口；只结束明确命名的提示 Activity。 */
    data class TeenagersModePoints(
        val onCreateMethods: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("methods", JSONArray().apply { onCreateMethods.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): TeenagersModePoints {
                val methods = o.getJSONArray("methods")
                return TeenagersModePoints(
                    (0 until methods.length()).map {
                        HookPoint.fromJson(methods.getJSONObject(it))
                    }
                )
            }
        }
    }

    /** 空评论区引导 getter 与对应 protobuf 默认实例 getter。 */
    data class CommentEmptyPagePoint(
        val contentGetter: HookPoint,
        val defaultInstanceGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("content", contentGetter.toJson())
            put("default", defaultInstanceGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): CommentEmptyPagePoint = CommentEmptyPagePoint(
                contentGetter = HookPoint.fromJson(o.getJSONObject("content")),
                defaultInstanceGetter = HookPoint.fromJson(o.getJSONObject("default"))
            )
        }
    }

    /** 独立关注控件的状态入口，以及头部装饰容器中的关注按钮绑定入口。 */
    data class CommentFollowPoints(
        val widgetStateMethods: List<HookPoint>,
        val headerBindMethods: List<HookPoint>,
        val followButtonClassName: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("widget", JSONArray().apply { widgetStateMethods.forEach { put(it.toJson()) } })
            put("header", JSONArray().apply { headerBindMethods.forEach { put(it.toJson()) } })
            followButtonClassName?.let { put("button", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): CommentFollowPoints {
                val widget = o.getJSONArray("widget")
                val header = o.getJSONArray("header")
                return CommentFollowPoints(
                    widgetStateMethods = (0 until widget.length()).map {
                        HookPoint.fromJson(widget.getJSONObject(it))
                    },
                    headerBindMethods = (0 until header.length()).map {
                        HookPoint.fromJson(header.getJSONObject(it))
                    },
                    followButtonClassName = o.optString("button").takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    /** protobuf 可选消息字段的公开读取边界；不依赖或修改生成类的私有存储。 */
    data class CommentOptionalPayloadPoint(
        val presenceGetter: HookPoint,
        val contentGetter: HookPoint,
        val defaultInstanceGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("presence", presenceGetter.toJson())
            put("content", contentGetter.toJson())
            put("default", defaultInstanceGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): CommentOptionalPayloadPoint =
                CommentOptionalPayloadPoint(
                    presenceGetter = HookPoint.fromJson(o.getJSONObject("presence")),
                    contentGetter = HookPoint.fromJson(o.getJSONObject("content")),
                    defaultInstanceGetter = HookPoint.fromJson(o.getJSONObject("default"))
                )
        }
    }

    /** 评论 protobuf 的净化边界；不触碰正文、emoji 或评论视图。 */
    data class CommentPurifyPoints(
        val urlMapGetters: List<HookPoint>,
        val emptyPageGetters: List<CommentEmptyPagePoint>,
        val voteWidgetMethods: List<HookPoint>,
        val follow: CommentFollowPoints?,
        val qoe: CommentOptionalPayloadPoint?,
        val operations: List<CommentOptionalPayloadPoint>,
        val quickReplyDialogMethods: List<HookPoint> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("urls", JSONArray().apply { urlMapGetters.forEach { put(it.toJson()) } })
            put(
                "empty_pages",
                JSONArray().apply { emptyPageGetters.forEach { put(it.toJson()) } }
            )
            put("vote", JSONArray().apply { voteWidgetMethods.forEach { put(it.toJson()) } })
            follow?.let { put("follow", it.toJson()) }
            qoe?.let { put("qoe", it.toJson()) }
            put("operations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
            put(
                "quick_reply",
                JSONArray().apply { quickReplyDialogMethods.forEach { put(it.toJson()) } }
            )
        }

        companion object {
            fun fromJson(o: JSONObject): CommentPurifyPoints {
                val urls = o.getJSONArray("urls")
                return CommentPurifyPoints(
                    (0 until urls.length()).map {
                        HookPoint.fromJson(urls.getJSONObject(it))
                    },
                    o.getJSONArray("empty_pages").let { emptyPages ->
                        (0 until emptyPages.length()).map {
                            CommentEmptyPagePoint.fromJson(emptyPages.getJSONObject(it))
                        }
                    },
                    o.getJSONArray("vote").let { voteMethods ->
                        (0 until voteMethods.length()).map {
                            HookPoint.fromJson(voteMethods.getJSONObject(it))
                        }
                    },
                    if (o.has("follow")) CommentFollowPoints.fromJson(o.getJSONObject("follow"))
                    else null,
                    o.optJSONObject("qoe")?.let(CommentOptionalPayloadPoint::fromJson),
                    o.getJSONArray("operations").let { operations ->
                        (0 until operations.length()).map {
                            CommentOptionalPayloadPoint.fromJson(operations.getJSONObject(it))
                        }
                    },
                    o.optJSONArray("quick_reply")?.let { methods ->
                        (0 until methods.length()).map {
                            HookPoint.fromJson(methods.getJSONObject(it))
                        }
                    }.orEmpty()
                )
            }
        }
    }

    /** 适配结果（各功能 hook 点） */
    data class AdaptResult(
        val biliVersionCode: Int,
        /** 适配完成时间戳（手动重适配标记比对用） */
        val ts: Long,
        /** 评论 holder 低版本路径（t0.o0 体系） */
        val commentLow: HookPoint?,
        /** 评论 handler 高版本路径（V2 体系） */
        val commentHigh: HookPoint?,
        /** “我的”页菜单构建、字段和点击分发入口。 */
        val mineEntry: MineEntryPoint?,
        /** 暂停页所有可用请求/渲染兜底入口。 */
        val pause: PausePoints,
        /** 首页 V8Banner 稳定视图入口。 */
        val banner: BannerPoint?,
        /** 首页顶部栏游戏入口/搜索默认词入口。 */
        val homeTopBar: HomeTopBarPoints?,
        /** “我的”页 VIP 卡片成员链。 */
        val mineVip: MineVipPoint?,
        /** 客户端更新信息同步入口。 */
        val blockUpdate: HookPoint?,
        /** 动态页筛选标签渲染与位置映射入口。 */
        val dynamicTabs: DynamicTabsPoint?,
        /** 完整数字显示的所有格式化入口。 */
        val fullNumbers: FullNumberPoints?,
        /** 播放器竖屏切换控件自身的可见性入口。 */
        val playerPortrait: PlayerPortraitPoints?,
        /** 视频详情页透明状态栏的精确 Activity 生命周期入口。 */
        val playerStatusBar: PlayerStatusBarPoints?,
        /** 首页推荐服务端响应及卡片公开读取边界。 */
        val homeRecommendFeed: HomeRecommendFeedPoints?,
        /** 视频详情相关推荐响应及精确类型读取边界。 */
        val videoRelate: VideoRelatePoints?,
        /** 首页 Tab 自定义隐藏的构建边界。 */
        val homeTabs: HomeTabPoints?,
        /** 首页子组件自定义隐藏的 Fragment 边界。 */
        val homeComponents: HomeComponentPoints?,
        /** “我的”页组件自定义隐藏的数据边界。 */
        val mineComponents: MineComponentPoints?,
        /** “我的”页数据层剪枝边界（AccountMine.sectionListV2 原地剪枝，9.9.x 主路径）。 */
        val mineAccountMine: MineAccountMinePoints? = null,
        /** Story 竖屏流响应与精确类型读取边界。 */
        val storyFeed: StoryFeedPoints?,
        /** 首页底栏单项绑定与条目元数据边界。 */
        val bottomBar: BottomBarPoints?,
        /** 播放器默认画质的统一计算入口。 */
        val playerQuality: PlayerQualityPoints?,
        /** 青少年模式提示页自身的 onCreate 入口。 */
        val teenagersMode: TeenagersModePoints?,
        /** 评论区净化所需的 protobuf 数据边界。 */
        val commentPurify: CommentPurifyPoints?,
        /** 评论关键词与等级过滤的公开 protobuf 列表/信号边界。 */
        val commentFilter: CommentFilterPoints?,
        /** 回复脉络的 ReplyInfo 身份桥与宿主 MOSS 分页边界。 */
        val commentTopology: CommentTopologyPoints? = null,
        /** 视频详情页评论 Tab 的结构化配置边界。 */
        val commentSection: CommentSectionPoints? = null,
        /** 开屏响应中的广告与展示策略列表边界。 */
        val splashAds: SplashAdPoints? = null,
        /** 宿主 APK + 适配规则指纹，防止只凭 versionCode 复用陈旧缓存。 */
        val hostFingerprint: String,
        /** 公开协议 getter、返回类型、嵌套链及可用字段编号的结构摘要。 */
        val protocolFingerprint: String,
        /** 每个逻辑 Hook 点的定位结果，供日志/UI 诊断。 */
        val diagnostics: List<AdaptDiagnostic>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("sv", SCHEMA_VERSION)
            put("v", biliVersionCode)
            put("ts", ts)
            commentLow?.let { put("low", it.toJson()) }
            commentHigh?.let { put("high", it.toJson()) }
            mineEntry?.let { put("mine", it.toJson()) }
            put("pause", pause.toJson())
            banner?.let { put("banner", it.toJson()) }
            homeTopBar?.let { put("home_top", it.toJson()) }
            mineVip?.let { put("mine_vip", it.toJson()) }
            blockUpdate?.let { put("block_update", it.toJson()) }
            dynamicTabs?.let { put("dynamic_tabs", it.toJson()) }
            fullNumbers?.let { put("full_numbers", it.toJson()) }
            playerPortrait?.let { put("player_portrait", it.toJson()) }
            playerStatusBar?.let { put("player_status_bar", it.toJson()) }
            homeRecommendFeed?.let { put("home_recommend_feed", it.toJson()) }
            videoRelate?.let { put("video_relate", it.toJson()) }
            homeTabs?.let { put("home_tabs", it.toJson()) }
            homeComponents?.let { put("home_components", it.toJson()) }
            mineComponents?.let { put("mine_components", it.toJson()) }
            mineAccountMine?.let { put("mine_account_mine", it.toJson()) }
            storyFeed?.let { put("story_feed", it.toJson()) }
            bottomBar?.let { put("bottom_bar", it.toJson()) }
            playerQuality?.let { put("player_quality", it.toJson()) }
            teenagersMode?.let { put("teenagers_mode", it.toJson()) }
            commentPurify?.let { put("comment_purify", it.toJson()) }
            commentFilter?.let { put("comment_filter", it.toJson()) }
            commentTopology?.let { put("comment_topology", it.toJson()) }
            commentSection?.let { put("comment_section", it.toJson()) }
            splashAds?.let { put("splash_ads", it.toJson()) }
            put("fp", hostFingerprint)
            put("protocol_fp", protocolFingerprint)
            put("diag", JSONArray().apply { diagnostics.forEach { put(it.toJson()) } })
        }

        fun isUsableWith(expectedFingerprint: String): Boolean =
            hostFingerprint == expectedFingerprint && isStructurallyValid()

        fun diagnosticSummary(): String = diagnostics
            .groupingBy { it.state }
            .eachCount()
            .let { counts ->
                AdaptState.entries.joinToString(",") { state ->
                    "${state.name.lowercase()}=${counts[state] ?: 0}"
                }
            }

        private fun HookPoint.isValid(): Boolean =
            className.isNotBlank() && methodName.isNotBlank() &&
                paramClassNames?.all { it.isNotBlank() } != false

        private fun isStructurallyValid(): Boolean =
            biliVersionCode >= 0 && hostFingerprint.isNotBlank() &&
                protocolFingerprint.matches(PROTOCOL_FINGERPRINT_PATTERN) &&
                commentLow?.isValid() != false && commentHigh?.isValid() != false &&
                mineEntry?.let { mine ->
                    mine.groupListField.isNotBlank() && mine.buildMethods.all { it.isValid() } &&
                        mine.clickMethod.isValid()
                } != false &&
                pause.requestMethods.all { it.isValid() } &&
                pause.legacyCallback?.isValid() != false &&
                pause.panelShow?.isValid() != false &&
                pause.countdown?.isValid() != false &&
                banner?.let { value ->
                    value.bannerClassName.isNotBlank() && value.lifecycleMethods.all { it.isValid() }
                } != false &&
                homeTopBar?.let { value ->
                    value.gameMenu?.let { point ->
                        point.isValid() && !point.viewField.isNullOrBlank()
                    } != false &&
                        value.baseOnViewCreated?.let { point ->
                            point.isValid() && !point.viewField.isNullOrBlank()
                        } != false &&
                        value.defaultWordMethods.all { it.isValid() }
                } != false &&
                mineVip?.let { value ->
                    value.onResume.isValid() && !value.onResume.viewField.isNullOrBlank() &&
                        value.bindingField.isNotBlank() && value.rootGetter.isValid()
                } != false &&
                blockUpdate?.isValid() != false &&
                dynamicTabs?.let { value ->
                    value.listGetter.isValid() && value.addTab.isValid() &&
                        value.tabCustomViewGetter.isValid() &&
                        value.mediatorTabClassName.isNotBlank() &&
                        value.itemClassName.isNotBlank() &&
                        value.itemTitleField.isNotBlank() && value.itemNameField.isNotBlank()
                } != false &&
                fullNumbers?.formatterMethods?.let { methods ->
                    methods.isNotEmpty() && methods.all { it.isValid() }
                } != false &&
                playerPortrait?.visibilityMethods?.let { methods ->
                    methods.isNotEmpty() && methods.all { it.isValid() }
                } != false &&
                playerStatusBar?.onCreateMethods?.let { methods ->
                    methods.isNotEmpty() && methods.all { it.isValid() }
                } != false &&
                homeRecommendFeed?.let { value ->
                    value.responseItemGetters.isNotEmpty() &&
                        value.responseItemGetters.all { it.isValid() } &&
                        value.holderTypeGetter.isValid() &&
                        value.bizTypeGetter?.isValid() != false &&
                        value.adInfoGetter?.isValid() != false &&
                        value.cardGotoGetter?.isValid() != false &&
                        value.goToGetter?.isValid() != false &&
                        value.uriGetter?.isValid() != false &&
                        value.paramGetter?.isValid() != false &&
                        value.titleGetter?.isValid() != false &&
                        value.subtitleGetter?.isValid() != false &&
                        value.descGetter?.isValid() != false &&
                        value.playerArgsGetter?.isValid() != false &&
                        value.cardTypeGetter?.isValid() != false &&
                        value.playerArgsDurationGetter?.isValid() != false &&
                        if (value.playerArgsGetter == null) {
                            value.playerArgsDurationField.isNullOrBlank() &&
                                value.playerArgsDurationGetter == null
                        } else {
                            !value.playerArgsDurationField.isNullOrBlank() ||
                                value.playerArgsDurationGetter != null
                        }
                } != false &&
                videoRelate?.let { value ->
                    value.responseItemGetters.isNotEmpty() &&
                        value.responseItemGetters.all { it.isValid() } &&
                        (value.cardCaseGetters.isNotEmpty() || value.gotoGetters.isNotEmpty() ||
                            value.cardTypeGetters.isNotEmpty() ||
                            value.relateCardTypeGetters.isNotEmpty() ||
                            value.fromSourceTypeGetters.isNotEmpty() ||
                            value.fromSourceTypeChains.isNotEmpty() ||
                            value.relateCardTypeValueGetters.isNotEmpty() ||
                            value.directDurationGetters.isNotEmpty() ||
                            value.durationChains.isNotEmpty()) &&
                        value.cardCaseGetters.all { it.isValid() } &&
                        value.gotoGetters.all { it.isValid() } &&
                        value.cardTypeGetters.all { it.isValid() } &&
                        value.relateCardTypeGetters.all { it.isValid() } &&
                        value.fromSourceTypeGetters.all { it.isValid() } &&
                        value.fromSourceTypeChains.all { chain ->
                            chain.itemGetter.isValid() && chain.sourceTypeGetter.isValid()
                        } &&
                        value.relateCardTypeValueGetters.all { it.isValid() } &&
                        value.directDurationGetters.all { it.isValid() } &&
                        value.durationChains.all { chain ->
                            chain.itemGetter.isValid() && chain.durationGetter.isValid()
                        }
                } != false &&
                homeTabs?.let { value ->
                    value.buildMethod.isValid() && value.resourceClassName.isNotBlank() &&
                        value.idField.isNotBlank() && value.titleField.isNotBlank() &&
                        value.uriField.isNotBlank()
                } != false &&
                homeComponents?.let { value ->
                    value.onViewCreated.isValid() && value.parentFragmentGetter.isValid()
                } != false &&
                mineComponents?.let { value ->
                    val getterPathValid = value.itemListGetters.isNotEmpty() &&
                        value.itemTitleGetters.isNotEmpty() &&
                        value.itemListGetters.all { it.isValid() } &&
                        value.itemTitleGetters.all { it.isValid() }
                    val legacyPathValid = value.legacyBuildMethods.isNotEmpty() &&
                        value.legacyBuildMethods.all { it.isValid() } &&
                        !value.legacyGroupListField.isNullOrBlank() &&
                        !value.legacyGroupClassName.isNullOrBlank() &&
                        !value.legacyItemListField.isNullOrBlank() &&
                        !value.legacyItemClassName.isNullOrBlank() &&
                        !value.legacyItemTitleField.isNullOrBlank()
                    getterPathValid || legacyPathValid
                } != false &&
                storyFeed?.let { value ->
                    (value.responseItemGetters.isNotEmpty() || value.pagerListMethods.isNotEmpty()) &&
                        value.responseItemGetters.all { it.isValid() } &&
                        value.pagerListMethods.all { it.isValid() } &&
                        (value.adGetter != null || value.liveGetter != null ||
                            value.gameGetter != null || value.bangumiGetter != null ||
                            value.courseGetter != null || value.musicGetter != null ||
                            value.cartInfoGetter != null || value.dramaPromptGetter != null ||
                            value.seasonInfoGetter != null) &&
                        value.adGetter?.isValid() != false &&
                        value.liveGetter?.isValid() != false &&
                        value.gameGetter?.isValid() != false &&
                        value.bangumiGetter?.isValid() != false &&
                        value.courseGetter?.isValid() != false &&
                        value.musicGetter?.isValid() != false &&
                        value.cartInfoGetter?.isValid() != false &&
                        value.dramaPromptGetter?.isValid() != false &&
                        value.seasonInfoGetter?.isValid() != false &&
                        value.seasonTypeGetter?.isValid() != false &&
                        (value.seasonInfoGetter == null) == (value.seasonTypeGetter == null)
                } != false &&
                bottomBar?.let { value ->
                    value.tabsGetter.isValid() && value.bindTabMethod.isValid() &&
                        value.itemClassName.isNotBlank() && value.itemStringFields.isNotEmpty() &&
                        value.itemStringFields.all { it.isNotBlank() }
                } != false &&
                playerQuality?.let { value ->
                    value.defaultQualityMethod.isValid() &&
                        value.capabilitySignals.all { it in PLAYER_QUALITY_CAPABILITY_SIGNALS }
                } != false &&
                teenagersMode?.onCreateMethods?.let { methods ->
                    methods.isNotEmpty() && methods.all { it.isValid() }
                } != false &&
                commentPurify?.let { value ->
                    (value.urlMapGetters.isNotEmpty() || value.emptyPageGetters.isNotEmpty() ||
                        value.voteWidgetMethods.isNotEmpty() || value.follow != null ||
                        value.qoe != null || value.operations.isNotEmpty() ||
                        value.quickReplyDialogMethods.isNotEmpty()) &&
                        value.urlMapGetters.all { it.isValid() } &&
                        value.emptyPageGetters.all {
                            it.contentGetter.isValid() && it.defaultInstanceGetter.isValid()
                        } && value.voteWidgetMethods.all { it.isValid() } &&
                        value.follow?.let { follow ->
                            (follow.widgetStateMethods.isNotEmpty() || follow.headerBindMethods.isNotEmpty()) &&
                                follow.widgetStateMethods.all { it.isValid() } &&
                                follow.headerBindMethods.all { it.isValid() } &&
                                (follow.headerBindMethods.isEmpty() ||
                                    !follow.followButtonClassName.isNullOrBlank())
                        } != false && value.qoe?.let { qoe ->
                            qoe.presenceGetter.isValid() && qoe.contentGetter.isValid() &&
                                qoe.defaultInstanceGetter.isValid()
                        } != false && value.operations.all { operation ->
                            operation.presenceGetter.isValid() &&
                                operation.contentGetter.isValid() &&
                                operation.defaultInstanceGetter.isValid()
                        } && value.quickReplyDialogMethods.all { it.isValid() }
                } != false &&
                commentFilter?.let { value ->
                    value.replyListGetters.isNotEmpty() &&
                        value.replyListGetters.all { it.isValid() } &&
                        value.contentGetter.isValid() && value.messageGetter.isValid() &&
                        value.memberGetter?.isValid() != false &&
                        value.levelGetter?.isValid() != false &&
                        value.memberV2Getter?.isValid() != false &&
                        value.memberV2BasicGetter?.isValid() != false &&
                        value.memberV2LevelGetter?.isValid() != false &&
                        ((value.memberGetter != null && value.levelGetter != null) ||
                            (value.memberV2Getter != null &&
                                value.memberV2BasicGetter != null &&
                                value.memberV2LevelGetter != null)) &&
                        value.topReplyGetters.all { it.isValid() } &&
                        (value.topReplyGetters.isEmpty() ||
                            value.replyDefaultInstanceGetter?.isValid() == true)
                } != false &&
                commentTopology?.let { value ->
                    value.mapperMethods.isNotEmpty() &&
                        value.mapperMethods.size <= CommentTopologyPoints.MAX_MAPPER_METHODS &&
                        value.mapperMethods.all { it.isValid() } &&
                        value.replyMossClassName.isNotBlank() &&
                        value.hasRequiredMethods() && value.methods.values.all { it.isValid() }
                } != false &&
                commentSection?.let { value ->
                    value.listConstructors.isNotEmpty() &&
                        value.listConstructors.all { point ->
                            point.className.isNotBlank() && point.paramClassNames.isNotEmpty() &&
                                point.paramClassNames.all { it.isNotBlank() } &&
                                point.listParameterIndex in point.paramClassNames.indices
                        } && value.locatableTagGetter.isValid()
                } != false &&
                splashAds?.listGetters?.let { getters ->
                    getters.isNotEmpty() && getters.all { it.isValid() } &&
                        splashAds.itemSignalGetters.all {
                            it.role in SPLASH_SIGNAL_METHOD_NAMES && it.getter.isValid()
                        }
                } != false &&
                diagnostics.map { it.id }.let { ids -> ids.all { it.isNotBlank() } && ids.distinct().size == ids.size }

        companion object {
            fun fromJson(o: JSONObject): AdaptResult? {
                if (o.optInt("sv", 0) != SCHEMA_VERSION) return null
                val diagnosticsArray = o.optJSONArray("diag") ?: return null
                val diagnostics = (0 until diagnosticsArray.length()).map { index ->
                    AdaptDiagnostic.fromJson(diagnosticsArray.getJSONObject(index)) ?: return null
                }
                return AdaptResult(
                    biliVersionCode = o.getInt("v"),
                    ts = o.optLong("ts", 0L),
                    commentLow = if (o.has("low")) HookPoint.fromJson(o.getJSONObject("low")) else null,
                    commentHigh = if (o.has("high")) HookPoint.fromJson(o.getJSONObject("high")) else null,
                    mineEntry = o.optJSONObject("mine")?.let(MineEntryPoint::fromJson),
                    pause = o.optJSONObject("pause")?.let(PausePoints::fromJson)
                        ?: PausePoints(emptyList(), null, null, null),
                    banner = o.optJSONObject("banner")?.let(BannerPoint::fromJson),
                    homeTopBar = o.optJSONObject("home_top")?.let(HomeTopBarPoints::fromJson),
                    mineVip = o.optJSONObject("mine_vip")?.let(MineVipPoint::fromJson),
                    blockUpdate = o.optJSONObject("block_update")?.let(HookPoint::fromJson),
                    dynamicTabs = o.optJSONObject("dynamic_tabs")?.let(DynamicTabsPoint::fromJson),
                    fullNumbers = o.optJSONObject("full_numbers")?.let(FullNumberPoints::fromJson),
                    playerPortrait = o.optJSONObject("player_portrait")
                        ?.let(PlayerPortraitPoints::fromJson),
                    playerStatusBar = o.optJSONObject("player_status_bar")
                        ?.let(PlayerStatusBarPoints::fromJson),
                    homeRecommendFeed = o.optJSONObject("home_recommend_feed")
                        ?.let(HomeRecommendFeedPoints::fromJson),
                    videoRelate = o.optJSONObject("video_relate")
                        ?.let(VideoRelatePoints::fromJson),
                    homeTabs = o.optJSONObject("home_tabs")?.let(HomeTabPoints::fromJson),
                    homeComponents = o.optJSONObject("home_components")
                        ?.let(HomeComponentPoints::fromJson),
                    mineComponents = o.optJSONObject("mine_components")
                        ?.let(MineComponentPoints::fromJson),
                    mineAccountMine = o.optJSONObject("mine_account_mine")
                        ?.let(MineAccountMinePoints::fromJson),
                    storyFeed = o.optJSONObject("story_feed")?.let(StoryFeedPoints::fromJson),
                    bottomBar = o.optJSONObject("bottom_bar")?.let(BottomBarPoints::fromJson),
                    playerQuality = o.optJSONObject("player_quality")
                        ?.let(PlayerQualityPoints::fromJson),
                    teenagersMode = o.optJSONObject("teenagers_mode")
                        ?.let(TeenagersModePoints::fromJson),
                    commentPurify = o.optJSONObject("comment_purify")
                        ?.let(CommentPurifyPoints::fromJson),
                    commentFilter = o.optJSONObject("comment_filter")
                        ?.let(CommentFilterPoints::fromJson),
                    commentTopology = o.optJSONObject("comment_topology")
                        ?.let(CommentTopologyPoints::fromJson),
                    commentSection = o.optJSONObject("comment_section")
                        ?.let(CommentSectionPoints::fromJson),
                    splashAds = o.optJSONObject("splash_ads")?.let(SplashAdPoints::fromJson),
                    hostFingerprint = o.optString("fp"),
                    protocolFingerprint = o.optString("protocol_fp"),
                    diagnostics = diagnostics
                ).takeIf { it.isStructurallyValid() }
            }
        }
    }

    /** 内置候选（已适配版本的已知 hook 点；新版本靠特征匹配自动修正） */
    private val COMMENT_LOW_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.holder.t0",
        // 8.63.0 及同类早期版本：评论 holder 是 h0（含 CommentItem 字段的 ViewHolder）；
        // 绑定方法为内部 CommentContentRichTextHandler 的 G(...)，走高路径更精确，
        // 此低路径仅作为「类存在即成功」的兜底候选（仍以特征方法名自动定位）
        "com.bilibili.app.comment3.ui.holder.h0"
    )

    private val COMMENT_HIGH_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.nextholderexp3.handle.CommentNextExperiment3ContentRichTextHandler",
        // 8.63.0 及同类早期版本：非混淆 handler（绑定方法 G(CommentItem, jv.u, v0, r, int)，
        // 字段 h 存 CommentItem——特征定位自动覆盖，候选仅提供类名入口）
        "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler"
    )

    private const val HOME_MENU_ITEM_CLASS =
        "com.bilibili.lib.homepage.startdust.menu.a"
    private val HOME_BASE_FRAGMENT_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment",
        "tv.danmaku.p9138bili.p9228ui.main2.basic.BaseMainFrameFragment"
    )
    private val HOME_MAIN_FRAGMENT_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.MainFragment",
        "tv.danmaku.p9138bili.p9228ui.main2.MainFragment"
    )
    private val HOME_DEFAULT_WORD_CLASS_CANDIDATES = setOf(
        // 8.90.2
        "com.bilibili.app.comm.list.common.api.d",
        // 9.1.0–9.9.0
        "com.bilibili.app.comm.list.common.api.b"
    )
    private val HOME_TOP_BAR_CANDIDATES = listOf(HOME_MENU_ITEM_CLASS) +
        HOME_BASE_FRAGMENT_CANDIDATES + HOME_MAIN_FRAGMENT_CANDIDATES
    private const val MINE_FRAGMENT_CLASS =
        "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
    private const val MINE_VIP_MANAGER_CLASS =
        "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager"
    private const val BILI_UPGRADE_INFO_CLASS =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"
    private const val DYNAMIC_MEDIATOR_FRAGMENT_CLASS =
        "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"
    private const val DYNAMIC_MEDIATOR_TAB_CLASS =
        "com.bilibili.bplus.followinglist.home.mediator.MediatorTabLayout"
    private val BLOCK_UPDATE_OWNER_CANDIDATES = listOf(
        // 9.9.0 → 9.1.0/9.1.1；再到 8.99.0 → 8.84.0。每个 owner 仍须通过
        // 精确 (Context) -> BiliUpgradeInfo 签名和叶子实现筛选，类名存在本身不算命中。
        "Ro1.c", "Sn1.c", "Wm1.c", "wm1.c", "dl1.c",
        "Uj1.c", "Ch1.c", "kh1.c", "ih1.c",
        "Xg1.c", "Lg1.c", "Kg1.c", "od1.c", "Sb1.c",
        "xb1.c", "Pa1.c", "si6.c", "oe6.c", "vd6.c",
        "id6.c", "wc6.c", "xc6.c", "aa6.c", "o56.c"
    )
    private const val KNTR_NUMBER_FORMAT_CLASS =
        "kntr.base.localization.NumberFormat_androidKt"
    private val FULL_NUMBER_CLASS_CANDIDATES = listOf(
        "com.bilibili.base.util.NumberFormat",
        "com.bilibili.p4566base.p4568util.NumberFormat",
        "com.bilibili.n9.util.NumberFormat",
        "com.bilibili.lib.utils.NumberFormat",
        KNTR_NUMBER_FORMAT_CLASS
    )
    private val PLAYER_PORTRAIT_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget"
    )
    private val PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES = listOf(
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    )
    private val PEGASUS_RESPONSE_CLASS_CANDIDATES = listOf(
        "com.bilibili.pegasus.data.base.PegasusResponse",
        "com.bilibili.pegasus.p5730data.p5731base.PegasusResponse",
        "com.bilibili.pegasus.p5730data.request.PegasusResponseWrapper"
    )
    private const val PEGASUS_HOLDER_DATA_CLASS = "com.bilibili.pegasus.PegasusHolderData"
    private val BASE_PEGASUS_DATA_CLASS_CANDIDATES = listOf(
        "com.bilibili.pegasus.data.base.BasePegasusData",
        "com.bilibili.pegasus.p5730data.p5731base.BasePegasusData"
    )
    private val VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.viewunite.v1.Relates",
        "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReply",
        "com.bapis.bilibili.app.view.v1.RelatesFeedReply",
        "com.bapis.bilibili.app.view.v1.ViewReply",
        "com.bapis.bilibili.app.view.v1.PlayerRelatesReply"
    )
    private val VIDEO_RELATE_ITEM_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.viewunite.common.RelateCard",
        "com.bapis.bilibili.app.view.v1.Relate"
    )
    private val HOME_FRAGMENT_V2_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.HomeFragmentV2",
        "tv.danmaku.p9138bili.p9228ui.main2.HomeFragmentV2"
    )
    private val HOME_TAB_RESOURCE_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.resource.z",
        "tv.danmaku.p9138bili.p9228ui.main2.resource.z"
    )
    private const val HOST_FRAGMENT_CLASS = "androidx.fragment.app.Fragment"
    private val MINE_MENU_GROUP_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.mine.MenuGroupV2",
        "com.bilibili.lib.homepage.mine.MenuGroup"
    )
    private val MINE_MENU_ITEM_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item",
        "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
    )
    private const val STORY_FEED_RESPONSE_CLASS =
        "com.bilibili.video.story.api.StoryFeedResponse"
    private const val STORY_DETAIL_CLASS = "com.bilibili.video.story.StoryDetail"
    private const val STORY_PAGER_PLAYER_CLASS =
        "com.bilibili.video.story.player.StoryPagerPlayer"
    private val THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES = listOf(
        "com.bilibili.ship.theseus.united.page.tab.TheseusTabPagerService",
        "com.bilibili.p5797ship.theseus.united.p5850page.p5861tab.TheseusTabPagerService"
    )
    private val SPLASH_RESPONSE_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.splash.ad.model.SplashListResponse",
        "tv.danmaku.bili.splash.ad.model.SplashShowResponse",
        "tv.danmaku.bili.ui.splash.SplashData",
        "tv.danmaku.bili.ui.splash.ad.model.SplashData",
        "tv.danmaku.bili.ui.splash.ShowSplashData",
        "tv.danmaku.bili.ui.splash.ad.model.SplashShowData"
    )
    private val SPLASH_LIST_GETTER_NAMES = setOf(
        "getSplashList",
        "getStrategyList",
        "getShowList",
        "getAdList"
    )
    private val SPLASH_SIGNAL_METHOD_NAMES = mapOf(
        "is_ad" to setOf("getIsAd", "isAd"),
        "is_ad_loc" to setOf("getIsAdLoc", "isAdLoc"),
        "cm_mark" to setOf("getCmMark"),
        "ad_cb" to setOf("getAdCb"),
        "uri" to setOf("getUri"),
        "card_type" to setOf("getCardType"),
        "server_type" to setOf("getServerType")
    )
    private val SPLASH_ITEM_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.splash.v1.SplashItem",
        "tv.danmaku.bili.splash.ad.model.SplashItem",
        "tv.danmaku.bili.ui.splash.ad.model.SplashItem"
    )
    private val BOTTOM_TAB_HOST_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.widget.TabHost",
        "com.bilibili.p5690lib.p5708homepage.widget.TabHost",
        "tv.danmaku.bili.ui.main2.widget.TabHost",
        "tv.danmaku.p9138bili.p9228ui.main2.widget.TabHost"
    )
    private val PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES = listOf(
        // 新版 dex 可能保留旧混淆类，因此按新→旧探测；每个 owner 内仍要求唯一的
        // 无参 Int 入口。8.84.0–8.99.0 与 9.1.0–9.9.0 均由
        // "quality settings:" / 画质偏好键的离线方法体语义交叉核验。
        "Jq1.l", "Kp1.l", "Oo1.i", "oo1.g", "Vm1.i",
        "Kl1.j", "tj1.g", "bj1.i", "Zi1.h",
        "Oi1.f", "Ci1.f", "Bi1.f", "Ze1.h", "Dd1.h",
        "hd1.h", "zc1.h", "dm6.h", "zh6.h", "gh6.h",
        "tg6.h", "hg6.h",
        // 8.84.0–8.87.0 的稳定公开入口；放在最后，避免新版残留包装器抢先命中。
        "com.bilibili.playerbizcommon.utils.PlayerSettingHelper"
    )
    private val PLAYER_QUALITY_CAPABILITY_SIGNALS = setOf(
        "stream_quality",
        "vip_entitlement",
        "codec"
    )
    private val PLAYER_SHARED_CLASS_CANDIDATES = mapOf(
        "stream_info" to listOf(
            "com.bapis.bilibili.playershared.StreamInfo",
            "com.bapis.bilibili.p4308playershared.StreamInfo"
        ),
        "dash_video" to listOf(
            "com.bapis.bilibili.playershared.DashVideo",
            "com.bapis.bilibili.p4308playershared.DashVideo"
        ),
        "video_vod" to listOf(
            "com.bapis.bilibili.playershared.VideoVod",
            "com.bapis.bilibili.p4308playershared.VideoVod"
        ),
        "vod_info" to listOf(
            "com.bapis.bilibili.playershared.VodInfo",
            "com.bapis.bilibili.p4308playershared.VodInfo"
        )
    )
    private val TEENAGERS_MODE_ACTIVITY_CANDIDATES = listOf(
        "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
        "com.bilibili.app.preferences.TeenagersModeDialogActivity",
        "com.bilibili.p4439app.preferences.TeenagersModeDialogActivity",
        "tv.danmaku.bili.ui.teenagersmode.TeenagersModeDialogActivity"
    )
    private val COMMENT_CONTENT_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.Content",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.Content"
    )
    private val COMMENT_EMPTY_PAGE_OWNER_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
        "com.bapis.bilibili.main.community.reply.v2.SubjectDescriptionReply",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.SubjectControl",
        "com.bapis.bilibili.p4311main.community.reply.p4313v2.SubjectDescriptionReply"
    )
    private val COMMENT_VOTE_WIDGET_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comment.ext.widgets.CmtVoteWidget",
        "com.bilibili.p4439app.comment.p4511ext.widgets.CmtVoteWidget",
        "com.bilibili.app.comment.ext.widgets.CmtMountWidget",
        "com.bilibili.p4439app.comment.p4511ext.widgets.CmtMountWidget",
        "com.bilibili.app.comment3.ui.widget.CommentVoteView",
        "com.bilibili.p4439app.comment3.p4518ui.widget.CommentVoteView"
    )
    private val COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget",
        "com.bilibili.p4439app.p4450comm.comment2.phoenix.p4467view.CommentFollowWidget"
    )
    private val COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView",
        "com.bilibili.p4439app.comment3.p4518ui.widget.CommentHeaderDecorativeView"
    )
    private val COMMENT_FOLLOW_BUTTON_CLASS_CANDIDATES = listOf(
        "com.bilibili.relation.widget.FollowButton"
    )
    private val COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.MainListReply"
    )
    private val COMMENT_REPLY_INFO_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.ReplyInfo"
    )
    private val PROTOCOL_FINGERPRINT_PATTERN =
        Regex("protocol-v1:[0-9]+:[0-9a-f]{24}")
    private const val COMMENT_ITEM_CLASS = "com.bilibili.app.comment3.data.model.CommentItem"
    private val COMMENT_REPLY_MAPPER_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.comment3.data.source.v1.c",
        "com.bilibili.app.comment3.data.source.v1.d",
        "com.bilibili.app.comment3.data.source.v1.e",
        "com.bilibili.p4439app.comment3.data.source.v1.c",
        "com.bilibili.p4439app.comment3.data.source.v1.d",
        "com.bilibili.p4439app.comment3.data.source.v1.e"
    )
    private val FEED_PAGINATION_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.pagination.FeedPagination",
        "com.bapis.bilibili.p4309pagination.FeedPagination"
    )
    private val COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES = (4..40).flatMap { index ->
        listOf(
            "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$$index",
            "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$$index\$2",
            "com.bilibili.p4439app.comment3.p4518ui.CommentContainerImpl\$attachRepository\$$index",
            "com.bilibili.p4439app.comment3.p4518ui.CommentContainerImpl\$attachRepository\$$index\$2"
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** 当前 B 站 versionCode（读自身包信息，隔离环境对自身可见） */
    fun biliVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo("tv.danmaku.bili", 0).versionCode
    }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun buildHostFingerprint(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo("tv.danmaku.bili", 0)
        val versionCode = info.versionCodeCompat
        val source = File(info.applicationInfo?.sourceDir.orEmpty())
        listOf(
            "tv.danmaku.bili",
            versionCode.toString(),
            info.versionName.orEmpty(),
            source.length().toString(),
            source.lastModified().toString(),
            ADAPTER_RULE_VERSION.toString()
        ).joinToString("|")
    }.getOrElse {
        "tv.danmaku.bili|${biliVersionCode(context)}|rules=$ADAPTER_RULE_VERSION"
    }

    fun cacheStatus(): String = lastCacheStatus

    /**
     * 读缓存适配结果（二级文件缓存优先；手动重置标记/版本不符返回 null）。
     * [resetTimestamp] 已由 API 82 或 NPatch 启动配置完成跨进程校验，适配器不再自行读取
     * Yuki prefs，以免不可读文件把明确的重置请求静默变成 0。
     */
    fun loadCached(context: Context?, resetTimestamp: Long): AdaptResult? {
        val resetTs = resetTimestamp.coerceAtLeast(0L)
        val expectedFingerprint = context?.let(::buildHostFingerprint)

        fun accepted(result: AdaptResult, source: String): AdaptResult? {
            if (result.ts < resetTs) {
                lastCacheStatus = "$source-stale-reset"
                return null
            }
            if (expectedFingerprint != null && !result.isUsableWith(expectedFingerprint)) {
                lastCacheStatus = "$source-fingerprint-mismatch"
                return null
            }
            val versionCode = context?.let(::biliVersionCode) ?: 0
            if (versionCode != 0 && result.biliVersionCode != versionCode) {
                lastCacheStatus = "$source-version-mismatch"
                return null
            }
            lastCacheStatus = "$source-hit"
            return result
        }

        // 文件缓存（loadApp 阶段可读）
        val fileResult = runCatching {
            val f = cacheFile()
            if (!f.exists()) return@runCatching null
            AdaptResult.fromJson(JSONObject(f.readText()))
                ?: run {
                    lastCacheStatus = "file-invalid"
                    null
                }
        }.onFailure {
            lastCacheStatus = "file-read-failed:${it.javaClass.simpleName}"
        }.getOrNull()
        fileResult?.let { accepted(it, "file") }?.let { return it }

        // prefs 缓存（兜底；同样检查手动重置标记）
        if (context != null) {
            runCatching {
                val p = prefs(context)
                val v = p.getInt(KEY_ADAPTED_VERSION, 0)
                val json = p.getString(KEY_ADAPT_RESULT, null) ?: return@runCatching null
                val r = AdaptResult.fromJson(JSONObject(json)) ?: return@runCatching null
                if (r.biliVersionCode == v) r else null
            }.onFailure {
                lastCacheStatus = "prefs-read-failed:${it.javaClass.simpleName}"
            }.getOrNull()?.let { accepted(it, "prefs") }?.let { return it }
        }
        if (!lastCacheStatus.contains("invalid") && !lastCacheStatus.contains("mismatch") &&
            !lastCacheStatus.contains("failed") && !lastCacheStatus.contains("stale")) {
            lastCacheStatus = "miss"
        }
        return null
    }

    /** 缓存是否覆盖当前版本 */
    fun isCached(context: Context, resetTimestamp: Long): Boolean {
        val cached = loadCached(context, resetTimestamp) ?: return false
        return cached.biliVersionCode == biliVersionCode(context)
    }

    /**
     * 手动重适配：写重置标记 + 清除记录（B 站下次启动即重新定位）。
     * reset_ts 写入模块私有权威设置后，由 RemoteHookConfigStore 发布到 API 102 配置组。
     */
    fun clearCache(context: Context, modulePrefs: SharedPreferences?) {
        runCatching {
            modulePrefs?.edit()?.putLong(KEY_RESET_TS, System.currentTimeMillis())?.apply()
        }
        runCatching {
            prefs(context).edit()
                .remove(KEY_ADAPTED_VERSION)
                .remove(KEY_ADAPT_RESULT)
                .apply()
        }
        runCatching { cacheFile().delete() }
    }

    /**
     * 判断并执行适配。在 B 站启动（loadApp 完成注册后）调用：
     * - 缓存命中当前版本 → 直接返回缓存（快路径，零开销）
     * - 需要适配 → 主线程 toast + 后台线程定位 + 写缓存
     */
    fun ensureAdapted(
        context: Context,
        classLoader: ClassLoader,
        resetTimestamp: Long,
        callback: AdaptCallback?
    ) {
        val vc = biliVersionCode(context)
        val expectedFingerprint = buildHostFingerprint(context)
        val cached = loadCached(context, resetTimestamp)
        // 快路径有效性：版本匹配 且（high 已定位 或 当前版本无 high 候选类）。
        // 防止旧缓存（sv 同但 high 缺失——如 8.63.0 早期 low-only 结果）被快路径
        // 复用而跳过重定位（曾有 01:04 prefs 旧结果导致 9.8.0 一直 low-only 的回归）。
        val highCandidateExists = COMMENT_HIGH_CANDIDATES.any {
            KavaMemberLookup.hasClass(classLoader, it)
        }
        if (cached != null && cached.biliVersionCode == vc &&
            cached.isUsableWith(expectedFingerprint) &&
            (cached.commentHigh != null || !highCandidateExists)) {
            // 快路径命中：确保文件缓存存在（loadApp 阶段无 context 只读文件缓存；
            // prefs 命中但文件缺失时补写，避免下次启动 loadApp 回退内置候选）
            runCatching {
                if (!cacheFile().exists()) cacheFile().writeText(cached.toJson().toString())
            }
            return // 快路径：已适配
        }
        ModernHookLog.info(
            "[BIL] 版本适配启动 vc=$vc cached=${cached != null} " +
                "cacheStatus=$lastCacheStatus"
        )
        // 后台执行（不阻塞启动；toast 提示用户等待）
        callback?.onAdaptStarted()
        Thread({
            val result = runCatching { adapt(context, classLoader) }.getOrNull()
            if (result != null) {
                // 写文件缓存（loadApp 快路径载体）
                runCatching {
                    cacheFile().writeText(result.toJson().toString())
                }
                // 写 prefs 缓存（模块 App 侧可读，供 UI 展示适配状态）
                runCatching {
                    prefs(context).edit()
                        .putInt(KEY_ADAPTED_VERSION, result.biliVersionCode)
                        .putString(KEY_ADAPT_RESULT, result.toJson().toString())
                        .apply()
                }
            }
            callback?.onAdaptFinished(result != null)
            ModernHookLog.info(
                "[BIL] 版本适配${if (result != null) "完成" else "失败"} " +
                    "v=${result?.biliVersionCode} low=${result?.commentLow} high=${result?.commentHigh} " +
                    "mine=${result?.mineEntry != null} pause=${result?.pause?.requestMethods?.size ?: 0} " +
                    "banner=${result?.banner != null} homeTop=${result?.homeTopBar != null} " +
                    "mineVip=${result?.mineVip != null} " +
                    "blockUpdate=${result?.blockUpdate != null} " +
                    "dynamicTabs=${result?.dynamicTabs != null} " +
                    "playerPortrait=${result?.playerPortrait != null} " +
                    "playerStatusBar=${result?.playerStatusBar != null} " +
                    "homeRecommendFeed=${result?.homeRecommendFeed != null} " +
                    "videoRelate=${result?.videoRelate != null} " +
                    "homeTabs=${result?.homeTabs != null} " +
                    "homeComponents=${result?.homeComponents != null} " +
                    "mineComponents=${result?.mineComponents != null} " +
                    "storyFeed=${result?.storyFeed != null} " +
                    "bottomBar=${result?.bottomBar != null} " +
                    "playerQuality=${result?.playerQuality != null} " +
                    "teenagersMode=${result?.teenagersMode != null} " +
                    "commentPurify=${result?.commentPurify != null} " +
                    "commentFilter=${result?.commentFilter != null} " +
                    "commentTopology=${result?.commentTopology != null} " +
                    "commentSection=${result?.commentSection != null} " +
                    "splashAds=${result?.splashAds != null} " +
                    "protocol=${result?.protocolFingerprint} " +
                    "diag=${result?.diagnosticSummary()}"
            )
        }, "BIL-VersionAdapter").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 即时快速定位（loadApp 阶段用，不依赖缓存/attach 适配时序）：
     * 纯内存反射 ~1ms，返回 low/high 定位结果（无版本/时间戳）。
     */
    fun quickLocate(loader: ClassLoader): AdaptResult? {
        val low = locateCommentLow(loader)
        val high = locateCommentHigh(loader)
        val mine = locateMineEntry(loader)
        val pause = locatePausePoints(loader)
        val banner = locateBanner(loader)
        val homeTopBar = locateHomeTopBar(loader)
        val mineVip = locateMineVip(loader)
        val blockUpdate = locateBlockUpdate(loader)
        val dynamicTabs = locateDynamicTabs(loader)
        val fullNumbers = locateFullNumbers(loader)
        val playerPortrait = locatePlayerPortrait(loader)
        val playerStatusBar = locatePlayerStatusBar(loader)
        val homeRecommendFeed = locateHomeRecommendFeed(loader)
        val videoRelate = locateVideoRelate(loader)
        val homeTabs = locateHomeTabs(loader)
        val homeComponents = locateHomeComponents(loader)
        val mineComponents = locateMineComponents(loader, mine)
        val mineAccountMine = locateMineAccountMinePoints(loader)
        val storyFeed = locateStoryFeed(loader)
        val bottomBar = locateBottomBar(loader)
        val playerQuality = locateDefaultVideoQuality(loader)
        val teenagersMode = locateTeenagersMode(loader)
        val commentPurify = locateCommentPurify(loader)
        val commentFilter = locateCommentFilter(loader)
        val commentTopologyOutcome = locateCommentTopologyWithDiagnostic(loader)
        val commentTopology = commentTopologyOutcome.points
        val commentSection = locateCommentSection(loader)
        val splashAds = locateSplashAds(loader)
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            playerStatusBar == null && homeRecommendFeed == null && videoRelate == null &&
            homeTabs == null && homeComponents == null && mineComponents == null &&
            mineAccountMine == null && storyFeed == null && bottomBar == null &&
            playerQuality == null && teenagersMode == null && commentPurify == null &&
            commentFilter == null && commentTopology == null && commentSection == null &&
            splashAds == null) return null
        val protocolFingerprint = buildProtocolStructureFingerprint(
            loader, homeRecommendFeed, videoRelate, commentFilter, playerQuality,
            splashAds, mineAccountMine
        )
        return AdaptResult(
            biliVersionCode = 0,
            ts = 0L,
            commentLow = low,
            commentHigh = high,
            mineEntry = mine,
            pause = pause,
            banner = banner,
            homeTopBar = homeTopBar,
            mineVip = mineVip,
            blockUpdate = blockUpdate,
            dynamicTabs = dynamicTabs,
            fullNumbers = fullNumbers,
            playerPortrait = playerPortrait,
            playerStatusBar = playerStatusBar,
            homeRecommendFeed = homeRecommendFeed,
            videoRelate = videoRelate,
            homeTabs = homeTabs,
            homeComponents = homeComponents,
            mineComponents = mineComponents,
            mineAccountMine = mineAccountMine,
            storyFeed = storyFeed,
            bottomBar = bottomBar,
            playerQuality = playerQuality,
            teenagersMode = teenagersMode,
            commentPurify = commentPurify,
            commentFilter = commentFilter,
            commentTopology = commentTopology,
            commentSection = commentSection,
            splashAds = splashAds,
            hostFingerprint = "runtime-no-context|rules=$ADAPTER_RULE_VERSION",
            protocolFingerprint = protocolFingerprint.value,
            diagnostics = buildDiagnostics(
                loader, low, high, mine, pause, banner, homeTopBar, mineVip, blockUpdate,
                dynamicTabs, fullNumbers, playerPortrait, playerStatusBar, homeRecommendFeed,
                videoRelate, homeTabs, homeComponents, mineComponents, storyFeed, bottomBar,
                playerQuality,
                teenagersMode, commentPurify, commentFilter, commentTopology,
                commentTopologyOutcome.failureDetail, commentSection,
                splashAds
            ) + protocolFingerprint.toDiagnostic()
        )
    }

    /** 智能定位核心：对内置候选类做存在性验证 + 方法签名特征匹配。
     * 全部在内存中完成（KavaRef ClassLoader/成员解析），无任何反编译/文件解压开销。
     * 成功标准放宽：任一候选类存在即算成功——运行期注册有 classExists 双路径回退，
     * 适配结果主要用于快路径签名（定位不到签名不影响运行期内置候选注册），
     * 避免「功能可用但报适配失败」的误导（8.90.2 实测）。
     */
    private fun adapt(context: Context, loader: ClassLoader): AdaptResult? {
        val vc = biliVersionCode(context)
        val low = locateCommentLow(loader)
        val high = locateCommentHigh(loader)
        val mine = locateMineEntry(loader)
        val pause = locatePausePoints(loader)
        val banner = locateBanner(loader)
        val homeTopBar = locateHomeTopBar(loader)
        val mineVip = locateMineVip(loader)
        val blockUpdate = locateBlockUpdate(loader)
        val dynamicTabs = locateDynamicTabs(loader)
        val fullNumbers = locateFullNumbers(loader)
        val playerPortrait = locatePlayerPortrait(loader)
        val playerStatusBar = locatePlayerStatusBar(loader)
        val homeRecommendFeed = locateHomeRecommendFeed(loader)
        val videoRelate = locateVideoRelate(loader)
        val homeTabs = locateHomeTabs(loader)
        val homeComponents = locateHomeComponents(loader)
        val mineComponents = locateMineComponents(loader, mine)
        val mineAccountMine = locateMineAccountMinePoints(loader)
        val storyFeed = locateStoryFeed(loader)
        val bottomBar = locateBottomBar(loader)
        val playerQuality = locateDefaultVideoQuality(loader)
        val teenagersMode = locateTeenagersMode(loader)
        val commentPurify = locateCommentPurify(loader)
        val commentFilter = locateCommentFilter(loader)
        val commentTopologyOutcome = locateCommentTopologyWithDiagnostic(loader)
        val commentTopology = commentTopologyOutcome.points
        val commentSection = locateCommentSection(loader)
        val splashAds = locateSplashAds(loader)
        val anyClassExists = COMMENT_LOW_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_HIGH_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || HOME_TOP_BAR_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || KavaMemberLookup.hasClass(loader, MINE_FRAGMENT_CLASS)
            || KavaMemberLookup.hasClass(loader, DYNAMIC_MEDIATOR_FRAGMENT_CLASS)
            || FULL_NUMBER_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || PLAYER_PORTRAIT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || PEGASUS_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || HOME_FRAGMENT_V2_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || MINE_MENU_GROUP_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || KavaMemberLookup.hasClass(loader, STORY_DETAIL_CLASS)
            || BOTTOM_TAB_HOST_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || TEENAGERS_MODE_ACTIVITY_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_CONTENT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || COMMENT_REPLY_INFO_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
            || SPLASH_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || BLOCK_UPDATE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            playerStatusBar == null && homeRecommendFeed == null && videoRelate == null &&
            homeTabs == null && homeComponents == null && mineComponents == null &&
            mineAccountMine == null && storyFeed == null && bottomBar == null &&
            playerQuality == null && teenagersMode == null && commentPurify == null &&
            commentFilter == null && commentTopology == null && commentSection == null &&
            splashAds == null &&
            !anyClassExists) return null
        val protocolFingerprint = buildProtocolStructureFingerprint(
            loader, homeRecommendFeed, videoRelate, commentFilter, playerQuality,
            splashAds, mineAccountMine
        )
        return AdaptResult(
            biliVersionCode = vc,
            ts = System.currentTimeMillis(),
            commentLow = low,
            commentHigh = high,
            mineEntry = mine,
            pause = pause,
            banner = banner,
            homeTopBar = homeTopBar,
            mineVip = mineVip,
            blockUpdate = blockUpdate,
            dynamicTabs = dynamicTabs,
            fullNumbers = fullNumbers,
            playerPortrait = playerPortrait,
            playerStatusBar = playerStatusBar,
            homeRecommendFeed = homeRecommendFeed,
            videoRelate = videoRelate,
            homeTabs = homeTabs,
            homeComponents = homeComponents,
            mineComponents = mineComponents,
            mineAccountMine = mineAccountMine,
            storyFeed = storyFeed,
            bottomBar = bottomBar,
            playerQuality = playerQuality,
            teenagersMode = teenagersMode,
            commentPurify = commentPurify,
            commentFilter = commentFilter,
            commentTopology = commentTopology,
            commentSection = commentSection,
            splashAds = splashAds,
            hostFingerprint = buildHostFingerprint(context),
            protocolFingerprint = protocolFingerprint.value,
            diagnostics = buildDiagnostics(
                loader, low, high, mine, pause, banner, homeTopBar, mineVip, blockUpdate,
                dynamicTabs, fullNumbers, playerPortrait, playerStatusBar, homeRecommendFeed,
                videoRelate, homeTabs, homeComponents, mineComponents, storyFeed, bottomBar,
                playerQuality,
                teenagersMode, commentPurify, commentFilter, commentTopology,
                commentTopologyOutcome.failureDetail, commentSection,
                splashAds
            ) + protocolFingerprint.toDiagnostic()
        )
    }

    private data class ProtocolFingerprint(
        val value: String,
        val evidenceCount: Int
    ) {
        fun toDiagnostic(): AdaptDiagnostic = AdaptDiagnostic(
            id = "protocol.structure",
            state = if (evidenceCount > 0) AdaptState.FOUND else AdaptState.MISSING,
            detail = value
        )
    }

    /**
     * 只在适配阶段计算公开协议结构摘要。成员解析失败时跳过该项，现有 Hook 点仍可按原路径
     * 使用；摘要本身不参与业务热路径，也不包含宿主内容或用户数据。
     */
    private fun buildProtocolStructureFingerprint(
        loader: ClassLoader,
        home: HomeRecommendFeedPoints?,
        relate: VideoRelatePoints?,
        comment: CommentFilterPoints?,
        quality: PlayerQualityPoints?,
        splash: SplashAdPoints?,
        mine: MineAccountMinePoints?
    ): ProtocolFingerprint {
        val descriptors = linkedSetOf<String>()

        fun resolve(point: HookPoint): Method? {
            val owner = KavaMemberLookup.classOrNull(loader, point.className) ?: return null
            return KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == point.methodName && point.paramClassNames?.let { expected ->
                    method.parameterTypes.map(Class<*>::getName) == expected
                } != false
            }.distinctBy(Method::toGenericString).singleOrNull()
        }

        fun add(label: String, point: HookPoint?) {
            point ?: return
            val method = resolve(point) ?: return
            val fieldNumber = method.protobufFieldNumberOrNull()?.let { "@field=$it" }.orEmpty()
            descriptors += "$label:${method.declaringClass.name}#${method.name}(" +
                method.parameterTypes.joinToString(",") { it.name } + ")" +
                "->${method.returnType.name}$fieldNumber"
        }

        home?.let { points ->
            points.responseItemGetters.forEachIndexed { index, point -> add("home.response.$index", point) }
            add("home.holder", points.holderTypeGetter)
            add("home.biz", points.bizTypeGetter)
            add("home.ad_info", points.adInfoGetter)
            add("home.card_type", points.cardTypeGetter)
            add("home.card_goto", points.cardGotoGetter)
            add("home.goto", points.goToGetter)
            add("home.uri", points.uriGetter)
            add("home.param", points.paramGetter)
            add("home.player_args", points.playerArgsGetter)
            add("home.duration", points.playerArgsDurationGetter)
            if (!points.playerArgsDurationField.isNullOrBlank()) {
                descriptors += "home.duration_field:${points.playerArgsDurationField}"
            }
        }
        relate?.let { points ->
            points.responseItemGetters.forEachIndexed { index, point -> add("relate.response.$index", point) }
            points.cardCaseGetters.forEachIndexed { index, point -> add("relate.case.$index", point) }
            points.gotoGetters.forEachIndexed { index, point -> add("relate.goto.$index", point) }
            points.cardTypeGetters.forEachIndexed { index, point -> add("relate.type.$index", point) }
            points.relateCardTypeGetters.forEachIndexed { index, point ->
                add("relate.relate_type.$index", point)
            }
            points.fromSourceTypeGetters.forEachIndexed { index, point ->
                add("relate.source_type.$index", point)
            }
            points.fromSourceTypeChains.forEachIndexed { index, chain ->
                add("relate.source_type_chain.$index.container", chain.itemGetter)
                add("relate.source_type_chain.$index.value", chain.sourceTypeGetter)
            }
            points.relateCardTypeValueGetters.forEachIndexed { index, point ->
                add("relate.relate_type_value.$index", point)
            }
            points.directDurationGetters.forEachIndexed { index, point ->
                add("relate.duration.$index", point)
            }
            points.durationChains.forEachIndexed { index, chain ->
                add("relate.chain.$index.container", chain.itemGetter)
                add("relate.chain.$index.duration", chain.durationGetter)
            }
        }
        comment?.let { points ->
            points.replyListGetters.forEachIndexed { index, point -> add("comment.list.$index", point) }
            add("comment.content", points.contentGetter)
            add("comment.message", points.messageGetter)
            add("comment.member", points.memberGetter)
            add("comment.level", points.levelGetter)
            add("comment.member_v2", points.memberV2Getter)
            add("comment.member_v2_basic", points.memberV2BasicGetter)
            add("comment.member_v2_level", points.memberV2LevelGetter)
            points.topReplyGetters.forEachIndexed { index, point -> add("comment.top.$index", point) }
        }
        splash?.let { points ->
            points.listGetters.forEachIndexed { index, point -> add("splash.list.$index", point) }
            points.itemSignalGetters.forEachIndexed { index, signal ->
                add("splash.${signal.role}.$index", signal.getter)
            }
        }
        quality?.capabilitySignals.orEmpty().sorted().forEach { capability ->
            descriptors += "player.capability:$capability"
        }
        mine?.let { points ->
            descriptors += "mine.account:${points.accountMineClass}:" +
                "section=${points.sectionListV2Field}:builders=${points.buildMethods.size}"
        }

        val canonical = descriptors.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(24)
        return ProtocolFingerprint(
            value = "protocol-v1:${descriptors.size}:$digest",
            evidenceCount = descriptors.size
        )
    }

    private fun Method.protobufFieldNumberOrNull(): Int? {
        val stem = name.removePrefix("get").removePrefix("is")
        if (stem.isBlank()) return null
        val snake = stem.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
        val candidates = linkedSetOf(
            "${snake}_FIELD_NUMBER",
            "${snake.removeSuffix("_LIST")}_FIELD_NUMBER",
            "${snake.removeSuffix("_MAP")}_FIELD_NUMBER",
            "${snake.removeSuffix("_VALUE")}_FIELD_NUMBER"
        )
        return declaringClass.fields.firstNotNullOfOrNull { field ->
            if (field.name !in candidates || field.type != Int::class.javaPrimitiveType) {
                return@firstNotNullOfOrNull null
            }
            runCatching { field.getInt(null) }.getOrNull()
        }
    }

    private fun HookPoint.label(): String = buildString {
        append(className)
        append('#')
        append(methodName)
        paramClassNames?.let { params ->
            append('(')
            append(params.joinToString(","))
            append(')')
        }
    }

    private fun buildDiagnostics(
        loader: ClassLoader,
        low: HookPoint?,
        high: HookPoint?,
        mine: MineEntryPoint?,
        pause: PausePoints,
        banner: BannerPoint?,
        homeTopBar: HomeTopBarPoints?,
        mineVip: MineVipPoint?,
        blockUpdate: HookPoint?,
        dynamicTabs: DynamicTabsPoint?,
        fullNumbers: FullNumberPoints?,
        playerPortrait: PlayerPortraitPoints?,
        playerStatusBar: PlayerStatusBarPoints?,
        homeRecommendFeed: HomeRecommendFeedPoints?,
        videoRelate: VideoRelatePoints?,
        homeTabs: HomeTabPoints?,
        homeComponents: HomeComponentPoints?,
        mineComponents: MineComponentPoints?,
        storyFeed: StoryFeedPoints?,
        bottomBar: BottomBarPoints?,
        playerQuality: PlayerQualityPoints?,
        teenagersMode: TeenagersModePoints?,
        commentPurify: CommentPurifyPoints?,
        commentFilter: CommentFilterPoints?,
        commentTopology: CommentTopologyPoints?,
        commentTopologyFailureDetail: String,
        commentSection: CommentSectionPoints?,
        splashAds: SplashAdPoints?
    ): List<AdaptDiagnostic> {
        fun stateFor(pointFound: Boolean, candidateExists: Boolean): AdaptState = when {
            pointFound -> AdaptState.FOUND
            candidateExists -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }

        val lowCandidateExists = COMMENT_LOW_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val highCandidateExists = COMMENT_HIGH_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val mineCandidateExists = KavaMemberLookup.hasClass(loader, MINE_FRAGMENT_CLASS)
        val mineVipCandidateExists = mineCandidateExists &&
            KavaMemberLookup.hasClass(loader, MINE_VIP_MANAGER_CLASS)
        val blockUpdateCandidateExists = BLOCK_UPDATE_OWNER_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val dynamicTabsCandidateExists =
            KavaMemberLookup.hasClass(loader, DYNAMIC_MEDIATOR_FRAGMENT_CLASS) &&
                KavaMemberLookup.hasClass(loader, DYNAMIC_MEDIATOR_TAB_CLASS)
        val fullNumbersCandidateExists = FULL_NUMBER_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val playerPortraitCandidateExists = PLAYER_PORTRAIT_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val playerStatusBarCandidateExists = PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val homeRecommendFeedCandidateExists =
            KavaMemberLookup.hasClass(loader, PEGASUS_HOLDER_DATA_CLASS) &&
                PEGASUS_RESPONSE_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
        val videoRelateCandidateExists = VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val homeTabsCandidateExists = HOME_FRAGMENT_V2_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val homeComponentsCandidateExists = KavaMemberLookup.hasClass(loader, HOST_FRAGMENT_CLASS)
        val mineComponentsCandidateExists = MINE_MENU_GROUP_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val storyFeedCandidateExists = KavaMemberLookup.hasClass(loader, STORY_DETAIL_CLASS) &&
            (KavaMemberLookup.hasClass(loader, STORY_FEED_RESPONSE_CLASS) ||
                KavaMemberLookup.hasClass(loader, STORY_PAGER_PLAYER_CLASS))
        val bottomBarCandidateExists = BOTTOM_TAB_HOST_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val playerQualityCandidateExists = PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val teenagersModeCandidateExists = TEENAGERS_MODE_ACTIVITY_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentPurifyCandidateExists = COMMENT_CONTENT_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentEmptyPageCandidateExists = COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentVoteCandidateExists = COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentFollowCandidateExists = COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        } || COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentMainListCandidateExists = COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentQuickReplyCandidateExists = COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentFilterCandidateExists = COMMENT_REPLY_INFO_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val commentTopologyCandidateExists = commentFilterCandidateExists &&
            KavaMemberLookup.hasClass(loader, COMMENT_ITEM_CLASS) &&
            COMMENT_REPLY_MAPPER_CLASS_CANDIDATES.any {
                KavaMemberLookup.hasClass(loader, it)
            }
        val commentSectionCandidateExists = THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val splashCandidateExists = SPLASH_RESPONSE_CLASS_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val pauseCandidateClasses = listOf(
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
            "com.bilibili.ship.theseus.united.page.pausedpage." +
                "PausedPageService\$requestPausedPageData\$2"
        )
        val pauseCandidateExists = pauseCandidateClasses.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val panelCandidateExists = KavaMemberLookup.hasClass(
            loader,
            "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository"
        )
        val countdownCandidateExists = KavaMemberLookup.hasClass(
            loader,
            "com.bilibili.ship.theseus.united.page.pausedpage." +
                "PausedPageService\$showPauseBarCountdownToast\$3"
        )
        val bannerCandidateExists = KavaMemberLookup.hasClass(
            loader,
            "com.bilibili.pegasus.holders.bannerv8.V8Banner"
        )
        val gameMenuCandidateExists = KavaMemberLookup.hasClass(loader, HOME_MENU_ITEM_CLASS)
        val searchCandidateExists = HOME_MAIN_FRAGMENT_CANDIDATES.any {
            KavaMemberLookup.hasClass(loader, it)
        }
        val searchReady = homeTopBar?.baseOnViewCreated != null &&
            homeTopBar.defaultWordMethods.isNotEmpty()
        return listOf(
            AdaptDiagnostic(
                "comment.low",
                stateFor(low != null, lowCandidateExists),
                low?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "comment.high",
                stateFor(high != null, highCandidateExists),
                high?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "mine.entry",
                stateFor(mine != null, mineCandidateExists),
                mine?.let { "build=${it.buildMethods.size},groups=${it.groupListField}" }.orEmpty()
            ),
            AdaptDiagnostic(
                "paused.request",
                stateFor(pause.requestMethods.isNotEmpty(), pauseCandidateExists),
                pause.requestMethods.joinToString("|") { it.label() }
            ),
            AdaptDiagnostic(
                "paused.panel",
                stateFor(pause.panelShow != null, panelCandidateExists),
                pause.panelShow?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "paused.countdown",
                stateFor(pause.countdown != null, countdownCandidateExists),
                pause.countdown?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "home.banner",
                stateFor(banner != null, bannerCandidateExists),
                banner?.let { "${it.bannerClassName},hooks=${it.lifecycleMethods.size}" }.orEmpty()
            ),
            AdaptDiagnostic(
                "home.top_bar.game",
                stateFor(homeTopBar?.gameMenu != null, gameMenuCandidateExists),
                homeTopBar?.gameMenu?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "home.top_bar.search",
                stateFor(searchReady, searchCandidateExists),
                homeTopBar?.let {
                    "view=${it.baseOnViewCreated?.label().orEmpty()},words=${it.defaultWordMethods.size}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "mine.vip",
                stateFor(mineVip != null, mineVipCandidateExists),
                mineVip?.let {
                    "resume=${it.onResume.label()},binding=${it.bindingField}," +
                        "root=${it.rootGetter.label()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "update.block",
                stateFor(blockUpdate != null, blockUpdateCandidateExists),
                blockUpdate?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "dynamic.tabs",
                stateFor(dynamicTabs != null, dynamicTabsCandidateExists),
                dynamicTabs?.let {
                    "list=${it.listGetter.label()},item=${it.itemClassName}#" +
                        "${it.itemTitleField}/${it.itemNameField}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "number.full",
                stateFor(fullNumbers != null, fullNumbersCandidateExists),
                fullNumbers?.formatterMethods?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "player.portrait",
                stateFor(playerPortrait != null, playerPortraitCandidateExists),
                playerPortrait?.visibilityMethods?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "player.status_bar",
                stateFor(playerStatusBar != null, playerStatusBarCandidateExists),
                playerStatusBar?.onCreateMethods?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "home.recommend_feed",
                stateFor(homeRecommendFeed != null, homeRecommendFeedCandidateExists),
                homeRecommendFeed?.let {
                    "contract=app-card-v1,responses=${it.responseItemGetters.size}," +
                        "signals=${listOfNotNull(it.cardTypeGetter, it.cardGotoGetter, it.goToGetter, it.adInfoGetter).size}," +
                        "duration=${it.playerArgsDurationGetter != null || !it.playerArgsDurationField.isNullOrBlank()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "video.relate",
                stateFor(videoRelate != null, videoRelateCandidateExists),
                videoRelate?.let {
                    "contract=viewunite-relate,responses=${it.responseItemGetters.size},types=" +
                        (it.cardCaseGetters.size + it.gotoGetters.size +
                            it.cardTypeGetters.size + it.relateCardTypeGetters.size +
                            it.fromSourceTypeGetters.size + it.fromSourceTypeChains.size +
                            it.relateCardTypeValueGetters.size)
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "home.tabs",
                stateFor(homeTabs != null, homeTabsCandidateExists),
                homeTabs?.let { "${it.buildMethod.label()},resource=${it.resourceClassName}" }
                    .orEmpty()
            ),
            AdaptDiagnostic(
                "home.components",
                stateFor(homeComponents != null, homeComponentsCandidateExists),
                homeComponents?.onViewCreated?.label().orEmpty()
            ),
            AdaptDiagnostic(
                "mine.components",
                stateFor(mineComponents != null, mineComponentsCandidateExists),
                mineComponents?.let {
                    "lists=${it.itemListGetters.size},titles=${it.itemTitleGetters.size}," +
                        "legacy=${it.legacyBuildMethods.size}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "story.feed",
                stateFor(storyFeed != null, storyFeedCandidateExists),
                storyFeed?.let {
                    "responses=${it.responseItemGetters.size},pager=${it.pagerListMethods.size}," +
                        "ad=${it.adGetter != null},live=${it.liveGetter != null}," +
                        "game=${it.gameGetter != null},bangumi=${it.bangumiGetter != null}," +
                        "course=${it.courseGetter != null},music=${it.musicGetter != null}," +
                        "cart=${it.cartInfoGetter != null},drama=${it.dramaPromptGetter != null}," +
                        "season=${it.seasonInfoGetter != null && it.seasonTypeGetter != null}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "home.bottom_bar",
                stateFor(bottomBar != null, bottomBarCandidateExists),
                bottomBar?.let {
                    "tabs=${it.tabsGetter.label()},bind=${it.bindTabMethod.label()}," +
                        "strings=${it.itemStringFields.size}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "player.default_quality",
                stateFor(playerQuality != null, playerQualityCandidateExists),
                playerQuality?.let {
                    "contract=playershared,request_qn=true,capabilities=" +
                        it.capabilitySignals.joinToString("+").ifBlank { "unobserved" }
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "teenagers.mode",
                stateFor(teenagersMode != null, teenagersModeCandidateExists),
                teenagersMode?.onCreateMethods?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.search",
                stateFor(
                    commentPurify?.urlMapGetters?.isNotEmpty() == true,
                    commentPurifyCandidateExists
                ),
                commentPurify?.urlMapGetters?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.empty_page",
                stateFor(
                    commentPurify?.emptyPageGetters?.isNotEmpty() == true,
                    commentEmptyPageCandidateExists
                ),
                commentPurify?.emptyPageGetters?.joinToString("|") {
                    "${it.contentGetter.label()}->${it.defaultInstanceGetter.label()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.vote",
                stateFor(
                    commentPurify?.voteWidgetMethods?.isNotEmpty() == true,
                    commentVoteCandidateExists
                ),
                commentPurify?.voteWidgetMethods?.joinToString("|") { it.label() }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.follow",
                stateFor(commentPurify?.follow != null, commentFollowCandidateExists),
                commentPurify?.follow?.let { follow ->
                    "widget=${follow.widgetStateMethods.joinToString("|") { it.label() }};" +
                        "header=${follow.headerBindMethods.joinToString("|") { it.label() }};" +
                        "button=${follow.followButtonClassName.orEmpty()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.qoe",
                stateFor(commentPurify?.qoe != null, commentMainListCandidateExists),
                commentPurify?.qoe?.let { qoe ->
                    "has=${qoe.presenceGetter.label()},get=${qoe.contentGetter.label()}," +
                        "default=${qoe.defaultInstanceGetter.label()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.purify.operation",
                stateFor(
                    commentPurify?.operations?.size == 2,
                    commentMainListCandidateExists
                ),
                commentPurify?.operations?.joinToString("|") { operation ->
                    "${operation.presenceGetter.methodName}/" +
                        "${operation.contentGetter.methodName}->" +
                        operation.defaultInstanceGetter.className
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.quick_reply",
                stateFor(
                    commentPurify?.quickReplyDialogMethods?.isNotEmpty() == true,
                    commentQuickReplyCandidateExists
                ),
                commentPurify?.quickReplyDialogMethods
                    ?.joinToString("|") { it.label() }
                    .orEmpty()
            ),
            AdaptDiagnostic(
                "comment.filter",
                stateFor(commentFilter != null, commentFilterCandidateExists),
                commentFilter?.let { points ->
                    "contract=reply-v1,lists=${points.replyListGetters.size}," +
                        "top=${points.topReplyGetters.size}," +
                        "level_paths=${listOfNotNull(points.levelGetter, points.memberV2LevelGetter).size}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "comment.topology",
                stateFor(commentTopology != null, commentTopologyCandidateExists),
                commentTopology?.let { points ->
                    "mappers=${points.mapperMethods.size}[" +
                        points.mapperMethods.joinToString("|") { it.label() } +
                        "],methods=${points.methods.size}"
                } ?: commentTopologyFailureDetail
            ),
            AdaptDiagnostic(
                "comment.section",
                stateFor(commentSection != null, commentSectionCandidateExists),
                commentSection?.let { points ->
                    "constructors=${points.listConstructors.size}," +
                        "tag=${points.locatableTagGetter.label()}"
                }.orEmpty()
            ),
            AdaptDiagnostic(
                "splash.ads",
                stateFor(splashAds != null, splashCandidateExists),
                splashAds?.let {
                    "contract=splash-v1,lists=${it.listGetters.size}," +
                        "signals=${it.itemSignalGetters.map(SplashItemSignalPoint::role).distinct().size}"
                }.orEmpty()
            )
        )
    }

    private fun Method.toHookPoint() = HookPoint(
        className = declaringClass.name,
        methodName = name,
        paramClassNames = parameterTypes.map { it.name }
    )

    /**
     * 按宿主类层级名称判断类型，避免模块与宿主各自打包 AndroidX 时 Class 身份不同。
     * 仅用于一次性版本探测，不进入任何 Hook 回调热路径。
     */
    private fun Class<*>.hasSuperclassNamed(expectedName: String): Boolean {
        var current: Class<*>? = this
        while (current != null) {
            if (current.name == expectedName) return true
            current = current.superclass
        }
        return false
    }

    /** 通过类名遍历接口层级，避免宿主 AndroidX 与模块 ClassLoader 身份差异。 */
    private fun Class<*>.implementsInterfaceNamed(expectedName: String): Boolean {
        val pending = java.util.ArrayDeque<Class<*>>()
        val visited = HashSet<Class<*>>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            current.interfaces.forEach { implemented ->
                if (implemented.name == expectedName) return true
                pending.addLast(implemented)
            }
            current.superclass?.let(pending::addLast)
        }
        return false
    }

    /**
     * 结构化定位“我的”页入口。已确认的字段漂移：
     * 8.90.2/9.8.0 由运行时结构匹配；9.1.1=of+n1/m1、9.2.0=pf+m1/l1、
     * 9.3.0=nf+u0/t0。只依赖方法参数、List 和 RecyclerView.Adapter 类型。
     */
    fun locateMineEntry(loader: ClassLoader): MineEntryPoint? = runCatching {
        val fragmentName = MINE_FRAGMENT_CLASS
        val accountMineName = "tv.danmaku.bili.ui.main2.api.AccountMine"
        val itemName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        val fragment = KavaMemberLookup.classOrNull(loader, fragmentName)
            ?: return@runCatching null
        val accountMine = KavaMemberLookup.classOrNull(loader, accountMineName)
            ?: return@runCatching null
        val itemClass = KavaMemberLookup.classOrNull(loader, itemName)
            ?: return@runCatching null

        val builds = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            it.isStatic && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(fragment, accountMine))
        }
        if (builds.isEmpty()) return@runCatching null

        val listFields = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf classOf<List<*>>()
        }
        val groups = listFields.singleOrNull() ?: return@runCatching null

        val adapter = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic &&
                it.type.hasSuperclassNamed("androidx.recyclerview.widget.RecyclerView\$Adapter")
        }.singleOrNull()

        val click = listOf("$fragmentName\$e", "$fragmentName\$i")
            .asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { clickClass ->
                KavaMemberLookup.declaredMethods(clickClass, makeAccessible = true) {
                    !it.isStatic && it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(itemClass))
                }.singleOrNull()
            }
            .firstOrNull() ?: return@runCatching null

        MineEntryPoint(builds.map { it.toHookPoint() }, groups.name, adapter?.name, click.toHookPoint())
    }.getOrNull()

    /**
     * 结构化定位“我的”页 VIP 卡片。8.90.2、9.1.0 与 9.9.0 的混淆字段名不同，
     * 但 Fragment 持有唯一 MineVipModuleManager、管理器持有唯一 ViewBinding，且
     * ViewBinding 暴露无参 getRoot。只在适配期扫描并缓存精确成员描述。
     */
    fun locateMineVip(loader: ClassLoader): MineVipPoint? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, MINE_FRAGMENT_CLASS)
            ?: return@runCatching null
        val manager = KavaMemberLookup.classOrNull(loader, MINE_VIP_MANAGER_CLASS)
            ?: return@runCatching null
        val onResume = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            !it.isStatic && it.name == "onResume" && it.parameterCount == 0 &&
                it.returnType == Void.TYPE
        }.singleOrNull() ?: return@runCatching null
        val managerField = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf manager
        }.singleOrNull() ?: return@runCatching null
        val bindingField = KavaMemberLookup.declaredFields(manager, makeAccessible = true) {
            !it.isStatic &&
                it.type.implementsInterfaceNamed("androidx.viewbinding.ViewBinding")
        }.singleOrNull() ?: return@runCatching null
        val rootGetter = KavaMemberLookup.declaredMethods(
            bindingField.type,
            makeAccessible = true
        ) {
            !it.isStatic && !it.isBridge && it.name == "getRoot" &&
                it.parameterCount == 0 && it.returnType isSubclassOf classOf<View>()
        }.singleOrNull() ?: return@runCatching null

        MineVipPoint(
            onResume = onResume.toHookPoint().copy(viewField = managerField.name),
            bindingField = bindingField.name,
            rootGetter = rootGetter.toHookPoint()
        )
    }.getOrNull()

    /**
     * 定位官方客户端更新信息同步入口。候选类来自 8.90.2 与 9.1.0–9.9.0 的离线字符串/
     * 签名交叉验证；运行期仍要求精确的 (Context) -> BiliUpgradeInfo 结构。
     * 8.90.2 同时存在接口桥接 a(Context) 与真实网络实现 c(Context)，优先选择没有被接口
     * 声明的叶子方法；新版本只有一个实现时直接采用唯一候选。
     */
    fun locateBlockUpdate(loader: ClassLoader): HookPoint? = runCatching {
        val upgradeInfo = KavaMemberLookup.classOrNull(loader, BILI_UPGRADE_INFO_CLASS)
            ?: return@runCatching null
        for (ownerName in BLOCK_UPDATE_OWNER_CANDIDATES) {
            val owner = KavaMemberLookup.classOrNull(loader, ownerName) ?: continue
            val candidates = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && !it.isAbstract &&
                    it.returnType == upgradeInfo &&
                    it.parameterTypes.contentEquals(arrayOf(classOf<Context>()))
            }
            if (candidates.isEmpty()) continue
            val interfaceSignatures = owner.interfaces.flatMap { contract ->
                KavaMemberLookup.declaredMethods(contract).map { method ->
                    method.name to method.parameterTypes.toList()
                }
            }.toSet()
            val leafCandidates = candidates.filter { method ->
                (method.name to method.parameterTypes.toList()) !in interfaceSignatures
            }
            val selected = leafCandidates.singleOrNull() ?: candidates.singleOrNull() ?: continue
            return@runCatching selected.toHookPoint()
        }
        null
    }.getOrNull()

    /**
     * 动态页页签定位。8.90.2、9.1.x 与 9.9.0 的渲染方法可能被 R8 内联，不能依赖
     * 某个混淆方法名；位置映射始终通过 MediatorFragment 唯一的无参 List getter，
     * 页签添加始终落到 TabLayout.addTab(Tab, boolean)。页签模型由 getter 泛型签名取得，
     * 再验证 title/name 两个 String 字段；任一结构不唯一即不安装，避免位置错配。
     */
    fun locateDynamicTabs(loader: ClassLoader): DynamicTabsPoint? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, DYNAMIC_MEDIATOR_FRAGMENT_CLASS)
            ?: return@runCatching null
        val mediatorTab = KavaMemberLookup.classOrNull(loader, DYNAMIC_MEDIATOR_TAB_CLASS)
            ?: return@runCatching null
        if (!mediatorTab.hasSuperclassNamed("com.google.android.material.tabs.TabLayout")) {
            return@runCatching null
        }
        val listGetter = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && classOf<List<*>>() == it.returnType
        }.singleOrNull() ?: return@runCatching null
        val itemClass = (listGetter.genericReturnType as? ParameterizedType)
            ?.actualTypeArguments
            ?.singleOrNull() as? Class<*>
            ?: return@runCatching null
        val titleField = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
            !it.isStatic && it.type == classOf<String>() && it.name == "a"
        }.singleOrNull() ?: return@runCatching null
        val nameField = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
            !it.isStatic && it.type == classOf<String>() && it.name == "b"
        }.singleOrNull() ?: return@runCatching null
        val tabLayout = mediatorTab.superclass?.let { current ->
            generateSequence(current) { it.superclass }
                .firstOrNull { it.name == "com.google.android.material.tabs.TabLayout" }
        } ?: return@runCatching null
        val tabClass = KavaMemberLookup.classOrNull(
            loader,
            "com.google.android.material.tabs.TabLayout\$Tab"
        ) ?: return@runCatching null
        val addTab = KavaMemberLookup.methodOrNull(
            tabLayout,
            "addTab",
            tabClass,
            classOf<Boolean>()
        ) ?: return@runCatching null
        val customViewGetter = KavaMemberLookup.methodOrNull(
            tabClass,
            "getCustomView"
        )?.takeIf { it.returnType isSubclassOf classOf<View>() }
            ?: return@runCatching null

        DynamicTabsPoint(
            listGetter = listGetter.toHookPoint(),
            addTab = addTab.toHookPoint(),
            tabCustomViewGetter = customViewGetter.toHookPoint(),
            mediatorTabClassName = mediatorTab.name,
            itemClassName = itemClass.name,
            itemTitleField = titleField.name,
            itemNameField = nameField.name
        )
    }.getOrNull()

    /**
     * 定位数字缩写格式化器。8.90.2、9.1.0 与 9.9.0 均同时保留 Java NumberFormat；
     * “我的”页则直接调用 kntr 的 Kotlin 顶层函数。只接受 static、String 返回值且首参
     * 为 int/long（含装箱）或 kntr 的数字字符串重载，避免碰触时间/小数格式化方法。
     */
    fun locateFullNumbers(loader: ClassLoader): FullNumberPoints? = runCatching {
        val acceptedNames = setOf(
            "format",
            "formatWithComma",
            "formatNumber",
            "format\$default",
            "formatNumber\$default"
        )
        val numericTypes = setOf(
            classOf<Int>(),
            classOf<Long>(),
            classOf<Int>(primitiveType = false),
            classOf<Long>(primitiveType = false)
        )
        val methods = FULL_NUMBER_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    val firstType = method.parameterTypes.firstOrNull()
                    method.isStatic && method.returnType == classOf<String>() &&
                        method.name in acceptedNames && method.parameterCount in 1..5 &&
                        (firstType in numericTypes ||
                            (firstType == classOf<String>() &&
                                owner.name == KNTR_NUMBER_FORMAT_CLASS))
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::FullNumberPoints)
    }.getOrNull()

    /**
     * 定位播放器“进入看一看”竖屏切换控件。8.90.2、9.1.0 与 9.9.0 的布局和 dex
     * 交叉验证表明该控件均由 GeminiPlayerFullStoryWidget 自身覆写 setVisibility(int)。
     * 只缓存控件自身声明的精确方法，不安装全局 View 可见性 Hook，也不扫描界面树。
     */
    fun locatePlayerPortrait(loader: ClassLoader): PlayerPortraitPoints? = runCatching {
        val methods = PLAYER_PORTRAIT_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(
                    owner,
                    "setVisibility",
                    classOf<Int>()
                )?.takeIf { method ->
                    method.declaringClass == owner && method.returnType == Void.TYPE
                }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::PlayerPortraitPoints)
    }.getOrNull()

    /**
     * 详情页透明状态栏只挂到目标 Activity 自己声明的 onCreate(Bundle)，避免全局
     * Activity 生命周期 Hook。9.x 主链均使用 UnitedBizDetailsActivity；类或签名漂移时
     * 直接不安装，不以类名模糊匹配其它页面。
     */
    fun locatePlayerStatusBar(loader: ClassLoader): PlayerStatusBarPoints? = runCatching {
        val methods = PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.app.Activity") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                    ?.takeIf { method ->
                        method.declaringClass == owner && method.returnType == Void.TYPE &&
                            !method.isStatic
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::PlayerStatusBarPoints)
    }.getOrNull()

    /**
     * 首页推荐卡片定位：响应只接受无参 List getter，卡片属性只接受公开无参 String
     * getter。运行期先由响应边界登记对象身份，再改写已登记竖屏卡片的路由，避免把同一
     * BasePegasusData 类型在其它页面的对象一并修改。
     */
    fun locateHomeRecommendFeed(loader: ClassLoader): HomeRecommendFeedPoints? = runCatching {
        val holder = KavaMemberLookup.classOrNull(loader, PEGASUS_HOLDER_DATA_CLASS)
            ?: return@runCatching null
        val holderType = KavaMemberLookup.methods(
            holder,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == "getHolderType" && method.parameterCount == 0 &&
                method.returnType == classOf<String>() && !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return@runCatching null

        val responseGetters = PEGASUS_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItems" && method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        !method.isStatic && !method.isAbstract
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (responseGetters.isEmpty()) return@runCatching null

        val base = BASE_PEGASUS_DATA_CLASS_CANDIDATES.firstNotNullOfOrNull {
            KavaMemberLookup.classOrNull(loader, it)
        } ?: holder
        fun stringGetter(name: String): HookPoint? = KavaMemberLookup.methods(
            base,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == name && method.parameterCount == 0 &&
                method.returnType == classOf<String>() && !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()

        val uri = stringGetter("getUri") ?: return@runCatching null
        fun objectGetterMethod(name: String): Method? = KavaMemberLookup.methods(
            base,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == name && method.parameterCount == 0 && method.isPublic &&
                !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull()
        fun objectGetter(name: String): HookPoint? = objectGetterMethod(name)?.toHookPoint()

        val playerArgsGetter = objectGetterMethod("getPlayerArgs")
        val playerArgsDurationGetter = playerArgsGetter?.returnType?.let { playerArgsClass ->
            KavaMemberLookup.methods(
                playerArgsClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getDuration" && method.parameterCount == 0 &&
                    method.isPublic && !method.isStatic &&
                    method.returnType in setOf(
                        classOf<Int>(), classOf<Long>(),
                        classOf<Int>(primitiveType = false),
                        classOf<Long>(primitiveType = false)
                    )
            }.distinctBy(Method::toGenericString).singleOrNull()
        }
        val playerArgsDurationField = playerArgsGetter?.returnType?.let { playerArgsClass ->
            KavaMemberLookup.declaredFields(playerArgsClass, makeAccessible = true) { field ->
                field.type in setOf(classOf<Int>(), classOf<Long>()) && field.isPublic &&
                    !field.isStatic && field.annotations.any { annotation ->
                        val annotationClass = annotation.annotationClass.java
                        val attributeName = when (annotationClass.name) {
                            "com.google.gson.annotations.SerializedName" -> "value"
                            "com.alibaba.fastjson.annotation.JSONField" -> "name"
                            else -> null
                        } ?: return@any false
                        runCatching {
                            annotationClass.getMethod(attributeName).invoke(annotation) as? String
                        }.getOrNull() == "duration"
                    }
            }.singleOrNull()
        }
        val durationOwner = playerArgsGetter?.takeIf {
            playerArgsDurationGetter != null || playerArgsDurationField != null
        }
        HomeRecommendFeedPoints(
            responseItemGetters = responseGetters,
            holderTypeGetter = holderType.toHookPoint(),
            bizTypeGetter = stringGetter("getBizType"),
            adInfoGetter = objectGetter("getAdInfo"),
            cardTypeGetter = stringGetter("getCardType"),
            cardGotoGetter = stringGetter("getCardGoto"),
            goToGetter = stringGetter("getGoTo"),
            uriGetter = uri,
            paramGetter = stringGetter("getParam"),
            titleGetter = stringGetter("getTitle"),
            subtitleGetter = stringGetter("getSubtitle"),
            descGetter = stringGetter("getDesc"),
            playerArgsGetter = durationOwner?.toHookPoint(),
            playerArgsDurationField = playerArgsDurationField?.name,
            playerArgsDurationGetter = playerArgsDurationGetter?.toHookPoint()
        )
    }.getOrNull()

    /** 精确定位相关推荐列表以及公开的直接/嵌套类型读取方法。 */
    fun locateVideoRelate(loader: ClassLoader): VideoRelatePoints? = runCatching {
        val responses = VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name in setOf("getCardsList", "getRelatesList", "getListList") &&
                        method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        !method.isStatic && !method.isAbstract
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (responses.isEmpty()) return@runCatching null

        fun itemMethods(name: String): List<Method> =
            VIDEO_RELATE_ITEM_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .flatMap { owner ->
                    KavaMemberLookup.methods(
                        owner,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { method ->
                        method.name == name && method.parameterCount == 0 &&
                            method.isPublic && !method.isStatic && method.returnType != Void.TYPE
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .toList()

        fun isDurationMethod(method: Method): Boolean = method.parameterCount == 0 &&
            method.isPublic && !method.isStatic &&
            method.returnType == classOf<Long>()

        fun isIntegralMethod(method: Method): Boolean = method.returnType in setOf(
            classOf<Long>(),
            classOf<Long>(primitiveType = false),
            classOf<Int>(),
            classOf<Int>(primitiveType = false)
        )

        val cases = itemMethods("getCardCase").map { it.toHookPoint() }
        val gotos = itemMethods("getGoto").map { it.toHookPoint() }
        val types = itemMethods("getCardType").map { it.toHookPoint() }
        val relateTypes = itemMethods("getRelateCardType")
            .filter { it.returnType.isEnum || it.returnType == classOf<String>() }
            .map { it.toHookPoint() }
        val sourceTypes = itemMethods("getFromSourceType")
            .filter(::isIntegralMethod)
            .map { it.toHookPoint() }
        val sourceTypeChains = itemMethods("getBasicInfo")
            .mapNotNull { itemGetter ->
                KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getFromSourceType" && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && isIntegralMethod(method)
                }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?.let { sourceTypeGetter ->
                        SourceTypeMethodChain(
                            itemGetter = itemGetter.toHookPoint(),
                            sourceTypeGetter = sourceTypeGetter.toHookPoint()
                        )
                    }
            }
            .distinctBy { it.itemGetter.label() + "->" + it.sourceTypeGetter.label() }
        val relateTypeValues = itemMethods("getRelateCardTypeValue")
            .filter {
                it.returnType == classOf<Int>() ||
                    it.returnType == classOf<Int>(primitiveType = false)
            }
            .map { it.toHookPoint() }
        val directDurations = itemMethods("getDuration")
            .filter(::isDurationMethod)
            .map { it.toHookPoint() }
        val durationChains = listOf("getAv", "getHistoryAv", "getAiCard")
            .flatMap(::itemMethods)
            .mapNotNull { itemGetter ->
                KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method -> method.name == "getDuration" && isDurationMethod(method) }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?.let { durationGetter ->
                        DurationMethodChain(
                            itemGetter = itemGetter.toHookPoint(),
                            durationGetter = durationGetter.toHookPoint()
                        )
                    }
            }
            .distinctBy { it.itemGetter.label() + "->" + it.durationGetter.label() }
        if (cases.isEmpty() && gotos.isEmpty() && types.isEmpty() &&
            relateTypes.isEmpty() && sourceTypes.isEmpty() && sourceTypeChains.isEmpty() &&
            relateTypeValues.isEmpty() && directDurations.isEmpty() && durationChains.isEmpty()
        ) return@runCatching null
        VideoRelatePoints(
            responseItemGetters = responses,
            cardCaseGetters = cases,
            gotoGetters = gotos,
            cardTypeGetters = types,
            relateCardTypeGetters = relateTypes,
            fromSourceTypeGetters = sourceTypes,
            fromSourceTypeChains = sourceTypeChains,
            relateCardTypeValueGetters = relateTypeValues,
            directDurationGetters = directDurations,
            durationChains = durationChains
        )
    }.getOrNull()

    /** 首页 Tab 构建方法：单个 List 参数、List 返回值，且参数泛型为 main2.resource。 */
    fun locateHomeTabs(loader: ClassLoader): HomeTabPoints? = runCatching {
        val candidates = HOME_FRAGMENT_V2_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && !method.isAbstract && method.parameterCount == 1 &&
                        (method.parameterTypes[0] isSubclassOf classOf<List<*>>()) &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        method.genericParameterTypes[0].toString().contains("main2.resource.")
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .toList()
        val build = candidates.singleOrNull() ?: return@runCatching null
        val resource = (build.genericParameterTypes[0] as? ParameterizedType)
            ?.actualTypeArguments?.singleOrNull() as? Class<*>
            ?: HOME_TAB_RESOURCE_CLASS_CANDIDATES.firstNotNullOfOrNull {
                KavaMemberLookup.classOrNull(loader, it)
            }
            ?: return@runCatching null
        val stringFields = KavaMemberLookup.declaredFields(resource, makeAccessible = true) {
            !it.isStatic && it.type == classOf<String>()
        }
        if (stringFields.size < 3) return@runCatching null
        HomeTabPoints(
            buildMethod = build.toHookPoint(),
            resourceClassName = resource.name,
            idField = stringFields[0].name,
            titleField = stringFields[1].name,
            uriField = stringFields[2].name,
            reporterIdField = stringFields.getOrNull(3)?.name
        )
    }.getOrNull()

    /** 首页子组件仅使用宿主 AndroidX Fragment 的公开生命周期和父级读取方法。 */
    fun locateHomeComponents(loader: ClassLoader): HomeComponentPoints? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, HOST_FRAGMENT_CLASS)
            ?: return@runCatching null
        val onViewCreated = KavaMemberLookup.methodOrNull(
            fragment,
            "onViewCreated",
            classOf<View>(),
            classOf<Bundle>()
        )?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
            ?: return@runCatching null
        val parent = KavaMemberLookup.methodOrNull(fragment, "getParentFragment")
            ?.takeIf { !it.isStatic && it.returnType.name == HOST_FRAGMENT_CLASS }
            ?: return@runCatching null
        HomeComponentPoints(onViewCreated.toHookPoint(), parent.toHookPoint())
    }.getOrNull()

    /**
     * “我的”页入口的数据层剪枝定位：以 AccountMine 为锚，在 Fragment 的静态构建方法
     * (Fragment, AccountMine) -> Unit 之后做原地剪枝。复用 locateMineEntry 的
     * AccountMine/Item 定位结论，但这里采集的是“运行时字段读写所需的字段名快照”。
     * 只有 sections、group.itemList 和 item.title 是剪枝必需字段；附件字段缺失时保留对应组件。
     */
    fun locateMineAccountMinePoints(loader: ClassLoader): MineAccountMinePoints? = runCatching {
        val fragmentName = MINE_FRAGMENT_CLASS
        val accountMineName = "tv.danmaku.bili.ui.main2.api.AccountMine"
        val groupName = "com.bilibili.lib.homepage.mine.MenuGroup"
        val itemName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        val fragment = KavaMemberLookup.classOrNull(loader, fragmentName)
            ?: return@runCatching null
        val accountMine = KavaMemberLookup.classOrNull(loader, accountMineName)
            ?: return@runCatching null
        val group = KavaMemberLookup.classOrNull(loader, groupName)
            ?: return@runCatching null
        val item = KavaMemberLookup.classOrNull(loader, itemName)
            ?: return@runCatching null

        val builds = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            it.isStatic && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(fragment, accountMine))
        }.map { it.toHookPoint() }
        if (builds.isEmpty()) return@runCatching null

        fun fieldName(owner: Class<*>, predicate: (java.lang.reflect.Field) -> Boolean): String? =
            KavaMemberLookup.declaredFields(owner, makeAccessible = true) { predicate(it) }
                .firstOrNull()
                ?.name

        val sectionsField = fieldName(accountMine) {
            it.name == "sectionListV2" && java.util.List::class.java.isAssignableFrom(it.type)
        } ?: fieldName(accountMine) {
            it.name.startsWith("sectionListV2") && java.util.List::class.java.isAssignableFrom(it.type)
        } ?: return@runCatching null
        val groupTitleField = fieldName(group) {
            it.name == "title" && it.type == classOf<String>()
        }
        val groupItemsField = fieldName(group) {
            !it.isStatic && !java.lang.reflect.Modifier.isFinal(it.modifiers) &&
                java.util.List::class.java.isAssignableFrom(it.type) &&
                (it.genericType as? java.lang.reflect.ParameterizedType)
                    ?.actualTypeArguments?.singleOrNull()?.rawClassOrNull()?.name == itemName
        } ?: return@runCatching null
        val itemTitleField = fieldName(item) {
            it.name == "title" && it.type == classOf<String>()
        } ?: return@runCatching null
        val itemIdField = fieldName(item) { it.name == "id" }
        val itemUriField = fieldName(item) {
            it.name == "uri" && it.type == classOf<String>()
        }
        val itemVisibleField = fieldName(item) { it.name == "visible" }
        val itemLocalShowField = fieldName(item) { it.name == "localShow" }
        val liveTipField = fieldName(accountMine) { it.name == "liveTip" }
        val vipSectionRightField = fieldName(accountMine) { it.name == "vipSectionRight" }
        val sectionButtonField = fieldName(group) { it.name == "button" }

        MineAccountMinePoints(
            buildMethods = builds,
            accountMineClass = accountMineName,
            sectionListV2Field = sectionsField,
            groupClass = groupName,
            groupTitleField = groupTitleField,
            groupItemListField = groupItemsField,
            itemClass = itemName,
            itemTitleField = itemTitleField,
            itemIdField = itemIdField,
            itemUriField = itemUriField,
            itemVisibleField = itemVisibleField,
            itemLocalShowField = itemLocalShowField,
            liveTipField = liveTipField,
            vipSectionRightField = vipSectionRightField,
            sectionButtonField = sectionButtonField
        )
    }.getOrNull()

    /**
     * “我的”页优先在 MenuGroup(V2) 的公开 getter 返回边界过滤；旧模型没有 getter 时，
     * 复用已定位的菜单构建方法，并缓存唯一 List<Item> 字段和语义稳定的 title 字段。
     */
    fun locateMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint? = null
    ): MineComponentPoints? = runCatching {
        val lists = MINE_MENU_GROUP_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItemList" && method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) && !method.isStatic
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val titles = MINE_MENU_ITEM_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getTitle" && method.parameterCount == 0 &&
                        method.returnType == classOf<String>() && !method.isStatic
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        fun itemGetter(name: String, requireString: Boolean): List<HookPoint> =
            MINE_MENU_ITEM_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .flatMap { owner ->
                    KavaMemberLookup.methods(
                        owner,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { method ->
                        method.name == name && method.parameterCount == 0 && !method.isStatic &&
                            method.returnType != Void.TYPE &&
                            (!requireString || method.returnType == classOf<String>())
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .map { it.toHookPoint() }
                .toList()
        val ids = itemGetter("getId", requireString = false)
        val uris = itemGetter("getUri", requireString = true)
        val groupTitles = MINE_MENU_GROUP_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getTitle" && method.parameterCount == 0 && !method.isStatic &&
                        method.returnType == classOf<String>()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (lists.isNotEmpty() && titles.isNotEmpty()) {
            return@runCatching MineComponentPoints(
                itemListGetters = lists,
                itemTitleGetters = titles,
                itemIdGetters = ids,
                itemUriGetters = uris,
                groupTitleGetters = groupTitles
            )
        }
        locateLegacyMineComponents(loader, mineEntry ?: locateMineEntry(loader))
    }.getOrNull()

    /** 仅供旧公开字段模型与单元测试使用；字段链在适配期确定，运行期直接 Field#get/set。 */
    internal fun locateLegacyMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint?
    ): MineComponentPoints? = runCatching {
        val entry = mineEntry ?: return@runCatching null
        for (groupName in MINE_MENU_GROUP_CLASS_CANDIDATES) {
            val group = KavaMemberLookup.classOrNull(loader, groupName) ?: continue
            val itemListFields = KavaMemberLookup.declaredFields(
                group,
                makeAccessible = true
            ) { field ->
                !field.isStatic &&
                    !java.lang.reflect.Modifier.isFinal(field.modifiers) &&
                    field.type isSubclassOf classOf<List<*>>() &&
                    ((field.genericType as? ParameterizedType)
                        ?.actualTypeArguments
                        ?.singleOrNull()
                        ?.rawClassOrNull()
                        ?.name in MINE_MENU_ITEM_CLASS_CANDIDATES)
            }
            val itemListField = itemListFields.singleOrNull() ?: continue
            val itemClass = (itemListField.genericType as? ParameterizedType)
                ?.actualTypeArguments
                ?.singleOrNull()
                ?.rawClassOrNull() ?: continue
            val titleField = KavaMemberLookup.declaredFields(
                itemClass,
                makeAccessible = true
            ) { field ->
                !field.isStatic && field.name == "title" && field.type == classOf<String>()
            }.singleOrNull() ?: continue
            return@runCatching MineComponentPoints(
                itemListGetters = emptyList(),
                itemTitleGetters = emptyList(),
                legacyBuildMethods = entry.buildMethods,
                legacyGroupListField = entry.groupListField,
                legacyAdapterField = entry.adapterField,
                legacyGroupClassName = group.name,
                legacyItemListField = itemListField.name,
                legacyItemClassName = itemClass.name,
                legacyItemTitleField = titleField.name
            )
        }
        null
    }.getOrNull()

    /** Story 只采用 StoryDetail 自身公开类型判断，不按标题、URI 或字段内容猜测。 */
    fun locateStoryFeed(loader: ClassLoader): StoryFeedPoints? = runCatching {
        val detail = KavaMemberLookup.classOrNull(loader, STORY_DETAIL_CLASS)
            ?: return@runCatching null
        fun booleanGetter(name: String): HookPoint? = KavaMemberLookup.methodOrNull(detail, name)
            ?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Boolean>()
            }
            ?.toHookPoint()

        fun objectGetter(name: String): Method? = KavaMemberLookup.methodOrNull(detail, name)
            ?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    !method.returnType.isPrimitive && method.returnType != Void.TYPE
            }

        val responses = KavaMemberLookup.classOrNull(loader, STORY_FEED_RESPONSE_CLASS)
            ?.let { response ->
                KavaMemberLookup.methods(
                    response,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItems" && !method.isStatic &&
                        method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>())
                }.distinctBy(Method::toGenericString).map { it.toHookPoint() }
            }.orEmpty()

        val pager = KavaMemberLookup.classOrNull(loader, STORY_PAGER_PLAYER_CLASS)
            ?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && !method.isAbstract && method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        (method.parameterTypes[0] isSubclassOf classOf<List<*>>()) &&
                        method.genericParameterTypes[0].toString().contains(STORY_DETAIL_CLASS)
                }.distinctBy(Method::toGenericString).map { it.toHookPoint() }
            }.orEmpty()

        val ad = booleanGetter("isAd")
        val live = booleanGetter("isLive")
        val game = booleanGetter("isGame")
        val bangumi = booleanGetter("isBangumi")
        val course = booleanGetter("isCheese")
        val music = booleanGetter("isMusic")
        val cartInfo = objectGetter("getCartIconInfo")
        val dramaPrompt = objectGetter("getDramaPromptBar")
        val seasonInfo = objectGetter("getSeasonInfo")
        val seasonType = seasonInfo?.returnType?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, "getSeasonType")
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == classOf<Int>()
                }
        }
        if ((responses.isEmpty() && pager.isEmpty()) ||
            (ad == null && live == null && game == null && bangumi == null && course == null &&
                music == null && cartInfo == null && dramaPrompt == null && seasonType == null)) {
            return@runCatching null
        }
        StoryFeedPoints(
            responses,
            pager,
            ad,
            live,
            game,
            bangumi,
            course,
            music,
            cartInfo?.toHookPoint(),
            dramaPrompt?.toHookPoint(),
            seasonInfo?.takeIf { seasonType != null }?.toHookPoint(),
            seasonType?.toHookPoint()
        )
    }.getOrNull()

    /**
     * 评论区只在 TheseusTabPagerService 的直接配置依赖中寻找 List<TabPage> 构造器，
     * 并要求 TabPage 存在返回含 Comment 枚举值的唯一无参方法，避免按混淆类名猜测。
     */
    fun locateCommentSection(loader: ClassLoader): CommentSectionPoints? = runCatching {
        val constructors = ArrayList<ListConstructorPoint>()
        var tagGetter: HookPoint? = null

        fun inspectConstructor(ownerConstructor: java.lang.reflect.Constructor<*>) {
            if (ownerConstructor.isSynthetic) return
            ownerConstructor.genericParameterTypes.forEachIndexed { index, genericType ->
                if (!(ownerConstructor.parameterTypes[index] isSubclassOf classOf<List<*>>())) {
                    return@forEachIndexed
                }
                val parameterized = genericType as? ParameterizedType ?: return@forEachIndexed
                val itemClass = parameterized.actualTypeArguments.singleOrNull()
                    ?.rawClassOrNull() ?: return@forEachIndexed
                val getter = KavaMemberLookup.methods(
                    itemClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && method.parameterCount == 0 && method.returnType.isEnum &&
                        method.returnType.enumConstants
                            ?.filterIsInstance<Enum<*>>()
                            ?.any { it.name.equals("Comment", ignoreCase = true) } == true
                }.distinctBy(Method::toGenericString).singleOrNull() ?: return@forEachIndexed

                val point = getter.toHookPoint()
                if (tagGetter != null && tagGetter != point) return@forEachIndexed
                tagGetter = point
                constructors += ListConstructorPoint(
                    ownerConstructor.declaringClass.name,
                    ownerConstructor.parameterTypes.map { it.name },
                    index
                )
            }
        }

        THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .forEach { service ->
                KavaMemberLookup.declaredConstructors(service, makeAccessible = true)
                    .forEach { serviceConstructor ->
                        inspectConstructor(serviceConstructor)
                        serviceConstructor.parameterTypes.asSequence()
                            .filter { parameter ->
                                !parameter.isPrimitive && !parameter.isArray &&
                                    parameter.classLoader === service.classLoader
                            }
                            .forEach { parameter ->
                                KavaMemberLookup.declaredConstructors(
                                    parameter,
                                    makeAccessible = true
                                ).forEach(::inspectConstructor)
                            }
                    }
            }

        val distinctConstructors = constructors.distinctBy {
            "${it.className}(${it.paramClassNames.joinToString(",")})#${it.listParameterIndex}"
        }
        CommentSectionPoints(
            listConstructors = distinctConstructors.takeIf { it.isNotEmpty() }
                ?: return@runCatching null,
            locatableTagGetter = tagGetter ?: return@runCatching null
        )
    }.getOrNull()

    /** 开屏净化定位白名单 List getter，并尽量补充公开 SplashItem 广告语义。 */
    fun locateSplashAds(loader: ClassLoader): SplashAdPoints? = runCatching {
        val getterMethods = SPLASH_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && !method.isAbstract && method.parameterCount == 0 &&
                        method.name in SPLASH_LIST_GETTER_NAMES &&
                        (method.returnType isSubclassOf classOf<List<*>>())
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .toList()
        if (getterMethods.isEmpty()) return@runCatching null
        val genericItemClasses = getterMethods.mapNotNull { getter ->
            (getter.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull()?.rawClassOrNull()
                ?.takeIf { it != classOf<Any>() }
        }
        val itemClasses = (genericItemClasses + SPLASH_ITEM_CLASS_CANDIDATES.mapNotNull {
            KavaMemberLookup.classOrNull(loader, it)
        }).distinctBy { it.name }
        val numericTypes = setOf(
            classOf<Int>(), classOf<Long>(),
            classOf<Int>(primitiveType = false), classOf<Long>(primitiveType = false)
        )
        val signals = itemClasses.flatMap { itemClass ->
            SPLASH_SIGNAL_METHOD_NAMES.flatMap { (role, names) ->
                KavaMemberLookup.methods(
                    itemClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && method.isPublic && method.parameterCount == 0 &&
                        method.name in names && when (role) {
                            "is_ad", "is_ad_loc" -> method.returnType in setOf(
                                classOf<Boolean>(), classOf<Boolean>(primitiveType = false)
                            )
                            "cm_mark", "card_type", "server_type" ->
                                method.returnType in numericTypes
                            else -> method.returnType == classOf<String>()
                        }
                }.map { method -> SplashItemSignalPoint(role, method.toHookPoint()) }
            }
        }.distinctBy { it.role + "|" + it.getter.label() }
        SplashAdPoints(
            listGetters = getterMethods.map { it.toHookPoint() },
            itemSignalGetters = signals
        )
    }.getOrNull()

    private fun Type.rawClassOrNull(): Class<*>? = when (this) {
        is Class<*> -> this
        is ParameterizedType -> rawType as? Class<*>
        is WildcardType -> upperBounds.firstNotNullOfOrNull { it.rawClassOrNull() }
        else -> null
    }

    /** 底栏按 TabHost 的公开 tabs 列表和唯一 (int, View) 绑定入口定位。 */
    fun locateBottomBar(loader: ClassLoader): BottomBarPoints? = runCatching {
        for (ownerName in BOTTOM_TAB_HOST_CLASS_CANDIDATES) {
            val owner = KavaMemberLookup.classOrNull(loader, ownerName) ?: continue
            val tabsGetter = KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getTabs" && !method.isStatic && method.parameterCount == 0 &&
                    (method.returnType isSubclassOf classOf<List<*>>())
            }.distinctBy(Method::toGenericString).singleOrNull() ?: continue
            val itemClass = (tabsGetter.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: continue
            val bind = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                !method.isStatic && method.returnType == Void.TYPE && method.parameterCount == 2 &&
                    method.parameterTypes[0] == classOf<Int>() &&
                    (method.parameterTypes[1] isSubclassOf classOf<View>())
            }.singleOrNull() ?: continue
            val strings = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
                !it.isStatic && it.type == classOf<String>()
            }.map { it.name }
            if (strings.isEmpty()) continue
            return@runCatching BottomBarPoints(
                tabsGetter = tabsGetter.toHookPoint(),
                bindTabMethod = bind.toHookPoint(),
                itemClassName = itemClass.name,
                itemStringFields = strings
            )
        }
        null
    }.getOrNull()

    /**
     * 定位播放器默认画质计算方法。8.84.0–8.87.0 使用稳定公开
     * PlayerSettingHelper#getDefaultQuality()，8.88.0–8.96.0 主要为混淆 h#c()，
     * 8.97.0 起迁移为混淆 f#a()，9.x 继续按版本漂移；方法体均读取
     * pref_player_mediaSource_quality_wifi_key，执行宿主限制降级并记录 "quality settings:"。
     * 运行期按新到旧候选探测，并要求单个 owner 内只有一个无参 Int 入口；这样既避开
     * 新版 dex 对旧混淆类的残留引用，也不会按宽泛方法名跨类批量安装。
     */
    fun locateDefaultVideoQuality(loader: ClassLoader): PlayerQualityPoints? = runCatching {
        val defaultMethod = PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.firstNotNullOfOrNull { ownerName ->
            val owner = KavaMemberLookup.classOrNull(loader, ownerName)
                ?: return@firstNotNullOfOrNull null
            KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isAbstract &&
                        method.parameterCount == 0 &&
                        method.returnType == classOf<Int>() &&
                        method.name in setOf(
                            "a",
                            "c",
                            "getDefaultQuality"
                        )
            }.singleOrNull()
        } ?: return@runCatching null

        fun sharedClass(key: String): Class<*>? = PLAYER_SHARED_CLASS_CANDIDATES[key]
            .orEmpty()
            .firstNotNullOfOrNull { KavaMemberLookup.classOrNull(loader, it) }
        fun hasPublicNoArg(owner: Class<*>?, names: Set<String>): Boolean = owner != null &&
            KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                !method.isStatic && method.isPublic && method.parameterCount == 0 &&
                    method.name in names
            }.isNotEmpty()

        val streamInfo = sharedClass("stream_info")
        val dashVideo = sharedClass("dash_video")
        val videoVod = sharedClass("video_vod")
        val vodInfo = sharedClass("vod_info")
        val capabilities = buildList {
            if (hasPublicNoArg(streamInfo, setOf("getQuality")) ||
                hasPublicNoArg(videoVod, setOf("getQn")) ||
                hasPublicNoArg(vodInfo, setOf("getQuality"))
            ) add("stream_quality")
            if (hasPublicNoArg(streamInfo, setOf("getNeedVip", "getVipFree"))) {
                add("vip_entitlement")
            }
            if (hasPublicNoArg(dashVideo, setOf("getCodecid")) ||
                hasPublicNoArg(vodInfo, setOf("getVideoCodecid"))
            ) add("codec")
        }
        PlayerQualityPoints(defaultMethod.toHookPoint(), capabilities)
    }.getOrNull()

    /**
     * 青少年模式提示页在 8.90.2、9.1.0 与 9.9.0 均保留稳定类名。
     * 只接受 Activity 自身声明的 onCreate(Bundle)，避免结束正常青少年模式设置页面。
     */
    fun locateTeenagersMode(loader: ClassLoader): TeenagersModePoints? = runCatching {
        val methods = TEENAGERS_MODE_ACTIVITY_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.app.Activity") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                    ?.takeIf { method ->
                        !method.isStatic && method.declaringClass == owner &&
                            method.returnType == Void.TYPE
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::TeenagersModePoints)
    }.getOrNull()

    /**
     * 定位评论筛选的公开 protobuf 读取链。
     *
     * 以 ReplyInfo 的真实返回类型反推 Content/Member，避免包重定位后分别选择到不同代
     * 的同名类；列表边界还要求泛型元素精确为同一个 ReplyInfo，拒绝无关 List getter。
     */
    fun locateCommentFilter(loader: ClassLoader): CommentFilterPoints? = runCatching {
        val replyInfo = COMMENT_REPLY_INFO_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull() ?: return@runCatching null

        fun publicNoArg(owner: Class<*>, name: String): Method? =
            KavaMemberLookup.methodOrNull(owner, name)?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 && method.isPublic
            }

        val contentGetter = publicNoArg(replyInfo, "getContent")
            ?.takeIf { !it.returnType.isPrimitive } ?: return@runCatching null
        val messageGetter = publicNoArg(contentGetter.returnType, "getMessage")
            ?.takeIf { it.returnType == classOf<String>() } ?: return@runCatching null
        val memberGetter = publicNoArg(replyInfo, "getMember")
            ?.takeIf { !it.returnType.isPrimitive }
        val levelGetter = memberGetter?.let { getter -> publicNoArg(getter.returnType, "getLevel") }
            ?.takeIf { method ->
                method.returnType in setOf(
                    classOf<Int>(),
                    classOf<Long>(),
                    classOf<Int>(primitiveType = false),
                    classOf<Long>(primitiveType = false)
                )
            }
        val memberV2Getter = publicNoArg(replyInfo, "getMemberV2")
            ?.takeIf { !it.returnType.isPrimitive }
        val memberV2BasicGetter = memberV2Getter?.let { getter ->
            publicNoArg(getter.returnType, "getBasic")?.takeIf { !it.returnType.isPrimitive }
        }
        val memberV2LevelGetter = memberV2BasicGetter?.let { getter ->
            publicNoArg(getter.returnType, "getLevel")?.takeIf { method ->
                method.returnType in setOf(
                    classOf<Int>(), classOf<Long>(),
                    classOf<Int>(primitiveType = false),
                    classOf<Long>(primitiveType = false)
                )
            }
        }
        if (levelGetter == null && memberV2LevelGetter == null) return@runCatching null

        fun Method.returnsReplyInfoList(): Boolean {
            if (!(returnType isSubclassOf classOf<List<*>>())) return false
            val generic = genericReturnType as? ParameterizedType ?: return false
            val argument = generic.actualTypeArguments.singleOrNull() ?: return false
            val argumentName = when (argument) {
                is Class<*> -> argument.name
                is ParameterizedType -> (argument.rawType as? Class<*>)?.name
                else -> null
            }
            return argumentName == replyInfo.name
        }

        val listGetters = (COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES + replyInfo.name)
            .asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 0 && method.isPublic &&
                        method.name in setOf("getRepliesList", "getTopRepliesList") &&
                        method.returnsReplyInfoList()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (listGetters.isEmpty()) return@runCatching null

        val topReplyGetters = COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                listOf("getUpTop", "getAdminTop", "getVoteTop").asSequence()
                    .mapNotNull { name ->
                        publicNoArg(owner, name)?.takeIf { it.returnType == replyInfo }
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val replyDefaultInstance = KavaMemberLookup.declaredMethods(
            replyInfo,
            makeAccessible = true
        ) { method ->
            method.isStatic && method.isPublic && method.name == "getDefaultInstance" &&
                method.parameterCount == 0 && method.returnType == replyInfo
        }.singleOrNull()

        CommentFilterPoints(
            replyListGetters = listGetters,
            contentGetter = contentGetter.toHookPoint(),
            messageGetter = messageGetter.toHookPoint(),
            memberGetter = memberGetter?.takeIf { levelGetter != null }?.toHookPoint(),
            levelGetter = levelGetter?.toHookPoint(),
            memberV2Getter = memberV2Getter?.takeIf { memberV2LevelGetter != null }?.toHookPoint(),
            memberV2BasicGetter = memberV2BasicGetter?.takeIf {
                memberV2LevelGetter != null
            }?.toHookPoint(),
            memberV2LevelGetter = memberV2LevelGetter?.toHookPoint(),
            topReplyGetters = topReplyGetters.takeIf { replyDefaultInstance != null }.orEmpty(),
            replyDefaultInstanceGetter = replyDefaultInstance?.takeIf {
                topReplyGetters.isNotEmpty()
            }?.toHookPoint()
        )
    }.getOrNull()

    /**
     * 定位回复脉络的 ReplyInfo 身份桥和宿主 MOSS 公开分页边界。
     *
     * protobuf 类型和方法名在 8.90.2—9.9.0 样本中保持公开稳定；唯一可能混淆的
     * ReplyInfo -> CommentItem 映射方法按首参/返回类型结构定位，拒绝猜测 a..g 字段。
     */
    fun locateCommentTopology(loader: ClassLoader): CommentTopologyPoints? =
        locateCommentTopologyWithDiagnostic(loader).points

    private fun locateCommentTopologyWithDiagnostic(
        loader: ClassLoader
    ): CommentTopologyLocateOutcome {
        var stage = "reply-info-class"
        val attempt = runCatching {
        val replyInfo = COMMENT_REPLY_INFO_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull() ?: return@runCatching null
        val replyPackage = replyInfo.name.substringBeforeLast('.')
        stage = "comment-item-class"
        val commentItem = KavaMemberLookup.classOrNull(loader, COMMENT_ITEM_CLASS)
            ?: return@runCatching null
        stage = "reply-mapper-methods"
        val mapperMethods = COMMENT_REPLY_MAPPER_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { mapperOwner ->
                KavaMemberLookup.declaredMethods(mapperOwner, makeAccessible = true) { method ->
                    method.isStatic && !method.isAbstract && !method.isSynthetic &&
                        method.returnType == commentItem && method.parameterCount >= 1 &&
                        method.parameterTypes.firstOrNull() == replyInfo
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .sortedBy(Method::toGenericString)
            .take(CommentTopologyPoints.MAX_MAPPER_METHODS + 1)
            .toList()
        if (mapperMethods.isEmpty()) return@runCatching null
        if (mapperMethods.size > CommentTopologyPoints.MAX_MAPPER_METHODS) {
            error("too-many-reply-mappers:${mapperMethods.size}")
        }

        fun publicNoArg(owner: Class<*>, name: String): Method? =
            KavaMemberLookup.inheritedMethodOrNull(owner, name)?.takeIf { method ->
                !method.isStatic && method.isPublic && method.parameterCount == 0
            }

        fun publicStaticNoArg(owner: Class<*>, name: String): Method? =
            KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                method.isStatic && method.isPublic && method.name == name &&
                    method.parameterCount == 0
            }.singleOrNull()

        fun publicExact(owner: Class<*>, name: String, vararg params: Class<*>): Method? =
            KavaMemberLookup.inheritedMethodOrNull(owner, name, *params)?.takeIf { method ->
                !method.isStatic && method.isPublic &&
                    method.parameterTypes.contentEquals(params)
            }

        val methods = linkedMapOf<String, HookPoint>()
        fun add(key: String, method: Method?): Method {
            val resolved = method ?: throw NoSuchMethodException(key)
            methods[key] = resolved.toHookPoint()
            return resolved
        }

        add(CommentTopologyPoints.REPLY_ID, publicNoArg(replyInfo, "getId"))
        add(CommentTopologyPoints.COMMENT_ITEM_ID, publicNoArg(commentItem, "getId"))
        add(CommentTopologyPoints.REPLY_OID, publicNoArg(replyInfo, "getOid"))
        add(CommentTopologyPoints.REPLY_TYPE, publicNoArg(replyInfo, "getType"))
        add(CommentTopologyPoints.REPLY_ROOT, publicNoArg(replyInfo, "getRoot"))
        add(CommentTopologyPoints.REPLY_PARENT, publicNoArg(replyInfo, "getParent"))
        add(CommentTopologyPoints.REPLY_DIALOG, publicNoArg(replyInfo, "getDialog"))
        add(CommentTopologyPoints.REPLY_CTIME, publicNoArg(replyInfo, "getCtime"))
        add(CommentTopologyPoints.REPLY_COUNT, publicNoArg(replyInfo, "getCount"))
        add(CommentTopologyPoints.REPLY_MID, publicNoArg(replyInfo, "getMid"))
        val contentGetter = add(
            CommentTopologyPoints.REPLY_CONTENT,
            publicNoArg(replyInfo, "getContent")
        )
        val memberGetter = publicNoArg(replyInfo, "getMember")
        memberGetter?.let { methods[CommentTopologyPoints.REPLY_MEMBER] = it.toHookPoint() }
        val memberV2Getter = add(
            CommentTopologyPoints.REPLY_MEMBER_V2,
            publicNoArg(replyInfo, "getMemberV2")
        )
        val parentMemberGetter = publicNoArg(replyInfo, "getParentReplyMember")
        parentMemberGetter?.let {
            methods[CommentTopologyPoints.REPLY_PARENT_MEMBER] = it.toHookPoint()
        }
        add(CommentTopologyPoints.REPLY_CHILDREN, publicNoArg(replyInfo, "getRepliesList"))
        add(
            CommentTopologyPoints.CONTENT_MESSAGE,
            publicNoArg(contentGetter.returnType, "getMessage")
        )
        memberGetter?.let { getter ->
            publicNoArg(getter.returnType, "getName")?.let { nameGetter ->
                methods[CommentTopologyPoints.MEMBER_NAME] = nameGetter.toHookPoint()
            }
        }
        val memberBasicGetter = add(
            CommentTopologyPoints.MEMBER_V2_BASIC,
            publicNoArg(memberV2Getter.returnType, "getBasic")
        )
        add(
            CommentTopologyPoints.MEMBER_BASIC_NAME,
            publicNoArg(memberBasicGetter.returnType, "getName")
        )
        parentMemberGetter?.let { getter ->
            publicNoArg(getter.returnType, "getName")?.let { nameGetter ->
                methods[CommentTopologyPoints.PARENT_MEMBER_NAME] = nameGetter.toHookPoint()
            }
        }

        stage = "feed-pagination-class"
        val pagination = FEED_PAGINATION_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull() ?: return@runCatching null
        val paginationNewBuilder = add(
            CommentTopologyPoints.PAGINATION_NEW_BUILDER,
            publicStaticNoArg(pagination, "newBuilder")
        )
        val paginationBuilder = paginationNewBuilder.returnType
        add(
            CommentTopologyPoints.PAGINATION_SET_OFFSET,
            publicExact(paginationBuilder, "setOffset", classOf<String>())
        )
        add(CommentTopologyPoints.PAGINATION_BUILD, publicNoArg(paginationBuilder, "build"))

        stage = "detail-list-request-class"
        val detailReq = KavaMemberLookup.classOrNull(loader, "$replyPackage.DetailListReq")
            ?: return@runCatching null
        val detailNewBuilder = add(
            CommentTopologyPoints.DETAIL_NEW_BUILDER,
            publicStaticNoArg(detailReq, "newBuilder")
        )
        val detailBuilder = detailNewBuilder.returnType
        add(CommentTopologyPoints.DETAIL_SET_OID, publicExact(detailBuilder, "setOid", classOf<Long>()))
        add(CommentTopologyPoints.DETAIL_SET_TYPE, publicExact(detailBuilder, "setType", classOf<Long>()))
        add(CommentTopologyPoints.DETAIL_SET_MODE, publicExact(detailBuilder, "setModeValue", classOf<Int>()))
        add(
            CommentTopologyPoints.DETAIL_SET_PAGINATION,
            publicExact(detailBuilder, "setPagination", pagination)
        )
        add(CommentTopologyPoints.DETAIL_SET_ROOT, publicExact(detailBuilder, "setRoot", classOf<Long>()))
        add(CommentTopologyPoints.DETAIL_SET_RPID, publicExact(detailBuilder, "setRpid", classOf<Long>()))
        add(CommentTopologyPoints.DETAIL_BUILD, publicNoArg(detailBuilder, "build"))

        stage = "reply-moss-class"
        val replyMoss = KavaMemberLookup.classOrNull(loader, "$replyPackage.ReplyMoss")
            ?: return@runCatching null
        stage = "reply-moss-constructor"
        if (KavaMemberLookup.constructorOrNull(replyMoss) == null) return@runCatching null
        stage = "reply-moss-detail-list"
        val detailCall = KavaMemberLookup.methods(
            replyMoss,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            !method.isStatic && method.isPublic && method.name == "detailList" &&
                method.parameterCount == 2 && method.parameterTypes[0] == detailReq &&
                method.returnType == Void.TYPE
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return@runCatching null
        add(CommentTopologyPoints.MOSS_DETAIL, detailCall)

        stage = "detail-list-reply-class"
        val detailReply = KavaMemberLookup.classOrNull(loader, "$replyPackage.DetailListReply")
            ?: return@runCatching null
        val detailRoot = add(
            CommentTopologyPoints.DETAIL_ROOT,
            publicNoArg(detailReply, "getRoot")
        )
        stage = "detail-root-return-type"
        if (detailRoot.returnType != replyInfo) return@runCatching null
        val detailPagination = add(
            CommentTopologyPoints.DETAIL_PAGINATION,
            publicNoArg(detailReply, "getPaginationReply")
        )
        add(
            CommentTopologyPoints.PAGINATION_NEXT_OFFSET,
            publicNoArg(detailPagination.returnType, "getNextOffset")
        )

        stage = "required-method-validation"
        CommentTopologyPoints(
            mapperMethods = mapperMethods.map { it.toHookPoint() },
            replyMossClassName = replyMoss.name,
            methods = methods
        ).takeIf(CommentTopologyPoints::hasRequiredMethods)
        }
        val points = attempt.getOrNull()
        val detail = if (points != null) {
            ""
        } else {
            attempt.exceptionOrNull()?.let { throwable ->
                val message = throwable.message?.takeIf(String::isNotBlank)
                "${throwable.javaClass.simpleName}:${message ?: stage}"
            } ?: "unresolved:$stage"
        }
        return CommentTopologyLocateOutcome(points, detail)
    }

    private data class CommentTopologyLocateOutcome(
        val points: CommentTopologyPoints?,
        val failureDetail: String
    )

    /**
     * 定位评论内容的公开 URL Map getter。8.90.2、9.1.0 与 9.9.0 均保留 getUrls/
     * getUrlsMap；只接管 Map 返回边界，避免修改 protobuf 内部 MapFieldLite 的可变状态。
     */
    fun locateCommentPurify(loader: ClassLoader): CommentPurifyPoints? = runCatching {
        val urlMethods = COMMENT_CONTENT_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.name in setOf("getUrls", "getUrlsMap") &&
                        method.returnType isSubclassOf classOf<Map<*, *>>()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val emptyPageGetters = COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { owner ->
                val contentGetter = KavaMemberLookup.methodOrNull(owner, "getEmptyPage")
                    ?.takeIf { method ->
                        !method.isStatic && method.parameterCount == 0 &&
                            method.returnType.simpleName == "EmptyPage"
                    } ?: return@mapNotNull null
                val defaultInstanceGetter = KavaMemberLookup.methodOrNull(
                    contentGetter.returnType,
                    "getDefaultInstance"
                )?.takeIf { method ->
                    method.isStatic && method.parameterCount == 0 &&
                        method.returnType == contentGetter.returnType
                } ?: return@mapNotNull null
                CommentEmptyPagePoint(
                    contentGetter.toHookPoint(),
                    defaultInstanceGetter.toHookPoint()
                )
            }
            .distinctBy { it.contentGetter.label() }
            .toList()
        val voteWidgetMethods = COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    if (method.isStatic || method.returnType != Void.TYPE) return@declaredMethods false
                    val parameterNames = method.parameterTypes.map { it.name }
                    when (owner.simpleName) {
                        "CmtVoteWidget" -> method.parameterCount == 4 &&
                            parameterNames.count { it.endsWith(".CmtThemeStrategy") } == 1 &&
                            parameterNames.count { it == "kotlin.jvm.functions.Function0" } == 1 &&
                            parameterNames.count { it == "kotlin.jvm.functions.Function1" } == 1
                        "CmtMountWidget" -> method.parameterCount == 2 &&
                            parameterNames.count { it.endsWith(".CmtThemeStrategy") } == 1
                        "CommentVoteView" -> method.parameterCount == 1 &&
                            method.name.startsWith("setVoteData") &&
                            !method.parameterTypes[0].isPrimitive
                        else -> false
                    }
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val followWidgetMethods = COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .flatMap { owner ->
                val directMethods = KavaMemberLookup.declaredMethods(
                    owner,
                    makeAccessible = true
                ) { method ->
                    if (method.isStatic || method.returnType != Void.TYPE) {
                        return@declaredMethods false
                    }
                    val binder = method.parameterCount == 1 &&
                        !method.parameterTypes[0].isPrimitive
                    val visibilityState = method.parameterCount == 0 &&
                        method.name != "onDetachedFromWindow"
                    binder || visibilityState
                }.map { it.toHookPoint() }
                val callbackMethods = owner.declaredClasses.flatMap { callbackClass ->
                    val outerField = KavaMemberLookup.declaredFields(
                        callbackClass,
                        makeAccessible = true
                    ) { field -> !field.isStatic && field.type == owner }.singleOrNull()
                        ?: return@flatMap emptyList()
                    KavaMemberLookup.declaredMethods(
                        callbackClass,
                        makeAccessible = true
                    ) { method ->
                        !method.isStatic && method.returnType == Void.TYPE &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[1] == classOf<Int>()
                    }.map { method -> method.toHookPoint().copy(viewField = outerField.name) }
                }
                (directMethods + callbackMethods).asSequence()
            }
            .distinctBy { it.label() }
            .toList()
        val followButtonClass = COMMENT_FOLLOW_BUTTON_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull()
        val headerBindMethods = if (followButtonClass == null) {
            emptyList()
        } else {
            COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .filter { it.hasSuperclassNamed("android.view.ViewGroup") }
                .flatMap { owner ->
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                        !method.isStatic && method.returnType == Void.TYPE &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[0] isSubclassOf classOf<List<*>>() &&
                            !method.parameterTypes[1].isPrimitive
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .map { it.toHookPoint() }
                .toList()
        }
        val followPoints = if (followWidgetMethods.isEmpty() && headerBindMethods.isEmpty()) {
            null
        } else {
            CommentFollowPoints(
                followWidgetMethods,
                headerBindMethods,
                followButtonClass?.name
            )
        }
        val mainListOwner = COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull()
        fun optionalPayloadPoint(
            owner: Class<*>?,
            presenceName: String,
            contentName: String
        ): CommentOptionalPayloadPoint? {
            val messageClass = owner ?: return null
            val presenceGetter = KavaMemberLookup.methodOrNull(messageClass, presenceName)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == classOf<Boolean>()
                } ?: return null
            val contentGetter = KavaMemberLookup.methodOrNull(messageClass, contentName)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        !method.returnType.isPrimitive
                } ?: return null
            val defaultInstanceGetter = KavaMemberLookup.methodOrNull(
                contentGetter.returnType,
                "getDefaultInstance"
            )?.takeIf { method ->
                method.isStatic && method.parameterCount == 0 &&
                    method.returnType == contentGetter.returnType
            } ?: return null
            return CommentOptionalPayloadPoint(
                presenceGetter.toHookPoint(),
                contentGetter.toHookPoint(),
                defaultInstanceGetter.toHookPoint()
            )
        }
        val qoePoint = optionalPayloadPoint(mainListOwner, "hasQoe", "getQoe")
        val operationPoints = listOfNotNull(
            optionalPayloadPoint(mainListOwner, "hasOperation", "getOperation"),
            optionalPayloadPoint(mainListOwner, "hasOperationV2", "getOperationV2")
        )
        val quickReplyDialogMethods = COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && method.parameterCount == 2 &&
                        method.parameterTypes[0].name.endsWith(".PublishDialogIntent") &&
                        method.parameterTypes[1].name == "kotlin.coroutines.Continuation"
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (urlMethods.isEmpty() && emptyPageGetters.isEmpty() && voteWidgetMethods.isEmpty() &&
            followPoints == null && qoePoint == null && operationPoints.isEmpty() &&
            quickReplyDialogMethods.isEmpty()) {
            null
        } else {
            CommentPurifyPoints(
                urlMethods,
                emptyPageGetters,
                voteWidgetMethods,
                followPoints,
                qoePoint,
                operationPoints,
                quickReplyDialogMethods
            )
        }
    }.getOrNull()

    /** 暂停页请求入口并行探测；仅零参数 invoke 才允许被识别为旧 Function0。 */
    private fun locatePausePoints(loader: ClassLoader): PausePoints {
        fun method(className: String, name: String, predicate: (Method) -> Boolean): HookPoint? {
            val owner = KavaMemberLookup.classOrNull(loader, className) ?: return null
            return KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                it.name == name && predicate(it)
            }.singleOrNull()?.toHookPoint()
        }

        val requests = listOfNotNull(
            method(
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
                "invokeSuspend"
            ) { !it.isStatic && it.parameterCount == 1 },
            method(
                "com.bilibili.ship.theseus.united.page.pausedpage." +
                    "PausedPageService\$requestPausedPageData\$2",
                "invokeSuspend"
            ) { !it.isStatic && it.parameterCount == 1 }
        )
        val legacy = method(
            "kntr.app.ad.biz.videodetail.pausedpage.ui.g",
            "invoke"
        ) { !it.isStatic && it.parameterCount == 0 }
        val panel = method(
            "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository",
            "showPanel"
        ) { !it.isStatic && it.parameterCount >= 2 }
        val countdown = method(
            "com.bilibili.ship.theseus.united.page.pausedpage." +
                "PausedPageService\$showPauseBarCountdownToast\$3",
            "invokeSuspend"
        ) { !it.isStatic && it.parameterCount == 1 }
        return PausePoints(requests, legacy, panel, countdown)
    }

    /**
     * V8Banner 从 8.90.2 到 9.9.0 均继承 SwiperBanner；父类的 attach、setAdapter 与
     * visibility 回调是稳定低频入口。Hook 时仍按 V8Banner 实例过滤，不影响其它轮播控件。
     */
    private fun locateBanner(loader: ClassLoader): BannerPoint? = runCatching {
        val bannerName = "com.bilibili.pegasus.holders.bannerv8.V8Banner"
        val banner = KavaMemberLookup.classOrNull(loader, bannerName)
            ?: return@runCatching null
        var currentOwner: Class<*>? = banner.superclass
        while (
            currentOwner != null &&
            currentOwner.name != "com.bilibili.app.comm.list.widget.swiper.SwiperBanner"
        ) {
            currentOwner = currentOwner.superclass
        }
        val owner = currentOwner ?: return@runCatching null
        val hooks = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            when (it.name) {
                "onAttachedToWindow" -> it.parameterCount == 0
                "setAdapter" -> it.parameterCount == 1 &&
                    it.parameterTypes[0].name.endsWith(".SwiperBannerAdapter")
                "onVisibilityChanged" -> it.parameterTypes.contentEquals(
                    arrayOf(classOf<android.view.View>(), Integer.TYPE)
                )
                else -> false
            }
        }.map { it.toHookPoint() }
        if (hooks.none { it.methodName == "onAttachedToWindow" }) return@runCatching null
        BannerPoint(bannerName, hooks)
    }.getOrNull()

    /**
     * 首页顶部栏结构化定位：
     * - 游戏菜单构建方法在 8.90.2 为 c(Menu, MenuInflater)，9.1.0–9.9.0 为 b(...)；
     * - 默认搜索词模型在 8.90.2 为 api.d，9.1.0–9.9.0 为 api.b。
     * 因此只依赖参数/返回类型与 SwitchTextView 字段特征，不缓存宿主实例。
     */
    fun locateHomeTopBar(loader: ClassLoader): HomeTopBarPoints? = runCatching {
        val menuOwner = KavaMemberLookup.classOrNull(loader, HOME_MENU_ITEM_CLASS)
        val gameMenu = menuOwner?.let { owner ->
            val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(
                        arrayOf(classOf<Menu>(), classOf<MenuInflater>())
                    )
            }.singleOrNull() ?: return@let null
            val configField = KavaMemberLookup.declaredFields(
                owner,
                makeAccessible = true
            ) { field ->
                !field.isStatic &&
                    field.type.enclosingClass == owner &&
                    KavaMemberLookup.declaredFields(field.type) {
                        it.type == classOf<String>()
                    }.isNotEmpty()
            }.singleOrNull() ?: return@let null
            method.toHookPoint().copy(viewField = configField.name)
        }

        val baseOnViewCreated = HOME_BASE_FRAGMENT_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { owner ->
                val searchFields = KavaMemberLookup.declaredFields(
                    owner,
                    makeAccessible = true
                ) {
                    (it.type isSubclassOf classOf<TextView>()) &&
                        it.type.simpleName == "SwitchTextView"
                }
                val searchField = searchFields.singleOrNull() ?: return@mapNotNull null
                val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                    !it.isStatic && it.returnType == Void.TYPE &&
                        it.name == "onViewCreated" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(classOf<View>(), classOf<Bundle>())
                        )
                }.singleOrNull() ?: return@mapNotNull null
                method.toHookPoint().copy(viewField = searchField.name)
            }
            .firstOrNull()

        val defaultWordMethods = if (baseOnViewCreated == null) {
            emptyList()
        } else {
            HOME_MAIN_FRAGMENT_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .map { owner ->
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                        !it.isStatic && !it.isAbstract &&
                            it.returnType == Void.TYPE && it.parameterCount == 1 &&
                            it.parameterTypes[0].name in HOME_DEFAULT_WORD_CLASS_CANDIDATES
                    }.distinctBy(Method::toGenericString)
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .map { it.toHookPoint() }
        }

        if (gameMenu == null && baseOnViewCreated == null && defaultWordMethods.isEmpty()) {
            null
        } else {
            HomeTopBarPoints(gameMenu, baseOnViewCreated, defaultWordMethods)
        }
    }.getOrNull()

    /**
     * 低版本评论 holder：候选类存在 + 有绑定方法（方法名漂移自适应：8.90.2 为 o0、
     * 9.8.0 为 q0——按候选方法名列表匹配；参数不限，运行期 hook 全部重载）。
     */
    private fun locateCommentLow(loader: ClassLoader): HookPoint? {
        val methodCandidates = listOf("o0", "q0")
        for (cn in COMMENT_LOW_CANDIDATES) {
            val c = KavaMemberLookup.classOrNull(loader, cn) ?: continue
            val declaredMethods = KavaMemberLookup.declaredMethods(c)
            for (mn in methodCandidates) {
                if (declaredMethods.any { it.name == mn }) {
                    return HookPoint(cn, mn)
                }
            }
        }
        return null
    }

    /**
     * 高版本评论 handler：候选类存在 + 有 CommentItem 字段 + 绑定方法。
     * 特征（版本无关、字段名/方法名/参数类名全部自适应，历史漂移全覆盖）：
     * - 8.63.0：CommentContentRichTextHandler（字段 h、绑定方法 G(CommentItem, jv.u, ...)）
     * - 9.0.0 ：Pj.J / b、9.8.0：al.J / d/e——三者类名/字段名/方法名均不同
     * 特征 1：存在「声明了 CommentItem 类型字段」的字段（字段名不限 i/h）。
     * 特征 2（绑定方法）：参数中存在「声明了 View 类型字段」的类（ViewBinding 特征，
     *   jv.u / Pj.J / al.J 均命中——含 View 字段即可，不依赖字段名 a），且参数个数 1-5
     *   （G 有 5 参）。候选列表仅含评论 handler 类，双特征已足够精确，不再加额外校验
     *   （9.8.0 的 d(al.J, boolean) 参数无 comment3.* 类，曾因外加校验误判漏定位）。
     */
    private fun locateCommentHigh(loader: ClassLoader): HookPoint? {
        for (cn in COMMENT_HIGH_CANDIDATES) {
            val c = KavaMemberLookup.classOrNull(loader, cn) ?: continue
            // 特征 1：任一字段声明 CommentItem（字段名不限；8.63.0 为 h、9.x 为 i）
            val hasCommentItemField = KavaMemberLookup.declaredFields(c) {
                it.type.name.endsWith(".CommentItem")
            }.isNotEmpty()
            if (!hasCommentItemField) continue
            // 特征 2：绑定方法 = 非 static + 参数含 ViewBinding（View 字段）+ 参数 1-5。
            // 9.8.0 的 static h(al.J) 只设置字号/颜色，旧定位器会误把它缓存成绑定入口。
            // 候选中优先带 CommentItem 实参的方法（可避免 Handler 可变字段串项），否则
            // 选参数更多的主绑定方法（9.8.0 为 d(al.J, boolean)，而 e(al.J) 是分支布局）。
            val bindingCandidates = java.util.ArrayList<java.lang.reflect.Method>()
            for (m in KavaMemberLookup.declaredMethods(c)) {
                if (m.parameterCount < 1 || m.parameterCount > 5) continue
                if (m.isStatic) continue
                var hasViewBinding = false
                for (pt in m.parameterTypes) {
                    if (pt.isPrimitive || pt.isArray || pt.isInterface) continue
                    val hasViewField = KavaMemberLookup.declaredFields(pt) {
                        it.type isSubclassOf classOf<android.view.View>()
                    }.isNotEmpty()
                    if (hasViewField) { hasViewBinding = true; break }
                }
                if (!hasViewBinding) continue
                bindingCandidates.add(m)
            }
            val best = bindingCandidates.maxWithOrNull(
                compareBy<java.lang.reflect.Method> {
                    if (it.parameterTypes.any { pt -> pt.name.endsWith(".CommentItem") }) 1 else 0
                }.thenBy { it.parameterCount }
            )
            if (best != null) {
                return HookPoint(cn, best.name, best.parameterTypes.map { it.name }, null)
            }
        }
        return null
    }

    /** 主线程弹适配 toast（B 站进程内） */
    fun showAdaptToast(context: Context, text: String) {
        runCatching {
            android.os.Looper.getMainLooper().let { looper ->
                if (android.os.Looper.myLooper() == looper) {
                    // 保留主线程显式分支；BetterAndroid 默认禁止后台线程直接弹 Toast。
                    //noinspection ReplaceWithToastExtension
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                } else {
                    // 保留 Handler 切换主线程的既有行为，不交由扩展创建后台 Looper。
                    //noinspection ReplaceWithToastExtension
                    android.os.Handler(looper).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}
