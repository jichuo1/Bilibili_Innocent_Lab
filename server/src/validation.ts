import {
  ABI_CODES,
  CACHE_STATUSES,
  DELIVERY_CHANNELS,
  DURATION_BUCKETS,
  FEATURE_ID_PATTERN,
  FEATURE_STATES,
  FORBIDDEN_KEYS,
  FRAMEWORK_CODES,
  MAX_FEATURES,
  MAX_LEGACY_FEATURES,
  CAPABILITY_DISCLOSURE_VERSION,
  MAX_JSON_DEPTH,
  MODULE_CHANNELS,
  PURGE_TOKEN_PATTERN,
  REASON_CODE_PATTERN,
  UUID_V4_PATTERN,
  DEVICE_DISCLOSURE_VERSION,
  DEVICE_LABEL_PATTERN,
  ROM_CODES,
} from "./constants";
import type {
  AdaptationInfo,
  BootstrapInfo,
  FeatureInfo,
  HostInfo,
  ModuleInfo,
  RuntimeInfo,
  StoredPayload,
  ValidatedPurge,
  ValidatedReport,
  DeviceProfile,
} from "./types";

import { CAPABILITY_CATALOG_VERSION, CAPABILITY_IDS, CAPABILITY_GROUP_IDS, CAPABILITY_BY_ID } from "./capabilities";

export class InputError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(code: string, status = 400) {
    super(code);
    this.name = "InputError";
    this.code = code;
    this.status = status;
  }
}

type JsonRecord = Record<string, unknown>;

function record(value: unknown, path: string): JsonRecord {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new InputError(`INVALID_${path}`);
  }
  return value as JsonRecord;
}

function exactKeys(
  value: JsonRecord,
  allowed: readonly string[],
  required: readonly string[],
  path: string,
): void {
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      throw new InputError(`UNKNOWN_${path}_FIELD`);
    }
  }
  for (const key of required) {
    if (!Object.hasOwn(value, key)) {
      throw new InputError(`MISSING_${path}_FIELD`);
    }
  }
}

function integer(value: unknown, min: number, max: number, code: string): number {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    throw new InputError(code);
  }
  return value as number;
}

function dexAssistUsage(value: unknown): boolean | "unknown" {
  if (typeof value === "boolean" || value === "unknown") return value;
  throw new InputError("INVALID_DEX_ASSIST");
}

function matchingString(value: unknown, pattern: RegExp, code: string): string {
  if (typeof value !== "string" || !pattern.test(value)) throw new InputError(code);
  return value;
}

function enumValue<const T extends readonly string[]>(
  value: unknown,
  values: T,
  code: string,
): T[number] {
  if (typeof value !== "string" || !values.includes(value)) throw new InputError(code);
  return value as T[number];
}

function optionalCount(value: unknown, code: string): number | undefined {
  if (value === undefined) return undefined;
  return integer(value, 0, 1_000_000, code);
}

function scanForbiddenKeys(value: unknown, depth = 0): void {
  if (depth > MAX_JSON_DEPTH) throw new InputError("JSON_TOO_DEEP");
  if (Array.isArray(value)) {
    for (const item of value) scanForbiddenKeys(item, depth + 1);
    return;
  }
  if (value === null || typeof value !== "object") return;

  for (const [key, child] of Object.entries(value as JsonRecord)) {
    if (FORBIDDEN_KEYS.has(key.toLowerCase())) throw new InputError("FORBIDDEN_FIELD");
    if (key === "__proto__" || key === "constructor" || key === "prototype") {
      throw new InputError("FORBIDDEN_FIELD");
    }
    scanForbiddenKeys(child, depth + 1);
  }
}

function parseModule(value: unknown): ModuleInfo {
  const input = record(value, "MODULE");
  exactKeys(input, ["version_code", "channel"], ["version_code", "channel"], "MODULE");
  return {
    version_code: integer(input.version_code, 1, 2_147_483_647, "INVALID_MODULE_VERSION"),
    channel: enumValue(input.channel, MODULE_CHANNELS, "INVALID_MODULE_CHANNEL"),
  };
}

