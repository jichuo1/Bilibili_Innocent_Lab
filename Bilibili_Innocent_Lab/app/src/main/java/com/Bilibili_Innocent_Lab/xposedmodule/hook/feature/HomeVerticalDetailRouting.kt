package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** 首页竖屏卡片进入普通详情页所需的最小、脱敏路由快照。 */
internal data class HomeVerticalRouteSnapshot(
    val holderType: String? = null,
    val bizType: String? = null,
    val cardType: String? = null,
    val cardGoto: String? = null,
    val goTo: String? = null,
    val uri: String? = null,
    val param: String? = null,
    val hasAdInfo: Boolean = false
) {
    fun toHostSignals(): HostContentSignals = HostContentSignals(
        holderType = holderType,
        bizType = bizType,
        cardType = cardType,
        cardGoto = cardGoto,
        goTo = goTo,
        uri = uri,
        param = param,
        hasAdInfo = hasAdInfo
    )
}

/** 规范视频身份只用于进程内短期匹配，不保存标题、完整 URI 或跟踪参数。 */
internal data class CanonicalHomeVideoId(
    val kind: Kind,
    val value: String
) {
    enum class Kind { BV, AID }

    val registryKey: String
        get() = "${kind.name}:$value"
}

internal data class HomeVerticalRoutePlan(
    val identity: CanonicalHomeVideoId,
    val detailUri: String,
    val rewriteCardGoto: Boolean,
    val rewriteGoTo: Boolean,
    val rewriteUri: Boolean
)

internal sealed interface HomeVerticalRouteDecision {
    data object NotVertical : HomeVerticalRouteDecision

    data class KeepOriginal(val reason: Reason) : HomeVerticalRouteDecision

    data class Rewrite(val plan: HomeVerticalRoutePlan) : HomeVerticalRouteDecision

    enum class Reason {
        UNSAFE_CONTENT_KIND,
        MISSING_OR_INVALID_VIDEO_ID,
        CONFLICTING_VIDEO_ID,
        NO_ROUTE_CHANGE_REQUIRED
    }
}

/**
 * 纯路由策略：只在结构化证据确认是普通投稿型竖屏卡片、且 BV/aid 可验证时生成改写计划。
 * 未知、冲突或特殊业务卡片全部 fail-open。
 */
internal object HomeVerticalDetailRoutePolicy {
    private const val STORY_URI_PREFIX = "bilibili://story/"
    private const val VIDEO_URI_PREFIX = "bilibili://video/"
    private const val VERTICAL_AV_GOTO = "vertical_av"
    private const val AV_GOTO = "av"
    private const val MAX_ROUTE_LENGTH = 4_096
    private val BV_PATTERN = Regex("BV[0-9A-Za-z]{6,30}", RegexOption.IGNORE_CASE)
    private val AID_PATTERN = Regex("(?:av)?[0-9]{1,19}", RegexOption.IGNORE_CASE)
    private val excludedKinds = setOf(
        HostContentKind.ADVERTISEMENT,
        HostContentKind.PICTURE,
        HostContentKind.GAME,
        HostContentKind.LIVE,
        HostContentKind.COURSE,
        HostContentKind.BANGUMI,
        HostContentKind.SPECIAL
    )

