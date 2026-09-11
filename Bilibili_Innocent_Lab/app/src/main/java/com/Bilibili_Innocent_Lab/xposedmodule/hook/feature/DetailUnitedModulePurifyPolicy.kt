package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * United 详情页**协议层**的模块净化判据。
 *
 * ### 为什么会有这一层（它是 `view.v1` 那一层的正确落点）
 *
 * 2026-09-11 实测：详情页由 `viewunite.v1.View/View` 供数，
 * 而 [DetailModulePurifyPolicy] 那五项挂在 `view.v1.ViewReply` 的顶层字段上，
 * **在这条入口上改不到东西**。把同样五项搬到这里才是真正生效的落点。
 *
 * 24 个存档宿主（8.84.0 → 9.11.0）逐版核对，下面整条链路**全部齐备**：
 *
 * ```
 * ViewReply.getTab()            -> Tab
 *   .getTabModuleList()         -> List<TabModule>
 *     .getIntroduction()        -> IntroductionTab      （hasIntroduction 为真时）
 *       .getModulesList()       -> List<Module>
 *         .getTypeValue()       -> int                  （对 ModuleType.*_VALUE）
 * ViewReply.getOwner()          -> Owner.hasVip()       （UP 会员标）
 * ```
 *
 * ### 定位依据：`ModuleType` 枚举常量，不是混淆类名
 *
 * `Module` 是一个 oneof，但它**另外带一个 `type` 枚举**，
 * 于是判据可以是 `getTypeValue() == ModuleType.HONOR_VALUE` 这种纯整数比较——
 * `bapis` 包没被混淆，`ModuleType` 上的 `static final int *_VALUE` 常量在 24 版里名字一致。
 * 这比 oneof 的 `dataCase_` 稳（后者的序号会随 proto 增删字段变动），
 * 也比按类名找映射类稳（那些是 `tags.f` 这类被 R8 重命名的类）。
 *
 * ### 与其它层的关系：互为保底，且**不会互相冲突**
 *
 * 同一个用户开关现在最多有三层在兜：
 *
 * | 开关 | 本层（协议·viewunite） | 展示模型层 | View 层 |
 * | --- | --- | --- | --- |
 * | 话题标签 | 删 `SPECIALTAG` 模块 | [DetailUnitedPresentationPurifyPolicy] 过滤 cell | — |
 * | 荣誉 / 直播预约 / 合辑 / 会员标 | 删模块 / 清 `vip` | — | — |
 * | 热搜横条 | — | 清 `Headline.label` | [DetailViewPurifyPolicy] 隐 item 根 |
 *
 * 不冲突的原因是**每一层都是"没命中就原样放行"**：本层把模块删掉之后，
 * 下游展示模型层拿不到对应数据、View 层的结构指纹也就不成立，各自自然空转，
 * 不存在两层同时改同一个对象的情况。反过来若本层在某宿主上降级，
 * 下游那两层仍然各自独立生效。
 */
internal object DetailUnitedModulePurifyPolicy {

    const val ID = "detail_united_module_purify"

    private const val V1 = "com.bapis.bilibili.app.viewunite.v1."
    private const val COMMON = "com.bapis.bilibili.app.viewunite.common."

    const val MOSS_CLASS = V1 + "ViewMoss"
    const val REQUEST_CLASS = V1 + "ViewReq"
    const val REPLY_CLASS = V1 + "ViewReply"
    const val TAB_CLASS = V1 + "Tab"
    const val TAB_MODULE_CLASS = V1 + "TabModule"
    const val INTRODUCTION_TAB_CLASS = V1 + "IntroductionTab"
    const val MODULE_CLASS = COMMON + "Module"
    const val MODULE_TYPE_CLASS = COMMON + "ModuleType"
    const val OWNER_CLASS = COMMON + "Owner"

    /** 同步一元调用；与 `view.v1` 同名，但类不同。 */
    const val SYNC_METHOD = "executeView"

