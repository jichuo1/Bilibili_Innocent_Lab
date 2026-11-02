# F19：重签名微信/QQ 与 B 站分享链路调查

2026-10-23 调查，2026-10-24 更新实施状态。微信的 B 站端兼容已实现，限定环境的 Manager/embed 无账号交接证据见[实施记录](npatch-stage7-wechat-share.md)。QQ 9.2.20 的原签名/NPatch 重签名对照及双模式交接也已完成，未复现安装误报，无需新增 Hook，见 [QQ 记录](npatch-stage7-qq-share.md)。用户明确将账号内流程保留待验。以下保留原代码调查，当前状态以两份阶段记录为准。

## 场景与结论

用户明确的场景是：**微信或 QQ 自身经 NPatch/LSPatch 修补并重新签名，B 站随后提示未安装客户端**。这与“B 站被重签名后，接收方拒绝 B 站身份”是两种不同问题。不能用后一种问题的限制否定前一种的宿主端修复。

| 渠道 | B 站 8.90.2 代码结论 | 仅 Hook B 站的可行性 |
|---|---|---|
| 微信/朋友圈 | B 站内置 OpenSDK 检查微信自身证书；重新签名可使安装检测返回 false，宿主随后显示未安装提示 | F19.1 已仅 Hook B 站分享 SDK 实例，覆盖安装、注册及发送检查；双模式原生交接已观测，投递未验 |
| QQ/QQ 空间 | 所查宿主处理器及原生分享 SDK 路径主要检查包存在、版本、参数和 Intent 可解析性；未发现同类的 QQ 客户端证书门槛 | F19.2 的 QQ 9.2.20 对照未复现失败，双模式重签名接收方原生交接已观测，无需新增 Hook；其它版本若复现提示仍按实际分支定位 |

调查输入为 B 站 8.90.2 / 8902100 原版单 APK，SHA-256 `804ab5d719da42f257aac6237dda408151b93ecff71a0da99c5f32c71161a2e5`，与阶段 6 修补记录的 `originalSha256` 一致。下述混淆类名仅属于此版本，不能硬编码外推到其它宿主版本。

## 微信：“未安装”提示的准确位置

传统分享路径：`ShareHelperV2` → 委托 `com.bilibili.lib.sharewrapper.a` → 第三方拦截器 `m23.e` → `ShareService` 路由 `action://share/shareto` → 分享代理/`WxAssistActivity` → `kz3.b`（朋友）或 `kz3.c`（朋友圈），共用 `kz3.a`。

其中 ShareService 的接口路由和后半段代理 Activity 已分别核对，具体路由实现类未在本次进一步展开；实机系统日志另确认本次普通视频分享经过 `BiliShareDelegateActivity/WxAssistActivity`。不把所有宿主分享入口都假设为同一条路径。

1. `cz3.b.c(...)` 在构造/发送分享内容前调用 `o()` 检查配置，再调用 `t()` 初始化 SDK。
2. `kz3.a.t()` 创建 `WXAPIFactory.createWXAPI(context, appId, true)`，第三个参数明确开启微信客户端签名校验。先调用 `isWXAppInstalled()` 决定是否注册，再次调用同一检测；失败则显示 `i3.z` 文案并抛出 `ShareException(string, -234)`。
3. `f8.i3.z = 2131848254 = 0x7f116c3e`，对应 `string/share_global_string_31`，简体文案是 **`(>_>)请先安装微信~`**。它发生在 B 站进程、发送请求之前。用户记忆中的“找不到微信”不是本版本的逐字文案，不能据此推断其它版本资源相同。
4. `BaseWXApiImplV10.isWXAppInstalled()` 查询 `PackageManager.getPackageInfo("com.tencent.mm", 64)`（本版本有 PrivacyGhost 包装），再把得到的 `signatures` 交给 `WXApiImplComm.validateAppSignature(..., checkSignature)`。包不可见/不存在、异常和证书不匹配均可能表现为 false。
5. `validateAppSignature` 把证书编码与 SDK 内置 `WX_APP_SIGNATURE` 比较。其内置证书 SHA-256 为 `0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c`。若系统返回的是重签名后的不同证书且检查开启，即使包已安装，也会失败。微信进程内自己的签名伪装不会改变 B 站进程独立查询的结果。

这里不是微信在检查 B 站签名，而是 **B 站在检查微信签名**。

### 为什么只把 isWXAppInstalled 改成 true 不够

