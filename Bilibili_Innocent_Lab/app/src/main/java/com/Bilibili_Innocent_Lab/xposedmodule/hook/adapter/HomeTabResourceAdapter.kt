package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTabPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/** 定位 HomeFragmentV2 的 List<Resource> -> List 构建入口及被扁平化/泛型擦除的 Resource 类。 */
internal object HomeTabResourceAdapter {
    const val DIAGNOSTIC = "dex.assist.home_tab_resource"
    val query = DexAssistQuery.HOME_TAB_RESOURCE
    const val HOME_FRAGMENT_V2 = "tv.danmaku.bili.ui.main2.HomeFragmentV2"
    const val EQUALS_ANCHOR = "home_primary_equals_ignore_from"
    private const val MAX_CANDIDATES = 32
    private val SYSTEM_PREFIXES = listOf(
        "[", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik.", "com.google."
    )

    internal data class FieldMapping(
        val idField: String,
        val titleField: String,
        val uriField: String,
        val reporterIdField: String?
    )

    fun applicable(loader: ClassLoader): Boolean = Lookup.hasClass(loader, HOME_FRAGMENT_V2)

    fun allowedResourceClassName(name: String): Boolean = name.isNotBlank() &&
        name.length <= 512 &&
        !name.startsWith("tv.danmaku.bili.ui.main2.HomeFragmentV2") &&
        SYSTEM_PREFIXES.none { name.startsWith(it) }

    fun verifiedBuildMethod(method: Method): Boolean =
        method.declaringClass.name == HOME_FRAGMENT_V2 &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.parameterCount == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            List::class.java.isAssignableFrom(method.returnType)

    fun verifiedResourceClass(type: Class<*>): List<Field>? = runCatching {
        if (type.isInterface || type.isPrimitive || type.isArray || type.isEnum ||
            !allowedResourceClassName(type.name)
        ) {
            return null
        }
        val stringFields = Lookup.declaredFields(type, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it.type == classOf<String>()
        }
        stringFields.takeIf { it.size >= 3 }
    }.getOrNull()

    private fun semanticTokens(field: Field): Set<String> {
        val tokens = linkedSetOf(field.name)
        for (annotation in field.annotations) {
            val simple = annotation.annotationClass.java.simpleName
            if (simple == "JSONField" || simple == "SerializedName") {
                for (attr in listOf("name", "value")) {
                    val raw = runCatching {
                        annotation.annotationClass.java.getMethod(attr).invoke(annotation) as? String
                    }.getOrNull()?.trim()
                    if (!raw.isNullOrBlank()) tokens += raw
                }
            }
        }
        return tokens.mapTo(linkedSetOf()) { it.lowercase() }
    }

    internal fun resolveFieldMapping(resource: Class<*>): FieldMapping? {
        val stringFields = verifiedResourceClass(resource) ?: return null
        val tokensByField = stringFields.associateWith(::semanticTokens)
        fun uniqueField(vararg keys: String): Field? =
            stringFields.singleOrNull { field ->
                val tokens = tokensByField.getValue(field)
                keys.any { it in tokens }
            }

        val semanticId = uniqueField("id")
        val semanticTitle = uniqueField("title", "name")
        val semanticUri = uniqueField("uri", "url")
        val semanticReporter = uniqueField("reporterid", "reportid", "report_id", "tabid", "tab_id")
        if (semanticId != null && semanticTitle != null && semanticUri != null &&
            setOf(semanticId, semanticTitle, semanticUri).size == 3
        ) {
            val reporter = semanticReporter?.takeIf {
                it != semanticId && it != semanticTitle && it != semanticUri
            }
            return FieldMapping(
                idField = semanticId.name,
                titleField = semanticTitle.name,
                uriField = semanticUri.name,
                reporterIdField = reporter?.name
            )
        }

        // R8 扁平化为单字母字段（如 y 的 a/b/d/e/f/g/h）时按字典序确定性对齐 DEX 字段序，消除 JVM/ART 反射顺序差异。
        val sorted = stringFields.sortedBy(Field::getName)
        return FieldMapping(
            idField = sorted[0].name,
            titleField = sorted[1].name,
            uriField = sorted[2].name,
            reporterIdField = sorted.getOrNull(3)?.name
        )
    }

