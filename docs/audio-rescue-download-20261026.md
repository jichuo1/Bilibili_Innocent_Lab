# 音频增强按需资源与故障恢复

日期：2026-10-26。工作区为 `experiment/audio-enhance`，本次无手机可用，未运行 adb。

## 分发与使用

GTCRN 权重和 ONNX Runtime ARM 原生库均不再装进 APK。APK 仍包含与原生版本匹配的 Java 桥接代码、既有音频处理器和许可证说明。防爆音与声道修复不依赖下载；资源未就绪时，人声增强保留原声，降噪走已有基础处理。

入口：设置 → 进阶增强 → 播放 → 音频拯救。页面提供下载/继续下载、导入离线文件、复制离线下载地址、检查/重试发布、取消和复制诊断。首次网络流量约 55.7 MB，原始 AAR 同时含其他架构，当前仅解包固定的 ARM32/ARM64 四个库；没有为节省流量而发布未经验证的新二进制下载包。完成后可离线复用，设置保存后完整重启哔哩哔哩。

离线导入接受两份原文件，可以一起选择或分两次导入：`gtcrn-dns3.onnx` 与 `onnxruntime-android-1.31.0.aar`。文件名和 MIME 不参与信任判定，内容大小与 SHA-256 必须完全匹配。页面可复制主源和备用源地址；不导入 ZIP 自定义模型，不接受用户填写的任意模型 URL。

固定身份由 `AudioAssetSpec.kt` 定义：

| 文件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| GTCRN / DNS3 ONNX | 535190 | `b4718df6228e7bdf1a8a435cf98f838636eb2fd331acabf86ba87c5192ebcb87` |
| ONNX Runtime Android 1.31.0 AAR | 55138854 | `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7` |

