package com.Bilibili_Innocent_Lab.xposedmodule.hook

/** 适配缓存结构与规则代际的轻量单源；模块 App 可读取而不初始化完整适配器。 */
internal object VersionAdapterContract {
    /**
     * 56 → 57（2026-09-12，按视频分区过滤）：
     * `HomeRecommendFeedPoints` **接入了两个新定位点**（`args` / `args_tid`）。
     * 按档案口径"接入新定位点要抬 SCHEMA"——抬 schema 会让所有宿主全量重跑适配，
     * 这次付这个代价是值得的：tid 读取链缺失时整个分区维度静默失效，
     * 而 rule 单独抬只保证重定位、不保证旧结构缓存被丢弃。
     */
    const val SCHEMA_VERSION = 62

    /**
     * 59 → 60（2026-09-14，9.12.0(9120100) 适配）：
     * `BLOCK_UPDATE_OWNER_CANDIDATES` 增加 `gr1.c`，
     * `PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES` 增加 `Xs1.j`；两处均只是宿主混淆
     * owner 搬家，`AdaptResult` JSON 形状没有变化，所以只抬规则版本，不抬 schema。
     * 快路径指纹包含 rule，旧缓存会重定位；旧候选仍保留以维持跨版本回退。
     */
    /**
     * 60 → 61（2026-09-15，评论搜索链接加第二道防线）：
     * `CommentPurifyPoints` **新增 `url_schemas` 键**，定位
     * `reply.v1.Url#getAppUrlSchema`。JSON 只是新增键、老缓存缺它时降级成空列表，
     * `AdaptResult` 形状没变，所以只抬规则版本、不抬 schema。
     * 但必须抬——快路径指纹含 rule，不抬的话已经缓存过的设备永远拿不到新定位点，
     * 第二道防线会静默缺席。
     */
    /**
     * 61 → 62（2026-09-17，按播放量过滤推荐）：
     * `VideoRelatePoints` **新增可选 `play_count_chains` 键**，定位
     * `RelateAVCard/RelatedAICard#getStat → Stat#getVt → StatInfo#getValue`。
     * 不包含 `getHistoryAv`（历史卡没有播放量）。JSON 只是新增键、老缓存缺它时
     * 降级成空列表，所以只抬 rule、不抬 schema。必须抬——否则已缓存设备永远
     * 拿不到详情页播放量链，该维度会静默变成"从不命中"。
     * 首页封面文案走运行期 Class 缓存，不进适配 JSON。
     */
    /** 62 → 63（2026-09-23）：9.13.0(9130300) 更新/默认画质 owner 搬迁；JSON 形状不变。 */
    /** 63 → 64（2026-09-30）：9.14.0(9140200) 更新/默认画质 owner 再次搬迁（Dr1.c / ut1.h）；JSON 形状不变。 */
    /**
     * 64 → 65（2026-10-01）：DexKit 兜底改成"签名 + 方法体常量"。更新检查原查询自 8.97.0 起每版都
     * 命中网络边界与包装层两个 owner、恒报歧义；默认画质新增兜底，且只命中稳定包装层
     * `PlayerSettingHelper` 时也会查。JSON 形状不变。
     */
    /** 65 → 66：搜索默认词 K 回调增加语义 DEX 兜底及 home_top.kotlin_words 可选缓存键；外层 JSON 形状不变。 */
    /** 66 → 67：SponsorBlock 增加后台语义 DEX 兜底及可选类名缓存，旧启用状态缓存需重定位。 */
    /** 67 → 68：旧版评论增加 KMP 容器配置读取与后台语义 DEX 兜底、可选成员缓存。 */
    /** 68 → 69：播放器推广增加后台语义 DEX 兜底和可选专用入口缓存；旧缓存缺键可读。 */
    /** 69 → 70：双 9.15.0 入口更新及可选开屏/关注流 DEX 结果；旧键继续兼容。 */
    /** 70 → 71：默认画质新增可选 Story 角色；普通默认值与 Story 独立缓存及语义兜底。 */
    /**
     * 71 → 72（2026-10-18，按发布时间过滤推荐）：`VideoRelatePoints` 新增可选
     * `publish_text_chains` 键（`RelateCard#getBasicInfo → CardBasicInfo#getDesc`、
     * `view.v1.Relate#getDesc`）。老缓存缺键降级成空表，只抬 rule、不抬 schema；
     * 必须抬，否则已缓存设备永远拿不到详情页发布时间文案链。
     */
    /** 72 → 73：倍速菜单增加后台 DexKit 兜底和可选成员缓存；旧缓存缺键仍可读取。 */
    /** 73 → 74：9.16.0 更新/默认画质、动态绘制器及本地归属字段迁移；缓存 JSON 形状不变。 */
    /** 74 → 75：Story 清晰度支持 R8 将无状态策略基类优化为接口；旧缓存需重新验证。 */
    /** 75 → 76：Story 读取契约、页面/卡片语义兜底及 Kotlin 播放请求；新增可选缓存键。 */
    /** 76 → 77：我的页保留 getter/旧字段双边界，新增首页语义缓存并修正零参精确查询；旧结果需重定位。 */
    const val RULE_VERSION = 77

    /**
     * 51 → 52（2026-09-11，9.11.0(9110400) 适配）：
     * 只往 `BLOCK_UPDATE_OWNER_CANDIDATES` 与 `PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES`
     * 前置了两个新 owner（`qq1.c` / `is1.h`），`AdaptResult` 的 JSON 形状没变，
     * 所以**只抬 rule、不抬 schema**——抬 schema 会让所有宿主全量重跑适配，
     * 为一次候选表增补付这个代价不值。快路径指纹含 rule，旧设备会自然重定位。
     *
     * 52 → 53（2026-09-11，首页游戏中心入口加 Compose 顶栏层）：
     * `HomeTopBarPoints` 多了 `game_compose` 一个键。JSON 只是**新增**键，
     * 老缓存缺它时 `fromJson` 得到 null、新层降级即可，不需要抬 schema；
     * 但必须抬 rule——`HostIdentity` 指纹含 rule，抬了旧设备才会重定位拿到新落点。
     *
     * 53 → 54（2026-09-12，按视频分区过滤）：与上面的 SCHEMA 56 → 57 同一批。
     * schema 抬了仍要抬 rule：前者管"旧缓存作废"，后者管"快路径指纹变化 ⇒ 重定位"，
     * 两件事不互相替代。
     */
}
