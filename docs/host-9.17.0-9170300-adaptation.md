# 9.17.0（9170300）宿主适配与验证

目标为 `tv.danmaku.bili` 9.17.0 的第二个构建 9170300，APK SHA-256 `b3950fd3e4f0111e22803eddb85f6e54fcfea2f7cb224d821ad0f9dd9ac7b57f`（243,720,779 字节）。基线为同版本号的 9170100，SHA-256 `f0f880e841d017db335b3919e6d4792961f2e08aa9993da661b626d52cfcb955`（243,687,778 字节）。两者证书一致（`93ba270f…910e`），绑定样本 revision `2a0628e3077d910e97f2dff993d3e8764589dd55`。

本轮只通过项目云端 dexq 读取宿主结构、成员、字符串引用与反编译结果，没有下载 APK/DEX，也没有上传模块源码。远端 `reports` 分支的多路径报告（run `41120475810-a1`）只作线索：它的 12 条发现全部是 `REVIEW_REQUIRED` / `runtime_enabled=unknown`，且源码快照来自 2026-10-15 的未提交工作树，不能当作当前模块的适配结论。用户指定以软件检查为交付门禁，本轮没有真机验收。

## 先要回答的问题：9170300 是增量构建吗

不是。对全部 3313 个类名前缀桶（前三个字符）统计含方法的类数、方法数、指令数和描述符长度，2678 个桶不一致；含方法的类总数只从 341,197 变到 341,298。模块里硬编码的混淆名因此不能默认沿用，必须逐个核对。

好在高价值的顶层混淆 owner 没有动：`Bx1.c`（更新检查）、`tz1.h`（默认画质）、`rU.d`（关注流绘制基类）、`Xw1.a`（品牌开屏解码）、`CS.I` / `CS.J`（动态模型）的方法体、签名与指令数逐项一致。混淆名被重新排位的是各业务包里的叶子类。

## 已修复

| 路径 | 9170100 → 9170300 | 模块处理 |
| --- | --- | --- |
| 关注流首页装饰器 | `followinglist.home.a0` → `home.X`；`a0` 已不存在，`home.U` 变成不相干的类 | `FollowFeedAdapter` 当前 painter 一代的候选加入 `X`，仍要求直接继承 `rU.d` |
| 关注流个人页装饰器 | `quick.consume.F` → `quick.consume.D` | 个人页候选加入 `D`；`F`、`I` 在 9170300 里不是 `rU.d` 子类，被父类检查排除 |
| 关注流分隔线 | `quick.consume.T` → `quick.consume.P`（`onDrawOver` 均为 50 条指令） | 分隔线候选加入 `P`；9170300 的 `T` 没有 `onDrawOver`，不会被选中 |
| 动态模型本地归属字段 | `CS.I` 上 `CS.J` 类型字段由 `b` 前移为 `a` | 原有 `a`/`b` 双接受不需要改；新增 9170300 夹具钉住 `a` |
| 图文宿主背景 | `opus.t`（继承 `opus.q → detail.a → rU.d`）→ `opus.p`（继承 `opus.m → detail.a → rU.d`）；`opus.t` 与 `opus.k` 变成无关的 synthetic lambda | `HostOpusBackgroundAccess` 候选加入 `p`；继承过滤保证 `t` / `k` 不会命中 |
| 首页 Tab 资源模型 | `main2.resource.z` → `resource.y`，13 个字段完全相同 | 主路径取 `HomeFragmentV2.og(List)` 的泛型实参，不受影响；仅更新泛型不可读时的兜底候选，`z`（现为 lambda）会被字符串字段数检查拒绝 |

上述任何一项落空，关注流 points 都会整体为 null（`home` 候选的 `singleOrNull` 失败），图文背景则是 `resolve` 返回 null，两者都是静默不生效。适配规则升至 81（缓存 JSON 形状不变，不抬 schema），使 9170300 上曾缓存过「未命中」的设备重新定位。

## 逐项核对为未变化

DexKit 语义兜底逐条改写成 DexSQL，在两个构建上各跑一遍，比较命中的 owner、规范化签名和指令数。

