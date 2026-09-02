package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isFinal
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** 首页竖屏卡片进入普通详情页所需的最小、脱敏路由快照。 */
internal data class HomeVerticalRouteSnapshot(
    val holderType: String? = null,
    val bizType: String? = null,
    val cardType: String? = null,
    val cardGoto: String? = null,
    val goTo: String? = null,
    val uri: String? = null,
    val param: String? = null,
    val playerAid: Long? = null,
    /** PlayerArgs 已明确指向直播、番剧等非普通投稿时禁止改写。 */
    val playerNonUgc: Boolean = false,
    val hasAdInfo: Boolean = false
)

/** 规范视频身份，不保存标题、完整 URI 或跟踪参数。 */
internal data class CanonicalHomeVideoId(
    val kind: Kind,
    val value: String
) {
    enum class Kind { BV, AID }
}

internal data class HomeVerticalRoutePlan(
    val identity: CanonicalHomeVideoId,
    /** 同一卡片中不同命名空间（BV/aid）的身份别名。 */
    val aliases: Set<CanonicalHomeVideoId> = setOf(identity),
    val detailUri: String,
    val rewriteCardGoto: Boolean,
    val rewriteGoTo: Boolean,
    val rewriteUri: Boolean
)

/**
 * 纯路由策略：只在结构化证据确认是普通投稿型竖屏卡片、且 BV/aid 可验证时生成改写计划。
 * 未知、冲突或特殊业务卡片全部 fail-open。
 */
internal object HomeVerticalDetailRoutePolicy {
    private const val STORY_URI_ROOT = "bilibili://story"
    private const val STORY_TRANSLUCENT_URI_ROOT = "bilibili://story_translucent"
    private const val VIDEO_URI_ROOT = "bilibili://video"
    private const val VIDEO_URI_PREFIX = "$VIDEO_URI_ROOT/"
    private const val AV_GOTO = "av"
    /**
     * 真实 Story 路由长度上限。
     *
     * 2026-09-02 在宿主 9.9.0 实测：首页竖屏卡片的 Intent data URI 长 **35,657** 字符——
     * `player_preload` 里内联了完整 DASH manifest（每个清晰度的 CDN 直链）。原值 4096 会把
     * 每一条带预加载的 Story URI 在第一道长度检查就丢弃，这正是"有概率不生效"的真实成因：
     * 未预加载的短 URI 能通过，预加载过的长 URI 一律失败。
     *
     * 现值留足量级余量。Intent 受 Binder 事务上限约束（实践中数百 KB），256 KB 之外属于
     * 病态输入。
     */
    private const val MAX_ROUTE_LENGTH = 262_144
    private val BV_PATTERN = Regex("BV[0-9A-Za-z]{6,30}", RegexOption.IGNORE_CASE)
    private val AID_PATTERN = Regex("(?:av)?[0-9]{1,19}", RegexOption.IGNORE_CASE)
    /**
     * 在宿主统一路由边界将具有明确视频身份的 Story 路由规范化为普通详情路由。
     * 无 aid/BV 的 Story 根页、身份冲突或非哔哩哔哩路由均 fail-open。
     */
    fun normalizeVideoDetailUri(uri: String): String? {
        STORY_URI_ROOTS.forEach { root ->
            parseUnambiguousRoute(uri, root)?.let { route ->
                return rewriteStoryRoute(uri, route, root)
            }
        }
        parseUnambiguousRoute(uri, VIDEO_URI_ROOT) ?: return null
        return sanitizeStoryRoutingHints(uri).takeUnless { it == uri }
    }


