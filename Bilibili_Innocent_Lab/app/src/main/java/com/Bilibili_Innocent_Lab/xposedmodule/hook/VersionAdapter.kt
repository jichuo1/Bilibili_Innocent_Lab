package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Method

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
    private const val KEY_RESET_TS = "adapt_reset_ts"

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

    /** 适配结果 JSON 结构版本（结构变化时强制重新适配，防止旧结构缓存误用） */
    private const val SCHEMA_VERSION = 16
    private const val ADAPTER_RULE_VERSION = 8

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

    /** 评论 protobuf 中对外暴露的 URL 映射入口；不触碰正文、emoji 或评论视图。 */
    data class CommentPurifyPoints(
        val urlMapGetters: List<HookPoint>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("urls", JSONArray().apply { urlMapGetters.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): CommentPurifyPoints {
                val urls = o.getJSONArray("urls")
                return CommentPurifyPoints(
                    (0 until urls.length()).map {
                        HookPoint.fromJson(urls.getJSONObject(it))
                    }
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
        /** 评论区净化所需的 protobuf 数据边界。 */
        val commentPurify: CommentPurifyPoints?,
        /** 宿主 APK + 适配规则指纹，防止只凭 versionCode 复用陈旧缓存。 */
        val hostFingerprint: String,
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
            commentPurify?.let { put("comment_purify", it.toJson()) }
            put("fp", hostFingerprint)
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
                commentPurify?.urlMapGetters?.let { methods ->
                    methods.isNotEmpty() && methods.all { it.isValid() }
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
                    commentPurify = o.optJSONObject("comment_purify")
                        ?.let(CommentPurifyPoints::fromJson),
                    hostFingerprint = o.optString("fp"),
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
        // 8.90.2；9.1.0/9.1.1；9.2.0；9.3.0；9.4.0；9.5.0；
        // 9.6.0；9.7.0；9.8.0；9.9.0（按已核验版本顺序）。
        "vd6.c", "ih1.c", "kh1.c", "Ch1.c", "Uj1.c",
        "dl1.c", "wm1.c", "Wm1.c", "Sn1.c", "Ro1.c"
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
    private val COMMENT_CONTENT_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.main.community.reply.v1.Content",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.Content"
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** 当前 B 站 versionCode（读自身包信息，隔离环境对自身可见） */
    fun biliVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo("tv.danmaku.bili", 0).versionCode
    }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun buildHostFingerprint(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo("tv.danmaku.bili", 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
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

    /** 读缓存适配结果（二级文件缓存优先；手动重置标记/版本不符返回 null）
     *  @param yukiPrefs YukiHookAPI prefs（DirectAccessService 跨进程读模块 App 的
     *   apexdata prefs；手动重适配的 reset_ts 由模块 UI 写入该处——原生 SharedPreferences
     *   在 B 站进程读的是 B 站自己的内部存储，读不到模块侧的 reset 标记） */
    fun loadCached(context: Context?, yukiPrefs: com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge?): AdaptResult? {
        val resetTs = yukiPrefs?.getLong(KEY_RESET_TS, 0L) ?: 0L
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
    fun isCached(context: Context, yukiPrefs: com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge?): Boolean {
        val cached = loadCached(context, yukiPrefs) ?: return false
        return cached.biliVersionCode == biliVersionCode(context)
    }

    /**
     * 手动重适配：写重置标记 + 清除记录（B 站下次启动即重新定位）。
     * 关键：reset_ts 必须写 **YukiHookAPI prefs**（B 站侧经 DirectAccessService 读的
     * 是 YukiHookAPI 默认 prefs 文件 com.Bilibili_Innocent_Lab.xposedmodule_preferences.xml；原生
     * SharedPreferences 在模块 App 被重定向到独立文件 innocent_lab_version_adapter.xml，
     * B 站读不到）。
     */
    fun clearCache(context: Context, yukiPrefs: com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge?) {
        runCatching {
            yukiPrefs?.edit { putLong(KEY_RESET_TS, System.currentTimeMillis()) }
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
        yukiPrefs: com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge?,
        callback: AdaptCallback?
    ) {
        val vc = biliVersionCode(context)
        val expectedFingerprint = buildHostFingerprint(context)
        val cached = loadCached(context, yukiPrefs)
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
        XposedBridge.log(
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
            XposedBridge.log(
                "[BIL] 版本适配${if (result != null) "完成" else "失败"} " +
                    "v=${result?.biliVersionCode} low=${result?.commentLow} high=${result?.commentHigh} " +
                    "mine=${result?.mineEntry != null} pause=${result?.pause?.requestMethods?.size ?: 0} " +
                    "banner=${result?.banner != null} homeTop=${result?.homeTopBar != null} " +
                    "mineVip=${result?.mineVip != null} " +
                    "blockUpdate=${result?.blockUpdate != null} " +
                    "dynamicTabs=${result?.dynamicTabs != null} " +
                    "playerPortrait=${result?.playerPortrait != null} " +
                    "commentPurify=${result?.commentPurify != null} " +
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
        val commentPurify = locateCommentPurify(loader)
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            commentPurify == null) return null
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
            commentPurify = commentPurify,
            hostFingerprint = "runtime-no-context|rules=$ADAPTER_RULE_VERSION",
            diagnostics = buildDiagnostics(
                loader, low, high, mine, pause, banner, homeTopBar, mineVip, blockUpdate,
                dynamicTabs, fullNumbers, playerPortrait, commentPurify
            )
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
        val commentPurify = locateCommentPurify(loader)
        val anyClassExists = COMMENT_LOW_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_HIGH_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || HOME_TOP_BAR_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || KavaMemberLookup.hasClass(loader, MINE_FRAGMENT_CLASS)
            || KavaMemberLookup.hasClass(loader, DYNAMIC_MEDIATOR_FRAGMENT_CLASS)
            || FULL_NUMBER_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || PLAYER_PORTRAIT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_CONTENT_CLASS_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || BLOCK_UPDATE_OWNER_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null &&
            homeTopBar == null && mineVip == null && blockUpdate == null &&
            dynamicTabs == null && fullNumbers == null && playerPortrait == null &&
            commentPurify == null &&
            !anyClassExists) return null
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
            commentPurify = commentPurify,
            hostFingerprint = buildHostFingerprint(context),
            diagnostics = buildDiagnostics(
                loader, low, high, mine, pause, banner, homeTopBar, mineVip, blockUpdate,
                dynamicTabs, fullNumbers, playerPortrait, commentPurify
            )
        )
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
        commentPurify: CommentPurifyPoints?
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
        val commentPurifyCandidateExists = COMMENT_CONTENT_CLASS_CANDIDATES.any {
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
                "comment.purify.search",
                stateFor(commentPurify != null, commentPurifyCandidateExists),
                commentPurify?.urlMapGetters?.joinToString("|") { it.label() }.orEmpty()
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
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                List::class.java.isAssignableFrom(it.type)
        }
        val groups = listFields.singleOrNull() ?: return@runCatching null

        val adapter = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
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
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && manager.isAssignableFrom(it.type)
        }.singleOrNull() ?: return@runCatching null
        val bindingField = KavaMemberLookup.declaredFields(manager, makeAccessible = true) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.type.implementsInterfaceNamed("androidx.viewbinding.ViewBinding")
        }.singleOrNull() ?: return@runCatching null
        val rootGetter = KavaMemberLookup.declaredMethods(
            bindingField.type,
            makeAccessible = true
        ) {
            !it.isStatic && !it.isBridge && it.name == "getRoot" &&
                it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
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
                !it.isStatic && !java.lang.reflect.Modifier.isAbstract(it.modifiers) &&
                    it.returnType == upgradeInfo &&
                    it.parameterTypes.contentEquals(arrayOf(Context::class.java))
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
            !it.isStatic && it.parameterCount == 0 && List::class.java == it.returnType
        }.singleOrNull() ?: return@runCatching null
        val itemClass = (listGetter.genericReturnType as? ParameterizedType)
            ?.actualTypeArguments
            ?.singleOrNull() as? Class<*>
            ?: return@runCatching null
        val titleField = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.type == String::class.java && it.name == "a"
        }.singleOrNull() ?: return@runCatching null
        val nameField = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.type == String::class.java && it.name == "b"
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
            Boolean::class.javaPrimitiveType ?: return@runCatching null
        ) ?: return@runCatching null
        val customViewGetter = KavaMemberLookup.methodOrNull(
            tabClass,
            "getCustomView"
        )?.takeIf { View::class.java.isAssignableFrom(it.returnType) }
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
     * 定位评论内容的公开 URL Map getter。8.90.2、9.1.0 与 9.9.0 均保留 getUrls/
     * getUrlsMap；只接管 Map 返回边界，避免修改 protobuf 内部 MapFieldLite 的可变状态。
     */
    fun locateCommentPurify(loader: ClassLoader): CommentPurifyPoints? = runCatching {
        val methods = COMMENT_CONTENT_CLASS_CANDIDATES.asSequence()
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
        methods.takeIf { it.isNotEmpty() }?.let(::CommentPurifyPoints)
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
                    arrayOf(android.view.View::class.java, Integer.TYPE)
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
                        arrayOf(Menu::class.java, MenuInflater::class.java)
                    )
            }.singleOrNull() ?: return@let null
            val configField = KavaMemberLookup.declaredFields(
                owner,
                makeAccessible = true
            ) { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    field.type.enclosingClass == owner &&
                    KavaMemberLookup.declaredFields(field.type) {
                        it.type == String::class.java
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
                    TextView::class.java.isAssignableFrom(it.type) &&
                        it.type.simpleName == "SwitchTextView"
                }
                val searchField = searchFields.singleOrNull() ?: return@mapNotNull null
                val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                    !it.isStatic && it.returnType == Void.TYPE &&
                        it.name == "onViewCreated" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(View::class.java, Bundle::class.java)
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
                        !it.isStatic && !java.lang.reflect.Modifier.isAbstract(it.modifiers) &&
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
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                } else {
                    android.os.Handler(looper).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}