    internal fun hasResourceConstructorShape(type: Class<*>): Boolean = runCatching {
        val stringType = classOf<String>()
        Lookup.declaredConstructors(type).any { ctor ->
            val params = ctor.parameterTypes
            (params.size == 4 || params.size == 5) &&
                params[0] == stringType &&
                params[1] == stringType &&
                params[2] != stringType && !params[2].isPrimitive &&
                params[3] == stringType &&
                (params.size == 4 || params[4] == stringType)
        }
    }.getOrDefault(false)

    fun buildPoints(build: Method, resource: Class<*>): HomeTabPoints? {
        if (!verifiedBuildMethod(build)) return null
        val mapping = resolveFieldMapping(resource) ?: return null
        return HomeTabPoints(
            buildMethod = HookPoint(
                className = build.declaringClass.name,
                methodName = build.name,
                paramClassNames = build.parameterTypes.map(Class<*>::getName)
            ),
            resourceClassName = resource.name,
            idField = mapping.idField,
            titleField = mapping.titleField,
            uriField = mapping.uriField,
            reporterIdField = mapping.reporterIdField
        )
    }

    fun resolve(loader: ClassLoader, cached: HomeTabPoints?): HomeTabPoints? {
        val points = cached ?: return null
        val buildPoint = points.buildMethod
        if (buildPoint.viewField != null || buildPoint.className != HOME_FRAGMENT_V2) return null
        val fragment = Lookup.classOrNull(loader, HOME_FRAGMENT_V2) ?: return null
        val build = Lookup.declaredMethods(fragment) { method ->
            method.name == buildPoint.methodName &&
                verifiedBuildMethod(method) &&
                (buildPoint.paramClassNames == null ||
                    method.parameterTypes.map(Class<*>::getName) == buildPoint.paramClassNames)
        }.singleOrNull() ?: return null
        if (!allowedResourceClassName(points.resourceClassName)) return null
        val resource = Lookup.classOrNull(loader, points.resourceClassName) ?: return null
        val stringFieldNames = verifiedResourceClass(resource)?.mapTo(hashSetOf()) { it.name } ?: return null
        val required = listOf(points.idField, points.titleField, points.uriField)
        if (required.any { it.isBlank() || it !in stringFieldNames } || required.distinct().size != 3) {
            return null
        }
        val reporter = points.reporterIdField
        if (reporter != null && (reporter.isBlank() || reporter !in stringFieldNames || reporter in required)) {
            return null
        }
        return HomeTabPoints(
            buildMethod = HookPoint(
                className = build.declaringClass.name,
                methodName = build.name,
                paramClassNames = build.parameterTypes.map(Class<*>::getName)
            ),
            resourceClassName = resource.name,
            idField = points.idField,
            titleField = points.titleField,
            uriField = points.uriField,
            reporterIdField = reporter
        )
    }

    fun select(candidates: DexAssistResult.Candidates): HomeTabPoints? {
        if (candidates.methods.size > MAX_CANDIDATES || candidates.classes.size > MAX_CANDIDATES) return null
        val validBuilds = candidates.methods.asSequence()
            .filter(::verifiedBuildMethod)
            .distinctBy(Method::toGenericString)
            .toList()
        if (validBuilds.isEmpty()) return null

        val anchoredResources = candidates.classes
            .distinctBy(Class<*>::getName)
            .filter { verifiedResourceClass(it) != null }
        if (anchoredResources.size > 1) return null
        if (anchoredResources.size == 1) {
            val build = validBuilds.singleOrNull() ?: return null
            return buildPoints(build, anchoredResources.single())
        }

        val matches = validBuilds.mapNotNull { build ->
            val genericArg = (build.genericParameterTypes.firstOrNull() as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*>
            val pool = (candidates.relatedClasses[build].orEmpty() + listOfNotNull(genericArg))
                .distinctBy(Class<*>::getName)
                .filter { verifiedResourceClass(it) != null }
            val resource = pool.singleOrNull()
                ?: pool.filter(::hasResourceConstructorShape).singleOrNull()
                ?: return@mapNotNull null
            buildPoints(build, resource)
        }
        return matches.singleOrNull()
    }

    fun needsQuery(
        loader: ClassLoader,
        directFound: Boolean,
        cached: HomeTabPoints?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && !directFound && applicable(loader) && resolve(loader, cached) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        direct: HomeTabPoints?,
        cached: HomeTabPoints?,
        planned: Boolean
    ): HomeTabPoints? {
        if (!enabled) return direct ?: cached
        direct?.let { return it }
        resolve(loader, cached)?.let { return it }
        if (!planned) return null
        val candidates = session?.result(query) as? DexAssistResult.Candidates ?: return null
        return select(candidates)
    }
}
