import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { insertReport, purgeReports, runDailyMaintenance } from "../src/database";
import { toStoredPayload, validateReport } from "../src/validation";
import { fakeEnv, validReport } from "./helpers";

function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  for (const name of ["0001_init.sql", "0002_manual_quota.sql", "0003_purge_lookup_index.sql", "0004_capability_daily.sql"]) {
    sqlite.exec(readFileSync(new URL("../migrations/" + name, import.meta.url), "utf8"));
  }
  const execute = (sql: string, params: (string | number)[]) => {
    const result = sqlite.prepare(sql).run(...params);
    return { success: true, meta: { changes: Number(result.changes) } };
  };
  const db = {
    prepare(sql: string) {
      return { bind(...params: (string | number)[]) {
        return { sql, params, async run() { return execute(sql, params); } };
      } };
    },
    async batch(statements: {sql: string; params: (string | number)[]}[]) {
      sqlite.exec("BEGIN");
      try {
        const result = statements.map(s => execute(s.sql, s.params));
        sqlite.exec("COMMIT");
        return result;
      } catch (e) {
        sqlite.exec("ROLLBACK");
        throw e;
      }
    },
  } as unknown as D1Database;
  const env = { ...fakeEnv(), DB: db };
  const now = Date.parse("2026-09-07T20:00:00Z");
  async function send(manual = true, time = now, install = "installation-a", token = "token-a") {
    const report = validateReport(validReport({
      upload_kind: manual ? "manual" : "automatic", client_report_id: randomUUID(),
    }));
    return insertReport(env, report, toStoredPayload(report), install, token, time);
  }
  const count = (table: string) => sqlite.prepare("SELECT COUNT(*) AS n FROM " + table).get()?.n;
  return { sqlite, env, now, send, count };
}

test("old clients default to automatic and invalid upload kinds are rejected", () => {
  assert.equal(validateReport(validReport()).upload_kind, "automatic");
  assert.throws(() => validateReport(validReport({upload_kind: "force"})));
});

test("unknown deletion tokens use an indexed lookup instead of a report table scan", () => {
  const f = fixture();
  try {
    const plan = f.sqlite.prepare("EXPLAIN QUERY PLAN DELETE FROM reports WHERE purge_key_hash = ?").all("unknown");
    assert.ok(plan.some(row => String(row.detail).includes("idx_reports_purge_key")));
    assert.ok(plan.every(row => !String(row.detail).includes("SCAN reports")));
  } finally { f.sqlite.close(); }
});

test("three concurrent manual attempts succeed, the fourth fails, without inflating daily reports", async () => {
  const f = fixture();
  try {
    assert.equal(await f.send(false), true);
    const results = await Promise.all(Array.from({length: 12}, () => f.send()));
    assert.equal(results.filter(Boolean).length, 3);
    assert.equal(f.count("manual_upload_attempts"), 3);
    assert.equal(f.count("reports"), 1);
    // Automatic attempts do not consume manual quota and retain prior daily deduplication.
    assert.equal(await f.send(false), true);
    assert.equal(f.count("manual_upload_attempts"), 3);
    assert.equal(f.count("reports"), 1);
  } finally { f.sqlite.close(); }
});

test("quota is rolling across UTC midnight and refills exactly at the oldest 24h boundary", async () => {
  const f = fixture();
  try {
    for (let i = 0; i < 3; i++) assert.equal(await f.send(true, f.now + i * 1000), true);
    assert.equal(await f.send(true, f.now + 5 * 3600_000), false);
    assert.equal(await f.send(true, f.now + 86_400_000 - 1), false);
    assert.equal(await f.send(true, f.now + 86_400_000), true);
    assert.equal(await f.send(true, f.now + 86_400_000), false);
  } finally { f.sqlite.close(); }
});

test("purge and changing only one identifier cannot reset manual quota", async () => {
  const f = fixture();
  try {
    for (let i = 0; i < 3; i++) await f.send();
    await purgeReports(f.env, "token-a");
    assert.equal(f.count("reports"), 0);
    assert.equal(await f.send(), false);
    assert.equal(await f.send(true, f.now, "installation-b", "token-a"), false);
    assert.equal(await f.send(true, f.now, "installation-a", "token-b"), false);
    assert.equal(f.count("reports"), 0);
  } finally { f.sqlite.close(); }
});

