package com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.bilab.plugin.api.model.AdaptPatchEntry
import com.bilab.plugin.api.model.AdaptPatchTable
import com.bilab.plugin.api.model.DexQuerySpec
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method

internal data class QualifiedPluginDexQuery(
    val pluginId: String,
    val spec: DexQuerySpec
)

internal data class RejectedPluginDexQuery(
    val pluginId: String,
    val queryId: String,
    val reason: String
)

internal data class MergedDexQueryPlan(
    val accepted: List<QualifiedPluginDexQuery> = emptyList(),
    val rejected: List<RejectedPluginDexQuery> = emptyList()
) {
    val specs: List<DexQuerySpec> get() = accepted.map { it.spec }
    val isEmpty: Boolean get() = accepted.isEmpty()

    companion object {
        val EMPTY = MergedDexQueryPlan()
    }
}

internal data class PluginAdaptResolution(
    val plan: MergedDexQueryPlan = MergedDexQueryPlan.EMPTY,
    val resolvedMethods: Map<String, Method?> = emptyMap(),
    val resolvedAdaptMembers: Map<String, Member?> = emptyMap()
) {
    companion object {
        val EMPTY = PluginAdaptResolution()
    }
}

/**
 * Merges and validates multi-plugin [DexQuerySpec] and [AdaptPatchTable] declarations within strict
 * per-plugin ([DexQuerySpec.MAX_QUERIES_PER_PLUGIN]) and global ([DexQuerySpec.MAX_TOTAL_PLUGIN_QUERIES])
 * ceilings before dispatching a single pass through [DexMeguvizAssistEngine].
 */
internal object PluginAdaptIntegration {

