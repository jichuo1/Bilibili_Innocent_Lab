# 无痕与免登录：交接说明

更新于 2026-10-31 12:00。分支 `feature/incognito-no-login`，远端 `fork`。本文只写事实：哪些做了、在哪台机器上看到过、哪些没验证、哪些界面是官方的、哪些是模块自己画的。按时间追加的证据记录在 `docs/incognito-no-login-round3-plan.md`，其中 2026-10-28 的状态表与缺口表已过时，以本文为准。

## 1. 目标

在哔哩哔哩 8.84.0 到 9.17.0（hostlab 里共 38 个版本）上：

- **无痕**：本次会话不向服务端泄露身份，写操作和埋点被拦下。
- **免登录（游客）**：不登录也能用「我的」页的收藏、稍后再看、历史、关注等入口，数据存在本地，界面尽量复用宿主自己的页面，不另造一套。

用户的原则：**使用官方原生的操作，不要割裂**。8.90.2 上做到过（星标弹层、提示条、收藏夹编辑器都是官方的，只替换存储）。后来为了覆盖更多版本，部分界面改成了模块自己的，见第 4 节。

## 2. 状态一览

| 项目 | 状态 |
|---|---|
| 提交 | 本地 HEAD 之前的功能提交见第 3 节。已推送到 `c15fd96a`；之后的提交（`3eaf89b6` 起，含 2026-10-31 的四个）只在本地，**推送须用户明确要求，只推 `feature/incognito-no-login`，不推 main** |
| 门禁 | 2026-10-31（`d60f185f`）：`:app:testDebugUnitTest :app:lintFast :app:assembleDebug` 4226 个测试，0 失败，1 个无关跳过；FastLint 0 错误、261 条警告。**完整 `:app:lintDebug` 已跑**：0 错误（基线 3 个不变）、1387 条警告；本轮改动文件里新增的两条 KavaRef 建议已改掉（`d60f185f`），其余命中这些文件的建议是更早的代码，没动 |
| 真机 | 只有一台小米 10（Android 16，root，LSPosed）。此前装 9.16.1，2026-10-30 起装 arm64 的 9.0.0（versionCode 9000200）。8.90.2 在这个阶段没有设备 |
| 其余版本 | 只有 hostlab 静态证据（反编译、dex 形状查询），没有真机 |

## 3. 提交清单（时间正序，节选与本阶段相关的）

- `41a2ac2b` 游客点「我的」页收藏、稍后再看、历史三行，打开宿主原生页
- `bc99b979` 视频页星标：游客收藏写本地库（简化版）
- `b52e534a` 收藏、稍后再看列表由本地库应答
- `16cde2a7` 「我的」页不再被强制登录盖层遮住
- `02bb9377` 引导页（欢迎向导）跳过，仅 9.16.0 与 9.16.1 生效
- `492da0ca` 本地库支持改收藏、稍后再看
- `d041d941` 游客收藏后重绘视频页星标
- `c165bee1` 视频页星标弹出收藏夹选择框（模块自有）
- `bc93ea64` Story 竖屏播放器的星标
- `99b51a9a` 8.84 到 8.96 的星标重绘（只静态核对）
- `422c9923` 视频页收藏数随星标变化
- `b31c2871` Story 收藏数随星标变化
- `4f38b78b` 8.97 起收藏列表由本地库应答（按形状找转换器，不再写死 8.90.2 的名字）
- `c15fd96a` 没有 `compose/favorites/kntr` 路由的版本（8.97 到 9.11）上，「我的收藏」直接打开 `FavoritesActivityV2`
- `3eaf89b6` 8.97 起阻止本地列表写进宿主的回退缓存
- `617614ce` 点收藏夹卡片打开详情页（`BLRouter.routeTo(请求, Fragment)` 重载）
- `934700ef` 引导页跳过扩到 8.99 到 9.15：首启等待判断在 `pegasus.request.b` 上也认（9.0.0 实机）
- `6c52a164` 8.97 起游客「添加至稍后再看」走宿主自己的流程和弹窗（9.0.0 实机）
- `21df16e5` 去掉游客文案里的 ` · ` 分隔
- `d60f185f` 新定位代码改用 KavaRef 的 `isStatic`（完整 Lint 建议）

## 4. 官方原生与模块自有（重点）

### 4.1 模块自己画的界面

这些是 `AlertDialog` 等普通 Android 控件，样式不是哔哩哔哩的，**与用户「用官方的」目标不一致**：