function parseHost(value: unknown): HostInfo {
  const input = record(value, "HOST");
  exactKeys(input, ["version_code"], ["version_code"], "HOST");
  return {
    version_code: integer(input.version_code, 1, 2_147_483_647, "INVALID_HOST_VERSION"),
  };
}

function parseRuntime(value: unknown): RuntimeInfo {
  const input = record(value, "RUNTIME");
  const keys = ["framework", "framework_api", "android_sdk", "abi", "delivery_channel"];
  exactKeys(input, [...keys, "framework_version", "framework_version_code"], keys, "RUNTIME");
  return {
    ...(Object.hasOwn(input, "framework_version") || Object.hasOwn(input, "framework_version_code") ? {
      framework_version: matchingString(input.framework_version, DEVICE_LABEL_PATTERN, "INVALID_FRAMEWORK_VERSION").trim(),
      framework_version_code: integer(input.framework_version_code, 0, 2_147_483_647, "INVALID_FRAMEWORK_VERSION_CODE"),
    } : {}),
    framework: enumValue(input.framework, FRAMEWORK_CODES, "INVALID_FRAMEWORK"),
    framework_api: integer(input.framework_api, 0, 999, "INVALID_FRAMEWORK_API"),
    android_sdk: integer(input.android_sdk, 27, 100, "INVALID_ANDROID_SDK"),
    abi: enumValue(input.abi, ABI_CODES, "INVALID_ABI"),
    delivery_channel: enumValue(
      input.delivery_channel,
      DELIVERY_CHANNELS,
      "INVALID_DELIVERY_CHANNEL",
    ),
  };
}

function parseBootstrap(value: unknown): BootstrapInfo {
  const input = record(value, "BOOTSTRAP");
  const keys = ["resolved", "installed", "missing", "failed"];
  exactKeys(input, keys, keys, "BOOTSTRAP");
  return {
    resolved: integer(input.resolved, 0, 10_000, "INVALID_BOOTSTRAP_COUNT"),
    installed: integer(input.installed, 0, 10_000, "INVALID_BOOTSTRAP_COUNT"),
    missing: integer(input.missing, 0, 10_000, "INVALID_BOOTSTRAP_COUNT"),
    failed: integer(input.failed, 0, 10_000, "INVALID_BOOTSTRAP_COUNT"),
  };
}

function parseAdaptation(value: unknown): AdaptationInfo {
  const input = record(value, "ADAPTATION");
  const keys = [
    "schema_version",
    "rule_version",
    "cache_status",
    "duration_bucket",
    "dex_assist_used",
    "bootstrap",
  ];
  exactKeys(input, keys, keys, "ADAPTATION");
  return {
    schema_version: integer(input.schema_version, 0, 100_000, "INVALID_ADAPTER_SCHEMA"),
    rule_version: integer(input.rule_version, 0, 100_000, "INVALID_ADAPTER_RULE"),
    cache_status: enumValue(input.cache_status, CACHE_STATUSES, "INVALID_CACHE_STATUS"),
    duration_bucket: enumValue(
      input.duration_bucket,
      DURATION_BUCKETS,
      "INVALID_DURATION_BUCKET",
    ),
    dex_assist_used: dexAssistUsage(input.dex_assist_used),
    bootstrap: parseBootstrap(input.bootstrap),
  };
}

