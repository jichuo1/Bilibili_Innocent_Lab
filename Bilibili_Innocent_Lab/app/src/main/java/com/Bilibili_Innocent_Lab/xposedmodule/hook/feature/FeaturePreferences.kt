package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 新增功能的稳定配置键；旧功能键在完成迁移前仍保留在 HookEntry。 */
internal object FeaturePreferences {
    const val PLAYER_SUBTITLE_STYLE = "player_subtitle_style"
    const val PLAYER_AI_SUBTITLE = "player_ai_subtitle"
    const val HIDE_VIDEO_DETAIL_APP_PROMOTION = "hide_video_detail_app_promotion"
    const val HIDE_LIVE_HOME_PROMOTION = "hide_live_home_promotion"
    const val HIDE_HOME_GAME_MENU = "hide_home_game_menu"
    const val HIDE_HOME_SEARCH_DEFAULT_WORD = "hide_home_search_default_word"
    const val HOME_VERTICAL_OPEN_DETAIL = "home_vertical_open_detail"
    const val REMOVE_HOME_RECOMMEND_ADS = "remove_home_recommend_ads"
    const val REMOVE_HOME_RECOMMEND_PGC = "remove_home_recommend_pgc"
    const val REMOVE_HOME_RECOMMEND_COMIC = "remove_home_recommend_comic"
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

    /**
     * 是否劫持 B 站三点面板的"分区/频道"项。**默认关**：它改变的是宿主界面的行为，
     * 要由用户显式开启。开启后点一次当场生效（内存），长期生效仍需在模块设置里确认。
     */
    const val HOME_RECOMMEND_SECTION_PICK_ENABLED = "home_recommend_section_pick_enabled"

    /**
     * 首页推荐的 UP 主黑名单；空串就是整个维度关闭。
     *
     * 与详情页那档（`VIDEO_RELATE_BLOCKED_AUTHORS`）判据一致：整串相等，
     * 同时承认 UP 名与 mid，所以一个设置项就够。解析见 [ExactRuleSetCodec]。
     *
     * **不能用 `getStringSet`**，原因同 [HOME_RECOMMEND_BLOCKED_TIDS]。
     */
    const val HOME_RECOMMEND_BLOCKED_AUTHORS = "home_recommend_blocked_authors"

    /**
     * 「自动确认新增屏蔽标签」。
     *
     * **纯模块 App 行为，宿主一次都不读**：它只决定反馈面板里记下的点选要不要
     * 自动并入上面两份名单。放在这里、并登记进 `SettingsCatalog`，是为了让它能被
     * 设置备份带走（代价是它会跟着授权文档一起发布给宿主，宿主收下但不使用）。
     * 同一张面板的 `recommendation_feedback_reviewed_events` 是**处理状态**不是设置，
     * 继续留在本地、不进目录。
     */
    const val HOME_RECOMMEND_FEEDBACK_AUTO_CONFIRM = "recommendation_feedback_auto_confirm"

    /**
     * 屏蔽带「含AI生成内容」创作声明的视频。**默认关**。
     *
     * 一个开关管三处：详情页命中声明即改写 `ViewReply.ecode` 让宿主自己跳到补位视频；
     * 首页推荐按 `qn_feature.creation_tags` 的 `aigc` 标签提前删卡；
     * 相关推荐按本进程已确认的 aid 删卡。判据见 `AiDeclaredVideoPolicy`。
     */
    const val BLOCK_AI_DECLARED_VIDEOS = "block_ai_declared_videos"

    /**
     * 强力模式 · 屏蔽发布者（键名沿用最初的「强力模式」开关，已开启的用户不受影响）：
     * 详情页确认声明后，把发布者记进「屏蔽 UP」的点选观测面。
     *
     * 宿主侧只写本进程的 [AuthorPickSession] 与观测快照，**不写配置**；
     * 长期名单仍由模块 App 在前台时并入 [HOME_RECOMMEND_BLOCKED_AUTHORS]，
     * 且这一类来源不需要打开「自动确认」也会被并入。只在 [BLOCK_AI_DECLARED_VIDEOS] 开着时生效。
     */
    const val BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE = "block_ai_declared_videos_strong_mode"

