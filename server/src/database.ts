import {
  DEFAULT_AGGREGATION_MIN_DEVICES,
  DEFAULT_RAW_RETENTION_DAYS,
} from "./constants";
import type { Env, StoredPayload, ValidatedReport } from "./types";

export const INSERT_REPORT_SQL = `
INSERT INTO reports (
  report_id,
  schema_version,
  install_key,
  purge_key_hash,
  report_day,
  module_version_code,
  host_version_code,
  framework_code,
  delivery_channel,
  payload_json
) SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10 WHERE changes() = 1
ON CONFLICT DO NOTHING
`;

export const PURGE_REPORTS_SQL = `
DELETE FROM reports
WHERE purge_key_hash = ?1
`;

const DELETE_AGGREGATE_DAY_SQL = `
DELETE FROM feature_daily WHERE report_day = ?1
`;

const INSERT_AGGREGATE_DAY_SQL = `
INSERT INTO feature_daily (
  report_day,
  host_version_code,
  module_version_code,
  framework_code,
  feature_id,
  device_count,
  installed_count,
  failed_count
)
SELECT
  reports.report_day,
  reports.host_version_code,
  reports.module_version_code,
  reports.framework_code,
  json_extract(feature.value, '$.id'),
  COUNT(DISTINCT reports.install_key),
  SUM(CASE WHEN json_extract(feature.value, '$.state') = 'installed' THEN 1 ELSE 0 END),
  SUM(CASE WHEN json_extract(feature.value, '$.state') IN ('failed', 'missing') THEN 1 ELSE 0 END)
FROM reports, json_each(reports.payload_json, '$.features') AS feature
WHERE reports.report_day = ?1 AND reports.schema_version = 1
GROUP BY
  reports.report_day,
  reports.host_version_code,
  reports.module_version_code,
  reports.framework_code,
  json_extract(feature.value, '$.id')
HAVING COUNT(DISTINCT reports.install_key) >= ?2
`;

// Keep legacy groups and new leaf capabilities in different aggregate tables.
const INSERT_CAPABILITY_DAY_SQL = INSERT_AGGREGATE_DAY_SQL
  .replace("INSERT INTO feature_daily", "INSERT INTO capability_daily")
  .replace("reports.schema_version = 1", "reports.schema_version = 2 AND json_extract(reports.payload_json, '$.snapshot_complete') = 1 AND json_extract(feature.value, '$.state') IN ('installed','partial','failed','missing')")
  .replace("IN ('failed', 'missing')", "IN ('failed', 'missing', 'partial')");

const DELETE_OLD_REPORTS_SQL = `
DELETE FROM reports
WHERE report_id IN (
  SELECT report_id
  FROM reports
  WHERE report_day < ?1
  LIMIT 1000
)
`;

export function utcDay(epochMs = Date.now()): string {
  return new Date(epochMs).toISOString().slice(0, 10);
}

export function shiftUtcDay(day: string, deltaDays: number): string {
  const timestamp = Date.parse(`${day}T00:00:00.000Z`);
  if (!Number.isFinite(timestamp)) throw new Error("INVALID_DAY");
  return utcDay(timestamp + deltaDays * 86_400_000);
}

function boundedInt(
  raw: string | undefined,
  fallback: number,
  min: number,
  max: number,
): number {
  if (raw === undefined) return fallback;
  const value = Number(raw);
  return Number.isInteger(value) && value >= min && value <= max ? value : fallback;
}

export const RESERVE_BUDGET_SQL = `
INSERT INTO ingest_budget(report_day, attempts) VALUES (?1, 1)
ON CONFLICT(report_day) DO UPDATE SET attempts = attempts + 1
WHERE attempts < ?2
`;

export const RESERVE_MANUAL_SQL = `
INSERT INTO manual_upload_attempts(report_id, install_key, purge_key_hash, accepted_at)
SELECT ?1, ?2, ?3, ?4
WHERE changes() = 1
  AND (SELECT COUNT(*) FROM manual_upload_attempts WHERE install_key = ?2 AND accepted_at > ?5) < 3
  AND (SELECT COUNT(*) FROM manual_upload_attempts WHERE purge_key_hash = ?3 AND accepted_at > ?5) < 3
ON CONFLICT DO NOTHING
`;

