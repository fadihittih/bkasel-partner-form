// Bearer-token check shared by all protected routes.
import { createHash, timingSafeEqual } from "node:crypto";
import type { RequestHandler } from "express";

const digest = (s: string) => createHash("sha256").update(s).digest();

/** Constant-time comparison (hashing first makes lengths equal). */
export function tokenMatches(given: string, expected: string): boolean {
  return timingSafeEqual(digest(given), digest(expected));
}

export function requireBearer(expected: string): RequestHandler {
  return (req, res, next) => {
    const header = req.get("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (!token || !tokenMatches(token, expected)) {
      res.status(401).json({ error: "unauthorized" });
      return;
    }
    next();
  };
}
