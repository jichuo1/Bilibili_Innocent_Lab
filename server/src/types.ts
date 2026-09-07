import type {
  ABI_CODES,
  CACHE_STATUSES,
  DELIVERY_CHANNELS,
  DURATION_BUCKETS,
  FEATURE_STATES,
  FRAMEWORK_CODES,
  MODULE_CHANNELS,
} from "./constants";

export interface RateLimiter {
  limit(input: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  DB: D1Database;
  EDGE_LIMITER: RateLimiter;
  INSTALL_LIMITER: RateLimiter;
  SOURCE_LIMITER: RateLimiter;
  MAX_REPORT_ATTEMPTS_PER_DAY?: string;
  ID_HMAC_KEY: string;
  INGEST_RETIRED?: string;
  RAW_RETENTION_DAYS?: string;
  AGGREGATION_MIN_DEVICES?: string;
}

type ValueOf<T extends readonly string[]> = T[number];

export interface ModuleInfo {
  version_code: number;
  channel: ValueOf<typeof MODULE_CHANNELS>;
}

export interface HostInfo {
  version_code: number;
}

export interface RuntimeInfo {
  framework_version?: string;
  framework_version_code?: number;
  framework: ValueOf<typeof FRAMEWORK_CODES>;
  framework_api: number;
  android_sdk: number;
  abi: ValueOf<typeof ABI_CODES>;
  delivery_channel: ValueOf<typeof DELIVERY_CHANNELS>;
}

export interface BootstrapInfo {
  resolved: number;
  installed: number;
  missing: number;
  failed: number;
}

export interface AdaptationInfo {
  schema_version: number;
  rule_version: number;
  cache_status: ValueOf<typeof CACHE_STATUSES>;
  duration_bucket: ValueOf<typeof DURATION_BUCKETS>;
  dex_assist_used: boolean | "unknown";
  bootstrap: BootstrapInfo;
}

export interface FeatureInfo {
  parent?: string;
  id: string;
  state: ValueOf<typeof FEATURE_STATES>;
  reason_code?: string;
  hook_count?: number;
  observed?: number;
  applied?: number;
  runtime_error?: boolean;
}

export interface ValidatedReport {
  device?: DeviceProfile;
  disclosure_version?: 3 | 4;
  feature_catalog_version?: number;
  snapshot_complete?: boolean;
  snapshot_state?: string;
  evidence_scope?: "current_host_process";
  feature_groups?: FeatureInfo[];
  upload_kind: "automatic" | "manual";
  schema_version: 1 | 2;
  client_report_id: string;
  install_id: string;
  purge_token: string;
  module: ModuleInfo;
  host: HostInfo;
  runtime: RuntimeInfo;
  adaptation: AdaptationInfo;
  features: FeatureInfo[];
}

export interface ValidatedPurge {
  purge_token: string;
}

export interface StoredPayload {
  device?: DeviceProfile;
  disclosure_version?: 3 | 4;
  feature_catalog_version?: number;
  snapshot_complete?: boolean;
  snapshot_state?: string;
  evidence_scope?: "current_host_process";
  feature_groups?: FeatureInfo[];
  schema_version: 1 | 2;
  module: ModuleInfo;
  host: HostInfo;
  runtime: RuntimeInfo;
  adaptation: AdaptationInfo;
  features: FeatureInfo[];
}

export interface DeviceProfile {
  manufacturer: string;
  model: string;
  rom: string;
}
