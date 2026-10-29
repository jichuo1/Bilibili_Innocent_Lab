package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import org.json.JSONObject
import java.util.EnumMap

/**
 * 同步定位（`VersionAdapter.quickLocate` / 后台 `adapt` 的直接定位段）的最小调度单位：一次定位器调用 = 一个家族。
 * [key] 同时是适配缓存 JSON 里该家族结果的键，覆盖范围 `cov` 也用它。
 *
 * 新增定位器必须同时：加一个家族、在 [AdaptPlanCatalog] 登记读取者；没有登记的家族一律常开（失效安全）。
 */
internal enum class PointFamily(val key: String) {
    COMMENT_LOW("low"),
    COMMENT_HIGH("high"),
    MINE_ENTRY("mine"),
    PAUSE("pause"),
    BANNER("banner"),
    HOME_TOP_BAR("home_top"),
    MINE_VIP("mine_vip"),
    BLOCK_UPDATE("block_update"),
    DYNAMIC_TABS("dynamic_tabs"),
    FULL_NUMBERS("full_numbers"),
    PLAYER_PORTRAIT("player_portrait"),
    PLAYER_INTERACTIVE_OVERLAYS("player_interactive_overlays"),
    PGC_AUTO_ACTIVITY_POPUP("pgc_auto_activity_popup"),
    PLAYER_STATUS_BAR("player_status_bar"),
    HOME_RECOMMEND_FEED("home_recommend_feed"),
    VIDEO_RELATE("video_relate"),
    HOME_TABS("home_tabs"),
    HOME_COMPONENTS("home_components"),
    MINE_COMPONENTS("mine_components"),
    MINE_ACCOUNT_MINE("mine_account_mine"),
    STORY_FEED("story_feed"),
    BOTTOM_BAR("bottom_bar"),
    PLAYER_QUALITY("player_quality"),
    TEENAGERS_MODE("teenagers_mode"),
    COMMENT_PURIFY("comment_purify"),
    COMMENT_FILTER("comment_filter"),
    COMMENT_TOPOLOGY("comment_topology"),
    COMMENT_SECTION("comment_section"),
    SPLASH_ADS("splash_ads");

    companion object {
        private val byKey = entries.associateBy(PointFamily::key)
        fun fromKey(key: String): PointFamily? = byKey[key]
    }
}

/**
 * 由安装链已接受的配置快照生成的不可变定位计划。
 *
 * - [required]：本次必须直接定位的家族（始终需要的家族 ∪ 有启用读取者的家族 ∪ 它们的定位依赖）；
 * - [always]：永远不裁剪的家族（入口/扫描面/常驻能力，见 [AdaptPlanCatalog]），[required] 的子集；
 * - [configRevision]：生成计划的配置快照代际，仅用于诊断与测试，不参与缓存有效性（有效性只看覆盖范围）。
 *
 * 当前所有读取者都在 `installAuthorizedHooks` 构造阶段取走定位结果，所以 [required] 同时就是「必须在首次初始化前
 * 同步定位」的集合；没有可以延后到后台适配完成的同步家族。
 */
