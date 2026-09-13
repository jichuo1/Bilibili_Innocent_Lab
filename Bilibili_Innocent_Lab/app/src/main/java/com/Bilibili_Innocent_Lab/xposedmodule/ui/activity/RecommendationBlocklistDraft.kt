package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExactRuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TidBlocklistCodec

internal enum class RecommendationBlockKind { TAG, AUTHOR }

internal data class RecommendationBlockRule(val kind: RecommendationBlockKind, val value: String)

internal data class RecommendationBlockRow(
    val rule: RecommendationBlockRule,
    val label: String,
    val pending: Boolean
)

/** 保存前只改草稿。处理标记与名单在同一笔提交中写入，旧观测不会重新引入已撤销项。 */
internal class RecommendationBlocklistDraft(
    private val savedTags: String,
    private val savedAuthors: String,
    snapshots: List<MineComponentSnapshot>,
    reviewed: String
) {
    private val initialTags = TidBlocklistCodec.normalize(savedTags)
    private val initialAuthors = ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(savedAuthors))
    private val initialReviewed = reviewed
    private val reviewedTokens = reviewed.lineSequence().filter(String::isNotBlank).toSet()
    private val observedTokens = linkedSetOf<String>()
    private val selected = linkedSetOf<RecommendationBlockRule>()
    val rows: List<RecommendationBlockRow>

    init {
        val entries = linkedMapOf<RecommendationBlockRule, RecommendationBlockRow>()
        fun addSaved(kind: RecommendationBlockKind, values: Set<String>) {
            values.forEach { value ->
                val rule = RecommendationBlockRule(kind, value)
                entries[rule] = RecommendationBlockRow(rule, value, pending = false)
            }
        }
        addSaved(RecommendationBlockKind.TAG, ExactRuleSetCodec.parse(initialTags))
        addSaved(RecommendationBlockKind.AUTHOR, ExactRuleSetCodec.parse(initialAuthors))
        snapshots.forEach { snapshot ->
            val kind = when (snapshot.surface) {
                MineComponentSnapshotCodec.SURFACE_SECTION_PICKS -> RecommendationBlockKind.TAG
                MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS -> RecommendationBlockKind.AUTHOR
                else -> return@forEach
            }
            snapshot.entries.forEach entryLoop@ { entry ->
                val value = when (kind) {
                    RecommendationBlockKind.TAG -> entry.id?.toLongOrNull()?.takeIf { it > 0 }?.toString()
                    RecommendationBlockKind.AUTHOR -> ExactRuleSetCodec.parse(entry.id).singleOrNull()
                } ?: return@entryLoop
                // 老版本快照没有事件身份：只确认这条旧记录，新版本再次点选会有新 token。
                val token = "${snapshot.surface}:${entry.key}:${entry.selectionToken ?: "legacy"}"
                observedTokens += token
                val rule = RecommendationBlockRule(kind, value)
                val existing = entries[rule]
                if (existing == null && token in reviewedTokens) return@entryLoop
                val name = entry.title?.takeIf(String::isNotBlank) ?: value
                val label = if (kind == RecommendationBlockKind.TAG && name != value) "$name ($value)" else name
                entries[rule] = RecommendationBlockRow(rule, label, pending = existing?.pending ?: true)
            }
        }
        rows = entries.values.toList()
        selected.addAll(entries.keys)
    }

    fun isSelected(rule: RecommendationBlockRule): Boolean = rule in selected

    fun setSelected(rule: RecommendationBlockRule, checked: Boolean) {
        require(rows.any { it.rule == rule })
        if (checked) selected += rule else selected -= rule
    }

    fun selectedValue(kind: RecommendationBlockKind): String = selected
        .filter { it.kind == kind }.joinToString(",") { it.value }

    fun acknowledgedValue(): String = ((reviewedTokens - observedTokens) + observedTokens)
        .toList().takeLast(512).joinToString("\n")

    /** 发现同一名单已被其他入口修改时保留现值，让用户重新打开，避免覆盖新配置。 */
    fun save(prefs: SharedPreferences): Boolean = runCatching {
        if (TidBlocklistCodec.normalize(prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "")) != initialTags ||
            ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, ""))) != initialAuthors ||
            prefs.getString(REVIEWED_EVENTS_KEY, "").orEmpty() != initialReviewed
        ) return@runCatching false
        val committed = runCatching { prefs.edit()
            .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, selectedValue(RecommendationBlockKind.TAG))
            .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, selectedValue(RecommendationBlockKind.AUTHOR))
            .putString(REVIEWED_EVENTS_KEY, acknowledgedValue())
            .commit() }.getOrDefault(false)
        if (!committed) {
            // SharedPreferences 的失败提交也可能已更新内存，恢复本次打开时的原值。
            runCatching { prefs.edit()
                .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, savedTags)
                .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, savedAuthors)
                .putString(REVIEWED_EVENTS_KEY, initialReviewed)
                .commit() }
        }
        committed
    }.getOrDefault(false)

    companion object {
        // 本地观测处理状态，不是 Hook 设置，不进入备份目录或远程配置白名单。
        const val REVIEWED_EVENTS_KEY = "recommendation_feedback_reviewed_events"
    }
}
