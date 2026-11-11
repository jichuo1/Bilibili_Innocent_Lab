package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 稳定 owner 仅反射；混淆的画质计算角色必须有方法体语义证据，不能按字母名猜测。 */
internal object StoryQualityLocator {
    const val QUALITY_PACKAGE = "com.bilibili.video.story.player.quality"
    const val PREFERENCE_PACKAGE = "com.bilibili.player.tangram.basic"
    const val SERVICE = "$QUALITY_PACKAGE.StoryQualityService"
    const val OWNER = "com.bilibili.video.story.player.StoryPlayer"
    const val CONTINUATION = "kotlin.coroutines.Continuation"
    const val CONTINUATION_IMPL = "kotlin.coroutines.jvm.internal.ContinuationImpl"
    const val LIFECYCLE_OWNER = "androidx.lifecycle.LifecycleOwner"
    const val MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource"
    const val SETTING_COMPANION = "tv.danmaku.biliplayerv2.service.setting.IPlayerSettingService\$Companion"
    const val DEFAULT_HELPER = "com.bilibili.playerbizcommon.utils.PlayerSettingHelper"
    const val WIFI_KEY = "pref_player_mediaSource_quality_wifi_key"
    const val RESOURCE_MARK = "set MediaResource resolvedIndex "
    const val MATCH_MARK = "matchPreferenceQuality switch to auto"
    const val DIAGNOSTIC = "dex.assist.story_quality"
    val queries = setOf(DexAssistQuery.STORY_QUALITY_DEFAULT, DexAssistQuery.STORY_QUALITY_RESOURCE, DexAssistQuery.STORY_QUALITY_MATCH)

    /** 这些成员仅表示可注册角色；真正起点、当前条目与实时准入由运行安装器另行验证。 */
    data class HostAccess(val points: StoryQualityPoints, val owner: Class<*>, val service: Class<*>,
        val preference: StoryQualityPreferenceAccess, val manual: Method, val serviceField: Field,
        val onCreate: Method, val release: Method, val onDestroy: Method, val settingIntReader: Method,
        val integerDefault: Method?, val policy: Method?, val resourceSelection: Method?, val matchPreference: Method?)

    fun applicable(loader: ClassLoader): Boolean = Lookup.classOrNull(loader, SERVICE) != null

    fun direct(loader: ClassLoader): StoryQualityPoints? = runCatching {
        val service = Lookup.classOrNull(loader, SERVICE) ?: return null
        val owner = Lookup.classOrNull(loader, OWNER) ?: return null
        val manual = Lookup.declaredMethods(service, true) { method ->
            val p = method.parameterTypes
            instance(method) && Modifier.isPublic(method.modifiers) && method.returnType == Any::class.java &&
                p.size == 2 && p[0].name.startsWith("$PREFERENCE_PACKAGE.") && Modifier.isAbstract(p[0].modifiers) &&
                p[1].name == CONTINUATION
        }.singleOrNull() ?: return null
        val field = Lookup.declaredFields(owner, true) { !Modifier.isStatic(it.modifiers) && it.type == service }
            .singleOrNull() ?: return null
        fun lifecycle(name: String, params: List<String>): Method? = Lookup.declaredMethods(owner, true) {
            instance(it) && it.name == name && it.returnType == Void.TYPE && it.parameterTypes.map(Class<*>::getName) == params
        }.singleOrNull()
        StoryQualityPoints(manual.parameterTypes[0].name, point(manual), field.name,
            point(lifecycle("onCreate", listOf(LIFECYCLE_OWNER)) ?: return null),
            point(lifecycle("release", emptyList()) ?: return null),
            point(lifecycle("onDestroy", listOf(LIFECYCLE_OWNER)) ?: return null))
    }.getOrNull()

    fun planned(loader: ClassLoader, cached: StoryQualityPoints?, enabled: Boolean, attempted: Boolean): Set<DexAssistQuery> {
        if (!enabled || attempted || !applicable(loader)) return emptySet()
        val points = merge(direct(loader), validated(loader, cached)) ?: return queries
        return buildSet {
            if (points.policyMethod == null) add(DexAssistQuery.STORY_QUALITY_DEFAULT)
            if (points.resourceSelectionMethod == null) add(DexAssistQuery.STORY_QUALITY_RESOURCE)
            if (points.matchPreferenceMethod == null) add(DexAssistQuery.STORY_QUALITY_MATCH)
        }
    }

