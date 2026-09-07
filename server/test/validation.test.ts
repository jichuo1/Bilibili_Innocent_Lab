import assert from "node:assert/strict";
import test from "node:test";
import { InputError, toStoredPayload, validatePurge, validateReport } from "../src/validation";
import { validReport } from "./helpers";
import { CAPABILITIES } from "../src/capabilities";
import { MAX_REPORT_BYTES } from "../src/constants";

function capabilityReport(overrides: Record<string,unknown> = {}) {
  return validReport({
    schema_version:2,disclosure_version:4,
    device:{manufacturer:"unknown",model:"unknown",rom:"unknown"},
    feature_catalog_version:1,snapshot_complete:true,snapshot_state:"completed",
    evidence_scope:"current_host_process",feature_groups:[],
    features:[{id:"comments_search_links_removed",state:"partial",reason_code:"PARTIAL_COVERAGE",runtime_error:true}],
    ...overrides,
  });
}

test("accepts a valid report and sorts feature ids", () => {
  const report = validateReport(validReport());
  assert.deepEqual(
    report.features.map((feature) => feature.id),
    ["block_app_update", "comment_filter"],
  );

  const stored = toStoredPayload(report) as unknown as Record<string, unknown>;
  assert.equal(Object.hasOwn(stored, "install_id"), false);
  assert.equal(Object.hasOwn(stored, "purge_token"), false);
  assert.equal(Object.hasOwn(stored, "client_report_id"), false);
});

test("rejects unknown top-level fields", () => {
  assert.throws(
    () => validateReport(validReport({ uploaded_at_client_epoch_ms: Date.now() })),
    (error: unknown) => error instanceof InputError && error.code === "UNKNOWN_REPORT_FIELD",
  );
});

test("rejects forbidden fields at any depth", () => {
  const report = validReport();
  report.module = { version_code: 14, channel: "stable", uid: 123 };
  assert.throws(
    () => validateReport(report),
    (error: unknown) => error instanceof InputError && error.code === "FORBIDDEN_FIELD",
  );
});

test("rejects duplicate feature ids", () => {
  const report = validReport();
  report.features = [
    { id: "comment_filter", state: "installed" },
    { id: "comment_filter", state: "failed" },
  ];
  assert.throws(
    () => validateReport(report),
    (error: unknown) => error instanceof InputError && error.code === "DUPLICATE_FEATURE_ID",
  );
});

test("rejects free-form reason strings", () => {
  const report = validReport();
  report.features = [{ id: "comment_filter", state: "failed", reason_code: "user email here" }];
  assert.throws(
    () => validateReport(report),
    (error: unknown) => error instanceof InputError && error.code === "INVALID_REASON_CODE",
  );
});

test("accepts explicit unknown dex-assist evidence but rejects disabled feature preferences", () => {
  const report = validateReport(validReport());
  assert.equal(report.adaptation.dex_assist_used, "unknown");

  const disabled = validReport();
  disabled.features = [{ id: "comment_filter", state: "disabled" }];
  assert.throws(
    () => validateReport(disabled),
    (error: unknown) => error instanceof InputError && error.code === "INVALID_FEATURE_STATE",
  );

  const disguisedDisabled = validReport();
  disguisedDisabled.features = [
    { id: "comment_filter", state: "not_applicable", reason_code: "DISABLED" },
  ];
  assert.throws(
    () => validateReport(disguisedDisabled),
    (error: unknown) =>
      error instanceof InputError && error.code === "FORBIDDEN_FEATURE_PREFERENCE",
  );
});

test("validates purge requests separately", () => {
  assert.deepEqual(
    validatePurge({
      purge_token: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
    }),
    {
      purge_token: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
    },
  );
});

test("device extension requires disclosure v3 while legacy clients remain accepted",()=>{
  const device={manufacturer:"Xiaomi",model:"Xiaomi 14",rom:"hyperos"};
  const legacy=validateReport(validReport());
  assert.equal(legacy.device,undefined);
  assert.equal(Object.hasOwn(toStoredPayload(legacy),"device"),false);
  for(const version of [undefined,0,2,5,"3"]) {
    assert.throws(()=>validateReport(validReport({device,disclosure_version:version})));
  }
  const report=validateReport(validReport({device,disclosure_version:3}));
  assert.deepEqual(report.device,{...device,manufacturer:"xiaomi"});
  assert.equal(toStoredPayload(report).disclosure_version,3);
});

