# Bilibili Innocent Lab 开发经验主文档

> 本文是项目长期维护的主经验文档，不受本地 `AGENTS.md` 速查篇幅约束。后续开发、
> 兼容性排查、性能优化和版本发布前，默认同时阅读本文与仓库内相关架构/验证文档；新经验
> 优先追加到本文，避免只留在聊天记录、临时反编译目录或单台设备日志中。

## 使用约定

- 经验必须写清楚现象、证据、根因、最终方案、失败方案和验证范围，不能只记录“已修复”。
- 宿主版本、ROM、LSPosed 分支和混淆符号会变化；优先记录稳定结构与判断条件，不把某个
  版本的字段名当成永久接口。
- 真机验证、JVM 测试、Lint、本地构建和 GitHub Runner 是不同的验证状态，必须分别说明。
- 性能优化要记录热路径是否改变、是否新增反射/对象分配、引用生命周期以及可回滚边界。
- 发布前只提交目标改动；本地 `AGENTS.md`、`Temp/`、设备日志和个人配置不得进入公开仓库。

---

## 2026-08-24：v1.0.6 评论自由复制稳定性与 Emoji 语义复制收口

### 1. 问题链与根因

本轮不是单一缺陷，而是评论绑定、宿主交互、富文本显示和系统剪贴板四条链路相互影响：

1. **切换评论区/详情页后误把“热门评论”“最新评论”当成正文**
   - RecyclerView 复用后，旧绑定任务仍可能把上一节点的 raw 写回已经换用途的 View。
   - 正文 TextView 兜底根与父 itemView 语义根并存时，raw 为空的子根曾遮蔽父根。
   - 修复方向是为每次绑定分配 generation，只允许最新代次登记或撤销状态；解析时沿祖先链
     优先选择 `rawTrusted` 的语义状态，raw 为空的触摸兜底根只保证手势可用性。

2. **触发自由复制后，评论右下角三点按钮不再弹官方面板**
   - 三点短按本不属于自由复制，但早期整树监听器和宿主窗口抑制范围过大。
   - 官方面板控制器会在 `show()` 前设置“已打开”状态；beforeHook 直接吞掉 `show()` 会让
     `onDismiss` 永远不执行，宿主状态无法复位，之后所有三点短按都被宿主自己拒绝。
   - 最终原则：图片、按钮、操作栏及其可点击容器不纳入评论长按树；需要压制官方长按面板时
     允许宿主完成 show/dismiss 状态机，再在首帧前按会话身份关闭，不能破坏宿主内部生命周期。

3. **快速滚动后概率弹官方面板，重新滚动又恢复**
   - 性能优化将全树绑定延后到 RecyclerView 空闲阶段后，滑停立即长按存在短暂未绑定窗口。
   - 功能检测不能完全依赖批量绑定完成。评论根入队时立即登记弱状态，触摸长按检测与
     IdleHandler 全树监听器补齐相互独立；LIFO 优先处理最新进入视口的节点。
   - 滚动中仍不做全树遍历，滑停后 120 ms 空窗配合每批 6 条，兼顾即时性与帧预算。

4. **9.8.0 气泡文本概率来自另一条评论**
   - 高版本 handler 的 CommentItem 字段是可变状态，afterHook 读取时可能已经被下一次复用覆盖。
   - 有 CommentItem 实参时必须优先取本次实参；9.8.0 部分绑定方法确实没有实参，则在
     beforeHook 同步捕获 handler 当前 CommentItem/raw，通过 `MethodHookParam` objectExtra
     传给 afterHook，不能事后再次读取可变字段。
   - 显示前仍以当前 View 树可见正文交叉验证 raw；generation、同步快照和可见校验三层同时
     成立后，才允许把完整 raw 送入气泡。

