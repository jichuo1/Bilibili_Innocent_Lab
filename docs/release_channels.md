# Release 通道

本项目将构建验证与 Release 发布分开处理：

- 推送到 `main` 只触发构建、单元测试和 Lint，不会上传 Release。
- 在 GitHub Actions 中手动运行 `Build and publish Alpha`，并将 `publish_alpha` 明确设为 `true`，才具备发布资格。
- `release_tag` 必须符合 `vMAJOR.MINOR.PATCH-alpha.NUMBER`，例如 `v1.0.5-alpha.2`。
- 发布任务还必须等待 `verify` 任务通过，随后使用 `alpha-release` 环境发布 Pre-release。
- CI 会在 GitHub Runner 上使用可用的 Android 35 SDK 进行验证构建；本地开发仍可使用项目默认的 compileSdk 配置。

建议在 GitHub 仓库的 Settings → Environments 中创建 `alpha-release` 环境，并设置 Required reviewers。这样即使有人误触发工作流，上传前仍需人工审批。

稳定版使用不带 `-alpha.N` 后缀的独立 Tag，例如 `v1.0.5`；Alpha 版本使用递增的后缀，例如 `v1.0.5-alpha.1`、`v1.0.5-alpha.2`。
