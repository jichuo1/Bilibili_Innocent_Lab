# NPatch 1.0.9 (833) loader 修补工具

> 当前模块版本不支持集成（embed）模式，仅开放 Manager 模式。工具及下文 embed 流程保留供既有实现维护；生成 embed 产物不会绕过模块代码中的支持门控。

## 阶段 5 启动采样

`MeasureStartup.ps1` 只用于已授权测试机，显式要求 serial、模式和当前产物的 `verified.json`。运行前先自行构建并同证书覆盖安装设置 App、测试包及相同内嵌内容的宿主；脚本核对两份已安装 APK 的 SHA-256，不代为安装或修补。详细测量需在 Debug 构建时传 `-PinnocentLab.startupTrace=true`，默认构建和 Release 不注册采集入口；原生产查询和支持包协议不变。

脚本会停止并通过明确的主 Activity 重启宿主，每轮等待首页导航/搜索入口、实际点击搜索并确认输入框响应，随后查询本次配置身份与有界耗时。`-ConfigureProfile default|combined` 明确写入固定的宿主功能测试配置，不改变账号授权、条款、手动恢复项或系统设置；不传则保留当前配置。测试期间设置端点因 Instrumentation 存活，该条件写入样本，不能外推设置 App 被强停的结果。`-Warmup` 单独保留但排除一轮预热；升级首启、缓存重绑定/首次适配须先独立核对，不能只靠该开关判断稳定命中。

```powershell
./scripts/npatch/MeasureStartup.ps1 -Serial 13a7a291 -Mode embedded `
    -VerifiedManifest '<当前产物目录>/verified.json' `
    -OutputDirectory '<不存在的证据目录>' -ConfigureProfile default -Warmup -Rounds 5
```

输出保留原始 Instrumentation 日志、原始轮次 JSON、产物哈希与采样前后电池热状态。首帧取系统 Displayed 记录，缺失保持 null，不用 `am start -W` 的返回时间代替；首页/搜索响应为 250ms 轮询及实际输入操作的观察口径。失败不进入成功样本文件，原日志保留；不同配置、模式、缓存状态和产物应分组，详见[阶段 5 设计](../../docs/npatch-stage5-startup-performance-design.md)。

连续日志配对必须同时核对当前 PID 与进程启动时间，避免长时间测试的 PID 重用；模块普通输出和带固定模块名前缀的镜像输出可能只保留其一，不能因少一份镜像就推断宿主没有启动。阶段 5 的完整分组、暂停原因和恢复证据见[实际结果](../../docs/npatch-stage5-performance-results.md)，原样本不因解析修复重写。

首次适配可在已授权备用机显式运行 `NPatchStartupBenchmarkInstrumentedTest#resetAdaptationForFirstStartup` 并传 `-e startupReset true`；它调用设置页同一重适配动作，只请求清除适配记录及更新重置标记，不清宿主数据或恢复许可。先单独验证首启后台完整适配及下一次 `file-hit`，不要混入稳定缓存统计。业务触摸后可运行 `NPatchFeatureEvidenceInstrumentedTest`，传 `expectedMode=MANAGER|EMBEDDED` 和明确的 `applied/observed/quiet` 能力 ID；它只核对当前配置身份和真实回调，不模拟操作成功。

## Loader 修补与历史验收

当前 embed 使用 Java 存活修复与原生锁反转修复的组合产物。下文先保留 Java 修复及 2026-10-20 的历史验收，再说明原生构建和当前 embed 工具。仅 Java 修复的旧产物仍含 Web 原生死锁，不能用于本轮 embed 验收。

该工具修复已配置 Manager 作用域后，Manager 不可达时缓存模块完全不执行的问题。它只支持下列 SHA-256 的 **833 patcher JAR**，不会猜测适配其它版本：

```text
patcher: d7e69d464b90aa888a5d555b94d38dab94a4aab19d80f863d238b6715a0fcea3
loader:  29325bd4715bf62dcfdfeb7d1acdaa58d9bcf0040a4fa22756d615542736ea8b
```

## 原因与修复边界

