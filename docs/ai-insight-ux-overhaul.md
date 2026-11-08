# AI Insight 执行链路 Code Review 与体验优化方案

本文以「设置写入 → 启动读取 → 选源 → 请求 → 流式 → 校验 → 渲染 → 失败」为线索做完整走查，回答配置共用后的实际行为，列出全部恶性 bug 与体验缺陷，并给出分阶段修复方案。本次只产出方案，不改代码、不跑构建与 Lint。

代码位置均为本仓库当前 worktree 的相对路径，格式 `文件:行`。

---

## 0. 结论

**AI Insight 能跑通，但「可用」和「体验不差」两个标准都不达标。** 三层根因：

1. **配置架构错误**：AI Insight 没有自己的服务配置，直接吃 JEV 语义判定的「来源 1～8」。两者请求栈、协议、参数、输出预算完全不同，却被绑在同一组数据上，导致用户只想给 AI Insight 配一个模型，却同时把四个自动过滤面全部指向它。
2. **协议适配缺失**：只支持 OpenAI `chat/completions`，Anthropic `messages` 完全不支持；端点解析有两套互相矛盾的规则，说明文案承诺的「只填域名」实现根本不接受。
3. **失败处理把用户成果作废**：流式已经显示一大半的内容，末尾任何异常都会整段丢弃并统一显示「请重试」。

**P0 共 14 项**（见 §3），其中用户直接遇到的是 D1/D2/D3/D4、B1/B2、C1/C2、A1/A2/A3。

---

## 1. 执行链路全景

| # | 环节 | 入口 / 关键代码 | 走查结论 |
|---|---|---|---|
| 1 | 设置写入 | `SemanticJevDialogs.kt:473-560`（`showSemanticSourceDialog`） | ❌ 保存地址校验与说明矛盾；Key 单行密码框；静默截断 |
| 2 | 偏好存储 | `FeaturePreferences.kt:337-397`、`RemoteHookConfigContract.kt:67` | ⚠️ 1 号用旧 `semantic_jev_*` 键，2–8 号用 `semantic_source_N_*`，与「判定」语义绑定 |
| 3 | 启动读取 | `HookEntry.kt:2536-2556`（`SemanticSettings.from`） | ❌ AI Insight 的来源来自 JEV 共享对象；任一有效来源都没有时该对象为 null，AI Insight 静默失效 |
| 4 | 来源解析 | `SemanticSources.kt:27-34`、`SemanticBackend.kt:87-107,151-169,250-259,357-377` | ❌ 纯域名被拒；允许 http；三层各自补路径 |
| 5 | AI Insight 选源 | `AiInsightFeatureInstaller.kt:26-30`（`InsightSources.resolve`） | ❌ 取「编号最小的非 JEV 来源」，用户不可见、不可指定、无提示 |
| 6 | 来源二次解析 | `HookEntry.kt:2778-2784` → `AgentModelTypes.kt:44-61` | ❌ 强制 https、一律补 `/chat/completions`；与第 4 步规则不一致 |
| 7 | 请求构造 | `AgentModelClient.kt:61-148` | ❌ 仅 OpenAI 格式；对 openrouter.ai 无条件注入 `reasoning.effort=none` |
| 8 | 传输 | `AgentHttpTransport.kt:45-120`、`AgentChatStream.kt:11-84` | ❌ `finish_reason=length` 丢弃全部；流尾只认 `stop`；`Authorization: Bearer` 写死 |
| 9 | 生成编排 | `InsightRepository.kt:91-164` | ❌ 90 秒共享 deadline；校验失败清空预览重放；只有一类异常会重放 |
| 10 | 流式投影 | `InsightStreamText.kt:4-51`、`InsightResponseText.kt:38-64` | ⚠️ 设计上只是「预览」，不算成果；失败时 `fail()` 直接覆盖正文 |
| 11 | 结果校验 | `InsightProtocol.kt:125-212,215-246` | ❌ 字段集合必须完全相等、禁止任意 `mm:ss`、时间引用必须落在字幕覆盖内 |
| 12 | 渲染 | `InsightCard.kt:47-424` | ⚠️ 按需生成、结果分面板、缓存隔离等设计是合理的 |
| 13 | 失败 | `InsightCard.kt:262,332-337,360-392` | ❌ 所有失败统一「暂时无法生成，请重试。」+ 重试按钮，`Reason.description` 完全没用上 |
| 14 | 缓存 | `InsightRepository.kt:14-74` | ✅ 按 版本/视频/分 P/端点/模型/任务 隔离，失败不缓存，行为正确 |

