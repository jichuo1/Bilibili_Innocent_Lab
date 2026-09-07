import { sha256Hex } from "../crypto";
import type { RateLimiter } from "../types";
import { parseFilter, readAnalytics } from "./queries";
import { renderPage } from "./page";

export interface AnalyticsEnv {
  PROD_DB: D1Database;
  STAGING_DB: D1Database;
  ANALYTICS_LIMITER: RateLimiter;
  ADMIN_EMAIL_SHA256?: string;
}

const headers = {
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "x-frame-options": "DENY",
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
};
const response = (message: string, status: number) => new Response(message,{status,headers});

export async function handleAnalytics(request: Request, env: AnalyticsEnv,
  ctx: Pick<ExecutionContext,"access">): Promise<Response> {
  // Trust only the Workers runtime Access context; headers supplied by callers are not credentials.
  if (!ctx.access || !env.ADMIN_EMAIL_SHA256) return response("看板尚未授权，请通过 Cloudflare Access 登录。",403);
  try {
    const identity = await ctx.access.getIdentity();
    if (!identity?.email || await sha256Hex(identity.email.trim().toLowerCase()) !== env.ADMIN_EMAIL_SHA256) {
      return response("此账号无权查看分析。",403);
    }
  } catch { return response("身份验证暂不可用。",503); }
  if (request.method !== "GET") return response("只支持 GET 查询。",405);
  const url = new URL(request.url);
  if (url.pathname !== "/") return response("页面不存在。",404);
  let filter;
  try { filter = parseFilter(url); } catch { return response("筛选条件无效。",400); }
  try {
    if (!(await env.ANALYTICS_LIMITER.limit({key:"private-dashboard"})).success) {
      return response("刷新过于频繁，请一分钟后再试。",429);
    }
    const data = await readAnalytics(filter.environment === "production" ? env.PROD_DB : env.STAGING_DB,filter);
    return new Response(renderPage(data,filter),{headers:{...headers,"content-type":"text/html; charset=utf-8"}});
  } catch { return response("统计查询暂时失败，请稍后刷新或检查 D1 配额。",503); }
}

export default { fetch: handleAnalytics } satisfies ExportedHandler<AnalyticsEnv>;
