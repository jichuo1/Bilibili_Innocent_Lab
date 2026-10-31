# 冻结指定云控键

哔哩哔哩的页面与按钮大量由服务端云控决定，云控一变，功能就可能在宿主不升级的情况下突然出现或消失。`home_frame_lock`（固定旧版首页框架）只处理了一个键；本功能把同样的做法推广到任意键：用户写下 `键名=取值`，宿主在读取这些键时拿到固定值，其它云控键一律不碰。

## 规则语法

设置页「冻结指定云控键」→「云控键规则」，一行一条（也可用 `;` 分隔），重启哔哩哔哩后生效。

| 写法 | 含义 |
| --- | --- |
| `键名=true` / `键名=false` | 布尔读取固定为该值；字符串读取不受影响 |
| `键名=default` | 忽略云控下发的值，回到宿主调用处自己写的默认值；布尔、字符串读取都适用 |
| `键名=s:文本` | 字符串读取固定为该文本（文本里不能含 `;` 与换行）；布尔读取不受影响 |
| `# 注释` | 整行忽略 |

- 键名整串精确匹配、区分大小写，只允许 `A-Za-z0-9_.-`，最长 96 字符；不支持通配。
- 最多 64 条，文本值最长 512 字符；同一个键重复时后者生效。
- 规则只在**类型对得上**时生效：同一个键若同时被布尔与字符串两种方式读取，只有对得上的那种被改写。
- 网络、登录、风控与初始化类前缀会被拒绝，设置页摘要会显示"另有 N 条格式不对或被拒绝"：`infra.` `grpc.` `net.` `network.` `okhttp.` `blrouter.` `flowcontrol.` `login.` `passport.` `risk.` `security.`。冻结它们可能让宿主无法登录、联网或完成初始化，且用户无从判断后果。前缀按键名小写、带点比较，`login_banner` 这类不受影响。

「记录宿主读取的云控键名」是配套的观测开关：开启后把宿主读到的键名写入 LSPosed 日志（最多 400 个，需日志详细度为"完整"）。**只记录键名与布尔值，不记录字符串值**：字符串云控里有上报盐、密钥一类内容（例如 `videodetail.report_click_secret_key`），不能进日志。

## 读取边界

2026-10-31 用云端 dexq 对 9.1.0–9.17.0 共 24 个宿主逐版核对。9.17.0(9170300) 上，约 4000 个读取点经下列入口取云控值：`DeviceDecision#getBoolean` 1156 处、`#dd` 539 处、`IDeviceDecisionKt#getString$default` 191 处、`#getBool$default` 173 处、`KConfig#ff` 135 处、`ConfigManager$Companion#isHitFF` 125 处、`KConfig#config` 88 处，另有旧 blconfig 的 `Contract.get` 1399 处（`ab()` 816、`config()` 702）和 `ab2().getWithDefault` 555 处。

挂钩的入口（`family` 是安装状态里的分组名）：

| family | 入口 | 24 版核对 |
| --- | --- | --- |
| `dd` | `DeviceDecision#getBoolean(String, boolean[, Function1])`、`#dd(String, String[, Function1])` | 4 个签名 24/24 |
| `dd-impl` | `GDeviceDecision` 的实现类（`dd.a` / `dd.b`）的 3 参重载；按"实现了接口、不是接口、不是门面"识别 | 实现类集合 24/24 一致 |
| `kotlin-dd` | `IDeviceDecisionKt#getBool` / `#getString`（静态，键在第 2 位）；`KConfig.ff / config` 最终汇入这里，所以不另挂 `$default` 桩和 `KConfig` | 24/24 |
| `config` | `ConfigManager$Companion#isHitFF / getConfig`、`ConfigManager#isHitFF / getConfig` | 24/24 |
| `contract` | `OverrideFF#get / getWithDefault`、`OverrideConfig#get`、`TypedContract#get` | 24/24 |
| `contract-ff` | `TypedContract` 的 FF 子类（实现 `FeatureFlagContract`） | 类名逐版变化 |

关键事实：

