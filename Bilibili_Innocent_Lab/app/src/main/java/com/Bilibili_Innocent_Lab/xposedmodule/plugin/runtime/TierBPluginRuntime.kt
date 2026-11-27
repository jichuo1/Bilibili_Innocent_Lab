package com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostResourceRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.BilpPackageVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.VerifiedBilpBundle
import com.bilab.plugin.api.model.AdaptPatchEntry
import com.bilab.plugin.api.model.DexQuerySpec
import com.bilab.plugin.api.model.FilterRuleEntry
import com.bilab.plugin.api.model.FilterSurface
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.model.SemanticTemplateRule
import com.bilab.plugin.api.model.ThemeRecipe
import com.bilab.plugin.api.spi.CommentView
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import com.bilab.plugin.api.spi.DanmakuBatchView
import com.bilab.plugin.api.spi.DynamicItemView
import com.bilab.plugin.api.spi.FeedCardView
import com.bilab.plugin.api.spi.FilterDecision
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

internal data class LoadedTierBPlugin(
    val pluginId: String,
    val manifest: PluginManifest,
    val packageSha256: String,
    val adaptPatches: List<AdaptPatchEntry>,
    val dexQueries: List<DexQuerySpec>,
    val semanticTemplates: List<SemanticTemplateRule>,
    val themeRecipe: ThemeRecipe?,
    val filterRuleCount: Int
)

/**
 * Runtime interpreter and lifecycle registry for Tier-B [PluginTier.DECLARATIVE_BUNDLE] plugins.
 *
 * Strictly rejects any [PluginTier.SIGNED_CODE], DEX code, native binaries, entryClass, or L3
 * permissions, compiling declarative JSON payloads into immutable host-side matchers only.
 */
