# Release 通道

本项目将构建验证与 Release 发布分开处理：

- 推送到 `main` 只触发 Debug 构建、单元测试和 Lint，不接触正式签名密钥，也不会上传
  Release 或生成候选发布资产。
- Stable 只通过 GitHub Actions 手动运行 `Build and publish Stable` 发布；必须将
  `publish_stable` 明确设为 `true`，填写与源码版本完全一致的 `vMAJOR.MINOR.PATCH` 标签，
  并提供 2～4 句版本概述。
- Stable 的 `project.app.versionName` 与 `project.app.versionCode` 必须在发布前写入源码、
  commit 并 push 到 `main`；工作流不会临时覆盖正式版本号。
- 在 GitHub Actions 中手动运行 `Build and publish Alpha`，并将 `publish_alpha` 明确设为 `true`，才具备发布资格。
- `release_tag` 必须符合 `vMAJOR.MINOR.PATCH-alpha.NUMBER`，且版本前缀必须是当前稳定
  基础版本的下一个补丁版本。例如 `project.app.versionName` 为 `1.0.6` 时，只接受
  `v1.0.7-alpha.N`；无需为了发布 Alpha 先把项目稳定基础版本改成 `1.0.7`。
- `project.app.versionCode` 仍由源码控制，并按 Android 安装升级计划递增；工作流不会通过
  Alpha 标签暗中改写它。
- 发布任务还必须等待 `verify` 任务通过，随后使用 `alpha-release` 环境恢复固定签名、构建
  Release APK 并发布 Pre-release。
- CI 会在 GitHub Runner 上安装 `platforms;android-37.0` 与 `build-tools;36.0.0`，用与源码
  一致的 compileSdk 37 进行验证构建；自 2026-09-03 起本地与 CI 的 SDK 已对齐，不再存在
  “本地能过、CI 过不了”的分叉。
- 工作流只接受远端 `main` 的最新提交：所选 ref、事件 SHA、实际 checkout SHA 与
  `origin/main` 任一不一致都会立即失败，避免从旧分支、旧标签或旧提交生成 Release。
- Alpha 标签会覆盖 APK 内部 `versionName`（例如稳定基础版为 `1.0.6` 时写入
  `1.0.7-alpha.2`）；构建完成后再用
  Android build-tools 反查 APK 的真实 `versionName/versionCode`，不一致时禁止发布。
- APK 文件名包含完整 Alpha 标签和源码短 SHA。Release 同时附带 `BUILD_INFO.txt` 与
  `SHA256SUMS.txt`，可直接追溯源码提交、APK 内部版本与文件哈希。
- Stable 工作流同样生成带完整标签和源码短 SHA 的 APK、`BUILD_INFO.txt` 与
  `SHA256SUMS.txt`；创建 Release 后会重新下载全部附件、核对 Git Tag 指向和逐文件哈希，
  验证通过后结束主仓库发布，不再自动进入 LSPosed 同步。

建议在 GitHub 仓库的 Settings → Environments 中创建 `alpha-release` 环境，设置
Required reviewers，并将 Deployment branches 限制为 `main`。这样即使有人从旧 ref
运行仍然保留一层仓库端阻断。

Stable 使用独立的 `stable-release` 环境，并同样设置 Required reviewers 与仅允许 `main`
部署。Stable 工作流的并发任务不会互相取消；重复运行会排队，并在真正发布前拒绝覆盖任何
已经存在的 Git Tag 或 GitHub Release。

## 固定 APK 发布签名

Alpha 与 Stable 必须使用同一个长期 PKCS12 密钥和同一个证书指纹。普通 `main` 推送只执行
`assembleDebug`；只有手动发布并通过对应 GitHub Environment 审批后，发布任务才读取环境
Secrets、执行 `assembleRelease`。Gradle 的 Release 打包采用失败封闭策略：密钥路径、密钥库
密码、别名和私钥密码任一缺失时立即失败，不会回退为 Debug 签名或 unsigned APK。

在 Windows 上首次配置或需要将既有密钥重新写入 GitHub 时，从仓库根运行：

```powershell
.\.github\release-signing\setup-release-signing.ps1
```

脚本默认把密钥库生成到仓库外的
`Documents\AndroidSigning\Bilibili_Innocent_Lab\innocent-lab-release.p12`，也允许通过
`-KeyStorePath` 复用指定的既有 PKCS12。脚本以隐藏输入方式要求两次输入同一密码，不把密码
写入仓库、文件或命令行；随后将同一组值写入 `alpha-release` 与 `stable-release` 环境：

```text
ANDROID_SIGNING_KEY_BASE64
ANDROID_SIGNING_STORE_PASSWORD
ANDROID_SIGNING_KEY_ALIAS
ANDROID_SIGNING_KEY_PASSWORD
ANDROID_SIGNING_CERT_SHA256
```

