package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.toast

/** Extracted business operations; no source View or source setter is captured. */
internal fun MainActivity.executeModeledToggle(storageKey: String, requested: Boolean): Boolean {
    return when (storageKey) {
        FeaturePreferences.BRAND_SPLASH_SKIP -> {
            val checked = requested
            val outcome = SettingWritePolicy.attempt(brandSplashSkip, checked) {
                prefs().edit { putBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, checked) }
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write brand splash skip prefs failed", it) }
            brandSplashSkip = outcome.confirmed
            brandSplashSkip
        }
        FeaturePreferences.HOME_FRAME_LEGACY_LOCK -> {
            val checked = requested
            val outcome = SettingWritePolicy.attempt(homeFrameLegacyLock, checked) {
                prefs().edit { putBoolean(FeaturePreferences.HOME_FRAME_LEGACY_LOCK, checked) }
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write home frame legacy lock prefs failed", it) }
            homeFrameLegacyLock = outcome.confirmed
            homeFrameLegacyLock
        }
        FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED -> {
            val checked = requested
            val outcome = SettingWritePolicy.attempt(cloudKeyFreeze, checked) {
                prefs().edit { putBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, checked) }
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write cloud key freeze prefs failed", it) }
            cloudKeyFreeze = outcome.confirmed
            cloudKeyFreeze
        }
        FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE -> {
            val checked = requested
            val outcome = SettingWritePolicy.attempt(cloudKeyFreezeObserve, checked) {
                prefs().edit { putBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE, checked) }
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write cloud key freeze observe prefs failed", it) }
            cloudKeyFreezeObserve = outcome.confirmed
            cloudKeyFreezeObserve
        }
        FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION -> {
            val checked = requested
            systemMediaNotification = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write system media notification prefs failed",
                    throwable
                )
            }
            systemMediaNotification
        }
        FeaturePreferences.FORCE_EXTERNAL_BROWSER -> {
            val checked = requested
            forceExternalBrowser = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.FORCE_EXTERNAL_BROWSER,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write external browser prefs failed",
                    throwable
                )
            }
            forceExternalBrowser
        }
        FeaturePreferences.SHOW_FULL_NUMBERS -> {
            val isChecked = requested
            showFullNumbers = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SHOW_FULL_NUMBERS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write full number prefs failed",
                    t
                )
            }
            showFullNumbers
        }
        FeaturePreferences.SHOW_BV_AS_AV -> {
            val checked = requested
            showBvAsAv = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SHOW_BV_AS_AV,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write bv to av prefs failed",
                    throwable
                )
            }
            showBvAsAv
        }
        FeaturePreferences.COMMENT_CLASSIC_STYLE -> {
            val checked = requested
            commentClassicStyle = checked
            prefs().edit { putBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, checked) }
            commentClassicStyle
        }
        FeaturePreferences.REPLY_TOPOLOGY_ENABLED -> {
            val checked = requested
            replyTopologyEnabled = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.REPLY_TOPOLOGY_ENABLED,
                    checked
                )
            }
            replyTopologyEnabled
        }
        FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY -> {
            val isChecked = requested
            blockCommentQuickReply = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment quick reply prefs failed",
                    t
                )
            }
            blockCommentQuickReply
        }
        FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH -> {
            val checked = requested
            blockLiveRoomSwitch = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write live room switch prefs failed",
                    throwable
                )
            }
            blockLiveRoomSwitch
        }
        FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE -> {
            val checked = requested
            liveRoomDoubleTapPause = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write live room double tap prefs failed",
                    throwable
                )
            }
            liveRoomDoubleTapPause
        }
        FeaturePreferences.PLAYER_DISABLE_LONG_PRESS -> {
            val checked = requested
            playerDisableLongPress = checked
            runCatching {
                prefs().edit { putBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, checked) }
            }.onFailure {
                Log.e("BilibiliInnocentLab", "write player long press prefs failed", it)
            }
            updatePlayerSpeedSummaries()
            playerDisableLongPress
        }
        FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED -> {
            val checked = requested
            homeRecommendSectionPickEnabled = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
                    checked
                )
            }
            homeRecommendSectionPickEnabled
        }
        FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS -> {
            val isChecked = requested
            blockAiDeclaredVideos = isChecked
            updateAiStrongModeEntry()
            runCatching {
                prefs().edit {
                    putBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, isChecked)
                }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write ai declared videos prefs failed", t)
            }
            blockAiDeclaredVideos
        }
        FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL -> {
            val isChecked = requested
            homeVerticalOpenDetail = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write home vertical detail prefs failed",
                    t
                )
            }
            homeVerticalOpenDetail
        }
        FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB -> {
            val isChecked = requested
            preferDynamicVideoTab = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write preferred dynamic video prefs failed",
                    t
                )
            }
            preferDynamicVideoTab
        }
        FeaturePreferences.HOST_BOTTOM_BAR_COMPACT -> {
            val checked = requested
            if (!hostBottomBarLiquidGlass) {

                return hostBottomBarCompact
            }
            hostBottomBarCompact = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host bottom bar compact prefs failed",
                    throwable
                )
            }
            hostBottomBarCompact
        }
        FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY -> {
            val checked = requested
            if (!hostBottomBarLiquidGlass) {

                return hostBottomBarIconOnly
            }
            hostBottomBarIconOnly = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host bottom bar icon_only prefs failed",
                    throwable
                )
            }
            hostBottomBarIconOnly
        }
        FeaturePreferences.HOST_MINE_GARB_DIM -> {
            val checked = requested
            if (!hostBottomBarLiquidGlass) {

                return hostMineGarbDim
            }
            hostMineGarbDim = checked
            runCatching {
                prefs().edit { putBoolean(FeaturePreferences.HOST_MINE_GARB_DIM, checked) }
            }.onFailure { throwable ->
                Log.e("BilibiliInnocentLab", "write host mine garb dim prefs failed", throwable)
            }
            hostMineGarbDim
        }
        FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW -> {
            val checked = requested
            hostBottomBarTouchGlow = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host bottom bar touch glow prefs failed",
                    throwable
                )
            }
            hostBottomBarTouchGlow
        }
        FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW -> {
            val checked = requested
            hostTopBarTouchGlow = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host top bar touch glow prefs failed",
                    throwable
                )
            }
            hostTopBarTouchGlow
        }
        FeaturePreferences.PURIFY_SHARE_CONTENT -> {
            val checked = requested
            purifyShareContent = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.PURIFY_SHARE_CONTENT,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write share purify prefs failed",
                    throwable
                )
            }
            purifyShareContent
        }
        FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK -> {
            val checked = requested
            shareMiniProgramDirectLink = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write mini program share prefs failed",
                    throwable
                )
            }
            shareMiniProgramDirectLink
        }
        FeaturePreferences.PURIFY_SPLASH_ADS -> {
            val checked = requested
            purifySplashAds = checked
            prefs().edit {
                putBoolean(FeaturePreferences.PURIFY_SPLASH_ADS, checked)
            }
            purifySplashAds
        }
        FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT -> {
            val isChecked = requested
            blockTeenagersModePrompt = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write teenagers mode prefs failed",
                    t
                )
            }
            blockTeenagersModePrompt
        }
        FeaturePreferences.BLOCK_APP_UPDATE -> {
            val isChecked = requested
            blockAppUpdate = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_APP_UPDATE,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write block app update prefs failed",
                    t
                )
            }
            blockAppUpdate
        }
        FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD -> {
            val checked = requested
            if (programmaticSwitch) return blockComponentLibraryDownload
            val previous = blockComponentLibraryDownload
            val outcome = SettingWritePolicy.attempt(previous, checked) {
                val preferences = prefs()
                preferences.edit {
                    putBoolean(FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD, checked)
                }
                check(
                    preferences.getBoolean(
                        FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD,
                        !checked
                    ) == checked
                )
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write component library prefs failed", it) }
            if (!outcome.saved) {
                blockComponentLibraryDownload = outcome.confirmed
                toast(getString(R.string.block_component_library_download_save_failed))
                return outcome.confirmed
            }
            blockComponentLibraryDownload = checked
            blockComponentLibraryDownload
        }
        FeaturePreferences.HIDE_COMMENT_SECTION -> {
            val checked = requested
            hideCommentSection = checked
            prefs().edit {
                putBoolean(FeaturePreferences.HIDE_COMMENT_SECTION, checked)
            }
            hideCommentSection
        }
        FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS -> {
            val isChecked = requested
            removeCommentSearchLinks = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment search prefs failed",
                    t
                )
            }
            removeCommentSearchLinks
        }
        FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE -> {
            val isChecked = requested
            removeCommentEmptyGuide = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment empty guide prefs failed",
                    t
                )
            }
            removeCommentEmptyGuide
        }
        FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS -> {
            val isChecked = requested
            removeCommentVoteWidgets = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment vote prefs failed",
                    t
                )
            }
            removeCommentVoteWidgets
        }
        FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS -> {
            val isChecked = requested
            removeCommentFollowButtons = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment follow prefs failed",
                    t
                )
            }
            removeCommentFollowButtons
        }
        FeaturePreferences.REMOVE_COMMENT_QOE -> {
            val isChecked = requested
            removeCommentQoe = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_QOE,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment qoe prefs failed",
                    t
                )
            }
            removeCommentQoe
        }
        FeaturePreferences.REMOVE_COMMENT_OPERATIONS -> {
            val isChecked = requested
            removeCommentOperations = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_COMMENT_OPERATIONS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment operation prefs failed",
                    t
                )
            }
            removeCommentOperations
        }
        FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED -> {
            val checked = requested
            commentSemanticFilterEnabled = checked
            writeSemanticSwitch(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED, checked)
            commentSemanticFilterEnabled
        }
        FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED -> {
            val checked = requested
            commentKeywordFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment keyword filter prefs failed",
                    throwable
                )
            }
            commentKeywordFilterEnabled
        }
        FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED -> {
            val checked = requested
            commentMinLevelFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment minimum level filter prefs failed",
                    throwable
                )
            }
            commentMinLevelFilterEnabled
        }
        FeaturePreferences.REMOVE_AT_ONLY_COMMENTS -> {
            val checked = requested
            removeAtOnlyComments = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write at-only comment prefs failed",
                    throwable
                )
            }
            removeAtOnlyComments
        }
        FeaturePreferences.COMMENT_USER_FILTER_ENABLED -> {
            val checked = requested
            commentUserFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment author filter prefs failed",
                    throwable
                )
            }
            commentUserFilterEnabled
        }
        FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL -> {
            val isChecked = requested
            hidePlayerPortraitControl = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write player portrait prefs failed",
                    t
                )
            }
            hidePlayerPortraitControl
        }
        FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS -> {
            val isChecked = requested
            hidePlayerInteractiveOverlays = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write player interactive overlays prefs failed",
                    t
                )
            }
            hidePlayerInteractiveOverlays
        }
        FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION -> {
            val isChecked = requested
            hidePlayerPopupPromotion = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write player popup promotion prefs failed",
                    t
                )
            }
            hidePlayerPopupPromotion
        }
        FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND -> {
            val isChecked = requested
            hidePlayerEndPageRecommend = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write player end page recommend prefs failed",
                    t
                )
            }
            hidePlayerEndPageRecommend
        }
        FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP -> {
            val isChecked = requested
            hidePgcAutoActivityPopup = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write PGC auto activity popup prefs failed",
                    t
                )
            }
            hidePgcAutoActivityPopup
        }
        FeaturePreferences.REMOVE_STORY_ADS -> {
            val checked = requested
            removeStoryAds = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_ADS, checked)
            }
            removeStoryAds
        }
        FeaturePreferences.REMOVE_STORY_LIVE -> {
            val checked = requested
            removeStoryLive = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_LIVE, checked)
            }
            removeStoryLive
        }
        FeaturePreferences.REMOVE_STORY_GAMES -> {
            val checked = requested
            removeStoryGames = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_GAMES, checked)
            }
            removeStoryGames
        }
        FeaturePreferences.REMOVE_STORY_BANGUMI -> {
            val checked = requested
            removeStoryBangumi = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_BANGUMI, checked)
            }
            removeStoryBangumi
        }
        FeaturePreferences.REMOVE_STORY_COURSES -> {
            val checked = requested
            removeStoryCourses = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_COURSES, checked)
            }
            removeStoryCourses
        }
        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> {
            val checked = requested
            removeStoryShortDrama = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.REMOVE_STORY_SHORT_DRAMA,
                    checked
                )
            }
            removeStoryShortDrama
        }
        FeaturePreferences.REMOVE_STORY_SHOPPING -> {
            val checked = requested
            removeStoryShopping = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.REMOVE_STORY_SHOPPING,
                    checked
                )
            }
            removeStoryShopping
        }
        FeaturePreferences.REMOVE_STORY_MOVIES -> {
            val checked = requested
            removeStoryMovies = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_MOVIES, checked)
            }
            removeStoryMovies
        }
        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> {
            val checked = requested
            removeStoryDocumentaries = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.REMOVE_STORY_DOCUMENTARIES,
                    checked
                )
            }
            removeStoryDocumentaries
        }
        FeaturePreferences.REMOVE_STORY_TV -> {
            val checked = requested
            removeStoryTv = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_TV, checked)
            }
            removeStoryTv
        }
        FeaturePreferences.REMOVE_STORY_VARIETY -> {
            val checked = requested
            removeStoryVariety = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_VARIETY, checked)
            }
            removeStoryVariety
        }
        FeaturePreferences.REMOVE_STORY_MUSIC -> {
            val checked = requested
            removeStoryMusic = checked
            prefs().edit {
                putBoolean(FeaturePreferences.REMOVE_STORY_MUSIC, checked)
            }
            removeStoryMusic
        }
        FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED -> {
            val checked = requested
            danmakuWeightFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write danmaku weight filter prefs failed",
                    throwable
                )
            }
            danmakuWeightFilterEnabled
        }
        FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU -> {
            val checked = requested
            removeVipColorfulDanmaku = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write vip colorful danmaku prefs failed",
                    throwable
                )
            }
            removeVipColorfulDanmaku
        }
        FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED -> {
            val checked = requested
            danmakuSemanticFilterEnabled = checked
            writeSemanticSwitch(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED, checked)
            danmakuSemanticFilterEnabled
        }
        FeaturePreferences.HIDE_MINE_VIP -> {
            val isChecked = requested
            hideMineVip = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_MINE_VIP,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write mine vip prefs failed",
                    t
                )
            }
            hideMineVip
        }
        FeaturePreferences.KEEP_MINE_VIP_SPACE -> {
            val isChecked = requested
            keepMineVipSpace = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.KEEP_MINE_VIP_SPACE,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write mine vip space prefs failed",
                    t
                )
            }
            keepMineVipSpace
        }
        FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND -> {
            val checked = requested
            hideSearchHomeRecommend = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write search home recommend prefs failed",
                    throwable
                )
            }
            hideSearchHomeRecommend
        }
        FeaturePreferences.REMOVE_SEARCH_COMMERCIAL -> {
            val checked = requested
            removeSearchCommercial = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write search commercial filter prefs failed",
                    throwable
                )
            }
            removeSearchCommercial
        }
        FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED -> {
            val checked = requested
            searchKeywordFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write search keyword filter prefs failed",
                    throwable
                )
            }
            searchKeywordFilterEnabled
        }
        FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED -> {
            val checked = requested
            searchAuthorFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write search author filter prefs failed",
                    throwable
                )
            }
            searchAuthorFilterEnabled
        }
        FeaturePreferences.HIDE_DYNAMIC_CITY_TAB -> {
            val isChecked = requested
            hideDynamicCityTab = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_DYNAMIC_CITY_TAB,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic city tab prefs failed",
                    t
                )
            }
            hideDynamicCityTab
        }
        FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB -> {
            val isChecked = requested
            hideDynamicSchoolTab = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic school tab prefs failed",
                    t
                )
            }
            hideDynamicSchoolTab
        }
        FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED -> {
            val checked = requested
            dynamicSemanticFilterEnabled = checked
            writeSemanticSwitch(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED, checked)
            dynamicSemanticFilterEnabled
        }
        FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED -> {
            val checked = requested
            dynamicKeywordFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic keyword filter prefs failed",
                    throwable
                )
            }
            dynamicKeywordFilterEnabled
        }
        FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED -> {
            val checked = requested
            dynamicAuthorFilterEnabled = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic author filter prefs failed",
                    throwable
                )
            }
            dynamicAuthorFilterEnabled
        }
        FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS -> {
            val checked = requested
            removeDynamicPromotions = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic promotion filter prefs failed",
                    throwable
                )
            }
            removeDynamicPromotions
        }
        FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY -> {
            val checked = requested
            removeDynamicChargeOnly = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic charge only filter prefs failed",
                    throwable
                )
            }
            removeDynamicChargeOnly
        }
        FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS -> {
            val checked = requested
            hideDynamicFrequentVisits = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic frequent visits prefs failed",
                    throwable
                )
            }
            hideDynamicFrequentVisits
        }
        FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST -> {
            val checked = requested
            hideDynamicTopicList = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic topic list prefs failed",
                    throwable
                )
            }
            hideDynamicTopicList
        }
        FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES -> {
            val checked = requested
            removeDynamicLiveUpEntries = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write dynamic live up entries prefs failed",
                    throwable
                )
            }
            removeDynamicLiveUpEntries
        }
        FeaturePreferences.HIDE_HOME_GAME_MENU -> {
            val isChecked = requested
            hideHomeGameMenu = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_HOME_GAME_MENU,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write home game menu prefs failed",
                    t
                )
            }
            hideHomeGameMenu
        }
        FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD -> {
            val isChecked = requested
            hideHomeSearchDefaultWord = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write home search word prefs failed",
                    t
                )
            }
            hideHomeSearchDefaultWord
        }
        FeaturePreferences.HIDE_LIVE_HOME_PROMOTION -> {
            val checked = requested
            hideLiveHomePromotion = checked
            runCatching {
                prefs().edit { putBoolean(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, checked) }
            }.onFailure { throwable ->
                Log.e("BilibiliInnocentLab", "write live home promotion prefs failed", throwable)
            }
            hideLiveHomePromotion
        }
        FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2 -> {
            val isChecked = requested
            removeHomeRecommendCmV2 = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write home recommend cm_v2 prefs failed",
                    t
                )
            }
            removeHomeRecommendCmV2
        }
        FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED -> {
            val checked = requested
            videoSemanticFilterEnabled = checked
            writeSemanticSwitch(FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED, checked)
            videoSemanticFilterEnabled
        }
        FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED -> {
            val checked = requested
            homeRecommendTitleFilterEnabled = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                    checked
                )
            }
            homeRecommendTitleFilterEnabled
        }
        HookEntry.PREF_GAMECARD_ENABLED -> {
            val isChecked = requested
            gamecardAdEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_GAMECARD_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write gamecard prefs failed", t)
            }
            gamecardAdEnabled
        }
        FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION -> {
            val isChecked = requested
            hideVideoDetailAppPromotion = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write video detail app promotion prefs failed",
                    t
                )
            }
            hideVideoDetailAppPromotion
        }
        HookEntry.PREF_MERCH_ENABLED -> {
            val isChecked = requested
            merchAdEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_MERCH_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write merch prefs failed", t)
            }
            merchAdEnabled
        }
        HookEntry.PREF_ENABLED -> {
            val isChecked = requested
            adskipEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write paused page ad prefs failed", t)
            }
            adskipEnabled
        }
        HookEntry.PREF_BANNER_ENABLED -> {
            val isChecked = requested
            bannerAdEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_BANNER_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write banner prefs failed", t)
            }
            bannerAdEnabled
        }
        HookEntry.PREF_FREE_COPY_ENABLED -> {
            val isChecked = requested
            freeCopyEnabled = isChecked
            refreshCopyAppearanceOptions()
            runCatching {
                val revision = System.currentTimeMillis().coerceAtLeast(1L)
                prefs().edit {
                    putBoolean(HookEntry.PREF_FREE_COPY_ENABLED, isChecked)
                    putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision)
                }
                check(FreeCopyConfigStore.write(
                    applicationContext, freeCopyEnabled,
                    freeCopyDescEnabled, revision
                )) { "atomic mirror write returned false" }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write free copy prefs failed", t)
            }
            freeCopyEnabled
        }
        HookEntry.PREF_FREE_COPY_DESC_ENABLED -> {
            val isChecked = requested
            freeCopyDescEnabled = isChecked
            refreshCopyAppearanceOptions()
            runCatching {
                val revision = System.currentTimeMillis().coerceAtLeast(1L)
                prefs().edit {
                    putBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, isChecked)
                    putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision)
                }
                check(FreeCopyConfigStore.write(
                    applicationContext, freeCopyEnabled,
                    freeCopyDescEnabled, revision
                )) { "atomic mirror write returned false" }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write free copy desc prefs failed", t)
            }
            freeCopyDescEnabled
        }
        FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR -> {
            val isChecked = requested
            transparentPlayerStatusBar = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write player status bar prefs failed",
                    t
                )
            }
            transparentPlayerStatusBar
        }
        FeaturePreferences.BRAND_SPLASH_CUSTOM -> {
            val checked = requested
            val outcome = SettingWritePolicy.attempt(brandSplashCustom, checked) {
                prefs().edit { putBoolean(FeaturePreferences.BRAND_SPLASH_CUSTOM, checked) }
            }
            outcome.failure?.let { Log.e("BilibiliInnocentLab", "write brand splash custom prefs failed", it) }
            brandSplashCustom = outcome.confirmed
            brandSplashCustom
        }
        FeaturePreferences.SPLASH_AUTO_NIGHT -> {
            val checked = requested
            splashAutoNight = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SPLASH_AUTO_NIGHT,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write splash auto night prefs failed",
                    throwable
                )
            }
            splashAutoNight
        }
        HookEntry.PREF_FREE_COPY_AUTO_LIGHT -> {
            val isChecked = requested
            if (programmaticSwitch) return freeCopyAutoLight
            freeCopyAutoLight = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write free copy auto light failed", t)
            }
            // tip 动画切换（不重建界面）：淡出 → 换文本 → 淡入
            animateLightModeTip()
            freeCopyAutoLight
        }
        HookEntry.PREF_ROAMING_COMPAT_ENABLED -> {
            val isChecked = requested
            roamingCompatEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write roaming compat prefs failed", t)
            }
            // 同步给正在运行的 B 站进程（HookEntry 在 B 站进程内注册了接收器，
            // 收到后写入其自身缓存，下次启动即生效）。
            runCatching {
                val intent = Intent(RoamingCompatHook.ACTION_SET_ROAMING_COMPAT)
                    .setPackage(HookEntry.TARGET_PACKAGE)
                    .putExtra(RoamingCompatHook.EXTRA_ENABLED, isChecked)
                // 这里的 this 是开关 View，sendBroadcast 落到外层的 MainActivity 接收者上。
                sendBroadcast(intent)
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "send roaming compat broadcast failed", t)
            }
            roamingCompatEnabled
        }
        HookEntry.PREF_PREDICTIVE_BACK_ENABLED -> {
            val isChecked = requested
            predictiveBackEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write predictive back prefs failed", t)
            }
            // 立即作用于当前 window（无需重启界面）
            applyPredictiveBack()
            predictiveBackEnabled
        }
        HookEntry.PREF_LOG_ENABLED -> {
            val isChecked = requested
            logEnabled = isChecked
            runCatching {
                prefs().edit { putBoolean(HookEntry.PREF_LOG_ENABLED, isChecked) }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write log enabled failed", t)
            }
            logEnabled
        }
        FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON -> {
            val isChecked = requested
            removeCommentReplyButton = isChecked
            runCatching {
                prefs().edit { putBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, isChecked) }
            }.onFailure { throwable ->
                Log.e("BilibiliInnocentLab", "write comment reply button prefs failed", throwable)
            }
            removeCommentReplyButton
        }
        FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS -> {
            val checked = requested
            hostBottomBarLiquidGlass = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
                        checked
                    )
                    putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL, hostBottomBarGlassAppearance.material.value)
                    putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER, hostBottomBarGlassAppearance.renderer.value)
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host bottom bar liquid glass prefs failed",
                    throwable
                )
            }


            hostBottomBarLiquidGlass
        }
        FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS -> {
            val checked = requested
            hostTopBarLiquidGlass = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
                        checked
                    )
                    putString(FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL, hostTopBarGlassAppearance.material.value)
                    putString(FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER, hostTopBarGlassAppearance.renderer.value)
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write host top bar liquid glass prefs failed",
                    throwable
                )
            }

            hostTopBarLiquidGlass
        }
        FeaturePreferences.HOST_VIDEO_CARDS -> {
            val checked = requested
            hostVideoCards = checked
            runCatching { prefs().edit { putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, checked) } }
                .onFailure { Log.e("BilibiliInnocentLab", "write host video card prefs failed", it) }

            hostVideoCards
        }
        FeaturePreferences.PLAYER_UNLOCK_BACKGROUND, FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW, FeaturePreferences.PLAYER_UNLOCK_CAST -> {
            runCatching { prefs().edit { putBoolean(storageKey, requested) } }.onFailure {
                Log.e("BilibiliInnocentLab", "write player capability prefs failed", it)
            }
            requested
        }
        FeaturePreferences.SPONSORBLOCK_ENABLED, FeaturePreferences.SPONSORBLOCK_AUTOMATIC -> {
            runCatching { prefs().edit { putBoolean(storageKey, requested) } }.onFailure {
                Log.e("BilibiliInnocentLab", "write sponsorblock prefs failed", it)
            }
            requested
        }
        FeaturePreferences.PLAYER_FRAME_INTERPOLATION -> {
            runCatching { prefs().edit { putBoolean(storageKey, requested) } }.onFailure {
                Log.e("BilibiliInnocentLab", "write frame interpolation prefs failed", it)
            }
            requested
        }
        FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST -> {
            runCatching { prefs().edit { putBoolean(storageKey, requested) } }.onFailure {
                Log.e("BilibiliInnocentLab", "write frame status toast prefs failed", it)
            }
            requested
        }
        FeaturePreferences.PLAYER_SUBTITLE_STYLE, FeaturePreferences.PLAYER_AI_SUBTITLE -> {
            val store = checkNotNull(settingStore)
            val descriptor = store.descriptors.single { it.storageKey == storageKey }
            var savedChecked = store.state(descriptor.id).checked
            if (requested != savedChecked) {
                runCatching { prefs().edit { putBoolean(storageKey, requested) } }
                    .onSuccess { savedChecked = requested }
                    .onFailure { Log.e("BilibiliInnocentLab", "write subtitle prefs failed", it) }
            }
            savedChecked
        }
        else -> error("Setting requires its explicit page command: $storageKey")
    }
}

