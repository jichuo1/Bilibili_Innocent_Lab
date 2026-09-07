import { hmacSha256Hex, sha256Hex } from "./crypto";
import { insertReport, purgeReports, runDailyMaintenance } from "./database";
import {
  emptyResponse,
  errorResponse,
  healthResponse,
  methodNotAllowed,
  readPurgeJson,
  readReportJson,
} from "./http";
import type { Env } from "./types";
import { InputError, toStoredPayload, validatePurge, validateReport } from "./validation";

async function limitIngress(request: Request, env: Env): Promise<Response | null> {
  const edge = await env.EDGE_LIMITER.limit({ key: "ingress-v2" });
  if (!edge.success) return errorResponse(429, "RATE_LIMITED", { "retry-after": "60" });
  // CF-Connecting-IP is supplied by Cloudflare, not a user-selected forwarding header.
  // Retain only a daily keyed digest in the short-lived limiter, never in D1 or logs.
  const sourceKey = await hmacSha256Hex(env.ID_HMAC_KEY,
    "source:" + new Date().toISOString().slice(0, 10) + ":" +
    (request.headers.get("cf-connecting-ip") ?? "unknown"));
  const source = await env.SOURCE_LIMITER.limit({ key: sourceKey });
  return source.success ? null : errorResponse(429, "RATE_LIMITED", { "retry-after": "60" });
}

async function handleReport(request: Request, env: Env): Promise<Response> {
  if (env.INGEST_RETIRED === "1") return emptyResponse(410);

  const ingressError = await limitIngress(request, env);
  if (ingressError) return ingressError;

  const report = validateReport(await readReportJson(request));
  const installKey = await hmacSha256Hex(env.ID_HMAC_KEY, report.install_id);
  const installLimit = await env.INSTALL_LIMITER.limit({ key: report.upload_kind + ":" + installKey });
  if (!installLimit.success) {
    return errorResponse(429, "RATE_LIMITED", { "retry-after": "60" });
  }

  const purgeKeyHash = await sha256Hex(report.purge_token);
  const accepted = await insertReport(env, report, toStoredPayload(report), installKey, purgeKeyHash);
  if (!accepted) return errorResponse(429, "UPLOAD_QUOTA_REACHED", { "retry-after": "60" });
  return emptyResponse(204);
}

async function handlePurge(request: Request, env: Env): Promise<Response> {
  const ingressError = await limitIngress(request, env);
  if (ingressError) return ingressError;
  const purge = validatePurge(await readPurgeJson(request));
  const purgeKeyHash = await sha256Hex(purge.purge_token);
  const installLimit = await env.INSTALL_LIMITER.limit({ key: purgeKeyHash });
  if (!installLimit.success) {
    return errorResponse(429, "RATE_LIMITED", { "retry-after": "60" });
  }

  await purgeReports(env, purgeKeyHash);
  return emptyResponse(204);
}

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const path = new URL(request.url).pathname;
  try {
    if (path === "/healthz") {
      return request.method === "GET" ? healthResponse() : methodNotAllowed("GET");
    }
    if (path === "/v1/report") {
      return request.method === "POST"
        ? await handleReport(request, env)
        : methodNotAllowed("POST");
    }
    if (path === "/v1/purge") {
      return request.method === "POST"
        ? await handlePurge(request, env)
        : methodNotAllowed("POST");
    }
    return errorResponse(404, "NOT_FOUND");
  } catch (error) {
    if (error instanceof InputError) return errorResponse(error.status, error.code);
    console.error(JSON.stringify({ event: "telemetry_service_error" }));
    return errorResponse(503, "SERVICE_UNAVAILABLE");
  }
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },

  async scheduled(controller: ScheduledController, env: Env, context: ExecutionContext) {
    context.waitUntil(runDailyMaintenance(env, controller.scheduledTime));
  },
} satisfies ExportedHandler<Env>;
