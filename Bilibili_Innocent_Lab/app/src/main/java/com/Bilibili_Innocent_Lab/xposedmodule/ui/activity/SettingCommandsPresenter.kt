package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Intent
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.toast

/** Extracted business operations; no source View or source setter is captured. */
internal fun MainActivity.bindSettingsStateMirrors() {
    if (settingsState.hasMirror(FeaturePreferences.BRAND_SPLASH_SKIP)) return
    fun mirror(key: String, error: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        settingsState.registerBooleanMirror(key, error, get, set, verifyReadback = true)

    mirror(FeaturePreferences.BRAND_SPLASH_SKIP, "write brand splash skip prefs failed", { brandSplashSkip }, { brandSplashSkip = it })
    mirror(FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION, "write system media notification prefs failed", { systemMediaNotification }, { systemMediaNotification = it })
    mirror(FeaturePreferences.HOME_FRAME_LEGACY_LOCK, "write home frame legacy lock prefs failed", { homeFrameLegacyLock }, { homeFrameLegacyLock = it })
    mirror(FeaturePreferences.HOME_RECOMMEND_EARLY_LOAD, "write home recommend early load prefs failed", { homeRecommendEarlyLoad }, { homeRecommendEarlyLoad = it })
    mirror(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, "write cloud key freeze prefs failed", { cloudKeyFreeze }, { cloudKeyFreeze = it })
    mirror(FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE, "write cloud key freeze observe prefs failed", { cloudKeyFreezeObserve }, { cloudKeyFreezeObserve = it })
    mirror(FeaturePreferences.FORCE_EXTERNAL_BROWSER, "write external browser prefs failed", { forceExternalBrowser }, { forceExternalBrowser = it })
    mirror(FeaturePreferences.SHOW_FULL_NUMBERS, "write full numbers prefs failed", { showFullNumbers }, { showFullNumbers = it })
    mirror(FeaturePreferences.SHOW_BV_AS_AV, "write show bv as av prefs failed", { showBvAsAv }, { showBvAsAv = it })
    mirror(FeaturePreferences.COMMENT_CLASSIC_STYLE, "write comment classic style prefs failed", { commentClassicStyle }, { commentClassicStyle = it })
    mirror(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, "write reply topology prefs failed", { replyTopologyEnabled }, { replyTopologyEnabled = it })
    mirror(FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, "write comment quick reply prefs failed", { blockCommentQuickReply }, { blockCommentQuickReply = it })
    mirror(FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH, "write live room switch prefs failed", { blockLiveRoomSwitch }, { blockLiveRoomSwitch = it })
    mirror(FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE, "write live room double tap prefs failed", { liveRoomDoubleTapPause }, { liveRoomDoubleTapPause = it })
    mirror(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, "write player long press prefs failed", { playerDisableLongPress }, { playerDisableLongPress = it })
    mirror(FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED, "write home recommend section pick prefs failed", { homeRecommendSectionPickEnabled }, { homeRecommendSectionPickEnabled = it })
    mirror(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, "write ai declared videos prefs failed", { blockAiDeclaredVideos }, { blockAiDeclaredVideos = it })
    mirror(FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL, "write home vertical detail prefs failed", { homeVerticalOpenDetail }, { homeVerticalOpenDetail = it })
    mirror(FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB, "write preferred dynamic video prefs failed", { preferDynamicVideoTab }, { preferDynamicVideoTab = it })
    mirror(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, "write comment fish filter prefs failed", { commentFishFilterEnabled }, { commentFishFilterEnabled = it })
    mirror(FeaturePreferences.COMMENT_FISH_STRONG_MODE, "write comment fish strong mode prefs failed", { commentFishStrongMode }, { commentFishStrongMode = it })
    mirror(FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, "write comment fish protect high level prefs failed", { commentFishProtectHighLevel }, { commentFishProtectHighLevel = it })
    mirror(FeaturePreferences.NO_LOGIN_ENABLED, "write no-login prefs failed", { noLoginEnabled }, { noLoginEnabled = it })
    mirror(FeaturePreferences.INCOGNITO_ENABLED, "write incognito prefs failed", { incognitoEnabled }, { incognitoEnabled = it })
    mirror(FeaturePreferences.SKIP_GUEST_ONBOARDING, "write skip guest onboarding prefs failed", { skipGuestOnboarding }, { skipGuestOnboarding = it })
    mirror(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, "write host bottom bar liquid glass prefs failed", { hostBottomBarLiquidGlass }, { hostBottomBarLiquidGlass = it })
    mirror(FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, "write host bottom bar compact prefs failed", { hostBottomBarCompact }, { hostBottomBarCompact = it })
    mirror(FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, "write host bottom bar icon_only prefs failed", { hostBottomBarIconOnly }, { hostBottomBarIconOnly = it })
    mirror(FeaturePreferences.HOST_MINE_GARB_DIM, "write host mine garb dim prefs failed", { hostMineGarbDim }, { hostMineGarbDim = it })
    mirror(FeaturePreferences.HOST_BOTTOM_BAR_BADGE_ACCENT, "write host bottom bar badge accent prefs failed", { hostBottomBarBadgeAccent }, { hostBottomBarBadgeAccent = it })
    mirror(FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW, "write host bottom bar touch glow prefs failed", { hostBottomBarTouchGlow }, { hostBottomBarTouchGlow = it })
    mirror(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, "write host top bar liquid glass prefs failed", { hostTopBarLiquidGlass }, { hostTopBarLiquidGlass = it })
    mirror(FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, "write host top bar touch glow prefs failed", { hostTopBarTouchGlow }, { hostTopBarTouchGlow = it })
    mirror(FeaturePreferences.HOST_VIDEO_CARDS, "write host video card prefs failed", { hostVideoCards }, { hostVideoCards = it })
    mirror(FeaturePreferences.PURIFY_SHARE_CONTENT, "write share purify prefs failed", { purifyShareContent }, { purifyShareContent = it })
    mirror(FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK, "write mini program share prefs failed", { shareMiniProgramDirectLink }, { shareMiniProgramDirectLink = it })
    mirror(FeaturePreferences.WECHAT_SHARE_COMPAT, "write WeChat share compatibility prefs failed", { weChatShareCompat }, { weChatShareCompat = it })
    mirror(FeaturePreferences.PURIFY_SPLASH_ADS, "write splash ads purify prefs failed", { purifySplashAds }, { purifySplashAds = it })
    mirror(FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, "write teenagers mode prefs failed", { blockTeenagersModePrompt }, { blockTeenagersModePrompt = it })
    mirror(FeaturePreferences.BLOCK_APP_UPDATE, "write block app update prefs failed", { blockAppUpdate }, { blockAppUpdate = it })
    mirror(FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD, "write component library prefs failed", { blockComponentLibraryDownload }, { blockComponentLibraryDownload = it })
    mirror(FeaturePreferences.HIDE_COMMENT_SECTION, "write hide comment section prefs failed", { hideCommentSection }, { hideCommentSection = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, "write remove comment search links prefs failed", { removeCommentSearchLinks }, { removeCommentSearchLinks = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, "write remove comment empty guide prefs failed", { removeCommentEmptyGuide }, { removeCommentEmptyGuide = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, "write remove comment vote widgets prefs failed", { removeCommentVoteWidgets }, { removeCommentVoteWidgets = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, "write remove comment follow buttons prefs failed", { removeCommentFollowButtons }, { removeCommentFollowButtons = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_QOE, "write remove comment qoe prefs failed", { removeCommentQoe }, { removeCommentQoe = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_OPERATIONS, "write comment operation prefs failed", { removeCommentOperations }, { removeCommentOperations = it })
    mirror(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED, "write comment semantic switch failed", { commentSemanticFilterEnabled }, { commentSemanticFilterEnabled = it })
    mirror(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, "write comment keyword filter prefs failed", { commentKeywordFilterEnabled }, { commentKeywordFilterEnabled = it })
    mirror(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, "write comment min level filter prefs failed", { commentMinLevelFilterEnabled }, { commentMinLevelFilterEnabled = it })
    mirror(FeaturePreferences.REMOVE_AT_ONLY_COMMENTS, "write remove at-only comments prefs failed", { removeAtOnlyComments }, { removeAtOnlyComments = it })
    mirror(FeaturePreferences.COMMENT_USER_FILTER_ENABLED, "write comment user filter prefs failed", { commentUserFilterEnabled }, { commentUserFilterEnabled = it })
    mirror(FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, "write hide player portrait control prefs failed", { hidePlayerPortraitControl }, { hidePlayerPortraitControl = it })
    mirror(FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS, "write hide player interactive overlays prefs failed", { hidePlayerInteractiveOverlays }, { hidePlayerInteractiveOverlays = it })
    mirror(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, "write hide player popup promotion prefs failed", { hidePlayerPopupPromotion }, { hidePlayerPopupPromotion = it })
    mirror(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, "write hide player end page recommend prefs failed", { hidePlayerEndPageRecommend }, { hidePlayerEndPageRecommend = it })
    mirror(FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP, "write hide pgc auto activity popup prefs failed", { hidePgcAutoActivityPopup }, { hidePgcAutoActivityPopup = it })
    mirror(FeaturePreferences.REMOVE_STORY_ADS, "write remove story ads prefs failed", { removeStoryAds }, { removeStoryAds = it })
    mirror(FeaturePreferences.REMOVE_STORY_LIVE, "write remove story live prefs failed", { removeStoryLive }, { removeStoryLive = it })
    mirror(FeaturePreferences.REMOVE_STORY_GAMES, "write remove story games prefs failed", { removeStoryGames }, { removeStoryGames = it })
    mirror(FeaturePreferences.REMOVE_STORY_BANGUMI, "write remove story bangumi prefs failed", { removeStoryBangumi }, { removeStoryBangumi = it })
    mirror(FeaturePreferences.REMOVE_STORY_COURSES, "write remove story courses prefs failed", { removeStoryCourses }, { removeStoryCourses = it })
    mirror(FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, "write remove story short drama prefs failed", { removeStoryShortDrama }, { removeStoryShortDrama = it })
    mirror(FeaturePreferences.REMOVE_STORY_SHOPPING, "write remove story shopping prefs failed", { removeStoryShopping }, { removeStoryShopping = it })
    mirror(FeaturePreferences.REMOVE_STORY_MOVIES, "write remove story movies prefs failed", { removeStoryMovies }, { removeStoryMovies = it })
    mirror(FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, "write remove story documentaries prefs failed", { removeStoryDocumentaries }, { removeStoryDocumentaries = it })
    mirror(FeaturePreferences.REMOVE_STORY_TV, "write remove story tv prefs failed", { removeStoryTv }, { removeStoryTv = it })
    mirror(FeaturePreferences.REMOVE_STORY_VARIETY, "write remove story variety prefs failed", { removeStoryVariety }, { removeStoryVariety = it })
    mirror(FeaturePreferences.REMOVE_STORY_MUSIC, "write remove story music prefs failed", { removeStoryMusic }, { removeStoryMusic = it })
    mirror(FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED, "write danmaku weight filter prefs failed", { danmakuWeightFilterEnabled }, { danmakuWeightFilterEnabled = it })
    mirror(FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU, "write vip colorful danmaku prefs failed", { removeVipColorfulDanmaku }, { removeVipColorfulDanmaku = it })
    mirror(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED, "write danmaku semantic switch failed", { danmakuSemanticFilterEnabled }, { danmakuSemanticFilterEnabled = it })
    mirror(FeaturePreferences.HIDE_MINE_VIP, "write hide mine vip prefs failed", { hideMineVip }, { hideMineVip = it })
    mirror(FeaturePreferences.KEEP_MINE_VIP_SPACE, "write keep mine vip space prefs failed", { keepMineVipSpace }, { keepMineVipSpace = it })
    mirror(FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND, "write hide search home recommend prefs failed", { hideSearchHomeRecommend }, { hideSearchHomeRecommend = it })
    mirror(FeaturePreferences.REMOVE_SEARCH_COMMERCIAL, "write remove search commercial prefs failed", { removeSearchCommercial }, { removeSearchCommercial = it })
    mirror(FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED, "write search keyword filter prefs failed", { searchKeywordFilterEnabled }, { searchKeywordFilterEnabled = it })
    mirror(FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED, "write search author filter prefs failed", { searchAuthorFilterEnabled }, { searchAuthorFilterEnabled = it })
    mirror(FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, "write dynamic city tab prefs failed", { hideDynamicCityTab }, { hideDynamicCityTab = it })
    mirror(FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, "write dynamic school tab prefs failed", { hideDynamicSchoolTab }, { hideDynamicSchoolTab = it })
    mirror(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED, "write dynamic semantic switch failed", { dynamicSemanticFilterEnabled }, { dynamicSemanticFilterEnabled = it })
    mirror(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED, "write dynamic keyword filter prefs failed", { dynamicKeywordFilterEnabled }, { dynamicKeywordFilterEnabled = it })
    mirror(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED, "write dynamic author filter prefs failed", { dynamicAuthorFilterEnabled }, { dynamicAuthorFilterEnabled = it })
    mirror(FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS, "write dynamic promotion filter prefs failed", { removeDynamicPromotions }, { removeDynamicPromotions = it })
    mirror(FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY, "write dynamic charge only filter prefs failed", { removeDynamicChargeOnly }, { removeDynamicChargeOnly = it })
    mirror(FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS, "write dynamic frequent visits prefs failed", { hideDynamicFrequentVisits }, { hideDynamicFrequentVisits = it })
    mirror(FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST, "write dynamic topic list prefs failed", { hideDynamicTopicList }, { hideDynamicTopicList = it })
    mirror(FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES, "write dynamic live up entries prefs failed", { removeDynamicLiveUpEntries }, { removeDynamicLiveUpEntries = it })
    mirror(FeaturePreferences.HIDE_HOME_GAME_MENU, "write home game menu prefs failed", { hideHomeGameMenu }, { hideHomeGameMenu = it })
    mirror(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, "write home search word prefs failed", { hideHomeSearchDefaultWord }, { hideHomeSearchDefaultWord = it })
    mirror(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, "write live home promotion prefs failed", { hideLiveHomePromotion }, { hideLiveHomePromotion = it })
    mirror(FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2, "write home recommend cm_v2 prefs failed", { removeHomeRecommendCmV2 }, { removeHomeRecommendCmV2 = it })
    mirror(FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED, "write video semantic switch failed", { videoSemanticFilterEnabled }, { videoSemanticFilterEnabled = it })
    mirror(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, "write home recommend title filter prefs failed", { homeRecommendTitleFilterEnabled }, { homeRecommendTitleFilterEnabled = it })
    mirror(HookEntry.PREF_GAMECARD_ENABLED, "write gamecard prefs failed", { gamecardAdEnabled }, { gamecardAdEnabled = it })
    mirror(FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION, "write hide video detail app promotion prefs failed", { hideVideoDetailAppPromotion }, { hideVideoDetailAppPromotion = it })
    mirror(FeaturePreferences.HIDE_FOLLOW_UP_RECOMMEND, "write follow up recommend prefs failed", { hideFollowUpRecommend }, { hideFollowUpRecommend = it })
    mirror(HookEntry.PREF_MERCH_ENABLED, "write merch prefs failed", { merchAdEnabled }, { merchAdEnabled = it })
    mirror(HookEntry.PREF_ENABLED, "write paused page ad prefs failed", { adskipEnabled }, { adskipEnabled = it })
    mirror(HookEntry.PREF_BANNER_ENABLED, "write banner prefs failed", { bannerAdEnabled }, { bannerAdEnabled = it })
    mirror(HookEntry.PREF_FREE_COPY_ENABLED, "write free copy prefs failed", { freeCopyEnabled }, { freeCopyEnabled = it })
    mirror(HookEntry.PREF_FREE_COPY_DESC_ENABLED, "write free copy desc prefs failed", { freeCopyDescEnabled }, { freeCopyDescEnabled = it })
    mirror(FeaturePreferences.FREE_COPY_TITLE_ENABLED, "write free copy title prefs failed", { freeCopyTitleEnabled }, { freeCopyTitleEnabled = it })
    mirror(FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, "write player status bar prefs failed", { transparentPlayerStatusBar }, { transparentPlayerStatusBar = it })
    mirror(FeaturePreferences.BRAND_SPLASH_CUSTOM, "write brand splash custom prefs failed", { brandSplashCustom }, { brandSplashCustom = it })
    mirror(FeaturePreferences.SPLASH_AUTO_NIGHT, "write splash auto night prefs failed", { splashAutoNight }, { splashAutoNight = it })
    mirror(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, "write free copy auto light failed", { freeCopyAutoLight }, { freeCopyAutoLight = it })
    mirror(HookEntry.PREF_FREE_COPY_LIGHT_MODE, "write free copy light mode prefs failed", { freeCopyLightMode }, { freeCopyLightMode = it })
    mirror(HookEntry.PREF_ROAMING_COMPAT_ENABLED, "write roaming compat prefs failed", { roamingCompatEnabled }, { roamingCompatEnabled = it })
    mirror(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, "write predictive back prefs failed", { predictiveBackEnabled }, { predictiveBackEnabled = it })
    mirror(HookEntry.PREF_LOG_ENABLED, "write log enabled failed", { logEnabled }, { logEnabled = it })
    mirror(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, "write comment reply button prefs failed", { removeCommentReplyButton }, { removeCommentReplyButton = it })
    mirror(FeaturePreferences.HOST_UI_POWER_SAVING, "write host ui power saving prefs failed", { hostUiPowerSaving }, { hostUiPowerSaving = it })
}

internal fun MainActivity.executeModeledToggle(storageKey: String, requested: Boolean): Boolean {
    bindSettingsStateMirrors()
    return when (storageKey) {
        FeaturePreferences.PLAYER_DISABLE_LONG_PRESS -> {
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed.also {
                updatePlayerSpeedSummaries()
            }
        }
        FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS -> {
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed.also {
                updateAiStrongModeEntry()
            }
        }
        FeaturePreferences.HOST_BOTTOM_BAR_COMPACT -> {
            if (!hostBottomBarLiquidGlass) return hostBottomBarCompact
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
        }
        FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY -> {
            if (!hostBottomBarLiquidGlass) return hostBottomBarIconOnly
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
        }
        FeaturePreferences.HOST_MINE_GARB_DIM -> {
            if (!hostBottomBarLiquidGlass) return hostMineGarbDim
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
        }
        FeaturePreferences.HOST_BOTTOM_BAR_BADGE_ACCENT -> {
            if (!hostBottomBarLiquidGlass) return hostBottomBarBadgeAccent
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
        }
        FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD -> {
            if (programmaticSwitch) return blockComponentLibraryDownload
            val outcome = settingsState.persistRegisteredBoolean(storageKey, requested)!!
            if (!outcome.saved) toast(getString(R.string.block_component_library_download_save_failed))
            outcome.confirmed
        }
        FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
        FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
        FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
        FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED -> {
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
        }
        HookEntry.PREF_FREE_COPY_ENABLED,
        HookEntry.PREF_FREE_COPY_DESC_ENABLED -> {
            val previous = if (storageKey == HookEntry.PREF_FREE_COPY_ENABLED) freeCopyEnabled else freeCopyDescEnabled
            val outcome = settingsState.commitCustomBoolean(
                key = storageKey,
                previous = previous,
                requested = requested,
                failureMessage = if (storageKey == HookEntry.PREF_FREE_COPY_ENABLED) {
                    "write free copy prefs failed"
                } else {
                    "write free copy desc prefs failed"
                }
            ) {
                val nextComment = if (storageKey == HookEntry.PREF_FREE_COPY_ENABLED) requested else freeCopyEnabled
                val nextDesc = if (storageKey == HookEntry.PREF_FREE_COPY_DESC_ENABLED) requested else freeCopyDescEnabled
                val revision = System.currentTimeMillis().coerceAtLeast(1L)
                SettingWritePolicy.attemptBoolean(
                    preferencesProvider = { prefs() },
                    key = storageKey,
                    previous = previous,
                    requested = requested,
                    verifyReadback = true,
                    extraWrites = { putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision) }
                ).also { prefOutcome ->
                    prefOutcome.failure?.let { throw it }
                }
                check(
                    FreeCopyConfigStore.write(
                        applicationContext,
                        nextComment,
                        nextDesc,
                        revision
                    )
                ) { "atomic mirror write returned false" }
            }
            refreshCopyAppearanceOptions()
            outcome.confirmed
        }
        FeaturePreferences.FREE_COPY_TITLE_ENABLED -> {
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed.also {
                refreshCopyAppearanceOptions()
            }
        }
        HookEntry.PREF_FREE_COPY_AUTO_LIGHT -> {
            if (programmaticSwitch) return freeCopyAutoLight
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed.also {
                animateLightModeTip()
            }
        }
        HookEntry.PREF_ROAMING_COMPAT_ENABLED -> {
            val confirmed = settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed
            runCatching {
                val intent = Intent(RoamingCompatHook.ACTION_SET_ROAMING_COMPAT)
                    .setPackage(HookEntry.TARGET_PACKAGE)
                    .putExtra(RoamingCompatHook.EXTRA_ENABLED, confirmed)
                sendBroadcast(intent)
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "send roaming compat broadcast failed", t)
            }
            confirmed
        }
        HookEntry.PREF_PREDICTIVE_BACK_ENABLED -> {
            settingsState.persistRegisteredBoolean(storageKey, requested)!!.confirmed.also {
                applyPredictiveBack()
            }
        }
        FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS -> {
            settingsState.persistBoolean(
                key = storageKey,
                previous = hostBottomBarLiquidGlass,
                requested = requested,
                failureMessage = "write host bottom bar liquid glass prefs failed",
                extraWrites = {
                    putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL, hostBottomBarGlassAppearance.material.value)
                    putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER, hostBottomBarGlassAppearance.renderer.value)
                }
            ).confirmed
        }
        FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS -> {
            settingsState.persistBoolean(
                key = storageKey,
                previous = hostTopBarLiquidGlass,
                requested = requested,
                failureMessage = "write host top bar liquid glass prefs failed",
                extraWrites = {
                    putString(FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL, hostTopBarGlassAppearance.material.value)
                    putString(FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER, hostTopBarGlassAppearance.renderer.value)
                }
            ).confirmed
        }
        FeaturePreferences.PLAYER_UNLOCK_BACKGROUND,
        FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW,
        FeaturePreferences.PLAYER_UNLOCK_CAST -> {
            settingsState.persistBoolean(storageKey, !requested, requested, "write player capability prefs failed").confirmed
        }
        FeaturePreferences.SPONSORBLOCK_ENABLED,
        FeaturePreferences.SPONSORBLOCK_AUTOMATIC,
        FeaturePreferences.SPONSORBLOCK_STORY -> {
            settingsState.persistBoolean(storageKey, !requested, requested, "write sponsorblock prefs failed").confirmed
        }
        FeaturePreferences.PLAYER_FRAME_INTERPOLATION -> {
            settingsState.persistBoolean(storageKey, !requested, requested, "write frame interpolation prefs failed").confirmed
        }
        FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST -> {
            settingsState.persistBoolean(storageKey, !requested, requested, "write frame status toast prefs failed").confirmed
        }
        FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE -> {
            val store = checkNotNull(settingStore)
            val descriptor = store.descriptors.single { it.storageKey == storageKey }
            val savedChecked = store.state(descriptor.id).checked
            if (requested == savedChecked) savedChecked
            else settingsState.persistBoolean(
                key = storageKey,
                previous = savedChecked,
                requested = requested,
                failureMessage = "write frame force mode prefs failed",
                verifyReadback = true
            ).confirmed
        }
        FeaturePreferences.PLAYER_SUBTITLE_STYLE,
        FeaturePreferences.PLAYER_AI_SUBTITLE,
        FeaturePreferences.AI_INSIGHT_ENABLED,
        FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS,
        FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION,
        FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED,
        FeaturePreferences.HOST_UI_POWER_SAVING -> {
            val store = checkNotNull(settingStore)
            val descriptor = store.descriptors.single { it.storageKey == storageKey }
            val savedChecked = store.state(descriptor.id).checked
            if (requested == savedChecked) savedChecked
            else settingsState.persistBoolean(
                key = storageKey,
                previous = savedChecked,
                requested = requested,
                failureMessage = "write feature prefs failed"
            ).confirmed
        }
        else -> settingsState.persistRegisteredBoolean(storageKey, requested)?.confirmed
            ?: error("Setting requires its explicit page command: $storageKey")
    }
}

internal val modeledToggleKeys = setOf(
    FeaturePreferences.BRAND_SPLASH_SKIP,
    FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
    FeaturePreferences.HOME_FRAME_LEGACY_LOCK,
    FeaturePreferences.HOME_RECOMMEND_EARLY_LOAD,
    FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED,
    FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE,
    FeaturePreferences.FORCE_EXTERNAL_BROWSER,
    FeaturePreferences.SHOW_FULL_NUMBERS,
    FeaturePreferences.SHOW_BV_AS_AV,
    FeaturePreferences.COMMENT_CLASSIC_STYLE,
    FeaturePreferences.REPLY_TOPOLOGY_ENABLED,
    FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
    FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
    FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
    FeaturePreferences.PLAYER_DISABLE_LONG_PRESS,
    FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
    FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS,
    FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
    FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB,
    FeaturePreferences.COMMENT_FISH_FILTER_ENABLED,
    FeaturePreferences.COMMENT_FISH_STRONG_MODE,
    FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL,
    FeaturePreferences.NO_LOGIN_ENABLED,
    FeaturePreferences.INCOGNITO_ENABLED,
    FeaturePreferences.SKIP_GUEST_ONBOARDING,
    FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
    FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
    FeaturePreferences.HOST_MINE_GARB_DIM,
    FeaturePreferences.HOST_BOTTOM_BAR_BADGE_ACCENT,
    FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
    FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
    FeaturePreferences.PURIFY_SHARE_CONTENT,
    FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
    FeaturePreferences.WECHAT_SHARE_COMPAT,
    FeaturePreferences.PURIFY_SPLASH_ADS,
    FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT,
    FeaturePreferences.BLOCK_APP_UPDATE,
    FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD,
    FeaturePreferences.HIDE_COMMENT_SECTION,
    FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS,
    FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE,
    FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS,
    FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS,
    FeaturePreferences.REMOVE_COMMENT_QOE,
    FeaturePreferences.REMOVE_COMMENT_OPERATIONS,
    FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
    FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED,
    FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED,
    FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
    FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
    FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL,
    FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
    FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION,
    FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND,
    FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
    FeaturePreferences.REMOVE_STORY_ADS,
    FeaturePreferences.REMOVE_STORY_LIVE,
    FeaturePreferences.REMOVE_STORY_GAMES,
    FeaturePreferences.REMOVE_STORY_BANGUMI,
    FeaturePreferences.REMOVE_STORY_COURSES,
    FeaturePreferences.REMOVE_STORY_SHORT_DRAMA,
    FeaturePreferences.REMOVE_STORY_SHOPPING,
    FeaturePreferences.REMOVE_STORY_MOVIES,
    FeaturePreferences.REMOVE_STORY_DOCUMENTARIES,
    FeaturePreferences.REMOVE_STORY_TV,
    FeaturePreferences.REMOVE_STORY_VARIETY,
    FeaturePreferences.REMOVE_STORY_MUSIC,
    FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
    FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
    FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
    FeaturePreferences.HIDE_MINE_VIP,
    FeaturePreferences.KEEP_MINE_VIP_SPACE,
    FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND,
    FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
    FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
    FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
    FeaturePreferences.HIDE_DYNAMIC_CITY_TAB,
    FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB,
    FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
    FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
    FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
    FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
    FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
    FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS,
    FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
    FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
    FeaturePreferences.HIDE_HOME_GAME_MENU,
    FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD,
    FeaturePreferences.HIDE_LIVE_HOME_PROMOTION,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2,
    FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,
    FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
    HookEntry.PREF_GAMECARD_ENABLED,
    FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
    FeaturePreferences.HIDE_FOLLOW_UP_RECOMMEND,
    HookEntry.PREF_MERCH_ENABLED,
    HookEntry.PREF_ENABLED,
    HookEntry.PREF_BANNER_ENABLED,
    HookEntry.PREF_FREE_COPY_ENABLED,
    HookEntry.PREF_FREE_COPY_DESC_ENABLED,
    FeaturePreferences.FREE_COPY_TITLE_ENABLED,
    FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
    FeaturePreferences.BRAND_SPLASH_CUSTOM,
    FeaturePreferences.SPLASH_AUTO_NIGHT,
    HookEntry.PREF_FREE_COPY_AUTO_LIGHT,
    HookEntry.PREF_ROAMING_COMPAT_ENABLED,
    HookEntry.PREF_PREDICTIVE_BACK_ENABLED,
    FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION,
    FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED,
    HookEntry.PREF_LOG_ENABLED,
    FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON,
    FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
    FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
    FeaturePreferences.HOST_VIDEO_CARDS,
    FeaturePreferences.PLAYER_UNLOCK_BACKGROUND,
    FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW,
    FeaturePreferences.PLAYER_UNLOCK_CAST,
    FeaturePreferences.SPONSORBLOCK_ENABLED,
    FeaturePreferences.SPONSORBLOCK_AUTOMATIC,
    FeaturePreferences.SPONSORBLOCK_STORY,
    FeaturePreferences.AI_INSIGHT_ENABLED,
    FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS,
    FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
    FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST,
    FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE,
    FeaturePreferences.PLAYER_SUBTITLE_STYLE,
    FeaturePreferences.PLAYER_AI_SUBTITLE,
    FeaturePreferences.HOST_UI_POWER_SAVING
)

internal fun MainActivity.synchronizeSettingField(key: String, confirmed: Boolean) {
    bindSettingsStateMirrors()
    settingsState.syncMirror(key, confirmed)
}