    /**
     * 强力模式 · 获取 access_key：打开详情页后在后台预检首屏推荐（[AiRelatePrechecker]）。
     * 实际生效 = 本项 且 [BILI_ACCESS_KEY_AUTHORIZED]（通用授权）；只在 [BLOCK_AI_DECLARED_VIDEOS] 开着时有意义。
     */
    const val BLOCK_AI_DECLARED_VIDEOS_PRECHECK = "block_ai_declared_videos_precheck"

    /**
     * 实验性兼容：允许模块在哔哩哔哩进程内读取当前登录账号的 access_key。**默认关**，打开前必须二次确认风险。
     * 这是**通用授权**：任何需要账号身份的功能都以它为前提（读取经 [BiliAccessKeyProbe]），并在说明里注明。
     *
     * 令牌原文只在宿主进程内存里读、用完即弃：不写盘、不进日志/诊断/回执/备份，也不离开宿主进程。
     * [BLOCK_AI_DECLARED_VIDEOS_PRECHECK] 以它为前提（推荐预检的实际生效条件 = 预检开 且 本项已授权）。
     * 备份恢复为 MANUAL：换机导入备份不会悄悄重新授权。
     */
    const val BILI_ACCESS_KEY_AUTHORIZED = "bili_access_key_authorized"

    /**
     * 要拦截下载的组件库资源池。
     *
     * 与其余四个勾选面同构：`_SELECTORS` 是面板勾出来的，`_RULES` 是手填的，
     * 两者**取并集**。手填这条对本功能尤其有用——某个池本次会话没被宿主请求过就不会
     * 出现在扫描快照里，只能手打池名。
     */
    const val COMPONENT_POOL_BLOCKED_SELECTORS = "component_pool_blocked_selectors"
    const val COMPONENT_POOL_BLOCKED_RULES = "component_pool_blocked_rules"
    const val REMOVE_HOME_RECOMMEND_LIVE = "remove_home_recommend_live"
    const val REMOVE_HOME_RECOMMEND_COURSES = "remove_home_recommend_courses"
    const val REMOVE_HOME_RECOMMEND_VERTICAL = "remove_home_recommend_vertical"
    const val REMOVE_HOME_RECOMMEND_LARGE = "remove_home_recommend_large"
    const val HOME_TAB_HIDDEN_RULES = "home_tab_hidden_rules"
    const val HOME_COMPONENT_HIDDEN_RULES = "home_component_hidden_rules"
    const val RECOMMEND_VIDEO_MIN_DURATION_SECONDS = "recommend_video_min_duration_seconds"
    const val RECOMMEND_VIDEO_MAX_DURATION_SECONDS = "recommend_video_max_duration_seconds"
    const val RECOMMEND_VIDEO_MIN_PLAY_COUNT = "recommend_video_min_play_count"
    const val RECOMMEND_VIDEO_MAX_PLAY_COUNT = "recommend_video_max_play_count"
    /** 发布时间规则文本，编码见 VideoPublishTimeRule；空串为关闭。 */
    const val RECOMMEND_VIDEO_PUBLISH_TIME_RULE = "recommend_video_publish_time_rule"
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
    const val HIDE_PLAYER_END_PAGE_RECOMMEND = "hide_player_end_page_recommend"
    const val HIDE_PLAYER_POPUP_PROMOTION = "hide_player_popup_promotion"
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
    /** Story 竖屏流右侧互动图标，每个图标单独开关，见 StoryActionIcon。 */
    const val HIDE_STORY_ACTION_LIKE = "hide_story_action_like"
    const val HIDE_STORY_ACTION_COMMENT = "hide_story_action_comment"
    const val HIDE_STORY_ACTION_COIN = "hide_story_action_coin"
    const val HIDE_STORY_ACTION_FAVORITE = "hide_story_action_favorite"
    const val HIDE_STORY_ACTION_SHARE = "hide_story_action_share"
    const val HIDE_STORY_ACTION_DANMAKU_TOGGLE = "hide_story_action_danmaku_toggle"
    const val BOTTOM_BAR_HIDDEN_RULES = "bottom_bar_hidden_rules"

