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

渲染示例：

```bash
python3 .github/release-templates/render_release_template.py \
  --template .github/release-templates/stable.md \
  --output RELEASE_NOTES.md \
  --version v1.0.6 \
  --commit 0123456789abcdef \
  --release-date 2026-09-01 \
  --sha256 abcdef0123456789
```