    fun mergeDexQueries(pluginQueries: Map<String, List<DexQuerySpec>>): MergedDexQueryPlan {
        if (pluginQueries.isEmpty()) return MergedDexQueryPlan.EMPTY

        val accepted = ArrayList<QualifiedPluginDexQuery>()
        val rejected = ArrayList<RejectedPluginDexQuery>()
        val seenQueryIds = HashSet<String>()

        for ((pluginId, rawList) in pluginQueries) {
            if (pluginId.isBlank()) {
                for (spec in rawList) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, "blank_plugin_id")
                }
                continue
            }
            var acceptedForPlugin = 0
            for (spec in rawList) {
                val errors = spec.validate()
                if (errors.isNotEmpty()) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, errors.first())
                    continue
                }
                if (!spec.uniqueOnly) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, "unique_only_required")
                    continue
                }
                if (acceptedForPlugin >= DexQuerySpec.MAX_QUERIES_PER_PLUGIN) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, "per_plugin_limit_exceeded")
                    continue
                }
                if (accepted.size >= DexQuerySpec.MAX_TOTAL_PLUGIN_QUERIES) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, "global_limit_exceeded")
                    continue
                }
                if (!seenQueryIds.add(spec.queryId)) {
                    rejected += RejectedPluginDexQuery(pluginId, spec.queryId, "duplicate_query_id")
                    continue
                }
                accepted += QualifiedPluginDexQuery(pluginId, spec)
                acceptedForPlugin++
            }
        }

        if (accepted.isEmpty() && rejected.isEmpty()) return MergedDexQueryPlan.EMPTY
        return MergedDexQueryPlan(
            accepted = accepted.toList(),
            rejected = rejected.toList()
        )
    }

    fun activeAdaptPatchesForHost(
        tables: Iterable<AdaptPatchTable>,
        hostVersionCode: Long
    ): List<AdaptPatchEntry> {
        val result = ArrayList<AdaptPatchEntry>()
        for (table in tables) {
            for (entry in table.entries) {
                if (isEntryActiveForHost(entry, hostVersionCode)) {
                    result += entry
                }
            }
        }
        return result
    }

    fun isEntryActiveForHost(entry: AdaptPatchEntry, hostVersionCode: Long): Boolean {
        if (entry.key.isBlank() || entry.ownerClassName.isBlank() || entry.memberName.isBlank()) {
            return false
        }
        return hostVersionCode in entry.minHostVersionCode..entry.maxHostVersionCode
    }

    fun resolveAdaptPatchEntries(
        entries: List<AdaptPatchEntry>,
        hostVersionCode: Long,
        hostClassLoader: ClassLoader?
    ): Map<String, Member?> {
        if (entries.isEmpty() || hostClassLoader == null) return emptyMap()
        val resolved = LinkedHashMap<String, Member?>(entries.size)
        for (entry in entries) {
            if (!isEntryActiveForHost(entry, hostVersionCode)) continue
            resolved[entry.key] = resolveSinglePatchEntry(entry, hostClassLoader)
        }
        return resolved
    }

    fun resolveAll(
        apkPaths: List<File>,
        hostVersionCode: Long,
        hostClassLoader: ClassLoader?,
        pluginQueries: Map<String, List<DexQuerySpec>>,
        adaptPatches: List<AdaptPatchEntry> = emptyList()
    ): PluginAdaptResolution {
        val plan = mergeDexQueries(pluginQueries)
        val hasPatches = adaptPatches.any { isEntryActiveForHost(it, hostVersionCode) }
        if (plan.isEmpty && !hasPatches) return PluginAdaptResolution.EMPTY

        val resolvedMethods = if (plan.isEmpty || apkPaths.isEmpty() || hostClassLoader == null) {
            emptyMap()
        } else {
            DexMeguvizAssistEngine.resolvePluginDexQueries(apkPaths, plan.specs, hostClassLoader)
        }
        val resolvedPatches = resolveAdaptPatchEntries(adaptPatches, hostVersionCode, hostClassLoader)
        return PluginAdaptResolution(
            plan = plan,
            resolvedMethods = resolvedMethods,
            resolvedAdaptMembers = resolvedPatches
        )
    }

    private fun resolveSinglePatchEntry(entry: AdaptPatchEntry, loader: ClassLoader): Member? {
        val owner = KavaMemberLookup.classOrNull(loader, entry.ownerClassName) ?: return null
        val descriptor = entry.descriptor.trim()
        if (entry.memberName == "<init>") {
            val ctors = runCatching { owner.declaredConstructors }.getOrNull().orEmpty()
            val matches = ctors.filter { ctor ->
                descriptor.isEmpty() || constructorDescriptor(ctor) == descriptor
            }
            return matches.singleOrNull()?.apply { isAccessible = true }
        }
        if (descriptor.isNotEmpty() && !descriptor.startsWith("(")) {
            val fieldMatches = KavaMemberLookup.declaredFields(owner, makeAccessible = true) { field ->
                field.name == entry.memberName && typeDescriptor(field.type) == descriptor
            }
            if (fieldMatches.size == 1) return fieldMatches[0]
        }
        val methodMatches = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
            method.name == entry.memberName &&
                (descriptor.isEmpty() || methodDescriptor(method) == descriptor)
        }
        if (methodMatches.size == 1) return methodMatches[0]
        if (descriptor.isEmpty() && methodMatches.isEmpty()) {
            val fieldMatches = KavaMemberLookup.declaredFields(owner, makeAccessible = true) { field ->
                field.name == entry.memberName
            }
            if (fieldMatches.size == 1) return fieldMatches[0]
        }
        return null
    }

    private fun methodDescriptor(method: Method): String = buildString {
        append('(')
        for (param in method.parameterTypes) {
            append(typeDescriptor(param))
        }
        append(')')
        append(typeDescriptor(method.returnType))
    }

    private fun constructorDescriptor(ctor: java.lang.reflect.Constructor<*>): String = buildString {
        append('(')
        for (param in ctor.parameterTypes) {
            append(typeDescriptor(param))
        }
        append(")V")
    }

    private fun typeDescriptor(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Short.TYPE -> "S"
        type == Character.TYPE -> "C"
        type == Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> type.name.replace('.', '/')
        else -> "L${type.name.replace('.', '/')};"
    }
}
