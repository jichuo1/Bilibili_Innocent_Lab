# NPatch 专项：Feature 拆解、可实施性与实施方案

> 当前实施入口：[NPatch 当前实施路线图](npatch-implementation-roadmap.md)。本文保留历史研究，不是当前实现规格。2026-10-23 已复核关键结论并纠错；F19.1 当前实现及无账号验证见[微信记录](npatch-stage7-wechat-share.md)，F19.2 的限定 QQ 对照无需新增 Hook，见 [QQ 记录](npatch-stage7-qq-share.md)。已完成项以路线图、阶段记录和当前代码为准，探索项先验证；F6 缓存许可方案继续撤回。

> 2026-10-24 [全目录范围复核](npatch-scope-audit.md)已逐项覆盖 F1–F22 / N1–N7：本清单混有通用业务、产品体验和未证实提案，“NPatch 专属”历史分组不代表技术独占或必须实施。已做的通用修复及混合增强也分别列出，不只排除 F13。

> 后续用户已明确选择 **Shizuku → LSPatch，并一并调查其它免 Root 框架**，见[下一阶段计划](no-root-next-stage-plan.md)。F11 的 Shizuku 已在[阶段 8](shizuku-stage8.md)实现并通过一加限定专项；LSPatch “支持不佳”为待复现反馈，不沿用 NPatch 通过状态，其它历史候选仍按范围复核处理。

