package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticCustomRule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticGuidance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticJudge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticRoute
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSensitivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSettings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object PrivacyAndCompatCatalogSpecs {

    val communicationAndAccessSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "communication.compatibility.enabled",
            CommunicationCompatibilityStore.KEY,
            R.string.communication_compatibility_mode,
            default = CommunicationCompatibilityStore.DEFAULT,
            restorePolicy = RestorePolicy.MANUAL,
            introducedCatalogVersion = 22
        ),
        boolSpec(
            SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED,
            FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED,
            R.string.bili_access_key_authorize,
            restorePolicy = RestorePolicy.MANUAL,
            introducedCatalogVersion = 29
        )
    )

    val aiInsightAndSemanticLeadSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "compat.ai_insight.enabled",
            FeaturePreferences.AI_INSIGHT_ENABLED,
            R.string.ai_insight_title,
            restorePolicy = RestorePolicy.MANUAL,
            introducedCatalogVersion = 71
        ),
        boolSpec(
            "compat.ai_insight.auto_chapters",
            FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS,
            R.string.ai_insight_auto_chapters,
            introducedCatalogVersion = 71
        ),
        // JEV 语义判定的非敏感配置；API Key 是 hook_config 运行时键，不进目录、不进备份。
        textSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT,
            FeaturePreferences.SEMANTIC_JEV_ENDPOINT,
            R.string.semantic_jev_endpoint,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 31
        ),
        textSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY,
            FeaturePreferences.SEMANTIC_JEV_SENSITIVITY,
            R.string.semantic_jev_sensitivity,
            default = SemanticSensitivity.DEFAULT.id,
            allowed = SemanticSensitivity.IDS,
            introducedCatalogVersion = 31
        ),
        boolSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN,
            FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN,
            R.string.semantic_jev_wait_first_screen,
            introducedCatalogVersion = 31
        ),
        intSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_CACHE_DAYS,
            FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS,
            R.string.semantic_jev_cache_days,
            default = SemanticSettings.DEFAULT_CACHE_DAYS,
            range = SemanticSettings.MIN_CACHE_DAYS..SemanticSettings.MAX_CACHE_DAYS,
            introducedCatalogVersion = 33
        ),
        textSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_PROVIDER,
            FeaturePreferences.SEMANTIC_JEV_PROVIDER,
            R.string.semantic_jev_provider,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 34
        ),
        textSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_MODEL,
            FeaturePreferences.SEMANTIC_JEV_MODEL,
            R.string.semantic_jev_model,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 34
        ),
        // 0 = 自动（各面默认值）；非 0 时宿主侧再夹到 500–30000。
        intSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_TIMEOUT_MS,
            FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS,
            R.string.semantic_jev_timeout,
            default = 0,
            range = 0..SemanticSettings.MAX_TIMEOUT_MS,
            introducedCatalogVersion = 35
        ),
        // v36：多来源（2–4 号，Key 是运行时键不进备份）、判定口径、各面判定来源与自定义类型。
        textSpec(
            SettingsCatalog.ID_SEMANTIC_JEV_GUIDANCE,
            FeaturePreferences.SEMANTIC_JEV_GUIDANCE,
            R.string.semantic_jev_guidance,
            maxStringLength = SemanticGuidance.MAX_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_SEMANTIC_SOURCES,
            FeaturePreferences.SEMANTIC_SOURCE_2_PROVIDER,
            R.string.semantic_source_2_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.2.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_2_ENDPOINT,
            R.string.semantic_source_2_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.2.model",
            FeaturePreferences.SEMANTIC_SOURCE_2_MODEL,
            R.string.semantic_source_2_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.3.provider",
            FeaturePreferences.SEMANTIC_SOURCE_3_PROVIDER,
            R.string.semantic_source_3_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.3.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_3_ENDPOINT,
            R.string.semantic_source_3_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.3.model",
            FeaturePreferences.SEMANTIC_SOURCE_3_MODEL,
            R.string.semantic_source_3_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.4.provider",
            FeaturePreferences.SEMANTIC_SOURCE_4_PROVIDER,
            R.string.semantic_source_4_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.4.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_4_ENDPOINT,
            R.string.semantic_source_4_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            "compat.semantic_source.4.model",
            FeaturePreferences.SEMANTIC_SOURCE_4_MODEL,
            R.string.semantic_source_4_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 36
        ),
        // v46：扩展到 8 个来源；新来源默认无凭据，不会自行启用。
        textSpec(
            "compat.semantic_source.5.provider",
            FeaturePreferences.SEMANTIC_SOURCE_5_PROVIDER,
            R.string.semantic_source_5_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.5.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_5_ENDPOINT,
            R.string.semantic_source_5_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.5.model",
            FeaturePreferences.SEMANTIC_SOURCE_5_MODEL,
            R.string.semantic_source_5_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.6.provider",
            FeaturePreferences.SEMANTIC_SOURCE_6_PROVIDER,
            R.string.semantic_source_6_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.6.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_6_ENDPOINT,
            R.string.semantic_source_6_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.6.model",
            FeaturePreferences.SEMANTIC_SOURCE_6_MODEL,
            R.string.semantic_source_6_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.7.provider",
            FeaturePreferences.SEMANTIC_SOURCE_7_PROVIDER,
            R.string.semantic_source_7_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.7.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_7_ENDPOINT,
            R.string.semantic_source_7_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.7.model",
            FeaturePreferences.SEMANTIC_SOURCE_7_MODEL,
            R.string.semantic_source_7_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.8.provider",
            FeaturePreferences.SEMANTIC_SOURCE_8_PROVIDER,
            R.string.semantic_source_8_label,
            default = SemanticBackend.JEV,
            allowed = SemanticBackend.IDS,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.8.endpoint",
            FeaturePreferences.SEMANTIC_SOURCE_8_ENDPOINT,
            R.string.semantic_source_8_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            "compat.semantic_source.8.model",
            FeaturePreferences.SEMANTIC_SOURCE_8_MODEL,
            R.string.semantic_source_8_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 46
        ),
        textSpec(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_SOURCE,
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_SOURCE,
            R.string.semantic_route_title,
            default = SemanticRoute.AUTO,
            allowed = SemanticRoute.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_DANMAKU_SEMANTIC_SOURCE,
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_SOURCE,
            R.string.semantic_route_title,
            default = SemanticRoute.AUTO,
            allowed = SemanticRoute.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_COMMENT_SEMANTIC_SOURCE,
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_SOURCE,
            R.string.semantic_route_title,
            default = SemanticRoute.AUTO,
            allowed = SemanticRoute.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_VIDEO_SEMANTIC_SOURCE,
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_SOURCE,
            R.string.semantic_route_title,
            default = SemanticRoute.AUTO,
            allowed = SemanticRoute.IDS,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_CUSTOM_RULES,
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_CUSTOM_RULES,
            R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_DANMAKU_SEMANTIC_CUSTOM_RULES,
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_CUSTOM_RULES,
            R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_COMMENT_SEMANTIC_CUSTOM_RULES,
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_CUSTOM_RULES,
            R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH,
            introducedCatalogVersion = 36
        ),
        textSpec(
            SettingsCatalog.ID_VIDEO_SEMANTIC_CUSTOM_RULES,
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_CUSTOM_RULES,
            R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH,
            introducedCatalogVersion = 36
        )
    )

    val surfaceSemanticToggleSpecs: List<SettingSpec> = listOf(
        boolSpec(
            SettingsCatalog.ID_DANMAKU_SEMANTIC_FILTER,
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
            R.string.danmaku_semantic_filter,
            introducedCatalogVersion = 32
        ),
        semanticRulesSpec(
            SettingsCatalog.ID_DANMAKU_SEMANTIC_RULES,
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES,
            SemanticSurface.DANMAKU
        ),
        boolSpec(
            SettingsCatalog.ID_COMMENT_SEMANTIC_FILTER,
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
            R.string.comment_semantic_filter,
            introducedCatalogVersion = 32
        ),
        semanticRulesSpec(
            SettingsCatalog.ID_COMMENT_SEMANTIC_RULES,
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES,
            SemanticSurface.COMMENT
        ),
        boolSpec(
            SettingsCatalog.ID_VIDEO_SEMANTIC_FILTER,
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,
            R.string.video_semantic_filter,
            introducedCatalogVersion = 32
        ),
        semanticRulesSpec(
            SettingsCatalog.ID_VIDEO_SEMANTIC_RULES,
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES,
            SemanticSurface.VIDEO
        )
    )

    val browsingAndIncognitoSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "browsing.no_login.enabled",
            FeaturePreferences.NO_LOGIN_ENABLED,
            R.string.no_login_enabled,
            default = false,
            introducedCatalogVersion = 75
        ),
        boolSpec(
            "browsing.guest.skip_onboarding",
            FeaturePreferences.SKIP_GUEST_ONBOARDING,
            R.string.skip_guest_onboarding,
            default = false,
            introducedCatalogVersion = 75
        ),
        boolSpec(
            "privacy.incognito.enabled",
            FeaturePreferences.INCOGNITO_ENABLED,
            R.string.incognito_enabled,
            default = false,
            introducedCatalogVersion = 75
        )
    )

    val roamingCompatSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "compat.roaming.enabled",
            HookEntry.PREF_ROAMING_COMPAT_ENABLED,
            R.string.roaming_compat_enable,
            restorePolicy = RestorePolicy.MANUAL,
            effects = emptySet()
        )
    )

    val aiInsightServiceSpecs: List<SettingSpec> = listOf(
        // v74：AI Insight 专用服务配置（与判定来源分离）。API Key 是运行时凭据，不进目录、备份与诊断。
        // 放在列表末尾，避免改变 ModuleUiSettings 对「首个 text 默认值为空」的既有位置约定。
        textSpec(
            SettingsCatalog.ID_AI_INSIGHT_PROTOCOL,
            FeaturePreferences.AI_INSIGHT_PROTOCOL,
            R.string.ai_insight_protocol,
            default = InsightConfig.DEFAULT_PROTOCOL,
            allowed = InsightConfig.IDS,
            introducedCatalogVersion = 74
        ),
        textSpec(
            SettingsCatalog.ID_AI_INSIGHT_BASE_URL,
            FeaturePreferences.AI_INSIGHT_BASE_URL,
            R.string.ai_insight_base_url,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH,
            introducedCatalogVersion = 74
        ),
        textSpec(
            SettingsCatalog.ID_AI_INSIGHT_MODEL,
            FeaturePreferences.AI_INSIGHT_MODEL,
            R.string.ai_insight_model,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH,
            introducedCatalogVersion = 74
        ),
        // v78：端点「自动补全 /v1」开关。默认开启 = 沿用旧规则，旧备份缺这一项时恢复为开启，不改变既有行为。
        boolSpec(
            SettingsCatalog.ID_AI_INSIGHT_AUTO_V1,
            FeaturePreferences.AI_INSIGHT_AUTO_V1,
            R.string.ai_insight_auto_v1,
            default = InsightConfig.DEFAULT_AUTO_V1,
            introducedCatalogVersion = 78
        )
    )

    val hostPredictiveBackSpecs: List<SettingSpec> = listOf(
        // v86：强制启用宿主预见式返回（仅作用于符合条件的宿主标准页面）。默认关闭；改动需重启宿主。
        boolSpec(
            SettingsCatalog.ID_HOST_PREDICTIVE_BACK,
            FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED,
            R.string.host_predictive_back_enable,
            default = false,
            introducedCatalogVersion = 86
        )
    )
}