1. **`isHitFF(key)` 实际是 `ab().get(key, true)`**：没有云控值时视为命中，所以它的"默认值"是 `true`，`default` 规则据此处理。
2. **9.17.0 上旧 blconfig 的读取经 `OverrideFF / OverrideConfig` 转给设备决策。** 两条初始化（`BLConfigInitTask` 与 `produceDDOverrideBLConfig`，由云控 `infra.blconfig.new.init` 二选一）都把 `gripper.container.blconfig.g` 作为 `userDelegate` 传给 `ConfigManager.init`，`g.getAB / getConfig` 直接调用 `GDeviceDecision` 的实现类（`getFallbackToOrigin()` 恒为 false），**不经过 `DeviceDecision` 门面**。所以 `dd-impl` 是这条路上最深的稳定汇合点，`contract` 一族是在它之前再拦一层，并覆盖没有 `userDelegate` 的较老宿主（那里读取走 `TypedContract` 系列）。
3. **`TypedContract` 的 FF 子类名字每版都变**（`q` `s` `r` `p` `o` `u` 在 24 版里轮换），但每版恰有一个，且在 `com.bilibili.lib.blconfig.internal` 包的单字母叶名里。安装器扫描单字母叶名，按"直接继承 `TypedContract` 且实现 `FeatureFlagContract`"识别，不认具体字母。`GDeviceDecision` 实现类同理（24 版固定为 `a` `b`，仍按结构识别）。
4. **接口调用落在合成桥接方法上**：`Contract<T>` 的实现类里，`get(String, Object)Object` 往往是编译器生成的桥接方法（带 `ACC_SYNTHETIC`），真正的类型化实现被改名后只由它转发。因此挂钩时不能过滤合成方法，测试里有专门的桥接用例。

## 为什么不动宿主自带的 `UserDelegate`

`ConfigManager$UserDelegate` 与 `IDeviceDecision$UserDelegate` 各只有一个实现类（`gripper.container.blconfig.g`、`gripper.container.devicedecision.l`），在 `BLConfigInitTask`、`InitDeviceDecisionKt.produceDeviceDecision` 里**无条件**安装。它们是宿主把旧 blconfig 读取转给设备决策的生产路径（`g.getFallbackToOrigin()` 恒为 false），不是调试或用户覆盖开关。替换它等于改写宿主的核心配置路由，风险远大于在读取边界上按键名查表。

## 实现要点

- `CloudKeyFreezeFeatureInstaller`：`after` 回调，宿主原实现照常执行（读取埋点、曝光上报不丢），只在返回前换结果。规则表在安装时一次建好，之后只读，回调里只做一次 `HashMap` 查表，未命中立即返回；一次读取可能连过 3–4 个钩子，换值是幂等的。
- 规则解析与类型匹配在 `CloudKeyFreezeRules` / `CloudKeyRuleResolver`，纯 Kotlin、无分配。
- 只在主进程安装；规则为空且未开观测时不注册任何钩子（`no-rules`）。
- 安装状态 `cloud_key_freeze_status`：`installed:rules=N,families=…`；个别钩子注册失败为 `partial:x/y:…`；全部失败为 `registration-failed`。
- 诊断项 `cloud_key_freeze`：`ADAPTED` 钩子已装；`OBSERVED` 读到了规则里的键（观测模式下读到任意键）；`APPLIED` 确实改写了一次与宿主原结果不同的值。
- 设置项 `cloud.key_freeze.enabled` / `.rules` / `.observe`，默认全关/空，自动恢复，需重启宿主；目录版本 66 → 67。

## 已知限制与未验证项

- **没有在真机上验证。** 本机无 Android 设备，签名、继承关系与调用链来自对 24 个宿主 dex 的静态核对与反编译，行为由单元测试里的同形假类覆盖。首次发布前应在真机上用观测模式确认键名被读到、规则确实生效。
- **只改读取结果，不阻止云控下发。** 服务端仍会照常推送新值，宿主缓存里的值也在变，只是被冻结的键读出来是固定值。想阻止同步（`DeviceDecision#onDDVersion`、`Contract#onVersion` 一带）是另一个方向，宿主的 `Worker` 另有兜底刷新，且会让网络类键一并过期，本功能没有做。
- **冻结不等于功能可用。** 把一个键固定成旧值，只能保证宿主读到旧值；如果服务端同时下线了旧行为，功能仍可能不可用。
- **约定的 `ff_*`、`dd.*` 之外的键**（`live.` `uper.` `miniapp.` `ad.` `player.` 等）同样可冻结，但很多是 JSON、阈值或地址而不是布尔，写 `s:` 前先用观测模式确认它被当作字符串读取。
- 观测模式的键名上限是每个进程 400 个，读到海量动态键时之后的不再登记。
- 与 `home_frame_lock` 并存时，后者挂在 `KConfig#ff` 外层，对 `dd.use_new_home_frame` 优先生效。
- 只处理主进程；其它进程（`:web` 等）不安装。

## 后续方向

- 观测结果回传模块设置页，做成可勾选列表（需要新增快照通道的面与取值编辑）。
- 把已确认的问题键整理成可一键开启的预设。
- 评估"阻止云控同步"作为独立开关。