test("device fields reject oversized free text, extra properties and unique identifiers",()=>{
  for(const device of [
    {manufacturer:"x",model:"x".repeat(65),rom:"unknown"},
    {manufacturer:"x",model:"<script>",rom:"unknown"},
    {manufacturer:"x",model:"M",rom:"my custom ROM full fingerprint"},
    {manufacturer:"x",model:"M",rom:"unknown",serial:"secret"},
    {manufacturer:"x",model:"M",rom:"unknown",fingerprint:"secret"},
    {manufacturer:" x ",model:"M",rom:"unknown"},
    {manufacturer:"x",model:"M\n",rom:"unknown"},
  ]) assert.throws(()=>validateReport(validReport({device,disclosure_version:3})));
});

test("framework version is a separate bounded service version, never inferred from API level",()=>{
  const base=validReport();
  const runtime={...(base.runtime as object),framework_version:"2.2 (3110)",framework_version_code:3110};
  assert.throws(()=>validateReport(validReport({runtime})));
  const report=validateReport(validReport({runtime,disclosure_version:3,device:{
    manufacturer:"unknown",model:"unknown",rom:"unknown",
  }}));
  assert.equal(report.runtime.framework_api,102);
  assert.equal(report.runtime.framework_version_code,3110);
  assert.equal(report.runtime.framework_version,"2.2 (3110)");
  assert.equal(toStoredPayload(report).runtime.framework_version_code,3110);
  for(const invalid of [-1,2147483648,"3110"]) {
    assert.throws(()=>validateReport(validReport({runtime:{...runtime,framework_version_code:invalid},
      disclosure_version:3,device:report.device})));
  }
});

test("capability schema preserves independent states and requires current disclosure and catalog",()=>{
  const report=validateReport(capabilityReport({feature_groups:[
    {id:"comment_purify",state:"partial",reason_code:"PARTIAL_COVERAGE",observed:1,runtime_error:true},
  ]}));
  assert.equal(report.schema_version,2);
  assert.equal(report.features[0]?.state,"partial");
  assert.equal(report.features[0]?.runtime_error,true);
  assert.equal(toStoredPayload(report).snapshot_complete,true);
  assert.equal(toStoredPayload(report).feature_groups?.[0]?.id,"comment_purify");
  for(const change of [
    {disclosure_version:3},{feature_catalog_version:2},{snapshot_complete:"true"},
    {snapshot_complete:true,snapshot_state:"failed"},{evidence_scope:"all_time"},
  ]) assert.throws(()=>validateReport(capabilityReport(change)));
});

test("capability leaf and aggregate namespaces are disjoint and arbitrary IDs are rejected",()=>{
  for(const change of [
    {features:[{id:"comment_purify",state:"installed"}]},
    {features:[{id:"unregistered_capability",state:"installed"}]},
    {feature_groups:[{id:"comments_search_links_removed",state:"installed"}]},
    {features:[{id:"comments_search_links_removed",state:"disabled"}]},
    {features:[{id:"comments_search_links_removed",state:"unknown",enabled:true}]},
    {features:[{id:"comments_search_links_removed",state:"unknown",runtime_error:"true"}]},
  ]) assert.throws(()=>validateReport(capabilityReport(change)));
  assert.throws(()=>validateReport(validReport({features:[{id:"test",state:"partial"}]})));
});

test("the full capability catalog fits one report and no leaf is silently dropped",()=>{
  const input=capabilityReport({features:CAPABILITIES.map(capability=>({
    id:capability.id,state:"partial",reason_code:"PARTIAL_COVERAGE",hook_count:256,
    ...(capability.runtime>0?{observed:1}:{}),...(capability.runtime===2?{applied:1}:{}),runtime_error:true,
  }))});
  assert.ok(Buffer.byteLength(JSON.stringify(input))<=MAX_REPORT_BYTES);
  const report=validateReport(input);
  assert.equal(report.features.length,CAPABILITIES.length);
  assert.throws(()=>validateReport(capabilityReport({
    features:[...report.features,report.features[0]],
  })));
  assert.throws(()=>validateReport(validReport({features:Array.from({length:65},(_,i)=>({id:"f_"+i,state:"installed"}))})));
});