833 的 `RemoteApplicationService` 已经缓存模块列表，并在 Manager 失联时提供本地回退；但其 `asBinder()` 返回远端服务 Binder，断连时返回 null。`VectorServiceClient.init()` 因而不保留这个本地服务对象，`getModules()` 得到空列表。Manager 运行中死亡也会触发同类失效。

833 发布包中的服务类只实现 `IFrameworkService`，**不继承 Binder**。不能直接把方法改成 `return this`。本工具仅把这个方法改为 `return new android.os.Binder()`，提供进程内存活令牌；Vector 仍直接调用原来的本地 Java 服务对象，配置、作用域、模块列表和重连逻辑均沿用原实现。该令牌没有远端 IPC 分发用途，不能当作跨进程 `IFrameworkService.Stub` 使用。

工具检查输入 JAR/DEX 哈希与唯一方法签名，重写后重新解析 DEX，再用原方法恢复序列化，要求与原 DEX 的规范化输出逐字节一致；此外逐项校验 JAR 其它文件内容。已有输出和未知输入均拒绝，不覆盖原文件。

修复需要**重新修补并覆盖安装宿主 APK**，单独更新模块 APK 不会修改旧宿主里的 loader。作用域必须已正常配置，宿主也必须在 Manager 在线时成功读取过当前配置；没有初始缓存，或改配置后宿主尚未读取新文档时，仍需打开 Manager 再冷启动宿主。设置页“已同步”表示写入 Manager，并不表示旧宿主缓存已更新。它不绕过模块授权、免 Root 开关或作用域配置，也不修复宿主本身的基础启动开销。

## 复现命令（PowerShell）

准备原版 833 patcher JAR、JADX 1.5.6 的 fat JAR（内含重定位后的 dexlib2）、BouncyCastle 1.80 JAR，以及**未修补的宿主 APK**。路径由调用者传入；仓库不分发第三方二进制或签名私钥。

```powershell
$java17 = 'C:/Program Files/Java/jdk-17/bin/java.exe'
$java21 = 'D:/graalvm-jdk-21.0.2+13.1/bin/java.exe' # 仅 patcher；Android Gradle 仍用标准 JDK 17
$jadx = '<jadx-1.5.6-all.jar>'
$originalPatcher = '<jar-v1.0.9-833-release.jar>'
$fixedPatcher = '<不存在的输出文件.jar>'
$bcprov = '<bcprov-jdk18on-1.80.jar>'
$originalApk = '<未修补的宿主.apk>'
$apkOutput = '<输出目录>'

& $java17 -cp $jadx scripts/npatch/FixLoader833.java $originalPatcher $fixedPatcher
if ($LASTEXITCODE -ne 0) { throw 'Loader patch failed' }
& $java21 -cp "$bcprov;$fixedPatcher" scripts/npatch/RunPatcher.java --manager --sigbypasslv 2 -o $apkOutput $originalApk
if ($LASTEXITCODE -ne 0) { throw 'APK patch failed' }
```

保留原修补参数和签名密钥；上例是测试机的 Manager 模式、签名绕过等级 2 与默认签名方案。安装前分别使用 SDK `apksigner verify --print-certs` 核对旧、新 APK 的签名证书一致，再 `adb -s <设备> install -r <新 APK>`。签名不一致时停止，不通过卸载规避。

本次固定 loader 的 SHA-256 为 `7eb53e46e4d7fb31c4a7441a9478bb26f73563496547ebe82e66a39301d4efaf`。原参数生成的 patcher SHA-256 为 `fd6257b562af7f2c55776131f97ef72e7e7c772f5b2c0396c69ae1517f7e1e78`。

## 2026-10-20 实机验收

