# 第三轮计划：免登录（guest browsing）多版本适配

## 全版本适配原则与待办（2026-11-04）

**目标**：无痕与免登录的每一项功能都要在 8.84.0–9.17.0 的 38 个归档样本上可用。「按稳定名 → 稳定配置键 → 形状唯一」定位、歧义即跳过（`handover.md` 第 9 节）只是实现手段，**不是把功能限定在某一版本的理由**；不允许用写死的单版本混淆名（如 `et1.c`、`CommentSubjectDescription.k()`）作为功能的实现边界。只有宿主本身不存在该入口，或结构已整体换成另一套实现时，才在写明宿主事实与替代路径后不作适配。

本节是现行计划。下方「核心事实」中「不是强行 100% 全版本，定位不到的版本整子功能干净跳过」是 2026-10-28 的实现取舍记录，已不再是本阶段目标；「已知缺口」「下一步」和按时间追加的证据记录是历史快照，其中「暂不排期」「没做」「干净跳过（无回归）」等表述一律以本节为准，不构成放弃。

### 必须补齐（当前只在部分版本生效或被干净跳过）

1. **评论游客上限**（`comment-guest-limit`）：仍写死 8.90.2 的 `CommentSubjectDescription.k()`；8.97+ 把 `guestLimit` 改成公开字段、无 getter，方法级拦截失效，26 版干净跳过。待办：做字段级方案（定位读取点内联处），跨 38 版核对后实机触发。取消「暂不排期」。（批次 2 记录；`GuestBrowsingFeatureInstaller.kt` 的 `comment-guest-limit` 注册）**【11-04 已实现构造参数方案；11-05 经矩阵 `a5738826` 扩为 Java + KMP 双模型，9.16.1 实机确认 `models=java,kmp` 且 KMP 路径触发，8.90.2 确认 hook 注册；页尾 footer 与分页解锁仍待逐项确认，见「2026-11-05 实机验证」】**
2. **动态页底部登录条**（`dynamic-login-row`）：仍写死 8.90.2 的 `et1.c`；9.16.1 上 `dy_login_bottom` 字符串已消失，9.x 干净跳过。待办：先判定 9.x 是该 UI 被移除、改名还是换成 Compose，再按 38 版重新定位；字符串消失不等于功能不存在，不能因旧名失效就只留一个版本。（批次 2 记录；`handover.md` 第 6 节第 7 条）**【11-04 判定完成、11-05 真机确认：9.x 换成 `DynamicNotLoginFragment` 未登录整页引导，无底部条；稳定类 38/38 存在但隐藏会改变整页布局，补路径仍待实现，见「2026-11-05 实机验证」】**
3. **引导页 8.84–8.96 共 12 版**：首启等待门在 `ApiManagerKt` 与另外 3 个同形静态 `()Z` 并列，按形状分不出，仍靠旧插件层且未验证；8.97 起 26 版已由 `56060759` 结构定位。待办：按调用栈或方法体指纹定位，或统一走 38/38 稳定的 `PegasusResponse.getInterestChoose()`。（`handover.md` 第 5、6 节）**【11-04 已实现：`GuestOnboardingHooks` 按两代精确形状分流，8.84–8.96 走 `ApiManagerKt` 四选一、8.97+ 走四类中唯一一个，见「2026-11-04 静态销账」；11-05 在 8.90.2 实机确认 legacy gate 装并触发，见「2026-11-05 实机验证」】**
4. **「我的收藏」入口 8.97–9.11 共 16 版**：`GuestMineMenuHooks` 的收藏目标只在 9.12–9.17 登记，其余版本保持宿主行为；`GuestFavoritesRouteHooks` 仅在 9.0.0 实机绕开登录门，其余 15 版只有静态结论，原文明确「不交付只有静态证据的实现」。待办：做不依赖 `compose/favorites/kntr` 的通用放行方案，并在其余版本实机验证目标页能显示本地数据。（「8.97 到 9.11 的『我的收藏』入口调查」；`handover.md` 第 5 节）**【11-04 静态核对完成：通用放行方案 `c15fd96a` 已存在，其锚点 `FavoritesActivityV2`、`BLRouter.routeTo` 两个重载、`RouteResponse(Code, Request)` 构造器、`user_center/favourite` 字符串均 38/38 成立；待办收敛为补其余 15 版实机证据；11-05 的 9.0.0 因模块未注入未能复测，见「2026-11-05 实机验证」】**
5. **联合创作按钮 `com.mall` 变体**：商城视频页同名 `VerifyLottieFollowButton`，本次状态键为 `+bili,-mall`，真实触发待验证。待办：确认是有意排除还是未覆盖，覆盖后纳管。（`handover.md` 第 24 节）**【11-04 已销账：代码已纳管，9.16.1 的 `-mall` 是宿主类不存在的正确结果；商城页真实触发仍并入第 7 项，见「2026-11-04 静态销账」】**
6. **`guest.follow-button` 8.84 一个重载**：批次 1 记「已 37/38，只差 8.84 一个重载，补上即可」，文档无销账记录。待办：确认是否已由后续 38 版结构核对覆盖，未补则补齐。（批次 1 记录）**【11-04 已销账：代码早已按形状 hook 每个构建声明的全部重载，批次 1 只是漏记，见「2026-11-04 静态销账」】**
7. **跨版本实机验证**：以下已按版本分流、静态覆盖 38 版，但实机只有 9.16.1 / 9.0.0 / 8.90.2 三版——8.84–8.96 星标单例写入口与宿主弹窗「新建夹返回刷新」、8.92–8.96 关注数 `copy$default` 与 8.84–8.91 旧 TextView 路径、稍后再看添加、关注列表双实现、作者回调与取消确认双代际、空间页关系类双命名、分组菜单、联合创作按钮。待办：补其余版本证据，清单见 `handover.md` 第 5、6、18、19、23、24 节。**【11-05 已补 9.16.1 与 8.90.2 两版的状态键/覆盖日志证据；9.0.0 模块未注入，其余版本仍缺，见「2026-11-05 实机验证」】**

### 有理由不按全版本适配（写明宿主事实，不算放弃）

- **海外强制登录 8.84 / 8.86**：宿主这两版没有 `HomeLoginExpKt` 的静态 `()Z` 入口，属于「旧版本根本没有这个东西」；其余 36 版由形状唯一定位覆盖。（批次 2 记录；`GuestBrowsingFeatureInstaller.kt`）
- **`force-login-toast` 的 `lp3.a.run` 第二层**：只是 8.90.2 附加层；主体已改为按 `BLRemoteConfig.getBoolean/getInt` 配置键拦截，38/38 覆盖，不构成单版本功能。（批次 2 记录）
- **`NormalMediaHistoryStorage` 在 9.15+ 的 6 版整类消失**：宿主改走另一存储类，属结构整体替换；若播放记录在这些版本出现缺口，按新存储类补一条路径，而不是把功能标为跳过。（批次 1 `guest.playback-history`）
- **联合创作按钮 `com.mall` 变体在 9.0.0 起 23 版整类消失**：`com.mall.videodetail.vd.videopage.common.widget.view.VerifyLottieFollowButton` 只存在于 8.84–8.99 共 15 版，宿主 9.x 不再有商城视频页的同类按钮；`GuestLottieFollowButton.BUTTONS` 已含 mall 且在类存在时尝试 `anchors()`，因此 9.16.1 的 `-mall` 是宿主事实，不是未覆盖。（矩阵 `4610f0e8`、`9b1ddfac`）

### 2026-11-04 静态销账（矩阵 9b1ddfac / 20d5ad0e / 4610f0e8 / follow-ui-compat）

本轮在 38 个归档样本上跑了两个新矩阵（`9b1ddfac…` 查第 2、5 项；`20d5ad0e…` 查第 1、3、4 项），并复用已缓存的 `4610f0e8…`（联合创作按钮）与 `follow-ui-compat-rows-*`（关注按钮）。结论只属于静态结构，不等于运行时功能已验证。

- **第 6 项 `guest.follow-button` 已销账**：`GuestLibraryHostHooks.kt` 的 `follow_button` 按形状 hook 每个构建声明的全部 `FollowFlowHelper.onButtonClick` 重载。38 版只有两种组合：8.84 仅 `(Context,String)`，其余 37 版同时有 `(Context,String,Rect)`；`c:J`（mid）与 `i:FollowFlowCallback` 字段 38 版同名同形。批次 1 的「只差 8.84 一个重载」早已被这段实现覆盖，文档此前只是漏记销账。
- **第 5 项 `com.mall` 变体已确认，不是漏覆盖**：`com.mall` 同名按钮只在 8.84–8.99 共 15 版存在，9.0.0 起 23 版整类消失，故 9.16.1 的 `+bili,-mall` 正确。8.84–8.96 的 mall 回调接口 `view/g` 声明 `onFollowSuccess()`，与 bili 结构平行，可命中 `anchors()`；8.97–8.99 的 mall 类仍在但回调接口换包/改名（`aV0/d` 等），且 8.97 与 9.17 连 bili 侧都未查到 `onFollowSuccess` 的 owner——`GuestLottieFollowButton` 依赖该形状，这两处需并入第 7 项实机/结构复核。商城视频页真实触发仍待验证。
- **第 2 项判定：9.x 是换成另一套实现，不是简单改名**：`dy_login_bottom` 字符串与 `et1.c` ViewHolder 在 9.x 不再承载该 UI；`et1.c` 只在 8.84–8.91 的 11 版存在。`com.bilibili.bplus.followinglist.page.nologin.DynamicNotLoginFragment` 与 `DynamicNotLoginViewModel` 在 38/38 版稳定存在，是未登录动态页（Compose 推荐流）的载体，不是底部登录条的等价 UI。待真机确认 9.x 游客态动态页是否仍有等价登录提示条，再决定是否基于稳定类补一条路径；在此之前不因旧名失效就把功能标成已完成。
- **第 1 项字段级方案已定位**：`CommentSubjectDescription` 在 38/38 版都有 12 个同序字段，唯一 `Z` 布尔字段是 `i`（8.84–8.96 私有 final，8.97 起公开 final），`<init>` 38/38 存在且恰好一个 boolean 参数（对应 `guestLimit`）；`k()Z` getter 只在 8.84–8.96 有，8.97+ 消失，`toString()` 在 38 版都含 `, guestLimit=`。因此可在构造前把唯一 boolean 参数置 false，宿主无论走 getter 还是内联字段读取都拿到 false，不依赖 `k()` 或字段名，覆盖全 38 版。（11-05 更正：这节只查了 Java 模型；8.96 起评论区主路径另用 KMP 模型，需双模型齐备才覆盖 38 版，见「2026-11-05 实机验证」。）
- **第 3 项门定位（已实现）**：38/38 版 `PegasusFragment.onCreate` 都恰好调用 `com.bilibili.pegasus.request` 包内一个静态 `()Z`：8.84–8.96 是 `ApiManagerKt.s()`（7 版）或 `ApiManagerKt.r()`（5 版），8.97+ 是 `request.a/b/c/d` 的 `f/g/h()Z` 之一。两代布局互斥且可精确验真：8.84–8.96 的 `ApiManagerKt` 恰好 4 个同形静态 `()Z`，门总是按名字序最小的一个（12/12 版验证）；8.97+ 四类合起来恰好 1 个。`GuestOnboardingHooks` 按这两种精确形状分流，歧义即跳过，不硬编码 `s`/`r` 等具体混淆名，覆盖全 38 版。`PegasusResponse.getInterestChoose()` 也在 38/38 版稳定。
- **第 4 项锚点 38/38 静态成立**：`FavoritesActivityV2`、`BLRouter.routeTo(RouteRequest,Context)/(RouteRequest,Fragment)`、`RouteResponse(Code,RouteRequest)` 构造器、`user_center/favourite` 字符串在 38 版全部存在；`GuestFavoritesRouteHooks`（`c15fd96a`）本身就是不依赖 `compose/favorites/kntr` 的通用放行方案。该项待办收敛为「补其余 15 版实机证据」；本机只有 8.90.2 / 9.0.0 / 9.16.1 三份宿主 APK，其余归档样本只有静态索引，不能凭静态证据宣称已交付。

### 2026-11-05 实机验证（9.16.1 / 8.90.2 / 9.0.0）

用本轮新构建的 Debug APK（SHA-256 `640faeab…`）在默认测试机 `192.168.1.78:5555` 上，通过模块界面打开「免登录浏览」+「无痕模式」，冷启动后按当前宿主 PID 读 LSPosed 模块日志。证据只属于对应 APK 与实际加载过的路径。

- **9.16.1（pid 17046 / 19821，两次冷启动一致）**：`incognito_privacy_status=partial`、`guest_browsing_status=partial:6/10`，覆盖日志 `hooks=6/10; missing=force-login-toast, dynamic-login-row`。与 11-03 的 `hooks=5/10; missing=…, comment-guest-limit, dynamic-login-row` 相比，**`comment-guest-limit` 已不再缺失**，第 1 项的构造函数 hook 在 9.16.1 注册成功；`guest_onboarding_gate=installed` 且 `guest_onboarding_gate_applied=seen`，`guest_lottie_follow_targets=+bili,-mall`，`guest_favorites_route=installed`。
- **8.90.2（pid 20011）**：`guest_browsing_status=partial:10/10`、覆盖日志 `hooks=10/10; missing=`（10 个入口全装，含 `comment-guest-limit` 与 `dynamic-login-row`）；**`guest_onboarding_gate=installed` + `guest_onboarding_gate_applied=seen`**，这是第 3 项 legacy 路径在 8.84–8.96 段的首次实机证据（旧代码该版本报 `absent`）；`guest_lottie_follow_targets=+bili,+mall`，坐实第 5 项「mall 类存在时已纳管」。
- **第 2 项真机判定**：9.16.1 游客态「关注」页显示整页「你还未登录 / 登录账号，查看你关注的UP主内容」+ 登录按钮，下方是「猜你喜欢的UP主」推荐流，**没有列表底部登录条**。与静态结论一致：9.x 换成 `DynamicNotLoginFragment` 未登录整页，旧 `et1.c` ViewHolder 不存在。该引导是宿主整页内容而非可摘除的列表项，隐藏会改变整页布局，故先不基于稳定类做隐藏；保留为待办，待确认新页可安全定位的锚点后再补路径。
- **9.0.0 环境限制**：覆盖安装 `captures/host-900-base.apk` 后，连续三次冷启动（含打开模块界面重新发布配置）该进程在模块日志中均为 0 行，模块未注入，无法复测第 4 项的 9.0.0 收藏入口。9.16.1 与 8.90.2 覆盖安装后注入正常，判断是该 9.0.0 base APK 与本机 LSPosed 作用域/签名缓存的问题，不是代码回归；第 4 项「其余 15 版实机证据」仍未补齐。
- **触发证据（批次 2 待办之一）**：`GuestBrowsingFeatureInstaller` 现在给每个注册的入口和评论构造函数 hook 各报一个独立的 `guest_browsing_<path>_applied=seen` 状态键，共享的 OBSERVED/APPLIED 证据只保留第一次、无法分辨哪个入口触发过的问题已解决。9.16.1 播放视频后实机看到 `guest_browsing_force_login_toast_config_applied=seen` 与 `guest_browsing_guest_quality_cap_config_applied=seen`。
- **第 1 项从「只注册不触发」修成双模型生效**：加上触发状态键后发现 9.16.1 打开评论区只有 config 类证据，`guest_browsing_comment_guest_limit_applied` 始终不出现。矩阵 `a5738826`（38 版）查明原因：8.96 起 27 版评论区数据由 KMP 模型 `kntr.common.comment.common.model.SubjectDescription` 承载（`kntr.common.comment.common.data.b.c(KSubjectDescriptionReply)` 构造），Java 的 `CommentSubjectDescription` 仍被旧数据源 `com.bilibili.app.comment3.data.source.v2.b` 构造但不再是评论区主路径；两套模型的 `toString()` 都含 `, guestLimit=`。两者都是 Kotlin data class，字段与构造参数顺序一致，`guestLimit` 是第一个 `Z` 字段/参数（9.01+ 多一个 `Z` 字段/参数，仍一一对应）。改为对两个模型分别按「boolean 字段数 == boolean 构造参数数」验真后 hook 第一个 boolean 参数，实机 9.16.1 报 `guest_browsing_comment_guest_limit_models=java,kmp`，打开评论区后 `guest_browsing_comment_guest_limit_kmp_applied=seen`（08:45:57），证明 KMP 路径真正触发。评论区页尾「仅展示本 App 中可查看的评论」footer 在触发前后都在，该文案是否由 `guestLimit` 控制、分页是否解锁仍未逐项确认，不宣称 UI 已改变。
- **门禁**：本轮 Debug APK、全量 JVM 单测（0 失败）、FastLint（262 警告、3 条既有基线错误过滤）通过；未运行完整 Lint。