| 界面 | 代码 | 何时出现 |
|---|---|---|
| 「收藏到」多选框（勾选收藏夹、「新建收藏夹」「取消」「完成」） | `GuestFavoriteFolderPicker`，由 `GuestVideoFavoriteHooks` 调用 | 游客点视频页星标或 Story 星标。9.0.0 与 9.16.1 实机看到。**8.90.2 除外**：那里旧钩子 `GuestNativeFavoriteHooks` 在登录门之前接管星标点击，走官方的 `FavoriteDialog`，但这个阶段没有 8.90.2 设备，没重新验证 |
| 「新建收藏夹」输入框 | 同上 | 在上面的框里点「新建收藏夹」 |
| 「已保存到本地」Toast | `GuestVideoFavoriteHooks` 的 `notify` | 找不到星标状态写入口，星标无法重绘时才出现；它是唯一的反馈 |
| 无痕下点顶部「登录」弹出的提示（请先关闭无痕再登录） | `GuestLibraryHostHooks` | 无痕开着，游客点「我的」页头部的登录文字 |
| 简易列表弹窗（历史、收藏、关注、稍后再看） | `GuestLibraryUi` | 挂在旧版「我的」页头部的点击上，原生页打不开时的备用入口。具体何时触发看 `GuestLibraryHostHooks` 里 `library.show` 的调用点，这个阶段没有逐项核对 |

另外，一些**文案**是模块给的：收藏夹详情页的创建者「本地账号」，默认夹名「默认收藏夹」（`LocalVirtualLibrary.DEFAULT_FOLDER`）。

### 4.2 复用宿主自己的界面

数据由模块在本地生成，按宿主自己的响应格式交给宿主自己的解析器，所以界面是官方的：

- 「我的收藏」页（`FavoritesActivityV2` 及其「收藏夹」「视频」两个标签）、收藏夹详情页（`MultiTypeListDetailActivityV2`）：`GuestNativeListHttpHooks` 在 `BiliCall.execute` 里短路 `/x/v3/fav/*`，`NativeFavoriteProtocol` 生成 JSON。
- 稍后再看页（KNTR Compose）、历史页（Java HistoryMoss 与 KMP KHistoryMoss）：`GuestNativeWatchLaterHooks`、`GuestNativeHistoryHooks`。
- 8.97 起「添加至稍后再看」（首页卡片菜单等，`MainCommonService.addToWatchLater` 两个重载与 `batchAddToWatchLater`）：`GuestWatchLaterLoginScope` 在 `LoginScopeKt.loginRequire` 处只放行来自 `tv.danmaku.bili.ui.watchlater.widget` 的宿主动作，宿主照常发 `/x/v2/history/toview/add`，由 `GuestNativeListHttpHooks` 本地应答；应答带 `show_toast` 等字段，弹出宿主自己的「已添加至稍后再看　查看」提示条。没打开过的视频先在网络线程用 `ViewMoss.executeView` 读元数据（`PrivacyVideoMetadata.fetch`）。
- 关注列表：`GuestNativeFollowListHooks` 复用宿主的关系列表容器和单元格，走单独标记的本地路由。
- 视频页星标颜色和收藏数：调用宿主自己的 `RelationRepository.n(boolean)`，它会改页面计数、写星标缓存并发总线消息（`GuestPageFavorite`）。Story 星标用 `StoryDetail` 的 getter 和 setter（`GuestStoryStar`）。
- 8.90.2 上的收藏夹编辑器：官方 V2 Compose 编辑器与状态机（`GuestNativeFolderEditorHooks`）。

### 4.3 为什么星标用了自己的弹窗，以及改回官方的思路（未实现、未验证）

9.16.1 的官方收藏弹层是 KMP 组件 `kntr.app.favorite.favoriteDialog`，入口 `KingPositionService.k(boolean)`，类和方法名混淆且逐版变化，当时判断接管成本最高，所以改成自己的框。8.97 起 `KingPositionService.B0/C0/V0` 也不存在，旧钩子装不上。

可能的做法，**都没试过**：

1. 在星标的登录门处放行，让宿主自己弹官方弹层（类似对菜单项 `needLogin` 的处理）。
2. 弹层读收藏夹列表和提交收藏走的是 `/x/v3/fav/folder/created/list-all` 与 `/x/v3/fav/resource/deal` 一类接口。`NativeFavoriteProtocol` 已实现列表、复制、移动，但**没有 `resource/deal`**，要补，并带上 `rid` 才能标出已勾选的夹。
3. KMP 弹层的网络大概率走 Ktor 与 ignet（`IgHttpRequest`），不走 OkHttp 的 `BiliCall`，要接到 `GuestIgnetLibraryResponses`。
4. 提交后星标和收藏数仍由现有的 `GuestPageFavorite` 处理。