internal class TierBPluginRuntime(
    val contentFilterPipeline: ContentFilterPipeline = ContentFilterPipeline()
) {
    private class CompiledRule(entry: FilterRuleEntry) {
        val keywordsLower: Array<String> = entry.keywords.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
            .toTypedArray()

        val blockedAuthorsLower: Set<String> = entry.blockedAuthors.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        val blockedAuthorIds: Set<Long> = if (entry.blockedAuthorIds.isEmpty()) {
            emptySet()
        } else {
            HashSet(entry.blockedAuthorIds)
        }

        val blockedAuthorIdStrings: Set<String> = if (entry.blockedAuthorIds.isEmpty()) {
            emptySet()
        } else {
            entry.blockedAuthorIds.mapTo(HashSet(entry.blockedAuthorIds.size)) { it.toString() }
        }

        val minWeight: Int? = entry.minWeight

        val regex: Regex? = entry.regexPattern
            ?.takeIf { it.isNotBlank() && it.length <= MAX_REGEX_LENGTH }
            ?.let { pattern -> runCatching { Regex(pattern) }.getOrNull() }

        val needsTextMatch: Boolean
            get() = keywordsLower.isNotEmpty() || regex != null

        val needsAuthorMatch: Boolean
            get() = blockedAuthorsLower.isNotEmpty() || blockedAuthorIds.isNotEmpty()

        fun matchesFeed(card: FeedCardView): Boolean {
            if (blockedAuthorIds.isNotEmpty() && card.authorId in blockedAuthorIds) return true
            if (blockedAuthorsLower.isNotEmpty() &&
                card.authorName.trim().lowercase(Locale.ROOT) in blockedAuthorsLower
            ) {
                return true
            }
            if (!needsTextMatch) return false
            return matchesText(card.title) ||
                matchesText(card.reasonText) ||
                matchesText(card.authorName)
        }

        fun matchesComment(comment: CommentView): Boolean {
            if (blockedAuthorIds.isNotEmpty() && comment.authorId in blockedAuthorIds) return true
            if (blockedAuthorsLower.isNotEmpty() &&
                comment.authorName.trim().lowercase(Locale.ROOT) in blockedAuthorsLower
            ) {
                return true
            }
            if (!needsTextMatch) return false
            return matchesText(comment.message) || matchesText(comment.authorName)
        }

        fun matchesDynamic(item: DynamicItemView): Boolean {
            if (blockedAuthorIds.isNotEmpty() && item.authorId in blockedAuthorIds) return true
            if (blockedAuthorsLower.isNotEmpty() &&
                item.authorName.trim().lowercase(Locale.ROOT) in blockedAuthorsLower
            ) {
                return true
            }
            if (!needsTextMatch) return false
            return matchesText(item.text) || matchesText(item.authorName)
        }

        fun matchesDanmakuItem(
            content: String?,
            contentLower: String?,
            senderHashLower: String?,
            hasWeight: Boolean,
            weight: Int
        ): Boolean {
            val threshold = minWeight
            if (threshold != null && hasWeight && weight < threshold) {
                return true
            }
            if (senderHashLower != null && senderHashLower.isNotEmpty()) {
                if (blockedAuthorsLower.isNotEmpty() && senderHashLower in blockedAuthorsLower) return true
                if (blockedAuthorIdStrings.isNotEmpty() && senderHashLower in blockedAuthorIdStrings) return true
            }
            if (content != null) {
                if (keywordsLower.isNotEmpty() && contentLower != null) {
                    for (kw in keywordsLower) {
                        if (contentLower.contains(kw)) return true
                    }
                }
                val compiledRegex = regex
                if (compiledRegex != null) {
                    val bounded = if (content.length > MAX_REGEX_INPUT_CHARS) {
                        content.substring(0, MAX_REGEX_INPUT_CHARS)
                    } else {
                        content
                    }
                    if (compiledRegex.containsMatchIn(bounded)) return true
                }
            }
            return false
        }

        private fun matchesText(raw: String): Boolean {
            if (raw.isEmpty()) return false
            if (keywordsLower.isNotEmpty()) {
                val lower = raw.lowercase(Locale.ROOT)
                for (kw in keywordsLower) {
                    if (lower.contains(kw)) return true
                }
            }
            val compiledRegex = regex
            if (compiledRegex != null) {
                val bounded = if (raw.length > MAX_REGEX_INPUT_CHARS) {
                    raw.substring(0, MAX_REGEX_INPUT_CHARS)
                } else {
                    raw
                }
                if (compiledRegex.containsMatchIn(bounded)) return true
            }
            return false
        }
    }

    private class ActiveRecord(
        val loaded: LoadedTierBPlugin,
        val handles: List<AutoCloseable>
    ) {
        fun closeAll() {
            for (handle in handles.asReversed()) {
                runCatching { handle.close() }
            }
        }
    }

    private val lock = Any()
    private val activeRecords = LinkedHashMap<String, ActiveRecord>()

    fun activate(
        bundle: VerifiedBilpBundle,
        hostVersionCode: Long = -1L
    ): LoadedTierBPlugin? {
        val manifest = bundle.manifest
        if (manifest.tier != PluginTier.DECLARATIVE_BUNDLE) return null
        if (bundle.hasDexCode || bundle.verifiedNativeLibs.isNotEmpty() || manifest.entryClass != null) {
            return null
        }
        if (manifest.permissions.any { it.includes(PluginPermission.L3_NATIVE_OR_PRIVILEGED) }) {
            return null
        }
        if (manifest.validate().isNotEmpty()) return null
        if (hostVersionCode >= 0L) {
            if (!manifest.isCompatibleWith(hostVersionCode, PluginApiVersion.CURRENT_API_LEVEL)) {
                return null
            }
        } else if (PluginApiVersion.CURRENT_API_LEVEL !in manifest.minApiLevel..manifest.maxApiLevel) {
            return null
        }

        val pluginId = manifest.id
        val rawRules = bundle.filterRules?.rules.orEmpty()
        val feedRules = ArrayList<CompiledRule>()
        val commentRules = ArrayList<CompiledRule>()
        val dynamicRules = ArrayList<CompiledRule>()
        val danmakuRules = ArrayList<CompiledRule>()

        for (rule in rawRules) {
            when (rule.surface) {
                FilterSurface.FEED -> feedRules += CompiledRule(rule)
                FilterSurface.COMMENT -> commentRules += CompiledRule(rule)
                FilterSurface.DYNAMIC -> dynamicRules += CompiledRule(rule)
                FilterSurface.DANMAKU -> danmakuRules += CompiledRule(rule)
                FilterSurface.SEARCH,
                FilterSurface.RELATED_VIDEO -> Unit
            }
        }

        val handles = ArrayList<AutoCloseable>(3)
        try {
            if (feedRules.isNotEmpty() || commentRules.isNotEmpty() || dynamicRules.isNotEmpty()) {
                val extension = DeclarativeContentExtension(feedRules, commentRules, dynamicRules)
                handles += contentFilterPipeline.registerContentFilter(pluginId, extension)
            }
            if (danmakuRules.isNotEmpty()) {
                val batchFilter = DeclarativeDanmakuFilter(danmakuRules)
                handles += contentFilterPipeline.registerDanmakuBatchFilter(pluginId, batchFilter)
            }
            handles += HostResourceRegistry.register("tier_b:$pluginId") { _ -> }

            val adaptPatches = Collections.unmodifiableList(
                bundle.adaptPatches?.entries.orEmpty().toList()
            )
            val dexQueries = Collections.unmodifiableList(bundle.dexQueries.toList())
            val semanticTemplates = Collections.unmodifiableList(bundle.semanticTemplates.toList())
            val loaded = LoadedTierBPlugin(
                pluginId = pluginId,
                manifest = manifest,
                packageSha256 = bundle.packageSha256,
                adaptPatches = adaptPatches,
                dexQueries = dexQueries,
                semanticTemplates = semanticTemplates,
                themeRecipe = bundle.themeRecipe,
                filterRuleCount = rawRules.size
            )
            val newRecord = ActiveRecord(loaded, handles.toList())
            val previous = synchronized(lock) {
                activeRecords.put(pluginId, newRecord)
            }
            previous?.closeAll()
            return loaded
        } catch (throwable: Throwable) {
            for (handle in handles.asReversed()) {
                runCatching { handle.close() }
            }
            throw throwable
        }
    }

    fun unload(pluginId: String): Boolean {
        val removed = synchronized(lock) { activeRecords.remove(pluginId) } ?: return false
        removed.closeAll()
        return true
    }

    fun unloadAll() {
        val snapshot = synchronized(lock) {
            activeRecords.values.toList().also { activeRecords.clear() }
        }
        for (record in snapshot) {
            record.closeAll()
        }
    }

    fun activePluginIds(): Set<String> = synchronized(lock) {
        activeRecords.keys.toSet()
    }

    fun activePlugins(): List<LoadedTierBPlugin> = synchronized(lock) {
        activeRecords.values.map { it.loaded }
    }

    fun activeAdaptPatches(hostVersionCode: Long): List<AdaptPatchEntry> = synchronized(lock) {
        val out = ArrayList<AdaptPatchEntry>()
        for (record in activeRecords.values) {
            for (entry in record.loaded.adaptPatches) {
                if (PluginAdaptIntegration.isEntryActiveForHost(entry, hostVersionCode)) {
                    out += entry
                }
            }
        }
        out
    }

    fun activeDexQueries(): List<DexQuerySpec> = synchronized(lock) {
        val out = ArrayList<DexQuerySpec>()
        for (record in activeRecords.values) {
            out += record.loaded.dexQueries
        }
        out
    }

    fun activeDexQueriesByPlugin(): Map<String, List<DexQuerySpec>> = synchronized(lock) {
        val out = LinkedHashMap<String, List<DexQuerySpec>>(activeRecords.size)
        for ((pluginId, record) in activeRecords) {
            if (record.loaded.dexQueries.isNotEmpty()) {
                out[pluginId] = record.loaded.dexQueries
            }
        }
        out
    }

    fun activeSemanticTemplates(): List<SemanticTemplateRule> = synchronized(lock) {
        val out = ArrayList<SemanticTemplateRule>()
        for (record in activeRecords.values) {
            out += record.loaded.semanticTemplates
        }
        out
    }

    fun activeThemeRecipe(): ThemeRecipe? = synchronized(lock) {
        var latest: ThemeRecipe? = null
        for (record in activeRecords.values) {
            val recipe = record.loaded.themeRecipe
            if (recipe != null) latest = recipe
        }
        latest
    }

    private class DeclarativeContentExtension(
        private val feedRules: List<CompiledRule>,
        private val commentRules: List<CompiledRule>,
        private val dynamicRules: List<CompiledRule>
    ) : ContentFilterExtension {
        override fun filterFeedCard(card: FeedCardView): FilterDecision {
            for (rule in feedRules) {
                if (rule.matchesFeed(card)) return FilterDecision.DROP
            }
            return FilterDecision.KEEP
        }

        override fun filterComment(comment: CommentView): FilterDecision {
            for (rule in commentRules) {
                if (rule.matchesComment(comment)) return FilterDecision.DROP
            }
            return FilterDecision.KEEP
        }

        override fun filterDynamicItem(item: DynamicItemView): FilterDecision {
            for (rule in dynamicRules) {
                if (rule.matchesDynamic(item)) return FilterDecision.DROP
            }
            return FilterDecision.KEEP
        }
    }

    private class DeclarativeDanmakuFilter(
        private val rules: List<CompiledRule>
    ) : DanmakuBatchFilter {
        private val needsText = rules.any { it.needsTextMatch }
        private val needsKeywords = rules.any { it.keywordsLower.isNotEmpty() }
        private val needsAuthor = rules.any { it.needsAuthorMatch }

        override fun filterBatch(
            view: DanmakuBatchView,
            actionsOut: ByteArray,
            colorsOut: IntArray
        ): Boolean {
            val count = view.count
            if (count <= 0 || actionsOut.size < count) return false
            var anyDropped = false
            for (i in 0 until count) {
                val hasWeight = view.hasWeightAt(i)
                val weight = if (hasWeight) view.weightAt(i) else 0
                val content = if (needsText) view.contentAt(i) else null
                val contentLower = if (needsKeywords && content != null) {
                    content.lowercase(Locale.ROOT)
                } else {
                    null
                }
                val senderHashLower = if (needsAuthor) {
                    view.senderHashAt(i).trim().lowercase(Locale.ROOT)
                } else {
                    null
                }
                var dropped = false
                for (rule in rules) {
                    if (rule.matchesDanmakuItem(content, contentLower, senderHashLower, hasWeight, weight)) {
                        dropped = true
                        break
                    }
                }
                if (dropped) {
                    actionsOut[i] = DanmakuBatchFilter.ACTION_DROP
                    anyDropped = true
                }
            }
            return anyDropped
        }
    }

    private companion object {
        private const val MAX_REGEX_LENGTH = BilpPackageVerifier.MAX_REGEX_PATTERN_LENGTH
        private const val MAX_REGEX_INPUT_CHARS = 4096
    }
}