链路中 **12、14 是健康的**；**1–11、13 都有明确缺陷**。

---

## 2. 配置与 JEV 共用后到底怎么选源（回答核心疑问）

### 2.1 不是随机，是速度加权调度 + 三重放大

`SemanticSources.kt:38-50` 定义 `AUTO` = **全部已配置来源**；`SemanticSourcePool.acquire`（`SemanticSources.kt:149-171`）按 `(在途数+1) × 平滑耗时 ÷ 可信度` 选「预计最快完成」的来源，并列按编号，首轮每个来源各试一次。所以**不是随机智能分流，是确定性加权**，但结果一样会把请求撒到所有来源上。

在此基础上还有三重放大，全部会重复计费：

- **对冲（hedge）**：`SemanticJudge.kt:793-795,826-832,855-889`。用户在等的分批（`SemanticMode.WAIT`）超过该来源平滑耗时一定倍数还没回来，就**把同一批再发给第二个来源**，谁先成功用谁，落后的那份「照常跑完写缓存」（`:795`）。
- **灰区 / 屏蔽复核**：`SemanticJudge.kt:798-799,902-972`。≥2 个来源时，判「屏蔽」或分数贴近门槛的条目，在后台交给**另一个来源再判一次**；新来源、低可信来源的屏蔽结果全部复核。
- **故障转移重试**：`SemanticJudge.kt:833-844`。一个来源失败后把未判出的条目交给下一个来源。

### 2.2 四个过滤面默认全开 AUTO

`FeaturePreferences.kt:374-378,399-404` 有四个面：动态、弹幕、评论、推荐视频。`HookEntry.kt:2581` 读取路由时默认值就是 `SemanticRoute.AUTO`，`SettingsCatalog.kt:265-272` 里四个条目的 `default` 也全是 `AUTO`。`SemanticSources.kt:52-54` 明确：**来源池是进程级、所有过滤面共享的**。

判定请求本身按「每条文本 × 每条规则一题」构造（`SemanticBackend.kt:285-305`），输出预算 `1024 + 条数 × 规则数 × 32` token；规则正文每批重付。

### 2.3 AI Insight 用的是另一套请求栈，却共用同一份配置

- AI Insight 选源：`AiInsightFeatureInstaller.kt:27-30`，按编号升序跳过 `JEV` 后端，取第一个能构造 `AgentModelSource` 的来源。
- 请求栈：`InsightRepository.kt:80` 的 `AgentModelClient`（8192 输出预算、50 万字符输入上限、SSE 流式、`json_schema`）→ 与 JEV 的 `OpenAiCompatibleBackend` 完全不同。
- 也就是说：**两者只共用配置数据，不共用请求实现**，却被硬绑在同一组「来源 N」上。这是架构错配，不是复用。

### 2.4 直接后果

用户把 OpenRouter 的 Haiku 填进任意来源后：

1. 只要**任一智能过滤面处于开启状态**，四个面默认 AUTO 就会把请求发到这个昂贵模型上，且带对冲与复核的重复请求。用户「只想用于少量 AI Insight」的意图完全无法表达。
2. AI Insight 自己只会用**编号最小的非 JEV 来源**。想换模型只能调整来源编号顺序，而调整顺序又会改变四个过滤面的调度；没有任何 UI 显示「AI Insight 当前用哪个来源」。
3. 若把便宜模型放 1 号、昂贵模型放 2 号，AI Insight 用的是便宜的 1 号，与用户预期相反。
4. 若 8 个来源全是 JEV 类型，或全填了 http 地址，AI Insight **完全没有来源**，卡片只显示「请先在模块 AI 配置中设置文本生成模型」（`InsightCard.kt:104`），不告诉用户为什么。

**结论：配置共用确实会极大浪费昂贵模型的 token，而且用户没有任何手段阻止。这不是「智能分流」能解释的，是功能边界缺失。**

---

## 3. 问题清单

严重度：**P0** = 功能不可用 / 用户明确踩到 / 直接造成费用或数据损失；**P1** = 明显体验缺陷或隐性失效；**P2** = 打磨项。

