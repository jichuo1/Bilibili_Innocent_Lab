package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCacheStore
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomainCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultMerger
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultValidator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AiPlaylistDirectorAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CloudKeyFreezeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PgcAutoActivityPopupLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentQuickReplyAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailRelateGameAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailSpecialTagAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureFlags
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureRefreshContext
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowUpRecommendAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.GuestPlayerCoreAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeRecommendEarlyLoadAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LocateObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LongPressCalculatorPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PausedAdDexAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ProtocolFingerprintBuilder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StandardDexFeatureAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SyncLocatorRun
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.Theseus2LongPressPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareVersionProfile
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistAttemptGuard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistCandidateSelector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.CommentLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.CommentTopologyLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.CommentTopologyLocator.CommentTopologyLocateOutcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.HomeRecommendFeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.label
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MediaQualityAndPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MineAndHomeUiLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.VideoRelateLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.withoutDomains
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeSemanticsAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 哔哩哔哩版本适配器（学 BiliRoaming 的 hook 点自动定位思路，轻量实现）。
 *
 * 设计目标：
 * - **前置适配**：B 站版本变化 / 全新版本 / 模块首次安装时，在 B 站启动阶段一次性
 *   自动定位各功能 hook 点并缓存；适配完成后每次启动走快路径（仅读缓存），不影响
 *   冷启动与运行期性能。
 * - **智能定位**：不在手机上反编译（性能/时间不允许），而是运行时对「内置候选类名」
 *   通过 MeguvizRef 验证类 + 匹配方法签名特征（如 ViewBinding 参数类含 View 字段 a），
 *   自动适配 hook 点漂移（方法重载变化、签名变化）。
 * - **手动重适配**：UI 提供「重新适配」按钮，清除缓存后重启即重新定位。
 *
 * 缓存位置：模块 prefs（LSPosed 托管，跨进程可读）：
 *   adapted_bili_version : Int     已适配的 B 站 versionCode
 *   adapt_result         : String  适配结果 JSON（各功能 hook 点类名/方法/签名）
 */
object VersionAdapter {

    private val adaptationRunning = AtomicBoolean(false)

    private val dexAuditRunning = AtomicBoolean(false)

    /** 适配状态回调（toast 提示用） */
    interface AdaptCallback {
        /** 适配开始（主线程，弹提示） */
        fun onAdaptStarted()

        /** 适配完成（主线程） */
        fun onAdaptFinished(ok: Boolean)

        /** 静默重绑定或静默补适配完成（不弹提示；成功时触发待重试安装器，失败时释放等待状态） */
        fun onSilentCacheReady(ok: Boolean = true) {}
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
    private const val SCHEMA_VERSION = VersionAdapterContract.SCHEMA_VERSION
    private const val ADAPTER_RULE_VERSION = VersionAdapterContract.RULE_VERSION

    /**
     * DEX 兜底诊断 id 前缀。每个兜底点各占一条诊断，便于在诊断中心直接读到"兜底是否被用到、
     * 结论是什么"；缓存合并按前缀识别，见 [mergeRuntimeWithCached]。
     * `dex.assist` 保持为 block-update 的历史 id，不改名以兼容既有缓存。
     */
    private const val DEX_ASSIST_ID_PREFIX = AdaptAssistGate.ID_PREFIX
    internal const val DEX_ASSIST_BLOCK_UPDATE_ID = AdaptAssistGate.BLOCK_UPDATE_ID
    internal const val DEX_ASSIST_COMMENT_TOPOLOGY_ID = AdaptAssistGate.COMMENT_TOPOLOGY_ID
    internal const val DEX_ASSIST_PLAYER_QUALITY_ID = AdaptAssistGate.PLAYER_QUALITY_ID
    private const val DEX_SOURCE_UNAVAILABLE = AdaptCacheStore.DEX_SOURCE_UNAVAILABLE

    /** 计划没有要求该家族：诊断里的「未请求」，既不是 FOUND 也不是 MISSING，更不是为了补全诊断而重新探测。 */
    internal const val PLAN_NOT_REQUESTED = "plan:not-requested"

