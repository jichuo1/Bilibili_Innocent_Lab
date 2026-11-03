# 无痕与免登录：交接说明

> 2026-11-03 更新：关注分组数据层、请求保护和本轮隐私修复见第 21 节；原生分组菜单仍未接入。视频作者回调兼容见第 20 节，收藏操作见第 18 节，无痕广播边界见第 12、13 节。

最近更新于 2026-11-03。分支 `feature/incognito-no-login`，远端 `fork`。本文只写事实：哪些做了、在哪台机器上看到过、哪些没验证、哪些界面是官方的、哪些是模块自己画的。按时间追加的证据记录在 `docs/incognito-no-login-round3-plan.md`，其中 2026-10-28 的状态表与缺口表已过时，以本文为准。

## 1. 目标

在哔哩哔哩 8.84.0 到 9.17.0（hostlab 里共 38 个版本）上：

- **无痕**：本次会话不向服务端泄露身份，写操作和埋点被拦下。
- **免登录（游客）**：不登录也能用「我的」页的收藏、稍后再看、历史、关注等入口，数据存在本地，界面尽量复用宿主自己的页面，不另造一套。

用户的原则：**使用官方原生的操作，不要割裂**。8.90.2 上做到过（星标弹层、提示条、收藏夹编辑器都是官方的，只替换存储）。后来为了覆盖更多版本，部分界面改成了模块自己的，见第 4 节。

## 2. 状态一览

| 项目 | 状态 |
|---|---|
| 提交 | 本地已提交分组数据、协议、会话绑定及两项隐私修复，最新代码提交 `a7652a59`；fork 跟踪分支仍停在 `a35ec42d`，后续提交尚未推送。 |
| 门禁 | 当前 Debug 构建、4307 项 JVM 测试（0 失败、1 跳过）和快速 Lint 通过（262 条警告，3 条既有基线错误过滤）；未重跑完整 Lint。首次两 worker 并发 Lint 时一项原有计时测试失败，单 worker 重跑全量测试通过。 |
| 真机 | 小米 10（Android 16，root，LSPosed），9.16.1 本轮回归收藏、原生选夹、稍后再看，并观察到新增第三方统计拦截命中；分组菜单尚未开放。8.90.2 为第 19 节候选包实测，本轮最终包未在旧版复测。 |
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
- `c02df9db` 8.97 起历史页登录横幅对游客隐藏（9.0.0 实机）
- `374ffbfd` 8.92 起「我的」页点「关注」打开本地关注列表（宿主 `BlackListFragment` 页，9.0.0 实机）
- `3c692894` 空间页关注按钮走本地：宿主关注广播同步进本地库，空间数据响应按本地状态显示已关注（9.0.0 实机）
- `a929a38e` 本地关注列表删到空时当场显示空提示（9.0.0 实机）
- `3e048601` 本地收藏协议补 `resource/batch-deal`、`resource/unfav-all`，收藏夹列表按 `rid` 标勾选
- `20b31940` 8.97 到 9.11 游客点视频页星标打开宿主自己的 `FavoriteDialog`（9.0.0 实机）
- `56060759` 引导页首启等待门在 `request.a/b/c/d` 里找，覆盖 8.97 起全部 26 个 V2 版本（9.0.0 实机）
- `592449ad` 宿主收藏弹窗扩到全部 38 版（参数类 `$c`/`$b` 从构造器取；9.0.0 实机回归）
- `e084eb96` 视频页关注后用宿主自己的提示（「关注成功~」）并发全局关注广播（9.0.0 实机）
- `b79b2690` 8.84 到 8.91 的本地关注列表改用同一个页面类（`BlackListActivity`），只认 8.90.2 成员名的旧钩子降为备用（9.0.0 回归，8.84 到 8.91 静态）

## 4. 官方原生与模块自有（重点）

### 4.1 模块自己画的界面

这些是 `AlertDialog` 等普通 Android 控件，样式不是哔哩哔哩的，**与用户「用官方的」目标不一致**：

| 界面 | 代码 | 何时出现 |
|---|---|---|
| 「收藏到」多选框（勾选收藏夹、「新建收藏夹」「取消」「完成」） | `GuestFavoriteFolderPicker`，由 `GuestVideoFavoriteHooks` 调用 | 游客点视频页星标或 Story 星标，**只在宿主弹窗构造失败或显示不了时**（38 版结构上都能用宿主弹窗，第 4.3 节）。9.16.1 实机看到过。**8.90.2 除外**：那里旧钩子 `GuestNativeFavoriteHooks` 在登录门之前接管星标点击，走官方的 `FavoriteDialog`，本轮候选包复测见第 19 节 |
| 「新建收藏夹」输入框 | 同上 | 在上面的框里点「新建收藏夹」 |
| 取消关注确认框 | GuestHostFollowConfirmation | 仅在识别到视频作者回调，但宿主确认结构、Hook 注册或显示失败时作为备用；默认使用宿主菜单。 |
| 「已保存到本地」Toast | `GuestVideoFavoriteHooks` 的 `notify` | 找不到星标状态写入口，星标无法重绘时才出现；它是唯一的反馈 |
| 无痕下点顶部「登录」弹出的提示（请先关闭无痕再登录） | `GuestLibraryHostHooks` | 无痕开着，游客点「我的」页头部的登录文字 |
| 简易列表弹窗（历史、收藏、关注、稍后再看） | `GuestLibraryUi` | 挂在旧版「我的」页头部的点击上，原生页打不开时的备用入口。具体何时触发看 `GuestLibraryHostHooks` 里 `library.show` 的调用点，这个阶段没有逐项核对 |

另外，一些**文案**是模块给的：收藏夹详情页的创建者「本地账号」，默认夹名「默认收藏夹」（`LocalVirtualLibrary.DEFAULT_FOLDER`）。

### 4.2 复用宿主自己的界面

数据由模块在本地生成，按宿主自己的响应格式交给宿主自己的解析器，所以界面是官方的：

- 「我的收藏」页（`FavoritesActivityV2` 及其「收藏夹」「视频」两个标签）、收藏夹详情页（`MultiTypeListDetailActivityV2`）：`GuestNativeListHttpHooks` 在 `BiliCall.execute` 里短路 `/x/v3/fav/*`，`NativeFavoriteProtocol` 生成 JSON。
- 稍后再看页（KNTR Compose）、历史页（Java HistoryMoss 与 KMP KHistoryMoss）：`GuestNativeWatchLaterHooks`、`GuestNativeHistoryHooks`。
- 8.97 起「添加至稍后再看」（首页卡片菜单等，`MainCommonService.addToWatchLater` 两个重载与 `batchAddToWatchLater`）：`GuestWatchLaterLoginScope` 在 `LoginScopeKt.loginRequire` 处只放行来自 `tv.danmaku.bili.ui.watchlater.widget` 的宿主动作，宿主照常发 `/x/v2/history/toview/add`，由 `GuestNativeListHttpHooks` 本地应答；应答带 `show_toast` 等字段，弹出宿主自己的「已添加至稍后再看　查看」提示条。没打开过的视频先在网络线程用 `ViewMoss.executeView` 读元数据（`PrivacyVideoMetadata.fetch`）。
- 关注列表：`GuestFollowListPage` 复用宿主的黑名单页（8.84 到 8.91 是 `BlackListActivity`，8.92 起是 `BlackListFragment`），经 `RelationApiManager.getBlackList` 的回调交本地数据；`GuestNativeFollowListHooks`（8.90.2 成员名）只在它装不上时备用。
- 视频页作者关注按钮：已关注时复用宿主单项取消确认，只有确认后才移除本地关注；按宿主成功路径回调、提示并发关注广播。本地关注分组和特别关注尚未实现，未展示宿主完整分组菜单；已识别视频作者但宿主确认结构或调用不可用时使用模块确认兜底；作者回调也无法识别时仍沿用原路径，见第 19 节。
- 视频页星标颜色和收藏数：调用宿主自己的 `RelationRepository.n(boolean)`，它会改页面计数、写星标缓存并发总线消息（`GuestPageFavorite`）。Story 星标用 `StoryDetail` 的 getter 和 setter（`GuestStoryStar`）。
- 8.90.2 上的收藏夹编辑器：官方 V2 Compose 编辑器与状态机（`GuestNativeFolderEditorHooks`）。

### 4.3 星标弹窗：8.97 到 9.11 已改回官方（`3e048601`、`20b31940`）

