# 重启能力探测与优先级

2026-10-24，按用户确认的顺序实施：**Root → Shizuku → 手动重启**。框架加载方式和执行权限分别判断；通过 NPatch、LSPatch 加载的模块也可以使用 Root。本文接续 [Shizuku 阶段 8](shizuku-stage8.md)，不把已有名称识别或服务探测外推成 LSPatch 已通过兼容验收。

## 探测时机与状态

设置页在条款授权完成后、主布局构建前启动只读预热，回前台刷新，重启确认框复用同一进程内的结果。Root 与 Shizuku 使用独立后台队列，主线程只处理快照；同一轮合并重复请求。

一轮检查有 2 秒界面等待预算，结果缓存 5 秒。Root 的结果已足以确定首选时即可操作，不必等待低优先级的 Shizuku；否则在首选尚不能确定时显示“检查中…”，特权操作不可点击，取消和手动入口保留。打开弹窗不会强制废弃刚预热的结果。没有“探测好了就自动执行”的待重启请求。超时保留已经完成的检查，将未返回的 Root 标为未知、Shizuku 标为连接异常，显示重新检测入口。超时与缺失不同；迟到、重复及上一轮的结果不能覆盖当前快照。

Shizuku Binder 的连接、死亡以及显式授权结果会使旧一轮失效，重新检查。退出弹窗移除界面订阅，进程级缓存只持有 Application，不保存 Activity 或 View。

## Root 证据

只读预检检查常见 su 路径和绝对 PATH 目录中的可执行文件，不调用 su、不读取 Root 管理器私有数据、不弹授权窗。找到 su 只显示“尚未确认本模块的 Root 授权”，提供首选 Root 入口；找不到表示“未找到可用 su”，不能据此断言整台手机没有 Root。

用户明确点击申请授权后，在后台执行固定的 `su -c '/system/bin/id -u'`，只有退出码为 0 且输出严格为 UID 0 才记录授权证据。等待最多 120 秒，关闭面板会中断等待；授权完成只更新状态，仍需点击保存并重启。证据只在当前进程中短暂保留 30 秒，并绑定 su 路径；过期或路径变化后恢复待确认状态。

实际 Root 命令复用固定目标 `HostRestartCommands`，只允许当前用户的 B 站进程查询、强停和指定 MainActivity 启动。每条命令在同一个 su 调用中先验证 UID 0，再输出本次随机身份标记，随后执行固定命令。没有当前唯一标记、权限失效、非零命令退出或进程检查失败，都不能继续伪报成功。不会因旧授权证据跳过实际身份校验，不修改方向锁定设置。

