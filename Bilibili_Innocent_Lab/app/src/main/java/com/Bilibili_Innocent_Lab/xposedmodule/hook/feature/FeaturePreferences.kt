package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 新增功能的稳定配置键；旧功能键在完成迁移前仍保留在 HookEntry。 */
internal object FeaturePreferences {
    const val HIDE_VIDEO_DETAIL_APP_PROMOTION = "hide_video_detail_app_promotion"
    const val HIDE_HOME_GAME_MENU = "hide_home_game_menu"
    const val HIDE_HOME_SEARCH_DEFAULT_WORD = "hide_home_search_default_word"
    const val HOME_VERTICAL_OPEN_DETAIL = "home_vertical_open_detail"
    const val REMOVE_HOME_RECOMMEND_ADS = "remove_home_recommend_ads"
    const val REMOVE_HOME_RECOMMEND_PGC = "remove_home_recommend_pgc"
    const val REMOVE_HOME_RECOMMEND_SPECIAL_CARDS = "remove_home_recommend_special_cards"
    const val REMOVE_HOME_RECOMMEND_CM_V2 = "remove_home_recommend_cm_v2"
    const val REMOVE_HOME_RECOMMEND_PICTURES = "remove_home_recommend_pictures"
    const val REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS = "remove_home_recommend_game_promotions"
    const val HOME_RECOMMEND_TITLE_FILTER_ENABLED = "home_recommend_title_filter_enabled"
    const val HOME_RECOMMEND_TITLE_FILTER_KEYWORDS = "home_recommend_title_filter_keywords"

    /**
     * 分区 id 黑名单，分隔符拼起来的一条 Text。
     *
     * **不能用 `getStringSet`**：授权链只搬 Bool/Int/Text 三种 `SettingValue`
     * （`RemoteHookConfigContract.resolveSourceValues`），集合型偏好过不了那一层。
     * 解析见 [TidBlocklistCodec]。
     */
    const val HOME_RECOMMEND_BLOCKED_TIDS = "home_recommend_blocked_tids"
    const val REMOVE_HOME_RECOMMEND_LIVE = "remove_home_recommend_live"
    const val REMOVE_HOME_RECOMMEND_COURSES = "remove_home_recommend_courses"
    const val REMOVE_HOME_RECOMMEND_VERTICAL = "remove_home_recommend_vertical"
    const val REMOVE_HOME_RECOMMEND_LARGE = "remove_home_recommend_large"
    const val HOME_TAB_HIDDEN_RULES = "home_tab_hidden_rules"
    const val HOME_COMPONENT_HIDDEN_RULES = "home_component_hidden_rules"
    const val RECOMMEND_VIDEO_MIN_DURATION_SECONDS = "recommend_video_min_duration_seconds"
    const val RECOMMEND_VIDEO_MAX_DURATION_SECONDS = "recommend_video_max_duration_seconds"
    const val HIDE_MINE_VIP = "hide_mine_vip"
    const val KEEP_MINE_VIP_SPACE = "keep_mine_vip_space"
    const val BLOCK_APP_UPDATE = "block_app_update"
    const val HIDE_DYNAMIC_CITY_TAB = "hide_dynamic_city_tab"
    const val HIDE_DYNAMIC_SCHOOL_TAB = "hide_dynamic_school_tab"
    const val PREFER_DYNAMIC_VIDEO_TAB = "prefer_dynamic_video_tab"
    const val SHOW_FULL_NUMBERS = "show_full_numbers"
    const val HIDE_PLAYER_PORTRAIT_CONTROL = "hide_player_portrait_control"
    const val HIDE_PGC_AUTO_ACTIVITY_POPUP = "hide_pgc_auto_activity_popup"
    const val HIDE_PLAYER_INTERACTIVE_OVERLAYS = "hide_player_interactive_overlays"
    const val TRANSPARENT_PLAYER_STATUS_BAR = "transparent_player_status_bar"
    // 详情页模块净化（UGC 详情页 view.v1 协议面的顶层字段），四项各自独立、默认全关。
    const val REMOVE_DETAIL_HONOR = "remove_detail_honor"
    const val REMOVE_DETAIL_LIVE_ORDER = "remove_detail_live_order"
    const val REMOVE_DETAIL_UGC_SEASON = "remove_detail_ugc_season"
    const val REMOVE_DETAIL_UP_VIP_LABEL = "remove_detail_up_vip_label"
    const val REMOVE_DETAIL_TOPIC_TAGS = "remove_detail_topic_tags"
    // 这一项走 View 层（协议层做不到，见 DetailStaffFollowPolicy）。
    const val REMOVE_DETAIL_STAFF_FOLLOW = "remove_detail_staff_follow"
    const val REMOVE_DETAIL_HOT_BANNER = "remove_detail_hot_banner"
    const val REMOVE_RELATE_COMMERCIAL = "remove_relate_commercial"
    const val REMOVE_RELATE_GAME = "remove_relate_game"
    const val REMOVE_RELATE_LIVE = "remove_relate_live"
    const val REMOVE_RELATE_COURSE = "remove_relate_course"
    const val REMOVE_RELATE_SPECIAL = "remove_relate_special"
    const val VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED =
        "video_relate_matching_enhancement_enabled"
    const val VIDEO_RELATE_STRONG_MODE_ENABLED = "video_relate_strong_mode_enabled"
    const val VIDEO_RELATE_REASON_FILTER_ENABLED = "video_relate_reason_filter_enabled"
    const val VIDEO_RELATE_REASON_FILTER_KEYWORDS = "video_relate_reason_filter_keywords"