同一个 SDK 实例的 `registerApp()` 和 `sendReq()` 还会调用 `validateAppSignatureForPackage(context, "com.tencent.mm", checkSignature)`。只改安装检测仍可能导致注册失败，或者 `sendReq` 返回 false。后者在 `kz3.a` 的发送 Runnable 中产生另一种失败：`sendReq failed`，错误码 `-238`。

成功通过宿主检查后，`sendReq` 构造请求并调用 `MMessageActV2.send`，目标为 `com.tencent.mm.plugin.base.stub.WXEntryActivity`；返回 true 只证明 SDK 发起了交接，不代表微信接受内容或消息已经送达。

### 同 APK 的另一套分享包装

`kntr.common.share.common.handler.wx.k.j()` 同样以 `createWXAPI(context, appId, true)` 创建实例，再通过 `isWXAppInstalled()` 初始化检查，失败记录“微信未安装”。因此未来修复需要审计两套包装及分享回调实例，不能只覆盖旧混淆类 `kz3.a`。本次未证明该备用包装参与上述实机样本。

## QQ：不能套用微信结论

传统路径由 `QQAssistActivity` 创建 `hz3.b`（QQ）或 `hz3.c`（QQ 空间），共用 `hz3.a`。

- `hz3.a` 的分享 Runnable 调用 `M(context)`：检查 `com.tencent.mobileqq` 的 `versionName >= 4.1`，否则尝试 `com.tencent.tim`。这些调用使用 flags 0，不读取客户端证书。
- 检测失败，或其内部原生调用抛出 `ActivityNotFoundException`，会进入 `N(context)`。`f8.i3.U = 2131848285 = 0x7f116c5d`，资源 `share_global_string_6` 文案为 **`尚未安装QQ/TIM或当前QQ/TIM版本不支持分享~`**，失败回报为 `-234`。所以这条提示也不一定说明真没安装。
- `hz3.b.O(...)` 调用 `Tencent.shareToQQ(...)`，转到 `QQShare.shareToQQ`；支持性判断包含版本、参数和 `queryIntentActivities`。最终构造 `mqqapi://share/to_fri?...`，经宿主 `AssistActivity` 交给 QQ 的 `JumpActivity`。
- `Tencent.isQQInstalled()` 本身也只查询 QQ 包是否存在；`BaseApi.a(Intent)` 经 `SystemUtils` 查询可处理的 Activity，不验证 QQ 证书。
- 同 APK 的 `kntr.common.share.common.handler.qq.g` 也先检查 QQ/TIM 包，然后调用同一 Tencent 分享 API；未见微信式的固定客户端证书比较。

SDK 中确实存在签名相关方法，但不能按名字移植结论：`SystemUtils.getSignValidString` 对调用方自身包签名生成数据；`SocialApiIml` 的独立 `com.qzone` 社交接口选择有证书检查；工具中另有 QQ 浏览器证书判断。它们不能作为本次 QQ 原生分享必然拒绝重签名 QQ 的证据。QQ 空间分享与独立 `com.qzone` 社交应用路径也应分开。

本次未穷尽 QQ 内部、其它版本或所有消息类型；不声称它们永远不存在额外检查。

## B 站端确定实施方案与边界

微信按以下方案实施：在用户显式选择兼容重签名微信时，仅对 **B 站分享用途的 SDK 实例**使用 SDK 已有的 `checkSignature=false` 参数。该参数在 `validateAppSignature/validateAppSignatureForPackage` 中关闭客户端证书比较，可同时解决安装、注册和发送三处同类检查。代码可行性已经确认，不再以“只能降级”或是否能在 B 站端修复作为探索前提；这道检查不要求追加微信端 Hook，交付目标是保留原生分享。

这不是把 `isWXAppInstalled`、`sendReq` 或所有签名验证强制返回成功。`isWXAppInstalled` 仍应真正查询微信包；保留目标包名、分享参数、消息 checksum 和回调身份等原有检查。不能全局改 PackageManager 的签名结果，不能影响微信登录/支付等无关 SDK 实例。分享实例识别、两套包装、已有实例及回调创建路径需在实现前完成适配设计，失败时保留原行为。

触发条件应围绕用户接受重签名接收方这一需求，不能只按“B 站自己是否由 NPatch 加载”决定。B 站是否改签、微信是否改签是两个独立维度；解除宿主的客户端证书门槛也不保证解决接收方自身的 loader、运行或对 B 站身份的其它检查。

QQ 已于 2026-10-24 另做专项对照，结果见 [F19.2](npatch-stage7-qq-share.md)。后续若其它版本/产物确实失败，仍核对包名、可见性、版本判断、Intent 解析/启动及 SDK 错误。只改签名而包名/版本/组件均未改变，不足以从本次所查代码推出必然失败。

