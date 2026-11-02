# 阶段 8：Shizuku 宿主重启

后续用户已明确将执行优先级调整为 **Root → Shizuku → 手动兜底**，并要求提前预检和可靠的等待状态。Root 与 Shizuku 已归入统一保存 / 执行 / 当前回执流程，具体实现及本轮证据见[重启能力探测](restart-capabilities.md)。下文首版和并列按钮部分保留各轮当时的产物、结果及历史 UI。

2026-10-24。实施范围为 F11 的固定宿主重启通道，设备限定为无 Root 一加备用机 `13a7a291`（Android 16 / arm64、B 站 8.90.2）。系统操作能力与 NPatch 注入能力分别验证；不包含 DexOpt、任意命令、其它框架支持或正式发布验收。

## 依赖与接口依据

- Maven Central 实际发布的 `dev.rikka.shizuku:api` / `provider` 为 `13.1.5`；沿用现有 Android 工具链及 BetterAndroid / KavaRef 版本。
- 管理器使用[官方 13.6.0 Release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)，APK SHA-256 为 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`。界面显示的服务版本为 13.5，实际服务及 UserService 的 UID 均另行读取，不从管理器版本推定权限。
- 依据[官方 API 指南](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)使用 UserService，不使用已弃用的 `newProcess`。Provider 由 `INTERACT_ACROSS_USERS_FULL` 保护；API 13 的包 Context 仅用于查询设置 App 和固定宿主的 UID。
- [官网启动手册](https://shizuku.rikka.app/guide/setup/)仍展示旧的 `start.sh` 路径；本次安装的管理器实际提供原生 `lib/arm64/libshizuku.so` 启动命令。按设备管理器当场显示的路径执行，通过 ADB 取得 shell UID `2000`。没有启用 NPatch 自身可选的 Shizuku 授权。
- API 的 MIT 许可证保存在 `third_party/Shizuku-API-LICENSE.txt`，APK 内包含同一许可证，第三方声明同步更新。

## 用户流程与边界

重启确认采用并列的“取消 / 打开应用详情 / Shizuku 重启”按钮，原系统应用详情向导和 Root 分支保留。打开弹窗即做只读能力探测：没有管理器也没有可用 Binder 时隐藏 Shizuku 选项；已安装但服务未启动、版本/身份不支持或连接中断时显示禁用按钮及原因；服务可用时作为推荐主按钮，未授权时仍可进入显式授权流程。弹窗监听服务连接、断开及回前台事件，动态更新推荐状态。探测不请求授权、不绑定 UserService、不操作宿主。

点击推荐按钮后进入授权/重启进度面板；面板继续区分未安装、服务未运行、版本不满足、未授权、拒绝、可用、连接中断及执行结果，可打开官方安装页或管理器。仅点击时请求授权，授权完成后需要再次点击“保存并重启”，Binder 重连或迟到的权限回调不会自动执行重启。

执行顺序：

1. 复用 `NoRootSupportController.flushBeforeRestart` 保存当前启用快照或关闭 tombstone；标准框架分支同步等待偏好落盘并经既有发布器发布。保存失败不进入特权操作。
2. 捕获当前发布身份；强停前、启动前和确认回执时复读。条款、配置、用户意图或发布身份变化会终止本次流程。
3. 绑定非 daemon UserService。Binder 业务调用仅接受设置 App UID，服务本身只接受实际 root / shell 身份。接口不接受包名、用户编号、组件或 shell 文本。
4. 对同一 Android 用户内的固定 `tv.danmaku.bili` 执行强停，以 `ps` 的 UID、PID、进程名确认主进程和子进程全部退出。针对 ROM 在死亡回调中重建 sticky 下载进程的实际行为，最多执行三次相同目标强停，并要求连续 300 毫秒没有目标进程。命令失败、输出无法解析或持续残留均阻断启动。
5. 使用有效期 15 秒、一次性的内部票据执行固定 `am start --user … -W -n tv.danmaku.bili/.MainActivityV2`。同时检查退出码、错误输出和新主进程 PID；不使用 `monkey`，不写旋转或方向锁定设置。
6. 启用态要求当前 nonce 诊断、新 PID / 新会话、当前完整发布身份及安装链完成；NPatch 另核对本次进程查询的安装状态。关闭态发起既有 nonce 存活探测，要求新 PID 的 `DISABLED` 状态及当前关闭 revision 的确认。命令成功不等同于配置生效，回执超时明确保持未确认。

设置页离开前台时取消尚未发出的后续变更；已进入启动后的阶段只读验证。弹窗关闭、Activity 销毁、服务死亡会释放单飞状态并清理监听、连接和待执行回调。完成启动后主动销毁特权服务，避免闲置常驻；同一次请求的迟到回调不能产生额外重启。

## 验证记录

原始截图、UI 树、固定产物、签名与测试日志均位于忽略目录 `captures/shizuku-stage8/`。以下仅记录实际运行结果，不以历史 NPatch 验收代替本次证据。

| 场景 | 证据与状态 |
|---|---|
| 安装与启动 | 一加官方管理器安装成功，官方原生启动器退出码 0，服务 UID 2000 |
| 首次授权与拒绝 | 正常系统授权弹窗；拒绝后显示拒绝提示，宿主保持未运行。后从管理器正常允许本模块 |
| 取消 | `cancel-test.log`，生产 Controller 在保存阶段取消，没有进入强停或启动，专项通过 |
| embed 正常重启及重复操作 | 初轮 `embedded-success-test.log` 20.965 秒通过；有界强停修正版 `delivery/embedded-repeat.log` 34.463 秒通过。最终 URI 扩展调整包 `final-ui/embedded-repeat.log` 20.547 秒通过；连续 20 次请求只有一次强停阶段、一次启动及当前配置确认 |
| embed 关闭配置 | 修正版 `delivery/embedded-disabled.log` 12.924 秒通过，实际得到新 PID 的停用状态及当前关闭 revision 确认。首轮遗漏 nonce 探测的 `embedded-disabled-test.log` 仍保留为失败，不计通过 |
| Manager 正常、关闭及重复 | `delivery/manager-repeat.log` 14.458 秒、`delivery/manager-disabled.log` 19.574 秒均通过；最终 URI 扩展调整包 `final-ui/manager-repeat.log` 13.345 秒通过 |
| 授权撤销、服务停止及恢复 | `revoked-status-window.xml`、`binder-dead-window.xml`、`service-reconnected-window.xml`：撤销后回到未授权，Binder 死亡显示断开，恢复服务仅更新状态，没有自动打开宿主 |
| 一加 sticky 下载进程重建 | `final/manager-disabled.log` 首轮如实保留为失败；系统日志证实 `force-stop` 后立即重建 `:download`。增加三次上限及稳定退出窗口后，两项 JVM 回归和同一 Manager 关闭场景通过；持续残留依旧明确失败 |

JVM 测试覆盖固定目标和 Android 用户、强停失败/残留进程、ROM 重建一次后的重试及持续重建的失败上限、错误启动输出、外部重新启动、一次性/失效票据、无法解析的进程输出，以及旧进程、PID 重用、旧发布身份、缺失在线会话、部分安装及旧配置回执拒绝。已有落盘、授权及配置协议测试继续参加全套 JVM 门禁。

首轮 JVM 发现新弹窗文件名未纳入既有扫描集，改为 `ShizukuRestartDialogs.kt`；纳入扫描后按既有约定把受检弹窗总数从 48 更新为 49。首轮快速 Lint 的 Kotlin/UAST 构造参数属性分析异常通过显式属性声明规避；分析期间源码变动后的 PSI 遍历停滞以停止本任务构建、冻结源码重跑处理，保留中断日志，不计为通过。没有关闭检测器、改工具链或扩展 baseline。

最终门禁 `gates-delivery.log`：Debug / AndroidTest APK、3860 项 JVM（0 失败、0 错误、1 项既有跳过）及快速 Lint 通过，4 分 39 秒；0 个未基线化错误、253 条警告，原 3 项错误基线保持。修正版后端的上一轮 `gates-rom-final.log` 同样通过（6 分 13 秒），最终仅将下载入口的 `Uri.parse` 换为项目已有 `toUri` 扩展，消除新增建议；后端源码哈希未变，关闭态专项复用同后端的通过结果。没有运行完整 Lint、Release/R8 或再次扩展验收范围。

首次交付的模块与测试 APK、两种宿主及 `verified.json` 保存于 `final-ui/`。模块 SHA-256 为 `9a82b9fa9995ac4836c20d58ba9665f8b944bd819aae0e074dd6deea31f40f78`，Manager 宿主为 `f90f2bed19f142a0f8d7c8f3f7e447bb90f3c57d71b3f900f163f94eef103d5e`，embed 宿主为 `d2feb4e30ac9a819639db45b7b83955e45e4fe7b6561532fe723cc5209083669`。两种宿主签名均保持 `08b00b38…e23`，模块签名保持 `9d1552d1…b9e2`；embed 保留既有漫游模块。后续并列推荐按钮修订的当前产物与结果见下节。

最终真实 UI 从右上角重启 → 使用 Shizuku 重启 → 保存并重启完成闭环，旧 PID `7398` 退出，新 PID `10935` 取得当前配置回执；`final-ui/ui-result-window.xml` 和截图明确显示“已确认哔哩哔哩新进程和当前配置回执”。`final-ui/installed-verified.json` 实际读取设备 APK 哈希，与上述最终模块 / embed 完全一致。方向锁定 `accelerometer_rotation=0`、`user_rotation=0` 与基线一致，无闲置 `host_restart` 进程；Shizuku server UID 2000 持续运行，手机留在成功结果面板。

Gradle 已执行 `--stop`；确认没有其它 Gradle 构建后关闭本项目闲置 Kotlin 守护进程，`final-ui/process-cleanup.json` 记录构建 / 编译进程均为 0。文档回填与链接检查不重新运行 Android 分析。恢复工作后设备操作均显式限定为 `13a7a291`，不再操作用户正在使用的另一台手机。

## 并列按钮修订

用户指出首版独立文字入口不像按钮，要求与取消和应用详情并列，并优先推荐可用的 Shizuku。三项操作改用同一行的真实 `Button`，可用的 Shizuku 位于右侧、突出推荐；进度面板的保存操作也使用相同按钮样式。共享只读 `ShizukuAvailability` 供入口与执行 Controller 使用，避免两个入口各自判断出不同状态。既有 UserService、固定命令与当前回执协议不变。

`captures/shizuku-stage8/recommendation-ui/ready.xml` 验证三个按钮为相同父行的 `android.widget.Button`，纵向边界一致且均可点击；截图 `ready.png` 验证布局。仅在一加 `13a7a291` 停止当前 Shizuku server，`stopped.xml` 验证 Shizuku 按钮即时 `enabled=false` 并显示启动提示，取消与应用详情仍可用；恢复服务后 `restored.xml` 中原按钮重新启用并显示推荐，宿主仍未被自动启动。缺失服务/管理器、已安装未运行、旧 API、未知 UID、未授权与 Sui Binder 等边界由新增 5 项分类测试覆盖，不为测试添加状态伪造入口。

本轮 Debug / AndroidTest APK、3865 JVM（0 失败、0 错误、1 项既有跳过）及快速 Lint 通过（4 分 54 秒，253 Warning、原 3 项错误基线不变），见 `recommendation-ui/gates.log`。本轮只改推荐入口与共享探测、未变的底层重启执行边界沿用上述双模式专项；新版匹配的 embed 产物另从真实推荐入口复测。该轮模块 SHA-256 为 `128fbe0a2b1dde82e092ee565e7d1d89d643e0548b26ef1343830a5d0d867213`，embed 为 `5fa3fb4d95f8736d40b1ffbc9c76c1b966f3823f956dca974ae6ed890b2b20b1`；均为同证书覆盖安装，既有漫游模块保留。

真实点击“Shizuku 重启 / 推荐”进入新面板后，再点击“保存并重启”，`recommendation-ui/result.xml` 确认当前配置回执，实际宿主 PID 为 `24135`。`installed-verified.json` 核对安装包与上述当前产物完全一致；方向锁定仍为 `0 / 0`，没有闲置 `host_restart` 进程。手机最终留在 `final.xml` / `final.png` 的三按钮推荐确认框，服务保持运行；本轮 Gradle / Kotlin 收尾记录为 `process-cleanup.json`。

用户后续要求去掉按钮上换行的“推荐”标签。按钮统一显示“Shizuku 重启”，英文与繁体同步移除额外标签；保留可用时的主按钮样式、只读探测和禁用 / 隐藏策略。

该文案修订的 Debug 构建及快速 Lint 通过（4 分 25 秒、253 Warning、0 个未基线化错误），见 `captures/shizuku-stage8/button-label/gates.log`；未改变的重启逻辑复用前述测试。仅覆盖安装一加 `13a7a291` 的模块，`after.xml` / `after.png` 确认“取消 / 打开应用详情 / Shizuku 重启”三个按钮并列，Shizuku 按钮为单行。安装包 SHA-256 与本轮模块 `9000f28d7628c93dde835791d71ad68160a009fc0707a01e382e33485e9fb735` 一致，宿主 APK 沿用上一轮产物。手机留在修订后的确认框，方向锁定仍为 `0 / 0`，构建与编译进程已全部退出。

## 支持边界

仅将本页实测组合记为通过。Root / Sui 启动身份、其它 Android / ROM / 用户空间、未知 NPatch 版本和其它免 Root 框架没有由一加 shell 结果外推。原 Root 重启分支保留，但本轮不在正在使用的另一台手机上回归。正式发布时仍须针对最终产物执行完整 Lint、Release/R8 和发布验收。

阶段 9 的 LSPatch 与其它免 Root 框架调查继续按路线图另行推进。