### A. 配置架构

| 编号 | 严重度 | 现象 | 根因与位置 |
|---|---|---|---|
| A1 | P0 | AI Insight 无法独立配置服务，必须与 JEV 共用来源表 | `HookEntry.kt:2781` 把 `semanticSettings?.sources` 直接传给 `AiInsightFeatureInstaller`；无专用偏好键 |
| A2 | P0 | AI Insight 用哪个来源不可见、不可指定 | `AiInsightFeatureInstaller.kt:26-30` 硬编码「首个非 JEV」；`ai_insight_help` 只写「下方首个文本模型」（`strings.xml:5`） |
| A3 | P0 | 昂贵模型被四个自动过滤面重复调用，token 成倍消耗 | §2.1–2.2；`SemanticJudge.kt:793-799,826-832,902-972` |
| A4 | P1 | 一个有效来源都没有时 AI Insight 静默失效 | `SemanticJudge.kt:394` 返回 null → `HookEntry.kt:2781` 得到空列表 |
| A5 | P1 | 菜单归类混乱，同一组配置有五种叫法 | 开关在「实验性功能 → 兼容」`SettingsCardSections.kt:1348`；配置面板叫「AI 语义判定」(`:1377`)；Agent 面板入口叫「配置 API 来源（最多 8 个）」`AgentDialogs.kt:148`；目录 id 是 `compat.ai_insight.*` `SettingsCatalog.kt:198`；卡片提示叫「模块 AI 配置」`InsightCard.kt:104`。全部塞在「兼容」区，与通信兼容、access_key 授权、免 Root 并列 |
| A6 | P1 | 改一次配置要冷启动宿主才能验证 | `ai_insight_help` 与 `semantic_route_dialog_tip`（`strings.xml:1330`）均要求重启；`HookEntry` 只在启动时组装 `semanticSettings` |
| A7 | P2 | 两层各自补端点路径，规则重复且易漂移 | `SemanticBackend.resolveEndpoint` 与 `AgentModelTypes.kt:44-61` 各补一次 `/chat/completions` |

### B. 端点与协议

| 编号 | 严重度 | 现象 | 根因与位置 |
|---|---|---|---|
| B1 | P0 | 说明写「可以只填域名」，填了却报「接口地址必须是 http(s) 地址」 | `strings.xml:1363`「留空用默认；可以只填域名」、`:1229`「只填主机自动补 /v1/systemone」；`strings.xml:1200` 报错文案；实现 `SemanticBackend.kt:88-94` 用 `URL(value)` 且要求 `protocol ∈ {https,http}`，纯域名抛 `MalformedURLException` 直接返回 null；保存校验 `SemanticJevDialogs.kt:542-545`。唯一例外是 Cloudflare 的 32 位账户 ID（`SemanticBackend.kt:359`） |
| B2 | P0 | 只支持 OpenAI `chat/completions`，不支持 Anthropic `messages`；填 Anthropic base URL 会 404 | `SemanticBackend.IDS = {JEV, OPENAI, CLOUDFLARE}`（`:74`）；`AgentSourceProtocol = {CHAT, DECISIONS}`（`AgentModelTypes.kt:9`）；`AgentModelTypes.kt:55-60` 一律补 `/chat/completions`；请求体 `AgentModelClient.kt:74-93`、鉴权 `AgentHttpTransport.kt:69` 写死 `Bearer`；SSE 只解析 `choices[0].delta.content`（`AgentChatStream.kt:39-62`）。`strings.xml:1367` 已承认「暂不支持 Azure OpenAI（它要 api-key 请求头）」，同类问题在 Anthropic 上完全没做 |
| B3 | P1 | 填 http 地址：JEV 过滤能用，AI Insight 静默失效 | `SemanticBackend.kt:92` 允许 http；`AgentModelTypes.kt:49` 强制 `https`；`AgentModelSource.from` 用 `runCatching{...}.getOrNull()`（`AgentModelTypes.kt:42`）吞掉异常 → `InsightSources.resolve` 跳过该来源，无任何提示 |
| B4 | P1 | 「留空使用官方地址」对三种后端语义不同，Cloudflare 留空必失败 | `strings.xml:1228` 单一 hint；`SemanticBackend.kt:152,251` 有默认端点，`CloudflareBackend.resolveEndpoint`（`:357-377`）无默认地址返回 null |
| B5 | P2 | 地址 / 模型输入框使用 URI 键盘 | `SemanticJevDialogs.kt:140` `TYPE_TEXT_VARIATION_URI`，输入模型名、带符号地址时键盘行为不友好 |
| B6 | P2 | 基址与完整端点、query 的处理散落两处，用户填 `…/chat/completions?api-version=…` 类地址时行为不直观 | `SemanticBackend.kt:96-107` 与 `AgentModelTypes.kt:54-60` |

