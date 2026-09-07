import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { validReport } from "../test/helpers";
import { CAPABILITIES } from "../src/capabilities";

const environment = process.argv[2];
assert.ok(environment === "staging" || environment === "production", "Specify staging or production");
const base = environment === "staging"
  ? "https://telemetry-staging.bilibili.date"
  : "https://telemetry.bilibili.date";
const installId = randomUUID();
const purgeToken = randomBytes(32).toString("base64url");
const includeCapabilities = process.argv[3] === "capability";
const includeDevice = process.argv[3] === "device" || includeCapabilities;
const extension = includeDevice ? {
  ...(includeCapabilities?{schema_version:2,feature_catalog_version:1,
    snapshot_complete:true,snapshot_state:"completed",evidence_scope:"current_host_process",feature_groups:[],
    features:CAPABILITIES.map(capability=>({id:capability.id,state:"installed",hook_count:1,
      ...(capability.runtime>0?{observed:1}:{}),...(capability.runtime===2?{applied:1}:{})}))}:{}),
  disclosure_version: includeCapabilities ? 4 : 3,
  device: {manufacturer:"synthetic",model:"Test Model",rom:"unknown"},
  runtime: {...validReport().runtime as object, framework_version:"test-1",framework_version_code:1},
} : {};
async function post(path: string, body: unknown) {
  return fetch(base + path, {
    method: "POST", redirect: "error", signal: AbortSignal.timeout(15000),
    headers: { "content-type": "application/json" }, body: JSON.stringify(body),
  });
}
async function upload(kind: "manual" | "automatic") {
  return post("/v1/report", validReport({
    upload_kind: kind, install_id: installId, purge_token: purgeToken,
    client_report_id: randomUUID(), features: [],
    ...extension,
  }));
}
try {
  const health = await fetch(base + "/healthz", {signal: AbortSignal.timeout(15000)});
  assert.equal(health.status, 200);
  if (includeDevice) {
    const rejected = await post("/v1/report", validReport({...extension,disclosure_version:includeCapabilities?3:2}));
    assert.equal(rejected.status,400);
    console.log("old disclosure with expanded fields: 400");
  }
  assert.equal((await upload("automatic")).status, 204);
  console.log(environment + ": health 200, automatic 204");
  for (let i = 1; i <= 3; i++) {
    assert.equal((await upload("manual")).status, 204);
    console.log("manual " + i + ": 204");
  }
  const fourth = await upload("manual");
  assert.equal(fourth.status, 429);
  assert.deepEqual(await fourth.json(), {error: "UPLOAD_QUOTA_REACHED"});
  console.log("manual 4: 429 UPLOAD_QUOTA_REACHED");
  assert.equal((await upload("automatic")).status, 204);
  console.log("automatic after manual quota exhausted: 204");
  if (includeCapabilities) execFileSync(process.execPath, ["--import","tsx",
    fileURLToPath(new URL("./check-analytics.ts",import.meta.url)), environment],
    {stdio:"inherit",timeout:90000,windowsHide:true});
} finally {
  // Delete only this run's synthetic raw reports, using its random deletion token.
  const purge = await post("/v1/purge", {purge_token: purgeToken});
  assert.equal(purge.status, 204);
  console.log("synthetic raw-report cleanup: 204");
}
