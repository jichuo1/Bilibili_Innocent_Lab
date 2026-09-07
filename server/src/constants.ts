export const REPORT_SCHEMA_VERSION = 1;
export const DEVICE_DISCLOSURE_VERSION = 3;
export const CAPABILITY_DISCLOSURE_VERSION = 4;
export const DEVICE_LABEL_PATTERN = /^[A-Za-z0-9\u3400-\u9fff][A-Za-z0-9\u3400-\u9fff ._()+-]{0,63}$/;
export const ROM_CODES = [
  "lineageos", "hyperos", "miui", "realme_ui", "oxygenos", "coloros",
  "originos", "funtouchos", "one_ui", "emui", "harmonyos", "flyme", "unknown",
] as const;
export const MAX_REPORT_BYTES = 32 * 1024;
export const MAX_PURGE_BYTES = 1024;
export const MAX_FEATURES = 256;
export const MAX_LEGACY_FEATURES = 64;
export const MAX_JSON_DEPTH = 8;
export const DEFAULT_RAW_RETENTION_DAYS = 30;
export const DEFAULT_AGGREGATION_MIN_DEVICES = 10;

export const MODULE_CHANNELS = ["stable", "alpha"] as const;
export const FRAMEWORK_CODES = [
  "lsposed",
  "vector",
  "irena",
  "npatch",
  "lspatch",
  "unknown",
] as const;
export const DELIVERY_CHANNELS = [
  "standard",
  "npatch",
  "lspatch_manager",
  "lspatch_embed",
] as const;
export const ABI_CODES = ["arm64-v8a", "armeabi-v7a", "unknown"] as const;
export const CACHE_STATUSES = [
  "file_hit",
  "prefs_hit",
  "miss",
  "fingerprint_mismatch",
  "schema_mismatch",
  "unknown",
] as const;
export const DURATION_BUCKETS = ["lt_1s", "1_to_5s", "gt_5s", "unknown"] as const;
export const FEATURE_STATES = [
  "installed",
  "partial",
  "unknown",
  "failed",
  "missing",
  "not_applicable",
  "safe_skip",
] as const;

export const UUID_V4_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const PURGE_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
export const FEATURE_ID_PATTERN = /^[a-z][a-z0-9_]{0,63}$/;
export const REASON_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;

export const FORBIDDEN_KEYS = new Set([
  "uid",
  "mid",
  "nickname",
  "username",
  "email",
  "phone",
  "cookie",
  "access_key",
  "accesskey",
  "token",
  "password",
  "android_id",
  "imei",
  "aaid",
  "serial",
  "mac",
  "device_model",
  "device_brand",
  "exception_message",
  "stacktrace",
  "search_query",
  "query",
  "comment",
  "danmaku",
  "location",
  "latitude",
  "longitude",
  "ip",
]);
