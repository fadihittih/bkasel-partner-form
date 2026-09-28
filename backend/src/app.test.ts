// Integration tests against a real MySQL/MariaDB database.
// Run: DB_USER=... DB_PASSWORD=... DB_NAME=... npm test   (uses a throwaway database!)
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { createPool, migrate, type Pool } from "./db.js";
import { createApp } from "./app.js";

const TOKEN = "t".repeat(40);
let pool: Pool;
let server: Server;
let base: string;

const post = (body: unknown, token = TOKEN) =>
  fetch(`${base}/api/v1/ingest`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });

const counts = async () => {
  const r = await fetch(`${base}/api/v1/status`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return ((await r.json()) as any).counts;
};

const sleep = {
  id: "sleep-1",
  start: "2026-09-26T20:30:00Z",
  end: "2026-09-27T04:00:00.123456789Z",
  title: null,
  origin: "com.example.fitness",
  last_modified: "2026-09-27T05:00:00Z",
  stages: [
    { stage: "light", stage_code: 4, start: "2026-09-26T20:30:00Z", end: "2026-09-26T22:00:00Z" },
    { stage: "deep", stage_code: 5, start: "2026-09-26T22:00:00Z", end: "2026-09-27T01:00:00Z" },
    { stage: "rem", stage_code: 6, start: "2026-09-27T01:00:00Z", end: "2026-09-27T04:00:00Z" },
  ],
};
const hr = {
  id: "hr-1",
  start: "2026-09-27T10:00:00Z",
  end: "2026-09-27T10:02:00Z",
  origin: "com.example.fitness",
  last_modified: "2026-09-27T10:03:00Z",
  samples: [
    { time: "2026-09-27T10:00:00Z", bpm: 70 },
    { time: "2026-09-27T10:01:00Z", bpm: 72 },
    { time: "2026-09-27T10:02:00Z", bpm: 75 },
  ],
};
const steps = {
  id: "steps-1",
  start: "2026-09-27T10:00:00Z",
  end: "2026-09-27T11:00:00Z",
  count: 812,
  origin: "com.example.fitness",
  last_modified: "2026-09-27T11:00:00Z",
};

before(async () => {
  pool = createPool({
    host: process.env.DB_HOST ?? "127.0.0.1",
    port: Number(process.env.DB_PORT ?? 3306),
    user: process.env.DB_USER!,
    password: process.env.DB_PASSWORD ?? "",
    database: process.env.DB_NAME!,
  });
  for (const t of ["sleep_stages", "sleep_sessions", "heart_rate_samples", "steps", "exercise_sessions", "ingest_log"]) {
    await pool.query(`DROP TABLE IF EXISTS ${t}`);
  }
  await migrate(pool);
  await migrate(pool); // running twice must be harmless
  server = createApp(pool, TOKEN).listen(0);
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(async () => {
  server.close();
  await pool.end();
});

test("rejects missing or wrong token", async () => {
  assert.equal((await post({}, "")).status, 401);
  assert.equal((await post({}, "wrong")).status, 401);
});

test("rejects plain http behind the proxy", async () => {
  const r = await fetch(`${base}/health`, { headers: { "x-forwarded-proto": "http" } });
  assert.equal(r.status, 403);
});

test("stores a batch", async () => {
  const r = await post({ sleep_sessions: [sleep], heart_rate: [hr], steps: [steps] });
  assert.equal(r.status, 200);
  assert.deepEqual(await counts(), {
    sleep_sessions: 1, sleep_stages: 3, heart_rate_samples: 3, steps: 1, exercise_sessions: 0,
  });
  const [[row]] = (await pool.query("SELECT duration_min FROM sleep_sessions WHERE id='sleep-1'")) as any;
  assert.equal(row.duration_min, 450);
});

test("re-sending the same batch is idempotent", async () => {
  assert.equal((await post({ sleep_sessions: [sleep], heart_rate: [hr], steps: [steps] })).status, 200);
  const c = await counts();
  assert.equal(c.sleep_stages, 3);
  assert.equal(c.heart_rate_samples, 3);
  assert.equal(c.steps, 1);
});

test("an edited record replaces the old version", async () => {
  const edited = { ...hr, samples: hr.samples.slice(0, 2) };
  const moreSteps = { ...steps, count: 900 };
  assert.equal((await post({ heart_rate: [edited], steps: [moreSteps] })).status, 200);
  assert.equal((await counts()).heart_rate_samples, 2);
  const [[row]] = (await pool.query("SELECT count FROM steps WHERE id='steps-1'")) as any;
  assert.equal(row.count, 900);
});

test("deletions remove records (sleep stages cascade)", async () => {
  const r = await post({ deleted: [{ type: "sleep_sessions", id: "sleep-1" }, { type: "heart_rate", id: "hr-1" }] });
  assert.equal(r.status, 200);
  const c = await counts();
  assert.equal(c.sleep_sessions, 0);
  assert.equal(c.sleep_stages, 0);
  assert.equal(c.heart_rate_samples, 0);
});

test("invalid payload returns field names, not values", async () => {
  const r = await post({ steps: [{ ...steps, count: -5 }] });
  assert.equal(r.status, 400);
  const body = (await r.json()) as any;
  assert.deepEqual(body.fields, ["steps.0.count"]);
  assert.ok(!JSON.stringify(body).includes("-5"));
});

test("large heart-rate batch (5000 samples)", async () => {
  const t0 = Date.parse("2026-09-20T00:00:00Z");
  const big = {
    ...hr,
    id: "hr-big",
    samples: Array.from({ length: 5000 }, (_, i) => ({ time: new Date(t0 + i * 60000).toISOString(), bpm: 60 + (i % 40) })),
  };
  assert.equal((await post({ heart_rate: [big] })).status, 200);
  assert.equal((await counts()).heart_rate_samples, 5000);
});