## 当前进度与推荐顺序（2026-11-04）

- 普通视频页的游客关注已接上宿主菜单、特别关注和本地分组；9.16.1 实测分组新建 / 保存 / 回显及关注确认，8.90.2 实测分组新建 / 保存 / 回显。收藏和稍后再看已有宿主原生入口及本地响应，覆盖范围和历史结论见交接文档。
- 联合创作「创作团队」卡片的 Lottie 关注按钮已接管（`c3a98c95`）：9.16.1 实测双成员关注、弹层回显、行内取关与重新关注，无登录框、无 relation / attention 网络调用，见交接文档第 24 节。
- 相关结构在 8.84–9.17 的 38 个非连续 HostLab 样本中做了静态核对；实机结果仅属于对应 APK 和实际执行过的路径，不能说全部发行版本已完成动态适配。
- 下一步先补 11-04 这块的验证闭环：8.90.2 真机同样的点击链路（本机只装了 9.16.1，其余样本均为静态核对）、登录账号回归、`com.mall` 变体的真实触发，以及外部取关后按钮停在 ✓ 帧的残留确认。
- 随后补分组弹窗打开期间的身份 / 无痕模式切换、页面恢复、普通游客库重启持久化，8.90.2 分组菜单的特别关注与取消确认逐项复测，以及其他版本和登录账号的回归；继续审计未覆盖的第三方请求。无痕状态仍为 `partial`。
- 下方旧状态、缺口和推送记录是当时的历史快照；当前证据、限制和进度以 `docs/incognito-no-login-handover.md` 第 23、24 节及后续章节为准。
> **2026-10-30 20:20 交接**：最新状态、官方原生与模块自有界面的清单、验证矩阵和剩余工作以 `docs/incognito-no-login-handover.md` 为准。本文件是按时间追加的证据记录，开头几节（2026-10-28 的状态与缺口表）已经过时，后半部分才是最新证据。

状态（2026-10-28 20:05 更新）：批次 0、1、1.5 完成；批次 2 主体完成但有覆盖缺口；批次 3 进行中：9.16.1 的「我的」页与三个资料库入口（收藏、稍后再看、历史）已通到宿主原生页；收藏页与稍后再看页已能显示本地数据（`b52e534a`）；写入只有视频页星标一处；批次 4 未开始。**2026-10-29 用户实测发现：收藏体验在 9.16.1 相对 8.90.2 回归（没有收藏夹选择菜单、星标不变色、部分入口点收藏无效），已升为最高优先级，见「已知缺口」第 0 条与「下一步」第 1a 条。**

分支 `feature/incognito-no-login`，远端 `fork`。**已全部推送，tip `b52e534a`（2026-10-28 20:00 推送，本地与远端一致）**，其中最后五个提交是：
- `d7052286` chore: report each guest library entry under its own status key
- `16cde2a7` fix: show the guest mine page instead of the forced login overlay
- `41a2ac2b` feat: let guests open the library rows on the Compose mine page
- `bc99b979` feat: let guests favorite a video from the star on the video page
- `b52e534a` feat: answer guest favorites and watch later lists from the local library
推送需要单独授权，只推功能分支，不推 main。

## 一眼看懂：哪些东西真机验证过

真机只有一台（9.16.1），其余 37 版只有 hostlab 静态证据。「真机验证」只对 9.16.1 成立。

| 子功能 | 真机 9.16.1 | 其他版本 |
|---|---|---|
| KMP gRPC 身份清洗与写操作拦截（批次 1/1.5） | 已看到 | 静态 38/38 |
| Ktor/ignet HTTP 封口、KMP 埋点丢弃（1.5） | 已看到 | 静态 38/38 |
| 关注按钮、历史页本地应答、播放记录（批次 1） | 已看到 | 静态 38/38 |
| 登录弹窗、一键登录引导、登录 toast、画质钳制、海外强制登录（批次 2） | 只确认「已注册」，**没有触发过的证据** | 静态 |
| 引导页（欢迎向导）跳过（批次 2 + 修复 02bb9377） | **已验证，连续 3 次冷启动不再弹出** | **仅 9.16.0/9.16.1 生效**；其余 24 个 V2 版本不生效，8.84–8.96 靠旧钩子未验证 |
| 「我的」页不再被强制登录盖层遮住（`16cde2a7`） | **已验证**（`guest_mine_overlay_applied=seen`，截图完整） | 静态：V2 页在 8.92–9.17；其余版本未覆盖 |
| 收藏、稍后再看、历史三个入口对游客打开宿主原生页（`41a2ac2b`） | **已验证**：三页都能打开、无登录页 | 静态：V2 菜单项 8.92–9.17；收藏改写目标只在 9.12–9.17 登记；8.97–9.11 的收藏没有方案 |
| 视频页收藏星标：游客点星标写入本地默认收藏夹，不弹登录（`bc99b979`，**简化版：没有收藏夹选择菜单，星标不变色**，见缺口第 0 条） | **已验证**：`guest_video_favorite_applied` 与 `_saved` 先后出现，无登录页，在全新视频 av100、av200、av80433022 上各一次；原提交星标外观不重绘；`d041d941` 起 9.16.1 上点一次变粉、再点一次变灰，且不再弹 Toast（状态键 `_state`、`_repainted`），收藏数不变。 | 静态：`Router$RouterProxy` 的 `open`/`getMExtras`/`getMData` 38/38 同形（q30），`getMContext` 38/38 同形（q31，20:00 补查；此前提交说明先写了结论后查证）；`key_prompt_scene` 38 版都有使用者 |
| 收藏夹选择菜单、星标变色（8.90.2 上由旧钩子 `GuestNativeFavoriteHooks` 完整接管：弹层选夹、提示条、星标重绘） | **9.16.1 没有**：该钩子 `guest_library_native_favorite=missing`，锚点类（`KingPositionService.B0/C0/V0`、`FavoriteDialog.d0/D` 等）已改名或消失；9.16.1 的弹层是 KMP 组件 | 8.84–8.96 旧弹层可能仍可用，**未验证**；注意 `bc99b979` 的路由钩子在 38 版都会装，与旧钩子是否冲突未验证 |
| 收藏页、稍后再看页显示本地数据（`b52e534a`） | **已验证**：游客星标收藏的视频出现在「收藏夹」默认收藏夹、「全部」页和收藏夹详情页（创建者「本地账号」）；稍后再看页显示宿主自己的空状态「暂无稍后再看视频哦」，不再「加载失败」 | 静态：Ktor 引擎对 `IgHttpResponse` 只调 `httpCode/httpMessage/protocol/headers/readDataV2/close`，38/38 一致（q32）；`IgHttpRequest.execute/url/method` 38/38 同形；但 8.84–9.11 的收藏页走哪条通道未查 |
| 其余本地库入口（关注数/关注列表、稍后再看添加、收藏 REST 等） | 旧钩子 **只 3/15 装上**，其余仍缺 | 未测 |
| 长跑稳定性（21 分钟） | 堆平稳 83–89MB，无崩溃 | 未测 |

## 已知缺口（按重要性）

0. **【回归，最高优先，2026-10-29 用户实测】收藏体验在 9.16.1 不完整。** 8.90.2 上正确的行为是：点星标弹出收藏夹选择菜单，选完写入本地库，星标变成已收藏的颜色，并有提示条。现在 9.16.1 上：(a) 没有菜单，点完直接写默认收藏夹并弹"已保存到本地"；(b) 星标不变色、数字不变；(c) 用户报告从历史记录进入的视频点收藏无效。原因：完整接管由旧钩子 `GuestNativeFavoriteHooks` 做，锚点全是 8.90.2 的类，9.16.1 上装不上；`bc99b979` 只是路由层的简化顶替品。待实测的点：(c) 的成因目前只是推测（元数据没记录则钩子放手），尚未复现；2026-10-29 早上我曾记录"首页推荐流视频点星标也没有任何日志"，**该证据作废**：当时的辅助脚本 `tapshot.ps1` 把参数命名为 `$Input`（PowerShell 内置变量），点击命令从未发出，那几次其实没点；星标变色是否只写 `com.bilibili.community.favorite.e` 就够也未验证，宿主自己的 `RelationRepository.n(boolean)` 还会改收藏数并发 `Violet` 消息。

1. **读取通了，写入只有一处**：收藏页与稍后再看页现在由本地库应答（`b52e534a`：请求仍发出，无痕下是清洗后的匿名请求；模块替换引擎读到的状态码、头和正文）。仍缺：稍后再看「添加」（所以列表永远是空的）；这两页里的写操作（删除、移动、新建收藏夹等走 POST）现在原样发给服务端，匿名会失败，本地库不会变，只保证不谎报成功；历史页「有记录时显示」未验证。另有：关注数（`W$r.a` 直接路由 `activity://main/login/`）、关注列表等入口仍缺。
2. **引导页修复只覆盖 2 个版本**：首页等待门的类名每版不同，9.03–9.14.1 的 `PegasusFragment.onCreate` 甚至不读这类谓词。要扩需要新思路，风险高。
3. **批次 2 多项只有「已注册」证据**，没有「真的触发过」。需要给这些钩子加独立的触发状态键，并在无痕下走一遍对应场景。
4. **评论游客上限、动态页底部登录条**：9.x 上干净跳过，未做。
5. **KMP 广播隧道（在线状态）仍开放**：原生层不容忍 Java 侧干预，试过会崩宿主。
6. **登录用户的行为只有单测**，真机没验证过。
7. 合并或发布前要补跑一次完整 `lintDebug`（日常只跑了 FastLint，会跳过库 API 替换建议）。
8. 小清理：`GuestLibraryHostHooks.kt` 有一处更早提交遗留的 ` · ` 中点分隔（用户明确不喜欢），未动。

## 下一步（建议顺序）

0. 【已定】`native_favorite`、`native_list_http`、`video_menu_watch_later` 依赖的类正是批次 4 的锚点，三项并入批次 4，合并成「收藏与列表」批次，批次 3 只做 A 类。
1. 【已完成，仅 9.16.1 实机】「我的」页本身与收藏、稍后再看、历史三个入口（`16cde2a7`、`41a2ac2b`）。
1a. 【基本完成，2026-10-29 14:50，已推送到 fork 的 `feature/incognito-no-login`，顶端 `99b51a9a`】完整收藏体验的 9.16.1 实机项都已通：视频页收藏夹选择框（`c165bee1`）、星标变色（`d041d941`）、Story 竖屏播放器星标（`bc93ea64`，这就是“历史页入口点收藏没反应”的根因）、8.84 到 8.96 的星标变色（`99b51a9a`，仅静态）。**视频页收藏数已随收藏变（`422c9923`，9.16.1 实机 4858 到 4859 再回 4858，见文末“收藏数”一节）。还没做的：Story 的收藏数不变；选择框/Story/单例路径/页面仓库在 9.16.1 之外只有静态核对；8.90.2 上旧钩子与新钩子是否冲突未装机验证。** 以下是更早的进度记录（保留）：**（11:25）：2c 收藏夹选择菜单已做并在 9.16.1 实机通过：不接管宿主混淆的 KMP 弹窗，改在星标钩子里弹模块自己的多选框（`GuestFavoriteFolderPicker`，标题「收藏到」，勾选收藏夹，「完成」「新建收藏夹」「取消」）。实机走通：弹出、新建 `trip`（回到弹窗并自动勾上）、勾两个夹点完成星标变粉、再点弹窗记得两个都勾着、全取消点完成星标变灰；状态键 `_picker`、`_saved`、`_repainted` 都出现，无 `_failed`。单测 23 个（含 8 个新）；变异检查（永远变亮）恰好让「清空则变灰」一条失败；全量 4160 个测试 0 失败，FastLint 0 错误 262 警告。没有 Activity 可挂弹窗时退回写默认夹。收藏数仍不变。**此前进度：2b 星标变色已提交（`d041d941`）；38 版本静态矩阵 q40/q42 已跑：8.97.3 到 9.17.0 共 26 版定位条件成立，8.84.0 到 8.96.4 共 12 版会退回 Toast，见「星标写入口 38 版本矩阵」一节。2a 在首页入口与历史页入口都没有复现“点星标无效”，Story 竖屏播放器与从未记录过元数据的视频还没试。** 补齐 9.16.1 的完整收藏体验，顺序固定：**2a** 在机上复现"点星标无效"（区分首页入口与历史入口，看 `_applied` 是否出现、元数据是否取到）；**2b** 星标变色与收藏数（先实测"只写缓存"够不够，不够再走 `RelationRepository` 的更新入口）；**2c** 收藏夹选择菜单（9.16.1 的 KMP 弹层 `kntr.app.favorite.favoriteDialog`，入口 `KingPositionService.k(boolean)`，是整批里最硬的一块，不给小时数）。每一步先在 hostlab 跨 38 版确认锚点，再装机验证。同时核实 8.84–8.96 上旧钩子与 `bc99b979` 路由钩子是否冲突。
2. 【已完成第一块，仅 9.16.1 实机】写入侧：视频页收藏星标（`bc99b979`，简化版）。下一块写入：稍后再看添加（视频页菜单、收藏弹层里的稍后再看按钮、`kntr.watch.later.watchlater.api.g` 的 `/x/v3/fav/toview/adds`，各处分散），以及收藏弹层里选收藏夹。思路同上：在稳定的路由或 API 边界上识别游客动作，写本地库，不碰混淆类。
3. 【已完成，仅 9.16.1 实机】读取侧（`b52e534a`）：接缝是稳定的 `IgHttpResponse` Java 方法，不碰 Ktor 混淆类。下一步是同一接缝上的写操作：在发出前改掉 POST（否则匿名请求白白发到服务端），并由本地协议应答删除、移动、新建收藏夹。
4. 其余入口：关注数（`mineV2.W$r.a` 直接路由 `activity://main/login/`，且本地关注列表依赖已消失的 `BlackListActivity`）、关注列表；8.97 到 9.11 的收藏目标（星标点击已由 `GuestVideoFavoriteHooks` 覆盖，但“我的收藏”页入口在这些版本仍没有方案）。**8.84 到 8.96 的旧类钩子已有结论（q47，2026-10-29）：只在 8.90.2 一个版本能装上，其余版本靠新钩子。**
5. 回头补批次 2 的「触发证据」，以及引导页修复扩到其余 24 个 V2 版本。
6. 合并前补跑完整 `lintDebug`。