    /** 勾选面板写入的选择器；与同名 *_HIDDEN_RULES 手填规则取并集，互不覆盖。 */
    const val BOTTOM_BAR_HIDDEN_SELECTORS = "bottom_bar_hidden_selectors"
    const val HOST_BOTTOM_BAR_LIQUID_GLASS = "host_bottom_bar_liquid_glass"
    const val HOST_BOTTOM_BAR_GLASS_MATERIAL = "host_bottom_bar_glass_material"
    const val HOST_BOTTOM_BAR_GLASS_RENDERER = "host_bottom_bar_glass_renderer"
    const val HOST_BOTTOM_BAR_COMPACT = "host_bottom_bar_compact"
    const val HOST_BOTTOM_BAR_ICON_ONLY = "host_bottom_bar_icon_only"
    const val HOST_MINE_GARB_DIM = "host_mine_garb_dim"
    const val HOST_BOTTOM_BAR_TOUCH_GLOW = "host_bottom_bar_touch_glow"
    const val HOST_VIDEO_CARDS = "host_video_cards"
    // 仅供已发布的布尔设置迁移；新界面与运行时只使用 HOST_CARD_MATERIAL。
    const val HOST_CARD_ACRYLIC = "host_card_acrylic"
    const val HOST_CARD_MATERIAL = "host_card_material"
    const val HOST_VIDEO_CARD_RADIUS_DP = "host_video_card_radius_dp"
    const val HOST_HOME_CARD_HORIZONTAL_DP = "host_home_card_horizontal_dp"
    const val HOST_HOME_CARD_VERTICAL_DP = "host_home_card_vertical_dp"
    const val HOST_BACKGROUND_PRESET = "host_background_preset"
    const val HOST_BACKGROUND_BLUR = "host_background_blur"
    const val HOST_BACKGROUND_SATURATION = "host_background_saturation"
    const val HOST_BACKGROUND_VEIL = "host_background_veil"
    const val HOST_BACKGROUND_ASSET = "host_background_asset"
    const val HOST_TOP_BAR_LIQUID_GLASS = "host_top_bar_liquid_glass"
    const val HOST_TOP_BAR_GLASS_MATERIAL = "host_top_bar_glass_material"
    const val HOST_TOP_BAR_GLASS_RENDERER = "host_top_bar_glass_renderer"
    const val HOST_TOP_BAR_TOUCH_GLOW = "host_top_bar_touch_glow"
    const val HOME_TAB_HIDDEN_SELECTORS = "home_tab_hidden_selectors"
    const val HOME_COMPONENT_HIDDEN_SELECTORS = "home_component_hidden_selectors"
    const val PLAYER_DEFAULT_QUALITY_QN = "player_default_quality_qn"
    const val PLAYER_CODEC_PREFERENCE = "player_codec_preference"
    /** 主播放器插帧（≤720p）；仅当 FrameInterpolationEntryGate 放行时才生效与显示。 */
    const val PLAYER_FRAME_INTERPOLATION = "player_frame_interpolation"
    /** 补帧帧率上限（60/90/120/144/165/185）；实际目标还受系统实际刷新与本机能力约束。 */
    const val PLAYER_FRAME_INTERPOLATION_MAX_FPS = "player_frame_interpolation_max_fps"
    /** 每次播放视频时提示插帧是否生效与补帧目标帧率（默认关）。 */
    const val PLAYER_FRAME_INTERPOLATION_STATUS_TOAST = "player_frame_interpolation_status_toast"
    /** 经用户二次确认后跳过插帧自身的温度暂停；默认关。 */
    const val PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL = "player_frame_interpolation_disable_thermal_control"
    const val PLAYER_DECODE_MODE = "player_decode_mode"
    const val PLAYER_BUFFER_MODE = "player_buffer_mode"
    const val PLAYER_BUFFER_SECONDS = "player_buffer_seconds"
    const val PLAYER_BUFFER_MEMORY_MB = "player_buffer_memory_mb"
    const val PLAYER_BUFFER_DISK_MB = "player_buffer_disk_mb"
    const val PLAYER_BUFFER_WIFI_ONLY = "player_buffer_wifi_only"
    const val BLOCK_COMPONENT_LIBRARY_DOWNLOAD = "block_component_library_download"
    const val PLAYER_UNLOCK_BACKGROUND = "player_unlock_background"
    const val PLAYER_UNLOCK_SMALL_WINDOW = "player_unlock_small_window"
    const val PLAYER_UNLOCK_CAST = "player_unlock_cast"
    const val PLAYER_DISABLE_LONG_PRESS = "player_disable_long_press"
    const val PLAYER_LONG_PRESS_SPEED_PERCENT = "player_long_press_speed_percent"
    const val PLAYER_DEFAULT_SPEED_PERCENT = "player_default_speed_percent"
    const val PLAYER_SPEED_MENU_PERCENTS = "player_speed_menu_percents"
    const val SPONSORBLOCK_ENABLED = "sponsorblock_enabled"
    const val SPONSORBLOCK_AUTOMATIC = "sponsorblock_automatic"
    const val BLOCK_TEENAGERS_MODE_PROMPT = "block_teenagers_mode_prompt"
    const val REMOVE_COMMENT_SEARCH_LINKS = "remove_comment_search_links"
    const val REMOVE_COMMENT_EMPTY_GUIDE = "remove_comment_empty_guide"
    const val REMOVE_COMMENT_VOTE_WIDGETS = "remove_comment_vote_widgets"
    const val REMOVE_COMMENT_FOLLOW_BUTTONS = "remove_comment_follow_buttons"
    const val REMOVE_COMMENT_QOE = "remove_comment_qoe"
    const val REMOVE_COMMENT_OPERATIONS = "remove_comment_operations"
    const val REMOVE_COMMENT_REPLY_BUTTON = "remove_comment_reply_button"
    const val BLOCK_COMMENT_QUICK_REPLY = "block_comment_quick_reply"
    const val HIDE_COMMENT_SECTION = "hide_comment_section"
    const val COMMENT_CLASSIC_STYLE = "comment_classic_style"
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