官方 [libsu Shell 文档](https://topjohnwu.github.io/libsu/com/topjohnwu/superuser/Shell.html#isAppGrantedRoot()) 同样区分尚不能确定的授权和已建立 Root shell 的证据；初始化 Root shell 可能触发授权并耗时。本实现复用工程已有有界进程执行器，不为有限的固定命令新增通用 shell 依赖。

## 配置与执行分离

配置通道与发布权威源保持一致：显式选择免 Root、尚待完成关闭，或当前能力 / 有效宿主安装档案确认载体是 NPatch 时，保存对应启用 / 停用文档；其它情况保存标准配置。已关闭且确认完成的 NPatch 再次重启仍应保存停用文档、验证停用结果，不能转去等待标准启用回执。NPatch 载体复用既有 API / Remote / 系统能力边界及 `NPatchRuntimeProfiles` 对当前固定宿主元数据的严格解析；有效安装档案覆盖 embed 没有模块侧框架服务的情形，损坏或未知档案不伪造有效结论。LSPatch 不套用 NPatch 私有配置。框架名字不再覆盖用户选中的发布通道，也不决定执行权限，原框架推断重启路径及其旧测试已经移除。Root、Shizuku 均复用 `RestartConfigBarrier` 和 `PrivilegedRestartController`：先等待当前配置保存，捕获发布身份，再执行强停、新进程检查及当前配置回执验证。配置在期间变化、权限撤回、取消或服务失效时停止后续操作。

Root 直接执行与 Shizuku UserService 共用固定包名、当前 Android 用户、稳定退出检查、有限 ROM 重试和一次性启动票据；命令成功不等同于配置已生效。没有 Root 实际授权时不会按 LSPosed 名称伪造 UID 0，也不会因为 NPatch / LSPatch 是免 Root 框架而禁止使用真实 Root。

已有 NPatch 系统详情返回后的保存、打开及启停握手向导保留。标准配置通道的手动返回使用当前会话和当前配置回执，旧进程会话不能确认新启动。

系统应用详情是最后兜底：保存失败会明确提示，仍允许用户进入系统详情；从详情返回后的自动打开 / 验证继续要求保存成功，不将手动入口可达冒充配置已经生效。

## 界面选择

| 状态 | 首选 |
|---|---|
| 发现可申请授权的 su，或最近已实际确认 Root | Root 重启；未确认授权时先显式申请 |
| Root 未找到、上次未确认或检查异常，Shizuku 服务与授权可用 | Shizuku 重启 |
| 同上，Shizuku 服务可用但尚未申请授权 | Shizuku 授权入口，授权本身不重启 |
| Root 和 Shizuku 都不能提供可用操作 | 手动应用详情兜底，保留重试或修复入口 |

主行保持取消、应用详情与首选特权方式；混合情况下另一种方式位于同一弹窗的次级按钮。未安装且无服务的 Shizuku 隐藏，版本不支持时置灰；已安装未运行和授权拒绝均可进入状态面板，再打开管理器恢复。2026-10-26 补齐未运行时的恢复入口，`STOPPED` 仍不具备重启能力，首选方式仍是手动；点击入口不会自行启动服务、授权或重启宿主。按钮不增加换行的“推荐”标签。失败面板可返回重启方式选择，不自动申请另一种权限或发起第二次重启。

## 验证边界

探测单飞、短缓存、未完成检查、超时、服务事件使旧结果失效、晚到回调、Root 优先及身份验证边界有独立 JVM 测试。实机操作严格限定一加备用机 `13a7a291`，该机无 Root；Root 真机、Sui、LSPosed、LSPatch 和其他框架组合仍须各自验证，不能由 shell UID 2000 的结果外推。

## 2026-10-24 实际结果

证据目录为 `captures/shizuku-stage8/root-priority/`。最终源码的 Debug / AndroidTest APK、3877 项 JVM（0 失败、0 错误、1 项既有跳过）及快速 Lint 通过，见 `gates-complete.log`，5 分 18 秒；253 条警告，0 个未基线化错误，原 3 项错误基线不变。没有运行完整 Lint 或 Release/R8。

真实无 Root 预检在 Manager / embed 测得 15 / 17ms；最终交付包的 `delivery-preflight.log` 测得 13ms。测试在检查结果返回前连续调用 20 次，结算后再调用 20 次，未出现 Root 授权请求、保存、强停或启动状态，不存在迟到的待执行重启。

`final-manager-{repeat,disabled,cancel,restored}.log` 与 `final-embed-{repeat,disabled,restored}.log` 均通过真实生产 Controller：重复点击只出现一次强停 / 启动，停用结果按当前 revision 确认，取消不进入强停。随后只修改了入口的 NPatch 配置载体判据和手动详情兜底，执行后端源码未变，复用这组专项。最终匹配产物另验证真实关闭态 UI（`delivery-disabled-result.xml`）、当前启用回执与重复点击（`delivery-restored.log`，32.466 秒）。

真实 UI 曾复现 embed 没有模块侧框架服务时的保存失败，宿主当时没有被重启；`disabled-ui-result.xml` 保留失败证据。复用有效宿主安装档案后，`profile-disabled-result.xml` 确认新进程按当前配置停用，最终匹配包再次通过同一入口，未通过跳过保存或伪造回执规避问题。

`service-transitions.json` 记录同一弹窗随服务停止而置灰、恢复而启用，服务变化没有启动宿主。最终包的 `delivery-service-stopped.xml`、`manual-details.xml`、`manual-resume.xml` 验证无可用特权时仍可进入真实系统详情，返回后显示打开 / 验证确认，取消后没有自动启动。未在这次手动入口检查中声称执行过系统强停。

最终模块 SHA-256 为 `2e021f31963e9d3e865b5b90674c8b54f42ddc6d19e6db4926fef6cf69ef4594`，匹配 embed 为 `278fea1337f1df4c292652653785649e6ab1c8662f4348452a7d3b08df47e90c`，见 `delivery-embedded/verified.json`。已从设备读取 APK 哈希并核对一致，原签名和漫游 1.7.0 保留；方向锁定仍为 `0 / 0`，无闲置 `host_restart` 进程，Shizuku server 为 shell UID 2000。`final.png` / `final.xml` 为交付确认框。

一次主动中止检查后遗留了本轮测试子进程，造成 Windows `R.jar` 被占用；没有清缓存或修改工具链。核实其父进程已退出及工作路径后，仅清理本轮孤立测试 / 编译进程，恢复记录为 `resource-lock-recovery.json`。最终 Gradle 已正常 `--stop`，`process-cleanup.json` 记录构建 / 编译进程为 0。

## 实机异常与未解决项

最终启用重启的当前回执确认后，B 站出现 ANR 弹窗。`host-anr-events.txt` 和 `anr-system.log` 记录下载服务、Web 小程序服务 20 秒启动超时，以及主进程的既有诊断广播超时；系统日志同时出现其它应用启动 / Provider 超时与较高换页开销。该现象的根因尚未确认，不能由回执通过推出宿主后续稳定性通过，也不能直接归因于 Root 探测。

`dumpsys activity lastanr` 未提供栈，系统 `debuggerd -j` 要求 Root，宿主又不是 debuggable，未取得主线程 Java 栈；没有为此操作另一台手机、修改系统策略或获取 Root。记录保存后，仅停止一加上卡住的 B 站进程以解除弹窗，保留其数据，再完成手动详情入口 / 返回取消 / 服务恢复检查。手机最终留在模块重启确认框，Shizuku 保持运行，宿主保持停止，见 `installed-verified.json`。