function parseFeature(value: unknown, schema: number): FeatureInfo {
  const input = record(value, "FEATURE");
  const allowed = ["id", "state", "reason_code", "hook_count", "observed", "applied", ...(schema === 2 ? ["runtime_error"] : [])];
  exactKeys(input, allowed, ["id", "state"], "FEATURE");

  const feature: FeatureInfo = {
    id: matchingString(input.id, FEATURE_ID_PATTERN, "INVALID_FEATURE_ID"),
    state: enumValue(input.state, schema === 2 ? FEATURE_STATES : FEATURE_STATES.filter(state => state !== "partial" && state !== "unknown"), "INVALID_FEATURE_STATE"),
  };
  if (input.reason_code !== undefined) {
    feature.reason_code = matchingString(
      input.reason_code,
      REASON_CODE_PATTERN,
      "INVALID_REASON_CODE",
    );
    if (feature.reason_code === "DISABLED") {
      throw new InputError("FORBIDDEN_FEATURE_PREFERENCE");
    }
  }

  const hookCount = optionalCount(input.hook_count, "INVALID_FEATURE_COUNT");
  const observed = optionalCount(input.observed, "INVALID_FEATURE_COUNT");
  const applied = optionalCount(input.applied, "INVALID_FEATURE_COUNT");
  if (hookCount !== undefined) feature.hook_count = hookCount;
  if (observed !== undefined) feature.observed = observed;
  if (applied !== undefined) feature.applied = applied;
  if (input.runtime_error !== undefined) {
    if (typeof input.runtime_error !== "boolean") throw new InputError("INVALID_RUNTIME_ERROR");
    if (input.runtime_error) feature.runtime_error = true;
  }
  return feature;
}

function parseFeatures(value: unknown, schema: number, groups = false): FeatureInfo[] {
  if (!Array.isArray(value) || value.length > (schema === 2 ? MAX_FEATURES : MAX_LEGACY_FEATURES)) {
    throw new InputError("INVALID_FEATURES");
  }
  const ids = new Set<string>();
  const result = value.map(item => parseFeature(item, schema));
  for (const feature of result) {
    if (schema === 2 && !(groups ? CAPABILITY_GROUP_IDS : CAPABILITY_IDS).has(feature.id)) throw new InputError("UNKNOWN_CAPABILITY");
    if (schema === 2 && !groups) {
      const capability = CAPABILITY_BY_ID.get(feature.id)!;
      feature.parent = capability.parent;
      if ((capability.runtime === 0 && feature.observed !== undefined) ||
          (capability.runtime < 2 && feature.applied !== undefined)) {
        throw new InputError("UNSUPPORTED_CAPABILITY_EVIDENCE");
      }
      if ((feature.observed !== undefined && feature.observed > 1) ||
          (feature.applied !== undefined && feature.applied > 1)) {
        throw new InputError("INVALID_STAGE_FLAG");
      }
    }
    if (ids.has(feature.id)) throw new InputError("DUPLICATE_FEATURE_ID");
    ids.add(feature.id);
  }
  return result.sort((left, right) => left.id.localeCompare(right.id));
}

export function validateReport(value: unknown): ValidatedReport {
  scanForbiddenKeys(value);
  const input = record(value, "REPORT");
  const keys = [
    "schema_version",
    "client_report_id",
    "install_id",
    "purge_token",
    "module",
    "host",
    "runtime",
    "adaptation",
    "features",
  ];
  const schemaVersion = integer(input.schema_version, 1, 2, "UNSUPPORTED_SCHEMA") as 1 | 2;
  const capabilityKeys = schemaVersion === 2
    ? ["feature_catalog_version","snapshot_complete","snapshot_state","evidence_scope","feature_groups"] : [];
  exactKeys(input, [...keys, "upload_kind", "device", "disclosure_version", ...capabilityKeys],
    [...keys, ...capabilityKeys], "REPORT");


  const runtimeInput = record(input.runtime, "RUNTIME");
  if (Object.hasOwn(runtimeInput, "framework_version") || Object.hasOwn(runtimeInput, "framework_version_code")) {
    integer(input.disclosure_version, 3, 4, "DEVICE_CONSENT_REQUIRED");
  }

  return {
    ...parseDeviceExtension(input),
    ...(schemaVersion === 2 ? parseCapabilityExtension(input) : {}),
    upload_kind: input.upload_kind === undefined ? "automatic" :
      enumValue(input.upload_kind, ["automatic", "manual"] as const, "INVALID_UPLOAD_KIND"),
    schema_version: schemaVersion,
    client_report_id: matchingString(
      input.client_report_id,
      UUID_V4_PATTERN,
      "INVALID_REPORT_ID",
    ),
    install_id: matchingString(input.install_id, UUID_V4_PATTERN, "INVALID_INSTALL_ID"),
    purge_token: matchingString(input.purge_token, PURGE_TOKEN_PATTERN, "INVALID_PURGE_TOKEN"),
    module: parseModule(input.module),
    host: parseHost(input.host),
    runtime: parseRuntime(input.runtime),
    adaptation: parseAdaptation(input.adaptation),
    features: parseFeatures(input.features, schemaVersion),
  };
}

