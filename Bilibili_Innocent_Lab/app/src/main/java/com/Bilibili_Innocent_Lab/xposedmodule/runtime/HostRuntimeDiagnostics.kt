package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import org.json.JSONArray
import org.json.JSONObject

internal data class HostRuntimeFeatureEvidence(
    val featureId: String,
    val adaptedCount: Long,
    val observedCount: Long,
    val appliedCount: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", featureId)
        .put("adapted", adaptedCount)
        .put("observed", observedCount)
        .put("applied", appliedCount)
}

internal data class HostRuntimeDiagnosticsSnapshot(
    val capturedAtEpochMs: Long,
    val processName: String,
    val features: List<HostRuntimeFeatureEvidence>
) {
    val adaptedFeatureCount: Int
        get() = features.count { it.adaptedCount > 0L }
    val observedFeatureCount: Int
        get() = features.count { it.observedCount > 0L }
    val appliedFeatureCount: Int
        get() = features.count { it.appliedCount > 0L }
}

/** 固定白名单诊断协议；拒绝任意键、文本、成员名和宿主业务数据。 */
internal object HostRuntimeDiagnosticsCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val TARGET_PACKAGE = "tv.danmaku.bili"
    const val MAX_PAYLOAD_CHARS = 16 * 1024
    const val MAX_FEATURE_COUNT = 16
    const val MAX_COUNTER = 1L

    val allowedFeatureIds: Set<String> = linkedSetOf(
        "home_recommend_purify",
        "video_relate_filter",
        "comment_filter",
        "comment_purify",
        "player_default_quality",
        "splash_ad_purify",
        "mine_component_filter"
    )

    fun encode(snapshot: HostRuntimeDiagnosticsSnapshot): String = JSONObject()
        .put("schema", CURRENT_SCHEMA_VERSION)
        .put("captured_at", snapshot.capturedAtEpochMs.coerceAtLeast(1L))
        .put("process", TARGET_PACKAGE)
        .put("features", JSONArray().apply {
            snapshot.features
                .asSequence()
                .filter { it.featureId in allowedFeatureIds }
                .distinctBy(HostRuntimeFeatureEvidence::featureId)
                .take(MAX_FEATURE_COUNT)
                .forEach { put(it.sanitized().toJson()) }
        })
        .toString()
        .also { require(it.length <= MAX_PAYLOAD_CHARS) }

    fun decodeOrNull(raw: String): HostRuntimeDiagnosticsSnapshot? = runCatching {
        if (raw.isBlank() || raw.length > MAX_PAYLOAD_CHARS) return@runCatching null
        val root = JSONObject(raw)
        if (root.optInt("schema") != CURRENT_SCHEMA_VERSION) return@runCatching null
        if (root.optString("process") != TARGET_PACKAGE) return@runCatching null
        val capturedAt = root.optLong("captured_at", 0L)
        if (capturedAt <= 0L) return@runCatching null
        val array = root.optJSONArray("features") ?: return@runCatching null
        if (array.length() > MAX_FEATURE_COUNT) return@runCatching null
        val features = ArrayList<HostRuntimeFeatureEvidence>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.getJSONObject(index)
            val id = value.optString("id")
            if (id !in allowedFeatureIds || !ids.add(id)) return@runCatching null
            val evidence = HostRuntimeFeatureEvidence(
                featureId = id,
                adaptedCount = value.optLong("adapted", -1L),
                observedCount = value.optLong("observed", -1L),
                appliedCount = value.optLong("applied", -1L)
            )
            if (!evidence.isValid()) return@runCatching null
            features += evidence
        }
        HostRuntimeDiagnosticsSnapshot(capturedAt, TARGET_PACKAGE, features)
    }.getOrNull()

    fun increment(
        current: HostRuntimeFeatureEvidence?,
        featureId: String,
        stage: FeatureRuntimeStage,
        delta: Int
    ): HostRuntimeFeatureEvidence? {
        if (featureId !in allowedFeatureIds || delta <= 0) return current
        val base = current ?: HostRuntimeFeatureEvidence(featureId, 0L, 0L, 0L)
        return when (stage) {
            FeatureRuntimeStage.ADAPTED -> base.copy(adaptedCount = 1L)
            FeatureRuntimeStage.OBSERVED -> base.copy(observedCount = 1L)
            FeatureRuntimeStage.APPLIED -> base.copy(appliedCount = 1L)
        }
    }

    private fun HostRuntimeFeatureEvidence.isValid(): Boolean =
        featureId in allowedFeatureIds &&
            adaptedCount in 0L..1L && observedCount in 0L..MAX_COUNTER &&
            appliedCount in 0L..MAX_COUNTER

    private fun HostRuntimeFeatureEvidence.sanitized(): HostRuntimeFeatureEvidence = copy(
        adaptedCount = adaptedCount.coerceIn(0L, 1L),
        observedCount = observedCount.coerceIn(0L, MAX_COUNTER),
        appliedCount = appliedCount.coerceIn(0L, MAX_COUNTER)
    )
}