    /**
     * 详情页作者黑名单（UP 名或 mid）与标签黑名单，各一条 Text。
     *
     * 详情页协议里没有 tid（`view.v1.Relate` 实测 98 字段无此字段），所以"按分区过滤"
     * 在详情页退到这两档。判据是**整串相等**（[ExactRuleSetCodec]），不是 contains。
     */
    const val VIDEO_RELATE_BLOCKED_AUTHORS = "video_relate_blocked_authors"
    const val VIDEO_RELATE_BLOCKED_TAGS = "video_relate_blocked_tags"
    const val MINE_COMPONENT_HIDDEN_RULES = "mine_component_hidden_rules"
    /** “我的”页勾选隐藏的组件 id 集合（新 UI 勾选列表持久化，换行/逗号分隔的字符串）。 */
    const val MINE_COMPONENT_HIDDEN_IDS = "mine_component_hidden_ids"
    /** 新版勾选项的稳定选择键 JSON 数组；旧 id/标题规则继续作为兼容输入保留。 */
    const val MINE_COMPONENT_HIDDEN_SELECTORS = "mine_component_hidden_selectors"
    /** 宿主剪枝时写入的可屏蔽项快照 JSON（模块 UI 勾选列表数据源；仅宿主写、模块 App 读）。 */
    const val MINE_COMPONENT_SCAN_SNAPSHOT = "mine_component_scan_snapshot"
    const val REMOVE_STORY_ADS = "remove_story_ads"
    const val REMOVE_STORY_LIVE = "remove_story_live"
    const val REMOVE_STORY_GAMES = "remove_story_games"
    const val REMOVE_STORY_BANGUMI = "remove_story_bangumi"
    const val REMOVE_STORY_COURSES = "remove_story_courses"
    const val REMOVE_STORY_SHORT_DRAMA = "remove_story_short_drama"
    const val REMOVE_STORY_SHOPPING = "remove_story_shopping"
    const val REMOVE_STORY_MOVIES = "remove_story_movies"
    const val REMOVE_STORY_DOCUMENTARIES = "remove_story_documentaries"
    const val REMOVE_STORY_TV = "remove_story_tv"
    const val REMOVE_STORY_VARIETY = "remove_story_variety"
    const val REMOVE_STORY_MUSIC = "remove_story_music"
    const val BOTTOM_BAR_HIDDEN_RULES = "bottom_bar_hidden_rules"

    /** 勾选面板写入的选择器；与同名 *_HIDDEN_RULES 手填规则取并集，互不覆盖。 */
    const val BOTTOM_BAR_HIDDEN_SELECTORS = "bottom_bar_hidden_selectors"
    const val HOME_TAB_HIDDEN_SELECTORS = "home_tab_hidden_selectors"
    const val HOME_COMPONENT_HIDDEN_SELECTORS = "home_component_hidden_selectors"
    const val PLAYER_DEFAULT_QUALITY_QN = "player_default_quality_qn"
    const val PLAYER_UNLOCK_BACKGROUND = "player_unlock_background"
    const val PLAYER_UNLOCK_SMALL_WINDOW = "player_unlock_small_window"
    const val PLAYER_UNLOCK_CAST = "player_unlock_cast"
    const val PLAYER_DISABLE_LONG_PRESS = "player_disable_long_press"
    const val PLAYER_LONG_PRESS_SPEED_PERCENT = "player_long_press_speed_percent"
    const val PLAYER_DEFAULT_SPEED_PERCENT = "player_default_speed_percent"
    const val BLOCK_TEENAGERS_MODE_PROMPT = "block_teenagers_mode_prompt"
    const val REMOVE_COMMENT_SEARCH_LINKS = "remove_comment_search_links"
    const val REMOVE_COMMENT_EMPTY_GUIDE = "remove_comment_empty_guide"
    const val REMOVE_COMMENT_VOTE_WIDGETS = "remove_comment_vote_widgets"
    const val REMOVE_COMMENT_FOLLOW_BUTTONS = "remove_comment_follow_buttons"
    const val REMOVE_COMMENT_QOE = "remove_comment_qoe"
    const val REMOVE_COMMENT_OPERATIONS = "remove_comment_operations"
    const val BLOCK_COMMENT_QUICK_REPLY = "block_comment_quick_reply"
    const val HIDE_COMMENT_SECTION = "hide_comment_section"
    const val REPLY_TOPOLOGY_ENABLED = "reply_topology_enabled"
    const val COMMENT_KEYWORD_FILTER_ENABLED = "comment_keyword_filter_enabled"
    const val COMMENT_FILTER_KEYWORDS = "comment_filter_keywords"
    const val COMMENT_MIN_LEVEL_FILTER_ENABLED = "comment_min_level_filter_enabled"
    const val COMMENT_MIN_LEVEL = "comment_min_level"
    const val PURIFY_SPLASH_ADS = "purify_splash_ads"