    /**
     * 在最终 Activity 启动边界生成完整的普通详情页契约。新版宿主优先使用 United 详情页，
     * 旧版宿主退回 Legacy 详情页；无法证明身份或缺少 United 必需 cid 时保持原 Intent。
     */
    fun planActivityLaunch(
        snapshot: HomeVerticalActivityLaunchSnapshot,
        backend: HomeVerticalDetailBackend
    ): HomeVerticalActivityLaunchOutcome {
        fun skip(reason: HomeVerticalLaunchSkip) =
            HomeVerticalActivityLaunchOutcome.Skipped(reason)
        if (snapshot.componentPackage != null && snapshot.componentPackage != TARGET_PACKAGE) {
            return skip(HomeVerticalLaunchSkip.CROSS_PACKAGE)
        }
        if (snapshot.targetPackage != null && snapshot.targetPackage != TARGET_PACKAGE) {
            return skip(HomeVerticalLaunchSkip.CROSS_PACKAGE)
        }
        val original = snapshot.dataUri ?: return skip(HomeVerticalLaunchSkip.NO_DATA_URI)
        // story 与 story_translucent 都是宿主注册的竖屏路由；两者只有根不同，身份契约一致。
        val storyRoot = storyRootFor(original) ?: return skip(HomeVerticalLaunchSkip.NOT_STORY_ROUTE)
        // 长度拒绝必须落在这里而不是入口门禁：门禁静默，这里才有原因可上报。
        if (original.length > MAX_ROUTE_LENGTH) {
            return skip(HomeVerticalLaunchSkip.ROUTE_TOO_LONG)
        }

        val extraIdentities = parseIntentIdentities(
            HomeVerticalIntentRouteSnapshot(
                dataUri = original,
                componentPackage = snapshot.componentPackage,
                targetPackage = snapshot.targetPackage,
                aid = snapshot.aid,
                avid = snapshot.avid,
                bvid = snapshot.bvid
            )
        ) ?: return skip(HomeVerticalLaunchSkip.MALFORMED_INTENT_IDENTITY)

        // 路径身份缺失时不再整体放弃：查询串身份由 parseUnambiguousRoute 直接给出，两者都
        // 没有的裸根路由再退回 Intent 结构化字段，与卡片层 decide() 的既有语义对齐。
        val route = parseUnambiguousRoute(original, storyRoot)
            ?: run {
                if (!permitsFallbackIdentity(original, storyRoot)) {
                    return skip(HomeVerticalLaunchSkip.MALFORMED_ROUTE)
                }
                val fallback = extraIdentities
                    .singleOrNull { it.kind == CanonicalHomeVideoId.Kind.AID }
                    ?: extraIdentities.singleOrNull()
                    ?: return skip(HomeVerticalLaunchSkip.NO_IDENTITY)
                ParsedRoute(fallback, setOf(fallback), rawToken = null)
            }

        val identities = linkedSetOf<CanonicalHomeVideoId>().apply {
            addAll(route.identities)
            addAll(extraIdentities)
        }
        if (identities.groupBy(CanonicalHomeVideoId::kind).any { it.value.size > 1 }) {
            return skip(HomeVerticalLaunchSkip.IDENTITY_CONFLICT)
        }

        val plan = when (backend) {
            HomeVerticalDetailBackend.LEGACY -> HomeVerticalActivityLaunchPlan.Legacy(
                detailUri = rewriteStoryRoute(original, route, storyRoot)
            )

            HomeVerticalDetailBackend.UNITED -> {
                val identity = route.primaryIdentity
                if (identity.kind != CanonicalHomeVideoId.Kind.AID) {
                    return skip(HomeVerticalLaunchSkip.BV_ONLY_UNITED)
                }
                val aid = identity.value.toLongOrNull()?.takeIf { it > 0L }
                    ?: return skip(HomeVerticalLaunchSkip.NO_IDENTITY)
                val cid = snapshot.preloadCid?.takeIf { it > 0L }
                    ?: return skip(HomeVerticalLaunchSkip.MISSING_PRELOAD_CID)
                val targetUrl = "$UNITED_VIDEO_URI_ROOT/$aid"
                val fromSpmid = uniqueQueryValue(original, FROM_SPMID_QUERY)
                    ?.takeIf { it.length <= MAX_FROM_SPMID_LENGTH }
                val detailUri = buildString {
                    append(targetUrl).append('?')
                    if (fromSpmid != null) {
                        append(FROM_SPMID_QUERY)
                            .append('=')
                            .append(encodeQueryComponent(fromSpmid))
                            .append('&')
                    }
                    append("aid=").append(aid).append("&bvid=")
                }
                HomeVerticalActivityLaunchPlan.United(
                    detailUri = detailUri,
                    targetUrl = targetUrl,
                    aid = aid,
                    cid = cid
                )
            }
        }
        return HomeVerticalActivityLaunchOutcome.Planned(plan)
    }

