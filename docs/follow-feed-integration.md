# 关注页凝光接入

## 依赖与边界

catalog 保留 JitPack 的 `com.github.jichuo1.LumenCoacervationEngine:lumen-engine` 和 `lumen-motion`，基线统一固定为上游正式标签 `1.2.6`。Android settings 的复合构建同时替换两个模块，实际 APK 不混入旧 JitPack 类。线性淡出等历史公共扩展已在 1.2.3 被上游吸收；当前仅应用纯色罩更新保留采样的表面连续性补丁。源码下载校验、工具链对齐、补丁与升级方法见 [接入说明](../Bilibili_Innocent_Lab/gradle/lumen/README.md)。此前发布依赖 1.0.0 不包含局部表面与分段组件；没有使用浮动版本、修改包名或发布依赖。

这是对宿主原生 RecyclerView/TabLayout 的 LSPosed 局部适配，正文及部分辅助内容仍由宿主 Compose 实现。沿用 `HOST_VIDEO_CARDS` 开关与 `HostChromeTheme` 主题源；不接管 Activity、Window、系统栏配置和播放器。状态栏背后的渐隐表面只属于关注页。现有 `HostBottomBarFxController` 及其历史内嵌材质会话保持不变，没有迁移到底部以外的新会话。

## 统一配置

| 区域 | 公开引擎能力 | 宿主职责 |
| --- | --- | --- |
| 页面底色、主次前景 | `LumenPalette.modern` | `FollowFeedStyle.palette` 从现有宿主主题源映射，`FollowFeedViewEdits` 同步可识别的原生文字并可逆恢复；彩色会员名、认证、点赞强调及富文本语义保留 |
| 完整动态卡片 | `LumenSurfacePresets.staticPanel`、`LumenSurfaceSession.bind` | 明确覆盖角色为 `CARD`；根据顶层动态身份与模块首尾边界合并一个背景表面；管理布局与回收 |
| 全部/视频底座、选中框 | `LumenSlidingSelection`、局部表面 `TOP_BAR` / `SELECTED_ITEM` | 显式横向；绑定公开 indicator View；转发原 TabView 点击，读取实际选中状态；初始化与恢复只同步视觉 |
| 紧凑顶部与发布入口 | 同一 palette、现有分段组件 | `FollowFeedHeader` 可逆收起已识别的外层关注标题；原发布 View 移到右侧，保留监听；只避让状态栏与挖孔；普通字号分段宽 176dp 并居中，窄屏和大字号调整可用宽度 |
| 状态栏融合 | `LumenSurfacePresets.fadingBand`、扩展后的公开 `LumenSurfaceFadeCurve.LINEAR`、同一局部会话的 `bind(..., source)` | `FollowFeedStyle.statusBand` 统一选择线性曲线；`FollowFeedStatusBar` 只在内容进入顶部区域时启用一个采样表面，来源是原 RecyclerView，卡片材质在其外部兄弟层；`FollowFeedViewport` 可逆上延内容，并补等量顶部 padding |
| 最常访问 | 同一 palette | 保留原横向列表及条目、直播装饰，46dp 头像、单行省略、完整昵称无障碍描述与大字号尺寸调整 |
| 列表末尾 | 无新增材质或动画 | 以原底栏实际覆盖区域、原 padding、系统 inset 求并集，只更新列表 padding |

`FollowFeedStyle`、`FollowFeedController` 等是本仓库新增的薄适配层，不是引擎现成 API。材质、圆角、描边、透明度和动画分别由引擎表面和分段组件实现；业务层没有新增玻璃 Drawable、Shader、弹簧或 ValueAnimator。

卡片为 `STATIC`，`sampling.enabled=false`，不提供 source。实际静态路径使用 `fallbackTintOpacity`，所以同时将它与 `tintOpacity` 设为 1，palette.surface 本身为不透明颜色；不是只靠 `opacity=1` 推断可读性。默认 16 个表面预算中预留分段底座、选中框与状态栏融合带，卡片最多占 13 个。只对当前附着且分组正确的动态建立表面，不扩大预算、不为 holder 创建独立会话。卡片与分段的无采样策略不受状态栏融合影响。

## 宿主模型与原背景归属

已检查测试机宿主 8.90.2（8902100）的真实 APK：`j0.M()` 返回转发归属的顶层 `q0`，`q0.f()` 是稳定身份，`j0.d0()` / `g0()` 是顶层模块首尾。不要用嵌套投稿的 `N()` 来分组。Adapter bind 只缓存标量身份和成员，既不保留正文与历史模型，也不按位置奇偶分卡。刷新插入条目可能不重绑全部 holder，因此运行时使用当前布局的相邻条目验证分组，而非旧 bind position。