    enum class AdaptState {
        FOUND,
        MISSING,
        NOT_APPLICABLE,
        /** Startup-only omitted locator; never serialized into the complete adaptation cache. */
        NOT_REQUESTED
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
        val countdown: HookPoint?,
        val apiEntry: HookPoint? = null
    ) {
        /** 五个子点都没有定位到；被计划裁剪的暂停页家族就是这个值。 */
        fun isEmpty(): Boolean = requestMethods.isEmpty() && legacyCallback == null &&
            panelShow == null && countdown == null && apiEntry == null

        fun toJson(): JSONObject = JSONObject().apply {
            put("requests", JSONArray().apply { requestMethods.forEach { put(it.toJson()) } })
            legacyCallback?.let { put("legacy", it.toJson()) }
            panelShow?.let { put("panel", it.toJson()) }
            countdown?.let { put("countdown", it.toJson()) }
            apiEntry?.let { put("api_entry", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): PausePoints {
                val requests = o.optJSONArray("requests")
                return PausePoints(
                    requestMethods = if (requests == null) emptyList() else
                        (0 until requests.length()).map { HookPoint.fromJson(requests.getJSONObject(it)) },
                    legacyCallback = o.optJSONObject("legacy")?.let(HookPoint::fromJson),
                    panelShow = o.optJSONObject("panel")?.let(HookPoint::fromJson),
                    countdown = o.optJSONObject("countdown")?.let(HookPoint::fromJson),
                    apiEntry = o.optJSONObject("api_entry")?.let(HookPoint::fromJson)
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
        /**
         * 新 Compose 顶栏的整列表入口（9.11.0 实测与老 options menu 路径**并存**）。
         *
         * 落点是 `TopRightComponent$initTopRight$1$1.invoke(List, Continuation)`，
         * 参数 0 就是全部右上角项。比 [gameMenu] 更靠上游，且锚点类名未混淆
         * （R8 把外层组件改成了 `topbar.k`，合成 lambda 的描述符仍保留原名）。
         * 两条路径互为保底，不可互相替代：老宿主只有前者、放量后只有后者生效。
         */
        val composeGameMenu: HookPoint?,
        val baseOnViewCreated: HookPoint?,
        val defaultWordMethods: List<HookPoint>,
        val kotlinDefaultWords: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            kotlinDefaultWords?.let { put("kotlin_words", it.toJson()) }
            gameMenu?.let { put("game", it.toJson()) }
            composeGameMenu?.let { put("game_compose", it.toJson()) }
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
                    composeGameMenu = o.optJSONObject("game_compose")?.let(HookPoint::fromJson),
                    baseOnViewCreated = o.optJSONObject("view")?.let(HookPoint::fromJson),
                    defaultWordMethods = if (words == null) emptyList() else
                        (0 until words.length()).map {
                            HookPoint.fromJson(words.getJSONObject(it))
                        },
                    kotlinDefaultWords = o.optJSONObject("kotlin_words")?.let(HookPoint::fromJson)
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

    /** Theseus 活动描述器的独立构造单元；槽位来自当前构造器类型，安装期再验属性语义。 */
    data class PgcAutoActivityPopupPoints(
        val construct: HookPoint,
        val constructorParameters: List<String>,
        val popupIndex: Int,
        val propertiesField: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("construct", construct.toJson())
            put("params", JSONArray(constructorParameters))
            put("index", popupIndex)
            put("properties", propertiesField)
        }

        internal fun isValid(): Boolean =
            construct.className ==
                "com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor" &&
                construct.methodName == "constructWith" &&
                construct.paramClassNames == listOf("[Ljava.lang.Object;") &&
                propertiesField.isNotBlank() && constructorParameters.all { it.isNotBlank() } &&
                popupIndex in constructorParameters.indices &&
                constructorParameters.count {
                    it == "com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"
                } == 1 && constructorParameters[popupIndex] ==
                "com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"

        companion object {
            fun fromJson(o: JSONObject): PgcAutoActivityPopupPoints =
                PgcAutoActivityPopupPoints(
                    HookPoint.fromJson(o.getJSONObject("construct")),
                    o.getJSONArray("params").let { values ->
                        List(values.length()) { values.getString(it) }
                    },
                    o.getInt("index"),
                    o.getString("properties")
                )
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

    /**
     * 播放器互动层（投票/关注引导/契约卡/指令弹幕）的 protobuf 读边界。
     *
     * Guide 只缓存白名单 `clear*`，不含 `clearVideoPoint`。同步/异步响应是主路径，
     * getter 补充缓存读取；Guide 与 DmResource 独立定位。
     */
    data class PlayerInteractiveGuideFamily(
        val replyClassName: String,
        val guideGetter: HookPoint?,
        val guideClears: List<HookPoint>,
        /** 保留默认实例定位信息；实际清理通过字段 has/count 判断，不再无条件清空。 */
        val guideDefault: HookPoint? = null,
        /** `ViewProgressReply.getDm()`：互动层的第二个载体，只有 viewunite 有。 */
        val dmGetter: HookPoint? = null,
        val dmClears: List<HookPoint> = emptyList(),
        val dmDefault: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("reply", replyClassName)
            guideGetter?.let { put("getter", it.toJson()) }
            put("clears", JSONArray().apply { guideClears.forEach { put(it.toJson()) } })
            guideDefault?.let { put("default", it.toJson()) }
            dmGetter?.let { put("dm_getter", it.toJson()) }
            if (dmClears.isNotEmpty()) {
                put("dm_clears", JSONArray().apply { dmClears.forEach { put(it.toJson()) } })
            }
            dmDefault?.let { put("dm_default", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerInteractiveGuideFamily =
                PlayerInteractiveGuideFamily(
                    replyClassName = o.getString("reply"),
                    guideGetter = o.optJSONObject("getter")?.let(HookPoint::fromJson),
                    guideClears = o.getJSONArray("clears").let { values ->
                        (0 until values.length()).map {
                            HookPoint.fromJson(values.getJSONObject(it))
                        }
                    },
                    guideDefault = o.optJSONObject("default")?.let(HookPoint::fromJson),
                    dmGetter = o.optJSONObject("dm_getter")?.let(HookPoint::fromJson),
                    dmClears = o.optJSONArray("dm_clears")?.let { values ->
                        (0 until values.length()).map {
                            HookPoint.fromJson(values.getJSONObject(it))
                        }
                    }.orEmpty(),
                    dmDefault = o.optJSONObject("dm_default")?.let(HookPoint::fromJson)
                )
        }
    }

    data class PlayerInteractiveOverlayPoints(
        val families: List<PlayerInteractiveGuideFamily>,
        val mossExecutes: List<HookPoint>,
        val commandGetter: HookPoint?,
        val commandClear: HookPoint?,
        val commandDefault: HookPoint?,
        /** `DmViewReply.clearActivityMeta()`：清运营活动横幅（TV 版推广那块图）。 */
        val commandActivityMetaClear: HookPoint? = null,
        val mossAsync: List<HookPoint> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("families", JSONArray().apply { families.forEach { put(it.toJson()) } })
            put("moss", JSONArray().apply { mossExecutes.forEach { put(it.toJson()) } })
            put("moss_async", JSONArray().apply { mossAsync.forEach { put(it.toJson()) } })
            commandGetter?.let { put("command_getter", it.toJson()) }
            commandClear?.let { put("command_clear", it.toJson()) }
            commandDefault?.let { put("command_default", it.toJson()) }
            commandActivityMetaClear?.let { put("activity_meta_clear", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerInteractiveOverlayPoints =
                PlayerInteractiveOverlayPoints(
                    families = o.getJSONArray("families").let { values ->
                        (0 until values.length()).map {
                            PlayerInteractiveGuideFamily.fromJson(values.getJSONObject(it))
                        }
                    },
                    mossExecutes = o.optJSONArray("moss")?.let { values ->
                        (0 until values.length()).map {
                            HookPoint.fromJson(values.getJSONObject(it))
                        }
                    }.orEmpty(),
                    commandGetter = o.optJSONObject("command_getter")?.let(HookPoint::fromJson),
                    commandClear = o.optJSONObject("command_clear")?.let(HookPoint::fromJson),
                    commandDefault = o.optJSONObject("command_default")?.let(HookPoint::fromJson),
                    commandActivityMetaClear = o.optJSONObject("activity_meta_clear")
                        ?.let(HookPoint::fromJson),
                    mossAsync = o.optJSONArray("moss_async")?.let { values ->
                        (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                    }.orEmpty()
                )
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
        val playerArgsDurationGetter: HookPoint? = null,
        /**
         * `BasePegasusData.getArgs()`：分区过滤的上下文来源（`ArgsData`）。
         *
         * 与 [playerArgsGetter] 同构的两级链——外层拿容器、内层拿数值，
         * 缺任一级就整个维度降级，不影响其他判据。
         */
        val argsGetter: HookPoint? = null,
        /** `ArgsData.getTid()`：分区 id。9110400 实测返回 `long`，但装箱形式也收。 */
        val argsTidGetter: HookPoint? = null,
        /** `ArgsData.getTname()`：分区名，只用于快照展示，**不参与任何判定**。 */
        val argsTnameGetter: HookPoint? = null,
        /**
         * `ArgsData.getRid()`：一级分区 id。
         *
         * 卡片同时带 `rid`（一级）与 `tid`（二级），是同一套分区 id 体系的两级。
         * 服务端下发的面板项指哪一级不确定，所以两级都要参与比对。
         */
        val argsRidGetter: HookPoint? = null,
        /**
         * `DislikeItemData.setSelectedDislikeReason(DislikeReason)`——**广告卡**三点面板
         * 执行"不感兴趣"时调用。
         *
         * 宿主在调用点自己已经按 `FeedbackType.DISLIKE` 分流（反馈组走
         * `setSelectedFeedbackReason`），所以走到这里就一定是不喜欢组。
         */
        val dislikeReasonSetter: HookPoint? = null,
        /**
         * `DislikeItemData.setDislikeRequestRecord(DislikeRequestRecord)`——**普通卡**三点面板
         * 执行"不感兴趣"时调用。
         *
         * 这条链不按类型分流，所以要自己读 [dislikeTypeGetter] 再判一次。
         */
        val dislikeRecordSetter: HookPoint? = null,
        /**
         * `DislikeItemData.getDislikeAnchor()`：不喜欢占位卡回指原卡。
         *
         * 占位卡自己的 `args` 是空的（宿主构造时全走默认值），分区只能从这里回读。
         */
        val dislikeAnchorGetter: HookPoint? = null,
        /** `DislikeItemData.getSelectedDislikeType()`：区分"不感兴趣"与"反馈"两组。 */
        val dislikeTypeGetter: HookPoint? = null,
        /**
         * `FeedbackItem.getExtend()`——被点项的 `extend`，分区项上等于这张卡的分区 id。
         *
         * 2026-09-12 实测：**不能**改从 `DislikeRequestRecord.getExtend()` 读，那个返回的是
         * `DislikeReason.extra`；工厂搬运时只保留 id/toast/extra，`extend` 当场就丢了。
         *
         * `FeedbackItem` 在未混淆的共享弹窗模块里，且这个 getter 的 6 个调用点全部落在
         * 不感兴趣的执行路径上（两条派发链 + 各自的撤销 lambda + 旧版 CardClickProcessor），
         * 渲染期一次都不调，所以挂在这里既准又不烫。
         */
        val feedbackItemExtendGetter: HookPoint? = null,
        /** `FeedbackItem.getId()`：被点项 id，只用于旧面板兜底判据与诊断。 */
        val feedbackItemIdGetter: HookPoint? = null,
        /**
         * `FeedbackDialogFragmentV5.onCreate(Bundle)`——**手机**上的三点面板。
         *
         * 2026-09-12 实测：`com.bilibili.pegasus.feedbackdialog.a#b` 一开头按屏幕宽度分流，
         * `isWidthNormal` 为真（手机）走 `a#a` → `FeedbackDialogFragmentV5`；
         * `FeedbackDialogV5` 是平板/宽屏那条。前两版落点（`BottomSheetContent`、
         * `FeedbackDialogV5` 构造器）都不在手机这条路上，表现为注入器建成了却一次不触发。
         *
         * 类名未混淆；分组列表与点击回调在它的字段里，各自**类型唯一**，按类型认即可。
         */
        val feedbackDialogFragmentCreate: HookPoint? = null,
        /**
         * `Feedback.getItems()`——一个**分组**里的项列表。
         *
         * 面板是分组结构（"稍后再看"一组、"不感兴趣"一组），外层 List 装的是分组，
         * 不是项。注入要落到某个分组的 items 里，否则类型对不上。
         */
        val feedbackGroupItemsGetter: HookPoint? = null,
        /** `Feedback.getStyle()`；`copy` 回去时原样带上，保持视觉一致。 */
        val feedbackGroupStyleGetter: HookPoint? = null,
        /** `Feedback.getTitle()`；同上。 */
        val feedbackGroupTitleGetter: HookPoint? = null,
        /** `Feedback.copy(style, title, items)`——用它把我们的行并进现有分组。 */
        val feedbackGroupCopy: HookPoint? = null,
        /**
         * `FeedbackItem` 的主构造器（`methodName` 固定为 `<init>`，只用 className 与参数表）。
         *
         * 参数位置不写死：安装期用哨兵值构造一次再读 getter 反查，见 `FeedbackPanelInjector`。
         */
        val feedbackItemConstructor: HookPoint? = null,
        /** `FeedbackItem.getTitle()`：只用于安装期标定构造器参数位置。 */
        val feedbackItemTitleGetter: HookPoint? = null,
        /** `FeedbackItem.getType()`：同上，兼作注入项的自检。 */
        val feedbackItemTypeGetter: HookPoint? = null,
        /** `FeedbackItem.getOnClick()`：同上。行点击调的就是它，所以注入项的回调归我们。 */
        val feedbackItemOnClickGetter: HookPoint? = null,
        /** `ArgsData.getUpId()`：UP 主 mid。 */
        val argsUpIdGetter: HookPoint? = null,
        /** `ArgsData.getUpName()`：UP 主名。 */
        val argsUpNameGetter: HookPoint? = null,
        /** 宿主统一 Intent 入口；只用于近期首页视频的 Story 路由最终净化。 */
        val intentHandlerOnCreate: HookPoint? = null
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
            argsGetter?.let { put("args", it.toJson()) }
            argsTidGetter?.let { put("args_tid", it.toJson()) }
            argsTnameGetter?.let { put("args_tname", it.toJson()) }
            argsRidGetter?.let { put("args_rid", it.toJson()) }
            dislikeReasonSetter?.let { put("dislike_reason_setter", it.toJson()) }
            dislikeRecordSetter?.let { put("dislike_record_setter", it.toJson()) }
            dislikeAnchorGetter?.let { put("dislike_anchor", it.toJson()) }
            dislikeTypeGetter?.let { put("dislike_type", it.toJson()) }
            feedbackItemExtendGetter?.let { put("feedback_item_extend", it.toJson()) }
            feedbackItemIdGetter?.let { put("feedback_item_id", it.toJson()) }
            feedbackDialogFragmentCreate?.let { put("feedback_dialog_create", it.toJson()) }
            feedbackGroupItemsGetter?.let { put("feedback_group_items", it.toJson()) }
            feedbackGroupStyleGetter?.let { put("feedback_group_style", it.toJson()) }
            feedbackGroupTitleGetter?.let { put("feedback_group_title", it.toJson()) }
            feedbackGroupCopy?.let { put("feedback_group_copy", it.toJson()) }
            feedbackItemConstructor?.let { put("feedback_item_ctor", it.toJson()) }
            feedbackItemTitleGetter?.let { put("feedback_item_title", it.toJson()) }
            feedbackItemTypeGetter?.let { put("feedback_item_type", it.toJson()) }
            feedbackItemOnClickGetter?.let { put("feedback_item_onclick", it.toJson()) }
            argsUpIdGetter?.let { put("args_up_id", it.toJson()) }
            argsUpNameGetter?.let { put("args_up_name", it.toJson()) }
            intentHandlerOnCreate?.let { put("intent_handler_on_create", it.toJson()) }
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
                    ?.let(HookPoint::fromJson),
                argsGetter = o.optJSONObject("args")?.let(HookPoint::fromJson),
                argsTidGetter = o.optJSONObject("args_tid")?.let(HookPoint::fromJson),
                argsTnameGetter = o.optJSONObject("args_tname")?.let(HookPoint::fromJson),
                argsRidGetter = o.optJSONObject("args_rid")?.let(HookPoint::fromJson),
                dislikeReasonSetter = o.optJSONObject("dislike_reason_setter")
                    ?.let(HookPoint::fromJson),
                dislikeRecordSetter = o.optJSONObject("dislike_record_setter")
                    ?.let(HookPoint::fromJson),
                dislikeAnchorGetter = o.optJSONObject("dislike_anchor")?.let(HookPoint::fromJson),
                dislikeTypeGetter = o.optJSONObject("dislike_type")?.let(HookPoint::fromJson),
                feedbackItemExtendGetter = o.optJSONObject("feedback_item_extend")
                    ?.let(HookPoint::fromJson),
                feedbackItemIdGetter = o.optJSONObject("feedback_item_id")
                    ?.let(HookPoint::fromJson),
                feedbackDialogFragmentCreate = o.optJSONObject("feedback_dialog_create")
                    ?.let(HookPoint::fromJson),
                feedbackGroupItemsGetter = o.optJSONObject("feedback_group_items")
                    ?.let(HookPoint::fromJson),
                feedbackGroupStyleGetter = o.optJSONObject("feedback_group_style")
                    ?.let(HookPoint::fromJson),
                feedbackGroupTitleGetter = o.optJSONObject("feedback_group_title")
                    ?.let(HookPoint::fromJson),
                feedbackGroupCopy = o.optJSONObject("feedback_group_copy")
                    ?.let(HookPoint::fromJson),
                feedbackItemConstructor = o.optJSONObject("feedback_item_ctor")
                    ?.let(HookPoint::fromJson),
                feedbackItemTitleGetter = o.optJSONObject("feedback_item_title")
                    ?.let(HookPoint::fromJson),
                feedbackItemTypeGetter = o.optJSONObject("feedback_item_type")
                    ?.let(HookPoint::fromJson),
                feedbackItemOnClickGetter = o.optJSONObject("feedback_item_onclick")
                    ?.let(HookPoint::fromJson),
                argsUpIdGetter = o.optJSONObject("args_up_id")?.let(HookPoint::fromJson),
                argsUpNameGetter = o.optJSONObject("args_up_name")?.let(HookPoint::fromJson),
                intentHandlerOnCreate = o.optJSONObject("intent_handler_on_create")
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

    /**
     * 详情页推荐项的播放量链：`getAv`/`getAiCard` → `getStat` → `getVt` → `getValue`。
     *
     * 不包含 `getHistoryAv`。四步缺一即整条丢弃。老缓存没有 `play_count_chains`
     * 时按空表降级，对应维度不生效。
     */
    data class PlayCountMethodChain(
        val itemGetter: HookPoint,
        val statGetter: HookPoint,
        val vtGetter: HookPoint,
        val valueGetter: HookPoint
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("item", itemGetter.toJson())
            put("stat", statGetter.toJson())
            put("vt", vtGetter.toJson())
            put("value", valueGetter.toJson())
        }

        companion object {
            fun fromJson(o: JSONObject): PlayCountMethodChain? {
                val item = o.optJSONObject("item") ?: return null
                val stat = o.optJSONObject("stat") ?: return null
                val vt = o.optJSONObject("vt") ?: return null
                val value = o.optJSONObject("value") ?: return null
                return PlayCountMethodChain(
                    itemGetter = HookPoint.fromJson(item),
                    statGetter = HookPoint.fromJson(stat),
                    vtGetter = HookPoint.fromJson(vt),
                    valueGetter = HookPoint.fromJson(value)
                )
            }
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

    /** 相关推荐结构化理由的公开 getter 链，长度限定为 1–3 层。 */
    data class ReasonMethodChain(
        val steps: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): ReasonMethodChain = ReasonMethodChain(
                steps = o.getJSONArray("steps").let { values ->
                    (0 until values.length()).map {
                        HookPoint.fromJson(values.getJSONObject(it))
                    }
                }
            )
        }
    }

    /** 相关推荐结构化商业证据的公开布尔 getter 链，长度限定为 1–2 层。 */
    data class BooleanMethodChain(
        val steps: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): BooleanMethodChain = BooleanMethodChain(
                steps = o.getJSONArray("steps").let { values ->
                    (0 until values.length()).map {
                        HookPoint.fromJson(values.getJSONObject(it))
                    }
                }
            )
        }
    }

    /** 详情页推荐 DTO 转为界面组件前的第二层入口；字段和 getter 均在适配期唯一确认。 */
    data class DetailRelateServicePoint(
        val componentFactory: HookPoint,
        val typeField: String? = null,
        val typeGetter: HookPoint? = null,
        val titleGetter: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("factory", componentFactory.toJson())
            typeField?.let { put("type_field", it) }
            typeGetter?.let { put("type_getter", it.toJson()) }
            titleGetter?.let { put("title_getter", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): DetailRelateServicePoint = DetailRelateServicePoint(
                componentFactory = HookPoint.fromJson(o.getJSONObject("factory")),
                typeField = o.optString("type_field").takeIf(String::isNotBlank),
                typeGetter = o.optJSONObject("type_getter")?.let(HookPoint::fromJson),
                titleGetter = o.optJSONObject("title_getter")?.let(HookPoint::fromJson)
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
        val durationChains: List<DurationMethodChain>,
        val playCountChains: List<PlayCountMethodChain> = emptyList(),
        val reasonChains: List<ReasonMethodChain> = emptyList(),
        val commercialEvidenceChains: List<BooleanMethodChain> = emptyList(),
        /**
         * 作者与标签维度的读取链（按分区过滤的详情页侧）。
         *
         * 详情页协议里**没有 tid**（`view.v1.Relate` 98 字段实测无此字段），
         * 所以这里退到作者与 `tagName`。三条都是可选的，缺了只降级对应那一档。
         */
        val authorNameChains: List<ReasonMethodChain> = emptyList(),
        val authorMidChains: List<ReasonMethodChain> = emptyList(),
        val tagNameChains: List<ReasonMethodChain> = emptyList(),
        val detailRelateService: DetailRelateServicePoint? = null,
        /**
         * 发布时间文案链：`RelateCard#getBasicInfo → CardBasicInfo#getDesc` 与
         * `view.v1.Relate#getDesc`。35 个宿主版本都没有结构化发布时间，只能读展示文案；
         * 老缓存缺 `publish_text_chains` 时为空表，发布时间维度报 partial。
         */
        val publishTextChains: List<ReasonMethodChain> = emptyList()
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
            put(
                "play_count_chains",
                JSONArray().apply { playCountChains.forEach { put(it.toJson()) } }
            )
            put(
                "reason_chains",
                JSONArray().apply { reasonChains.forEach { put(it.toJson()) } }
            )
            put(
                "commercial_evidence_chains",
                JSONArray().apply { commercialEvidenceChains.forEach { put(it.toJson()) } }
            )
            put("author_name_chains", JSONArray().apply { authorNameChains.forEach { put(it.toJson()) } })
            put("author_mid_chains", JSONArray().apply { authorMidChains.forEach { put(it.toJson()) } })
            put("tag_name_chains", JSONArray().apply { tagNameChains.forEach { put(it.toJson()) } })
            put("publish_text_chains", JSONArray().apply { publishTextChains.forEach { put(it.toJson()) } })
            detailRelateService?.let { put("detail_relate_service", it.toJson()) }
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
                }.orEmpty(),
                playCountChains = o.optJSONArray("play_count_chains")?.let { values ->
                    (0 until values.length()).mapNotNull {
                        PlayCountMethodChain.fromJson(values.getJSONObject(it))
                    }
                }.orEmpty(),
                reasonChains = o.getJSONArray("reason_chains").let { values ->
                    (0 until values.length()).map {
                        ReasonMethodChain.fromJson(values.getJSONObject(it))
                    }
                },
                commercialEvidenceChains = o.optJSONArray("commercial_evidence_chains")
                    ?.let { values ->
                        (0 until values.length()).map {
                            BooleanMethodChain.fromJson(values.getJSONObject(it))
                        }
                    }.orEmpty(),
                authorNameChains = chainsOf(o, "author_name_chains"),
                authorMidChains = chainsOf(o, "author_mid_chains"),
                tagNameChains = chainsOf(o, "tag_name_chains"),
                detailRelateService = o.optJSONObject("detail_relate_service")
                    ?.let(DetailRelateServicePoint::fromJson),
                publishTextChains = chainsOf(o, "publish_text_chains")
            )

            /** 老缓存没有这三个键时得到空表，对应那一档降级，不影响既有维度。 */
            private fun chainsOf(o: JSONObject, key: String): List<ReasonMethodChain> =
                o.optJSONArray(key)?.let { values ->
                    (0 until values.length()).map {
                        ReasonMethodChain.fromJson(values.getJSONObject(it))
                    }
                }.orEmpty()
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
     * 公开 getter 与“菜单构建方法 + 精确字段链”独立保留。同一 APK 可同时携带新旧
     * 模型，存在 getter 不代表当前布局消费它；仍不扫描 View 树或缓存宿主实例。
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
     * 评论关键词/等级/@整条/发布者过滤的公开 protobuf 读取边界。
     *
     * 列表 getter 只负责提供待筛选的 ReplyInfo；正文、等级、@ 映射和发布者 getter 只读取
     * 判定信号，不写 protobuf 私有字段，也不接触评论富文本、emoji 或 View 绑定链路。
     *
     * 四类判据各自可缺失：某一条判据的 getter 没定位到时，安装器只降级该判据并把它计入
     * 覆盖分母，不影响其余判据，也绝不静默当作已生效。
     */
    data class CommentFilterPoints(
        val replyListGetters: List<HookPoint>,
        val contentGetter: HookPoint?,
        val messageGetter: HookPoint?,
        val memberGetter: HookPoint?,
        val levelGetter: HookPoint?,
        val memberV2Getter: HookPoint? = null,
        val memberV2BasicGetter: HookPoint? = null,
        val memberV2LevelGetter: HookPoint? = null,
        val topReplyGetters: List<HookPoint> = emptyList(),
        val replyDefaultInstanceGetter: HookPoint? = null,
        /** `Content.getAtNameToMidCount()`：先读计数再决定要不要取 Map，避免无谓分配。 */
        val atNameCountGetter: HookPoint? = null,
        /** `Content.getAtNameToMidMap()`：@ 名字集合，用于判断整条是否只有 @。 */
        val atNameMapGetter: HookPoint? = null,
        val memberNameGetter: HookPoint? = null,
        val memberMidGetter: HookPoint? = null,
        val memberV2NameGetter: HookPoint? = null,
        val memberV2MidGetter: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("lists", JSONArray().apply { replyListGetters.forEach { put(it.toJson()) } })
            contentGetter?.let { put("content", it.toJson()) }
            messageGetter?.let { put("message", it.toJson()) }
            memberGetter?.let { put("member", it.toJson()) }
            levelGetter?.let { put("level", it.toJson()) }
            memberV2Getter?.let { put("member_v2", it.toJson()) }
            memberV2BasicGetter?.let { put("member_v2_basic", it.toJson()) }
            memberV2LevelGetter?.let { put("member_v2_level", it.toJson()) }
            put("top_replies", JSONArray().apply { topReplyGetters.forEach { put(it.toJson()) } })
            replyDefaultInstanceGetter?.let { put("reply_default", it.toJson()) }
            atNameCountGetter?.let { put("at_count", it.toJson()) }
            atNameMapGetter?.let { put("at_map", it.toJson()) }
            memberNameGetter?.let { put("member_name", it.toJson()) }
            memberMidGetter?.let { put("member_mid", it.toJson()) }
            memberV2NameGetter?.let { put("member_v2_name", it.toJson()) }
            memberV2MidGetter?.let { put("member_v2_mid", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): CommentFilterPoints = CommentFilterPoints(
                replyListGetters = o.getJSONArray("lists").let { values ->
                    (0 until values.length()).map { HookPoint.fromJson(values.getJSONObject(it)) }
                },
                contentGetter = o.optJSONObject("content")?.let(HookPoint::fromJson),
                messageGetter = o.optJSONObject("message")?.let(HookPoint::fromJson),
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
                    ?.let(HookPoint::fromJson),
                atNameCountGetter = o.optJSONObject("at_count")?.let(HookPoint::fromJson),
                atNameMapGetter = o.optJSONObject("at_map")?.let(HookPoint::fromJson),
                memberNameGetter = o.optJSONObject("member_name")?.let(HookPoint::fromJson),
                memberMidGetter = o.optJSONObject("member_mid")?.let(HookPoint::fromJson),
                memberV2NameGetter = o.optJSONObject("member_v2_name")?.let(HookPoint::fromJson),
                memberV2MidGetter = o.optJSONObject("member_v2_mid")?.let(HookPoint::fromJson)
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

    /** Compose 评论更多菜单（气泡／分组 Sheet／旧版纵向行）的可选后台兜底定位点。 */
    data class CommentComposeMenuPoints(
        val bubbleRow: HookPoint? = null,
        val groupedRow: HookPoint? = null,
        val groupedItem: HookPoint? = null,
        val legacyRow: HookPoint? = null
    ) {
        fun isEmpty(): Boolean =
            bubbleRow == null && groupedRow == null && groupedItem == null && legacyRow == null

        fun isStructurallyValid(): Boolean {
            fun HookPoint?.validShape(expectedParams: IntRange): Boolean =
                this == null || (className.isNotBlank() && methodName.isNotBlank() &&
                    viewField == null && paramClassNames?.all { it.isNotBlank() } == true &&
                    paramClassNames.size in expectedParams)
            return bubbleRow.validShape(4..4) &&
                groupedRow.validShape(7..7) &&
                groupedItem.validShape(6..6) &&
                legacyRow.validShape(8..9)
        }

        fun toJson(): JSONObject = JSONObject().apply {
            bubbleRow?.let { put("bubble", it.toJson()) }
            groupedRow?.let { put("grouped", it.toJson()) }
            groupedItem?.let { put("grouped_item", it.toJson()) }
            legacyRow?.let { put("legacy", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): CommentComposeMenuPoints? = CommentComposeMenuPoints(
                bubbleRow = o.optJSONObject("bubble")?.let(HookPoint::fromJson),
                groupedRow = o.optJSONObject("grouped")?.let(HookPoint::fromJson),
                groupedItem = o.optJSONObject("grouped_item")?.let(HookPoint::fromJson),
                legacyRow = o.optJSONObject("legacy")?.let(HookPoint::fromJson)
            ).takeIf { !it.isEmpty() && it.isStructurallyValid() }
        }
    }

    /** 播放器默认画质计算边界；保存无参 Int 默认入口、Story 角色与 9.15+ 自动画质开关。 */
    data class PlayerQualityPoints(
        val defaultQualityMethod: HookPoint?,
        /** 只读结构证据，不注册额外播放响应 Hook。 */
        val capabilitySignals: List<String> = emptyList(),
        val story: StoryQualityPoints? = null,
        val normalAutoSwitch: HookPoint? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            defaultQualityMethod?.let { put("default", it.toJson()) }
            put("capabilities", JSONArray(capabilitySignals))
            story?.let { put("story", it.toJson()) }
            normalAutoSwitch?.let { put("normal_auto", it.toJson()) }
        }

        companion object {
            fun fromJson(o: JSONObject): PlayerQualityPoints = PlayerQualityPoints(
                defaultQualityMethod = o.optJSONObject("default")?.let(HookPoint::fromJson),
                capabilitySignals = o.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).map(values::getString)
                }.orEmpty(),
                story = o.optJSONObject("story")?.let(StoryQualityPoints::fromJson),
                normalAutoSwitch = o.optJSONObject("normal_auto")?.let(HookPoint::fromJson)
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
        val quickReplyDialogMethods: List<HookPoint> = emptyList(),
        /**
         * `reply.v1.Url#getAppUrlSchema`：搜索链接的**第二道防线**。
         *
         * 与 [urlMapGetters] 各自独立：前者摘掉整条 map 条目（连搜索小图标一起没有），
         * 后者把跳转目标本身置空。渲染侧只要还想跳转，就绕不开这个字段——
         * 31 个本地宿主（8.84.0–9.12.0）逐版实测它**每一版都有跨 dex 消费者**，
         * 不是空 Hook。两道必须互不知情，见 AGENTS 的纵深防御条目。
         */
        val urlSchemaGetters: List<HookPoint> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("urls", JSONArray().apply { urlMapGetters.forEach { put(it.toJson()) } })
            put("url_schemas", JSONArray().apply { urlSchemaGetters.forEach { put(it.toJson()) } })
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
                    }.orEmpty(),
                    // 老缓存没有这个键：降级成空列表，第二道防线这次不装，
                    // 由 RULE_VERSION 抬升保证下次启动会重定位补上。
                    o.optJSONArray("url_schemas")?.let { methods ->
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
        /** 播放器投票/关注/契约卡/指令弹幕的 protobuf 读边界。 */
        val playerInteractiveOverlays: PlayerInteractiveOverlayPoints? = null,
        /** 影视页活动响应的自动半屏构造边界。 */
        val pgcAutoActivityPopup: PgcAutoActivityPopupPoints? = null,
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
        val diagnostics: List<AdaptDiagnostic>,
        /** 宿主全部 classes*.dex 的中央目录内容指纹；不包含安装路径。 */
        val dexSourceFingerprint: String = DEX_SOURCE_UNAVAILABLE,
        val sponsorPlayer: SponsorPlayerClasses? = null,
        val playerSpeedSession: PlayerSpeedSessionClasses? = null,
        val playerSpeedCalculator: LongPressCalculatorPoint? = null,
        val weChatShare: WeChatShareClasses? = null,
        val commentClassicStyle: CommentClassicStylePoints? = null,
        val playerPopupPromotion: PlayerPopupPromotionPoints? = null,
        val brandSplashDecoder: HookPoint? = null,
        val followFeedStyle: FollowFeedPoints? = null,
        /** Ephemeral startup projection, excluded from all persisted cache schemas. */
        val startupExcluded: Set<String> = emptySet(),
        val playerSpeedMenu: PlayerSpeedMenuPoints? = null,
        val pageBoundaries: PageBoundaryPoints? = null,
        val homeChrome: Map<String, HookPoint> = emptyMap(),
        val homeChromeSemantics: HomeChromeSemanticsPoint? = null,
        val systemEnvironment: SystemEnvironmentPoints? = null,
        val commentComposeMenu: CommentComposeMenuPoints? = null,
        val kmpGrpcWireEngine: HookPoint? = null,
        val brandSplashKntrDraw: String? = null,
        val brandSplashKntrExit: String? = null,
        val brandSplashKntrPage: HookPoint? = null,
        val cloudKeyFreezeContract: String? = null,
        val privacyIgnetHttp: HookPoint? = null,
        val privacyKNeuron: String? = null,
        val guestPlayerCore: String? = null,
        val detailSpecialTagMapper: HookPoint? = null,
        val aiPlaylistDirector: String? = null,
        val followUpRecommendBodies: Map<String, String> = emptyMap(),
        val detailRelateGameComponent: String? = null,
        val commentReplyButtonOwners: Map<String, String> = emptyMap(),
        val homeRecommendEarlyLoadListener: String? = null,
        val legacyStoryDanmakuBind: HookPoint? = null,
        val playerSpeedTheseus2LongPress: Theseus2LongPressPoint? = null,
        /**
         * 同步定位家族的覆盖范围与尝试状态；`null` = 旧缓存（没有 `cov` 键），覆盖范围未知，**不等于**覆盖完整。
         * 只描述「这一轮尝试过什么」，不描述数据是否有效：被计划裁剪的家族即使带着此前缓存的点，也要看这里。
         */
        val coverage: AdaptCoverage? = null,
        /** Phase 2 按功能域拆分的规则版本映射（缺失时按各域基线 0 对待，与旧缓存向后兼容）。 */
        val domainRuleVersions: Map<String, Int> = emptyMap()
    ) {
        /** 按诊断 ID 的 O(1) 惰性只读索引（重复 ID 时与历史 `lastOrNull` 语义一致）。 */
        val diagnosticsById: Map<String, AdaptDiagnostic> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            if (diagnostics.isEmpty()) emptyMap() else diagnostics.associateBy(AdaptDiagnostic::id)
        }

        /** 该家族此刻是否带有定位结果（暂停页含四个子点，任一有值即算）。 */
        internal fun hasPoint(family: PointFamily): Boolean = when (family) {
            PointFamily.COMMENT_LOW -> commentLow != null
            PointFamily.COMMENT_HIGH -> commentHigh != null
            PointFamily.MINE_ENTRY -> mineEntry != null
            PointFamily.PAUSE -> !pause.isEmpty()
            PointFamily.BANNER -> banner != null
            PointFamily.HOME_TOP_BAR -> homeTopBar != null
            PointFamily.MINE_VIP -> mineVip != null
            PointFamily.BLOCK_UPDATE -> blockUpdate != null
            PointFamily.DYNAMIC_TABS -> dynamicTabs != null
            PointFamily.FULL_NUMBERS -> fullNumbers != null
            PointFamily.PLAYER_PORTRAIT -> playerPortrait != null
            PointFamily.PLAYER_INTERACTIVE_OVERLAYS -> playerInteractiveOverlays != null
            PointFamily.PGC_AUTO_ACTIVITY_POPUP -> pgcAutoActivityPopup != null
            PointFamily.PLAYER_STATUS_BAR -> playerStatusBar != null
            PointFamily.HOME_RECOMMEND_FEED -> homeRecommendFeed != null
            PointFamily.VIDEO_RELATE -> videoRelate != null
            PointFamily.HOME_TABS -> homeTabs != null
            PointFamily.HOME_COMPONENTS -> homeComponents != null
            PointFamily.MINE_COMPONENTS -> mineComponents != null
            PointFamily.MINE_ACCOUNT_MINE -> mineAccountMine != null
            PointFamily.STORY_FEED -> storyFeed != null
            PointFamily.BOTTOM_BAR -> bottomBar != null
            PointFamily.PLAYER_QUALITY -> playerQuality != null
            PointFamily.TEENAGERS_MODE -> teenagersMode != null
            PointFamily.COMMENT_PURIFY -> commentPurify != null
            PointFamily.COMMENT_FILTER -> commentFilter != null
            PointFamily.COMMENT_TOPOLOGY -> commentTopology != null
            PointFamily.COMMENT_SECTION -> commentSection != null
            PointFamily.SPLASH_ADS -> splashAds != null
        }

        fun toJson(): JSONObject = AdaptResultCodec.toJson(this)

        fun isUsableWith(expectedFingerprint: String): Boolean =
            AdaptResultValidator.isUsableWith(this, expectedFingerprint)

        fun diagnosticSummary(): String = diagnostics
            .groupingBy { it.state }
            .eachCount()
            .let { counts ->
                AdaptState.entries.filter { it != AdaptState.NOT_REQUESTED || (counts[it] ?: 0) > 0 }.joinToString(",") { state ->
                    "${state.name.lowercase()}=${counts[state] ?: 0}"
                }
            }

        private fun isStructurallyValid(): Boolean =
            AdaptResultValidator.isStructurallyValid(this)

        companion object {
            fun fromJson(o: JSONObject): AdaptResult? = AdaptResultCodec.fromJson(o)
        }
    }

    /**
     * 播放器互动层的 `clear*` 白名单唯一来源。安装器与策略层都从这里读，禁止再复制一份，
     * 否则"golden test 锁着一份、运行时用另一份"的假保障会重新出现（2026-09-04 补记）。
     */
    internal const val PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR =
        AdaptResultValidator.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR

    /**
     * 一个互动层协议家族：Moss / Reply / Guide 三个稳定类名，外加**显式**的 `clear*` 名单。
     * 名单跟着家族写死，不按包名子串推断——`.viewunite.` 这类子串判断会在同名子包上误选。
     */
    internal data class PlayerInteractiveFamilySpec(
        val mossClassName: String,
        val replyClassName: String,
        val guideClassName: String,
        val clearNames: List<String>,
        /**
         * 同一族互动层的**第二个载体**：`ViewProgressReply.dm`（`DmResource`）。
         * 只有 viewunite 有这个字段，`view.v1.ViewProgressReply` 没有。
         * 它装的是关注引导 / 卡片 / 指令弹幕，与 `VideoGuide` 目标一致，不含弹幕流本身。
         * 2026-09-04 对照哔哩漫游 `remove_video_cmd_dms` 时发现我们整族漏了这一路。
         */
        val dmClassName: String? = null,
        val dmClearNames: List<String> = emptyList(),
        val diagnosticFamilyId: String = "unknown"
    )

    internal val PLAYER_INTERACTIVE_MOSS_FAMILIES = MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_MOSS_FAMILIES
    internal const val PLAYER_INTERACTIVE_DM_MOSS_CLASS = MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_DM_MOSS_CLASS
    internal const val PLAYER_INTERACTIVE_DM_REPLY_CLASS = MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_DM_REPLY_CLASS
    internal const val PLAYER_INTERACTIVE_DM_COMMAND_CLASS = MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_DM_COMMAND_CLASS
    internal const val PLAYER_INTERACTIVE_DM_REPLY_CLEAR = MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_DM_REPLY_CLEAR
    internal const val PLAYER_INTERACTIVE_DM_ACTIVITY_META_CLEAR =
        MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_DM_ACTIVITY_META_CLEAR

    internal fun PlayerQualityPoints.usesLegacyQualityHelper(): Boolean =
        MediaQualityAndPlayerLocator.usesLegacyQualityHelper(this)

    /** 当前 B 站 versionCode（读自身包信息，隔离环境对自身可见） */
    fun biliVersionCode(context: Context): Int = AdaptCacheStore.biliVersionCode(context)

    internal data class HostIdentity(val versionCode: Int, val fingerprint: String)

    internal data class SplitFileMeta(val name: String, val length: Long, val lastModified: Long)

    internal data class ParsedHostFingerprint(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val baseLength: Long,
        val baseLastModified: Long,
        val splitsHash: String?,
        val ruleVersion: Int
    )

    internal fun buildHostFingerprint(
        versionCode: Long,
        versionName: String,
        baseLength: Long,
        baseLastModified: Long,
        splits: Collection<SplitFileMeta> = emptyList(),
        ruleVersion: Int = ADAPTER_RULE_VERSION
    ): String = ProtocolFingerprintBuilder.buildHostFingerprint(
        versionCode = versionCode,
        versionName = versionName,
        baseLength = baseLength,
        baseLastModified = baseLastModified,
        splits = splits,
        ruleVersion = ruleVersion
    )

    internal fun parseHostFingerprint(fingerprint: String): ParsedHostFingerprint? =
        ProtocolFingerprintBuilder.parseHostFingerprint(fingerprint)

    /** 启动时对适配缓存的处置：沿用 / 静默补适配（新增计划家族或局部域规则升级） / 完整适配。 */
    internal enum class AdaptDecision { REUSE, SUPPLEMENT_SILENTLY, ADAPT }

    internal fun adaptDecision(
        usable: AdaptResult?,
        refreshRequested: Boolean,
        coverageMissing: Set<PointFamily>,
        staleDomains: Set<AdaptDomain> = emptySet()
    ): AdaptDecision = AdaptResultMerger.adaptDecision(
        usable = usable,
        refreshRequested = refreshRequested,
        coverageMissing = coverageMissing,
        staleDomains = staleDomains
    )

    internal fun verifyProtocolFingerprint(loader: ClassLoader, previous: AdaptResult): String? =
        ProtocolFingerprintBuilder.verifyProtocolFingerprint(loader, previous)

    /** Only called on the adaptation worker after verifying both DEX source and live protocol structure; never an admission cache. */
    internal fun rebindRepackedCache(
        previous: AdaptResult,
        identity: HostIdentity,
        resetTimestamp: Long,
        actualDex: String?,
        actualProtocol: String?
    ): AdaptResult? = ProtocolFingerprintBuilder.rebindRepackedCache(
        previous = previous,
        identity = identity,
        resetTimestamp = resetTimestamp,
        actualDex = actualDex,
        actualProtocol = actualProtocol
    )

    /** 只在同一次 attach 安装栈内复用；不持有 Context，不作为进程级永久缓存。 */
    internal data class StartupCacheSnapshot(
        val identity: HostIdentity,
        val resetTimestamp: Long,
        val cached: AdaptResult?
    ) {
        /** 身份、手动重置、宿主指纹和结构都有效的缓存；不看覆盖范围，其它功能的补适配历史也从这里取。 */
        fun usableCache(highCandidateExists: Boolean): AdaptResult? = cached?.takeIf {
            it.ts >= resetTimestamp && it.biliVersionCode == identity.versionCode &&
                it.isUsableWith(identity.fingerprint) &&
                (it.commentHigh != null || !highCandidateExists)
        }

        /**
         * 缓存有效，且尝试过 [plan] 要求的每个家族才算「按当前计划完整」。
         * 局部缓存（上次计划更小）或旧缓存（没有覆盖范围）返回 null，由调用方决定补适配。
         */
        fun usableCache(highCandidateExists: Boolean, plan: AdaptPlan): AdaptResult? =
            usableCache(highCandidateExists)?.takeIf { AdaptPlanner.missing(it.coverage, plan).isEmpty() }

        /** 有效缓存里计划要求、却从未尝试过的家族；缓存本身无效时返回 null（整轮重新适配，不是补扫）。 */
        fun missingCoverage(highCandidateExists: Boolean, plan: AdaptPlan): Set<PointFamily>? =
            usableCache(highCandidateExists)?.let { AdaptPlanner.missing(it.coverage, plan) }

        /** 经过身份/重置/结构校验后，按域规则版本剔除过期域，再查询指定后台诊断 ID 是否在本宿主尝试过。 */
        internal fun isDiagnosticAttempted(id: String): Boolean {
            val value = usableCache(highCandidateExists = false) ?: return false
            val stale = AdaptDomainCatalog.staleDomains(value.domainRuleVersions)
            val active = if (stale.isEmpty()) value else value.withoutDomains(stale)
            return active.diagnosticsById[id]?.let(AdaptAssistGate::isAttemptedInCache) == true
        }
    }

    @Suppress("DEPRECATION")
    private fun readHostIdentity(context: Context): HostIdentity =
        ProtocolFingerprintBuilder.readHostIdentity(context)

    private fun buildHostFingerprint(context: Context): String =
        ProtocolFingerprintBuilder.buildHostFingerprint(context)

    internal fun readStartupCache(context: Context, resetTimestamp: Long): StartupCacheSnapshot =
        AdaptCacheStore.readStartupCache(context, resetTimestamp)

    fun cacheStatus(): String = AdaptCacheStore.cacheStatus()

    /**
     * 读缓存适配结果（二级文件缓存优先；手动重置标记/版本不符返回 null）。
     * [resetTimestamp] 已由 API 82 或 NPatch 启动配置完成跨进程校验，适配器不再自行读取
     * Yuki prefs，以免不可读文件把明确的重置请求静默变成 0。
     */
    fun loadCached(context: Context?, resetTimestamp: Long): AdaptResult? =
        AdaptCacheStore.loadCached(context, resetTimestamp)

    /**
     * 以实时结构定位为主，只用已经过宿主指纹校验的缓存补齐缺失点。
     *
     * 该合并使后台 DexMeguviz 的结果能在下一次冷启动参与 Hook 注册，同时禁止缓存覆盖当前
     * ClassLoader 已确认的点。调用方必须给 [loadCached] 传入宿主 Context 后再传到这里。
     */
    internal fun mergeRuntimeWithCached(
        runtime: AdaptResult?,
        cached: AdaptResult?
    ): AdaptResult? = AdaptResultMerger.mergeRuntimeWithCached(runtime, cached)

    internal fun AdaptResult.applyStartupExclusions(): AdaptResult =
        AdaptResultMerger.applyStartupExclusions(this)

    internal fun mergePlayerQuality(live: PlayerQualityPoints?, cached: PlayerQualityPoints?): PlayerQualityPoints? =
        AdaptResultMerger.mergePlayerQuality(live, cached)

    internal fun mergeCommentPurify(live: CommentPurifyPoints?, cached: CommentPurifyPoints?): CommentPurifyPoints? =
        AdaptResultMerger.mergeCommentPurify(live, cached)

    /** 缓存是否覆盖当前版本 */
    fun isCached(context: Context, resetTimestamp: Long): Boolean =
        AdaptCacheStore.isCached(context, resetTimestamp)

    /**
     * 手动重适配：写重置标记 + 清除记录（B 站下次启动即重新定位）。
     * reset_ts 写入模块私有权威设置后，由 RemoteHookConfigStore 发布到 API 102 配置组。
     */
    fun clearCache(context: Context, modulePrefs: SharedPreferences?) =
        AdaptCacheStore.clearCache(context, modulePrefs)

    /**
     * 后台校验缓存里的 DEX 内容指纹是否仍与宿主一致。
     *
     * **为什么不放进快路径**：[ensureAdapted] 在 `Application.attach` 内同步执行，指纹需要
     * 打开每个代码 APK 的 ZIP 中央目录，属于 I/O；快路径必须保持零开销，因此比对下沉到
     * 守护线程。
     *
     * **为什么只作废不重适配**：本进程的 Hook 已按旧缓存安装完毕，就地重定位无法回填已装
     * 的 Hook，中途替换只会制造「一半新一半旧」的不一致状态。作废缓存后由下次启动重新
     * 定位，与 [clearCache] 的既有语义一致。
     *
     * **为什么读不出来时保留缓存**：一次 I/O 失败不足以推翻一份正在正常工作的适配结果，
     * 失败放行优于误删。
     *
     * 这条通道同时校验缓存 DEX 内容摘要与宿主实际 DEX 是否一致。
     * 重打包或包结构变动导致启动身份变化时，另在适配工作线程按内容核对并重绑定缓存，
     * 不能声称本审计会恢复已被启动身份拒绝的缓存。
     */
    @androidx.annotation.WorkerThread
    private fun inspectHostDexSource(context: Context): DexSourceFingerprint.Result? = runCatching {
        val info = context.packageManager.getPackageInfo("tv.danmaku.bili", 0).applicationInfo
        val patched = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
            .read(context, "tv.danmaku.bili").markerPresent ||
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles.read(context, "tv.danmaku.bili").modern
        DexSourceFingerprint.inspect(info, File(context.codeCacheDir, "bil-host-dex"), materializeArchiveView = patched)
    }.getOrNull()

    private fun auditDexSourceAsync(context: Context, cached: AdaptResult) {
        val expected = cached.dexSourceFingerprint
        if (!dexAuditRunning.compareAndSet(false, true)) return
        val worker = Thread({
            try {
                // 线程体必须过防波堤：这是宿主进程内的线程，Android 对**任何**线程的
                // 逃逸异常都按杀进程处理，原先 try/finally 无 catch 会直接带走宿主。
                HostThreadGuard.run("adapter.dex_audit") {
                    // prefs 命中但文件缺失时补写；exists 和原子写入也属于 I/O，不能留在 attach。
                    runCatching {
                        if (!AdaptCacheStore.cacheFile().exists()) {
                            AdaptCacheStore.writeCache(cached)
                        }
                    }.onFailure { ModernHookLog.error("[BIL] 后台适配缓存维护失败", it) }
                    if (expected == DEX_SOURCE_UNAVAILABLE) return@run
                    val actual = inspectHostDexSource(context)?.value
                    when (actual) {
                        null -> ModernHookLog.info("[BIL] DEX 指纹不可读，保留现有适配缓存")
                        expected -> Unit
                        else -> {
                            ModernHookLog.info(
                                "[BIL] 宿主 DEX 内容已变化，作废适配缓存 " +
                                    "expected=$expected actual=$actual"
                            )
                            clearCache(context, modulePrefs = null)
                        }
                    }
                }
            } finally {
                dexAuditRunning.set(false)
            }
        }, "BIL-DexAudit").apply { isDaemon = true }
        runCatching { worker.start() }.onFailure { throwable ->
            dexAuditRunning.set(false)
            ModernHookLog.error("[BIL] DEX 缓存审计线程启动失败", throwable)
        }
    }

    /**
     * 判断并执行适配。在 B 站启动（loadApp 完成注册后）调用：
     * - 缓存命中且必要入口完整 → 复用快路径，仍核对宿主指纹和各项补适配条件
     * - 需要适配 → 主线程 toast + 后台线程定位 + 写缓存
     */
    fun ensureAdapted(
        context: Context,
        classLoader: ClassLoader,
        resetTimestamp: Long,
        callback: AdaptCallback?,
        kotlinDefaultWordsEnabled: Boolean = false,
        sponsorBlockEnabled: Boolean = false,
        commentClassicStyleEnabled: Boolean = false,
        playerPopupPromotionEnabled: Boolean = false,
        brandSplashCustomEnabled: Boolean = false,
        followFeedStyleEnabled: Boolean = false,
        playerQualityEnabled: Boolean = false,
        playerSpeedMenuEnabled: Boolean = false,
        playerSpeedSessionEnabled: Boolean = false,
        playerSpeedCalculatorEnabled: Boolean = false,
        weChatShareEnabled: Boolean = false,
        searchHomeRecommendEnabled: Boolean = false,
        playerEndPageRecommendEnabled: Boolean = false,
        homeChromeEnabled: Boolean = false,
        homeChromeIconOnly: Boolean = false,
        homeTopTabCenteringEnabled: Boolean = false,
        systemEnvironmentEnabled: Boolean = false,
        blockUpdateEnabled: Boolean = false,
        commentTopologyEnabled: Boolean = false,
        kmpGrpcWireEnabled: Boolean = false,
        cloudKeyFreezeEnabled: Boolean = false,
        incognitoPrivacyEnabled: Boolean = false,
        detailSpecialTagEnabled: Boolean = false,
        commentReplyButtonEnabled: Boolean = false,
        guestBrowsingEnabled: Boolean = false,
        homeRecommendEarlyLoadEnabled: Boolean = false,
        commentQuickReplyEnabled: Boolean = false,
        pausedAdEnabled: Boolean = false,
        brandSplashSkipEnabled: Boolean = false,
        aiDeclaredVideoEnabled: Boolean = false,
        followUpRecommendEnabled: Boolean = false,
        detailRelateGameEnabled: Boolean = false,
        storyDanmakuToggleHidden: Boolean = false,
        playerSpeedLongPressEnabled: Boolean = false,
        splashAutoNightEnabled: Boolean = false
    ) = ensureAdapted(
        context, classLoader, resetTimestamp, callback, readStartupCache(context, resetTimestamp), kotlinDefaultWordsEnabled, sponsorBlockEnabled, commentClassicStyleEnabled, playerPopupPromotionEnabled, brandSplashCustomEnabled, followFeedStyleEnabled, playerQualityEnabled, playerSpeedMenuEnabled, playerSpeedSessionEnabled, playerSpeedCalculatorEnabled, weChatShareEnabled, searchHomeRecommendEnabled, playerEndPageRecommendEnabled, homeChromeEnabled, homeChromeIconOnly, homeTopTabCenteringEnabled, systemEnvironmentEnabled, blockUpdateEnabled, commentTopologyEnabled, kmpGrpcWireEnabled, cloudKeyFreezeEnabled, incognitoPrivacyEnabled, detailSpecialTagEnabled, commentReplyButtonEnabled, guestBrowsingEnabled, homeRecommendEarlyLoadEnabled, commentQuickReplyEnabled, pausedAdEnabled, brandSplashSkipEnabled, aiDeclaredVideoEnabled, followUpRecommendEnabled, detailRelateGameEnabled, storyDanmakuToggleHidden, playerSpeedLongPressEnabled, splashAutoNightEnabled
    )

    internal fun ensureAdapted(
        context: Context,
        classLoader: ClassLoader,
        resetTimestamp: Long,
        callback: AdaptCallback?,
        startupCache: StartupCacheSnapshot,
        kotlinDefaultWordsEnabled: Boolean = false,
        sponsorBlockEnabled: Boolean = false,
        commentClassicStyleEnabled: Boolean = false,
        playerPopupPromotionEnabled: Boolean = false,
        brandSplashCustomEnabled: Boolean = false,
        followFeedStyleEnabled: Boolean = false,
        playerQualityEnabled: Boolean = false,
        playerSpeedMenuEnabled: Boolean = false,
        playerSpeedSessionEnabled: Boolean = false,
        playerSpeedCalculatorEnabled: Boolean = false,
        weChatShareEnabled: Boolean = false,
        searchHomeRecommendEnabled: Boolean = false,
        playerEndPageRecommendEnabled: Boolean = false,
        homeChromeEnabled: Boolean = false,
        homeChromeIconOnly: Boolean = false,
        homeTopTabCenteringEnabled: Boolean = false,
        systemEnvironmentEnabled: Boolean = false,
        blockUpdateEnabled: Boolean = false,
        commentTopologyEnabled: Boolean = false,
        kmpGrpcWireEnabled: Boolean = false,
        cloudKeyFreezeEnabled: Boolean = false,
        incognitoPrivacyEnabled: Boolean = false,
        detailSpecialTagEnabled: Boolean = false,
        commentReplyButtonEnabled: Boolean = false,
        guestBrowsingEnabled: Boolean = false,
        homeRecommendEarlyLoadEnabled: Boolean = false,
        commentQuickReplyEnabled: Boolean = false,
        pausedAdEnabled: Boolean = false,
        brandSplashSkipEnabled: Boolean = false,
        aiDeclaredVideoEnabled: Boolean = false,
        followUpRecommendEnabled: Boolean = false,
        detailRelateGameEnabled: Boolean = false,
        storyDanmakuToggleHidden: Boolean = false,
        playerSpeedLongPressEnabled: Boolean = false,
        splashAutoNightEnabled: Boolean = false,
        /** 安装链从已接受的配置快照生成的定位计划；缺省 = 全量（原始行为）。 */
        plan: AdaptPlan = AdaptPlan.full()
    ) {
        // 带 DEX 兜底的三类家族，其显式开关本身就是需求：计划与开关不一致时以多要为准（失效安全）。
        val adaptPlan = plan.including(buildSet {
            if (blockUpdateEnabled) add(PointFamily.BLOCK_UPDATE)
            if (playerQualityEnabled) add(PointFamily.PLAYER_QUALITY)
            if (commentTopologyEnabled) add(PointFamily.COMMENT_TOPOLOGY)
            if (commentQuickReplyEnabled) add(PointFamily.COMMENT_PURIFY)
            if (pausedAdEnabled) add(PointFamily.PAUSE)
        })
        val featureFlags = DexFeatureFlags(
            kotlinDefaultWordsEnabled = kotlinDefaultWordsEnabled,
            sponsorBlockEnabled = sponsorBlockEnabled,
            commentClassicStyleEnabled = commentClassicStyleEnabled,
            playerPopupPromotionEnabled = playerPopupPromotionEnabled,
            brandSplashCustomEnabled = brandSplashCustomEnabled,
            followFeedStyleEnabled = followFeedStyleEnabled,
            playerQualityEnabled = playerQualityEnabled,
            playerSpeedMenuEnabled = playerSpeedMenuEnabled,
            playerSpeedSessionEnabled = playerSpeedSessionEnabled,
            playerSpeedCalculatorEnabled = playerSpeedCalculatorEnabled,
            weChatShareEnabled = weChatShareEnabled,
            searchHomeRecommendEnabled = searchHomeRecommendEnabled,
            playerEndPageRecommendEnabled = playerEndPageRecommendEnabled,
            homeChromeEnabled = homeChromeEnabled,
            homeChromeIconOnly = homeChromeIconOnly,
            homeTopTabCenteringEnabled = homeTopTabCenteringEnabled,
            systemEnvironmentEnabled = systemEnvironmentEnabled,
            blockUpdateEnabled = blockUpdateEnabled,
            commentTopologyEnabled = commentTopologyEnabled,
            kmpGrpcWireEnabled = kmpGrpcWireEnabled,
            cloudKeyFreezeEnabled = cloudKeyFreezeEnabled,
            incognitoPrivacyEnabled = incognitoPrivacyEnabled,
            detailSpecialTagEnabled = detailSpecialTagEnabled,
            commentReplyButtonEnabled = commentReplyButtonEnabled,
            guestBrowsingEnabled = guestBrowsingEnabled,
            homeRecommendEarlyLoadEnabled = homeRecommendEarlyLoadEnabled,
            commentQuickReplyEnabled = commentQuickReplyEnabled,
            pausedAdEnabled = pausedAdEnabled,
            brandSplashSkipEnabled = brandSplashSkipEnabled,
            aiDeclaredVideoEnabled = aiDeclaredVideoEnabled,
            followUpRecommendEnabled = followUpRecommendEnabled,
            detailRelateGameEnabled = detailRelateGameEnabled,
            storyDanmakuToggleHidden = storyDanmakuToggleHidden,
            playerSpeedLongPressEnabled = playerSpeedLongPressEnabled,
            splashAutoNightEnabled = splashAutoNightEnabled,
            homeTabsRequested = adaptPlan.needs(PointFamily.HOME_TABS)
        )
        // reset 不同意味着不是同一次安装快照，必须重新读取而不能复用已缓存的 null/旧结果。
        val startup = if (startupCache.resetTimestamp == resetTimestamp.coerceAtLeast(0L)) {
            startupCache
        } else {
            readStartupCache(context, resetTimestamp)
        }
        val vc = startup.identity.versionCode
        // 快路径有效性：版本匹配 且（high 已定位 或 当前版本无 high 候选类）。
        // 防止旧缓存（sv 同但 high 缺失——如 8.63.0 早期 low-only 结果）被快路径
        // 复用而跳过重定位（曾有 01:04 prefs 旧结果导致 9.8.0 一直 low-only 的回归）。
        val highCandidateExists = CommentLocator.COMMENT_HIGH_CANDIDATES.any {
            KavaMemberLookup.hasClass(classLoader, it)
        }
        // 先保留同指纹/版本/reset 的有效快照；按域版本剔除失效域，其余域在增量补适配时继续沿用。
        val usable = startup.usableCache(highCandidateExists)
        val staleDomains = usable?.let { AdaptDomainCatalog.staleDomains(it.domainRuleVersions) }.orEmpty()
        val effectiveUsable = usable?.let { if (staleDomains.isEmpty()) it else it.withoutDomains(staleDomains) }
        // 评论 high 点缺失只影响整轮快路径，不能使播放器已经完成的兜底每次启动重扫。
        val rawPopupHistory = startup.usableCache(highCandidateExists = false)
        val popupHistory = rawPopupHistory?.let {
            val stale = AdaptDomainCatalog.staleDomains(it.domainRuleVersions)
            if (stale.isEmpty()) it else it.withoutDomains(stale)
        }
        // 有效缓存里计划要求、却从未尝试过的家族（旧缓存没有覆盖范围 = 全部待补）。
        val coverageMissing = if (effectiveUsable != null) {
            AdaptPlanner.missing(effectiveUsable.coverage, adaptPlan)
        } else {
            emptySet()
        }
        fun runAdaptWithHistory(
            history: AdaptResult?,
            dexSource: DexSourceFingerprint.Result?
        ): AdaptResult? {
            val prev = HistorySnapshot.of(history)
            val historyDiagnosticsById = history?.diagnosticsById.orEmpty()
            fun wasConclusiveMissing(id: String): Boolean =
                historyDiagnosticsById[id]?.let(AdaptAssistGate::isConclusiveMissing) == true
            val semanticsAttempted = wasConclusiveMissing(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC)
            val chromeAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
                wasConclusiveMissing(HomeChromeAdapter.diagnostic(query))
            }
            val pageAttempted = PageBoundaryAdapter.queries.filterTo(linkedSetOf()) { query ->
                historyDiagnosticsById[PageBoundaryAdapter.diagnostic(query)]?.let {
                    it.state == AdaptState.FOUND || AdaptAssistGate.isConclusiveMissing(it)
                } == true
            }
            val speedMenuAttempted = historyDiagnosticsById[PlayerSpeedMenuAdapter.DIAGNOSTIC]?.let {
                it.state == AdaptState.FOUND || AdaptAssistGate.isConclusiveMissing(it)
            } == true
            val speedSessionAttempted = wasConclusiveMissing(PlayerSpeedSessionLocator.DIAGNOSTIC)
            val speedCalculatorAttempted = wasConclusiveMissing(PlayerSpeedLocator.CALCULATOR_DIAGNOSTIC)
            val weChatShareAttempted = wasConclusiveMissing(WeChatShareCompatAccess.DIAGNOSTIC)
            val previouslyMissingStoryQuality = wasConclusiveMissing(StoryQualityLocator.DIAGNOSTIC)
            val previouslyMissingFollow = wasConclusiveMissing(FollowFeedAdapter.DIAGNOSTIC)
            val previouslyMissingBrand = wasConclusiveMissing(BrandSplashDecoderAdapter.DIAGNOSTIC)
            val previouslyMissingBrandKntr = wasConclusiveMissing(BrandSplashKntrAdapter.DIAGNOSTIC)
            val previouslyMissingBrandKntrExit = wasConclusiveMissing(BrandSplashKntrAdapter.DIAGNOSTIC_EXIT)
            val previouslyMissingBrandKntrPage = wasConclusiveMissing(BrandSplashKntrAdapter.DIAGNOSTIC_PAGE)
            val previouslyMissingCommentQuickReply = wasConclusiveMissing(CommentQuickReplyAdapter.DIAGNOSTIC)
            val previouslyMissingPauseCountdown = wasConclusiveMissing(PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN)
            val previouslyMissingPauseApiEntry = wasConclusiveMissing(PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API)
            val previouslyMissingHomeTabResource = wasConclusiveMissing(HomeTabResourceAdapter.DIAGNOSTIC)
            val previouslyMissingCloudKeyContract = wasConclusiveMissing(CloudKeyFreezeAdapter.DIAGNOSTIC)
            val previouslyMissingPrivacyIgnet = wasConclusiveMissing(PrivacyChannelAdapter.DIAGNOSTIC_IGNET)
            val previouslyMissingPrivacyKNeuron = wasConclusiveMissing(PrivacyChannelAdapter.DIAGNOSTIC_KNEURON)
            val previouslyMissingGuestPlayerCore = wasConclusiveMissing(GuestPlayerCoreAdapter.DIAGNOSTIC)
            val previouslyMissingNormalAutoSwitch = wasConclusiveMissing(NormalQualityLocator.DIAGNOSTIC)
            val previouslyMissingDetailSpecialTag = wasConclusiveMissing(DetailSpecialTagAdapter.DIAGNOSTIC)
            val previouslyMissingCommentReplyOwners = wasConclusiveMissing(CommentReplyButtonAdapter.DIAGNOSTIC)
            val previouslyMissingHomeEarlyLoadListener = wasConclusiveMissing(HomeRecommendEarlyLoadAdapter.DIAGNOSTIC)
            val previouslyMissingDefaultWords = wasConclusiveMissing(KotlinDefaultWordsLocator.DIAGNOSTIC)
            val previouslyMissingSponsor = wasConclusiveMissing(SponsorPlayerLocator.DIAGNOSTIC)
            val previouslyMissingClassic = wasConclusiveMissing(CommentClassicStyleLocator.DIAGNOSTIC)
            val previousPopup = history?.playerPopupPromotion
            val assistHistory = AssistHistory.of(history)
            val previouslyMissingPopup = PlayerPopupPromotionAdapter.Family.entries.filterTo(linkedSetOf()) { family ->
                wasConclusiveMissing(family.diagnostic)
            }
            val previouslyMissingSystemEnv = SystemEnvironmentAdapter.Family.entries.filterTo(linkedSetOf()) { family ->
                wasConclusiveMissing(family.diagnostic)
            }
            val previouslyMissingComposeMenu = wasConclusiveMissing(CommentComposeMenuAdapter.DIAGNOSTIC)
            val previouslyMissingKmpGrpcWire = wasConclusiveMissing(KmpGrpcWireAdapter.DIAGNOSTIC)
            val previouslyMissingAiPlaylistDirector = wasConclusiveMissing(AiPlaylistDirectorAdapter.DIAGNOSTIC)
            val previouslyMissingFollowUp = wasConclusiveMissing(FollowUpRecommendAdapter.DIAGNOSTIC)
            val previouslyMissingDetailRelateGame = wasConclusiveMissing(DetailRelateGameAdapter.DIAGNOSTIC)
            val previouslyMissingLegacyStoryBind = wasConclusiveMissing(LegacyStoryDanmakuAdapter.DIAGNOSTIC)
            val previouslyMissingTheseus2LongPress = wasConclusiveMissing(PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC)
            return adapt(
                context, classLoader, kotlinDefaultWordsEnabled, sponsorBlockEnabled,
                commentClassicStyleEnabled, playerPopupPromotionEnabled, previousPopup, previouslyMissingPopup,
                brandSplashCustomEnabled, prev.brand, previouslyMissingBrand,
                followFeedStyleEnabled, prev.follow, previouslyMissingFollow,
                playerQualityEnabled, prev.storyQuality, previouslyMissingStoryQuality,
                playerSpeedMenuEnabled, prev.speedMenu, speedMenuAttempted,
                playerSpeedSessionEnabled, prev.speedSession, speedSessionAttempted,
                playerSpeedCalculatorEnabled, prev.speedCalculator, speedCalculatorAttempted,
                weChatShareEnabled, prev.weChatShare, weChatShareAttempted,
                searchHomeRecommendEnabled, playerEndPageRecommendEnabled, prev.pages, pageAttempted,
                homeChromeEnabled, prev.chrome, chromeAttempted, homeChromeIconOnly,
                prev.chromeSemantics, semanticsAttempted, homeTopTabCenteringEnabled,
                systemEnvironmentEnabled, prev.systemEnv, previouslyMissingSystemEnv,
                blockUpdateEnabled, commentTopologyEnabled,
                prev.composeMenu, previouslyMissingComposeMenu, kmpGrpcWireEnabled, prev.kmpGrpcWire, previouslyMissingKmpGrpcWire,
                prev.brandKntr, previouslyMissingBrandKntr,
                prev.brandKntrExit, previouslyMissingBrandKntrExit,
                prev.brandKntrPage, previouslyMissingBrandKntrPage,
                commentQuickReplyEnabled, previouslyMissingCommentQuickReply,
                pausedAdEnabled, previouslyMissingPauseCountdown, previouslyMissingPauseApiEntry,
                brandSplashSkipEnabled, previouslyMissingHomeTabResource,
                cloudKeyFreezeEnabled, prev.cloudKeyContract, previouslyMissingCloudKeyContract,
                incognitoPrivacyEnabled, prev.privacyIgnet, previouslyMissingPrivacyIgnet, prev.privacyKNeuron, previouslyMissingPrivacyKNeuron,
                guestBrowsingEnabled, prev.guestPlayerCore, previouslyMissingGuestPlayerCore,
                prev.normalAutoSwitch, previouslyMissingNormalAutoSwitch, detailSpecialTagEnabled, prev.detailSpecialTag, previouslyMissingDetailSpecialTag,
                commentReplyButtonEnabled, prev.commentReplyOwners, previouslyMissingCommentReplyOwners,
                homeRecommendEarlyLoadEnabled, prev.homeEarlyLoadListener, previouslyMissingHomeEarlyLoadListener,
                prev.defaultWords, previouslyMissingDefaultWords,
                prev.sponsor, previouslyMissingSponsor,
                prev.classic, previouslyMissingClassic,
                aiDeclaredVideoEnabled, prev.aiPlaylistDirector, previouslyMissingAiPlaylistDirector,
                followUpRecommendEnabled, prev.followUpBodies, previouslyMissingFollowUp,
                detailRelateGameEnabled, prev.detailRelateGame, previouslyMissingDetailRelateGame,
                storyDanmakuToggleHidden, prev.legacyStoryBind, previouslyMissingLegacyStoryBind,
                playerSpeedLongPressEnabled, prev.theseus2LongPress, previouslyMissingTheseus2LongPress,
                assistHistory, adaptPlan, history, dexSource, vc, startup.identity.fingerprint,
                splashAutoNightEnabled = splashAutoNightEnabled
            )
        }
        fun requiresFeatureRefresh(value: AdaptResult): Boolean =
            DexFeatureRegistry.requiresFeatureRefresh(
                DexFeatureRefreshContext(classLoader, vc, featureFlags),
                value
            )
        val refreshRequested = usable?.let(::requiresFeatureRefresh) ?: false
        val cached = usable?.takeUnless { refreshRequested || coverageMissing.isNotEmpty() || staleDomains.isNotEmpty() }
        if (cached != null) {
            // 缓存命中后的文件维护和 DEX 内容比对一并下沉；不一致只作废缓存。
            auditDexSourceAsync(context, cached)
            return // 快路径：已适配
        }
        if (!adaptationRunning.compareAndSet(false, true)) {
            ModernHookLog.info("[BIL] 版本适配已在本进程运行，跳过重复请求")
            return
        }
        // 只因计划新增从未尝试的同步家族或局部域规则升级而补适配，或全模式重绑定命中：不弹适配提示。
        val silentSupplement = adaptDecision(usable, refreshRequested, coverageMissing, staleDomains) == AdaptDecision.SUPPLEMENT_SILENTLY
        ModernHookLog.info(
            "[BIL] 版本适配启动 vc=$vc cached=${startup.cached != null} " +
                "cacheStatus=${AdaptCacheStore.cacheStatus()} plan=$adaptPlan " +
                "missing=${coverageMissing.size} stale=${staleDomains.size} silent=$silentSupplement"
        )
        // 是否弹提示推迟到工作线程检查全模式 DEX/协议内容重绑定后再决定。
        val worker = Thread({
            var result: AdaptResult? = null
            var persisted = false
            var startedNotified = false
            var runSilent = silentSupplement
            try {
                // `adapt` 已自带 runCatching，但 writeCache 与日志不在其中；宿主进程内
                // 线程的逃逸异常一律杀进程，整段必须过防波堤。
                HostThreadGuard.run("adapter.adapt_worker") {
                    // One original-source inspection shared by content rebind validation and normal adaptation.
                    // No new disk work or unvalidated cached members enter Application.attach.
                    var adaptHistory: AdaptResult? = popupHistory
                    val dexSource by lazy(LazyThreadSafetyMode.NONE) { inspectHostDexSource(context) }
                    val repacked = if (startup.cached == null) {
                        AdaptCacheStore.readRawCacheCandidates(context, resetTimestamp).firstNotNullOfOrNull { previous ->
                            val actualProtocol = verifyProtocolFingerprint(classLoader, previous)
                            rebindRepackedCache(previous, startup.identity, resetTimestamp, dexSource?.value, actualProtocol)
                        }?.let { candidate ->
                            val reboundStale = AdaptDomainCatalog.staleDomains(candidate.domainRuleVersions)
                            val reboundStripped = if (reboundStale.isEmpty()) {
                                candidate
                            } else {
                                candidate.withoutDomains(reboundStale)
                            }
                            val reboundUsable = StartupCacheSnapshot(
                                startup.identity,
                                resetTimestamp.coerceAtLeast(0L),
                                candidate
                            ).usableCache(highCandidateExists)
                            val reboundCoverageMissing = if (reboundUsable != null) {
                                AdaptPlanner.missing(reboundStripped.coverage, adaptPlan)
                            } else {
                                emptySet()
                            }
                            val reboundRefresh = reboundUsable?.let(::requiresFeatureRefresh) ?: false
                            if (reboundUsable != null && reboundStale.isEmpty() && !reboundRefresh &&
                                reboundCoverageMissing.isEmpty()
                            ) {
                                runSilent = true
                                AdaptCacheStore.recordCacheStatus("dex-content-rebound")
                                ModernHookLog.info(
                                    "[BIL] 宿主 DEX 与协议指纹一致，静默复用适配缓存 dex=${candidate.dexSourceFingerprint}"
                                )
                                candidate
                            } else {
                                adaptHistory = reboundStripped
                                runSilent = adaptDecision(
                                    reboundUsable,
                                    reboundRefresh,
                                    reboundCoverageMissing,
                                    reboundStale
                                ) == AdaptDecision.SUPPLEMENT_SILENTLY
                                AdaptCacheStore.recordCacheStatus("dex-content-rebound-incremental")
                                ModernHookLog.info(
                                    "[BIL] 宿主 DEX 与协议指纹一致，按增量历史补适配 stale=${reboundStale.size} missing=${reboundCoverageMissing.size} silent=$runSilent"
                                )
                                null
                            }
                        }
                    } else {
                        null
                    }
                    result = repacked ?: run {
                        if (!runSilent) {
                            startedNotified = true
                            runCatching { callback?.onAdaptStarted() }
                        }
                        runCatching { runAdaptWithHistory(adaptHistory, dexSource) }.getOrNull()
                    }
                    result?.let { adapted ->
                        // 写文件缓存（loadApp 快路径载体）；校验后原子替换，旧缓存不会被半截 JSON 覆盖。
                        val fileWritten = AdaptCacheStore.writeCache(adapted)
                        if (!fileWritten) {
                            ModernHookLog.error("[BIL] 版本适配文件缓存写入失败")
                        }
                        // 写 prefs 缓存（模块 App 侧可读，供 UI 展示适配状态）
                        val prefsWritten = runCatching {
                            AdaptCacheStore.prefs(context).edit()
                                .putInt(AdaptCacheStore.KEY_ADAPTED_VERSION, adapted.biliVersionCode)
                                .putString(AdaptCacheStore.KEY_ADAPT_RESULT, adapted.toJson().toString())
                                .apply()
                            true
                        }.onFailure { throwable ->
                            ModernHookLog.error("[BIL] 版本适配 prefs 缓存写入失败", throwable)
                        }.getOrDefault(false)
                        persisted = fileWritten || prefsWritten
                    }
                }
            } finally {
                val succeeded = result != null && persisted
                adaptationRunning.set(false)
                if (startedNotified || (!succeeded && !runSilent)) {
                    runCatching { callback?.onAdaptFinished(succeeded) }
                } else {
                    runCatching { callback?.onSilentCacheReady(succeeded) }
                }
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
                        "dex=${result?.dexSourceFingerprint} " +
                        "protocol=${result?.protocolFingerprint} " +
                        "diag=${result?.diagnosticSummary()}"
                )
            }
        }, "BIL-VersionAdapter").apply { isDaemon = true }
        runCatching { worker.start() }.onFailure { throwable ->
            adaptationRunning.set(false)
            if (silentSupplement) {
                runCatching { callback?.onSilentCacheReady(false) }
            } else {
                runCatching { callback?.onAdaptFinished(false) }
            }
            ModernHookLog.error("[BIL] 版本适配线程启动失败", throwable)
        }
    }

    /**
     * 即时快速定位（loadApp 阶段用，不依赖缓存/attach 适配时序）：纯内存反射，返回 low/high 等定位结果
     * （无版本/时间戳）。这个重载是原始行为：全量计划，所有家族都定位。
     *
     * 耗时没有实测结论：同步定位段与冷启动 P50/P95 需要设备另行测量，不要引用「约 1 ms」。
     */
    fun quickLocate(loader: ClassLoader): AdaptResult? = quickLocate(loader, AdaptPlan.full())

    /**
     * 按 [plan] 定位：只对计划内家族调用定位器，计划外的结果为 null、诊断为「未请求」、覆盖范围记为
     * NOT_REQUESTED，不会为了凑齐诊断去求值。[observer] 给测试和 A/B 对照计数、计时用，正式路径传 null。
     * 结果不持久化，覆盖范围只描述本轮尝试过什么。
     */
    internal fun quickLocate(
        loader: ClassLoader,
        plan: AdaptPlan,
        observer: LocateObserver? = null,
        hostVersionCode: Int? = null
    ): AdaptResult? = quickLocatePlanned(loader, plan, observer, emptySet(), hostVersionCode)

    /** Apply NPatch's process exclusions without dropping families required by the upstream plan. */
    internal fun quickLocateForStartup(
        loader: ClassLoader,
        excluded: Set<String>,
        plan: AdaptPlan = AdaptPlan.full(),
        observer: LocateObserver? = null,
        allowPruneRequested: Boolean = true,
        hostVersionCode: Int? = null
    ): AdaptResult? {
        val mapping = mapOf(
            StartupAdapterPlan.Family.DYNAMIC_TABS to PointFamily.DYNAMIC_TABS,
            StartupAdapterPlan.Family.FULL_NUMBERS to PointFamily.FULL_NUMBERS,
            StartupAdapterPlan.Family.PLAYER_PORTRAIT to PointFamily.PLAYER_PORTRAIT,
            StartupAdapterPlan.Family.PLAYER_INTERACTIVE to PointFamily.PLAYER_INTERACTIVE_OVERLAYS,
            StartupAdapterPlan.Family.PGC_POPUP to PointFamily.PGC_AUTO_ACTIVITY_POPUP,
            StartupAdapterPlan.Family.PLAYER_STATUS_BAR to PointFamily.PLAYER_STATUS_BAR,
            StartupAdapterPlan.Family.PLAYER_QUALITY to PointFamily.PLAYER_QUALITY,
            StartupAdapterPlan.Family.TEENAGERS to PointFamily.TEENAGERS_MODE,
            StartupAdapterPlan.Family.COMMENT_PURIFY to PointFamily.COMMENT_PURIFY,
            StartupAdapterPlan.Family.COMMENT_FILTER to PointFamily.COMMENT_FILTER,
            StartupAdapterPlan.Family.COMMENT_TOPOLOGY to PointFamily.COMMENT_TOPOLOGY,
            StartupAdapterPlan.Family.COMMENT_SECTION to PointFamily.COMMENT_SECTION,
            StartupAdapterPlan.Family.SPLASH to PointFamily.SPLASH_ADS,
            StartupAdapterPlan.Family.VIDEO_RELATE to PointFamily.VIDEO_RELATE
        )
        val names = mapping.keys.associateBy { it.name }
        val valid = if (excluded.all { it in names }) excluded else emptySet()
        val requestedOmissions = valid.mapNotNull { names[it]?.let(mapping::get) }
            .filterTo(linkedSetOf()) { allowPruneRequested || !plan.needs(it) }
        val required = AdaptPlanCatalog.closure((plan.required - requestedOmissions) + plan.always)
        val effectiveExcluded = valid.filterTo(linkedSetOf()) { name -> mapping.getValue(names.getValue(name)) !in required }
        val effectivePlan = AdaptPlan(plan.configRevision, required, plan.always)
        return quickLocatePlanned(loader, effectivePlan, observer, effectiveExcluded, hostVersionCode)
    }

    private fun quickLocatePlanned(
        loader: ClassLoader,
        plan: AdaptPlan,
        observer: LocateObserver?,
        excluded: Set<String>,
        hostVersionCode: Int?
    ): AdaptResult? = KavaMemberLookup.withLookupBatch(loader) {
        val locators = SyncLocatorRun(plan, observer, traceStartup = true)
        val low = locators.locate(PointFamily.COMMENT_LOW) { locateCommentLow(loader) }
        val high = locators.locate(PointFamily.COMMENT_HIGH) { locateCommentHigh(loader) }
        val mine = locators.locate(PointFamily.MINE_ENTRY) { locateMineEntry(loader) }
        val pause = locators.locate<PausePoints>(PointFamily.PAUSE, found = { !it.isEmpty() }) { locatePausePoints(loader) }
            ?: EMPTY_PAUSE_POINTS
        val banner = locators.locate(PointFamily.BANNER) { locateBanner(loader) }
        val homeTopBar = locators.locate(PointFamily.HOME_TOP_BAR) { locateHomeTopBar(loader) }
        val mineVip = locators.locate(PointFamily.MINE_VIP) { locateMineVip(loader) }
        val blockUpdate = locators.locate(PointFamily.BLOCK_UPDATE) { locateBlockUpdate(loader, hostVersionCode) }
        val dynamicTabs = locators.locate(PointFamily.DYNAMIC_TABS) { locateDynamicTabs(loader) }
        val fullNumbers = locators.locate(PointFamily.FULL_NUMBERS) { locateFullNumbers(loader) }
        val playerPortrait = locators.locate(PointFamily.PLAYER_PORTRAIT) { locatePlayerPortrait(loader) }
        val playerInteractiveOverlays = locators.locate(PointFamily.PLAYER_INTERACTIVE_OVERLAYS) {
            locatePlayerInteractiveOverlays(loader)
        }
        val pgcAutoActivityPopup = locators.locate(PointFamily.PGC_AUTO_ACTIVITY_POPUP) { locatePgcAutoActivityPopup(loader) }
        val playerStatusBar = locators.locate(PointFamily.PLAYER_STATUS_BAR) { locatePlayerStatusBar(loader) }
        val homeRecommendFeed = locators.locate(PointFamily.HOME_RECOMMEND_FEED) { locateHomeRecommendFeed(loader) }
        val videoRelate = locators.locate(PointFamily.VIDEO_RELATE) { locateVideoRelate(loader) }
        val homeTabs = locators.locate(PointFamily.HOME_TABS) { locateHomeTabs(loader) }
        val homeComponents = locators.locate(PointFamily.HOME_COMPONENTS) { locateHomeComponents(loader) }
        val mineComponents = locators.locate(PointFamily.MINE_COMPONENTS) { locateMineComponents(loader, mine) }
        val mineAccountMine = locators.locate(PointFamily.MINE_ACCOUNT_MINE) { locateMineAccountMinePoints(loader) }
        val storyFeed = locators.locate(PointFamily.STORY_FEED) { locateStoryFeed(loader) }
        val bottomBar = locators.locate(PointFamily.BOTTOM_BAR) { locateBottomBar(loader) }
        val playerQuality = locators.locate(PointFamily.PLAYER_QUALITY) {
            locateDefaultVideoQuality(loader, hostVersionCode = hostVersionCode)
        }
        val teenagersMode = locators.locate(PointFamily.TEENAGERS_MODE) { locateTeenagersMode(loader) }
        val commentPurify = locators.locate(PointFamily.COMMENT_PURIFY) { locateCommentPurify(loader) }
        val commentFilter = locators.locate(PointFamily.COMMENT_FILTER) { locateCommentFilter(loader) }
        val commentTopologyOutcome = locators.locate<CommentTopologyLocateOutcome>(
            PointFamily.COMMENT_TOPOLOGY, found = { it.points != null }
        ) { locateCommentTopologyWithDiagnostic(loader, hostVersionCode = hostVersionCode ?: 0) }
        val commentTopology = commentTopologyOutcome?.points
        val commentSection = locators.locate(PointFamily.COMMENT_SECTION) { locateCommentSection(loader) }
        val splashAds = locators.locate(PointFamily.SPLASH_ADS) { locateSplashAds(loader) }
        if (excluded.isEmpty() && low == null && high == null && mine == null &&
            pause.isEmpty() && banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            playerInteractiveOverlays == null && pgcAutoActivityPopup == null &&
            playerStatusBar == null && homeRecommendFeed == null && videoRelate == null &&
            homeTabs == null && homeComponents == null && mineComponents == null &&
            mineAccountMine == null && storyFeed == null && bottomBar == null &&
            playerQuality == null && teenagersMode == null && commentPurify == null &&
            commentFilter == null && commentTopology == null && commentSection == null &&
            splashAds == null) return@withLookupBatch null
        val protocolFingerprint = StartupTrace.measure("adapter.protocol") {
            ProtocolFingerprintBuilder.buildProtocolStructureFingerprint(
                loader, homeRecommendFeed, videoRelate, commentFilter, playerQuality,
                splashAds, mineAccountMine
            )
        }
        val located = AdaptResult(
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
            playerInteractiveOverlays = playerInteractiveOverlays,
            pgcAutoActivityPopup = pgcAutoActivityPopup,
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
            diagnostics = emptyList(),
            coverage = locators.coverage()
        )
        located.copy(
            diagnostics = buildDiagnostics(
                loader, plan, null, located, commentTopologyOutcome?.failureDetail.orEmpty(), hostVersionCode
            ) + protocolFingerprint.toDiagnostic(plan) + listOf(
                AdaptDiagnostic(
                    id = DEX_ASSIST_BLOCK_UPDATE_ID,
                    state = AdaptState.NOT_APPLICABLE,
                    detail = "quick-locate"
                ),
                AdaptDiagnostic(
                    id = DEX_ASSIST_COMMENT_TOPOLOGY_ID,
                    state = AdaptState.NOT_APPLICABLE,
                    detail = "quick-locate"
                ),
                AdaptDiagnostic(
                    id = DEX_ASSIST_PLAYER_QUALITY_ID,
                    state = AdaptState.NOT_APPLICABLE,
                    detail = "quick-locate"
                ),
                AdaptDiagnostic(KotlinDefaultWordsLocator.DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "quick-locate"),
                AdaptDiagnostic(BrandSplashDecoderAdapter.DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "quick-locate"),
                AdaptDiagnostic(FollowFeedAdapter.DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "quick-locate"),
                AdaptDiagnostic(StoryQualityLocator.DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "quick-locate")
            ) + PlayerPopupPromotionAdapter.Family.entries.map {
                AdaptDiagnostic(it.diagnostic, AdaptState.NOT_APPLICABLE, "quick-locate")
            },
            startupExcluded = excluded
        ).applyStartupExclusions()
    }

    private val EMPTY_PAUSE_POINTS = MediaQualityAndPlayerLocator.EMPTY_PAUSE_POINTS

    /** 智能定位核心：对内置候选类做存在性验证 + 方法签名特征匹配。
     * 成员定位在内存中完成；NPatch 的原版 APK 来源只在后台工作线程准备只读快照。
     * 成功标准放宽：任一候选类存在即算成功——运行期注册有 classExists 双路径回退，
     * 适配结果主要用于快路径签名（定位不到签名不影响运行期内置候选注册），
     * 避免「功能可用但报适配失败」的误导（8.90.2 实测）。
     */
    private fun adapt(context: Context, loader: ClassLoader, kotlinDefaultWordsEnabled: Boolean, sponsorBlockEnabled: Boolean,
                      commentClassicStyleEnabled: Boolean, playerPopupPromotionEnabled: Boolean,
                      previousPopup: PlayerPopupPromotionPoints?, previouslyMissingPopup: Set<PlayerPopupPromotionAdapter.Family>,
                      brandSplashCustomEnabled: Boolean, previousBrand: HookPoint?, previouslyMissingBrand: Boolean,
                      followFeedStyleEnabled: Boolean, previousFollow: FollowFeedPoints?, previouslyMissingFollow: Boolean,
                      playerQualityEnabled: Boolean, previousStoryQuality: StoryQualityPoints?, previouslyMissingStoryQuality: Boolean,
                      playerSpeedMenuEnabled: Boolean, previousSpeedMenu: PlayerSpeedMenuPoints?, speedMenuAttempted: Boolean,
                      playerSpeedSessionEnabled: Boolean, previousSpeedSession: PlayerSpeedSessionClasses?, speedSessionAttempted: Boolean,
                      playerSpeedCalculatorEnabled: Boolean, previousSpeedCalculator: LongPressCalculatorPoint?, speedCalculatorAttempted: Boolean,
                      weChatShareEnabled: Boolean, previousWeChatShare: WeChatShareClasses?, weChatShareAttempted: Boolean,
                      searchHomeRecommendEnabled: Boolean, playerEndPageRecommendEnabled: Boolean,
                      previousPages: PageBoundaryPoints?, pageAttempted: Set<DexAssistQuery>,
                      homeChromeEnabled: Boolean, previousChrome: Map<String, HookPoint>,
                      chromeAttempted: Set<DexAssistQuery>, homeChromeIconOnly: Boolean,
                      previousChromeSemantics: HomeChromeSemanticsPoint?, semanticsAttempted: Boolean,
                      homeTopTabCenteringEnabled: Boolean, systemEnvironmentEnabled: Boolean,
                      previousSystemEnv: SystemEnvironmentPoints? = null,
                      previouslyMissingSystemEnv: Set<SystemEnvironmentAdapter.Family> = emptySet(),
                      blockUpdateEnabled: Boolean = false, commentTopologyEnabled: Boolean = false,
                      previousComposeMenu: CommentComposeMenuPoints? = null,
                      previouslyMissingComposeMenu: Boolean = false,
                      kmpGrpcWireEnabled: Boolean = false,
                      previousKmpGrpcWire: HookPoint? = null,
                      previouslyMissingKmpGrpcWire: Boolean = false,
                      previousBrandKntr: String? = null,
                      previouslyMissingBrandKntr: Boolean = false,
                      previousBrandKntrExit: String? = null,
                      previouslyMissingBrandKntrExit: Boolean = false,
                      previousBrandKntrPage: HookPoint? = null,
                      previouslyMissingBrandKntrPage: Boolean = false,
                      commentQuickReplyEnabled: Boolean = false,
                      previouslyMissingCommentQuickReply: Boolean = false,
                      pausedAdEnabled: Boolean = false,
                      previouslyMissingPauseCountdown: Boolean = false,
                      previouslyMissingPauseApiEntry: Boolean = false,
                      brandSplashSkipEnabled: Boolean = false,
                      previouslyMissingHomeTabResource: Boolean = false,
                      cloudKeyFreezeEnabled: Boolean = false,
                      previousCloudKeyContract: String? = null,
                      previouslyMissingCloudKeyContract: Boolean = false,
                      incognitoPrivacyEnabled: Boolean = false,
                      previousPrivacyIgnet: HookPoint? = null,
                      previouslyMissingPrivacyIgnet: Boolean = false,
                      previousPrivacyKNeuron: String? = null,
                      previouslyMissingPrivacyKNeuron: Boolean = false,
                      guestBrowsingEnabled: Boolean = false,
                      previousGuestPlayerCore: String? = null,
                      previouslyMissingGuestPlayerCore: Boolean = false,
                      previousNormalAutoSwitch: HookPoint? = null,
                      previouslyMissingNormalAutoSwitch: Boolean = false,
                      detailSpecialTagEnabled: Boolean = false,
                      previousDetailSpecialTag: HookPoint? = null,
                      previouslyMissingDetailSpecialTag: Boolean = false,
                      commentReplyButtonEnabled: Boolean = false,
                      previousCommentReplyOwners: Map<String, String> = emptyMap(),
                      previouslyMissingCommentReplyOwners: Boolean = false,
                      homeRecommendEarlyLoadEnabled: Boolean = false,
                      previousHomeEarlyLoadListener: String? = null,
                      previouslyMissingHomeEarlyLoadListener: Boolean = false,
                      previousDefaultWords: HookPoint? = null,
                      previouslyMissingDefaultWords: Boolean = false,
                      previousSponsor: SponsorPlayerClasses? = null,
                      previouslyMissingSponsor: Boolean = false,
                      previousClassic: CommentClassicStylePoints? = null,
                      previouslyMissingClassic: Boolean = false,
                      aiDeclaredVideoEnabled: Boolean = false,
                      previousAiPlaylistDirector: String? = null,
                      previouslyMissingAiPlaylistDirector: Boolean = false,
                      followUpRecommendEnabled: Boolean = false,
                      previousFollowUpBodies: Map<String, String> = emptyMap(),
                      previouslyMissingFollowUp: Boolean = false,
                      detailRelateGameEnabled: Boolean = false,
                      previousDetailRelateGame: String? = null,
                      previouslyMissingDetailRelateGame: Boolean = false,
                      storyDanmakuToggleHidden: Boolean = false,
                      previousLegacyStoryBind: HookPoint? = null,
                      previouslyMissingLegacyStoryBind: Boolean = false,
                      playerSpeedLongPressEnabled: Boolean = false,
                      previousTheseus2LongPress: Theseus2LongPressPoint? = null,
                      previouslyMissingTheseus2LongPress: Boolean = false,
                      assistHistory: AssistHistory = AssistHistory.EMPTY,
                      plan: AdaptPlan = AdaptPlan.full(), previous: AdaptResult? = null,
                      dexSource: DexSourceFingerprint.Result?, vc: Int,
                      hostFingerprint: String? = null,
                      splashAutoNightEnabled: Boolean = false): AdaptResult? = KavaMemberLookup.withLookupBatch(loader) {
        val resolvedHostFingerprint = hostFingerprint ?: buildHostFingerprint(context)
        ModernHookLog.info("[BIL] DEX source count=${dexSource?.dexEntryCount ?: 0}, " +
            "isolated=${dexSource?.codePaths?.all { File(it).parentFile?.name == "bil-host-dex" } == true}")
        // 只对计划内家族调用定位器；计划外的家族沿用此前有覆盖证明的缓存点，没有证明就是未请求（null）。
        val locators = SyncLocatorRun(plan)
        val previousCoverage = previous?.coverage ?: AdaptCoverage.EMPTY
        fun <T> sync(family: PointFamily, prior: T?, found: (T) -> Boolean = { true }, block: () -> T?): T? =
            locators.locateOrCarry(family, prior, previousCoverage, found, block)
        val low = sync(PointFamily.COMMENT_LOW, previous?.commentLow) { locateCommentLow(loader) }
        val high = sync(PointFamily.COMMENT_HIGH, previous?.commentHigh) { locateCommentHigh(loader) }
        val mine = sync(PointFamily.MINE_ENTRY, previous?.mineEntry) { locateMineEntry(loader) }
        val pause = sync<PausePoints>(PointFamily.PAUSE, previous?.pause, found = { !it.isEmpty() }) {
            locatePausePoints(loader)
        } ?: EMPTY_PAUSE_POINTS
        val banner = sync(PointFamily.BANNER, previous?.banner) { locateBanner(loader) }
        var homeTopBar = sync(PointFamily.HOME_TOP_BAR, previous?.homeTopBar) { locateHomeTopBar(loader) }
        val directDefaultWordsFound = homeTopBar?.kotlinDefaultWords != null
        val carriedDefaultWords = if (!directDefaultWordsFound) {
            previousDefaultWords?.takeIf { KotlinDefaultWordsLocator.resolve(loader, it) != null }
        } else {
            null
        }
        if (homeTopBar?.kotlinDefaultWords == null && carriedDefaultWords != null) {
            homeTopBar = homeTopBar?.copy(kotlinDefaultWords = carriedDefaultWords)
                ?: HomeTopBarPoints(null, null, null, emptyList(), carriedDefaultWords)
        }
        val defaultWordsNeedsAssist = KotlinDefaultWordsLocator.refreshCache(
            kotlinDefaultWordsEnabled,
            KotlinDefaultWordsLocator.applicable(loader),
            directDefaultWordsFound,
            directDefaultWordsFound || carriedDefaultWords != null,
            previouslyMissingDefaultWords
        )
        val directSponsor = if (sponsorBlockEnabled) SponsorPlayerLocator.direct(loader) else null
        val cachedSponsor = if (sponsorBlockEnabled && directSponsor == null) {
            previousSponsor?.let { SponsorPlayerLocator.resolve(loader, it) }
        } else {
            null
        }
        val sponsorNeedsAssist = sponsorBlockEnabled && SponsorPlayerLocator.needsQuery(
            sponsorBlockEnabled,
            SponsorPlayerLocator.applicable(loader),
            directSponsor != null
        ) && cachedSponsor == null && !previouslyMissingSponsor
        val directSpeedSession = if (playerSpeedSessionEnabled) PlayerSpeedSessionLocator.modern(loader) else null
        val speedSessionPlanned = PlayerSpeedSessionLocator.refreshCache(playerSpeedSessionEnabled,
            { PlayerSpeedSessionLocator.applicable(loader) },
            { directSpeedSession != null },
            { previousSpeedSession?.let { PlayerSpeedSessionLocator.resolve(loader, it) } != null },
            { speedSessionAttempted })
        val directSpeedCalculator = if (playerSpeedCalculatorEnabled)
            PlayerSpeedLocator.theseus2LongPressCalculator(loader, vc) else null
        val speedCalculatorPlanned = PlayerSpeedLocator.calculatorRefreshCache(playerSpeedCalculatorEnabled,
            { PlayerSpeedLocator.calculatorApplicable(loader) },
            { directSpeedCalculator != null },
            { previousSpeedCalculator?.let { PlayerSpeedLocator.resolveCalculator(loader, it) } != null },
            { speedCalculatorAttempted })
        val directWeChatShare = if (weChatShareEnabled) {
            WeChatShareCompatAccess.locate(loader, vc)?.takeIf { it.wrappers.size == it.expectedWrappers }
        } else null
        val weChatSharePlanned = WeChatShareCompatAccess.refreshCache(weChatShareEnabled,
            { WeChatShareCompatAccess.applicable(loader) },
            { directWeChatShare != null },
            { previousWeChatShare?.let { WeChatShareCompatAccess.resolve(loader, it) } != null },
            { weChatShareAttempted })
        val classicDirectMissing = if (commentClassicStyleEnabled) CommentClassicStyleLocator.missing(loader) else emptySet()
        val classicMissing = if (commentClassicStyleEnabled && !previouslyMissingClassic) {
            CommentClassicStyleLocator.missing(loader, previousClassic)
        } else {
            emptySet()
        }
        val followPlanned = FollowFeedAdapter.needsQuery(loader, previousFollow, followFeedStyleEnabled, previouslyMissingFollow)
        val pagePlanned = PageBoundaryAdapter.planned(loader, previousPages, searchHomeRecommendEnabled,
            playerEndPageRecommendEnabled, pageAttempted, styleEnabled = followFeedStyleEnabled)
        val speedMenuPlanned = PlayerSpeedMenuAdapter.needsQuery(loader, previousSpeedMenu, playerSpeedMenuEnabled, speedMenuAttempted)
        val storyQualityPlanned = StoryQualityLocator.planned(loader, previousStoryQuality, playerQualityEnabled, previouslyMissingStoryQuality)
        val brandPlanned = BrandSplashDecoderAdapter.needsQuery(loader, previousBrand, brandSplashCustomEnabled, previouslyMissingBrand)
        val brandKntrPlanned = BrandSplashKntrAdapter.needsQuery(loader, previousBrandKntr, brandSplashCustomEnabled, previouslyMissingBrandKntr)
        val brandKntrExitPlanned = BrandSplashKntrAdapter.needsExitQuery(
            loader, previousBrandKntrExit, brandSplashCustomEnabled || brandSplashSkipEnabled, previouslyMissingBrandKntrExit
        )
        val brandKntrPageEnabled = brandSplashCustomEnabled || splashAutoNightEnabled
        val brandKntrPagePlanned = BrandSplashKntrAdapter.needsPageQuery(
            loader, previousBrandKntrPage, brandKntrPageEnabled, previouslyMissingBrandKntrPage
        )
        val directHomeTabs = sync(PointFamily.HOME_TABS, previous?.homeTabs) { locateHomeTabs(loader) }
        val directCommentPurify = sync(PointFamily.COMMENT_PURIFY, previous?.commentPurify) { locateCommentPurify(loader) }
        val directQuickReplyMethods = if (commentQuickReplyEnabled) {
            directCommentPurify?.quickReplyDialogMethods?.takeIf { it.isNotEmpty() } ?: locateCommentQuickReplyDirect(loader)
        } else emptyList()
        val commentQuickReplyPlanned = CommentQuickReplyAdapter.needsQuery(
            loader,
            directQuickReplyMethods,
            previous?.commentPurify?.quickReplyDialogMethods.orEmpty(),
            commentQuickReplyEnabled,
            previouslyMissingCommentQuickReply
        )
        val pausedCountdownPlanned = PausedAdDexAdapter.needsCountdownQuery(
            loader,
            pause.countdown,
            previous?.pause?.countdown,
            pausedAdEnabled,
            previouslyMissingPauseCountdown
        )
        val pausedRequestApiPlanned = PausedAdDexAdapter.needsRequestApiQuery(
            loader,
            vc,
            previous?.pause?.apiEntry,
            pausedAdEnabled,
            previouslyMissingPauseApiEntry
        )
        val homeTabResourcePlanned = HomeTabResourceAdapter.needsQuery(
            loader,
            directFound = directHomeTabs != null,
            cached = previous?.homeTabs,
            enabled = plan.needs(PointFamily.HOME_TABS),
            attempted = previouslyMissingHomeTabResource
        )
        val cloudKeyContractPlanned = CloudKeyFreezeAdapter.needsQuery(loader, previousCloudKeyContract, cloudKeyFreezeEnabled, previouslyMissingCloudKeyContract)
        val privacyIgnetEnabled = incognitoPrivacyEnabled || guestBrowsingEnabled
        val privacyIgnetPlanned = PrivacyChannelAdapter.needsIgnetQuery(loader, previousPrivacyIgnet, privacyIgnetEnabled, previouslyMissingPrivacyIgnet)
        val privacyKNeuronPlanned = PrivacyChannelAdapter.needsKNeuronQuery(loader, previousPrivacyKNeuron, incognitoPrivacyEnabled, previouslyMissingPrivacyKNeuron)
        val guestPlayerCorePlanned = GuestPlayerCoreAdapter.needsQuery(loader, previousGuestPlayerCore, guestBrowsingEnabled, previouslyMissingGuestPlayerCore)
        val popupMissing = if (playerPopupPromotionEnabled) PlayerPopupPromotionAdapter.missing(loader) else emptySet()
        val popupPlanned = PlayerPopupPromotionAdapter.planned(loader, previousPopup, previouslyMissingPopup, playerPopupPromotionEnabled)
        val mineVip = sync(PointFamily.MINE_VIP, previous?.mineVip) { locateMineVip(loader) }
        val chromePlanned = HomeChromeAdapter.planned(loader, previousChrome, homeChromeEnabled, chromeAttempted, homeChromeIconOnly, homeTopTabCenteringEnabled)
        val semanticsPlanned = HomeChromeAdapter.semanticsNeedsQuery(loader, previousChromeSemantics,
            homeChromeEnabled, semanticsAttempted, homeChromeIconOnly)
        // 这三类带 DEX 兜底：计划外时不直接定位，缓存里已有的点由 AssistHistory 原样带入。
        val directBlockUpdate = locators.locate(PointFamily.BLOCK_UPDATE) { locateBlockUpdate(loader, vc) }
        val directPlayerQuality = locators.locate(PointFamily.PLAYER_QUALITY) {
            locateDefaultVideoQuality(loader, hostVersionCode = vc)
        }
        val directTopologyOutcome = locators.locate<CommentTopologyLocateOutcome>(
            PointFamily.COMMENT_TOPOLOGY, found = { it.points != null }
        ) { locateCommentTopologyWithDiagnostic(loader, hostVersionCode = vc) } ?: CommentTopologyLocateOutcome(null, PLAN_NOT_REQUESTED)
        val playerQualityNeedsAssist = directPlayerQuality?.defaultQualityMethod == null ||
            directPlayerQuality.usesLegacyQualityHelper()
        val normalAutoSwitchPlanned = NormalQualityLocator.needsQuery(
            loader, directPlayerQuality?.defaultQualityMethod, previousNormalAutoSwitch,
            playerQualityEnabled, previouslyMissingNormalAutoSwitch
        )
        val detailSpecialTagPlanned = DetailSpecialTagAdapter.needsQuery(
            loader, previousDetailSpecialTag, detailSpecialTagEnabled, previouslyMissingDetailSpecialTag
        )
        val commentReplyButtonPlanned = CommentReplyButtonAdapter.needsQuery(
            loader, previousCommentReplyOwners, commentReplyButtonEnabled, previouslyMissingCommentReplyOwners
        )
        val homeEarlyLoadPlanned = HomeRecommendEarlyLoadAdapter.needsQuery(
            loader, previousHomeEarlyLoadListener, homeRecommendEarlyLoadEnabled, previouslyMissingHomeEarlyLoadListener
        )
        val aiPlaylistDirectorPlanned = AiPlaylistDirectorAdapter.needsQuery(
            loader, previousAiPlaylistDirector, aiDeclaredVideoEnabled, previouslyMissingAiPlaylistDirector
        )
        val followUpPlanned = FollowUpRecommendAdapter.needsQuery(
            loader, previousFollowUpBodies, followUpRecommendEnabled, previouslyMissingFollowUp
        )
        val detailRelateGamePlanned = DetailRelateGameAdapter.needsQuery(
            loader, previousDetailRelateGame, detailRelateGameEnabled, previouslyMissingDetailRelateGame
        )
        val legacyStoryDanmakuPlanned = LegacyStoryDanmakuAdapter.needsQuery(
            loader, previousLegacyStoryBind, storyDanmakuToggleHidden, previouslyMissingLegacyStoryBind
        )
        val theseus2LongPressPlanned = PlayerSpeedLocator.theseus2LongPressNeedsQuery(
            loader, previousTheseus2LongPress, playerSpeedLongPressEnabled, previouslyMissingTheseus2LongPress
        )
        // 三类兜底只在功能开启、直接定位未满足、缓存没有可复用的点、且历史没有确认缺失时入队。
        val blockUpdatePlanned = AdaptAssistGate.needsQuery(blockUpdateEnabled, assistHistory.blockUpdate != null,
            assistHistory.attempt(AdaptAssistGate.Family.BLOCK_UPDATE)) { directBlockUpdate != null }
        val playerQualityPlanned = AdaptAssistGate.needsQuery(playerQualityEnabled, assistHistory.hasAssistedQuality,
            assistHistory.attempt(AdaptAssistGate.Family.PLAYER_QUALITY)) { !playerQualityNeedsAssist }
        val topologyPlanned = AdaptAssistGate.needsQuery(commentTopologyEnabled, assistHistory.commentTopology != null,
            assistHistory.attempt(AdaptAssistGate.Family.COMMENT_TOPOLOGY)) { directTopologyOutcome.points != null }
        val systemEnvPlanned = SystemEnvironmentAdapter.planned(loader, previousSystemEnv, previouslyMissingSystemEnv, systemEnvironmentEnabled)
        val composeMenuPlanned = CommentComposeMenuAdapter.needsQuery(
            loader, previousComposeMenu, commentTopologyEnabled, previouslyMissingComposeMenu
        )
        val effectiveKmpGrpcWireEnabled = kmpGrpcWireEnabled || commentTopologyEnabled
        val kmpGrpcWirePlanned = KmpGrpcWireAdapter.needsQuery(
            loader, previousKmpGrpcWire, effectiveKmpGrpcWireEnabled, previouslyMissingKmpGrpcWire
        )
        // 本轮兜底共享适配会话，每个代码归档只打开一次 DEX 快照。
        val dexAssist = dexSource?.let { source ->
            DexAssistSession(
                engine = DexAssistAttemptGuard(
                    marker = AdaptCacheStore.dexAssistMarkerFile(context),
                    key = "$resolvedHostFingerprint|drv=${VersionAdapterContract.domainRulesSignature()}",
                    delegate = DexMeguvizAssistEngine
                ),
                codePaths = source.codePaths,
                classLoader = loader,
                planned = buildSet {
                    if (blockUpdatePlanned) add(DexAssistQuery.BLOCK_UPDATE)
                    if (playerQualityPlanned) add(DexAssistQuery.PLAYER_DEFAULT_QUALITY)
                    addAll(storyQualityPlanned)
                    addAll(pagePlanned)
                    addAll(chromePlanned)
                    if (semanticsPlanned) add(DexAssistQuery.HOME_CHROME_SEMANTICS)
                    if (topologyPlanned) add(DexAssistQuery.COMMENT_REPLY_MAPPER)
                    if (defaultWordsNeedsAssist) add(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)
                    if (sponsorNeedsAssist) addAll(SponsorPlayerLocator.queries)
                    if (speedSessionPlanned) addAll(PlayerSpeedSessionLocator.queries)
                    if (speedCalculatorPlanned) addAll(PlayerSpeedLocator.calculatorQueries)
                    if (weChatSharePlanned) addAll(WeChatShareCompatAccess.queries)
                    classicMissing.forEach { add(it.query) }
                    popupPlanned.forEach { add(it.query) }
                    if (brandPlanned) add(BrandSplashDecoderAdapter.query)
                    if (brandKntrPlanned) add(BrandSplashKntrAdapter.query)
                    if (cloudKeyContractPlanned) add(CloudKeyFreezeAdapter.query)
                    if (privacyIgnetPlanned) add(PrivacyChannelAdapter.queryIgnet)
                    if (privacyKNeuronPlanned) add(PrivacyChannelAdapter.queryKNeuron)
                    if (guestPlayerCorePlanned) add(GuestPlayerCoreAdapter.query)
                    if (normalAutoSwitchPlanned) add(NormalQualityLocator.query)
                    if (detailSpecialTagPlanned) add(DetailSpecialTagAdapter.query)
                    if (commentReplyButtonPlanned) add(CommentReplyButtonAdapter.query)
                    if (homeEarlyLoadPlanned) add(HomeRecommendEarlyLoadAdapter.query)
                    if (followPlanned) {
                        add(DexAssistQuery.FOLLOW_FEED_STYLE)
                        add(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS)
                    }
                    if (speedMenuPlanned) add(PlayerSpeedMenuAdapter.query)
                    systemEnvPlanned.forEach { add(it.query) }
                    if (composeMenuPlanned) add(CommentComposeMenuAdapter.query)
                    if (kmpGrpcWirePlanned) add(KmpGrpcWireAdapter.query)
                    if (brandKntrExitPlanned) add(BrandSplashKntrAdapter.queryExit)
                    if (brandKntrPagePlanned) add(BrandSplashKntrAdapter.queryPage)
                    if (commentQuickReplyPlanned) add(CommentQuickReplyAdapter.query)
                    if (pausedCountdownPlanned) add(PausedAdDexAdapter.queryCountdown)
                    if (pausedRequestApiPlanned) add(PausedAdDexAdapter.queryRequestApi)
                    if (homeTabResourcePlanned) add(HomeTabResourceAdapter.query)
                    if (aiPlaylistDirectorPlanned) add(AiPlaylistDirectorAdapter.query)
                    if (followUpPlanned) add(FollowUpRecommendAdapter.query)
                    if (detailRelateGamePlanned) add(DetailRelateGameAdapter.query)
                    if (legacyStoryDanmakuPlanned) add(LegacyStoryDanmakuAdapter.query)
                    if (theseus2LongPressPlanned) addAll(PlayerSpeedLocator.theseus2LongPressQueries)
                }
            )
        }
        val pages = PageBoundaryAdapter.locate(loader, previousPages, dexAssist, pagePlanned)
        val chrome = HomeChromeAdapter.locate(loader, previousChrome, dexAssist, chromePlanned)
        val semanticsOutcome = if (semanticsPlanned) dexAssist?.result(DexAssistQuery.HOME_CHROME_SEMANTICS) else null
        val chromeSemantics = HomeChromeAdapter.locateSemantics(loader, previousChromeSemantics, semanticsOutcome)
        val semanticsReady = homeChromeEnabled && homeChromeIconOnly && HomeChromeAdapter.applicable(loader) &&
            HostComposeSemanticsAccess.resolve(loader, chromeSemantics) != null
        val semanticsConclusive = semanticsAttempted || HomeChromeAdapter.conclusive(semanticsOutcome)
        val systemEnvironment = if (systemEnvironmentEnabled) {
            SystemEnvironmentAdapter.locate(loader, dexAssist, true, previousSystemEnv, systemEnvPlanned)
        } else {
            previousSystemEnv
        }
        val systemEnvironmentDiagnostics = SystemEnvironmentAdapter.Family.entries.map { family ->
            val found = systemEnvironmentEnabled && SystemEnvironmentAdapter.resolve(loader, family, systemEnvironment) != null
            val outcome = if (family in systemEnvPlanned) dexAssist?.result(family.query) else null
            val conclusiveMissing = family in previouslyMissingSystemEnv ||
                (family in systemEnvPlanned && SystemEnvironmentAdapter.conclusive(outcome))
            val state = when {
                found -> AdaptState.FOUND
                !systemEnvironmentEnabled && family in previouslyMissingSystemEnv -> AdaptState.MISSING
                !systemEnvironmentEnabled -> AdaptState.NOT_APPLICABLE
                conclusiveMissing -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            }
            val detail = when {
                found -> "verified-local-result-boundary"
                !systemEnvironmentEnabled && family in previouslyMissingSystemEnv -> "feature-disabled:cached-attempt-preserved"
                !systemEnvironmentEnabled -> "feature-disabled"
                family in previouslyMissingSystemEnv && family !in systemEnvPlanned -> "cached-attempt-preserved"
                outcome is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                conclusiveMissing -> "no-safe-result-boundary"
                else -> "no-dex-source"
            }
            AdaptDiagnostic(family.diagnostic, state, detail)
        }
        val semanticsDiagnostic = AdaptDiagnostic(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC, when {
            !homeChromeEnabled || !homeChromeIconOnly || !HomeChromeAdapter.applicable(loader) -> AdaptState.NOT_APPLICABLE
            semanticsReady -> AdaptState.FOUND
            semanticsConclusive -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }, when {
            semanticsReady -> "verified-canonical-content-description"
            semanticsConclusive -> "no-safe-semantics-key"
            semanticsOutcome is DexAssistResult.Unavailable -> semanticsOutcome.reason.name.lowercase()
            semanticsPlanned && dexAssist == null -> "no-dex-source"
            semanticsPlanned -> "query_failed"
            else -> "disabled-or-absent"
        })
        val chromeDiagnostics = HomeChromeAdapter.families.map { (query, family) ->
            val found = family in chrome
            val outcome = if (query in chromePlanned) dexAssist?.result(query) else null
            val attempted = query in chromeAttempted || HomeChromeAdapter.conclusive(outcome)
            val state = when {
                !HomeChromeAdapter.enabled(family, homeChromeEnabled, homeChromeIconOnly, homeTopTabCenteringEnabled) ||
                    !HomeChromeAdapter.applicable(loader) -> AdaptState.NOT_APPLICABLE
                found -> AdaptState.FOUND
                attempted -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            }
            AdaptDiagnostic(HomeChromeAdapter.diagnostic(query), state,
                when {
                    found -> "verified-local-compose-boundary"
                    attempted -> "no-safe-boundary"
                    outcome is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    query in chromePlanned && dexAssist == null -> "no-dex-source"
                    query in chromePlanned -> "query_failed"
                    else -> "disabled-or-absent"
                })
        }
        val pageDiagnostics = PageBoundaryAdapter.queries.map { query ->
            val enabled = when (query) {
                in HostCardSemanticAdapter.queries -> followFeedStyleEnabled
                DexAssistQuery.PLAYER_END_PAGE_MERGER -> playerEndPageRecommendEnabled
                else -> searchHomeRecommendEnabled
            }
            val ready = enabled && PageBoundaryAdapter.ready(loader, pages, query)
            AdaptDiagnostic(PageBoundaryAdapter.diagnostic(query), when {
                !enabled -> AdaptState.NOT_APPLICABLE
                ready -> AdaptState.FOUND
                else -> AdaptState.MISSING
            }, when { !enabled -> "disabled"; ready -> "verified-boundary"; query in pageAttempted -> "previously-missing"
                query !in pagePlanned -> "not-applicable-host"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    else -> "missing-or-ambiguous"
                }
            })
        }
        val sponsor = directSponsor ?: cachedSponsor ?: if (sponsorNeedsAssist) SponsorPlayerLocator.assisted(loader, dexAssist) else null
        val speedSession = when {
            !playerSpeedSessionEnabled -> previousSpeedSession
            directSpeedSession != null -> PlayerSpeedSessionClasses(
                directSpeedSession.run.declaringClass.name, directSpeedSession.wrapper?.declaringClass?.name)
            previousSpeedSession?.let { PlayerSpeedSessionLocator.resolve(loader, it) } != null -> previousSpeedSession
            speedSessionPlanned -> PlayerSpeedSessionLocator.assisted(loader, dexAssist)
            else -> previousSpeedSession
        }
        val speedSessionReady = playerSpeedSessionEnabled && (directSpeedSession != null ||
            speedSession?.let { PlayerSpeedSessionLocator.resolve(loader, it) } != null)
        fun failedDexReason(planned: Iterable<DexAssistQuery>): String? = planned.firstNotNullOfOrNull { query ->
            val result = dexAssist?.result(query) as? DexAssistResult.Unavailable
            result?.reason?.takeUnless { it == DexAssistResult.Reason.NO_MATCH || it == DexAssistResult.Reason.TOO_MANY_MATCHES }
                ?.name?.lowercase()
        }
        val speedSessionDiagnostic = AdaptDiagnostic(PlayerSpeedSessionLocator.DIAGNOSTIC, when {
            !playerSpeedSessionEnabled || !PlayerSpeedSessionLocator.applicable(loader) -> AdaptState.NOT_APPLICABLE
            speedSessionReady -> AdaptState.FOUND
            speedSessionPlanned || speedSessionAttempted -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }, when {
            speedSessionReady -> "verified-speed-session-owner"
            !playerSpeedSessionEnabled -> "feature-disabled"
            speedSessionPlanned && dexAssist == null -> "no-dex-source"
            speedSessionPlanned -> failedDexReason(PlayerSpeedSessionLocator.queries)
                ?: when (val outcome = dexAssist?.result(DexAssistQuery.SPONSOR_RUN_PLAYABLE)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    else -> "missing-or-ambiguous-structure"
                }
            else -> "cached-attempt"
        })
        val speedCalculator = when {
            !playerSpeedCalculatorEnabled -> previousSpeedCalculator
            directSpeedCalculator != null -> LongPressCalculatorPoint(
                directSpeedCalculator.declaringClass.name, directSpeedCalculator.name,
                directSpeedCalculator.parameterTypes[0].name)
            previousSpeedCalculator?.let { PlayerSpeedLocator.resolveCalculator(loader, it) } != null -> previousSpeedCalculator
            speedCalculatorPlanned -> PlayerSpeedLocator.assistedCalculator(loader, dexAssist)
            else -> previousSpeedCalculator
        }
        val speedCalculatorReady = playerSpeedCalculatorEnabled && (directSpeedCalculator != null ||
            speedCalculator?.let { PlayerSpeedLocator.resolveCalculator(loader, it) } != null)
        val speedCalculatorDiagnostic = AdaptDiagnostic(PlayerSpeedLocator.CALCULATOR_DIAGNOSTIC, when {
            !playerSpeedCalculatorEnabled || !PlayerSpeedLocator.calculatorApplicable(loader) -> AdaptState.NOT_APPLICABLE
            speedCalculatorReady -> AdaptState.FOUND
            speedCalculatorPlanned || speedCalculatorAttempted -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }, when {
            speedCalculatorReady -> "verified-long-press-calculator"
            !playerSpeedCalculatorEnabled -> "feature-disabled"
            speedCalculatorPlanned && dexAssist == null -> "no-dex-source"
            speedCalculatorPlanned -> when (val outcome = dexAssist?.result(DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR)) {
                is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                else -> "missing-or-ambiguous-structure"
            }
            else -> "cached-attempt"
        })
        val weChatShare = when {
            !weChatShareEnabled -> previousWeChatShare
            directWeChatShare != null -> WeChatShareVersionProfile.forVersion(vc)?.let(WeChatShareClasses::fromProfile)
            previousWeChatShare?.let { WeChatShareCompatAccess.resolve(loader, it) } != null -> previousWeChatShare
            weChatSharePlanned -> WeChatShareCompatAccess.assisted(loader, dexAssist)
            else -> previousWeChatShare
        }
        val weChatShareReady = weChatShareEnabled && (directWeChatShare != null ||
            weChatShare?.let { WeChatShareCompatAccess.resolve(loader, it) } != null)
        val weChatShareDiagnostic = AdaptDiagnostic(WeChatShareCompatAccess.DIAGNOSTIC, when {
            !weChatShareEnabled || !WeChatShareCompatAccess.applicable(loader) -> AdaptState.NOT_APPLICABLE
            weChatShareReady -> AdaptState.FOUND
            weChatSharePlanned || weChatShareAttempted -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }, when {
            weChatShareReady -> "verified-wechat-share-wrappers"
            !weChatShareEnabled -> "feature-disabled"
            weChatSharePlanned && dexAssist == null -> "no-dex-source"
            weChatSharePlanned -> when (val outcome = dexAssist?.result(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY)) {
                is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                else -> "missing-or-ambiguous-structure"
            }
            else -> "cached-attempt"
        })
        val speedMenu = PlayerSpeedMenuAdapter.locate(loader, dexAssist, previousSpeedMenu, playerSpeedMenuEnabled, speedMenuPlanned)
        val speedMenuComplete = playerSpeedMenuEnabled && PlayerSpeedMenuAdapter.complete(loader, PlayerSpeedMenuAdapter.resolve(loader, speedMenu))
        val speedMenuDiagnostic = AdaptDiagnostic(PlayerSpeedMenuAdapter.DIAGNOSTIC,
            if (speedMenuComplete) AdaptState.FOUND else if (playerSpeedMenuEnabled || speedMenuAttempted) AdaptState.MISSING else AdaptState.NOT_APPLICABLE,
            when {
                !playerSpeedMenuEnabled -> if (speedMenuAttempted) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                speedMenuComplete -> "verified"
                !speedMenuPlanned -> "cached-attempt"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(PlayerSpeedMenuAdapter.query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    else -> "missing-or-ambiguous-structure"
                }
            })
        val classic = if (commentClassicStyleEnabled) {
            CommentClassicStyleLocator.locate(loader, dexAssist, true, previousClassic, classicMissing)
        } else {
            previousClassic
        }
        val popup = if (playerPopupPromotionEnabled) PlayerPopupPromotionAdapter.locate(loader, dexAssist, true, previousPopup, popupPlanned) else previousPopup
        val followOutcome = StandardDexFeatureAdapter.locateAndDiagnoseDirectOrVerified(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = FollowFeedAdapter.DIAGNOSTIC,
            enabled = followFeedStyleEnabled,
            planned = followPlanned,
            previouslyMissing = previouslyMissingFollow,
            query = DexAssistQuery.FOLLOW_FEED_STYLE,
            plannedFailureOverride = failedDexReason(
                listOf(DexAssistQuery.FOLLOW_FEED_STYLE, DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS)
            ),
            directFoundCheck = { FollowFeedAdapter.direct(it) != null },
            locateBlock = {
                FollowFeedAdapter.locate(loader, dexAssist, followFeedStyleEnabled, previousFollow, followPlanned)
            },
            readyCheck = { FollowFeedAdapter.resolve(loader, it) != null },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.methods.size}" }
        )
        val follow = followOutcome.value
        val followDiagnostic = followOutcome.diagnostic
        val brandOutcome = StandardDexFeatureAdapter.locateAndDiagnoseDirectOrVerified(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = BrandSplashDecoderAdapter.DIAGNOSTIC,
            enabled = brandSplashCustomEnabled,
            planned = brandPlanned,
            previouslyMissing = previouslyMissingBrand,
            query = BrandSplashDecoderAdapter.query,
            directFoundCheck = { BrandSplashDecoderAdapter.direct(it) != null },
            locateBlock = {
                BrandSplashDecoderAdapter.locate(loader, dexAssist, brandSplashCustomEnabled, previousBrand, brandPlanned)
            },
            readyCheck = { BrandSplashDecoderAdapter.resolve(loader, it) != null }
        )
        val brand = brandOutcome.value
        val brandDiagnostic = brandOutcome.diagnostic
        val brandKntrOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC,
            enabled = brandSplashCustomEnabled,
            planned = brandKntrPlanned,
            previouslyMissing = previouslyMissingBrandKntr,
            query = BrandSplashKntrAdapter.query,
            applicableCheck = BrandSplashKntrAdapter::applicable,
            directFoundCheck = { BrandSplashKntrAdapter.direct(it) != null },
            locateBlock = {
                BrandSplashKntrAdapter.locate(loader, dexAssist, brandSplashCustomEnabled, previousBrandKntr, brandKntrPlanned)
            },
            readyCheck = { BrandSplashKntrAdapter.resolve(loader, it) != null }
        )
        val brandKntrDraw = brandKntrOutcome.value
        val brandKntrDiagnostic = brandKntrOutcome.diagnostic
        val brandKntrExitEnabled = brandSplashCustomEnabled || brandSplashSkipEnabled
        val brandKntrExitOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC_EXIT,
            enabled = brandKntrExitEnabled,
            planned = brandKntrExitPlanned,
            previouslyMissing = previouslyMissingBrandKntrExit,
            query = BrandSplashKntrAdapter.queryExit,
            applicableCheck = BrandSplashKntrAdapter::applicable,
            directFoundCheck = { BrandSplashKntrAdapter.directExit(it) != null },
            locateBlock = {
                BrandSplashKntrAdapter.locateExit(loader, dexAssist, brandKntrExitEnabled, previousBrandKntrExit, brandKntrExitPlanned)
            },
            readyCheck = { BrandSplashKntrAdapter.resolveExit(loader, it) != null },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.classes.size + it.methods.size}" }
        )
        val brandKntrExit = brandKntrExitOutcome.value
        val brandKntrExitDiagnostic = brandKntrExitOutcome.diagnostic
        val brandKntrPageOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = BrandSplashKntrAdapter.DIAGNOSTIC_PAGE,
            enabled = brandKntrPageEnabled,
            planned = brandKntrPagePlanned,
            previouslyMissing = previouslyMissingBrandKntrPage,
            query = BrandSplashKntrAdapter.queryPage,
            applicableCheck = BrandSplashKntrAdapter::applicable,
            directFoundCheck = { BrandSplashKntrAdapter.directPage(it) != null },
            locateBlock = {
                BrandSplashKntrAdapter.locatePage(loader, dexAssist, brandKntrPageEnabled, previousBrandKntrPage, brandKntrPagePlanned)
            },
            readyCheck = { BrandSplashKntrAdapter.resolvePage(loader, it) != null }
        )
        val brandKntrPage = brandKntrPageOutcome.value
        val brandKntrPageDiagnostic = brandKntrPageOutcome.diagnostic
        val cloudKeyOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = CloudKeyFreezeAdapter.DIAGNOSTIC,
            enabled = cloudKeyFreezeEnabled,
            planned = cloudKeyContractPlanned,
            previouslyMissing = previouslyMissingCloudKeyContract,
            query = CloudKeyFreezeAdapter.query,
            applicableCheck = CloudKeyFreezeAdapter::applicable,
            directFoundCheck = { CloudKeyFreezeAdapter.direct(it) != null },
            locateBlock = {
                CloudKeyFreezeAdapter.locate(loader, dexAssist, cloudKeyFreezeEnabled, previousCloudKeyContract, cloudKeyContractPlanned)
            },
            readyCheck = { CloudKeyFreezeAdapter.resolve(loader, it) != null }
        )
        val cloudKeyFreezeContract = cloudKeyOutcome.value
        val cloudKeyContractDiagnostic = cloudKeyOutcome.diagnostic
        val popupUnresolved = if (playerPopupPromotionEnabled && popup != null) PlayerPopupPromotionAdapter.missing(loader, popup) else emptySet()
        val popupDiagnostics = PlayerPopupPromotionAdapter.Family.entries.map { family ->
            val found = playerPopupPromotionEnabled && family !in popupUnresolved
            val detail = when {
                !playerPopupPromotionEnabled && family in previouslyMissingPopup -> "feature-disabled:cached-attempt-preserved"
                !playerPopupPromotionEnabled -> "feature-disabled"
                family !in popupMissing -> "direct"
                family in previouslyMissingPopup && family in popupUnresolved && family !in popupPlanned -> "cached-attempt-preserved"
                family !in popupPlanned && found -> "cached-verified"
                found -> "verified"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(family.query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> "unavailable-or-ambiguous:${outcome.methods.size}"
                }
            }
            AdaptDiagnostic(family.diagnostic,
                if (found) AdaptState.FOUND else if (playerPopupPromotionEnabled || family in previouslyMissingPopup) AdaptState.MISSING else AdaptState.NOT_APPLICABLE,
                detail)
        }
        val classicReady = commentClassicStyleEnabled && classic != null &&
            CommentClassicStyleLocator.missing(loader, classic).isEmpty()
        val classicUnresolved = commentClassicStyleEnabled &&
            CommentClassicStyleLocator.missing(loader, classic).isNotEmpty()
        val classicDiagnostic = AdaptDiagnostic(
            CommentClassicStyleLocator.DIAGNOSTIC,
            when {
                classicReady -> AdaptState.FOUND
                classicUnresolved || previouslyMissingClassic -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            },
            when {
                !commentClassicStyleEnabled ->
                    if (previouslyMissingClassic) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                classicReady ->
                    if (classicDirectMissing.isEmpty()) "direct"
                    else if (classicMissing.isEmpty()) "cached-verified"
                    else "verified"
                previouslyMissingClassic && classicMissing.isEmpty() -> "cached-attempt-preserved"
                dexAssist == null && classicMissing.isNotEmpty() -> "no-dex-source"
                else -> failedDexReason(classicMissing.map { it.query }) ?: "unavailable-or-ambiguous"
            }
        )
        val sponsorDiagnostic = AdaptDiagnostic(
            SponsorPlayerLocator.DIAGNOSTIC,
            when {
                sponsorBlockEnabled && sponsor != null -> AdaptState.FOUND
                sponsorNeedsAssist || previouslyMissingSponsor -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            },
            when {
                !sponsorBlockEnabled ->
                    if (previouslyMissingSponsor) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                directSponsor != null -> "direct"
                cachedSponsor != null -> "cached-verified"
                sponsor != null -> "verified"
                previouslyMissingSponsor -> "cached-attempt-preserved"
                sponsorNeedsAssist && dexAssist == null -> "no-dex-source"
                sponsorNeedsAssist -> failedDexReason(SponsorPlayerLocator.queries) ?: "unavailable-or-ambiguous"
                else -> "not-applicable-host"
            }
        )
        val blockUpdateAssist = when {
            blockUpdatePlanned -> locateBlockUpdateByDex(loader, dexAssist)
            directBlockUpdate != null -> BlockUpdateDexAssist(
                point = null,
                diagnostic = AdaptDiagnostic(
                    id = DEX_ASSIST_BLOCK_UPDATE_ID,
                    state = AdaptState.NOT_APPLICABLE,
                    detail = "block-update:not-required"
                )
            )
            // 未请求（功能关闭）或历史已有结论：不查询，缓存里已有的点原样带入，诊断保留既有结论。
            else -> BlockUpdateDexAssist(
                point = assistHistory.blockUpdate,
                diagnostic = AdaptAssistGate.skippedDiagnostic(AdaptAssistGate.Family.BLOCK_UPDATE, blockUpdateEnabled,
                    assistHistory.diagnostic(AdaptAssistGate.Family.BLOCK_UPDATE),
                    assistHistory.attempt(AdaptAssistGate.Family.BLOCK_UPDATE))
            )
        }
        val defaultWordsAssist = if (defaultWordsNeedsAssist) locateKotlinDefaultWordsByDex(dexAssist) else {
            val resolved = kotlinDefaultWordsEnabled && homeTopBar?.kotlinDefaultWords != null
            val diagState = when {
                resolved -> AdaptState.FOUND
                previouslyMissingDefaultWords -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            }
            val diagDetail = when {
                !kotlinDefaultWordsEnabled ->
                    if (previouslyMissingDefaultWords) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                resolved && carriedDefaultWords != null -> "cached-verified"
                resolved -> "direct"
                previouslyMissingDefaultWords -> "cached-attempt-preserved"
                else -> "not-required"
            }
            KotlinDefaultWordsDexAssist(null, AdaptDiagnostic(KotlinDefaultWordsLocator.DIAGNOSTIC, diagState, diagDetail))
        }
        defaultWordsAssist.point?.let { point ->
            homeTopBar = homeTopBar?.copy(kotlinDefaultWords = point)
                ?: HomeTopBarPoints(null, null, null, emptyList(), point)
        }
        val blockUpdate = directBlockUpdate ?: blockUpdateAssist.point
        val dynamicTabs = sync(PointFamily.DYNAMIC_TABS, previous?.dynamicTabs) { locateDynamicTabs(loader) }
        val fullNumbers = sync(PointFamily.FULL_NUMBERS, previous?.fullNumbers) { locateFullNumbers(loader) }
        val playerPortrait = sync(PointFamily.PLAYER_PORTRAIT, previous?.playerPortrait) { locatePlayerPortrait(loader) }
        val playerInteractiveOverlays = sync(PointFamily.PLAYER_INTERACTIVE_OVERLAYS, previous?.playerInteractiveOverlays) {
            locatePlayerInteractiveOverlays(loader)
        }
        val pgcAutoActivityPopup = sync(PointFamily.PGC_AUTO_ACTIVITY_POPUP, previous?.pgcAutoActivityPopup) {
            locatePgcAutoActivityPopup(loader)
        }
        val playerStatusBar = sync(PointFamily.PLAYER_STATUS_BAR, previous?.playerStatusBar) { locatePlayerStatusBar(loader) }
        val homeRecommendFeed = sync(PointFamily.HOME_RECOMMEND_FEED, previous?.homeRecommendFeed) {
            locateHomeRecommendFeed(loader)
        }
        val videoRelate = sync(PointFamily.VIDEO_RELATE, previous?.videoRelate) { locateVideoRelate(loader) }
        val homeTabResourceOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = HomeTabResourceAdapter.DIAGNOSTIC,
            enabled = plan.needs(PointFamily.HOME_TABS),
            planned = homeTabResourcePlanned,
            previouslyMissing = previouslyMissingHomeTabResource,
            query = HomeTabResourceAdapter.query,
            applicableCheck = HomeTabResourceAdapter::applicable,
            directFoundCheck = { directHomeTabs != null },
            locateBlock = {
                directHomeTabs ?: if (plan.needs(PointFamily.HOME_TABS)) {
                    HomeTabResourceAdapter.locate(
                        loader, dexAssist, enabled = true, direct = null,
                        cached = previous?.homeTabs, planned = homeTabResourcePlanned
                    )
                } else null
            },
            readyCheck = { it != null },
            verifiedDetail = { if (!homeTabResourcePlanned) "cached-verified" else "verified" },
            disabledDetail = if (previouslyMissingHomeTabResource) "plan-not-requested:cached-attempt-preserved" else "plan-not-requested"
        )
        val homeTabs = homeTabResourceOutcome.value
        val homeTabResourceDiagnostic = homeTabResourceOutcome.diagnostic
        val homeComponents = sync(PointFamily.HOME_COMPONENTS, previous?.homeComponents) { locateHomeComponents(loader) }
        val mineComponents = sync(PointFamily.MINE_COMPONENTS, previous?.mineComponents) { locateMineComponents(loader, mine) }
        val mineAccountMine = sync(PointFamily.MINE_ACCOUNT_MINE, previous?.mineAccountMine) {
            locateMineAccountMinePoints(loader)
        }
        val storyFeed = sync(PointFamily.STORY_FEED, previous?.storyFeed) { locateStoryFeed(loader) }
        val bottomBar = sync(PointFamily.BOTTOM_BAR, previous?.bottomBar) { locateBottomBar(loader) }
        val playerQualityAssist = when {
            playerQualityPlanned -> locatePlayerQualityByDex(loader, dexAssist)
            !playerQualityNeedsAssist -> PlayerQualityDexAssist(
                method = null,
                diagnostic = playerQualityAssistDiagnostic(AdaptState.NOT_APPLICABLE, "not-required")
            )
            else -> PlayerQualityDexAssist(
                method = null,
                diagnostic = AdaptAssistGate.skippedDiagnostic(AdaptAssistGate.Family.PLAYER_QUALITY, playerQualityEnabled,
                    assistHistory.diagnostic(AdaptAssistGate.Family.PLAYER_QUALITY),
                    assistHistory.attempt(AdaptAssistGate.Family.PLAYER_QUALITY))
            )
        }
        // 兜底实现优先于直接命中的旧包装层；跳过查询时沿用缓存里已验证的兜底实现。
        val carriedQuality = if (playerQualityNeedsAssist && !playerQualityPlanned) assistHistory.assistedQuality else null
        val globalPlayerQuality = playerQualityAssist.method
            ?.let { locateDefaultVideoQuality(loader, assisted = it) }
            ?: carriedQuality
            ?: directPlayerQuality
        val storyQuality = StoryQualityLocator.locate(loader, dexAssist, previousStoryQuality, storyQualityPlanned)
        val effectiveDefaultQuality = globalPlayerQuality?.defaultQualityMethod ?: directPlayerQuality?.defaultQualityMethod
        val normalAutoSwitch = NormalQualityLocator.locateAutoSwitch(
            loader, dexAssist, effectiveDefaultQuality, playerQualityEnabled, previousNormalAutoSwitch, normalAutoSwitchPlanned
        )
        val normalAutoSwitchReady = playerQualityEnabled && NormalQualityLocator.resolveAutoSwitch(loader, normalAutoSwitch) != null
        val normalAutoSwitchDiagnostic = AdaptDiagnostic(
            NormalQualityLocator.DIAGNOSTIC,
            when {
                normalAutoSwitchReady -> AdaptState.FOUND
                playerQualityEnabled && NormalQualityLocator.applicableModern(loader, effectiveDefaultQuality, previousNormalAutoSwitch) &&
                    (normalAutoSwitchPlanned || previouslyMissingNormalAutoSwitch) -> AdaptState.MISSING
                !playerQualityEnabled && previouslyMissingNormalAutoSwitch -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            },
            when {
                !playerQualityEnabled ->
                    if (previouslyMissingNormalAutoSwitch) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                NormalQualityLocator.applicable(effectiveDefaultQuality) -> "legacy-hook-active"
                !NormalQualityLocator.applicableModern(loader, effectiveDefaultQuality, previousNormalAutoSwitch) ->
                    if (previouslyMissingNormalAutoSwitch) "cached-attempt-preserved" else "not-applicable-host"
                normalAutoSwitchReady -> "verified"
                !normalAutoSwitchPlanned -> "cached-attempt-preserved"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(NormalQualityLocator.query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> "unavailable-or-ambiguous:${outcome.methods.size}"
                }
            }
        )
        val playerQuality = globalPlayerQuality?.copy(story = storyQuality, normalAutoSwitch = normalAutoSwitch)
            ?: storyQuality?.let { PlayerQualityPoints(null, story = it, normalAutoSwitch = normalAutoSwitch) }
            ?: normalAutoSwitch?.let { PlayerQualityPoints(null, normalAutoSwitch = it) }
        val storyDexFailure = if (storyQualityPlanned.isEmpty() || storyQuality?.semanticComplete == true) null
            else if (dexAssist == null) "no-dex-source" else failedDexReason(storyQualityPlanned)
        val storyQualityDiagnostic = AdaptDiagnostic(StoryQualityLocator.DIAGNOSTIC,
            if (storyQuality?.semanticComplete == true) AdaptState.FOUND
            else if (playerQualityEnabled || previouslyMissingStoryQuality) AdaptState.MISSING else AdaptState.NOT_APPLICABLE,
            when {
                !playerQualityEnabled -> if (previouslyMissingStoryQuality) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                storyQuality?.semanticComplete == true -> "semantic-roles-complete;runtime-requires-session-admission"
                storyDexFailure != null -> storyDexFailure
                storyQuality == null -> "missing-stable-owner-contract"
                else -> "partial:policy=${storyQuality.policyMethod != null},resource=${storyQuality.resourceSelectionMethod != null},match=${storyQuality.matchPreferenceMethod != null}"
            })
        val teenagersMode = sync(PointFamily.TEENAGERS_MODE, previous?.teenagersMode) { locateTeenagersMode(loader) }
        val quickReplyOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = CommentQuickReplyAdapter.DIAGNOSTIC,
            enabled = commentQuickReplyEnabled,
            planned = commentQuickReplyPlanned,
            previouslyMissing = previouslyMissingCommentQuickReply,
            query = CommentQuickReplyAdapter.query,
            applicableCheck = CommentQuickReplyAdapter::applicable,
            directFoundCheck = { directQuickReplyMethods.isNotEmpty() },
            locateBlock = {
                if (commentQuickReplyEnabled) {
                    CommentQuickReplyAdapter.locate(
                        loader,
                        dexAssist,
                        enabled = true,
                        directMethods = directQuickReplyMethods,
                        cachedMethods = previous?.commentPurify?.quickReplyDialogMethods.orEmpty(),
                        planned = commentQuickReplyPlanned
                    )
                } else emptyList()
            },
            readyCheck = { it.isNotEmpty() },
            verifiedDetail = { if (!commentQuickReplyPlanned) "cached-verified" else "verified" }
        )
        val assistedQuickReplyMethods = quickReplyOutcome.value
        val commentQuickReplyDiagnostic = quickReplyOutcome.diagnostic
        val commentPurify = when {
            !commentQuickReplyEnabled || assistedQuickReplyMethods.isEmpty() -> directCommentPurify
            directCommentPurify == null -> CommentPurifyPoints(
                urlMapGetters = emptyList(),
                emptyPageGetters = emptyList(),
                voteWidgetMethods = emptyList(),
                follow = null,
                qoe = null,
                operations = emptyList(),
                quickReplyDialogMethods = assistedQuickReplyMethods,
                urlSchemaGetters = emptyList()
            )
            directCommentPurify.quickReplyDialogMethods.isNotEmpty() -> directCommentPurify
            else -> directCommentPurify.copy(quickReplyDialogMethods = assistedQuickReplyMethods)
        }
        val pausedCountdownOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN,
            enabled = pausedAdEnabled,
            planned = pausedCountdownPlanned,
            previouslyMissing = previouslyMissingPauseCountdown,
            query = PausedAdDexAdapter.queryCountdown,
            applicableCheck = PausedAdDexAdapter::applicableCountdown,
            directFoundCheck = { PausedAdDexAdapter.directCountdown(it) != null },
            locateBlock = {
                val assisted = if (pausedAdEnabled) {
                    PausedAdDexAdapter.locateCountdown(
                        loader, dexAssist, enabled = true, direct = pause.countdown,
                        cached = previous?.pause?.countdown, planned = pausedCountdownPlanned
                    )
                } else pause.countdown
                pause.countdown ?: assisted
            },
            readyCheck = { PausedAdDexAdapter.resolveCountdown(loader, it) != null },
            verifiedDetail = { if (!pausedCountdownPlanned) "cached-verified" else "verified" }
        )
        val pausedRequestApiOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API,
            enabled = pausedAdEnabled,
            planned = pausedRequestApiPlanned,
            previouslyMissing = previouslyMissingPauseApiEntry,
            query = PausedAdDexAdapter.queryRequestApi,
            applicableCheck = PausedAdDexAdapter::applicableRequestApi,
            directFoundCheck = { PauseRequestCoverage.hasVerifiedApiEntry(it, vc, cachedApiEntry = null) },
            locateBlock = {
                val assisted = if (pausedAdEnabled) {
                    PausedAdDexAdapter.locateRequestApi(
                        loader, vc, dexAssist, enabled = true,
                        cached = previous?.pause?.apiEntry, planned = pausedRequestApiPlanned
                    )
                } else pause.apiEntry
                assisted ?: pause.apiEntry
            },
            readyCheck = { PauseRequestCoverage.resolveApiEntry(loader, it) != null },
            verifiedDetail = { if (!pausedRequestApiPlanned) "cached-verified" else "verified" }
        )
        val mergedPause = PausePoints(
            requestMethods = pause.requestMethods,
            legacyCallback = pause.legacyCallback,
            panelShow = pause.panelShow,
            countdown = pausedCountdownOutcome.value,
            apiEntry = pausedRequestApiOutcome.value
        )
        val pausedCountdownDiagnostic = pausedCountdownOutcome.diagnostic
        val pausedRequestApiDiagnostic = pausedRequestApiOutcome.diagnostic
        val commentFilter = sync(PointFamily.COMMENT_FILTER, previous?.commentFilter) { locateCommentFilter(loader) }
        val topologyAssist = when {
            topologyPlanned -> locateCommentTopologyMapperByDex(loader, dexAssist)
            directTopologyOutcome.points != null -> CommentTopologyDexAssist(
                mapperOwners = emptyList(),
                diagnostic = commentTopologyAssistDiagnostic(
                    AdaptState.NOT_APPLICABLE,
                    "not-required"
                )
            )
            else -> CommentTopologyDexAssist(
                mapperOwners = emptyList(),
                diagnostic = AdaptAssistGate.skippedDiagnostic(AdaptAssistGate.Family.COMMENT_TOPOLOGY, commentTopologyEnabled,
                    assistHistory.diagnostic(AdaptAssistGate.Family.COMMENT_TOPOLOGY),
                    assistHistory.attempt(AdaptAssistGate.Family.COMMENT_TOPOLOGY))
            )
        }
        val commentTopologyOutcome = if (topologyAssist.mapperOwners.isEmpty()) {
            directTopologyOutcome
        } else {
            locateCommentTopologyWithDiagnostic(loader, topologyAssist.mapperOwners, hostVersionCode = vc)
        }
        val commentTopology = commentTopologyOutcome.points
            ?: if (topologyPlanned) null else assistHistory.commentTopology
        val commentComposeMenu = CommentComposeMenuAdapter.locate(
            loader, dexAssist, commentTopologyEnabled, previousComposeMenu, composeMenuPlanned
        )
        val composeMenuResolved = CommentComposeMenuAdapter.resolve(loader, commentComposeMenu)
        val composeMenuDiagnostic = AdaptDiagnostic(
            CommentComposeMenuAdapter.DIAGNOSTIC,
            when {
                !commentTopologyEnabled || !CommentComposeMenuAdapter.applicable(loader) ->
                    if (previouslyMissingComposeMenu) AdaptState.MISSING else AdaptState.NOT_APPLICABLE
                composeMenuResolved.isComplete() -> AdaptState.FOUND
                composeMenuPlanned || previouslyMissingComposeMenu -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            },
            when {
                !commentTopologyEnabled ->
                    if (previouslyMissingComposeMenu) "feature-disabled:cached-attempt-preserved" else "feature-disabled"
                !CommentComposeMenuAdapter.applicable(loader) ->
                    if (previouslyMissingComposeMenu) "cached-attempt-preserved" else "not-applicable-host"
                composeMenuResolved.isComplete() ->
                    "bubble=${composeMenuResolved.bubbleRow != null}," +
                        "grouped=${composeMenuResolved.groupedRow != null}," +
                        "legacy=${composeMenuResolved.legacyRow != null}"
                !composeMenuPlanned -> "cached-attempt-preserved"
                dexAssist == null -> "no-dex-source"
                else -> when (val outcome = dexAssist.result(CommentComposeMenuAdapter.query)) {
                    is DexAssistResult.Unavailable -> outcome.reason.name.lowercase()
                    is DexAssistResult.Candidates -> "incomplete-or-ambiguous:${outcome.methods.size}"
                }
            }
        )
        val kmpGrpcWireOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = KmpGrpcWireAdapter.DIAGNOSTIC,
            enabled = effectiveKmpGrpcWireEnabled,
            planned = kmpGrpcWirePlanned,
            previouslyMissing = previouslyMissingKmpGrpcWire,
            query = KmpGrpcWireAdapter.query,
            applicableCheck = KmpGrpcWireAdapter::applicable,
            directFoundCheck = { KmpGrpcWireAdapter.direct(it) != null },
            locateBlock = {
                KmpGrpcWireAdapter.locate(loader, dexAssist, effectiveKmpGrpcWireEnabled, previousKmpGrpcWire, kmpGrpcWirePlanned)
            },
            readyCheck = { KmpGrpcWireAdapter.resolve(loader, it) != null }
        )
        val kmpGrpcWireEngine = kmpGrpcWireOutcome.value
        val kmpGrpcWireDiagnostic = kmpGrpcWireOutcome.diagnostic
        val privacyIgnetOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = PrivacyChannelAdapter.DIAGNOSTIC_IGNET,
            enabled = privacyIgnetEnabled,
            planned = privacyIgnetPlanned,
            previouslyMissing = previouslyMissingPrivacyIgnet,
            query = PrivacyChannelAdapter.queryIgnet,
            applicableCheck = PrivacyChannelAdapter::applicableIgnet,
            directFoundCheck = { PrivacyChannelAdapter.directIgnet(it) != null },
            locateBlock = {
                PrivacyChannelAdapter.locateIgnet(loader, dexAssist, privacyIgnetEnabled, previousPrivacyIgnet, privacyIgnetPlanned)
            },
            readyCheck = { PrivacyChannelAdapter.resolveIgHttpRequest(loader, it) != null }
        )
        val privacyIgnetHttp = privacyIgnetOutcome.value
        val privacyIgnetDiagnostic = privacyIgnetOutcome.diagnostic
        val privacyKNeuronOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = PrivacyChannelAdapter.DIAGNOSTIC_KNEURON,
            enabled = incognitoPrivacyEnabled,
            planned = privacyKNeuronPlanned,
            previouslyMissing = previouslyMissingPrivacyKNeuron,
            query = PrivacyChannelAdapter.queryKNeuron,
            applicableCheck = PrivacyChannelAdapter::applicableKNeuron,
            directFoundCheck = { PrivacyChannelAdapter.directKNeuron(it) != null },
            locateBlock = {
                PrivacyChannelAdapter.locateKNeuron(loader, dexAssist, incognitoPrivacyEnabled, previousPrivacyKNeuron, privacyKNeuronPlanned)
            },
            readyCheck = { PrivacyChannelAdapter.resolveKNeuron(loader, it) != null }
        )
        val privacyKNeuron = privacyKNeuronOutcome.value
        val privacyKNeuronDiagnostic = privacyKNeuronOutcome.diagnostic
        val guestPlayerCoreOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = GuestPlayerCoreAdapter.DIAGNOSTIC,
            enabled = guestBrowsingEnabled,
            planned = guestPlayerCorePlanned,
            previouslyMissing = previouslyMissingGuestPlayerCore,
            query = GuestPlayerCoreAdapter.query,
            applicableCheck = GuestPlayerCoreAdapter::applicable,
            directFoundCheck = { GuestPlayerCoreAdapter.direct(it) != null },
            locateBlock = {
                GuestPlayerCoreAdapter.locate(loader, dexAssist, guestBrowsingEnabled, previousGuestPlayerCore, guestPlayerCorePlanned)
            },
            readyCheck = { GuestPlayerCoreAdapter.resolve(loader, it) != null }
        )
        val guestPlayerCore = guestPlayerCoreOutcome.value
        val guestPlayerCoreDiagnostic = guestPlayerCoreOutcome.diagnostic
        val detailSpecialTagOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = DetailSpecialTagAdapter.DIAGNOSTIC,
            enabled = detailSpecialTagEnabled,
            planned = detailSpecialTagPlanned,
            previouslyMissing = previouslyMissingDetailSpecialTag,
            query = DetailSpecialTagAdapter.query,
            applicableCheck = DetailSpecialTagAdapter::applicable,
            directFoundCheck = { DetailSpecialTagAdapter.direct(it) != null },
            locateBlock = {
                DetailSpecialTagAdapter.locate(loader, dexAssist, detailSpecialTagEnabled, previousDetailSpecialTag, detailSpecialTagPlanned)
            },
            readyCheck = { DetailSpecialTagAdapter.resolve(loader, it) != null }
        )
        val detailSpecialTagMapper = detailSpecialTagOutcome.value
        val detailSpecialTagDiagnostic = detailSpecialTagOutcome.diagnostic
        val commentReplyButtonOwners = CommentReplyButtonAdapter.locate(
            loader, dexAssist, commentReplyButtonEnabled, previousCommentReplyOwners, commentReplyButtonPlanned
        )
        val resolvedCommentReplyOwners = if (commentReplyButtonEnabled) {
            CommentReplyButtonAdapter.resolveOwners(loader, commentReplyButtonOwners)
        } else {
            emptyMap()
        }
        val commentReplyButtonDiagnostic = StandardDexFeatureAdapter.buildApplicableDiagnostic(
            diagnosticId = CommentReplyButtonAdapter.DIAGNOSTIC,
            enabled = commentReplyButtonEnabled,
            applicable = CommentReplyButtonAdapter.applicable(loader),
            directFound = CommentReplyButtonAdapter.directOwners(loader).size == CommentReplyButtonAdapter.FAMILIES.size,
            ready = commentReplyButtonEnabled && resolvedCommentReplyOwners.size == CommentReplyButtonAdapter.FAMILIES.size,
            planned = commentReplyButtonPlanned,
            previouslyMissing = previouslyMissingCommentReplyOwners,
            dexAssist = dexAssist,
            query = CommentReplyButtonAdapter.query,
            candidateFailureDetail = {
                "incomplete:families=${resolvedCommentReplyOwners.keys.sorted().joinToString("+")}"
            }
        )
        val homeEarlyLoadOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
            enabled = homeRecommendEarlyLoadEnabled,
            planned = homeEarlyLoadPlanned,
            previouslyMissing = previouslyMissingHomeEarlyLoadListener,
            query = HomeRecommendEarlyLoadAdapter.query,
            applicableCheck = HomeRecommendEarlyLoadAdapter::applicable,
            directFoundCheck = { HomeRecommendEarlyLoadAdapter.direct(it) != null },
            locateBlock = {
                HomeRecommendEarlyLoadAdapter.locate(
                    loader, dexAssist, homeRecommendEarlyLoadEnabled, previousHomeEarlyLoadListener, homeEarlyLoadPlanned
                )
            },
            readyCheck = { HomeRecommendEarlyLoadAdapter.resolve(loader, it) != null },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.classes.size}" }
        )
        val homeRecommendEarlyLoadListener = homeEarlyLoadOutcome.value
        val homeEarlyLoadDiagnostic = homeEarlyLoadOutcome.diagnostic
        val aiPlaylistOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = AiPlaylistDirectorAdapter.DIAGNOSTIC,
            enabled = aiDeclaredVideoEnabled,
            planned = aiPlaylistDirectorPlanned,
            previouslyMissing = previouslyMissingAiPlaylistDirector,
            query = AiPlaylistDirectorAdapter.query,
            applicableCheck = AiPlaylistDirectorAdapter::applicable,
            directFoundCheck = { AiPlaylistDirectorAdapter.direct(it) != null },
            locateBlock = {
                AiPlaylistDirectorAdapter.locate(
                    loader, dexAssist, aiDeclaredVideoEnabled, previousAiPlaylistDirector, aiPlaylistDirectorPlanned
                )
            },
            readyCheck = { AiPlaylistDirectorAdapter.resolve(loader, it) != null },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.classes.size}" }
        )
        val aiPlaylistDirector = aiPlaylistOutcome.value
        val aiPlaylistDirectorDiagnostic = aiPlaylistOutcome.diagnostic
        val followUpRecommendBodies = FollowUpRecommendAdapter.locate(
            loader, dexAssist, followUpRecommendEnabled, previousFollowUpBodies, followUpPlanned
        )
        val followUpApplicable = FollowUpRecommendAdapter.applicable(loader)
        val followUpMissingAfterLocate = if (followUpRecommendEnabled && followUpApplicable) {
            FollowUpRecommendAdapter.missingFamilies(loader, followUpRecommendBodies)
        } else {
            emptyList()
        }
        val followUpRecommendDiagnostic = StandardDexFeatureAdapter.buildApplicableDiagnostic(
            diagnosticId = FollowUpRecommendAdapter.DIAGNOSTIC,
            enabled = followUpRecommendEnabled,
            applicable = followUpApplicable,
            directFound = FollowUpRecommendAdapter.missingFamilies(loader, emptyMap()).isEmpty(),
            ready = followUpRecommendEnabled && followUpApplicable && followUpMissingAfterLocate.isEmpty(),
            planned = followUpPlanned,
            previouslyMissing = previouslyMissingFollowUp,
            dexAssist = dexAssist,
            query = FollowUpRecommendAdapter.query,
            candidateFailureDetail = {
                "incomplete:missing=${followUpMissingAfterLocate.joinToString("+") { f -> f.key }}"
            }
        )
        val detailRelateGameOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = DetailRelateGameAdapter.DIAGNOSTIC,
            enabled = detailRelateGameEnabled,
            planned = detailRelateGamePlanned,
            previouslyMissing = previouslyMissingDetailRelateGame,
            query = DetailRelateGameAdapter.query,
            applicableCheck = DetailRelateGameAdapter::applicable,
            directFoundCheck = { DetailRelateGameAdapter.direct(it) != null },
            locateBlock = {
                DetailRelateGameAdapter.locate(
                    loader, dexAssist, detailRelateGameEnabled, previousDetailRelateGame, detailRelateGamePlanned
                )
            },
            readyCheck = { DetailRelateGameAdapter.resolve(loader, it) != null },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.classes.size + it.methods.size}" }
        )
        val detailRelateGameComponent = detailRelateGameOutcome.value
        val detailRelateGameDiagnostic = detailRelateGameOutcome.diagnostic
        val legacyStoryDanmakuOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = LegacyStoryDanmakuAdapter.DIAGNOSTIC,
            enabled = storyDanmakuToggleHidden,
            planned = legacyStoryDanmakuPlanned,
            previouslyMissing = previouslyMissingLegacyStoryBind,
            query = LegacyStoryDanmakuAdapter.query,
            applicableCheck = LegacyStoryDanmakuAdapter::applicable,
            directFoundCheck = { LegacyStoryDanmakuAdapter.direct(it) != null },
            locateBlock = {
                LegacyStoryDanmakuAdapter.locate(
                    loader, dexAssist, storyDanmakuToggleHidden, previousLegacyStoryBind, legacyStoryDanmakuPlanned
                )
            },
            readyCheck = { LegacyStoryDanmakuAdapter.resolve(loader, it) != null }
        )
        val legacyStoryDanmakuBind = legacyStoryDanmakuOutcome.value
        val legacyStoryDanmakuDiagnostic = legacyStoryDanmakuOutcome.diagnostic
        val theseus2SpeedService = PlayerSpeedLocator.theseus2LongPressService(loader)
        val theseus2LongPressContinuation = KavaMemberLookup.classOrNull(loader, "kotlin.coroutines.Continuation")
        val theseus2LongPressOutcome = StandardDexFeatureAdapter.locateAndDiagnoseApplicable(
            loader = loader,
            dexAssist = dexAssist,
            diagnosticId = PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC,
            enabled = playerSpeedLongPressEnabled,
            planned = theseus2LongPressPlanned,
            previouslyMissing = previouslyMissingTheseus2LongPress,
            query = DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS,
            applicableCheck = { theseus2SpeedService != null && theseus2LongPressContinuation != null },
            directFoundCheck = {
                theseus2SpeedService != null &&
                    PlayerSpeedLocator.theseus2LongPress(it, theseus2SpeedService, cached = null) != null
            },
            locateBlock = {
                PlayerSpeedLocator.locateTheseus2LongPress(
                    loader, dexAssist, playerSpeedLongPressEnabled, previousTheseus2LongPress, theseus2LongPressPlanned
                )
            },
            readyCheck = { point ->
                theseus2SpeedService != null && point != null &&
                    PlayerSpeedLocator.resolveTheseus2LongPress(
                        loader, theseus2SpeedService, point, theseus2LongPressContinuation
                    ) != null
            },
            candidateFailureDetail = { "unavailable-or-ambiguous:${it.classes.size + it.methods.size}" }
        )
        val playerSpeedTheseus2LongPress = theseus2LongPressOutcome.value
        val theseus2LongPressDiagnostic = theseus2LongPressOutcome.diagnostic
        val commentSection = sync(PointFamily.COMMENT_SECTION, previous?.commentSection) { locateCommentSection(loader) }
        val splashAds = sync(PointFamily.SPLASH_ADS, previous?.splashAds) { locateSplashAds(loader) }
        // 只有所有点都落空时才需要；改成惰性求值，避免每轮适配白白探测上百个候选类。
        val anyClassExists by lazy {
            CommentLocator.COMMENT_LOW_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_HIGH_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MineAndHomeUiLocator.HOME_TOP_BAR_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.MINE_FRAGMENT_CLASS)
                || KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.DYNAMIC_MEDIATOR_FRAGMENT_CLASS)
                || MineAndHomeUiLocator.FULL_NUMBER_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MediaQualityAndPlayerLocator.PLAYER_PORTRAIT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_CANDIDATE_CLASSES.any { KavaMemberLookup.hasClass(loader, it) }
                || MediaQualityAndPlayerLocator.PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || HomeRecommendFeedLocator.PEGASUS_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || VideoRelateLocator.VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || MineAndHomeUiLocator.HOME_FRAGMENT_V2_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MineAndHomeUiLocator.MINE_MENU_GROUP_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.STORY_DETAIL_CLASS)
                || MineAndHomeUiLocator.BOTTOM_TAB_HOST_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MediaQualityAndPlayerLocator.PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || MediaQualityAndPlayerLocator.TEENAGERS_MODE_ACTIVITY_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_CONTENT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || CommentLocator.COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || CommentLocator.COMMENT_REPLY_INFO_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || CommentLocator.THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.any {
                    KavaMemberLookup.hasClass(loader, it)
                }
                || MediaQualityAndPlayerLocator.SPLASH_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
                || MediaQualityAndPlayerLocator.BLOCK_UPDATE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        if (low == null && high == null && mine == null &&
            mergedPause.requestMethods.isEmpty() && mergedPause.legacyCallback == null &&
            mergedPause.panelShow == null && mergedPause.countdown == null && mergedPause.apiEntry == null &&
            banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            playerInteractiveOverlays == null && pgcAutoActivityPopup == null &&
            playerStatusBar == null && homeRecommendFeed == null && videoRelate == null &&
            homeTabs == null && homeComponents == null && mineComponents == null &&
            mineAccountMine == null && storyFeed == null && bottomBar == null &&
            playerQuality == null && teenagersMode == null && commentPurify == null &&
            commentFilter == null && commentTopology == null && commentSection == null &&
            splashAds == null &&
            !anyClassExists) return@withLookupBatch null
        val protocolFingerprint = ProtocolFingerprintBuilder.buildProtocolStructureFingerprint(
            loader, homeRecommendFeed, videoRelate, commentFilter, playerQuality,
            splashAds, mineAccountMine
        )
        val located = AdaptResult(
            biliVersionCode = vc,
            ts = System.currentTimeMillis(),
            commentLow = low,
            commentHigh = high,
            mineEntry = mine,
            pause = mergedPause,
            banner = banner,
            homeTopBar = homeTopBar,
            mineVip = mineVip,
            blockUpdate = blockUpdate,
            dynamicTabs = dynamicTabs,
            fullNumbers = fullNumbers,
            playerPortrait = playerPortrait,
            playerInteractiveOverlays = playerInteractiveOverlays,
            pgcAutoActivityPopup = pgcAutoActivityPopup,
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
            hostFingerprint = resolvedHostFingerprint,
            protocolFingerprint = protocolFingerprint.value,
            diagnostics = emptyList(),
            dexSourceFingerprint = dexSource?.value ?: DEX_SOURCE_UNAVAILABLE,
            sponsorPlayer = if (sponsorBlockEnabled) sponsor?.classes else previousSponsor,
            playerSpeedSession = speedSession,
            playerSpeedCalculator = speedCalculator,
            weChatShare = weChatShare,
            commentClassicStyle = classic,
            playerPopupPromotion = popup,
            brandSplashDecoder = brand,
            followFeedStyle = follow,
            playerSpeedMenu = speedMenu,
            pageBoundaries = pages,
            homeChrome = chrome,
            homeChromeSemantics = chromeSemantics,
            systemEnvironment = systemEnvironment,
            commentComposeMenu = commentComposeMenu,
            kmpGrpcWireEngine = kmpGrpcWireEngine,
            brandSplashKntrDraw = brandKntrDraw,
            brandSplashKntrExit = brandKntrExit,
            brandSplashKntrPage = brandKntrPage,
            cloudKeyFreezeContract = cloudKeyFreezeContract,
            privacyIgnetHttp = privacyIgnetHttp,
            privacyKNeuron = privacyKNeuron,
            guestPlayerCore = guestPlayerCore,
            detailSpecialTagMapper = detailSpecialTagMapper,
            aiPlaylistDirector = aiPlaylistDirector,
            followUpRecommendBodies = followUpRecommendBodies,
            detailRelateGameComponent = detailRelateGameComponent,
            commentReplyButtonOwners = commentReplyButtonOwners,
            homeRecommendEarlyLoadListener = homeRecommendEarlyLoadListener,
            legacyStoryDanmakuBind = legacyStoryDanmakuBind,
            playerSpeedTheseus2LongPress = playerSpeedTheseus2LongPress,
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
        val diagnostics = buildDiagnostics(loader, plan, previous, located, commentTopologyOutcome.failureDetail, vc) +
            protocolFingerprint.toDiagnostic(plan) + blockUpdateAssist.diagnostic +
            topologyAssist.diagnostic + playerQualityAssist.diagnostic + defaultWordsAssist.diagnostic + sponsorDiagnostic + speedSessionDiagnostic + speedCalculatorDiagnostic + weChatShareDiagnostic + classicDiagnostic + popupDiagnostics + brandDiagnostic + brandKntrDiagnostic + brandKntrExitDiagnostic + brandKntrPageDiagnostic + cloudKeyContractDiagnostic + followDiagnostic + storyQualityDiagnostic + normalAutoSwitchDiagnostic + speedMenuDiagnostic + pageDiagnostics + chromeDiagnostics + semanticsDiagnostic + systemEnvironmentDiagnostics + composeMenuDiagnostic + kmpGrpcWireDiagnostic + privacyIgnetDiagnostic + privacyKNeuronDiagnostic + guestPlayerCoreDiagnostic + detailSpecialTagDiagnostic + commentReplyButtonDiagnostic + homeEarlyLoadDiagnostic + aiPlaylistDirectorDiagnostic + followUpRecommendDiagnostic + detailRelateGameDiagnostic + legacyStoryDanmakuDiagnostic + theseus2LongPressDiagnostic + homeTabResourceDiagnostic + commentQuickReplyDiagnostic + pausedCountdownDiagnostic + pausedRequestApiDiagnostic
        located.copy(
            diagnostics = diagnostics,
            coverage = coverageOf(located, locators, plan, previous?.coverage)
        )
    }

    /**
     * 一轮适配之后各家族的尝试状态。计划内：带点即 FOUND，定位器抛异常且没点为 RETRYABLE，否则确认缺失；
     * 计划外：沿用此前的结论，但只在这份结果里还带着对应的点、或此前是缺失/可重试时才沿用——
     * 点已经丢了就不能再说「找到」，只能退回未请求。局部结果因此永远不会被标成全量完成。
     */
    internal fun coverageOf(
        result: AdaptResult,
        locators: SyncLocatorRun,
        plan: AdaptPlan,
        previous: AdaptCoverage?
    ): AdaptCoverage = AdaptCoverage(PointFamily.entries.associateWith { family ->
        val prior = previous?.attempt(family) ?: AdaptAssistGate.Attempt.NOT_REQUESTED
        when {
            plan.needs(family) -> when {
                result.hasPoint(family) -> AdaptAssistGate.Attempt.FOUND
                locators.attemptOf(family) == AdaptAssistGate.Attempt.RETRYABLE -> AdaptAssistGate.Attempt.RETRYABLE
                else -> AdaptAssistGate.Attempt.CONCLUSIVE_MISSING
            }
            result.hasPoint(family) ->
                if (prior == AdaptAssistGate.Attempt.NOT_REQUESTED) prior else AdaptAssistGate.Attempt.FOUND
            prior == AdaptAssistGate.Attempt.CONCLUSIVE_MISSING || prior == AdaptAssistGate.Attempt.RETRYABLE -> prior
            else -> AdaptAssistGate.Attempt.NOT_REQUESTED
        }
    })

    /**
     * 每个逻辑 Hook 点的定位诊断。计划外（[plan] 没有要求）的家族不探测候选类，直接记为
     * 「未请求」：沿用 [previous] 里已有的诊断（此前真的尝试过），否则写 `NOT_APPLICABLE` + [PLAN_NOT_REQUESTED]。
     * 未请求不是缺失，也不为了凑齐诊断去重新探测关闭的功能。
     *
     * [hostVersionCode] 决定候选存在性是否把当前构建的已验真 owner（[MediaQualityAndPlayerLocator.VERIFIED_MEDIA_OWNERS]）算进去。
     * 已验真分支（[locateBlockUpdate] / [locateDefaultVideoQuality]）只按 owner 名字查一次，
     * 类缺失或签名不符都直接返回 null，不再回候选表；不并进来就会把这两种真实的适配失败
     * 误记成 `NOT_APPLICABLE`（宿主没有该结构），掩盖问题。登记了 owner 即按「结构存在」处理，
     * 因为验真证据来自该构建的真实 dex，而不是「现在按名字还能不能加载到」。
     * 为 null 时维持只看候选表的旧口径。
     */
    internal fun buildDiagnostics(
        loader: ClassLoader,
        plan: AdaptPlan,
        previous: AdaptResult?,
        r: AdaptResult,
        commentTopologyFailureDetail: String,
        hostVersionCode: Int? = null
    ): List<AdaptDiagnostic> {
        val low = r.commentLow
        val high = r.commentHigh
        val mine = r.mineEntry
        val pause = r.pause
        val banner = r.banner
        val homeTopBar = r.homeTopBar
        val mineVip = r.mineVip
        val blockUpdate = r.blockUpdate
        val dynamicTabs = r.dynamicTabs
        val fullNumbers = r.fullNumbers
        val playerPortrait = r.playerPortrait
        val playerInteractiveOverlays = r.playerInteractiveOverlays
        val pgcAutoActivityPopup = r.pgcAutoActivityPopup
        val playerStatusBar = r.playerStatusBar
        val homeRecommendFeed = r.homeRecommendFeed
        val videoRelate = r.videoRelate
        val homeTabs = r.homeTabs
        val homeComponents = r.homeComponents
        val mineComponents = r.mineComponents
        val storyFeed = r.storyFeed
        val bottomBar = r.bottomBar
        val playerQuality = r.playerQuality
        val teenagersMode = r.teenagersMode
        val commentPurify = r.commentPurify
        val commentFilter = r.commentFilter
        val commentTopology = r.commentTopology
        val commentSection = r.commentSection
        val splashAds = r.splashAds
        fun stateFor(pointFound: Boolean, candidateExists: Boolean): AdaptState = when {
            pointFound -> AdaptState.FOUND
            candidateExists -> AdaptState.MISSING
            else -> AdaptState.NOT_APPLICABLE
        }

        val previousCoverage = previous?.coverage
        val previousDiagnostics = if (previousCoverage != null) {
            previous.diagnosticsById
        } else {
            emptyMap()
        }
        // 当前构建已验真的媒体 owner；候选存在性必须并进来，否则已验真分支失败会被记成「无此结构」。
        val verifiedBlockUpdateOwner = hostVersionCode?.let { MediaQualityAndPlayerLocator.VERIFIED_MEDIA_OWNERS[it]?.first }
        val verifiedQualityOwner = hostVersionCode?.let { MediaQualityAndPlayerLocator.VERIFIED_MEDIA_OWNERS[it]?.second }

        /** 计划内才求值状态与详情；计划外直接写未请求（或沿用此前真实尝试过的诊断）。 */
        fun item(family: PointFamily, id: String, state: () -> AdaptState, detail: () -> String): AdaptDiagnostic =
            if (plan.needs(family)) {
                AdaptDiagnostic(id, state(), detail())
            } else {
                previousDiagnostics[id]?.takeIf {
                    previousCoverage?.attempt(family) != AdaptAssistGate.Attempt.NOT_REQUESTED
                } ?: AdaptDiagnostic(id, AdaptState.NOT_APPLICABLE, PLAN_NOT_REQUESTED)
            }

        val lowCandidateExists by lazy {
            CommentLocator.COMMENT_LOW_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val highCandidateExists by lazy {
            CommentLocator.COMMENT_HIGH_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val mineCandidateExists by lazy { KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.MINE_FRAGMENT_CLASS) }
        val mineVipCandidateExists by lazy {
            mineCandidateExists && KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.MINE_VIP_MANAGER_CLASS)
        }
        val blockUpdateCandidateExists by lazy {
            // 已验真 owner 按登记算存在：验真证据来自该构建真实 dex，类名漂移/签名不符都是适配失败，
            // 不能因为「现在按名字加载不到」就退回「宿主无此结构」。
            verifiedBlockUpdateOwner != null ||
                MediaQualityAndPlayerLocator.BLOCK_UPDATE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val dynamicTabsCandidateExists by lazy {
            KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.DYNAMIC_MEDIATOR_FRAGMENT_CLASS) &&
                KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.DYNAMIC_MEDIATOR_TAB_CLASS)
        }
        val fullNumbersCandidateExists by lazy {
            MineAndHomeUiLocator.FULL_NUMBER_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val playerPortraitCandidateExists by lazy {
            MediaQualityAndPlayerLocator.PLAYER_PORTRAIT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val playerInteractiveCandidateExists by lazy {
            MediaQualityAndPlayerLocator.PLAYER_INTERACTIVE_CANDIDATE_CLASSES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val playerStatusBarCandidateExists by lazy {
            MediaQualityAndPlayerLocator.PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val homeRecommendFeedCandidateExists by lazy {
            KavaMemberLookup.hasClass(loader, HomeRecommendFeedLocator.PEGASUS_HOLDER_DATA_CLASS) &&
                HomeRecommendFeedLocator.PEGASUS_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val videoRelateCandidateExists by lazy {
            VideoRelateLocator.VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val homeTabsCandidateExists by lazy {
            MineAndHomeUiLocator.HOME_FRAGMENT_V2_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val homeComponentsCandidateExists by lazy { KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.HOST_FRAGMENT_CLASS) }
        val mineComponentsCandidateExists by lazy {
            MineAndHomeUiLocator.MINE_MENU_GROUP_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val storyFeedCandidateExists by lazy {
            KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.STORY_DETAIL_CLASS) &&
                (KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.STORY_FEED_RESPONSE_CLASS) ||
                    KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.STORY_PAGER_PLAYER_CLASS))
        }
        val bottomBarCandidateExists by lazy {
            MineAndHomeUiLocator.BOTTOM_TAB_HOST_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val playerQualityCandidateExists by lazy {
            verifiedQualityOwner != null ||
                MediaQualityAndPlayerLocator.PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val teenagersModeCandidateExists by lazy {
            MediaQualityAndPlayerLocator.TEENAGERS_MODE_ACTIVITY_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentPurifyCandidateExists by lazy {
            CommentLocator.COMMENT_CONTENT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentEmptyPageCandidateExists by lazy {
            CommentLocator.COMMENT_EMPTY_PAGE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentVoteCandidateExists by lazy {
            CommentLocator.COMMENT_VOTE_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentFollowCandidateExists by lazy {
            CommentLocator.COMMENT_FOLLOW_WIDGET_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) } ||
                CommentLocator.COMMENT_HEADER_DECORATIVE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentMainListCandidateExists by lazy {
            CommentLocator.COMMENT_MAIN_LIST_REPLY_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentQuickReplyCandidateExists by lazy {
            CommentLocator.COMMENT_QUICK_REPLY_COLLECTOR_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentFilterCandidateExists by lazy {
            CommentLocator.COMMENT_REPLY_INFO_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val commentTopologyCandidateExists by lazy {
            commentFilterCandidateExists &&
                KavaMemberLookup.hasClass(loader, CommentTopologyLocator.COMMENT_ITEM_CLASS) &&
                (CommentTopologyLocator.COMMENT_REPLY_MAPPER_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) } ||
                    (hostVersionCode != null && CommentTopologyLocator.VERIFIED_COMMENT_REPLY_MAPPERS.containsKey(hostVersionCode)))
        }
        val commentSectionCandidateExists by lazy {
            CommentLocator.THESEUS_TAB_PAGER_SERVICE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val splashCandidateExists by lazy {
            MediaQualityAndPlayerLocator.SPLASH_RESPONSE_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val pauseCandidateExists by lazy {
            listOf(
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
                "com.bilibili.ship.theseus.united.page.pausedpage." +
                    "PausedPageService\$requestPausedPageData\$2"
            ).any { KavaMemberLookup.hasClass(loader, it) }
        }
        val panelCandidateExists by lazy {
            KavaMemberLookup.hasClass(loader, "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository")
        }
        val countdownCandidateExists by lazy {
            KavaMemberLookup.hasClass(
                loader,
                "com.bilibili.ship.theseus.united.page.pausedpage." +
                    "PausedPageService\$showPauseBarCountdownToast\$3"
            )
        }
        val bannerCandidateExists by lazy {
            KavaMemberLookup.hasClass(loader, "com.bilibili.pegasus.holders.bannerv8.V8Banner")
        }
        val gameMenuCandidateExists by lazy { KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.HOME_MENU_ITEM_CLASS) }
        val composeGameCandidateExists by lazy { KavaMemberLookup.hasClass(loader, MineAndHomeUiLocator.HOME_TOP_RIGHT_LAMBDA_CLASS) }
        val searchCandidateExists by lazy {
            MineAndHomeUiLocator.HOME_MAIN_FRAGMENT_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        }
        val searchReady = homeTopBar?.baseOnViewCreated != null &&
            homeTopBar.defaultWordMethods.isNotEmpty()
        return listOf(
            item(PointFamily.COMMENT_LOW, "comment.low",
                { stateFor(low != null, lowCandidateExists) }) { low?.label().orEmpty() },
            item(PointFamily.COMMENT_HIGH, "comment.high",
                { stateFor(high != null, highCandidateExists) }) { high?.label().orEmpty() },
            item(PointFamily.MINE_ENTRY, "mine.entry",
                { stateFor(mine != null, mineCandidateExists) }) {
                mine?.let { "build=${it.buildMethods.size},groups=${it.groupListField}" }.orEmpty()
            },
            item(PointFamily.PAUSE, "paused.request",
                { stateFor(pause.requestMethods.isNotEmpty(), pauseCandidateExists) }) {
                pause.requestMethods.joinToString("|") { it.label() }
            },
            item(PointFamily.PAUSE, "paused.panel",
                { stateFor(pause.panelShow != null, panelCandidateExists) }) { pause.panelShow?.label().orEmpty() },
            item(PointFamily.PAUSE, "paused.countdown",
                { stateFor(pause.countdown != null, countdownCandidateExists) }) { pause.countdown?.label().orEmpty() },
            item(PointFamily.BANNER, "home.banner",
                { stateFor(banner != null, bannerCandidateExists) }) {
                banner?.let { "${it.bannerClassName},hooks=${it.lifecycleMethods.size}" }.orEmpty()
            },
            item(PointFamily.HOME_TOP_BAR, "home.top_bar.game",
                { stateFor(homeTopBar?.gameMenu != null, gameMenuCandidateExists) }) {
                homeTopBar?.gameMenu?.label().orEmpty()
            },
            // 两条路径分开上报：合并成一条就没法在 Compose 顶栏放量时分因。
            item(PointFamily.HOME_TOP_BAR, "home.top_bar.game_compose",
                { stateFor(homeTopBar?.composeGameMenu != null, composeGameCandidateExists) }) {
                homeTopBar?.composeGameMenu?.label().orEmpty()
            },
            item(PointFamily.HOME_TOP_BAR, "home.top_bar.search",
                { stateFor(searchReady, searchCandidateExists) }) {
                homeTopBar?.let {
                    "view=${it.baseOnViewCreated?.label().orEmpty()},words=${it.defaultWordMethods.size}"
                }.orEmpty()
            },
            item(PointFamily.MINE_VIP, "mine.vip",
                { stateFor(mineVip != null, mineVipCandidateExists) }) {
                mineVip?.let {
                    "resume=${it.onResume.label()},binding=${it.bindingField}," +
                        "root=${it.rootGetter.label()}"
                }.orEmpty()
            },
            item(PointFamily.BLOCK_UPDATE, "update.block",
                { stateFor(blockUpdate != null, blockUpdateCandidateExists) }) { blockUpdate?.label().orEmpty() },
            item(PointFamily.DYNAMIC_TABS, "dynamic.tabs",
                { stateFor(dynamicTabs != null, dynamicTabsCandidateExists) }) {
                dynamicTabs?.let {
                    "list=${it.listGetter.label()},item=${it.itemClassName}#" +
                        "${it.itemTitleField}/${it.itemNameField}"
                }.orEmpty()
            },
            item(PointFamily.FULL_NUMBERS, "number.full",
                { stateFor(fullNumbers != null, fullNumbersCandidateExists) }) {
                fullNumbers?.formatterMethods?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.PLAYER_PORTRAIT, "player.portrait",
                { stateFor(playerPortrait != null, playerPortraitCandidateExists) }) {
                playerPortrait?.visibilityMethods?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.PLAYER_INTERACTIVE_OVERLAYS, "player.interactive_overlay",
                { stateFor(playerInteractiveOverlays != null, playerInteractiveCandidateExists) }) {
                playerInteractiveOverlays?.let { points ->
                    "families=${points.families.size}," +
                        "clears=${points.families.sumOf { it.guideClears.size }}," +
                        "dm=${points.families.sumOf { it.dmClears.size }}," +
                        "moss=${points.mossExecutes.size}," +
                        "command=${points.commandClear != null}," +
                        "activity=${points.commandActivityMetaClear != null}"
                }.orEmpty()
            },
            item(PointFamily.PGC_AUTO_ACTIVITY_POPUP, "pgc.auto_activity_popup",
                {
                    stateFor(
                        pgcAutoActivityPopup != null,
                        KavaMemberLookup.hasClass(loader, PgcAutoActivityPopupLocator.MODEL_CLASS)
                    )
                }) {
                pgcAutoActivityPopup?.let { "construct:slot=${it.popupIndex}" }.orEmpty()
            },
            item(PointFamily.PLAYER_STATUS_BAR, "player.status_bar",
                { stateFor(playerStatusBar != null, playerStatusBarCandidateExists) }) {
                playerStatusBar?.onCreateMethods?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.HOME_RECOMMEND_FEED, "home.recommend_feed",
                { stateFor(homeRecommendFeed != null, homeRecommendFeedCandidateExists) }) {
                homeRecommendFeed?.let {
                    "contract=app-card-v1,responses=${it.responseItemGetters.size}," +
                        "signals=${listOfNotNull(it.cardTypeGetter, it.cardGotoGetter, it.goToGetter, it.adInfoGetter).size}," +
                        "duration=${it.playerArgsDurationGetter != null || !it.playerArgsDurationField.isNullOrBlank()}," +
                        "intent=${it.intentHandlerOnCreate != null}"
                }.orEmpty()
            },
            item(PointFamily.VIDEO_RELATE, "video.relate",
                { stateFor(videoRelate != null, videoRelateCandidateExists) }) {
                videoRelate?.let {
                    "contract=viewunite-relate,responses=${it.responseItemGetters.size},types=" +
                        (it.cardCaseGetters.size + it.gotoGetters.size +
                            it.cardTypeGetters.size + it.relateCardTypeGetters.size +
                            it.fromSourceTypeGetters.size + it.fromSourceTypeChains.size +
                            it.relateCardTypeValueGetters.size) +
                        ",reasons=${it.reasonChains.size},commercial=" +
                        it.commercialEvidenceChains.size +
                        ",writeback=${it.responseItemGetters.count { point -> !point.viewField.isNullOrBlank() }}" +
                        ",service=${it.detailRelateService != null}"
                }.orEmpty()
            },
            item(PointFamily.HOME_TABS, "home.tabs",
                { stateFor(homeTabs != null, homeTabsCandidateExists) }) {
                homeTabs?.let { "${it.buildMethod.label()},resource=${it.resourceClassName}" }
                    .orEmpty()
            },
            item(PointFamily.HOME_COMPONENTS, "home.components",
                { stateFor(homeComponents != null, homeComponentsCandidateExists) }) {
                homeComponents?.onViewCreated?.label().orEmpty()
            },
            item(PointFamily.MINE_COMPONENTS, "mine.components",
                { stateFor(mineComponents != null, mineComponentsCandidateExists) }) {
                mineComponents?.let {
                    "lists=${it.itemListGetters.size},titles=${it.itemTitleGetters.size}," +
                        "legacy=${it.legacyBuildMethods.size}"
                }.orEmpty()
            },
            item(PointFamily.STORY_FEED, "story.feed",
                { stateFor(storyFeed != null, storyFeedCandidateExists) }) {
                storyFeed?.let {
                    "responses=${it.responseItemGetters.size},pager=${it.pagerListMethods.size}," +
                        "ad=${it.adGetter != null},live=${it.liveGetter != null}," +
                        "game=${it.gameGetter != null},bangumi=${it.bangumiGetter != null}," +
                        "course=${it.courseGetter != null},music=${it.musicGetter != null}," +
                        "cart=${it.cartInfoGetter != null},drama=${it.dramaPromptGetter != null}," +
                        "season=${it.seasonInfoGetter != null && it.seasonTypeGetter != null}"
                }.orEmpty()
            },
            item(PointFamily.BOTTOM_BAR, "home.bottom_bar",
                { stateFor(bottomBar != null, bottomBarCandidateExists) }) {
                bottomBar?.let {
                    "tabs=${it.tabsGetter.label()},bind=${it.bindTabMethod.label()}," +
                        "strings=${it.itemStringFields.size}"
                }.orEmpty()
            },
            item(PointFamily.PLAYER_QUALITY, "player.default_quality",
                { stateFor(playerQuality?.defaultQualityMethod != null, playerQualityCandidateExists) }) {
                playerQuality?.let {
                    "global-default=${it.defaultQualityMethod != null},story-semantic=${it.story?.semanticComplete == true},capabilities=" +
                        it.capabilitySignals.joinToString("+").ifBlank { "unobserved" }
                }.orEmpty()
            },
            item(PointFamily.TEENAGERS_MODE, "teenagers.mode",
                { stateFor(teenagersMode != null, teenagersModeCandidateExists) }) {
                teenagersMode?.onCreateMethods?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.search",
                { stateFor(commentPurify?.urlMapGetters?.isNotEmpty() == true, commentPurifyCandidateExists) }) {
                commentPurify?.urlMapGetters?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.empty_page",
                { stateFor(commentPurify?.emptyPageGetters?.isNotEmpty() == true, commentEmptyPageCandidateExists) }) {
                commentPurify?.emptyPageGetters?.joinToString("|") {
                    "${it.contentGetter.label()}->${it.defaultInstanceGetter.label()}"
                }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.vote",
                { stateFor(commentPurify?.voteWidgetMethods?.isNotEmpty() == true, commentVoteCandidateExists) }) {
                commentPurify?.voteWidgetMethods?.joinToString("|") { it.label() }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.follow",
                { stateFor(commentPurify?.follow != null, commentFollowCandidateExists) }) {
                commentPurify?.follow?.let { follow ->
                    "widget=${follow.widgetStateMethods.joinToString("|") { it.label() }};" +
                        "header=${follow.headerBindMethods.joinToString("|") { it.label() }};" +
                        "button=${follow.followButtonClassName.orEmpty()}"
                }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.qoe",
                { stateFor(commentPurify?.qoe != null, commentMainListCandidateExists) }) {
                commentPurify?.qoe?.let { qoe ->
                    "has=${qoe.presenceGetter.label()},get=${qoe.contentGetter.label()}," +
                        "default=${qoe.defaultInstanceGetter.label()}"
                }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.purify.operation",
                { stateFor(commentPurify?.operations?.size == 2, commentMainListCandidateExists) }) {
                commentPurify?.operations?.joinToString("|") { operation ->
                    "${operation.presenceGetter.methodName}/" +
                        "${operation.contentGetter.methodName}->" +
                        operation.defaultInstanceGetter.className
                }.orEmpty()
            },
            item(PointFamily.COMMENT_PURIFY, "comment.quick_reply",
                { stateFor(commentPurify?.quickReplyDialogMethods?.isNotEmpty() == true, commentQuickReplyCandidateExists) }) {
                commentPurify?.quickReplyDialogMethods
                    ?.joinToString("|") { it.label() }
                    .orEmpty()
            },
            item(PointFamily.COMMENT_FILTER, "comment.filter",
                { stateFor(commentFilter != null, commentFilterCandidateExists) }) {
                commentFilter?.let { points ->
                    "contract=reply-v1,lists=${points.replyListGetters.size}," +
                        "top=${points.topReplyGetters.size}," +
                        "level_paths=${listOfNotNull(points.levelGetter, points.memberV2LevelGetter).size}," +
                        "at=${points.atNameMapGetter != null}," +
                        "author_paths=${
                            listOfNotNull(
                                points.memberNameGetter,
                                points.memberMidGetter,
                                points.memberV2NameGetter,
                                points.memberV2MidGetter
                            ).size
                        }"
                }.orEmpty()
            },
            item(PointFamily.COMMENT_TOPOLOGY, "comment.topology",
                { stateFor(commentTopology != null, commentTopologyCandidateExists) }) {
                commentTopology?.let { points ->
                    "mappers=${points.mapperMethods.size}[" +
                        points.mapperMethods.joinToString("|") { it.label() } +
                        "],methods=${points.methods.size}"
                } ?: commentTopologyFailureDetail
            },
            item(PointFamily.COMMENT_SECTION, "comment.section",
                { stateFor(commentSection != null, commentSectionCandidateExists) }) {
                commentSection?.let { points ->
                    "constructors=${points.listConstructors.size}," +
                        "tag=${points.locatableTagGetter.label()}"
                }.orEmpty()
            },
            item(PointFamily.SPLASH_ADS, "splash.ads",
                { stateFor(splashAds != null, splashCandidateExists) }) {
                splashAds?.let {
                    "contract=splash-v1,lists=${it.listGetters.size}," +
                        "signals=${it.itemSignalGetters.map(SplashItemSignalPoint::role).distinct().size}"
                }.orEmpty()
            }
        )
    }

    /**
     * 结构化定位“我的”页入口。已确认的字段漂移：
     * 8.90.2/9.8.0 由运行时结构匹配；9.1.1=of+n1/m1、9.2.0=pf+m1/l1、
     * 9.3.0=nf+u0/t0。只依赖方法参数、List 和 RecyclerView.Adapter 类型。
     */
    fun locateMineEntry(loader: ClassLoader, allowModuleInstance: Boolean = false): MineEntryPoint? =
        MineAndHomeUiLocator.locateMineEntry(loader, allowModuleInstance)

    /**
     * 结构化定位“我的”页 VIP 卡片。8.90.2、9.1.0 与 9.9.0 的混淆字段名不同，
     * 但 Fragment 持有唯一 MineVipModuleManager、管理器持有唯一 ViewBinding，且
     * ViewBinding 暴露无参 getRoot。只在适配期扫描并缓存精确成员描述。
     */
    fun locateMineVip(loader: ClassLoader): MineVipPoint? =
        MineAndHomeUiLocator.locateMineVip(loader)

    /**
     * 定位官方客户端更新信息同步入口。候选类来自 8.90.2 与 9.1.0–9.11.0 的离线字符串/
     * 签名交叉验证；运行期仍要求精确的 (Context) -> BiliUpgradeInfo 结构。
     * 8.90.2 同时存在接口桥接 a(Context) 与真实网络实现 c(Context)，优先选择没有被接口
     * 声明的叶子方法；新版本只有一个实现时直接采用唯一候选。
     */
    fun locateBlockUpdate(loader: ClassLoader, hostVersionCode: Int? = null): HookPoint? =
        MediaQualityAndPlayerLocator.locateBlockUpdate(loader, hostVersionCode)

    internal data class KotlinDefaultWordsDexAssist(val point: HookPoint?, val diagnostic: AdaptDiagnostic)

    internal fun locateKotlinDefaultWordsByDex(session: DexAssistSession?): KotlinDefaultWordsDexAssist {
        val result = session?.result(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)
        val selected = (result as? DexAssistResult.Candidates)?.methods?.let(KotlinDefaultWordsLocator::select)
        val reason = when (result) {
            null -> "no-dex-source"
            is DexAssistResult.Unavailable -> result.reason.name.lowercase()
            is DexAssistResult.Candidates -> if (selected == null) "no-unique-verified-entry" else "verified"
        }
        return KotlinDefaultWordsDexAssist(selected?.let(KotlinDefaultWordsLocator::point),
            AdaptDiagnostic(KotlinDefaultWordsLocator.DIAGNOSTIC,
                if (selected == null) AdaptState.MISSING else AdaptState.FOUND, reason))
    }

    private data class HistorySnapshot(
        val pages: PageBoundaryPoints? = null,
        val chrome: Map<String, HookPoint> = emptyMap(),
        val chromeSemantics: HomeChromeSemanticsPoint? = null,
        val follow: FollowFeedPoints? = null,
        val speedMenu: PlayerSpeedMenuPoints? = null,
        val speedSession: PlayerSpeedSessionClasses? = null,
        val speedCalculator: LongPressCalculatorPoint? = null,
        val weChatShare: WeChatShareClasses? = null,
        val storyQuality: StoryQualityPoints? = null,
        val brand: HookPoint? = null,
        val brandKntr: String? = null,
        val brandKntrExit: String? = null,
        val brandKntrPage: HookPoint? = null,
        val cloudKeyContract: String? = null,
        val privacyIgnet: HookPoint? = null,
        val privacyKNeuron: String? = null,
        val guestPlayerCore: String? = null,
        val normalAutoSwitch: HookPoint? = null,
        val detailSpecialTag: HookPoint? = null,
        val commentReplyOwners: Map<String, String> = emptyMap(),
        val homeEarlyLoadListener: String? = null,
        val defaultWords: HookPoint? = null,
        val sponsor: SponsorPlayerClasses? = null,
        val classic: CommentClassicStylePoints? = null,
        val systemEnv: SystemEnvironmentPoints? = null,
        val composeMenu: CommentComposeMenuPoints? = null,
        val kmpGrpcWire: HookPoint? = null,
        val aiPlaylistDirector: String? = null,
        val followUpBodies: Map<String, String> = emptyMap(),
        val detailRelateGame: String? = null,
        val legacyStoryBind: HookPoint? = null,
        val theseus2LongPress: Theseus2LongPressPoint? = null
    ) {
        companion object {
            val EMPTY = HistorySnapshot()
            fun of(history: AdaptResult?): HistorySnapshot = if (history == null) {
                EMPTY
            } else {
                HistorySnapshot(
                    pages = history.pageBoundaries,
                    chrome = history.homeChrome,
                    chromeSemantics = history.homeChromeSemantics,
                    follow = history.followFeedStyle,
                    speedMenu = history.playerSpeedMenu,
                    speedSession = history.playerSpeedSession,
                    speedCalculator = history.playerSpeedCalculator,
                    weChatShare = history.weChatShare,
                    storyQuality = history.playerQuality?.story,
                    brand = history.brandSplashDecoder,
                    brandKntr = history.brandSplashKntrDraw,
                    brandKntrExit = history.brandSplashKntrExit,
                    brandKntrPage = history.brandSplashKntrPage,
                    cloudKeyContract = history.cloudKeyFreezeContract,
                    privacyIgnet = history.privacyIgnetHttp,
                    privacyKNeuron = history.privacyKNeuron,
                    guestPlayerCore = history.guestPlayerCore,
                    normalAutoSwitch = history.playerQuality?.normalAutoSwitch,
                    detailSpecialTag = history.detailSpecialTagMapper,
                    commentReplyOwners = history.commentReplyButtonOwners,
                    homeEarlyLoadListener = history.homeRecommendEarlyLoadListener,
                    defaultWords = history.homeTopBar?.kotlinDefaultWords,
                    sponsor = history.sponsorPlayer,
                    classic = history.commentClassicStyle,
                    systemEnv = history.systemEnvironment,
                    composeMenu = history.commentComposeMenu,
                    kmpGrpcWire = history.kmpGrpcWireEngine,
                    aiPlaylistDirector = history.aiPlaylistDirector,
                    followUpBodies = history.followUpRecommendBodies,
                    detailRelateGame = history.detailRelateGameComponent,
                    legacyStoryBind = history.legacyStoryDanmakuBind,
                    theseus2LongPress = history.playerSpeedTheseus2LongPress
                )
            }
        }
    }

    /**
     * 同宿主、同规则版本、未被重置的有效缓存里，三类兜底的既有结果与诊断。
     * 缺缓存（宿主升级、手动重置、规则版本变化）时为 [EMPTY]，即全部视为未请求。
     */
    internal class AssistHistory(
        val blockUpdate: HookPoint?,
        val playerQuality: PlayerQualityPoints?,
        val commentTopology: CommentTopologyPoints?,
        private val diagnostics: List<AdaptDiagnostic>
    ) {
        private val diagnosticsById: Map<String, AdaptDiagnostic> =
            if (diagnostics.isEmpty()) emptyMap() else diagnostics.associateBy(AdaptDiagnostic::id)

        fun attempt(family: AdaptAssistGate.Family): AdaptAssistGate.Attempt = AdaptAssistGate.attempt(diagnostics, family)
        fun diagnostic(family: AdaptAssistGate.Family): AdaptDiagnostic? = diagnosticsById[family.diagnosticId]

        /** 缓存里已有「兜底实现」级别的默认画质点（不是旧包装层）。 */
        val assistedQuality: PlayerQualityPoints? = playerQuality
            ?.takeIf { it.defaultQualityMethod != null && !it.usesLegacyQualityHelper() }
            ?.copy(story = null, normalAutoSwitch = null)
        val hasAssistedQuality: Boolean get() = assistedQuality != null

        companion object {
            val EMPTY = AssistHistory(null, null, null, emptyList())
            fun of(result: AdaptResult?): AssistHistory = result?.let {
                AssistHistory(it.blockUpdate, it.playerQuality, it.commentTopology, it.diagnostics)
            } ?: EMPTY
        }
    }

    /**
     * 缓存快路径：这三类兜底中，只要有一个「功能已开启、直接定位未满足、缓存没有可复用的点、且从未请求过」，
     * 就不能走快路径，需要补扫一次（补扫之后诊断为 FOUND 或 MISSING，不再是未请求，不会逐次启动重扫）。
     * 功能关闭时一个也不会求值，零成本。
     */
    internal fun assistRefreshNeeded(
        loader: ClassLoader,
        cached: AdaptResult,
        blockUpdateEnabled: Boolean,
        playerQualityEnabled: Boolean,
        commentTopologyEnabled: Boolean
    ): Boolean {
        val history = AssistHistory.of(cached)
        return AdaptAssistGate.needsSupplementalScan(blockUpdateEnabled, history.blockUpdate != null,
            history.attempt(AdaptAssistGate.Family.BLOCK_UPDATE)) {
                locateBlockUpdate(loader, cached.biliVersionCode) != null
            } ||
            AdaptAssistGate.needsSupplementalScan(playerQualityEnabled, history.hasAssistedQuality,
                history.attempt(AdaptAssistGate.Family.PLAYER_QUALITY)) {
                locateDefaultVideoQuality(loader, hostVersionCode = cached.biliVersionCode)
                    ?.let { it.defaultQualityMethod != null && !it.usesLegacyQualityHelper() } == true
            } ||
            AdaptAssistGate.needsSupplementalScan(commentTopologyEnabled, history.commentTopology != null,
                history.attempt(AdaptAssistGate.Family.COMMENT_TOPOLOGY)) {
                locateCommentTopology(loader, cached.biliVersionCode) != null
            }
    }

    private data class BlockUpdateDexAssist(
        val point: HookPoint?,
        val diagnostic: AdaptDiagnostic
    )

    /**
     * 仅在内置 owner 全部未命中时执行全 DEX 精确签名查询；查询结果仍由宿主 ClassLoader
     * 解析，并复用 MeguvizRef/反射结构约束二次验真。任何不唯一结果都按缺失处理。
     */
    private fun locateBlockUpdateByDex(
        loader: ClassLoader,
        dexAssist: DexAssistSession?
    ): BlockUpdateDexAssist {
        val session = dexAssist ?: return BlockUpdateDexAssist(
            point = null,
            diagnostic = AdaptDiagnostic(
                id = DEX_ASSIST_BLOCK_UPDATE_ID,
                state = AdaptState.MISSING,
                detail = "block-update:no-dex-source"
            )
        )
        val upgradeInfo = KavaMemberLookup.classOrNull(loader, MediaQualityAndPlayerLocator.BILI_UPGRADE_INFO_CLASS)
            ?: return BlockUpdateDexAssist(
                point = null,
                diagnostic = AdaptDiagnostic(
                    id = DEX_ASSIST_BLOCK_UPDATE_ID,
                    state = AdaptState.MISSING,
                    detail = "block-update:return-type-missing"
                )
            )
        return when (val result = session.result(DexAssistQuery.BLOCK_UPDATE)) {
            is DexAssistResult.Candidates -> {
                val verified = result.methods.filter { method ->
                    !method.isStatic && !method.isAbstract &&
                        method.returnType == upgradeInfo &&
                        method.parameterTypes.contentEquals(arrayOf(classOf<Context>()))
                }
                val selected = DexAssistCandidateSelector.selectUniqueLeaf(verified)
                BlockUpdateDexAssist(
                    point = selected?.toHookPoint(),
                    diagnostic = AdaptDiagnostic(
                        id = DEX_ASSIST_BLOCK_UPDATE_ID,
                        state = if (selected != null) AdaptState.FOUND else AdaptState.MISSING,
                        detail = if (selected != null) {
                            "block-update:${selected.toHookPoint().label()}"
                        } else {
                            "block-update:ambiguous:${verified.size}"
                        }
                    )
                )
            }

            is DexAssistResult.Unavailable -> BlockUpdateDexAssist(
                point = null,
                diagnostic = AdaptDiagnostic(
                    id = DEX_ASSIST_BLOCK_UPDATE_ID,
                    state = AdaptState.MISSING,
                    detail = "block-update:${result.reason.name.lowercase()}"
                )
            )
        }
    }

    private data class PlayerQualityDexAssist(
        val method: Method?,
        val diagnostic: AdaptDiagnostic
    )

    private fun playerQualityAssistDiagnostic(state: AdaptState, detail: String): AdaptDiagnostic =
        AdaptDiagnostic(id = DEX_ASSIST_PLAYER_QUALITY_ID, state = state, detail = "player-quality:$detail")

    /**
     * 混淆候选全部落空（或只落到稳定包装层）时，按“`()I` + 方法体常量 `quality settings:`”全 DEX 查询
     * 默认画质实现。结果由宿主 ClassLoader 解析并复核签名，不唯一按缺失处理。
     */
    private fun locatePlayerQualityByDex(
        loader: ClassLoader,
        dexAssist: DexAssistSession?
    ): PlayerQualityDexAssist {
        val session = dexAssist ?: return PlayerQualityDexAssist(
            method = null,
            diagnostic = playerQualityAssistDiagnostic(AdaptState.MISSING, "no-dex-source")
        )
        return when (val result = session.result(DexAssistQuery.PLAYER_DEFAULT_QUALITY)) {
            is DexAssistResult.Candidates -> {
                val verified = result.methods.filter { method ->
                    !method.isAbstract && method.parameterCount == 0 &&
                        method.returnType == classOf<Int>()
                }
                val selected = verified.distinctBy(Method::toGenericString).singleOrNull()
                PlayerQualityDexAssist(
                    method = selected,
                    diagnostic = playerQualityAssistDiagnostic(
                        state = if (selected != null) AdaptState.FOUND else AdaptState.MISSING,
                        detail = selected?.toHookPoint()?.label() ?: "ambiguous:${verified.size}"
                    )
                )
            }

            is DexAssistResult.Unavailable -> PlayerQualityDexAssist(
                method = null,
                diagnostic = playerQualityAssistDiagnostic(AdaptState.MISSING, result.reason.name.lowercase())
            )
        }
    }

    private data class CommentTopologyDexAssist(
        val mapperOwners: List<Class<*>>,
        val diagnostic: AdaptDiagnostic
    )

    private fun commentTopologyAssistDiagnostic(
        state: AdaptState,
        detail: String
    ): AdaptDiagnostic = AdaptDiagnostic(
        id = DEX_ASSIST_COMMENT_TOPOLOGY_ID,
        state = state,
        detail = "comment-topology:$detail"
    )

    /**
     * 仅在包内字母表候选全部落空时执行 DEX 查询，补齐 `ReplyInfo -> CommentItem` 的 mapper owner。
     *
     * 这里只回传 owner 类，不回传判定结论：候选仍由宿主 ClassLoader 解析，再交回
     * [locateCommentTopologyWithDiagnostic] 的同一段结构过滤复核，兜底路径不存在第二份规则。
     * 该 facade 在所有已验证版本上都是唯一类，因此多 owner 一律按缺失处理，禁止把分散在多个类里
     * 的候选拼成一组安装。
     */
    private fun locateCommentTopologyMapperByDex(
        loader: ClassLoader,
        dexAssist: DexAssistSession?
    ): CommentTopologyDexAssist {
        val session = dexAssist ?: return CommentTopologyDexAssist(
            mapperOwners = emptyList(),
            diagnostic = commentTopologyAssistDiagnostic(AdaptState.MISSING, "no-dex-source")
        )
        val replyInfo = CommentLocator.COMMENT_REPLY_INFO_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .firstOrNull() ?: return CommentTopologyDexAssist(
            mapperOwners = emptyList(),
            diagnostic = commentTopologyAssistDiagnostic(AdaptState.MISSING, "reply-info-missing")
        )
        val commentItem = KavaMemberLookup.classOrNull(loader, CommentTopologyLocator.COMMENT_ITEM_CLASS)
            ?: return CommentTopologyDexAssist(
                mapperOwners = emptyList(),
                diagnostic = commentTopologyAssistDiagnostic(
                    AdaptState.MISSING,
                    "comment-item-missing"
                )
            )
        return when (val result = session.result(DexAssistQuery.COMMENT_REPLY_MAPPER)) {
            is DexAssistResult.Candidates -> {
                val verified = result.methods.filter { method ->
                    method.isStatic && !method.isAbstract && !method.isSynthetic &&
                        method.returnType == commentItem &&
                        method.parameterTypes.firstOrNull() == replyInfo
                }
                val grouped = DexAssistCandidateSelector.selectSingleOwnerGroup(verified)
                val owner = grouped.firstOrNull()?.declaringClass
                CommentTopologyDexAssist(
                    mapperOwners = listOfNotNull(owner),
                    diagnostic = commentTopologyAssistDiagnostic(
                        state = if (owner != null) AdaptState.FOUND else AdaptState.MISSING,
                        detail = if (owner != null) {
                            "${owner.name},mappers=${grouped.size}"
                        } else {
                            "ambiguous:${verified.map(Method::getDeclaringClass).distinct().size}"
                        }
                    )
                )
            }

            is DexAssistResult.Unavailable -> CommentTopologyDexAssist(
                mapperOwners = emptyList(),
                diagnostic = commentTopologyAssistDiagnostic(
                    AdaptState.MISSING,
                    result.reason.name.lowercase()
                )
            )
        }
    }

    /**
     * 动态页页签定位。8.90.2、9.1.x 与 9.9.0 的渲染方法可能被 R8 内联，不能依赖
     * 某个混淆方法名；位置映射始终通过 MediatorFragment 唯一的无参 List getter，
     * 页签添加始终落到 TabLayout.addTab(Tab, boolean)。页签模型由 getter 泛型签名取得，
     * 再验证 title/name 两个 String 字段；任一结构不唯一即不安装，避免位置错配。
     */
    fun locateDynamicTabs(loader: ClassLoader): DynamicTabsPoint? =
        MineAndHomeUiLocator.locateDynamicTabs(loader)

    /**
     * 定位数字缩写格式化器。8.90.2、9.1.0 与 9.9.0 均同时保留 Java NumberFormat；
     * “我的”页则直接调用 kntr 的 Kotlin 顶层函数。只接受 static、String 返回值且首参
     * 为 int/long（含装箱）或 kntr 的数字字符串重载，避免碰触时间/小数格式化方法。
     */
    fun locateFullNumbers(loader: ClassLoader): FullNumberPoints? =
        MineAndHomeUiLocator.locateFullNumbers(loader)

    fun locatePgcAutoActivityPopup(loader: ClassLoader): PgcAutoActivityPopupPoints? =
        MediaQualityAndPlayerLocator.locatePgcAutoActivityPopup(loader)

    /**
     * 定位播放器“进入看一看”竖屏切换控件。8.90.2、9.1.0 与 9.9.0 的布局和 dex
     * 交叉验证表明该控件均由 GeminiPlayerFullStoryWidget 自身覆写 setVisibility(int)。
     * 只缓存控件自身声明的精确方法，不安装全局 View 可见性 Hook，也不扫描界面树。
     */
    fun locatePlayerPortrait(loader: ClassLoader): PlayerPortraitPoints? =
        MediaQualityAndPlayerLocator.locatePlayerPortrait(loader)

    /**
     * 定位播放器互动层 protobuf 读边界。只认公开稳定类名，按家族自带的白名单在 Guide 上
     * 取实际存在的 `clear*`；`clearVideoPoint` 永不进入名单。
     *
     * 每个家族额外缓存 Guide 的 `getDefaultInstance()`：字段未设置时 getter 返回的是进程级
     * 单例，安装器要靠它区分"空 Guide"和"真的有互动卡"，既不动单例也不谎报生效。
     */
    fun locatePlayerInteractiveOverlays(
        loader: ClassLoader
    ): PlayerInteractiveOverlayPoints? =
        MediaQualityAndPlayerLocator.locatePlayerInteractiveOverlays(loader)

    /**
     * 详情页透明状态栏只挂到目标 Activity 自己声明的 onCreate(Bundle)，避免全局
     * Activity 生命周期 Hook。9.x 主链均使用 UnitedBizDetailsActivity；类或签名漂移时
     * 直接不安装，不以类名模糊匹配其它页面。
     */
    fun locatePlayerStatusBar(loader: ClassLoader): PlayerStatusBarPoints? =
        MediaQualityAndPlayerLocator.locatePlayerStatusBar(loader)

    /**
     * 首页推荐卡片定位：响应只接受无参 List getter，卡片属性只接受公开无参 String
     * getter。运行期先由响应边界登记对象身份，再改写已登记竖屏卡片的路由，避免把同一
     * BasePegasusData 类型在其它页面的对象一并修改。
     */
    fun locateHomeRecommendFeed(loader: ClassLoader): HomeRecommendFeedPoints? =
        HomeRecommendFeedLocator.locate(loader)

    internal fun selectDetailRelateComponentFactory(serviceClass: Class<*>): Method? =
        VideoRelateLocator.selectDetailRelateComponentFactory(serviceClass)

    /** 8.90.2 C0/r2 DTO 的 H() 只返回对应卡型的推荐理由；不读取标题或 toString。 */
    internal fun selectDetailRelateServiceReasonGetter(factory: Method): Method? =
        VideoRelateLocator.selectDetailRelateServiceReasonGetter(factory)

    /** 精确定位相关推荐列表以及公开的直接/嵌套类型读取方法。 */
    fun locateVideoRelate(loader: ClassLoader): VideoRelatePoints? =
        VideoRelateLocator.locate(loader)

    /** 首页 Tab 构建方法：单个 List 参数、List 返回值，且参数泛型为 main2.resource。 */
    fun locateHomeTabs(loader: ClassLoader): HomeTabPoints? =
        MineAndHomeUiLocator.locateHomeTabs(loader)

    /** 首页子组件仅使用宿主 AndroidX Fragment 的公开生命周期和父级读取方法。 */
    fun locateHomeComponents(loader: ClassLoader): HomeComponentPoints? =
        MineAndHomeUiLocator.locateHomeComponents(loader)

    /**
     * “我的”页入口的数据层剪枝定位：以 AccountMine 为锚，在 Fragment 构建菜单前剪枝。
     * 旧构建方法接收 (Fragment, AccountMine)，9.19.0 的实例构建方法只接收 AccountMine。
     * 实例形状复用 locateMineEntry 的菜单结构复核，这里另采集数据剪枝所需的字段名。
     * 只有 sections、group.itemList 和 item.title 是剪枝必需字段；附件字段缺失时保留对应组件。
     */
    fun locateMineAccountMinePoints(loader: ClassLoader): MineAccountMinePoints? =
        MineAndHomeUiLocator.locateMineAccountMinePoints(loader)

    /**
     * “我的”页保留 MenuGroup(V2) 公开 getter 边界，同时保留可验证的旧菜单构建字段链。
     * 新旧模型可并存，不能因闲置 getter 存在而丢弃实际消费路径。
     */
    fun locateMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint? = null
    ): MineComponentPoints? =
        MineAndHomeUiLocator.locateMineComponents(loader, mineEntry)

    /** 仅供旧公开字段模型与单元测试使用；字段链在适配期确定，运行期直接 Field#get/set。 */
    internal fun locateLegacyMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint?
    ): MineComponentPoints? =
        MineAndHomeUiLocator.locateLegacyMineComponents(loader, mineEntry)

    /** Story 只采用 StoryDetail 自身公开类型判断，不按标题、URI 或字段内容猜测。 */
    fun locateStoryFeed(loader: ClassLoader): StoryFeedPoints? =
        MineAndHomeUiLocator.locateStoryFeed(loader)

    /**
     * 评论区只在 TheseusTabPagerService 的直接配置依赖中寻找 List<TabPage> 构造器，
     * 并要求 TabPage 存在返回含 Comment 枚举值的唯一无参方法，避免按混淆类名猜测。
     */
    fun locateCommentSection(loader: ClassLoader): CommentSectionPoints? =
        CommentLocator.locateCommentSection(loader)

    /** 开屏净化定位白名单 List getter，并尽量补充公开 SplashItem 广告语义。 */
    fun locateSplashAds(loader: ClassLoader): SplashAdPoints? =
        MediaQualityAndPlayerLocator.locateSplashAds(loader)

    /** 底栏按 TabHost 的公开 tabs 列表和唯一 (int, View) 绑定入口定位。 */
    fun locateBottomBar(loader: ClassLoader): BottomBarPoints? =
        MineAndHomeUiLocator.locateBottomBar(loader)

    /**
     * 定位播放器默认画质计算方法。8.84.0–8.87.0 使用稳定公开
     * PlayerSettingHelper#getDefaultQuality()，8.88.0–8.96.0 主要为混淆 h#c()，
     * 8.97.0 起迁移为混淆 f#a()，9.x 继续按版本漂移；方法体均读取
     * pref_player_mediaSource_quality_wifi_key，执行宿主限制降级并记录 "quality settings:"。
     * 运行期按新到旧候选探测，并要求单个 owner 内只有一个无参 Int 入口；这样既避开
     * 新版 dex 对旧混淆类的残留引用，也不会按宽泛方法名跨类批量安装。
     */
    fun locateDefaultVideoQuality(
        loader: ClassLoader,
        /** 后台 DexMeguviz 兜底找到的实现；给了就不再查候选表。 */
        assisted: Method? = null,
        hostVersionCode: Int? = null
    ): PlayerQualityPoints? =
        MediaQualityAndPlayerLocator.locateDefaultVideoQuality(loader, assisted, hostVersionCode)

    /**
     * 青少年模式提示页在 8.90.2、9.1.0 与 9.9.0 均保留稳定类名。
     * 只接受 Activity 自身声明的 onCreate(Bundle)，避免结束正常青少年模式设置页面。
     */
    fun locateTeenagersMode(loader: ClassLoader): TeenagersModePoints? =
        MediaQualityAndPlayerLocator.locateTeenagersMode(loader)

    /**
     * 定位评论筛选的公开 protobuf 读取链。
     *
     * 以 ReplyInfo 的真实返回类型反推 Content/Member，避免包重定位后分别选择到不同代
     * 的同名类；列表边界还要求泛型元素精确为同一个 ReplyInfo，拒绝无关 List getter。
     */
    fun locateCommentFilter(loader: ClassLoader): CommentFilterPoints? =
        CommentLocator.locateCommentFilter(loader)

    /**
     * 定位回复脉络的 ReplyInfo 身份桥和宿主 MOSS 公开分页边界。
     *
     * protobuf 类型和方法名在 8.90.2—9.9.0 样本中保持公开稳定；唯一可能混淆的
     * ReplyInfo -> CommentItem 映射方法按首参/返回类型结构定位，拒绝猜测 a..g 字段。
     */
    fun locateCommentTopology(
        loader: ClassLoader,
        hostVersionCode: Int = 0
    ): CommentTopologyPoints? =
        CommentTopologyLocator.locate(loader, hostVersionCode)

    /**
     * @param extraMapperOwners DEX 兜底补充的 mapper owner。它只扩大候选 owner 集合，不放宽
     *   任何结构判定——所有候选仍走下面同一段过滤，避免兜底路径出现第二份判定规则。
     */
    private fun locateCommentTopologyWithDiagnostic(
        loader: ClassLoader,
        extraMapperOwners: List<Class<*>> = emptyList(),
        hostVersionCode: Int = 0
    ): CommentTopologyLocateOutcome =
        CommentTopologyLocator.locateWithDiagnostic(loader, extraMapperOwners, hostVersionCode)

    /**
     * 定位评论内容的公开 URL Map getter。8.90.2、9.1.0 与 9.9.0 均保留 getUrls/
     * getUrlsMap；只接管 Map 返回边界，避免修改 protobuf 内部 MapFieldLite 的可变状态。
     */
    fun locateCommentPurify(loader: ClassLoader): CommentPurifyPoints? =
        CommentLocator.locateCommentPurify(loader)

    /** 评论快捷回复收集器的同包候选直连定位（Tier-1 零 I/O 快路径）。 */
    private fun locateCommentQuickReplyDirect(loader: ClassLoader): List<HookPoint> =
        CommentLocator.locateCommentQuickReplyDirect(loader)

    /** 暂停页请求入口并行探测；仅零参数 invoke 才允许被识别为旧 Function0。 */
    private fun locatePausePoints(loader: ClassLoader): PausePoints =
        MediaQualityAndPlayerLocator.locatePausePoints(loader)

    /**
     * V8Banner 从 8.90.2 到 9.9.0 均继承 SwiperBanner；父类的 attach、setAdapter 与
     * visibility 回调是稳定低频入口。Hook 时仍按 V8Banner 实例过滤，不影响其它轮播控件。
     */
    private fun locateBanner(loader: ClassLoader): BannerPoint? =
        MineAndHomeUiLocator.locateBanner(loader)

    /**
     * 首页顶部栏结构化定位：
     * - 游戏菜单构建方法在 8.90.2 为 c(Menu, MenuInflater)，9.1.0–9.9.0 为 b(...)；
     * - 默认搜索词模型在 8.90.2 为 api.d，9.1.0–9.9.0 为 api.b。
     * 因此只依赖参数/返回类型与 SwitchTextView 字段特征，不缓存宿主实例。
     */
    fun locateHomeTopBar(loader: ClassLoader): HomeTopBarPoints? =
        MineAndHomeUiLocator.locateHomeTopBar(loader)

    /**
     * 低版本评论 holder：候选类存在 + 有绑定方法（方法名漂移自适应：8.90.2 为 o0、
     * 9.8.0 为 q0——按候选方法名列表匹配；参数不限，运行期 hook 全部重载）。
     */
    private fun locateCommentLow(loader: ClassLoader): HookPoint? =
        CommentLocator.locateCommentLow(loader)

    /**
     * 高版本评论 handler：候选类存在 + 有 CommentItem 字段 + 绑定方法。
     */
    private fun locateCommentHigh(loader: ClassLoader): HookPoint? =
        CommentLocator.locateCommentHigh(loader)

    /** 主线程弹适配 toast（B 站进程内） */
    fun showAdaptToast(context: Context, text: String) {
        runCatching {
            android.os.Looper.getMainLooper().let { looper ->
                if (android.os.Looper.myLooper() == looper) {
                    // 保留主线程显式分支；MeguvizAndroid 的 toast 扩展只允许主线程调用。
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                } else {
                    // 保留 Handler 切换主线程的既有行为，不交由扩展创建后台 Looper。
                    // 外层 runCatching 只覆盖 post 本身；Runnable 稍后跑在宿主主线程上，
                    // 必须自带防波堤。
                    android.os.Handler(looper).post(
                        HostThreadGuard.runnable("adapter.toast") {
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }
}
