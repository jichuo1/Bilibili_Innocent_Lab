-- Unknown purge tokens must not trigger a full scan of every retained report.
CREATE INDEX IF NOT EXISTS idx_reports_purge_key ON reports(purge_key_hash);
