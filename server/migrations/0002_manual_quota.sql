-- No raw IP, installation UUID or deletion token. Kept independently of report purge
-- to prevent delete-and-retry quota bypass, and removed after the rolling window.
CREATE TABLE manual_upload_attempts (
  report_id TEXT PRIMARY KEY,
  install_key TEXT NOT NULL,
  purge_key_hash TEXT NOT NULL,
  accepted_at INTEGER NOT NULL
);
CREATE INDEX idx_manual_install_time ON manual_upload_attempts(install_key, accepted_at);
CREATE INDEX idx_manual_purge_time ON manual_upload_attempts(purge_key_hash, accepted_at);
CREATE INDEX idx_manual_time ON manual_upload_attempts(accepted_at);

CREATE TABLE ingest_budget (
  report_day TEXT PRIMARY KEY,
  attempts INTEGER NOT NULL CHECK(attempts >= 1)
);