    /** 异步调用，第二参是 `MossResponseHandler`。 */
    const val ASYNC_METHOD = "view"

    const val PATHS_PER_TARGET = 2

    /**
     * 按 `ModuleType` 整模块删除的子项。
     *
     * @param typeConstant `ModuleType` 上的 `static final int` 常量名。
     *   用 `*_VALUE` 而不是枚举实例：取一个 int 不需要 `Enum.valueOf`，
     *   也不会因为某版本枚举少了某个常量就整条链路崩。
     */
    data class ModuleTarget(
        val capabilityId: String,
        val preferenceKey: String,
        val typeConstant: String
    )

    val HONOR = ModuleTarget(
        capabilityId = "detail_united_honor_removed",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_HONOR,
        typeConstant = "HONOR_VALUE"
    )

    val LIVE_ORDER = ModuleTarget(
        capabilityId = "detail_united_live_order_removed",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER,
        typeConstant = "LIVE_ORDER_VALUE"
    )

    val UGC_SEASON = ModuleTarget(
        capabilityId = "detail_united_ugc_season_removed",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_UGC_SEASON,
        typeConstant = "UGC_SEASON_VALUE"
    )

    /**
     * 话题标签。
     *
     * ⚠️ 枚举名是 `SPECIALTAG`（**没有下划线**），与相邻常量的命名风格不同；
     * 写成 `SPECIAL_TAG_VALUE` 会解析失败并静默降级。24 版都是这个拼法。
     */
    val TOPIC_TAGS = ModuleTarget(
        capabilityId = "detail_united_topic_tags_removed",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS,
        typeConstant = "SPECIALTAG_VALUE"
    )

    val moduleTargets = listOf(HONOR, LIVE_ORDER, UGC_SEASON, TOPIC_TAGS)

    /**
     * 热搜横条的协议层保底。
     *
     * 横条在协议里就是 `ACTIVITY_GUIDANCE_BAR` 模块（`common.ActivityGuidanceBar`）：
     * 它的 `title` / `subTitle` / `images` / `button` 与 layout
     * `theseus_detail_guide_strip` 的 `tvTitle` / `tvSubtitle` / `ivIcon` / `tvConfirm`
     * 一一对应。24 个存档宿主里枚举常量、`Module.hasActivityGuidanceBar` 与
     * `ActivityGuidanceBar.getUrl` 全部齐备。
     *
     * ⚠️ **不能按类型无条件删**：`ACTIVITY_GUIDANCE_BAR` 是通用的活动引导条，
     * 里面不止热搜一种。所以判据是"类型对上 **且** `url` 是热搜跳转"，
     * 复用 [DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri]
     * （那条 URI 判据是 2026-09-11 抓包核实过的）。
     *
     * ⚠️ **这一层尚未真机验证**：已核实的是**标题角标**的 label URI 用
     * `from=apphotword_search_huangtiao`，**横条自己的 `url` 没有抓包证据**。
     * 因此实现上是**失效方向安全**的：URL 对不上就完全不动，
     * View 层 [DetailViewPurifyPolicy.HOT_BANNER] 照旧兜住。
     * 为了让下一次真机一眼能确认，遇到"类型对上但 URL 不匹配"的引导条会
     * 打一条（只打一次的）诊断日志，把 URL 原样记下来。
     */
    object HotBannerGuidanceBar {
        const val CAPABILITY_ID = "detail_united_hot_banner_removed"
        val PREFERENCE_KEY = FeaturePreferences.REMOVE_DETAIL_HOT_BANNER
        const val TYPE_CONSTANT = "ACTIVITY_GUIDANCE_BAR_VALUE"
        const val PAYLOAD_CLASS = COMMON + "ActivityGuidanceBar"
        const val PAYLOAD_PRESENCE = "hasActivityGuidanceBar"
        const val PAYLOAD_GETTER = "getActivityGuidanceBar"
        const val URL_GETTER = "getUrl"