### C. 设置 UI 与说明

| 编号 | 严重度 | 现象 | 根因与位置 |
|---|---|---|---|
| C1 | P0 | 输入长 API Key 时内容越出输入框、无法核对是否粘贴完整 | `SemanticJevDialogs.kt:122-156` 的 `form.field` 对所有字段统一：单行（`isSingleLine=true`）、固定 padding、无 `ellipsize`/横向滚动保护、无显示/隐藏切换、无字符数反馈；`secret` 仅 `TYPE_TEXT_VARIATION_PASSWORD`（`:138`）；保存时 `key.trim().take(512)`（`:546`）与 `model.trim().take(128)`（`:536`）**静默截断**，超限不提示。用户报告的越界具体渲染原因需一张截图确认，但「统一表单对长单行凭据没有任何约束与反馈」是确定的设计缺陷 |
| C2 | P0 | 三处说明互相矛盾 | `strings.xml:1363`、`:1229` 说可只填域名/主机；`:1200` 说必须 http(s)；`:1367` 的示例却全部写完整 `https://…`。`semantic_jev_provider_help`（`:1303`）只列 OpenAI 兼容站点，不提 Anthropic |
| C3 | P1 | 「测试连通性」通过不代表 AI Insight 能用 | `SemanticJevDialogs.kt:514-520,608-622` 用 `SemanticConnectivity.probe(source)` 发的是 **JEV 判定请求**；AI Insight 实际走 `AgentModelClient` 的 `chat/completions` + `response_format=json_schema` + SSE 流式。测试成功与 AI Insight 成功无因果关系，反而误导用户 |
| C4 | P1 | 找不到配置入口，提示语含糊 | AI Insight 开关旁只有两个开关（`SettingsCardSections.kt:1348-1372`），来源配置藏在旁边「AI 语义判定」面板里；`InsightCard.kt:104` 只说「模块 AI 配置」；`strings.xml:5` 说「下方首个文本模型」 |
| C5 | P2 | 没有用量 / 费用可见性，无法察觉昂贵模型被自动调用 | 判定侧有 `SemanticUsage`（`SemanticBackend.kt:112-127`）只进观测日志；AI Insight 侧完全无用量展示 |

### D. 流式与失败处理（恶性 bug 集中区）

