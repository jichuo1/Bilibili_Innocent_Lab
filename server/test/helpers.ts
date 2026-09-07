import type { Env, RateLimiter } from "../src/types";

export const TEST_HMAC_KEY = "test-only-key-with-at-least-thirty-two-bytes";

export function validReport(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: 1,
    client_report_id: "7f3c1e2a-9b44-4c8e-a1d2-0f5e6a7b8c9d",
    install_id: "3e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
    purge_token: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
    module: { version_code: 14, channel: "stable" },
    host: { version_code: 91100 },
    runtime: {
      framework: "lsposed",
      framework_api: 102,
      android_sdk: 33,
      abi: "arm64-v8a",
      delivery_channel: "standard",
    },
    adaptation: {
      schema_version: 54,
      rule_version: 49,
      cache_status: "file_hit",
      duration_bucket: "lt_1s",
      dex_assist_used: "unknown",
      bootstrap: { resolved: 58, installed: 56, missing: 2, failed: 0 },
    },
    features: [
      { id: "comment_filter", state: "installed", hook_count: 3, observed: 40, applied: 1 },
      { id: "block_app_update", state: "not_applicable", reason_code: "NOT_APPLICABLE" },
    ],
    ...overrides,
  };
}

export class FakeRateLimiter implements RateLimiter {
  calls: string[] = [];
  success = true;

  async limit(input: { key: string }): Promise<{ success: boolean }> {
    this.calls.push(input.key);
    return { success: this.success };
  }
}

class FakeStatement {
  readonly db: FakeD1;
  readonly query: string;
  params: unknown[] = [];

  constructor(db: FakeD1, query: string) {
    this.db = db;
    this.query = query;
  }

  bind(...values: unknown[]): FakeStatement {
    this.params = values;
    return this;
  }

  async run(): Promise<D1Result> {
    this.db.runs.push({ query: this.query, params: this.params });
    return { success: true, meta: { changes: this.db.nextChanges } } as D1Result;
  }
}

export class FakeD1 {
  runs: Array<{ query: string; params: unknown[] }> = [];
  batches: Array<Array<{ query: string; params: unknown[] }>> = [];
  nextChanges = 0;

  prepare(query: string): D1PreparedStatement {
    return new FakeStatement(this, query) as unknown as D1PreparedStatement;
  }

  async batch(statements: D1PreparedStatement[]): Promise<D1Result[]> {
    this.batches.push(
      statements.map((statement) => {
        const fake = statement as unknown as FakeStatement;
        return { query: fake.query, params: fake.params };
      }),
    );
    return statements.map(() => ({ success: true, meta: { changes: 1 } } as D1Result));
  }
}

export function fakeEnv(): Env & {
  DB: D1Database & FakeD1;
  EDGE_LIMITER: FakeRateLimiter;
  INSTALL_LIMITER: FakeRateLimiter;
  SOURCE_LIMITER: FakeRateLimiter;
} {
  const db = new FakeD1();
  return {
    DB: db as unknown as D1Database & FakeD1,
    EDGE_LIMITER: new FakeRateLimiter(),
    INSTALL_LIMITER: new FakeRateLimiter(),
    SOURCE_LIMITER: new FakeRateLimiter(),
    ID_HMAC_KEY: TEST_HMAC_KEY,
  };
}