    /** 仅清理 IntentHandler 中强制回到 Story 的参数，不在该层改写目标页面。 */
    fun sanitizeIntentHandlerUri(uri: String): String? {
        if (uri.length > MAX_ROUTE_LENGTH || storyRootFor(uri) == null) return null
        return sanitizeStoryRoutingHints(uri).takeUnless { it == uri }
    }

    /**
     * 启动边界的廉价前置门禁：只判断是不是宿主的竖屏路由根。
     *
     * 这里**刻意不设长度上限**。门禁返回 false 是完全静默的（否则每次 Activity 启动都会打
     * 日志），所以任何在此处被拒绝的情况都不可归因。实测教训：原实现在门禁上叠了
     * `length <= 4096`，而真实 Story URI 长 35,657 字符，于是每一次失败都既无 OBSERVED
     * 也无放行原因，排查时表现为"功能像是没装"。
     *
     * 现在门禁只回答"这条路由是不是我们的"（`startsWith` 与 URI 长度无关），长度、身份、
     * cid 等一切拒绝理由都由 [planActivityLaunch] 给出有界原因。
     */
    fun isStrictStoryVideoRoute(uri: String?): Boolean =
        uri != null && storyRootFor(uri) != null

    fun parsePlayerPreloadCid(raw: String?): Long? {
        val json = raw?.takeIf { it.length in 2..MAX_PLAYER_PRELOAD_LENGTH } ?: return null
        return runCatching { JSONObject(json).optLong("cid", -1L).takeIf { it > 0L } }
            .getOrNull()
    }

