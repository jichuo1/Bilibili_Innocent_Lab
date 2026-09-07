-- Separate semantic units: never mix schema-v1 parent groups with schema-v2 leaves.
CREATE TABLE capability_daily (
  report_day TEXT NOT NULL,
  host_version_code INTEGER NOT NULL,
  module_version_code INTEGER NOT NULL,
  framework_code TEXT NOT NULL,
  feature_id TEXT NOT NULL,
  device_count INTEGER NOT NULL,
  installed_count INTEGER NOT NULL,
  failed_count INTEGER NOT NULL,
  PRIMARY KEY(report_day,host_version_code,module_version_code,framework_code,feature_id)
);
