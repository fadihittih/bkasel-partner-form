#!/usr/bin/env python3
"""
Tiny mock of the backend's ingest endpoint, for testing the Android app.

- POST /api/v1/ingest with "Authorization: Bearer <MOCK_TOKEN>"
- Prints only COUNTS per type and the source app names - never health values.
- Python standard library only.

Run:  MOCK_TOKEN=some-long-secret python3 mock_server.py      (listens on :8787)
"""
import hmac
import json
import os
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("MOCK_TOKEN", "")
PORT = int(os.environ.get("PORT", "8787"))
totals = Counter()


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/api/v1/ingest":
            return self._reply(404, {"error": "not found"})
        auth = self.headers.get("Authorization", "")
        if not TOKEN or not hmac.compare_digest(auth, f"Bearer {TOKEN}"):
            print("rejected: bad or missing token")
            return self._reply(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length", "0"))
        try:
            data = json.loads(self.rfile.read(length))
        except ValueError:
            return self._reply(400, {"error": "invalid json"})

        batch = Counter()
        origins = set()
        for key in ("sleep_sessions", "heart_rate", "steps", "exercise_sessions", "deleted"):
            items = data.get(key, [])
            batch[key] += len(items)
            for item in items:
                if key == "heart_rate":
                    batch["heart_rate_samples"] += len(item.get("samples", []))
                if key == "sleep_sessions":
                    batch["sleep_stages"] += len(item.get("stages", []))
                if "origin" in item:
                    origins.add(item["origin"])
        totals.update(batch)
        print(f"batch {dict(+batch)}  origins={sorted(origins)}  totals={dict(totals)}")
        return self._reply(200, {"ok": True})

    def log_message(self, *args):
        pass  # silence default access log


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("Set MOCK_TOKEN first, e.g. MOCK_TOKEN=$(openssl rand -hex 24)")
    print(f"Mock ingest server on http://127.0.0.1:{PORT}/api/v1/ingest")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
