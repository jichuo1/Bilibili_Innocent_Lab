# 阶段 9：LSPatch 支持与免 Root 框架盘点

> 当前支持政策（2026-10-28）：正式发布仅开放本地（Manager）模式，不支持集成（embedded）模式。debug/release 构建中 `EmbeddedModePolicy.SUPPORTED` 恒为 `false`；只有 CI 的 `embedded` 变体（见[集成模式内测构建](embedded-test-build.md)）编译为 `true`，产物只供内测群验证，不改变对外支持声明。NPatch / LSPatch 集成模式共用该门控，实现保留，未来是否恢复另行决定。下文集成模式的专项结果及操作步骤属于门控前的历史验证记录。

2026-10-24。对应 [issue #31](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues/31)，当前排期见[实施路线图](npatch-implementation-roadmap.md)。本轮只处理框架加载、配置、授权、运行确认和恢复，不扩大到通用业务适配。

## 模式与支持范围

JingMatrix LSPatch 1.2（487）中文界面的**“本地”是 Manager 模式**：`useManager=true` / `--manager`，模块由管理器提供；**“集成”是 embedded 模式**：`useManager=false` / `--embed`，模块 APK 被嵌入宿主。不能把“本地”误当成 embedded。

本轮实机为有线一加 `13a7a291`，PJZ110、Android 16 / API 36、arm64，无 Root。模块是本分支 Debug 1.2.0（21）；issue 中的用户是模块 1.2.1、B 站 9.13.0、LSPatch 1.2（487），不是同一手机或同一模块产物。

| 框架 / 宿主 | 模式与参数 | 本轮证据 |
|---|---|---|
| JingMatrix LSPatch 1.2（487）/ B 站 9.13.0（9130500） | 本地 / Manager，签名等级 2，debuggable | 已复现修改前准入拒绝；最终 6 项宿主及回退测试通过；真实播放、分享面板、横竖屏往返通过 |
| 同上 | 集成 / embedded，签名等级 2，debuggable | 修改前拒绝、修改后当前配置、条款与恢复专项通过；实际卸载管理器后最终 6 项实机专项通过；主页显示“模块已激活／由 LSPatch 激活” |
| 同框架 / B 站 8.90.2（8902100） | 集成 / embedded，签名等级 2，debuggable | 首轮当前配置、管理器停止、拒绝及恢复通过，57 个安装 Hook；真实 Web 页面完成渲染 |
| NPatch 固定修复版 833 / B 站 8.90.2 | 集成模式，无 NPatch Manager | 既有完整专项通过：配置、停用、条款、恢复和主进程/Web/播放器服务回执；LSPatch Manager 同时安装时也通过 |
| 同上 | Manager 模式，单独启用本模块作用域 | 交付模块上既有完整专项通过：停止管理器、修改配置、停用、拒绝条款及恢复；主进程/Web/播放器服务回执通过 |

以上是 Debug 实测边界，不外推到其它系统、ABI、签名等级、旧版 LSPatch、Canary 或全部业务功能。账号内分享、登录授权与真实消息投递仍沿用先前后排决定；本轮没有发送消息。实际发布前仍需针对最终 Release/R8 产物完成发布验收。

## 根因与修复

issue 的诊断显示：LSPatch 的 Modern 服务已连接且可用，但免 Root 意图仍为开启，NPatch 网关报告 `MODULE_NOT_REGISTERED`，宿主配置被 `admission_denied` 拒绝，Hook 链 `NOT_STARTED`。这不是“没有注入模块”。

在本机 9.13.0 的本地模式，修改前模块入口已加载 API 102；设置 App 可达时，旧 NPatch 开关使来源选择要求正的 NPatch revision，而 LSPatch 的标准文档 revision 为 0，得到 `manager_sync_required` / `admission_denied`。设置 App 被强停的另一对照中，旧代码还会采用管理器中的历史标准文档回退；两种结果分开记录，不能把偶然回退成功当作准入问题不存在。

修复包括：

- 从已安装宿主的有界 `lspatch` metadata 解析实际模式、loader API 和版本；宿主入口区分 LSPatch 与 NPatch，不把两者协议混用。无法确认 Modern 档案的旧标记不自动开放直达通道。
- 已确认 LSPatch 时，保留的 NPatch 偏好不再接管发布、准入、状态展示及重启保存。保留原偏好供切回 NPatch 使用，不靠清数据或偷偷改掉开关解决。
- 两种 LSPatch 模式通过既有的当前文档及 PREPARE/CONFIRM 握手获得许可。集成模式的数据库在宿主 UID 下，管理器服务的写入不能代表这份库；本地模式同样取当前权威文档，避免依赖历史镜像。
- UID、nonce、challenge、时限、条款及 PublicationIdentity 校验保留。LSPatch 的端点全部不可达时，不再采用旧标准文档恢复 Hook；拒绝条款时不安装功能。
- 激活展示仍要求当前前台会话的真实宿主回执。集成模式缺少管理器服务时可按安装档案发起查询，不伪造框架服务连接或 Remote 能力。
- LSPatch 重启保存屏障确认模块侧的当前配置已经持久化，不强制等待管理器写入；Root → Shizuku → 手动的系统操作优先级保持原设计。

9.13.0 的两种模式均观察到 `module_direct`、`ACCEPTED`、`COMPLETED` 和 61 个安装 Hook；这不是 61 项业务效果验收。拒绝条款后为 `REJECTED`、`NOT_STARTED`、0 个安装 Hook，重新接受后恢复。既有业务的缺失或部分适配继续按诊断如实报告。

## 安装、更新与恢复

1. 使用 [JingMatrix 官方 v1.2 发布](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)的管理器及修补工具。本轮未修改它们，也未升级模块的 BetterAndroid、KavaRef、AGP 或 Kotlin。
2. 在系统中安装模块设置 App。对官方原包重新修补；本地模式在管理器中选择模块后，还要点击**“应用”**保存作用域。只勾选但未应用时，宿主没有模块入口，不能归为准入失败。
3. 在模块中完成条款授权并修改设置；LSPatch 不需要开启 NPatch 开关或通信兼容模式。启动 B 站后，以主页及诊断的实际宿主回执确认安装链。
4. 本地模式更新模块后冷启动宿主，加载系统中新安装的模块；本轮已做修改前模块 → 当前模块的覆盖更新及重新加载对照。集成模式必须把与设置 App 配套的模块 APK 重新嵌入，再更新宿主并冷启动。单独更新设置 App 不能替换已嵌入的代码。
5. 管理器停止时，本地模式是否能加载模块仍取决于 LSPatch 自己的已保存模块快照；首次没有正确作用域/快照不能由模块补造。集成模式的模块加载不需要管理器，但仍需要设置 App 中可达的当前授权；移除设置 App 不是支持的独立运行模式。
6. 出现拒绝时区分入口未加载、设置 App 不可达、条款拒绝、文档/模块版本不一致和安装链失败。恢复设置 App、正确版本及作用域后，重新启动宿主再查询，不用旧回执宣布成功。

官方 CLI 示例（Java 21 仅用于运行官方修补工具；Android 工程继续使用项目规定的标准 JDK 17）：

```powershell
# 本地 / Manager：之后在管理器中应用模块作用域
java -jar lspatch-v1.2-487-release.jar bilibili-original.apk --manager --sigbypasslv 2 -o manager-output

# 集成 / embedded：使用与独立设置 App 相同的模块 APK
java -jar lspatch-v1.2-487-release.jar bilibili-original.apk --embed app-debug.apk --sigbypasslv 2 -o embedded-output
```

## 可复现输入与检查

官方 tag 对应提交 `0dc50f42503711b14f5e2bb217c2bdd6321ce5be`。源码证据包括 [LSPatchXposedService](https://github.com/JingMatrix/LSPatch/blob/v1.2/share/android/src/main/java/org/lsposed/lspatch/share/remote/LSPatchXposedService.java)、[EmbeddedRemoteServices](https://github.com/JingMatrix/LSPatch/blob/v1.2/patch-loader/src/main/java/org/lsposed/lspatch/service/EmbeddedRemoteServices.java)、[XposedPullService](https://github.com/JingMatrix/LSPatch/blob/v1.2/manager/src/main/java/org/lsposed/lspatch/manager/XposedPullService.kt)及 [ManagerRemoteServices](https://github.com/JingMatrix/LSPatch/blob/v1.2/manager/src/main/java/org/lsposed/lspatch/manager/ManagerRemoteServices.kt)。

| 输入 | SHA-256 |
|---|---|
| 官方 release JAR | `d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165` |
| 官方 release Manager APK | `e93e34c5170831aad51b2035e3cd94a1c3b5298711189fabc359b566bc34ad17` |
| B 站 9.13.0 原包 | `d9025a8245d6426cfd3bea1a3e4cb57096de33dcc7c83cd8cc0412fad1238ec8` |
| 交付模块 Debug APK | `f1c3f0c214838beed9c58ca6859d1d8e8c439078d29ab0588bba1c1f08ee21ff` |

9.13.0 从[豌豆荚历史版本详情](https://www.wandoujia.com/apps/281291/history_v9130500)的普通 APK 地址取得，未安装下载器。`apksigner verify` 通过，包名/版本码核对通过；签名 SHA-256 `93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e` 与本地官方 8.90.2 原包一致。发行来源与签名核对是不同证据。

最终源码已通过 `assembleDebug`、`assembleDebugAndroidTest`、3881 项 JVM 测试（0 失败、0 错误、1 跳过）及**快速 Lint**。未扩展 baseline 或 suppress，未运行完整 Lint/Release 验收。构建结束执行了 `--stop` 并核对项目 Gradle/Kotlin 守护进程退出。

收尾时已卸载临时 NPatch Manager，恢复官方 LSPatch Manager 1.2（487）及 B 站 9.13.0 集成模式。设备上的独立模块 APK 与交付模块 SHA-256 一致，宿主 APK 与已验证的交付集成包一致（`1cb5a100e1a11cf162a03eeb4aa203c9c23b7022b75f5efd678f0a43d4c6cc01`）。`delivery-activation.png` 记录“模块已激活／由 LSPatch 激活”；`delivery-diagnostics.png` 在管理器服务断开时如实区分当前宿主回执与服务状态；`delivery-video-visible.png` 记录最终宿主视频页。构建后只校正模式相关的普通注释和本文记录，不影响已验证的代码与资源。

原始证据保存在被忽略的 `captures/lspatch-stage9/`：`913-manager-baseline-awake.log`、`913-baseline.log`、`913-manager-final-instrumentation.txt`、`913-embedded-no-manager-final.txt`、`913-delivery-embedded-instrumentation.txt`、`npatch-regression-installed.txt`、`npatch-manager-ready-regression.txt`、`gates-delivery.log`、`jvm-summary.json`、`artifacts-verified.json`、版本/来源记录及稳定界面截图。核心双模式回归的模块产物 SHA-256 为 `f441ca002c6e8478f37dd66206f731f0b087024927bc2d26997c0d7c5d6119dd`；随后只修正无管理器诊断激活行的证据文案，生成表中的交付 APK，并重跑构建、单测及快速 Lint。交付 APK 的实际无管理器集成模式也重跑了完整 6 项实机专项，全部通过（44.936 秒）；NPatch Manager 补充回归在交付 APK 上通过。其被旧安装器叠层、首启协议及协议更新提示中断的尝试也保留，清理夹具后完整通过；没有因此改动业务代码或放宽断言。早期名称中的 `913-local` 实际是集成模式，正式结论按读取的 `useManager` 和此文模式定义判断，不凭目录名推定。

## 其它免 Root 框架候选

这是资料与契约筛选，不是全部项目的实机兼容保证。查询日期为 2026-10-24；“没有取得 Modern 契约证据”不能写成已证明永远不支持。

| 候选 | 技术与版本证据 | 当前判断 |
|---|---|---|
| JingMatrix LSPatch 1.2（487） | Vector 内核、Modern API 102；本地和集成两种模式；上文的实际产物 | 本轮适配目标；按实测矩阵支持 |
| JingMatrix Canary 488 / 489 | [官方发布页](https://github.com/JingMatrix/LSPatch/releases)存在预发布；包括 AppComponentFactory 及 metaloader 类名修复 | 与稳定 487 分列，未安装实测，不推定通过 |
| NPatch 固定修复版 833 | APK 修补、API 102；[现有支持说明](npatch-release-support.md)的固定 Java/native 修复及组合 | 已有对照，不当作新发现或需要重做的框架 |
| LSPosed 原版 LSPatch / JingMatrix 0.8 等旧线 | [原版仓库](https://github.com/LSPosed/LSPatch)已归档；[旧版入口源码](https://github.com/JingMatrix/LSPatch/blob/v0.8/patch-loader/src/main/java/org/lsposed/lspatch/loader/LSPApplication.java)与 1.x 重建不同 | 不套用 API 102 及 1.2 结论；本轮没有为 Legacy 线新增另一套模块入口 |
| VirtualXposed | [官方说明](https://github.com/android-hacker/VirtualXposed)：VirtualApp + epic，应用和模块装入容器，声明 Android 5–10 | 容器改变运行/身份模型；未取得当前 Modern API/配置服务契约，不能进入现有授权模型的实机准入 |
| SandVXposed | [官方说明](https://github.com/asLody/SandVXposed)：VirtualApp + SandHook，Android 5–10，10 不稳定；公开最新 release V2.1.4 | 同上，资料筛选后不在此 Android 16 专项中盲目安装 |
| Xpatch | [官方说明](https://github.com/WindySha/Xpatch)：重打包、SandHook/whale 及传统 Xposed 插件加载 | 与容器不同，但仍缺少本模块需要的 Modern 入口、服务与当前配置证据；后续有对应产物再评估 |

太极原公开入口 `tiann/taichi` 本次返回 404，未取得可核对的当前版本/产物与 Modern 契约，不将网络中“兼容 Xposed”的泛称写成支持结论。其它未识别到明确项目和产物的 fork 保持未验证。候选筛选不通过放宽 UID、改包名或跳过条款来凑兼容。
