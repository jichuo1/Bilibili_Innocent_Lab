# F19.1：重签名微信分享兼容

2026-10-23 实施，2026-10-24 收尾，已按用户授权提交为 `f3f33a50`。已实现 B 站端兼容开关，并完成限定环境的无账号验收。用户明确选择将账号内预览、取消回调和投递流程保留待验；这些流程不计为通过。后续 QQ / QQ 空间独立对照已完成，结果见 [F19.2 记录](npatch-stage7-qq-share.md)。

## 行为与实现边界

入口为「净化 → 净化进阶设置 → 分享 → 兼容重签名微信分享」，默认关闭，修改后需要冷启动 B 站。更新亮点可定位该开关；三语文案、设置备份目录 v58 和诊断能力目录 v26 已接入。

`WeChatShareCompatAccess` 仅适配已审计的 B 站 8.90.2 / 8902100：传统 `kz3.a.t()` 与 `kntr.common.share.common.handler.wx.k.j()`。版本不符、SDK 不存在或包装结构不匹配时保留相应原行为；只有一个包装可用或注册成功时报告部分安装。反射复用 `KavaMemberLookup`，安装期缓存 Class / Method / Field，不缓存宿主 Activity、Context 或 SDK 实例，也不新增 DexKit 扫描。

`WeChatShareCompatFeatureInstaller` 在主进程、已获准配置且用户启用后注册三个点：两套分享初始化，以及它们共用的四参 `WXAPIFactory.createWXAPI`。已核对此版本的两/三参工厂委托四参版本。线程内创建作用域仅覆盖已审计的分享初始化，原方法成功或抛异常后均恢复；新建分享实例采用 SDK 原生 `checkSignature=false`。包装已有 SDK 时只调整该包装持有实例的同一开关。因此安装检测、注册和后续异步 `sendReq` 继续执行 SDK 原方法及实际返回值。

没有强制 `isWXAppInstalled` / `sendReq` 返回成功，没有修改 PackageManager 返回的签名，没有改变无关登录、支付、回调实例的工厂参数。`WXEntryActivity` 经另一包装取得 SDK；源码核查显示 `handleIntent` 不读取此开关，其来源、checksum 和回调处理保持原实现。这里的 `APPLIED` 只证明实例开关已实际放宽，不代表接收方预览或投递成功。

## 固定输入与产物

有线机 `13a7a291`，Android 16 / arm64；B 站 8.90.2，固定 Java/native 修复版 NPatch 833、等级 2、非 debuggable、无 injectdex。Manager 与 embed 均同证书覆盖安装，保留 B 站与模块数据。只操作该有线机。

| 输入 / 产物 | SHA-256 |
|---|---|
| 原版 B 站 | `804ab5d719da42f257aac6237dda408151b93ecff71a0da99c5f32c71161a2e5` |
| 最终模块 | `c3aea1fa508552db7f224769c9bc959882437e49c90cf0657c8ae77e367e3a5d` |
| 最终 embed 宿主 | `e01f5bd7555b192c66c31e0cdd46b0c95020e76ee60e985c6643ed241021656a` |
| 最终 Manager 宿主 | `29aa04115f5a4118fc5718c95b81ea46e6874c6d493e9c9d335ba88dd1cfefe7` |
| 原签名微信 8.0.64 / 2940 | `39fd81770ba854877e0433f3386279956da3b99e66c6e372767e91f1cbd27cb2` |
| 仅重签名微信 | `3796a83d39a1fe6532e9369743768485c37995d3e83769bc4b5d6b4d844f962e` |
| NPatch 重签名微信 | `106cf41b834884b791a3392892465e485608c1edc567c3116cb8aaa9cdef9aa1` |

微信原证书为 SDK 期望的 `0fe4ff85…c3c7c`；两个重签名样本均为本机调试证书 `9d1552d1…2b9e2`。NPatch 微信使用相同固定 833 patcher、Manager 模式、等级 2、`--extract-libs`，再用该调试证书签名；没有给微信加载 BIL Hook。原始证书和 APK 不改写，测试副本及实际安装内容的哈希单独核对。

首次未启用 native 提取的 NPatch 微信样本未完成安装，系统安装器出现损坏提示；保留原会话与日志，放弃该会话。另一轮直接传入调试密钥库时发生格式读取错误，未计为有效产物。最终样本经原 patcher 生成、独立重签、验签和真实安装，不将前两轮记为通过。

## 无账号验收