设备 `13a7a291`，Android 16、B 站 8.90.2，Manager 1.0.9 (833)，模块 1.2.0 debug；新旧宿主签名证书 SHA-256 均为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`，已覆盖安装并保留数据。

- 原包：Manager `stopped=true` 时，出现 `Loaded cached module`，没有模块 Modern 入口和功能 Hook。
- 修复包：相同停止状态出现 Modern 入口、授权 `granted`，Hook 链完成 2557ms；恢复开关后再次冷启动 2083ms。
- 早期模块在 Manager 与设置进程均停止时可用缓存授权；最终 B1 回归发现这会复活旧关闭态，现已禁止 NPatch 只凭缓存授权，全部端点不可达时不安装功能 Hook。
- Manager 重启后：授权 40ms、Hook 链 2181ms；运行中再次停止 Manager，设置页仍显示“由 NPatch 激活（回执已验证）”。
- Manager 停止、免 Root 关闭：授权明确拒绝，没有功能 Hook 安装链；测试后恢复开启。
- 工具：相同输入重复生成哈希一致；未知输入和已有输出均拒绝，DEX 单方法及 JAR 其它文件内容校验通过。

最初复测使用等级 1；最终宿主已按原配置恢复等级 2 并同签名覆盖安装。最终模块同步后、Manager 停止时授权 28ms、Hook 链 2039ms；旧缓存且授权端点全部不可达时明确拒绝，没有功能 Hook。

最终 B3 修复包复测：Manager 停止时明确关闭许可可使设置页归位为“已关闭，不会连接 NPatch”；重新开启、在线读取当前配置后，再停止 Manager，授权 39ms、Hook 链 1898ms，设置页显示已激活且回执有效。证据 `b3-close-confirmed.*`、`final-online-enabled.log`、`final-offline-enabled.log`、`final-active.png`。

原始证据在已忽略的 `captures/npatch-closure/b12-*`、`b1-final-*`；最终生成宿主位于 `captures/npatch-closure/b12-final/`。

## 原生 loader 锁反转修复

833 的 `bypass_sig.cpp` 在持有路径互斥锁时首次访问 `thread_local std::string`，会经 `__cxa_thread_atexit_impl` 取得 Android linker 锁；另一线程在持有 linker 锁的 `dlopen` 中进入文件重定向并等待路径锁，形成 AB-BA 死锁。实机 ANR 栈与原始二进制反汇编相互印证。此路径在免 Root 关闭或条款拒绝、模块功能 Hook 数为 0 时仍可能运行，不能靠隔离 Web 或延迟功能安装解决。

`BuildNativeLoader833.ps1` 仅将重定向缓冲区改为无需析构的 TLS 定长缓冲区，保留原路径判据和 Hook 时序。超过 `PATH_MAX - 1` 或含 NUL 的路径保留原路径，不截断。另补齐独立 native 构建缺失的直接头文件声明。固定源码来自 [NPatch v1.0.9](https://github.com/7723mod/NPatch/releases/tag/v1.0.9) 对应提交及其依赖；`native/sources-833.json` 固定每份官方源码归档的提交和 SHA-256，不使用移动分支。

准备上述 **Java 已修复**的 patcher、NDK `29.0.14206865`、CMake `3.31.5` 和独立 Ninja `1.12.1`。SDK 自带 Ninja 1.10.2 不能配置该固定依赖；仅为此工具单独提供新版 Ninja，不修改 Android 工程工具链。Ninja 官方 Windows 包见 [v1.12.1](https://github.com/ninja-build/ninja/releases/tag/v1.12.1)，归档 SHA-256 为 `f550fec705b6d6ff58f2db3c374c2277a37691678d6aba463adcbb129108467a`。源码归档按清单名称预先放入目录，或明确传入 `-AllowDownload` 只下载缺失项，下载后仍严格核对哈希。

```powershell
./scripts/npatch/BuildNativeLoader833.ps1 `
    -ArchivesDirectory '<固定源码归档目录>' `
    -BasePatcherJar '<patcher-833-final.jar>' `
    -NdkDirectory 'G:/AndroidSdk/ndk/29.0.14206865' `
    -CMakeDirectory 'G:/AndroidSdk/cmake/3.31.5' `
    -Ninja '<ninja-1.12.1/ninja.exe>' `
    -OutputDirectory '<不存在的 ASCII 输出目录>'

