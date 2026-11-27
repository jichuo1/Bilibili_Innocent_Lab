package com.bilab.plugin.api.model

/**
 * Bounded bytecode query specification executed during background host adaptation.
 */
data class DexQuerySpec(
    val queryId: String,
    val withinPackages: List<String> = emptyList(),
    val methodName: String? = null,
    val returnType: String? = null,
    val parameterTypes: List<String>? = null,
    val requiredStrings: List<String> = emptyList(),
    val invokedMethods: List<String> = emptyList(),
    val uniqueOnly: Boolean = true
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (queryId.isBlank()) {
            errors += "queryId must not be blank"
        }
        val hasAnchor = withinPackages.isNotEmpty() ||
            !methodName.isNullOrBlank() ||
            !returnType.isNullOrBlank() ||
            !parameterTypes.isNullOrEmpty() ||
            requiredStrings.isNotEmpty() ||
            invokedMethods.isNotEmpty()
        if (!hasAnchor) {
            errors += "DexQuerySpec '$queryId' must specify at least one semantic anchor"
        }
        return errors
    }

    companion object {
        const val MAX_QUERIES_PER_PLUGIN = 8
        const val MAX_TOTAL_PLUGIN_QUERIES = 32
    }
}

/**
 * Host surface targets for declarative filter and semantic template rules.
 */
enum class FilterSurface {
    FEED,
    COMMENT,
    DYNAMIC,
    DANMAKU,
    SEARCH,
    RELATED_VIDEO
}

data class FilterRuleEntry(
    val ruleId: String,
    val surface: FilterSurface,
    val keywords: List<String> = emptyList(),
    val blockedAuthors: List<String> = emptyList(),
    val blockedAuthorIds: Set<Long> = emptySet(),
    val minWeight: Int? = null,
    val regexPattern: String? = null
)

/**
 * Declarative rule pack interpreted by host filters without executing plugin bytecode.
 */
data class DeclarativeFilterRules(
    val rulesetId: String,
    val version: Int = 1,
    val rules: List<FilterRuleEntry> = emptyList()
)

data class AdaptPatchEntry(
    val key: String,
    val ownerClassName: String,
    val memberName: String,
    val descriptor: String = "",
    val minHostVersionCode: Long = 0L,
    val maxHostVersionCode: Long = Long.MAX_VALUE
)

/**
 * Declarative member adaptation table keyed by host version bounds.
 */
data class AdaptPatchTable(
    val tableId: String,
    val schemaVersion: Int = 1,
    val entries: List<AdaptPatchEntry> = emptyList()
)

/**
 * Declarative semantic classification template rule.
 */
data class SemanticTemplateRule(
    val templateId: String,
    val surface: FilterSurface,
    val displayName: String,
    val promptTemplate: String,
    val positiveHints: List<String> = emptyList(),
    val negativeHints: List<String> = emptyList(),
    val maxInputChars: Int = 1024
)

/**
 * Declarative visual token recipe applied by the host UI runtime.
 */
data class ThemeRecipe(
    val recipeId: String,
    val displayName: String,
    val preferDark: Boolean? = null,
    val surfaceArgb: Int? = null,
    val accentArgb: Int? = null,
    val textPrimaryArgb: Int? = null,
    val textSecondaryArgb: Int? = null,
    val cornerRadiusDp: Float = 16f,
    val backdropBlurDp: Float = 0f
)

/**
 * Read-only snapshot of the current host theme metrics exposed to plugins.
 */
data class HostThemeSnapshot(
    val isDark: Boolean,
    val surfaceColor: Int,
    val accentColor: Int,
    val textPrimaryColor: Int,
    val textSecondaryColor: Int,
    val density: Float,
    val cornerRadiusDp: Float = 16f
)

/**
 * Read-only host application identity exposed to plugins.
 */
data class HostAppInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val processName: String,
    val abi: String
)