| 编号 | 严重度 | 现象 | 根因与位置 |
|---|---|---|---|
| D1 | P0 | 流式已输出一大半，突然「请重试」 | `AgentChatStream.kt:59` 读到 `finish_reason=length` 立即抛 `OUTPUT_LIMIT`，丢弃此前全部 delta；`AgentModelClient.kt:236` 非流式同样处理。输出预算写死 8192（`InsightRepository.kt:80`），结构化 JSON + 完整字幕很容易撑满。该行为被写进测试断言 `AgentChatStreamTest.kt:55`，是既有设计不是偶发 |
| D2 | P0 | 失败时已显示的正文被整段替换成错误文字 | `InsightResponseText.kt:60-64` `fail()` 直接 `view.text = text`；调用点 `InsightCard.kt:373` |
| D3 | P0 | 所有失败都显示同一句「暂时无法生成，请重试。」 | `InsightCard.kt:332-337` 只认 3 个 message，其余全部 fallback；`AgentModelException.Reason` 每个都有中文 `description`（`AgentModelTypes.kt:144-157`）却一个都没用；`InsightValidationException.rule/hint`（`InsightProtocol.kt:23`）也没用 |
| D4 | P0 | 90 秒硬截止，长流式输出中途或完整到达后被丢弃 | `InsightRepository.kt:122` 一次 deadline 两次生成共享；`AgentChatStream.kt:32` 每个事件 `checkAlive` → `AgentHttpTransport.kt:138-141` 抛 TIMEOUT；`AgentModelClient.kt:114` **响应完整收到之后**仍检查 `nanoTime >= deadline` 并丢弃。`AgentHttpsTransport.MAX_TIMEOUT_MS=120s`（`:214`）与业务 90s 也不一致 |
| D5 | P1 | 格式修正时清空用户已看到的正文，重放只剩残额时间 | `InsightRepository.kt:142-149`：`onProgress("")` 清空预览（`:145`）、追加修正提示后第二次生成；`remaining` 来自同一个 deadline（`:126`） |
| D6 | P1 | 校验过严，小模型 / 非 strict-schema 服务极易失败 | `InsightProtocol.kt:221` 字段集合必须完全相等；`:44,207` 禁止任何 `mm:ss`；`:204-206` 时间引用必须落在字幕覆盖内；`:146-151` 章节首项必须为 0 且间隔 ≥10 秒。`json_schema` 带 `strict:true`（`:75`），OpenRouter 中转 Claude 系模型支持不佳 |
| D7 | P1 | SSE 流尾契约过死，中转差异即全盘作废 | `AgentChatStream.kt:60` 只接受 `finish_reason == "stop"`；`:34` `[DONE]` 前必须先有 finish；`:80` EOF 前必须收到 stop；`:37` 流内出现 `error` 字段即 invalid；`:50` finish 后仍有非空 content 即 invalid |
| D8 | P1 | OpenRouter 上的非推理模型被注入不支持的参数 | `AgentModelClient.kt:151-155` 对 `openrouter.ai` 无条件注入 `AiChatCompat.reasoningParams(..., OFF)` → `{"reasoning":{"effort":"none"}}`（`AiChatCompat.kt:83-86`）；回退只认特定结构化错误码（`AgentHttpTransport.kt:159-161`） |
| D9 | P1 | 只有一类校验异常有修正机会，其余直接失败 | `InsightRepository.kt:142-143` 只捕获 `InsightValidationException`；`InsightProtocol.decode` 里 `error("invalid-insight-response")`、各处 `require(...)`、`JSONException` 全部直接抛出 |
| D10 | P2 | 90 秒、8192 token 均不可配置 | `InsightRepository.kt:80,122` 硬编码 |

### E. 其它可用性

| 编号 | 严重度 | 现象 | 位置 |
|---|---|---|---|
| E1 | P1 | 失败原因虽写进日志，但 UI 完全不暴露 | `InsightRepository.kt:161` 记 `failure mode/stage/reason` → `AiInsightFeatureInstaller.kt:63-67` 变成 `insight.failure.*` 日志标签。用户只能去 `/data/adb/lspd/log/` 过滤才知道是 `OUTPUT_LIMIT` 还是 `TIMEOUT` |
| E2 | P2 | 章节、缓存、原生注入、上下文读取等设计本身合理，但被上述失败处理掩盖 | `InsightRepository.kt:109-115`、`InsightCache.kt:19-54`、`InsightNativeChapters.kt` |

---

## 4. 修复方案

### 4.1 设计原则

1. **配置按功能边界拆开，不按实现复用凑合**：AI Insight 与智能过滤是两种用途、两种请求栈、两种计费模型，必须有各自独立的来源与开关。
2. **用户已看到的内容是成果，不是可丢弃的临时预览**：任何失败都不得整段覆盖已输出的正文。
3. **失败要可诊断**：错误文案必须区分 超时 / 输出截断 / 格式不符 / 网络 / 接口拒绝 / 校验规则，并给出下一步动作。
4. **不给用户制造意外账单**：自动、重复、后台的请求必须显式告知并可关闭。
5. **不破坏既有配置**：迁移只做一次性、可见、可撤销的导入，不静默自动复制。

### 4.2 阶段 1：P0 止血（先做，按逻辑单元拆成独立提交）

**S1-1 独立 AI Insight 服务配置**