9.0.0 的官方弹窗是 `com.bilibili.playset.widget.favorite.FavoriteDialog`（类名不混淆），由 `KingPositionService.k(boolean)` 在登录门之后构造。q69、q71、q72：**38 版都用它**，构造都是 `FavoriteDialog(ComponentActivity, 参数类, ActivityResultLauncher)`；参数类 8.84 到 8.96 叫 `$c`（有普通 19 参数构造），8.97 起叫 `$b`（20 参数，带默认值掩码），字段类型一致。此前说「9.12 起是 KMP 弹层」是错的（q69 那 10 版分析失败，q71 证实 `KingPositionService` 在 9.12 到 9.17 也持有这个弹窗）。此前 8.84 到 8.96 的弹窗因存在多个自有无参方法而无法确定刷新入口；本轮改为按实际调用栈学习刷新方法，静态范围与实机限制见第 18 节。

做法（`GuestHostFavoriteDialog`）：

- 现有的星标登录拦截（`RouterProxy.open`）照旧，拦下后不再弹模块框，而是构造宿主的 `FavoriteDialog` 并显示。
- 弹窗加载收藏夹前要 `BiliAccounts.isLogin()` 为真。只在这个弹窗自己的方法运行时，当前线程上 `isLogin()` 返回真（`GuestLoginScope`）。无痕的账号隔离钩子也读它，因为同一方法上先设结果的钩子说了算。
- 弹窗走 `BiliCall` 的 `/x/v3/fav/folder/created/list-all`（按 `rid` 标勾选）和 `/x/v3/fav/resource/batch-deal`（只发改动的夹，`0` 是默认夹），由本地收藏协议回答。
- 弹窗里「新建收藏夹」打开宿主的创建页，返回时视频页只刷新它自己字段里的弹窗；模块钩视频页实际生效的 `onActivityResult`，对自己打开的弹窗调用同一个加载方法（新版使用唯一无参方法，旧版从实际构造更新协程时的调用栈定位）。
- 弹窗关闭后，若收藏状态变了，星标和收藏数照旧由 `GuestPageFavorite` 处理。

以下是改动前的分析，保留作记录。


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
- 星标：点视频页星标，弹出模块自己的「收藏到」框，勾选「默认收藏夹」点「完成」，状态键 `guest_video_favorite_picker`、`_applied`、`_saved`、`_counted` 都出现。（2026-10-31 起 9.0.0 改为宿主弹窗，见下面第四轮。）
- 收藏夹卡片：`guest_favorites_folder_route_opened=seen`，点卡片后栈顶为 `MultiTypeListDetailActivityV2`。收藏一个视频后（**不重启宿主**）进详情页，显示「默认收藏夹」「创建者: 本地账号」「1个内容」和那条视频（`617614ce`）。

2026-10-31 补测（截图在已忽略的 `captures/r4/`）：

- 引导页：修复前 `guest_onboarding_gate=absent`，冷启动弹「欢迎来到哔哩哔哩」。`934700ef` 后 `gate=installed`、`data=installed`、`entry=installed`，冷启动出现 `guest_onboarding_gate_applied`、`guest_onboarding_data_suppressed`，**连续三次冷启动直接进首页推荐流**，不再弹引导页。
- 稍后再看：从「我的」打开 KNTR 原生页，`guest_ignet_library_answered=/x/v2/history/toview/v2/list`，空态正常。首页卡片三点菜单「添加至稍后再看」修复前会拉起 `LoginActivityV2`；`6c52a164` 后出现 `guest_watch_later_login_scope_applied`，弹出宿主自己的「已添加至稍后再看　查看」，不跳登录；再进稍后再看页，那条视频的封面、标题、UP 主、时长都对。没打开过的视频的元数据走了已封口的 `View/View` RPC。
- 历史记录：从「我的」打开原生历史页；播放一个视频约 10 秒后再进，列表里有它，进度「00:24 / 02:27」、今天的时间。**页顶仍有宿主的「登录查看更多历史记录　立即登录」横幅**：8.97 起页面把登录判断内联到 `BiliAccounts` 调用里（`GuestNativeHistoryHooks` 的 `isLogin`、`up(boolean)` 在 9.0.0 都不存在，状态键是 `adapted-rpc`），点它会去登录页。
- 关注数、关注列表：**不通**。「我的」页是 Compose 的 `mineV2.HomeUserCenterFragmentV2`，点「关注」数字直接拉起 `LoginActivityV2`。反编译 9.0.0：动态、关注、粉丝三个数字的点击是密封接口 `mineV2.L` 的 Kotlin object 实现（关注是 `L$q`、粉丝是 `L$r`，方法 `a(b)`），先查 `BiliAccounts.isLogin()`，未登录就 `routeTo("activity://main/login/")`。类名全混淆。数字本身显示宿主账号的 0，不是本地关注数（`guest_library_follow_count` 钩的是旧 View 版 `HomeUserCenterFragment.p0/jr`，9.0.0 为 `missing`）。本地关注列表页 `GuestNativeFollowListHooks` 写死 8.90.2 的 `BlackListActivity.g9/Y8/X8/D/P`，9.0.0 为 `missing`，所以即使放行点击也没有可用的原生列表页。

2026-10-31 12:40 第二轮（`c02df9db`、`374ffbfd`）：

- 历史页：`guest_native_history=adapted:banner-guard`，登录横幅不再出现，「全部」「视频」「未看完」三个标签来回切都不出现，列表从页顶开始。
- 关注列表：`guest_follow_page`、`guest_mine_follow_entry` 都是 `installed`。点「我的」页的「关注」数字，出现 `guest_mine_follow_entry_applied`、`guest_follow_page_applied`，打开宿主的原生页，标题「我的关注」，空时是宿主的「再怎么找也没有啦」。在视频页关注一个 UP 主后再进，列表里有头像、名字和「已关注」；左滑不会触发移出黑名单；点行进入对方空间页。
- 关注数字仍显示宿主账号的 0（见第 6 节）。

2026-10-31 16:10 第三轮（`3c692894`、`a929a38e`）：

- 空间页：`guest_library_space_follow=installed`。新开 `bilibili://space/2` 显示「＋关注」，点它后宿主自己变「已关注」并提示「关注成功~」，出现 `guest_space_follow_synced`；退出再进，出现 `guest_space_follow_shown`，按钮直接是「已关注」。
- 关注列表里这条显示空间页上的名字「碧诗」和头像。点进空间页「已关注 → 取消关注」，宿主提示「取消关注成功」；返回列表当场变成「再怎么找也没有啦」，重进也是空。

2026-10-31 16:41 第四轮（`3e048601`、`20b31940`，av170001）：

- 状态键 `guest_video_favorite_host_dialog=installed`。点星标出现宿主自己的弹窗（「添加到稍后再看」「选择收藏夹」「新建收藏夹」「完成」），列出本地「默认收藏夹 0个内容 · 私密」，出现 `_host_picker`。
- 勾选点完成：星标变粉，`_saved`、`_counted` 出现。再点星标，默认夹显示「1个内容」并已勾选；取消勾选点完成，星标变灰。
- 「新建收藏夹」打开宿主的「创建」页，填名字点完成，回到弹窗时新夹排在最上面并已勾选；点完成后再开，新夹「1个内容」。
- 弹窗里的「添加到稍后再看」出宿主提示条「已添加至稍后再看 / 查看」。
- 没看到：完成后宿主的收藏提示条（`commitChange` 里有 `n(...)`，这次没出现，原因没查）；收藏数显示 87.7万，加一减一看不出变化。

上面「关注数、关注列表：不通」一条，其中关注列表已由 `374ffbfd` 解决；那段分析保留作记录。

没验证：

- 设置里真正的「黑名单」入口在游客下是否仍是宿主原样（代码上只有本模块刚打开的页才当成本地页，10 秒过期，没实机点过）。
- 视频页关注按钮的取消关注：当时本地接管后直接切换，后续已补宿主单项确认，范围及分组限制见第 19 节。
- 空间页之外的宿主关注按钮（搜索结果、评论区等）只要发 `FollowStateManager.notify` 就会同步进本地库，但这些页面重进后按钮仍按服务端显示，没逐个看。
- 收藏页里那张卡片在非空时的封面和「N 个内容」文字（详情页的数量是对的）。
- 收藏页的「+」新建收藏夹、卡片长按编辑。接口 `folder/add`、`edit`、`add_top` 本地协议里有，但没在 9.0.0 点过。
- 稍后再看提示条上的「查看」：提示条 4 秒后自动收起，这次没点到，链接 `bilibili://main/playset/watch-later` 在 9.0.0 已登记，但跳转没实测。
- 视频页「更多」菜单里的稍后再看（`guest_library_video_menu_watch_later` 在 9.0.0 为 `missing`，它也走 `addToWatchLater` 的话应当被新钩子覆盖，没点过）。
- `21df16e5` 改的两处文案：9.0.0 的「我的」页是 V2，没有旧版的 `tv_login`；关注列表页在 9.0.0 打不开。两处都只有静态核对。