运行脚本前必须确保 `gh auth status` 已登录主仓库管理员账号，两个 Environment 已创建且仅允许
`main`。若环境 Secret 写入中断，可用同一路径、同一密码重新运行；脚本只复用既有密钥库，绝不
覆盖或自动轮换。密码不会被脚本持久化，发布者必须立即把密码存入可靠的密码管理器，并把密钥库
制作至少两份加密离线备份。丢失私钥或密码意味着无法继续为现有安装提供覆盖升级。

发布 APK 必须同时满足：单一签名者；证书 SHA-256 与环境中的固定值一致；
`application-debuggable` 不存在；包名、`versionName`、`versionCode` 与源码身份一致；
`apksigner verify --verbose --print-certs` 成功。`BUILD_INFO.txt` 固定记录
`apk_build_type=release`、`apk_debuggable=false` 和
`apk_signer_certificate_sha256=<64 位十六进制>`，便于主仓库与 LSPosed 仓库对照。

从历史临时 Debug 证书切换到首个固定签名版本时，不同证书的旧安装无法直接覆盖，通常会返回
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。迁移版本发布前必须明确提示用户先导出模块设置、卸载
旧模块、安装固定签名版本、重新启用模块并导入设置。从该版本开始，Alpha、Stable 与 LSPosed
镜像资产都必须保持同一证书；APK 文件 SHA-256 随内容变化是正常现象，长期不变的是签名证书
SHA-256。

手动发布前必须先确认目标源码已经 commit 并 push 到远端 `main`。GitHub Runner 无法读取
开发电脑上未提交或未推送的工作区文件；仅在“Use workflow from”中切换选项不能上传本地改动。

稳定版使用不带 `-alpha.N` 后缀的独立 Tag。Alpha 始终预览下一个补丁版本，例如当前
稳定版是 `v1.0.6` 时，依次发布 `v1.0.7-alpha.1`、`v1.0.7-alpha.2`；正式发布
`v1.0.7` 后，下一轮 Alpha 前缀切换为 `v1.0.8-alpha.N`。

## LSPosed 仓库独立同步

主仓库 `jichuo1/Bilibili_Innocent_Lab` 是唯一构建与发布源；
`Xposed-Modules-Repo/com.Bilibili_Innocent_Lab.xposedmodule` 只保存 LSPosed 元数据和
Release，不镜像源码分支。`.github/workflows/sync-lsposed-release.yml` 负责复制 Release
标题、正文和全部附件，并保留 Stable/Pre-release 属性。

- `alpha-release.yml` 与 `stable-release.yml` 只负责主仓库构建、校验和 Release 发布，不再
  调用同步工作流，也不再传递 `LSPOSED_RELEASE_TOKEN`。目标仓库权限问题不会再把主仓库
  发布流程标记为失败。
- 需要同步时，在 Actions 中单独运行 `Sync Release to LSPosed repository`，输入已存在的
  主仓库标签；启用 `dry_run` 时只验证主仓库 Release，不写目标仓库。
- 人工发布 Release 仍可能通过 `release.published` 事件启动独立同步，但 Actions 使用
  `GITHUB_TOKEN` 创建的 Stable 和 Alpha 不依赖该事件，也不会从发布流程直接调用同步。
- 同步凭据只允许从仓库 Secret `LSPOSED_RELEASE_TOKEN` 读取，不得写入工作流、源码、日志
  或 Release 附件。令牌至少需要目标仓库 `Contents: write`，不需要管理其他仓库功能。

同步前必须同时满足：Release 标题与标签完全一致；标签符合 `vX.Y.Z` 或
`vX.Y.Z-alpha.N`；Stable 未标为 Pre-release、Alpha 已标为 Pre-release；附件中恰好有一个
APK 和一个 `SHA256SUMS.txt`；APK 包名、内部 `versionName/versionCode`、GitHub 资产摘要、
校验清单及可选 `BUILD_INFO.txt` 相互一致。

LSPosed 目标标签按 Xposed Modules Repo 规范生成：

```text
<APK versionCode>-<去掉 v 前缀的 APK versionName>
```

例如 `v1.0.7`（versionCode 8）同步为 `8-1.0.7`，`v1.0.8-alpha.2`
同步为 `8-1.0.8-alpha.2`。不得从历史 Release 猜测 versionCode。

同步是幂等的：目标标签不存在时创建并回读校验；已存在时不覆盖，而是下载双方全部附件并
逐文件计算 SHA-256，同时比较正文、标题和通道属性。完全一致则成功结束；任一内容不同则
失败并要求人工检查，不自动删除 Release、不移动标签，也不使用 `--clobber`。