| 查询 | 命中（9170100 → 9170300） | 结论 |
| --- | --- | --- |
| `BLOCK_UPDATE` | `Bx1.c#a(Context)` → 同名同签名，152 条指令 | 一致 |
| `PLAYER_DEFAULT_QUALITY` | `tz1.h#a()I` → 同名，28 条指令 | 一致 |
| `STORY_QUALITY_RESOURCE` | `story.player.quality.u#b` → `v#b`，71 条指令 | 唯一，名称漂移 |
| `STORY_QUALITY_MATCH` | `StoryQualityService#n`，219 条指令 | 一致 |
| `STORY_QUALITY_DEFAULT` | `story.player.quality` 包内调用 `PlayerSettingHelper#getDefaultQuality` 的无参方法唯一，24 条指令 | 一致（Wi-Fi key 一支两侧均 0 命中，与既有「取并集」一致） |
| `FOLLOW_FEED_STYLE` | `rU.d#getItemOffsets` 唯一，36 条指令；offsets 日志另命中 `opus.q` → `opus.m`（78 条） | 一致 |
| `BRAND_SPLASH_DECODER` | `Xw1.a#a(BrandShowInfo)`，56 条指令 | 一致 |
| `PLAYER_POPUP_FREE_DATA_GUIDE` | `mediaplay.network.p#f` → `m#f`，294 条指令 | 唯一，名称漂移 |
| `PLAYER_POPUP_ADVERT_FLOAT` | `DmAdvert#getFloatLayers` / `getValidPanelData` | 一致 |
| `COMMENT_CLASSIC_NATIVE` / `KOTLIN` | 实验键调用者 `Cq1.b` → `Sr1.k`、`Kq1.a` → `Fp0.f`，各唯一；方法体已不同，但两侧都经 `DeviceDecision#getBoolean(String,Z)` / `IDeviceDecisionKt#getBool$default` 读取，这两个类的 28 个成员逐项一致 | 唯一，owner 漂移，读取边界不变 |
| `SPONSOR_RUN_PLAYABLE` / `WRAPPER` / `CONTAINER_SCOPE` | 3 个 / 1 个 / 17 个候选，两侧集合一致 | 一致 |
| `PLAYER_SPEED_MENU` 四支 | 填充 `widget.speed.f#l0`、选项工厂 `utils.I#a`、打开事件 `page.speed.b` → `a` | 唯一，名称漂移 |
| `OGV_TRENDING` / `DOUBLE` / `UPCOMING` | 各 1 个，指令数 ±6 以内 | 一致 |
| 首页 chrome 八支 | `HomeTopTabRow` 等 8 个标记各唯一，指令数 ±12 以内 | 一致 |
| `PEGASUS_DIVIDER` | `pegasus.widget.j#onDrawOver`，51 条指令 | 一致 |
| `SEARCH_HOME_DELIVERY` / `STATE`、`PLAYER_END_PAGE_MERGER`、`COMMENT_REPLY_MAPPER` | 候选集合两侧一致 | 一致 |

其余核对：