    /** 固定使用旧版首页框架，屏蔽云控把首页切到新版框架。 */
    const val HOME_FRAME_LEGACY_LOCK = "home_frame_legacy_lock"

    /** 开屏页背景跟随系统深色模式。 */
    const val SPLASH_AUTO_NIGHT = "splash_auto_night"
    const val BRAND_SPLASH_SKIP = "brand_splash_skip"
    const val BRAND_SPLASH_CUSTOM = "brand_splash_custom"

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

    /** 智能过滤动态：用 JEV 按语义判断抽奖、带货、引战；配置在实验性功能 → 兼容。 */
    const val DYNAMIC_SEMANTIC_FILTER_ENABLED = "dynamic_semantic_filter_enabled"

    /** 各过滤面勾选的屏蔽类型（逗号分隔的预设 id，见 `SemanticPresets`）。 */
    const val DYNAMIC_SEMANTIC_FILTER_RULES = "dynamic_semantic_filter_rules"

    /** 智能过滤弹幕 / 评论 / 推荐视频（首页推荐与相关推荐共用）。 */
    const val DANMAKU_SEMANTIC_FILTER_ENABLED = "danmaku_semantic_filter_enabled"
    const val DANMAKU_SEMANTIC_FILTER_RULES = "danmaku_semantic_filter_rules"
    const val COMMENT_SEMANTIC_FILTER_ENABLED = "comment_semantic_filter_enabled"
    const val COMMENT_SEMANTIC_FILTER_RULES = "comment_semantic_filter_rules"
    const val VIDEO_SEMANTIC_FILTER_ENABLED = "video_semantic_filter_enabled"
    const val VIDEO_SEMANTIC_FILTER_RULES = "video_semantic_filter_rules"