### 9.16.1（真机，此前阶段）

星标选择框、星标变色、视频页与 Story 的收藏数、Story 星标、收藏页、稍后再看页、历史页、「我的」页不被登录盖层遮住，都看到过。引导页跳过只在 9.16.0 与 9.16.1 实机验证（连续三次冷启动）。

### 8.90.2

2026-11-02 已重新在小米 10 上回归；设备上的宿主反馈路径缺少 `FavorRepository`，针对该异常的处理和最终验证见第 18 节。旧钩子拦截 `KingPositionService.B0`，新钩子挂在登录路由上。

### 其余版本

只有静态证据：

- 旧钩子 `GuestNativeFavoriteHooks` 在 38 个版本里只有 8.90.2 一个满足全部锚点；8.84 到 8.96 其余 11 个版本缺 1 到 3 个类，8.97 起 `KingPositionService.B0/C0/V0` 一个都没有。所以 37 个版本的星标全靠 `GuestVideoFavoriteHooks`。
- 8.84 到 8.96 的星标缓存是 `FavorRepository` 单例（`99b51a9a`），没有这些版本的设备，没跑过。
- 8.97 到 9.11 的「我的收藏」入口只在 9.0.0 实机跑过，其余版本只有静态结论。
- 引导页首启等待门（q60、q70、q73）：38 版的 `PegasusFragment.onCreate` 都恰好调用 `pegasus.request` 包里一个读 `isFirstStart()` 的静态 `()Z`。8.97 起 26 版在 `a/b/c/d` 四个类之一，四个类合起来只有这一个；8.84 到 8.96 在 `ApiManagerKt`，与另外 3 个静态 `()Z` 并列。实机只有 9.0.0、9.16.0、9.16.1。
- 稍后再看添加（q61、q64）：8.97 到 9.17 共 26 版都由 `tv.danmaku.bili.*` 下一个混淆类实现 `addToWatchLater` 两个重载和 `batchAddToWatchLater`，都把 `ui.watchlater.widget` 里的 `Function0` 交给 `LoginScopeKt.loginRequire$default`。8.84 到 8.96 由 `WatchLaterManagerV2.j/k/p` 直接调用同一个 `loginRequire$default`，动作类也在 `ui.watchlater.widget` 包里，所以 `GuestWatchLaterLoginScope` 结构上覆盖全部 38 版。实机只有 9.0.0。

## 6. 已知问题与剩余工作（按重要性）

1. **视频关注分组与适配边界**：视频页已复用宿主单项取消确认，8.90.2 和 9.16.1 已实测；本地尚无关注分组与特别关注，宿主完整分组菜单没有接入。已识别作者回调但宿主确认不可用时有模块确认兜底；作者回调无法识别时仍走旧路径。详见第 19 节。
2. **官方收藏操作**：普通视频页和 Story 星标已恢复单击一键收藏、再次单击取消、长按或点提示条打开宿主选夹；38 个归档样本完成关键结构核对，实机与限制见第 18、19 节。8.84–8.96 由模块打开的宿主弹窗的新建夹刷新路径还缺实机验证。
3. **空间页关注按钮**：9.0.0 和 9.16.1 的本地关注、重进和取消已实机验证，见第 16 节。空间页是 H5（`LocalAuthorSpaceActivity`，38 版都有，q68）：点关注后 H5 自己发请求（无痕封口当成功），再经 `SpaceRelationWrapper` 调宿主的全局关注广播 `FollowStateManager.notify(mid, followed, except)`；模块钩这个广播同步本地库。H5 的初始关系来自原生 `BiliCall` 取的 `/x/v2/space`，对本地已关注的人改写成 `relation=1`、`card.relation.status=2`。试过在 H5 首次报告后用广播把本地状态推回去：宿主那边收到了（包装类的关系变成 FOLLOW_HIM），但 H5 按钮不变，延后到 4 秒也一样，所以放弃推送，只改响应。视频页作者关注按钮的重进状态已补上，见第 17 节；搜索、评论等其他位置仍待验证。
4. **「我的」页的关注数字**：8.90.2、9.0.0 和 9.16.1 已在无痕会话中随本地关注数更新，见第 15、19 节。8.92–8.96 新增旧数据类复制适配，五个样本的复制方法形状与单元测试通过，页面实际绑定和刷新尚未实测，见第 18 节。数字在 `AccountMineV2.following`（8.92 起字段名都是 `s`，q65）。2026-10-31 试过在 `BiliCall` 转换处改 `/x/v2/account/mine` 的 `following`，但 9.0.0 无痕下这个请求根本没经过转换点（游客不请求这份数据，页面用默认值），已撤回；后续改在页面状态层处理。
5. 引导页：8.97 起 26 个 V2 版本的首启等待门都能找到（q70、q73，`56060759`）。8.84 到 8.96 的门在 `ApiManagerKt` 里与另外 3 个静态 `()Z` 并列，按形状分不出，仍靠旧插件那一层，没验证。
6. 批次 2 的多项（登录弹窗、一键登录引导、登录 toast、画质钳制、海外强制登录）只有「已注册」证据，没有真机触发过。
7. 评论游客上限、动态页底部登录条：9.x 上干净跳过，没做。
8. KMP 广播：改走宿主的禁用路径后，8.90.2、9.0.0、9.16.1 的测试窗口未观察到已知广播建连；仍需验证其他版本、网络切换和长时间行为，见第 12 节。
9. 登录用户的行为只有单元测试，没有真机。

## 7. 实机测试的坑（这几条每条都浪费过时间）

- **无痕下本地库只存在内存里**（`LocalVirtualLibrary.enterIncognito`、`resetIncognito`），宿主进程重启就清空，磁盘上没有 `bil_guest_library.json`。收藏后重启宿主再看，列表一定是空的，这不是缺陷。验证收藏列表时不要重启宿主。
- 普通 `adb shell input tap` 会被系统以 `INJECT_EVENTS` 拒绝，要用 `su -c 'input tap X Y'`。
- 截图在对话里可能被缩放，**真实坐标 = 看到的坐标 × 约 1.17**（原图 1080×2340）。用错比例会点到别处，比如把标签点成搜索页。点之前先读当次截图，视频页有广告行时操作栏位置会变，简介展开后也会下移。
- 平台给出的截图偶尔是旧图（时间戳和内容与设备对不上）。用设备状态栏时间核对，对不上的弃用，改看日志、栈顶 Activity（`dumpsys activity activities | grep topResumedActivity`）。
- 冷启动会先出欢迎页，约 1 到 2 秒后菜单才可点，第一下点菜单常常没反应。
- 状态键从模块日志读：`/data/adb/lspd/log/` 下取当前日志，用 `su -c` 读。日志里中文在 PowerShell 的 `Select-String` 里会丢字，判断「没有」之前先用别的方式确认。
- 无痕开关优先在模块设置界面修改，等配置发布后重启宿主；收尾恢复原值。`inc-on.sh`、`inc-off.sh` 只改本地 XML 并恢复 SELinux 标签，不能单独同步到 LSPosed Remote Preferences。若使用脚本，必须先打开模块界面让它发布配置，再重启宿主；关闭时也同样要发布。
- **在点击关注、收藏等账号操作前，按当前宿主 PID 确认日志出现 `incognito_privacy_status=partial` 和所需的 `guest_` Hook 安装状态，并核对首页呈游客状态。** 本地 XML 显示开关已启用不代表宿主已接收；本轮漏掉配置发布时，宿主实际按原账号启动并触发了一次真实关注，随后已撤回且页面恢复为未关注。播放记录可能已写入原账号，不应擅自清理用户历史。
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
- 其他：`GuestNativeHistoryHooks`（含 8.97 起的横幅守卫）、`GuestFollowListPage` 与 `GuestMineFollowEntry`（8.92 起的本地关注列表和入口）、`GuestSpaceFollowSync`（关注广播同步与空间页响应）、`GuestMineFollowCount`（「我的」页本地关注数）、`GuestNativeWatchLaterHooks`、`GuestNativeWatchLaterAddHooks`（仅 8.90.2）、`GuestWatchLaterLoginScope`（8.97 起的稍后再看添加）、`GuestNativeFollowListHooks`、`GuestPlaybackHooks`、`GuestOnboardingHooks`、`PrivacyStructuralResolver`（按形状找混淆成员，歧义不猜）。

