# Innocent Lab telemetry server

Cloudflare Worker + D1 ingest service for privacy-minimized module-health
statistics. This directory is intentionally independent from the Android build.

## Privacy and safety invariants

- The endpoint accepts legacy schema v1 and catalog-bound capability schema v2,
  rejecting unknown or forbidden fields. Schema v2 requires disclosure version 4.
- Optional device and service-version fields require disclosure version 3. Product
  manufacturer/model labels are bounded to 64 safe characters; ROM is an enum.
  Framework version comes from the connected service, not API level or an app scan.
  Missing legacy metadata is not backfilled. Unique hardware identifiers, full build
  fingerprints, raw system properties and free-form exception text remain excluded.
- Capability IDs and parent relationships come from the Android directory projection.
  New and legacy aggregates use separate tables; incomplete/unknown capability
  observations do not become successful installations or long-term samples.
- The request body is capped at 32 KiB and is never logged or stored verbatim.
- Raw `install_id` and `purge_token` values never enter D1. The former is HMACed
  with a Worker secret and the latter is SHA-256 hashed. Purge looks up only the
  deletion-token hash, so it remains usable after an HMAC secret rotation.
- One installation contributes at most one raw snapshot per UTC day. Automatic reports
  retain daily deduplication; manual reports refresh that day's snapshot.
- Optional schema-v1 `upload_kind` defaults to `automatic` for old clients. Manual
  requests have an atomic rolling 24-hour quota of 3, indexed by both installation
  and deletion-token digests. Purging reports does not reset that quota.
- Before parsing report/purge bodies: shared per-location 300/minute ingress limiter,
  daily-HMAC source-IP limiter at 60/minute, then per-identity/mode 4/minute.
  Raw IPs are not logged or stored in D1. Bodies must finish within 5 seconds.
- A transactional global ingest budget stops report processing after 2,000 validated
  attempts per UTC day/environment (`MAX_REPORT_ATTEMPTS_PER_DAY`). This is a safety
  cutoff, not a Cloudflare billing cap; attacks can still exhaust availability.
- Quota-only records expire after 24 hours and are cleaned by the daily job
  (normally within 48 hours). Budget rows are cleaned on the next daily job.
  These keyed security records are separate from raw reports and analytics.
- Raw reports default to 30 days. Persistent aggregate cells require at least 10
  distinct installation keys and contain no installation identifier.
- `INGEST_RETIRED=1` disables ingestion with HTTP 410 but leaves purge available.

## Local checks

```powershell
npm install
npm run check
```

After Cloudflare resource IDs have been written to `wrangler.jsonc`, copy
`.dev.vars.example` to the ignored `.dev.vars`, replace the placeholder locally,
apply the migration, and start Wrangler:

```powershell
npx wrangler d1 migrations apply innocent-lab-telemetry-staging --local --env staging
npx wrangler dev --env staging
```

The test fixture is `test/fixtures/report-v1.json`. It contains only synthetic IDs.

See `OPERATIONS.md` for the current Cloudflare resource inventory, deployment,
health checks, emergency retirement, Secret rotation, and Bot Fight Mode caveat.