证据记录在本文件后半部分，按时间追加；以本节为准。

## 提交清单

批次 1（已推送）：`f67efbeb` 关注按钮、`543ce25e` 播放封口 8.97+、`49a1b3b1` 历史预检、`03d4f488` KMP gRPC 边界、`cb84d6fd` KMP 播放记录、`a4ee8222` KMP 历史本地应答、`e19a3341` 流量审计探针。重排前的原顺序留在 `backup/b1-before-reorder`。
批次 1.5（已推送）：`6bfac3fd` KNeuron 丢弃 + 首次加入 `biligame.net` 名单、`cd26a446` Ktor/ignet 封口。`6bfac3fd` 的 `biligame.net` 名单项导致宿主堆暴涨，由 `8661036e` 修复。
批次 2（已推送）：`f26636f0` one-pass 引导、`3e9e03de` 引导跳过（旧实现，仅旧宿主有效）、`c590b28b` 登录提示配置键。
之后：`59a18d64` 播放类预检、`9f2dbb02` 被扣请求的调试日志、`8661036e` 移出 biligame 名单（均已推送）。
`02bb9377`、`d7052286` 已于 12:50 推送。

全量门禁（最近一次，HEAD bc99b979）：4129 测试 0 失败，lintFast 262 警告（未增，改动文件无新增）。

### 批次 1 旧进度记录（保留）
- 全量门禁（当时）：4077 测试 0 失败，lintFast 262 警告。
- 真机仅 9.16.1：KMP 写操作拦截、身份头清洗、历史页本地应答、播放后出现本地记录（含进度）均已看到。其余 37 版仅 hostlab 静态证据。

### 批次 1.5 — 宿主 KMP 通道（新增，发现于批次 1）
- 9.x 大量请求绕过 Java `MossServiceImp` 与 OkHttp，原无痕边界看不到。
- [x] KMP gRPC（`kntr.base.moss` `GrpcEngine` + `MossInterceptor` 链）：已封口，38/38 结构可解析，不可路由则无痕拒绝启动。
- [x] Ktor / 原生 ignet HTTP：已封口（`IgnetHttpBoundary` 在 `IgHttpRequest.Builder.build()` 清洗 URL+头，在 `execute()` 按 `blockHttp` 扣下埋点/上报并以 IOException 交回宿主）。`IgHttpRequest` 稳定 API，38/38 存在；`IgnetHttpEngine`→`IgHttpRequest.execute` 唯一链路，38/38。真机 9.16.1：`incognito_transport_ignet_http=sealed`，冷启动+播放宿主稳定。
- [x] KMP Neuron 埋点：`KNeuron` 的 report/track 生产者与 Java Neurons 一样在入队前丢弃（它自带 Room 队列，只扣上传会延后）。38/38 存在，名字稳定（8.84 有 8 个，9.16.1 有 10 个）。
- [x] OkHttp 埋点主机名单补入 `biligame.net`。
- [x] 审计探针（`KtorTrafficProbe`、OkHttp host+path 日志）改为仅 debug 构建。
- [ ] KMP `StreamClient` 广播隧道（`/bilibili.broadcast.v1.BroadcastTunnel/CreateTunnel`，走原生 `RpcEngine`）仍开放。尝试过：扣下 `createRpc` 返回 0 句柄 → 宿主不处理该异常，SIGABRT；只拒绝隧道 `asyncBidiStreamCall` 返回 false → 原生栈 SIGSEGV。原生广播层不容忍 Java 侧干预，已从无痕移除（宿主稳定优先）。隐私影响是在线状态/推送 presence，低于已封的身份与历史。待查：是否能在 KMP header provider 层清洗，而非掐断隧道。该路径的 `RpcEngine.createRpc(3-arg)` 与隧道 path 38/38 唯一归属 `Tunnel_androidKt$createTunnel$3`（8.84–8.91 归属 `stream/q`）。
- [x] 懒绑定锚点（`IPlayerCoreService.getCurrentMediaItem`、`MediaItemParams` 构造）38 版评估：38/38 齐全；`NormalMediaHistoryStorage` 仅 9.15+ 的 6 版缺失（本就不存在）。预检在 `59a18d64`：安装前一次解析所有播放类，缺任何一个整块跳过。

本文件是从本地的 `captures/compat-incognito/round3-plan.md`（被 `.gitignore` 忽略）拷进 `docs/` 的快照，2026-10-30 20:20。文中提到的 `captures/` 脚本和 hostlab 产物只存在于原机器上。所有版本数据来自 `captures/compat-incognito/` 的 hostlab 矩阵（38 版 8.84.0–9.17.0，plan sha `dbad84e2...`）。

## 核心事实

- 8.97.0 起宿主混淆大幅加重，覆盖 38 版中的 26 版：不仅混淆短名类（`gh6.f`、`lp3.a`、`rt3.a`、`te6.b`、`fa6.b`、`hv2.a` 等）每版漂移，连有正式类名的方法也被改名（`KingPositionService.B0/C0/V0`、`HomeUserCenterFragment.jr/Ur/lr`、`MinePageManager.c/h`、`WatchLaterManagerV2.j/i/u` 等全部变成单字母且逐版不同），个别类整体消失或改名（`PlaySetEditorStateMachine`→`M`，`NormalMediaHistoryStorage` 在 9.15+ 不存在）。
- 因此每个锚点只有三条适配路径：
  - (A) 改用稳定锚点：gRPC/protobuf 的方法名（`historyTab`/`cursorV2`/`read`/`write` 等）、公开 API、Android 平台类——最便宜。
  - (B) 加 DexKit 结构特征（`DexAssistQuery`）：用字符串常量 + 方法 ABI 形状唯一定位，运行时用宿主 ClassLoader 二次验真。遵循项目既有约定（`DexAssistEngine`/`DexAssistSession`，见 `hook/adapter/dex`）。贵且需逐版验证唯一命中。
  - (C) 从稳定邻居结构推导：如某稳定类上"唯一返回某类型/参数形状"的方法。
- 免登录安装器本就逐 Hook 软失败（缺锚点只是界面不显示，不泄漏），所以第三轮的现实目标是：**能安全唯一定位的版本上适配，定位不到的版本整子功能干净跳过（不崩溃、不半装）**，而不是强行 100% 全版本。对无法形成强特征的锚点，宁可跳过也不猜。
- 每个子功能都要像第 1 步那样做"全有或全无"预检：子功能所需锚点全解析成功才注册，否则整块跳过并在诊断标 `not-applicable-host`。

## 批次安排（按依赖与难度排序，每批独立可验证、可提交）

### 批次 0 — 基础设施（无需测试机）
- 建 `PrivacyStructuralResolver` 共享层：把 (A)(B)(C) 三类定位收敛到统一边界，接入既有 `DexAssistSession`；为每个 guest 子功能加 per-feature 预检骨架。
- 给免登录各安装器补"全有或全无"预检入口（复用第 1 步模式）。
- 预估：2–3 小时。产出：编译 + 单测通过，无行为变化（纯骨架）。

### 批次 1 — 便宜的稳定锚点子功能（需测试机做冒烟）
- `guest.follow-button`：已 37/38，只差 8.84 一个重载，补上即可。
- `guest.history`：`HistoryMoss` 的 6 个方法是 gRPC 方法名，38 版稳定（直接用）；只需结构化解析 `HistoryFragmentV3.isLogin/up` 和两个 viewmodel 缓存 getter（8.97+ 改名）。
- `guest.playback-history`：`read/write/readFromDB`、播放器 `setMediaResource/getCurrentPosition/seekTo` 等是真实方法名，稳定；需处理 `TheseusHistoryService.t`、`P9.a/b` 改名，以及 `NormalMediaHistoryStorage` 在 9.15+ 整类消失（该版走另一存储类）。
- 预估：3–4 小时 + 1 次 hostlab 验证轮 + 真机冒烟。

### 批次 2 — 中等：登录拦截与引导（需测试机）

进度（matrix `d10de235030445c5ac5ced24c1d927a3`，38 版）：
- [x] `login-dialog`：`MainDialogManager.addDialog` + `DialogManagerInfo.getKey()=="login_guide"` 确认 38/38 存在（既有的 1/10 跨版稳定）。
- [x] `one-pass-guide`：改为混合锚定。8.84–8.96 的 `OnePassLoginGuideHelperV2$Companion` 上有三个 `(String, FragmentActivity, boolean):void` 同形方法（c/w/x），按稳定名 `w`+`x` 命中；8.97+（26 版）宿主改名并只留唯一一个同形方法（如 9.16.1 的 `f`），用 `PrivacyStructuralResolver.uniqueMethod` 按形状兜底命中，歧义时失败即跳过。单测通过；真机冒烟待批准。
- [x] `force-login-toast` + 画质上限 + 强制登录实验开关：**不再需要 DexKit**。反编译 8.90.2 `gh6.f` 与 9.16.1 `Qu0.a` / `xu1.g` 确认三者都只是读两个远程配置键：`enable_player_force_login_toast`（排程开关，`getBoolean(key,true)`）与 `enable_player_force_login_qn`（游客画质钳制 `min(默认档, 该值)`，`getInt(key,32)`）。读取方类名逐版改名，但 `com.bilibili.lib.config.BLRemoteConfig.getBoolean(String,boolean)` / `getInt(String,int)` 是未混淆稳定类，38/38 存在（matrix `08d0cfb5…`/`41b7af34…`），两个键各自恰被一个方法读取。改为按键拦截（`force-login-toast-config` / `guest-quality-cap-config`），替换旧的 8.90.2 `gh6.f.a/h` 钩子；`lp3.a.run` 保留作 8.90.2 第二层。9.17 新增 `LoginGuidanceService`（协程）同样读这两个键，一并覆盖。廉价键预筛先于任何登录态读取，其它配置读取不碰账号状态。
- [x] `overseas-forced-login`：`HomeLoginExpKt` 里拉起 `bilibili://login/overseas_force` 的静态 `()Z`（8.90.2 私有 `n`，8.97+ 公开 `d`，方法体一致）。matrix `beee1320…` 确认该路由字符串在 8.87–8.96 由 `HomeLoginExpKt.n`、8.97–9.17 由 `HomeLoginExpKt.d` 引用（36/38 版），8.84/8.86 没有该入口干净跳过。按"唯一静态 ()Z"形状定位。
- [ ] 评论游客上限：**8.97+ 无法方法级拦截**。9.16.1 的 `guestLimit` 是公开字段，无 getter；该类唯一的 `()Z` `b()` 是"是否 UP 主本人"，挂它会出错。保持 8.90.2 名称并在新版本跳过。要做需字段级方案（读取点内联），暂不排期。
- [ ] 动态底栏登录条（`et1.c`，9.16.1 `dy_login_bottom` 字符串已消失）：仍是 8.90.2 混淆名，9.x 上干净跳过（无回归）。
- 9.16.1 真机（2026-10-27）：`guest_browsing_status=partial:5/10`（此前 1–2/10），已注册 `login-dialog`、`one-pass-guide`、`force-login-toast-config`、`guest-quality-cap-config`、`overseas-forced-login`；缺失仅 `force-login-toast`(lp3.a，8.90.2 专用)、`comment-guest-limit`、`dynamic-login-row`。
- [~] `guest.onboarding-skip`：**部分完成，覆盖缺口大。**
  - 3e9e03de（旧实现）按插件 ABI 形状定位 `NewUserInterestChoosePlugin` / `LowActiveInterestChoosePlugin` 的 show/finish。这些旧插件只在 8.84–8.96 驱动向导；8.97 起宿主实例化 V2 Compose 插件，旧钩子永远不触发。**该提交原先声称「New 全 38 版生效」，真机证明是错的**（9.16.1 欢迎页照常弹出）。
  - 02bb9377（修复）：游客时让 `PegasusResponse.getInterestChoose()` 返回 null（向导唯一数据源，38/38 名字稳定），并绑定首页等待门；另有 V2 入口拒绝（真机未触发过）。9.16.1 连续 3 次冷启动欢迎页不再弹出。
  - **覆盖**：首页等待门只在 9.16.0/9.16.1 能结构定位；其余 24 个 V2 版本（8.97–9.15、9.17）data/entry 因门缺失而跳过，**修复不生效**。8.84–8.96（12 版）靠旧钩子，真机未验证。
  - 详见文末「引导页修复」一节。
- 批次 2 收口：剩余工作有三项。(1) 评论游客上限（字段级，暂不排期）；(2) 动态底栏登录条（需另做 38 版定位轮）；(3) **补「触发证据」**：登录 toast、画质钳制、海外强制登录、一键登录引导目前只确认「已注册」，没有真机触发过的证据，要给它们加独立的触发状态键并走一遍场景。另外**引导页修复扩到其余 24 个 V2 版本**也挂在批次 2 名下。
- 已解决（2026-10-27 20:20）真机 9.16.1 宿主 Java 堆暴涨 OOM：根因是我在 `6bfac3fd` 把 `biligame.net` 加进 `isBiliHost`。无痕会话用合成 `200 {"code":0,"data":{}}` 就地应答游戏中心埋点 SDK 的 `collector/admin/config` 与 `collector/api/report`，SDK 把刷新间隔解析成 0，flush 事件零延迟自我重投，`com.bilibili.biligame.track.mq` 队列涨到约 80 万个 EventMessage（hprof 直方图 + 线程转储 + 反编译 `Mr.d`/`Mr.a` 证实），30–60 秒耗尽 512MB 堆。修复 `8661036e`：把 `biligame.net` 移出 Bilibili 主机名单（流量仍封口身份头，只是不再被短路）。实测无痕 ON + 播放视频 3.5 分钟堆平稳 82–127MB。残留：该 SDK 的埋点报文正文可能含真实标识，只有头部被替换，待批次 3 后评估是否做格式正确的本地应答。
- 教训：新增任何拦截规则后，必须开无痕实测 3 分钟以上并采样 `dumpsys meminfo`。

### 批次 3 — 较难：本地库入口（需测试机）

**真机基线（9.16.1，2026-10-28）：15 个入口只装上 3 个**（`mine_header`、`native_history`、`follow_button`）。每个入口现在有独立状态键 `guest_library_<name>`（d7052286）。

按依赖类在 9.16.1 的存亡分两类（q12，27 个依赖类中 8 个消失）：

