# Bilibili_Innocent_Lab {{VERSION}}

> **发布通道：Stable 稳定版**<br>
> 发布日期：{{RELEASE_DATE}}<br>
> 对应提交：[`{{COMMIT_SHA}}`](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/{{COMMIT_SHA}})

## 版本概述

{{RELEASE_SUMMARY}}

## 更新内容

{{CHANGELOG}}

## 兼容性

- Android：8.1 及以上（minSdk 27）
- targetSdk：35
- LSPosed：不同版本与分支可能存在兼容性差异，请在反馈时注明具体版本
- 哔哩哔哩客户端：宿主更新可能改变 Hook 目标，请在反馈时注明具体版本
- 自动发布流程完成云端构建验证，不替代多设备、多 ROM 和多宿主版本真机验证

## 安装与升级

1. 下载本 Release 中的 APK，并核对 SHA-256。
2. 直接覆盖安装模块；首次安装则在 LSPosed 中启用模块并勾选哔哩哔哩作用域。
3. 按本版本需要重启模块界面、哔哩哔哩或设备。
4. 本版本没有自动执行额外数据迁移；如遇异常，请先停止目标应用后重新打开。

> [!TIP]
> 若您已确定您下载的模块版本应高于已安装版本却无法安装，请尝试使用核心破解功能。

## 已知问题

- 暂无已确认问题；发布后发现的问题将通过 Release 更新或 GitHub Issues 补充。

## 验证结果

- [x] GitHub Runner 全新检出后 Gradle 构建通过
- [x] 单元测试通过
- [x] Android Lint 通过
- [x] APK 包名、versionName 与 versionCode 反查通过
- [x] 发布附件生成及本地 SHA-256 校验通过
- [ ] 多设备、多 ROM 与多宿主版本真机验证仍需结合实际环境确认

## 下载与校验

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `{{APK_FILENAME}}` | 模块安装包 | `{{APK_SHA256}}` |
| `SHA256SUMS.txt` | 文件校验清单 | 以附件内容为准 |
| `BUILD_INFO.txt` | 构建源码、APK 内部版本与哈希溯源 | 以附件内容为准 |

## 问题反馈

请前往 [GitHub Issues](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues) 反馈，并附上：

- Android、ROM、LSPosed 与哔哩哔哩版本
- 问题复现步骤和预期行为
- 必要的截图、录屏或已脱敏日志

## 使用提醒

不同 Android 版本、厂商 ROM、LSPosed 分支及宿主版本可能产生差异。升级前请做好必要备份，并在合法合规、遵守相关服务条款的前提下使用。本项目不代表任何官方立场，也不隶属于相关平台。