宿主类与成员的查找统一走 `runtime/KavaMemberLookup.kt`。

## 9. 约定

- 版本适配一律先做结构定位：稳定类名、公开 API、方法形状；定位不唯一就跳过并上报状态键，不猜。
- 每个子功能有独立状态键，日志通道对同一个键只保留第一条，所以不同结果要用不同键。
- 提交信息用英文，`前缀: 内容`，小写，要点用短横线。
- hostlab 数据：38 个版本，另有 9.0.0 的近邻 9.0.1（`9010300`、`9011100`）可当参考。9.0.0 自己没有 hostlab 数据，反编译用邻版再到真机确认。

## 10. 2026-11-01：游戏中心日志上报

- `PrivacyNetworkPolicy.blockHttp` 只对 `biligame.net` 及其子域的 `/collector/api/report` 拦截，不把整个域名加入主机名单。`/collector/admin/config` 仍经原有清洗流程发出，避免空配置令 SDK 刷新间隔归零。OkHttp 使用原有本地成功应答；ignet 仍以 IOException 拒绝。新增 `incognito_game_tracker_report` 独立状态，避免通用网络日志达到 64 条上限后无法确认命中。
- 9.0.0（arm64，小米 10）：最终 APK 打开游戏中心后看到 `incognito_game_tracker_report=withheld-okhttp`，配置请求仍显示 sealed and sent。此前仅差诊断异常保护的构建，命中后采样 10 次、间隔 20 秒，宿主 PID 10370 不变，Java 堆 PSS 为 68,368–109,492 KiB；记录在本机忽略的 `captures/incognito-game-20261101-final-soak.log`。
- 9.16.1（arm64，同一手机）：覆盖升级后，配置请求正常发出，实际日志上报命中 `withheld-okhttp`。视频详情页播放期间采样 10 次、间隔 20 秒，宿主 PID 17785 不变，Java 堆 PSS 为 76,428–127,120 KiB；截图与采样在 `captures/incognito-916-playing.png`、`captures/incognito-game-916-20261101-soak.log`。此前 30–60 秒内的持续堆暴涨在此次窗口未复现。
- 9.16.1 APK 来自哔哩哔哩官方 CDN，versionCode 9161100、arm64，签名 SHA-256 为 `93ba270f...faf5910e`；APK SHA-256 为 `2af77f5e...154f6ffe`，与 hostlab 归档的 `41efb1c9...d17bbfb2` 不是同一字节样本，不能把该实机结果当成后者的实机结果。
- 门禁：Debug APK、4235 个 JVM 测试（0 失败、1 跳过）和快速 Lint（0 新错误、261 条警告；3 条现有基线错误不变）通过。完整 Lint 未在这次普通迭代重跑。
- 边界：这里只证明已确认的游戏日志上报路径被拦截；其他未知埋点路径仍需审计。当时 KMP 广播隧道仍开放，后续进展见第 12 节；不能声称无痕已完全隔离身份。

## 11. 2026-11-01：广播隧道实验与 9.16.1 补测

### 此前的广播隧道实验（后续方案见第 12 节）

- 38 个归档版本的静态矩阵 `6280f7819458450196e36470569d3090`：`RpcEngine.createRpc(String,int,String)` 的广播调用方，8.84–8.91 的 7 个样本在 `stream.q.b`，8.92–9.17 的 31 个样本在 `Tunnel_androidKt$createTunnel$3.invokeSuspend`。这些是样本结构证据，不代表每个版本都做过实机测试。
- 实验一：在 9.16.1 只把广播连接地址改成 `127.0.0.1:1`，保留真实 native handle。日志出现 `incognito_broadcast_tunnel_applied=loopback`，`tcpdump` 捕获到目标为回环地址的 SYN；无痕播放期间 10 次、间隔 20 秒采样，PID 19815 不变，Java 堆 PSS 为 88,504–111,540 KiB。但单独 50 秒回环采样捕获到 11 个 SYN：先快速退避，约半分钟后又出现一组重试。端口若被本机其他进程监听也不能保证认证帧不会到达该进程，因此该方案不交付。
- 进一步读了 9.16.1 的 `StreamClient`：`f()` 发起首次启动，静态 `a(StreamClient,long)` 安排重启；重启协程可直接向 reactor 发事件，不能只挡首次启动。38 版静态矩阵 `505e542116bd413ab3ab42b56bb25010` 表明 8.92–8.95 样本的启动/重启分别为 `G()`/`F(long)`，8.96 为 `H()`/`G(long)`，8.97–9.17 样本为 `f()`/静态 `a(StreamClient,long)`；这些混淆名必须结合方法体指纹，不能仅凭名字推断其他版本。8.90.2 本地反编译表明旧 `StreamReactor.x(String,String,Continuation)` 为 start，`u(String,String,long,Continuation)` 为 retry，其他旧版尚未核实。
- 实验二：临时在实机 9.16.1 同时挡住上述新版启动和重启入口，并保留实验一回环改址作为后备。日志证明首次启动被挡；冷启动后 50 秒、过滤 `lo` 目的端口 1 的抓包为 0，不能将其理解成对所有网络通道的抓包证明。视频播放及一次后台返回的 10 次、间隔 20 秒采样中 PID 25296 不变，Java 堆 PSS 为 90,212–104,052 KiB。此次没有实际触发到重启钩子，未验证网络断开恢复或登录态变化。
- 不能仅凭三分钟采样认为上层方案安全：9.16.1 归档样本的只读查询 `49be3453a7884b82ad4d7eef59afa33f` 与反编译显示，广播客户端未连接时，双向流的发送调用可能把消息追加到 `StreamReg` 的待发送列表；无法连接期间的长期积累、调用方回调和退出/恢复语义尚未验证。下一步应先确认关闭广播时如何通知发送方并限制或避免排队，再验证首次启动、网络恢复、登录态变化、长时间和旧版行为。回环重试方案与上层实验均已撤出正式源码，实验文件和采样仅留在忽略的 `captures/compat-incognito/`、`captures/incognito-broadcast-*.log`。

### 9.16.1 收藏补测

- 使用第 10 节同一官方 APK，恢复到提交 `8841570d` 的 Debug 模块后，在无痕会话的普通视频页点星标：显示宿主收藏弹窗；勾选默认夹并完成后，星标变粉、数量由 883 变为 884；再次打开时默认夹为「1个内容」且已勾选。日志出现 `guest_video_favorite_host_picker`、`_saved`、`_counted`。
- 从该弹窗新建临时私密收藏夹，返回弹窗后新夹自动勾选；完成并再次打开后，新夹和默认夹各显示「1个内容」且均已勾选。无痕库在内存中，重启宿主后清除。截图位于忽略目录 `captures/incognito-guest-release-*.png`。
- 另在广播实验包上验证了 Story 星标：打开宿主收藏弹窗、保存后星标变粉，重开时默认夹为「1个内容」且已勾选；这条不是恢复后正式构建上的重测。

### 关注数后续定位

- 9.0.1 归档样本 `9010300` 的只读查询 `3c7b052c64ba401f9049cc0f1b0d0770` 显示 `AccountMineV2.s` 被 `mineV2.ui.A0.b` 和 `mineV3.ui.I.b` 直接读取；9.16.1 归档样本查询 `a698cc3cefee4f759bb230ec558e23d5` 显示由 `mineV2.ui.B0.b` 直接读取。这些均为混淆方法，归档 APK 与第 10 节实机 APK 的字节不同。
- 后续需要定位 Compose 渲染入口并验证本地关注变化如何触发重组；单纯修改不可变模型字段不能证明页面会刷新，也不应为了数字而猜其他 long 字段。此项当时尚未实现；后续结果见第 15 节。

## 12. 2026-11-01：使用宿主配置禁用广播