./scripts/npatch/TestNativeLoader833.ps1 `
    -Serial '13a7a291' -BuildDirectory '<上一步输出目录>' -Iterations 20
```

构建 arm64-v8a 和 x86_64 两份 loader，只替换 JAR 内这两个 native 项，要求其它每个项（包括修复后的 Java loader）内容不变。固定构建时间、移除路径与 debuglink 差异，并输出哈希和工具版本清单 `verified.json`。当前 Windows 上的 PowerShell 7.6.5 配方已在不同输出目录重建出逐字节一致的 JAR 和双 ABI 原生库；未知产物不会被 embed 工具接受：

```text
patcher: dfa41340b717ca4de5a0de8e283e60d21ec38ce4b42f3d67657e01efeeecaff6
arm64:   ceac8f206404b5b2267ca70b0d6eb5b0f3738bc11d60e61738ccaa6af4e05a0f
x86_64:  7933cae217a3f0183d15fee01674d682d4d8eb6a6f227d208cf6bbbc356ca5d1
```

测试在设备 shell 的专用 `/data/local/tmp` 目录运行，不需要 root。旧 TLS 实现应在真实 `dlopen` 构造函数持锁场景中于 5 秒超时并返回 42，证明对照有效；修复实现必须通过路径边界、线程隔离及相同锁竞争，默认连续 20 轮。当前仅 arm64 有实机运行证据，x86_64 只完成交叉编译，不能据此宣称其它系统/ABI 已验收。

## embed 同签名产物

`NewPatchedApk.ps1` 是 Manager/embed 共用的产物与签名入口；`NewEmbeddedApk.ps1` 保留原调用方式并固定选择 embed。两者固定上述 **Java 与 native 均已修复**的 833 patcher、普通 entry-hook 和签名等级 2，不启用 `injectdex`。拒绝已修补输入、已有输出目录和未知 patcher；验证原版宿主、双 ABI loader 与 embed 模块字节，Manager 产物必须不含内嵌模块。模块必须是已签名、应用 ID/Modern 入口正确的 APK，Debug 和已签名 Release 均可；模块包名从 APK 自身读取，因此 `com.Bilibili_Innocent_Lab.xposedmodule.embedded` 之类的内测变体也可直接传入；版本与模块证书进入 `verified.json`。输出宿主证书必须与参照 APK 一致，任何失败均不生成通过记录。脚本不安装 APK、不导出私钥。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17' # apksigner 使用
./scripts/npatch/NewEmbeddedApk.ps1 `
    -OriginalApk '<未修补的宿主.apk>' `
    -ModuleApk 'Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk' `
    -ReferencePatchedApk '<当前宿主同证书的 patched.apk>' `
    -PatcherJar '<patcher-833-native-fixed.jar>' -BcprovJar '<bcprov-jdk18on-1.80.jar>' `
    -Java21 'D:/graalvm-jdk-21.0.2+13.1/bin/java.exe' `
    -ApkSigner 'G:/AndroidSdk/build-tools/37.0.0/apksigner.bat' `
    -OutputDirectory '<不存在的输出目录>'
```

配合漫游的 embed 产物可额外传入 `-RoamingModuleApk '<兼容漫游.apk>'`（两个产物入口均支持）。它只接受包名 `me.iacn.biliroaming` 且有合法 Legacy 入口的已签名 APK，保留原字节，逐项校验内嵌结果，并将版本、SHA-256 和模块证书记入 `verified.json` 的 `roamingModule`。更新任一内嵌模块均需重新修补。该参数不用于 Manager；Manager 在其作用域界面同时启用 BIL 与漫游。APK 可嵌入不等于其全部功能兼容，当前缓存验收输入和边界见[阶段 6 记录](../../docs/npatch-stage6-experience.md)。

发布模块先完成 Release/R8 构建并签名，再把该**同一份已签名模块 APK**用于修补和独立设置 App 安装。默认使用 NPatch 测试证书；已有自有证书的宿主必须显式提供匹配的 keystore，不能退回默认密钥。只接受环境变量**名称**，口令值由 `apksigner` 直接读取，不进入 patcher 参数、日志或产物记录。`Aapt2` 默认从所选 `ApkSigner` 的同目录查找，也可显式传入。

