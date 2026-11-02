# NPatch 支持范围与恢复

> 当前支持政策（2026-10-28）：正式发布仍仅开放 Manager 模式，不支持集成（embed）模式。`EmbeddedModePolicy.SUPPORTED` 在 debug/release 构建中恒为 `false`；只有 CI 的 `embedded` 变体（见[集成模式内测构建](embedded-test-build.md)）通过 BuildConfig 编译为 `true`，产物只供内测群验证，不改变对外支持声明。相关实现保留，未来是否恢复另行决定。下文双模式矩阵与 embed 操作是此前验证记录，不代表正式版本可用。

2026-10-22，2B 已按下表限定组合完成正式准入，最终门禁见[当前路线图](npatch-implementation-roadmap.md)和 [RELEASE_ADMISSION_2B](npatch-embed-acceptance.md#release_admission_2b)。逐业务证据在[行为案例](npatch-embed-acceptance.md)和[目录矩阵](npatch-feature-matrix.tsv)，未测业务不因加载模式验收而自动变为通过。

2026-10-24 的[全目录范围复核](npatch-scope-audit.md)区分 NPatch 核心、支持配套、通用业务和混合增强。本页列出的通用功能是兼容回归对象，不意味着都由 NPatch 专项新增或需要在此专项穷尽修复；阶段 4–6 和 F19 的具体归属也分别说明。

## 已排期的免 Root 支持扩展

用户已明确安排：先做 Shizuku，再调查和适配 LSPatch，同时检查其它免 Root 框架，详见[下一阶段计划](no-root-next-stage-plan.md)。Shizuku 已接入，完成一加 `13a7a291`、shell UID 2000、NPatch Manager / embed 的限定重启专项，见[阶段 8 记录](shizuku-stage8.md)。LSPatch “支持不佳”为用户转述的待复现反馈，已有识别/服务探测代码不代替完整实测；其它候选的具体项目/版本将在调查中核实。

这些排期不改变下表的已验证 NPatch 组合，也不表示其它框架已正式支持。Shizuku 系统操作通道和框架注入分别登记结果；现有零特权恢复路径继续可用。

重启执行按 **Root → Shizuku → 手动兜底** 排序，与 NPatch / LSPatch 等加载方式独立。设置页预热及回前台异步检查，弹窗复用短缓存；首选尚不能确定时显示检查中，2 秒未返回保留未知和重试入口，不将超时误报为没有 Root。发现 su 只表示可申请权限，实际 UID 0 授权及执行前校验另行确认。主行保留取消、应用详情与首选方式，混合时提供次选按钮；没有可用服务的未安装 Shizuku 隐藏，已安装但未启动时置灰。授权、探测完成或服务恢复都不会自动重启宿主，见[重启能力实现](restart-capabilities.md)。

## 固定组合

| 项目 | 本轮组合与边界 |
|---|---|
| 测试环境 | 有线备用机 `13a7a291`，Android 16，arm64-v8a，无 Root |
| 宿主 | 官方原版 B 站 8.90.2 / versionCode 8902100，单 APK；包名 `tv.danmaku.bili` |
| Loader | 833 / API 102，普通 entry-hook，签名绕过等级 2，不启用 injectdex |
| 固定修复 | Java Manager Binder 修复及 native TLS/linker 锁修复；由工具核对双 ABI 内容哈希 |
| 配置 | 独立设置 App 的当前直连配置、许可与回执；接受条款并显式开启免 Root；不要求开启通信兼容模式 |
| 模块 API | Modern API min 101 / target 102，固定作用域，不启用 autoHotReload |
| 签名 | 测试宿主使用 NPatch 测试证书；用户证书的同证书产物流程另经离线测试，不外推其账号/第三方应用兼容性 |

本轮 arm64 真机运行，x86_64 loader 仅交叉编译并核对字节。其它 Android、ROM、宿主版本、split APK、签名等级、injectdex 和第三方模块共存组合保持未验证。最低系统要求与 Manager 的最低版本是代码准入条件，不表示所有满足最低要求的组合都已实测。补丁档案未知时不猜测当前模式，也不把 Manager 已安装等同于 Manager 模式。

## 签名与版本矩阵

| Loader / 宿主 / 环境 | Manager | embed | 证据与处理 |
|---|---|---|---|
| 固定修复版 833 / 8.90.2 / Android 16 arm64 / 等级 2 | 已实测 | 已实测 | 复用 2B 正式准入和模式往返证据 |
| 833 / 相同宿主和环境 / 等级 0、1 | 未验证 | 未验证 | 不根据更低等级推断业务可用；当前产物工具固定等级 2 |
| 833 / 相同宿主和环境 / 等级 3–5 | 未验证 | 固定 patcher 不接受 | embed 上限来自固定源码；不外推其它 Loader 的行为 |
| 其它 Loader、宿主、Android 或 ABI / 任意等级 | 未验证 | 未验证 | 按具体组合补证据，不从版本号大小或设备 ABI 推导兼容性 |
| 自有证书 / 固定工具参数 | 离线产物校验通过 | 离线产物校验通过 | 同证书升级与错误证书拒绝已有工具证据，账号和第三方回调仍未验证 |

诊断显示实际安装的宿主版本、Android API、设备 ABI，以及参数是否匹配上述基线。参数匹配只是一项静态对照：相同的 `Loader=833` 不能证明已经应用 Java/native 修复，设备支持 arm64 也不能证明当前宿主运行在 64 位进程。需结合 `verified.json` 的内容哈希、当前宿主回执及实际进程观测确认。未知或矩阵外参数只提示未验证，不自动关闭整组功能或改变授权。

## 两种模式的使用流程

embed 将**同一份已签名模块 APK**放进宿主，另行安装并保留这份模块作为设置 App；无需 Manager。Manager 模式从已安装模块加载，需在 Manager 中配置模块对宿主的作用域。两者共用直连配置、条款和当前身份校验，没有 embed 专用的功能类别或 Web 整进程裁剪。

首次使用：安装设置 App 与修补宿主，打开设置 App，接受条款并选择 NPatch 免 Root 通道，再手动冷启动 B 站。只有收到当前配置的有效宿主回执，设置界面才确认激活。普通配置变更后也需冷启动宿主；本轮不承诺热应用全部设置。

代码更新：先验证并签名模块，再用原版宿主重新修补，覆盖安装设置 App 与新宿主，最后冷启动。只更新设置 App 不会替换 embed 内的代码；同版本码也可能内容不同，以诊断中的内容一致性与 `verified.json` 中模块 SHA-256 核对。不能用旧心跳或仅版本号相同替代当前内容验证。

模式切换：使用相同原版输入、当前模块及同一宿主证书生成另一模式的产物，覆盖安装并冷启动。设置 App 和宿主各自保持原有签名链。工具拒绝证书不一致的升级，不通过卸载宿主规避签名错误。

构建和自有签名的完整参数见[产物工具说明](../scripts/npatch/README.md)。固定 patcher 及 native 哈希由脚本验证；未知 patcher、已修补输入、已有输出、缺少签名参数或错误证书均不能生成通过记录。没有 `verified.json` 的中间 APK 不属于验收产物。

## 配置、授权与恢复

| 情况 | 处理与预期结果 |
|---|---|
| 设置 App 被强行停止 | 当前端点可能不可达，宿主不凭旧授权安装功能；先打开设置 App，再冷启动宿主 |
| 免 Root 关闭或条款撤回 | 当前许可被拒绝，冷启动后零功能安装；旧租约、回执或配置不能重新启用 |
| 仅覆盖安装设置 App | 保留配置与当前身份；embed 可能显示内容不同，按提示重新修补宿主 |
| 卸载重装设置 App | 配置、条款和账号授权已删除；新身份不能继承旧授权，需重新接受条款并启用，账号授权需重做 |
| 导入设置备份 | 只恢复白名单业务设置，不恢复条款、免 Root 意图、账号凭据、租约或运行回执 |
| 协议未知或数据损坏 | 拒绝该次文档/许可，不用旧缓存冒充就绪；恢复匹配版本后重新握手 |
| 内嵌内容未知/不同 | 先核对当前设置 App 与修补记录，使用当前模块重新修补并冷启动，不通过卸载猜测恢复 |

无需清除 B 站账号、缓存或全局系统设置。验收过程的覆盖安装保留了原有登录、已下载缓存及缓存入口播放；并未通过卸载宿主证明“升级成功”。

## 功能与客观约束

2026-10-23 阶段 5 已完成当前固定组合的启动优化：NPatch 主进程按已获准配置省略无消费者定位，已知 Web/下载/播放子进程省略已审计的主进程专用准备。公共复制等 Hook、每进程新鲜许可和原注册期限保留，标准框架/未知进程维持完整策略，不保存许可或整体延迟安装。未请求的启动投影不覆盖完整适配缓存。

双模式各完成默认基线/候选各 10 次、组合各 5 次交替对照。默认模块同步中位数 Manager 减少 21.95%（488ms）、embed 减少 17.57%（375ms，未达 20% 期望）；首帧中位数分别减少 607/474ms。普通 Debug 关闭详细计时仍有实际收益，首次/失败/重置/保留数据回归通过。完整口径、p90、单次首用观察及未知范围见[阶段 5 结果](npatch-stage5-performance-results.md)；这些数字不代表整个宿主冷启动或所有设备的保证。

代表性证据覆盖净化/过滤、评论与触摸复制、普通/Story 播放器、外观、账号授权、Web、下载与缓存播放，以及原版 DEX/DexKit native 查询。主进程与适用子进程分别保留实际配置与安装证据；某进程的 Hook 数量不能代替业务效果。没有样本的通用业务项仍在目录矩阵中保持待验。

833 embed 不提供 Manager 的动态作用域管理；更新内嵌模块必须重新修补宿主。固定源码 `7723mod/NPatch` 的 `a9dc461945fbe96caf22acf80e67a72d68a6d4d4` 中，`patch/src/main/java/top/nkbe/patch/NPatch.java` 明确拒绝 `!useManager && sigbypassLevel > SIGBYPASS_HIGH`，其中 HIGH 为 2；这不能通过设置页开关绕过。源码归档及哈希固定在[构建输入清单](../scripts/npatch/native/sources-833.json)。当前普通 entry-hook 已覆盖实测进程，没有启用 injectdex 的必要证据。

模块在 [module.prop](../Bilibili_Innocent_Lab/app/src/main/resources/META-INF/xposed/module.prop) 声明 `autoHotReload=false`，功能 Hook 在 package-ready 生命周期注册。不能把 Manager 同步配置或上游 hot-reload 回调等同于卸载并重建现有 Hook；代码更新和需要重装 Hook 的设置仍通过冷启动生效。

native 能力按自身 ABI 与运行环境独立判定。原版 DEX/DexKit 通道已有真实 ART 查询证据；不把它外推为插帧模型推理或其它 ABI 全部通过。缺少某项可选 native 能力时按原有能力边界降级，不据此裁掉整个 embed 模式。

### native 能力边界

| 能力 | 当前实现与降级 | 运行验证范围 |
|---|---|---|
| DexKit 后备适配 | APK 按现有配置打包 arm64-v8a/armeabi-v7a；加载失败返回 `NATIVE_UNAVAILABLE`，不取消其它 KavaRef/候选表定位或整个 Hook 安装 | arm64 原版 DEX 真实查询已通过；其它 ABI 未实测 |
| 插帧依赖准备 | `FrameNativeLoader` 按当前进程位数和对应 ABI 列表选 ARM 依赖，有界验证归档/库哈希后加载；不支持的 ABI 单独返回 `UNSUPPORTED_ABI`，准备/链接失败仅影响本能力 | ARM64/ARM32 归档及状态机已有构建/JVM 验证；不等于模型推理或宿主播放器整合已通过 |
| x86/x86_64 | 当前模块不打包 DexKit 和插帧依赖；DexKit 尝试加载失败后降级，插帧拒绝不匹配的运行 ABI | 没有 x86 实机运行证据；833 x86_64 loader 交叉编译不能代替模块 native 验证 |

当前进程诊断使用只读查询协议 v4，保留 v2 引入的实际位数、DexKit 加载状态和插帧准备状态，并增加有界启动及支持证据；不为查询主动加载库或执行模型。`尚未尝试` 不代表失败，`库已加载` 不代表真实查询或推理已验证。旧协议进程不响应新查询，保留未知并要求更新/冷启动，不把设置 App 自身的位数或 native 状态当成宿主观测。此扩展不改变授权协议、主进程心跳或普通 JSON 报告格式。

旧研究提案中的“所有非 arm64 一律禁用插帧”不再作为当前能力规则：实际工程已有 ARM32 依赖和进程位数检查，保留现有实现，未验证的 ABI 继续在矩阵中标为未知。

### 重新修补与适配缓存

Manager/embed 共用后台内容校验：启动仍按宿主包名、版本、APK 大小/修改时间和规则版本检查缓存；重新修补使这份安装身份变化时，不在 `Application.attach` 同步打开 APK 或直接采用旧成员。取得当前端点许可后，既有适配工作线程核对原版全部 DEX 的稳定指纹，以及相同宿主版本/名称、规则、缓存结构和手动重置时间；全部一致才将旧结果绑定到当前安装身份并原子保存，保留原始时间戳。这里的 DEX 指纹是对 ZIP 条目名称、CRC、尺寸与压缩尺寸等元数据生成 SHA-256 摘要，用于正常重修补的缓存失效判断，不等于逐字节加密内容哈希或对抗恶意构造的认证。

需要新功能补适配、原版 DEX 改变、来源未知/不可读、规则变化或手动重置时，不通过重绑定绕过原有刷新条件，继续正常适配。首次重修补后的启动保留原有实时定位回退和有限补适配路径，下一次冷启动可直接命中保存的缓存；不承诺第一次启动全量使用旧缓存，也不把这项优化等同于阶段 5 的 Hook 安装耗时优化。

同签名升级 embed 及 embed→Manager→embed 往返已实际观测到后台重绑定，随后冷启动 `cache=file-hit` 且没有全量适配；当前配置身份和新鲜许可另经模式检查验证。这里复用的是类/成员适配数据，不保存或恢复授权许可；端点不可达、关闭或条款撤回时仍按原边界拒绝功能安装。跨卸载、跨宿主版本或跨规则的缓存迁移不在此实现范围内。

## 阶段 6 的设置与恢复流程

从宿主“我的”页的 BIL 设置入口或设置 App 的兼容页打开三步向导，核对补丁模式、签名和 Loader，再显式开启免 Root。修改设置后的重启入口先落盘当前配置；无 Root 时前往系统详情由用户强停，返回后确认“打开并验证”，以新进程的当前配置回执判断是否生效。应用详情的返回值不能证明已强停。

“立即应用可热更新的值”只处理已完整安装的复制/手动亮色、弹幕净化值及首页关键词/时长/播放量规则；仅影响后续新内容，不重装 Hook，不替换旧完整启动回执。初次启用未安装能力、自动跟随、布局及其它设置仍需冷启动。每次请求重新授权并核对实际加载模块内容；Manager 与 embed 均有独立实机回归，代码更新仍按各模式更新或重修补。

漫游缓存优化仅在已验证的 833 适配边界启用。实际宿主/漫游内容、版本和手动重建代次一致时，可刷新安装时间或恢复缺失的主缓存；损坏、内容变化或“重新适配当前版本”仍重新分析。先检查缓存状态，实际就绪后才显示继续重启入口；未确认不自动强停或启动。备份保存在宿主 cacheDir，清除整个应用缓存或数据会同时移除备份，不能描述为跨清数据恢复。缓存就绪不代表第三方漫游所有功能已验证。

详细产物、失败保留、双模式证据与限制见[阶段 6 记录](npatch-stage6-experience.md)。
## 本地诊断支持包

打开设置 App 的“模块 → 统一功能诊断”，先刷新当前状态，再点“导出支持包”。预览确认包含内容后选择本地保存位置；只有写入、回读字节比对及协议校验全部通过，才提示保存成功。原工具栏“导出”继续生成普通 JSON。支持包不会自动上传或发送。

ZIP 包含原格式 7 报告、独立 schema 1 的补丁/进程档案、最近主动采集启动及脱敏 Java 崩溃和 Media 白名单证据，最大 1 MiB。每进程最多 8 次已采集启动和 3 份 Java 崩溃，未采集的启动不会追溯补齐；耗时只覆盖授权与 Hook 安装阶段。异常消息、路径、凭据、偏好值和原始日志不进入附件。崩溃栈中的模块位置只是排查线索，不能据此判定根因，也不保证捕获 native 信号、ANR 或安装 handler 前的崩溃。

无响应保留未知，不主动唤醒宿主、不拿历史记录冒充当前响应；旧/混合版本只保留版本数字，按提示更新或重新修补并冷启动。Manager 可达、作用域和宿主许可分别解释，embed 不依赖 Manager。详细结构与采集边界见[支持证据协议](npatch-stage4-support-protocol.md)，两种模式真实保存及回读证据见[阶段 4 实施记录](npatch-implementation-roadmap.md#2026-10-23-阶段-45本地支持包与阶段完成)。

## 准入证据索引

| 通道或类别 | 代表性案例 | 证据边界 |
|---|---|---|
| Web 与进程 | [EMBED_WEB](npatch-embed-acceptance.md#embed_web)、[MODE_ROUNDTRIP](npatch-embed-acceptance.md#mode_roundtrip) | 修复 loader 根因，恢复公共 Hook；关闭/条款拒绝时 Web 仍可用，主/Web/下载/播放服务分别核对 |
| 净化与过滤 | [RELATED_COMMERCIAL](npatch-embed-acceptance.md#related_commercial)、RELATED_RUNTIME、RELATED_DURATION、RELATED_PLAY_COUNT、RELATED_AUTHOR | 实际响应写回、当前能力归因及可见前后对照；其它规则/表面仍按矩阵保存状态 |
| 评论与复制 | [FREE_COPY_REPLIES](npatch-embed-acceptance.md#free_copy_replies)、COMMENT_SEARCH_LINKS | 折叠正文、表情与触摸选区/复制组合；未发送评论或消息 |
| 播放器 | [PLAYER_SPEED](npatch-embed-acceptance.md#player_speed)、PLAYER_QUALITY、PLAYER_STORY_QUALITY_ABI、STORY_ACTION_ICONS、PLAYER_PORTRAIT_CONTROL | 普通/Story 的真实 ABI、设置与控件；不外推所有视频类型/会员组合 |
| 外观 | [APPEARANCE_REPRESENTATIVE](npatch-embed-acceptance.md#appearance_representative) | 默认→海盐→恢复跟随，页面与当前回调一起核对 |
| 账号、下载与分享 | [ACCOUNT_GATE](npatch-embed-acceptance.md#account_gate)、LOGIN、DOWNLOAD、CACHED_PLAYBACK、SHARE_HANDOFF、[WECHAT_RESIGNED_SHARE](npatch-embed-acceptance.md#wechat_resigned_share)、[QQ_RESIGNED_SHARE](npatch-embed-acceptance.md#qq_resigned_share) | 本地授权/撤销、原有登录、普通缓存播放、系统分享选择页及取消；微信 / QQ 另有限定无账号原生交接，不等于账号内预览、实际投递或长期风控验证 |
| native 与适配来源 | [EMBED_DEX_SOURCE](npatch-embed-acceptance.md#embed_dex_source) | Java/native 共用原版 38 DEX 快照、真实 DexKit 查询及缓存失效；插帧推理另验 |
| 安装、升级与恢复 | [EMBED_BUILD_IDENTITY](npatch-embed-acceptance.md#embed_build_identity)、EMBED_SETTINGS_LIFECYCLE、MODE_ROUNDTRIP、PATCH_R8_ENTRY | 同版本不同内容、设置 App 生命周期、新身份重新授权、模式往返、R8 真实混淆入口 |

标准框架回归覆盖来源优先级、标准文档与直连授权回退契约；本轮仅授权上述无 Root 有线机，没有新增 LSPosed 真机回归。正式发布密钥未在本机配置；Release/R8 实机包通过原测试证书签名并明确作为本地验收产物。正式分发继续使用项目既有发布签名门禁。

测试账号的已有登录、普通下载与本地账号授权/撤销均有观测；没有主动制造风控、令牌失效、支付或长期封禁实验。外部分享应分别核查 B 站端安装检测、SDK 检查、请求构造/拉起、接收方处理和回调；本地 OpenSDK 的微信证书检查运行在 B 站进程，不能据此断言失败一定来自微信端。第三方若独立向系统核验宿主证书，仅改变 B 站进程内的签名返回值无法影响该查询，但当前尚未证明微信/QQ 分享均受此阻断。F19 先调查与修复原生链路，降级仅作已确认限制下的可选兜底；系统选择页与取消不代表实际外部投递，未知组合不能描述为永久不支持。

另一个独立场景是微信/QQ 接收方自身被修补重签名：B 站 8.90.2 内置微信 SDK 会检查微信证书，失败可能误报未安装。F19.1 已增加默认关闭的「兼容重签名微信分享」选项，限定于两套已审计的 B 站分享 SDK 包装，保留原生分享。8.90.2/固定修复版 833/Android 16/arm64 的 Manager/embed 均已观察到 NPatch 重签名微信 8.0.64 的好友及朋友圈交接；关闭恢复和未安装状态另有对照，详见 [F19.1](npatch-stage7-wechat-share.md)。

F19.2 另行对照设备原有 QQ 9.2.20 / 11650 与同原包 NPatch 重签名副本：embed 原签名基线和包缺失状态、Manager/embed 重签名 QQ 的好友与 QQ 空间交接均有实际观察，目标为 QQ `JumpActivity` 后的登录页，未复现未安装误报；该组合无需额外 QQ 开关或 Hook。接收方自身仅测了固定 833 / 等级 2 的 Manager 修补方式，完整输入与边界见 [QQ 独立验收](npatch-stage7-qq-share.md)。

用户决定将微信 / QQ 账号内预览、取消回调和投递保留待验，当前不能据此宣称消息已送达。其它宿主/接收方版本、消息类型、TIM、独立 QQ 空间 App、LSPatch 和标准框架组合继续未验证。

上述上游约束的版本证据及固定源码输入见[调研档案](npatch-compatibility-plan.md)、[实施记录](npatch-implementation-roadmap.md)和[可复现 loader 配方](../scripts/npatch/README.md)。阶段 3–6 已完成声明范围；阶段 7 的 F19.1 微信兼容及 F19.2 QQ 对照已完成限定无账号交接验证，其余探索项按当前路线图研究，矩阵外组合仍需验证。