    /** JEV 接口地址（空 = 官方）；API Key 不在这里，见 `RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY`。 */
    const val SEMANTIC_JEV_ENDPOINT = "semantic_jev_endpoint"

    /** JEV 灵敏度：low / medium / high。 */
    const val SEMANTIC_JEV_SENSITIVITY = "semantic_jev_sensitivity"

    /** 判定后端：jev（结构化接口）/ openai（OpenAI 兼容聊天接口）/ cloudflare（Workers AI）。 */
    const val SEMANTIC_JEV_PROVIDER = "semantic_jev_provider"

    /** 模型名；JEV 留空为 jev-latest，OpenAI 兼容必填（如 deepseek-chat、glm-4-flash）。 */
    const val SEMANTIC_JEV_MODEL = "semantic_jev_model"

    /** 用户写的判定口径（空 = 默认）；防注入前缀与输出格式由模块固定，见 SemanticGuidance。 */
    const val SEMANTIC_JEV_GUIDANCE = "semantic_jev_guidance"

    /** 2–8 号判定来源：接口类型 / 地址 / 模型（Key 是运行时键，见 RemoteHookConfigContract）。1 号沿用 SEMANTIC_JEV_*。 */
    const val SEMANTIC_SOURCE_2_PROVIDER = "semantic_source_2_provider"
    const val SEMANTIC_SOURCE_2_ENDPOINT = "semantic_source_2_endpoint"
    const val SEMANTIC_SOURCE_2_MODEL = "semantic_source_2_model"
    const val SEMANTIC_SOURCE_3_PROVIDER = "semantic_source_3_provider"
    const val SEMANTIC_SOURCE_3_ENDPOINT = "semantic_source_3_endpoint"
    const val SEMANTIC_SOURCE_3_MODEL = "semantic_source_3_model"
    const val SEMANTIC_SOURCE_4_PROVIDER = "semantic_source_4_provider"
    const val SEMANTIC_SOURCE_4_ENDPOINT = "semantic_source_4_endpoint"
    const val SEMANTIC_SOURCE_4_MODEL = "semantic_source_4_model"
    const val SEMANTIC_SOURCE_5_PROVIDER = "semantic_source_5_provider"
    const val SEMANTIC_SOURCE_5_ENDPOINT = "semantic_source_5_endpoint"
    const val SEMANTIC_SOURCE_5_MODEL = "semantic_source_5_model"
    const val SEMANTIC_SOURCE_6_PROVIDER = "semantic_source_6_provider"
    const val SEMANTIC_SOURCE_6_ENDPOINT = "semantic_source_6_endpoint"
    const val SEMANTIC_SOURCE_6_MODEL = "semantic_source_6_model"
    const val SEMANTIC_SOURCE_7_PROVIDER = "semantic_source_7_provider"
    const val SEMANTIC_SOURCE_7_ENDPOINT = "semantic_source_7_endpoint"
    const val SEMANTIC_SOURCE_7_MODEL = "semantic_source_7_model"
    const val SEMANTIC_SOURCE_8_PROVIDER = "semantic_source_8_provider"
    const val SEMANTIC_SOURCE_8_ENDPOINT = "semantic_source_8_endpoint"
    const val SEMANTIC_SOURCE_8_MODEL = "semantic_source_8_model"

    /** 各过滤面的判定来源：auto（自动分流）或来源编号。 */
    const val DYNAMIC_SEMANTIC_FILTER_SOURCE = "dynamic_semantic_filter_source"
    const val DANMAKU_SEMANTIC_FILTER_SOURCE = "danmaku_semantic_filter_source"
    const val COMMENT_SEMANTIC_FILTER_SOURCE = "comment_semantic_filter_source"
    const val VIDEO_SEMANTIC_FILTER_SOURCE = "video_semantic_filter_source"