- 不改原生 RPC 句柄、地址或回调。无痕安装时提前把 `grpc_broadcast_enable`、`grpc_stream_biz_enabled` 和 `grpc_stream_biz_room_enabled` 的宿主配置查询结果置为 false，让宿主走自己已有的禁用路径；无痕关闭时不安装这些钩子。KMP 走 `KConfig.ff(String,boolean)`，Java 走 `RuntimeHelper.getCm()` 返回的实际 `ConfigManager.ff(String)` 实现；前者或动态钩子不可用时记录错误，不能当作身份隔离已完成。
- 静态矩阵 `3804bcdd5b694f978ea952fec4a10032`、`2b77701128b34e05ac937c7687a77c8d`：38 个归档版本都有上述三个配置键及两个配置入口的锚点。8.84–8.91 的主要路径为 Java，8.92–9.17 还使用 KMP；这只是静态结构证据。9.16.1 的发送路径在开关关闭时返回宿主的 `UNSUPPORTED`，避免直接拦截底层连接后仍向未连接客户端排队；直播间还有独立开关，因此三项均需关闭。
- `incognito_broadcast_policy` 记录安装结果，`incognito_broadcast_java_hook` 记录实际 Java 实现是否挂上；`incognito_broadcast_{java,kmp}_{global,business,room}` 分别记录真实查询命中。独立观察已知的 38 版广播调用栈：若仍进入 `RpcEngine.createRpc`，记录 `incognito_broadcast_tunnel_attempt=observed` 并上报运行错误，但不改变调用。观察器缺失会在 policy 标记 `monitor-missing`；不出现 attempt 并不证明不存在未知调用方、其他网络通道或已在钩子安装前创建的连接。
- 安装时序有影响：在其他无痕预检之后安装的首次 9.16.1 实验观察到已知广播建连；改为预检之前安装后，下列测试窗口未观察到该状态。若目标版本在整个功能安装前已缓存配置或创建连接，本方案不能保证阻断，需要继续验证。
- 9.16.1（arm64，第 10 节同一官方 APK）：早安装的候选构建命中 Java 三个开关及 KMP 总、业务开关；主页和直播间期间采样 10 次、间隔 20 秒，PID 11514 不变，Java 堆 PSS 122,716–153,040 KiB，未观察到已知广播建连。进入直播间时曾出现无法加载的网页风控验证，关闭后直播画面变化，部分互动区域显示加载失败；现有证据不能判断该提示与本改动的因果关系。采样见 `captures/incognito-broadcast-config-916-soak.log`。最终 APK 恢复到 9.16.1 后在临时网络隔离下冷启动，再次命中 Java 三个开关及 KMP 总、业务开关；该次未做长时间联网采样。
- 8.90.2（arm64，versionCode 8902100，APK SHA-256 `804ab5d719da42f257aac6237dda408151b93ecff71a0da99c5f32c71161a2e5`）：候选构建命中 Java 三个开关，采样 10 次、间隔 20 秒，PID 12022 不变，Java 堆 PSS 80,940–164,324 KiB，未观察到已知广播建连。普通视频播放、收藏并重开后默认夹显示 1 个内容。最终 APK 冷启动后再次命中 Java 三个开关，短时间观察未见已知广播建连；长时间采样采用的是候选构建，二者正常命中路径相同。
- 9.0.0（arm64，versionCode 9000200，APK SHA-256 `3d639ed495089da9d8707545054b0a4c07c27033f3667d8d78c32dd073969a20`）：最终 APK 命中 Java 总、业务、直播间开关和 KMP 总、业务开关；普通视频播放，收藏后星标变粉，重开显示默认夹 1 个内容且已勾选。最终 APK 播放视频、收藏及后台返回期间采样 10 次、间隔 20 秒，PID 21652 不变，Java 堆 PSS 63,488–104,176 KiB，未观察到已知广播建连；采样见 `captures/incognito-broadcast-config-900-final-soak.log`。
- 关闭模式：9.16.1 将无痕和免登录恢复关闭并冷启动后，模块日志正常出现，当前进程没有无痕广播状态；未进一步逐项验证所有实时功能的恢复。测试结束后已停止宿主和模块，临时 IPv4/IPv6 隔离规则均已移除。
- 门禁：最终源码的 Debug APK、4242 个 JVM 测试（0 失败、1 跳过）及快速 Lint（0 新错误、261 条警告；3 条现有基线错误不变）通过；未重跑完整 Lint。
- 功能代价与边界：实时广播被关闭，依赖它的在线推送、直播或其他互动可能不可用；视频和直播画面在上述观察中可播放。其余归档版本主要是静态证据，且同版本 APK 不一定是同一字节样本；网络断开恢复、长时间运行、无痕开关切换后所有实时功能的恢复及其他身份通道尚未完整验证，`incognito_privacy_status` 仍为 `partial`。

## 13. 2026-11-02：连接中断与普通模式恢复补测

- 测试对象为 9.16.1（第 10 节的官方 arm64 APK）和 `3816a1b4` 的 Debug 模块，设备上模块 APK 与该构建的 SHA-256 一致。无痕和免登录原始状态都是关闭；测试期间只打开无痕，结束后恢复两项均关闭。
- 确认无痕和广播配置已命中后，仅对宿主 UID 10276 的 IPv4/IPv6 输出依次加临时 REJECT，三轮各中断约 20 秒、恢复约 40 秒。三轮 IPv4 规则分别拒绝 601、541、278 个包；IPv6 计数均为 0。每轮规则已删除并单独检查不存在，设备 Wi-Fi 和 ADB 保持连接。这模拟的是该应用的网络请求失败与恢复，**不是**系统 Wi-Fi 断开、切换网络或 Connectivity 回调。
- 首轮恢复后推荐首页刷新出新内容；第二轮断网时直播列表显示加载失败，恢复后点重试重新显示列表；第三轮断网时打开直播间显示异常，恢复后按宿主提示退出重进，直播画面加载。此前见过的网页风控验证仍显示加载失败，部分互动区域也有加载失败，无法仅凭这次现象判定原因。
- 三轮约 3 分钟采样中 PID 25210 不变，Java 堆 PSS 为 88,684–137,756 KiB；模块日志未记录 `incognito_broadcast_tunnel_attempt`。这只能说明已知调用栈在观察窗口内未被记录，不能证明未知调用方或其他网络通道没有泄露。记录在 `captures/incognito-broadcast-reconnect-20261102.log`，截图在同目录 `incognito-network-*20261102.png`。
- 将无痕与免登录恢复关闭并冷启动后，模块日志正常，当前进程没有无痕广播状态；普通直播间画面可播放，聊天内容在两次截图间更新。该直播间与无痕测试时不同，不能由此判断某项互动在无痕下失效的原因，也不能代表所有实时功能均已验证。
- 设置说明补充「部分直播互动和实时更新可能不可用」，并同步英文、简体和繁体资源；简体设置页已在设备上检查完整显示。资源修改后的 Debug APK 与快速 Lint 通过（0 新错误、261 条警告，3 条现有基线错误过滤）；这次是文案修改，未重跑单元测试或完整 Lint。系统级网络切换、真实账号登录态变化、其他版本及更长时间运行仍待验证。

## 14. 2026-11-02：9.0.0 本地关注数的页面路径

- 对此前从手机备份的 9.0.0 arm64 APK 的 `classes21.dex` 做只读定位：`AccountMineV2.s` 的页面读取方包括 `mineV2.ui.A0.b(AccountMineV2, Function1, J, Composer, int)` 和 `mineV3.ui.L.b(...)`。这与 9.0.1 归档样本中 `mineV2.ui.A0.b` 的记录相邻，但不是同一个 APK 的证据。
- 对 9.0.0 的 `A0` 做单类反编译：`b` 是 `AmountLayout`，分别格式化 `AccountMineV2.r`、`s`、`t` 为动态、关注、粉丝数。其 `shouldExecute` 判定包含 `changedInstance(accountMineV2)`；如果只修改同一个模型的字段，不能据此认为 Compose 会重绘。`A0.m` 接收为空时直接返回，否则调用该 `b`。JADX 报了两个反编译错误，以上具体方法内容已结合 DEX 字段读取指令核对，不把整个类当作无错误源码。
- 9.0.0 `UserCenterPageKt` 在未登录且 `mineV2.N.a(false)` 为 true 时走另一套 `LoggedOutUserInfo`；`N.a` 在青少年或课堂模式启用时返回 true。该分支的 `LoggedOutAmountItem` 当前画 `-`，并不从 `AccountMineV2.s` 读取。因此仅适配 `A0.b` 不能声称覆盖此分支。
- 仍未实现关注数修复：需要先验证普通游客页的模型构造、不同版本的结构定位、只影响本地游客态的安全更新方式，以及本地关注变化后真实触发重组；不能直接更改共享的宿主账号模型，或凭格式化调用顺序猜测数值。该阶段只完成静态定位；后续实现和实机结果见第 15 节。

