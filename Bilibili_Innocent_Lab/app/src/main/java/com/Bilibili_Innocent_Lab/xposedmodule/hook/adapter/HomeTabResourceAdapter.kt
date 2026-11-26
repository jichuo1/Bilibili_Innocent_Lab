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
        extractJsonAnnotationTokens(field.annotations, tokens)
        return tokens.mapTo(linkedSetOf()) { it.lowercase() }
    }

    private fun extractJsonAnnotationTokens(annotations: Array<Annotation>, out: MutableSet<String>) {
        for (annotation in annotations) {
            val simple = annotation.annotationClass.java.simpleName
            if (simple == "JSONField" || simple == "SerializedName") {
                for (attr in listOf("name", "value")) {
                    val raw = runCatching {
                        annotation.annotationClass.java.getMethod(attr).invoke(annotation) as? String
                    }.getOrNull()?.trim()
                    if (!raw.isNullOrBlank()) out += raw
                }
                for (attr in listOf("alternateNames", "alternate")) {
                    val arr = runCatching {
                        annotation.annotationClass.java.getMethod(attr).invoke(annotation) as? Array<*>
                    }.getOrNull()
                    arr?.forEach { item ->
                        val s = (item as? String)?.trim()
                        if (!s.isNullOrBlank()) out += s
                    }
                }
            }
        }
    }

    private fun splitWordsLower(raw: String): List<String> =
        raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .split('_', '-', '.')
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

    private fun roleFromTokenWords(tokens: Set<String>): String? {
        for (token in tokens) {
            val words = splitWordsLower(token)
            val joined = words.joinToString("")
            when {
                joined in setOf("reporterid", "reportid", "tabid") ||
                    ("report" in words && "id" in words) ||
                    ("reporter" in words && "id" in words) -> return "reporter"
                joined == "id" || (words.lastOrNull() == "id" && "tab" in words) -> return "id"
                joined in setOf("title", "name") || "title" in words || words.lastOrNull() == "name" -> return "title"
                joined in setOf("uri", "url") || "uri" in words || "url" in words -> return "uri"
            }
        }
        return null
    }

    private fun isSafeProbeClass(type: Class<*>, stringFields: List<Field>): Boolean {
        if (Modifier.isAbstract(type.modifiers) || stringFields.size > 16) return false
        val unsafePrefixes = listOf(
            "android.content.", "android.view.", "android.app.", "android.os.",
            "java.io.", "java.net.", "kotlinx.coroutines."
        )
        return Lookup.declaredFields(type) { !Modifier.isStatic(it.modifiers) }.all { f ->
            val tName = f.type.name
            unsafePrefixes.none { tName.startsWith(it) }
        }
    }

    private fun inferR8RolesByUsageAndConstants(
        resource: Class<*>,
        stringFields: List<Field>
    ): Map<String, Field> {
        val inferred = linkedMapOf<String, MutableSet<Field>>()
        fun record(role: String, field: Field) {
            inferred.getOrPut(role) { linkedSetOf() } += field
        }

        // 1. 语义分词补全（如 mTitle / tabUri / jumpUrl / reportId / JSON 注解备选名）
        for (field in stringFields) {
            val tokens = semanticTokens(field)
            roleFromTokenWords(tokens)?.let { record(it, field) }
        }

        // 2. 轻量无副作用实例探测：若存在安全无参构造，则通过默认常量特征（如 URI scheme）、
        //    无参 String getter 以及 toString() 字段标签反推被 R8 重命名的单字母字段角色。
        if (isSafeProbeClass(resource, stringFields)) {
            runCatching {
                val noArgCtor = Lookup.declaredConstructors(resource, makeAccessible = true)
                    .firstOrNull { it.parameterCount == 0 }
                if (noArgCtor != null) {
                    val instance = noArgCtor.newInstance()
                    for (field in stringFields) {
                        val initial = field.get(instance) as? String ?: continue
                        val lower = initial.lowercase()
                        if (lower.startsWith("http://") || lower.startsWith("https://") ||
                            lower.startsWith("bilibili://") || (initial.startsWith("/") && initial.length > 1)
                        ) {
                            record("uri", field)
                        }
                    }

                    val markerToField = linkedMapOf<String, Field>()
                    stringFields.forEachIndexed { idx, field ->
                        val marker = "__bil_probe_${idx}__"
                        if (runCatching { field.set(instance, marker) }.isSuccess) {
                            markerToField[marker] = field
                        }
                    }

                    if (markerToField.isNotEmpty()) {
                        val stringGetters = Lookup.declaredMethods(resource, makeAccessible = true) { m ->
                            !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 &&
                                m.returnType == classOf<String>() && m.name != "toString"
                        }
                        for (getter in stringGetters) {
                            val getterTokens = linkedSetOf(getter.name.removePrefix("get").lowercase())
                            extractJsonAnnotationTokens(getter.annotations, getterTokens)
                            val role = roleFromTokenWords(getterTokens) ?: continue
                            val value = runCatching { getter.invoke(instance) as? String }.getOrNull() ?: continue
                            markerToField[value]?.let { record(role, it) }
                        }

                        val toStringDecl = runCatching {
                            resource.getMethod("toString").takeIf { it.declaringClass != Any::class.java }
                        }.getOrNull()
                        if (toStringDecl != null) {
                            val rendered = runCatching { instance.toString() }.getOrNull().orEmpty()
                            val pairPattern = Regex("([A-Za-z_][A-Za-z0-9_]*)[=:]'?(__bil_probe_[0-9]+__)'?")
                            for (match in pairPattern.findAll(rendered)) {
                                val label = match.groupValues[1].lowercase()
                                val marker = match.groupValues[2]
                                val field = markerToField[marker] ?: continue
                                roleFromTokenWords(setOf(label))?.let { record(it, field) }
                            }
                        }
                    }
                }
            }
        }

        // 仅采纳一对一确定且彼此不冲突的推断结果；歧义角色保持未定并由上层确定性回退。
        val uniqueByRole = inferred.mapNotNull { (role, fields) ->
            fields.singleOrNull()?.let { role to it }
        }.toMap()
        val fieldUseCounts = uniqueByRole.values.groupingBy { it }.eachCount()
        return uniqueByRole.filterValues { fieldUseCounts[it] == 1 }
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

        // 若精确字段名被 R8 剥离（如单字母 a/b/d/e），综合语义注解、常量与方法使用线索区分角色；
        // 对仍无法唯一区分的角色，在剩余字段中按字典序确定性补齐以兼容无额外线索的纯混淆 DTO。
        val used = linkedSetOf<Field>()
        val resolvedId = semanticId?.also { used += it }
        val resolvedTitle = semanticTitle?.takeIf { used.add(it) }
        val resolvedUri = semanticUri?.takeIf { used.add(it) }
        val resolvedReporter = semanticReporter?.takeIf { used.add(it) }

        val inferred = inferR8RolesByUsageAndConstants(resource, stringFields)
        val finalId = resolvedId ?: inferred["id"]?.takeIf { used.add(it) }
        val finalTitle = resolvedTitle ?: inferred["title"]?.takeIf { used.add(it) }
        val finalUri = resolvedUri ?: inferred["uri"]?.takeIf { used.add(it) }
        val finalReporter = resolvedReporter ?: inferred["reporter"]?.takeIf { used.add(it) }

        val remaining = stringFields.filter { it !in used }.sortedBy(Field::getName)
        var nextRemaining = 0
        fun nextFallback(): Field? = remaining.getOrNull(nextRemaining++)

        val id = finalId ?: nextFallback() ?: return null
        val title = finalTitle ?: nextFallback() ?: return null
        val uri = finalUri ?: nextFallback() ?: return null
        val reporter = finalReporter ?: nextFallback()
        return FieldMapping(
            idField = id.name,
            titleField = title.name,
            uriField = uri.name,
            reporterIdField = reporter?.name
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
