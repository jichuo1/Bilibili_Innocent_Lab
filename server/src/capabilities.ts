import catalog from "./analytics/capability-catalog.json" with { type: "json" };

// Generated display/protocol projection of DiagnosticCapabilityCatalog.kt.
// Tests compare IDs, parent relationships and labels against the Android source.
export const CAPABILITY_CATALOG_VERSION = catalog.version;
export const CAPABILITIES = catalog.capabilities;
export const CAPABILITY_IDS = new Set(CAPABILITIES.map(capability => capability.id));
export const CAPABILITY_GROUP_IDS = new Set(CAPABILITIES
  .filter(capability => capability.id !== capability.parent).map(capability => capability.parent));
export const CAPABILITY_BY_ID = new Map(CAPABILITIES.map(capability => [capability.id, capability]));
