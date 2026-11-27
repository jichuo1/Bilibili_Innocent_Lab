package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpecStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.ModalBackdropBlurStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValueType
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object HostUiCatalogSpecs {

    val hostNavigationAndSurfaceSpecs: List<SettingSpec> = listOf(
        textSpec("navigation.bottom_bar.hidden_rules", FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES, R.string.custom_bottom_bar_hide),
        textSpec(
            "navigation.bottom_bar.hidden_selectors",
            FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS,
            R.string.custom_bottom_bar_hide,
            introducedCatalogVersion = 9
        ),
        boolSpec(
            "host.bottom_bar.liquid_glass.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
            R.string.host_bottom_bar_liquid_glass,
            default = false,
            introducedCatalogVersion = 39
        ),
        textSpec(
            "host.background.preset",
            FeaturePreferences.HOST_BACKGROUND_PRESET,
            R.string.host_background_title,
            default = "off",
            allowed = HostBackgroundPreset.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 49
        ),
        intSpec(
            "host.background.blur",
            FeaturePreferences.HOST_BACKGROUND_BLUR,
            R.string.host_background_blur,
            default = 16,
            range = 0..60,
            introducedCatalogVersion = 49
        ),
        intSpec(
            "host.background.saturation",
            FeaturePreferences.HOST_BACKGROUND_SATURATION,
            R.string.host_background_saturation,
            default = 85,
            range = 0..200,
            introducedCatalogVersion = 49
        ),
        intSpec(
            "host.background.veil",
            FeaturePreferences.HOST_BACKGROUND_VEIL,
            R.string.host_background_veil,
            default = 35,
            range = 0..90,
            introducedCatalogVersion = 49
        ),
        // 图片仍在本机私有存储，资产引用只随 Hook 快照发布，不跨设备自动恢复。
        SettingSpec(
            "host.background.asset",
            FeaturePreferences.HOST_BACKGROUND_ASSET,
            R.string.host_background_custom,
            SettingValueType.STRING,
            SettingValue.Text(""),
            introducedCatalogVersion = 49,
            restorePolicy = RestorePolicy.MANUAL,
            maxStringLength = 36
        ),
        boolSpec(
            "host.video_cards.enabled",
            FeaturePreferences.HOST_VIDEO_CARDS,
            R.string.host_video_cards,
            default = false,
            introducedCatalogVersion = 42
        ),
        textSpec(
            "host.cards.material",
            FeaturePreferences.HOST_CARD_MATERIAL,
            R.string.host_card_material,
            default = "original",
            allowed = HostCardMaterial.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 55
        ),
        intSpec(
            "host.video_cards.radius_dp",
            FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP,
            R.string.host_video_card_radius,
            default = -1,
            range = -1..40,
            introducedCatalogVersion = 43
        ),
        intSpec(
            "host.home_cards.horizontal_dp",
            FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP,
            R.string.host_home_card_horizontal,
            default = -1,
            range = -1..40,
            introducedCatalogVersion = 50
        ),
        intSpec(
            "host.home_cards.vertical_dp",
            FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP,
            R.string.host_home_card_vertical,
            default = -1,
            range = -1..40,
            introducedCatalogVersion = 50
        ),
        boolSpec(
            "host.bottom_bar.compact.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
            R.string.host_bottom_bar_compact,
            default = false,
            introducedCatalogVersion = 41
        ),
        textSpec(
            "host.bottom_bar.glass.material",
            FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL,
            R.string.host_glass_material,
            default = HostGlassMaterial.SOFT.value,
            allowed = HostGlassMaterial.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 45
        ),
        textSpec(
            "host.bottom_bar.glass.renderer",
            FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER,
            R.string.host_glass_renderer,
            default = HostGlassRenderer.LEGACY,
            allowed = HostGlassRenderer.entries.map { it.value }.toSet() + HostGlassRenderer.LEGACY,
            introducedCatalogVersion = 45
        ),
        textSpec(
            "host.top_bar.glass.material",
            FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL,
            R.string.host_glass_material,
            default = HostGlassMaterial.SOFT.value,
            allowed = HostGlassMaterial.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 45
        ),
        textSpec(
            "host.top_bar.glass.renderer",
            FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER,
            R.string.host_glass_renderer,
            default = HostGlassRenderer.LEGACY,
            allowed = HostGlassRenderer.entries.map { it.value }.toSet() + HostGlassRenderer.LEGACY,
            introducedCatalogVersion = 45
        ),
        boolSpec(
            "host.bottom_bar.icon_only.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
            R.string.host_bottom_bar_icon_only,
            default = false,
            introducedCatalogVersion = 41
        ),
        boolSpec(
            "host.mine.garb_dim.enabled",
            FeaturePreferences.HOST_MINE_GARB_DIM,
            R.string.host_mine_garb_dim,
            default = true,
            introducedCatalogVersion = 65
        ),
        // 默认值与 HostUiPowerPolicy.DEFAULT_ENABLED 一致（单测钉住），目录不依赖 hook 包。
        boolSpec(
            "host.ui.power_saving.enabled",
            FeaturePreferences.HOST_UI_POWER_SAVING,
            R.string.host_ui_power_saving,
            default = true,
            introducedCatalogVersion = 72
        ),
        boolSpec(
            "host.bottom_bar.touch_glow.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
            R.string.host_bottom_bar_touch_glow,
            default = false,
            introducedCatalogVersion = 39
        ),
        boolSpec(
            "host.top_bar.liquid_glass.enabled",
            FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
            R.string.host_top_bar_liquid_glass,
            default = false,
            introducedCatalogVersion = 40
        ),
        boolSpec(
            "host.top_bar.touch_glow.enabled",
            FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
            R.string.host_top_bar_touch_glow,
            default = false,
            introducedCatalogVersion = 40
        )
    )

    val moduleUiSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "module_ui.predictive_back.enabled",
            HookEntry.PREF_PREDICTIVE_BACK_ENABLED,
            R.string.predictive_back_enable,
            effects = setOf(
                ImportEffect.REAPPLY_PREDICTIVE_BACK,
                ImportEffect.RECREATE_MODULE_UI
            )
        ),
        textSpec(
            SettingsCatalog.ID_MATERIAL_COLOR_SPEC,
            MaterialColorSpecStore.PREF_KEY,
            R.string.material_color_spec_title,
            default = MaterialColorSpec.DEFAULT.storageValue,
            allowed = setOf(
                MaterialColorSpec.SPEC_2021.storageValue,
                MaterialColorSpec.SPEC_2025.storageValue
            ),
            introducedCatalogVersion = 4,
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)
        ),
        boolSpec(
            "module_ui.appearance.panel_window_blur",
            ModalBackdropBlurStore.PREF_KEY,
            R.string.panel_window_blur_title,
            default = ModalBackdropBlurStore.DEFAULT,
            introducedCatalogVersion = 17,
            // 只影响模块界面自己的弹窗动画，不进宿主，不需要重启哔哩哔哩。
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)
        )
    )

    val hostBottomBarBadgeAccentSpecs: List<SettingSpec> = listOf(
        // v79：浮动底栏下数量角标使用强调色。默认关闭，保持现有外观；改动需重启宿主。
        boolSpec(
            SettingsCatalog.ID_HOST_BOTTOM_BAR_BADGE_ACCENT,
            FeaturePreferences.HOST_BOTTOM_BAR_BADGE_ACCENT,
            R.string.host_bottom_bar_badge_accent,
            default = false,
            introducedCatalogVersion = 79
        )
    )
}