- 新增专用偏好键（建议 `insight_protocol` / `insight_base_url` / `insight_api_key` / `insight_model` / `insight_output_budget` / `insight_timeout_ms`），Key 走与现有 JEV Key 相同的签名保护运行时通道（`RemoteHookConfigContract.kt`），不进备份与诊断。
- 新增独立设置分组「AI Insight」（放在实验性功能下的独立卡片，不再塞进「兼容」）：启用开关、自动生成原生章节、协议、Base URL、API Key、模型、输出预算、超时、**测试连接**。
- 面板内显式展示「当前生效来源：协议 / 规范化后的完整 URL / 模型」，并提供「从现有来源 N 导入」按钮；专用配置为空时不自动继承、不回退，卡片给出明确引导而非含糊提示。
- `HookEntry.kt:2778-2784` 改为读专用配置构造 `AgentModelSource`，不再传 `semanticSettings?.sources`；`AiInsightFeatureInstaller` 的 `InsightSources.resolve` 改为接收单个来源或删除该对象。
- `SettingsCatalog` 版本递增，新增条目并保留旧 `compat.semantic_source.*` 的可读性；旧配置不删除、不迁移覆盖。
- 对应解决 A1/A2/A4/A5/C4，并让 A3 自然消失（AI Insight 的来源不再出现在任何过滤面的候选池里）。

**S1-2 统一端点归一化**

- 新增单一 `EndpointResolver`：输入接受 `example.com`、`example.com/v1`、`https://example.com/v1`、`https://…/chat/completions?api-version=…`；纯域名默认补 `https://`；区分「基址」与「完整端点」，完整端点不再二次拼接（同时解决 A7/B6）。
- 协议与目标路径由协议适配器决定（OpenAI → `/chat/completions`，Anthropic → `/v1/messages`），不再各层各补一次。
- 保存前回显「实际请求地址」，校验失败时给出具体原因（缺协议 / 缺主机 / 路径冲突），不再只弹「接口地址必须是 http(s) 地址」。
- 删除或改写 `strings.xml:1363`、`:1229`、`:1200`、`:1367` 中所有矛盾表述，统一为「可填域名或完整 URL，默认 https」。
- 对应解决 B1/B3/B4/B5/C2。

**S1-3 Anthropic Messages 协议适配**

- 新增 `AgentSourceProtocol.ANTHROPIC`（或独立 `AnthropicChatTransport`），覆盖：
  - 请求：`POST {base}/v1/messages`，头 `x-api-key`、`anthropic-version: 2023-06-01`、`content-type`，体为 `model` / `system` / `messages` / `max_tokens` / `stream`；
  - 结构化输出：Anthropic 无 `response_format`，改为 tool-use 或 system 注入 schema，沿用现有 `InsightProtocol.decode` 本地校验；
  - 响应：`content[].text` 收敛成现有 `AgentModelTurn`；
  - SSE：解析 `message_start` / `content_block_delta` / `message_delta` / `message_stop`，映射到现有 `onDelta` 与 `finish_reason`；
  - `stop_reason=max_tokens` 映射为 `OUTPUT_LIMIT`。
- 保持现有 `AgentModelClient` / `AgentChatStream` 边界，新增协议作为适配层，不改动非流式 Agent 入口。
- 对应解决 B2/D7（部分）。

**S1-4 长凭据输入控件**

- 为 API Key 单独实现输入行（不再走通用 `form.field`）：多行自动增高但有上限、可切换明文/密文、显示字符数、粘贴后自动 trim、超长明确报错而非静默截断、`ellipsize` 与横向滚动保护、宽度严格约束在表单内。
- 地址与模型改用普通文本键盘，去掉 `TYPE_TEXT_VARIATION_URI`。
- 对应解决 C1。

**S1-5 失败处理止血**

按 D 组逐条：

1. `InsightResponseText.fail` 改为「保留当前正文 + 在末尾追加明确状态行」，不再覆盖；正文保留为只读文本，时间链接不激活。
2. `InsightCard.errorText` 按 `AgentModelException.Reason`（用其 `description`）、`InsightValidationException.rule`、固定 message 分类给具体文案与动作建议（例如「输出被截断，可提高输出预算或换更长上下文模型后重试」「请求超时，可检查网络或延长超时」「模型返回的格式不符合要求，已保留已生成内容」）。
3. `InsightRepository.generate`：
   - 去掉「完整响应已到达后仍按 deadline 丢弃」的检查（`AgentModelClient.kt:114`）；
   - 改为「有增量就续期」的空闲超时，只有长时间无任何 delta 才判超时；
   - 90 秒改为可配置，默认放宽；两次生成各自计时，不再共享残额；
   - 格式修正不再清空已显示正文，重放期间保留上一次预览。
