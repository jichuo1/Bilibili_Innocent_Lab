package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
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
        /** itemView 来源字段名（handler 内 ViewGroup 类型字段；null = 从方法参数拿） */
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
    private const val SCHEMA_VERSION = 8

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
        val banner: BannerPoint?
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
        }

        companion object {
            fun fromJson(o: JSONObject): AdaptResult? {
                if (o.optInt("sv", 0) != SCHEMA_VERSION) return null
                return AdaptResult(
                    biliVersionCode = o.getInt("v"),
                    ts = o.optLong("ts", 0L),
                    commentLow = if (o.has("low")) HookPoint.fromJson(o.getJSONObject("low")) else null,
                    commentHigh = if (o.has("high")) HookPoint.fromJson(o.getJSONObject("high")) else null,
                    mineEntry = o.optJSONObject("mine")?.let(MineEntryPoint::fromJson),
                    pause = o.optJSONObject("pause")?.let(PausePoints::fromJson)
                        ?: PausePoints(emptyList(), null, null, null),
                    banner = o.optJSONObject("banner")?.let(BannerPoint::fromJson)
                )
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** 当前 B 站 versionCode（读自身包信息，隔离环境对自身可见） */
    fun biliVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo("tv.danmaku.bili", 0).versionCode
    }.getOrDefault(0)

    /** 读缓存适配结果（二级文件缓存优先；手动重置标记/版本不符返回 null）
     *  @param yukiPrefs YukiHookAPI prefs（DirectAccessService 跨进程读模块 App 的
     *   apexdata prefs；手动重适配的 reset_ts 由模块 UI 写入该处——原生 SharedPreferences
     *   在 B 站进程读的是 B 站自己的内部存储，读不到模块侧的 reset 标记） */
    fun loadCached(context: Context?, yukiPrefs: com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge?): AdaptResult? {
        val resetTs = yukiPrefs?.getLong(KEY_RESET_TS, 0L) ?: 0L
        // 文件缓存（loadApp 阶段可读）
        runCatching {
            val f = cacheFile()
            if (f.exists()) {
                val r = AdaptResult.fromJson(JSONObject(f.readText())) ?: return@runCatching
                if (r.ts >= resetTs) { // 手动重置标记晚于缓存则作废
                    val vc = context?.let { biliVersionCode(it) } ?: 0
                    if (vc == 0 || r.biliVersionCode == vc) return r
                }
            }
        }.getOrNull()
        // prefs 缓存（兜底；同样检查手动重置标记）
        if (context != null) {
            runCatching {
                val p = prefs(context)
                val v = p.getInt(KEY_ADAPTED_VERSION, 0)
                val json = p.getString(KEY_ADAPT_RESULT, null) ?: return null
                val r = AdaptResult.fromJson(JSONObject(json)) ?: return null
                if (r.ts >= resetTs && r.biliVersionCode == v) r else null
            }.getOrNull()?.let { return it }
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
        val cached = loadCached(context, yukiPrefs)
        // 快路径有效性：版本匹配 且（high 已定位 或 当前版本无 high 候选类）。
        // 防止旧缓存（sv 同但 high 缺失——如 8.63.0 早期 low-only 结果）被快路径
        // 复用而跳过重定位（曾有 01:04 prefs 旧结果导致 9.8.0 一直 low-only 的回归）。
        val highCandidateExists = COMMENT_HIGH_CANDIDATES.any {
            KavaMemberLookup.hasClass(classLoader, it)
        }
        if (cached != null && cached.biliVersionCode == vc &&
            (cached.commentHigh != null || !highCandidateExists)) {
            // 快路径命中：确保文件缓存存在（loadApp 阶段无 context 只读文件缓存；
            // prefs 命中但文件缺失时补写，避免下次启动 loadApp 回退内置候选）
            runCatching {
                if (!cacheFile().exists()) cacheFile().writeText(cached.toJson().toString())
            }
            return // 快路径：已适配
        }
        XposedBridge.log("[BIL] 版本适配启动 vc=$vc cached=${cached != null}")
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
                    "banner=${result?.banner != null}"
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
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null) return null
        return AdaptResult(0, 0L, low, high, mine, pause, banner)
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
        val anyClassExists = COMMENT_LOW_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
            || COMMENT_HIGH_CANDIDATES.any { KavaMemberLookup.hasClass(loader, it) }
        if (low == null && high == null && mine == null &&
            pause.requestMethods.isEmpty() && pause.legacyCallback == null &&
            pause.panelShow == null && pause.countdown == null && banner == null &&
            !anyClassExists) return null
        return AdaptResult(vc, System.currentTimeMillis(), low, high, mine, pause, banner)
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

    /**
     * 结构化定位“我的”页入口。已确认的字段漂移：
     * 8.90.2/9.8.0 由运行时结构匹配；9.1.1=of+n1/m1、9.2.0=pf+m1/l1、
     * 9.3.0=nf+u0/t0。只依赖方法参数、List 和 RecyclerView.Adapter 类型。
     */
    fun locateMineEntry(loader: ClassLoader): MineEntryPoint? = runCatching {
        val fragmentName = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
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
