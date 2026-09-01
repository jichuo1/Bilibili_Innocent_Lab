package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 宿主运行时诊断的分层证据；只记录阶段是否发生，不记录卡片、评论或用户内容。 */
internal enum class FeatureRuntimeStage {
    ADAPTED,
    OBSERVED,
    APPLIED
}

internal fun HookEnvironment.reportRuntimeEvidence(
    featureId: String,
    stage: FeatureRuntimeStage,
    delta: Int = 1
) {
    if (delta <= 0) return
    runtimeEvidence?.invoke(featureId, stage, delta)
}