**A. 旧承载类已消失，要先探索宿主现在用什么承载（成本高、不确定）**
- 「我的」页菜单：`MinePageManager`、`mine.provider.b$a` / `r$a` 这几个具体类不存在，**但职责已转到 `mine.provider.c`（收藏）、`mine.provider.I`（稍后再看）和 `mine.f`（菜单项构建，持有历史/收藏/稍后再看三个抽屉 URI）**（q13 已查到，见下方假设一节）→ `mine_menu`、`provider_favorites`、`provider_watchLater` 要对这几个新类重新定位。宿主现有 `mine/*` 与 `mineV2/*` 两套我的页，需先确认真机走哪套。`HomeUserCenterFragment` 在，但方法已是 `Vf..og` 序列名。
- 稍后再看：`WatchLaterManagerV2`、`kntr.watch.later.watchlaterold.{api.WatchLaterApiKt, api.FavoritesApiKt, edit.u}` 不存在 → `native_watch_later`、`native_watch_later_add`。
- 关注列表：`BlackListActivity` 不存在 → `native_follow_list`、`follow_list`、`follow_count`。
- 可用的稳定落点：`WatchLaterActivity`、`FavoritesActivityV2` 都还在，`openNative` 要跳的页没丢。

**B. 类还在、成员改名（可结构定位，成本较低）**
- `native_favorite`、`native_list_http`、`video_menu_watch_later`：依赖 `KingPositionService`、`FavoriteDialog`、`RelationRepository`、`MultiTypeListDetailActivityV2`、`MenuService`、`FavoritesTabFragment`、`FavoritesFolderTabFragment`。

**【已决定 2026-10-28】批次边界：B 类三项并入批次 4，合并成「收藏与列表」批次；批次 3 只做 A 类三块（我的页菜单、稍后再看、关注列表）。**

**与批次 4 的重叠（决定前的分析）**：B 类三项依赖的类，正是下面批次 4 的 `guest.favorites` / `guest.lists-http` 的锚点。「先做收藏相关三项」实质是把批次 4 的核心拉进批次 3。建议把这三项明确划归批次 4，批次 3 只留 A 类三块（我的页菜单、稍后再看、关注列表）；或者反过来合并成一个「收藏与列表」批次。开工前先定边界。

旧锚点清单（仅作参考，多数已失效）：`MinePageManager.c/h`、`HomeUserCenterFragment.jr/Ur/lr/Ir`、`MenuGroup$Item` 字段、`MenuService.P1`、`te6.b`、`WatchLaterApiKt`/`FavoritesApiKt` 的 `e/c/b`、`BlackListActivity` 的 `g9/Y8/P/showEmpty`。

**待验证的假设（可能大幅降低 A 类成本；2026-10-28 12:55 已部分验证，仍不能当结论）**：`BLRouter` 是未混淆稳定类（38/38 在），带 `registerPreMatchInterceptor` / `registerGlobalInterceptor`（后者等价于前者）/ `registerPostMatchInterceptor` / `routeTo` / `RouteAuthenticator`。若「我的」页菜单、稍后再看、关注列表的点击最终都经过 `BLRouter`，就可以在路由层按 URI（`bilibili://main/drawer/*` 等，`sectionForUri` 已有映射）为游客改走本地页。

q13（9.16.1）已查到的事实：
- **菜单承载类没有整套消失，只是换了位置**。`mine.provider.c`、`mine.provider.I` 的 `a(FragmentActivity, MenuGroup$Item)` 分别持有收藏（`bilibili://main/favorite`）和稍后再看（`user_center/watch_later_v2`）的 URI，是旧 `b$a` / `r$a` 的继任者；`mine.f` 的 `a(MenuGroup$Item)` / `b(Context)` 一次持有历史、收藏、稍后再看三个抽屉 URI。`MenuGroup$Item` 的 `uri` / `needLogin` 字段都在。只有 `MinePageManager` 这个具体类确实没了。宿主现在有两套我的页：`mine/*`（V1）与 `mineV2/*`（`MineUserCenterViewModelV2`、`mineV2/c`），与引导页一样是双实现，改之前要先弄清真机走哪套。
- 点击确实有一批经过路由器：`main2` 里 `I.onClick`、`J.onClick`、`basic/f.onClick` 等直接调 `BLRouter.routeTo(RouteRequest, Context)`。
- 宿主自己就在用路由拦截器拦稍后再看：`tv.danmaku.bili.ui.watchlater.b.intercept(RouteInterceptor$Chain)` 持有 `bilibili://compose/watch_later/kntr`，是现成的参照。
- BLRouter 持有全局 `RouteAuthenticator`（`setAuthenticator`，包成 `bridge.w`）。**拦截器与认证器谁先谁后，BLRouter 本体反编译看不出来**，要读 `blrouter/internal/bridge/*` 的链路装配类。

仍未验证：(1) `provider/c`、`provider/I` 的点击是否经过 `routeTo`；(2) 登录检查在认证器、拦截器还是 provider 里，决定拦截点放哪一层；(3) 游客实际点击走哪条路径。模块里目前没有任何 `BLRouter` 用法。

**估计（上调）**：原估计 5–8 小时是按「锚点改名」算的，对 A 类不成立。A 类每块都要先做一轮探索（反编译 + 找新承载类 + 看 `bilibili://main/drawer/*` 路由落点）才能谈定位。我没有足够依据给精确数字：粗略 **A 类三块各 3–6 小时，共 10–18 小时**，不确定性主要来自「新承载类能不能被结构定位」，可能有某块最终只能干净跳过。外加每块 1 次 hostlab 验证轮 + 真机验证。

### 批次 4 — 最难：原生收藏与 REST 列表（需测试机）
- `guest.favorites`：**按版本分两段，且已被 `GuestVideoFavoriteHooks` 替代大半（2026-10-29 更正）**。星标点击、收藏夹选择框、星标变色、Story 星标已在新钩子里完成，不再依赖下面这 16 个旧锚点；静态矩阵 q47 显示旧钩子（用这 16 个锚点那套）只在 8.90.2 一个版本能装上。下面的清单只对“收藏页内的原生管理界面”（改名、删夹等）仍有参考价值。原文：8.84–8.96 是旧弹层（`FavoriteDialog`），下面这 16 个锚点是按它列的，可能仍可用，未验证。8.97 及以后，尤其 9.x，弹层换成 KMP 组件 `kntr.app.favorite.favoriteDialog`（入口 `KingPositionService.k(boolean)`，回调 `showKntrFavoriteDialog$params$1`），这些锚点不适用，要重做，不是改名。原锚点清单：16 个漂移锚点。`KingPositionService.B0/C0/V0/D0`、`RelationRepository`、`FavoriteDialog` 的 `$b/$c/$d` 内部类逐版重排、资源 id 持有类 `F10/F12`、`MultiTypeListDetailActivityV2` 方法群、`rt3.a` 拦截器、`PlaySetEditorStateMachine`（9.17 已改名为 `M`）+ ViewModel/Fragment。
- `guest.lists-http`：`BiliCall` 的 `execute/p/isCanceled` + `m/b` 字段、三个收藏 viewmodel、`fa6.b` 路由、`F13.Wc`。
- 预估：6–10 小时 + 2–3 次 hostlab 验证轮 + 真机验证（收藏夹增删改、多文件夹、列表分页）。

## 总预估

- 纯开发 + 静态验证（hostlab）：原估计 25–40 小时是按「锚点改名」算的，**已过期**。按 12:28 的真机基线重算：批次 3 的 A 类三块约 10–18 小时（见批次 3 一节，不确定性来自新承载类能否结构定位）；批次 4 与批次 3 的 B 类收藏项重叠，合并后约 8–14 小时；再加批次 2 的「触发证据」与「引导页扩到其余 24 版」各数小时。合计量级约 **30–50 小时**，同样是区间不是承诺，且某些块最终可能只能干净跳过。
- 真机验证：批次 1–4 各需一次，合计数小时，取决于打断弹窗和解锁情况。
- 每批结束都应：hostlab 跨 38 版确认锚点唯一命中或干净跳过 → `testDebugUnitTest` + `lintFast` → 真机冒烟 → 单独提交。

## 风险与边界

- 部分锚点可能找不到足够强的唯一结构特征（项目约定不允许凭弱特征猜测安装）。这些点在无法定位的版本上会让对应子功能跳过——可接受，因为软失败不泄漏、不崩溃。
- 真机只有一台、单一宿主版本：静态证明锚点存在 ≠ 运行时 Hook 生效。真机只能验证当前安装版本那一版；其余版本仍只有静态证据。
- 不扩大 lint baseline、不加 suppress；8.97+ 的 DexKit 特征必须逐版在矩阵里核验唯一命中后才纳入。


## 长跑实测（2026-10-28 09:48-10:09，9.16.1，HEAD 8661036e，无痕开）

- 21 分钟、118 个采样（每 10 秒），宿主进程全程 pid 13720，没有重启，崩溃缓冲区为空。
- Java 堆全程 78-127MB，播放阶段（10:00 之后）83-89MB；原生堆 288MB 不变；PSS 733MB 不变。启动第一个采样原生堆 978MB，10 秒内回落到 227MB（启动瞬态）。
- 结论：biligame.net 移出名单（8661036e）后，堆暴涨问题在 21 分钟窗口内未复现。仍只有 9.16.1 一版。
- 视频 av170001 播放到 10:11 仍在（弹幕在滚动），但截图里视频画面是黑的，没有独立证据证明画面在渲染。

### 新发现：引导页没被跳过（批次 2 的 3e9e03de 在真机上未生效）
> 本节是当时的早期记录，其中「钩子要么没装上，要么这个页面不走 show 方法」的推测已被证实为后者。结论与修复见下一节「引导页修复」，以那一节为准。
- 条件：无痕开、skip_guest_onboarding=true、宿主冷启动，弹出「欢迎来到哔哩哔哩」性别/年龄/兴趣三步页（"下一步 1/3"）。
- 整份模块日志没有 guest_onboarding 状态，也没有 "adapter unavailable" 错误：钩子要么没装上（install 形状不符时静默返回 0），要么装上了但这个页面不走 show 方法。
- 它不在 Fragment 视图树里（Fragment 只有 HomeFragment/PegasusFragment 等），不是独立窗口（宿主只有一个 MainActivityV2 窗口），uiautomator 在该页取不到树（could not get idle state）。
- 按返回键可以关掉，不提交任何资料，首页为游客态（左上角「登录」）。
- 待办：(1) 给 GuestOnboardingHooks 每个插件加 install 结果状态（New/Low 各自 installed 或原因）；(2) 反编译 9.16.1，找画「下一步 1/3」的类和入口；(3) 38 版矩阵核对后再改。

### 没拿到的证据
- 登录 toast、画质钳制、海外强制登录、一键登录引导：启动时只确认已注册（guest_browsing_status=partial:5/10），没有「触发过」的运行证据。调试审计日志有条数上限，09:56 之后模块日志不再写入。
- 免登录单独验证仍需要用户退登录。


## 引导页修复（2026-10-28 11:00-12:00，9.16.1，已提交并推送 02bb9377）

### 根因（比上一轮的推测复杂得多）
9.16.1 的欢迎向导是 V2 的 Compose 叠加层（`interestv2.host.m` 往 `android.R.id.content` 加一个全屏 ComposeView），不在 Fragment 树也不是独立窗口。
- 旧插件钩子（NewUser/LowActive 的 `(InterestChoose):void`）在 V2 宿主上永远不触发：`interest_choose_kmp_enabled`（`interest/a.b()Z`）决定插件注册表实例化 V2 还是旧插件。
- 真机探针实测：这台手机走的是 `LowActiveInterestChoosePluginV2`，不是 `NewUserInterestChoosePluginV2`。
- `request.d.h()`（首次启动等待门）只管首页要不要等向导，不管向导挂不挂载：钩子触发（gate_applied=seen）向导仍弹出。
- 向导的数据源是 `PegasusResponse.getInterestChoose()`（38/38 都在，名字未混淆，只有一个调用方 PegasusResponseWrapper）。游客返回 null，宿主自己有空值保护，向导无数据可显示。

### 实现（GuestOnboardingHooks.kt）
四层，全部带独立状态键（状态通道按 key 去重，只留首个值，所以「已装」和「已触发」必须是不同 key）：
1. gate：request 包里唯一的静态 ()Z（guest_onboarding_gate / _applied）
2. data：`PegasusResponse.getInterestChoose()` 返回 null（guest_onboarding_data / _suppressed）— 真机生效的主手段
3. entry：沿 AndroidInterestChooseSession -> runtime -> entry 字段链找唯一的 `(int,String,ref,boolean[,ref])Z`，返回 false（guest_onboarding_entry / _rejected）— 真机未触发过，只有单测
4. 旧插件 show/finish（8.84-8.96）— 沿用
data 和 entry 以 gate 已装为前提：门没装的版本首次启动首页可能在等向导，拦掉向导会把首页卡死。

### 验证
- 真机 9.16.1 无痕开，连续 3 次冷启动：欢迎页均未出现，首页游客态，推荐流和内联视频正常。
- 单测 4103 个 0 失败，FastLint 262 不变，改动文件 LF 无 BOM。
- 变异验证：拿掉 loadClass 覆盖后「gate absent」测试确实失败。

### 覆盖范围（静态矩阵 q7 b687820c、q8 882a02c4、q9 b388be50、q11 9e752e0a）
- gate 的类名每版不同（request.c.f / b.g / d.h / c.h …），9.03-9.14.1 的 PegasusFragment.onCreate 甚至不读谓词，改走 interest/<x>.a(I,Function0)V。
- 仅 9.16.0 / 9.16.1 能按「request.d 上唯一静态 ()Z」装上 gate；其余 V2 宿主（8.97-9.15、9.17，共 24 版）data/entry 因 gate 缺失而跳过，向导修复在这些版本上未生效。
- 8.84-8.96（12 版）没有 V2，靠旧插件钩子，真机未验证。
- 要扩到其余 V2 版本，需要另想办法定位首页等待门（DexKit 策略 B 或换思路），风险高，未做。

### 其他
- 引导修复只在 isGuest 时生效；登录用户的行为由单测覆盖，真机未验证。
- 临时探针已拆除，不在提交里。


## 批次 3 基线（2026-10-28 12:28，9.16.1，无痕开，HEAD d7052286）

观测改动（d7052286）让 15 个本地库入口各自上报 `guest_library_<name>`。真机 9.16.1 上 **3/15 装上**：`mine_header`、`native_history`、`follow_button`。其余 12 个缺失。

matrix 外的单版本查询 q12（`75cc4847…`）对照 27 个依赖类：19 个在，8 个消失。

### 类整个消失（需要先探索宿主现在用什么承载，不是改名映射）
- `tv.danmaku.bili.ui.main2.mine.MinePageManager`、`mine.provider.b$a`、`mine.provider.r$a` → 我的页菜单（mine_menu、provider_favorites、provider_watchLater）
- `tv.danmaku.bili.ui.watchlater.widget.WatchLaterManagerV2`、`kntr.watch.later.watchlaterold.{api.WatchLaterApiKt, api.FavoritesApiKt, edit.u}` → 稍后再看（native_watch_later、native_watch_later_add）
- `com.bilibili.relation.blacklist.BlackListActivity` → 关注列表（native_follow_list；follow_list/follow_count 依赖的 `HomeUserCenterFragment` 方法 `Ur/jr` 也被改名）

### 类还在、成员改名（可结构定位）
- `HomeUserCenterFragment`（50 个方法，现为 `Vf..og` 序列名）、`MultiTypeListDetailActivityV2`、`FavoriteDialog`、`KingPositionService`、`RelationRepository`、`MenuService`、`FavoritesTabFragment`、`FavoritesFolderTabFragment`、`FollowFlowHelper`
- 稳定类都在：`BiliCall`、`BLRouter`、`ListExtentionsKt`；`FavoritesActivityV2`、`WatchLaterActivity` 也在（`openNative` 的跳转目标没丢）

