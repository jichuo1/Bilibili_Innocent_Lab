# NPatch 免 Root 兼容性增强：调研、现状与分支计划

> 当前实施入口：[NPatch 当前实施路线图](npatch-implementation-roadmap.md)。本文保留历史调研和 B1–B12 验收证据；后文阶段、待办及恢复提示均不再作为当前排期。

> 2026-10-24 [范围复核](npatch-scope-audit.md)另行区分专项与通用工作：B7 角标是通用业务修复；clone 产品、ROM NFC 与 ADB 输入源分别属于独立产品实验或测试环境事项。历史 A–F 的核心方向仍与 NPatch 有关，原“非目标”及后来按用户授权扩展的边界分别保留，不能逐条自动续做。

> 2026-10-23 复核：后文“embed 无法配置”“等级 2 可能只能实验”“只能手动操作 Manager”等是早期假设/环境快照，已由阶段 1–6 的 DIRECT、正式准入及输入源验证更新，不作为当前限制。原版 DEX 来源与缓存也已修复。Feature 研究中的 F19 进程判断、自动安全模式和版本建议等已纠正，详见[文档复核记录](npatch-feature-program.md#10-2026-10-23-文档复核记录)；不能把历史未验证项当作永久不支持。

> 状态：本轮 B1–B12 问题已闭环；后文保留历史调研与扩展提案
> 日期：2026-10-20（调研始于 2026-10-19）
> 基线分支：next（当时工作区 clean，HEAD 80a21910）<br>
> 实施分支：feat/npatch-compat
> 本轮测试机：13a7a291（Android 16、arm64-v8a、无 root）；早期调研设备为 192.168.1.78:5555
> 相关产物：captures/npatch-*（已被 .gitignore 忽略，未入库）

本文保留 NPatch 兼容性的历史调研上下文。当前修复结论以下节为准；后文历史“现状”和新 Feature 提案不代表本轮问题仍未修复，也不代表所有扩展功能均已实现。

## 2026-10-20 专项验收结论

本轮 12 项问题均按范围闭环，临时清单已移除。原始复现与逐项修复记录保留在 Git 历史；此处保存当前结果和必要边界。

| 编号 | 验收结果 |
|---|---|
| B1 | 关闭后不安装功能 Hook；NPatch 拒绝旧标准及旧正 revision 缓存单独授权，必须取得当前端点许可。标准框架回退保留。 |
| B2 | 标准发布器按设计绕过时，入口徽章与诊断详情一致。 |
| B3 | 关闭文档或经校验的当前关闭回复使宿主终止安装，再由回执清除重启提示；Manager 离线场景也通过。 |
| B4 | Manager 不可达与显式身份拒绝分开，连接失败重试一次，不重放写入。 |
| B5 | 同机三组各三轮对照完成归因；无模块首帧中位 11.417s，正常开启 14.544s，约 2s 为 Hook 成本。环境基础慢启动没有被宣称消除。 |
| B6 | 重复/乱序副本成功确认且不覆盖较新快照，实际失败按原因区分。 |
| B7 | 原有角标开关覆盖已核实的搜索和活动页路由；同视频开启移除、关闭保留。与播放器下推广卡区分。 |
| B8 | 更正 `--injectdex` 说明；默认 Manager 模式已实测普通子进程注入，isolated 等其它进程不据此推定。 |
| B9 | `NPatch selected` 的预期短路只记调试日志。 |
| B10 | 作用域移除可降级；激活卡区分当前有效回执、未确认及未生效。 |
| B11 | 有序广播确认无人处理后结束本路等待，最终极端回归授权为 77ms，无固定 2s 空等。 |
| B12 | 提供固定哈希、单方法的 833 loader 修补工具，同签名覆盖安装；缓存有效后 Manager 停止、重启及运行中失联均验证模块仍可运行。 |

最终验证为 Debug APK、测试 APK、3651 项 JVM 测试（0 失败、1 跳过）、3 项实机 IPC 回归、快速 Lint。现有 3 个错误基线未扩大；本轮没有把快速检查称作完整 Lint。Gradle/Kotlin 守护进程已退出。

B12 的[修补工具与操作说明](../scripts/npatch/README.md)只支持已核实的 833 产物。旧 patched APK 必须重新修补，单独更新模块 APK 不能修改其 loader。宿主须已读取当前配置；修改配置后需要 Manager 在线同步并由宿主读取一次，旧缓存不会绕过当前授权校验。最终修复包在 Manager 停止时授权 39ms、Hook 链 1898ms，设置页显示有效回执。

证据在 `captures/npatch-closure/`（已忽略），早期证据在 `captures/npatch-remaining/` 等目录。测试后恢复免 Root 开启、角标开关关闭、兼容模式关闭及原模块作用域，保留应用数据；未执行 push。

> 2026-10-20 离线停用确认：Manager 无法写入关闭文档时，当前授权端点的明确关闭拒绝会携带本次关闭 revision。NPatch 宿主校验回复并放弃安装闭包后，才由既有探测回执清除“仍需重启”提示；普通不可达与安装中状态均不能充当停用证明。Manager 保持停止的实机复测已通过，证据 `captures/npatch-closure/b3-close-confirmed.*`。

> 2026-10-20 离线授权边界：NPatch 的正 revision 文档也可能是 Manager 失联前的旧缓存，不能证明用户此后未关闭免 Root。现要求从已有授权端点取得当前许可；所有端点均不可达时不安装功能 Hook，标准框架的已校验文档回退保持原样。Manager 不可达但设置端点可达且配置已同步时，新鲜授权与 Hook 正常；修改配置后仍须按原流程同步，未凭旧缓存假定成功。

> 2026-10-20 Manager 回收修复：833 发布包的本地服务在 Manager 不可达时返回空 Binder，Vector 丢弃缓存服务，模块因而完全不执行。仓库已提供限定哈希、仅改一个方法的修补工具与[复现说明](../scripts/npatch/README.md)。同签名重修补宿主已覆盖安装，Manager 停止、重启及运行中失联均验证模块入口与回执正常。已有旧 patched APK 必须重新修补，更新模块 APK 本身无法修改旧 loader。

> 2026-10-20 标题活动角标：8.90.2 的 `HeadlineData.label` 可跳转 `https://www.bilibili.com/blackboard/era/<活动 ID>.html`，既有搜索路由判据漏掉此类角标。已补齐明确路由，仍使用「详细页组件 → 隐藏热搜横条与角标」开关；它与播放器下推广卡是不同功能。同视频实测开启后移除、关闭后保留，测试后恢复原值。证据 `captures/npatch-closure/b7-*`。

> 2026-10-20 冷启动对照：同机同 patched APK 三轮首帧中位数，无模块 11.417s、仅加载模块且免 Root 关闭 12.564s、正常开启 14.544s。正常开启的 Hook 链中位数 2000ms，与后两组首帧差 1980ms 接近；授权 30–42ms。`am start -W` 的约 10 秒超时在无模块时也出现，不能作为模块故障判据。测试机整体约 11 秒基础耗时仍存在；本次完成归因，没有宣称消除宿主或环境全部开销。证据 `captures/npatch-closure/b5-*`，作用域及开关在测量后已恢复。

> 2026-10-20 子进程勘误：Manager 模式、不启用 `--injectdex` 时，已实测 `:download` / `:ijkservice` 加载模块并执行授权与 Hook。`--injectdex` 不是普通子进程注入的统一前置条件；其他进程及 isolated 进程仍需分别验证。证据及本轮修复状态见上方专项验收结论。

---

## 0. TL;DR

- 现有 NPatch 支持不是“兼容性已经很好”，而是 **Manager 模式 happy path 做得比较细**：只支持 --manager，配置依赖 NPatch Remote Store，嵌入模式未闭环，子进程覆盖范围及签名等级/ABI 矩阵尚未完整验证。
- 代码里已经存在“模块自己的直达配置通道”，但默认关闭，且直达文档把 noRootRevision 固定为 0，导致 NPatch 下心跳永远不发、设置页无法确认激活。这是明确的代码缺口，不是文档问题。
- 目标：把 NPatch 当“注入器”，不要把它当“配置基础设施”。核心是 **配置通道自主化**，其次是 **嵌入模式**、**多进程**、**补丁自省/签名矩阵**、**ABI/native gating**。
- 用户已明确：A/B/C/D 都要做；提供 ADB 手机 + root，授权 AI 自行创建副用户/分身并完成所有操作，用户不想手动操作。
- 实施顺序：Phase 1 做 A（配置自主化 + 心跳修复 + 模式判定 + 测试，不需要设备）；Phase 2 做 B/C（嵌入 + 多进程，手机副用户验证）；Phase 3 做 D/E（签名矩阵、补丁自省、ABI gating、文档）。
- **重要限制**：Android 包管理是设备级共享的；同一个包名 tv.danmaku.bili 的官方版和 NPatch 重签名版不能共存，副用户/工作资料也不行。NPatch 实机测试需要第二台手机、模拟器，或临时替换官方版（不推荐）。
- 不自动 commit/push；分支创建和每个提交动作等用户明确授权。

---

## 1. 目标与非目标

### 1.1 目标

1. **Manager 模式稳定性**：NPatch Manager/Remote Store 不可用、被关闭、升级或行为变化时，模块仍能通过自己的鉴权通道交付配置并确认激活。
2. **嵌入模式可用性**：在 NPatch --embed 下，不依赖 Manager 常驻；由模块设置 App 通过自己的 Provider 提供配置。先验证可行性，再决定产品化程度。
3. **多进程覆盖**：覆盖 B 站 :web、:download、:pushservice 等子进程，核对默认注入覆盖及 --injectdex 的实际差异。
4. **可诊断**：诊断页/日志能说明当前 NPatch 模式、版本、签名绕过等级、进程、ABI、配置来源、Hook 安装结果。
5. **可回归**：不破坏现有 LSPosed/标准 Xposed 行为；所有改动有 JVM 测试或明确的实机验收步骤。
6. **可复现**：补丁、安装、Manager DB 预置、日志采集都有脚本/命令记录，下一次不需要重新摸索。

### 1.2 非目标

- 不重写现有 Hook/功能安装器；兼容层只改配置、模式、诊断和边界。
- 不支持改包名克隆宿主（现有代码已明确不支持）。
- 不承诺所有 B 站功能在嵌入模式/所有签名等级下都可用。
- 不绕过 B 站账号、DRM、付费或风控；登录/播放验证只用于兼容性测试。
- 不修改 NPatch 上游源码；只在本模块侧适配，必要时向上游反馈问题。
- 不自动 commit/push；不删除用户主用户数据；不修改系统旋转/方向设置。

---

## 2. 现有实现盘点

### 2.1 代码规模与测试

- 免 Root 子系统：app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/runtime/noroot/，约 **2,557 行**。
- 相关测试：10 个类（NPatchRemoteGatewayPolicyTest、NoRootConfigSnapshotCodecTest、NoRootRuntimeDetectorTest、NoRootSupportStateTest、NoRootSupportStoreGuardTest、NoRootSyncFlightRegistryTest、NoRootTargetConfigBridgeTest、NoRootUpgradeRecoveryCoordinatorTest、NoRootRestartFlushGuardTest、TrailingDebouncerTest）。
- 全项目 JVM 测试约 2,463 个（AGENTS.md 记录）；当前分支工作区 clean。

### 2.2 现有链路（Manager 模式）

1. 模块设置 App 中开启“免 Root 支持（NPatch，实验性）”。
2. NoRootSupportController 构造 NoRootConfigSnapshot，通过 NPatchRemoteGateway 写入 NPatch Remote Store（top.nkbe.npatch.remote，IXposedService 102，事务码 21/22）。
3. 已修补的 B 站进程启动，HookEntry.onPackageReady 安装 Hook；通过 moduleInstance.getRemotePreferences(GROUP) 读取配置（HookEntry.kt:396）。
4. 授权安装链成功后，主进程发送心跳广播（HookEntry.kt:4880）；模块设置 App 校验 revision/版本/目标更新时间后显示“已激活”。
5. 设置变更通过 NoRootSupportController 去抖同步；关闭/升级有重启提示和恢复协调器。

### 2.3 已实现得比较好的部分

- 配置快照有 schema/catalog/version/revision/摘要校验，失败不回退旧文件。
- 条款授权、revision、心跳、目标版本/更新时间校验。
- Manager 缺失、模块未注册、超时、同步失败等状态。
- 重启前落盘、关闭免 Root 后的重启提示。
- 防旧配置回退、升级恢复、单飞同步、看门狗。
- NPatch 1.0.8 起会把框架名/能力位与 Root 框架区分，避免 NPatch 用户拿到 Root 重启流程。
- 已有“直达配置 + admission”兜底通道，用于 Manager 中转不可用时由宿主直接读取模块完整配置。

### 2.4 硬边界与缺口（按影响排序）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | **只支持 --manager** | NPatchRemoteGateway.kt:38 明确写集成模式是 non-goal | 用户必须让 Manager 参与；不能只装 patched APK |
| 2 | **配置以 NPatch Remote Store 为唯一正常来源** | HookEntry.kt:396；NPatchRemoteGateway 整条链路 | Manager Remote API 改版/挂起/模块未注册，配置就断 |
| 3 | **直达配置默认关闭** | CommunicationCompatibilityStore.kt:10-11，DEFAULT=false | 普通用户不会开“兼容模式”，NPatch 下没有兜底 |
| 4 | **直达文档 noRootRevision=0** | PublicationAuthorityStore.kt:26-28 | 即使直达配置装上 Hook，HookEntry.kt:4881 也因 revision<=0 不发心跳，设置页无法确认激活 |
| 5 | **直达授权没有按 NPatch 模式/免 Root 开关做门控** | HostAdmissionSourcePolicy + PublicationAuthorityStore.directAllowed | 需要明确：NPatch 下只有免 Root 已开启才能发直达文档；关闭后新进程不能继续拿配置 |
| 6 | **嵌入模式没有配置通道** | NPatch 1.0.9 有 EmbeddedXposedService，但模块设置 App 无法写宿主私有 Remote Store | --embed 下无法从设置页下发配置；当前 UI 只能提示 Manager 缺失 |
| 7 | **子进程覆盖未完整验证** | 默认 Manager 模式已观察 :download/:ijkservice 注入；其他进程及 isolated 进程尚待验证 | 不能由是否使用 --injectdex 推断全部子进程功能的覆盖情况 |
| 8 | **没有签名等级/反篡改矩阵** | 现有代码不读 assets/npatch/config.json，不展示 sigBypassLevel | 登录/播放/WebView 异常时无法判断是否该提高签名绕过等级 |
| 9 | **没有 ABI/native gating** | 模块 native 只有 arm64/armeabi；NPatch core 有 arm64/x86_64 | x86_64 模拟器/32 位设备上插帧、DexKit 等可能直接失败 |
| 10 | **部分检测/桥接代码未接生产** | NoRootRuntimeDetector 只被测试引用；NoRootTargetConfigBridge.resolve 只被测试引用 | 说明支持是分阶段落地，运行模式判定没有进入诊断/决策 |
| 11 | **文档口径** | README.md:76 写“本地/管理器模式” | 容易被理解成 --embed 也支持；应明确 Manager-only 与集成模式边界 |

### 2.5 关键代码位置

- runtime/noroot/NPatchRemoteGateway.kt:38 — Manager-only 说明。
- runtime/noroot/NPatchRemoteGateway.kt:47 — AUTHORITY = "top.nkbe.npatch.remote"。
- runtime/noroot/NPatchRemoteGateway.kt:69 — 事务码 21/22。
- hook/HookEntry.kt:396 — getRemotePreferences。
- hook/HookEntry.kt:4880-4881 — 心跳入口与 noRootRevision <= 0 提前返回。
- settings/remote/PublicationAuthorityStore.kt:26-28 — 直达文档 noRootRevision = 0L。
- settings/remote/PublicationAuthorityStore.kt:58 — direct = CommunicationCompatibilityStore.isEnabled。
- runtime/compat/CommunicationCompatibilityStore.kt:10-11 — 兼容模式默认关闭。
- runtime/HostAdmissionSourcePolicy.kt — NORMAL/DIRECT 选择。
- runtime/HostAdmissionEndpoint.kt — 直达文档发放与身份校验。
- runtime/HostAdmissionClient.kt — Provider/Binder/Local/Broadcast 多路授权。
- README.md:76 — 实验性 NPatch 支持说明。

---

## 3. NPatch 1.0.9 调研结果

### 3.1 版本与形态

- 最新稳定版：NPatch v1.0.9（build 833，2026-10-12）；上一版 v1.0.8（build 801，2026-09-27）；v1.0.7（build 741）。
- 包名：top.nkbe.npatch。
- 两种修补模式：
  - --manager（官方称 Local mode）：不内嵌模块，Manager DB 保存模块/作用域；运行时通过 Manager Remote Store 下发；支持 Extreme/Seccomp/Stealth 签名绕过等级。
  - --embed（Integrated mode）：模块 APK 直接嵌入 target APK；签名绕过上限 High(2)；不需要 Manager 常驻。
- 模块 API：
  - Legacy 经典 Xposed。
  - API 101 Modern。
  - API 102 Modern + Remote API；单 Java 入口且无 native entry 的模块可由 Manager 热重载。
- 核心：core 子模块指向 HSSkyBoy/Vector，同一 Vector runtime 加载三类模块。

### 3.2 Manager 模式协议（已核对源码）

- Remote Provider authority：top.nkbe.npatch.remote。
- 方法：getRemoteService、getInjectedRemoteService。
- RemoteApiProvider 鉴权：
  - 调用方必须是已配置模块（ConfigManager.isModuleConfigured）；
  - 写服务要求 modulePackageName in callerPackages；
  - 读服务要求调用方是已 scoped target（getInjectedRemoteService）。
- 模块设置 App 拿的是可写 IXposedService；target 内的模块拿的是只读 IModuleService / Remote Preferences。
- NPatchRemoteStore 用 SQLite 保存 remote prefs/files；target 侧 VectorRemotePreferences 是只读实现，edit() 抛 UnsupportedOperationException。
- 当前模块网关的 authority/descriptor/事务码/readback 逻辑与 1.0.9 源码一致；这部分不是主要风险，风险在“把 Remote Store 当唯一来源”。

### 3.3 嵌入模式协议（1.0.9 新增）

- patch-loader/src/main/java/top/nkbe/npatch/service/EmbeddedXposedService.java：
  - 进程内 IXposedService.Stub；
  - getApiVersion() = API_102；
  - getFrameworkName() = "NPatch"；
  - getFrameworkProperties() = CAP_REMOTE；
  - requestRemotePreferences/updateRemotePreferences/deleteRemotePreferences 操作 target 内的 NPatchRemoteStore；
  - 由 LSPApplication 在 !config.useManager 时通过 XposedServiceHelper.onBinderReceived 投递给嵌入模块。
- 结论：嵌入模块在 target 进程内可以读写自己的 remote store，但 **模块设置 App 在另一个 UID/进程，无法直接访问 target 私有 store**。所以要支持 --embed 的设置页配置，必须走模块自己的 Provider/广播/文件通道，不能只依赖 NPatch Remote Store。

### 3.4 NPatch 其他事实

- ConfigProvider：top.nkbe.npatch.manager.provider.config，按 target package 返回已启用模块列表；用于 Manager 关闭时刷新 scope。
- --injectdex：把 loader dex 注入主 dex 序列，默认关闭。未开启时 Manager 模式也已实测 :download/:ijkservice 注入；其他子进程及 isolated 进程的差异需实测，不能把该参数视为子进程注入开关。
- 签名绕过等级：
  - 0 None / 1 Basic / 2 High / 3 Extreme / 4 Seccomp / 5 Stealth；
  - 3/4/5 仅 --manager 可用；--embed 最高 2。
- NPatch native core ABI：arm64-v8a、x86_64（build.gradle.kts 的 abiFilters）。
- B 站宿主 captures/background-coverage-20261011/host-current.apk：8.90.2，arm64-v8a only，minSdk 23，targetSdk 35。
- 模块自身 native 库：arm64-v8a、armeabi-v7a；没有 x86_64。

### 3.5 静态修补实验（已完成）

已在 captures/ 下完成一次不依赖设备的静态验证：

- 下载 jar-v1.0.9-833-release.jar、manager-v1.0.9-833-release.apk 到 captures/npatch-tools/。
- 浅克隆 NPatch 源码到 captures/npatch-src/，Vector 源码到 captures/vector-src/。
- 关键问题：直接运行 NPatch 1.0.9 CLI 会在签名阶段报 UnrecoverableKeyException: no match（内置 BKS 密钥读取失败）。用 Java 21 + 把 bcprov-jdk18on-1.80.jar 放在 NPatch jar **之前** 的 classpath、并注册 BouncyCastle Provider 后成功。
- 产物：captures/npatch-out/host-current-833-npatched.apk，约 190 MB。
- 验证结果：
  - package tv.danmaku.bili，versionName 8.90.2，versionCode 8902100；
  - minSdk 从 23 变为 **28**（NPatch 要求 Android 9+）；
  - 注入 assets/npatch/loader.bin、assets/npatch/origin.apk、assets/npatch/so/arm64-v8a/libnpatch.so、assets/npatch/so/x86_64/libnpatch.so；
  - apksigner verify：v3 通过，v1/v2 为 false；证书 CN=NPatch；
  - assets/npatch/config.json：useManager=true、sigBypassLevel=2、appComponentFactory=com.bilibili.lib.tribe.core.internal.TribeAppComponentFactory、lspConfig.API_CODE=102。
- 尚未在真机/模拟器运行；运行时结论未验证。

### 3.6 兼容性判断

- NPatch 1.0.9 对 API 102 的支持是认真的，Vector 的 HookBuilder/Chain/RemotePreferences/生命周期实现比较完整；不是“只支持 Legacy”那么简单。
- 真正的兼容风险不在 Hook API 本身，而在：
  1. Manager/Remote Store 生命周期与版本变化；
  2. 嵌入模式没有设置页配置通道；
  3. B 站多进程；
  4. B 站签名/反篡改；
  5. native ABI 与模块 native 功能；
  6. 版本/补丁方式差异没有诊断。

---

## 4. 工作方向

### A. 配置通道自主化（核心，优先级最高）

**目标**：NPatch 下不再把 Remote Store 当唯一真相；模块自己的鉴权直达通道成为一等公民。

具体任务：

- [ ] 新增 NPatch 运行模式判定：NPATCH_MANAGER / NPATCH_EMBEDDED / UNKNOWN；来源包括 NPatch metadata、assets/npatch/config.json 的 useManager、Manager Provider 是否可用、framework name/version/api。
- [ ] 让 NoRootSupportController 支持“直达发布”路径：不要求 NPatch Manager Remote API 成功；本地写入 PublicationAuthorityStore / 配置快照后，target 通过 HostAdmissionProvider 取直达文档；Remote Store 同步作为可选缓存/状态，不作为启用前置。
- [ ] 修复 PublicationAuthorityStore.document() 的 noRootRevision：NPatch 且免 Root 已开启时，写入当前 NoRootSupportStore.readSnapshot()?.revision；非 NPatch/未开启时保持 0，避免 LSPosed 误发心跳。
- [ ] HostAdmissionSourcePolicy / target 侧：NPatch + 免 Root 开启时优先 DIRECT；Manager Remote Store 可用时仍可作为 NORMAL 缓存，但不阻塞 DIRECT；免 Root 关闭后新进程不得继续拿直达文档（按 isDesiredEnabled 门控）。
- [ ] HostAdmissionEndpoint / AuthorityPublication：增加 noRootSelected/noRootRevision 字段；直达文档发放时校验免 Root 状态；保持现有 UID/nonce/terms/fingerprint 校验。
- [ ] NoRootSupportState：增加“通过模块直达激活”的状态/文案；心跳确认后不再依赖 remote sync 状态显示 ACTIVE。
- [ ] 诊断：显示配置来源（manager remote / module direct）、Manager 是否可用、Remote API 版本、直达是否允许；失败时区分 Manager 缺失、Remote API 失败、直达授权失败、心跳未回。
- [ ] 测试：直达文档 revision > 0；直达心跳；免 Root 关闭门控；Manager Remote 不可用但直达可用；标准 LSPosed 行为不变；stale remote / fingerprint mismatch；多用户/多进程（后续）。

**验收**：

- 在 NPatch Manager 模式、Remote API 被人为置为不可用时，设置页开启免 Root，宿主仍能安装 Hook 并回传心跳，设置页显示 ACTIVE（来源标注 direct）。
- LSPosed/标准 Xposed 现有测试与行为不变。
- 不再要求用户手动打开“通信兼容模式”才能使用 NPatch 直达。

**工作量**：M。风险：低于 B/C；主要是权限门控和状态语义，需要仔细测试。

### B. 嵌入模式 --embed（用户明确要做，实验性）

**目标**：不依赖 Manager 常驻；patched APK 内嵌模块，模块设置 App 通过自己的 Provider 下发配置。

具体任务：

- [ ] 用 --embed 修补 B 站 8.90.2（模块 APK 作为 --embed 参数），保留 --injectdex 选项；检查产物 assets/npatch/modules/<pkg>.apk、assets/npatch/config.json 的 useManager=false。
- [ ] 安装 patched B 站 + 独立模块设置 App（同一副用户/工作资料）；不安装/不依赖 NPatch Manager。
- [ ] 在模块设置 App 中提供“NPatch 集成模式”入口：检测不到 Manager 但检测到 NPatch embedded；允许直接发布配置（复用 A 的直达通道）；提示“集成模式不支持运行时换模块；更新模块需重新 patch；签名绕过上限 High(2)”。
- [ ] 验证 target 能否调用模块设置 App 的 HostAdmissionProvider：Android 11+ 包可见性；ContentResolver.call 是否可用；若不可用，评估显式广播 / createPackageContext / patch 时注入 queries / 共享文件通道；记录每条通道的实际结果。
- [ ] 心跳：直达文档带 noRootRevision 后，target 主进程发心跳；设置页显示“集成模式已激活”；若嵌入模式下无法发广播到模块 App，考虑 Provider 回执/下次启动查询。
- [ ] 签名等级：--embed 最高 High(2)；测试 B 站启动、首页、评论区、WebView、登录、播放；若 2 不够，明确“嵌入模式仅 UI/部分功能可用，完整功能用 Manager 模式”，不要硬承诺。
- [ ] 模块更新：重新 patch；设置页提供版本/产物提示；不假装支持热更新。
- [ ] 可选：一 APK 模式（没有模块设置 App）：patch 时把一份配置 JSON 放进 assets；target 无 Provider 时读默认配置；作为后续增强，不阻塞 B 的首版。

**验收**：

- 不安装 NPatch Manager，仅 patched B 站 + 模块设置 App，能开启集成模式、安装核心 UI Hook、设置页确认激活。
- 明确列出嵌入模式下不可用/未验证的功能（登录、播放、native、子进程）。
- 如果 provider 可见性或签名等级 2 导致不可行，记录实测结论并保留 Manager 模式作为主路径。

**工作量**：M/L。风险：最高；provider 可见性、B 站反篡改、签名等级 2 都是未知数。

---

### C. 多进程覆盖

**目标**：覆盖 B 站 :web、:download、:pushservice 等子进程，或明确 gating。

具体任务：

- [ ] 评估 --injectdex：分别产出“默认”和“--injectdex”两个 patched APK；对比启动时间、崩溃、Hook 安装数；确认 B 站 TribeAppComponentFactory/multidex 是否受影响。
- [ ] 验证子进程：:web（WebView/广告/分享）、:download（下载相关）、:pushservice（推送，通常不需要 Hook）；记录每个进程是否加载模块、是否拿到直达配置、Hook 安装结果。
- [ ] 诊断：每个进程上报 processName、配置来源、Hook 安装数/失败点；主进程心跳保持；子进程只上报状态，不抢心跳。
- [ ] Feature gating：只在主进程有意义的功能，在子进程跳过；按实际进程验证默认/--injectdex 覆盖，仅对证实依赖该参数的场景说明要求，不将其作为普通子进程的统一前置条件。
- [ ] 兼容性：若 --injectdex 导致 B 站启动/签名/加固异常，回退默认并记录；不把“必须 injectdex”写死，给用户/场景选择。

**验收**：

- 至少一个子进程功能在默认补丁下验证通过；对 --injectdex 声称的额外覆盖单独验证，或明确说明未验证范围。
- 默认补丁与 injectdex 补丁的差异、风险、推荐场景写清楚。

**工作量**：M。风险：中；取决于 B 站加固/多 dex 行为。

### D. 补丁自省与签名/反篡改矩阵

**目标**：让“为什么 NPatch 下不正常”可诊断，而不是靠猜。

具体任务：

- [ ] 在 target 内读取 patched APK 的 assets/npatch/config.json：useManager（Manager/嵌入）、sigBypassLevel、appComponentFactory、lspConfig（API/版本/core）；可选：patch 脚本写入自定义 marker（injectDex、模块构建号、patch 时间）。
- [ ] 诊断页/导出报告增加“NPatch patch profile”：模式、签名等级、API、framework version、进程、ABI；当前配置来源（manager/direct）；心跳/授权状态。
- [ ] 测试签名等级矩阵（B 站 8.90.2 起）：2（High）、3（Extreme）、4（Seccomp）、5（Stealth）；场景：冷启动、首页、评论区、WebView、登录、播放、下载；记录每个等级的成功/失败/崩溃/风控表现。
- [ ] 形成推荐：仅 UI 净化最低可用等级；登录/播放推荐等级；嵌入模式明确最高 2 的限制。
- [ ] 设置页提示：当前等级低于推荐时给出警告；不自动改 patch，只给操作建议。
- [ ] 版本矩阵：B 站 8.90.2（现有 APK）；如用户提供 9.x，再补一列；Android 9/11/14/15（以测试机/模拟器实际为准）。

**验收**：

- 诊断报告能看出“当前是 manager/embed、sig level 多少、哪个进程、配置从哪来”。
- 至少一份 B 站 8.90.2 的签名等级实测矩阵；没有实测的格子明确标“未验证”。

**工作量**：M。风险：中；主要是测试时间，不是代码难度。

### E. ABI / native 功能 gating

**目标**：非 arm64 环境不再“一崩全崩”，而是明确降级。

具体任务：

- [ ] 检测 Build.SUPPORTED_ABIS、Process.is64Bit、模块 native 库可用性。
- [ ] 插帧/RIFE/其他 native 功能：非 arm64/非 64 位时禁用；诊断/设置页显示“当前 ABI 不支持”；不让 UnsatisfiedLinkError 影响其他功能安装。
- [ ] DexKit：System.loadLibrary("dexkit") 失败时已有降级；补充诊断和功能状态。
- [ ] 测试：ARM64 手机（主测试机）；x86_64 模拟器（如果搭）；32 位设备/模拟器（可选）。
- [ ] 文档：native 功能支持矩阵。

**验收**：

- x86_64 模拟器上模块能启动、非 native 功能可用、native 功能明确不可用，不崩溃。
- ARM64 手机上 native 功能回归通过。

**工作量**：S/M。风险：低。

### F. 跨方向基础工作

- [ ] 统一 NPatch 模式/能力模型，供 A/B/C/D/E 共用。
- [ ] 让 NoRootRuntimeDetector 接入生产（诊断/决策），或删除只被测试引用的死代码；NoRootTargetConfigBridge.resolve 同样处理。
- [ ] 更新 README/设置页文案：“仅 Manager 模式” vs “集成模式实验性”；直达配置的安全边界；--injectdex/签名等级/ABI 的说明。
- [ ] 测试组织：纯 JVM（配置文档、revision、心跳、模式判定、门控）；契约测试（fake IXposedService/Provider/Binder）；实机（手机副用户，截图/logcat/NPatch 日志）。
- [ ] 诊断导出：包含 NPatch patch profile、进程、配置来源、Hook 安装摘要，方便用户反馈。

---

## 5. 测试环境计划

### 5.1 手机副用户/工作资料（原计划，存在包签名限制）

> **重要限制（2026-10-19 确认）**：Android 的包管理是设备级共享的；同一个包名 tv.danmaku.bili 的官方版和 NPatch 重签名版不能共存，即使安装在副用户/工作资料。adb install --user <id> 仍会做全局签名校验，通常报 INSTALL_FAILED_UPDATE_INCOMPATIBLE；不能靠副用户隔离。没有先确认这一点前，不要创建副用户或安装 patched B 站。

空间估算（如果走第二台手机/临时替换）：patched APK 约 190 MB；安装后代码约 0.6–1.0 GB；首次数据/缓存约 0.5–1.5 GB；Manager + 模块约 0.1–0.2 GB；临时 APK 约 0.25 GB；合计约 **1.5–3 GB**。当前测试机 /data 约 44 GB 可用，空间不是瓶颈，签名冲突才是。

用户已明确授权：

- 设备：192.168.1.78:5555，已 root。
- AI 可以自行创建副用户/工作资料/分身。
- AI 可以安装 NPatch Manager、模块 APK、patched B 站。
- AI 可以预置 Manager DB、自动化 UI、抓日志/截图。
- 不得影响主用户（user 0）的官方 B 站；不得卸载/清除主用户数据；不得修改系统旋转/方向设置；安全锁屏需用户手动解锁。

建议流程：

1. 连接并确认状态：adb connect 192.168.1.78:5555；adb -s ... get-state。
2. 创建隔离用户（优先副用户；若 NPatch/安装器不支持，再试工作资料）；记录 user id；不改 user 0 的应用数据。
3. 在隔离用户安装：NPatch Manager 1.0.9（Manager 模式）或不需要（嵌入模式）；模块 app-debug.apk；patched B 站（manager 或 embed 产物）。
4. 预置 Manager DB（仅 Manager 模式）：路径 /data/user/<id>/top.nkbe.npatch/databases/modules_config.db；表 LoadedModule(pkgName, apkPath)、Scope(appPkgName, modulePkgName)；先 force-stop Manager，备份 DB，再插入模块/作用域，重启 Manager；若 Room schema 校验失败，回退 UI 自动化，不硬改。
5. 模块设置 App：开启免 Root/集成模式；若是直达模式，确认无需 Manager Remote API；自动化点击/输入使用 su -c 'input ...'（该机普通 input 可能被拒）。
6. 启动宿主：am start -n tv.danmaku.bili/.MainActivityV2；不要用 monkey；保留用户方向锁定/旋转设置。
7. 采集：截图 PNG 先存手机再 adb pull；logcat 按 PID/标签过滤；NPatch 日志/崩溃日志；模块诊断导出。
8. 清理：不删除主用户数据；副用户是否删除，等用户确认；默认保留以便复测。

注意：

- 该机已有 root/LSPosed；NPatch 是免 root 方案，但在副用户中运行不冲突。
- patched B 站签名与官方不同，不能与主用户官方 B 站共存于同一用户；副用户隔离正好解决。
- 安全锁屏/后台启动限制按 AGENTS.md 处理；不要反复盲目启动。
- 若副用户不支持 NPatch Manager（多用户 Binder/存储问题），回退工作资料或模拟器，并记录原因。

### 5.2 本机 Windows 模拟器（可选）

- 硬件：Ryzen 5 5600X（6C/12T）、32 GB、Hyper-V 已启用；当前空闲内存约 8 GB，跑模拟器前可停 Docker/WSL 或限制内存。
- 需安装 Android Emulator + API 30/33 Google APIs x86_64 镜像（约 3–5 GB）。
- B 站 APK arm64-only；Android 11+ x86_64 镜像支持 ARM translation，但兼容性不保证；native/播放可能失败。
- 用途：UI/流程/配置通道验证；不作为 native/播放结论。
- 优点：可重复、可快照；缺点：ARM translation 风险。

### 5.3 Linux 服务器（可选）

- 16 GB 内存足够一个 headless 模拟器/redroid（4–6 GB）；需 x86_64 + KVM（/dev/kvm）。
- 若是 VPS 且无嵌套虚拟化，则不可行；若为 ARM64 主机，更理想。
- 需要用户提供 SSH 地址/用户/密钥；当前未提供。
- 用途：重复测试、长时间运行；构建仍在本地。

### 5.4 纯静态/JVM

- 现有 2,463 个 JVM 测试；新增 A 的文档/revision/心跳/门控测试。
- NPatch CLI 静态 patch + aapt2/apksigner/unzip 检查。
- fake IXposedService/Provider 契约测试。
- 不能替代实机：B 站签名、登录、播放、native、多进程必须实机/模拟器。

### 5.5 建议测试矩阵（先做最小集）

| 维度 | 取值 |
|---|---|
| 模式 | Manager、Embed |
| NPatch | 1.0.9 为主；1.0.8 回归可选 |
| B 站 | 8.90.2（现有）；9.x 待用户提供 |
| Android | 测试机实际版本优先；模拟器 API 30/33 |
| 签名等级 | 2、3、5（4 可选） |
| 进程 | 主进程、:web、:download |
| 配置来源 | Remote Store、模块 direct |
| 功能 | 首页净化、评论、WebView、登录、播放、native（分层） |

---

## 6. 分支与阶段计划

### 6.1 分支

- 从 next 创建 feat/npatch-compat（待用户确认后执行）。
- 不自动 commit/push；每个提交动作等用户明确授权。
- 每个 phase 结束保留可运行状态、测试结果和文档更新。

### 6.2 Phase 1：配置自主化（A）

- 模式/能力模型；
- direct 发布路径；
- noRootRevision/心跳修复；
- 门控与状态；
- JVM/契约测试；
- 更新 README/设置文案。
- 验收：Manager Remote 不可用时仍可激活；LSPosed 不回归。
- 不需要设备即可完成大部分。

### 6.3 Phase 2：嵌入 + 多进程（B/C）

- embed patch 脚本/产物；
- 模块设置 App 集成模式；
- provider 可见性验证；
- 心跳/状态；
- --injectdex 对比；
- 第二台手机或模拟器实机验证；手机副用户因同包名签名冲突不可行（见 5.1）。
- 验收：embed 核心 UI 可用（或明确不可行原因）；子进程至少一个场景通过/明确 gating。

### 6.4 Phase 3：签名矩阵 + 补丁自省 + ABI（D/E）

- 读取 assets/npatch/config.json；
- 诊断报告；
- 签名等级矩阵；
- ABI/native gating；
- 文档/用户指引。
- 验收：诊断可解释；矩阵有实测/未验证标注；非 arm64 不硬崩。

### 6.5 Phase 4：收尾

- 全量回归（JVM、FastLint、实机 smoke）；
- README/兼容文档；
- 可选：向上游反馈 NPatch 问题（CLI 签名、v3-only、embed 限制）；
- 用户 review 后决定是否 commit/push。

---

## 7. 风险与未知

1. **嵌入模式签名等级上限 2**：B 站可能要求 3/4/5；若 2 不够，embed 只能作为轻量/实验路径，Manager 仍是完整路径。
2. **Provider 可见性**：Android 11+ target 调模块 App Provider 可能受包可见性影响；需要实测，必要时加 patch 时 manifest 注入或广播/本地回退。
3. **B 站反篡改**：签名、native 检测、登录/播放风控；模块只能诊断/建议，不能保证绕过。
4. **NPatch 版本漂移**：1.0.9 的 Remote Store/DB/Provider 可能再变；A 的 direct 通道正是为了降低这个风险。
5. **Manager DB 预置**：Room schema/版本变化可能让 sqlite 直改失效；需备份、回退 UI 自动化。
6. **多用户**：NPatch Manager 对副用户/工作资料的支持未知；可能需要模拟器或主用户隔离方案。
7. **ARM translation**：本机模拟器跑 B 站 arm64 的稳定性未知；native/播放可能不可用。
8. **账号**：登录/播放验证可能需要测试账号；用户未提供，先用未登录 smoke。
9. **当前测试机状态**：Android 版本、可用存储、副用户支持情况需连接后确认；安全锁屏可能需要用户解锁。
10. **直连配置安全**：必须保持 UID/nonce/terms/fingerprint 校验；不能因为“方便”放宽到任意宿主/任意进程。
11. **LSPosed 回归**：direct 文档/noRootRevision 的改动必须只在 NPatch 模式生效，不能影响标准 LSPosed 心跳/激活语义。

---

## 8. 需要用户提供/确认

已提供：

- ADB 手机 + root；
- 授权 AI 自行创建副用户/工作资料/分身、安装应用、预置数据库、自动化操作；
- 不希望手动操作。

还需要：

- [ ] 确认分支名/范围（默认 feat/npatch-compat，A/B/C/D 都做）。
- [ ] 确认 B 站目标版本：继续 8.90.2，还是提供 9.x APK。
- [ ] 确认验收范围：只 UI 净化，还是登录/播放/native 也要。
- [ ] 若登录/播放验收，提供测试账号或允许扫码登录（用户手动一次即可）。
- [ ] 安全锁屏时协助解锁；不需要其他手动配置。
- [ ] 是否允许删除副用户/保留复测环境（默认保留到工作结束）。
- [ ] 由于同包名签名冲突，需确认实机测试改用第二台手机、本机模拟器，还是临时替换官方 B 站（不推荐）。

---

## 9. 参考与产物

### 9.1 仓库内

- Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/runtime/noroot/
- .../runtime/HostAdmission*.kt
- .../settings/remote/PublicationAuthorityStore.kt
- .../settings/remote/RemoteHookConfigContract.kt
- .../runtime/compat/CommunicationCompatibilityStore.kt
- .../hook/HookEntry.kt
- README.md:76
- AGENTS.md（构建、ADB、测试机规则）

### 9.2 本地调研产物（gitignored）

- captures/npatch-src/ — NPatch 1.0.9 源码（浅克隆）
- captures/vector-src/ — Vector 源码（浅克隆）
- captures/npatch-tools/ — NPatch jar/manager、BouncyCastle、RunNPatch.java
- captures/npatch-out/host-current-833-npatched.apk — B 站 8.90.2 manager 模式修补产物
- captures/background-coverage-20261011/host-current.apk — 原始 B 站 8.90.2

### 9.3 外部

- NPatch 官网：https://npatch.nkbe.top/
- NPatch 仓库：https://github.com/7723mod/NPatch
- NPatch 旧仓库：https://github.com/HSSkyBoy/NPatch
- Vector：https://github.com/HSSkyBoy/Vector
- NPatch Releases：https://github.com/7723mod/NPatch/releases

---

## 10. 复现命令（草稿，后续按实际调整）

### 10.1 构建模块

在 G:/src/Bilibili_Innocent_Lab/Bilibili_Innocent_Lab，使用 JDK 17：

    $repo = 'G:/src/Bilibili_Innocent_Lab'
    $env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
    $env:GRADLE_USER_HOME = "$repo/.gradle-user-home"
    $env:ANDROID_USER_HOME = "$repo/.android-user-home"
    Set-Location "$repo/Bilibili_Innocent_Lab"
    ./gradlew.bat :app:assembleDebug --offline --no-daemon
    ./gradlew.bat --stop

### 10.2 NPatch CLI 修补（已验证的 workaround）

    cd G:/src/Bilibili_Innocent_Lab/captures
    # 需要 Java 21+；bcprov 必须在 NPatch jar 之前
    D:/graalvm-jdk-21.0.2+13.1/bin/java.exe -Xmx4g -cp "npatch-tools;npatch-tools/bcprov-jdk18on-1.80.jar;npatch-tools/jar-v1.0.9-833-release.jar" RunNPatch --manager -l 2 -o npatch-out --force background-coverage-20261011/host-current.apk

嵌入模式（待验证）：

    D:/graalvm-jdk-21.0.2+13.1/bin/java.exe -Xmx4g -cp "npatch-tools;npatch-tools/bcprov-jdk18on-1.80.jar;npatch-tools/jar-v1.0.9-833-release.jar" RunNPatch --embed Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk --injectdex -o npatch-out --force background-coverage-20261011/host-current.apk

注意：--embed 与 --manager 互斥；--embed 签名绕过最高 2。

### 10.3 手机连接/安装（草稿）

    adb connect 192.168.1.78:5555
    adb -s 192.168.1.78:5555 get-state
    adb -s 192.168.1.78:5555 install -r -g Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk
    adb -s 192.168.1.78:5555 install -r captures/npatch-tools/manager-v1.0.9-833-release.apk
    adb -s 192.168.1.78:5555 install -r captures/npatch-out/host-current-833-npatched.apk
    adb -s 192.168.1.78:5555 shell am force-stop tv.danmaku.bili
    adb -s 192.168.1.78:5555 shell am start -n tv.danmaku.bili/.MainActivityV2

副用户/工作资料、Manager DB 预置、UI 自动化命令在 Phase 2 前补充；不要直接套用到 user 0。

---

## 11. 下一步

1. 用户确认分支/范围。
2. 建 feat/npatch-compat（等授权）。
3. Phase 1：A 的代码 + JVM 测试 + 文案（不需要设备）。
4. Phase 2：手机副用户/工作资料，验证 A，再做 B/C。
5. 每个 phase 更新本文的“状态快照”和实测结果。

---

## 12. 状态快照

- 2026-10-19：完成现状盘点、NPatch 1.0.9/Vector 源码调研、B 站 8.90.2 manager 模式静态 patch 验证；工作区 clean；分支未创建；未实机运行。
- 待办：Phase 1 代码/测试；手机副用户环境；embed 产物；签名矩阵。

---

## 13. 附录：Phase 1 预计改动文件

新增/调整（文件名以实现时为准）：

- runtime/noroot/NPatchRuntimeProfile.kt（新）：模式、版本、config.json、ABI、进程等只读快照。
- runtime/noroot/NoRootSupportController.kt：直达发布路径；remote sync 改为可选/缓存；启用/关闭状态机。
- runtime/noroot/NoRootSupportState.kt：直达激活状态/文案；与现有 heartbeat 状态合并。
- settings/remote/PublicationAuthorityStore.kt：noRootRevision 注入；direct 授权门控；避免影响标准 LSPosed。
- runtime/HostAdmissionSourcePolicy.kt：NPatch 下 DIRECT/NORMAL 选择。
- runtime/HostAdmissionEndpoint.kt：直达文档字段/校验；免 Root 开关门控。
- runtime/HostAdmissionClient.kt：配置来源/回执诊断（如需要）。
- hook/HookEntry.kt：心跳条件与 NPatch direct 兼容；诊断日志。
- diagnostics/ModuleDiagnostics*.kt：NPatch patch profile/配置来源/进程/ABI。
- app/src/test/.../runtime/noroot/*Test.kt：direct revision、心跳、门控、模式判定、LSPosed 回归。
- README.md / 设置页 strings：Manager/Embed 边界、签名等级、injectdex、ABI。

设计决策（待实现时确认）：

1. NPatch 下直达配置默认开启，还是首次启用时弹一次明确的“允许宿主直接读取完整配置”确认？建议后者：不要求用户去开旧的“通信兼容模式”，但保留知情同意。
2. 标准 LSPosed 下 direct 文档的 noRootRevision 必须保持 0；只有 NPatch + 免 Root 开启才注入 revision。
3. embed 模式首版是否要求安装模块设置 App？建议首版要求；一 APK 模式作为后续。
4. 若 --injectdex 导致 B 站启动异常，默认补丁保持不开；只在具体进程实测默认注入不足、且该参数确能改善时给出使用说明，不提示“需要子进程就必须开启”。

---

## 14. 会话恢复提示

- 先读本文第 0、2.4、4、6、12 节。
- 检查 git status / 当前分支；本文最后更新时工作区只有本文件未跟踪。
- 若尚未建分支：先确认用户是否仍要 feat/npatch-compat。
- 若已开始 Phase 1：先跑 :app:testDebugUnitTest 的相关测试，再改代码。
- 不要假设 captures/ 产物还在；它们可能被清理，按第 10 节重建。
- 不要自动 commit/push；手机操作遵守第 5.1 节和 AGENTS.md。

---


---

## 16. 补充：手机分身 / 应用双开 / XSpace 的区别

之前把“副用户”说得太简单，需要把三类方案分开：

1. **标准副用户 / 工作资料 / 部分“手机分身”**
   - 本质是 Android 多用户；
   - PackageManager 是设备级共享的，包名 + 签名是全局身份；
   - 同一个 tv.danmaku.bili 的官方版和 NPatch 重签名版不能共存；
   - adb install --user <id> 仍会做全局签名校验，通常报 INSTALL_FAILED_UPDATE_INCOMPATIBLE；
   - 除非厂商对 PackageManager 做了额外的虚拟化（需要实测），否则不能靠这类分身隔离。

2. **应用双开 / App Twin**
   - 通常是克隆“已安装的同一个 APK”，签名与原应用一致；
   - 不提供“选择任意 APK 安装到克隆槽”的能力；
   - 因此不能把 NPatch 重签名的 patched B 站塞进双开克隆；
   - 部分厂商会生成不同包名的克隆，但底层仍是原 APK/签名，且模块目前拒绝非 tv.danmaku.bili 包名。

3. **XSpace / Parallel Space / VMOS 等虚拟容器**
   - 属于 VirtualApp/Android-in-Android，不是标准多用户；
   - 可能允许在容器内安装任意 APK，这是唯一“可能绕过同包名签名冲突”的方向；
   - 但 NPatch 的 Manager/Provider/UID/心跳/多进程/native 在容器内是否成立，未知；
   - 模块设置 App 与容器内 target 的 UID/包可见性可能对不上；
   - B 站也可能检测虚拟环境；
   - 只能作为实验，不作为主测试路径。

当前测试机是 Xiaomi Mi 10、MIUI/HyperOS V816、Android 16、user 0，官方 B 站 8.90.2 已安装；系统里没有现成的独立双开/分身包。若要在当前手机验证，需要先做最小实验：

- 先启用小米“应用双开/手机分身”，确认它是否允许安装/替换不同签名的 patched APK；
- 或装 XSpace/VMOS 类容器，尝试在容器内安装 patched APK；
- 无论哪条，都先不要动主空间官方 B 站；失败就清理容器/分身。

空间：手机 /data 约 44GB 可用；普通 patched 安装约 1.5–3GB；VMOS 容器本身可能再占 5–10GB；XSpace 视实现而定。

---

## 17. 改包名（NPatch -p）可行性分析

### 17.1 实测：-p 到底改了什么

用 NPatch 1.0.9 CLI 对 B 站 8.90.2 生成了一份改包名产物：

    RunNPatch --manager -p com.bilibili.innocent.clone -l 2 ...

产物在 captures/npatch-out-clone/host-current-833-npatched.apk。对比原 Manifest：

- package 已从 tv.danmaku.bili 变为 com.bilibili.innocent.clone；
- 以原包名开头的 52 个 provider authority 已同步改成新包名；
- **6 个自定义 permission 没有改**，仍是 tv.danmaku.bili.permission.*；
- **4 个不属于原包名前缀的 authority 没有改**：
  - com.facebook.katana.provider.PlatformProvider
  - com.facebook.app.FacebookContentProvider1158523682358502
  - com.bilibili.lib.accessbridge.BiliAccessContentProvider
  - com.bilibili.biligame.PreDownloadProvider
- **4 个 taskAffinity 仍是 tv.danmaku.bili**；
- dex 里仍有 267 处 tv.danmaku.bili 字符串，包括：
  - /data/data/tv.danmaku.bili、/data/data/tv.danmaku.bili/cache
  - content://tv.danmaku.bili.appwidget...
  - tv.danmaku.bili.MainActivityV2
  - tv.danmaku.bili:web
  - tv.danmaku.bili.action.*
  - tv.danmaku.bili.permission.*

NPatch 的 remapAuthority 只处理 originPackage 前缀的 authority；permission/taskAffinity/dex 常量不在它的处理范围。

### 17.2 直接后果

1. **自定义 permission 冲突**
   - 官方 B 站已定义 tv.danmaku.bili.permission.*，签名是 B 站官方；
   - clone 仍定义同名 permission，签名是 NPatch；
   - 安装时可能直接 INSTALL_FAILED_DUPLICATE_PERMISSION，或者安装后权限归属错乱。

2. **Provider authority 冲突**
   - 4 个未改的 authority 与官方 B 站重复；
   - 安装时可能 INSTALL_FAILED_CONFLICTING_PROVIDER，或运行时 ContentResolver 被官方/克隆互相抢占。

3. **taskAffinity 冲突**
   - 不是安装阻断，但两个 App 的 Recents/任务栈可能合并或互相干扰。

4. **dex 常量仍指向旧包**
   - 数据路径、组件名、action、permission、process 名、content URI 都可能仍用旧值；
   - 有的 path 会写到 /data/data/tv.danmaku.bili（官方 UID 的数据目录），clone 无权访问，可能启动即崩或功能异常；
   - action 可能被官方 B 站同时接收，造成联动/串扰。

### 17.3 B 站代码依赖

- Manifest：56 个 provider、6 个自定义 permission、216 处 tv.danmaku.bili 字符串。
- dex：267 处 tv.danmaku.bili 字符串；其中一部分是 Java 类名/调试信息，不能盲目全局替换。
- 可能受影响：登录/账号 SDK、推送、Widget、WebView 深链、数据目录、组件跳转、下载、分享、更新检查。
- 登录/播放/账号是否可用，必须实机验证；不能仅凭“能启动”判断成功。

### 17.4 模块侧改动量

模块里 TARGET_PACKAGE 相关引用约 **235 处**，分布在 HookEntry、admission、心跳、资源 ID、组件启动、诊断、设置等。要支持 clone 包，至少需要：

- 用运行期 target package 替代固定 TARGET_PACKAGE；
- 资源 ID 解析同时尝试 runtime package 和原包名；
- HostAdmissionEndpoint 的 UID 校验支持 clone 包 allowlist；
- 心跳/NoRootSupportState 的 target package 改成运行期；
- module.prop 的 scope.list（当前 tv.danmaku.bili + staticScope=true）支持 clone 包，或改 staticScope；
- 模块 App 的 queries/包可见性、设置页目标包选择；
- README/诊断/测试。

这不是改一个常量，是一次 target identity 抽象，工作量 L。

### 17.5 结论

- 改包名**技术上可做**，但不是 NPatch -p 一键解决。
- 单靠 -p：大概率装不上（permission/authority 冲突）；即使装上，B 站代码里的旧包常量、登录/播放/推送也可能出问题。
- 要做成，需要：
  1. 后处理 Manifest：permission、未改 authority、taskAffinity、action（视需要）；
  2. 定向修补 dex 中的旧包常量（不能全局替换类名/调试串）；
  3. 模块 target identity 重构 + scope/admission/心跳适配；
  4. 在模拟器上先验证 install/launch，再上手机；
  5. 接受“B 站登录/播放可能不可用”的风险。
- 工作量：**L，且版本相关**；不是当前 Phase 1/2 的快速路径。
- 如果目标只是给 NPatch 做测试：第二台手机或模拟器更便宜。
- 如果目标是产品级“官方 B 站 + 改版共存”：这是一条独立产品线，值得单开 experiment 分支，但不建议和当前兼容性分支混在一起。

### 17.6 建议的最小 spike

1. 静态后处理 clone Manifest：重命名 6 个 permission、4 个 authority、taskAffinity；保留可回滚脚本。
2. 定向替换 dex 中的关键旧包字符串（data path、component、action、permission、content URI），不碰类名/调试串。
3. 在 **模拟器** 上安装 clone，确认能否启动；不要在主力手机主空间直接试。
4. 若启动成功，再适配模块 clone target，验证首页/评论/WebView。
5. 若登录/播放失败，记录为 clone 模式的已知限制，不阻塞 Manager/embed 主线。

空间：clone APK 约 190MB；安装后代码 + 数据约 1.5–3GB；测试机 44GB 可用，空间足够。

---

## 18. 实机基线（2026-10-19）

- 测试设备：adb serial 13a7a291；ROM 自报 OnePlus PJZ110、Android 16、arm64-v8a、/data 约 91GB 可用；无 root（su 不存在）。
- 官方 B 站 8.65.0 已卸载；patched B 站 8.90.2（manager 模式）已安装。
- NPatch Manager 1.0.9（833）已安装；模块 com.Bilibili_Innocent_Lab.xposedmodule（1.2.0 debug）已安装。
- Manager 作用域已配置：ConfigProvider 对 tv.danmaku.bili 返回模块包名。
- 模块设置显示：模块已激活，由 NPatch 激活（API 102），统一功能诊断状态正常。
- B 站首页已加载；logcat 出现 BilibiliInnocentLab 的 [BIL] 日志，说明 NPatch loader 和模块入口已工作。
- NFC：com.android.nfc 因该 ROM 的 RoleParser 崩溃循环，已 pm uninstall --user 0；不要再启用。vendor nxp/oplus nfc 服务仍在，但不再弹错误框/抢焦点。
- ADB 输入：在系统设置、B 站、模块设置里 input tap 正常；NPatch Manager 主界面对合成触摸不响应（手动点正常）。Manager 作用域已手工配好，正常测试不再需要进 Manager；改作用域/模块列表时仍需手动。
- 空间：patched B 站安装后约 0.6–1.0GB 代码 + 数据；91GB 可用，充足。

后续正常测试流程（不再需要 Manager）：

    adb -s 13a7a291 shell am force-stop tv.danmaku.bili
    adb -s 13a7a291 shell am start -n tv.danmaku.bili/.MainActivityV2
    adb -s 13a7a291 logcat -d | grep -a 'BIL'

如果要改模块作用域/Manager 设置，目前只能手动点 Manager；Phase 1/2 的 direct 配置/embed 模式目标就是减少对 Manager 的依赖。

---

## 19. 全屏现状 bug 观察（2026-10-19，只记录现象）

### 19.1 测试条件

- 设备：13a7a291，Android 16，patched B 站 8.90.2，NPatch Manager 1.0.9，模块已激活（API 102）。
- B 站未登录；已完成首次引导（性别/年龄/兴趣）。
- 播放器：热门页视频“带着100万，我们见了成人网红...”，详情 Activity：com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity。
- 半屏播放正常：logcat 有 OMX.qcom.video.decoder.hevc、856x480、30fps；BiliPlayerV2 ControlContainerService-control service show。
- 未修改系统旋转/方向设置；测试使用 B 站自己的控制区。

### 19.2 已观察到的现象

1. **全屏入口无法通过 ADB 合成触摸触发**
   - 在播放器右下控制区尝试了多组坐标（1040/1000/980/960/1020/1060 × 600/620/640/650/660/680），均未进入全屏；
   - 每次 dumpsys display 仍是 rotation 0，topResumedActivity 仍是 UnitedBizDetailsActivity；
   - B 站普通按钮（首页卡片、引导按钮、权限弹窗、模块设置）的合成触摸正常，说明不是全局 input 权限问题；
   - 播放器控制区在 uiautomator 树里没有可访问节点，可能是 SurfaceView/自定义 overlay，也可能全屏按钮实际不响应。
   - **状态：未确认是模块 bug 还是 ADB 合成触摸对播放器 overlay 无效；需要手动点一次全屏按钮确认。**

2. **详情页仍出现广告卡片**
   - 模块已激活、统一功能诊断“状态正常”时，视频详情页仍出现“广告,值得买实测，这款无线耳机...”和“广告,比至冬的刀更先到来的是师徒的刀...”等卡片；
   - 这可能是详情页广告净化未覆盖的类型，也可能是无 Root/未登录/功能默认关闭导致；
   - **状态：现象确认，原因未定位。**

3. **播放期间无模块异常日志**
   - logcat 未发现 BilibiliInnocentLab 的失败/异常、FATAL EXCEPTION、ANR in tv.danmaku.bili；
   - 半屏播放、详情页、首页切换未出现崩溃。

4. **首次使用弹窗会打断自动化**
   - 未登录 B 站会弹通知权限、隐私协议、性别/年龄/兴趣引导、部分路径会弹登录注册；
   - 这些不是模块 bug，但会让“打开视频→全屏”自动化步骤变长，测试时必须先处理。

5. **NFC 崩溃是 ROM 问题，已处理**
   - 该 ROM 的 com.android.nfc 在 RoleParser 崩溃循环，弹“NFC服务已停止运行”，抢焦点；
   - 已 pm uninstall --user 0 com.android.nfc，不再出现；不是模块 bug。

### 19.3 已解决：输入源问题（2026-10-19 更新）

> 已确认不是模块 bug，也不是全屏入口坏。B 站播放器 overlay 忽略 touchscreen，但接受 stylus/touchpad/touchnavigation；使用 input stylus tap 已稳定进入全屏，20 轮进出无失败。NPatch Manager 主界面同样用 stylus 可点，不再需要手动。下面“待补”内容保留为初始记录。

手动点一次播放器右下角的全屏按钮：

- 如果能进全屏：记录进入后的 Activity/旋转/控制条/弹幕/返回行为，再复现 5–10 次，观察“看缘分”的间歇性；
- 如果手动也进不去：基本可判定该 B 站版本/该 ROM 下全屏入口本身异常，继续记录 B 站版本、Activity、logcat；
- 如果手动能进、ADB 不能：说明是合成触摸对播放器 overlay 的限制，不是模块 bug；后续用真实触摸/自动化辅助手段。

### 19.4 产物

- captures/bili-home.xml
- captures/video-detail4.xml
- captures/player-controls.xml
- captures/player.png（播放器截图，含控制区）
- logcat 关键行：OMX.qcom.video.decoder.hevc、BiliPlayerV2 ControlContainerService-control service show

### 19.5 自动化命令（已验证）

    # 打开视频详情后：显示控制条 -> 点右下角全屏
    adb -s 13a7a291 shell input stylus tap 540 400
    adb -s 13a7a291 shell input stylus tap 1040 650

    # 退出全屏
    adb -s 13a7a291 shell input keyevent 4

    # 状态检查
    adb -s 13a7a291 shell dumpsys window | grep -o 'mRotation=[0-9]'
    adb -s 13a7a291 shell dumpsys activity activities | grep requestedOrientation

- 进入全屏后截图应为 2160×1080，requestedOrientation=SCREEN_ORIENTATION_LANDSCAPE，mRotation=1。
- 2026-10-19 连续 20 轮进出全部通过，未复现“看缘分”的间歇失败。
- Manager 主界面同样用 input stylus tap；touchscreen 对 Manager 也无效。
- 坐标只适用于当前 1080×2160 竖屏/B 站 8.90.2；换设备/版本先截图或 dump 确认。
- NPatch Manager 作用域核对：content query --uri content://top.nkbe.npatch.manager.provider.config?package=tv.danmaku.bili，返回模块包名即已挂载。
