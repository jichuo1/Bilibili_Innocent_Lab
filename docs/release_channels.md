# Release 通道

本项目将构建验证与 Release 发布分开处理：

- 推送到 `main` 只触发构建、单元测试和 Lint，不会上传 Release。
- 在 GitHub Actions 中手动运行 `Build and publish Alpha`，并将 `publish_alpha` 明确设为 `true`，才具备发布资格。
- `release_tag` 必须符合 `vMAJOR.MINOR.PATCH-alpha.NUMBER`，且版本前缀必须是当前稳定
  基础版本的下一个补丁版本。例如 `project.app.versionName` 为 `1.0.6` 时，只接受
  `v1.0.7-alpha.N`；无需为了发布 Alpha 先把项目稳定基础版本改成 `1.0.7`。
- `project.app.versionCode` 仍由源码控制，并按 Android 安装升级计划递增；工作流不会通过
  Alpha 标签暗中改写它。
- 发布任务还必须等待 `verify` 任务通过，随后使用 `alpha-release` 环境发布 Pre-release。
- CI 会在 GitHub Runner 上使用可用的 Android 35 SDK 进行验证构建；本地开发仍可使用项目默认的 compileSdk 配置。
- 工作流只接受远端 `main` 的最新提交：所选 ref、事件 SHA、实际 checkout SHA 与
  `origin/main` 任一不一致都会立即失败，避免从旧分支、旧标签或旧提交生成 Release。
- Alpha 标签会覆盖 APK 内部 `versionName`（例如稳定基础版为 `1.0.6` 时写入
  `1.0.7-alpha.2`）；构建完成后再用
  Android build-tools 反查 APK 的真实 `versionName/versionCode`，不一致时禁止发布。
- APK 文件名包含完整 Alpha 标签和源码短 SHA。Release 同时附带 `BUILD_INFO.txt` 与
  `SHA256SUMS.txt`，可直接追溯源码提交、APK 内部版本与文件哈希。

建议在 GitHub 仓库的 Settings → Environments 中创建 `alpha-release` 环境，设置
Required reviewers，并将 Deployment branches 限制为 `main`。这样即使有人从旧 ref
运行仍然保留一层仓库端阻断。

手动发布前必须先确认目标源码已经 commit 并 push 到远端 `main`。GitHub Runner 无法读取
开发电脑上未提交或未推送的工作区文件；仅在“Use workflow from”中切换选项不能上传本地改动。

稳定版使用不带 `-alpha.N` 后缀的独立 Tag。Alpha 始终预览下一个补丁版本，例如当前
稳定版是 `v1.0.6` 时，依次发布 `v1.0.7-alpha.1`、`v1.0.7-alpha.2`；正式发布
`v1.0.7` 后，下一轮 Alpha 前缀切换为 `v1.0.8-alpha.N`。

## LSPosed 仓库自动同步

主仓库 `jichuo1/Bilibili_Innocent_Lab` 是唯一构建与发布源；
`Xposed-Modules-Repo/com.Bilibili_Innocent_Lab.xposedmodule` 只保存 LSPosed 元数据和
Release，不镜像源码分支。`.github/workflows/sync-lsposed-release.yml` 负责复制 Release
标题、正文和全部附件，并保留 Stable/Pre-release 属性。

- 人工发布 Stable 后，GitHub 的 `release.published` 事件自动启动同步。
- Alpha 由 `GITHUB_TOKEN` 创建 Release，不会可靠触发后续普通工作流，因此
  `alpha-release.yml` 的发布任务成功后直接调用同一个可复用同步工作流。
- Actions 中可手动运行 `Sync Release to LSPosed repository`，输入已存在的主仓库标签进行
  补偿同步；启用 `dry_run` 时只验证主仓库 Release，不写目标仓库。
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