internal class AdaptPlan internal constructor(
    val configRevision: Long,
    val required: Set<PointFamily>,
    val always: Set<PointFamily>,
    private val requesters: Map<PointFamily, List<String>> = emptyMap()
) {
    init {
        require(always.all { it in required }) { "always 必须是 required 的子集" }
    }

    fun needs(family: PointFamily): Boolean = family in required

    /** 被裁剪（本次不求值）的家族。 */
    val pruned: Set<PointFamily> get() = PointFamily.entries.toSet() - required

    /** 请求该家族的读取者，用于诊断；全量计划返回 `full-plan`。 */
    fun requestedBy(family: PointFamily): List<String> = requesters[family].orEmpty()

    /** 追加必须的家族（含它们的定位依赖）；已经全部包含时返回自身。计划只会变大，不会变小。 */
    fun including(extra: Set<PointFamily>): AdaptPlan {
        if (required.containsAll(extra)) return this
        val expanded = AdaptPlanCatalog.closure(required + extra)
        val added = (expanded - required).associateWith { listOf("explicit-flag") }
        return AdaptPlan(configRevision, expanded, always, requesters + added)
    }

    override fun equals(other: Any?): Boolean =
        other is AdaptPlan && configRevision == other.configRevision && required == other.required &&
            always == other.always

    override fun hashCode(): Int = 31 * (31 * configRevision.hashCode() + required.hashCode()) + always.hashCode()

    override fun toString(): String =
        "AdaptPlan(rev=$configRevision, required=${required.size}/${PointFamily.entries.size}, always=${always.size})"

    companion object {
        /** 原始（未裁剪）行为：所有家族都定位。缓存里的旧结果和没有配置快照的调用方使用它。 */
        fun full(configRevision: Long = 0L): AdaptPlan = AdaptPlan(
            configRevision,
            required = PointFamily.entries.toSet(),
            always = AdaptPlanCatalog.alwaysFamilies,
            requesters = PointFamily.entries.associateWith { listOf("full-plan") }
        )
    }
}

/** 一次定位器调用的观察点：计数、计时和 A/B 对照（原始 / 裁剪后定位 / 将来的候选后端）共用。 */
internal fun interface LocateObserver {
    fun located(family: PointFamily, elapsedNanos: Long, outcome: Attempt)
}

/** 逐家族记录定位器耗时（纳秒），汇总成一行日志；只在 debug 构建的设备对照里使用。 */
internal class LocateTimings : LocateObserver {
    private val nanos = EnumMap<PointFamily, Long>(PointFamily::class.java)
    private val outcomes = EnumMap<PointFamily, Attempt>(PointFamily::class.java)

    override fun located(family: PointFamily, elapsedNanos: Long, outcome: Attempt) {
        nanos[family] = elapsedNanos
        outcomes[family] = outcome
    }

    val familyCount: Int get() = nanos.size
    val totalNanos: Long get() = nanos.values.sum()

    fun summary(top: Int = 3): String {
        val slowest = nanos.entries.sortedByDescending { it.value }.take(top)
            .joinToString(",") { "${it.key.key}=${it.value / 1000}us" }
        return "families=$familyCount/${PointFamily.entries.size} locatorsTotal=${totalNanos / 1000}us slowest=[$slowest]"
    }
}

/**
 * 缓存里每个家族的尝试状态，复用 [AdaptAssistGate.Attempt] 语义：
 * [Attempt.NOT_REQUESTED] 从未尝试（含被计划裁剪）；[Attempt.FOUND] 找到；
 * [Attempt.CONCLUSIVE_MISSING] 尝试过且确认没有；[Attempt.RETRYABLE] 定位器异常，没有有效结论。
 *
 * 类本身是 public（[VersionAdapter.AdaptResult] 是 public 数据类，`copy` 要求参数类型同级可见），
 * 但所有涉及 [PointFamily]/[Attempt] 的成员都是 internal。
 *
 * 持久化键 `cov` 只写已尝试的家族；缺键的旧缓存读成 `null`（覆盖范围未知），**不能**当成覆盖完整。
 * 这是纯加法的可选键，所以不抬 SCHEMA/RULE：旧缓存缺它时由 [AdaptPlanner.missing] 判为全部待补，
 * 后台静默补一次后写回；旧模块读新缓存时忽略未知键，且自身 quickLocate 仍是全量定位。
 */
class AdaptCoverage internal constructor(source: Map<PointFamily, Attempt>) {
    private val attempts: Map<PointFamily, Attempt> = EnumMap<PointFamily, Attempt>(PointFamily::class.java).also { map ->
        source.forEach { (family, attempt) -> if (attempt != Attempt.NOT_REQUESTED) map[family] = attempt }
    }

    internal fun attempt(family: PointFamily): Attempt = attempts[family] ?: Attempt.NOT_REQUESTED

    internal val attempted: Set<PointFamily> get() = attempts.keys