| 场景 | 实际结果与证据 |
|---|---|
| 原签名微信基线 | 原有宿主可以交接到微信 `WXEntryActivity`，随后为登录页；材料 `original-wx*` |
| 仅重签名，尚未安装兼容实现 | 真实出现「请先安装微信」，日志记录 `wx app not installed when init` / `ShareException`；材料 `resigned-before-hook/` |
| 最终 embed + NPatch 重签名，开关开启 | 普通视频的微信好友、朋友圈均真实拉起微信；当前配置获准，能力 `INSTALLED`、3 Hook、`observed=1` / `applied=1`；材料 `delivery-embedded-friend/`、`delivery-embedded-timeline/` |
| 最终 embed，关闭后冷启动 | 同一重签名微信重新被原 SDK 检查拒绝；能力 `DISABLED`、0 Hook、无回调；材料 `delivery-embedded-off/` |
| 最终 Manager + NPatch 重签名，开关开启 | 好友与朋友圈均真实拉起微信，当前身份与安装证据通过；材料 `delivery-manager-friend/`、`delivery-manager-timeline/` |
| 最终 Manager，微信未安装，开关开启 | 系统包路径为空，分享面板同时省略微信/朋友圈入口；材料 `delivery-manager-absent/`。没有伪造按钮或继续发送；SDK 包缺失返回值另由 JVM 测试覆盖 |
| 最终 Manager + 原签名微信，开关开启 | 2026-10-24 恢复后核对原 APK 哈希，真实交接到微信并取得当前 `APPLIED` 回执；材料 `resume-original-manager/` |

Manager 初轮为当前配置许可不可达，原失败回执保存在 `evidence-before-refresh.log`，没有计为功能通过。重启 Manager、重新选择现装 BIL、打开设置 App 并冷启动后，先验证当前身份与完整安装链，再实际分享并取得 `APPLIED`。期间没有放宽授权、修改运行协议或增加缓存许可。

接收方始终未登录；拉起、系统返回与宿主可继续操作的证据不代替账号内取消回调。实际消息发送未执行。B 站签名维度本轮仅覆盖 NPatch 修补的 Manager / embed；原签名 B 站 / 标准框架、LSPatch 接收方、其他版本 / ABI / 系统、图片及其他业务分享入口继续未验证。`kntr` 有实际注册与 JVM 调用链证据，本轮普通视频样本走传统包装，不宣称已触发备用包装的真实业务入口。

## 检查与材料

最终源码的 Debug、AndroidTest APK、3843 项 JVM（0 失败、0 错误、1 跳过）和快速 Lint 通过，5 分 14 秒；253 Warning，原 3 项错误基线不变。8 项新增 Hook 测试覆盖两套包装、已有实例、独立登录/支付/回调用途实例、包缺失、SDK 原始拒绝、异常清理、线程隔离、版本 / 进程 / 缺失结构与注册失败；另有新增设置默认值及备份边界测试。

首轮构建因沙箱拒绝 Kotlin 临时文件而失败；后续发现设置数量断言、布尔类型数量及新增公告目录遗漏，均修正并重新完整运行该轮检查。实机发现更新亮点缺少导航绑定，补齐后再次运行上述检查，并用最终冻结产物复测导航与分享。启动动画导致的界面树失败，以及触发分享前过早要求 `APPLIED` 的失败查询均保留，不计为通过。

材料均在忽略目录 `captures/npatch-f19/`：最终检查 `navigation-final-checks.log`，冻结产物 `delivery-{module.apk|embedded/|manager/}`，证书 / 内容摘要 `artifact-sha256.json`，各行为目录保留视图树、截图、原始 SDK / 系统日志和独立当前回执。没有新增依赖、扩大 baseline / suppress、完整 Lint、Release / R8、commit、push 或分发。

2026-10-24 电脑关机后恢复工作，先核对设备上的模块、Manager 宿主及原签名微信哈希，再完成 `resume-original-manager/` 回归；没有把昨晚中断、缺少结果的最后一轮记为成功。更新验收矩阵后，独立运行 `NPatchAcceptanceMatrixTest` 通过（39 秒，主源码/测试编译均 `UP-TO-DATE`），复用上述全套 JVM、Debug 和快速 Lint 结果。

最终恢复 embed 与 NPatch 重签名微信，并在 `resume-final-embedded/` 冷启动后再次取得真实交接及当前获准的 3 Hook / `APPLIED` 证据。`final-device-20261024.json` 核对已安装的模块、宿主、微信与上述冻结哈希完全一致；手机留在兼容开关已开启的设置页。矩阵的 412 行元数据及全部案例引用再次核对通过，文档相对引用有效，差异无空白错误；Gradle 已收尾且无守护进程运行。
