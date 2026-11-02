# F19.2：重签名 QQ / QQ 空间分享验证

2026-10-24 完成限定环境的无账号对照。测试机原有 QQ 9.2.20 确实已安装；从同一原包构造 NPatch 重签名副本后，B 站的好友和 QQ 空间入口在 Manager、embed 两种宿主模式下均交接到 QQ 登录页，未复现未安装误报。本轮无需新增 QQ Hook 或兼容开关。

用户已选择先完成无账号验证，账号内预览、取消回调和投递保留待验。这里的交接结果不代表消息已送达，也不扩大到其它版本和消息类型。

## 代码依据

复核 B 站 8.90.2 / 8902100 的传统 `QQAssistActivity` → `hz3.b` / `hz3.c` → `hz3.a` 路径，以及其调用的 Tencent SDK：

- `hz3.a.M(Context)` 检查 QQ / TIM 包及版本。检测失败，或原生启动抛 `ActivityNotFoundException`，均可进入 `N(Context)` 的“尚未安装QQ/TIM或当前QQ/TIM版本不支持分享~”（`-234`）。因此该提示需要按实际失败阶段定位。
- `QQShare` / `QzoneShare` 经参数、版本和 Intent 解析检查，构造 `mqqapi://share/to_fri` / `mqqapi://share/to_qzone`，由宿主 `AssistActivity` 交给 QQ `JumpActivity`；已追踪路径没有微信式的固定 QQ 客户端证书门槛。
- `SystemUtils.getSignValidString` 使用调用方自身包的签名；`SocialApiIml` 对独立 `com.qzone` 的证书选择不等于本次经 QQ 的空间分享。这些方法不能作为新增 QQ 签名绕过的依据。
- 同 APK 的 `kntr.common.share.common.handler.qq.g` 调用同一 Tencent 分享 API；本轮实际普通视频走传统包装，未把备用入口算为实机通过。

原始调查及本地源码索引见 [F19 调查](npatch-f19-share-investigation.md)。JADX 的部分 QQ 空间条件重建存在失真，本轮没有按这些条件臆造修复；运行证据以 SDK 日志、真实目标 Activity 和接收界面配对。

## 固定样本