    /** 各过滤面的自定义屏蔽类型（JSON 数组文本，见 SemanticCustomRule）。 */
    const val DYNAMIC_SEMANTIC_FILTER_CUSTOM_RULES = "dynamic_semantic_filter_custom_rules"
    const val DANMAKU_SEMANTIC_FILTER_CUSTOM_RULES = "danmaku_semantic_filter_custom_rules"
    const val COMMENT_SEMANTIC_FILTER_CUSTOM_RULES = "comment_semantic_filter_custom_rules"
    const val VIDEO_SEMANTIC_FILTER_CUSTOM_RULES = "video_semantic_filter_custom_rules"

    /** 判定来源 n 的 (接口类型, 地址, 模型) 偏好键；1 号是最早的单来源键。 */
    fun semanticSourceKeys(index: Int): Triple<String, String, String> = when (index) {
        1 -> Triple(SEMANTIC_JEV_PROVIDER, SEMANTIC_JEV_ENDPOINT, SEMANTIC_JEV_MODEL)
        2 -> Triple(SEMANTIC_SOURCE_2_PROVIDER, SEMANTIC_SOURCE_2_ENDPOINT, SEMANTIC_SOURCE_2_MODEL)
        3 -> Triple(SEMANTIC_SOURCE_3_PROVIDER, SEMANTIC_SOURCE_3_ENDPOINT, SEMANTIC_SOURCE_3_MODEL)
        4 -> Triple(SEMANTIC_SOURCE_4_PROVIDER, SEMANTIC_SOURCE_4_ENDPOINT, SEMANTIC_SOURCE_4_MODEL)
        5 -> Triple(SEMANTIC_SOURCE_5_PROVIDER, SEMANTIC_SOURCE_5_ENDPOINT, SEMANTIC_SOURCE_5_MODEL)
        6 -> Triple(SEMANTIC_SOURCE_6_PROVIDER, SEMANTIC_SOURCE_6_ENDPOINT, SEMANTIC_SOURCE_6_MODEL)
        7 -> Triple(SEMANTIC_SOURCE_7_PROVIDER, SEMANTIC_SOURCE_7_ENDPOINT, SEMANTIC_SOURCE_7_MODEL)
        8 -> Triple(SEMANTIC_SOURCE_8_PROVIDER, SEMANTIC_SOURCE_8_ENDPOINT, SEMANTIC_SOURCE_8_MODEL)
        else -> throw IllegalArgumentException("semantic source $index")
    }

    fun semanticRouteKey(surface: SemanticSurface): String = when (surface) {
        SemanticSurface.DYNAMIC -> DYNAMIC_SEMANTIC_FILTER_SOURCE
        SemanticSurface.DANMAKU -> DANMAKU_SEMANTIC_FILTER_SOURCE
        SemanticSurface.COMMENT -> COMMENT_SEMANTIC_FILTER_SOURCE
        SemanticSurface.VIDEO -> VIDEO_SEMANTIC_FILTER_SOURCE
    }

    fun semanticCustomRulesKey(surface: SemanticSurface): String = when (surface) {
        SemanticSurface.DYNAMIC -> DYNAMIC_SEMANTIC_FILTER_CUSTOM_RULES
        SemanticSurface.DANMAKU -> DANMAKU_SEMANTIC_FILTER_CUSTOM_RULES
        SemanticSurface.COMMENT -> COMMENT_SEMANTIC_FILTER_CUSTOM_RULES
        SemanticSurface.VIDEO -> VIDEO_SEMANTIC_FILTER_CUSTOM_RULES
    }

    /** 等待判定的上限（毫秒）；0 = 自动（弹幕 3 s、其余 2.5 s），否则 500–30000。 */
    const val SEMANTIC_JEV_TIMEOUT_MS = "semantic_jev_timeout_ms"

    /** JEV 判定结果保存时长（天，1–90），内存与硬盘缓存共用。 */
    const val SEMANTIC_JEV_CACHE_DAYS = "semantic_jev_cache_days"

    /** JEV 首屏是否等待判定；默认不等（首屏放行，下次加载生效）。 */
    const val SEMANTIC_JEV_WAIT_FIRST_SCREEN = "semantic_jev_wait_first_screen"

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