    internal fun canonicalIdentity(raw: String?): CanonicalHomeVideoId? {
        val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (BV_PATTERN.matches(token)) {
            return CanonicalHomeVideoId(
                CanonicalHomeVideoId.Kind.BV,
                "BV" + token.substring(2)
            )
        }
        if (!AID_PATTERN.matches(token)) return null
        val digits = if (token.startsWith("av", ignoreCase = true)) token.substring(2) else token
        val value = digits.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.AID, value.toString())
    }

    private fun CanonicalHomeVideoId.routeToken(): String = when (kind) {
        CanonicalHomeVideoId.Kind.BV -> value
        CanonicalHomeVideoId.Kind.AID -> value
    }

    private fun parseRoute(raw: String?, root: String): ParsedRoute? {
        val route = raw?.takeIf { it.length <= MAX_ROUTE_LENGTH && isRouteFor(it, root) }
            ?: return null
        val suffixStart = route.indexOfFirstFrom(root.length) { it == '?' || it == '#' }
        val pathEnd = suffixStart.takeIf { it >= 0 } ?: route.length
        val path = route.substring(root.length, pathEnd)
        val rawToken = when {
            path.isEmpty() -> null
            !path.startsWith('/') || path.length == 1 || '/' in path.substring(1) -> return null
            else -> path.substring(1)
        }
        val pathIdentity = rawToken?.let(::canonicalIdentity) ?: if (rawToken == null) null else return null
        val query = parseQueryIdentities(route) ?: return null
        val identities = linkedSetOf<CanonicalHomeVideoId>().apply {
            pathIdentity?.let(::add)
            addAll(query)
        }
        val primary = pathIdentity
            ?: identities.firstOrNull { it.kind == CanonicalHomeVideoId.Kind.BV }
            ?: identities.firstOrNull()
            ?: return null
        return ParsedRoute(primary, identities, rawToken)
    }

    private fun parseUnambiguousRoute(raw: String?, root: String): ParsedRoute? {
        val route = parseRoute(raw, root) ?: return null
        if (route.identities.groupBy(CanonicalHomeVideoId::kind).any { it.value.size > 1 }) {
            return null
        }
        return route
    }

    private fun storyRootFor(raw: String?): String? =
        STORY_URI_ROOTS.firstOrNull { isRouteFor(raw, it) }

    private fun parseIntentIdentities(
        snapshot: HomeVerticalIntentRouteSnapshot
    ): Set<CanonicalHomeVideoId>? {
        val identities = linkedSetOf<CanonicalHomeVideoId>()
        listOf(
            snapshot.bvid to CanonicalHomeVideoId.Kind.BV,
            snapshot.aid to CanonicalHomeVideoId.Kind.AID,
            snapshot.avid to CanonicalHomeVideoId.Kind.AID
        ).forEach { (raw, expectedKind) ->
            if (raw == null) return@forEach
            val identity = canonicalIdentity(raw) ?: return null
            if (identity.kind != expectedKind) return null
            identities += identity
        }
        return identities
    }

    /** 只允许“无路径、且没有显式非法视频身份”的 Story 根路由从外部结构化字段补身份。 */
    private fun permitsFallbackIdentity(raw: String?, root: String): Boolean {
        val route = raw?.takeIf { it.length <= MAX_ROUTE_LENGTH && isRouteFor(it, root) }
            ?: return false
        val suffixStart = route.indexOfFirstFrom(root.length) { it == '?' || it == '#' }
        val pathEnd = suffixStart.takeIf { it >= 0 } ?: route.length
        if (route.substring(root.length, pathEnd).isNotEmpty()) return false
        return parseQueryIdentities(route)?.isEmpty() == true
    }

    private data class ParsedRoute(
        val primaryIdentity: CanonicalHomeVideoId,
        val identities: Set<CanonicalHomeVideoId>,
        val rawToken: String?
    )

    private fun isRouteFor(raw: String?, root: String): Boolean {
        if (raw == null || raw.length < root.length || !raw.startsWith(root, ignoreCase = true)) {
            return false
        }
        return raw.length == root.length || raw[root.length] in charArrayOf('/', '?', '#')
    }

    private fun rewriteStoryRoute(raw: String, route: ParsedRoute, storyRoot: String): String {
        val sanitized = sanitizeStoryRoutingHints(raw)
        if (route.rawToken != null) {
            return VIDEO_URI_ROOT + sanitized.substring(storyRoot.length)
        }
        val suffixStart = sanitized.indexOfFirstFrom(storyRoot.length) {
            it == '?' || it == '#'
        }
        val suffix = if (suffixStart >= 0) sanitized.substring(suffixStart) else ""
        return VIDEO_URI_PREFIX + route.primaryIdentity.routeToken() + suffix
    }

    /** 去掉宿主用于强制进入 Story 的控制参数，保留其余参数的原始编码、顺序和 fragment。 */
    private fun sanitizeStoryRoutingHints(raw: String): String {
        val queryStart = raw.indexOf('?')
        if (queryStart < 0) return raw
        val fragmentStart = raw.indexOf('#', queryStart + 1).takeIf { it >= 0 } ?: raw.length
        val components = raw.substring(queryStart + 1, fragmentStart).split('&')
        val retained = components.filterNot(::isStoryRoutingHint)
        if (retained.size == components.size) return raw
        return buildString(raw.length) {
            append(raw, 0, queryStart)
            if (retained.isNotEmpty()) append('?').append(retained.joinToString("&"))
            if (fragmentStart < raw.length) append(raw.substring(fragmentStart))
        }
    }

    private fun isStoryRoutingHint(component: String): Boolean {
        val delimiter = component.indexOf('=')
        val encodedName = if (delimiter >= 0) component.substring(0, delimiter) else component
        val name = decodeQueryComponent(encodedName)?.lowercase() ?: return false
        // 先判名再解值：宿主把整个 DASH manifest 内联进 player_preload（实测解码后 28 KB），
        // 无条件解码每个组件的值只为查白名单，纯属浪费。名字不在白名单里就没必要解码。
        if (name !in STORY_ROUTING_QUERY_KEYS) return false
        val encodedValue = if (delimiter >= 0) component.substring(delimiter + 1) else ""
        val value = decodeQueryComponent(encodedValue) ?: return false
        return value.equals("story", ignoreCase = true)
    }

    /** 返回 null 表示出现了显式但无效的 aid/BV 查询身份，调用方据此 fail-open。 */
    private fun parseQueryIdentities(raw: String): Set<CanonicalHomeVideoId>? {
        val queryStart = raw.indexOf('?')
        if (queryStart < 0) return emptySet()
        val fragmentStart = raw.indexOf('#', queryStart + 1).takeIf { it >= 0 } ?: raw.length
        val identities = linkedSetOf<CanonicalHomeVideoId>()
        raw.substring(queryStart + 1, fragmentStart).split('&').forEach { component ->
            val delimiter = component.indexOf('=')
            if (delimiter <= 0) return@forEach
            val name = decodeQueryComponent(component.substring(0, delimiter))
                ?.lowercase() ?: return@forEach
            if (name !in VIDEO_ID_QUERY_KEYS) return@forEach
            val value = decodeQueryComponent(component.substring(delimiter + 1)) ?: return null
            val identity = canonicalIdentity(value) ?: return null
            if (name == "bvid" && identity.kind != CanonicalHomeVideoId.Kind.BV) return null
            if (name != "bvid" && identity.kind != CanonicalHomeVideoId.Kind.AID) return null
            identities += identity
        }
        return identities
    }

    private fun decodeQueryComponent(raw: String): String? =
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull()

    private fun encodeQueryComponent(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

    private fun uniqueQueryValue(raw: String, expectedName: String): String? {
        val queryStart = raw.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = raw.indexOf('#', queryStart + 1).takeIf { it >= 0 } ?: raw.length
        val values = raw.substring(queryStart + 1, fragmentStart)
            .split('&')
            .mapNotNull { component ->
                val delimiter = component.indexOf('=')
                if (delimiter <= 0) return@mapNotNull null
                val name = decodeQueryComponent(component.substring(0, delimiter)) ?: return null
                if (!name.equals(expectedName, ignoreCase = true)) return@mapNotNull null
                decodeQueryComponent(component.substring(delimiter + 1)) ?: return null
            }
            .distinct()
        return values.singleOrNull()
    }

    private inline fun String.indexOfFirstFrom(
        startIndex: Int,
        predicate: (Char) -> Boolean
    ): Int {
        for (index in startIndex until length) if (predicate(this[index])) return index
        return -1
    }

    private val STORY_ROUTING_QUERY_KEYS = setOf("-arouter", "-atype")
    private val VIDEO_ID_QUERY_KEYS = setOf("aid", "avid", "bvid")
    private val STORY_URI_ROOTS = listOf(STORY_TRANSLUCENT_URI_ROOT, STORY_URI_ROOT)
    private val STORY_ACTIVITY_CLASSES = setOf(
        "com.bilibili.video.story.StoryVideoActivity",
        "com.bilibili.video.story.StoryTransparentActivity"
    )
    private const val UNITED_VIDEO_URI_ROOT = "bilibili://united_video"
    private const val FROM_SPMID_QUERY = "from_spmid"
    private const val MAX_FROM_SPMID_LENGTH = 512
    /**
     * 实测同一条 URI 的 `player_preload` 解码后为 **28,449** 字符。原值 8192 与
     * [MAX_ROUTE_LENGTH] 的旧值 4096 还自相矛盾——preload 是 URI 的子串，8192 的载荷永远
     * 装不进 4096 的 URI。必须小于 [MAX_ROUTE_LENGTH]。
     */
    private const val MAX_PLAYER_PRELOAD_LENGTH = 131_072
    private const val TARGET_PACKAGE = "tv.danmaku.bili"

    internal const val NORMAL_AV_GOTO: String = AV_GOTO
}