## 15. 2026-11-02：「我的」页本地关注数

- 普通游客页由宿主的 `MutableStateFlow<AccountMineData>` 提供 `AccountMineV2`。模块只对已识别的页面状态流与有效游客会话生效：本地库变化时复制账号和页面状态、替换副本的 `following` 字段并通过 `compareAndSet` 发布；宿主随后写入状态时也应用当前会话的计数。其他字段经逐项校验，原宿主对象保持不变，并发更新失败时最多重试三次。页面状态流使用弱引用，会话刷新或登录后缓存快照失效。
- 仅在模型复制方法、关注字段及页面状态形状可唯一识别时工作。绑定找不到唯一候选时记录 `guest_mine_follow_count_binding=unavailable`，模型副本无法验证时记录 `guest_mine_follow_count_copy=unavailable`；安装、绑定和应用分别有独立状态键。旧版原有 TextView 路径保留，不据此推断未实测版本的行为。
- 9.0.0（第 12 节的 arm64 APK，诊断补充前的候选包）：同一无痕进程中，视频页关注后「我的」页由 0 变 1，关注列表有一人；从其空间取消关注后「我的」页回到 0。截图位于忽略目录 `captures/follow-count-900-new-one.png`、`captures/follow-count-900-new-list.png`、`captures/follow-cancel-mine-900.png`。
- 9.16.1（第 10 节的官方 arm64 APK）：同一无痕进程中，视频页关注后「我的」页由 0 变 1，关注列表有一人；返回视频页再次点击关注按钮后本地关注被移除，「我的」页回到 0。诊断补充后的最终包再次完成 0→1→0；日志记录页面状态流安装与计数应用。截图位于 `captures/follow-916-one.png`、`captures/follow-916-list.png`、`captures/follow-916-video-cancel-mine.png`。
- 新发现的独立问题：9.16.1 在本地列表已有该作者时进入其空间，按钮仍显示「关注」；在空间页先关注再取消后，空间页提示成功，但本地列表仍保留该作者。重进视频页也显示「关注」，点击后实际移除原有本地关注。因此不能把计数正确等同于这些按钮的关系同步正确，当时需要定位该版本的空间与视频页适配。9.0.0 此次空间取消可正确更新本地库。9.16.1 当时的日志记录 `guest_library_space_follow=missing:IllegalArgumentException`；空间页问题已在第 16 节修复，视频页按钮的后续处理见第 17 节。
- 青少年或课堂模式使用的 `LoggedOutAmountItem` 分支仍显示 `-`，不读取该模型；此次未覆盖。8.90.2 及其他版本此次没有实机测试；真实登录账号、真实会话刷新和长时间使用仍需单独验证。
- 最终包在 9.16.1 上切换页面、查看历史并执行关注/取消期间，10 次、间隔约 20 秒采样中 PID 15766 不变，Java 堆 PSS 为 86,028–115,920 KiB，未出现本关注数功能的错误或结构不兼容状态；这只是约三分钟窗口的观察。记录在 `captures/follow-count-916-final-soak.log`。
- 门禁：最终源码的 Debug APK、4249 项 JVM 测试（0 失败、1 跳过）和快速 Lint（0 新错误、261 条警告，3 条现有基线错误过滤）通过；没有重跑完整 Lint。测试后 B 站恢复为 9.16.1，免登录与无痕均关闭，宿主和模块进程停止，临时 IPv4/IPv6 隔离规则均已移除。

## 16. 2026-11-02：空间页关注同步兼容性

- 只读检查本机两份官方 arm64 APK：9.0.0 的宿主 `ViewModelStore.put/get` 使用 `String` 键，9.16.1 使用 `Object` 键。原实现固定查找 `String`，因此 9.16.1 在注册空间关注钩子之前退出。该结论只对应这两份样本，不推断其他构建的具体签名。
- 使用 `KavaMemberLookup` 成对查找 `put`、`get` 和 `keys`，支持匹配的 `String` 或 `Object` 键，并检查返回值与实例方法形状；不存在完整匹配或出现多组匹配时不猜。读取模型时使用 `keys()` 返回的原始键，避免把新版本的键强转成字符串。
- 9.16.1（第 10 节的官方 APK）：修复后日志出现 `guest_library_space_follow=installed`、`guest_space_follow_synced=applied` 与 `guest_space_follow_shown=applied`。同一无痕进程中，从作者空间关注后列表显示该作者、「我的」页关注数为 1；从列表重进空间显示「已关注」；在空间取消后列表为空、关注数为 0。截图在忽略目录 `captures/space-916-*.png`。
- 9.0.0（第 12 节的 APK）：同一无痕进程中完成同样的关注、列表、重进和取消路径，列表与关注数均正确；两个同步状态也均出现。截图在 `captures/space-900-*.png`。
- 门禁：Debug APK、4250 项 JVM 测试（0 失败、1 跳过）和快速 Lint（0 新错误、261 条警告，3 条现有基线错误过滤）通过；未重跑完整 Lint。两版仅验证了上述路径，8.90.2、其他版本、真实登录账号及长时间运行未实测；视频页重进后的按钮适配见第 17 节。测试机已恢复到 9.16.1，无痕与免登录均关闭，宿主和模块进程停止。

## 17. 2026-11-02：视频页关注状态初始化

- 9.0.0 和 9.16.1 的普通视频作者区调用宿主 `FollowButton.bind(FollowButtonConfig)`，传入的初始关注状态来自宿主数据，原实现只接管点击，因而重进时可能显示「关注」，再次点击却取消本地关注。该控件还用于搜索和评论等页面；本次根据作者区 `OwnerService$a` 回调限定目标，其他页面的绑定不处理。
- 视频作者按钮完成宿主绑定后，异步读取本地库并调用宿主 `updateUI`，保留宿主的第二个游客状态参数。回调只持有按钮与辅助对象的弱引用，应用前重新核对游客态、辅助对象身份及作者 ID；`GuestLibraryRuntime.readCollections` 本身也验证会话令牌，避免登录、刷新或视图复用后应用过期结果。结构不匹配时跳过，由独立安装状态记录。
- 实测包在 9.16.1 和 9.0.0 的同一无痕进程里分别完成：未关注进入视频、点击关注、退出重进显示「已关注」、取消后重进显示「关注」。状态键 `guest_library_video_follow_initial=installed`、`guest_video_follow_initial=applied` 均出现。截图位于忽略目录 `captures/video-follow-916-*.png` 和 `captures/video-follow-900-*.png`。
- 已观察到 9.16.1 点击关注后出现的「充电」入口，重进已关注视频时没有显示；作者区还有其他依赖宿主初始状态的视图，这一现象尚未定位。其他版本、搜索/评论等其他作者按钮、真实账号及会话刷新未实机验证；无痕本地库仍只保存在当前进程内。
- 最终源码生成的 Debug APK SHA-256 为 `754624f8ef50fb01bba3e889db722587718ca02924f71ead6f08cbd0d25a6aa4`，与两版实测时安装的模块包一致。最终源码通过 4253 项 JVM 测试（0 失败、1 跳过）和快速 Lint（0 新错误、261 条警告，3 条现有基线错误过滤）；未重跑完整 Lint。测试机恢复为 9.16.1，无痕与免登录均关闭，宿主和模块进程停止，临时网络隔离规则已移除。

## 18. 2026-11-02：官方收藏操作与跨版本复核

