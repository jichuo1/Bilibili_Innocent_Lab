# 凝光稳定版接入

默认使用上游 [LumenCoacervationEngine 1.6.1](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.6.1)，`lumen-engine` 与 `lumen-motion` 在 `gradle/libs.versions.toml` 中共用同一个版本。普通构建直接从 Maven Central 解析官方产物（坐标 `io.github.jichuo1:lumen-engine`、`io.github.jichuo1:lumen-motion`；1.5.6 及更早版本在 JitPack 的 `com.github.jichuo1.LumenCoacervationEngine` 下），不下载源码归档，也不修改依赖缓存。

1.6.0 新增可选、默认关闭的原生 backdrop（`LumenSurfaceBinding.setRenderTargetBackdropEnabled`，仅在 Android 17 QPR2 / API 37.2 上生效），未开启时与 1.5.6 行为一致；1.6.1 是修订版，只修复引擎演示 Sample 与设备测试，九个库模块的源码与 1.6.0 相同。本模块尚未接入原生 backdrop。1.5.6 到 1.6.x 的逐类描述符比对上游尚未做；本次升级是否兼容以本仓库云端 CI 的构建、JVM 测试与契约测试为准。

1.5.6 新增可选的材质浓度（`LumenMaterialOpacity` 系列，按材质分别消退，默认 `p = 1` 与原外观逐位相同），并收录透镜/模糊内核提速、随 AAR 提供的 Baseline Profile 与静止期采集唤醒优化；公开 API 只做新增，契约版本仍为 1，最低 SDK 仍为 27，存储协议与默认选项不变。本模块尚未接入材质浓度。

1.5.5 新增可选通用滑块 `LumenSlider`（五种材质外观与 HDR 高光，引擎长按弹性自动排除），并修复多级手风琴首级标题的长按形变；公开 API 只做新增，契约版本仍为 1，最低 SDK 仍为 27，默认材质质量不变。本模块尚未接入滑块。

1.5.4 修复展开手风琴标题向下形变受限和回弹中再次长按的续接，并优化 Effect 初始化复用、Liquid 静止刷新率归还与弹性位移的窗口范围通知。此前的输入框描边、分页手势、屏幕圆角与皮肤动画修复继续保留；契约版本为 1，最低 SDK 仍为 27，默认材质质量不变。

2026-10-20 升级验证：普通 Maven 构建确认 engine/motion 均解析为 1.5.1；Debug APK、测试 APK、3791 项 JVM 测试（0 失败、1 跳过）和完整 Debug Lint 通过，既有 3 项错误基线未扩展。使用同一正式依赖的独立包名测试版在设备上通过 5 项回归，覆盖双向分页、竖向滚动、下游选项禁用、背景图片导入、标准选中框与关闭动画，以及 Liquid 搜索/连续回弹。测试版沿用原模块已有的条款和皮肤前置状态；设备上的发布签名模块未被覆盖。

跨工程修复验证时，可以显式传入 `-Plumen.sourceDir=/绝对路径/LumenCoacervationEngine`：复合构建同时替换 engine/motion，契约测试从同一份本地源码生成独立的源码 JAR。该选项默认关闭，不把本机路径写进工程配置。引擎修复发布后应统一升级 catalog，普通构建才会取得该修复。

```sh
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintFast \
  -Plumen.sourceDir=/绝对路径/LumenCoacervationEngine --offline --no-daemon
```

## 底栏阴影

`HostBottomBarShadow` 直接调用正式版公开的 `LumenControlShadow.shadowOnly()` 和 `LumenControlShadowOptions`。阴影算法由引擎提供，模块只负责把 Drawable 放到父级 overlay，避开底栏自身的圆角裁剪，并同步位移、透明度、可见性和 detach 生命周期；不包裹 Session 持有的背景，保持玻璃采样与背景所有权。浅色使用环境影和接触带，深色保留原有亮色描边。

此前为了取得尚未发布的软阴影 API，settings 固定下载上游开发提交并编译；没有把阴影渲染器复制进模块业务源码。1.5.0 已正式发布该 API，因此保留宿主接线即可。