        val TARGET = ModuleTarget(CAPABILITY_ID, PREFERENCE_KEY, TYPE_CONSTANT)
    }

    fun hotBannerEnabled(enabledKeys: Set<String>): Boolean =
        HotBannerGuidanceBar.PREFERENCE_KEY in enabledKeys

    /**
     * 视频提及（游戏推广卡）的协议层总闸。
     *
     * 现有 `GamePromotionFeatureInstaller` 有 10+ 条路由，但全是**按宿主实现定位**
     * （`GameVideoMentionedComponent`、`yx3.a.c` 工厂这类混淆/半混淆类），
     * 换一个渲染组件就可能漏网——抓包里的 `videomention_mutiplegames` 路由就是这种风险。
     *
     * 这一层把整个 `VIDEO_MENTIONS` 模块从简介页删掉：**无论渲染层走哪个 Component，
     * 数据源头没了就无物可展**。`ModuleType.VIDEO_MENTIONS_VALUE` 与
     * `Module.hasVideoMentions` 在 25 个存档宿主里全部存在，且不含任何混淆名。
     *
     * ⚠️ 开关沿用现有的游戏卡开关，而它**默认开**——所以它不走
     * [preferenceKeys]（那条路径按"默认关"过滤），而是由安装器单独接一个参数。
     * 混进 [preferenceKeys] 会让老用户的默认值从"开"变成"关"，是行为回退。
     */
    object VideoMentions {
        const val CAPABILITY_ID = "detail_united_video_mentions_removed"
        const val PREFERENCE_KEY = "gamecard_ad_enabled"
        const val TYPE_CONSTANT = "VIDEO_MENTIONS_VALUE"

        val TARGET = ModuleTarget(CAPABILITY_ID, PREFERENCE_KEY, TYPE_CONSTANT)
    }

    /**
     * 「UP 主分享好物」商品卡的协议层总闸。
     *
     * ### 落点判定：它是简介页的一个 Module，不是 `CM.sourceContent_` 里的项
     *
     * 方案文档按"解压响应里商品卡上游约 4KB 处出现 `SourceContent` 标记"推断它在
     * `CM.sourceContent_` 列表里。这个推断**不成立**——同一个 406KB 响应里
     * `CM` 和简介模块本来就挨着，邻近不是归属证据。实际核对：
     *
     * - `Module.hasMerchandise()` / `getMerchandise()` 存在，
     *   `ModuleType.MERCHANDISE_VALUE` 存在（25 版全部齐备）；
     * - `common.Merchandise` 的字段正是商品卡的形状：
     *   `title_` + `card_`（商品列表）+ `button_`（`MerchandiseButton`，即「立即购买」）；
     * - 而现有渲染层 hook 挂的是
     *   `...intro.module.merchandise.MerchandiseComponent`——**intro 模块**那条路径。
     *
     * 三条一致指向：简介区商品卡 = `ModuleType.MERCHANDISE` 模块。
     *
     * ### 为什么**没有**去过滤 `CM.sourceContent_`
     *
     * `CM` 是另一套商业位容器（还带 `sourceContentItem_` / `cmUnderPlayer_` /
     * `cmHalfPanel_` / `adsControl_`）。方案 §2.1 想按"电商 scheme + `bfs/mall/` 图前缀"
     * 的交集在那条列表上删项，但它自己的风险表 #1 就写着
     * "`CM.sourceContent_` 是否只含商品（有无正常内容混入）"**未确认**。
     * 在没有排他性证据之前按启发式删一条共享列表，违反"宁可漏网不可误删"，
     * 所以这一轮**不做**。删整个 MERCHANDISE 模块已经把简介区这张卡从数据源头切掉了，
     * 而且不需要任何文本/scheme 启发式。
     * 若将来要覆盖播放器下方商业位（`cmUnderPlayer_`），那是另一个落点、另一份证据。
     *
     * ⚠️ 与视频提及同理：沿用的开关 `merch_ad_enabled` **默认开**，
     * 所以不走 [preferenceKeys]（那条按"默认关"过滤），由安装器单独接参数。
     */
    object Merchandise {
        const val CAPABILITY_ID = "detail_united_merchandise_removed"
        const val PREFERENCE_KEY = "merch_ad_enabled"
        const val TYPE_CONSTANT = "MERCHANDISE_VALUE"

