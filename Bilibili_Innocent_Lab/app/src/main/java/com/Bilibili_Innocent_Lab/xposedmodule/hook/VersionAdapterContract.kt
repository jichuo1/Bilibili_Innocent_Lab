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
    /** 77 → 78：新增顶栏独立重组边界；会员 Compose、首页 top 菜单和 sections 协议分层补齐。 */
    /** 78 → 79：首页第二行原生背景/分隔线增加独立装饰边界，旧缓存需补定位。 */
    /** 79 → 80：9.17.0 更新/默认画质、关注流及品牌开屏边界迁移；缓存 JSON 形状不变。 */
    /**
     * 80 → 81（2026-10-31，9.17.0(9170300) 适配）：关注流首页/个人页装饰器、分隔线和图文装饰器
     * 随构建重排（home.a0→X、quick.F→D、quick.T→P、opus.t→p），Tab 资源模型 z→y；缓存 JSON 形状不变。
     * 必须抬——9170300 上用旧规则缓存下来的关注流结果是「未命中」，不抬就永远拿不到新名称。
     */
    /**
     * 2026-10-29（同步定位计划）：缓存新增**可选**键 `cov`（各同步定位家族的覆盖范围与尝试状态），
     * **不抬 SCHEMA/RULE**。理由：没有新增定位点，只是新增只加不改的元数据；旧缓存缺 `cov` 时读成
     * 「覆盖范围未知」，按任何非空计划都判为待补，由后台静默补一轮后写回——效果和抬 RULE 一致，
     * 却不让全体用户的缓存作废、也不弹适配提示。旧模块读到新缓存会忽略 `cov`，其 quickLocate 仍是全量定位。
     * 若以后改变 `cov` 的语义（而不是增删家族），必须抬 RULE。
     */
    /** 81 → 82：合并 NPatch 原版 DEX 来源、相关推荐结构链与 Story 接入；两侧旧缓存均需重新验证。 */
    /** 82 → 83：切换后台 DEX 引擎，按新引擎重新校验同宿主缓存中的旧候选和旧缺失结论。 */
    /** 83 → 84：刷新 9.18/9.19 重新打包后的反射入口、页边界和 DEX 候选。 */
    /** 84 → 85：新增 9.20.0 三构建的已验真媒体 owner（block update / 默认画质）；只加不改 JSON 形状，
     *  不抬 SCHEMA。抬 RULE 让旧设备快路径指纹变化后重定位拿到新落点。 */
    /** 85 → 86：倍速会话新增可选缓存键 player_speed_session 并接入既有 Sponsor run/wrapper 查询兜底；
     *  只加不改 JSON 形状，不抬 SCHEMA。 */
    /** 86 → 87：SEARCH_HOME_DELIVERY 查询去掉 DISCOVER 包限制（云端枚举证每版声明至多 7 个），
     *  根包扁平 owner 改由 delivery() 全图验真准入；旧缺失结论须重查，只加不改 JSON 形状。 */
    /** 87 → 88：长按倍速计算器新增可选缓存键 player_speed_calculator 与 getScriptPace 调用边兜底查询；
     *  只加不改 JSON 形状，不抬 SCHEMA。 */
    /** 88 → 89：微信分享新增可选缓存键 wechat_share 与 createWXAPI 候选发现查询（候选仍须全量验真）；
     *  只加不改 JSON 形状，不抬 SCHEMA。 */
    /** 89 → 90：关注流装饰新增构造边发现查询，缓存恢复改按父类/形状复验而非类名名单；
     *  只加不改 JSON 形状，不抬 SCHEMA。 */
    /** 90 → 91：局部 Root/Hook 结果新增后台语义定位与可选缓存键，快路径必须重新验真后才能注册。 */
    /** 91 → 92：9.20.0 三构建（9200100/9200200/9200300）ReplyInfo -> CommentItem 根包映射器搬迁入库，抬 rule 使已缓存缺失结论重定位。 */
    /** 92 → 93：新增 Compose 更多菜单与 KMP GrpcEngine 线格式可选缓存键及 DexMeguviz 兜底查询，旧缓存缺键降级、不抬 schema。 */
    /** 93 → 94：Wave 1 引擎防锁死重试、根包扁平化跨版本泛化，新增 brand_splash_kntr_draw 与 cloud_key_freeze_contract 可选缓存键，不抬 schema。 */
    /** 94 → 95：Wave 2 新增 privacy_ignet_http 与 privacy_kneuron 可选缓存键及后台 DexMeguviz 兜底，不抬 schema。 */
    /** 95 → 96：Wave 3 新增 normal_auto、detail_special_tag_mapper 与 comment_reply_button_owners 可选缓存键及后台 DexMeguviz 兜底，并移除失效硬编码与死候选，不抬 schema。 */
    /** 96 → 97：Wave 4 新增 guest_player_core 可选缓存键及后台 DexMeguviz 兜底，不抬 schema。 */
    /** 97 → 98：补齐 9.21.0 双构建（9210100 / 9210200）已验真表项，并泛化首页早加载与故事弹幕控制器混淆适配，不抬 schema。 */
    /** 98 → 99：Phase 1 为首页提前加载接入共享后台 DEX 图发现缓存键 home_recommend_early_load_listener，不抬 schema。 */
    /** 99 → 100：补齐 9.21.0 build 3（9210300）已验真表项（更新阻断/默认画质/评论映射/长按倍速/暂停页请求入口），不抬 schema。 */
    const val RULE_VERSION = 100

    /**
     * Phase 2 按功能域拆分的规则版本号（相对于当前 [RULE_VERSION] 从 0 起算）：
     * Phase 3 升级（图原语硬化 + Tier-A + Tier-B）仅递增受影响的 8 个域到 1，
     * 其余 5 个未修改功能域保留基准 0 与既有热缓存。
     */
    val DOMAIN_RULE_VERSIONS: Map<String, Int> = mapOf(
        "update" to 0,
        "player_quality" to 1,
        "player_speed" to 1,
        "player_ui" to 1,
        "comment" to 1,
        "home_feed" to 1,
        "home_chrome" to 0,
        "detail_relate" to 1,
        "follow_page" to 1,
        "mine" to 0,
        "splash" to 1,
        "kmp_wire" to 0,
        "privacy_compat" to 0
    )

    /** 用于 DEX 崩溃保护标记的域版本摘要；全 0 时为 `"0"`，任一域递增立即轮转标记键。 */
    fun domainRulesSignature(versions: Map<String, Int> = DOMAIN_RULE_VERSIONS): String =
        versions.entries
            .asSequence()
            .filter { it.value != 0 }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
            .ifEmpty { "0" }

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