internal data class HomeVerticalIntentRouteSnapshot(
    val dataUri: String?,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val targetPackage: String? = null,
    val aid: String? = null,
    val avid: String? = null,
    val bvid: String? = null
)

internal enum class HomeVerticalDetailBackend(val activityClassName: String) {
    UNITED("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"),
    LEGACY("com.bilibili.video.videodetail.VideoDetailsActivity")
}

internal data class HomeVerticalActivityLaunchSnapshot(
    val dataUri: String?,
    val componentPackage: String? = null,
    val targetPackage: String? = null,
    val aid: String? = null,
    val avid: String? = null,
    val bvid: String? = null,
    /** 已由调用方按多来源解析出的 cid；策略层不关心它来自 URI 查询还是 Intent extra。 */
    val preloadCid: Long? = null
)

/**
 * 启动边界未改写的有界原因。
 *
 * 只用于日志与诊断，不携带任何 URI 内容、视频身份或跟踪参数。放行原因必须可枚举——静默
 * `return` 会让"有概率不生效"退化成无法定位的现象。
 */
internal enum class HomeVerticalLaunchSkip {
    NO_DATA_URI,
    CROSS_PACKAGE,
    NOT_STORY_ROUTE,
    ROUTE_TOO_LONG,
    MALFORMED_ROUTE,
    NO_IDENTITY,
    MALFORMED_INTENT_IDENTITY,
    IDENTITY_CONFLICT,
    BV_ONLY_UNITED,
    MISSING_PRELOAD_CID
}

