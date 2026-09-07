import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest } from "../src/index";
import { fakeEnv, validReport } from "./helpers";

function jsonRequest(path: string, body: unknown, headers: HeadersInit = {}): Request {
  return new Request(`https://telemetry-staging.bilibili.date${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

test("health endpoint is side-effect free", async () => {
  const env = fakeEnv();
  const response = await handleRequest(
    new Request("https://telemetry-staging.bilibili.date/healthz"),
    env,
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
  assert.equal(env.DB.runs.length, 0);
});

test("valid report stores hashes and canonical payload only", async () => {
  const env = fakeEnv();
  const input = validReport();
  const response = await handleRequest(jsonRequest("/v1/report", input), env);
  assert.equal(response.status, 204);
  assert.equal(env.DB.batches.length, 1);

  const params = env.DB.batches[0]?.at(-1)?.params ?? [];
  assert.notEqual(params[2], input.install_id);
  assert.notEqual(params[3], input.purge_token);
  const storedJson = String(params[9]);
  assert.equal(storedJson.includes(String(input.install_id)), false);
  assert.equal(storedJson.includes(String(input.purge_token)), false);
  assert.equal(storedJson.includes(String(input.client_report_id)), false);
});

test("retired ingest rejects before rate limiting or database access", async () => {
  const env = fakeEnv();
  env.INGEST_RETIRED = "1";
  const response = await handleRequest(jsonRequest("/v1/report", validReport()), env);
  assert.equal(response.status, 410);
  assert.equal(env.EDGE_LIMITER.calls.length, 0);
  assert.equal(env.DB.runs.length, 0);
});

test("invalid reports never reach the database", async () => {
  const env = fakeEnv();
  const response = await handleRequest(
    jsonRequest("/v1/report", validReport({ unexpected: true })),
    env,
  );
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "UNKNOWN_REPORT_FIELD" });
  assert.equal(env.DB.runs.length, 0);
});

test("rejects unsupported content types", async () => {
  const env = fakeEnv();
  const request = new Request("https://telemetry-staging.bilibili.date/v1/report", {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: JSON.stringify(validReport()),
  });
  const response = await handleRequest(request, env);
  assert.equal(response.status, 415);
  assert.equal(env.DB.runs.length, 0);
});

test("rejects oversized declared bodies", async () => {
  const env = fakeEnv();
  const response = await handleRequest(
    jsonRequest("/v1/report", validReport(), { "content-length": String(32 * 1024 + 1) }),
    env,
  );
  assert.equal(response.status, 413);
  assert.equal(env.DB.runs.length, 0);
});

test("rejects oversized streamed bodies even without a content-length header", async () => {
  const env = fakeEnv();
  const request = new Request("https://telemetry-staging.bilibili.date/v1/report", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: `{"padding":"${"x".repeat(33 * 1024)}"}`,
  });
  request.headers.delete("content-length");
  const response = await handleRequest(request, env);
  assert.equal(response.status, 413);
  assert.equal(env.DB.runs.length, 0);
});

test("rejects compressed request bodies", async () => {
  const env = fakeEnv();
  const response = await handleRequest(
    jsonRequest("/v1/report", validReport(), { "content-encoding": "gzip" }),
    env,
  );
  assert.equal(response.status, 415);
  assert.equal(env.DB.runs.length, 0);
});

test("known paths reject unsupported methods without CORS headers", async () => {
  const env = fakeEnv();
  const response = await handleRequest(
    new Request("https://telemetry-staging.bilibili.date/v1/report"),
    env,
  );
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("allow"), "POST");
  assert.equal(response.headers.has("access-control-allow-origin"), false);
});

test("rate limiting happens before database writes", async () => {
  const env = fakeEnv();
  env.EDGE_LIMITER.success = false;
  const response = await handleRequest(jsonRequest("/v1/report", validReport()), env);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.equal(env.DB.runs.length, 0);
});

test("purge uses only the token hash and remains available when ingest is retired", async () => {
  const env = fakeEnv();
  env.INGEST_RETIRED = "1";

  const input = validReport();
  const response = await handleRequest(
    jsonRequest("/v1/purge", {
      purge_token: input.purge_token,
    }),
    env,
  );
  assert.equal(response.status, 204);
  const params = env.DB.runs[0]?.params ?? [];
  assert.equal(env.DB.runs[0]?.params.length, 1);
  assert.notEqual(env.DB.runs[0]?.params[0], input.purge_token);
});

test("source limiter rejects report and purge before reading bodies or querying D1", async () => {
  for (const path of ["/v1/report", "/v1/purge"]) {
    const env = fakeEnv();
    env.SOURCE_LIMITER.success = false;
    const response = await handleRequest(jsonRequest(path, { invalid: true },
      { "cf-connecting-ip": "192.0.2.1" }), env);
    assert.equal(response.status, 429);
    assert.equal(env.DB.batches.length, 0);
    assert.equal(env.DB.runs.length, 0);
    assert.match(env.SOURCE_LIMITER.calls[0]!, /^[a-f0-9]{64}$/);
    assert.equal(env.SOURCE_LIMITER.calls[0]!.includes("192.0.2.1"), false);
  }
});

test("a stalled upload body is cancelled within the fixed deadline before database access", async () => {
  const env = fakeEnv();
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode("{")); },
    cancel() { cancelled = true; },
  });
  const request = new Request("https://telemetry-staging.bilibili.date/v1/report", {
    method: "POST", headers: {"content-type": "application/json"}, body, duplex: "half",
  } as RequestInit);
  const response = await handleRequest(request, env);
  assert.equal(response.status, 408);
  assert.deepEqual(await response.json(), {error: "BODY_TIMEOUT"});
  assert.equal(cancelled, true);
  assert.equal(env.DB.batches.length, 0);
  assert.equal(env.DB.runs.length, 0);
});