    fun locate(loader: ClassLoader, session: DexAssistSession?, cached: StoryQualityPoints?,
               planned: Set<DexAssistQuery>): StoryQualityPoints? {
        var points = merge(direct(loader), validated(loader, cached)) ?: return null
        fun methods(query: DexAssistQuery): List<Method> = if (query !in planned) emptyList() else
            (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty().takeIf { it.size <= 32 }.orEmpty()
        val defaults = methods(DexAssistQuery.STORY_QUALITY_DEFAULT)
        if (DexAssistQuery.STORY_QUALITY_DEFAULT in planned) {
            val integer = defaults.filter(::integerAbi).distinct().singleOrNull()
            val policy = defaults.filter { policyAbi(it, points.preferenceClassName) }.distinct().singleOrNull()
            val resource = defaults.filter(::resourceAbi).distinct().singleOrNull()
            if (integer == null || policy == null || integer.declaringClass == policy.declaringClass) points = points.copy(
                integerDefaultMethod = integer?.let(::point), policyMethod = policy?.let(::point),
                resourceSelectionMethod = resource?.takeIf { policy?.declaringClass == it.declaringClass }?.let(::point))
        }
        if (DexAssistQuery.STORY_QUALITY_RESOURCE in planned) {
            val resource = methods(DexAssistQuery.STORY_QUALITY_RESOURCE).filter(::resourceAbi).distinct().singleOrNull()
            if (resource != null && (points.policyMethod == null || points.policyMethod.className == resource.declaringClass.name))
                points = points.copy(resourceSelectionMethod = point(resource))
        }
        if (DexAssistQuery.STORY_QUALITY_MATCH in planned) {
            val result = session?.result(DexAssistQuery.STORY_QUALITY_MATCH) as? DexAssistResult.Candidates
            val candidates = methods(DexAssistQuery.STORY_QUALITY_MATCH)
            val match = candidates.filter(::matchAbi).filter { method ->
                result?.relatedClasses?.get(method)?.singleOrNull()?.name == points.policyMethod?.className
            }.distinct().singleOrNull()
            if (match != null) points = points.copy(matchPreferenceMethod = point(match))
            if (!points.semanticComplete && result != null) {
                points = locateFlattened(points, candidates, result.relatedClasses) ?: points
            }
        }
        return points.takeIf(StoryQualityPoints::isStructurallyValid)
    }

    private fun locateFlattened(points: StoryQualityPoints, candidates: List<Method>,
                                related: Map<Method, List<Class<*>>>): StoryQualityPoints? {
        val match = candidates.filter(::matchAbi).distinct().singleOrNull() ?: return null
        val owner = related[match]?.singleOrNull()?.takeIf { flattenedQualityOwner(it.name) } ?: return null
        val members = candidates.filter { it.declaringClass === owner }.distinct()
        val policy = members.singleOrNull { policyAbi(it, points.preferenceClassName) } ?: return null
        val resource = members.singleOrNull(::resourceAbi) ?: return null
        return points.copy(integerDefaultMethod = null, policyMethod = point(policy),
            resourceSelectionMethod = point(resource), matchPreferenceMethod = point(match))
    }

    fun merge(live: StoryQualityPoints?, cached: StoryQualityPoints?): StoryQualityPoints? {
        live ?: return cached
        if (cached == null || live.preferenceClassName != cached.preferenceClassName ||
            live.manualMethod != cached.manualMethod || live.serviceFieldName != cached.serviceFieldName) return live
        val liveOwner = live.policyMethod?.className ?: live.integerDefaultMethod?.className ?: live.resourceSelectionMethod?.className
        val cachedOwner = cached.policyMethod?.className ?: cached.integerDefaultMethod?.className ?: cached.resourceSelectionMethod?.className
        if (liveOwner != null && cachedOwner != null && liveOwner != cachedOwner) return live
        val policy = live.policyMethod ?: cached.policyMethod
        val resource = live.resourceSelectionMethod ?: cached.resourceSelectionMethod
        val integer = live.integerDefaultMethod ?: cached.integerDefaultMethod
        return live.copy(integerDefaultMethod = integer, policyMethod = policy,
            resourceSelectionMethod = if (policy == null || resource?.className == policy.className) resource else null,
            matchPreferenceMethod = live.matchPreferenceMethod ?: cached.matchPreferenceMethod)
    }

    fun validated(loader: ClassLoader, points: StoryQualityPoints?): StoryQualityPoints? {
        points ?: return null
        if (!points.isStructurallyValid()) return null
        val direct = direct(loader) ?: return null
        if (direct.preferenceClassName != points.preferenceClassName || direct.manualMethod != points.manualMethod ||
            direct.serviceFieldName != points.serviceFieldName || direct.ownerOnCreate != points.ownerOnCreate ||
            direct.ownerRelease != points.ownerRelease || direct.ownerOnDestroy != points.ownerOnDestroy) return null
        return direct.copy(
            integerDefaultMethod = resolvePoint(loader, points.integerDefaultMethod, ::integerAbi)?.let(::point),
            policyMethod = resolvePoint(loader, points.policyMethod) { policyAbi(it, direct.preferenceClassName) }?.let(::point),
            resourceSelectionMethod = resolvePoint(loader, points.resourceSelectionMethod, ::resourceAbi)?.let(::point),
            matchPreferenceMethod = resolvePoint(loader, points.matchPreferenceMethod, ::matchAbi)?.let(::point))
            .takeIf(StoryQualityPoints::isStructurallyValid)
    }

    fun resolve(loader: ClassLoader, points: StoryQualityPoints?): HostAccess? = runCatching {
        val valid = validated(loader, points) ?: return null
        val owner = Lookup.classOrNull(loader, OWNER) ?: return null
        val service = Lookup.classOrNull(loader, SERVICE) ?: return null
        val preferenceClass = Lookup.classOrNull(loader, valid.preferenceClassName) ?: return null
        val preference = StoryQualityPreferenceAccess.resolve(loader, preferenceClass) ?: return null
        val field = Lookup.fieldOrNull(owner, valid.serviceFieldName)?.takeIf { it.type == service && !Modifier.isStatic(it.modifiers) }
            ?: return null
        val companion = Lookup.classOrNull(loader, SETTING_COMPANION) ?: return null
        val intReader = Lookup.declaredMethods(companion, true) {
            instance(it) && it.name == "getInt" && it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType!!))
        }.singleOrNull() ?: return null
        HostAccess(valid, owner, service, preference,
            resolvePoint(loader, valid.manualMethod) { instance(it) } ?: return null, field,
            resolvePoint(loader, valid.ownerOnCreate) { instance(it) } ?: return null,
            resolvePoint(loader, valid.ownerRelease) { instance(it) } ?: return null,
            resolvePoint(loader, valid.ownerOnDestroy) { instance(it) } ?: return null, intReader,
            resolvePoint(loader, valid.integerDefaultMethod, ::integerAbi),
            resolvePoint(loader, valid.policyMethod) { policyAbi(it, valid.preferenceClassName) },
            resolvePoint(loader, valid.resourceSelectionMethod, ::resourceAbi),
            resolvePoint(loader, valid.matchPreferenceMethod, ::matchAbi))
    }.getOrNull()

    private fun resolvePoint(loader: ClassLoader, point: HookPoint?, abi: (Method) -> Boolean): Method? {
        point ?: return null
        if (point.paramClassNames == null || point.viewField != null) return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, true) {
            it.name == point.methodName && it.parameterTypes.map(Class<*>::getName) == point.paramClassNames && abi(it)
        }.singleOrNull()
    }

    private fun instance(method: Method) = !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge
    internal fun flattenedQualityOwner(name: String): Boolean = name.length in 1..128 &&
        (name.first() in 'a'..'z' || name.first() in 'A'..'Z') &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '$' }
    private fun staticQuality(method: Method, allowFlattened: Boolean = false) =
        Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge &&
            (method.declaringClass.name.startsWith("$QUALITY_PACKAGE.") ||
                (allowFlattened && flattenedQualityOwner(method.declaringClass.name)))
    internal fun integerAbi(method: Method) = staticQuality(method) && method.parameterCount == 0 && method.returnType == Int::class.javaPrimitiveType
    internal fun policyAbi(method: Method, preference: String) = staticQuality(method, allowFlattened = true) && method.parameterCount == 0 &&
        method.returnType.name == preference && method.returnType.name.startsWith("$PREFERENCE_PACKAGE.")
    internal fun resourceAbi(method: Method) = staticQuality(method, allowFlattened = true) && method.returnType == Void.TYPE &&
        method.parameterTypes.map(Class<*>::getName) == listOf(MEDIA_RESOURCE)
    internal fun matchAbi(method: Method) = instance(method) && method.declaringClass.name == SERVICE &&
        method.returnType == Any::class.java && method.parameterTypes.singleOrNull()?.name in setOf(CONTINUATION, CONTINUATION_IMPL)
    private fun point(method: Method) = HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName))
}