宿主的 `home.z0` 继承 `gv1.c`，`k(Canvas, RecyclerView, View, j0)` 在 `onDraw` 中调用矩形模块底色绘制。该矩形会遮住背景兄弟层上的凝光卡片。适配只在目标列表、合法分组且已有附着卡片绑定时跳过这项底色绘制；整个 ItemDecoration、原 offsets、onDrawOver、内容和点击均保留。未识别条目、预算不足、页面关闭时仍走宿主原绘制。

引擎表面是原列表的非交互背景兄弟层，不新增整卡点击层，不重新挂载列表，不裁剪媒体子树。只清理识别到的直接模块承托背景；保留播放器的 TextureView、原媒体圆角、封面比例、黑边、字幕及控制层。

## 生命周期与维护

`MediatorFragment` 的视图、暂停、恢复、销毁事件驱动一个页面会话。bind、child attach、回收和 detach 同步模块样式；接入时只扫描一次已有列表，滚动帧只处理附着模块的标量元数据和几何。重新 attach 建立新绑定，detach、暂停、退出关闭绑定。暂停时保留仍会参与转场绘制的标题隐藏与布局；真正销毁 View 时才恢复仍属于本适配器的背景、布局和文字，不覆盖宿主后续写入。滚动、回收均不调用进程级 `releaseGraphics()`。

主题变化通过现有主题源驱动 `updatePalette`、分段前景及已显示模块前景，并影响随后复用的模块。宿主 Compose 正文、富文本链接、认证装饰等继续使用宿主原主题和语义，不直接修改其 Compose 内部实现。

今后统一材质或尺寸调整集中改 `FollowFeedStyle`；生命周期、分组和原背景协调集中改 `FollowFeedController`；混淆契约集中改 `FollowFeedHostAccess` 与安装器。升级引擎时统一更新 catalog 和源码提交/校验，并核对公开契约及像素测试；上游提供线性渐隐后移除公共补丁与复合构建入口。页面只组合公开配置，无需逐个修改散落的自绘实现。

## 可执行验证与实际范围