    fun toJson(): JSONObject = JSONObject().also { json ->
        attempts.forEach { (family, attempt) -> json.put(family.key, attempt.name) }
    }

    override fun equals(other: Any?): Boolean = other is AdaptCoverage && attempts == other.attempts
    override fun hashCode(): Int = attempts.hashCode()
    override fun toString(): String = attempts.entries.joinToString(prefix = "AdaptCoverage(", postfix = ")") { "${it.key.key}=${it.value}" }

    companion object {
        internal val EMPTY = AdaptCoverage(emptyMap())

        /** 宽松读取：未知家族键（新版本写入）忽略，非法状态值按未请求处理——都只会导致补扫，不会误报覆盖。 */
        fun fromJson(json: JSONObject): AdaptCoverage = AdaptCoverage(buildMap {
            json.keys().forEach { key ->
                val family = PointFamily.fromKey(key) ?: return@forEach
                val attempt = runCatching { Attempt.valueOf(json.getString(key)) }.getOrNull() ?: return@forEach
                put(family, attempt)
            }
        })

        /** 实时结果与缓存合并：实时尝试过的家族以实时为准，其余沿用缓存。 */
        internal fun merge(live: AdaptCoverage?, cached: AdaptCoverage?): AdaptCoverage? {
            if (live == null) return cached
            if (cached == null) return live
            return AdaptCoverage(PointFamily.entries.associateWith { family ->
                live.attempt(family).takeIf { it != Attempt.NOT_REQUESTED } ?: cached.attempt(family)
            })
        }
    }
}

/**
 * 一轮定位的执行器：只对计划内家族调用定位器，并记录每个家族的尝试状态。
 *
 * 定位器抛出异常（含类链接失败）时记为 [Attempt.RETRYABLE] 并按未找到处理（原先异常会冒到安装链）；定位器内部自己
 * `runCatching` 吞掉的异常仍无法区分，那种情况只能记为确认缺失。
 */
internal class SyncLocatorRun(val plan: AdaptPlan, private val observer: LocateObserver? = null) {
    private val attempts = EnumMap<PointFamily, Attempt>(PointFamily::class.java)

    fun <T> locate(family: PointFamily, found: (T) -> Boolean = { true }, block: () -> T?): T? {
        if (!plan.needs(family)) return null
        val started = if (observer != null) System.nanoTime() else 0L
        val value: T?
        val outcome: Attempt
        try {
            value = block()
            outcome = if (value != null && found(value)) Attempt.FOUND else Attempt.CONCLUSIVE_MISSING
        } catch (failure: Throwable) {
            // 反射定位期间的类加载/链接失败属于可重试；虚拟机级错误必须继续抛出。
            if (failure is VirtualMachineError) throw failure
            attempts[family] = Attempt.RETRYABLE
            observer?.located(family, System.nanoTime() - started, Attempt.RETRYABLE)
            return null
        }
        attempts[family] = outcome
        observer?.located(family, System.nanoTime() - started, outcome)
        return value
    }

    /**
     * 计划内定位；计划外不求值，沿用 [prior]（此前缓存里的点），但只有 [previous] 证明此前真的尝试过该家族才沿用，
     * 否则视为未请求（null）——不把没有覆盖证明的旧点当成本轮结果。
     */
    fun <T> locateOrCarry(
        family: PointFamily,
        prior: T?,
        previous: AdaptCoverage?,
        found: (T) -> Boolean = { true },
        block: () -> T?
    ): T? =
        if (plan.needs(family)) locate(family, found, block)
        else prior.takeIf { (previous?.attempt(family) ?: Attempt.NOT_REQUESTED) != Attempt.NOT_REQUESTED }

    /** 本轮尝试过的家族；计划外的家族不出现（等同未请求）。 */
    fun coverage(): AdaptCoverage = AdaptCoverage(attempts)

    fun attemptOf(family: PointFamily): Attempt = attempts[family] ?: Attempt.NOT_REQUESTED
}