### 对路线图估计的修正
原估计 5-8 小时按「锚点改名」算。实际「我的页入口、稍后再看、关注列表」三块的旧承载类已消失，要先花一轮探索（反编译 `HomeUserCenterFragment` 看菜单怎么构建、找 `watchlater` 与 `relation` 包的新类、看 `bilibili://main/drawer/*` 路由现在落到哪），再谈定位。收藏相关的 native_favorite / native_list_http / video_menu_watch_later 反而可能是改名问题。建议顺序：先收藏相关（类在，成本低），再探索我的页菜单。

### 真机路由探针结果（2026-10-28 13:14，9.16.1，无痕开，探针不提交）

在模块里临时挂了一个只记录的钩子到 `BLRouter.newCall`（`routeTo` 只是 `newCall(...).execute()` 的薄壳，所以 `newCall` 能看到所有路由）。
- 启动时路由器收到首页各 tab 的路由，其中 `bilibili://user_center/mine` 只是被登记成 tab，此前探针里没出现这两个 fragment，只是因为我当时没点底栏的「我的」tab（右下角小电视图标），不是游客没有这个页。
- 游客点左上角「登录」头像：`TopLeftComponent$initActionHandler$1$1`（`tv.danmaku.bili.home.components.topbar.topLeft`）直接 `routeTo` 到 `bilibili://login`，随后是 `bilibili://klogin/dialog/sms`。顶栏左上角对游客就是登录按钮（已登录用户点它走 `bilibili://side_center/container`）。
- 顶栏右侧 ☰ 打开的是「分区」页，不是资料库。
- 路由器入口栈里有宿主已有的拦截器 `j2.intercept < k.proceed`，在所有请求之前。
- 探针自身的坑：`"$Proxy"` 在 Kotlin 字符串里被当成模板引用，编译失败，而构建包装器的日志里没有 `e:` 行，导致我一度把旧包当新包装上去。**构建后必须同时核对 APK 时间戳是否更新、设备哈希是否等于本地哈希**，只信日志行会静默出错。

**更正（13:24 实机点「我的」tab）**：游客有「我的」tab，但宿主给游客换成了强制登录版本。点击后依次是 `route#25 bilibili://main/drawer/history`（来自 Compose 版 `mineV2` 组装入口列表，`mineV2.q.c < ui.main2.mineV2.G.c`），创建 `HomeUserCenterForceLoginFragment`，再 `route#27 bilibili://login/inner/strict`，落到账号密码登录页。所以我上一版写的「游客没有任何我的页入口」是错的。批次 3 要解决的是：谁在为游客选择 ForceLogin 版本，以及能否让游客拿到正常的我的页或本模块的资料库。已登录用户的资料库推测在 `side_center`（`SideCenterActivity` 未导出，只能经路由 `bilibili://side_center/container` 进入）。q15 正在查 ForceLogin fragment 的选择点。

### 进展与实机证据（2026-10-28 14:16，9.16.1，无痕开）

- 提交 `16cde2a7`（已推送）：游客在 V2「我的」页不再被强制登录盖层遮住。状态键 `guest_mine_overlay=installed` 与 `guest_mine_overlay_applied=seen` 分开，实机截图确认页面完整（离线缓存、历史记录、我的收藏、稍后再看四个入口都在）。只在 9.16.1 实机验证过；其余版本待版本矩阵，不外推。旧的 V1 守卫 `HomeUserCenterFragment.Ir` 在这台机器上从未生效，暂未改动。
- 游客点菜单项的判断在 `MinePageManagerV2$switchTo$1`：未登录且 `MenuGroupV2$Item.getNeedLogin()==1` 时直接 `routeTo(bilibili://login)`；为 0 时走 `mineV2.n.c`，把 `item.getUri()` 交给 `routeTo`。`MenuGroupV2$Item`、`getNeedLogin`、`getUri` 名字都没混淆。历史记录的 needLogin 为 0，所以能进；收藏为 1（实机问到的 URI 是 `bilibili://user_center/favourite`）。
- 历史页：游客点「历史记录」打开宿主原生历史页（`VertexActivity`），KMP `History/HistoryTabV2` 与 `History/CursorV2` 由本模块本地应答。页面为空只因为本次启动后本地还没有记录，「有记录时能显示」尚未验证。
- 临时探针实验（不提交）：让资料库类 URI 的 `getNeedLogin` 返回 0 之后，宿主确实路由 `bilibili://user_center/favourite`，但紧接着是 `bilibili://klogin/dialog/sms`，屏幕上没有收藏页也没有可见登录框。说明收藏入口前面还有第二道门：路由器层的登录要求（推测是路由声明的登录要求加 `RouteAuthenticator`，由 `rp1.k.j` 发起，仍是推测）。只放行 needLogin 不够。
- 候选做法（未实现）：游客且 `GuestLibraryHostHooks.sectionForUri` 命中时，在 `BLRouter.routeTo` 之前直接 `openNative`（已有），不让请求进入路由器。落地前要确认 `routeTo` 的返回类型和调用方能否容忍跳过，以及 38 版本里 `routeTo` 签名是否稳定。
- 未测：稍后再看、关注、离线缓存；我的页头像或登录按钮点击是否触发登录；本地有记录时历史页是否显示。
- 探针教训：手写 URI 条件漏了 `favourite` 的英式拼写，第一次实验没测到想测的东西。以后一律复用 `sectionForUri`，不手拼 URI。

### 入口修复实验（2026-10-28 15:35，9.16.1 实机，无痕开，探针不提交）

结论：游客在 V2「我的」页打开资料库入口，靠两个公开 API 的小钩子就够，不需要路由拦截器、认证器或新入口。

- **第一道门 `MenuGroupV2$Item.getNeedLogin()`**：对资料库 URI 让游客得到 0。稍后再看（`bilibili://user_center/watch_later_v2`）只靠这一条就打开了宿主原生页（`VertexActivity`），并发出 `x/v2/history/toview/v2/list`。
- **第二道门是路由核心的登录要求**（本条已更正，见下方矩阵）：服务端下发的收藏 URI 是 `bilibili://user_center/favourite`，它在 9.16.1 路由表里是登记过的（矩阵 q20 为 1；先前说“没有登记”是我读了被 LIMIT 截断的 q19 结果得出的错误结论）。只放行 needLogin 时，宿主走 `n.c -> BLRouter.routeTo`，路由核心发现需要登录，由认证器 `gripper.router.q$a.authenticate` 无条件改写成 `bilibili://login`，拉起 `LoginActivityV2`。把 `MenuGroupV2$Item.getUri()` 对游客换成 `bilibili://compose/favorites/kntr` 后，打开了宿主原生收藏页（`UserPortraitVertexActivity`，有「收藏/追更」标签），并发出 `x/v3/fav/tab`、`x/v3/fav/tab/my_fav`，无登录门。
- **离线缓存**本来就不需要登录（路由到 `activity://main/download-list`，`VertexActivity`）。
- **两个页面都显示「加载失败」**：入口通了，数据没有。那是「收藏与列表」批要做的本地应答。（更正：这些请求不是被无痕封住，而是清洗后发给了服务端，服务端对匿名请求回错误。）
- **关注数**不走菜单：`mineV2.W$r.a` 直接路由 `activity://main/login/`，需要另行处理。
- 没有成功的路线（别再试）：运行时 `registerPreMatchInterceptor` 在启动早期注册会抛异常，首次 `newCall` 时注册能成功，但返回 `redirectTo(...)` 的 `REDIRECT` 响应在菜单调用点不被跟随（改道到已知可用的历史路由也没有任何页面打开）；认证器返回 null 会让路由变 `NOT_FOUND`（这是置空认证器造成的，不代表路由缺失；不置空时响应是 `code=OK, request=bilibili://login`），所以不是放行手段；`BLRouter.routeTo(RouteRequest, Context)` 是很短的静态方法，`registrar.exact` 钩它没有触发（疑似被内联）。
- 页面路由类 `tv.danmaku.bili.mineV2.n` 上有两个同形状的 `(MainActivityV2, Item):void`（`a` 为空方法，`c` 真正路由），按形状唯一定位会 fail-closed，所以不钩它。
- **38 版本矩阵 q20 结果（静态，`e0412eb2d14244e48b1a73ce07dee18b`）**：`user_center/favourite` 登记于 9.01 到 9.17，8.84 到 8.99 没有；`compose/favorites/kntr` 只登记于 9.12 到 9.17（8.84 到 9.11 没有）；V2 菜单项的 `getUri` 与 `getNeedLogin` 在 8.92 到 9.17 都有，8.84 到 8.91 没有 V2；旧的 `MinePageManager` 存在于 8.84 到 8.96，8.97 起消失；`HomeUserCenterFragmentV2` 在 8.92 到 9.17 都有；`watch_later_v2` 登记于 9.01 到 9.17。结论：只有 9.12 到 9.17 同时具备“V2 菜单项”和“`compose/favorites/kntr`”，改写方案对其余版本不成立（8.97 到 9.11 必须另找收藏目标，8.84 到 8.96 仍走旧类）。实机只验证了 9.16.1。

### 为入口接上数据：调查结论（2026-10-28 17:40，9.16.1，无痕开，探针不提交）

**页面数据请求不是被封住，是发给了服务端**：收藏页 `x/v3/fav/tab`、`x/v3/fav/tab/my_fav` 与稍后再看页 `x/v2/history/toview/v2/list` 的日志都是 `ignet HTTP sealed and sent`，服务端对匿名请求回错误，页面才显示「加载失败」。（此前路线图、提交 `41a2ac2b` 的正文、给用户的汇报里写成「被无痕封住」，不准确，已在路线图更正，提交正文已推送无法改。）

**协议层其实是现成的**：`NativeFavoriteProtocol`（含 `/x/v3/fav/tab`、`/x/v3/fav/tab/my_fav`）与 `NativeWatchLaterProtocol`（含 `/x/v2/history/toview/v2/list`）已能生成本地 JSON，但只接在 okretro `BiliCall`（OkHttp 通道）上，由 `GuestNativeListHttpHooks` 挂接，该钩子在 9.16.1 缺失。新页走 Ktor/ignet，根本不经过 `BiliCall`。

**要把本地应答接到 Ktor/ignet 上，两个层都不好下手**：
- `IgHttpResponse`（`com.bilibili.lib.ighttp`，未混淆）的状态、头由原生回调填，读体走 `native_response_read_data(_v2)` 并依赖 `_nativePtr`，Java 侧造不出能读出字节的假对象；`IgHttpRequest.execute()` 只能抛 `IOException`（现有 `IgnetHttpBoundary` 就是这么「扣下」请求的），不能返回合成应答。
- 其上的 Ktor 引擎 `kntr.base.ktor.ignet.IgnetHttpEngine`（类名稳定）`execute(HttpRequestData, Continuation): HttpResponseData`，但 `HttpRequestData/HttpResponseData` 等 Ktor 类被 R8 改成 `p1510b31.c/f`、`io.ktor.client.engine.c` 之类短名，构造一个 `HttpResponseData` 要造一串混淆类，且逐版会变。没有稳定锚。

**写入侧同样整体换了实现**：视频页游客点收藏星标，宿主直接拉起 `LoginActivityV2`（日志：`LoginActivityV2$c.intercept` 在路由拦截器链里，`gripper.router.*`、`restrict.h` 在其上游），**没有 `bilibili://login` 的 `newCall`**。`KingPositionService` 在 9.16.1 的 14 个方法全是单字母 `a` 到 `m`（旧钩子用的 `B0/C0/V0` 一个都没有），收藏点击在 `g(Z)V`（含埋点串 `united.player-video-detail.user-action.collect.click`），实际收藏动作在 `j(String,int,boolean,String,FavFrom)`、`favOrCancel$2`、`showKntrFavoriteDialog$params$1`，说明收藏弹层已换成 KMP 的 `kntr.app.favorite.favoriteDialog`（`ButtonType`）。旧钩子 `GuestNativeFavoriteHooks` 里的 16 个混淆锚点（`RelationRepository` 构造器、`FavoriteDialog.d0/D/onClick`、资源字段 `p5/d1`、`rt3.a`、`mc/Yc/fb/gb`）在 9.16.1 上基本都要重新做。稍后再看的添加同样分散：`hottopic.shareUtils.p`、`rank.api.c`、`kntr.watch.later.watchlater.api.g` 各自直接调 `/x/v2/history/toview/add` 或 `/x/v3/fav/toview/adds`。

**关注数**：`mineV2.W$r`（`ToAttentionClick`）的 `a(mineV2.b)` 含字符串 `关注`、`3`、`activity://main/login/`，即游客点关注数直接 `routeTo` 登录。旧的 `BlackListActivity` 已不存在，宿主关注列表现在是 `bilibili://compose/following_list/*`（路由登记，`FollowingListCmpRootFragment`）。

**顺序必须反过来**：读取（页面显示本地数据）与写入（点星标、加稍后再看）缺一不可，但没有写入，读取层做完页面也是空的。所以「收藏与列表」批应写入优先，读取其次。预估上调：写入侧是一整套新适配（收藏、稍后再看添加、收藏夹弹层）加读取侧一个混淆 Ktor 层，合起来更像 **15 到 25 小时**，且收藏弹层与 Ktor 响应层任一环可能只能干净跳过。

**调度教训**：这一轮我把互相独立的静态调查一个一个串着做（每轮构建装机 5 到 8 分钟）。应当把独立的 hostlab 问题合成一个批一次问完（q24 一次问了五块），再统一读结果。

### 写入侧：视频页星标的登录门（2026-10-28 18:20，9.16.1，无痕开，探针不提交）

**登录门找到了，是一个很小的汇聚点 `bt1.o`**（包 `bt1`，类 `o`，公开静态）：`a(Context, String toast, String promptScene, String fromSpmid, String extend): boolean` 先读 `BiliAccounts.isLogin()`，已登录返回 true，否则调 `c(...)`（用旧 `Router.global().with(context).open(login)` 打开登录页）并返回 false。全宿主只有 14 个调用方（点赞、收藏、下载缓存、举报申诉、关注 UP、播放列表、稍后再看页……）。`c` 是 `public static void (Context, String, String, String, String)`。

**游客点收藏星标的调用链（栈实测）**：`UIComponentExtKt$onClickEvent` 点击 -> `KingPositionService$create$1$1$14$a.emit` -> `bt1.o.a/c` -> `Router$RouterProxy.open` -> brouter 拦截器链（`restrict.utils.a`、`at0.f`、`LoginActivityV2$c.intercept`）-> `LoginActivityV2`。这条路走旧版 `com.bilibili.lib.router.Router`，不经过 `BLRouter.newCall`，所以此前的 `route#` 日志里看不到。

**探针实测的参数**：星标点击时 `c` 收到 `promptScene = player.ugc-video-detail.user-action.fav.click`，`fromSpmid = united.player-video-detail.0.0`，`extend = {"oid":"<aid>","sid":"<id>"}`（点赞是 `...user-action.unlike.click`）。所以按 `promptScene` 能把收藏从其他互动里区分出来，`extend` 里直接带视频 aid。