风险：弹层内部状态依赖账号信息，放行登录门后可能在别处再次要求登录。先在 9.0.0 或 9.16.1 上读反编译、再试，没有证据前不要承诺。

## 5. 验证矩阵

「真机」指小米 10。

### 9.0.0（arm64，真机，2026-10-30）

已看到：

- 状态键 `guest_native_lists_http=adapted;skipped=folder_route,detail_owner`：本地列表应答通过形状找转换器装上；`fa6.b`（8.90.2 的文件夹路由）在 9.0.0 不存在。
- `guest_native_lists_cache_dropped=seen`：回退缓存的 `kotlin.Lazy` 被替换成返回 null 的代理（`3eaf89b6`）。**宿主缓存文件本身没有去查看**，「没有写入」是由缓存为 null 推出来的。
- 「我的收藏」入口：`guest_favorites_route_opened=seen`，`FavoritesActivityV2` 打开，两个标签正常渲染（`c15fd96a`）。
- 星标：点视频页星标，弹出模块自己的「收藏到」框，勾选「默认收藏夹」点「完成」，状态键 `guest_video_favorite_picker`、`_applied`、`_saved`、`_counted` 都出现。
- 收藏夹卡片：`guest_favorites_folder_route_opened=seen`，点卡片后栈顶为 `MultiTypeListDetailActivityV2`。收藏一个视频后（**不重启宿主**）进详情页，显示「默认收藏夹」「创建者: 本地账号」「1个内容」和那条视频（`617614ce`）。

2026-10-31 补测（截图在已忽略的 `captures/r4/`）：

- 引导页：修复前 `guest_onboarding_gate=absent`，冷启动弹「欢迎来到哔哩哔哩」。`934700ef` 后 `gate=installed`、`data=installed`、`entry=installed`，冷启动出现 `guest_onboarding_gate_applied`、`guest_onboarding_data_suppressed`，**连续三次冷启动直接进首页推荐流**，不再弹引导页。
- 稍后再看：从「我的」打开 KNTR 原生页，`guest_ignet_library_answered=/x/v2/history/toview/v2/list`，空态正常。首页卡片三点菜单「添加至稍后再看」修复前会拉起 `LoginActivityV2`；`6c52a164` 后出现 `guest_watch_later_login_scope_applied`，弹出宿主自己的「已添加至稍后再看　查看」，不跳登录；再进稍后再看页，那条视频的封面、标题、UP 主、时长都对。没打开过的视频的元数据走了已封口的 `View/View` RPC。
- 历史记录：从「我的」打开原生历史页；播放一个视频约 10 秒后再进，列表里有它，进度「00:24 / 02:27」、今天的时间。**页顶仍有宿主的「登录查看更多历史记录　立即登录」横幅**：8.97 起页面把登录判断内联到 `BiliAccounts` 调用里（`GuestNativeHistoryHooks` 的 `isLogin`、`up(boolean)` 在 9.0.0 都不存在，状态键是 `adapted-rpc`），点它会去登录页。
- 关注数、关注列表：**不通**。「我的」页是 Compose 的 `mineV2.HomeUserCenterFragmentV2`，点「关注」数字直接拉起 `LoginActivityV2`。反编译 9.0.0：动态、关注、粉丝三个数字的点击是密封接口 `mineV2.L` 的 Kotlin object 实现（关注是 `L$q`、粉丝是 `L$r`，方法 `a(b)`），先查 `BiliAccounts.isLogin()`，未登录就 `routeTo("activity://main/login/")`。类名全混淆。数字本身显示宿主账号的 0，不是本地关注数（`guest_library_follow_count` 钩的是旧 View 版 `HomeUserCenterFragment.p0/jr`，9.0.0 为 `missing`）。本地关注列表页 `GuestNativeFollowListHooks` 写死 8.90.2 的 `BlackListActivity.g9/Y8/X8/D/P`，9.0.0 为 `missing`，所以即使放行点击也没有可用的原生列表页。

没验证：

- 收藏页里那张卡片在非空时的封面和「N 个内容」文字（详情页的数量是对的）。
- 收藏页的「+」新建收藏夹、卡片长按编辑。接口 `folder/add`、`edit`、`add_top` 本地协议里有，但没在 9.0.0 点过。
- 稍后再看提示条上的「查看」：提示条 4 秒后自动收起，这次没点到，链接 `bilibili://main/playset/watch-later` 在 9.0.0 已登记，但跳转没实测。
- 视频页「更多」菜单里的稍后再看（`guest_library_video_menu_watch_later` 在 9.0.0 为 `missing`，它也走 `addToWatchLater` 的话应当被新钩子覆盖，没点过）。
- `21df16e5` 改的两处文案：9.0.0 的「我的」页是 V2，没有旧版的 `tv_login`；关注列表页在 9.0.0 打不开。两处都只有静态核对。

