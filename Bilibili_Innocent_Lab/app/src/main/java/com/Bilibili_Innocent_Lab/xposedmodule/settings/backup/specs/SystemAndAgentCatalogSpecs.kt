package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec

internal object SystemAndAgentCatalogSpecs {

    val adsLeadSpecs: List<SettingSpec> = listOf(
        boolSpec("ads.pause.hidden", HookEntry.PREF_ENABLED, R.string.paused_page_ad_enable, default = true),
        boolSpec("ads.game_card.hidden", HookEntry.PREF_GAMECARD_ENABLED, R.string.gamecard_ad_enable, default = true),
        boolSpec(
            "ads.video_detail.app_promotion.hidden",
            FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
            R.string.hide_video_detail_app_promotion,
            introducedCatalogVersion = 2
        ),
        boolSpec(
            "ads.video_detail.follow_recommend.hidden",
            FeaturePreferences.HIDE_FOLLOW_UP_RECOMMEND,
            R.string.hide_follow_up_recommend,
            introducedCatalogVersion = 83
        )
    )

    val agentSpecs: List<SettingSpec> = listOf(
        boolSpec(
            AgentPreferences.CATALOG_ID,
            AgentPreferences.ENABLED,
            R.string.agent_title,
            restorePolicy = RestorePolicy.MANUAL,
            introducedCatalogVersion = 47
        )
    )

    val homeAdsSpecs: List<SettingSpec> = listOf(
        boolSpec("ads.home_banner.hidden", HookEntry.PREF_BANNER_ENABLED, R.string.banner_ad_enable, default = true),
        boolSpec("ads.merchandise.hidden", HookEntry.PREF_MERCH_ENABLED, R.string.merch_ad_enable, default = true)
    )

    val clientUpdateSpecs: List<SettingSpec> = listOf(
        boolSpec("client.update_prompt.blocked", FeaturePreferences.BLOCK_APP_UPDATE, R.string.block_app_update)
    )

    val fullNumbersSpecs: List<SettingSpec> = listOf(
        boolSpec("numbers.full.enabled", FeaturePreferences.SHOW_FULL_NUMBERS, R.string.show_full_numbers)
    )

    val componentLibraryAndTeenagersSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "client.component_library.download.blocked",
            FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD,
            R.string.block_component_library_download,
            introducedCatalogVersion = 24
        ),
        // 勾选面板的两把钥匙，与其余四个面同构：selectors 是勾出来的，rules 是手填的
        //（`*` 即「全量禁止」开关写的哨兵），两者取并集。
        textSpec(
            "client.component_library.blocked_pools.rules",
            FeaturePreferences.COMPONENT_POOL_BLOCKED_RULES,
            R.string.component_pool_block,
            introducedCatalogVersion = 26
        ),
        textSpec(
            "client.component_library.blocked_pools.selectors",
            FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS,
            R.string.component_pool_block,
            introducedCatalogVersion = 26
        ),
        boolSpec("prompt.teenagers_mode.blocked", FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, R.string.block_teenagers_mode_prompt)
    )

    val splashShareAndSystemSpecs: List<SettingSpec> = listOf(
        boolSpec("splash.ads.purified", FeaturePreferences.PURIFY_SPLASH_ADS, R.string.purify_splash_ads),
        boolSpec(
            "splash.brand.skipped",
            FeaturePreferences.BRAND_SPLASH_SKIP,
            R.string.brand_splash_skip,
            introducedCatalogVersion = 38
        ),
        boolSpec(
            "splash.custom_selection.enabled",
            FeaturePreferences.BRAND_SPLASH_CUSTOM,
            R.string.brand_splash_custom,
            introducedCatalogVersion = 38
        ),
        boolSpec(
            "splash.auto_night.enabled",
            FeaturePreferences.SPLASH_AUTO_NIGHT,
            R.string.splash_auto_night,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "share.content.purified",
            FeaturePreferences.PURIFY_SHARE_CONTENT,
            R.string.purify_share_content,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "share.mini_program.direct_link",
            FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
            R.string.share_mini_program_direct_link,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "share.wechat.resigned_compat",
            FeaturePreferences.WECHAT_SHARE_COMPAT,
            R.string.wechat_share_compat,
            introducedCatalogVersion = 70
        ),
        boolSpec(
            "links.external_browser.enabled",
            FeaturePreferences.FORCE_EXTERNAL_BROWSER,
            R.string.force_external_browser,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "system.media_notification.enabled",
            FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
            R.string.system_media_notification,
            introducedCatalogVersion = 11
        )
    )

    val cloudKeyFreezeAndBvSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "cloud.key_freeze.enabled",
            FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED,
            R.string.cloud_key_freeze,
            introducedCatalogVersion = 67
        ),
        textSpec(
            "cloud.key_freeze.rules",
            FeaturePreferences.CLOUD_KEY_FREEZE_RULES,
            R.string.cloud_key_freeze_rules,
            introducedCatalogVersion = 67
        ),
        boolSpec(
            "cloud.key_freeze.observe",
            FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE,
            R.string.cloud_key_freeze_observe,
            introducedCatalogVersion = 67
        ),
        textSpec(
            "cloud.key_freeze.picked",
            FeaturePreferences.CLOUD_KEY_FREEZE_PICKED,
            R.string.cloud_key_freeze_picked,
            introducedCatalogVersion = 69
        ),
        boolSpec(
            "numbers.bv_as_av.enabled",
            FeaturePreferences.SHOW_BV_AS_AV,
            R.string.show_bv_as_av,
            introducedCatalogVersion = 11
        )
    )

    val diagnosticsLoggingSpecs: List<SettingSpec> = listOf(
        boolSpec("diagnostics.logging.enabled", HookEntry.PREF_LOG_ENABLED, R.string.log_capture_enable, default = true),
        textSpec(
            "diagnostics.logging.level",
            HookEntry.PREF_LOG_LEVEL,
            R.string.log_level_label,
            default = HookEntry.LOG_LEVEL_COMPLETE,
            allowed = setOf(HookEntry.LOG_LEVEL_MINIMAL, HookEntry.LOG_LEVEL_COMPLETE)
        )
    )

    val systemEnvDetectionSpecs: List<SettingSpec> = listOf(
        // v85：阻止检测系统环境（仅作用于已适配的宿主本地 Root / Hook 检测点）。默认关闭；改动需重启宿主。
        boolSpec(
            "system.env_detection.blocked",
            FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION,
            R.string.block_system_env_detection,
            default = false,
            introducedCatalogVersion = 85
        )
    )
}
