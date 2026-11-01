# 冻结指定云控键

哔哩哔哩的页面与按钮大量由服务端云控决定，云控一变，功能就可能在宿主不升级的情况下突然出现或消失。`home_frame_lock`（固定旧版首页框架）只处理了一个键；本功能把同样的做法推广到任意键：用户写下 `键名=取值`，宿主在读取这些键时拿到固定值，其它云控键一律不碰。

## 扫描并勾选（推荐）

手写键名门槛高：用户要先知道有哪些键。设置页「冻结指定云控键」→「扫描并勾选云控键」把这一步变成列表：

1. 打开「冻结指定云控键」和「扫描宿主读取的云控键」，重启哔哩哔哩，正常使用一阵、多打开几个页面。
2. 回到设置页点开「扫描并勾选云控键」，列表里是宿主读到过的**开/关类**云控键，可搜索，可只看已勾选。
3. 勾选要固定的键。默认固定成**服务端当前下发的值**；已勾选的行右侧「固定为：开/关」可点击翻成相反值——例如云控刚把某个新功能打开，想固定成“关”。
4. 确定后重启哔哩哔哩生效。若冻结总开关是关的，保存时会一并打开，免得勾了却没效果。

没有扫描结果时（还没开扫描、宿主没在运行、结果校验失败）会弹出说明，并提供「开启扫描」一键打开冻结与扫描开关；宿主暂时连不上时显示上一次保存的结果，至少能取消已有的固定。

面板的边界：

- **只列布尔键。** 快照里不带字符串值，面板也就无从固定字符串或 JSON；这类键仍用下面的手写规则（`s:文本` / `default`）。
- **读到过开也读到过关的键（`mixed`）不能新勾选。** 通常是宿主各调用处传的默认值不一致，说明这个键此刻没有可靠的“当前值”；已固定的 mixed 键仍可取消。
- **已被固定的键不重新记录值。** 它们的读取结果可能已被内层钩子换成固定值，此刻看到的不再是云控原值；面板保留上一次扫描到的原值。
- **手写规则优先。** 同一个键两边都有时以手写规则为准，面板里该行只读并标出；之前的勾选原样保留，手写规则删掉后重新生效。
- 扫描结果最多 1024 个键，固定最多 256 个；手写规则另有 64 条上限，两边互不挤占。极端长键名会让载荷超限，此时按键名顺序收缩到放得下，宁可少几个键也不让整份结果被拒。
- 扫描结果是累积的：同一宿主、同一模块版本下，多次运行读到的键取并集，同一个键以最近一次读到的值为准；宿主或模块升级后重置。受保护前缀的键、不合法的键名不进扫描结果。

勾选结果存为设置 `cloud.key_freeze.picked`（目录版本 69），每行 `键名=true|false`，与手写规则同一套语法，备份、导入、解析都复用同一个解析器。

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

「扫描宿主读取的云控键」是配套的观测开关：开启后一方面把布尔云控键汇总成扫描结果交给上面的勾选面板，另一方面把宿主读到的键名写入 LSPosed 日志（最多 400 个，需日志详细度为"完整"）。**只记录键名与布尔值，不记录字符串值**：字符串云控里有上报盐、密钥一类内容（例如 `videodetail.report_click_secret_key`），不能进日志，也不进扫描快照。

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
- 设置项 `cloud.key_freeze.enabled` / `.rules` / `.observe`，默认全关/空，自动恢复，需重启宿主；目录版本 66 → 67。扫描勾选面板的固定值 `.picked` 在目录版本 69。
- 扫描：`CloudKeyScanCollector` 在读取回调里只做一次 `ConcurrentHashMap` 查表（不分配、不加锁、不碰日志），新键与状态变化才登记；状态只会 `未见 → true/false → mixed` 单向推进，一个键最多触发两次发布，读到不稳定的值也不会反复落盘。发布在自己的守护线程上做，最多每 3 秒一次，任务体带 `HostThreadGuard`。
- 扫描结果走现成的宿主扫描快照通道（新增 `cloud_keys` 面、`cloud_key` 条目类型，条目多了一个只放 `true` / `false` / `mixed` 的 `value` 字段），签名权限保护的有序广播查询、来源版本校验、累积合并都复用；条目与载荷上限按面取，云控键面是 1024 个 / 256 KiB，其它面不变。
- 扫描是挂在冻结上的可选能力，有自己的状态键 `cloud_key_scan_status`（`collecting` / `unavailable`）和错误日志：扫描起不来时冻结照常安装。
- 手写规则与勾选固定值在安装时合并成一张表，同一个键手写规则优先。

## 已知限制与未验证项

- **没有在真机上验证。** 本机无 Android 设备，签名、继承关系与调用链来自对 24 个宿主 dex 的静态核对与反编译，行为由单元测试里的同形假类覆盖。首次发布前应在真机上用观测模式确认键名被读到、规则确实生效。
- **只改读取结果，不阻止云控下发。** 服务端仍会照常推送新值，宿主缓存里的值也在变，只是被冻结的键读出来是固定值。想阻止同步（`DeviceDecision#onDDVersion`、`Contract#onVersion` 一带）是另一个方向，宿主的 `Worker` 另有兜底刷新，且会让网络类键一并过期，本功能没有做。
- **冻结不等于功能可用。** 把一个键固定成旧值，只能保证宿主读到旧值；如果服务端同时下线了旧行为，功能仍可能不可用。
- **约定的 `ff_*`、`dd.*` 之外的键**（`live.` `uper.` `miniapp.` `ad.` `player.` 等）同样可冻结，但很多是 JSON、阈值或地址而不是布尔，写 `s:` 前先用观测模式确认它被当作字符串读取。
- 观测模式的键名上限是每个进程 400 个，读到海量动态键时之后的不再登记。
- 与 `home_frame_lock` 并存时，后者挂在 `KConfig#ff` 外层，对 `dd.use_new_home_frame` 优先生效。
- 只处理主进程；其它进程（`:web` 等）不安装。

## 后续方向

- 扫描面板目前只处理布尔键。字符串与 JSON 键要固定，需要先决定值怎么跨进程传：快照里不能带字符串内容（可能有盐值、密钥），备份导出也会带上它。
- 面板里给每个键附上“它大概控制什么”的线索（比如首次被读到时所在的页面），现在只有键名。
- 把已确认的问题键整理成可一键开启的预设。
- 评估"阻止云控同步"作为独立开关。