## 最初调查的操作记录与后续验证计划

本节保留 2026-10-23 实施前基线，以下“未再操作”“仍需”均为当时状态。后续按用户授权构造的重签名样本、真实操作和剩余边界以微信 / QQ 阶段记录为准。

用户澄清具体场景前，曾在既有有线机 `13a7a291` 做当前环境交接观察；未操作无线机 `192.168.1.78:5555`。确认场景为历史现象、先按代码调查后，未再操作手机界面、修补或替换客户端。

该机微信 8.0.64、QQ 9.2.20 的已安装 APK 未见 NPatch/LSPatch manifest 标记，appComponentFactory 均为 AndroidX；提取的微信证书与 B 站 SDK 内置期望一致，QQ 证书 SHA-256 为 `ea6e97ad6c34f7039a9c6daba732c97d0e098e83ede2b4d52c76eb0184ac7a38`。它们**不构成重签名接收方样本**。

该基线中微信被拉起至 `WXEntryActivity` 后进入登录页；QQ 日志显示 `shareToQQ -- type: 7`、`support share` 并拉起 `JumpActivity`，随后进入登录/升级提示。仅证明当前宿主能交接到这些客户端；没有选择联系人、登录账号、升级、修补客户端或发送消息，不作为接收预览、重签名修复或最终投递通过证据。手机界面操作已停止，最后观察为 QQ 升级提示页。

仍需的后续验证：

1. 原签名与明确重签名的微信分别验证，复现宿主 `-234`，实现后检查安装、注册、发送和适用预览/回调路径；未安装微信仍应正确失败。
2. 朋友/朋友圈及实际使用消息类型分别检查，核对两套宿主包装与取消/返回；登录、支付等无关入口保持原行为。
3. QQ 原签名/重签名对照；若没有失败，不为了“微信有修复”而给 QQ 加绕过。确有错误则按实际分支定位。
4. B 站原签名/修补与接收方原签名/修补分列，避免把两个签名方向再次混为一谈。真实发送只有另获明确授权才执行。

## 可复核本地证据

忽略目录 `captures/f19-investigation/` 保存原版 APK 类定义索引、按 DEX 提取/反编译工具、源码、资源表及基线截图/日志；不把客户端 APK 或日志加入版本库。

| 文件或类 | 关键位置 |
|---|---|
| `source/kz3.a.java` | 276 行初始化；282 行 `checkSignature=true`；293–296 行未安装提示/异常；77 行发送 |
| `source/f8.i3.java`、`resources.txt` | `z/U` 的整数 ID 与实际本地化文案 |
| `source/com.tencent.mm.opensdk.openapi.BaseWXApiImplV10.java` | 840 行安装检测；882 行发送；1131 行注册证书检查 |
| `WXApiImplComm-fallback.java` | 实际分支：证书匹配返回 true、不匹配返回 false；检查关闭返回 true |
| `source/kntr.common.share.common.handler.wx.k.java` | 348–353 行另一套包装的初始化 |
| `source/hz3.a.java` | 210 行包/版本检查；225 行统一未安装提示；64 行发送 Runnable |
| `source/com.tencent.connect.share.QQShare.java` | 197 行入口；66 行原生 URI；166 行调用方包名；后续 resolver/AssistActivity |
| `source/com.tencent.open.utils.i.java` | 190 行安装查询；204 行 Activity 解析；79/155 行不同用途签名方法 |
| `share-window.log`、`qq-window.log`、`wx-attempt.xml`、`qq-activity.txt` | 澄清前基线的目标 Activity、登录边界及 QQ SDK 结果，不包含重签名失败复现 |

注意 JADX 的结构化 Java 在 `validateAppSignature` 成功分支出现了错误重建（漏掉匹配后的成功返回）。本次已另用 `--decompilation-mode fallback` 核对寄存器/跳转，成功匹配跳到 `return true`；不能把失真的 Java 输出当作 SDK 恒失败。备用 QQ 包检测的嵌套 catch 也不据结构化排版臆造运行行为。

提取的 SDK DEX `classes26.dex` SHA-256 为 `682ea220cc14afeb173f1c113b7552cf9c38cd31cd601ad83e99642d1778e914`。基线微信 APK SHA-256 为 `39fd81770ba854877e0433f3386279956da3b99e66c6e372767e91f1cbd27cb2`，QQ APK 为 `7fba840b238555c970dce7f47bed8cb5f7b3a64818c99f3ee951fc6a550be743`。