**能钩的只有 `c`**：钩 `a`（boolean）的探针一次都没触发，钩 `c`（void）每次都触发，说明 `a` 被 R8 内联进了调用方。`a` 的逻辑已内联在调用点：调用方拿到的是内联后的 `false` 并停止，所以光取消登录页（钩 `c` 不执行）动作不会继续。要让收藏真的发生，必须整体替换：钩 `c`，游客且 scene 为 fav 时取消登录页，取 `extend.oid` 作 aid，调本地库切换收藏（`LocalVirtualLibrary.toggleFavorite`，元数据可由 `GuestLibraryRuntime.videoMetadata(aid, cid)` 取），再弹本地 Toast。第一版不改宿主星标外观（星标变黄要改 `RelationRepository`，那是旧钩子的 16 个漂移锚点之一）。

**无痕写策略是兜底**：`PrivacyNetworkPolicy.blockHttp` 对无痕下所有非 GET/HEAD/OPTIONS 请求默认扣下，`/fav/resource/`、`/relation/modify`、`/toview/add` 等写入显式被列出；gRPC 侧 `blockRpc` 对 `like`、`follow`、`add` 等前缀同样扣下，且 `access_key`、`csrf` 等账号参数在 `parameters()` 里被剥掉。所以即使游客点赞、点关注，也不会落到真实账号。（本轮我在公开视频 av170001 上点过一次点赞和关注，页面显示已点赞/已关注：关注是旧的本地关注钩子 `guest_library_follow_button` 的正常效果，只写本地库；点赞请求被无痕写策略扣下。）

**旧钩子的可用性（38 版矩阵 q27）**：`GuestNativeListHttpHooks` 依赖 `BiliCall.p(Response)`，该方法只在 8.84–8.96 存在；8.97–9.17（26 版）改成 `a(Response):retrofit2.Response`，所以这个钩子在 26 版上首先死在 `p` 一个名字上，可以用「旧版按名、新版按形状」修。但 9.16.1 的收藏页和稍后再看页走 Ktor/ignet，不经过 `BiliCall`，修了也不影响这两页。

**收藏弹层有两种实现**，由设备开关 `dd_kntr_favorite_dialog`（默认 false）选：旧 `com.bilibili.playset.widget.favorite.FavoriteDialog`（OkHttp，`community.favorite.b` -> `/x/v3/fav/resource/batch-deal`）或 KMP 的 `kntr.app.favorite.favoriteDialog`（Ktor）。`KingPositionService.k(boolean)` 是显示弹层的入口，自身不含登录判断。

### 教训（追加）
- 含中文的日志行用 PowerShell 的 `Select-String` 再 `-replace` 会整行消失，导致「没有 XX 日志」的负结论是假的（本轮我因此误判「星标不弹登录」约 10 分钟）。凡要下「没有」的结论，必须用 Python 读日志。
- 反复在同一个视频上点互动会改变页面状态（已点赞、已关注），污染后面的观察。每次实验换一个没碰过的视频。
- 独立的静态问题合成一个 hostlab 批一次问完，不要串着问（本轮 q24 一次问五块，比前面逐个问快得多）。

### 教训（本轮）
- 共用一个日志键或状态键会把多个失败合并成一条，真机上看不出缺的是哪个。每个独立入口必须有自己的 key，且「已装」和「已触发」要分开。
- 并行发依赖的调用（先拉文件后读图）会读到不存在的文件，要等拉取完成再读。
- 偏好键和滚动位置在每次重装后都可能变，点开关前先截图确认位置，点完用偏好文件核实。


### 星标写入口 38 版本矩阵（2026-10-29 10:50，静态，q40 与 q42）

被检验的假设：`GuestVideoFavoriteHooks.locateFavoriteState` 先取 `com.bilibili.community.favorite.FavorRepository$favor$1`，再取它唯一构造函数的第一个参数类，最后取该类唯一的 public static `void(long, boolean)`，当作星标缓存的写入口。

结果（q40 查构造函数，q42 查 `(JZ)V` 方法，matrix_id 分别是 `dc6454fa…` 与 `7f9e443f…`）：

| 版本范围 | 版本数 | 构造函数第一个参数 | 包内唯一 `(long, boolean)V` | 定位结果 |
|---|---|---|---|---|
| 8.97.3 到 9.17.0 | 26 | 混淆的缓存类（`e`、`d`、`f`、`c` 轮换） | 在同一个类上，访问标志 9（public static），名字 `b`（8.97 到 9.11）或 `c`（9.12 起） | 成立 |
| 8.84.0 到 8.96.4 | 12 | `FavorRepository` 本身 | 在 `FavorRepository` 上，访问标志 17（public final，实例方法，名字 `e` 或 `l`） | 不成立，退回 Toast |

要点：
- 名字 `b` 和 `c` 会随版本变，代码按签名取而不是按名字取，这一点和矩阵一致。
- 8.84 到 8.96 的写入口是实例方法，要拿到 `FavorRepository` 实例才能调用，目前代码不支持，所以这 12 版点星标仍是“写入成功但星标不变色，弹 Toast”。要补的话需要找 `FavorRepository` 的单例或从页面对象取实例，未做。
- 只有 9.16.1 做过实机；其余 25 个“成立”的版本是静态结论，未装机。
- 方法论教训：第一版 q40 的 `writers` 查询写了 `substr(class_descriptor,1,35)`（实际前缀 33 个字符）并且用 `method_descriptor='(JZ)V'` 做等值比较，而 `method_descriptor` 列存的是完整签名，所以两处都匹配不到，38 版全部返回空，连 9.16.1 也是空。q41 只修了前缀仍然空，q42 改成 `LIKE '%(JZ)V'` 才对。空结果先拿已知存在的版本校验查询，再下结论。

### Story 竖屏播放器的星标（2026-10-29 12:15 查明，13:05 已实现并提交 `bc93ea64`，9.16.1 实机通过）

**结论（13:05 更新）：已解决。** 下面保留调查过程；“为什么没做完”那一段是 12:15 的旧结论，已被取代。

解决方式：不依赖“当前播放的视频”，改读被点的星标控件本身。`StoryFavoriteWidget` 持有一个控制器字段，它的 `getData()` 返回 `StoryDetail`；`StoryDetail` 的 `getAid/getCid/getTitle/getCover/getDuration/getOwner/getRequestUser`、`Owner.getMid/getName/getFace`、`RequestUser.get/setFavorite` 在 38 版本里形状一致（q46）。控制器字段名和接口名随版本变（`a`、`d`、`f`…），所以按类型找：控件里类型带 `getData(): StoryDetail` 的唯一字段。控件的三个公开构造器 38/38 一致，构造后弱引用登记。Story 星标图标就是控件里唯一的 `ImageView` 字段，保存后给它 `setSelected` 即可变色。

实机（9.16.1）：点 Story 星标弹出“收藏到”，勾选完成后星标变粉，再点取消勾选星标变灰；“我的收藏”里出现该视频，标题“双人抬棺”、作者“Z 贤者”、封面和时长 3:19 都对（时长按秒乘 1000，与视频页读取一致）。状态键 `_story_source`（安装）、`_story`、`_applied`、`_picker`、`_saved` 都出现，无 `_failed`。

实机上定位到的两个坑：
- 屏幕上同时可见的星标控件可能不止一个（当时是 2 个，大小和可见面积相同），按“面积并列就不确定”会误判为空。改成读全部可见控件，指向同一个 aid 就没有歧义；aid 不同才比面积，面积也相同才放弃并保留登录页。放弃时状态键 `guest_video_favorite_story_why` 会写出原因（只含数量）。
- `modlog.py` 会把每行截断到 300 字符，长的状态值要用 grep 工具读 `mod.log` 原文。

还没做：Story 星标下的收藏数不变；Story 钩子是否在 38 版都生效只做了静态核对，实机只有 9.16.1。

以下是 12:15 的调查过程（保留）：

用户说“从历史页进入的视频点收藏没反应”，这很可能就是它：历史页里的竖屏视频用 `StoryVideoActivity` 打开，它的星标不走详情页那条路。

实机证据：
- 点 Story 星标，宿主用 `bilibili://login/...` 启动 `LoginActivityV2`（logcat 的 `START u0 {dat=bilibili://login/... cmp=...LoginActivityV2}`），登录页是半透明浮层，所以画面“什么都没发生”。
- 这个请求**没有**经过详情页用的 `Router$RouterProxy.open`，所以 `bc99b979` 起的路由钩子完全没触发（探针在 `RouterProxy` 上零输出）。
- 它走新路由 `BLRouter.routeTo(RouteRequest, Context)`。`RouteRequest.getTargetUri()` 是 `bilibili://login`，`getExtras()`、`getProps()` 都是空的：没有 `key_prompt_scene`，没有 `extend`，也没有 aid。单看请求分不出是星标还是点赞。
- 调用栈里有未混淆的 `StoryFavoriteWidget`：`j2.intercept < k.proceed < u.a < c.b < c.a < StoryFavoriteWidget.C0 < a.onClick`。可以拿它判断“是星标”。

38 版本静态矩阵（q43、q44、q45）：
- `BLRouter.routeTo(RouteRequest, Context)` 38/38 都是 public static；8.84 到 8.96 另有一个内部 `newCall` 变体，不影响。
- `RouteRequest.getTargetUri/getExtras/getProps` 38/38 有。
- `RouteResponse(Code, RouteRequest)` 公开构造函数 38/38 有，`Code.OK` 38/38 有，所以拦截后可以安全地返回一个 OK 响应。
- `com.bilibili.video.story.action.widget.StoryFavoriteWidget` 38/38 存在（名字没混淆）。

为什么没做完：钩子本身做好了、单测 31 个全过、变异检查有效，装机后日志 `guest_video_favorite_story_hook=installed`、`guest_video_favorite_story_unknown=seen`。也就是钩子认出了 Story 星标，但本地库里没有“当前正在播放哪个视频”的任何信息，所以按设计放行了登录页：
- Story 播放时只出现 `guest_player_params-built`（只把 cid 到 aid 记进了一张表，没有标题、封面、作者），确认播放事件（`recordProgress`）一次也没出现，`confirmedKey` 为空。
- Story 的推荐流是 `app.bilibili.com/x/v2/feed/index/story`，不是视频页用的 View 回包，所以也没有元数据。
- 用“最近记录的那条”去猜不可靠：Story 会预加载下一条，可能收藏错视频，所以没有采用。

要做完需要新增一条数据管线：从 Story 的推荐流或播放器读出当前条目的 aid、cid、标题、封面、作者，并可靠地知道“现在显示的是哪一条”。做完后，钩子这一半可以直接用备份的补丁。

补丁位置：`captures/story-star-wip/tracked.patch`（`GuestLibraryRuntime.kt` 的 `currentVideo()`、`GuestVideoFavoriteHooks.kt` 的 `installStoryStar`、测试）和 `captures/story-star-wip/storyrouter/`（四个路由夹具，放回 `src/test/java/fixtures/privacy/videofavorite/storyrouter/`）。

方法论教训：
- 这台手机上点击没有任何可见变化，不代表钩子没触发或点击没送到：半透明的登录浮层会让画面看起来没动。先用 `logcat` 看有没有 `START u0 ... LoginActivityV2`。
- logcat 缓冲只有约 30 秒，点击前 `logcat -c`，点击后马上抓；LSPosed 模块日志文件有时整段不更新，不能作为“没有事件”的证据。
- 临时探针要从“路由请求里有什么”问起，再问“调用栈里有什么”；先不要假设请求里有场景串。


### 旧钩子 GuestNativeFavoriteHooks 实际生效范围（2026-10-29 13:20，静态矩阵 q47，`75b06d35…`）

旧钩子要求同时满足：7 个类（`ju0`、`ky3.a`、`playset.i5`、`united.page.view.a`、`united.utils.s`、`PlaySet`、`PlaySetPageData`）、`KingPositionService.B0/C0/V0`、`FavoriteDialog.d0/D/onClick/O`。结果：

| 版本范围 | 满足情况 |
|---|---|
| 8.90.2 | **全部满足**，旧钩子能装上，是唯一的一个 |
| 8.84.0 到 8.96.4 的其余 11 个 | 星标三个方法在，但 7 个类里缺 1 到 3 个（8.93 起只剩 4/7），旧钩子预检不通过，等于没装 |
| 8.97.3 到 9.17.0（26 个） | `KingPositionService.B0/C0/V0` 一个都没有，旧钩子装不上 |

结论：
- 旧钩子只在 8.90.2 一个版本生效；其余 37 个版本里星标完全靠 `GuestVideoFavoriteHooks`。
- “新旧钩子冲突”只可能发生在 8.90.2：那里旧钩子在登录门之前接管了点击，新钩子的登录路由不会被触发，没有冲突。该版本未装机验证。
- 所以 8.84 到 8.96 其余 11 个版本补星标变色是必要的，不是兜底。


### 8.84 到 8.96 的星标变色（2026-10-29 13:50，已提交 `99b51a9a`，只在 9.16.1 回归过，8.9x 为静态结论）

8.84 到 8.96 共 12 个版本的星标缓存是 `FavorRepository` 本身，一个 Kotlin `object`（hostlab q49、q50）：构造函数私有；恰好一个类型是自身的静态字段（单例，名字混淆，叫 `a`）；恰好一个公开的 `(long, boolean)V` 实例方法（8.84 叫 `e`，8.86 到 8.96 叫 `l`）。8.97 以后这个类里没有任何字段，走原来的 9.x 静态路径。两条路径互斥。

实现：`locateStateWriter` 先试 9.x 的静态写入口，拿不到再试单例（`locateSingletonWriter`，按类型找字段，字段不唯一就不猜）。写入口统一成 `StateWriter.write(aid, favorited)`。8.9x 的 `l()` 会同时更新状态缓存和计数缓存，所以那 12 个版本的收藏数也会跟着变（未实机验证）。

验证：单测 36 个（新增 3 个：单例路径、歧义不猜、静态优先），变异检查（放宽成任意自身类型字段）恰好让“不猜歧义”那条失败。9.16.1 实机回归：视频页点星标弹窗、勾选完成后星标变粉，静态路径没变。8.84 到 8.96 的单例路径手机上没有那些版本，没法实机验证。

实机提醒：视频页有的有一行广告，会把操作栏整体往下推，星标不一定在固定坐标。点之前先看截图，别沿用旧坐标（这次误点出了分享面板）。


### 8.90.2 上新旧钩子是否冲突（2026-10-29 15:00，读代码结论，未装机）

不冲突。旧钩子拦截 `KingPositionService.B0`（星标点击）时设 `result = null` 跳过原方法，宿主不会再走到登录门；长按 `C0` 同样跳过原方法再自己调 `V0` 弹层。`GuestVideoFavoriteHooks` 挂在登录路由上（视频页 `RouterProxy.open`、Story 的 `BLRouter.routeTo`），而 8.90.2 的星标点击根本到不了那里，所以不会叠出第二个弹窗。Story 星标是 `StoryFavoriteWidget`，旧钩子不管它，新钩子管，两者各管各的点击。装机验证需要 8.90.2 的手机，目前没有。


### 收藏数不变的根因与 38 版本形状（2026-10-29 15:30，q51，未实现）

根因：9.16.1 里 `e.c(aid, favorited)` 本来就会更新全局的状态缓存和计数缓存，但页面显示的收藏数读的是页面自己的 `RelationRepository`（`com.bilibili.ship.theseus.united.page.view`，类名未混淆）里的 `StatData` 流。星标和计数的联动在该类的 `n(boolean)`：状态变化时 `x(f()±1)` 改页面计数，`w(boolean)` 才是写全局缓存。所以要让数字变，得拿到当前页面的 `RelationRepository` 实例。它是页面级对象，没有单例，要像 Story 星标登记 `StoryFavoriteWidget` 那样挂构造函数登记实例。