test("manual snapshot replaces daily data and later automatic duplicates do not overwrite it", async () => {
  const f = fixture();
  try {
    await f.send(false);
    const report = validateReport(validReport({upload_kind: "manual", features: []}));
    assert.equal(await insertReport(f.env, report, toStoredPayload(report),
      "installation-a", "token-a", f.now), true);
    await f.send(false);
    assert.equal(f.count("reports"), 1);
    const row = f.sqlite.prepare("SELECT payload_json FROM reports").get();
    assert.deepEqual(JSON.parse(String(row?.payload_json)).features, []);
  } finally { f.sqlite.close(); }
});

test("daily safety budget bounds writes across fabricated identities and recovers the next day", async () => {
  const f = fixture();
  f.env.MAX_REPORT_ATTEMPTS_PER_DAY = "2";
  try {
    assert.equal(await f.send(false), true);
    assert.equal(await f.send(true, f.now, "b", "b"), true);
    assert.equal(await f.send(false, f.now, "c", "c"), false);
    assert.equal(await f.send(true, f.now, "d", "d"), false);
    assert.equal(f.count("reports"), 2);
    assert.equal(f.sqlite.prepare("SELECT attempts FROM ingest_budget").get()?.attempts, 2);
    assert.equal(await f.send(false, f.now + 86_400_000, "c", "c"), true);
    // Deletion is not subject to the ingest budget.
    await purgeReports(f.env, "token-a");
    assert.equal(f.count("reports"), 2);
  } finally { f.sqlite.close(); }
});

test("database errors roll back quota and budget instead of silently consuming either", async () => {
  const f = fixture();
  try {
    f.sqlite.exec("CREATE TRIGGER reject_report BEFORE INSERT ON reports BEGIN SELECT RAISE(ABORT, 'test failure'); END");
    await assert.rejects(() => f.send());
    assert.equal(f.count("manual_upload_attempts"), 0);
    assert.equal(f.count("ingest_budget"), 0);
  } finally { f.sqlite.close(); }
});

test("maintenance removes expired security records but keeps active rolling quota", async () => {
  const f = fixture();
  try {
    await f.send(true, f.now - 86_400_000);
    await f.send(true, f.now - 1);
    await runDailyMaintenance(f.env, f.now);
    assert.equal(f.count("manual_upload_attempts"), 1);
    assert.equal(f.count("ingest_budget"), 1);
  } finally { f.sqlite.close(); }
});

test("daily aggregation separates legacy groups from capability units and excludes unknown or incomplete samples", async () => {
  const f=fixture();
  try {
    for(let i=0;i<10;i++) {
      for(const schema of [1,2] as const) {
        const report=validateReport(validReport({
          client_report_id:randomUUID(),schema_version:schema,
          ...(schema===2?{disclosure_version:4,device:{manufacturer:"unknown",model:"unknown",rom:"unknown"},
            feature_catalog_version:1,snapshot_complete:true,snapshot_state:"completed",
            evidence_scope:"current_host_process",feature_groups:[]}:{}),
          features:schema===1?[{id:"paused_ad",state:"installed"}]:[
            {id:"paused_ad",state:i<3?"partial":"installed"},
            {id:"comments_vote_widgets_removed",state:"unknown"},
          ],
        }));
        await insertReport(f.env,report,toStoredPayload(report),"installation-"+schema+"-"+i,"token-"+schema+"-"+i,f.now);
      }
    }
    const incomplete=validateReport(validReport({
      client_report_id:randomUUID(),schema_version:2,disclosure_version:4,
      device:{manufacturer:"unknown",model:"unknown",rom:"unknown"},
      feature_catalog_version:1,snapshot_complete:false,snapshot_state:"failed",
      evidence_scope:"current_host_process",feature_groups:[],
      features:[{id:"paused_ad",state:"failed"}],
    }));
    await insertReport(f.env,incomplete,toStoredPayload(incomplete),"unfinished","unfinished-token",f.now);
    await runDailyMaintenance(f.env,f.now+86_400_000);
    const legacy=f.sqlite.prepare("SELECT * FROM feature_daily").all();
    const capabilities=f.sqlite.prepare("SELECT * FROM capability_daily").all();
    assert.equal(legacy.length,1);
    assert.equal(capabilities.length,1);
    assert.equal(legacy[0]?.installed_count,10);
    assert.equal(legacy[0]?.failed_count,0);
    assert.equal(capabilities[0]?.device_count,10);
    assert.equal(capabilities[0]?.installed_count,7);
    assert.equal(capabilities[0]?.failed_count,3);
  } finally {f.sqlite.close();}
});
