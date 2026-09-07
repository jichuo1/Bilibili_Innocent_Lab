import { CAPABILITIES } from "../capabilities";
import { ABI_CODES, DELIVERY_CHANNELS, FEATURE_ID_PATTERN, FEATURE_STATES, FRAMEWORK_CODES, DEVICE_LABEL_PATTERN, ROM_CODES } from "../constants";

export interface AnalyticsFilter {
  environment: "production" | "staging";
  days: number;
  schema: 1 | 2;
  parent: string;
  host: number; module: number; sdk: number;
  framework: string; channel: string; abi: string; feature: string; state: string;
  manufacturer: string; model: string; rom: string; framework_release: string;
  startDay: string; endDay: string; splitDay: string; custom: boolean;
}
export type SummaryRow = Record<string, string | number | null>;
const DAY = 86400000;
const iso = (time: number) => new Date(time).toISOString().slice(0, 10);
function numeric(value: string, min = 1, max = 2147483647): number {
  if (!value) return 0;
  if (!/^[1-9][0-9]{0,9}$/.test(value) || Number(value) < min || Number(value) > max) throw new Error("INVALID_FILTER");
  return Number(value);
}
function day(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error("INVALID_DATE");
  const time = Date.parse(value + "T00:00:00Z");
  if (!Number.isFinite(time) || iso(time) !== value) throw new Error("INVALID_DATE");
  return time;
}
export function parseFilter(url: URL, now = Date.now()): AnalyticsFilter {
  const allowed = new Set(["environment","days","host","module","sdk","framework","channel","abi","feature","state","start","end","manufacturer","model","rom","framework_release","schema","parent"]);
  if (url.search.length > 2048) throw new Error("INVALID_FILTER");
  for (const key of url.searchParams.keys()) {
    if (!allowed.has(key) || url.searchParams.getAll(key).length !== 1) throw new Error("INVALID_FILTER");
  }
  const value = (key: string, fallback = "") => url.searchParams.get(key) ?? fallback;
  const environment = value("environment", "production"), preset = value("days", "7");
  const schema = value("schema", "2");
  if (!["1","2"].includes(schema)) throw new Error("INVALID_SCHEMA_FILTER");
  if (!["production","staging"].includes(environment) || !["1","7","14","30"].includes(preset)) throw new Error("INVALID_FILTER");
  const enums = (key: string, options: readonly string[]) => {
    const v = value(key);
    if (v && !options.includes(v)) throw new Error("INVALID_FILTER");
    return v;
  };
  const product = (key: string) => {
    const v=value(key);
    if (v && (!DEVICE_LABEL_PATTERN.test(v) || v !== v.trim())) throw new Error("INVALID_FILTER");
    return v;
  };
  const release=value("framework_release");
  if (release && !/^[A-Za-z0-9 ._()+/\u3400-\u9fff-]{1,160}$/.test(release)) throw new Error("INVALID_FILTER");
  const parent = value("parent");
  if (parent && !CAPABILITIES.some(capability => capability.parent === parent)) throw new Error("INVALID_PARENT_FILTER");
  const feature = value("feature");
  if (feature && !FEATURE_ID_PATTERN.test(feature)) throw new Error("INVALID_FILTER");
  const today = day(iso(now)), custom = Boolean(value("start") || value("end"));
  const end = custom ? day(value("end")) : today;
  const start = custom ? day(value("start")) : today - (Number(preset)-1)*DAY;
  if (start < today-29*DAY || start > end || end > today) throw new Error("INVALID_RANGE");
  const days = (end-start)/DAY+1;
  return {
    environment: environment as AnalyticsFilter["environment"], days, schema: Number(schema) as 1 | 2, parent,
    host: numeric(value("host")), module: numeric(value("module")), sdk: numeric(value("sdk"),27,100),
    framework: enums("framework",FRAMEWORK_CODES), channel: enums("channel",DELIVERY_CHANNELS),
    manufacturer: product("manufacturer"), model: product("model"), rom: enums("rom",[...ROM_CODES,"not_collected"]), framework_release: release,
    abi: enums("abi",ABI_CODES), feature, state: enums("state",FEATURE_STATES),
    startDay: iso(start), endDay: iso(end), splitDay: iso(start+Math.floor(days/2)*DAY), custom,
  };
}