- 539 个源码字面量锚点与 149 个常量拼接结果中，两侧都有定义的 274 个类，忽略混淆类型名和混淆成员名后 206 个完全一致；24 个是更新/默认画质/Story 旧版候选表里的历史顶层 owner（`Ip1.c`、`Ro1.c`、`Dr1.c` 等），当前宿主不依赖它们；39 个是仅指令数 ±1～6 的漂移；5 个结构变化即上表的 `opus.k`、`opus.t`、`feedback.v`、`pausedpage.ui.g`、`resource.z`。
- `feedback.v` 只作 OGV 单卡的 helper 过滤，`ed3.r` / `sc3.n` / `rc3.l` 在 9.17 不存在，该分支本就走语义兜底；`pausedpage.ui.g` 在两个构建里都不是零参 `invoke`，旧版 Function0 判定本来就不命中；`TeenagersMode.isEnable` 签名变化与模块无关，模块只依赖 `TeenagersModeDialogActivity#onCreate`。
- 9170300 里新出现的 `Sn1.c`、`Ar1.l`、`Oo1.i`、`Ze1.h` 重用了旧候选名，但分别是 editor lambda、资源解析器和数组常量类，不满足更新/画质签名，而且新 owner 排在候选表前面。
- 评论 Compose 模型 `CommentModel` 29 个字段、`CommentRichTextRendererModel` 18 个字段按规范化名称完全一致，`CommentComposeCopyBridge` 的 `a/i/j/k/o/p/b/c` 读取契约成立。
- 评论回复映射 `data.source.v1.c`、快速回复收集器 `CommentContainerImpl$attachRepository$5$2`（`PublishDialogIntent`，260 条指令）两侧一致。
- 模块用到的 137 个 `com.bapis` 协议类，字段数完全一致，方法定义的指令总数完全一致（方法数的差别来自跨类引用）。
- 品牌开屏 `kntr.srcs.app.splash.brand.startup` 包 30 个方法的签名与指令数逐项一致，只有 `Zn1.a` 模型被重命名为 `Yn1.a`。
- 字母表扫描型定位（评论 holder handle、push holder、图片卡 handler、Compose 菜单、tags 映射、relate game、gemini ui、倍速、搜索）按方法签名多重集逐类映射，体量大于两个方法的类全部仍落在各自扫描字母表内。

## 远端报告线索的处置

远端报告只比较了家族级指纹，把每一项都标成「需复核」。逐项对照模块后的处置如下。

| 报告发现 | 处置 |
| --- | --- |
| F001 `dynamic_compose` +10 个 Compose 方法 | 动态净化工作在 MOSS 协议层（`com.bapis.bilibili.app.dynamic.v2.*`、`KDynamicMoss`），这些类的字段数与方法体指令总数两侧一致；模块没有 Compose 方法级锚点，无需改动 |
| F002 `comment_native_legacy` +11 类 | 原生评论 handler 由包内字母表加结构过滤定位；按方法签名多重集逐类映射，体量大于两个方法的角色类没有跑出扫描字母表。新增类没有逐个反编译 |
| F003 `CommentClassicStyleLocator` | 两个读取边界类 28 个成员逐项一致，实验键调用者各唯一（见上表） |
| F004 首页双路径 | chrome 八支语义查询两侧各唯一；`HomeFragmentV2.og(List)` 338 条指令不变；Tab 模型 `z → y` 已处理 |
| F005 / F006 搜索 | 投递、状态、默认词查询两侧一致；`search_native` 的边数变化没有对应的模块锚点变化 |
| F007 `detail_theseus_*` | 命名锚点类仅有指令数漂移；`DetailRelateService` 等未见签名变化 |
| F008 `pgc_theseus` / `pgc_legacy` | 活动弹窗用到的 `OgvActivityVo`、`_JsonDescriptor`、`OgvActivityHalfScreenPopup`、`PojoClassDescriptor`、`PojoPropertyDescriptor` 五个类逐字节一致 |
| F009 `player_kotlin`、F010 `danmaku_kotlin` | 倍速菜单、Sponsor、默认画质查询一致；弹幕净化走 `DMMoss` 协议层，协议类一致 |
| F011 `story_native` | Story 清晰度三支一致；动作图标按 widget 类名挂接，8 个 widget 类仍在，`StoryDanmakuToggleWidget` 与 `StoryLandscapeCommentWidget` 只有混淆字段名前移，模块不按字段名读取 |
| F012 `mine_account_model` | `AccountMineV2`、`MenuGroup`、`MenuGroupV2`、`MenuGroupV2$Item` 在忽略混淆名后一致 |

## 边界

- 这是静态结构证据，不证明 LSPosed 注册、服务端实验分组或页面展示。没有设备，也没有在 9170300 上运行 `HostApkCompatibilityInstrumentedTest.verifyHostApkContracts`；建议拿到 9170300 的原版 APK 后按该测试的说明显式跑一遍，它直接用生产定位器解析真实类。
- 字母表扫描型定位只能离线证明「角色类仍在扫描字母表内」，不能证明谓词逻辑本身仍然成立。
- 指令数 ±1～6 的漂移覆盖了绝大多数命名类，其中多数是编译器内联/重排，少数可能是真实小改；没有逐个反编译比对，Hook 点所在方法的方法体差异未逐条核。
- 样本只有 9170100 与 9170300 两个 9.17.0 构建，没有 9170200。