    fun decide(snapshot: HomeVerticalRouteSnapshot): HomeVerticalRouteDecision {
        val kinds = HostContentSemanticClassifier.classify(snapshot.toHostSignals())
        if (HostContentKind.VERTICAL !in kinds) return HomeVerticalRouteDecision.NotVertical
        if (kinds.any(excludedKinds::contains)) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.UNSAFE_CONTENT_KIND
            )
        }

        val storyRoute = parseRoute(snapshot.uri, STORY_URI_PREFIX)
        val videoRoute = parseRoute(snapshot.uri, VIDEO_URI_PREFIX)
        if (snapshot.uri?.startsWith(STORY_URI_PREFIX) == true && storyRoute == null) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.MISSING_OR_INVALID_VIDEO_ID
            )
        }
        if (snapshot.uri?.startsWith(VIDEO_URI_PREFIX) == true && videoRoute == null) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.MISSING_OR_INVALID_VIDEO_ID
            )
        }
        if (!snapshot.uri.isNullOrBlank() && storyRoute == null && videoRoute == null) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.MISSING_OR_INVALID_VIDEO_ID
            )
        }

        val paramIdentity = canonicalIdentity(snapshot.param)
        val routeIdentity = storyRoute?.identity ?: videoRoute?.identity
        if (routeIdentity != null && paramIdentity != null && routeIdentity != paramIdentity) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.CONFLICTING_VIDEO_ID
            )
        }
        val identity = routeIdentity ?: paramIdentity
            ?: return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.MISSING_OR_INVALID_VIDEO_ID
            )

        val detailUri = when {
            storyRoute != null -> VIDEO_URI_PREFIX + storyRoute.rawToken + storyRoute.suffix
            videoRoute != null -> snapshot.uri.orEmpty()
            else -> VIDEO_URI_PREFIX + identity.routeToken()
        }
        val rewriteCardGoto = snapshot.cardGoto == VERTICAL_AV_GOTO
        val rewriteGoTo = snapshot.goTo == VERTICAL_AV_GOTO
        val rewriteUri = storyRoute != null || videoRoute == null
        if (!rewriteCardGoto && !rewriteGoTo && !rewriteUri) {
            return HomeVerticalRouteDecision.KeepOriginal(
                HomeVerticalRouteDecision.Reason.NO_ROUTE_CHANGE_REQUIRED
            )
        }
        return HomeVerticalRouteDecision.Rewrite(
            HomeVerticalRoutePlan(
                identity = identity,
                detailUri = detailUri,
                rewriteCardGoto = rewriteCardGoto,
                rewriteGoTo = rewriteGoTo,
                rewriteUri = rewriteUri
            )
        )
    }

    fun rewriteRegisteredStoryUri(
        uri: String,
        isRegistered: (CanonicalHomeVideoId) -> Boolean
    ): String? {
        val route = parseRoute(uri, STORY_URI_PREFIX) ?: return null
        if (!isRegistered(route.identity)) return null
        return VIDEO_URI_PREFIX + route.rawToken + route.suffix
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
        val digits = token.removePrefix("av").removePrefix("AV")
        val value = digits.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.AID, value.toString())
    }

    private fun CanonicalHomeVideoId.routeToken(): String = when (kind) {
        CanonicalHomeVideoId.Kind.BV -> value
        CanonicalHomeVideoId.Kind.AID -> value
    }

    private fun parseRoute(raw: String?, prefix: String): ParsedRoute? {
        val route = raw?.takeIf { it.length <= MAX_ROUTE_LENGTH && it.startsWith(prefix) }
            ?: return null
        val tail = route.removePrefix(prefix)
        val delimiter = tail.indexOfFirst { it == '?' || it == '#' }
        val rawToken = if (delimiter >= 0) tail.substring(0, delimiter) else tail
        val suffix = if (delimiter >= 0) tail.substring(delimiter) else ""
        if (rawToken.isEmpty() || '/' in rawToken) return null
        val identity = canonicalIdentity(rawToken) ?: return null
        return ParsedRoute(identity, rawToken, suffix)
    }

    private data class ParsedRoute(
        val identity: CanonicalHomeVideoId,
        val rawToken: String,
        val suffix: String
    )

    internal const val NORMAL_AV_GOTO: String = AV_GOTO
}

/** 有界、过期的首页视频身份集合，防止路由层兜底扩大为全局 Story 改写。 */
internal class RecentHomeVideoRegistry(
    private val maxEntries: Int = 384,
    private val ttlMillis: Long = 10 * 60 * 1_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val entries = ConcurrentHashMap<String, Long>()

    init {
        require(maxEntries > 0)
        require(ttlMillis > 0L)
    }

    fun register(identity: CanonicalHomeVideoId) {
        val now = nowMillis()
        entries[identity.registryKey] = now + ttlMillis
        if (entries.size > maxEntries) trim(now)
    }

    fun contains(identity: CanonicalHomeVideoId): Boolean {
        val key = identity.registryKey
        val expiry = entries[key] ?: return false
        if (expiry > nowMillis()) return true
        entries.remove(key, expiry)
        return false
    }

    fun rewriteIfRegistered(uri: String): String? =
        HomeVerticalDetailRoutePolicy.rewriteRegisteredStoryUri(uri, ::contains)

    internal fun size(): Int = entries.size

    private fun trim(now: Long) {
        entries.entries.removeIf { it.value <= now }
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value } ?: break
            entries.remove(oldest.key, oldest.value)
        }
    }
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
            !Modifier.isStatic(field.modifiers) &&
                !Modifier.isFinal(field.modifiers) &&
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
                    method.parameterTypes[0] == String::class.java &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers)
            }.distinctBy(Method::toGenericString)
        )
        val stringFields = KavaMemberLookup.fields(
            type,
            includeSuperclasses = true,
            makeAccessible = true
        ) { field ->
            field.type == String::class.java && !Modifier.isStatic(field.modifiers) &&
                !Modifier.isFinal(field.modifiers)
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
