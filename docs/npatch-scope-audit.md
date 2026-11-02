# docs 全目录的 NPatch 专项范围复核

2026-10-24，基于 `ea8b3005` 后的工作区复核。覆盖本次复核前 `docs/` 全部 14 个文件、F1–F22、N1–N7、B1–B12、A–F、36 个验收案例，以及 237 个设置 / 175 个能力共 412 行目录。已完成、未完成、撤回与历史观察均纳入；没有把历史“下一步”当成当前授权。

**超出专项的内容不止 F13。** 已完成记录中包含 13 项通用业务修复、2 项通用业务诊断增强；阶段 4–6 的若干增强也包含可复用的通用部分。另有一整份此前独立完成的关注页视觉接入文档，以及大量通用业务待验条目。它们应保留各自成果和真实状态，但不能全部记为 NPatch 缺陷或后续必做项。

本报告只调整归属和排期口径，不撤回代码、不改写提交、不改变验收状态。过去获准实施与现在的技术归属是两件事：通用工作可能当时已获授权，本报告不据此倒推为未经授权。

后续范围更新：用户于 2026-10-24 已明确追加 **Shizuku → LSPatch，并一并调查其它免 Root 框架**，见[下一阶段计划](no-root-next-stage-plan.md)。本报告对旧项目的技术归属仍成立；F11 的 Shizuku 已在[阶段 8](shizuku-stage8.md)实现并通过一加限定专项，接下来为阶段 9。原 NPatch 支持边界不因此扩大为其它框架已经通过。

## 判断标准

| 类别 | 判断依据 | 后续处理 |
|---|---|---|
| 专项核心 | 加载器、Manager/embed 差异、实际模块身份、原版 DEX、配置/授权/回执、更新和恢复的具体问题 | 保留在 NPatch 主线 |
| 专项配套 | 为当前支持组合提供必要回归、诊断、测量、产物和操作流程 | 可以保留；限定对象和验收规模 |
| 混合项目 | 同时包含 NPatch 接入/运输与通用业务/产品能力 | 分开记账，不能整项宣称为 NPatch 特有修复 |
| 通用项目 | 当前证据指向宿主业务契约、算法、界面或通用性能，缺少 NPatch 因果关联 | 移出专项默认排期，进入独立业务待办 |
| 相关扩展 / 待论证 / 已撤回 | 有相关场景但超出当前基线，或必要性和技术前提未证实 | 单独立项；历史候选不自动续做 |

判断对象是具体改动及其原因。公共代码可以修复 NPatch 问题；仅在 NPatch 机上发现问题，也不能证明它由 NPatch 引起。必要的登录、下载、复制等代表性回归仍属于专项配套，但把所有业务条件逐一修好属于另一项工程。

## 已做的通用业务修复

以下 13 项有代码提交和文档依据。现有记录没有给出它们由 NPatch 模式、重签名或加载器导致的专属根因；据宿主业务契约归为通用修复，不声称已在标准框架实机复现。引用指向原始事实记录，历史完成证据继续有效。

