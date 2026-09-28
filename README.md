# Personal health data pipeline

```
Xiaomi Smart Band 10 → Mi Fitness → Android Health Connect → Health Sync (Android app)
  → backend (HTTPS + bearer token) → remote MCP server → Claude (custom connector)
```

Single-user, personal project.

| Step | What | Status |
|------|------|--------|
| 1 | Android app: read sleep/HR/steps/exercise, 7-day counts, manual sync | ✅ `android/` |
| 3 | Background hourly sync with Health Connect changes tokens | ✅ `android/` (built together with step 1) |
| – | Mock ingest endpoint for testing the app | ✅ `mock-server/` |
| 2 | Backend storage + push endpoint + deployment | next |
| 4 | MCP server (Streamable HTTP) + Claude connector | after that |

## Repository layout

```
android/                         Android app (Kotlin, Jetpack Compose)
  app/src/main/AndroidManifest.xml   permissions, privacy screen hooks for Health Connect
  app/src/main/java/com/fadi/healthsync/
    HealthSyncApp.kt             Application: schedules the hourly sync
    DataType.kt                  the 4 record types + their read permissions
    HealthConnectManager.kt      Health Connect availability, permissions, paginated reads, 7-day counts
    Payload.kt                   JSON sent to the backend + mapping from Health Connect records
    BackendClient.kt             HTTPS POST with bearer token (refuses plain http)
    SyncEngine.kt                backfill + changes-token logic, batching
    SyncWorker.kt                WorkManager job (hourly + "Sync now")
    Prefs.kt                     settings and sync state (no health values)
    MainViewModel.kt             screen state
    MainActivity.kt              the single screen
    PermissionsRationaleActivity.kt  privacy policy screen Health Connect requires
  app/src/main/res/xml/network_security_config.xml  HTTPS only (except 127.0.0.1 for adb testing)
mock-server/mock_server.py       fake /api/v1/ingest that prints counts only
.github/workflows/android.yml    builds the APK on GitHub (download from the run's Artifacts)
```

## Permissions the app asks for

| Permission | Why |
|---|---|
| `READ_SLEEP` | sleep sessions with light/deep/REM stages |
| `READ_HEART_RATE` | all-day heart-rate samples |
| `READ_STEPS` | step counts |
| `READ_EXERCISE` | exercise sessions. Optional: if Mi Fitness doesn't write them, the app just reports "none found" |
| `READ_HEALTH_DATA_IN_BACKGROUND` | lets the hourly sync read while the app is closed. Only requested if the device's Health Connect supports it (feature check). Without it, background runs fail with a clear error and "Sync now" (app open) still works |
| `READ_HEALTH_DATA_HISTORY` | read data older than 30 days before your first grant. With it, the first sync imports 365 days; without it, 30 days |
| `INTERNET` | upload to your server |

## How sync works

For each type, separately (Health Connect recommends one changes token per type):

1. **First sync:** reserve a changes token, then read history in 7-day windows and upload each window.
   Progress is saved after every window, so if Android kills the job it resumes where it stopped.
2. **Every later sync:** `getChanges(token)` returns only inserted, updated, or deleted records since last time.
   They're uploaded, then the token is advanced. The token only moves after the server returns 200.
3. **Expired token** (unused for ~30 days): re-read from 1 day before the last successful sync.

Uploads are batched (≤5000 heart-rate samples per request). The backend must upsert by record `id`,
because a batch can be re-sent after a failure.

### Upload format: `POST {server}/api/v1/ingest`

Header: `Authorization: Bearer <token>`. Times are UTC ISO-8601.

```json
{
  "sleep_sessions": [{"id": "...", "start": "...", "end": "...", "title": null, "origin": "<package>",
                      "last_modified": "...",
                      "stages": [{"stage": "deep", "stage_code": 5, "start": "...", "end": "..."}]}],
  "heart_rate": [{"id": "...", "start": "...", "end": "...", "origin": "...", "last_modified": "...",
                  "samples": [{"time": "...", "bpm": 62}]}],
  "steps": [{"id": "...", "start": "...", "end": "...", "count": 812, "origin": "...", "last_modified": "..."}],
  "exercise_sessions": [{"id": "...", "start": "...", "end": "...", "exercise_type": 56, "title": null,
                         "origin": "...", "last_modified": "..."}],
  "deleted": [{"type": "steps", "id": "..."}]
}
```

Stage names come from Health Connect's `SleepSessionRecord.STAGE_TYPE_*` constants (awake, sleeping, out_of_bed,
light, deep, rem). Any other code is sent as `"unknown"` with its `stage_code`.
`origin` is the package that wrote the record, useful for de-duplicating steps if your phone also counts steps.

## Testing step 1 (install and sync to the mock server)

1. **Install the APK** from the GitHub Actions run (Actions → "Android APK" → Artifacts), or the file shared in chat.
   Allow "install unknown apps" when prompted.
2. **Open Health Sync → Grant permissions.** Allow everything. The screen shows ✓ per permission and the
   7-day counts per type. Compare them with the Health Connect app.
3. **Run the mock server** on your laptop:
   ```bash
   cd mock-server
   export MOCK_TOKEN=$(openssl rand -hex 24); echo $MOCK_TOKEN
   python3 mock_server.py
   ```
4. **Connect the phone to it.** Pick one:
   - **USB (simplest):** `adb reverse tcp:8787 tcp:8787`, then in the app set
     Server URL `http://127.0.0.1:8787` (plain HTTP is allowed only to 127.0.0.1).
   - **HTTPS tunnel (no cable):** `cloudflared tunnel --url http://localhost:8787` prints an
     `https://….trycloudflare.com` URL. Use that as the Server URL.
5. Paste the token, press **Save**, then **Sync now**. The mock server prints counts per batch, for example
   `batch {'heart_rate': 120, 'heart_rate_samples': 4300}`. The app shows last sync time, what was uploaded, and any error.
6. Press **Sync now** again. Only changes since the last sync are sent, usually a small batch or nothing.

Background sync runs about every 60 minutes when there's network. Android may delay it (Doze, battery saver).

## Building yourself

Android Studio: open the `android/` folder and run. CLI: `cd android && ./gradlew assembleDebug`.
Settings (server URL, token) are entered in the app, not at build time, so no secrets are in the repo.

> This repository is **public**. Never commit tokens, `.env` files, keystores or data exports.
> Consider making it private in GitHub settings.