5. **自定义 Emoji 能显示但复制到剪贴板为空**
   - B 站图片 Emoji 的底层文本常是 `U+200B`，`ReplacementSpan` 只负责绘制，没有可复制语义。
   - 保留 `Spanned` 只能解决“看得见”，不能让 Android Editor 自动知道 `[表情名]`。
   - 自由复制需要把显示语义与复制语义分开：气泡继续绘制原 Span；用户点击系统“复制”时，
     只替换选区内已确认的 Span 区间为模型 token，再写入纯文本剪贴板。

6. **部分 B 站自有 Emoji 仍为空或映射到错误 token**
   - 不能把所有 ReplacementSpan 都当成 Emoji：评论中还存在卡片、角标、图标等绘制单元。
   - `CommentEmojiAdapter` 从当前 CommentItem 的 RichText contents 按稳定结构提取 Emote：节点
     同时具有 raw 中的 `[表情名]` 和图片/动画 URL；再从可见 Span 类层级读取字符串字段，
     以相同 URL 做一对一精确匹配。
   - 结构顺序对齐只作辅助。只要本条评论已有 URL 精确命中，未分类 Span 就不能再通过顺序
     猜测占用某个 Emote；无法确认的模型 Emoji 保留 raw `[表情名]`，最差也不能退回空白占位符。

7. **可展开评论未展开时 Emoji 仍复制为空，展开后正常**
   - 9.8.0 `NextExperiment3ExpandableTextView` 的折叠实现会截取完整富文本前缀，再追加带独立
     点击/着色 Span 的 `... 展开` 控制尾部；展开态才恢复完整富文本。
   - 旧身份校验只忽略省略号，不知道宿主追加的操作文案；安全快照又会丢弃 CharacterStyle，
     因而把同一评论误判成不同评论并退回只含 `U+200B` 的可见快照。
   - 最终在安全快照之前做折叠投影：按“尾部省略标记装饰 + 相邻操作装饰”的结构确定正文
     结束位置，不写死“展开”或任何语言；投影只参与身份校验和 Emoji 映射，不修改宿主 View。

### 2. 最终链路与 Adapter 分层

评论自由复制的稳定链路如下：

1. `VersionAdapter` 只负责定位不同客户端版本的评论绑定入口。
2. 绑定回调同步捕获 CommentItem/raw，生成 generation，并把状态存入弱 key 评论根。
3. 用户长按时才读取当前 View 的原始 `Spanned`，识别并剔除宿主折叠控制尾部。
4. 用可见正文、可信 raw、Emoji 槽位和当前绑定代次完成评论身份校验。
5. `CommentEmojiAdapter` 只负责模型 Emote 与可见 Span 的 URL/结构映射，不参与 Hook 定位。
6. `mergeVisibleEmojiSpans` 生成完整 raw 气泡：已确认 Emoji 保留绘制 Span，未知项显示 token 文本。
7. `FreeCopySelectionMapper` 只在系统复制动作发生时，把选区 Span 映射为 `[表情名]`。
8. 模块主动写剪贴板时使用 ThreadLocal 同步豁免，并要求当前模块气泡仍在显示；`finally`
   必须 remove，不能用全局时间窗放行官方复制。

这套分层的关键是：版本适配、评论身份、Emoji 模型、气泡显示和剪贴板选择互不越界。
不能把新版本混淆类名写入通用选择器，也不能为了复制语义破坏原有 Emoji 绘制 Span。

### 3. 性能与内存边界

- 本轮 Emoji 模型反射、URL 读取、折叠投影和选区映射都只发生在用户长按/复制的低频路径；
  没有新增滚动、setText 或触摸 MOVE 全局 Hook。
- 普通纯文本评论没有 ReplacementSpan 时，`CommentEmojiAdapter` 立即返回，不扫描模型。
- Method/Field 只按运行时 Class 缓存，不缓存 CommentItem、RichText、Span 或 View 实例。
- 评论根与最新 generation 使用 WeakHashMap；Dialog、触摸目标和长期 View 引用使用
  WeakReference；页面回收不应被模块状态阻止。
