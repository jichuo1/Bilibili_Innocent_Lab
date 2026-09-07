# 遥测服务运维手册

## 当前资源

| 环境 | Worker | 域名 | D1 |
|---|---|---|---|
| Staging | `innocent-lab-telemetry-staging` | `telemetry-staging.bilibili.date` | `innocent-lab-telemetry-staging` |
| Production | `innocent-lab-telemetry-prod` | `telemetry.bilibili.date` | `innocent-lab-telemetry-prod` |

两个环境使用独立的 D1、Rate Limiting namespace 和 `ID_HMAC_KEY` Secret。
数据库使用亚太地区位置提示；该提示不是数据驻留保证。

当前交付状态：维护者已明确授权 Production 正式接收上述遥测数据并由境外
Cloudflare Workers/D1 处理。Staging 与 Production 的 `INGEST_RETIRED` 均为 `0`。
模块客户端仍须满足条款版本 2 已接受、独立遥测开关开启及发送频率限制。

Production 部署版本：`7b8777e0-38f1-4ab8-b327-77b728656332`。
开启后的合成验收：健康检查正常，上报与重复上报均为 `204`，数据库只出现一行，
安装键/删除令牌均为 64 字符哈希，payload 无原始标识或令牌；purge 返回 `204`，
随后查询原始表与聚合表均为 0 行。测试不代表 Android 真机或三网验收。

## 日常验证

分析看板位于 https://stats.bilibili.date/ ，使用方法和统计口径见 `ANALYTICS.md`。
看板为独立 Worker，只允许获准的维护者登录，不改变公开上报接口的访问方式。

```powershell
Set-Location 'C:\Users\Administrator\Documents\Bilibili_Innocent_Lab\server'
npm run check

curl.exe -i 'https://telemetry-staging.bilibili.date/healthz'
curl.exe -i 'https://telemetry.bilibili.date/healthz'
```

两个健康检查都应返回 `200` 和 `{ "status": "ok" }`。

## 部署

先部署并验证 Staging：

```powershell
npm run check
npm run deploy:staging
```

确认 Staging 后再部署 Production：

```powershell
npm run deploy:production
```

数据库迁移必须先本地验证，再分别应用：

```powershell
npx wrangler d1 migrations apply innocent-lab-telemetry-staging --local --env staging
npx wrangler d1 migrations apply innocent-lab-telemetry-staging --remote --env staging
npx wrangler d1 migrations apply innocent-lab-telemetry-prod --remote --env production
```

迁移属于生产数据变更；执行前先确认文件内容和目标环境。

## 查看数据库行数

```powershell
npx wrangler d1 execute innocent-lab-telemetry-staging --remote --env staging --command "SELECT COUNT(*) AS reports FROM reports"
npx wrangler d1 execute innocent-lab-telemetry-prod --remote --env production --command "SELECT COUNT(*) AS reports FROM reports"
```

不要在共享日志、Issue 或聊天中输出 `payload_json` 原文。

## 紧急停止接收

1. 把 `wrangler.jsonc` 对应环境中的 `INGEST_RETIRED` 从 `0` 改为 `1`。
2. 部署该环境。
3. 验证 `POST /v1/report` 返回 `410`。
4. `POST /v1/purge` 必须继续可用，不能随接收端一起关闭。

恢复接收时把值改回 `0`，运行测试后重新部署。

## Secret

真实 `ID_HMAC_KEY` 只存在于 Cloudflare Secret 存储和本机 Wrangler 授权中，
不在仓库、`.dev.vars.example` 或测试 fixture 中。

如需轮换：

```powershell
npx wrangler secret put ID_HMAC_KEY --env staging
npx wrangler secret put ID_HMAC_KEY --env production
```

两个环境必须使用不同随机值。轮换会改变后续报告的安装键；token-only purge
仍能删除轮换前的原始报告。

## Cloudflare 安全设置

`bilibili.date` 的 Free Bot Fight Mode 当前关闭。免费版无法按子域或路径跳过，
开启后会对 Android/API 请求发 JavaScript Challenge，表现为所有接口返回
`403` 且响应头带 `Cf-Mitigated: challenge`。

Cloudflare 基础 DDoS 防护仍启用。接口另外具有：

- 32 KiB 实际字节硬顶；
- 严格 schema 和禁采字段；
- 报告与删除入口在解析前共用每节点 300 次/分钟总量限流；
- 可信来源 IP 按天生成 HMAC 后限流（60 次/分钟），不写入 D1 或日志；
- 每安装键与上传模式独立 4 次/分钟；手动上传另由 D1 事务强制滚动 24 小时 3 次；
- 每环境每日最多处理 2000 次有效报告请求，超过后返回 429，删除不受此总量影响；
- 每安装键每日最多一份快照，手动刷新不增加当天样本数；
- 额度记录不随原始报告删除，24 小时失效后由每日任务清理，正常最多保留 48 小时；
- 原始数据 30 天清理；
- 长期聚合最少 10 个安装键。

## Wrangler 登录

当前 Wrangler OAuth 使用 Windows keyring 保存，授权范围限制为账户/用户只读、
Worker 脚本与路由写入、D1 写入和 Zone 只读。不要改成默认的全量 29 项权限。

不再需要本机部署时，可在 Cloudflare 的 My Profile → Access Management →
Connected Applications 中撤销 Wrangler，或运行：

```powershell
npx wrangler logout
```

撤销后线上 Worker 和 D1 不受影响，只是本机不能继续部署。
## 手动额度更新（2026-09-07）

发布顺序：先应用 `0002_manual_quota.sql` 与 `0003_purge_lookup_index.sql`，
再部署 Worker。迁移只新增额度表和索引。
旧客户端未发送 `upload_kind` 时继续按 automatic 处理。Android 手动点击发送 manual，
使用独立的本地持久化滚动次数和独立 Retry-After 状态；不会读写自动上传冷却时间。

验收：`node --import tsx scripts/check-manual-quota.ts staging`（或 production）。
脚本仅产生少量合成请求，验证 204/204/204/429 与后续 automatic 204，最后删除自己
生成的原始报告；去标识化防滥用记录留待定时清理，不能通过删除重置额度。

安全边界：Cloudflare Rate Limiting API 按节点且最终一致，不能代替全局精确额度。
精确手动计数与每日写入预算由 D1 batch 事务承担。随机安装 ID 不是设备认证；
攻击者若同时更换标识和来源仍可能耗尽共享预算，不能宣称完全杜绝 DDoS 或费用。
参考：https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/