// Fixed SQL only. All user values are bound; no raw JSON or identifiers leave D1.
const INPUT = "WITH input AS (SELECT ?1 AS start, ?2 AS end, ?3 AS host, ?4 AS module, ?5 AS sdk, ?6 AS framework, ?7 AS channel, ?8 AS abi, ?9 AS feature, ?10 AS state, ?11 AS mid, ?12 AS manufacturer, ?13 AS model, ?14 AS rom, ?15 AS framework_release, ?16 AS data_schema, ?17 AS parent)";
const RELEASE = "CASE WHEN json_extract(payload_json,'$.runtime.framework_version') IS NULL THEN 'not_collected' ELSE framework_code || ' / ' || json_extract(payload_json,'$.runtime.framework_version') || ' / ' || COALESCE(CAST(json_extract(payload_json,'$.runtime.framework_version_code') AS TEXT),'0') END";
// Budget first on indexed/scalar fields. Refuse the whole analysis instead of returning a truncated sample.
const CANDIDATES = [
  ", candidates AS MATERIALIZED (SELECT r.* FROM reports r, input p",
  "WHERE report_day BETWEEN p.start AND p.end AND schema_version=p.data_schema",
  "AND (p.host=0 OR host_version_code=p.host) AND (p.module=0 OR module_version_code=p.module)",
  "AND (p.framework='' OR framework_code=p.framework) AND (p.channel='' OR delivery_channel=p.channel) LIMIT 2001),",
  "budgeted AS MATERIALIZED (SELECT * FROM candidates WHERE (SELECT COUNT(*) FROM candidates)<=2000)"
].join(" ");
const BASE = INPUT + CANDIDATES + [
  ", selected AS (SELECT r.*, json_extract(payload_json,'$.runtime.android_sdk') AS sdk,",
  "json_extract(payload_json,'$.runtime.abi') AS abi,",
  "COALESCE(json_extract(payload_json,'$.device.manufacturer'),'not_collected') AS manufacturer,",
  "COALESCE(json_extract(payload_json,'$.device.model'),'not_collected') AS model,",
  "COALESCE(json_extract(payload_json,'$.device.rom'),'not_collected') AS rom,"+RELEASE+" AS framework_release FROM budgeted r, input p",
  "WHERE r.report_day BETWEEN p.start AND p.end",
  "AND (p.host=0 OR r.host_version_code=p.host) AND (p.module=0 OR r.module_version_code=p.module)",
  "AND (p.sdk=0 OR json_extract(payload_json,'$.runtime.android_sdk')=p.sdk)",
  "AND (p.framework='' OR r.framework_code=p.framework)",
  "AND (p.manufacturer='' OR COALESCE(json_extract(payload_json,'$.device.manufacturer'),'not_collected')=p.manufacturer)",
  "AND (p.model='' OR COALESCE(json_extract(payload_json,'$.device.model'),'not_collected')=p.model)",
  "AND (p.rom='' OR COALESCE(json_extract(payload_json,'$.device.rom'),'not_collected')=p.rom)",
  "AND (p.framework_release='' OR ("+RELEASE+")=p.framework_release)",
  "AND (p.channel='' OR r.delivery_channel=p.channel) AND (p.abi='' OR json_extract(payload_json,'$.runtime.abi')=p.abi)",
  "AND ((p.feature='' AND p.state='' AND p.parent='') OR EXISTS (SELECT 1 FROM json_each(payload_json,'$.features') f",
  "WHERE (p.feature='' OR json_extract(f.value,'$.id')=p.feature) AND (p.state='' OR json_extract(f.value,'$.state')=p.state) AND (p.parent='' OR COALESCE(json_extract(f.value,'$.parent'),json_extract(f.value,'$.id'))=p.parent))))",
].join(" ");
const OBS = BASE + [
  ", observations AS (SELECT r.report_id, r.report_day AS day, r.install_key, r.host_version_code AS host,",
  "r.module_version_code AS module, r.framework_code AS framework, r.delivery_channel AS channel, r.sdk, r.abi, r.manufacturer, r.model, r.rom, r.framework_release,",
  "json_extract(f.value,'$.id') AS feature, json_extract(f.value,'$.state') AS state,",
  "COALESCE(json_extract(f.value,'$.reason_code'),'UNSPECIFIED') AS reason,",
  "COALESCE(json_extract(r.payload_json,'$.snapshot_complete'),1) AS complete,",
  "COALESCE(json_extract(f.value,'$.runtime_error'),0) AS runtime_error,",
  "json_extract(f.value,'$.observed') AS observed, json_extract(f.value,'$.applied') AS applied",
  "FROM selected r, json_each(r.payload_json,'$.features') f, input p",
  "WHERE (p.feature='' OR json_extract(f.value,'$.id')=p.feature) AND (p.state='' OR json_extract(f.value,'$.state')=p.state) AND (p.parent='' OR COALESCE(json_extract(f.value,'$.parent'),json_extract(f.value,'$.id'))=p.parent))",
].join(" ");
const BAD = "complete=1 AND state IN ('failed','missing','partial')";
const ELIGIBLE = "complete=1 AND state IN ('installed','failed','missing','partial')";
const sum = (condition: string, name: string) => "SUM(CASE WHEN "+condition+" THEN 1 ELSE 0 END) AS "+name;
const outcomes = [
  sum("complete=1 AND state='installed'","installed"), sum(BAD,"failed"), sum(ELIGIBLE,"eligible"),
  sum("complete=1 AND observed>0","observed"), sum("complete=1 AND applied>0","applied"),
  sum("state='unknown'","unknown"), sum("complete=1 AND state='partial'","partial"), sum("runtime_error=1","runtime_errors"),
].join(",");
export const ANALYTICS_QUERIES = {
  overview: OBS + " SELECT " + [
    "(SELECT COUNT(*)>2000 FROM candidates) AS query_blocked",
    "(SELECT COUNT(*) FROM selected WHERE json_extract(payload_json,'$.snapshot_complete')=0) AS incomplete_reports",
    "(SELECT COUNT(*) FROM selected) AS reports",
    "(SELECT COUNT(DISTINCT install_key) FROM selected) AS installations",
    "(SELECT COUNT(DISTINCT host_version_code) FROM selected) AS host_versions",
    "(SELECT MAX(report_day) FROM selected) AS latest_day", "COUNT(*) AS feature_samples",
    "COUNT(DISTINCT CASE WHEN "+BAD+" THEN install_key END) AS affected", outcomes,
  ].join(",") + " FROM observations",
  daily: OBS + ", totals AS (SELECT day," + sum(ELIGIBLE,"eligible")+","+sum(BAD,"failed")+
    " FROM observations GROUP BY day) SELECT r.report_day AS day, COUNT(*) AS reports, COUNT(DISTINCT r.install_key) AS installations,"+
    "COALESCE(t.eligible,0) AS eligible, COALESCE(t.failed,0) AS failed FROM selected r LEFT JOIN totals t ON r.report_day=t.day GROUP BY r.report_day ORDER BY r.report_day",
  distributions: BASE + [
    ", ranked AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY install_key ORDER BY report_day DESC,report_id DESC) AS rn FROM selected),",
    "latest AS (SELECT * FROM ranked WHERE rn=1), counts AS (",
    "SELECT dims.key AS dimension,CAST(dims.value AS TEXT) AS value,COUNT(*) AS installations FROM latest,",
    "json_each(json_object('host',host_version_code,'module',module_version_code,'sdk',COALESCE(CAST(sdk AS TEXT),'unknown'),",
    "'framework',framework_code,'channel',delivery_channel,'abi',COALESCE(abi,'unknown'),'manufacturer',manufacturer,'model',model,'rom',rom,'framework_release',framework_release)) dims GROUP BY dims.key,dims.value),",
    "ordered AS (SELECT *,ROW_NUMBER() OVER (PARTITION BY dimension ORDER BY installations DESC,value) AS rank FROM counts)",
    "SELECT dimension,CASE WHEN rank<=8 THEN value ELSE '__other__' END AS value,SUM(installations) AS installations FROM ordered",
    "GROUP BY dimension,CASE WHEN rank<=8 THEN value ELSE '__other__' END ORDER BY dimension,installations DESC,value",
  ].join(" "),
  features: OBS + [
    ", sampled AS (SELECT *,ROW_NUMBER() OVER (PARTITION BY install_key,host,module,framework,sdk,channel,abi,manufacturer,model,rom,framework_release,feature,",
    "CASE WHEN day<(SELECT mid FROM input) THEN 0 ELSE 1 END ORDER BY day DESC,report_id DESC) AS rn FROM observations)",
    "SELECT host,module,framework,sdk,channel,abi,manufacturer,model,rom,framework_release,feature,COUNT(*) AS reports,COUNT(DISTINCT install_key) AS installations,",
    "COUNT(DISTINCT CASE WHEN "+ELIGIBLE+" THEN install_key END) AS eligible_installations,",
    "COUNT(DISTINCT CASE WHEN "+BAD+" THEN install_key END) AS affected,",
    "COUNT(DISTINCT CASE WHEN runtime_error=1 THEN install_key END) AS runtime_error_installations,", outcomes+",",
    sum("rn=1 AND day<(SELECT mid FROM input) AND "+ELIGIBLE,"before_n")+",",
    sum("rn=1 AND day<(SELECT mid FROM input) AND "+BAD,"before_failed")+",",
    sum("rn=1 AND day>=(SELECT mid FROM input) AND "+ELIGIBLE,"after_n")+",",
    sum("rn=1 AND day>=(SELECT mid FROM input) AND "+BAD,"after_failed"),
    "FROM sampled GROUP BY host,module,framework,sdk,channel,abi,manufacturer,model,rom,framework_release,feature",
    "ORDER BY affected DESC,failed DESC,reports DESC,host DESC,module DESC,feature LIMIT 401",
  ].join(" "),
  reasons: OBS + " SELECT host,module,framework,sdk,channel,abi,manufacturer,model,rom,framework_release,feature,reason,COUNT(*) AS reports,COUNT(DISTINCT install_key) AS installations"+
    " FROM observations WHERE "+BAD+" GROUP BY host,module,framework,sdk,channel,abi,manufacturer,model,rom,framework_release,feature,reason ORDER BY reports DESC,host DESC,feature,reason LIMIT 101",
  groups: BASE + [
    " SELECT json_extract(g.value,'$.id') AS feature,COUNT(*) AS reports,",
    "COUNT(DISTINCT CASE WHEN json_extract(g.value,'$.runtime_error')=1 THEN install_key END) AS runtime_error_installations,",
    "SUM(CASE WHEN json_extract(g.value,'$.state')='installed' THEN 1 ELSE 0 END) AS installed,",
    "SUM(CASE WHEN json_extract(g.value,'$.state')='unknown' THEN 1 ELSE 0 END) AS unknown,",
    "SUM(CASE WHEN json_extract(g.value,'$.state') IN ('failed','missing') THEN 1 ELSE 0 END) AS failed,",
    "SUM(CASE WHEN json_extract(g.value,'$.state')='partial' THEN 1 ELSE 0 END) AS partial,",
    "SUM(CASE WHEN json_extract(g.value,'$.observed')>0 THEN 1 ELSE 0 END) AS observed,",
    "SUM(CASE WHEN json_extract(g.value,'$.applied')>0 THEN 1 ELSE 0 END) AS applied,",
    "SUM(CASE WHEN json_extract(g.value,'$.runtime_error')=1 THEN 1 ELSE 0 END) AS runtime_errors",
    "FROM selected,json_each(payload_json,'$.feature_groups') g,input p WHERE (p.parent='' OR json_extract(g.value,'$.id')=p.parent) GROUP BY feature ORDER BY feature"
  ].join(" "),
  history: INPUT + ", history_source AS (SELECT * FROM feature_daily WHERE ?16=1 UNION ALL SELECT * FROM capability_daily WHERE ?16=2)" + [
    " SELECT report_day AS day,SUM(device_count) AS feature_samples,SUM(installed_count) AS installed,SUM(failed_count) AS failed FROM history_source r,input p",
    "WHERE report_day BETWEEN p.start AND p.end AND (p.host=0 OR host_version_code=p.host)",
    "AND (p.module=0 OR module_version_code=p.module) AND (p.framework='' OR framework_code=p.framework)",
    "AND (p.feature='' OR feature_id=p.feature) AND p.parent='' AND p.sdk=0 AND p.channel='' AND p.abi='' AND p.state='' AND p.manufacturer='' AND p.model='' AND p.rom='' AND p.framework_release=''",
    "GROUP BY report_day ORDER BY report_day",
  ].join(" "),
} as const;
export type AnalyticsData = { -readonly [K in keyof typeof ANALYTICS_QUERIES]: SummaryRow[] };
export function historyAvailable(filter: AnalyticsFilter): boolean {
  return !filter.sdk && !filter.channel && !filter.abi && !filter.state && !filter.manufacturer && !filter.model && !filter.rom && !filter.framework_release && !filter.parent;
}
export async function readAnalytics(db: D1Database, filter: AnalyticsFilter): Promise<AnalyticsData> {
  const entries = Object.entries(ANALYTICS_QUERIES);
  const results = await db.batch(entries.map(([,sql]) => db.prepare(sql).bind(
    filter.startDay,filter.endDay,filter.host,filter.module,filter.sdk,filter.framework,
    filter.channel,filter.abi,filter.feature,filter.state,filter.splitDay,
    filter.manufacturer,filter.model,filter.rom,filter.framework_release,filter.schema,filter.parent,
  )));
  const output = {} as AnalyticsData;
  entries.forEach(([name],index) => {
    const result = results[index];
    if (!result?.success) throw new Error("ANALYTICS_QUERY_FAILED");
    output[name as keyof AnalyticsData] = (result.results ?? []) as SummaryRow[];
  });
  return output;
}