        val TARGET = ModuleTarget(CAPABILITY_ID, PREFERENCE_KEY, TYPE_CONSTANT)
    }

    /**
     * 沿用"默认开"的老开关、因而**不能**进 [preferenceKeys] 的那些子项。
     *
     * 混进去会把老用户的默认行为从"开"悄悄改成"关"（行为回退）。有测试钉住。
     */
    val defaultOnTargets = listOf(VideoMentions.TARGET, Merchandise.TARGET)

    /**
     * UP 会员标：**不是**一个模块，而是 `ViewReply.owner.vip` 这个子消息。
     *
     * 所以它走"清子消息字段"而不是"删模块"——`OWNER` 模块整条删掉会把
     * 整个 UP 主信息行一起拿走，那是过度处理。
     */
    object UpVipLabel {
        const val CAPABILITY_ID = "detail_united_up_vip_label_removed"
        val PREFERENCE_KEY = FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL
        const val OWNER_PRESENCE = "hasOwner"
        const val OWNER_GETTER = "getOwner"
        const val OWNER_SETTER = "setOwner"
        const val VIP_PRESENCE = "hasVip"
        const val VIP_CLEAR = "clearVip"
    }

    const val TAB_PRESENCE = "hasTab"
    const val TAB_GETTER = "getTab"
    const val TAB_SETTER = "setTab"
    const val TAB_MODULE_LIST = "getTabModuleList"
    const val TAB_MODULE_CLEAR = "clearTabModule"
    const val TAB_MODULE_ADD_ALL = "addAllTabModule"
    const val INTRODUCTION_PRESENCE = "hasIntroduction"
    const val INTRODUCTION_GETTER = "getIntroduction"
    const val INTRODUCTION_SETTER = "setIntroduction"
    const val MODULES_LIST = "getModulesList"
    const val MODULES_CLEAR = "clearModules"
    const val MODULES_ADD_ALL = "addAllModules"
    const val MODULE_TYPE_VALUE = "getTypeValue"

    val preferenceKeys: List<String> = (
        moduleTargets.map { it.preferenceKey } +
            UpVipLabel.PREFERENCE_KEY +
            HotBannerGuidanceBar.PREFERENCE_KEY
        ).distinct()

    fun moduleTargetsFor(enabledKeys: Set<String>): List<ModuleTarget> =
        moduleTargets.filter { it.preferenceKey in enabledKeys }

    fun upVipLabelEnabled(enabledKeys: Set<String>): Boolean =
        UpVipLabel.PREFERENCE_KEY in enabledKeys

    val capabilityIds: List<String> = moduleTargets.map { it.capabilityId } +
        UpVipLabel.CAPABILITY_ID + HotBannerGuidanceBar.CAPABILITY_ID

    fun capabilityIdsFor(
        enabledKeys: Set<String>,
        videoMentions: Boolean = false,
        merchandise: Boolean = false
    ): List<String> =
        moduleTargetsFor(enabledKeys).map { it.capabilityId } +
            (if (upVipLabelEnabled(enabledKeys)) listOf(UpVipLabel.CAPABILITY_ID) else emptyList()) +
            (if (hotBannerEnabled(enabledKeys)) listOf(HotBannerGuidanceBar.CAPABILITY_ID) else emptyList()) +
            (if (videoMentions) listOf(VideoMentions.CAPABILITY_ID) else emptyList()) +
            (if (merchandise) listOf(Merchandise.CAPABILITY_ID) else emptyList())
}