internal sealed interface HomeVerticalActivityLaunchOutcome {
    data class Planned(val plan: HomeVerticalActivityLaunchPlan) : HomeVerticalActivityLaunchOutcome
    data class Skipped(val reason: HomeVerticalLaunchSkip) : HomeVerticalActivityLaunchOutcome
}

internal sealed interface HomeVerticalActivityLaunchPlan {
    val detailUri: String

    data class Legacy(
        override val detailUri: String
    ) : HomeVerticalActivityLaunchPlan

    data class United(
        override val detailUri: String,
        val targetUrl: String,
        val aid: Long,
        val cid: Long
    ) : HomeVerticalActivityLaunchPlan
}

internal data class HomeVerticalReadAccessors(
    val cardGoto: Method?,
    val goTo: Method?,
    val uri: Method?
)

internal enum class HomeVerticalMutationResult {
    APPLIED,
    NO_SAFE_ACCESSOR,
    ROLLED_BACK,
    ROLLBACK_INCOMPLETE
}

/**
 * 按具体卡片类缓存 setter/稳定序列化字段；一次事务中全部写入并读回，失败则恢复原值。
 * 缓存只持有 Class/Method/Field，不持有宿主卡片实例。
 */
internal class ConcreteHomeVerticalRouteMutator {
    private val accessors = ConcurrentHashMap<Class<*>, ConcreteAccessors>()

    fun apply(
        item: Any,
        snapshot: HomeVerticalRouteSnapshot,
        plan: HomeVerticalRoutePlan,
        readers: HomeVerticalReadAccessors
    ): HomeVerticalMutationResult = synchronized(item) {
        val resolved = accessors.computeIfAbsent(item.javaClass, ::resolveAccessors)
        val changes = buildList {
            if (plan.rewriteCardGoto) {
                add(
                    PropertyChange(
                        resolved.cardGoto,
                        snapshot.cardGoto,
                        HomeVerticalDetailRoutePolicy.NORMAL_AV_GOTO,
                        readers.cardGoto
                    )
                )
            }
            if (plan.rewriteGoTo) {
                add(
                    PropertyChange(
                        resolved.goTo,
                        snapshot.goTo,
                        HomeVerticalDetailRoutePolicy.NORMAL_AV_GOTO,
                        readers.goTo
                    )
                )
            }
            if (plan.rewriteUri) {
                add(PropertyChange(resolved.uri, snapshot.uri, plan.detailUri, readers.uri))
            }
        }
        if (changes.isEmpty() || changes.any { it.writer == null || it.reader == null }) {
            return@synchronized HomeVerticalMutationResult.NO_SAFE_ACCESSOR
        }

        val cacheSnapshot = resolved.cacheFields.mapNotNull { field ->
            runCatching { field to field.get(item) }.getOrNull()
        }
        val applied = ArrayList<PropertyChange>(changes.size)
        for (change in changes) {
            if (change.writer?.write(item, change.expected) != true) {
                return@synchronized rollback(item, applied, cacheSnapshot)
            }
            applied += change
        }
        if (!clearCaches(item, resolved.cacheFields) ||
            changes.any { change -> invokeString(change.reader, item) != change.expected }
        ) {
            return@synchronized rollback(item, applied, cacheSnapshot)
        }
        HomeVerticalMutationResult.APPLIED
    }

    internal fun cachedClassCount(): Int = accessors.size

    private fun rollback(
        item: Any,
        applied: List<PropertyChange>,
        cacheSnapshot: List<Pair<Field, Any?>>
    ): HomeVerticalMutationResult {
        var complete = true
        applied.asReversed().forEach { change ->
            if (change.writer?.write(item, change.original) != true) complete = false
        }
        cacheSnapshot.forEach { (field, value) ->
            if (runCatching { field.set(item, value) }.isFailure) complete = false
        }
        return if (complete) {
            HomeVerticalMutationResult.ROLLED_BACK
        } else {
            HomeVerticalMutationResult.ROLLBACK_INCOMPLETE
        }
    }