`HostBottomBarShadowInstrumentedTest` 检查外侧像素、内部纯净、移动、明暗切换、隐藏与释放。亚克力、云母、玻璃会话和动效继续使用各自公开接口。

## 源码契约测试

部分宿主动效兼容名称委托到引擎类型。`LceMotionSource` 先核对宿主委托，再读取与二进制依赖同版本的官方 `sources.jar`，保留原有语义护栏。`lumenContractSources` 是不传递依赖的独立测试配置，源码包计入测试缓存输入，不参与 APK 编译或打包。普通 Debug 构建不需要解析源码包。

首次构建需要联网取得正式依赖及测试所需的源码包，缓存齐全后支持 `--offline`。使用本项目既有 JDK 17、Gradle wrapper、Android SDK 和本地缓存配置。

## 本次验证（2026-10-18）

Debug APK、测试 APK、3359 项 JVM 测试（0 失败、1 项跳过）与快速 Lint 通过；244 条警告，原有 3 个错误基线未扩展。离线运行依赖报告确认 core/motion 均解析为 1.5.0 的正式 AAR。构建结束已停止并确认无 Gradle/Kotlin 守护进程。

默认手机覆盖安装后，底栏阴影、玻璃视口与连续性、亚克力、云母共 14 项设备回归全部通过。系统后台启动拦截通过检查实际测试栈后显式启动同一 Activity 恢复，未修改系统安全策略。真实宿主冷启动后，日志确认底栏增强已加载且 Hook 安装无失败，截图检查底栏与卡片正常显示。报告、测试输出、截图和加载日志保存在忽略目录 `captures/lumen-stable/`。

## 升级

只在 catalog 中统一更新 Lumen 版本，核对该版本的官方发布说明、公开 API 和依赖要求，再运行模块构建、JVM 测试及适用的 Lint。涉及材质或生命周期变化时，补充设备回归，重点检查顶底栏采样、明暗主题、气泡/面板动效、detach 与重绑定后的资源和背景归属。

1.2.3 已吸收历史的线性淡出、表面及动效扩展，1.4.1 已吸收表面连续性修复，1.4.6 已吸收亚克力保色修复。旧补丁已移除，历史实现和当时的验证记录可从 Git 历史查询；当前构建不应用任何 Lumen 补丁。

## 棱镜顶底栏接入（2026-10-18）

美化页的顶栏、底栏材质菜单各新增“液态玻璃”，直接使用 1.5.0 的公开 `LumenPrismGlass`。旧 Liquid 选项命名为“透明玻璃”，柔光透镜名称保持不变；界面材质名称不附加英文括号。具体采样、几何和生命周期边界见宿主 `hook/hostui/README.md` 的“顶栏与底栏材质配置”。

新增真实窗口测试覆盖 GPU 折射开启、背景刷新、明暗切换、缩岛/位移、裁剪共享恢复、背景所有权及静止零录制，并记录按 VSYNC 去重的总帧、GPU 与绘制耗时。测试另保留官方直接接入对照：直接接入共用一个完整来源/会话，模块的两个栏使用各自的裁剪来源/会话，所以该项用于定位开销，不作为完全同条件的引擎性能排名。测试机的 FrameMetrics deadline 出现累加异常，不采用该数据，也不把总帧延迟简单按 16ms 换算为卡顿率。

真实宿主已临时启用两栏 Prism，冷启动日志确认配置生效，复测推荐流滚动、收岛/展开与个人中心切换。系统本次滚动记录 520 帧、1 帧卡顿（0.19%，采用当前口径，非 legacy 指标）；受页面内容、动态刷新率和设备温度影响，结果只代表本机本次采样。未发现需要修改引擎实现的性能问题。用户原来的柔光选择已恢复。测试日志、性能数据、配置恢复证据和截图保存在忽略目录 `captures/prism/`。

最终门禁：Debug APK、测试 APK、3360 项 JVM 测试（0 失败、1 项跳过）、8 项顶底栏设备回归和快速 Lint 通过（244 条警告，原有 3 个错误基线不变）。后续只清理了测试统计输出与说明性注释，测试 APK 重新编译通过；不重复运行已通过且输入语义未变的检查。构建结束已停止并确认无项目 Gradle/Kotlin 守护进程。