- 普通视频详情页：识别宿主星标的单击和长按，单击写入默认收藏夹并显示宿主的「已收藏到默认收藏夹 / 修改收藏夹」，再次单击从各收藏夹中移除并显示宿主取消提示；长按或点「修改收藏夹」打开宿主 `FavoriteDialog`。异步回调检查游客状态与会话令牌；无法辨认事件时沿用先前的选夹行为。Story 使用宿主的深色提示条和 Story 场景选夹，单击和长按路径同样接入本地库。
- 38 个 HostLab 归档样本（8.84.0–9.17.0，非连续版本）：短按与长按的宿主事件、收集器和提示条方法形状已核对。8.84 的旧收集器是 `$13$a`，8.86–8.96 是 `$14$a` 的类型化方法，8.97 起在 `$14$a.emit` 内联；8.84–8.96 的提示条方法是单例实例方法，8.97 起是静态方法，旧版 12 个样本都有唯一的自身类型静态单例字段。矩阵 `2d9fa623468d4d4b9c930c0b94ffcf99`、`fa0a208dbd6f4f978adb150407f149ff`、`0e5a71a715914f2c8cd19a5067315360`、`3c479ef95532422cbe2ed4f413338945`；这是结构证据，并非 38 版端到端运行或全部功能适配的证明。
- Story 的长按监听器：38 个样本均在控件构造时绑定长按监听器，控件自身不重写 `performLongClick`（矩阵 `8995ca1116934e6e8714ce18528113a4`）。其中 36 个监听器直接持有该控件类型，8.97.3 和 9.11.4 的监听器是持有 `Object` 的合并类（矩阵 `886a9414ab4f456c972915ab4207d6fb`）。实现只在实际 Story 收藏控件同步处理 Android 长按期间记录临时作用域，并优先读取实际按住的控件，避免猜混淆监听器或误将其他长按认成收藏。`View.performLongClick` 的系统实现和厂商行为仍需实机覆盖。
- Story 9.16.1 候选包实测：单击收藏后星标由白变粉、数量 1009→1010，显示宿主深色提示条；再次单击取消后恢复白色与 1009；从提示条或长按打开宿主选夹，新建测试收藏夹返回后自动出现并勾选，加入两个夹后再次取消，重开均为空。后续加入控件复用保护后的最终包再次验证单击和长按；截图 `captures/story-official-*.png`。其余版本的 Story 本轮只有结构证据。
- 38 个样本的宿主弹窗都能找到直接创建 `FavoriteDialog$updateFavBoxList$1` 的唯一无参方法。旧版存在多个其他无参方法，本轮只在模块打开的弹窗内学习实际调用栈，再用于新建收藏夹返回后的刷新；9.16.1 已实机验证新建、返回和选中，8.84–8.96 由模块打开的宿主弹窗路径仅有静态证据。8.90.2 有独立旧钩子，不代表已验证该路径。
- 9.16.1（第 10 节同一官方 arm64 APK）：此前候选包实测单击星标变色和计数加一、提示条打开官方选夹、创建收藏夹后自动刷新并选中、长按再次打开，以及单击取消后两个收藏夹均不再勾选；最终候选包再次验证单击收藏和提示条打开选夹。截图 `captures/fav-official-candidate-*.png`、`captures/fav-official-916-final-*.png`。
- 9.0.0（第 12 节同一 arm64 APK）：此前候选包实测单击收藏及提示条、提示条打开宿主选夹、长按打开选夹和再次单击取消；截图 `captures/fav-official-900-*.png`。HostLab 没有 9.0.0 样本，只有相邻 9.0.1 样本。
- 8.90.2（第 12 节同一 arm64 APK）：本机宿主 `KingPositionService.D0` / `RelationRepository.I` 在操作后访问缺失的 `FavorRepository`，导致本地数据已修改却显示失败。仅对捕获到的这一明确缺类异常，复用宿主提示条与取消提示；其他异常仍上报。普通视频阶段的候选包实测单击收藏、再次单击取消、长按后默认夹为空；提示条进入原生选夹在上一候选包验证，截图 `captures/fav-official-890-*.png`。后续 Story 改动调整了提示条的上下文处理；加入取消关注兜底前的 8.90.2 候选包回归见第 19 节。宿主缓存调用仍因缺类无法完成，不把该结果推广到其他安装包。
- 关注相关补充核对：38 个样本均找到本次检查的作者关注按钮、空间关系包装和 `ViewModelStore` 键方法结构（矩阵 `2b191afa144c4d5a85b1ba84910d4e15`）；这不等于所有关注入口、动态调用和行为都已验证。此前「我的」关注数在 8.92–8.96 缺少现有精简复制方法，本轮增加保留全部属性的 Kotlin `copy$default` 形状：五个样本均唯一匹配 45 个实例字段和两个掩码（矩阵 `ace8c9578a4d41268ab59b8d6263ec22`）。单元测试验证两个掩码及原对象不变；这五版页面绑定与 Compose 重绘尚未实机验证，8.84–8.91 的旧 TextView 路径也未因此得到全版本实证。
- 最终门禁：Debug APK、4268 项 JVM 测试（0 失败、1 跳过）与快速 Lint 通过（262 条警告，3 条基线错误不变）；未重跑完整 Lint。最终包 SHA-256 `2081df92c39fb5cef8c63688a16e91cc8c375ae86bab6504a29f427298cd1b11`，与测试机安装包一致。测试机已恢复 B 站 9.16.1、无痕和免登录均关闭，宿主与模块进程停止。
- 当时的待办优先级：① 视频页取消关注保留宿主分组/确认流程；② 补测 8.84–8.96 刷新及关注数、其他版本的 Story，并逐项审计其余功能和宿主入口；③ 继续无痕网络及真实登录态验证。`incognito_privacy_status` 仍为 `partial`，不能声称无痕身份隔离或全部功能已适配全部版本。后续结果见第 19 节。

## 19. 2026-11-03：视频页宿主取消确认与旧版回归

- 视频作者在本地已关注时，再点关注按钮会显示宿主自己的单项确认菜单「确认取消关注该 UP 主吗？ / 取消关注 / 取消」。退出菜单不修改数据；确认后才显式移除本地关注，随后复用宿主成功回调、提示和关注广播。仅视频作者回调走此路径；其他关注入口仍沿用原逻辑。本地库目前没有关注分组和特别关注数据，所以这里展示宿主的单项确认，不展示完整分组菜单。
- GuestHostFollowConfirmation 识别宿主 FollowFlowHelper 的两套完整结构：8.84–8.96 样本是三个私有 q/w/y(Context)，8.97–9.17 样本是三个 public final a/b/c(Context)；调用宿主的 w/b 展示菜单，钩住确认后的 y/c，在它读取账号或调用 deleteAuthorAttentionV2 之前转为本地操作。非唯一结构不猜测。视频作者回调可识别但宿主方法结构、Hook 注册或显示失败时，使用模块自己的确认框；如果连视频作者回调也无法识别，既有按钮钩子仍沿用原来的直接切换。
- 弹窗记录会话令牌、作者 ID 和弱引用的原回调；确认时重新核对游客状态、会话、ID 与回调，过期或已复用的页面不写入本地库，也不放行旧确认进入网络。重复确认由弱键标记拦住；本地目标状态使用幂等设置，避免过期的确认误变成重新关注。不在进程级缓存中持有 Activity 或 View。
- HostLab 矩阵 2d33f3dd0ded450fbc7061b00e9def12：38 个非连续归档样本均完成上述方法、入口及取消网络调用的静态结构查询。矩阵 a744e5cd89f04aa690c745411b8aacc8 有一条附加字段 SQL 因 class_descriptor 不明确而失败，整批显示失败；从原始结果单独提取的另外两项查询在 38 个样本均完整：12 个 8.84–8.96 样本的视频作者回调为 OwnerService$b，26 个 8.97–9.17 样本为 OwnerService$a，且都声明了对应的登录和关注成功回调。上述是静态结构证据，不表示全部版本的动态流程均已验证。
- 根据上述证据，GuestVideoFollowInitialState 识别旧版 $b 和新版 $a，避免把旧版只是伴生对象的 $a 当成关注回调。单元测试覆盖两个代际、取消、确认、重复确认、会话失效、按钮复用、未知宿主方法结构、Hook 注册失败、宿主弹窗失败及兜底不可用。模块确认兜底本轮没有在真机强制触发。
- 9.16.1（第 10 节的官方 arm64 包）实测：关注后显示已关注；点按钮出现宿主确认，点「取消」保持已关注，重进视频仍为已关注；确认取消后按钮恢复「关注」，「我的」页关注数从 1 回到 0。状态键 guest_library_video_follow_confirmation=installed、guest_video_follow_confirmation=shown、guest_video_follow_confirmed=local 均出现。截图 captures/unfollow-20261103-candidate-*.png、captures/unfollow-20261103-reopen.png、captures/unfollow-20261103-mine-*.png；加入兜底后的最终包再次验证宿主菜单与确认取消，截图 captures/unfollow-20261103-final-*.png。
- 8.90.2（arm64，APK SHA-256 804ab5d719da42f257aac6237dda408151b93ecff71a0da99c5f32c71161a2e5，与对应 HostLab 样本相同）使用加入兜底前的候选模块（SHA-256 0ab26b3241d4becaf9576dec5c74ee52278b5ff9f2b5dabb7df7d178a9b5135e）实测了相同的关注、宿主确认、取消退出、重进保持和确认取消；旧版「我的」页关注数 1→0，guest_video_follow_initial=applied 与确认的本地处理状态均出现。截图 captures/unfollow-20261103-890-*.png。这不能推广为其余 8.84–8.91 版本的实机结论。
- 加入兜底前的同一候选模块在 8.90.2 补测了视频星标：单击收藏显示宿主提示条，长按及提示条均能打开宿主选夹；新建临时收藏夹返回后自动刷新并勾选，保存到两个夹后单击取消，再打开时两夹均为空。此版走已有的 GuestNativeFavoriteHooks，不能据此声称 8.84–8.96 中由新钩子打开的弹窗刷新路径已实测。
- 第 19 节的 Debug APK、4280 项 JVM 测试（0 失败、1 跳过）及快速 Lint 通过（262 条警告、3 条既有基线错误过滤），未重跑完整 Lint。APK SHA-256 76fa7c359ca20c138a2c6c3f784259c627b8f9c8c7f7192593ac6fb28f137663，当时与测试机安装包一致。构建限制为两个 worker、Gradle 堆上限 4096m；结束后停止守护进程，检查时无 Java 进程。
- 测试机已恢复 B 站 9.16.1，无痕及免登录恢复为原来的关闭状态，宿主与模块进程停止。无痕测试收藏夹只存在当时的进程内。当时下一步是本地关注分组、特别关注和视频作者回调回退；后续进展见第 20 节。其后补测 8.92–8.96 关注数、其他版本 Story、其他作者入口及真实登录态。无痕身份隔离状态仍为 partial。