R8 会按工程已有规则改写 `META-INF/xposed/java_init.list`，入口不保证仍叫 `HookEntry`。工具接受一个合法的原名或混淆类名，并继续核对 Modern API 101/102、静态作用域及禁用热重载的档案；多入口或未知 API 档案被拒绝，实际入口名进入 `verified.json`。已用真实 Release/R8 产物的混淆入口 `ve0` 完成修补、同证书覆盖安装、四进程当前配置诊断及 Web 内容检查。该本地验收包使用既有测试签名，不等于取得或使用正式发布密钥。

```powershell
# 当前进程中预先安全设置 BIL_HOST_STORE_PASSWORD / BIL_HOST_KEY_PASSWORD，不在脚本内写入口令值。
./scripts/npatch/NewPatchedApk.ps1 -Mode embedded `
    -OriginalApk '<未修补宿主.apk>' -ModuleApk '<已签名 Release 模块.apk>' `
    -ReferencePatchedApk '<当前已安装宿主的 APK>' `
    -PatcherJar '<固定修复版 833.jar>' -BcprovJar '<bcprov-1.80.jar>' `
    -Java21 '<Java 21 可执行文件>' -ApkSigner '<SDK apksigner>' `
    -SigningKeyStore '<自己的签名库.p12>' -SigningKeyAlias '<alias>' `
    -SigningStorePasswordEnvironment BIL_HOST_STORE_PASSWORD `
    -SigningKeyPasswordEnvironment BIL_HOST_KEY_PASSWORD `
    -OutputDirectory '<不存在的输出目录>'
```

首次自有签名构建可用同证书的已签名 APK 作为证书参照；覆盖升级必须使用当前安装宿主的证书参照，并先核对新产物的 `verified.json`，再 `adb -s <serial> install -r <产物>`。不同证书无法保留原应用数据覆盖安装，工具会拒绝，不会自行卸载。宿主密钥与独立设置 App 的密钥分别保持各自升级链，不能混淆。模式切换用相同输入和宿主签名，将 `-Mode` 改为 `manager` 或 `embedded` 后覆盖安装、冷启动；Manager 另外安装并配置模块/宿主作用域，embed 固定内嵌作用域。仅更新设置 App 不等于更新 embed 代码。

`TestPatchedApk.ps1` 在新的输出目录生成一次性测试密钥，离线检查自有签名/同证书重建、Manager 档案、错误证书/口令/应用 ID、缺失密钥、未知 patcher、已修补输入与已有输出拒绝，并检查日志/产物记录没有口令。它不连接手机，输入参数与产物脚本一致，另需 `KeyTool`。提供可选 `RoamingModuleApk` 时还验证双模块字节和自有宿主签名；错误漫游包名及 Manager 模式误用均必须拒绝。测试密钥仅用于夹具，口令在本次进程结束时清除，不应拿这些测试产物分发。

安装修补后的 B 站及同版本独立模块设置 App；后者提供当前许可与配置，必须保留。首次打开设置 App，接受条款并选择 NPatch 免 Root 通道，冷启动 B 站后验证回执。embed 无需安装 Manager，作用域固定在 APK 内；独立设置 App 不写宿主 UID 的 Remote Store，也不查询 Manager 作用域。更改普通设置后重启宿主；**更新模块代码时必须用最新模块 APK 重新修补宿主**，只更新设置 App 不会替换 APK 内模块。同版本码的开发包也必须核对 `verified.json` 中的模块哈希。

设置 App 被强行停止时，当前授权端点可能不可达；此时宿主不采用旧授权缓存。先打开设置 App，再冷启动宿主。覆盖安装保留设置；卸载设置 App 会删除其配置、条款决定和账号授权，重新安装后需重新接受条款、开启免 Root，账号凭据需重新授权。设置备份只恢复白名单业务设置，不恢复条款、免 Root 启用意图、租约或运行回执。仅重装设置 App 不能更新宿主 APK 内的模块代码。