export function validatePurge(value: unknown): ValidatedPurge {
  scanForbiddenKeys(value);
  const input = record(value, "PURGE");
  const keys = ["purge_token"];
  exactKeys(input, keys, keys, "PURGE");
  return {
    purge_token: matchingString(input.purge_token, PURGE_TOKEN_PATTERN, "INVALID_PURGE_TOKEN"),
  };
}

export function toStoredPayload(report: ValidatedReport): StoredPayload {
  return {
    ...(report.device ? {device: report.device, disclosure_version: report.disclosure_version} : {}),
    ...(report.schema_version === 2 ? {
      feature_catalog_version: report.feature_catalog_version,
      snapshot_complete: report.snapshot_complete,
      snapshot_state: report.snapshot_state,
      evidence_scope: report.evidence_scope,
      feature_groups: report.feature_groups,
    } : {}),
    schema_version: report.schema_version,
    module: report.module,
    host: report.host,
    runtime: report.runtime,
    adaptation: report.adaptation,
    features: report.features,
  };
}

function parseDeviceExtension(input: JsonRecord): {device?: DeviceProfile; disclosure_version?: 3 | 4} {
  if (!Object.hasOwn(input, "device") && !Object.hasOwn(input, "disclosure_version")) return {};
  integer(input.disclosure_version, DEVICE_DISCLOSURE_VERSION, CAPABILITY_DISCLOSURE_VERSION, "DEVICE_CONSENT_REQUIRED");
  const device = record(input.device, "DEVICE");
  exactKeys(device, ["manufacturer","model","rom"], ["manufacturer","model","rom"], "DEVICE");
  const productLabel = (value: unknown): string => {
    const text = matchingString(value, DEVICE_LABEL_PATTERN, "INVALID_DEVICE_LABEL");
    if (text !== text.trim().replace(/ +/g, " ")) throw new InputError("INVALID_DEVICE_LABEL");
    return text;
  };
  return {
    disclosure_version: input.disclosure_version as 3 | 4,
    device: {
      manufacturer: productLabel(device.manufacturer).toLowerCase(),
      model: productLabel(device.model),
      rom: enumValue(device.rom, ROM_CODES, "INVALID_ROM"),
    },
  };
}

function parseCapabilityExtension(input: JsonRecord) {
  integer(input.disclosure_version, CAPABILITY_DISCLOSURE_VERSION, CAPABILITY_DISCLOSURE_VERSION, "CAPABILITY_CONSENT_REQUIRED");
  integer(input.feature_catalog_version, CAPABILITY_CATALOG_VERSION, CAPABILITY_CATALOG_VERSION, "UNSUPPORTED_CAPABILITY_CATALOG");
  if (typeof input.snapshot_complete !== "boolean") throw new InputError("INVALID_SNAPSHOT_COMPLETENESS");
  const state = enumValue(input.snapshot_state, ["not_started","started","completed","failed"] as const, "INVALID_SNAPSHOT_STATE");
  if (input.snapshot_complete !== (state === "completed")) throw new InputError("INCONSISTENT_SNAPSHOT_STATE");
  return {
    feature_catalog_version: CAPABILITY_CATALOG_VERSION,
    snapshot_complete: input.snapshot_complete,
    snapshot_state: state,
    evidence_scope: enumValue(input.evidence_scope, ["current_host_process"] as const, "INVALID_EVIDENCE_SCOPE"),
    feature_groups: parseFeatures(input.feature_groups, 2, true),
  };
}