## 云端证据

下列 matrix / job 保留在本机被忽略的 `runtime/hostlab`，均绑定上面的 APK SHA 与样本 revision。

| 内容 | 标识 |
| --- | --- |
| 样本清单 inventory | request `a16867c6dc00457692f34870c07c6ee2` |
| 前缀桶全量比对 | matrix `6f1fc7c2b5a248dcb9186867fc65da99` |
| 锚点形状指纹 | `7c0c24518fbe448f874f4918686d87f4` |
| 变化类逐方法指令数 | `6a5d66d37d1d462886a4bf8c7a2b410f` |
| 业务包类普查 | `f1da0ad62e504f8e95e87a1bb0110c6e`、`6c011fd0a4fd46ffbc288d2d727cd5fe` |
| 关注流继承树、绘制基类、分隔线、`CS.I`/`CS.J` | `d876ee055ed74bdca19d8215acf87d7a`、`32b3e6f496624224a3a4a69612b506e1`、`0e5d003a56934516ae4f32c39dd9c643` |
| 语义查询改写（字符串类） | `32e5119114fb4dfe9690d90745408884` |
| 语义查询改写（签名与调用类） | `eb77ff29e9724a1ebb0519ac173e19e3`、`358bbe59d38f4edbb54fad5a60cfeade`、`efe689122e0a4f54b396adb3da001c26`、`c669ced224434935a5661eba929cfbfc` |
| 协议类 | `2daf30a6fc6c42df874e9b36c7c517d4` |
| 评论模型字段、快速回复收集器 | `dbbe793144994f27b84c457c72c1eea4`、`81e9b112080b4eb0ac71e50ba95e7f6c` |
| Tab 资源模型 | `ff2289ed5edc4b89aeea15dcce2e8d33` |
| 字母表扫描包逐方法 | `430f3511fbcc4d0c895458b06d35aead`、`46ca9bcdade54ed4a7b557dc4f449990`、`3a11b1be3fcd4afa87862f337d0329fc`、`3a8e5fa172fe4524a301d399474b0f12`、`598dd4dd3385488f8c1eb5a607366b82`、`b6051aeab0c84cecbc9bdf06b1c4d763` |
| 6 个变化类的反编译（两个构建各一份） | `opus.t`、`opus.k`、`resource.z`、`TeenagersMode`、`pausedpage.ui.g`、`feedback.v` |

## 验证状态

完整门禁（`run_gradle_gates.py`：lintDebug、compileDebugUnitTestKotlin、testDebugUnitTest、assembleDebug、compileReleaseKotlin、compileReleaseJavaWithJavac、minifyReleaseWithR8）回执为 success：4446 项 JVM 测试，0 失败、0 错误、1 跳过；完整 Debug Lint 仍是既有 3 条错误基线，没有扩展。回执来自第二次运行，其中 assembleDebug、Release 编译和 R8 沿用同一源码上第一次运行实际执行过的结果（见下）。

新增回归：`FollowFeed916AdapterTest` 增加 9170300 构建夹具（`rU.d` 不变，子类重排，旧名保留为不相干类），覆盖首页/个人页/分隔线/图文解析、旧缓存与新缓存互相不能误验、未知本地归属保持不可用。撤掉 `FollowFeedAdapter` 与 `HostOpusBackgroundAccess` 的修改后，其中 3 项失败，恢复后通过。

首次完整门禁中，`QuickLocateCharacterizationTest` 有一次失败：黄金文件里 `CommentFollowWidget` 夹具的 `declaredMethods` 顺序与期望不同。该测试比较的是反射方法顺序，与本次改动无关；同一源码上重跑完整单测、完整门禁以及该测试 6 次均通过，没有复现，未作处理。