在项目标准 JDK 17 / 本地 Gradle 环境执行过：

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`：通过，APK 已在默认测试机覆盖安装并冷启动宿主。
- `:app:testDebugUnitTest`：2469 项通过，包括角色、无采样、透明度配置、浅深配色、动态分组、刷新插入与底栏覆盖并集。
- 引擎依赖升级后运行 `:app:lintDebug`：通过；后续宿主适配收敛后运行 `:app:lintFast`：通过，190 条警告，现有 3 个错误基线保持不变。宿主资源按名称解析的提示及 KTX 建议仍保留，没有为它们扩大 baseline。
- 指定运行 `FollowFeedInstrumentedTest`、`HostVideoCardPlayerScopeInstrumentedTest`、`HostBottomBarArtifactInstrumentedTest`、`HostBottomBarHiddenTabsInstrumentedTest`：初版 14 项通过；修正最常访问更多入口底色后新增一项原 selector 配色与恢复测试，15 项通过。

新增实机契约检查涵盖真实宿主模型与绘制入口、静态表面像素 alpha、实际 STATIC 后端、无录制、100 次重绑/解绑计数、背景所有权、浅深配色及复用文字更新、分段业务转发与恢复、未知额外标签回退，以及 280/640/1024dp、1/2 倍字号的原生布局测量。

运行时 diagnostics 分阶段输出数值，不包含动态身份、正文、用户信息或媒体路径。初版无状态栏融合时观察到 5 个附着表面，实际后端为 STATIC；GPU/software 绘制与新增会话内容录制均为 0，暂停后表面数回到 0。随后增加的状态栏融合后端与记录见末节。此计数只属于新增关注页会话，不能推断原底栏采样为零，也不能当成性能基准。

当前宿主实际检查了全部/视频切换与内容恢复、视频和转发/引用、长正文、文章、联合创作、竖版视频比例与黑边；打开作者空间、更多菜单、视频详情、评论和转发编辑页后返回，执行刷新、快速滚动与重复进出。转发编辑页已取消，没有发布；底栏实际边界仍为 `104,2175–976,2307`。最终覆盖安装版本的画面保存在 `captures/follow-delivery-final.png`，构建、14 项实机测试与局部会话诊断分别保存在 `follow-delivery-checks.log`、`follow-delivery-instrumentation.log`、`follow-delivery-final-diagnostics.log`。

实机画面验证限于当前 8.90.2 宿主和测试机浅色竖屏。浅深主题、大字号、窄屏、横屏/平板宽度通过原生 View 参数化检查，但这些模式下的完整宿主画面、系统关闭动画、实际数据流末尾与所有网络业务的端到端结果仍须进一步复核。没有修改用户偏好、发布转发或执行真实点赞来代替检查。其他宿主版本只在契约匹配时尝试接入，不能据此声称已经通过兼容性验收或零性能回退。运行截图、视图树、构建和诊断日志保存在已忽略的 `captures/`。

纯图文样本、实际点赞和静音状态切换仍待实机补测；图文模块分组有 JVM 检查，播放器作用域有设备回归检查，但不能用这些检查代替相应业务的完整实机验收。

最常访问右侧 `dy_more_container` 使用独立的 `bg_card_selector`，初版只清理父背景，留下了与页面底色不一致的白色矩形。修正通过原 selector 的独立副本映射默认背景和按压颜色，同时更新更多文字；不替换点击监听，恢复时遵循原背景所有权。修正版 Debug/AndroidTest 构建、快速 Lint、15 项设备测试通过，实机点击仍打开“我的关注”，返回正常。画面对照保存在 `captures/follow-more-before-current.png` 与 `captures/follow-more-fixed.png`，相关日志为 `follow-more-checks.log`、`follow-more-instrumentation.log`。

## 紧凑顶部适配

宿主 8.90.2 的独立“关注”标题属于外层 `ExhibitionFragment`，`fo_app_bar` 与 `top_tab_container` 共用它的 Coordinator；Mediator 根 View 的顶部 padding 原为 211px，其中状态栏为 90px。只识别标题为“关注”、没有可见一级标签、发布 View 可用且 `MediatorFragment.ye(int): boolean` 契约匹配的布局；其他布局保留宿主原标题。将这项 padding 映射为尚未由页面位置承担的状态栏/挖孔 inset，移除约 44dp 的原标题占位。没有修改 Activity、Window、系统栏配置或全局背景。

保留同一个 `fo_publish_menu` View、原监听和资源，将其放在分段右侧的 48dp 触控区域。分段两侧空间充足时以页面中心为轴，空间不足时优先保证发布入口与选项不重叠；测量时解析更新后的相对边距，避免复用原 12dp 边距。销毁 View 或失败时恢复原父容器、参数、可见性、宿主占位与背景；暂停只释放表面绑定，避免转场中重新出现官方白色标题。主题更新同步顶部底色，恢复时继续从原 TabLayout 读取业务选中状态。

顶部静止时保留统一 palette 的稳定底色。按后续要求，滚动时增加引擎渐隐融合带，见末节；没有复用首页的历史采样实现或增加新的表面会话。底栏文件、材质和采样路径均未改动。

此次顶部迭代执行 `assembleDebug`、`assembleDebugAndroidTest`、目标 `FollowFeedStyleTest`（6 项通过）、`lintFast`（197 条警告，原 3 个错误基线不变），以及上述四个设备测试类（16 项通过）。新增检查覆盖五次标题收起/恢复、原发布监听与父容器恢复、未知标题和额外标签跳过、主题底色更新、inset 不重复累计，以及 280/640/1024dp、1/2 倍字号下的居中与触控避让。构建和检查日志分别为 `captures/follow-top-compact-corrected-build.log`、`follow-top-compact-corrected-lint.log`、`follow-top-compact-corrected-instrumentation.log`。这次只重跑目标 JVM 测试，没有把历史全量测试结果计作此次全量运行。

最终 Debug APK 已覆盖安装、冷启动并在真实宿主复测全部/视频切换、原发布页打开及取消返回、首页往返与重复进入。最终画面及布局树保存在 `captures/follow-top-final-all.png`、`follow-top-final-video.png` 与 `follow-top-final-restored-tree.txt`。实机仍限于当前宿主、浅色竖屏；其他完整宿主画面与关闭动画的验收范围不因参数化测试扩大。

紧凑顶部初版匿名诊断记录在 `captures/follow-top-final-diagnostics.log`：内容显示后 6 个附着表面，实际静态绘制，GPU/software 绘制与内容录制为 0；打开发布页暂停后绑定数回到 0。底栏实际边界仍为 `104,2175–976,2307`，此诊断不包含或改变原底栏的采样。

## UP 主转场与状态栏融合修正

最常访问的头像打开宿主自己的 UP 主动态筛选浮层。原适配在 Mediator 暂停时恢复外层标题与 211px 占位，而底页仍参与浮层的入场动画；复现录像中可见官方白色顶部、错位分段与方形内容背景。修正将“释放绑定”和“恢复布局”分开：暂停释放分段、卡片及融合带绑定，保留标题隐藏和紧凑占位；销毁或 detach 才完整恢复。五次暂停/恢复的控制器设备检查直接覆盖该路径，浮层入场时的真实视图树也确认 `fo_app_bar`、`top_tab_container` 保持 GONE。

状态栏使用同一会话中的一个 `fadingBand`，无折射、描边和圆角，状态栏区域保持较强色罩，之后用引擎的渐隐能力收口到透明。`FollowFeedViewport` 仅在目标列表及其到 Mediator 根 View 的祖先链内放宽裁剪，上延一个状态栏高度并补等量顶部 padding；列表起点、内容静止位置与末尾覆盖区域保持不变。不会逐帧改 padding 或创建新动画，关闭时恢复仍由自己持有的参数和裁剪标志。

来源是当前可见、有合法动态卡片的原 RecyclerView。卡片表面为它的外部兄弟层，分段和融合带也在其外；直接 View 来源接受引擎的窗口与反馈校验，未声明 `excludesSurfaces=true`，未隐藏 View 或重挂层级来录制。内容尚未进入融合带时使用无来源静态渐隐，不录制列表；内容进入后才请求采样，切换列表重建绑定。卡片和分段仍无采样。旧设备和预算不足时沿用引擎回退，未调整或放大默认资源预算。

返回复测还发现列表的显示缓存保留了暂停时的原矩形底色。表面集合或绑定发生变化时，对原列表调用一次 invalidate，保证 ItemDecoration 按当前背景所有权重新绘制；没有增加逐帧刷新或布局请求。最终 UP 主筛选返回截图 `captures/follow-status-delivery-up-return.png` 已恢复完整圆角卡片。

此次执行 Debug 与 AndroidTest 构建、7 项目标 JVM 测试、快速 Lint（197 条警告，原 3 个错误基线不变）、19 项设备回归，均通过。新增设备检查覆盖转场暂停、等量上延的起点与末尾几何、幂等与后续宿主写入的所有权，以及静态回退色罩的顶部不透明和末尾渐隐像素。渐变最后一个 texel 允许 1/255 的栅格取整余量。最终日志为 `captures/follow-status-delivery-checks.log`、`follow-status-delivery-lint.log`、`follow-status-delivery-instrumentation.log`；此前像素取整验证保存在 `follow-status-test-rounding-checks.log`。

已安装并冷启动宿主，复测滚动/回顶、全部/视频切换、UP 主筛选进出与作者空间返回。真实画面为 `captures/follow-status-final-scroll.png`、`follow-status-final-rest.png`；修复前后录像为 `follow-up-before.mp4`、`follow-up-fixed.mp4`。匿名诊断 `follow-status-final-diagnostics.log` 确认实际 GPU 后端且 failure=NONE，存在状态栏所需的内容录制；暂停后绑定数为 0。不能再把新增会话整体描述为“零录制”，也不能据此声称零性能回退。底栏实际边界和其自身采样会话保持原样；当前实机仍只覆盖浅色竖屏，旧设备软件回退与完整深色/横屏/平板画面尚未验收。

2026-10-08 继续验证同一最终 APK：冷启动后检查全部/视频、最常访问更多入口、UP 主筛选进出、作者空间返回、首页往返及滚动/回顶。原作者入口确实打开 `AuthorSpaceActivity`，真实空间及返回画面为 `captures/follow-resume-author-ready.png`、`follow-resume-author-return.png`；空间界面保持宿主原样。最终返回画面和视图树为 `follow-resume-final.png`、`follow-resume-final-tree.txt`，官方外层标题仍为 GONE，底栏边界仍为 `104,2175–976,2307`。

此次匿名诊断 `captures/follow-resume-diagnostics.log` 确认暂停时 surfaces=0，静止内容未进入顶部前 recordings=0；首次采样实际为 SOFTWARE / GPU_UNAVAILABLE，说明使用了引擎的软件回退，不能将昨日的 GPU / NONE 记录概括为每次都成功使用 GPU。滚动稳定画面 `follow-resume-scroll-settled.png` 可见状态栏后的渐隐模糊。当前设备上的软件回退已观察到，其他 Android 版本与完整深色/横屏/平板画面仍未验收；未声称零性能回退。恢复任务时主源码未改变，复用同源码已通过的构建、7 项 JVM 测试、19 项设备测试与快速 Lint，未重复运行这些检查。

## 首次渐隐收口调整（已由线性方案替代）

按实际观感反馈，将全强度保持区从整个状态栏高度缩到其上半段。继续使用公开 `fadingBand(hold, end)` 的平滑曲线，渐隐在状态栏下方约 8dp 结束，随后保留 4dp 完全透明尾部；View 总高度从状态栏加 16dp 减到加 12dp。色罩和采样效果使用同一引擎 mask，在实际 View 边界之前归零，不依赖硬裁剪收口。保留 Z 顺序但取消轮廓投影，不增加自绘渐变或另一套渲染实现。

此次 Debug、AndroidTest 构建与 7 项目标 JVM 测试通过；12 项关注页设备检查通过，像素检查验证状态栏下缘已经衰减、每行 alpha 单调、末尾整段 alpha=0；快速 Lint 通过，197 条警告及原 3 项错误基线不变。日志为 `captures/follow-fade-checks.log`、`follow-fade-instrumentation.log`、`follow-fade-lint.log`。覆盖安装并冷启动后检查实际滚动收口，截图为 `follow-fade-after-scroll.png`、`follow-fade-final.png`；匿名诊断 `follow-fade-diagnostics.log` 确认该次实际 GPU / NONE，底栏组件、会话及 `104,2175–976,2307` 边界未改变。实机验收范围仍限于当前设备浅色竖屏。

## 等速渐隐（当前方案）

用户复看指出上方衰减慢、中段突然加快。查实上游基线和 2026-10-08 main `8839f2e90b5d7ff89a49f74d034bbe5ebe2a9af3` 都把渐隐固定为 smoothstep，调整 hold/end 无法产生等速衰减。当前基于原固定提交增加一个通用公共曲线选项，而非在宿主绘制另一个 mask；补丁 SHA-256 为 `fcdfd57b69dd3ae08db419f2701b0091aa5844fe7aed64beb2a44c80638664fd`，诊断构建版本为 `91e31dce23dda35d2fd6d2411d0aac3079e3c46a-linear.fcdfd57b69dd`。所有引擎模块保持同一源码基线与版本，默认曲线维持 SMOOTH，只有关注页顶部显式选择 LINEAR。

`FollowFeedStyle.statusBand` 将 hold 设为 0，从顶端开始等速衰减，仍在状态栏下约 8dp 归零并保留约 4dp 透明尾部。正常测试机对应 123px View、112px 归零；没有顶部全强度保持段、第二段缓动、轮廓阴影或硬裁剪收口。blur 半径保持原值，逐渐消失的是采样和色罩的覆盖率；内容自身颜色变化不等同于渐隐曲线不均匀。

实际执行并通过：

- 宿主 Debug / AndroidTest APK 构建；目标 7 项测试及全量 2471 项 JVM 回归。
- 引擎、motion、controls 与 sample Debug 构建；367 + 326 + 2 + 2 = 697 项 JVM 测试（sample 使用 Release），四模块完整 Lint；sample Release 和 AndroidTest 包构建。
- 宿主快速 Lint：197 条警告，原 3 项错误基线不变。
- 默认手机上 12 项关注页检查和 7 项底栏/播放器回归。静态实际像素每隔 10px 的 alpha 差一致（仅容许 8bit 取整误差），尾部 alpha=0，静态路径无录制、关闭绑定后计数归零。
- 引擎 sample 的 `linearGpuFadeHasUniformPixelCoverage`：真实 GPU 后端 + PixelCopy，在 10%–90% 的九个等距位置验证预期线性颜色覆盖率，通过。系统拦截测试后台启动后，在已解锁手机上显式启动同一测试 Activity，并处理“本次允许”；没有修改持久安全策略。

修复 APK 已覆盖安装并冷启动宿主，复测滚动和返回；实际画面为 `captures/follow-linear-rest.png`、`follow-linear-scroll.png`、`follow-linear-final.png`，视图树仍确认官方外层标题 GONE、原底栏 `104,2175–976,2307`。日志为 `follow-linear-build.log`、`follow-linear-regression.log`、`follow-linear-engine-checks.log`、`follow-linear-lint.log`、`follow-linear-instrumentation.log`、`follow-linear-bottom-player.log`、`follow-linear-gpu-pixels.log`、`follow-linear-diagnostics.log`。

本次宿主诊断观察到 SOFTWARE / GPU_UNAVAILABLE 回退，同时 GPU 绘制计数非零；独立 GPU 像素检查通过，不能据此把该次宿主回退说成 GPU / NONE。卡片、分段依然不采样，顶部融合按需录制，原底栏自身会话未更改。完整深色、横屏/平板及其他 Android 版本画面仍未验收，不声明零性能回退。首次复合构建须下载固定源码并补齐依赖缓存，之后可离线；上游合入等效能力后可撤掉补丁，页面继续使用集中公开配置。
