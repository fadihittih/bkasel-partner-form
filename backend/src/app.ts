// Express app: routes and middleware. Kept separate from server.ts so tests can use it.
import express from "express";
import { requireBearer } from "./auth.js";
import type { Pool } from "./db.js";
import { IngestPayload, ingest } from "./ingest.js";

export function createApp(pool: Pool, apiToken: string) {
  const app = express();
  app.disable("x-powered-by");

  // Refuse requests that reached the proxy over plain HTTP (Hostinger terminates TLS and
  // forwards the original scheme). Also turn on "Force HTTPS" for the domain in hPanel.
  app.use((req, res, next) => {
    if (req.get("x-forwarded-proto") === "http") {
      res.status(403).json({ error: "https required" });
      return;
    }
    next();
  });

  // Public, no data.
  app.get("/health", (_req, res) => {
    res.json({ ok: true });
  });

  const auth = requireBearer(apiToken);

  // Upload endpoint for the Android app.
  app.post("/api/v1/ingest", auth, express.json({ limit: "20mb" }), async (req, res) => {
    const parsed = IngestPayload.safeParse(req.body);
    if (!parsed.success) {
      // Report which fields failed, never their values.
      const where = parsed.error.issues.slice(0, 5).map((i) => i.path.join("."));
      res.status(400).json({ error: "invalid payload", fields: where });
      return;
    }
    const summary = await ingest(pool, parsed.data);
    console.log(`ingest ok: ${summary}`);
    res.json({ ok: true });
  });

  // Row counts + last uploads, to check that syncing works.
  app.get("/api/v1/status", auth, async (_req, res) => {
    const [[counts]] = (await pool.query(
      `SELECT (SELECT COUNT(*) FROM sleep_sessions) AS sleep_sessions,
              (SELECT COUNT(*) FROM sleep_stages) AS sleep_stages,
              (SELECT COUNT(*) FROM heart_rate_samples) AS heart_rate_samples,
              (SELECT COUNT(*) FROM steps) AS steps,
              (SELECT COUNT(*) FROM exercise_sessions) AS exercise_sessions`,
    )) as any;
    const [recent] = await pool.query(
      "SELECT received_at, summary FROM ingest_log ORDER BY id DESC LIMIT 5",
    );
    res.json({ counts, recent_uploads: recent });
  });

  // Errors: log the type only (no request bodies), return a generic message.
  app.use((err: any, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    if (err?.type === "entity.parse.failed") {
      res.status(400).json({ error: "invalid json" });
      return;
    }
    if (err?.type === "entity.too.large") {
      res.status(413).json({ error: "payload too large" });
      return;
    }
    console.error(`request failed: ${err?.code ?? err?.name ?? "error"}`);
    res.status(500).json({ error: "internal error" });
  });

  return app;
}
