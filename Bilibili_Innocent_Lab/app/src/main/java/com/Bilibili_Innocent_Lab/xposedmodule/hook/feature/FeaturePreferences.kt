package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 新增功能的稳定配置键；旧功能键在完成迁移前仍保留在 HookEntry。 */
internal object FeaturePreferences {
    const val HIDE_HOME_GAME_MENU = "hide_home_game_menu"
    const val HIDE_HOME_SEARCH_DEFAULT_WORD = "hide_home_search_default_word"
    const val HIDE_MINE_VIP = "hide_mine_vip"
    const val BLOCK_APP_UPDATE = "block_app_update"
    const val HIDE_DYNAMIC_CITY_TAB = "hide_dynamic_city_tab"
    const val HIDE_DYNAMIC_SCHOOL_TAB = "hide_dynamic_school_tab"
}