- 文本长度上限保持 3000；自定义 Emoji token 扫描上限 256；折叠控制尾部只检查末尾 96 字符。
- ActionMode 回调在气泡关闭时解除；Runnable 在 UP/MOVE/CANCEL 和页面销毁时移除。
- `ThreadLocal` 剪贴板豁免只覆盖当前线程当前调用栈，并在 `finally` 清理，不形成跨会话状态。

### 4. 必须保留的安全边界

- 不允许用裸时间戳全局抑制宿主窗口、剪贴板或点击事件；必须绑定会话 id、Dialog 身份和
  `isShowing/onDismiss` 生命周期。
- 不允许在评论链路过早 `toString()`；简介占位符净化和评论富文本必须走不同策略。
- 不允许把所有 ReplacementSpan 当成 Emoji；必须有模型 token、URL 或经过约束的结构证据。
- 不允许直接删除字符串“展开”；用户正文可能合法包含该词，多语言环境也会改变宿主文案。
- 不允许只信任 handler 可变字段或异步旧任务；优先本次方法实参，其次 beforeHook 同步快照。
- 不允许为了长按覆盖三点按钮、图片预览、头像、媒体卡片及其外层点击容器。
- 映射无法确认时必须显示 raw token 文本，不能重新退回不可复制的 `U+200B/U+FFFC`。

### 5. 验证结果与兼容范围

- 用户已在两台设备完成手工验证，哔哩哔哩 `8.90.2` 与 `9.8.0` 均通过。
- 已覆盖：普通评论、可展开评论折叠/展开态、纯 Emoji、多个 Emoji、混合绘制 Span、快速滚动
  后立即长按、评论区/详情页反复切换、三点官方面板、图片预览以及气泡内选择复制。
- 本轮 JVM 测试覆盖 Emoji 序列归一化、长 token、U+200B 哨兵、URL 一对一匹配、未知 Span
  回退、折叠控制结构、多语言操作文案和用户正文包含“展开”等反例。
- v1.0.6 发布前本地验证要求：`testDebugUnitTest`、`lintDebug`、`assembleDebug --no-daemon`，
  核对 APK mtime、内部 versionName/versionCode、打包 dex、SHA-256 和设备端 base.apk 哈希。

### 6. 后续回归清单

修改评论自由复制后，至少验证：

- 未展开长评论中的自定义 Emoji 可见且复制为 `[表情名]`，展开后结果一致。
- 本来不可展开的短评论、纯文字、Unicode Emoji 和纯 B 站 Emoji 均正常。
- 同一条评论含多个相同/不同 Emoji 时顺序正确，不串到其他评论。
- 快速滚动、滑停立即长按、展开回复、切换详情/评论页后文本身份正确。
- 自由复制气泡与官方面板不会双弹；关闭气泡后，三点、图片和其他短按立即恢复。
- 拖选、全选、复制和 OEM 选择句柄不崩溃；气泡关闭后没有遗留 ActionMode/Dialog 引用。
- 普通滚动和展开动画无新增明显卡顿；LSPosed 日志没有重复高频映射或反射异常。

---

## 稳定版发布纪律

1. 稳定版 `versionName` 使用不带 Alpha 后缀的 `X.Y.Z`，每次发布递增 `versionCode`。
2. 发布源码必须先通过测试、Lint 和无 daemon 构建；APK 必须晚于源码并包含目标 dex。
3. 提交并推送 `main` 后再构建最终发布 APK，确保 Release 标签、提交和产物可追溯。
4. Release 正文必须由 `.github/release-templates/stable.md` 渲染，删除填写提示并检查残留 token。
5. Stable Release 不得标记为 prerelease；资产至少包含带版本号 APK 与 `SHA256SUMS.txt`。
6. 发布后通过 GitHub Release API/CLI 回读 `tagName`、`isPrerelease`、目标提交和资产
   `name/size/state/digest`；不能用下载 URL 的 HEAD 响应判断上传是否成功。
7. 本地 `AGENTS.md`、`.gitignore`、`Temp/`、设备日志和个人文件不进入公开提交。
