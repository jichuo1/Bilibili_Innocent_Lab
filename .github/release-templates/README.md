# Release 文案规范

从 `v1.0.5` 之后，GitHub Release 统一使用本目录中的固定模板：

- Stable 正式版：`stable.md`
- Alpha 测试版：`alpha.md`

## 固定规则

1. 标题只使用版本号：Stable 为 `vX.Y.Z`，Alpha 为 `vX.Y.Z-alpha.N`。
2. 章节顺序固定，不得删除章节；没有内容时明确写“无”。
3. “版本概述”只写 2～4 句，说明本版本解决了什么问题，不堆砌实现细节。
4. 每项更新以用户可感知的结果开头，必要时再补充技术原因。
5. Stable 必须写明兼容性、已知问题、验证结果、升级方式和 SHA-256。
6. Alpha 必须写明测试目标、潜在风险、回滚方式和反馈所需信息，并明确不会作为稳定更新推送。
7. Alpha Release 上传 APK、`BUILD_INFO.txt` 与 `SHA256SUMS.txt`；APK 文件名必须包含
   完整版本号和源码短 SHA。
8. 发布前删除模板中的填写提示，并确认没有残留 `{{TOKEN}}` 占位符。
9. Alpha 工作流会自动读取上一 Alpha 标签到本次提交之间的 Git 记录，并填入“更新内容”和完整差异链接；提交标题应简洁描述用户可感知的结果。
10. Alpha 标签必须使用 `gradle.properties` 中稳定基础版本的下一个补丁前缀，例如
    `1.0.6` 对应 `v1.0.7-alpha.N`；工作流会把完整 Alpha 版本写入 APK，并在发布前反查
    APK 内部版本与构建源码 SHA。
11. Stable 只通过手动运行 `Build and publish Stable` 发布；标签必须与源码中的稳定
    `versionName` 完全一致，`versionCode` 必须在发布提交中完成递增，不允许由工作流临时覆盖。
12. Stable 工作流会按 Conventional Commits 分类生成变更记录，发布者仍必须填写 2～4 句
    `release_summary`；云端验证结果与待真机确认范围由模板明确区分。
13. 主仓库 Release 发布后由 `sync-lsposed-release.yml` 同步到 LSPosed 元数据仓库；目标
    标签固定为 `<versionCode>-<versionName>`，不得手工猜测 versionCode。
14. 自动同步只接受一个 APK 和 `SHA256SUMS.txt`，会复制全部附件。目标标签已存在时只做
    全量一致性校验，不自动覆盖或删除。

## 推荐流程

1. 复制对应模板到临时文件。
2. 填写各章节；未涉及的分类写“无”。
3. 使用 `render_release_template.py` 替换版本、提交、日期和哈希占位符。
4. 通读一次安装、兼容性、已知问题和校验值。
5. 创建 Release，并确认 Stable 未勾选 Pre-release、Alpha 已勾选 Pre-release。

Alpha 工作流会通过 `generate_alpha_changelog.py` 自动生成变更记录，再传入
`--changelog-file`，无需发布者另外生成整份说明。手动渲染 `alpha.md` 时，需要准备
Markdown 变更记录文件并增加：

```bash
  --changelog-file ALPHA_CHANGELOG.md
```

Stable 工作流使用 `generate_stable_changelog.py` 查找上一正式标签，并按“新增、修复、优化、
构建与维护”生成固定章节。`stable.md` 同时要求 `--release-summary` 和
`--changelog-file`，缺少任一内容或残留占位符都会终止发布。

渲染示例：

```bash
python3 .github/release-templates/render_release_template.py \
  --template .github/release-templates/stable.md \
  --output RELEASE_NOTES.md \
  --version v1.0.6 \
  --commit 0123456789abcdef \
  --release-date 2026-09-01 \
  --sha256 abcdef0123456789 \
  --apk-filename Bilibili_Innocent_Lab-v1.0.6-01234567.apk \
  --release-summary "本版本完成近期功能与兼容性调整的稳定化收口。" \
  --changelog-file STABLE_CHANGELOG.md
```

## LSPosed 同步凭据

主仓库 Actions Secret `LSPOSED_RELEASE_TOKEN` 必须能够在
`Xposed-Modules-Repo/com.Bilibili_Innocent_Lab.xposedmodule` 创建 Release。优先使用只授权
该目标仓库且仅包含 `Contents: write` 的 Fine-grained PAT 或 GitHub App；组织不允许外部
协作者使用细粒度令牌时，才使用带 `public_repo` 的 Classic PAT。令牌值不得写入任何文件。

人工 Stable 发布会通过 `release.published` 自动同步；Actions 使用 `GITHUB_TOKEN` 创建的
Stable 和 Alpha 都不依赖该事件，而是在各自发布作业成功后直接调用同步工作流。同步失败时，
在 Actions 中手动运行 `Sync Release to LSPosed repository` 并输入原主仓库标签即可补偿；
先选 `dry_run` 可只执行身份与附件校验。