4. `AgentChatStream`：`finish_reason=length` 时不再丢弃全部，返回「部分内容 + 截断标记」交给上层保留；兼容 `end_turn` 等非 `stop` 的 finish_reason、`[DONE]` 与 finish 的两种顺序、流内 `error` 的明确分类；EOF 无 finish 时按「部分结果」而非 `INVALID_RESPONSE` 处理。
5. `AgentModelClient.knownReasoningParams` 改为按模型能力白名单，不对 openrouter.ai 下所有模型无条件注入 `reasoning.effort=none`；未知模型默认不带该参数。
6. `InsightProtocol.decode` 的校验失败统一包装成 `InsightValidationException`（含 `rule`/`hint`），让所有格式问题都进入同一次修正流程；放宽「字段集合必须完全相等」为「必需字段齐全 + 忽略未知字段」，`bareTime` 只对最终渲染文本生效而非原始正文。
7. `InsightCard` 增加「停止生成」入口（关闭面板已有实际取消能力，补一个明确按钮）。

### 4.3 阶段 2：P1 体验（配置拆开后做）

- **连通性测试改为测真实路径**：复用 AI Insight 的实际请求构造 + 一条短 prompt，覆盖协议、鉴权、端点、模型名、流式可用性，结果分「鉴权失败 / 地址 404 / 模型不存在 / 不支持流式 / 不支持结构化输出 / 通过」。对应 C3。
- **用量可见**：面板显示本次输入/输出 token 与（中转提供时）费用，设置页提供「本次会话累计」；对应 C5。
- **热更新**：AI Insight 的来源与开关改为在宿主进程内按需读取，不再要求重启宿主（至少做到改配置后立即对新请求生效）；对应 A6。JEV 侧维持现状不动。
- **命名统一**：全项目统一为「AI Insight 服务配置」与「智能过滤判定来源」两套术语，更新中英繁三语文案、`ReleaseHighlightsCatalog` 与 `docs/ai-insight.md`。
- **自动请求告知**：在智能过滤开关附近明确列出「会自动向所选来源发起判定请求，多来源时可能重复请求」，避免用户不知情地产生费用。

### 4.4 阶段 3：验证与全版本

- 遵守项目硬要求：**涉及宿主 Hook / UI 的改动须覆盖 8.84–9.17 全部 38 个版本**，不写死单版本混淆名。`InsightCard` 是注入宿主简介列表的 View，改动需沿用 `InsightComponentContract` 的结构化解析与 `KavaMemberLookup` 边界（参见 `docs/ai-insight.md` §「多版本静态适配」）。
- 按 `AGENTS.md`：`assembleDebug testDebugUnitTest lintFast --offline --no-daemon`（标准 JDK 17、ASCII SDK 路径、复用 `.gradle-user-home`），结束后 `--stop` 并确认守护进程退出；只改文档不跑 Lint。
- 单测重点：端点归一化（纯域名 / 基址 / 完整端点 / query / http/https）、Anthropic 请求与 SSE 映射、失败分类文案、截断保留正文、超时续期、配置隔离（AI Insight 来源不出现在过滤面候选池）。
- 实机按 `192.168.1.78:5555` 覆盖安装 + 冷启动，用真实 OpenAI 兼容与真实 Anthropic 端点各验一轮：配置保存 → 测试连接 → 总结 / 提问 / 评论话题 / 章节 → 长输出 → 中途失败 → 错误文案 → 重启后缓存。
- 改动按逻辑单元拆成独立提交，一次提交只装一个逻辑单元。

### 4.5 明确不做

- 不把 JEV 的多来源调度、对冲、复核整套搬给 AI Insight；AI Insight 只需要一个明确来源。
- 不为「兼容旧配置」加自动回退到 JEV 来源表的逻辑；迁移只做一次性可见导入。
- 不引入新的网络 / JSON / UI 依赖，不升级 Gradle / AGP / Kotlin / BetterAndroid / KavaRef / Lumen。
- 不扩大 Lint baseline，不新增 suppress 掩盖问题。

---

## 5. 问题 → 代码位置索引

