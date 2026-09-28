// Validates an upload from the Android app and upserts it into MySQL.
// Idempotent: re-sending the same records (same Health Connect IDs) just overwrites them.
import { z } from "zod";
import type { PoolConnection } from "mysql2/promise";
import type { Pool } from "./db.js";

// ---------- Request schema (mirrors android/.../Payload.kt) ----------

const time = z.iso.datetime({ offset: true }).transform((s) => new Date(s));
const id = z.string().min(1).max(64);
const origin = z.string().max(255);

const SleepSession = z.object({
  id,
  start: time,
  end: time,
  title: z.string().max(255).nullish(),
  origin,
  last_modified: time,
  stages: z.array(
    z.object({ stage: z.string().max(16), stage_code: z.number().int(), start: time, end: time }),
  ),
});

const HeartRate = z.object({
  id,
  start: time,
  end: time,
  origin,
  last_modified: time,
  samples: z.array(z.object({ time, bpm: z.number().int().min(0).max(400) })),
});

const Steps = z.object({ id, start: time, end: time, count: z.number().int().min(0), origin, last_modified: time });

const Exercise = z.object({
  id,
  start: time,
  end: time,
  exercise_type: z.number().int(),
  title: z.string().max(255).nullish(),
  origin,
  last_modified: time,
});

export const IngestPayload = z.object({
  sleep_sessions: z.array(SleepSession).default([]),
  heart_rate: z.array(HeartRate).default([]),
  steps: z.array(Steps).default([]),
  exercise_sessions: z.array(Exercise).default([]),
  deleted: z
    .array(z.object({ type: z.enum(["sleep_sessions", "heart_rate", "steps", "exercise_sessions"]), id }))
    .default([]),
});
export type IngestPayload = z.infer<typeof IngestPayload>;

// ---------- Storage ----------

/** Inserts rows in chunks to keep each statement a reasonable size. */
async function bulk(conn: PoolConnection, sql: string, rows: unknown[][], chunk = 1000) {
  for (let i = 0; i < rows.length; i += chunk) {
    await conn.query(sql, [rows.slice(i, i + chunk)]);
  }
}

const minutes = (a: Date, b: Date) => Math.round((b.getTime() - a.getTime()) / 60000);

export async function ingest(pool: Pool, p: IngestPayload) {
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    // Sleep: upsert the session, then replace its stages.
    if (p.sleep_sessions.length) {
      await bulk(
        conn,
        `INSERT INTO sleep_sessions (id, start_time, end_time, duration_min, title, origin, last_modified)
         VALUES ? ON DUPLICATE KEY UPDATE start_time=VALUES(start_time), end_time=VALUES(end_time),
           duration_min=VALUES(duration_min), title=VALUES(title), origin=VALUES(origin),
           last_modified=VALUES(last_modified)`,
        p.sleep_sessions.map((s) => [s.id, s.start, s.end, minutes(s.start, s.end), s.title ?? null, s.origin, s.last_modified]),
      );
      await conn.query("DELETE FROM sleep_stages WHERE session_id IN (?)", [p.sleep_sessions.map((s) => s.id)]);
      const stages = p.sleep_sessions.flatMap((s) =>
        s.stages.map((st) => [s.id, st.stage, st.stage_code, st.start, st.end]),
      );
      if (stages.length) {
        await bulk(conn, "INSERT IGNORE INTO sleep_stages (session_id, stage_type, stage_code, start_time, end_time) VALUES ?", stages);
      }
    }

    // Heart rate: replace all samples of each record (records can be edited in Health Connect).
    if (p.heart_rate.length) {
      await conn.query("DELETE FROM heart_rate_samples WHERE record_id IN (?)", [p.heart_rate.map((r) => r.id)]);
      const samples = p.heart_rate.flatMap((r) => r.samples.map((s) => [r.id, s.time, s.bpm, r.origin]));
      if (samples.length) {
        await bulk(conn, "INSERT IGNORE INTO heart_rate_samples (record_id, sample_time, bpm, origin) VALUES ?", samples);
      }
    }

    if (p.steps.length) {
      await bulk(
        conn,
        `INSERT INTO steps (id, start_time, end_time, count, origin, last_modified) VALUES ?
         ON DUPLICATE KEY UPDATE start_time=VALUES(start_time), end_time=VALUES(end_time),
           count=VALUES(count), origin=VALUES(origin), last_modified=VALUES(last_modified)`,
        p.steps.map((s) => [s.id, s.start, s.end, s.count, s.origin, s.last_modified]),
      );
    }

    if (p.exercise_sessions.length) {
      await bulk(
        conn,
        `INSERT INTO exercise_sessions (id, start_time, end_time, exercise_type, title, origin, last_modified) VALUES ?
         ON DUPLICATE KEY UPDATE start_time=VALUES(start_time), end_time=VALUES(end_time),
           exercise_type=VALUES(exercise_type), title=VALUES(title), origin=VALUES(origin),
           last_modified=VALUES(last_modified)`,
        p.exercise_sessions.map((e) => [e.id, e.start, e.end, e.exercise_type, e.title ?? null, e.origin, e.last_modified]),
      );
    }

    // Records deleted in Health Connect.
    const deleteSql = {
      sleep_sessions: "DELETE FROM sleep_sessions WHERE id IN (?)", // stages cascade
      heart_rate: "DELETE FROM heart_rate_samples WHERE record_id IN (?)",
      steps: "DELETE FROM steps WHERE id IN (?)",
      exercise_sessions: "DELETE FROM exercise_sessions WHERE id IN (?)",
    } as const;
    for (const type of Object.keys(deleteSql) as (keyof typeof deleteSql)[]) {
      const ids = p.deleted.filter((d) => d.type === type).map((d) => d.id);
      if (ids.length) await conn.query(deleteSql[type], [ids]);
    }

    const summary = summarize(p);
    await conn.query("INSERT INTO ingest_log (received_at, summary) VALUES (?, ?)", [new Date(), summary]);
    await conn.commit();
    return summary;
  } catch (e) {
    await conn.rollback();
    throw e;
  } finally {
    conn.release();
  }
}

/** Counts only - safe to log. */
export function summarize(p: IngestPayload): string {
  const hrSamples = p.heart_rate.reduce((n, r) => n + r.samples.length, 0);
  return [
    `sleep=${p.sleep_sessions.length}`,
    `hr_records=${p.heart_rate.length}`,
    `hr_samples=${hrSamples}`,
    `steps=${p.steps.length}`,
    `exercise=${p.exercise_sessions.length}`,
    `deleted=${p.deleted.length}`,
  ].join(" ");
}