internal val modeledToggleKeys = setOf(
    FeaturePreferences.BRAND_SPLASH_SKIP,
    FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
    FeaturePreferences.HOME_FRAME_LEGACY_LOCK,
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
    FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
    FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
    FeaturePreferences.HOST_MINE_GARB_DIM,
    FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
    FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
    FeaturePreferences.PURIFY_SHARE_CONTENT,
    FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
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
    HookEntry.PREF_MERCH_ENABLED,
    HookEntry.PREF_ENABLED,
    HookEntry.PREF_BANNER_ENABLED,
    HookEntry.PREF_FREE_COPY_ENABLED,
    HookEntry.PREF_FREE_COPY_DESC_ENABLED,
    FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
    FeaturePreferences.BRAND_SPLASH_CUSTOM,
    FeaturePreferences.SPLASH_AUTO_NIGHT,
    HookEntry.PREF_FREE_COPY_AUTO_LIGHT,
    HookEntry.PREF_ROAMING_COMPAT_ENABLED,
    HookEntry.PREF_PREDICTIVE_BACK_ENABLED,
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
    FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
    FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST,
    FeaturePreferences.PLAYER_SUBTITLE_STYLE,
    FeaturePreferences.PLAYER_AI_SUBTITLE
)

internal fun MainActivity.synchronizeSettingField(key: String, confirmed: Boolean) {
    when (key) {
        FeaturePreferences.BRAND_SPLASH_SKIP -> brandSplashSkip = confirmed
        FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION -> systemMediaNotification = confirmed
        FeaturePreferences.HOME_FRAME_LEGACY_LOCK -> homeFrameLegacyLock = confirmed
        FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED -> cloudKeyFreeze = confirmed
        FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE -> cloudKeyFreezeObserve = confirmed
        FeaturePreferences.FORCE_EXTERNAL_BROWSER -> forceExternalBrowser = confirmed
        FeaturePreferences.SHOW_FULL_NUMBERS -> showFullNumbers = confirmed
        FeaturePreferences.SHOW_BV_AS_AV -> showBvAsAv = confirmed
        FeaturePreferences.COMMENT_CLASSIC_STYLE -> commentClassicStyle = confirmed
        FeaturePreferences.REPLY_TOPOLOGY_ENABLED -> replyTopologyEnabled = confirmed
        FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY -> blockCommentQuickReply = confirmed
        FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH -> blockLiveRoomSwitch = confirmed
        FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE -> liveRoomDoubleTapPause = confirmed
        FeaturePreferences.PLAYER_DISABLE_LONG_PRESS -> playerDisableLongPress = confirmed
        FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED -> homeRecommendSectionPickEnabled = confirmed
        FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS -> blockAiDeclaredVideos = confirmed
        FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL -> homeVerticalOpenDetail = confirmed
        FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB -> preferDynamicVideoTab = confirmed
        FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS -> hostBottomBarLiquidGlass = confirmed
        FeaturePreferences.HOST_BOTTOM_BAR_COMPACT -> hostBottomBarCompact = confirmed
        FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY -> hostBottomBarIconOnly = confirmed
        FeaturePreferences.HOST_MINE_GARB_DIM -> hostMineGarbDim = confirmed
        FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW -> hostBottomBarTouchGlow = confirmed
        FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS -> hostTopBarLiquidGlass = confirmed
        FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW -> hostTopBarTouchGlow = confirmed
        FeaturePreferences.HOST_VIDEO_CARDS -> hostVideoCards = confirmed
        FeaturePreferences.PURIFY_SHARE_CONTENT -> purifyShareContent = confirmed
        FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK -> shareMiniProgramDirectLink = confirmed
        FeaturePreferences.PURIFY_SPLASH_ADS -> purifySplashAds = confirmed
        FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT -> blockTeenagersModePrompt = confirmed
        FeaturePreferences.BLOCK_APP_UPDATE -> blockAppUpdate = confirmed
        FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD -> blockComponentLibraryDownload = confirmed
        FeaturePreferences.HIDE_COMMENT_SECTION -> hideCommentSection = confirmed
        FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS -> removeCommentSearchLinks = confirmed
        FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE -> removeCommentEmptyGuide = confirmed
        FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS -> removeCommentVoteWidgets = confirmed
        FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS -> removeCommentFollowButtons = confirmed
        FeaturePreferences.REMOVE_COMMENT_QOE -> removeCommentQoe = confirmed
        FeaturePreferences.REMOVE_COMMENT_OPERATIONS -> removeCommentOperations = confirmed
        FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED -> commentSemanticFilterEnabled = confirmed
        FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED -> commentKeywordFilterEnabled = confirmed
        FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED -> commentMinLevelFilterEnabled = confirmed
        FeaturePreferences.REMOVE_AT_ONLY_COMMENTS -> removeAtOnlyComments = confirmed
        FeaturePreferences.COMMENT_USER_FILTER_ENABLED -> commentUserFilterEnabled = confirmed
        FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL -> hidePlayerPortraitControl = confirmed
        FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS -> hidePlayerInteractiveOverlays = confirmed
        FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION -> hidePlayerPopupPromotion = confirmed
        FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND -> hidePlayerEndPageRecommend = confirmed
        FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP -> hidePgcAutoActivityPopup = confirmed
        FeaturePreferences.REMOVE_STORY_ADS -> removeStoryAds = confirmed
        FeaturePreferences.REMOVE_STORY_LIVE -> removeStoryLive = confirmed
        FeaturePreferences.REMOVE_STORY_GAMES -> removeStoryGames = confirmed
        FeaturePreferences.REMOVE_STORY_BANGUMI -> removeStoryBangumi = confirmed
        FeaturePreferences.REMOVE_STORY_COURSES -> removeStoryCourses = confirmed
        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> removeStoryShortDrama = confirmed
        FeaturePreferences.REMOVE_STORY_SHOPPING -> removeStoryShopping = confirmed
        FeaturePreferences.REMOVE_STORY_MOVIES -> removeStoryMovies = confirmed
        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> removeStoryDocumentaries = confirmed
        FeaturePreferences.REMOVE_STORY_TV -> removeStoryTv = confirmed
        FeaturePreferences.REMOVE_STORY_VARIETY -> removeStoryVariety = confirmed
        FeaturePreferences.REMOVE_STORY_MUSIC -> removeStoryMusic = confirmed
        FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED -> danmakuWeightFilterEnabled = confirmed
        FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU -> removeVipColorfulDanmaku = confirmed
        FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED -> danmakuSemanticFilterEnabled = confirmed
        FeaturePreferences.HIDE_MINE_VIP -> hideMineVip = confirmed
        FeaturePreferences.KEEP_MINE_VIP_SPACE -> keepMineVipSpace = confirmed
        FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND -> hideSearchHomeRecommend = confirmed
        FeaturePreferences.REMOVE_SEARCH_COMMERCIAL -> removeSearchCommercial = confirmed
        FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED -> searchKeywordFilterEnabled = confirmed
        FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED -> searchAuthorFilterEnabled = confirmed
        FeaturePreferences.HIDE_DYNAMIC_CITY_TAB -> hideDynamicCityTab = confirmed
        FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB -> hideDynamicSchoolTab = confirmed
        FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED -> dynamicSemanticFilterEnabled = confirmed
        FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED -> dynamicKeywordFilterEnabled = confirmed
        FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED -> dynamicAuthorFilterEnabled = confirmed
        FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS -> removeDynamicPromotions = confirmed
        FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY -> removeDynamicChargeOnly = confirmed
        FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS -> hideDynamicFrequentVisits = confirmed
        FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST -> hideDynamicTopicList = confirmed
        FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES -> removeDynamicLiveUpEntries = confirmed
        FeaturePreferences.HIDE_HOME_GAME_MENU -> hideHomeGameMenu = confirmed
        FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD -> hideHomeSearchDefaultWord = confirmed
        FeaturePreferences.HIDE_LIVE_HOME_PROMOTION -> hideLiveHomePromotion = confirmed
        FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2 -> removeHomeRecommendCmV2 = confirmed
        FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED -> videoSemanticFilterEnabled = confirmed
        FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED -> homeRecommendTitleFilterEnabled = confirmed
        HookEntry.PREF_GAMECARD_ENABLED -> gamecardAdEnabled = confirmed
        FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION -> hideVideoDetailAppPromotion = confirmed
        HookEntry.PREF_MERCH_ENABLED -> merchAdEnabled = confirmed
        HookEntry.PREF_ENABLED -> adskipEnabled = confirmed
        HookEntry.PREF_BANNER_ENABLED -> bannerAdEnabled = confirmed
        HookEntry.PREF_FREE_COPY_ENABLED -> freeCopyEnabled = confirmed
        HookEntry.PREF_FREE_COPY_DESC_ENABLED -> freeCopyDescEnabled = confirmed
        FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR -> transparentPlayerStatusBar = confirmed
        FeaturePreferences.BRAND_SPLASH_CUSTOM -> brandSplashCustom = confirmed
        FeaturePreferences.SPLASH_AUTO_NIGHT -> splashAutoNight = confirmed
        HookEntry.PREF_FREE_COPY_AUTO_LIGHT -> freeCopyAutoLight = confirmed
        HookEntry.PREF_FREE_COPY_LIGHT_MODE -> freeCopyLightMode = confirmed
        HookEntry.PREF_ROAMING_COMPAT_ENABLED -> roamingCompatEnabled = confirmed
        HookEntry.PREF_PREDICTIVE_BACK_ENABLED -> predictiveBackEnabled = confirmed
        HookEntry.PREF_LOG_ENABLED -> logEnabled = confirmed
        FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON -> removeCommentReplyButton = confirmed
    }
}
