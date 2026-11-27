package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationMode
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object PlayerCatalogSpecs {

    val endPageRecommendSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "player.end_page_recommend.hidden",
            FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND,
            R.string.hide_player_end_page_recommend,
            introducedCatalogVersion = 23
        )
    )

    val playbackAndDanmakuLiveSpecs: List<SettingSpec> = listOf(
        boolSpec("player.portrait_control.hidden", FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, R.string.hide_player_portrait_control),
        boolSpec(
            "player.interactive_overlays.hidden",
            FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
            R.string.hide_player_interactive_overlays,
            introducedCatalogVersion = 7
        ),
        boolSpec(
            "player.popup_promotion.hidden",
            FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION,
            R.string.hide_player_popup_promotion,
            introducedCatalogVersion = 21
        ),
        boolSpec(
            "pgc.auto_activity_popup.hidden",
            FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
            R.string.hide_pgc_auto_activity_popup,
            introducedCatalogVersion = 10
        ),
        boolSpec("player.status_bar.transparent", FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, R.string.transparent_player_status_bar),
        boolSpec(
            "player.danmaku.weight_filter.enabled",
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
            R.string.danmaku_weight_filter,
            introducedCatalogVersion = 11
        ),
        intSpec(
            SettingsCatalog.ID_DANMAKU_WEIGHT_MINIMUM,
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM,
            R.string.danmaku_weight_dialog_title,
            default = DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT,
            range = DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "player.danmaku.vip_colorful.removed",
            FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
            R.string.remove_vip_colorful_danmaku,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "live.room_switch.blocked",
            FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
            R.string.block_live_room_switch,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "live.double_tap.pause",
            FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
            R.string.live_room_double_tap_pause,
            introducedCatalogVersion = 11
        )
    )

    val storySpecs: List<SettingSpec> = listOf(
        boolSpec("story.ads.removed", FeaturePreferences.REMOVE_STORY_ADS, R.string.remove_story_ads),
        boolSpec("story.live.removed", FeaturePreferences.REMOVE_STORY_LIVE, R.string.remove_story_live),
        boolSpec("story.games.removed", FeaturePreferences.REMOVE_STORY_GAMES, R.string.remove_story_games),
        boolSpec("story.bangumi.removed", FeaturePreferences.REMOVE_STORY_BANGUMI, R.string.remove_story_bangumi),
        boolSpec("story.courses.removed", FeaturePreferences.REMOVE_STORY_COURSES, R.string.remove_story_courses),
        boolSpec("story.short_drama.removed", FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, R.string.remove_story_short_drama),
        boolSpec("story.shopping.removed", FeaturePreferences.REMOVE_STORY_SHOPPING, R.string.remove_story_shopping),
        boolSpec("story.movies.removed", FeaturePreferences.REMOVE_STORY_MOVIES, R.string.remove_story_movies),
        boolSpec("story.documentaries.removed", FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, R.string.remove_story_documentaries),
        boolSpec("story.tv.removed", FeaturePreferences.REMOVE_STORY_TV, R.string.remove_story_tv),
        boolSpec("story.variety.removed", FeaturePreferences.REMOVE_STORY_VARIETY, R.string.remove_story_variety),
        boolSpec("story.music.removed", FeaturePreferences.REMOVE_STORY_MUSIC, R.string.remove_story_music),
        // v37：Story 竖屏流右侧互动图标，每个图标单独开关。
        boolSpec(
            "story.action.like.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_LIKE,
            R.string.hide_story_action_like,
            introducedCatalogVersion = 37
        ),
        boolSpec(
            "story.action.comment.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_COMMENT,
            R.string.hide_story_action_comment,
            introducedCatalogVersion = 37
        ),
        boolSpec(
            "story.action.coin.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_COIN,
            R.string.hide_story_action_coin,
            introducedCatalogVersion = 37
        ),
        boolSpec(
            "story.action.favorite.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_FAVORITE,
            R.string.hide_story_action_favorite,
            introducedCatalogVersion = 37
        ),
        boolSpec(
            "story.action.share.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_SHARE,
            R.string.hide_story_action_share,
            introducedCatalogVersion = 37
        ),
        boolSpec(
            "story.action.danmaku_toggle.hidden",
            FeaturePreferences.HIDE_STORY_ACTION_DANMAKU_TOGGLE,
            R.string.hide_story_action_danmaku_toggle,
            introducedCatalogVersion = 37
        )
    )

    val qualityAndBufferSpecs: List<SettingSpec> = listOf(
        intSpec(
            "player.default_quality.qn",
            FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN,
            R.string.player_default_quality,
            default = 0,
            allowed = PlayerQualityConfig.supportedQns.toSet()
        ),
        intSpec(
            "player.codec.preference",
            FeaturePreferences.PLAYER_CODEC_PREFERENCE,
            R.string.player_codec_preference,
            default = 0,
            range = 0..3,
            introducedCatalogVersion = 24
        ),
        intSpec(
            "player.decode.mode",
            FeaturePreferences.PLAYER_DECODE_MODE,
            R.string.player_decode_mode,
            default = 0,
            range = 0..2,
            introducedCatalogVersion = 24
        ),
        intSpec(
            "player.buffer.mode",
            FeaturePreferences.PLAYER_BUFFER_MODE,
            R.string.player_buffer_strategy,
            default = 0,
            allowed = PlayerBufferConfig.supportedModes,
            introducedCatalogVersion = 51
        ),
        intSpec(
            "player.buffer.seconds",
            FeaturePreferences.PLAYER_BUFFER_SECONDS,
            R.string.player_buffer_seconds,
            default = PlayerBufferConfig.DEFAULT_SECONDS,
            range = PlayerBufferConfig.MIN_SECONDS..PlayerBufferConfig.MAX_SECONDS,
            introducedCatalogVersion = 51
        ),
        intSpec(
            "player.buffer.memory_mb",
            FeaturePreferences.PLAYER_BUFFER_MEMORY_MB,
            R.string.player_buffer_memory_mb,
            default = PlayerBufferConfig.DEFAULT_MEMORY_MB,
            range = PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB,
            introducedCatalogVersion = 51
        ),
        intSpec(
            "player.buffer.disk_mb",
            FeaturePreferences.PLAYER_BUFFER_DISK_MB,
            R.string.player_buffer_disk_mb,
            default = PlayerBufferConfig.DEFAULT_DISK_MB,
            range = PlayerBufferConfig.MIN_DISK_MB..PlayerBufferConfig.MAX_DISK_MB,
            introducedCatalogVersion = 51
        ),
        boolSpec(
            "player.buffer.wifi_only",
            FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY,
            R.string.player_buffer_wifi_only,
            default = PlayerBufferConfig.DEFAULT_WIFI_ONLY,
            introducedCatalogVersion = 51
        )
    )

    val capabilityAudioInterpolationAndSponsorSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "player.capability.background",
            FeaturePreferences.PLAYER_UNLOCK_BACKGROUND,
            R.string.player_unlock_background,
            introducedCatalogVersion = 13
        ),
        boolSpec(
            "player.capability.small_window",
            FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW,
            R.string.player_unlock_small_window,
            introducedCatalogVersion = 13
        ),
        boolSpec(
            "player.capability.cast",
            FeaturePreferences.PLAYER_UNLOCK_CAST,
            R.string.player_unlock_cast,
            introducedCatalogVersion = 13
        ),
        boolSpec(
            "player.subtitle.style.enabled",
            FeaturePreferences.PLAYER_SUBTITLE_STYLE,
            R.string.player_subtitle_style,
            introducedCatalogVersion = 62
        ),
        boolSpec(
            "player.subtitle.ai.enabled",
            FeaturePreferences.PLAYER_AI_SUBTITLE,
            R.string.player_ai_subtitle,
            introducedCatalogVersion = 62
        ),
        boolSpec(
            "player.frame_interpolation.enabled",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
            R.string.player_frame_interpolation,
            introducedCatalogVersion = 58
        ),
        boolSpec(
            "player.audio.peak_guard",
            FeaturePreferences.PLAYER_AUDIO_PEAK_GUARD,
            R.string.player_audio_peak_guard,
            introducedCatalogVersion = 81
        ),
        intSpec(
            "player.audio.channels",
            FeaturePreferences.PLAYER_AUDIO_CHANNELS,
            R.string.player_audio_channels,
            default = 0,
            allowed = setOf(0, 1, 2),
            introducedCatalogVersion = 81
        ),
        boolSpec(
            "player.audio.noise_reduction",
            FeaturePreferences.PLAYER_AUDIO_NOISE_REDUCTION,
            R.string.player_audio_noise_reduction,
            introducedCatalogVersion = 82
        ),
        boolSpec(
            "player.audio.voice_focus",
            FeaturePreferences.PLAYER_AUDIO_VOICE_FOCUS,
            R.string.player_audio_voice_focus,
            introducedCatalogVersion = 82
        ),
        intSpec(
            "player.frame_interpolation.max_fps",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS,
            R.string.player_frame_max_fps,
            default = 60,
            allowed = setOf(60, 90, 120, 144, 165, 185),
            introducedCatalogVersion = 59
        ),
        boolSpec(
            "player.frame_interpolation.status_toast",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST,
            R.string.player_frame_status_toast,
            introducedCatalogVersion = 61
        ),
        boolSpec(
            "player.frame_interpolation.disable_thermal_control",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL,
            R.string.player_frame_disable_thermal_control,
            introducedCatalogVersion = 64
        ),
        boolSpec(
            "player.frame_interpolation.force_mode",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE,
            R.string.player_frame_force_mode,
            introducedCatalogVersion = 84
        ),
        textSpec(
            "player.frame_interpolation.mode",
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MODE,
            R.string.player_frame_mode,
            default = FrameInterpolationMode.AUTO_DEFAULT,
            allowed = FrameInterpolationMode.ALLOWED_STORAGE_VALUES,
            introducedCatalogVersion = 84
        ),
        boolSpec(
            "player.long_press.disabled",
            FeaturePreferences.PLAYER_DISABLE_LONG_PRESS,
            R.string.player_disable_long_press,
            introducedCatalogVersion = 13
        ),
        intSpec(
            SettingsCatalog.ID_PLAYER_LONG_PRESS_SPEED,
            FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT,
            R.string.player_long_press_speed,
            default = PlayerSpeedConfig.FOLLOW_HOST,
            allowed = PlayerSpeedConfig.supportedPercents,
            introducedCatalogVersion = 13
        ),
        intSpec(
            SettingsCatalog.ID_PLAYER_DEFAULT_SPEED,
            FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            R.string.player_default_speed,
            default = PlayerSpeedConfig.FOLLOW_HOST,
            allowed = PlayerSpeedConfig.supportedPercents,
            introducedCatalogVersion = 13
        ),
        textSpec(
            SettingsCatalog.ID_PLAYER_SPEED_MENU,
            FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS,
            R.string.player_speed_menu,
            default = "",
            maxStringLength = PlayerSpeedConfig.MAX_MENU_SPEC_LENGTH,
            introducedCatalogVersion = 60
        ),
        boolSpec(
            "player.sponsorblock.enabled",
            FeaturePreferences.SPONSORBLOCK_ENABLED,
            R.string.sponsorblock_enabled,
            introducedCatalogVersion = 44
        ),
        boolSpec(
            "player.sponsorblock.automatic",
            FeaturePreferences.SPONSORBLOCK_AUTOMATIC,
            R.string.sponsorblock_automatic,
            introducedCatalogVersion = 44
        ),
        boolSpec(
            "player.sponsorblock.story",
            FeaturePreferences.SPONSORBLOCK_STORY,
            R.string.sponsorblock_story_enabled,
            introducedCatalogVersion = 68
        )
    )
}