### 9.16.1（真机，此前阶段）

星标选择框、星标变色、视频页与 Story 的收藏数、Story 星标、收藏页、稍后再看页、历史页、「我的」页不被登录盖层遮住，都看到过。引导页跳过只在 9.16.0 与 9.16.1 实机验证（连续三次冷启动）。

### 8.90.2

用户此前阶段用旧钩子做出了完整的官方体验（星标弹层、提示条、星标变色）。这个阶段没有设备，**没有重新验证**。读代码的结论：旧钩子拦截 `KingPositionService.B0` 并设 `result = null`，宿主不会走到登录门，新钩子挂在登录路由上，所以不会叠出第二个弹窗。

### 其余版本

只有静态证据：

- 旧钩子 `GuestNativeFavoriteHooks` 在 38 个版本里只有 8.90.2 一个满足全部锚点；8.84 到 8.96 其余 11 个版本缺 1 到 3 个类，8.97 起 `KingPositionService.B0/C0/V0` 一个都没有。所以 37 个版本的星标全靠 `GuestVideoFavoriteHooks`。
- 8.84 到 8.96 的星标缓存是 `FavorRepository` 单例（`99b51a9a`），没有这些版本的设备，没跑过。
- 8.97 到 9.11 的「我的收藏」入口只在 9.0.0 实机跑过，其余版本只有静态结论。
- 引导页首启等待门（q60）：`pegasus.request.b` 或 `request.d` 上唯一的静态 `()Z`，在 8.99、9.01（两版）、9.08 到 9.15、9.16（两版）共 13 版成立，调用方都含 `PegasusFragment.onCreate`；8.97、8.98、9.02 到 9.07、9.17 两个类里都没有，保持 `absent`。实机只有 9.0.0、9.16.0、9.16.1。
- 稍后再看添加（q61、q64）：8.97 到 9.17 共 26 版都由 `tv.danmaku.bili.*` 下一个混淆类实现 `addToWatchLater` 两个重载和 `batchAddToWatchLater`，都把 `ui.watchlater.widget` 里的 `Function0` 交给 `LoginScopeKt.loginRequire$default`。实机只有 9.0.0。

## 6. 已知问题与剩余工作（按重要性）

1. **星标弹窗不是官方的**（第 4.3 节）。要不要改、怎么改需要用户定。
2. **8.97 起「我的」页关注数与关注列表**（第 5 节 9.0.0）：要放行 `mineV2.L` 的关注、粉丝 object（类名混淆，需先找稳定形状），数字要换成本地关注数，本地关注列表页要把 `GuestNativeFollowListHooks` 的 8.90.2 成员名改成按形状找。三块都要跨版本矩阵，再在 9.0.0 实机验证。
3. **8.97 起历史页的登录横幅**：宿主内联了登录判断，要在 9.0.0 反编译 `HistoryFragmentV3` 找横幅的显示入口。
4. 引导页：8.97、8.98、9.02 到 9.07、9.17 共 9 个 V2 版本的首启等待判断不在 `request.b/d` 上，仍不生效；8.84 到 8.96 靠旧插件钩子，没验证。
5. 批次 2 的多项（登录弹窗、一键登录引导、登录 toast、画质钳制、海外强制登录）只有「已注册」证据，没有真机触发过。
6. 评论游客上限、动态页底部登录条：9.x 上干净跳过，没做。
7. KMP 广播隧道（在线状态）仍开放。试过掐断它会让宿主 SIGABRT 或 SIGSEGV，已移除。
8. 登录用户的行为只有单元测试，没有真机。

## 7. 实机测试的坑（这几条每条都浪费过时间）