- 状态：历史 Feature 调研；本轮 B1–B12 问题已闭环，扩展路线仍为提案
- 日期：2026-10-20
- 调研基线：`feat/npatch-compat`，当时 HEAD `80a21910`；以下源码行号与性能数字均为当时观察
- 调研方式：只读代码库 + 只读上游源码（`captures/npatch-src`、`captures/vector-src`、NPatch 1.0.9 build 833 产物字符串交叉验证），共 25 个并行只读调研代理；未修改任何业务代码、未构建、未连接 ADB
- 当前结果：[兼容性专项验收结论](npatch-compatibility-plan.md#2026-10-20-专项验收结论)；本文保留背景与扩展 Feature 提案，不作为未修复问题清单
- 证据口径：旧 `file:line` 属于调研时快照，不保证对应当前行号；源码、产物字符串、推断和实机证据应分别说明。方法名、字符串缺失、统计相关性均不能单独证明执行进程、能力缺失或故障根因。

> 说明：本机 GitHub 域名解析到非公网 IP，`7723mod/NPatch` 的 issue #154/#156/#161 与 build 846 的在线状态**无法核实**，相关结论均为源码内证。

---

## 0. TL;DR

- **真正属于 NPatch 的增量价值集中在 4 条主线**：①配置通道自主化（去 Manager 单点依赖 + 冷启动不阻塞）；②环境自省/体检；③日志与崩溃桥接；④免 Root 的“重启/更新”闭环。
- **历史实测**：曾观察 `admission=2032ms`、`total=2153ms` 及 `Hook 安装链完成耗时=2311ms`，118 条样本中 80 条 admission 打满窗口。这证明当时有启动等待，不能直接证明用户所报闪退的根因。B11 已消除确认无人处理后的空等，当前性能以阶段 5 对照为准。
- **历史优先项 B1–B4 已修复**：关闭门控、状态归位、Manager 不可达误判及诊断徽章已完成回归；后文 F1–F3 保留原调研推导，当前结果见专项验收结论。
- **代码热重载未启用**：`module.prop` 为 `autoHotReload=false`，当前没有完整的 Hook 迁移/重建实现，不能直接启用框架热重载。它与阶段 6 已实现的白名单配置值热应用是不同能力。
- **`--injectdex` 不是子进程的必要条件**：默认修补下子进程照样加载模块，缺口在模块自己的 installer 门控。现有文档相关表述需要更正。
- **当前进度**：阶段 1–6 已完成声明范围；F19.1 微信兼容及 F19.2 QQ 对照均完成限定双模式无账号交接验证，账号内流程按用户决定后排，其余阶段 7 项目继续按路线图研究。

---

## 1. 先纠正 6 个前提

排期前必须先纠正以下假设，否则会把工作量建在错误前提上。

| # | 讨论稿里的说法 | 核实结论 | 证据 |
|---|---|---|---|
| 1 | “NPatch 重签名导致模块 App 侧宿主签名校验误杀，需要 TOFU” | 当前宿主准入端点按目标包的实际 UID、调用身份及租约协议验证，没有以官方宿主证书作为此处准入条件；不能由此推广为“全仓无签名校验”。重装可能改变 UID，必须重新解析和授权，不能沿用旧 UID；TOFU 是否需要取决于具体威胁模型 | `runtime/HostAdmissionEndpoint.kt`、`runtime/HostAdmissionLocalIdentity.kt`、当前授权及恢复记录 |
| 2 | “BIL/Manager 不在后台 → 授权校验失败或 IPC 超时” | 历史测量中模块侧 `admission` 吃满 2000ms；B11 已消除确认无人处理后的空等。NPatch 自身绑定与 Provider 查询仍是另一层开销 | `captures/npatch-bughunt-20261020/baseline-logcat.txt:1575,1960`、`runtime/HostAdmissionContract.kt`、兼容性专项验收结论 B11/B12 |
| 3 | “配置只以 Manager Remote Store 为唯一来源，断了就废” | 上游会镜像成功请求的偏好组及收到的更新，Manager 不可达时可回退宿主本地快照；不保证未请求组/未收到更新已同步，也不代表取得当前运行许可。当前模块另有 DIRECT 通道 | `captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/service/FallbackModuleServiceWrapper.java:89-131` |
| 4 | “接 hotReload 实现免重启生效” | 上游有重载生命周期和旧 Hook 句柄迁移机制，但当前 BIL 不实现完整迁移且显式关闭自动重载；仅开启该选项不能证明功能可用。阶段 6 的动态值更新不依赖代码重载 | `captures/vector-src/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorModuleManager.kt`、`hook/HookEntry.kt`、`app/src/main/resources/META-INF/xposed/module.prop` |
| 5 | “子进程不生效，需要 `--injectdex`” | **错**。默认修补下子进程照样加载模块（`appComponentFactory` shim 每进程实例化）；实测 `:download` / `:ijkservice` 都拿到许可并装了 35 个 Hook。缺口是模块自己的 59 个 installer 主动跳过非主进程 | `captures/npatch-src/patch/src/main/java/top/nkbe/patch/NPatch.java:286-290,508-532,556-561`、兼容性专项验收结论 B8 |
| 6 | “适配缓存改个指纹就能跨重打包继承” | 默认修补的外层 DEX 是 loader，直接审计外层不能代表宿主业务 DEX；不是“永久失效”。阶段 2B/3 已实现原版来源验证、全部原版 DEX 指纹及后台重绑定，安装身份不匹配时仍保留实时定位回退 | `hook/adapter/dex/DexSourceFingerprint.kt`、当前路线图阶段 2B/3、支持说明“重新修补与适配缓存” |

---

## 2. 上游能力基线（可依赖 / 不可依赖）

### 2.1 可依赖

| 能力 | 说明 | 证据 |
|---|---|---|
| Manager 模式 Remote Store | 模块 App 经 `IXposedService` 读写 remote prefs 与 Remote Files；宿主侧只读 | `captures/npatch-src/manager/src/main/java/top/nkbe/npatch/manager/XposedServiceBinder.kt:100-128`、`captures/npatch-src/share/android/src/main/java/top/nkbe/npatch/util/LocalInjectedModuleService.java:19,48-57` |
| 实时配置推送 | `IRemotePreferenceCallback` 真实存在，`VectorRemotePreferences.registerOnSharedPreferenceChangeListener` 可用（NPatch 用 Vector runtime） | `captures/vector-src/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt:30-71,111-121`、`captures/npatch-src/share/android/src/main/java/top/nkbe/npatch/util/NPatchRemoteStore.java:292-305` |
| Remote Files | 平铺文件名；所查接口未设置显式字节上限，不等于无限容量。存储属于创建 `NPatchRemoteStore` 的 Context：Manager 远端与宿主 fallback 是不同目录，不自动镜像文件 | `NPatchRemoteStore.java` 的 `remoteFilesDir/openFile`、`LocalInjectedModuleService.java`、`FallbackModuleServiceWrapper.java` |
| 补丁档案（Patch Profile） | 宿主 manifest `meta-data "npatch"` = Base64(JSON(PatchConfig))，字段含 `useManager/sigBypassLevel/debuggable/lspConfig` | `NPatch.java:409-426,774`、`captures/npatch-src/share/java/src/main/java/top/nkbe/npatch/share/PatchConfig.java:5-21` |
| Manager ConfigProvider | `content://top.nkbe.npatch.manager.provider.config/config?package=<target>`，exported 且无权限校验，可列该目标已启用模块 | `captures/npatch-src/manager/src/main/java/top/nkbe/npatch/manager/ConfigProvider.kt:21-47`、`captures/npatch-src/manager/src/main/AndroidManifest.xml:129-133` |
| Media 日志 | `/sdcard/Android/media/<宿主包>/npatch/log/yyyyMMdd.log`，按日追加、无轮转；含框架日志 + `XposedInterface.log` + 未捕获 Java 异常 | `captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/loader/XposedLogPrinter.java:100-160` |
| 宿主本地 prefs 镜像 | Manager 的 prefs 会镜像进宿主本地 SQLite，Manager 死后回退读它 | `FallbackModuleServiceWrapper.java:89-131` |

### 2.2 不可依赖

| 限制 | 说明 | 证据 |
|---|---|---|
| `--embed` 无 Manager Remote Store 写通道 | 设置 App 不能直接写宿主私有 store；不表示无法建立配置 IPC。当前已通过独立 DIRECT 通道完成 embed 配置、授权与回执 | `EmbeddedXposedService.java`、当前路线图阶段 1–2B |
| embed 硬限制 | 签名绕过上限 High(2)、scope 不可运行期改、更新须重打补丁、`hotReloadModule` 恒 `HOT_RELOAD_UNSUPPORTED` | `EmbeddedXposedService.java:67-96`、`NPatch.java:197` |
| 833 的 EXTREME/Seccomp | 字符串缺失与源码注释仅是风险线索，不能证明二进制缺少防护，也不能证明未经核实的 846 已修复。撤回最低版本 ≥846 建议；当前仅支持固定修复版 833、等级 2 的实测组合，其它等级另验 | 历史间接证据：`svc_bypass.cpp` 与产物字符串；当前基线见支持说明 |
| `config.json` 改 `sigBypassLevel` 无效 | 运行时被 manifest meta-data 覆盖 | `LSPApplication.java:175-198,462` |
| 热重载换 Hook | 见「前提 4」 | — |
| Manager 不可达时的新推送与 Remote Files | 偏好仅能回退已成功镜像的快照；文件调用回退宿主本地服务，并不自动复制 Manager 文件。单纯进程未运行不等于 Binder/Provider 无法重新启动它 | `FallbackModuleServiceWrapper.java` 的 `requestRemotePreferences/openRemoteFile/getRemoteFileNames` |
| Media 日志不含 `android.util.Log` 与 native crash | 官方明确不含任意 `Log.*`、native fatal、tombstone | `XposedLogPrinter.java:27-28` |

---

## 3. Feature 拆解

> 工作量口径：S ≈ 0.5–2 人日，M ≈ 3–7 人日，L ≈ 8–15 人日（含测试，不含实机排队时间）。

### P0 — 必修：已确证缺陷 + 配置通道自主化

#### F1 关闭门控失效 + 直达文档元数据修复（对应 B1，最高优先级）

- **结论**：可行，S。
- **现状**：`settings/remote/PublicationAuthorityStore.kt:26-28` 把 `deliveryEnabled=true, noRootRevision=0L` 写死 → 关闭免 Root 后 NORMAL 路径仍“自洽”通过（`runtime/HostAdmissionSourcePolicy.kt:4-9`）；`hook/HookEntry.kt:4880-4882` 因 `noRootRevision<=0` 不发心跳；关闭路径的 remote 清理失败被静默（`runtime/noroot/NoRootSupportController.kt:133-147`）。
- **实施**：`document()` 按 `NoRootSupportStore.isDesiredEnabled` 注入 `noRootRevision` 与 `deliveryEnabled`；`HostAdmissionEndpoint` 对 `deliveryEnabled=false` 的文档直接 `denied`，不允许 NORMAL 回退重新授权；关闭路径的失败必须显式报错。
- **验收**：JVM 断言「关闭态 + NORMAL 文档」不得返回 NORMAL；实机重跑 B1 步骤，宿主不得再装 Hook。

#### F2 状态机修复：`DISABLE_RESTART_REQUIRED` 永不清除（B3）+ Manager 不可达误判（B4）

- **结论**：可行，S。
- **现状**：`runtime/noroot/NoRootSupportStore.kt:180-184` 关闭态无条件保留该状态；唯一清除点 `recordDisabledAck`（:291-315）要求 `active=false`，而宿主唯一发送点 `HookEntry.kt:4895-4903` 写死 `active=true`；`runtime/noroot/NPatchRemoteGateway.kt:118-134,216-218` 把“拿不到 binder”归类成身份拒绝。
- **实施**：宿主在“未装 Hook”时回执 `active=false`（或模块侧确认无 Hook 后主动清除）；网关区分「binder 不可达」与「显式拒绝」，前者映射 `ManagerMissing` / `ConnectionTimeout`。
- **验收**：契约测试 + 实机「关→强停→重启」后状态归位；停 Manager 后开/关不再误报。

#### F3 诊断入口徽章误报 + `NPatch selected` 日志噪声（B2/B9）

- **结论**：可行，S。
- **现状**：`settings/remote/RemoteHookConfigStore.kt:244-245` 按设计短路成 `Failure("NPatch selected")` 并置 FAILED（:273-281）；详情页有豁免判据（`diagnostics/ModuleDiagnostics.kt:185-199`），入口徽章（`ui/activity/MainActivity.kt:1050-1069`）没有用。
- **实施**：徽章与详情页共用 `standardPublisherBypassed`；把「按设计短路」从 FAILED 拆成独立 `BYPASSED`；`logFailure` 不再对短路打 W。
- **验收**：JVM `ActivationCardVisualSpec.diagnosticsSummaryState(publishFailed=true, standardPublisherBypassed=true) → READY`。

#### F4 回执 `SESSION_MISMATCH` 误码与丢回执（B6）

- **结论**：可行，S。
- **现状**：`runtime/HostReceiptRegistry.kt:57` 预置 `SESSION_MISMATCH`，任何 `return false` 都以此码出栈（:42,68-71,76-79,86）。
- **实施**：每条失败分支赋具体码（`SOURCE_MISMATCH` / `SURFACE_MISMATCH` / `STORE_FAILED` 等）；复核 `ReceiptSessionGate.accept` 在正常重启下的判定。
- **验收**：JVM 覆盖「同进程重复注册」与「进程重启」两种场景。

#### F5 NPatch 模式判定 + 补丁档案（Patch Profile）

- **当前状态**：阶段 1 已完成，`NoRootRuntimeDetector` 已接入生产；下述设计保留调研背景，不再作为死代码清理任务。
- **事实源**：宿主 manifest `meta-data "npatch"`（Base64 JSON）→ `useManager`；`top.nkbe.npatch.remote` / `manager.provider.config` 是否可解析；框架名 `"NPatch"` + API/能力位（`settings/remote/ModernFrameworkStatus.kt:56-58`）。
- **实施**：新增 `NPatchRuntimeMode`（MANAGER / EMBED / UNKNOWN），替换 `NoRootRuntimeDetector` 的死代码；补丁档案字段：`useManager / sigBypassLevel / debuggable / lspConfig.API_CODE,VERSION_CODE`。
- **验收**：JVM 判定矩阵；实机 manager 与 embed 各一轮。

#### F6 冷启动非阻塞化（含子进程重复 admit）

- **旧方案撤回**：保存旧许可并直接安装、后台再授权、不可达仍用快照及子进程跳过独立授权，均无法证明用户之后没有关闭或撤回条款；保留协议字段并不能弥补这一语义缺口。“300ms 装完 Hook”也不再作为目标。
- **当前实现**：阶段 5 已完成主进程按当前获准配置省略无消费者定位、已知子进程省略主进程专用准备；每进程当前许可与早期 Hook 注册期限保留，不整体后移安装。
- **结果**：默认同步中位数 Manager 减少 21.95%、embed 减少 17.57%（未达原 20% 期望）；口径、首次调用与失败恢复证据见[阶段 5 实际结果](npatch-stage5-performance-results.md)，不承诺所有设备相同收益。

### P1 — 可观测性与支持效率

#### F7 NPatch 体检面板（Environment Inspector）

- **结论**：可行，S（「零报告变更」路线）。
- **事实源**：宿主 `meta-data "npatch"` / `assets/npatch/config.json`（loader `lspConfig.VERSION_CODE`）、模块 App 侧 `top.nkbe.npatch` versionCode、`ModernFrameworkStatus`、ABI。
- **体检项**：①修补模式；②签名等级 vs embed 上限 2；③`debuggable` 标志；④Manager 是否安装/版本；⑤**Loader 与 Manager 版本一致性**（宿主报 loader versionCode，模块 App 比 Manager versionCode；仅 `useManager=true` 告警）；⑥配置来源；⑦ABI 与模块 native 支持；⑧`injectdex`（**无权威字段，显示「未知」**）。
- **实施**：先做「塞进现有 `HOST_BOOTSTRAP` / `NO_ROOT` 详情 + 导出报告」的 S 版，**不动** `diagnostics/DiagnosticReportCodec.kt` 的 `bootstrap.length()==11` 硬断言；确认事实源可靠后再升协议（`runtime/HostRuntimeDiagnostics.kt:102` schema 3→4 + `formatVersion` 7→8 + 同步测试）。
- **验收**：诊断页能看到「manager/embed、sig level、loader/manager 版本、配置来源」。

#### F8 崩溃与日志桥接（Crash / Media Log Bridge）

- **结论**：可行，S/M。分两半：
  - A 模块自挂 `UncaughtExceptionHandler`（链式交还已有 handler）→ 崩溃前写宿主私有目录（≤32KB、留 3 份、脱敏），下次主动查询读回进诊断。异常栈只能提供排查线索，不能单独判定模块/宿主根因，也不覆盖 native 信号、ANR 或 handler 安装前异常。
  - B 宿主侧读自己的 `externalMediaDirs/npatch/log` 尾部 64KB（崩溃窗前后约 200 行），随回执送模块 App，做成「一键导出」。**不要**把整份 Media 日志塞进诊断 JSON。
- **当前状态**：阶段 4 已由 `runtime/noroot/NPatchCrashStore.kt` 实现宿主侧读取与有界证据回传，使用 `externalMediaDirs`；不要求模块 App 获取全盘权限。旧“全仓未使用/无消费者”扫描结果不能作为当前事实，范围见[支持证据协议](npatch-stage4-support-protocol.md)。
- **验收**：DEBUG 版「测试崩溃」按钮构造一次可控崩溃，验证落盘→冷启动读回→导出。

#### F9 启动耗时基线（可并入 F7）

- **结论**：可行，S/M。耗时目前只进 logcat（`HookEntry.kt:4985-5005,4944-4947`），诊断协议/页面/报告均无时长字段。
- **建议**：模块侧维护最近 8 次样本的 max/p50（单次冷启动只有一个样本），只传枚举与整型毫秒；先只进诊断页与 `module.log`，等 admission 优化收敛后再决定是否进导出报告。

### P2 — 体验

#### F10 `--embed` 支持（最小闭环）

- **当前状态**：最小闭环已被正式支持目标取代，阶段 2B 已在限定矩阵准入；下述旧缺口和降级预案不再代表现状，不回退到“设置 App 常驻的实验模式”。
- **结论**：有条件可行，M。真正的阻塞是 4 处口径：`noRootRevision` 恒 0、DIRECT 被「通信兼容模式」卡住（`settings/remote/PublicationAuthorityStore.kt:58` + `HostAdmissionSourcePolicy.kt:7`）、Manager 缺失被当硬失败、无 embed 模式判定；外加两个必须实机验证的未知数（宿主包可见性、签名等级 2 是否够登录/播放）。
- **实施**：F5 的模式判定 + `document()` 修 revision + `directAllowed = compat || embed` + `HostAdmissionSourcePolicy/Endpoint` 在 embed 且免 Root 开启时优先 DIRECT（并校验 revision 与 `isDesiredEnabled`）+ `runtime/noroot/NoRootSupportStore.kt:363-388` 的 `exportState` 增加 direct 变体（不再要求 `remoteSynced`）+ UI 文案拆「Manager 缺失 / 集成模式就绪」。
- **硬限制必须在 UI 写明**：签名 ≤2、更新须重打补丁、scope 固定、无热重载。
- **降级预案**：Provider/广播都被包可见性拦死 → 只留 local + 广播并标「实验性、需模块 App 常驻」。

#### F11 免 Root 一键强停/重启

- **结论**：可行，M；但**先做便宜替代**：现在 `ui/activity/MainActivity.kt:208-221` 的 `openBilibiliAppDetails` 只跳详情页，补一次 `getLaunchIntentForPackage` 自动拉起即可，零依赖（S）。
- **当前状态**：零特权重启向导已在阶段 6.1 交付；Shizuku 已在阶段 8 接入并通过一加备用机 Manager / embed 的限定启停专项，见[记录](shizuku-stage8.md)。随后处理 LSPatch 及其它免 Root 框架兼容调查，详见[计划](no-root-next-stage-plan.md)。Root / Sui 和矩阵外组合不因一加 shell 结果变为已测。
- **Shizuku 实施前置**：依赖与目标版本文档须实际核对，不能沿用旧离线缓存结论。UserService 权限取决于实际启动身份与授权，不能固定认为都是 shell 2000；首期限定强停/重新拉起、当前回执确认及服务/授权失效恢复，保留零特权和 Root 原路径，不修改用户方向设置。

#### F12 配置热应用（已安装 Hook 的动态值子集）

- **当前状态**：阶段 6.3 已完成。准入依据是已安装的叶项证据及消费者语义，不能简单按“是否开关”分类；部分复制开关已在白名单，初次启用未安装能力仍需重启。当前通道每次重新授权并验证实际加载 APK 内容，下述旧设计不是完整实现规格。
- **结论**：有条件可行，M。宿主侧配置是启动期一次性快照（`hook/config/HookConfigSource.kt:19-34`、`HookEntry.kt:4923` 只 set 一次），值被 installer 构造参数固化。
- **实施**：`LiveHookConfigSource`（AtomicReference + revision）+ 复用现有「模块 App → 宿主进程」签名广播通道（`runtime/InjectedUiLocale.kt:101-111,235-257` 是现成「即时生效」范式）+ 首批三类：自由复制 4 个 volatile、弹幕权重/彩字、首页标题关键词/时长/播放量。
- **不要**做全量热改：开关翻转/布局注入本质是重挂 Hook 与重建 UI（`hook/HookPointRegistry.kt:261-269` 已有 DUPLICATE/半初始化风险）。替代：设置页按「即时生效 / 需重启」分级 + 「立即应用」按钮。
- **注意**：libxposed `service` 的 `RemotePreferences` 监听在 service jar 里是空实现，但 NPatch 用的是 Vector 实现（有真实回调）；不要依赖 service jar 行为。

#### F13 全屏视觉引擎冻结

- **范围归属**：通用视觉性能候选，尚无 NPatch 差异证据，退出本专项默认排期。下述测量前置只在未来另行立项时适用。
- **结论**：待测量的负载优化，不按“修闪退”立项；20 轮未复现也不能证明不存在间歇故障。
- **设计前置**：先核对实际 Lumen 版本、宿主播放器全屏时是否仍有活跃会话及采样成本，再比较预算降档、暂停和冻结。Insets 或横屏方向都不能单独证明播放器全屏；每帧轮询本身有成本，设置监听器还需避免覆盖宿主现有处理。
- **退出条件**：有实测收益，且竖屏全屏、横屏、分屏、后台恢复、反复进出后的颜色和会话恢复正确；没有收益则不新增机制。旧源码存在 `pause/resume` 不等于冻结方案已可交付。

#### F14 漫游「两次重启」缩短

- **当前状态**：阶段 6.4 已按宿主/漫游内容身份、实际隔离加载器观察、完整缓存及手动重建代次完成；下述只按版本原位刷新/自动二次重启的旧建议不作为当前实施步骤，不承诺所有环境一次重启。
- **结论**：有条件可行，M。机制已定位：首启删缓存 → BiliRoaming 全量分析（5–6s）产物缺 `biliAccounts.getAccessKey`、其自身校验必失败；补齐只能在 `Application.onCreate` 之后异步做 → 次启才走快速路径。
- **实施**：①过期分支在 `hasHookData` 且宿主 versionCode 未变时改走 `patchHookInfo` 原位刷新、不删（`hook/RoamingCompatHook.kt:1222-1258`）；②`reportScanResult` 时备份完整缓存到宿主 cacheDir，开启时键匹配即恢复；③首启重建完成后调用现有一键重启（`ui/activity/ConfirmationDialogs.kt:425-500`）；④改三语文案（`res/values*/strings.xml` 的 `roaming_compat_tip`）与 `README.md:320,400,472,477`。
- **降级**：不要取消「删除重建」（旧 hook 点跑新宿主风险更高）；保留两次但自动第二次。

### P3 — 探索 / 建议降级

#### F15 适配缓存稳定指纹

- **当前实现**：阶段 3 已完成后台核对全部原版 DEX 内容及缓存边界后重绑定；不能把 ZIP entry CRC 单独当作可靠内容身份或安全证明。`--injectdex` 与其它来源必须按实际可读来源验证，不凭缺少 `origin.apk` 就采用外层 loader 指纹。详见支持说明“重新修补与适配缓存”。
- **跨进程外置只解决「卸载重装」一次，不值得优先做**；确需跨卸载用宿主 `externalMediaDirs` 单文件，而非 Remote Files/Provider IPC。
- **历史缺口已处理**：当前 `DexSourceFingerprint.kt` 明确原版来源与 DexKit 共用；旧行号不能再作为未修复清单。

#### F16 同进程模块雷达

- **结论**：仅具备部分只读信号，尚未证明能可靠识别同进程已加载模块。ConfigProvider 返回目标的配置模块列表，不能证明模块已加载或存在冲突；`QUERY_ALL_PACKAGES` 也不能证明加载状态。锚点类是否能被当前 ClassLoader 看到须单独验证，尤其是隔离模块加载器与 embed。
- **纪律**：必须 fail-unknown、只提示不自动关停、≥2 独立信号、未知宿主版本静默。

#### F17 子进程诊断

- **结论**：S。只做「诊断页显示当前进程/是否加载/Hook 数」，**不建议**开 `--injectdex`（改变 dex 落点、丧失 entry-hook 隐蔽性，且默认修补已覆盖子进程）；同时更正 `docs/npatch-compatibility-plan.md:84,148` 的表述。

#### F18 共存包名（`-p`）

- **结论**：L，**建议放 Phase 4 / 独立 experiment**。
- **复核要点**：`staticScope/scope.list` 在 NPatch 下是死元数据（`ModuleMetadataParser.java:107,146`、`ModuleMetadataReader.kt:120,181` 均无消费者），**不是**改动点；最先撞上的硬故障是「主进程判据 `processName == TARGET_PACKAGE` 全量失效」（84 处）与 13 处 `getIdentifier`；模块侧改动约 70 文件、300–500 行机械替换（M）。
- **整体是 L**：6 个未改 permission、4 个非前缀 authority、4 个 taskAffinity、267 处 dex 常量全在宿主 APK 内，安装期 `DUPLICATE_PERMISSION` / `CONFLICTING_PROVIDER` 模块侧兜不住，必须先做宿主 Manifest/dex 后处理。
- **建议 spike 顺序**：先宿主后处理确认能装能启动 → 再改模块 target identity。

#### F19 微信兼容与 QQ 对照（限定无账号部分完成，账号内待验）

- **场景与排期（2026-10-24 更新）**：用户所指是微信/QQ 自身被 NPatch/LSPatch 重签名后，B 站误报未安装。微信已按仅 Hook B 站、保留原生分享的方向实施；QQ 已专项对照原签名与 NPatch 重签名 9.2.20，未复现误报，双模式原生交接止于登录页，无需新增 Hook。调查见[完整 F19 调查](npatch-f19-share-investigation.md)，当前结果见[微信记录](npatch-stage7-wechat-share.md)和 [QQ 记录](npatch-stage7-qq-share.md)。
- **准确报错分支**：B 站 8.90.2 的 `kz3.a.t()` 以 `createWXAPI(..., true)` 开启微信证书检查；`isWXAppInstalled()` 返回 false 后显示 `share_global_string_31`（`(>_>)请先安装微信~`）并抛出 `ShareException(-234)`。SDK 在 B 站进程查询微信自身证书，与内置证书比较；同 APK 的 `kntr` 分享包装也开启该检查。
- **当前实现**：默认关闭的兼容选项已对两套已审计分享包装使用 SDK 的 `checkSignature=false`，覆盖新建/已有实例，保留包存在、参数、目标和消息/回调处理。仅适配 B 站 8.90.2；Manager/embed 的普通视频好友/朋友圈交接已有实际证据，账号内预览、取消回调和投递按用户决定保留待验，详见[实施记录](npatch-stage7-wechat-share.md)。
- **QQ 独立结论**：`hz3.a.M()` 检查 QQ/TIM 包和版本，失败或启动抛 `ActivityNotFoundException` 时提示“尚未安装QQ/TIM或当前QQ/TIM版本不支持分享~”（`-234`）。`QQShare`/`BaseApi` 的已追踪分享路径继续检查参数和 Intent 解析，没有同类 QQ 客户端证书比较。SDK 的其它签名方法不等于参与该路径；旧 `AppUtil.getSignatureFromApk` 引用撤回，不据此新增 QQ 绕过。限定实机结果与该路径一致；包缺失时 QQ / 空间入口正确隐藏。
- **实现边界**：既有 `SharePurifyFeatureInstaller` 继续独立处理内容净化/小程序转链接；本项由 `WeChatShareCompatFeatureInstaller` 和 `WeChatShareCompatAccess` 处理分享 SDK 实例。旧提案的 `ShareDegradePolicy` 未采用；本轮普通视频触发传统包装，备用 `kntr` 入口及其他业务类型没有外推为实机已验证。
- **实施后验收**：将 B 站与接收方各自的原签名/修补维度分开，使用确切重签名客户端对照；包缺失仍正确失败。普通视频微信好友/朋友圈与 QQ 好友/空间已有双模式交接证据；其它消息类型、备用包装真实入口、账号内预览和取消/投递仍按两份阶段记录保留待验。
- **降级地位**：复制链接/系统分享只作为另外确认的客观限制下的可选兜底，不是微信重签名证书检查的实现目标或必经路径，不自动替换原生渠道。
- **验收边界**：安装检测、拉起、接收方预览、发送完成分别记录。真实外部发送须获得用户明确授权；未发送时不宣称投递成功，未测试的客户端/类型保持未知。既有 `SHARE_HANDOFF` 仅证明本地面板、系统交接与取消。
- **可复核输入**：原版 APK SHA-256 `804ab5d719da42f257aac6237dda408151b93ecff71a0da99c5f32c71161a2e5`，已核对为 8.90.2 / 8902100，并与修补记录一致；源码、资源 ID、寄存器分支及基线日志索引见调查文档。结构化反编译的微信证书匹配成功分支曾失真，已用 fallback 模式核对，不能把该 Java 输出解释为 SDK 恒失败。

#### F20 DexOpt（ART 编译加速）

- **结论**：建议只做「能力探测 + 引导」。
- **证据与未知**：所查 loader 的 `disableProfile` 会处理当前 profile 文件并设为只读，但不能仅凭这一点断言 `speed-profile` 无效或只有 `speed` 有意义。默认修补的业务代码来自原版 APK，包编译命令是否覆盖它、是否登记为 secondary dex、升级后哪些产物失效及真实收益均须在目标系统核对；`--secondary-dex` 不保证发现任意动态代码文件。命令授权与 Shizuku 可用性亦须现场确认，不直接执行优化。
- **设计方向**：现有诊断耗时可作为前后对照的一部分；是否可检测业务代码 AOT 状态、Manager 是否有适用入口及其编译覆盖范围须核对具体版本，不能仅检测到 su 就提示应优化。

#### F21 宿主偏好快照

- **范围归属**：通用备份/产品需求；只读展示和手动导出同样不属于 NPatch 兼容前置，移出专项默认排期。
- **结论**：**建议不做写回**，只做「只读展示 + 手动导出」。
- **原因**：B 站播放/弹幕偏好多走服务端同步，本地收益低；`SharedPreferencesImpl` 内存副本导致外部写回会被宿主 commit 覆盖（模块已有三态「未知不干预」先例：`hook/RoamingCompatHook.kt:625-640`）；`settings/backup/SettingsBackupCodec.kt` 硬绑 `PRODUCT_ID`、`SettingValue` 类型不匹配（只有 Bool/Int/String），复用成本高；且与免 Root 无因果关系。

#### F22 跨进程证书 TOFU

- **结论**：**不需要**（见「前提 1」）。
- **可选最小版本**：如果只为保护 DIRECT 全量文档（含语义 API key），可只在 DIRECT 路径首次钉扎证书摘要 + `installTime/versionCode`，变更即待确认、不自动轮换、不自动拒绝。S。

---

## 4. 建议新增的 NPatch 专属 Feature

本标题保留历史名称。N3 含通用菜单入口，N2/N5 含通用安全/备份需求，N6 也复用通用归档技术；实际专项关联及已完成范围以[逐项复核](npatch-scope-audit.md#f1f22n1n7-全量归类)为准，不能据标题继续全部实现。

### N1 补丁参数推荐器 / 补丁档案卡
复用现有诊断生成可复制/手动导出的精简档案卡。模式、签名等级、Loader 等只展示实际可知值，`injectdex` 等未知字段不猜测；当前支持基线与参数建议分开，不把等级越低/版本越高描述为越稳。分享前提供内容预览，避免带入账号、路径或凭据；不自动发送。与阶段 4 支持包区分精简摘要和详细证据，避免重复入口。

### N2 安全模式 / 崩溃自愈
待设计，不能沿用原“只能卸载重装”的前提：独立设置 App 可关闭免 Root，下一次冷启动按当前端点拒绝功能安装；loader 在模块授权前崩溃则是另一层问题，模块安全模式无法保证覆盖。

先评估现有关闭/冷启动救援是否足够，再设计有必要的显式手动模式与状态回执。自动保护另行论证：`Application.onCreate` 完成不等于首帧，缺少完成标记不能区分强停、回收、断电、ANR 与崩溃；bootTime 也不能独立解决归因。若增加自动触发，需定义进程范围、启动身份、观测期限、计数/恢复和误报验收。“核心净化”仍可能致崩，不能未经故障隔离就作为安全默认子集。当前未实现，不把本提案称为可靠自愈。

### N3 宿主内「BIL 设置」入口 + 首次使用向导
复用 `runtime/RoamingSettingsLauncher.kt:31-252` 的「前台宿主发一次性固定目标 PendingIntent」整套设施（不要由模块进程直接 `startActivity`，契约测试已钉死且受 Android 10+ 后台限制），在「我的/设置」页注入第二个入口；首启向导把 F5/F7 的检测项串成分步引导（约 80% 检测项已有实现，缺「签名等级」和「后台限制」，签名等级可零成本从宿主 manifest 解出）。S/M。

### N4 模块版本漂移检测 + 免 Root 更新闭环
模块升级后，NPatch Manager 里可能仍指向旧 APK 路径/旧版本 → 心跳的 `moduleVersionCode` 与快照不匹配（`runtime/noroot/NoRootSupportStore.kt:270-273`）→ 永远显示「需要重启」。新增诊断项明确提示「Manager 中配置的模块版本 X ≠ 已安装模块 Y，请在管理器中重新选择模块 APK」，并提供跳转 Manager 的按钮（`ui/activity/FrameworkManagerLauncher.kt:16-30`）。S。

### N5 Remote Files 三合一保险库
待重新设计。业务设置备份、可重建适配缓存、诊断材料应分别定义恢复和保留范围；不保存或恢复许可、条款、租约及旧回执作为运行授权。Manager Remote Files 不是 embed 的共同存储，宿主 fallback 也不自动镜像其文件；卸载宿主后能否恢复取决于副本实际存放位置及模块身份。当前阶段 3 缓存复用与阶段 4 本地支持包不能被描述为跨卸载保险库。双模式统一方案、容量/淘汰/脱敏、内容版本校验及清数据边界确定前不承诺实现。

### N6 NPatch 诊断一键打包
阶段 4 已完成本地有界 ZIP，包含诊断报告、补丁档案、已采集耗时及白名单崩溃/Media 线索，不自动发送。它不是完整日志或全故障证据；native、ANR、未观测启动等仍有限制，详细范围见[支持证据协议](npatch-stage4-support-protocol.md)。

### N7 授权健康度面板
阶段 4 已完成。Manager 可达性、配置来源及选中运输通道分别展示；四路比率仅为决策前已收结果中的授权取得率，不是所有请求的成功率，未完成/迟到及零分母保持未知。最近 8 次已采集启动不代表全部历史启动，见支持证据协议。

---

## 5. 建议路线图与工作量汇总

| 阶段 | 内容 | 预估 | 是否需要实机 |
|---|---|---|---|
| **Phase 0（可立即做）** | F1、F2、F3、F4、F5 | 约 1 周 | 否（JVM + 契约测试即可），F5 需一轮实机确认 |
| **Phase 1（核心）** | F6 冷启动非阻塞化 + 子进程去重 + N7 打点 | 约 1.5–2 周 | 是（manager/embed 各一轮冷启动、双杀） |
| **Phase 2（支持效率）** | F7 体检面板 + F8 崩溃/日志桥接 + F9 基线 + N6 一键打包 + N4 版本漂移 | 约 1.5–2 周 | 是（构造崩溃、导出） |
| **Phase 3（体验）** | F11（先做零依赖替代）、F12 热应用子集、F13 冻结、F14 漫游、N3 宿主内入口 + 向导 | 约 2–3 周 | 是（全屏、漫游、宿主菜单） |
| **Phase 4（历史提案）** | F10/F15/F17 已覆盖；F19 微信已规划待实施、QQ 实施时研究；F16/F20/N2/F18 仍为探索 | 旧估计作废 | 按当前路线图确定 |

上表其余 Phase 行同样只保留历史分组与旧估计，不再作为实施指令；当前完成状态、下一步及撤回方案以路线图为准。

---

## 6. 需要更正的现有文档

- `docs/npatch-compatibility-plan.md:84,148`：子进程不需要 `--injectdex` 才会加载；默认修补已覆盖，缺口在模块 installer 门控。
- `docs/npatch-compatibility-plan.md` §C 的 `--injectdex` 定位：它是上游 advanced opt-in，会改变 dex 落点并丧失 entry-hook 隐蔽性，不建议默认开。
- `docs/npatch-compatibility-plan.md` §17.4：把 `staticScope/scope.list` 列为改动点是误判（NPatch 下无消费者）；真正的第一道硬故障是主进程判据与 `getIdentifier`。
- 历史 §2.4 的 Manager 单点缺口已由 DIRECT 通道解决；本地偏好镜像不等于当前运行许可，不能采用“旧许可快照优先”。
- 原版 DEX 来源问题已由阶段 2B/3 处理，不再将旧 `VersionAdapter` 行号列为当前缺陷。
- README 的支持边界按当前固定修复版 833、等级 2 实测组合描述；撤回“≤2 最稳”这一未经比较验证的泛化建议。

---

## 7. 前置条件与风险

1. **实机资源是最大瓶颈**：Android 包管理设备级共享，官方版与 patched 版**不能同机同用户共存**（`docs/npatch-compatibility-plan.md:305-309`）；当前 ADB 手机被其他会话占用。建议第二台手机 / 模拟器（x86_64 镜像有 ARM translation 风险，只用于 UI 与通道验证）。
2. **上游版本**：固定修复版 833、等级 2 是当前限定基线；846 的状态及修复未经核实，不以版本号或字符串缺失作为升级依据。
3. **协议硬断言**：`runtime/HostRuntimeDiagnostics.kt:102`（schema=3）与 `diagnostics/DiagnosticReportCodec.kt`（`formatVersion=7`、`bootstrap.length()==11`、`framework.length()==9`、`items.length()==entries.size`）是硬门禁，任何字段新增都要同步测试；建议 P0/P1 阶段一律走「零报告变更」路线。
4. **跨 ROM 风险**：包可见性、关联启动、后台广播、Shizuku 被杀——所有「跳转/唤起/广播」类能力都必须 fail-soft + 明确降级文案。
5. **不要做的事**（避免返工）：接 hotReload 做免重启、写回宿主 prefs、为单次排查扩诊断协议、把 `--injectdex` 当子进程前提、按「修闪退」立项全屏冻结。

---

## 8. 附录：关键代码位置索引

| 主题 | 位置 |
|---|---|
| 冷启动授权与安装链 | `hook/HookEntry.kt:4966-5040,5083-5140` |
| 四路授权客户端 | `runtime/HostAdmissionClient.kt:79-192`、`runtime/HostAdmissionRoutePlan.kt`、`runtime/HostAdmissionRouteClassifier.kt` |
| 授权端点与来源选择 | `runtime/HostAdmissionEndpoint.kt:48-107`、`runtime/HostAdmissionSourcePolicy.kt:4-9` |
| 权威发布记录 | `settings/remote/PublicationAuthorityStore.kt:26-28,46-75` |
| 免 Root 快照与心跳 | `runtime/noroot/NoRootSupportStore.kt:250-320,340-412`、`NoRootSupportState.kt:48-96` |
| NPatch 网关 | `runtime/noroot/NPatchRemoteGateway.kt:38-70,118-134,152-154,205-227` |
| 宿主私有缓存范式 | `runtime/TargetAppStorage.kt:22-32`、`hook/adapter/dex/AtomicJsonCache.kt:12-46` |
| 诊断模型与协议 | `diagnostics/ModuleDiagnostics.kt:20-33`、`diagnostics/DiagnosticReportCodec.kt:21,116-134,258`、`runtime/HostRuntimeDiagnostics.kt:34-46,101-187,222-285` |
| 诊断页 | `ui/activity/DiagnosticsActivity.kt:547-609` |
| 设置目录 | `settings/backup/SettingsCatalog.kt:34-37`、`settings/backup/SettingsBackupModels.kt:49-110` |
| 免 Root 设置卡 | `ui/activity/SettingsCardSections.kt:1460-1504` |
| 一键重启 | `ui/activity/ConfirmationDialogs.kt:430-507,528-537` |
| 宿主内设置入口范式 | `runtime/RoamingSettingsLauncher.kt:31-252`、`receiver/RoamingOpenReceiver.kt:66-97` |
| 漫游扩展 | `hook/RoamingCompatHook.kt:436-516,902-989,1222-1258` |
| 分享净化 | `hook/feature/SharePurifyFeatureInstaller.kt:130-141,155-199` |
| 版本适配 | `hook/VersionAdapter.kt:90,2830-2846,3062-3097`、`hook/adapter/dex/DexSourceFingerprint.kt:41-84` |
| 液态玻璃/视觉引擎 | `hook/hostui/common/HostSurfaceScope.kt:60-83`、`hook/hostui/background/HostDetailSurfaces.kt:99-112,168-180` |

---

## 9. 调研产物与证据索引

- 上游源码副本：`captures/npatch-src/`（NPatch）、`captures/vector-src/`（Vector runtime）
- 修补产物：`captures/npatch-out/host-current-833-npatched.apk`（含 `assets/npatch/config.json`、`assets/npatch/origin.apk`）
- 实机日志与截图：`captures/npatch-bughunt-20261020/`
- 实机问题闭环记录：[兼容性专项验收结论](npatch-compatibility-plan.md#2026-10-20-专项验收结论)（B1–B12，临时清单已移除）
- 原始计划：`docs/npatch-compatibility-plan.md`

## 10. 2026-10-23 文档复核记录

本节记录实施前的文档复核；随后 F19.1 实现及有限验收见[阶段记录](npatch-stage7-wechat-share.md)。以下“尚未实现”等描述保留当时状态。

本次复核当前路线图、Feature 研究、兼容性档案、支持说明及相关阶段/验收记录；重点核对影响下一步决策的技术判断，并抽查相应当前代码和本地上游源码。不是全仓实现正确性审计，也没有重跑历史实机验收。历史样本/测试数字继续保留，不将文档修正冒充新增运行证据。

| 问题 | 本次处理 | 实现核查与边界 |
|---|---|---|
| F19 把 B 站内置 SDK 的微信证书检查误当作对方进程检查 | 按用户澄清定位重签名接收方场景，并确定微信仅 Hook B 站、保留原生分享的实施方向；QQ 实施时研究 | 已核对微信未安装资源/分支、注册/发送再检查及 QQ 包/版本/Intent 链路，见独立调查；实现和重签名客户端验收尚未执行 |
| 历史授权等待被直接归因为用户闪退 | 改为历史等待证据，撤回因果判断 | B11 与阶段 5 有独立验收，不能由此宣称全部闪退已解决 |
| F6 旧许可缓存被称为不放宽边界 | 删除可执行式旧步骤，明确撤回原因和当前替代方案 | 对照现有授权端点及阶段 5 设计，不把旧缓存当新鲜许可 |
| N2 以 onCreate/bootTime 判定首帧和崩溃，并声称只能卸载 | 改为先评估现有关闭救援、再论证手动模式及自动保护 | 现有关闭端点与零功能安装记录已存在；loader 授权前故障不在模块保护保证内 |
| F8 异常栈被当作根因证明 | 降为诊断线索，注明 native/ANR/安装前覆盖缺口 | `NPatchCrashStore/NPatchCrashHandler` 与阶段 4 协议本来就有界，不需要把实现改成根因分类器 |
| Remote Files 被描述为无限容量、统一 Manager 存储及离线文件镜像 | 区分 Context 所属存储、偏好镜像、文件 fallback；重写 N5 | 抽查 `NPatchRemoteStore`、`LocalInjectedModuleService`、`FallbackModuleServiceWrapper`；当前支持包不是保险库 |
| 依据字符串缺失推荐未经核实的 ≥846 | 撤回版本建议，保持实测固定基线 | 不修改依赖或修补工具，不推断高签名等级安全性 |
| F13 全屏信号、F16 模块列表、F20 DexOpt 结论超出证据 | 增加负载/信号、实际加载及真实代码编译覆盖的验证前置 | 探索提案不等于已有实现，也不据此新增 Hook 或执行编译命令 |
| F5/F10/F12/F14/F15 等旧现状与已交付能力混杂 | 加入当前状态，链接阶段记录；DEX 指纹精确注明 ZIP 元数据口径 | 适配指纹用于正常缓存失效，不是逐字节认证；热应用/漫游按当前边界，不回套旧设计 |

本次生产源码未修改，按本地规则不运行 Android 构建、测试或 Lint；核对文档差异、引用和残留旧断言。F19 代码调查另记于独立文档；微信已规划待实施及验收，QQ 实施时研究，其它阶段 7 项目继续探索。