| 项目 | 已做内容与通用根因 | 提交 | 原始记录 |
|---|---|---|---|
| B7 标题活动角标 | 识别 `blackboard/era` 活动目标路由；原搜索路由判据覆盖不足 | `72ba46e7` | [兼容档案 B7](npatch-compatibility-plan.md#2026-10-20-专项验收结论) |
| 账号预检 | `getAccessToken()` 声明返回 service 接口，旧代码误用 model 实现类作精确校验 | `a0d4af38` 的账号部分 | [账号修复](npatch-implementation-roadmap.md#2026-10-21-阶段-2b2全量目录登录态业务与账号授权修复) |
| 评论表情复制 | 区分搜索文字的 ReplacementSpan 与真正表情，保留全文/局部选区语义；缓存按实际 Class 隔离 | `8a24d824` | [FREE_COPY_COMMENT](npatch-embed-acceptance.md#free_copy_comment) |
| 折叠回复复制 | 从已核实控件读取完整富文本，排除昵称与截断预览 | `05262b76` | [FREE_COPY_REPLIES](npatch-embed-acceptance.md#free_copy_replies) |
| 相关推荐列表写回 | 宿主字段要求 `Internal.ProtobufList`，普通 List 无法赋值 | `8a834ba8` | [RELATED_COMMERCIAL](npatch-embed-acceptance.md#related_commercial) |
| 相关推荐服务定位 | 多个同签名构造器并列，需定位实际 `C0(r2)` 分发方法 | `c553f744` | [RELATED_SERVICE](npatch-embed-acceptance.md#related_service) |
| 相关推荐作者 | 补齐新版 `getBasicInfo → getAuthor → Owner` 字段链 | `e97f41fe` | [RELATED_AUTHOR](npatch-embed-acceptance.md#related_author) |
| 游戏卡标签 | 补齐 `RelateCard#getGame → getTagName`，保留旧链 | `b2570cc3` | [RELATED_TAG](npatch-embed-acceptance.md#related_tag) |
| 强力过滤误删 | 普通投稿也有反馈菜单；修正广告判据、服务理由和精确徽标识别 | `deb42ca1` | [RELATED_STRONG](npatch-embed-acceptance.md#related_strong) |
| 普通视频默认画质 | 默认构造和自动偏好读取覆盖不完整；保留手动选择 | `c5c9cdf3` | [PLAYER_QUALITY](npatch-embed-acceptance.md#player_quality) |
| Story 画质契约 | 补齐真实接口实现及抽象视频参数的具体实现契约 | `fb599ae7` | [PLAYER_STORY_QUALITY_ABI](npatch-embed-acceptance.md#player_story_quality_abi) |
| Story 共享首条画质 | 原绑定晚于首次选择，且把 `from` 来源编号误当成业务类型 | `4a58649b` | [PLAYER_STORY_SHARED_QUALITY](npatch-embed-acceptance.md#player_story_shared_quality) |
| Story 弹幕开关隐藏 | 补齐 8.90.2 的旧控制器载体与精确访问器 | `5009fa7b` | [STORY_ACTION_ICONS](npatch-embed-acceptance.md#story_action_icons) |

`a0d4af38` 是混合提交：同一提交中的 native TLS/linker 修复、恢复 Web Hook 属于 NPatch 核心，不能把整份提交都划出专项。Story 文档中的“ABI”在此指宿主类/方法契约，不能据名称把它归为 NPatch CPU/native ABI 问题。

另有两项已提交的**通用业务诊断增强**：

| 项目 | 已做内容 | 归属边界 |
|---|---|---|
| 相关推荐逐原因归因 `f45801bf` | 只把实际首个移除原因计入对应叶项，记录受影响项错误 | [RELATED_RUNTIME](npatch-embed-acceptance.md#related_runtime)。服务于可信验收，但业务原因映射本身不是 NPatch 特有能力 |
| 竖屏入口运行证据 `16e3ebf8` | 补观察/应用计数，原可见性策略不变 | [PLAYER_PORTRAIT_CONTROL](npatch-embed-acceptance.md#player_portrait_control)。属于通用诊断准确性，不能称为修复 NPatch 下隐藏失败 |

### 专项前已有的整份通用视觉工作

[follow-feed-integration.md](follow-feed-integration.md) 是 2026-10-07/08 前后独立关注页接入的记录，不是本次 NPatch 分支新增成果。下列已做内容及其后续验收均属于通用界面/引擎维护：

- Lumen 固定依赖、复合构建、公开渐隐曲线补丁与线性像素验证。
- 关注页静态卡片、配色、动态分组、原背景所有权与 RecyclerView 回收。
- 分段标签、最常访问、更多入口 selector 与文字配色。
- 紧凑顶部、原标题收起、发布按钮搬移、Insets 与触控避让。
- UP 主筛选转场、暂停/销毁恢复、列表重绘与状态栏融合带。
- GPU/software 回退、预算、采样与生命周期检查。

文档中剩余的纯图文、实际点赞/静音、完整深色/横屏/平板、其它 Android 版本画面和零性能回退验证也属于该通用功能的待验范围。它们不会因为与 F13 都使用视觉引擎而自动加入 NPatch 专项。

## 已做但必须拆分归属的增强

| 项目 | 已完成的 NPatch 关联部分 | 通用部分及后续边界 |
|---|---|---|
| F6 / 阶段 5 启动优化 | 在实际 NPatch 启动链测量，按 NPatch 主/已知子进程门控减少无消费者定位，保留独立许可与注册期限 | 热点是公共适配/反射/安装；仅有 NPatch 测量和执行门控不能证明性能根因由 NPatch 独有。归为通用启动优化的 NPatch 限定实现，作为已授权性能扩展保留，不冒充加载模式缺陷修复；[结果](npatch-stage5-performance-results.md) |
| F8 Java 崩溃 / Media 桥 | NPatch Media 日志来源、当前宿主支持查询和双模式恢复 | Java 未捕获异常记录与白名单整理是通用诊断技术。当前有界支持链可保留，不自动扩展成全仓 crash/ANR/native 平台；[协议](npatch-stage4-support-protocol.md) |
| F9、N6、N7 观测与支持包 | 当前授权耗时/运输、Manager 可达性、补丁档案和支持 ZIP | UI、统计、归档代码可复用，本次对象仍为 NPatch 支持。普通诊断全量增强不成为必做 |
| F11 重启引导 | 免 Root 下配置发布、用户强停、重新拉起与当前回执的闭环 | 系统详情/打开 App 是通用操作；作为恢复配套保留。用户追加的 Shizuku 已由阶段 8 实现，限定结果见独立记录 |
| N3 宿主入口与向导 | NPatch 模式/签名/Loader/显式启用/当前激活的三步向导 | “我的”页增加 BIL 入口、固定 PendingIntent 启动与面板展示是通用产品能力；已按阶段 6 交付，记为体验扩展，不是加载模式缺陷 |
| F12 热应用 | NPatch 独立请求、当前许可、实际加载 APK 身份和双模式反馈 | 动态快照、复制/亮色/弹幕/首页过滤值的消费算法是通用热配置能力。已交付子集保留；扩大白名单或做全量热重载不属于兼容准入欠账 |
| F14 漫游缓存 | 固定 833 隔离加载器观察、实际活动 APK、重签名后的原内容身份与双模块 embed 工具 | 完整缓存备份/恢复、时间戳与缺字段维护是通用漫游兼容逻辑。当前 NPatch 接入可保留；漫游全部业务、解锁、旧版本通用缺陷不在本专项；[结果](npatch-stage6-experience.md#64-f14-实现与验收边界) |
| F19 微信 / QQ | 用户明确追加的重签名接收方场景，微信有证书误判复现与修复，QQ 有实际对照 | 是与修补/重签名相关的授权扩展，且接收方 NPatch/LSPosed 来源与 B 站模式分开。微信实例修复可通用于其它框架，不因此失去场景关联；账号内流程继续待验 |

这些增强已有阶段授权和验收，不能把“包含通用代码”直接解释为整项做错。需要校正的是成果归属、继续投入的范围和“专项尚欠什么”。

## F1–F22、N1–N7 全量归类

本表穷尽 29 个命名提案；“完成”沿用实际限定范围，不代表其它系统/版本全部通过。来源为 [Feature Program](npatch-feature-program.md) 与各阶段记录。

| 项目 | 当前状态 | 归类与处理 |
|---|---|---|
| F1 关闭门控/元数据 | 完成 | 专项核心：NPatch 当前许可与停用 |
| F2 状态机/Manager 失联 | 完成 | 专项核心：关闭回执和连接分类 |
| F3 徽章/预期日志 | 完成 | 专项核心配套：正确表示 NPatch 发布器绕过 |
| F4 重复/乱序回执 | 完成 | 专项核心：共享回执代码为 NPatch 闭环所需 |
| F5 补丁档案 | 完成 | 专项核心：Manager/embed/Loader 的实际来源 |
| F6 启动性能 | 完成；旧缓存许可方案撤回 | 混合：通用启动优化的 NPatch 限定实现，已授权性能扩展；未证明独有性能根因 |
| F7 环境体检 | 完成当前档案/参数范围 | 专项配套；未知组合仍未知 |
| F8 崩溃/Media 桥 | 完成有界实现 | 混合：保留 NPatch 支持链，通用 Java 采集单列 |
| F9 启动基线 | 完成 | 专项配套；不是全宿主/APM 监控 |
| F10 embed | 完成正式准入基线 | 专项核心；旧最小实验方案已被替代 |
| F11 免 Root 重启 | 零特权向导完成；Shizuku 阶段 8 已实现并通过限定双模式专项 | 恢复配套及用户明确追加增强；实际设备与边界见[阶段 8 记录](shizuku-stage8.md) |
| F12 热应用 | 限定子集完成 | 混合产品扩展；扩大热改不是本专项剩余缺陷 |
| F13 全屏视觉冻结 | 未实施 | 通用性能候选；移出专项默认排期 |
| F14 漫游两次重启/缓存 | NPatch 接入与缓存完成 | 混合；NPatch 隔离加载/重修补部分保留，通用漫游维护另列 |
| F15 适配缓存指纹 | 重修补复用完成 | 专项核心关联；跨卸载的任意缓存迁移不随之承诺 |
| F16 同进程模块雷达 | 未实施，只有有限信号 | 待拆分：NPatch 实际配置/加载观测可做支持候选；通用模块识别、冲突推断不属当前基线 |
| F17 子进程诊断 | 完成当前进程范围 | 专项核心配套；未测 isolated/其它进程不外推 |
| F18 共存包名 `-p` | 静态产物/冲突调查，未交付共存产品 | 与工具有关但超出当前原包名基线；独立 clone 产品实验，原计划已明确是非目标 |
| F19 微信/QQ | 微信实现、QQ 对照完成；账号内待验 | 用户明确追加的重签名兼容扩展；不自动接入全部社交分享类型 |
| F20 DexOpt | 未实施 | 拆分：NPatch 原包 DEX 是否被编译及 profile 影响可研究；一般 ART 提速/Shizuku 优化工具属通用扩展，当前缺少必要性证据 |
| F21 宿主偏好快照 | 未实施，写回已不建议 | 通用备份/产品需求；移出专项，读取/导出也不是兼容前置 |
| F22 证书 TOFU | 原必要修复结论撤回，未实施 | 没有已证实的 NPatch UID 授权缺陷；可选 DIRECT 证书钉扎/信任模型另行安全设计 |
| N1 参数推荐/档案卡 | 基础档案已有，可分享卡未做 | NPatch 支持候选；只作已知参数的摘要，不是必须再造推荐/分享功能 |
| N2 安全模式/自愈 | 现有关闭救援已可用，新增模式未做 | 拆分：NPatch 关闭/恢复属核心；通用崩溃自动归因、连续失败保护和自愈不能默认立项 |
| N3 宿主入口/向导 | 阶段 6 完成 | 混合：NPatch 向导保留；通用菜单入口为已交付体验扩展 |
| N4 版本漂移 | 完成 | 专项核心配套：Manager 模块路径、embed 内嵌内容与更新恢复 |
| N5 三合一保险库 | 未实施，原方案待重设计 | 拆分：NPatch 存储/重装恢复可讨论；业务设置备份属通用需求，可重建缓存/支持包已有各自边界，不重复立项 |
| N6 支持包 | 完成 | 专项配套：NPatch 有界证据的本地导出 |
| N7 授权健康度 | 完成 | 专项配套：当前运输/许可/Manager 观测 |

## 其它编号、环境记录与撤回方案

- **A–F 六个工作方向**：A 直达配置、B embed、C 多进程、D 补丁/签名矩阵、E ABI/native 观察、F 共用模式/诊断/文档均与专项有关。E 中另做插帧算法/播放器整合或新增 x86 native 支持是通用能力开发；当前报告实际位数和独立降级仍可保留。
- **B1–B12**：B1–B6、B8–B12 属于 NPatch 门控、连接、回执、性能归因、进程与 loader 支持；B7 活动角标修复属于上表通用业务。B5 的环境基础慢启动只完成归因，不自动转成修复整机/宿主所有慢启动。
- **单 APK / 无设置 App**：旧 B 方向的可选提案与 embed 相关，但改变当前设置/许可交付模型，属于独立产品方案；尚未交付，不能从“embed 已正式支持”推出必须继续做。
- **副用户、工作资料、模拟器、服务器、双开**：用于获得可测试环境时属于配套；产品级双开/克隆属于 F18。搭建所有设备环境不是当前支持范围的隐含承诺。
- **ADB touchscreen/stylus、首次权限/隐私/登录弹窗**：测试自动化与环境事实，不是模块新增功能或 NPatch 缺陷。20 次全屏进出记录不证明需要或不需要视觉冻结。
- **ROM NFC 崩溃**：已有的测试环境处置，不属于 NPatch 或模块产品修复；本轮不再操作设备/系统。
- **旧许可缓存、子进程共用许可、整体后台装 Hook、无证据推荐高版本/签名等级**：属于已撤回或未经验证方案，不是剩余任务。修复 fixed 833 的 Java Binder 和 native TLS/linker 问题则有实际专项根因，应保留。
- **完整 Lint、R8、签名门禁、日志脱敏、稳定反射边界、测试工具与多语文案**：用于当前改动的工程配套，不单独当作“范围外功能”；不能借此开展与本专项无关的全仓重构。

## 通用业务待办应移出的范围

除 F13/F21 及上表须拆分的候选，下列未完成项均来自现有记录，归入普通模块业务 backlog；今后若出现明确 NPatch 差异，再另建相应专项问题：

| 范围 | 文档中的剩余事项 | 处理 |
|---|---|---|
| 相关推荐 | 标签/课程正样本、真实移除与关闭恢复、路由分类、独立服务表面、作者/类型/理由/强力等更多组合 | 保留 [RELATED_TAG / COURSE](npatch-embed-acceptance.md#related_tag) 等状态；不因 `installed` 或缺样本阻塞 NPatch |
| 其它净化/过滤 | 首页、动态、评论、搜索、直播、推广/商品、广告卡型、关键词/数值的全部表面和组合 | 逐行见范围附表；不把 355 行 embed pending 当作 355 个 NPatch 缺陷 |
| 复制 | 纯表情、长文本/跨行、图片/推送、回复复用、不同模型与组合的穷尽验收 | 独立通用质量测试；已有代表性复制回归可复用 |
| 播放器 | 预加载提档、会员/限制实机组合、全部 Story 形态、图文卡、所有控件组合、其它 native 模型推理 | 普通功能与能力验证；已确认 NPatch 原版 DEX/注入问题单独保留 |
| 播完推荐 | 结束页空标题修复、后续推荐区通用适配 | 已移出源码的补丁在 `captures/npatch-2b/deferred-end-page/`；仍非专项欠账 |
| 外观/视觉 | 关注页上述未验范围、全部主题参数/文件导入、全屏视觉采样/冻结 | 通用 UI 与性能需求 |
| AI/Agent/账号业务 | AI 声明实际过滤、Agent 自身业务和全量账号边界 | 账号授权/登录态保留的代表性兼容检查可保留；算法与产品功能归通用 |
| 长期平台行为 | 普通下载所有资源类型、长周期风控、支付、DRM、长期封禁/服务端变化 | 不能作为无限专项承诺；原目标也排除绕过账号/DRM/付费/风控 |

与此相对，**其它 Android/ABI、宿主/Loader 版本、签名等级、标准框架回归、isolated 进程和重签名后的账号内流程**仍可能是合理的专项验证轴。它们目前多数未验证或按用户选择后排，需要明确支持目标与测试资源后排期；不能一概称为“不属于专项”，也不能自动承诺全部完成。

后续用户已明确选择 LSPatch 及其它免 Root 框架调查作为阶段 9，与 Shizuku 阶段 8 有先后顺序。这是新增的跨框架范围，按实际项目/版本/模式登记候选与结果，不受“旧 NPatch 基线已完成”排除，也不自动带入普通业务穷尽验收。

## 412 行目录与 36 个案例的完整覆盖

[范围附表](npatch-scope-matrix.tsv)逐行保留原目录的 kind/id/links/注册组、本地角色、Manager/embed 状态和证据引用，另加归属、必要说明及源行；同类公共理由集中列于下表，避免重复 402 份说明。没有修改原矩阵、目录版本、状态或案例引用；设置行和能力行往往描述同一个功能，**412 行不是 412 项独立需求或修复**。

附表按功能本体归类：405 行为通用业务/通用前置，3 行混合（漫游 2、通信兼容设置 1），2 行复用诊断日志设置，2 行为明确重签名扩展（微信设置/能力）。该目录主要描述模块功能，NPatch 当前许可、loader 等基础设施并未全以设置/能力行呈现，因此不能用此比例估计本分支代码或工时的归属。

| 附表 scope | 行数 | 归属理由与 NPatch 角色 |
|---|---|---|
| `GENERAL_FEATURE` | 402 | 通用模块业务；可选代表性回归不等于逐项专项开发，原状态也不证明 NPatch 特有根因 |
| `GENERAL_PREREQUISITE` | 3 | Agent、账号读取、AI 预检的通用业务前置；区别于 NPatch 当前运行许可 |
| `MIXED` | 3 | 漫游的通用功能与 NPatch 加载/缓存接入，或共享通信设置；边界见该行说明 |
| `REUSABLE_SUPPORT` | 2 | 现有通用日志开关/等级供 NPatch 排查复用，不是本轮新造的专属业务 |
| `RESIGN_EXTENSION` | 2 | 用户明确追加的微信重签名兼容设置/能力，有实际误判复现 |

原矩阵状态保持：Manager 410 pending / 2 observed；embed 355 pending / 48 observed / 7 installed / 2 passed。通用功能归属不撤销它在 NPatch 设备上的已有观察，也不把未知升级为通过。

36 个案例全部按以下角色计入：

| 角色 | 案例 |
|---|---|
| 8 个专项加载/产物/恢复案例 | `EMBED_WEB`、`EMBED_BUILD_IDENTITY`、`EMBED_SETTINGS_LIFECYCLE`、`EMBED_DEX_SOURCE`、`PATCH_SIGNING_TOOL`、`PATCH_R8_ENTRY`、`MODE_ROUNDTRIP`、`RELEASE_ADMISSION_2B` |
| 2 个重签名扩展案例 | `WECHAT_RESIGNED_SHARE`、`QQ_RESIGNED_SHARE` |
| 4 个通用业务的兼容代表 | `LOGIN`、`DOWNLOAD`、`CACHED_PLAYBACK`、`ACCOUNT_GATE` |
| 5 个复制/评论/倍速案例 | `FREE_COPY_DESCRIPTION`、`FREE_COPY_COMMENT`、`FREE_COPY_REPLIES`、`PLAYER_SPEED`、`COMMENT_SEARCH_LINKS` |
| 10 个相关推荐案例 | `RELATED_COMMERCIAL`、`RELATED_SERVICE`、`RELATED_RUNTIME`、`RELATED_DURATION`、`RELATED_PLAY_COUNT`、`RELATED_AUTHOR`、`RELATED_TAG`、`RELATED_COURSE_INSTALLATION`、`RELATED_REASON`、`RELATED_STRONG` |
| 5 个画质/控件案例 | `PLAYER_QUALITY`、`PLAYER_STORY_QUALITY_ABI`、`PLAYER_STORY_SHARED_QUALITY`、`STORY_ACTION_ICONS`、`PLAYER_PORTRAIT_CONTROL` |
| 2 个外观/系统分享代表 | `APPEARANCE_REPRESENTATIVE`、`SHARE_HANDOFF` |

后五行合计 26 个业务案例：作为受控的代表性回归，可以服务专项；其中通用修复和后续穷尽业务验证应单独归属，不能把整页案例一起移除或整体升级成专项待办。

## 全部 14 个原文档的处置

| 文件 | 归属与本次梳理结果 |
|---|---|
| [follow-feed-integration.md](follow-feed-integration.md) | 整份为独立通用视觉功能，保留在普通模块文档 |
| [npatch-compatibility-plan.md](npatch-compatibility-plan.md) | 专项历史档案；B7、clone 产品、ROM/输入源和通用广告现象按本报告分开，旧任务不重新激活 |
| [npatch-feature-program.md](npatch-feature-program.md) | 29 个历史提案按上表穷尽；“NPatch 专属”旧标题不构成独占性或必要性的证明 |
| [npatch-implementation-roadmap.md](npatch-implementation-roadmap.md) | 当前排期入口；保留专项核心/配套，已做的通用业务修复改为独立归属，不撤销成果 |
| [npatch-embed-acceptance.md](npatch-embed-acceptance.md) | 36 个案例全部保留事实；加载/恢复与业务回归角色分别说明 |
| [npatch-feature-matrix.tsv](npatch-feature-matrix.tsv) | 412 行模块目录，不作为专项全量开发清单；逐行归类另存附表 |
| [npatch-release-support.md](npatch-release-support.md) | NPatch 支持边界；所列业务代表不意味着通用功能均由本专项新增或全量完成 |
| [npatch-stage4-support-protocol.md](npatch-stage4-support-protocol.md) | NPatch 支持协议及可复用诊断技术；保留有界证据，不扩展通用监控平台 |
| [npatch-stage5-startup-performance-design.md](npatch-stage5-startup-performance-design.md) | 通用启动优化在 NPatch 下的设计，保留实际许可/注册期限；归已授权性能扩展，不证明 NPatch 独有根因 |
| [npatch-stage5-performance-results.md](npatch-stage5-performance-results.md) | 有实测收益的 NPatch 限定实现；公共适配热点、通用首用回归及未证明的根因/整机收益分别表述 |
| [npatch-stage6-experience.md](npatch-stage6-experience.md) | F11/N3/F12/F14 的已授权增强，按上表拆分核心配套与通用产品逻辑 |
| [npatch-f19-share-investigation.md](npatch-f19-share-investigation.md) | 明确重签名场景调查；保留进程/签名方向和其它 SDK 的边界 |
| [npatch-stage7-wechat-share.md](npatch-stage7-wechat-share.md) | 重签名接收方相关扩展，账号内流程后排；通用内容净化与之分开 |
| [npatch-stage7-qq-share.md](npatch-stage7-qq-share.md) | 相关扩展的对照验收，无新增生产 Hook；未测范围继续未知 |

## 收敛后的排期结论

1. 固定组合的 NPatch 加载、配置/授权、多进程、原版 DEX、安装升级恢复和支持链已有阶段 1–6 结果；保留实际支持边界，不以通用业务未完为由重新宣布全部未完成。
2. F19 的限定无账号交接已完成，账号内流程按用户决定后排。剩余未知验证轴若要扩展，先指定版本/设备/场景；没有自动续做全部客户端/平台/业务的任务。
3. F13、F21、通用复制/过滤/画质/视觉待办及通用安全/备份/性能扩展退出专项默认排期。F18 作为独立产品实验，F16/F20/N2/N5 拆分后再判断必要性，F22 原必要修复结论维持撤回。
4. 不因为存在历史提案就必须继续开发；用户后续已明确选择 Shizuku 为下一步，LSPatch 及其它免 Root 框架调查为再下一步，当前按[新计划](no-root-next-stage-plan.md)推进。原 NPatch 已知组合与账号内后排决定保持；正式发布时再做最终交付门禁。文档更新不运行构建或设备测试，Git 提交遵循用户逐动作授权。
