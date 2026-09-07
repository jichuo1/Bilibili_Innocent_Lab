import assert from "node:assert/strict";
import test from "node:test";
import { runDailyMaintenance, shiftUtcDay, utcDay } from "../src/database";
import { fakeEnv } from "./helpers";

test("UTC day helpers do not depend on local timezone", () => {
  assert.equal(utcDay(Date.parse("2026-09-07T23:59:59.000Z")), "2026-09-07");
  assert.equal(shiftUtcDay("2026-03-01", -1), "2026-02-28");
});

test("maintenance aggregates yesterday and applies a bounded retention cutoff", async () => {
  const env = fakeEnv();
  env.RAW_RETENTION_DAYS = "30";
  env.AGGREGATION_MIN_DEVICES = "10";

  await runDailyMaintenance(env, Date.parse("2026-09-07T03:17:00.000Z"));

  assert.equal(env.DB.batches.length, 1);
  const statements = env.DB.batches[0] ?? [];
  assert.deepEqual(statements[0]?.params, ["2026-09-06"]);
  assert.deepEqual(statements[1]?.params, ["2026-09-06", 10]);
  assert.deepEqual(env.DB.runs.at(-1)?.params, ["2026-08-08"]);
});

test("invalid maintenance configuration falls back to privacy-preserving defaults", async () => {
  const env = fakeEnv();
  env.RAW_RETENTION_DAYS = "999";
  env.AGGREGATION_MIN_DEVICES = "1";

  await runDailyMaintenance(env, Date.parse("2026-09-07T03:17:00.000Z"));

  const statements = env.DB.batches[0] ?? [];
  assert.deepEqual(statements[1]?.params, ["2026-09-06", 10]);
  assert.deepEqual(env.DB.runs.at(-1)?.params, ["2026-08-08"]);
});