模型来自 [GTCRN 的固定提交](https://github.com/Xiaobin-Rong/gtcrn/tree/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73)，主源为 GitHub raw，备用为同提交 jsDelivr；推理库来自 [Maven Central 1.31.0](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/)，备用为阿里云 Central 镜像。2026-10-26 已实际下载四个源的完整文件，各自大小与 SHA-256 一致。该结果仅说明本机当时可访问，不能保证用户所有网络可用。

## 参照 main 的修复

本轮阅读本地 `main` 的插帧下载器、文件管理、宿主缓存、准备重试和设置页，以及 `04e56e96`、`08e8ab21`、`6207c350` 的相关修复。没有开发群聊天记录访问，以下依据代码与提交历史，不声称覆盖群内全部故障。

| 已见故障 | 音频实现的处理 |
| --- | --- |
| 文件/存储失败被当作框架未激活 | 引入 main 的 `FrameworkServiceCall` 结果类型，分开未连接、元数据等待、能力不支持、连接换代、服务死亡和文件操作错误 |
| 框架能力/身份返回晚，首次失败后不恢复 | 采用 main 的能力、框架身份与可选版本信息分阶段读取及有界恢复；身份确认前不把 LSPatch 当作 Root 框架 |
| 下载成功后发布失败，需要重新下载 | 校验文件保存在模块 `noBackupFilesDir`；设置页初始化后，框架恢复会触发缓存发布，无网络重下载 |
| 页面检查完成时已经换代/离开页面 | 框架操作前后检查连接代次；页面仅在 attached 且 STARTED 时订阅，离开/重建使旧回调失效 |
| 多进程共用临时路径、只准备一次 | 唯一临时文件、原子替换、跨进程文件租约；宿主准备以 1/4/15/45 秒间隔有限重试 |
| 原生初始化已经失败还继续反复创建加载器 | 原生加载失败终止本进程准备并回退，诊断要求重启宿主；不在播放写入线程重试 |

仅移植音频链路依赖的框架服务修复及对应测试，没有合并整个 main，也没有改动插帧功能。

## 校验与恢复边界

- 下载仅由用户点击发起。每个固定源尝试一次，失败切换备用；连接 15 秒、读取 20 秒超时，单次传输还有 180 秒总时限。只接受 HTTPS 固定端点，拒绝重定向和非 identity 编码。
- 断线/取消保留 `.resume`。206 必须匹配精确 Content-Range；服务器用 200 忽略 Range 时截断重下；最终仍完整计算大小与 SHA-256。镜像拼接出错误内容会失败并清掉该部分文件，不能绕过校验。
- 流式读入设最大字节数，失败不覆盖已验证文件。AAR 本体先校验，之后仅按四个固定 entry 读取，逐库再次校验，不解包任意路径、不加载下载的 dex/classes。
- 模块下载、导入、解包和发布使用一个工作队列及文件租约。正式文件通过唯一临时文件写入、同步、原子替换；持有租约时清理上次进程遗留的自有暂存文件，保留可续传文件。
- 框架远程文件名带固定摘要前缀；只有损坏或缺失项被重写，使用 AutoClose 流管理描述符，先 truncate，再写入并 sync，最后回读全套文件。框架不提供原子多文件提交，因此宿主对每项独立校验，绝不加载半份内容。
- 宿主仅从已授权的框架远程文件或宿主自己的 codeCache 读取，后台落地当前进程 ABI 的库和模型，再次完整校验后才创建会话、预热并暴露给 PCM 处理器。缓存损坏可修复；不打开模块私有文件、不新增授权旁路、不联网。
- 开始下载/导入前要求至少 180 MiB 可用空间，实际磁盘写入失败仍单独报告。校验过的 AAR 保留用于中断解包后的离线恢复。用户清除应用数据/系统清理 codeCache 后可能需要重新准备，但框架/模块的已验证副本仍可复用。
- 框架 Binder 或文件提供方不能被安全强杀。非网络准备阶段超过 45 秒会取消迟到结果的接收、显示“长时间未响应”、保留文件与诊断，提示重启模块/管理器再试；不会无限增加工作线程或宣称已完成。

## 排障信息

复制诊断包含资源版本、模块版本、当前阶段/字节数、框架能力与连接代次、最近 32 条有界事件及上一进程记录。下载事件记录源序号、HTTP 状态、大小/摘要差异与异常类型，不记录视频、PCM、文档 URI 或用户私有路径。失败后的已校验文件保留，重开页面检查不会抹去事件历史。

页面打开和点击检查时复用现有受校验的宿主诊断通道查询主进程，报告 `host_query`、查询时间和 `audio_model`。`published` 仅表示模块校验且框架回读成功；`audio_model=ready` 才表示宿主会话成功加载并预热，仍不等于已处理实际播放 PCM。旧宿主、未启动宿主或查询失败显示 unknown，不推断成功。`:ijkservice` 或加载详细原因查看已有宿主日志键 `player_audio_rescue.model.*`；模块下载日志标签为 `BILAudioAssets`。

| 诊断码 | 优先排查 |
| --- | --- |
| `network_timeout` / `network_deadline` / `http_status` | 对应源网络；继续下载或离线导入 |
| `range_invalid` / `response_size` | 代理或源未遵守响应约定；切换备用源 |
| `hash_mismatch` / `import_identity` | 错误版本、HTML 错误页、截断或篡改；只使用指定原文件 |
| `framework_disconnected` / `framework_pending` | 管理器连接与元数据；打开管理器后返回重试 |
| `framework_unsupported` | Remote 能力不支持；不要当作“模型缺失”反复下载 |
| `remote_io` / `remote_invalid` | 框架文件写入、权限、描述符或回读失败；本地副本仍在 |
| `operation_stalled` | 不能中断的 Binder/文件提供方；先复制诊断，再重启模块/管理器 |
| 宿主 `remote_missing` / `cache_io` | 尚未发布、远程读取失败、宿主缓存空间/权限；准备有界重试 |
| 宿主 `runtime_failed` / `fallback` | 原生加载或预热失败；查看阶段、ABI、异常类型，重启后验证 |

## 自动验证与真机后续

`AudioAssetsTest` 覆盖大小/摘要错误、暂存失败不覆盖、缓存修复、残留清理、文件租约、并发落地、固定 ZIP 条目、正确/错误 Range、服务器忽略 Range、取消、镜像切换、重定向拒绝、总时限、远程写入失败与重连修复。`AudioAssetsHttpTest` 使用真实本地 HTTP 连接制造中断并续传。`AudioAssetsArtifactTest` 使用完整原始文件完成校验、解包、模拟远程发布及两种 ABI 的宿主缓存落地。

设置环境变量 `BIL_AUDIO_ARTIFACTS` 指向含两份指定文件的目录，可在 JVM 测试中启用真实资源集成验证；未设置时该项明确跳过，构建和单测均不自动联网。`prepare_device_assets.py` 可生成后续 Android 测试所需的五项远程文件格式资源，见 `scripts/audio_benchmark/README.md`。

Gradle 在 Debug/Release assemble 后检查实际 APK：禁止权重或 ORT 原生库条目，同时检查实际文件长度与压缩条目总量的差距。已观察到增量 ZIP 删除库条目后留下约 59 MB 空洞，因此仅检查 ZIP 条目不够；超过 8 MiB 非条目空间会阻断交付并提示重建该 APK 输出。

本轮仍需后续手机验收：LSPatch/LSPosed 的实际 Remote 文件读写、设置页前后台与文件选择器、断网/取消/恢复、主进程与 `:ijkservice` 的原生加载及 PCM 生效、冷启动离线复用。这些平台与框架行为无法由桌面测试替代；本轮不操作手机，等用户明确提供设备后再做。

### 本地验收结果

最终同轮执行 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintFast --offline --no-daemon --continue`，9 分 33 秒完成：

- JVM：4022 项，4021 通过，1 项既有跳过，0 失败。真实资源集成项已设置 `BIL_AUDIO_ARTIFACTS`，实际执行通过；没有因缺文件跳过。资源核心 21 项也通过独立 Kotlin/JUnit 执行。
- Debug APK：23742542 字节（23.7 MB，22.64 MiB），模型/ORT 原生库条目检查和 ZIP 空洞检查通过；AndroidTest APK 构建通过，未运行设备测试。
- 快速 Lint：266 条警告，0 新增未基线化错误，原有 3 条错误基线未改。未运行完整 Lint。新资源协调器的 StaticFieldLeak 提示经人工核对仅持有 `applicationContext`，不持有 Activity；页面订阅在 STOP/detach 清除。UsableSpace 提示保留：本实现不主动让系统驱逐其他可清理缓存，空间不足有显式失败路径。
- 已用四个实际下载文件验证主备源；`prepare_device_assets.py` 生成五项校验通过的设备资源和两份离线导入原件。
- 新存储测试最初使用 `C:/Windows/Temp`，其 `toRealPath` 被本机 ACL 拒绝；改为测试所属工作区目录后通过，未放宽生产路径检查。第一次最终检查期间调整了源码，Lint 的 FIR 分析异常退出；随后固定源码重新运行并通过，失败结果未当作完成证据。
- 运行后已执行 Gradle `--stop`，最终进程检查未留下本轮 Java 构建或 Kotlin 守护进程。

忽略目录中的证据：`captures/audio-download-20261026/build3.log`、主备源原文件、`device-assets/`、`offline-import/`。当前 APK 位于 `Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`。本轮未修改语音算法参数或既有 A/B 回归音频，不把历史真机结果当作新下载链路验收。

## 2026-10-27 LSPatch 回退与实机验收

LSPatch Manager 模式不提供框架 Remote 文件服务，下载完成后的本地校验会成功，但发布阶段报 `framework_disconnected`，宿主无法取得模型。本轮新增模块 ContentProvider 只读回退：

- `RoamingCompatProvider` 新增 `audio_asset` 路径，仅接受目标宿主（`tv.danmaku.bili`）、root、shell 或模块自身，按固定 `remoteName` 读取 `noBackupFilesDir` 中已校验的模型/ORT 文件；每次打开重新执行大小与 SHA-256 校验，不提供目录列表或任意路径。
- 宿主优先使用框架 `openRemoteFile`；不可用时通过同一 provider 的 `content://com.Bilibili_Innocent_Lab.xposedmodule.roaming/audio_asset?name=...` 读取。宿主仍复制到自己的 codeCache 并再次校验，不直接使用模块文件播放。
- provider 与宿主缓存逻辑不依赖宿主版本或框架 Remote 能力，适用于 LSPatch、LSPosed 和 NPatch。

2026-10-27 同一有线一加 `13a7a291`（宿主 9.13.0 / 9130500，LSPatch Manager，模块 1.2.1 / 22 Debug）实测：

- 设置页“下载 / 继续下载”完成两份源文件与 ARM64 两个原生库的解包，`BILAudioAssets` 无 `hash_mismatch`/`network_timeout`；发布阶段仍如实报 `framework_disconnected local_verified=true`，本地已验证副本保留。
- 冷启动宿主后，回退 provider 使 `audio_assets stage=ready abi=arm64-v8a attempt=0`，主进程日志出现 `audio_rescue PCM entry=1 ... neural=true`。分别启用“人声清晰度增强”和单独“语音降噪”，两者均进入模型路径且输出 RMS/峰值发生变化（例如降噪单独测试 `inRmsPpm=133979 → outRmsPpm=67117`）。
- 最终配置恢复为用户原有的峰值保护 + 自动补齐缺失声道 + 人声清晰度增强，三项均开启；手机留在测试视频暂停页。

未覆盖：离线文件选择器的系统交互、断网/取消/续传、`:ijkservice` 进程的模型加载、冷启动离线复用仍需其它宿主进程场景复测；本次只验证主进程和 LSPatch 回退。其它 37 个宿主版本未逐版实机安装，模型回退本身与宿主版本无关。