只操作有线机 `13a7a291`，Android 16 / arm64。宿主和模块完全复用 F19.1 已检查的冻结产物，没有重建或修改生产代码：模块 `c3aea1…3a5d`、embed 宿主 `e01f5b…656a`、Manager 宿主 `29aa04…efe7`，完整哈希见 [F19.1 固定输入](npatch-stage7-wechat-share.md#固定输入与产物)。两次模式切换均同证书覆盖，保留 B 站和模块数据。

| QQ 9.2.20 / 11650 样本 | APK SHA-256 | 证书 SHA-256 |
|---|---|---|
| 设备提取的原签名包 | `7fba840b238555c970dce7f47bed8cb5f7b3a64818c99f3ee951fc6a550be743` | `ea6e97ad6c34f7039a9c6daba732c97d0e098e83ede2b4d52c76eb0184ac7a38` |
| 同一原包的 NPatch 重签名副本 | `5406c8250e50ca048af5c05ddca798214227422f880f00e2f59327c46106613e` | `9d1552d12d48a29b0d04c824700660129b87abfac8e26b2bc03930dabb32b9e2` |

接收方使用固定 Java/native 修复版 833 patcher（`dfa41340b717ca4de5a0de8e283e60d21ec38ce4b42f3d67657e01efeeecaff6`），参数 `--manager --sigbypasslv 2 --extract-libs`，再用既有本地调试证书签名并验签；其包名保持 `com.tencent.mobileqq`。实际安装文件的 SHA-256 与副本完全一致，`assets/npatch/config.json` 明确 `useManager=true`、`sigBypassLevel=2`、`debuggable=false`、loader 833。QQ 当前进程日志另有 NPatch 初始化及空模块缓存证据，没有给 QQ 加载 BIL Hook。

这里的两种宿主模式均指 B 站；QQ 接收方样本自身固定为上述 NPatch Manager 模式，不能据此宣称 QQ 自身 embed / LSPatch 也已测试。原签名 QQ APK 单独保留，未改写。

## 实机对照

| B 站模式 / 接收方 | 真实操作与结果 | 本地材料目录 |
|---|---|---|
| embed / 原签名 QQ | 普通视频好友入口：SDK `type: 7`、`support share`，系统成功启动 QQ `JumpActivity`，随后为登录页 | `embedded-official-friend/` |
| embed / 原签名 QQ | 同一视频 QQ 空间入口：`shareToQzone`、类型 7，系统成功启动 QQ `JumpActivity`，随后为登录页 | `embedded-official-qzone/` |
| embed / QQ、TIM 均未安装 | 包路径确认缺失；分享面板同时省略 QQ 和 QQ 空间，没有伪造入口或发送成功 | `embedded-absent/` |
| embed / NPatch 重签名 QQ | 好友首次启动经过 QQ 隐私说明与升级提示；完成当次隐私确认、退出升级提示后，重复好友和 QQ 空间分享均进入真实登录页 | `embedded-resigned-friend/`、`embedded-resigned-friend-repeat/`、`embedded-resigned-qzone/` |
| Manager / 同一 NPatch QQ | 冷启动并确认当前宿主获准后，好友和 QQ 空间均由 SDK 启动 QQ `JumpActivity`，随后为登录页 | `manager-resigned-preflight/`、`manager-resigned-friend/`、`manager-resigned-qzone/` |

当前进程证据查询在原签名 embed、重签名 embed、Manager 启动前置和分享后分别通过，严格校验实际模式、当前配置身份、DIRECT 许可及安装链完成。QQ 专项操作期间微信兼容能力为 `INSTALLED` / 3 Hook、`observed=0` / `applied=0`，没有把微信能力回调当成 QQ 成功依据。SDK 启动日志和接收界面才是 QQ 交接证据。

Manager 前置 Provider 查询曾返回 `No result found`，该项没有写成作用域可达证明；正常打开 Manager、模块和冷启动后取得的是独立当前宿主响应。没有变更作用域、放宽授权或复用旧心跳冒充成功。

原签名与重签名的空间分享日志均出现过 `libwbsafeedit_64` 缺失、独立 `com.qzone` 不存在或 SDK 遥测 DNS 错误；本轮仍真实启动了 QQ。保留这些日志，不把它们直接归因为 QQ 重签名失败，也不据交接结果保证账号内后续处理正常。

首次自动检查只匹配按钮文本“登录”，未识别 QQ 的手机号登录页，曾留下失败结果；随后按原始截图、`content-desc="登录界面"` 与 `LoginActivity` 配对复核，另存 `result-verified.json`，保留原失败记录。未以修改业务代码或放宽宿主身份检查修复自动化误判。

## 边界与收尾

没有登录 QQ、选择联系人、发送消息、升级接收方或修改系统安全/旋转设置。真实接收预览、账号内取消回调、最终投递、TIM、独立 QQ 空间 App、图片及其它业务类型、其它 QQ / B 站版本、标准框架与 LSPatch 保持未验证。Manager 下的原签名 QQ 和包缺失没有另行重复，不能从 embed 对照外推为已测。

本轮只新增验收记录和更新文档，不新增设置、能力或矩阵行，不提升分享净化开关的状态。F19.1 已按用户授权提交为 `f3f33a50`；QQ 本项为独立文档交付。源码与分析输入未变，复用 F19.1 的 Debug / AndroidTest、3843 项 JVM 与快速 Lint 结果，本轮不重复构建或 Lint。

材料保存在忽略目录 `captures/npatch-f19-qq/`：原包与重签名证书、修补日志、`resigned-artifact.json`、各场景的视图树 / 截图 / SDK 日志 / 当前宿主检查。账号相关视图与原始日志仅留本地，不写入公开文档或自动发送。恢复 embed 后的实际设备身份与最终界面另存 `final-embedded/`。

收尾已逐一核对设备模块、embed 宿主、NPatch 微信和 NPatch QQ 的实际 APK 哈希，均与冻结产物一致；冷启动后的当前身份 / 许可 / 安装链检查再次通过。手机留在 B 站原生分享面板，微信、朋友圈、QQ 和 QQ 空间入口均可见，QQ 保留为上述重签名测试副本。文档相对文件链接、412 行矩阵的案例引用及差异空白检查通过，矩阵元数据未变。

F19.1 / F19.2 的限定无账号工作已完成，账号内流程继续按用户决定后排。后续工作先按 NPatch 专项边界筛选；F13 全屏视觉引擎负载优化尚无 NPatch 差异证据，不作为 QQ 验收后的默认下一步。
