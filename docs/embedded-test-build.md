# 集成模式内测构建（embedded test build）

面向内测群的构建流程。合入 `main` 后，在本仓库的 `Build signed embedded APK` 工作流中手动构建；只上传 Actions artifact，不创建 GitHub Release，也不进入 Canary / Telegram 发布流程。`next` 尚未合入 `main` 时不要运行本工作流。

> 给后续维护者 / AI 会话
> - 工作流只允许从本仓库最新 `main` 手动触发；`next` 的 push 不会触发。
> - APK artifact 只包含一个可安装的 APK；R8 符号和检查报告作为独立 artifact 保存，无需分发给内测用户。
> - 旧版 [Bilibili_Innocent_Builds](https://github.com/LiJiaHua1024/Bilibili_Innocent_Builds) 的内测构建及其 Fork 签名是独立历史流程，不代表本仓库当前产物的签名。

## 产物与取回

- APK artifact 名：`BIL-embedded-v<versionName>-<sha8>`，保留 30 天；zip 解压后只有一个 APK。
- 对应的 `embedded-release-symbols-<sha>` 单独保存 embedded 变体的 R8 mapping 30 天；测试和完整 Lint 报告分别按现有门禁保留 14 天。
- 构建元数据（源码 commit、包名、版本、证书与 APK 的 SHA-256）在运行的 job summary 中，不在 artifact 里。

```powershell
# 1. 合入 main 后手动触发
gh workflow run embedded-build.yml --repo jichuo1/Bilibili_Innocent_Lab --ref main

# 2. 查运行
gh run list --repo jichuo1/Bilibili_Innocent_Lab --workflow embedded-build.yml --limit 3

# 3. 取回（gh 自动解压，目录内直接是 APK）
gh run download <run-id> --repo jichuo1/Bilibili_Innocent_Lab `
  -n BIL-embedded-v<versionName>-<sha8> -D .\embedded
```

## 与正式 Release 的差异

| 项 | 正式 Release | embedded 内测 |
|---|---|---|
| buildType | `release` | `embedded`（`initWith(release)`） |
| R8 / shrink / debuggable | 开 / 开 / 否 | 完全相同 |
| 签名 | 固定发布证书 | 复用 `alpha-release` 环境的同一张固定证书 |
| 包名 | `com.Bilibili_Innocent_Lab.xposedmodule` | `com.Bilibili_Innocent_Lab.xposedmodule.embedded` |
| `EmbeddedModePolicy.SUPPORTED` | `false` | `true` |
| versionName / versionCode | 源码 `gradle.properties` 指定的版本 | 相同 |

包名后缀同时作用于 Manifest 的 `${applicationId}`（权限、Provider authority）与 `assets/npatch/modules/<applicationId>.apk`（NPatch 内嵌条目）。跨 App 的 action、authority、权限常量统一由 `BuildConfig.APPLICATION_ID` 派生；Manifest 声明的类与 activity-alias 仍按 `BuildConfig.MODULE_CLASS_PACKAGE`（namespace）构造，二者不能混用。

## 构建流程与安全边界

工作流沿用本仓库的 Canary 门禁和签名方式：

1. **验证任务**：锁定最新 `main` 的提交，运行完整单元测试、源码契约、Debug 构建、Release R8 和并行完整 Lint；临时密钥只用于验证时的构建缓存配置。
2. **签名构建任务**：两项门禁均通过后，再次确认同一提交仍为最新 `main`，在 `alpha-release` 环境中直接运行 `:app:assembleEmbedded`。检查生成的 Flag、证书指纹、签名者、非 debuggable、包名和版本；上传 APK 后再读回并核验哈希。

签名构建任务会执行已验证的 `main` 源码，并临时使用长期签名密钥；这是与 Canary 相同的信任边界。运行结束后删除 runner 中的临时密钥文件。

## 本地构建

本地验证同样可以构建 `embedded` 变体，但需要一套完整签名配置（可以用任意 PKCS12，仅本地验证）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME = 'G:/AndroidSdk'
$env:ANDROID_SDK_ROOT = 'G:/AndroidSdk'
$env:GRADLE_USER_HOME = 'G:/src/Bilibili_Innocent_Lab/.gradle-user-home'
$env:ANDROID_USER_HOME = 'G:/src/Bilibili_Innocent_Lab/.android-user-home'
$env:INNOCENT_LAB_SIGNING_STORE_FILE = 'C:/path/to/your.p12'
$env:INNOCENT_LAB_SIGNING_STORE_PASSWORD = '<store password>'
$env:INNOCENT_LAB_SIGNING_KEY_ALIAS = '<alias>'
$env:INNOCENT_LAB_SIGNING_KEY_PASSWORD = '<key password>'
Set-Location 'G:/src/Bilibili_Innocent_Lab/Bilibili_Innocent_Lab'
./gradlew.bat :app:assembleEmbedded --offline --no-daemon
```

产物在 `app/build/outputs/apk/embedded/app-embedded.apk`。缺少完整签名时 Gradle 会直接拒绝打包，不会产出未签名文件。

## 使用与验证注意

1. `.embedded` 与正式模块是**两个 App**：配置、条款、免 Root 授权、心跳历史各自独立；作用域要选 `.embedded` 这个包。
2. 安装到设置 App 的模块与嵌入宿主的模块必须是**同一份已签名 APK**。更新时从原版宿主重新 patch；宿主须继续使用其原来的宿主签名才能覆盖升级。宿主和模块各自维持签名升级链，详见[修补工具说明](../scripts/npatch/README.md)。
3. 包名不同，任何版本的 `.embedded` 都无法覆盖正式模块；反之亦然。
4. 诊断页的 `NPatchEmbeddedBuild` 会比较宿主内 `assets/npatch/modules/<applicationId>.apk` 与已安装模块 APK 的字节哈希。只更新设置 App 或只重装宿主都会显示 `DIFFERENT`，需要重新 patch 后再冷启动。
5. 正式发布仍不支持集成模式：`debug` / `release` 的 `EmbeddedModePolicy.SUPPORTED` 恒为 `false`，本流程不改变 README 的对外支持声明。
6. 本地 patcher `scripts/npatch/NewPatchedApk.ps1` 从模块 APK 的 `aapt2` 输出读取包名，可直接接受 `.embedded` 产物，无需手改脚本。
7. `MeasureStartup.ps1` 仍按正式模块包名与测试包名固定查询，不适用于 `.embedded` 内测包。
8. 如果旧 BIB 内测模块与本仓库产物的签名不同，二者无法覆盖安装；卸载旧模块会删除其应用数据，应先备份需要保留的设置。