专项备用机可显式运行 `NPatchEmbeddedLifecycleInstrumentedTest`：`lifecycle=checkpoint` 保存当前身份和白名单设置到**独立测试包的私有目录**；`retained` 核对覆盖安装或强停恢复后的完整当前身份；`unavailable` 检查端点不可达时的零功能安装；`fresh` 检查设置 App 重装后的未同意/未启用状态与新身份；`restored` 通过正式导入器恢复可自动恢复的设置，确认导入未授予许可，再显式接受条款、启用并冷启动真实宿主。测试不自行卸载或安装 APK。使用 `fresh/restored` 前须明确批准设置 App 数据删除，并保留独立测试包；卸载测试包会删除检查点。该专项测试要求 Android 12 以上，不改变产品最低版本要求。

```powershell
adb -s <设备序列号> shell am instrument -w -r -e lifecycle checkpoint `
    -e class com.Bilibili_Innocent_Lab.xposedmodule.runtime.NPatchEmbeddedLifecycleInstrumentedTest `
    com.Bilibili_Innocent_Lab.xposedmodule.test/androidx.test.runner.AndroidJUnitRunner
```

强停场景必须先在测试进程外冷启动宿主，等待其前台授权重试完成，按本次 PID 保存 `admission_unavailable` 和未安装的日志，再运行 `unavailable`。提前启动 Instrumentation 会唤醒设置端点，可能让仍未结束的前台重试成功，不能把这种测试干预写成“强停时仍取得许可”。`checkpoint/retained/unavailable/fresh` 均不启动或重启宿主，查询通过当前 nonce/来源校验；失败不能以历史心跳补成功。执行后删除独立测试包的 `files/npatch-embedded-lifecycle.json` 可清理检查点。

模式往返使用 `NPatchModeSwitchInstrumentedTest`，显式传 `-e modePhase checkpoint|retained -e expectedMode EMBEDDED|MANAGER`。先对实际已就绪宿主建立独立测试包私有检查点，再从外部覆盖安装另一模式并冷启动，`retained` 严格核对完整当前身份、免 Root revision、实际模式及当前安装状态；不打开设置首页、不修改业务配置。首页本身会刷新自由复制运行修订号，在两个阶段之间额外打开首页应视为另一笔配置变化，不能把新指纹当作模式升级丢失配置，也不能放宽完整身份断言。完成后删除 `files/npatch-mode-switch.json`。Manager 作用域页勾选后还须点击“添加”，以只读 Provider 实际返回模块包名核对持久化结果。

`NPatchEmbeddedCoexistenceInstrumentedTest` 以 `-e verifyEmbeddedCoexistence true` 显式运行，要求 Manager 保留安装；验证 Manager 强停后的 embed 当前许可、配置变化、停用、条款拒绝、恢复及 Web/子进程行为。它与既有“无 Manager”测试互补，不自行安装、卸载或授予权限。共享实机测试期间不要同时运行 `uiautomator dump`，两者会竞争 UI Automation 连接。

诊断页只读查询当前响应的 NPatch 进程、许可/安装状态和 Hook 点数。没有响应不等于没有注入，零 Hook 也不等于功能故障；主进程专用功能继续在子进程跳过。查询最多 16 个进程、1.5 秒等待，使用各进程独立的只读回调线程，不依赖宿主主线程绘制，不唤醒未运行的进程，不抢主进程心跳。原生 loader 修复后，embed 的 `:web` 恢复原有功能安装及注册时序，不延迟自由复制 Hook。初始化未完成时显示 `LOADED`，完整注册后显示 `INSTALLED` 及实际 Hook 点数，失败显示 `FAILED`。旧内嵌模块的 `GATED` 状态仍可解析，当前模块不再主动整进程隔离。当前已验证登录态保留、普通视频缓存下载/播放及模块账号授权与撤销；逐项范围见[行为案例](../../docs/npatch-embed-acceptance.md)及[全量矩阵](../../docs/npatch-feature-matrix.tsv)。其它业务、隔离进程和未测版本保持未知，不把一次下载或账号 ready 当作全功能验收。