| 组 | 编号 | 主要位置 |
|---|---|---|
| A | A1–A7 | `HookEntry.kt:2536-2556,2778-2784`、`AiInsightFeatureInstaller.kt:26-30`、`SettingsCardSections.kt:1348-1410`、`SettingsCatalog.kt:198-200`、`FeaturePreferences.kt:337-404`、`SemanticSources.kt:38-54`、`SemanticJudge.kt:793-799,826-832,902-972` |
| B | B1–B6 | `SemanticBackend.kt:74,87-107,151-169,250-259,357-377`、`AgentModelTypes.kt:9,44-61`、`AgentModelClient.kt:74-93`、`AgentHttpTransport.kt:69`、`AgentChatStream.kt:39-62`、`strings.xml:1200,1228,1229,1303,1363,1367` |
| C | C1–C5 | `SemanticJevDialogs.kt:122-156,473-560,514-520,608-622`、`AgentDialogs.kt:148`、`InsightCard.kt:104` |
| D | D1–D10 | `AgentChatStream.kt:34,37,50,59,60,80`、`AgentModelClient.kt:114,151-155,236`、`InsightRepository.kt:80,91-164`、`InsightProtocol.kt:23,44,75,125-212,215-246`、`InsightResponseText.kt:60-64`、`InsightCard.kt:262,332-337,360-392`、`AgentModelTypes.kt:144-157` |
| E | E1–E2 | `InsightRepository.kt:161`、`AiInsightFeatureInstaller.kt:63-67` |

---

## 6. 附：本次 review 未做的事

- 未连接手机、未做实机验证；用户报告的「API Key 文字越出输入框」的具体渲染原因需要一张截图确认，但统一表单缺少长文本约束与反馈已在代码层面确认。
- 未运行构建、单测或 Lint（本轮只改 Markdown 文档，按 `AGENTS.md` 不触发）。
- 未改动任何代码；所有修复项仅为方案，待人工 review 后按阶段实施。

---

## 7. 实施进度

阶段 1（P0 止血）已按逻辑单元逐项实施并提交：

- **S1-5 失败处理止血** + **S1-2 端点归一化** + **S1-4 长凭据控件**：失败保留已流式正文、错误按 Reason 分类、两次生成独立计时、repair 不清空预览、统一校验异常、放宽字段集合、完整响应后不再按 deadline 丢弃、SSE 流尾兼容、聚合中转不再注入思考参数；纯域名按 https 补全、三语矛盾文案消除；API Key 可增高 + 显隐切换 + 字符数 + 超长明确报错。
- **S1-3 Anthropic 协议适配**：`AgentSourceProtocol.ANTHROPIC`、`/v1/messages`、`x-api-key` + `anthropic-version`、非流式 `content[].text` 与 SSE `content_block_delta`/`message_delta`/`message_stop` 映射。
- **S1-1 独立服务配置**（本次）：新增专用偏好键 `ai_insight_protocol` / `ai_insight_base_url` / `ai_insight_model`（进 `SettingsCatalog` v74，可备份）与签名保护的运行时凭据 `ai_insight_api_key`（不进备份/诊断/目录）。`InsightConfig.resolve` 把四项解析成**单一** `AgentModelSource`；`HookEntry` 不再传 `semanticSettings.sources`，`AiInsightFeatureInstaller` 改收单一来源，删除 `InsightSources.resolve`。设置页在现有兼容步骤内新增独立入口行（不新建 deferStep，避免破坏设置页分步构建的结构契约）与专用面板：协议选择、Base URL、长凭据 Key、模型、当前生效来源回显、从判定来源一次性可见导入、清除。`ai_insight_help` 与卡片未配置引导同步改为指向独立面板。

阶段 2（P1 体验）与阶段 3（验证与全版本）仍待后续逻辑单元推进，其中较重要的：

- C3：面板「测试连接」需走 AI Insight 真实请求路径（`chat/completions` / `messages` + SSE + schema），不能复用 JEV 判定探针（否则依旧"测了没用"）。当前面板不含测试连接，避免放置误导性的半成品探针。
- C5 用量可见、A6 配置热更新（当前仍需重启宿主）、命名统一（Agent 面板的「配置 API 来源」入口）、自动请求告知、D10 输出预算/超时可配置。
- 阶段 3：全版本静态核对、真实 OpenAI 兼容与真实 Anthropic 端点各一轮实机验收。

全版本支持仍是硬要求：本轮 S1-1 未改动宿主 Hook 锚点或 UIComponent 契约，仅调整模块设置与配置读取，38 版宿主结构不受影响；`InsightCard` 的宿主注入仍沿用 `InsightComponentContract` / `KavaMemberLookup` 边界。