## 20. 2026-11-03：作者回调更名兼容与分组入口调研

- `61a29125` 将视频页初始关注状态和取消关注确认共用同一识别边界：优先已验证的 `OwnerService$a` / `$b`，也接受同一 `OwnerService$` 内继承宿主 `FollowFlowHelper$SimpleCallback` 的其他回调；当已知类都不存在时，还要求作者服务类本身存在。其他页面、基类不符的回调不会命中。作者服务或回调基类也发生变化时，仍不能保证识别，继续沿用既有路径。
- 复查矩阵 `a744e5cd89f04aa690c745411b8aacc8` 中已成功的 `owner_callbacks` 查询：38 个非连续样本各有一个符合该作者服务前缀与基类的回调，其中 12 个是旧版 `$b`、26 个是新版 `$a`。改名场景由单元测试模拟，没有找到真实已改名的归档版本；不能据此声称未来版本均已适配。
- 小米 10 的 9.16.1、最终 APK SHA-256 `30db44685b783b0b431488792532abb0b7e67bc81c7fb73a950a61a6b8331e28`：确认无痕 Hook 已加载后，实测视频关注、打开宿主取消确认、取消后保持关注、重进保持关注和确认取消；日志出现 `guest_video_follow_initial=applied`、`guest_video_follow_confirmation=shown`、`guest_video_follow_confirmed=local`。截图 `captures/owner-callback-guest-*.png`。已安装 APK 与本地同哈希；8.90.2 此轮未复测，新回调名的分支仅经单元测试。
- Debug 构建、4284 项 JVM 测试（0 失败、1 跳过）和快速 Lint 通过，262 条警告、3 条既有基线错误过滤；未重跑完整 Lint。该轮构建将进程亲和性和 Java 可见处理器数限制为两个逻辑核心，最多两个 Gradle worker，Gradle 堆上限 4096m，Kotlin 在进程内编译。结束后 `--stop` 显示没有 Gradle 守护进程，检查时无 Java 进程。手机无痕和免登录恢复关闭，宿主与模块进程停止。
- 9.16.1 的远程 HostLab 反编译：`FollowFlowHelper.showCommonGroupMenu` 先通过 `getRelation` 读取是否特别关注，再显示「特别关注 / 设置分组 / 取消关注」；菜单回调打开 `AttentionGroupDialog`、处理特别关注或取消关注（请求 `9db72366684c4b3dbfa9b2cbc7baf95b`、`86723d70355243b59f3b22180ce4f360`、`fdd6468a5310445cba6351a33a57417e`）。弹窗支持新建分组并从页面结果刷新；`RelationService` 包含分组列表、成员、创建、分配和特别关注接口（`b7b5604fcac14e4284f3f4e589149a52`）。这些是单版静态证据，分组和特别关注的本地数据、协议、会话安全及多版本路径仍需实现验证；目前不开放无法保存的宿主完整菜单。

## 21. 2026-11-03：分组基础与隐私失败路径修复

- `cdee2c2e` 增加本地关注分组、成员关联及特别关注：普通游客库持久化，无痕会话仅存内存；取消关注和删除分组会清理对应关联，写盘失败不提交部分状态。新增数据使用版本 4 格式，仍可读取版本 1–3；旧模块不认识版本 4，会拒绝读取，使用分组数据后不要降级到旧模块并覆盖这份库。
- `51a40ccc` 按宿主响应形状增加本地分组协议，分组和特别关注选择在一次事务中保存；`cefa4652` 增加原始会话令牌、弱引用请求标记、请求克隆和过期页面的失败保护。这些是经过单元测试的基础组件，尚未连接宿主菜单、弹窗、创建页或 HTTP Hook；现有视频作者按钮仍使用取消关注确认，不能操作分组或特别关注。
- 38 个非连续归档样本的静态检查：菜单入口与模型矩阵 `a30dcee79d7a49d5b5c476c9c461dbb9`、回调形状 `a7394bf158ec4cca8ec8739679c8fe39`、页面生命周期和服务字段 `69a05f83a7054e8990cdac2de1e9a47e`、路由和请求方法 `aa70b92000ff41159b287962b1bd1e5c`、弹窗回调字段 `c8088fc2f9474d90a53267490facaad1` 全部完成。各样本均有所核对的菜单 / 弹窗回调、各一个服务字段与弹窗回调字段，以及所需路由和请求方法；这只是结构证据，不代表跨版本动态功能已经接通或实测。
- `fa0564bd` 修复本地收藏 / 稍后再看 HTTP 拦截的异常策略：此前默认框架保护模式可能在本地响应抛错后继续原始请求；改为向宿主交付 IOException，并在识别会话或请求失败时收口。9.16.1 本轮无痕会话里，收藏后宿主星标变色、原生选夹显示默认夹已选，稍后再看列表显示该视频；打开不存在的本地收藏夹显示加载失败并记录本地响应错误。失败时不回退原请求由异常策略与单元测试保证；这次没有抓包证明所有链路。
- `a7652a59` 修复实机发现的第三方统计地址：修复前在无痕播放带广告的视频时，日志显示一个第三方地址的路径带有疑似设备标识并被交给底层传输；不能仅据此判断服务端是否收到。该旧调试日志仍保存在手机的 LSPosed 日志中。本次仅拦截实测到的 `g.cn.miaozhen.com` 的 `/x/k=` 路径，并对该路径的调试日志脱敏；9.16.1 同类广告页面出现 `incognito_ad_measurement_okhttp=withheld`，视频继续播放。Ignet 的阻断和错误交付经单元测试，本次实机没有独立命中 Ignet。其余第三方地址没有完成普查，无痕状态仍为 partial。
- 门禁：Debug APK、4307 项 JVM 测试（0 失败、1 跳过）与快速 Lint（262 条警告、3 条已有基线错误过滤）通过；未运行完整 Lint。首次两 worker 并发分析时原有 `TrailingDebouncerTest` 的时间假设失败，改为单 worker 后全量重跑通过。构建限制两个逻辑核心、Gradle 堆上限 4096m、Kotlin 进程内编译，完成后守护进程已停止。测试机 APK SHA-256 `3e6525d0b21f555b082320701cb82c16619fc092d38627f9f55f82362117f45d`，无痕与免登录均恢复关闭，宿主与模块进程停止。
- 下一步：把会话绑定接入 `BiliCall` 执行 / 克隆和分组弹窗、新建页；保证弹窗恢复、身份刷新及游客切换后请求仍失败关闭，再开放宿主菜单。然后在 9.16.1 和 8.90.2 实机验证新建、选组、特别关注、取消关注及会话切换，并继续审计未覆盖的第三方请求。