q51（38 版本）：
- `RelationRepository` 38/38 存在，构造函数形状 38/38 一致：`(Context, CoroutineScope, ViewReply, page.view.a, <混淆接口>, BusinessType)`，只有第 5 个参数的混淆名随版本变。可以按这个形状挂构造函数。
- `StatData` 38/38 存在，构造函数 38/38 都是 `(J, StatInfoData, J, J, J, J, StatInfoData, J)`。
- 公开 `(Z)V` 方法有多个，不能靠签名选出 `n`：8.84 到 8.96 有 8 个（名字分 3 组：G H I O R S T V / H I J P S T U W / L M N T W X Y a0），8.97 到 9.17 有 5 个，名字 20 个版本完全一致：`n o v w y`。
- 结论：能稳定登记实例，但“哪个 (Z)V 是改计数的那个”不能只看签名。8.97 以后的名字稳定，但 `n` 是否在每个 9.x 版本里都是“比较状态后改计数”的那个，还没有逐版反编译验证；8.84 到 8.96 的 8 个候选里是哪个也没确认。
- `StatData` 的“收藏数”是构造函数里第几个 long，也只在 9.16.1 确认过（第 2 个 long）。其余版本没核对，直接改字段有写错字段的风险。

下一步（需要决定）：在 8.84、8.97、9.0.1、9.10、9.17 这几个有代表性的版本反编译 `RelationRepository` 和 `StatData`，确认 `n` 的方法体和收藏数字段位置，再决定是按名字加方法体指纹保护，还是只在 9.x 做。收益只是画面上一个数字，风险是写错别的计数（点赞、投币），所以不建议在没确认前就实现。

## 收藏数跟着变（2026-10-30，`422c9923`）
- 宿主自己的流程是 `if (!repo.i()) repo.n(true)`（`kd-q35-favor.java` 第 68 行）。`RelationRepository.n(boolean)` 依次：`x(f() ± 1)` 改页面收藏数（`StatData` 状态流），`w(z)` 写星标缓存（`favorite.e.c`），再发 `Violet` 总线消息。它开头是 `if (i() == z) return`，而 `i()` 读的就是星标缓存。所以**不能先写缓存再调 `n`**（`n` 会直接返回、数字不动），要用 `n` 代替缓存写入口；这也是之前“只写缓存数字不变”的原因。
- 38 版结构核对（`r38-rr-*.java`、`r38-va-*.java`，`rr38-roles.py`、`rr38-prov2.py`）：
  - `RelationRepository` 构造器第 4 个参数（下标 3）是页面的视频编号提供者类（`page.view.a`），38/38；仓库里恰好一个该类型的实例字段。
  - 提供者里“按名排序的第一个无参 long 公开方法”就是 aid（名为 `a`），仓库对它调用的唯一 long 方法也是它，38/38。
  - 收藏方法是按名排序后的公开 `void(boolean)` 里：有 8 个的版本（8.84 到 8.96）取第 2 个（`H`、`I`、`M`），有 5 个的版本（8.97 到 9.17）取第 1 个（`n`），38/38；其余几个分别是点赞、点踩、投币和两个缓存写入。8.84 的 `H`、8.97 的 `n` 体内都是“计数 ± 1、写缓存、发消息”。
- 实现：`GuestPageFavorite`（构造器钩子弱引用登记实例，按 aid 取最近的一个，主线程调收藏方法）；数量不是 8 或 5、提供者字段不唯一时不识别；没找到仓库、不识别或调用抛异常，都退回写星标缓存。状态键 `guest_video_favorite_page`（installed/absent）、`_counted`（页面仓库接手）。
- 9.16.1 实机（Mi10，普通视频“甲乙丙丁”）：收藏数 4858，勾默认收藏夹点完成，星标变粉、数字 4859；再点星标取消勾选点完成，星标变灰、数字回到 4858。日志出现 `_page=installed`、`_counted=seen`，没有 `_repainted` 和 `_failed`。
- 单测 `GuestPageFavoriteTest` 10 个 + 钩子测试新增 5 个；变异（8 方法版本改选第 1 个）恰好让 2 个用例失败；全量 4188 个测试 0 失败，FastLint 0 错误 262 警告。
- 没做：Story 的收藏数（Story 没有 `RelationRepository`，数字来自 `StoryDetail` 自己的统计）；其余 37 版只有静态核对。

## 8.97 到 9.11 的「我的收藏」入口调查（2026-10-30，进行中）
- 现状：V2 菜单项的目标只有 9.12 到 9.17 能改写成 `bilibili://compose/favorites/kntr`（q20、q52：这条路由 9.12 起才登记）。8.97 到 9.11 共 16 个版本没有这条路由，游客点「我的收藏」仍被登录门拦住（入口钩子在这些版本上"保持宿主行为"）。
- q52（38 版）：旧收藏 Activity `tv.danmaku.bili.ui.favorites.FavoritesActivityV2` 在 38 个版本里都在，内部类形状一致；它在路由登记（`BR_` 类）里没有直接字符串（`fav_activity_registrations` 全 0），入口靠 `startActivity`。
- q52 路由字符串：8.97 到 9.00 的 `BR_` 登记里收藏相关路由为 0（`main/favorite`、`user_center/favourite` 都没有），9.01 起才有；9.12 起多出 `compose/favorites/kntr`、`kntr.favorites` 等。
- q53：8.97 到 9.11 每版持有 `bilibili://main/favorite` 的方法里有一个 `a(FragmentActivity, MenuGroup$Item)V`（路线图此前记作 `mine.provider.c`），`user_center/favourite` 只出现在一个 `invokeSuspend`（菜单跳转协程）里。
- **9.16.1 实机实验（无痕开，直接 `am start` 旧页）**：`FavoritesActivityV2` 能打开，无登录门，标题「我的收藏」、有「收藏夹/视频」标签；`x/v3/fav/tab` 发出并被模块应答（`guest_ignet_library_answered`），但内容区空白，即使本地库已有收藏。logcat 原因：`scheme bilibili://user_center/favorite_v2/favorite/folders is not Fragment`、`.../items is not Fragment`、`ComposeContainer: Target UnregisteredTarget not supported`。结论：9.16.1 上旧 Activity 的两个标签页要靠这两条路由装 Fragment，而路由现在指向 Compose 页，装不出来。**所以“直接开旧页”在 9.16.1 是死路**；8.97 到 9.11 是否同样，要看这两条路由的登记形态（q54 在查）。
- 实验教训：无痕库是内存里的，冷启动宿主就清空；要先在同一进程里收藏，再开页面。热启动再开同一个 Activity 不会重新请求。

- q54（38 版，8.84 到 8.96 共 12 版因 CI 运行层面失败，没有任务结果，不据此下结论）：9.03 到 9.17 共 15 版里 `favorite_v2/favorite/folders` 与 `.../items` 都登记在同一个路由类 `BR_4056…$collectRoutes_…$2`（9.16.1 的该类、9.06 的该类反编译都是 0 字节，没取到目标）；8.97 到 8.99 的登记类是混淆短名（`D81.w`、`F3.g4`、`Ea1.q`），所以 q20 按 `BR_` 前缀查出的"8.97 到 9.00 收藏路由为 0"是查询方式造成的假阴性，**8.97.3 的 `D81.w` 里确有 `bilibili://main/favorite`（别名 `main/drawer/favorites`、`user_center/favourite`，目标 `F3.H0`）**。`folders`/`items` 的目标在 8.97.3 是 `F3.Y`、`F3.a0`（混淆类，看不出 Fragment 还是 Compose）。8.97 到 9.17 每版持有 `main/favorite` 的菜单方法都是 `mine.provider.c.a(FragmentActivity, MenuGroup$Item)V`。
- **结论**：8.97 到 9.11 的收藏路由存在，被拦的是登录门（同 9.16.1）；但"放行后目标页能否显示本地数据"只能实机验证，而手上只有 9.16.1，旧页在 9.16.1 上是空白的。所以这 16 个版本目前拿不到经过验证的收藏入口方案，不交付只有静态证据的实现。可行路线：(1) 在 9.16.1 上做出“放行 needLogin 加绕开认证器”的通用方案，它不依赖 `compose/favorites/kntr`；(2) 找一台装 8.97 到 9.11 的设备实机验证目标页。

- **2026-10-30 在 Mi10 上降级到 9.0.0 的实机结果（负结果，但有用）**：用户手动装了官方签名的 9.0.0（签名摘要与 8.90.2、9.16.1 完全相同 `93ba270f…`），冷启动后模块**不注入**：logcat 与 LSPosed 日志里 `BilibiliInnocentLab` 与 `LSPosedFramework` 为 0 行，BiliRoaming 同样没注入，宿主进程 `/proc/PID/maps` 里 LSPosed/lspd/xposed 映射为 0。LSPosed 管理器显示已激活，`modules_config.db` 里作用域（模块到 `tv.danmaku.bili`）、启用、apk_path 都正确。装回 9.16.1 后立刻注入（`bil=235`）。**原因：9.0.0 这份 APK 的原生库只有 `armeabi-v7a`/`x86`，没有 `arm64-v8a`**（`aapt2 dump badging`），在 arm64 的 Mi10 上跑 32 位 zygote；本机的 NeoZygisk/LSPosed 只有 `lib64`（`/data/adb/neozygisk/lib64/libzygisk.so`，日志里是 `zygiskd64`），只注入 64 位应用。9.16.1 与 8.90.2 都是 arm64 包。**结论：这台设备只能验证 arm64 的宿主；32 位的 9.0.0 包验证不了任何钩子，要换 arm64 版的 8.97 到 9.11 包。** 9.0.0 的 APK 已拉到本机 `captures/host-900-base.apk`（206 MB，32 位）；它仍可用于静态分析（反编译、路由登记），不依赖注入。

- **Story 收藏数跟着变（2026-10-30，`b31c2871`）**：`StoryDetail.getStat()` 与 `Stat.getFavorite()I`/`setFavorite(I)V` 在 38 版一致（q55）；`StoryFavoriteWidget` 里恰好一个 `ImageView` 和一个 `TextView` 字段（字段名 `c/d` 或 `f/g`，按类型找）。`markFavorite` 只在 `RequestUser.getFavorite()` 与新状态不同时才把 `Stat.favorite` 加减 1（不低于 0），并在 UI 线程微调计数文本；只改纯整数文本，"1.2万" 这类缩写不猜。9.16.1 实机：8860 到 8861（星标变粉）再回 8860（变灰）。单测 7 个（`GuestStoryStarCountTest`，变异去掉全数字检查恰好让 1 个失败），全量 4195 个测试 0 失败，FastLint 0 错误 262 警告。没做：缩写计数（如 "8.9万"）不会变；其余 37 版只有静态核对。

## 9.0.0 实机验证：「我的收藏」入口（2026-10-30，已提交 `4f38b78b`、`c15fd96a`）
- 设备：用户装了 arm64 的官方 9.0.0（versionCode 9000200）。前一版只有 32 位库，被 64 位 Zygisk 拒绝注入；这一版模块注入正常（hooks=277 installed）。hostlab 数据集里没有 9.0.0，只有 9.0.1（`9010300`、`9011100`），反编译对照用 9.0.1，最后以 9.0.0 实机为准。
- 9.0.0 的旧 `FavoritesActivityV2` 是 Fragment 版（有 `FavoritesFolderTabFragment`、`FavoritesTabFragment`），没有 9.16.1 上的 `is not Fragment` 问题，所以能显示本地数据。
- **第一个缺口：`GuestNativeListHttpHooks` 在 9.0.0 为 `missing`**，旧写法把 8.90.2 的混淆名写死。hostlab q56（38 版）：`BiliCall` 的 `(Response)->retrofit2.Response` 转换方法，8.84 到 8.96 是 `p`（另有同形私有 `q`），8.97 起是唯一的 public final `a`；布尔字段 `l m n` 在 9.10 起变成 `m n o`，`m` 从“已执行”变成“已取消”，所以不能再读它，改用公开的 `isExecuted()`、`isCanceled()`，响应类型字段按类型找（38/38 唯一）。改为“旧版按名、新版按形状”。缓存 getter（8.90.2 的 `P2/Q2/Z2`）、`fa6.b` 路由辅助、`Wc` 在 9.0.x 里不存在，这些钩子现在各自独立，缺哪个在状态键里写 `adapted;skipped=...`。
- 实机（9.0.0，无痕开）：收藏页不再显示真实账号的 525 个内容，换成本地库；收藏一个视频后，“视频”标签里出现它（标题、作者、时长 5:08 都对）。
- **第二个缺口：登录门第二道**。游客点“我的 -> 我的收藏”，第一道 `needLogin` 放行（`guest_mine_menu_login_waived`），但路由层仍把 `bilibili://user_center/favourite` 改写成登录，登录页是半透明浮层，看起来没反应。栈：`MinePageManagerV2$switchTo$1.invokeSuspend -> BLRouter.routeTo$default -> routeTo`。
- 修复 `GuestFavoritesRouteHooks`：钩 `BLRouter.routeTo(RouteRequest, Context)`，游客且是收藏 URI 且 `compose/favorites/kntr` 未登记时，直接启动 `FavoritesActivityV2` 并回 `RouteResponse(OK, request)`。**踩到的坑**：`routeTo$default` 不传 Context，钩子里第二个参数是 `null`，必须用 `ReflectAccess.currentApplication()` 兜底。9.0.0 实机：游客从“我的”点“我的收藏”，顶层 Activity 变成 `FavoritesActivityV2`。
- 门禁：4208 个测试 0 失败（1 个无关跳过），FastLint 0 错误、262 条警告（与基线一致）。新增 13 个单测（`GuestNativeListHttpHooksTest` 6 个，`GuestFavoritesRouteHooksTest` 7 个），变异检查各抓到一条。
- **仍未做 / 未验证**：
  - 9.0.0 以外的 8.97 到 9.11 版本只有 hostlab 的静态证据，没有实机。
  - 8.97+ 的缓存写入没有拦：这些版本里 `P2/Q2/Z2` 已不存在，宿主会把本地列表写进自己的磁盘回退缓存（以真实 mid 为键），无痕下理应不写。要拦需要在 9.0.1 的 `T0`（写）/`U0`、`V0`（读）这几个静态方法上找稳定形状，或者钩 `com.bilibili.common.fallbackcache.e` 的写入方法。
  - 收藏夹卡片上的“N 个内容”文字在 9.0.0 是空的，进入收藏夹的点击也没有跳转（`fa6.b` 路由辅助在 9.0.0 不存在，真实文件夹详情的入口还没找）。
  - 稍后再看、历史、关注数、关注列表在 9.0.0 还没逐项验证。
  - 冷启动 9.0.0 会弹“欢迎来到哔哩哔哩”引导页，`guest_onboarding_gate=absent`（路线图第 18 行的已知缺口）。