export const UPSERT_MANUAL_REPORT_SQL = INSERT_REPORT_SQL.replace(
  "ON CONFLICT DO NOTHING",
  `ON CONFLICT(install_key, report_day) DO UPDATE SET
    report_id = excluded.report_id,
    schema_version = excluded.schema_version,
    purge_key_hash = excluded.purge_key_hash,
    module_version_code = excluded.module_version_code,
    host_version_code = excluded.host_version_code,
    framework_code = excluded.framework_code,
    delivery_channel = excluded.delivery_channel,
    payload_json = excluded.payload_json
  ON CONFLICT DO NOTHING`,
);

/** Atomic budget -> manual quota -> snapshot. No client timestamp is trusted.
 * D1 batch rolls back all statements together on failure; changes() gates each write.
 * Manual refreshes retain one snapshot per installation/day, without inflating analytics.
 */
export async function insertReport(
  env: Env,
  report: ValidatedReport,
  payload: StoredPayload,
  installKey: string,
  purgeKeyHash: string,
  now = Date.now(),
): Promise<boolean> {
  const day = utcDay(now);
  const statements = [
    env.DB.prepare(RESERVE_BUDGET_SQL).bind(
      day, boundedInt(env.MAX_REPORT_ATTEMPTS_PER_DAY, 2000, 1, 10000),
    ),
  ];
  const manual = report.upload_kind === "manual";
  if (manual) statements.push(env.DB.prepare(RESERVE_MANUAL_SQL).bind(
    report.client_report_id, installKey, purgeKeyHash, now, now - 86_400_000,
  ));
  statements.push(env.DB.prepare(manual ? UPSERT_MANUAL_REPORT_SQL : INSERT_REPORT_SQL).bind(
    report.client_report_id, report.schema_version, installKey, purgeKeyHash, day,
    report.module.version_code, report.host.version_code, report.runtime.framework,
    report.runtime.delivery_channel, JSON.stringify(payload),
  ));
  const results = await env.DB.batch(statements);
  // Automatic duplicate snapshots remain an idempotent 204, as before.
  return Number(results[manual ? 1 : 0]?.meta?.changes ?? 0) === 1;
}

export async function purgeReports(
  env: Env,
  purgeKeyHash: string,
): Promise<void> {
  await env.DB.prepare(PURGE_REPORTS_SQL).bind(purgeKeyHash).run();
}

export async function runDailyMaintenance(env: Env, epochMs = Date.now()): Promise<void> {
  const today = utcDay(epochMs);
  const aggregateDay = shiftUtcDay(today, -1);
  const retentionDays = boundedInt(
    env.RAW_RETENTION_DAYS,
    DEFAULT_RAW_RETENTION_DAYS,
    7,
    90,
  );
  const minimumDevices = boundedInt(
    env.AGGREGATION_MIN_DEVICES,
    DEFAULT_AGGREGATION_MIN_DEVICES,
    10,
    1000,
  );
  const cutoffDay = shiftUtcDay(today, -retentionDays);

  await env.DB.batch([
    env.DB.prepare(DELETE_AGGREGATE_DAY_SQL).bind(aggregateDay),
    env.DB.prepare(INSERT_AGGREGATE_DAY_SQL).bind(aggregateDay, minimumDevices),
    env.DB.prepare("DELETE FROM capability_daily WHERE report_day = ?1").bind(aggregateDay),
    env.DB.prepare(INSERT_CAPABILITY_DAY_SQL).bind(aggregateDay, minimumDevices),
    env.DB.prepare("DELETE FROM manual_upload_attempts WHERE accepted_at <= ?1").bind(epochMs - 86_400_000),
    env.DB.prepare("DELETE FROM ingest_budget WHERE report_day < ?1").bind(today),
  ]);

  for (let batch = 0; batch < 50; batch += 1) {
    const result = await env.DB.prepare(DELETE_OLD_REPORTS_SQL).bind(cutoffDay).run();
    const changes = Number(result.meta?.changes ?? 0);
    if (changes < 1000) break;
  }
}