    private fun clearCaches(item: Any, fields: List<Field>): Boolean = fields.all { field ->
        runCatching { field.set(item, null) }.isSuccess
    }

    private fun resolveAccessors(type: Class<*>): ConcreteAccessors = ConcreteAccessors(
        cardGoto = propertyWriter(type, "setCardGoto", "cardGoto", "card_goto"),
        goTo = propertyWriter(type, "setGoTo", "goTo", "goto"),
        uri = propertyWriter(type, "setUri", "uri", "uri"),
        cacheFields = KavaMemberLookup.fields(
            type,
            includeSuperclasses = true,
            makeAccessible = true
        ) { field ->
            !field.isStatic &&
                !field.isFinal &&
                !field.type.isPrimitive &&
                field.name in ROUTE_CACHE_FIELD_NAMES
        }.distinctBy(Field::toGenericString)
    )

    private fun propertyWriter(
        type: Class<*>,
        setterName: String,
        fieldName: String,
        serializedName: String
    ): PropertyWriter? {
        val setter = mostSpecific(
            type,
            KavaMemberLookup.methods(
                type,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == setterName && method.parameterCount == 1 &&
                    method.parameterTypes[0] == classOf<String>() &&
                    !method.isStatic &&
                    !method.isAbstract
            }.distinctBy(Method::toGenericString)
        )
        val stringFields = KavaMemberLookup.fields(
            type,
            includeSuperclasses = true,
            makeAccessible = true
        ) { field ->
            field.type == classOf<String>() && !field.isStatic &&
                !field.isFinal
        }.distinctBy(Field::toGenericString)
        val annotated = mostSpecific(
            type,
            stringFields.filter { it.serializedNameValue() == serializedName }
        )
        val field = annotated ?: mostSpecific(type, stringFields.filter { it.name == fieldName })
        if (setter == null && field == null) return null
        return PropertyWriter(setter, field)
    }

    private fun <T> mostSpecific(type: Class<*>, candidates: List<T>): T? where T : java.lang.reflect.Member {
        if (candidates.isEmpty()) return null
        val scored = candidates.map { it to inheritanceDistance(type, it.declaringClass) }
        val nearest = scored.minOf { it.second }
        return scored.filter { it.second == nearest }.map { it.first }.singleOrNull()
    }

    private fun inheritanceDistance(type: Class<*>, owner: Class<*>): Int {
        var current: Class<*>? = type
        var distance = 0
        while (current != null) {
            if (current == owner) return distance
            current = current.superclass
            distance += 1
        }
        return Int.MAX_VALUE
    }

    private fun Field.serializedNameValue(): String? =
        declaredAnnotations.firstNotNullOfOrNull { annotation ->
            val annotationType = annotation.annotationClass.java
            val attribute = when (annotationType.name) {
                GSON_SERIALIZED_NAME -> "value"
                FASTJSON_JSON_FIELD -> "name"
                else -> null
            } ?: return@firstNotNullOfOrNull null
            runCatching { annotationType.getMethod(attribute).invoke(annotation) as? String }.getOrNull()
        }

    private fun invokeString(method: Method?, target: Any): String? {
        if (method == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) as? String }.getOrNull()
    }

    private data class ConcreteAccessors(
        val cardGoto: PropertyWriter?,
        val goTo: PropertyWriter?,
        val uri: PropertyWriter?,
        val cacheFields: List<Field>
    )

    private data class PropertyChange(
        val writer: PropertyWriter?,
        val original: String?,
        val expected: String,
        val reader: Method?
    )

    private data class PropertyWriter(
        val setter: Method?,
        val field: Field?
    ) {
        fun write(target: Any, value: String?): Boolean {
            val writableField = field
            if (value == null && writableField != null) {
                return runCatching { writableField.set(target, null) }.isSuccess
            }
            val writableSetter = setter
            if (writableSetter != null && runCatching { writableSetter.invoke(target, value) }.isSuccess) return true
            return writableField != null && runCatching { writableField.set(target, value) }.isSuccess
        }
    }

    private companion object {
        private const val GSON_SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"
        private const val FASTJSON_JSON_FIELD = "com.alibaba.fastjson.annotation.JSONField"
        private val ROUTE_CACHE_FIELD_NAMES = setOf("stringUriCache", "uriCache")
    }
}