    /** 整条只有 @ 某人、没有正文的评论；与关键词/等级过滤共用同一条列表边界。 */
    const val REMOVE_AT_ONLY_COMMENTS = "remove_at_only_comments"

    /** 按发布者过滤评论：规则行既可写 UID 也可写用户名，两者取并集。 */
    const val COMMENT_USER_FILTER_ENABLED = "comment_user_filter_enabled"
    const val COMMENT_USER_FILTER_RULES = "comment_user_filter_rules"

    /** 弹幕权重过滤：低于阈值的弹幕在 protobuf 边界删除。 */
    const val DANMAKU_WEIGHT_FILTER_ENABLED = "danmaku_weight_filter_enabled"
    const val DANMAKU_WEIGHT_FILTER_MINIMUM = "danmaku_weight_filter_minimum"

    /** 大会员渐变彩色弹幕（DmColorfulType.VipGradualColor）。 */
    const val REMOVE_VIP_COLORFUL_DANMAKU = "remove_vip_colorful_danmaku"

    /** 分享链接与分享文案里的推广/追踪查询参数。 */
    const val PURIFY_SHARE_CONTENT = "purify_share_content"

    /** 微信/QQ 小程序卡片降级为普通链接分享。 */
    const val SHARE_MINI_PROGRAM_DIRECT_LINK = "share_mini_program_direct_link"

    /** 站外链接改由系统浏览器打开，不再进入宿主内置 WebView。 */
    const val FORCE_EXTERNAL_BROWSER = "force_external_browser"

    /** 后台播放使用系统媒体控制样式通知。 */
    const val SYSTEM_MEDIA_NOTIFICATION = "system_media_notification"

    /** 开屏页背景跟随系统深色模式。 */
    const val SPLASH_AUTO_NIGHT = "splash_auto_night"

    /** 直播间上下滑动切换房间。 */
    const val BLOCK_LIVE_ROOM_SWITCH = "block_live_room_switch"

    /** 直播间双击由点赞改为暂停/继续播放。 */
    const val LIVE_ROOM_DOUBLE_TAP_PAUSE = "live_room_double_tap_pause"

    /** 视频号显示为 AV 号。 */
    const val SHOW_BV_AS_AV = "show_bv_as_av"

    /** 动态正文关键词过滤；命中的动态整条不显示。 */
    const val DYNAMIC_KEYWORD_FILTER_ENABLED = "dynamic_keyword_filter_enabled"
    const val DYNAMIC_FILTER_KEYWORDS = "dynamic_filter_keywords"

    /** 按发布者过滤动态：规则行既可写 UID 也可写用户名。 */
    const val DYNAMIC_AUTHOR_FILTER_ENABLED = "dynamic_author_filter_enabled"
    const val DYNAMIC_AUTHOR_FILTER_RULES = "dynamic_author_filter_rules"

    /** 带货与「UP 主推荐」附加卡。 */
    const val REMOVE_DYNAMIC_PROMOTIONS = "remove_dynamic_promotions"

    /** 未解锁的充电专属动态。 */
    const val REMOVE_DYNAMIC_CHARGE_ONLY = "remove_dynamic_charge_only"

    /** 动态页顶部最常访问整栏，包含两排 UP 条目和标题/更多入口。 */
    const val HIDE_DYNAMIC_FREQUENT_VISITS = "hide_dynamic_frequent_visits"

    /** 动态页顶部话题栏。 */
    const val HIDE_DYNAMIC_TOPIC_LIST = "hide_dynamic_topic_list"

    /** 动态页顶部 UP 栏中正在直播的条目。 */
    const val REMOVE_DYNAMIC_LIVE_UP_ENTRIES = "remove_dynamic_live_up_entries"

    /** 搜索结果中的商业广告卡与特殊运营卡。 */
    const val REMOVE_SEARCH_COMMERCIAL = "remove_search_commercial"
    /** 搜索首页热搜与搜索发现整区隐藏，保留本地历史。 */
    const val HIDE_SEARCH_HOME_RECOMMEND = "hide_search_home_recommend"

    /** 搜索结果标题关键词过滤（仅作用于视频卡）。 */
    const val SEARCH_KEYWORD_FILTER_ENABLED = "search_keyword_filter_enabled"
    const val SEARCH_FILTER_KEYWORDS = "search_filter_keywords"

    /** 按发布者过滤搜索结果（仅作用于视频卡）。 */
    const val SEARCH_AUTHOR_FILTER_ENABLED = "search_author_filter_enabled"
    const val SEARCH_AUTHOR_FILTER_RULES = "search_author_filter_rules"
}