## 8.97+ 缓存写入拦截（2026-10-30，已提交 `3eaf89b6`）
- 问题：8.90.2 的 `P2/Q2/Z2` 是三个收藏 ViewModel 取回退缓存的私有 getter，旧钩子把结果置 null。8.97 起 getter 没了，缓存放在 ViewModel 的 `public final kotlin.Lazy` 字段里（9.0.1：`FavoritesActivityViewModel` 3 个、`FolderListViewModel` 3 个、`FavoritesListViewModel` 3 个，其中一个的值是 `com.bilibili.common.fallbackcache.e`）。本地列表会被写进宿主磁盘缓存，键是 `mid_类型`。游客本来 mid 为 0，但无痕开着而宿主仍登录时键就是真实账号。
- 做法：getter 不存在时，给三个 ViewModel 的构造器挂 `after`，把值来自 `fallbackcache` 包的那个 Lazy 换成返回 null 的 Lazy。宿主代码里读写都有 `if (cache != null)` 守卫，所以不用改别处。
- 一个容易写错的点：Lazy 字段的类型是宿主自己的 `kotlin.Lazy`，模块带着另一份 Kotlin 运行时，`Lazy::class.java` 比不上，所以按类名比较，并用 `java.lang.reflect.Proxy` 实现宿主的接口。单测里这个差别看不出来，只有实机能证明。
- 实机（9.0.0，无痕开）：状态键 `guest_native_lists_http` 的 `skipped=` 从 `cache.*,folder_route,detail_owner` 缩成 `folder_route,detail_owner`，打开收藏页后出现 `guest_native_lists_cache_dropped = seen`；收藏夹、视频两个标签都正常渲染，无崩溃。
- 门禁：4212 个测试 0 失败（1 个无关跳过），FastLint 0 错误、262 条警告。新增 4 个单测，变异检查（去掉包名判断）让 4 个都失败。
- **没验证的**：宿主缓存文件本身没有查看，“没有写入”是由缓存为 null 推出来的，不是观察到的。8.90.2 走原来的 getter 路径，没有实机。8.97 到 9.11 里除 9.0.0 外只有静态证据。

## 9.0.0 收藏夹卡片点击（2026-10-30，已提交 `617614ce`，9.0.0 实机验证）

- 现象：9.0.0 游客进入「我的收藏」后，点收藏夹卡片没有任何反应。`fa6.b` 在 9.0.0 不存在，`folder_route` 钩子被跳过。
- 根因：9.0.1 反编译（`im.base.L` 的 default 分支，`FavoritesFolderTabFragment` 的卡片点击回调）显示，卡片点击调用 `BLRouter.routeTo(RouteRequest(jumpLink).requestCode(300).build(), fragment)`。这是 `(RouteRequest, Fragment)` 重载，而现有 `GuestFavoritesRouteHooks` 只钩 `(RouteRequest, Context)`。路由器的登录改写把 `bilibili://music/playlist/detail/<id>` 吞掉，所以界面不变。
- 注意：卡片里的 `recent_fav` 视频项点击走 `eh1.a.a(context, link)`，是另一条路径，和卡片本身无关。
- 实现：`GuestFavoritesRouteHooks.installFolderRoute` 钩 `(RouteRequest, Fragment)` 重载，游客且 URI 是 `bilibili://music/playlist/detail/<正数 id>` 时，用 `startActivityForResult(…, 300)` 启动 `MultiTypeListDetailActivityV2`（data=URI，extra `playlistId`），并返回 `RouteResponse(OK, request)`。启动失败则放行宿主路由。Fragment 类型走 `RouterNames.fragment`，缺失时只装收藏页钩子（状态 `guest_favorites_folder_route=absent`）。
- 实机（9.0.0，arm64）：状态键 `guest_favorites_folder_route=installed`、`guest_favorites_folder_route_opened=seen`；点卡片后栈顶为 `MultiTypeListDetailActivityV2`，页面标题「默认收藏夹」，创建者「本地账号」。
- **非空列表已验证（2026-10-30 20:16，9.0.0）**：用模块自己的收藏夹选择框收藏一个视频后，不重启宿主，从「我的」进「我的收藏」，点收藏夹卡片，详情页显示「默认收藏夹」「创建者: 本地账号」「1个内容」，下面是那条视频。
- 之前看到的「0 个内容」不是缺陷：无痕下本地库只存在内存里（`LocalVirtualLibrary.enterIncognito`/`resetIncognito`），宿主进程一重启，库就清空。我那次收藏后立刻 `force-stop` 再开，所以是空的，宿主 `no_backup` 里也找不到 `bil_guest_library.json`（无痕不落盘）。**验证无痕下的收藏列表时，不要重启宿主。**
- 截图在中途出现过与设备时间不符的旧图（时间戳和内容都对不上），已弃用，改用日志、栈顶 Activity 和设备时间一致的截图判断。
- 卡片自身（列表页那张卡）在非空时的封面与「N 个内容」文字没有再看一遍；详情页的数量是对的。
- 测试：`GuestFavoritesRouteHooksTest` 新增 6 例；夹具 `StoryFragment`、`StoryRouter` 新增 Fragment 重载；两处 `routeTo(request, null)` 因重载歧义改成显式 `null as Context?`。

## 2026-10-31：9.0.0 补测与两处修复（`934700ef`、`6c52a164`、`21df16e5`、`d60f185f`）

- 从手机拉了 arm64 的 9.0.0 `base.apk`（`captures/r4/h900/`，已忽略），按需用 jadx 反编译单个类；hostlab 新跑 q60（引导页首启等待门）、q61 与 q64（稍后再看添加入口），q62、q63 因 dexsql `LIKE` 不认 `_` 通配而为空。
- 引导页：9.0.0 的首启等待判断是 `pegasus.request.b.g()`（`isFirstStart() && a`），`PegasusFragment.onCreate` 调用它；9.16 是 `request.d.h()`。q60：`b`/`d` 合起来只有一个静态 `()Z` 的版本有 13 个。门改成跨两个类找唯一一个，数据与入口两层随之装上。实机连续三次冷启动不再弹引导页。
- 稍后再看添加：8.97 起入口是 `MainCommonService.addToWatchLater`/`batchAddToWatchLater`，实现类混淆（9.0.0 是 `tv.danmaku.bili.P`），动作是 `ui.watchlater.widget` 下的 `Function0`（9.0.0 的 `a/b/c`），交给 `LoginScopeKt.loginRequire`，未登录就去登录页。真正的添加是 `widget.f.a(...)`（9.0.0），协程里 `WatchLaterApiService.addV2` 经 `BiliCall.enqueue` 发 `/x/v2/history/toview/add`，`enqueue` 在工作线程上调 `execute()`，所以现有短路能接住。实机：首页卡片「添加至稍后再看」出宿主提示条，稍后再看页出现该视频。
- 关注数、关注列表、历史页登录横幅在 9.0.0 的结论与剩余工作见交接文档第 5、6 节。

## 2026-10-31 第二轮：关注列表与历史横幅（`c02df9db`、`374ffbfd`）

- q65、q67（38 版）：本地关注列表要复用的关系页 8.84 到 8.91 是 `BlackListActivity`，8.92 到 9.17 是 `BlackListFragment`（所以 9.16.1 上旧钩子其实也没生效），两者都经 `RelationApiManager.getBlackList(String, BiliApiDataCallback)` 取数据；`BlackListFragment` 都只有一个 `TextView` 字段，内部类和成员名几乎每版都变。「我的」页关注点击对象的 `toString()` 在 31 版都是 `ToAttentionClick`，所在类名每版不同。`AccountMineV2` 的长整型字段 26 版都是 `M a l r s t w`（`s` 是 following）。
- q66：历史页登录横幅是 `HistoryFragmentV3` 唯一的 `TintConstraintLayout` 字段（8.92 到 9.09 叫 `n`，9.10 起叫 `T`）；8.97 起 26 版没有 `isLogin()`。
- 9.0.0 实机：点「关注」打开「我的关注」原生页并显示本地关注；历史横幅三个标签都不出现。新发现的缺口（空间页关注按钮不认本地状态）记在交接文档第 6 节第 2 条。

## 2026-10-31 第三轮：空间页关注（`3c692894`、`a929a38e`）

- 探针（未提交）：9.0.0 空间页加载后 H5 先报一次服务端关系（`SpaceRelationWrapper` 的关系从 null 变 NO_RELATION，不广播）；点关注、取消关注时 H5 自己请求，再报新关系，包装类经 `updateFromH5` 调 `FollowStateManager.notify(2, true/false)`。从空间页进视频再返回不会重报。
- q68（38 版）：`LocalAuthorSpaceActivity`、`SpaceRelationWrapper`、`$updateFromH5$1`、`FollowStateManager.notify(JZ…)` 38 版都在；关系类 8.84 到 8.99 叫 `kntr.base.jsb.api.space.b0/c0/d0`，9.01 起叫 `SpaceRelation`；`SpaceRelationType` 的枚举名 38 版相同；包装类字段名 9.10 起整体后移一位（`a/c` 变 `b/d`）。
- 推回失败的证据：包装类的更新通道是 `replay=0` 的 `MutableSharedFlow`；推送时订阅数已大于 0，推送后包装类关系为 FOLLOW_HIM，H5 按钮仍是「＋关注」，延后 1.5 秒、4 秒都一样。
- 游客 `/x/v2/space`（vmid=2，签名的公开请求）：`data.relation=-999`、`data.guest_relation=-999`、`data.card.relation={"status":1}`。改写后 H5 显示「已关注」，说明它按这几个字段渲染。
- 门禁：4231 个测试 0 失败（1 个无关跳过），FastLint 0 错误、261 条警告；完整 Lint 这轮没跑。

## 2026-10-31 第四轮：星标改回宿主弹窗（`3e048601`、`20b31940`）

- 9.0.0 反编译：星标点击在 `KingPositionService$create$1$1$14$a.emit`，先过登录门 `Kg1.p.a`（只查 `BiliAccounts.isLogin()`，未登录就 `RouterProxy.open("activity://main/login/")`），通过后未收藏调 `playset.widget.favorite.f.d`（`batch-deal` 收进默认夹，失败才 `k(false)` 开弹窗），已收藏调 `f.f`（`unfav-all`）；长按直接 `k(false)`。收藏接口全在 `FavoriteService`，都是 `BiliCall`。
- q69（8.84 到 9.11 成功，9.12 起 10 版分析失败）：`FavoriteDialog` 38 版里 28 版有；8.97 到 9.11 共 16 版构造为 `(ComponentActivity, FavoriteDialog$b, ActivityResultLauncher)`，`$b` 的 20 参数构造描述符完全相同；8.84 到 8.96 是 `(ComponentActivity, FavoriteDialog$c, …)`。q69 第一次因 `method_strings` 列名写错（应为 `value`）全部失败，第二次修正。
- 实机走过三个坑：无痕的 `isLogin` 钩子先注册并设结果，后注册的钩子不执行，所以改成共享的 `GuestLoginScope`；`ComponentActivity.onActivityResult` 收不到创建页结果，改钩视频页实际生效的覆写；弹窗的 `ownerActivity` 与视频页不同，改为打开时自己记录所属页面。
- 门禁：4233 个测试 0 失败（1 个无关跳过），FastLint 0 错误、261 条警告；完整 Lint 这轮没跑。

## 2026-10-31 第五轮：全版本补齐（`56060759`、`592449ad`、`e084eb96`、`b79b2690`）

- q70（第一次用了 `length()`，结果全空；改用 `LIKE` 后正常）：38 版的 `PegasusFragment.onCreate` 都恰好调用 `pegasus.request` 包里一个静态 `()Z`，方法体都调 `EnvironmentManager.isFirstStart()`。所在类：8.84 到 8.96 是 `ApiManagerKt`（`s`/`r`），8.97 起是 `a`、`b`、`c` 或 `d`。q73：8.97 起这四个类合起来只有这一个静态 `()Z`；`ApiManagerKt` 里有 4 个。
- q71、q72：`FavoriteDialog` 38 版都在，`KingPositionService` 都有一个该类型字段；参数类 8.84 到 8.96 是 `$c`（19 参数普通构造 + 合成默认构造），8.97 起是 `$b`（20 参数带掩码），字段类型一致；`BizType.UGC`、`Scene.DetailPage` 38 版都在。
- 8.84 到 8.96 的稍后再看添加（q61、q64 复读）：`WatchLaterManagerV2.j/k/p` 直接调 `LoginScopeKt.loginRequire$default`，动作类是 `ui.watchlater.widget` 下的单字母类，现有放行规则适用。
- q65 复读：`BlackListActivity`（8.84 到 8.91）的数据回调 `$a` 持有页面字段，与 8.92 起的 `BlackListFragment` 同走 `RelationApiManager.getBlackList`。
- 9.0.0 实机：视频页关注后弹宿主「关注成功~」，`guest_library_follow_button_feedback=host`，`guest_space_follow_synced` 出现；星标弹窗回归正常；关注列表回归正常（`guest_library_native_follow_list=skipped-follow-page`）。实测中修了两处：钩子里的局部变量 `instance` 遮住了被点击的对象；关注监听字段的声明类型是实现接口的 lambda 类。
- 「我的」页关注数字：在 `BiliCall` 转换处改 `/x/v2/account/mine` 的方案，9.0.0 无痕下转换点没被触发（游客不请求），已撤回。
- 门禁：4234 个测试 0 失败（1 个无关跳过），FastLint 0 错误、261 条警告；完整 Lint 没跑。

## 2026-11-01：游戏中心日志上报

- 9.0.0 反汇编与 9.16.1 既有反编译均确认游戏 SDK 的日志上报使用 OkHttp POST `/collector/api/report`。配置请求 `/collector/admin/config` 由独立路径处理；空的 `data` 会让 SDK 间隔回退为 0，因此不能对整个 `biligame.net` 使用通用空成功应答。
- 改为仅拦截 `biligame.net` 域及子域的上述上报路径；配置路径维持原有清洗后联网。9.0.0 和 9.16.1 实机均捕获 `incognito_game_tracker_report=withheld-okhttp`，两版配置请求仍正常发出。对应测试覆盖域名边界、配置放行和 ignet 拒绝路径。
- 9.16.1 在真实命中后播放期间观察 180 秒、采样 10 次，PID 不变，Java 堆 PSS 为 76,428–127,120 KiB。9.0.0 上一版诊断代码同样通过 180 秒采样；最终版本再次观察到实际命中。当前 APK、4235 个测试和快速 Lint 均通过。具体版本、哈希和限制见交接说明第 10 节。

## 2026-11-03：关注分组基础与隐私补漏

当前进度和下一步以交接文档第 21 节为准。已提交本地分组 / 特别关注数据、原生协议和会话绑定；完整宿主菜单及分组页面仍未接入。38 个归档样本的相关菜单、字段、生命周期和路由已做静态核对，不能代替实机交互。

本轮还修复本地列表响应异常时可能恢复网络请求的问题，并在 9.16.1 观察到第三方统计地址的路径包含疑似设备标识：已针对观察到的路径拦截并脱敏，实机看到 OkHttp 路径命中，其他第三方请求仍待审计。构建、4307 项单测和快速 Lint 通过；详细证据、数据格式兼容边界和手机状态见交接文档第 21 节。

### 继续：分组请求安全边界

`8e60aa67` 已将带标记的分组请求接入本地响应，并让宿主克隆请求继承原会话；38 个归档样本的异步执行调用关系已静态核对。完整宿主菜单、弹窗和新建页还未接入，当前不能使用分组 / 特别关注；下一步先完成并验证页面绑定和路由恢复，详见交接文档第 22 节。
