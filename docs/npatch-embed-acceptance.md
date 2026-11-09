# embed 逐功能验收

本页随[当前路线图](npatch-implementation-roadmap.md)维护，当前范围为有线机 `13a7a291`、Android 16/arm64、B 站 8.90.2、固定修复版 833/签名等级 2。各案例注明 Manager/embed、Manager 安装状态与产物；不把一种模式的业务结果外推为另一种模式或标准框架实机结果。

本页 36 个案例的范围归属见[全目录复核](npatch-scope-audit.md#412-行目录与-36-个案例的完整覆盖)：8 个加载/产物/恢复案例、2 个重签名扩展案例、26 个通用业务回归案例。保留全部实际证据；业务案例里的通用修复不改称 NPatch 专有缺陷，剩余穷尽验收也不构成专项前置。该复核及[逐行范围归类](npatch-scope-matrix.tsv)对应当时的 412 行目录；2026-10-25 合并 main 后新增的 17 行不在该历史归类范围内。

## 全量目录与状态

[逐项矩阵](npatch-feature-matrix.tsv)由 `SettingsCatalog`、`DiagnosticCapabilityCatalog` 和 `DiagnosticFeatureRegistry` 交叉生成，包含 246 个设置和 183 个能力。设置行的 `links` 是相关能力，能力行的 `links` 是相关设置；`installer_group` 对应 Hook 注册组，具体进程适用性还需按安装器和实际业务核对。`local_scope` 保留纯界面、诊断或宿主前置授权项，避免它们因没有独立 Hook 被遗漏。

状态含义：`pending` 待验、`installed` 已安装、`observed` 已触发、`passed` 对应行为案例通过、`blocked` 当前案例失败、`not_applicable` 有证据证明不适用。每个非待验状态必须引用本页案例；安装成功不升级为行为通过。2B 的加载模式正式准入已按限定范围完成，不等于 429 行通用业务穷尽验收；embed 现为 372 pending、48 observed、7 installed、2 passed。F19.1 新增两行仅提升到双模式 `observed`，账号内流程仍待验。2026-10-25 合并 main 新增的 9 个设置和 8 个能力均为双模式 `pending`，原有验收状态与案例引用保留；构建和 JVM 测试通过不代表这些新增业务已在宿主内验证。

`NPatchAcceptanceMatrixTest` 校验目录版本、完整设置/能力集合、父注册组、状态值及案例引用是否存在。目录变化会使矩阵检查失败，更新模板在 `app/build/reports/npatch/feature-matrix.template.tsv`；合并新增行时保留已核验状态和证据，不整体用模板覆盖。Gradle 已把矩阵和本页计入 Test 输入，改动不会错误复用旧测试结果。矩阵只包含构建期公开元数据，不读取偏好、账号或令牌。

## 业务案例

### LOGIN

用户已在测试机完成登录。本轮个人页显示正式会员及个人空间入口；这是现有登录态的观测，不能表述为自动输入凭证完成登录。截图与视图树只保存在忽略目录，不把账号名、UID、联系人或令牌写入文档。

最终修复包同签名覆盖安装、完整启停/条款/Web 专项回归及再次冷启动后，个人页仍显示正式会员；`upgrade-my.xml` 与 `retained-account-cache.log` 记录保留结果。

### DOWNLOAD

通过已安装宿主的正常 UI，选择公开普通视频《bilibili上市宣传视频》，从播放器更多菜单进入“缓存”，使用宿主提供的 360P 免费画质。初始缓存为空；任务出现 `1.2MB/5.0MB`，随后进入“已缓存视频”，条目为 `5.0MB`。这验证真实下载完成，不能只凭 `:download` 已注入或 Hook 数量判断下载可用。

证据：`captures/npatch-2b/account-acceptance/download-before.xml`、`download-options.xml`、`download-progress.xml`、`download-finished.xml/.png`。没有测试会员付费资源、其它清晰度、存储介质或长任务断点恢复。

### CACHED_PLAYBACK

从已完成的缓存条目启动 `UnitedPlaylistActivity`，界面明确显示“离线缓存（1个视频）”、5.0 MB 条目和恢复进度，截图显示实际视频画面。没有关闭 Wi-Fi 或修改全局网络设置，因此本案例验证缓存入口播放，不声称完成断网环境验收。

最终修复包覆盖安装后再次验证：缓存条目与 5.0 MB 大小保留，重新打开后相隔两秒的截图显示不同视频帧。证据 `upgrade-cache.xml/.png`、`upgrade-cache-player.xml`、`upgrade-cache-player-1.png`、`upgrade-cache-player-2.png`。

证据：同目录 `cached-player.xml/.png`。普通在线视频播放已在选择样本时验证，未把缓存列表出现当作解码成功。

### ACCOUNT_GATE

通过设置搜索进入“获取哔哩哔哩 access_key”。取消确认后授权仍关闭；再次明确确认后开关开启。AI 总开关与强力模式中的“获取 access_key”单独启用，发布者屏蔽保持关闭，避免混淆两个分支。

旧代码冷启动拿到当前 `module_direct` 配置后，报告 `account=false, view=true` 和 `partial:missing-precheck`。检查原版 APK 的 DEX 方法声明确认：`BiliAccounts.getAccessToken()` 返回 `com.bilibili.lib.accounts.service.AccessToken` 接口，实际 model 类实现该接口。原探测器把 model 类当作声明返回类型，导致精确定位失败；修复使用 service 契约，单测保留真实接口与实现类的区别。

修复后同签名重新修补并覆盖安装，保留登录与缓存。真实宿主报告 `ai_declared_video_status=success`、`ai_declared_access_key_status=ready`。随后通过 UI 撤销授权，确认授权关闭且强力模式预检同步变为未开启；再冷启动宿主，当前进程明确报告 `precheck=false`，没有再报告账号状态。仅允许 `ready/not_logged_in/expired/unavailable` 状态离开探测边界；不导出、记录或持久化令牌，也不绕过授权开关。`ready` 证明当前账号状态可用，不能替代所有 AI 拦截效果或长期风控验收；未在实机主动制造令牌过期/登出，相关分支由单测覆盖。

证据：同目录 `access-cancelled.xml`、`access-authorized.xml`、`precheck-enabled.xml`、`account-methods.txt`、`account-logcat.txt`。初次未观测到 ready 的失败不能计入通过结果。

修复证据为 `account-fixed-logcat.txt`、`account-ready.log`、`access-revoked.xml`、`precheck-revoked-summary.xml`、`account-revoked-host.log`。账号修复当时的打包证据在 `patched/verified.json`：模块 SHA-256 `6132395828fc66a10b3505e54c8d1ff66c293a6ded2a1e8d427e4f8b8a7cfde5`，宿主 `05379e63ae90d79575f8511db0637618ec7f018196ab5ad90598902bc8fd3539`，保留原有双 ABI native 修复与同证书；后续复制修复包见下方案例。

### FREE_COPY_DESCRIPTION

展开公开视频 `BV1xx411c7mD` 的简介，长按正文后出现模块自由复制气泡，换行已归一。早期仅验证系统复制键，本轮补测真实触摸：新气泡内使用 `touchscreen` 首次长按，系统“复制/分享/全选”菜单及两个手柄正常出现；拖动手柄可改变选区，全选能覆盖两行，通过触摸菜单复制后在模块本地搜索框粘贴，两行内容完整。搜索框会把换行显示为空格，不能据此宣称原始换行字节已核对；跨行拖选、超长简介及外观组合仍待验，因此整项保留 `observed`。

早期证据：`captures/npatch-2b/account-acceptance/description-dialog.xml/.png`、`description-selection.png`、`description-pasted.xml`、`description-copy.log`。触摸证据：`captures/npatch-2b/copy-acceptance/20-description-touch-selection.png`、`21-description-dragged.png`、`23-description-all-selected.png`、`26-description-pasted.xml`。

### FREE_COPY_COMMENT

同一视频中，普通公开评论长按触发模块气泡，气泡内触摸长按显示系统菜单及选区手柄；点击“复制”后在模块本地搜索框粘贴，文字一致。关闭评论和简介两个开关并冷启动后，简介不弹模块气泡，评论恢复宿主“回复/分享/复制”等官方菜单；随后通过 UI 重新开启。未点击发送、回复、点赞、关注或外部分享。

含 `bv` 搜索链接及三个自定义表情的样本发现真实缺陷：气泡显示表情，但复制内容中只剩三个 U+200B。完整原文已捕获，失败发生在身份校验；宿主的文字搜索链接也使用 ReplacementSpan，旧逻辑将其底下的普通文字误当作 Emoji 槽位，导致可信原文被拒绝，退回可见占位符快照。

修复按底层内容区分绘制单元：普通文字保留在身份键里，仅完整表情标记、Unicode 表情或 U+200B/U+FFFC 占位区间归为 Emoji。只把确认的 Emoji Span 交给模型对齐及剪贴板语义映射；保留当前绑定、代次及防串评论判据。修复包实测 `model=3 spans=3 url=3 structural=3 applied=3`；触摸全选、复制并本地粘贴后，完整正文及三个 `[大哭]` 精确一致。并补齐正文读取器按实际 Class 缓存的回归，避免混排数据同名 getter 污染后续模型；这项缓存防护不是本例身份拒绝的根因。

最终设备回归和再次冷启动后，补测仅选中其中一个绘制表情，通过系统触摸菜单复制，本地粘贴精确为单个 `[大哭]`。证据 `85-single-emoji-selected.png`、`87-single-emoji-pasted.xml` 与 `emoji-copy-verification.log`；这验证选区语义转换，不把全文复制成功当作局部选择成功。

证据均在 `captures/npatch-2b/copy-acceptance/`：`13-comment-fresh-touch.png`、`16-comment-pasted.xml`、`58-both-off.xml`、`61-off-description-longpress.xml/.png`、`63-off-comment-longpress.xml/.png`、`66-both-on.xml`、`44-emoji-pasted.xml`（修复前失败）、`fresh-copy-host.log`、`76-identity-bubble.xml/.png`、`78-fixed-emoji-all.png`、`80-fixed-emoji-pasted.xml`、`emoji-copy-verification.log`。

`HostCopySelectionInstrumentedTest` 使用真实触摸长按、跨窗口查找系统菜单及坐标点击，再核对剪贴板选区；不以键盘快捷键替代菜单验证。无 Hook 的组件对照已通过，最终包另做宿主回归。输入源需分层：此 ROM 的宿主按钮优先 stylus，但新气泡内 stylus 长按只出现选区，touchscreen 可正常显示工具栏；不能把该输入源差异误报为产品缺陷。UI dump 默认只取活动窗口，系统浮动工具栏可能不在该树内，需结合截图或多窗口 UiAutomation。

最终包的真实 embed 宿主回归与上述触摸菜单回归合计 2 项、134.045 秒通过，日志 `final-device-regression.log`。Debug/AndroidTest APK、3670 项 JVM 测试（0 失败、1 跳过）及快速 Lint 通过；仍为 250 Warning/1 Hint，原 baseline 不变。当前产物及固定 native loader 哈希在同目录 `patched-identity/verified.json`，不沿用修复前的 `patched-fix1/` 候选。

覆盖安装及最终设备回归后再次冷启动，正式会员登录态、5.0 MB 缓存与缓存入口实际播放均保留，`upgrade-cache-player-1.png` / `-2.png` 为不同视频画面。证据同目录 `retained-account-cache.log` 与 `upgrade-*.xml/.png`；未清数据或切换全局网络状态。

本例验证当前 8.90.2 的普通评论及上述链接/表情组合；折叠长评和两类回复表面的后续证据见 `FREE_COPY_REPLIES`。纯表情、图片/推送等表面、复用压力和其它宿主版本仍待独立验收，评论整项记录为 `observed`。

### FREE_COPY_REPLIES

同一公开样本的短回复预览与专用“评论详情”回复列表能读取当前回复正文，不混入昵称或父评论。第二条折叠预览暴露独立缺陷：气泡仅有 46 字符的“昵称: 截断正文...”，实际正文为 50 字符。`NextLikableTextView` 保留完整富文本输入，但原校验使用了带昵称的截断显示文本，因此回退到了可见快照。

对照 8.90.2 的实际 DEX 和渲染源码：`C2` 的三个输入分别为完整文本、紧凑文本和点赞尾部；完整源保存在当前控件的 `d` 字段。前置昵称由 `Link.b` 生成 `f00.u` 自定义点击 Span，并非 Android `ClickableSpan`；冒号和空格由独立普通文本生成。修复仅对已核对的控件类和成员形状读取当前完整源，再以可信本次绑定正文、严格完整尾部匹配、前置点击区间及冒号空白间隔共同验证正文边界。未知形状、短公共片段、模型不匹配和表情数量不符继续回退；只缓存 Class/Member，不保留宿主实例，也不按昵称字符串或任意冒号猜测边界。

最终包再次长按折叠预览，气泡显示完整 50 字符正文；触摸长按、全选、复制，再粘贴到模块本地设置搜索框，逐字一致，昵称和截断尾部均未混入。短回复预览仍为正确的 23 字符正文；回复详情中“拉了半天总算是见了底”匹配当前回复，不是父评论。主列表多行折叠评论也保留完整八首曲目和末尾说明，共 433 字符；链接加三个 `[大哭]` 的既有样本保持正确。本轮长评案例核对的是气泡全文，未以此代替跨行剪贴板字节检查。

证据在 `captures/npatch-2b/comment-surfaces/`：`04-folded-preview-bubble.xml/.png`（修复前）、`21-final-folded-bubble.xml/.png`、`22-final-folded-selection.png`、`23-final-folded-all.png`、`25-folded-reply-pasted.xml`、`28-final-short-reply.xml/.png`、`30-final-emoji-bubble.xml/.png`、`33-final-reply-list-bubble.xml/.png`、`35-final-folded-main-bubble.xml/.png` 及 `reply-copy-verification.log`。中间候选的 Span 类型不符而保守拒绝，不计入通过结果；最终产物为 `patched-preview-link/verified.json`，仍使用固定双 ABI native 修复及相同证书覆盖安装。

源码门禁 `full-preview-link-build.log`：Debug/AndroidTest APK、3676 项 JVM 测试（0 失败、1 跳过）、快速 Lint 通过，250 Warning/1 Hint，原 baseline 不变；Gradle/Kotlin 守护进程已退出。新增回归覆盖完整源随 View 重绑更新、未知/超限源拒绝、实际点击契约、带冒号昵称、截断/错误模型和纯表情数量不匹配。当前证据只覆盖上述 8.90.2 表面，评论整项仍为 `observed`。

最终包真实宿主启停/条款/Web 与原生触摸复制合计 2 项、136.768 秒通过，见 `final-device-regression.log`。之后再次冷启动，会员登录态、5.0 MB 已完成缓存和缓存入口实际视频画面均保留，见 `retained-account-cache.log`、`upgrade-my.xml`、`upgrade-cache.xml` 及 `upgrade-cache-player-*.png`。未清数据或修改网络/旋转设置。

### PLAYER_SPEED

在同一最终宿主包上，通过模块正常设置页依次验证以下组合。使用公开视频 `BV1xx411c7mD`，新视频切换样本为正常推荐入口中的《bilibili上市宣传视频》；没有改系统方向锁定或旋转设置，横竖屏均由播放器正常进出全屏触发。

| 配置/操作 | 实际行为 |
|---|---|
| 两项跟随宿主、禁用长按关闭 | 原生长按出现“倍速播放中”；关闭态冷启动后倍速注册组为 `DISABLED`、0 Hook，真实长按后仍无模块倍速回调 |
| 默认 1.5×、长按 3.5× | 全屏控制条显示 `1.5X`；长按出现加速提示，松手恢复 `1.5X`。8 秒手势的约 9.9 秒记录窗口内进度 07:31→07:58，包含识别及截图开销，不当作精确倍率测量 |
| 播放中手动选 2.0X | 长按后恢复用户选择的 `2.0X`；切换另一公开视频后，新会话恢复默认 `1.5X` |
| 禁用长按开启，保留默认 1.5× 和保存的 3.5× | 设置明确提示保存的自定义值暂不生效；长按期间无加速提示，基础标签仍为 `1.5X`。同样 8 秒手势的约 9.8 秒窗口内进度 23:32→23:47，与基础播放一致 |

新增 `NPatchFeatureEvidenceInstrumentedTest` 配对核对当前进程的实际回调：必须显式传入公开能力 ID，校验 embed 模式、当前 `module_direct` 权威身份、已完成安装链及无运行错误。`applied` / `observed` 要求对应阶段真实出现；`quiet` 只核对无已报告回调，允许协议省略未启用叶项，不能单凭缺少记录判定开关关闭或业务成功。它不启动宿主、不操作界面或改变测试开关；身份通过既有权威记录 API 求值，协议及授权边界不变。

默认倍速和自定义长按两项的 `APPLIED` 查询通过；现有自定义长按实现完成构造参数写入和字段值回读后才报告该阶段。禁用组合的默认倍速/禁用回调均为 `APPLIED`，自定义长按无已报告回调。全部恢复跟随宿主、禁用关闭后，原生长按恢复且注册组为 `DISABLED / 0 Hook`，三叶项无已报告回调。负对照故意要求已关闭能力为 `APPLIED`，检查器按预期拒绝；无参数运行以 assumption 跳过，避免普通测试运行把未安排业务场景当成通过。早期检查器要求停用叶项必须存在，遇到协议省略而失败，已修正为明确的 `quiet` 含义，不把该失败计入通过结果。

证据在 `captures/npatch-2b/player-acceptance/`：`06-speed-baseline.xml`、`26-speed-configured.xml/.png`、`31-custom-full-controls.png`、`32-before-custom-hold.png`、`33-custom-hold-active.png`、`34-custom-hold-released.png`、`39-manual-base-restored.png`、`43-new-video-default.png`、`55-disable-saved.xml/.png`、`60-disabled-before-hold.png`、`61-disabled-hold-active.png`、`62-disabled-hold-released.png`、`71-speed-follow-host.xml/.png`、`74-follow-host-longpress.png`、`75-follow-host-release.png`、两份 `*-hold-timing.log` 及 `runtime-feature-results.log`。查询结果为 `default-and-hold-evidence.log`、`disabled-hold-final-evidence.log`、`follow-host-post-gesture.log`；负对照及无参数边界见 `negative-control-result.log`、`negative-control-evidence.log` 和 `no-arguments-guard.log`。

本轮仅新增实机检查测试和文档：AndroidTest APK 与快速 Lint 通过，250 Warning/1 Hint，主源码分析复用已通过缓存；日志 `evidence-test-final-build.log`。生产模块与宿主仍为 `FREE_COPY_REPLIES` 的相同字节，复用其 3676 项 JVM 与主 APK 门禁。Gradle/Kotlin 及本任务日志进程已停止。当前案例覆盖上述普通视频与组合，未覆盖全部倍率、PGC/Story/缓存播放器、prepared 动态发现路径和后台/拖动组合；三设置及三能力记录为 `observed`，不把安装期 `awaiting-prepared` 提示或局部证据提升为全场景支持。

### COMMENT_SEARCH_LINKS

在当前 8.90.2 原生评论列表，以 `BV1xx411c7mD` 中含 `bv` 搜索链接和三个自定义表情的公开评论完成“关→开→关”对照。关闭净化时，关键词为蓝色并带搜索图标，实际点击进入 `BiliMainSearchActivity`，查询栏显示 `bv`。

通过模块 UI 开启“关闭评论关键词搜索链接”，冷启动取得新配置后，`bv` 变为普通文字，搜索图标消失，完整正文和三个表情保留。点击相同位置按普通评论行为打开回复详情，前台仍为 `UnitedBizDetailsActivity`。现有请求侧通道报告 `search_word_disabled=true`，当前身份查询确认 `comments_search_links_removed` 为 `INSTALLED / APPLIED`；不以该日志单独替代界面对照，也不把此案例当作三道防线分别故障注入后的验收。

同一开启态继续长按，模块气泡完整保留 `bv` 和三个表情。气泡内使用真实触摸全选、复制，再仅粘贴到模块本地搜索，逐字等于原正文及三个 `[大哭]`，当前 `free_copy_comment_enabled` 也有 `APPLIED`。随后通过 UI 关闭净化并再次冷启动，蓝色关键词、搜索图标和实际 `bv` 搜索均恢复；当前净化注册组为 `DISABLED / 0 Hook`，对应能力无已报告回调。设置页在返回时可能重建折叠菜单，自动化重新核对实际控件后才修改开关；中间定位失败没有计入开启态证据。

证据在 `captures/npatch-2b/comment-search-acceptance/`：`06-search-removal-off.png`、`08-baseline-comments.xml/.png`、`09-baseline-search-opened.xml/.png`、`baseline-search-activity.txt`、`17-search-removal-enabled.xml/.png`、`19-enabled-comments.xml/.png`、`20-enabled-word-tap.xml/.png`、`enabled-word-tap-activity.txt`、`22-enabled-copy-bubble.xml`、`23-enabled-copy-selection.png`、`24-enabled-copy-all.png`、`26-combined-copy-pasted.xml`、`31-removal-off-restored.xml/.png`、`33-restored-comments.xml/.png`、`34-restored-search.xml/.png`、`restored-search-activity.txt` 和 `search-copy-verification.log`。当前回调查询两次通过，见 `enabled-feature-evidence.log`（2.004 秒）、`restored-off-feature-evidence.log` 及 `runtime-feature-results.log`。

本轮只更新验收文档与矩阵，生产/测试 APK 均沿用上一单元，复用已通过门禁，仅重新核对矩阵。未发评论、点赞、关注或外部消息，结束时本项恢复关闭。当前只覆盖上述原生列表、回复详情及自由复制组合；其它评论管线、表面和防线降级仍待验，两目录行记为 `observed`。

### RELATED_COMMERCIAL

在 `BV1xx411c7mD` 详情页，关闭所有相关推荐类型及匹配增强时，截图捕获明确标为“广告·查看详情”的京东推广卡；普通《【全弹幕】av10388 武器A》作为保留对照。推广卡没有出现在默认 accessibility XML 中，因此不能用 XML 搜不到广告文案证明过滤成功。服务器下发的推荐会变化，“课堂”角标也不能单独当作商业推广证据。

通过正常设置 UI 仅勾选“过滤商业推广”，其它四种类型、匹配增强及强力模式保持关闭。旧包虽报告移除条目，但同时出现“详情页推荐响应列表写回失败”，当前组的 `runtimeError` 检查失败，不计入通过。原版 8.90.2 DEX 证明 `ViewReply.relates_` 的声明类型为 `Internal$ProtobufList`；普通 Java List 无法赋给该字段。修复使用原列表契约生成独立、同类型的不可变副本，保持保留条目的身份，并清除当前宿主已验证的序列化长度与 hash 缓存；未知消息形状、别名副本和缓存重置失败均拒绝或回滚，不原地修改共享源列表。

最终包正常前台恢复模块、冷启动宿主后，当前 `module_direct` 身份下 `video_relate_filter` 的真实 `APPLIED` 检查通过（0.319 秒），没有 runtime error；新的已加载截图显示普通对照条目保留且无京东推广卡。此前专项回归修改配置后的旧快照不匹配、未重新打开模块时的许可不可用均被检查器拒绝，没有放宽身份或许可边界、没有计入通过。随后通过 UI 取消唯一勾选并保存 0 项，再冷启动，当前组与商业推广叶项的 `quiet` 检查通过（0.390 秒）；该结果须与 UI 关闭状态配对，不能由叶项缺席推断设置关闭。

证据在 `captures/npatch-2b/related-ad-acceptance/`：`08-related-second-baseline.png`、`11-cm-selected.png`、`cm-enabled-group-evidence.log`（修复前失败）、`view-reply-members.txt`、`protobuf-list-members.txt`、`GeneratedMessageLite.java`、`writeback-candidate-group-evidence.log`、`16-writeback-warm-1.png` / `-2.png`、`final-renewed-enabled-group-evidence.log`、`21-final-enabled-settled.xml/.png`、`23-final-cm-on-before-off.xml/.png`、`24-cm-off-selected.xml`、`25-cm-off-saved.xml/.png`、`final-off-group-evidence.log`、`26-cm-off-detail.png` 与 `related-ad-verification.log`。只有最终包证据用于最终验收，前期候选不能代替最终包。

最终产物 `patched-writeback-final/verified.json`：模块 SHA-256 `7f0df1b8969c5ce4564489f2b5b893bcc5d1def458634cd5a7f85e5bb48ea896`，宿主 `ab0c1406c339f9c60619a4cb99ac4abd52b1b7a4bdfc4f66eaad27e8e5af08b3`，相同证书覆盖安装，固定双 ABI native loader 不变。`writeback-final-build.log` 中 Debug/AndroidTest APK、3680 项 JVM 测试（0 失败、1 跳过）及快速 Lint 通过，250 Warning/1 Hint、原 baseline 不变；`full-jvm-suites.json` 保留全量结果。最终宿主启停/条款/Web 与原生触摸复制回归共 2 项、137.274 秒通过，见 `final-host-regression.log`。

最终覆盖安装及完整设备回归后，登录、5.0 MB 缓存和缓存入口实际播放均保留，见同目录 `retained-account-cache.log` 与 `upgrade-*.xml/.png`；相隔两秒的两帧显示实际解码画面，没有关闭网络。关闭态另做五次重新打开，普通推荐正常变化，但未再次下发同一张明确广告；`27-cm-off-warm-1..5.png` 不能冒充“同一广告关闭后恢复”的证据。

本项仍记为 `observed`：关闭后的同类广告重现尚待稳定样本；该包仅上报父组运行计数，商业推广叶项没有独立运行归因；当时 8.90.2 报告 `partial:missing-service-layer`，后续定位修复见 `RELATED_SERVICE`。其它类型、匹配增强、强力模式、其它进程和宿主版本未在本例验收，不能把本次响应写回修复扩展为全相关推荐通过。

### RELATED_SERVICE

对照同一 8.90.2 原版 DEX，服务类包含多个 `(r2) -> RunningUIComponent` 方法。旧定位按参数包名和返回类型打分，多个专用组件构造器并列，因而拒绝定位。`C0` 的实际指令先调用 `r2.y()` 读取 `RelateCardType`，再按枚举分发；调用者包括正常列表构建 `D0(List)` / `X1(RelateTab,List)` 及撤销路径。证据 `captures/npatch-2b/related-service-acceptance/dispatcher-dex-proof.txt`、`dispatcher-callers.txt`；声明和反编译源码在上一案例的 `detail-relate-service-members.txt`、`detail-relate-item-members.txt`、`DetailRelateService.java`。

修复只在完整已核对契约成立时优先 `C0`：精确 DTO 类 `r2`、精确组件返回类型、实例字段 `a` 的枚举类型及必要常量、实例 getter `y()` 返回同一枚举。保留旧 `d` 入口和最高分并列拒绝；不随意挑选同签名专用构造器。规则代次从 72 提升到 73，使旧缺失缓存重新定位；JSON 形状和 schema 不变。三项新增 JVM 回归覆盖真实同签名干扰、未验证 DTO/并列拒绝及旧入口。

最终包当前 `module_direct` 身份检查通过（0.348 秒）：父组由 `PARTIAL / 5 Hook` 变为 `INSTALLED / 6 Hook`，`observed=1 / applied=1`，没有 runtime error；该包商业推广叶项为 `INSTALLED / 6 Hook`、`observed=0 / applied=0`。正常 UI 仅商业推广开启，普通推荐对照保留；原服务层定位错误消失。证据 `06-cm-selected.xml/.png`、`08-final-enabled-detail.xml/.png`、`final-enabled-group-evidence.log`、`final-feature-results.log` 和 `service-host.log`。父组回调不能区分响应层与服务层，也不能替代叶项命中，本例只确认服务层定位及安装缺口修复；叶项归因后续见 `RELATED_RUNTIME`，服务层独立业务仍待验。

最终目录 `patched-service-final/verified.json`：模块 SHA-256 `e80a8c17e394a7080a3ee33e1d1a85c9ef606e91cc75b1ba1ff732b3b1c9ddbe`，宿主 `b82e726559f337d6e30c12aa1b80e484f8bfe2ce8ebe96b943bb86ee8d4df269`；相同证书覆盖安装、双 ABI native loader 不变。`service-final-build.log` 中 Debug/AndroidTest APK、3683 项 JVM（0 失败、1 跳过）、快速 Lint 通过，250 Warning/1 Hint，baseline 未扩大。最终宿主启停/条款/Web 与原生触摸复制共 2 项、135.092 秒通过，见 `final-host-regression.log`。

最终 UI 取消唯一商业推广勾选并保存 0 项，冷启动后当前组/叶项的无回调检查通过（0.275 秒），普通推荐保留；证据 `14-cm-off-selected.xml`、`15-cm-off-saved.xml/.png`、`16-final-off-detail.xml/.png` 及 `final-off-group-evidence.log`。升级后的登录、5.0 MB 缓存及实际缓存播放保留，见 `retained-account-cache.log` 和两张 `upgrade-cache-player-*.png`；未修改网络或旋转设置。商业推广矩阵仍为 `observed`，不因安装缺口修复升级为完整业务通过。

### RELATED_RUNTIME

此前服务层修复包的父组有实际应用，商业推广叶项却只有安装状态。此次让响应层和服务层返回实际移除原因，沿原过滤顺序，只将每张卡首个命中条件归给对应能力；未命中条件保持无叶项阶段。未知类型与选区标签暂保留父组证据。宿主桥继续保存当前进程的有界阶段证据，不能据此推算所有可能匹配条件或全程命中总量。内部列表写回失败时，实际受影响叶项同步报告 `ERROR`，getter 仍保留过滤返回值；诊断不会仅凭 `APPLIED` 将其认作无错误成功。

七项新增 JVM 回归经真实 Modern 回调核对：混合卡片分别归因、已启用但未命中项不冒领、CM/SPECIAL 载体回退、强力模式自身归因、数值过滤不冒领类型、重复写回失败只标记受影响叶项，以及当前 `r2` 服务 DTO 被拦截后原构造方法不执行。保留既有过滤判据，公共布尔匹配和原因提取共用类型判断。

最终有线机通过正常 UI 同时开启商业推广与游戏推荐，其它三类、匹配增强和强力模式关闭。冷启动后仍保留普通推荐对照，当前 `module_direct` 身份及无运行错误检查通过（0.516 秒）：

| 当前项 | 安装 | OBSERVED | APPLIED |
|---|---|---|---|
| 父组 `video_relate_filter` | INSTALLED / 6 Hook | 1 | 1 |
| 商业推广 `video_related_commercial_removed` | INSTALLED / 6 Hook | 1 | 1 |
| 已开启的游戏项 `video_related_game_removed` | INSTALLED / 6 Hook | 0 | 0 |

证据 `captures/npatch-2b/related-runtime-acceptance/06-selected-过滤游戏推荐.xml`、`06-cm-game-selected.png`、`07-cm-game-saved.xml`、`08-final-double-enabled-detail.xml/.png`、`final-double-enabled-evidence.log`、`final-double-enabled-results.log` 与 `runtime-verification.log`。游戏项只是此次未命中的负对照，未因此将游戏过滤矩阵提升为行为通过；服务层独立实际表面和其它过滤条件仍需各自案例。

最终目录 `patched-runtime-final/verified.json`：模块 SHA-256 `465851eb985e3deb8dac8d470cd68dd78afeefbe71fd54ca308775548091a0d3`，宿主 `637b9261de1c384e86e32d9676b43657d3133a6eb9818b1c0178f60fb5c8b60b`，同证书覆盖安装，native 字节不变。`runtime-build.log` 中 Debug/AndroidTest APK、3690 项 JVM（0 失败、1 跳过）和快速 Lint 通过，250 Warning/1 Hint，原 baseline 不变；最终宿主启停/条款/Web 与触摸复制共 2 项、140.606 秒通过，见 `final-host-regression.log`。

通过正常 UI 取消两项并保存 0 项，冷启动后当前父组与两个叶项均无已报告回调，检查通过（0.410 秒），普通推荐保留；证据 `14-both-off-selected.png`、`15-both-off-saved.xml`、`16-final-off-detail.xml/.png` 与 `final-off-evidence.log`。最终更新后登录、5.0 MB 缓存和实际缓存播放保留，见 `retained-account-cache.log` 与两张不同画面的 `upgrade-cache-player-*.png`；没有修改网络或旋转设置。商业推广保留 `observed`，其它过滤条件保持逐项待验。

### RELATED_DURATION

同一普通视频详情页的推荐列表，基线同时显示 2:24（144 秒）和 0:50 两个稳定对照；其它相关推荐类型、匹配增强和播放量范围关闭。通过正常设置 UI，每次保存后冷启动，并配对当前 `module_direct` 身份及无运行错误检查：

| 时长范围（秒） | 实际普通视频对照 | 当前进程检查 |
|---|---|---|
| 两端留空 | 144 秒、50 秒均出现 | 父组/时长无回调，0.525 秒通过 |
| 最短 100，最长留空 | 144 秒保留，50 秒从原列表移除；后续可见 3:59、7:55、2:03 | 时长 `APPLIED`，0.484 秒通过 |
| 最短留空，最长 100 | 50 秒和 1:30 保留，144 秒对照移除 | 时长 `APPLIED`，0.378 秒通过 |
| 最短 144，最长 144 | 恰好 2:24 的对照仍保留，验证两端包含边界 | 时长 `APPLIED`，0.556 秒通过 |
| 再次清空两端 | 144 秒、50 秒对照均恢复 | 父组/时长无回调，0.273 秒通过 |

另将最短设为 144、最长保持 100，UI 明确提示“最长时长不能短于最短时长。”并保留输入弹窗；未保存草稿关闭后原最长 100 的摘要仍保留。随后重新输入有效相等区间完成验收。自动化只在输入法实际显示时发送返回键，最终相等区间以恢复后的 25/26 号证据为准。

证据 `captures/npatch-2b/related-duration-acceptance/05-duration-baseline-dialog.xml/.png`、`07-duration-off-baseline.xml/.png`、`10-min-configured-values.xml/.png`、`11-min-only-detail.xml/.png`、`12-min-list-lower.xml/.png`、`16-max-configured-values.xml/.png`、`17-max-only-detail.xml/.png`、`20-invalid-range-rejected.xml/.png`、`25-equal-range-configured-values.xml/.png`、`26-equal-range-detail.xml/.png`、`30-duration-disabled-values.xml/.png`、`31-duration-restored-detail.xml/.png`、各阶段 `*-evidence.log` 和 `duration-verification.log`。当前回调汇总保存在 `final-feature-results.log`。

本轮生产/测试 APK 与源码均沿用 `f45801bf` 对应的 `patched-runtime-final/`，引用见 `reused-production-build.json`；复用其 3690 项 JVM、快速 Lint、最终宿主/触摸复制 2 项回归和相同包哈希，仅重查更新后的矩阵。两项共享设置及详情页时长能力记为 `observed`；首页能力、课程/商品等其它表面、独立服务层路径和跨条件组合继续待验，不将共享设置的详情页结果外推到首页。结束时两端均留空。

### RELATED_PLAY_COUNT

沿用全部相关推荐类型关闭、时长两端留空的基线，正常设置 UI 输入播放次数整数。普通投稿对照分别显示 471.9 万和 256.5 万；每次保存后冷启动，并核对当前 `module_direct` 身份与无运行错误：

| 播放量范围 | 普通投稿对照与当前检查 |
|---|---|
| 两端留空 | 两个对照均出现，父组/播放量无回调，0.349 秒通过 |
| 最低 3,000,000 | 471.9 万对照保留，256.5 万对照移除；播放量 `APPLIED`，0.381 秒通过 |
| 最高 3,000,000 | 256.5 万对照保留，471.9 万对照移除；播放量 `APPLIED`，0.430 秒通过 |
| 3,000,000～5,000,000 | 区间内 471.9 万对照保留，原低于下限/高于上限的普通投稿移除；播放量 `APPLIED`，0.303 秒通过 |
| 该播放量区间加最长 100 秒 | 时长、播放量两个叶项均 `APPLIED`，0.361 秒通过；0:12、311.6 万播放的交集对照保留，原两个对照不再出现 |
| 再次清空两类范围 | 原两个对照恢复；父组及两个叶项无回调，0.462 秒通过 |

UI 还拒绝最低 3,000,000、最高 1,000,000 的反向区间，明确显示“最高播放量不能低于最低播放量。”并保留输入弹窗。组合案例中，原 2:24 高播放量视频超出时长上限，原 0:50 视频低于播放量下限；保留的 0:12 / 311.6 万普通投稿同时满足两类条件。当前诊断只归因实际执行的首个过滤判据，不把两个开关同时开启当作两个能力都成功。

证据 `captures/npatch-2b/related-play-count-acceptance/01-play-count-baseline-dialog.png`、`02-play-count-baseline-detail.xml/.png`、`05-min-configured-values.xml/.png`、`07-min-visible-controls.xml/.png`、`10-invalid-play-range.xml/.png`、`13-max-configured-values.xml/.png`、`14-max-only-detail.xml/.png`、`18-band-configured-values.xml/.png`、`20-band-visible-controls.xml/.png`、`23-combo-duration-configured-values.xml/.png`、`24-combined-ranges-evidence.log`、`25-combined-visible-controls.xml/.png`、`28-duration-off-values.xml/.png`、`32-play-count-off-values.xml/.png`、`33-both-ranges-restored-detail.xml/.png`、`34-restored-controls-*.xml/.png`、各阶段 `*-evidence.log`、`play-count-verification.log` 与 `final-feature-results.log`。

本案例验证上述普通投稿的有效计数及范围交集；其它卡片表面的读取覆盖、首页、直播、未知计数和独立服务层路径仍需各自案例，不根据其它卡片的可见数量文案推断其内部类型或读取成功。两项共享设置与详情页播放量能力记为 `observed`，时长上限/能力补充本组合引用，首页能力继续待验。本轮复用相同 `f45801bf` 生产/测试 APK 和已通过的 3690 项 JVM、快速 Lint、140.606 秒最终包回归，只重查矩阵；包哈希引用 `reused-production-build.json`。结束时两类范围均留空。

### RELATED_AUTHOR

2026-10-22 使用相同有线环境和公开入口 `BV1xx411c7mD`，独立验证详情页相关推荐作者名单。作者 UID 来自普通投稿跳转后的公开空间 Intent，不读取当前登录账号资料。时长、播放量、发布时间和其它相关推荐过滤保持关闭。

修复前，完整公开 UID `208259` 已保存，但《bilibili上市宣传视频》仍出现；严格当前身份检查报告 `No applied callback for video_relate_filter`。原适配只有旧 `view.v1.Relate#getAuthor → Author#getName/getMid`，新版 `RelateCard` 把作者放在 `getBasicInfo → CardBasicInfo#getAuthor → Owner`。已核对实际 APK 成员和宿主映射器 `j.t` / `AuthorRepository.b.c`：新版作者显示名使用 `Owner#getTitle`，mid 使用 `getMid`。修复补齐这两条完整成员链，保留旧链；`RULE_VERSION` 73→74 使旧定位缓存失效，JSON/schema 不变。只从作者对象读取显示名，不将投稿标题充当作者。

| 修复后名单 | 普通推荐卡对照与当前身份检查 |
|---|---|
| 完整 UID `208259` | 对应投稿移除，武器 A 等其它普通推荐保留；父组及作者叶项 `APPLIED`，0.482 秒通过 |
| 仅 UP 名 `McIDE` | 保存归一为 `mcide`，该公开 UP 的 0:17 失效投稿卡移除，UID 对照恢复；父组及作者叶项 `APPLIED`，0.412 秒通过 |
| 前缀 `20825,Mci` | 保存归一为 `20825,mci`，上述两条卡片均出现；父组已执行、作者叶项无命中，0.446 秒通过 |
| 混合 `208259,McIDE,208259` | 保存归一并去重为 `208259,mcide`，两条目标卡片移除，河蟹/三体等其它普通推荐保留；父组及作者叶项 `APPLIED`，0.506 秒通过 |
| 再次清空名单 | 两条目标卡片均恢复；父组 `DISABLED`、0 Hook、无回调，作者叶项无回调，0.459 秒通过 |

修复前的 `15-control-prefix-no-match-evidence.log` 只能作为旧包观测；最终精确匹配对照采用修复后的 `28-fixed-prefixes-no-match-evidence.log`，不能把“作者链根本不可读”当成前缀匹配正确。每次查询仍严格校验当前主进程、授权、安装完成、DIRECT 来源和完整配置身份，不因业务失败放松检查。诊断计数是有界阶段证据，不作为完整推荐命中统计。

证据位于 `captures/npatch-2b/related-author-acceptance/`：`control-public-author-identity.log`、`card-basic-info-members.txt`、`owner-members.txt`、`RelateCardMapper-j.java`、`AuthorRepository-b.java`、`18-full-author-enabled-evidence.log`、`19-full-author-failure-current.xml/.png`、`20-fixed-full-author-*.log/.xml/.png`、`21-fixed-other-controls.xml/.png`、`23-name-saved.xml/.png`、`24-name-enabled-*.log/.xml/.png`、`25-name-visible-controls.xml/.png`、`28-fixed-prefixes-no-match-*.log/.xml/.png`、`29-prefix-controls.xml/.png` 和 `author-verification.log`。公开推荐会自然变化，因此同时要求实际叶项归因和界面对照，不用单张消失截图证明成功。

混合与恢复证据另见 `31-mixed-saved.xml/.png`、`32-mixed-enabled-evidence.log`、`33-mixed-controls.xml/.png`、`35-cleared-values.xml/.png`、`36-authors-off-evidence.log`、`37-restored-controls.xml/.png`。名单最终留空。

新增 5 项 JVM 回归覆盖新旧成员链序列化、精确 UID、作者显示名、缺失/异常/非正 mid 安全放行及旧直接作者路径；最终 3695 项 JVM（0 失败、1 跳过）、两个 Debug APK、快速 Lint 通过（250 Warning / 1 Hint，baseline 不变）。模块 SHA-256 `1d36c7554ea87b131f0b7c0a7f579e76ea8e65d5e15c4db6575869205b2035d6`，宿主 `c3b6cdb55fb5f1f2048ff7735ac48a5edb17272557c755e0cd6778358f43556a`；固定 patcher/native/证书复用上一单元，见 `patched-author-final/verified.json`。作者设置及详情页能力记为 `observed`，不外推首页、其它宿主版本、服务层独立路径或所有卡片类型。

最终包再次通过真实 `NPatchEmbeddedHostInstrumentedTest` 与 `HostCopySelectionInstrumentedTest`，2 项、138.851 秒，见 `final-host-regression.log`；覆盖当前配置、启停、条款、Web 和原生触屏选取复制。同签名升级及回归后，正式会员登录态和 5.0 MB 既有缓存均保留；`retained-account-cache.log`、`upgrade-cache.xml/.png` 和已查看的 `upgrade-cache-player-1.png` / `-2.png` 证明缓存播放页继续解码。没有据此声明断网或其它资源恢复场景通过。

### RELATED_TAG

2026-10-22 核对实际 8.90.2 APK：旧 `view.v1.Relate#getTagName` 与新版 `RelateCard#getGame → RelateGameCard#getTagName` 是不同成员链；宿主 `j.y(RelateGameCard)` 将后者映射到游戏卡模型。原定位只包含旧直接链，本轮补齐新版两级链并保留旧链，规则版本 74→75 使旧缓存重新定位，JSON/schema 不变。不从投稿标题、作者或推荐理由推断标签；没有标签的卡片继续保留。

新增 5 项 JVM 回归覆盖新旧标签链序列化、整串/大小写/中文标签匹配、混合规则、前缀不匹配、缺失/异常标签安全放行、推荐理由不作标签回退及旧直接路径。这些是协议夹具验证，不替代真实游戏卡业务命中。

同签名新包已通过正常设置输入未匹配规则 `NPatchTagAcceptanceUnmatched`。公开游戏投稿《我的世界入侵B站》（AID `117426016487382`）的推荐保留，父组已执行、标签叶项 `INSTALLED` 且无命中/错误，严格当前身份检查 0.237 秒通过。检查了该投稿前三段推荐，未取得带可核对标签的游戏推广卡；普通游戏题材投稿不等于协议游戏卡，不能据此断言标签真实命中已通过。

同一未匹配规则下，`BV1xx411c7mD` 的普通推荐对照也保留，父组已执行、标签叶项无命中/错误，0.304 秒通过。通过 UI 清空名单后，游戏投稿入口再次显示推荐；父组 `DISABLED`、0 Hook、无回调，标签叶项无回调，0.466 秒通过。名单最终留空，见 `12-unmatched-general-detail.xml/.png`、`12-unmatched-general-evidence.log`、`14-tag-cleared-values.xml/.png`、`15-tag-off-game-detail.xml/.png`、`15-tag-off-game-evidence.log` 与 `tag-verification.log`。

证据 `captures/npatch-2b/related-tag-acceptance/relate-public-model-members.txt`、`RelateCardMapper-j.java`、`public-game-sample-route.log`、`05-game-video-detail.xml/.png`、`06-game-list-*.xml/.png`、`08-final-tag-baseline-dialog.xml`、`08-final-tag-baseline.png`、`09-unmatched-tag-saved.xml/.png`、`11-unmatched-game-detail.xml/.png`、`11-unmatched-game-evidence.log`。外部启动使用已解析的 `bilibili://video/<aid>`；内部 `united_video` Intent 不是 shell 可调用的已导出入口，早期失败记录不作为验收证据。

最终 3700 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK 和快速 Lint 通过（250 Warning / 1 Hint，baseline 不变）。模块 SHA-256 `6a2a7971ede0255a6ef08db82ec7056c9ad4f4b142fccfd0ec09b1961000a608`，宿主 `e027584c5fd1ade3d54a171e03ea232a1b8e041ddfedc800d732610b897dc5ab`，固定 patcher/native/证书不变，见 `patched-tag-final/verified.json`。标签设置与详情页能力只记为 `installed`；实际标签命中、命中后恢复、作者/类型组合和服务层独立路径继续待验。

最终包再次通过真实配置/启停/条款/Web/触屏复制回归，2 项、141.779 秒，见 `final-host-regression.log`。之后正式会员登录态及 5.0 MB 既有缓存保留，缓存播放页的两帧已查看并确认继续解码，见 `retained-account-cache.log`、`upgrade-cache.xml/.png` 和 `upgrade-cache-player-1.png` / `-2.png`；未据此扩大为断网或更多资源验收。

### RELATED_COURSE_INSTALLATION

2026-10-22 复用 `b2570cc3` 相同生产/测试包，通过 UI 验证五类推荐类型和匹配增强均关闭。公开入口 `BV1xx411c7mD` 出现带“课堂”徽标的乐理卡以及两条普通投稿对照，父组 `DISABLED`、0 Hook、各类型叶项无回调，0.330 秒检查通过。

仅开启“过滤课程推荐”后，安装日志确认 `types=[COURSE]`，时长/播放量/匹配增强/强力模式均未开启。首次及保持相同配置的两轮补采均没有课程移除证据，画面也没有“课堂”徽标；预期 `APPLIED` 的检查分别失败，不能把画面消失视为过滤成功。后续冷启动同步当前快照后，严格检查确认父组已执行，课程叶项 `INSTALLED`、0 命中、无运行错误，其余类型叶项无回调，0.609 秒通过。这只证明安装与未命中观测，不证明实际移除。

通过 UI 恢复全关，父组 `DISABLED`、0 Hook、各类型叶项无回调，0.377 秒通过，普通推荐继续显示；本轮没有重新取得“课堂”卡，因此实际命中及命中后恢复仍待验。此前一次仅打开设置 App 后查询旧进程，被当前快照 revision 不一致拦下；该失败记录不作业务证据，后续保留相同严格断言并冷启动重查。可见“课堂”徽标尚未与协议 `COURSE` 类型或真实路由交叉确认；现有公共分类器支持课堂路由，而相关推荐尚未传入 URI，这是后续适配候选，需真实样本后再收敛实现。

证据 `captures/npatch-2b/related-course-acceptance/01-types-baseline-dialog.xml`、`01-types-baseline.png`、`02-all-types-off-selected.xml/.png`、`03-types-off-baseline-evidence.log`、`04-baseline-controls.xml/.png`、`06-course-only-selected.xml/.png`、`07-course-enabled-detail.xml/.png`、`07-course-enabled-evidence.log`、`09-course-retry-*-detail.xml/.png`、`09-course-retry-*-evidence.log`、`course-sampling.log`、`10b-course-on-observed-evidence.log`、`12-course-cleared-selected.xml/.png`、`13-course-off-restored-evidence.log`、`14-restored-controls.xml/.png`。课程设置与能力只记 `installed`，不声明命中成功或永久不适用。

本轮仅更新验收记录与矩阵，复用同一包的 3700 项 JVM、快速 Lint 和 141.779 秒最终包回归；包哈希见 `reused-production-build.json`。类型全部关闭，作者和标签名单仍为空。

### RELATED_REASON

2026-10-22 复用 `b2570cc3` 相同包，通过原生相关推荐面板验证自定义推荐理由。五类内容过滤、强力模式、时长/播放量、作者/标签均关闭；“过滤匹配增强”是理由设置的前置开关。公开视频 `BV1xx411c7mD` 的《bilibili上市宣传视频》卡显示“10万投币”，其它普通投稿显示“5万点赞”等不同理由，作为对照。

| 设置 | 普通推荐行为及当前身份检查 |
|---|---|
| 仅匹配增强开启，理由/强力模式关闭、未选类型 | 推荐保留，父组 `DISABLED`、0 Hook，理由/匹配增强叶项无回调，0.605 秒通过 |
| 匹配增强及自定义理由开启，关键词 `10` | “10万投币”目标卡移除，5 万点赞等其它普通推荐保留；父组及理由叶项 `APPLIED`，匹配增强叶项不误报，0.366 秒通过 |
| 关键词改为 `1000000000` | 目标卡及可见理由恢复；父组已执行，理由/匹配增强叶项无命中，0.274 秒通过 |
| 恢复关键词 `10`，保留理由开关但关闭匹配增强 | 目标卡保留，父组 `DISABLED`、0 Hook，两个叶项无回调，0.516 秒通过 |
| 重新开启匹配增强，未重新输入理由或关键词 | 理由开关及 `10` 均保留，理由叶项再次 `APPLIED`、目标移除，0.342 秒通过 |
| 清空关键词并关闭全部类型/匹配增强/强力/理由开关 | 目标卡及其“10万投币”理由恢复；父组 `DISABLED`、0 Hook，两个叶项无回调，0.406 秒通过 |

原因归因仍按实际执行判据：自定义关键词的移除归入 `video_related_reason_filter_enabled`，不能因前置匹配增强已开启就给 `video_related_matching_enhancement_enabled` 复制 `APPLIED`。本案例仅验证相关推荐自定义理由及前置门控，匹配增强的推广判据、其它安装器和强力模式仍待独立业务案例。

证据 `captures/npatch-2b/related-reason-acceptance/01-reason-baseline-dialog.xml`、`01-reason-baseline.png`、`02-enhancement-expanded.xml/.png`、`03-matching-only-selected.xml/.png`、`05-matching-only-detail.xml/.png`、`05-matching-only-evidence.log`、`10-reason-keyword-values.xml/.png`、`12-keyword-10-detail.xml/.png`、`12-keyword-10-evidence.log`、`13-reason-filtered-controls.xml/.png`、`15-unmatched-values.xml/.png`、`17-unmatched-keyword-detail.xml/.png`、`17-unmatched-keyword-evidence.log`、`19-restored-keyword-values.xml/.png`、`20-prerequisite-off-selected.xml/.png`、`21-prerequisite-off-evidence.log` 和 `reason-verification.log`。

保留与最终恢复证据另见 `23-retained-reason-*.xml`、`24-prerequisite-on-selected.xml/.png`、`25-retained-rule-applied-evidence.log`、`27-keyword-cleared-values.xml/.png`、`30-all-switches-cleared-selected.xml/.png`、`31-final-reason-off-detail.xml/.png` 和 `31-final-reason-off-evidence.log`。最终关键词清空，相关开关均关闭，作者/标签名单和时长/播放量范围仍为空。

本轮仅更新记录与矩阵，复用相同包的 3700 项 JVM、快速 Lint 与 141.779 秒最终包回归，包哈希见 `reused-production-build.json`。理由开关/关键词、理由能力及前置设置记为 `observed`；匹配增强能力只记 `installed`，其它安装器对应的推广能力继续待验。

### RELATED_STRONG

2026-10-22 通过原生相关推荐面板仅开启匹配增强与强力模式，其余五类过滤、自定义理由、时长/播放量和作者/标签名单均关闭。旧包把整个推荐区清空，带“10万投币”的《bilibili上市宣传视频》也被误删。实际 APK 与有界布尔探针交叉确认：8.90.2 的普通 AV 同样带反馈菜单，不能把菜单存在视为商业载体；此外服务层 `C0(r2)` 的结构化推荐理由位于 `r2.H(): String`，旧链无法读取，导致该层把本应保留的投币卡当成未知理由。

修复只用 `hasCm` / `hasCmStock` 作为商业载体证据，强力模式也不再将反馈菜单当作广告。通过现有 KavaMemberLookup 边界，在已核对的 C0/r2/RunningUIComponent 契约下精确定位公开非静态 H()，并加入现有可序列化理由链；不读取任意同名方法或 toString。规则版本 75→76 使旧定位缓存失效，JSON/schema 不变。新增服务层夹具验证可读投币理由放行、点赞/未知理由移除、自定义理由及推广匹配归因、读取异常边界和链序列化；这些单测不冒充服务层独立业务表面通过。

修复反馈判据及服务层理由后，真实画面保留“10万投币”及另一张“1千投币”卡，原“5万点赞”对照被移除；又实际观察到完整徽标“百万点赞”。最终解析器补齐这个精确标签，拒绝“百万投币”“获得百万点赞”等相似文本；协议层与服务层都有对应回归。诊断探针只记录类型和布尔状态，最终源码与 APK 已移除探针。证据 `captures/npatch-2b/related-strong-acceptance/strong-probe-flags.log`、`RelateItem-r2.java`、`RelateAVItem-m2.java`、`17-million-before-scroll.xml` 和 `18-million-format-baseline.xml/.png`。

最终包再次确认“10万投币”卡保留，强力叶项与父组 `APPLIED`，匹配增强叶项无命中，精确当前身份检查 0.444 秒通过，见 `19-complete-strong-detail.xml/.png`、`19-complete-strong-evidence.log` 和 `20-complete-controls.xml/.png`。“百万点赞”字样在旧解析器的真实卡上有观测，最终修复包含精确解析与两层回归；不单凭新一轮推荐里没有该卡宣称独立实机命中。

关闭前置匹配增强但保留强力选项，父组 `DISABLED`、0 Hook、强力/匹配增强无回调，普通推荐保留，0.375 秒通过；重开前置选项后，强力勾选仍在、无需重设，父组与强力叶项再次 `APPLIED`，投币对照保留，0.438 秒通过。最终包再关闭强力模式，开启自定义理由 `10`，目标投币卡移除而“5万点赞”对照保留，父组及自定义理由叶项 `APPLIED`，匹配增强不误报，0.480 秒通过。证据 `22-prerequisite-off-selected.xml/.png`、`23-prerequisite-off-evidence.log`、`25-prerequisite-on-selected.xml/.png`、`26-retained-strong-detail.xml/.png`、`26-retained-strong-evidence.log`、`30-reason-on-selected.xml/.png` 和 `31-complete-custom-reason-detail.xml/.png` / `-evidence.log`。

通过 UI 清空关键词与全部类型/增强/强力/理由选项后，“10万投币”与“5万点赞”卡都恢复，父组 `DISABLED`、0 Hook，理由与匹配增强叶项无回调，0.294 秒检查通过。见 `33-keyword-empty-values.xml/.png`、`35-all-off-selected.xml/.png`、`36-all-off-restored-detail.xml/.png` 和 `36-all-off-restored-evidence.log`。强力设置与能力只记 `observed`；共享设置关联的其它安装器保持原状态，不据此升级。

最终 3708 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK 与快速 Lint 通过（250 Warning / 1 Hint，原 baseline 不变），见 `strong-complete-gates.log` 与 `complete-full-jvm-suites.json`。最终模块 SHA-256 `91a881d23eafcc2ae470e73ff15a9321bffdbd15552a9c2e09ee23c9212bda38`，宿主 `22cc9e33abe3d59d123ec92768280c4a6473c5c3776fe38d3ec99200f3506283`，固定 patcher/native/证书不变，见 `patched-strong-complete/verified.json`。早期 H() 单独修复候选和探针包均不作为最终产物。

最终包真实配置/启停/条款/Web 与原生触摸复制回归 2 项、138.468 秒通过，见 `final-host-regression.log`。之后正式会员登录与 5.0 MB 既有缓存保留，缓存入口两张截图为不同的实际视频帧，见 `retained-account-cache.log` 与 `upgrade-cache-player-1.png` / `-2.png`；未据此声明断网播放、其它版本或全量功能验收完成。

### PLAYER_QUALITY

2026-10-22 以有线备用机上的公开普通投稿验证默认画质。设置基线为“跟随哔哩哔哩”，当前身份检查确认画质注册组 `DISABLED`、0 Hook、无回调。早期短视频会从旧进度播放到结尾并自动连播，后续固定为详情页进入的 11:43 投稿《从 PCL 到 HMCL：我们的 Minecraft 自由社区正在失去什么？》（AID `117474687257555`）；全屏连播队列中的其它视频与未进入全屏的中间截图不充当画质结果。

旧包设置 360P 后，严格检查虽有 `APPLIED`，固定样本的原生面板却选中“自动（1080P 高清）”，实际默认画质未生效。原版 APK 确认 `gh6.h.c()` 计算默认 QN，独立的 `h()` 读取自动偏好并保留青少年/课程限制；`PlayViewRepository.b(VideoVod$b)` 另外设置 QN 策略，而普通播放器偏好构造也独立读取自动策略。只覆盖默认 QN 不足以关闭这些默认构造阶段的自动分支。

修复经统一 KavaMemberLookup 边界精确核对该版本的三个同步默认构造签名和设置伴生对象的 Boolean 读取器；仅在这些调用期间，把指定自动偏好的读取收敛为关闭。不写宿主偏好、不修改资源或付费权限、不在最终自动策略方法上强制返回；原有青少年/课程限制分支继续执行。作用域不跨协程、不持有宿主实例，原方法抛异常也退出；Story 当前准入不允许覆盖时不借用普通作用域。新角色在安装时定位，不写入持久适配缓存，原有缓存 schema/rule 不变；仅对已核对的 `gh6.h.c()` 档案启用，不外推未知版本。

新增 8 项 JVM 回归，验证精确档案/缺失成员、三种同步边界、无关偏好、嵌套与跨线程隔离、原方法及偏好读取异常、当前目标撤回、部分安装停用与去重重试。安装覆盖增加普通默认策略家族，缺失 Story 的当前实机明确保留 `partial:2/3`、5 Hook，不把普通视频修复写成 Story 全覆盖；旧测试的覆盖分母随之更新，未削弱原有重试和去重断言。

最终包冷启动固定样本后，原生画质面板中的“360P 流畅”实际为粉色选中项；同一视频再手动选“480P 标清”及“自动”，两次选中状态均正确，没有被默认值立即拉回。证据 `captures/npatch-2b/quality-acceptance/39-fixed-360-evidence.log`（0.529 秒）、`41-fixed-360-retry-menu.xml/.png`、`43-manual-480-menu.xml/.png`、`44-auto-option.xml/.png` 和 `46-manual-auto-menu.xml/.png`。首次全屏进入失败记录不算画质证据；等待控制栏稳定后补采，保留原严格身份断言。

保留手动自动选择，再冷启动同一入口后，模块默认 360P 重新选中，0.395 秒当前身份检查通过，见 `48b-default-360-restored-menu.xml/.png` 与 `48b-default-360-restored-evidence.log`。通过正常设置将默认值改为 480P，设置摘要及冷启动后的原生面板均为 480P，0.321 秒检查通过，见 `49-default-480-saved.xml/.png`、`50-default-480-cold-detail.xml/.png`、`51-default-480-menu.xml/.png` 与 `51-default-480-evidence.log`。画质只按实际样本可用项验证，没有点击付费档位或试用。

保持模块默认 480P 开启，最终包真实配置/启停/条款/Web 与原生触摸复制回归 2 项、134.392 秒通过，见 `final-host-regression.log`。

回归后通过 UI 改为“跟随哔哩哔哩”，设置摘要恢复跟随；冷启动同一入口，原生面板实际选中“自动（1080P 高清）”，画质注册组 `DISABLED`、0 Hook、无回调，0.408 秒严格检查通过。见 `52-follow-restored-saved.xml/.png`、`53-follow-cold-detail.xml/.png`、`54-follow-restored-menu.xml/.png` 与 `54-follow-restored-evidence.log`。这验证关闭后的宿主原生选择恢复，默认值最终留在跟随状态；两行矩阵仅记 `observed`，Story 和其它业务继续待验。

最终再次确认正式会员登录态和 5.0 MB 既有缓存保留；缓存入口两张截图为不同的实际视频帧，见 `retained-account-cache.log`、`upgrade-cache.xml/.png` 与 `upgrade-cache-player-1.png` / `-2.png`。未据此声明断网或其它清晰度缓存验收。

最终 3716 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK 与快速 Lint 通过，250 Warning，原 baseline 不变。APK 构建见 `normal-policy-final-gates.log`，最终测试、快速 Lint 与矩阵检查见 `acceptance-final-gates.log` 和 `normal-policy-full-jvm-suites.json`；后续 JVM 负例、覆盖断言及文档调整不改变生产/测试 APK，生产字节已核对。模块 SHA-256 `e42ffa6f7acda2d4c77f36278f02866042aedc984ccaa76df220dd505b0edd16`，宿主 `4ed22485035b054d49b6ebe75f0b58ff296b03c9be5955515dab1955d0ac862a`，固定 patcher/native/证书不变，见 `patched-normal-policy/verified.json`。

源证据同目录 `default-quality-owner.log`、`DefaultQuality-h.java`、`PlayerSettingHelper.java`、`PlayerSettingCompanion.java`、`PlayViewRepository.java`、`PlayParams-n.java`、`KeelParams-h.java`、`default-preference-callers.log`、`33-quality-available.xml/.png` 和 `38-long-cold-360-quality.xml/.png`。日志中仅取到显示尺寸的抓取不作为解码分辨率证明；本例按原生画质实际选中状态与播放画面验收，不宣称完成独立解码尺寸测量、Story、其它业务类型或其它版本验证。

### PLAYER_STORY_QUALITY_ABI

2026-10-22，范围与本页相同，证据目录 `captures/npatch-2b/story-quality-acceptance/`。修复前模块设为 360P，进入 `StoryTransparentActivity` 后，原生画质面板仍实际选中“自动（720P 准高清）”，见 `01-story-old-default-dialog.xml/.png`、`05-old-portrait-entry.xml/.png`、`08-old-quality.xml/.png`；原安装仅覆盖普通/全局两族。

新增显式、只读原始 APK 的 ART/DexKit 契约检查，核对 SHA-256、包名、版本和私有只读夹具路径，分别断言语义定位、偏好成员、owner/provider、重解析及资源契约；不注册 Hook、不改账号或配置。真实 8.90.2 的偏好基类 `tangram.basic.d` 是接口，Auto/Fixed 直接实现它，旧实现错误拒绝接口；`Video.PlayableParams.getDisplayParams()` 是抽象方法，由具体业务参数实现，旧访问器又错误拒绝了合法的实例派发。先后失败报告明确区分这两层问题，修复后五项契约全部通过，6.623 秒、`OK (1 test)`，见 `initial-android-contracts.json`、`interface-android-contracts.json`、`abstract-dispatch-android-contracts.json/.log`。

偏好解析仍核对直接继承关系、唯一 Auto/Fixed、字段、类加载器和所有构造桥的 QN 往返；额外接口、间接实现、构造改写 QN 继续拒绝。抽象方法仅放行已核对的 display getter 实例调用，Hook 和其它成员约束保留。规则版本升为 77，schema 62 不变，旧缺失状态重新验证。新增 4 项偏好回归，既有 Story Hook 集成夹具改为实际的抽象基类/具体实例结构，保留全部会话、线程、权限、手动意图及重解析检查；3720 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK 与快速 Lint 通过，250 Warning，baseline 不变。临时定位探针已删除。严格证据测试另外将相同的公开状态、计数和 Hook 数输出到 instrumentation stream，避免小日志缓冲覆盖日志，不改变验收条件。

在最终候选包的新加载 Story 视频中，原生面板实际选中固定 360P；当前视频手动改为 480P，再改自动，均保留手动选择，下一条恢复固定 360P。模块默认改为 480P、冷启动并切换下一条后，面板实际选中固定 480P。三个当前授权/配置身份严格检查分别通过，见 `16-story-next-default-quality.xml/.png`、`17-story-manual-480-quality.xml/.png`、`18-story-manual-auto-quality.xml/.png`、`20-next-after-manual-default-quality.xml/.png`、`25-story480-next-quality.xml/.png` 及对应 `*-evidence.log`。运行记录 `runtime-live.log` 包含画质组 `INSTALLED`、20 Hook；安装/回调不作为共享首条行为通过证明。

**本单元发现的共享首条缺口，已由后续 `PLAYER_STORY_SHARED_QUALITY` 修复。** 首次 360P 测试显示“自动（360P）”，不能等同固定目标；把默认改成 480P 后，共享接入首条仍显示“自动（720P）”，见 `14-fixed-default-quality.xml/.png`、`23-cold480-carried-quality.xml/.png`。这条旧包路径保留失败，不将本单元写成 Story 整体通过；后续单元另行核对共享媒体的生命周期、当前条目与首次目标建立。两行矩阵仍为 `observed`，追加本案例引用，数量不变。其它版本、非普通 UGC、预加载提档、限制模式、会员画质和全部组合仍待验，embed 保持实验性。

同包两项无 Manager 启停/授权/复制实机回归通过（139.026 秒）。登录态、已完成的 5.0 MB 缓存及实际缓存解码画面保留，见 `final-embedded-regression.log`、`retained-account-cache.log`、`upgrade-cache-player-1.png` 与 `upgrade-cache-player-2.png`；未断网。随后通过 UI 恢复“跟随哔哩哔哩”，冷启动进入 Story，首条和下一条原生面板均选中“自动（720P）”，画质组 `DISABLED`、0 Hook、0 回调，0.335 秒严格检查通过，见 `30-follow-restored-saved.png`、`32-follow-carried-quality.xml/.png`、`34-follow-next-quality.xml/.png` 与 `34-follow-next-evidence.log`。本单元结束时默认值留在跟随状态。

当前生产模块 SHA-256 `8c85e53e65b3764710d123f9b5b9229a9d0a18d404846bb579730659af49a9ee`，嵌入宿主 `a7ee6e87cfe1c47edf98e8eee47bac47621e148e179a979e7ef5603fa1af8827`；固定 patcher/native/证书不变，见 `patched-abstract-dispatch/verified.json`。完整 JVM 统计已保存为 `abstract-dispatch-full-jvm.json`；APK/快速检查见 `abstract-dispatch-gates.log`，后续仅测试输出修改的构建见 `bounded-evidence-output-gates.log`。日志进程与构建守护进程已停止。

### PLAYER_STORY_SHARED_QUALITY

2026-10-22，同一有线机和支持范围，证据目录 `captures/npatch-2b/story-shared-acceptance/`。本例继续上一单元记录的共享首条缺口。临时、有界且不输出视频/账号标识的探针确认：首条 `R(int)` 在 `onCreate` 返回前发生，旧版直到返回后才绑定 handler，因而漏记首次选择；随后首次匹配虽已绑定，但普通详情页带入的 `from` 实测为来源编号 `"6"`，UGC 类型、登录、青少年及课程限制均正常，旧 `from=="vupload"` 条件仍将其拒绝。日志 `distinct-lifecycle-live.log`、`admission-lifecycle-live.log` 保留顺序；探针已全部移除，第一轮相同日志键被统一去重的记录不用于推断回调缺失。

修复通过 KavaMemberLookup 核对 Story owner 上唯一的 `(IPlayerContainer, PlayerSharingBundle) -> void` 接入回调，源码中的该角色负责 `playFromShared`；在成功的共享接入完成后，以当前指针、provider 条目和当前参数一致性建立首条代次。生命周期进入时即登记弱 owner 与所属线程，退出时补齐组件，不再清掉接入过程中已经建立的会话。共享来源仅在该接入得到确认后，才允许使用 UGC 类型及精确 Gemini 解析参数中相同的 aid/cid 作业务证据；无此证明的来源、明确的 live/music/bangumi/pugv、非 UGC、错条目/未知解析器仍拒绝，登录与限制条件继续实时核对。证明只持有当前参数的弱引用，不能跨实例沿用，新条目或释放会清掉证明；失败则撤销播放代次与目标，重复接入不重置手动意图或复活失败代次。

规则版本 78、schema 62，新增角色仅在运行安装边界核对，不增加适配 JSON 字段。新增 9 项 JVM 回归覆盖创建过程中的共享接入、同 ID 换参数、两种手动选择、空/失败接入、失败后重复、跨线程/未知 owner、指针错位、解析 ID/类型不符、退出登录和限制/非 UGC 门控。共 3729 项 JVM（0 失败、1 跳过），Debug/AndroidTest APK 与快速 Lint 通过，250 Warning，baseline 不变；见 `shared-ready-final-gates.log` 与 `shared-ready-full-jvm.json`。最终真实 APK 契约再次通过（7.166 秒），见 `final-android-contracts.log/.json`。测试夹具复用已有 IPlayerContainer，未保留重复定义。

最终 embed 包默认 480P，冷启动普通详情页并进入 Story，**共享首条原生画质面板实际选中固定 480P**；当前视频手动改为 360P、再改自动（当时 1080P），均保留选择；下一条重新实际选中默认 480P。两次当前配置/授权身份严格检查通过，画质组 `INSTALLED`、21 Hook、无运行错误，见 `41-final-shared480-quality.xml/.png`、`42-final-shared-manual360-quality.xml/.png`、`43-final-shared-manual-auto-quality.xml/.png`、`45-final-next480-quality.xml/.png` 及对应 `*-evidence.log`。不能用安装计数代替上述原生选择证据，也不宣称测得首帧解码尺寸或不存在初始切档延迟。

返回普通详情页，原视频与原生画质面板均可正常打开，见 `46-return-to-normal.xml/.png`、`47-return-normal-quality-menu.xml/.png`。通过模块 UI 将默认值改成 360P 后再次冷启动、进入共享 Story 首条，面板实际选中固定 360P，当前身份严格检查通过（0.349 秒），见 `50-default360-saved.png`、`52-final-shared360-quality.xml/.png` 与 `52-final-shared360-evidence.log`。因此本例没有只核对安装期目标或固定单一 QN。

同包两项无 Manager 配置启停/当前授权/复制实机回归通过（141.237 秒），登录态、已完成的 5.0 MB 缓存及两帧不同的实际缓存解码画面保留，见 `final-embedded-regression.log`、`retained-account-cache.log` 和 `upgrade-cache-player-1.png` / `upgrade-cache-player-2.png`；未断网。最后通过 UI 恢复跟随，冷启动后的共享首条与下一条均选中“自动（1080P）”，画质组 `DISABLED`、0 Hook/回调，0.381 秒严格检查通过，见 `60-follow-restored-saved.png`、`62-follow-first-quality.xml/.png`、`64-follow-next-quality.xml/.png` 与 `64-follow-next-evidence.log`。默认值留在跟随状态。

生产模块 SHA-256 `933ab14b360040fd88549ccb488d23a8042f170529de73ce7d0a562d0be278c4`，嵌入宿主 `b17ce77fa72b0c744885f6599bb1fac0252221487511c6e340369676c640a651`，固定 patcher/native/证书不变，见 `patched-shared-final/verified.json`。其它版本、其它业务及完整预加载/会员/限制模式实机组合仍待验。两行画质矩阵保持 `observed`，追加案例引用，不把局部覆盖升级成全场景通过。

### STORY_ACTION_ICONS

2026-10-22，同一有线机、8.90.2/833 embed 范围，证据目录 `captures/npatch-2b/story-icons-acceptance/`。默认六项均未隐藏，六个实际控件可见。只开启“隐藏弹幕开关”后，旧包底部开关仍可见，叶能力却报告 `NOT_APPLICABLE`、0 回调，见 `01-current-baseline.xml/.png`、`02-icons-dialog-dialog.xml` / `02-icons-dialog.png`、`03-toggle-only-selected.png`、`04-toggle-only-old-entry-0.xml/.png` 与 `04-toggle-only-old-evidence.log`。若进入竖屏第一次未成功，按该目录实际成功的 `entry-1` 取证，不把仍在详情页的尝试写成验收结果。

原实现只处理 9.4 起的新 widget；8.90.2 在 StoryAbsController 上直接持有底部 ImageView。新增 KavaMemberLookup 访问器契约：精确 getter/setter 类型、由播放器 getter 返回类型确定的唯一绑定签名，不按混淆生命周期字母猜测。setter 和绑定回调仅折叠目标开关，复用原有 GONE、0 宽高/边距、透明及不可点规则；初始化结束再核对一次，弱集合避免重复排队，不持有页面实例缓存。两处注册均成功后才启用；部分失败不执行，重试去重；延迟读取失败被收敛并报告。新版 widget 保留，旧访问器存在但失配时不能被新版成功掩盖为完整支持。

新增六项 JVM 回归，共 3735 项（0 失败、1 跳过）、Debug/AndroidTest APK 与快速 Lint 通过，250 Warning，baseline 不变，见 `legacy-danmaku-final-gates.log` 与 `legacy-danmaku-full-jvm.json`。说明文案去掉仅“右侧”的范围描述，资源收尾通过 APK/快速 Lint；全部 19 个 dex 和清单与已通过测试的逻辑包字节相同，见 `copy-final-gates.log`、`module-code-identity.json`。未修改适配缓存结构/规则或依赖。

旧底部弹幕开关单项已实际隐藏，另外五个图标保留，该叶从不适用变为 `INSTALLED / APPLIED`；见 `10-legacy-toggle-fixed-entry-1.xml/.png` 与 `10-legacy-toggle-fixed-evidence.log`。这是资源说明收尾前的同字节逻辑包；最终文案包上，点赞、评论的竖屏单项分别隐藏且其它五项保留，独立叶应用及其它叶无回调的严格检查通过。横屏控制栏也分别只隐藏所选图标，另一个保留，见 `12-landscape-controls-baseline.png`、`24-like-only-final-entry-0.xml/.png`、`25-like-only-final-landscape.png`、`31-comment-only-entry-0.xml/.png`、`32-comment-only-landscape.png` 及对应 evidence 日志。横屏控制栏不完整暴露在 accessibility 树，按控制栏实际显示的截图核对，不把隐藏控制栏时的空树作为图标消失证明。

最终模块 SHA-256 `6eb13359c3f13c189635c53a8b030d93e576986c6d76a73671bb65962b9c3bea`，嵌入宿主 `c3907addd8202971ccce1f7a4f3a65f5283e44675918a866f9468ee53f078c0d`；固定 patcher/native/证书不变，见 `patched-icons-final/verified.json`。投币、收藏、分享的单项也分别隐藏目标并保留其它五项，严格叶证据通过，见 `40-coin-only`、`50-favorite-only`、`60-share-only` 系列及 `individual-results.log`。六项全选的共享首条和横屏对照已验证：目标图标均隐藏，输入栏、进度、播放、倍速和画质控件保留，六叶各自报告应用，见 `70-all`、`71-all-landscape`。横屏返回的一次 UI dump 因宿主未空闲失败，保留失败记录并单独重取，不当作功能结果。首次上滑仍是原视频，不计下一条成功；第二次上滑后标题变更，截图与树确认新条目中目标控件仍隐藏，见 `74-all-next-retry`。最终配置/启停/条款/Web 与原生触屏复制回归 2 项通过（138.155 秒），见 `final-embedded-regression.log`。正式会员状态和已有 5.0 MB 缓存保留，缓存入口的两帧显示实际解码推进，见 `retained-account-cache.log` 与 `upgrade-cache-player-1/2.png`；未关闭网络，不作为断网播放验收。最后在设置中清空六项并冷启动，首轮入口点击未进入 Story、第二轮成功；六个控件均恢复，父组 `DISABLED`、0 Hook/回调，六叶无回调，严格检查通过（0.357 秒），见 `80-clear-config`、`80-restored-entry-1.xml/.png` 与 `80-restored-evidence.log`。不外推为图文卡片、其它版本或 Manager 验收。

### PLAYER_PORTRAIT_CONTROL

范围为同一有线设备上的 8.90.2 / 833 embed / Android 16 / arm64-v8a。公开样本仍为《从 PCL 到 HMCL：我们的 Minecraft 自由社区正在失去什么？》，证据目录 `captures/npatch-2b/portrait-control-acceptance/`。关闭基线 `02-baseline-controls.png` 同时显示竖屏和全屏按钮；开启后 `11-enabled-controls.png` 只隐藏竖屏入口，保留播放、进度和全屏。现有包的当前身份查询却为 `INSTALLED / observed=0 / applied=0 / hooks=1`，见 `12-existing-diagnostics.log`；该零回调结果记录的是原诊断缺口。

补齐单一能力的运行证据：识别有效可见性请求记观察，只在修改参数且原方法正常返回时记应用；原本 GONE 不记应用，原方法抛错保留原异常并记录失败。使用既有参数局部 extra 和诊断公共封装，不新增全局 View Hook、反射定位或页面缓存；原可见性策略不变。
新增五项回归覆盖真实 Hook DSL 的原调用顺序、无改动/无效参数、异常透传、诊断故障隔离及关闭/非主进程/缺适配点。3740 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK、快速 Lint 通过，250 Warning，baseline 不变；见 `final-gates.log`、`full-jvm.json`。规则 78、schema 62 和依赖不变。

最终包严格当前身份查询为 `INSTALLED / observed=1 / applied=1 / hooks=1`（2.017 秒），见 `20-final-enabled-evidence.log`。初次 `20-final-enabled-controls.png` 遇到恢复进度时的黑帧、底部控制栏尚未完整出现，不作为控件保留结论；后续已正常实际播放，原生全屏和画质菜单可打开，退出后半屏竖屏入口仍隐藏、全屏/播放/进度保留，见 `21-final-fullscreen-menu`、`22-final-landscape-controls.png`、`23-final-return-controls`。没有使用全局旋转设置或修改宿主画质选择。

最终模块 SHA-256 `9dc6e60df375b95cad2c290c112b02eedaeb1046e5bd731bb5361d90d79ef9a5`，嵌入宿主 `b6e238d71635e960f99c23ab0ebe765409e7dfef180e0cb55e502a07cb6e005f`；固定 patcher/native/证书不变，见 `patched-portrait-final/verified.json`。最终配置/启停/条款/Web 与触屏复制回归 2 项通过（141.138 秒），正式会员登录态、5.0 MB 缓存和两帧实际播放保留，见 `final-embedded-regression.log`、`retained-account-cache.log`、`upgrade-cache-player-1/2.png`。关闭设置后，冷启动的竖屏与全屏按钮都恢复，点击竖屏入口成功进入 Story，见 `30-disable`、`31-restored-controls`、`32-restored-story-0` 与 `restoration-results.log`；当前身份严格查询为 `DISABLED / observed=0 / applied=0 / hooks=0`（2.011 秒）。最后选项关闭。其它宿主版本、Manager 和其余独立入口仍待验。
### EMBED_WEB

2B.1 的原生 TLS 修复、恢复原有 Web Hook、关闭/条款拒绝/重新开启、重复打开/返回/后台恢复、正文滚动及复制链接均见路线图 2B.1 验收。当前模块的自由复制开关和安装诊断已有证据；评论/简介复制的具体业务仍需独立行为案例，不因 32 个公共 Hook 安装成功而全部标记通过。

账号修复后的同一最终包再次通过 `NPatchEmbeddedHostInstrumentedTest`，126.169 秒，覆盖当前配置身份、启停、条款拒绝、Web 无 Hook/完整安装及重复打开/返回/后台恢复；日志 `embedded-regression.log`。本测试不会登出账号，之后重新确认了登录与缓存保留。

### EMBED_BUILD_IDENTITY

2026-10-22 范围校正后进入 2B.3。仅验证 embed 的安装内容一致性与重新修补路径，不再把全部通用业务条目验收作为本阶段前置。环境仍为有线 `13a7a291`、Android 16/arm64、8.90.2/833 embed/sig2、无 Manager。

诊断后台线程直接比较宿主 APK 的 `assets/npatch/modules/com.Bilibili_Innocent_Lab.xposedmodule.apk` 与当前设置 App APK 的 SHA-256 内容。相同版本码不替代内容比对；缺模块、读取失败、损坏或越界分别保守展示。读取有大小上限、不解压到磁盘、不记录路径/完整哈希、不扩展诊断导出白名单、不改变授权协议。显示一致仅表示已安装内容一致，页面明确提醒更新后仍需冷启动，不把此结果当作运行中进程版本证明。

证据目录 `captures/npatch-2b/embedded-build-acceptance/`。只更新设置 App 时仍是 1.2.0（21），实际检查为 `DIFFERENT`，诊断页面显示重新修补、原证书覆盖安装、冷启动的具体步骤；见 `01-module-only-update.log`、`04-build-mismatch-visible.xml/.png`。同证书重新修补覆盖安装后为 `MATCHED`，页面恢复一致提示，见 `05-repatched-match.log`、`06-match-diagnostics.xml/.png`。未关闭账号或清除 B 站数据。

暂停的通用结束页改动随后保存至 `captures/npatch-2b/deferred-end-page/` 并移出源码。最终专项包再次通过只更新设置 App 的 `DIFFERENT` 检查及重新修补后的 `MATCHED` 检查，见 `10-scoped-module-only.log`、`11-scoped-repatched.log`。最终模块 SHA-256 `45262a068b89d783773e36f24ab740c7f097d32b4a4dad7b9c8f89d4209a639e`、宿主 `5122476c76dba553d6d4a7669df6831be177d330157c6928c2f37f8b44b90020`，固定 patcher/native/证书不变，见 `patched-build-scoped/verified.json`。

新增六项 JVM 回归覆盖精确内容、同长度不同内容、其它模块、缺失/损坏/空包、读取上限和同路径替换后重查；最终专项源码 3746 项 JVM（0 失败、1 跳过），见 `scoped-full-jvm.json`。本项不宣称全新安装、设置 App 重装恢复、不同协议兼容或 Manager/embed 切换已经验收。

最终专项包的 Debug/AndroidTest APK 与快速 Lint 通过（250 Warning，原 3 条错误基线不变），见 `scoped-final-gates.log`。配置变更、当前授权、免 Root 关闭/重开、条款拒绝及 Web 回归通过（129.889 秒），见 `scoped-embedded-regression.log`；前面的 131.892 秒结果仅保留为含暂停改动时的中间验证，不冒充最终专项产物。

### EMBED_SETTINGS_LIFECYCLE

2026-10-22，范围仍为有线 `13a7a291`、Android 16/arm64、8.90.2/833 embed/sig2，无 Manager。证据目录 `captures/npatch-2b/embedded-lifecycle-acceptance/`。本例只闭环设置 App 生命周期与当前协议的升级恢复，不代表 Manager/embed 切换或完整 2B 已完成。

新增显式分阶段的 `NPatchEmbeddedLifecycleInstrumentedTest`，检查点和白名单设置备份保存在独立测试包私有目录。除 `restored` 外不启动或重启宿主；安装、强停和卸载由外部 ADB 执行，避免测试自身替代恢复动作。检查通过当前 nonce/来源校验的真实宿主诊断，精确比较 incarnation、consent/policy/snapshot revision、fingerprint 和 no-root revision，不用全局心跳或安装数量证明配置已恢复。

| 安装/运行顺序 | 实际结果与证据 |
|---|---|
| 强停设置 App，冷启动宿主，设置端点不可达 | 当前 PID 日志记录 `admission_unavailable`；前台重试结束后，`unavailable` 阶段确认拒绝、安装链未开始、0 Hook、无 admission。原配置身份仍在。见 `15-settled-force-stopped-package.txt`、`16-settled-stopped-before-query.log`、`17-settled-stopped-rejected.log`（0.412 秒） |
| 打开设置 App，再冷启动宿主 | `retained` 阶段精确匹配强停前身份和配置代次，直连授权与安装恢复；最终包结果见 `18-final-reopened-retained.log`（12.823 秒） |
| 只覆盖更新设置 App，版本仍为 1.2.0（21），宿主内嵌代码保持旧包 | 当前协议下仍能取得当前配置且身份保持不变；APK 内容检查为 `DIFFERENT`。见 `07-module-only-update-retained.log`（4.906 秒）、`08-module-only-different.log`（0.676 秒）。内容不同不等于当前协议必然不兼容，也不等于宿主代码已经更新 |
| 用新模块重新修补、同证书覆盖宿主并冷启动 | 配置身份保留，内容检查恢复 `MATCHED`。见 `10-repatched-retained.log`（12.424 秒）、`11-repatched-matched.log`（2.182 秒） |
| 经用户明确批准只卸载设置 App，保留 B 站及数据 | 独立测试包仍在；宿主仍运行内嵌模块，但当前授权不可达，未安装功能。见 `19-settings-uninstalled.log`、`19-remaining-packages.txt`、`20-settings-absent-host.log` |
| 全新重装设置 App，未接受条款、未开启免 Root，冷启动宿主 | `fresh` 阶段确认 `UNDECIDED`、未启用、incarnation 与卸载前不同、宿主拒绝且 0 Hook/无 admission。见 `22-fresh-settings-rejected.log`（11.988 秒）。实际首次界面仍显示用户条款，见 `23-fresh-settings.xml/.png` |
| 使用正式导入器恢复备份，再显式重新接受条款和开启免 Root | 自动恢复白名单项逐项读回一致；导入前后仍未同意条款、未开启免 Root。随后重新接受/启用、冷启动，真实宿主精确匹配新身份，内容为 `MATCHED`，见 `24-restored-new-authority.log`（16.771 秒）。本阶段通过生产 Store/导入器执行，不冒充整套导入 UI 的触摸验收 |

强停负对照曾过早启动 Instrumentation，唤醒了设置端点，尚未结束的宿主前台重试随后成功，导致预期拒绝的检查失败（`14-final-stopped-rejected.log`）。这不是“强停时可使用旧缓存”的证据。最终先在测试进程外等前台重试结束并保存当前 PID 拒绝日志，再启动只读检查；较短日志窗口不足以覆盖两次启动记录的尝试也保留，不拿不完整日志推断成功。

未知授权协议版本 `-1/0/VERSION+1` 的 prepare 和 confirm 均拒绝、不发文档或 challenge；恢复现有版本后仍须重新准备/确认，租约仅可消耗一次。与旧正 revision 拒绝、关闭门控及标准框架文档回退共 11 项端点/策略实机测试通过（3.693 秒），见 `endpoint-protocol-fallback.log`。该测试入口显式提供调用 UID，不将其称为真实跨版本宿主 IPC；实际跨 APK 验证仅为上表相同版本码、当前协议的旧宿主/新设置 App 组合。不承诺任意历史或未来 schema 互通。

诊断页新增中英繁三种恢复引导，明确强停后先打开设置 App、重装后重新接受条款/启用、业务备份不恢复条款同意或账号凭据。未修改授权协议、适配 schema/rule、依赖或诊断导出白名单。最终源码 3746 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK 和快速 Lint 通过，250 Warning、原 3 条错误基线不变；见 `complete-gates.log`、`full-jvm.json`，构建守护进程已退出。

最终模块 SHA-256 `8de89a1135b9ec3be170af165b94c4d2b2f586fbcf768ee04209fe25fa737425`，宿主 `4ddab0d083f11c8274c3eaf2e8475e01dd30cbb05913a6c8cbeb7b1961a60482`；固定 patcher、双 ABI native loader 和证书不变，见 `patched-lifecycle-final/verified.json`。本例没有卸载 B 站、修改系统安全策略或操作无线机；首次重装时只处理了系统当次“继续安装”确认。

最终包真实配置/启停/条款/Web 与原生触摸选区复制回归 2 项通过（143.607 秒），见 `final-embedded-regression.log`。B 站正式会员登录、既有 5.0 MB 缓存保留，缓存入口两帧画面实际推进，见 `retained-account-cache.log`、`upgrade-cache.xml/.png`、`upgrade-cache-player-1.png` / `-2.png`；未断网，不作为断网播放验收。已安装诊断页确认当前直连配置，恢复提示滚动到可见区域后检查，见 `32-final-diagnostics.xml/.png`、`33-final-recovery-visible.xml/.png`；本轮独立测试包的私有检查点随后删除，手机留在诊断页。仅复用已有正式首次引导证据，本例不宣称全新安装宿主或其它 Android/ABI 已验收。

### EMBED_DEX_SOURCE

2026-10-22，同一有线机与 8.90.2/833/sig2/Android 16/arm64 范围，证据目录 `captures/npatch-2b/dex-source-acceptance/`。外层 APK 实际只有 2 个 loader DEX，原版含 38 个；833 原生代码对模块 native 调用方保留外层内容，对普通 Java I/O 重定向到原版。旧宿主日志中的 Java 指纹已是 `dex-v1:38:23e87bdd7be9c77b8480dcaeabe4cfe5c70b07cbeef6fb5023240299b174e093`，不能根据外层结构断言旧 Java 指纹始终错误。风险在于把同一外层路径继续交给 native DexKit，其读取分流可能与 Java 不同。归因材料为 `outer-original-counts.json`、上一单元的 `03-stopped-host-before-instrumentation.log`，以及固定上游 `bypass_sig.cpp` 的 `should_redirect_apk_contents` 和 `SigBypass.java` 的模块路径分流。

指纹与 DexKit 统一使用后台准备的只读来源。可读视图含 `assets/npatch/origin.apk` 时有界提取并验证尺寸/CRC；Java 已看到原版时冻结该实际视图，核对 DEX 中央目录和实际 DEX CRC，避免再次把外层路径交给 native。单 APK 上限 512 MiB，原有归档/DEX 数上限保留；只原子发布完整文件，不原地写已返回来源。保留本次全部 base/split 以及一份历史来源；回收不会删掉本次尚未查询的 split。来源损坏/越界/不可读返回不可用，不能退回 loader 指纹冒充成功。现有缓存命中的维护仍在工作线程；读不到来源保留现有工作缓存，读到不同来源才失效。规则 78→79 作废旧定位代次，schema 62 不变，未改授权、依赖或导出协议。

新增 7 项 JVM，覆盖原版来源、仅 loader 重打包的内容稳定性、原 DEX 改变、缺失/损坏/越界拒绝、同长度缓存损坏/丢失恢复、代码 split 与活跃文件回收。最终 3753 项 JVM（0 失败、1 跳过）、Debug/AndroidTest APK、快速 Lint 通过，250 Warning、原 3 条错误基线不变；见 `complete-source-gates.log`、`full-jvm.json`。完整 APK 路径的安装身份仍按原策略保守失效，内容指纹稳定不等同于承诺跨安装热复用所有适配结果。

最终模块 SHA-256 `f68386364751e745bd7f0412ab69f8c6b3ab163bf3d72ee7f69d7b949de93d29`，宿主 `15fcb337fab2ee0903b5ed460dd5b8865588f4de5ec8d80ba4fa95cab111360e`；同证书覆盖安装、固定 patcher/native 不变，见 `patched-source-complete/verified.json`。真实 ART/DexKit 检查逐字节核对原版 SHA、38 DEX 指纹和默认画质 native 查询，连同外观当前身份检查共 2 项通过（16.714 秒），见 `complete-android-native-and-appearance.log`。最终宿主日志 `complete-host-source.log` 显示 `count=38, isolated=true` 及相同原版内容指纹。前一隔离候选的 Web/启停/复制 2 项回归通过（138.892 秒）仅作中间证据；最终正式验收继续统一回归，不冒充最终文件回收逻辑的完整发布门禁。

本例验证 DexKit 这个 native 功能通道，不宣称插帧推理、其它 ABI 或其它版本均已实测通过。Manager 模式和正式支持边界继续在后续单元验证；矩阵没有因来源修复而批量提升业务状态。

### PATCH_SIGNING_TOOL

2026-10-22，产物入口扩展为 `NewPatchedApk.ps1 -Mode embedded|manager`，原 embed 脚本仅保留参数兼容转发。固定 833/普通 entry/sig2/native 哈希不变；输入接受已签名 Debug 或 Release 模块，核对包名、Modern 入口、版本和证书。用户密钥通过 SDK `apksigner` 重签名，口令只以环境变量名称传入；成功产物必须与参照证书相同，继续逐字节核对原 APK、embed 模块和 native payload。Manager 不允许残留内嵌模块。工具不安装、不卸载、不导出私钥；未通过时没有 `verified.json`。

证据 `captures/npatch-2b/signing-mode-acceptance/final-tool-tests/results.log` 共 11 项通过：自有证书 embed/同证书重建，固定 Manager 档案，部分签名参数、只有自有证书但无密钥、错误升级证书、未知 patcher、已有输出、已修补输入、错误模块包名、错误签名口令均拒绝，日志/产物记录无两种测试口令。正向产物的 `verified.json` 保存公开哈希/版本/证书，不含签名库或口令。测试使用不可用于分发的一次性密钥，口令仅存在本次环境并在 finally 清除。测试模块先复制为固定夹具，避免并行 APK 构建改写输入导致不一致；早期并行输入变化被原有字节核对拒绝，不当作最终失败或通过。

该检查证明产物和证书链，不证明任意自有证书下的登录/支付业务；运行支持矩阵仍按实测范围声明。正式 Release/R8 和模式往返在后续门禁单元记录，不以工具生成成功替代实机验收。安装前核对当前宿主证书，换证书不能通过卸载来伪装保留数据升级。

### APPEARANCE_REPRESENTATIVE

2026-10-22，在同一有线机及 8.90.2/833 embed/sig2/Android 16/arm64 范围补齐外观类别代表性兼容检查。证据 `captures/npatch-2b/representative-acceptance/`。使用正常模块 UI，将“宿主页面背景”从跟随哔哩哔哩切为海盐琉璃，保留默认模糊/饱和度/遮罩参数，未导入文件。冷启动后的首页卡片间隙、我的页内容区与返回首页均显示浅青色背景；原有宿主装扮的头像、顶栏和底栏装饰仍存在，列表和底栏可操作。见 `04-background-off-home`、`10-ocean-selected`、`11-ocean-saved`、`12-ocean-host-home`、`14-ocean-my`、`15-ocean-return` 的树/截图；不把推荐内容自然变化当作外观证据。

最终模块/宿主为 `f68386…3d29` / `15fcb3…360e`，与 `EMBED_DEX_SOURCE` 最终包相同。严格当前身份检查为 `host_background / INSTALLED / applied=1 / hooks=14 / runtime_error=false`，与 native 原版查询共 2 项通过（16.714 秒），见 `dex-source-acceptance/complete-android-native-and-appearance.log`。安装数量不能代替上述画面对照；应用回调和当前身份与画面一起判定。

通过同一 UI 恢复“跟随哔哩哔哩”并冷启动，页面恢复宿主背景，原有装扮保留；当前身份检查为 `DISABLED / observed=0 / applied=0 / hooks=0`，0.381 秒通过，见 `20-follow-saved`、`21-follow-restored-home` 与 `22-follow-restored-evidence.log`。本例没有调系统主题或旋转设置，不声明其它预设、自定义图片、性能、所有皮肤及 Manager 外观均已验收。仅将预设设置/背景能力两行记 `observed`；其余四个参数行及未测组合仍 pending。没有生产源码改动，复用相同 APK 的 3753 JVM、快速 Lint，另检查矩阵引用。

### SHARE_HANDOFF

2026-10-22，最终 DEX 来源 Debug 产物 `f68386…3d29` / `15fcb3…360e`，8.90.2/embed/833/sig2。打开既有公开视频《字幕君交流场所》，宿主分享面板正常显示；选择面板中的“更多”，系统 `com.android.intentresolver` 显示“分享文本”，预览为当前视频标题及宿主生成的 b23 短链，并列出可用接收应用。直接返回后仍是原视频详情，播放器画面保留。截图和树见 `captures/npatch-2b/representative-acceptance/31-share-video`、`33-share-panel`、`35-system-share`、`36-share-return`。

没有选择联系人、接收应用或发送内容，没有修改全局网络/互联设置。该案例证明本地分享面板、系统交接与取消返回，不证明微信/QQ 渠道的宿主端 SDK 检查、接收方身份检查、实际外部投递或账号风控；这些组合保持未验证，也不作为只能降级的证据。分享净化开关未开启，不提升其目录状态。仅更新证据文档，复用相同生产包的源码门禁。

### PATCH_R8_ENTRY

2026-10-22，正式产物检查发现 Debug 固定类名校验会拒绝工程正常 R8 改写的入口。改为一个合法类名并核对完整 Modern API 档案，保留签名、包名、原版/模块/native 内容校验；入口记录到 `verified.json`。真实 Release APK 入口为 `ve0`，不是固定的 `HookEntry`。13 项离线工具回归通过，新增多入口与未知 API 拒绝；负向 APK 夹具采用重建 ZIP 后重签名，避免原地更新 APK 对齐字段产生无效夹具。证据 `release-acceptance/r8-entry-debug-tools-fixed/results.log`。

`:app:minifyReleaseWithR8` 通过（4 分 20 秒）。本机未配置正式发布密钥，未修改工程签名门禁；通过既有签名配置入口，以原测试签名构建非 debuggable、开启 R8/资源压缩的本地 Release 验收包（1 分 12 秒），不作正式分发。模块 SHA-256 `93757b4d316a051ebc75d8e9cbff8f7c2dc581b11d250cbae85c06df20b8f86a`，embed 宿主 `b9bfc4eda1464e3dcc1dfa22e8f83cf030947e6358ca805322f67fcbd7c2952f`，设置 App 仍是原测试证书，宿主仍是原 NPatch 测试证书；未重建签名库或卸载应用。

相同有线机覆盖安装后，实际宿主日志获得新鲜 `module_direct` 许可，主进程安装链完成、49 Hook，后台原版来源 38 DEX/isolated。设置 UI 明确为“发布 构建”；首次打开设置页后出现旧配置提示，按提示再冷启动及刷新，诊断为当前直连配置已确认、内嵌内容一致，主/Web/下载/播放服务分别为 49/32/32/32 Hook。隐私政策 Web 页面有实际内容。证据 `release-acceptance/01-r8-host.log`、`04-r8-module`、`05-r8-diagnostics`、`10-r8-diagnostic-refreshed`、`11-r8-web` 及 `patched-r8-entry/verified.json`。不以首次旧回执或 Hook 数量代替这些检查；之后同证书恢复 Debug 包继续模式测试。完整 Lint 与最终统一回归在正式准入单元记录。

### MODE_ROUNDTRIP

2026-10-22，相同模块 `f68386…3d29`、embed 宿主 `15fcb3…360e` 与 Manager 宿主 `77200a…8b89`，同原版/固定 833/native/sig2/宿主证书。新增显式阶段检查，检查点只存独立测试包私有目录中的身份与免 Root revision；不备份账号或业务配置，不改设置或重启宿主。运行档案必须来自真实已安装 metadata；embed 为内容 MATCHED/作用域 UNKNOWN，Manager 为内容 NOT_APPLICABLE，均要求当前 nonce 查询、DIRECT、ACCEPTED、完整当前身份、实际安装完成及当前进程模式。Manager 已安装时仍识别 embed；不从 Manager 的存在推断模式。

首次引导在用户明确批准“永久授予”后，通过系统正常 UI 授予固定 Manager 1.0.9（833）所有文件访问，最终保留该权限；未启用可选 Shizuku、未读取个人文件。通过 Manager UI 为本模块选择 B 站作用域并点击“添加”，只读 Provider 返回本模块包名；见 `12-manager-access-granted`、`47-scope-saved` 和 `47-scope-provider.log`。

Manager 当前状态检查通过（0.536 秒），真实配置变化、关闭零 Hook、停用回执、重新开启及 Manager 强停后直连回归通过（81.844 秒），原版 38 DEX 的真实 ART/DexKit 查询通过（12.335 秒）。以 Manager 当前稳定身份重新建立检查点（0.544 秒），Manager→embed（12.522 秒）、embed→Manager（10.608 秒）、最终 Manager 强停后再→embed（10.287 秒）均严格保留同一完整身份与免 Root revision，且实际模式和新鲜宿主结果匹配。证据 `signing-mode-acceptance/50-manager-current-checkpoint.log` 至 `56-final-embedded-manager-stopped.log`。

共存专项复用既有完整真实宿主检查，Manager 保留安装并强停，embed 配置变更、关闭与条款拒绝零 Hook、恢复、Web 反复打开/返回/后台恢复及适用子进程通过；连同真实触摸复制共 2 项、135.726 秒通过，见 `57-embedded-coexistence-web-copy.log`。无 Manager 场景继续复用生命周期最终包证据；未为了复测而卸载 Manager 或宿主。测试最终实现复用已有环境封装，AndroidTest 构建与快速 Lint 通过，生产源码/模块 APK 未变，复用 3753 JVM 与生产源码门禁；最终正式准入再统一验证产物。

保留两次测试准备失败：`39-manager-retained.log` 在勾选未点击“添加”时无模块作用域；`48-manager-retained-confirmed.log` 在检查点后额外打开设置首页，首页刷新自由复制运行 revision，导致合法的新内容指纹。前者补齐 UI 确认；后者从当前稳定配置重新建立检查点且不再打开首页。没有放宽完整身份断言、用新检查点掩盖实际数据删除或把失败记为通过。UI Automation 连接占用时的一次 dump 失败也不代表宿主故障。

### RELEASE_ADMISSION_2B

2026-10-22，2B 按[支持范围](npatch-release-support.md)完成正式准入。最终中英繁文案移除 NPatch 的实验性标识，并明确仅验证 B 站 8.90.2、固定修复版 833、签名等级 2、Android 16/arm64；保留重新修补/固定作用域/设置 App 恢复及未知组合提示。此前各类别、升级/恢复、模式及签名案例复用，没有再次沿通用待验清单扩大修改。

最终源码门禁 `release-acceptance/final-source-gates.log`：Debug/AndroidTest APK、3753 JVM（0 失败、0 错误、1 跳过）、**完整 Lint**、Release R8/资源压缩通过，共 5 分 31 秒。完整报告 `app/build/reports/lint/full/` 为 1109 Warning、1 Hint，原 3 项错误基线不变；未改变依赖、混淆规则或 suppress。`final-jvm.json`、`final-matrix-check.json` 与 `final-daemon-check.json` 分别记录计数、410 行无失效案例引用和守护进程退出。正式发布签名未在本机配置，Release 只用原测试签名作本地验收，未发布。

最终 Debug 模块 SHA-256 `ac7261cbfc0df19a501a842c8ab28cac08251f8018fbe2f11a4858f273acd4a6`，embed 宿主 `0e5d0e112946ef90bacee1593e1c495e75d53b5707070017895c5c52ec9415a6`。最终 Release 模块 SHA-256 `93759e2bc7b7264a13afad00325bb77738887a08791e79f3779d0bc37b4982b1`，embed 宿主 `618390472ef5ad07aca31bd5b619508aee4ed076f88abcd13d80af738fa80c0f`，Modern 入口为实际 R8 名称 `ve0`。均核对原版、固定双 ABI loader、模块字节及原签名链，冻结副本与完整构建输出一致；记录在 `patched-final-debug/verified.json` 与 `patched-final-release/verified.json`。

最终 Debug 的 11 项端点/标准回退契约通过（3.087 秒），见 `final-endpoint-fallback.log`；这是端点及回退检查，不冒充全部为真实跨进程 IPC。真实宿主的 embed 共存配置变化/启停/条款拒绝/Web/适用子进程、原版 38 DEX 的 ART/native 查询及触摸复制共 3 项通过（163.727 秒），见 `final-embedded-native-web-copy.log`，运行的是最终测试实现。Manager 直连与模式往返复用同生产逻辑的 `MODE_ROUNDTRIP` 证据，标准框架没有新增 LSPosed 实机结果。

最后同证书覆盖最终 Release，正常 UI 显示发布构建、当前直连配置已确认、内嵌内容一致及限定支持提示；`23-final-release-current` 曾核对主/Web/下载/播放服务 49/32/32/32 Hook。`24-final-release-web` 截图可见实际隐私正文；`26-final-release-account`、`27-final-release-cache` 保留正式会员及 5.0 MB 条目，`29-final-release-cache-player-1` 与 `30-final-release-cache-player-2` 为缓存入口的两张不同实际视频帧，没有断网。未将 Hook 数量当作上述业务行为的替代证据。

收尾冷启动的首次查询没有本次进程响应，保留 `34-final-current.xml`；随后正常刷新得到当前完整身份，见 `37-final-current-refresh`、`38-final-current-ready` 与 `39-final-support-visible`。未放宽 nonce/身份或查询时限、未用旧 heartbeat 补成功；最终未响应的进程仍保持未知。`final-device-state.json` 为脱敏摘要。测试私有检查点已删除，Manager 所有文件访问按用户“永久授予”指示保留，设备留在最终 Release 当前配置诊断页。

## 后续顺序

2B 正式准入及阶段 3–6 的限定专项增强已完成。F19.1 / F19.2 当前结果见下例及阶段记录；其余系统/ABI/宿主版本/签名组合、第三方接收与长期风控验证继续保留在路线图，未测项保持待验。

### WECHAT_RESIGNED_SHARE

2026-10-23 实施、2026-10-24 收尾。最终模块 `c3aea1…3a5d`，Manager 宿主 `29aa04…efe7`、embed 宿主 `e01f5b…656a`，固定 8.90.2/833/等级 2/Android 16/arm64。详情与完整哈希见[阶段 7 微信记录](npatch-stage7-wechat-share.md)。

默认关闭的兼容选项仅调整已审计的分享 SDK 实例；最终两种模式均实际从普通视频的微信好友及朋友圈入口拉起 NPatch 重签名微信 8.0.64，当前配置获准、3 Hook、`observed=1` / `applied=1`。关闭后冷启动恢复原 SDK 的未安装误报与 0 Hook；真正未安装时面板省略微信/朋友圈。原签名微信在选项开启时另有交接及当前回执回归。

证据 `captures/npatch-f19/delivery-{embedded|manager}-{friend|timeline}/`、`delivery-embedded-off/`、`delivery-manager-absent/`、`resume-original-manager/`。Manager 初轮许可不可达保留原失败记录，经设置端恢复与冷启动后重新验收，没有计为成功或修改授权检查。

用户明确将账号内预览、取消回调和投递保留待验，未发送消息；`kntr` 仅有结构注册与 JVM 行为证据，真实备用入口及其他消息类型、宿主/接收方版本与标准框架未外推。本轮两条新增设置/能力记录在 Manager 与 embed 均为 `observed`，不提升为全链路 `passed`。

### QQ_RESIGNED_SHARE

2026-10-24，复用 F19.1 冻结模块和两种宿主产物，固定 8.90.2 / 833 / 等级 2 / Android 16 / arm64。设备原有 QQ 9.2.20 / 11650 为原签名包；同原包构造的 NPatch Manager 重签名副本已核对实际安装哈希和不同证书。完整输入、源码依据及边界见 [F19.2 记录](npatch-stage7-qq-share.md)。

embed 的原签名好友和 QQ 空间均交接到 QQ 登录页；QQ / TIM 均未安装时正确隐藏这两个入口。重签名 QQ 在 Manager / embed 下的普通视频好友、QQ 空间均由真实 SDK 启动 QQ `JumpActivity` 并进入登录页，未复现未安装误报，无需新增 QQ Hook 或设置。当前配置身份、实际模式、DIRECT 许可和安装链检查通过；QQ 专项操作期间微信兼容回调保持 0，不以该能力冒充 QQ 行为证据。

材料 `captures/npatch-f19-qq/embedded-official-{friend|qzone}/`、`embedded-absent/`、`embedded-resigned-friend-repeat/`、`embedded-resigned-qzone/`、`manager-resigned-{preflight|friend|qzone}/`。QQ 首次隐私 / 升级提示、登录页文本自动化误判与人工配对复核均保留，未升级、登录或发送消息。

账号内预览、取消回调与投递继续待验；QQ 自身 embed / LSPatch、TIM、独立 QQ 空间 App、其它版本 / 消息类型与备用真实业务入口未验证。本例没有对应新设置或能力，不新增矩阵行，不提高分享净化开关或既有 `SHARE_HANDOFF` 的状态；本轮只改文档，复用相同生产源码的既有检查。
