package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import org.json.JSONObject

/** 仅保存宿主成员描述；语义角色齐全仍不等于当前条目、准入及播放起点已确认。 */
data class StoryQualityPoints(
    val preferenceClassName: String,
    val manualMethod: HookPoint,
    val serviceFieldName: String,
    val ownerOnCreate: HookPoint,
    val ownerRelease: HookPoint,
    val ownerOnDestroy: HookPoint,
    val integerDefaultMethod: HookPoint? = null,
    val policyMethod: HookPoint? = null,
    val resourceSelectionMethod: HookPoint? = null,
    val matchPreferenceMethod: HookPoint? = null
) {
    val semanticComplete: Boolean get() =
        isStructurallyValid() && policyMethod != null && resourceSelectionMethod != null && matchPreferenceMethod != null &&
            policyMethod.className == resourceSelectionMethod.className

    fun toJson() = JSONObject().apply {
        put("preference", preferenceClassName); put("manual", manualMethod.toJson())
        put("service_field", serviceFieldName); put("create", ownerOnCreate.toJson())
        put("release", ownerRelease.toJson()); put("destroy", ownerOnDestroy.toJson())
        integerDefaultMethod?.let { put("integer_default", it.toJson()) }
        policyMethod?.let { put("policy", it.toJson()) }
        resourceSelectionMethod?.let { put("resource", it.toJson()) }
        matchPreferenceMethod?.let { put("match", it.toJson()) }
    }

    companion object {
        fun fromJson(value: JSONObject): StoryQualityPoints? = runCatching {
            fun point(key: String): HookPoint = HookPoint.fromJson(value.getJSONObject(key)).also {
                require(it.viewField == null && it.paramClassNames != null && it.paramClassNames.size <= 2 &&
                    it.className.length in 1..512 && it.methodName.length in 1..256 &&
                    it.paramClassNames.all { name -> name.length in 1..512 })
            }
            fun optional(key: String) = if (value.has(key)) point(key) else null
            StoryQualityPoints(value.getString("preference"), point("manual"), value.getString("service_field"),
                point("create"), point("release"), point("destroy"), optional("integer_default"),
                optional("policy"), optional("resource"), optional("match")).takeIf { it.isStructurallyValid() }
        }.getOrNull()
    }

    fun isStructurallyValid(): Boolean {
        fun identifier(value: String) = value.length in 1..256 && value.all { it.isLetterOrDigit() || it == '_' || it == '$' }
        val flattenedOwner = policyMethod?.className?.takeIf(StoryQualityLocator::flattenedQualityOwner)
        if (flattenedOwner != null && (integerDefaultMethod != null || resourceSelectionMethod?.className != flattenedOwner ||
                matchPreferenceMethod == null)) return false
        fun quality(point: HookPoint?) = point == null ||
            (point.className.startsWith(StoryQualityLocator.QUALITY_PACKAGE + ".") || point.className == flattenedOwner) &&
                identifier(point.methodName) && point.paramClassNames != null && point.viewField == null
        return preferenceClassName.startsWith(StoryQualityLocator.PREFERENCE_PACKAGE + ".") &&
            preferenceClassName.length <= 512 && identifier(serviceFieldName) &&
            manualMethod.className == StoryQualityLocator.SERVICE && identifier(manualMethod.methodName) &&
            manualMethod.viewField == null && manualMethod.paramClassNames == listOf(preferenceClassName, StoryQualityLocator.CONTINUATION) &&
            ownerOnCreate.className == StoryQualityLocator.OWNER && ownerOnCreate.methodName == "onCreate" &&
            ownerOnCreate.paramClassNames == listOf(StoryQualityLocator.LIFECYCLE_OWNER) && ownerOnCreate.viewField == null &&
            ownerRelease.className == StoryQualityLocator.OWNER && ownerRelease.methodName == "release" &&
            ownerRelease.paramClassNames == emptyList<String>() && ownerRelease.viewField == null &&
            ownerOnDestroy.className == StoryQualityLocator.OWNER && ownerOnDestroy.methodName == "onDestroy" &&
            ownerOnDestroy.paramClassNames == listOf(StoryQualityLocator.LIFECYCLE_OWNER) && ownerOnDestroy.viewField == null &&
            quality(integerDefaultMethod) && integerDefaultMethod?.paramClassNames?.isEmpty() != false &&
            quality(policyMethod) && policyMethod?.paramClassNames?.isEmpty() != false &&
            quality(resourceSelectionMethod) && resourceSelectionMethod?.paramClassNames?.let { it == listOf(StoryQualityLocator.MEDIA_RESOURCE) } != false &&
            quality(matchPreferenceMethod) && matchPreferenceMethod?.let {
                it.className == StoryQualityLocator.SERVICE && it.paramClassNames?.singleOrNull() in
                    setOf(StoryQualityLocator.CONTINUATION, StoryQualityLocator.CONTINUATION_IMPL)
            } != false &&
            (integerDefaultMethod == null || policyMethod == null || integerDefaultMethod.className == policyMethod.className) &&
            (policyMethod == null || resourceSelectionMethod == null || policyMethod.className == resourceSelectionMethod.className)
    }
}
