package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistCandidateSelector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只在后台适配验真的本地检测结果边界，不保留宿主返回值或业务实例。 */
data class SystemEnvironmentPoints(
    val environment: HookPoint? = null,
    val hook: HookPoint? = null
) {
    val isEmpty: Boolean get() = environment == null && hook == null

    fun toJson() = JSONObject().apply {
        environment?.let { put("environment", it.toJson()) }
        hook?.let { put("hook", it.toJson()) }
    }

    internal fun isStructurallyValid(): Boolean = !isEmpty &&
        (environment == null || validPoint(environment, listOf(SystemEnvironmentAdapter.ENV_PARAM_SET))) &&
        (hook == null || validPoint(hook, emptyList()))

    companion object {
        internal fun fromJson(json: JSONObject): SystemEnvironmentPoints? = runCatching {
            fun read(key: String, expectedParams: List<String>): HookPoint? {
                if (!json.has(key)) return null
                val raw = json.getJSONObject(key)
                require(raw.has("params") && raw.getJSONArray("params").length() == expectedParams.size)
                return HookPoint.fromJson(raw).also { point ->
                    require(validPoint(point, expectedParams))
                }
            }
            SystemEnvironmentPoints(
                read("environment", listOf(SystemEnvironmentAdapter.ENV_PARAM_SET)),
                read("hook", emptyList())
            ).takeUnless { it.isEmpty }
        }.getOrNull()

        private fun validPoint(point: HookPoint, expectedParams: List<String>): Boolean =
            point.viewField == null &&
                point.className.length in 1..512 &&
                point.methodName.length in 1..256 &&
                validClassName(point.className) &&
                validMethodName(point.methodName) &&
                point.paramClassNames == expectedParams

        private fun validClassName(name: String): Boolean = name.split('.').all { segment ->
            segment.isNotEmpty() && identifierStart(segment[0]) && segment.all { identifierStart(it) || it in '0'..'9' }
        }

        private fun validMethodName(name: String): Boolean =
            name.isNotEmpty() && identifierStart(name[0]) && name.all { identifierStart(it) || it in '0'..'9' }

        private fun identifierStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '$'
    }
}

/** 安装期只做有界反射验真；语义 DEX 查询由 VersionAdapter 后台共享 Session 调度。 */
internal object SystemEnvironmentAdapter {
    const val ENV_PARAM_SET = "java.util.Set"
    val ENV_STRINGS = listOf("biliid.hook", "axposed", "is_root", "root", "maps")
    val HOOK_STRINGS = listOf("xposed", "substrate", "frida", "riru", "RIRU_API_VERSION")
    const val MAX_CANDIDATES = 32

    enum class Family(val key: String, val query: DexAssistQuery, val diagnostic: String) {
        ENVIRONMENT("environment", DexAssistQuery.SYSTEM_ENV_ROOT_RESULT, "dex.assist.system_environment_root"),
        HOOK("hook", DexAssistQuery.SYSTEM_ENV_HOOK_RESULT, "dex.assist.system_environment_hook")
    }

    fun verifiedEnvironment(method: Method): Boolean = safeStaticMap(method) &&
        method.parameterTypes.contentEquals(arrayOf(classOf<Set<*>>()))

    fun verifiedHook(method: Method): Boolean = safeStaticMap(method) &&
        method.parameterCount == 0

    fun resolveEnvironment(loader: ClassLoader, point: HookPoint?): Method? =
        resolve(loader, point, listOf(ENV_PARAM_SET), ::verifiedEnvironment)

    fun resolveHook(loader: ClassLoader, point: HookPoint?): Method? =
        resolve(loader, point, emptyList(), ::verifiedHook)

    fun resolve(loader: ClassLoader, family: Family, points: SystemEnvironmentPoints?): Method? = when (family) {
        Family.ENVIRONMENT -> resolveEnvironment(loader, points?.environment)
        Family.HOOK -> resolveHook(loader, points?.hook)
    }

    private fun resolve(
        loader: ClassLoader,
        point: HookPoint?,
        expectedParams: List<String>,
        verified: (Method) -> Boolean
    ): Method? {
        point ?: return null
        if (point.viewField != null || point.paramClassNames != expectedParams) return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, makeAccessible = true) {
            it.name == point.methodName &&
                it.parameterTypes.map(Class<*>::getName) == expectedParams &&
                verified(it)
        }.singleOrNull()
    }

    /** 仅已完成查询的无命中或超限歧义视作确认缺失；原生不可用等短暂故障仍可重试。 */
    fun conclusive(result: DexAssistResult?): Boolean = result is DexAssistResult.Candidates ||
        (result is DexAssistResult.Unavailable && result.reason in setOf(
            DexAssistResult.Reason.NO_MATCH,
            DexAssistResult.Reason.TOO_MANY_MATCHES
        ))

    fun missing(loader: ClassLoader, cached: SystemEnvironmentPoints? = null): Set<Family> =
        Family.entries.filterTo(linkedSetOf()) { family -> resolve(loader, family, cached) == null }

    fun planned(
        loader: ClassLoader,
        cached: SystemEnvironmentPoints?,
        attemptedMissing: Set<Family>,
        enabled: Boolean
    ): Set<Family> = if (!enabled) emptySet() else missing(loader, cached) - attemptedMissing

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: SystemEnvironmentPoints? = null,
        planned: Set<Family> = Family.entries.toSet()
    ): SystemEnvironmentPoints? {
        if (!enabled) return cached
        fun resolveExisting(family: Family): HookPoint? =
            resolve(loader, family, cached)?.let(::point)

        fun assisted(family: Family): HookPoint? {
            if (family !in planned) return null
            val candidates = (session?.result(family.query) as? DexAssistResult.Candidates)?.methods.orEmpty()
            if (candidates.size > MAX_CANDIDATES) return null
            val verified = candidates.mapNotNull { candidate ->
                resolve(loader, family, when (family) {
                    Family.ENVIRONMENT -> SystemEnvironmentPoints(environment = point(candidate))
                    Family.HOOK -> SystemEnvironmentPoints(hook = point(candidate))
                })
            }
            return DexAssistCandidateSelector.selectUniqueLeaf(verified)?.let(::point)
        }

        val env = resolveExisting(Family.ENVIRONMENT) ?: assisted(Family.ENVIRONMENT)
        val hook = resolveExisting(Family.HOOK) ?: assisted(Family.HOOK)
        return SystemEnvironmentPoints(env, hook).takeUnless { it.isEmpty }
    }

    fun merge(live: SystemEnvironmentPoints?, cached: SystemEnvironmentPoints?): SystemEnvironmentPoints? =
        if (live == null) cached
        else SystemEnvironmentPoints(
            environment = live.environment ?: cached?.environment,
            hook = live.hook ?: cached?.hook
        ).takeUnless { it.isEmpty }

    fun point(method: Method): HookPoint =
        HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName))

    private fun safeStaticMap(method: Method): Boolean = method.isStatic &&
        !Modifier.isAbstract(method.modifiers) &&
        !method.isBridge &&
        !method.declaringClass.isInterface &&
        method.returnType == classOf<Map<*, *>>()
}
