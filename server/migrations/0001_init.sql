CREATE TABLE IF NOT EXISTS reports (
    report_id           TEXT PRIMARY KEY,
    schema_version      INTEGER NOT NULL,
    install_key         TEXT NOT NULL,
    purge_key_hash      TEXT NOT NULL,
    report_day          TEXT NOT NULL,
    module_version_code INTEGER NOT NULL,
    host_version_code   INTEGER NOT NULL,
    framework_code      TEXT NOT NULL,
    delivery_channel    TEXT NOT NULL,
    payload_json        TEXT NOT NULL,
    UNIQUE (install_key, report_day)
);

CREATE INDEX IF NOT EXISTS idx_reports_day
    ON reports(report_day);

CREATE INDEX IF NOT EXISTS idx_reports_host_module
    ON reports(host_version_code, module_version_code);

CREATE INDEX IF NOT EXISTS idx_reports_install
    ON reports(install_key);

CREATE TABLE IF NOT EXISTS feature_daily (
    report_day          TEXT NOT NULL,
    host_version_code   INTEGER NOT NULL,
    module_version_code INTEGER NOT NULL,
    framework_code      TEXT NOT NULL,
    feature_id          TEXT NOT NULL,
    device_count        INTEGER NOT NULL,
    installed_count     INTEGER NOT NULL,
    failed_count        INTEGER NOT NULL,
    PRIMARY KEY (
        report_day,
        host_version_code,
        module_version_code,
        framework_code,
        feature_id
    )
);
