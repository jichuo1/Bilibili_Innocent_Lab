# Bilibili_Innocent_Lab {{VERSION}}

> **发布通道：Alpha 预发布**<br>
> 发布日期：{{RELEASE_DATE}}<br>
> 对应提交：[`{{COMMIT_SHA}}`](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/{{COMMIT_SHA}})

> [!WARNING]
> 这是面向兼容性、稳定性与性能验证的测试版本，可能存在未发现的问题，不会作为应用内稳定版本推送。普通用户请优先使用最新 Stable Release。

## 版本概述

本版本自动汇集自上一 Alpha 以来的功能、修复、兼容性与文档调整，用于在正式发布前验证构建质量和真实设备表现。请重点结合下方变更记录与测试目标，反馈不同设备、ROM、LSPosed 分支和哔哩哔哩版本下的结果。

## 更新内容

{{CHANGELOG}}

## 本轮测试目标

- 模块能否正常安装、激活并加载。
- 主要功能在目标哔哩哔哩版本上是否正常。
- 长时间使用、快速滚动、弹窗与跨进程场景是否稳定。
- 不同 Android、ROM 与 LSPosed 分支下是否存在兼容性差异。

## 兼容性

- Android：8.1 及以上（minSdk 27）
- targetSdk：35
- LSPosed：需要测试者在反馈中注明版本或分支
- 哔哩哔哩客户端：需要测试者在反馈中注明版本

## 已知问题与风险

- Alpha 版本可能出现功能回归、兼容性问题或性能波动。
- 暂无已确认问题；发布后发现的问题将通过 Release 更新或 GitHub Issues 补充。

## 验证结果

- [x] Gradle 构建通过
- [x] 单元测试通过
- [x] Android Lint 通过
- [ ] 多设备与多版本真机验证待测试者反馈

## 安装与回滚

1. 下载本 Release 中的 Alpha APK，并核对 SHA-256。
2. 覆盖安装后，按测试需要重启模块界面、哔哩哔哩或设备。
3. 如遇严重问题，请覆盖安装最新 Stable Release，并重启相关进程完成回滚。
4. 回滚前请保留必要的已脱敏日志和复现步骤。

## 下载与校验

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `{{APK_FILENAME}}` | Alpha 测试安装包 | `{{APK_SHA256}}` |
| `SHA256SUMS.txt` | 文件校验清单 | 以附件内容为准 |
| `BUILD_INFO.txt` | 构建源码、APK 内部版本与哈希溯源 | 以附件内容为准 |

## 反馈格式

请前往 [GitHub Issues](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues) 反馈，并附上：

- 测试版本：{{VERSION}}
- Android、ROM、LSPosed 与哔哩哔哩版本
- 是否能够稳定复现
- 详细复现步骤和预期行为
- 必要的截图、录屏或已脱敏日志

## 使用提醒

该版本仅用于测试与反馈。请做好必要备份，并在合法合规、遵守相关服务条款的前提下使用。本项目不代表任何官方立场，也不隶属于相关平台。