- **无痕下本地库只存在内存里**（`LocalVirtualLibrary.enterIncognito`、`resetIncognito`），宿主进程重启就清空，磁盘上没有 `bil_guest_library.json`。收藏后重启宿主再看，列表一定是空的，这不是缺陷。验证收藏列表时不要重启宿主。
- 普通 `adb shell input tap` 会被系统以 `INJECT_EVENTS` 拒绝，要用 `su -c 'input tap X Y'`。
- 截图在对话里可能被缩放，**真实坐标 = 看到的坐标 × 约 1.17**（原图 1080×2340）。用错比例会点到别处，比如把标签点成搜索页。点之前先读当次截图，视频页有广告行时操作栏位置会变，简介展开后也会下移。
- 平台给出的截图偶尔是旧图（时间戳和内容与设备对不上）。用设备状态栏时间核对，对不上的弃用，改看日志、栈顶 Activity（`dumpsys activity activities | grep topResumedActivity`）。
- 冷启动会先出欢迎页，约 1 到 2 秒后菜单才可点，第一下点菜单常常没反应。
- 状态键从模块日志读：`/data/adb/lspd/log/` 下取当前日志，用 `su -c` 读。日志里中文在 PowerShell 的 `Select-String` 里会丢字，判断「没有」之前先用别的方式确认。
- 每次改完无痕开关要重启宿主；脚本 `inc-on.sh`、`inc-off.sh` 只改模块偏好文件里的 `incognito_enabled`，并恢复 SELinux 标签。收尾要把无痕关掉、停掉宿主与模块。
- **改完偏好文件要先打开一次模块界面**（root `am start -n com.Bilibili_Innocent_Lab.xposedmodule/.ui.activity.MainActivity`），再重启宿主。宿主通过 LSPosed Remote Preferences 读配置，直接改 XML 不会同步；日志里 `新鲜启动许可(source=remote_config_transport_fallback)` 且没有任何 `guest_`/`incognito_` 状态键，就是这个原因。
- 状态键的日志格式是 `状态(key=..., value=...)`，进程号在第二列（`10276: <pid>: <tid>`），按当前宿主 pid 过滤。
- `input keyevent` 也要 `su -c`。
- 9.0.0 的稍后再看提示条 4 秒后自动收起，要点「查看」得在出现后马上点。
- dexsql 的 `LIKE` 不把 `_` 当单字符通配，要用 `substr`/`instr`；q62、q63 因此返回空。

## 8. 代码地图

主目录 `Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/privacy/`：

- 网络封口：`IncognitoNetworkHooks`、`IncognitoWebHooks`、`IncognitoTelemetryHooks`、`KmpMossBoundary`、`KmpHeaderSanitizer`、`IgnetHttpBoundary`、`IgnetHttpSanitizer`。
- 会话：`IncognitoSession`、`IncognitoIdentityBus`。
- 入口：`PrivacyFeatureRuntime`（两个安装器：`incognito_privacy`、`guest_browsing`）、`GuestBrowsingFeatureInstaller`。
- 本地库：`LocalVirtualLibrary`（数据与校验）、`GuestLibraryRuntime`（线程与持久化，无痕时内存）。
- 本地协议：`NativeFavoriteProtocol`、`NativeWatchLaterProtocol`、`NativeFavoriteFolders`（夹 ID：夹名 sha256 前 6 字节加 1，默认夹是 162385528615728，自定义存储的 ID 优先）。
- 列表短路：`GuestNativeListHttpHooks`（`BiliCall.execute`，另含回退缓存屏蔽、`findFragmentByUrl`、文件夹路由、详情页归属）、`GuestIgnetLibraryResponses`（Ktor/ignet）。
- 入口与路由：`GuestMineMenuHooks`（菜单项 `needLogin`）、`GuestFavoritesRouteHooks`（收藏页与卡片，`routeTo` 两个重载）、`GuestMineOverlayHooks`、`GuestLibraryHostHooks`。
- 收藏写入：`GuestVideoFavoriteHooks`、`GuestFavoriteFolderPicker`、`GuestStoryStar`、`GuestPageFavorite`、`GuestNativeFavoriteHooks`（仅 8.90.2）、`GuestNativeFolderEditorHooks`。
- 其他：`GuestNativeHistoryHooks`、`GuestNativeWatchLaterHooks`、`GuestNativeWatchLaterAddHooks`（仅 8.90.2）、`GuestWatchLaterLoginScope`（8.97 起的稍后再看添加）、`GuestNativeFollowListHooks`、`GuestPlaybackHooks`、`GuestOnboardingHooks`、`PrivacyStructuralResolver`（按形状找混淆成员，歧义不猜）。

宿主类与成员的查找统一走 `runtime/KavaMemberLookup.kt`。

## 9. 约定

- 版本适配一律先做结构定位：稳定类名、公开 API、方法形状；定位不唯一就跳过并上报状态键，不猜。
- 每个子功能有独立状态键，日志通道对同一个键只保留第一条，所以不同结果要用不同键。
- 提交信息用英文，`前缀: 内容`，小写，要点用短横线。
- hostlab 数据：38 个版本，另有 9.0.0 的近邻 9.0.1（`9010300`、`9011100`）可当参考。9.0.0 自己没有 hostlab 数据，反编译用邻版再到真机确认。
