# 屏蔽 AI 评论与回复

2026 年 10 月起，大批 AI Agent 小号涌入评论区：昵称"XX的大肥鱼"、多为 Lv2 新号，正文自称"本鲸"、括号里演动作、@ 主人汇报。
本功能是评论过滤的**第五条判据**，与关键词、等级、@ 整条、发布者并列，默认关闭，重启哔哩哔哩后生效。

## 做什么、不做什么

- 做：按昵称、等级、正文三类特征离线打分，总分 ≥ 阈值的评论与回复被隐藏（主楼、子回复预览、置顶位）。
- 不做：不新增 Hook 点、不新建过滤通道、不联网、不读 Cookie 或账号态、不看头像像素、不调模型。
  需要语义判断的灰区交给已有的「智能过滤评论（AI）」。

## 接入位置

```
ReplyInfo 公开 getter（昵称 / 等级 / 正文，均为已有读取路径）
        │
CommentFilterFeatureInstaller.shouldRemove(signals, plan)
        │  关键词 → 等级 → @ 整条 → 发布者 → AI 账号打分（最贵，放最后）
        ├─ Java 列表 / 置顶 getter：ProtobufListRetention.filterOrSame
        └─ Kotlin KReplyMoss 六个 RPC：ProtobufReplyTreeRewriter 整树删除
```

- 复用同一批 getter Hook 与 Kotlin 通道：`comment.filter.list.*` / `comment.filter.top.*` / `installKotlinChannel` 之外没有新的注册 id。
- 评分比其它判据贵，而列表 getter 在滚动、绑定时会被宿主反复调用，所以同一个列表实例（按引用、长度未变）只算一次
  （`ReplyVerdictMemo`，容量 6）。判据关闭时不走这一支，既有判据的行为逐字不变。
- 热路径只读已读出的字段：规则在类加载时预编译一次，打分函数里没有 `Regex(`、配置读取、磁盘或网络。
  昵称与正文先过 `TextNormalizer.forMatching`（NFKC、去格式字符、小写），与其它词表同口径。
- UID 只在白名单里写了 UID 时才读。白名单读不到的那一半无法保护任何人，此时整条判据不装并在覆盖口径里记一笔，
  而不是在保护失效时静默删评论。

## 评分

权重与规则移植自 [dafeiyu-cleaner](https://github.com/liyupi/dafeiyu-cleaner) 的 `detector.js`（MIT，见 `THIRD_PARTY_NOTICES.md`）。

| 维度 | 规则（节选） | 范围 |
|---|---|---|
| 昵称 | 肥鱼 / 肥鲸 / 鲸鱼娘 / 人机 +45；鲸 +30；AI / bot / agent +28；"某某的鱼"式 +14；叠字 +10；兜底含鱼 +8 | 封顶 55 |
| 等级 | ≤Lv2 +14；Lv3 +6；≥Lv6 −10；读不到不计分 | — |
| 正文 | 自称"本鲸 / 本鱼" +50；自曝住在 DSH +46；括号演动作 +40；@ 主人汇报 +15~20；数弹幕 ×N +15；AI 安利话术 +10（封顶 20）；破折号、直角引号、Markdown 残留、总结腔 +4~10 | −20..75 |
| @ 主人 | 昵称去掉宠物名常用字后，与被 @ 的名字两字滑窗重合 +25；开头 @ 后长文 +20；只 @ 无正文 +15 | — |
| 长度 | >150 字 +8；>70 字 +4（去表情占位后计） | — |
| 真人味 | 提问 −14；≤10 字 −8；哈哈哈 / 草 −8；仅当正文分 <40 时减分 | — |

总分限制在 0..99，≥ 阈值判定。阈值三档：宽松 75、**标准 60**、严格 45。

保护与放行（先于计分）：放行名单（UID / 用户名混排，用户名全等匹配）、≥Lv5 保护（默认开）。
自定义昵称词、正文词至少 2 个字，最多 64 项，命中按"强特征"+50 计分，不是直接删除。

### 与上游的有意差异

这里是**静默删除**，没有"存疑"这一档，宁可漏判也不错判：

- 读不到的信号不计分：等级读不到不当作 Lv0，昵称或正文读不到对应维度整体跳过；
- 真人也常说的泛用说法（"让我来"、"来打卡"、"替他说"、"我是学AI的"、"本酱"、"不是我发的"）从强特征降为弱特征（+12），
  不再与 Lv≤2 的 +14 合起来越过标准阈值；
- 去掉只对应某一期视频内容的复述规则（"缓存命中"、"驾驭"、"马具"等）；
- 带圈数字经 NFKC 会变成普通数字，认不出，与"1."列表合并按后者算；
- `\b` 改为显式的 ASCII 前后断言：Android 的正则引擎会把汉字算单词字符，"小AI助手"里的 AI 否则边界不上。

## 设置

| 设置 id | 类型 | 默认 |
|---|---|---|
| `comments.fish_filter.enabled` | 开关 | 关 |
| `comments.fish_filter.sensitivity` | 宽松 / 标准 / 严格 | 标准 |
| `comments.fish_filter.nickname_words` | 词表 | 空 |
| `comments.fish_filter.text_words` | 词表 | 空 |
| `comments.fish_filter.whitelist` | UID / 用户名 | 空 |
| `comments.fish_filter.protect_high_level` | 开关 | 开 |

后五项是总开关的子项（`SettingsDependencies`），总开关关闭时置灰但保留已存取值。目录版本 75 → 76，能力目录版本 37 → 38
（`comments_fish_filter_enabled`，归 `comment_filter` 安装组）。

## 宿主兼容性（dexq）

P0 只用已有的昵称 / 等级 / 正文读取路径，不新增适配点，所以与评论过滤其它判据同样覆盖 8.84.0 起的各版本。

调研时核对了原计划里 P1 想用的字段（只查了 9.19.0 一个版本，dexq run 42954093689）：

- `Member` / `MemberV2$Basic` 有 `getFace()`（头像 URL，`String`）；
- **没有 `getSign()`**，也没有 `getOfficialVerify()` 对象：认证是扁平的 `Member.getOfficialVerifyType()`（`long`）与
  `MemberV2.getOfficial().getVerifyType()`，大会员是 `Member.getVipStatus()` / `MemberV2.getVip().getStatus()`；
- proto3 未设置时数值字段为 0，而 0 恰好是"个人认证"，所以认证减分必须先用真机载荷确认"无认证"的取值再用。

## 已知限制与后续

- 未做真机验收：需要在 8.90.2（经典原生）与 9.14.0+（KMP）上分别核对主楼、楼中楼预览、置顶位，并按
  "名字带鱼的真人、蓝发头像用户、提问与短吐槽、Lv6 老号、官方认证号、UP 主本人"做误伤回归。
- 智能过滤与本判据同开时，本判据命中的评论仍会被送去判定（只是结果不再用到）；让本判据先判、跳过已命中的正文可以省调用，
  但要同时改三处取正文的路径，没有放进这一版。
- 后续（各需单独验证）：头像 URL 名单（`getFace`，需要先有真实样本）、认证与大会员减分、同页刷屏计数、维度开关与自定义阈值。
