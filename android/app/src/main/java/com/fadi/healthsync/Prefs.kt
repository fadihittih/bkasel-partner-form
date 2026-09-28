package com.fadi.healthsync

import android.content.Context
import java.time.Instant

/**
 * Small wrapper around the app's private SharedPreferences.
 * Stores server settings, sync status, and per-type Health Connect sync state.
 * Only metadata lives here - never health values.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("healthsync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim()).apply()

    var apiToken: String
        get() = sp.getString("api_token", "") ?: ""
        set(v) = sp.edit().putString("api_token", v.trim()).apply()

    var lastSyncAt: Instant?
        get() = sp.getLong("last_sync_at", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(v) = sp.edit().putLong("last_sync_at", v?.toEpochMilli() ?: 0L).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    /** Human-readable summary of what the last successful sync uploaded, e.g. "steps: 12". */
    var lastResult: String?
        get() = sp.getString("last_result", null)
        set(v) = sp.edit().putString("last_result", v).apply()

    // --- Per data type sync state ---

    /** Active Health Connect changes token (set once the initial backfill finished). */
    fun changesToken(t: DataType): String? = sp.getString("token_${t.key}", null)
    fun setChangesToken(t: DataType, v: String?) = sp.edit().putString("token_${t.key}", v).apply()

    /** Token reserved at the start of a backfill; promoted to changesToken when it completes. */
    fun pendingToken(t: DataType): String? = sp.getString("pending_${t.key}", null)
    fun setPendingToken(t: DataType, v: String?) = sp.edit().putString("pending_${t.key}", v).apply()

    /** How far an in-progress backfill got, so a killed worker resumes instead of restarting. */
    fun backfillCursor(t: DataType): Instant? =
        sp.getLong("cursor_${t.key}", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    fun setBackfillCursor(t: DataType, v: Instant?) =
        sp.edit().putLong("cursor_${t.key}", v?.toEpochMilli() ?: 0L).apply()
}
