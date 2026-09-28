package com.fadi.healthsync

import android.content.Context
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * One sync run. Per data type:
 *  1. First run (no changes token): reserve a token, then backfill history in 7-day
 *     windows. Progress is saved after each window, so a killed run resumes.
 *  2. Later runs: ask Health Connect only for what changed since the token
 *     (inserts, edits, deletions) and upload that.
 * The token is advanced only after the server accepted the batch, so nothing is lost;
 * a repeated upload is harmless because the backend upserts by record ID.
 */
class SyncEngine(context: Context) {
    private val hc = HealthConnectManager(context)
    private val prefs = Prefs(context)

    suspend fun run() = lock.withLock {
        try {
            val granted = hc.grantedPermissions()
            val types = DataType.entries.filter { it.readPermission in granted }
            if (types.none { it.required }) {
                throw IllegalStateException("No Health Connect read permissions granted")
            }
            val client = BackendClient(prefs.serverUrl, prefs.apiToken)
            val history = HealthConnectManager.PERMISSION_HISTORY in granted

            val sent = types.map { type -> "${type.key}: ${syncType(type, client, history)}" }
            prefs.lastSyncAt = Instant.now()
            prefs.lastResult = sent.joinToString(", ")
            prefs.lastError = null
        } catch (e: Exception) {
            prefs.lastError = describe(e)
            throw e
        }
    }

    private suspend fun syncType(type: DataType, client: BackendClient, history: Boolean): Int {
        val token = prefs.changesToken(type) ?: return backfill(type, client, history)
        return applyChanges(type, token, client, history)
    }

    private suspend fun backfill(type: DataType, client: BackendClient, history: Boolean): Int {
        // Reserve the token BEFORE reading, so edits made during the backfill are caught later.
        val pending = prefs.pendingToken(type)
            ?: hc.client.getChangesToken(ChangesTokenRequest(setOf(type.recordClass)))
                .also { prefs.setPendingToken(type, it) }

        val now = Instant.now()
        val days = if (history) HISTORY_BACKFILL_DAYS else DEFAULT_BACKFILL_DAYS
        val oldest = now.minus(Duration.ofDays(days))
        var cursor = prefs.backfillCursor(type)?.coerceAtLeast(oldest) ?: oldest
        var count = 0
        while (cursor < now) {
            val windowEnd = minOf(cursor.plus(WINDOW), now)
            count += upload(type, hc.readType(type, cursor, windowEnd), emptyList(), client)
            cursor = windowEnd
            prefs.setBackfillCursor(type, cursor)
        }
        prefs.setChangesToken(type, pending)
        prefs.setPendingToken(type, null)
        prefs.setBackfillCursor(type, null)
        return count
    }

    private suspend fun applyChanges(type: DataType, token: String, client: BackendClient, history: Boolean): Int {
        var next = token
        var count = 0
        do {
            val response = hc.client.getChanges(next)
            if (response.changesTokenExpired) {
                // Tokens expire after ~30 days unused. Re-read from shortly before the last sync.
                prefs.setChangesToken(type, null)
                prefs.setBackfillCursor(type, prefs.lastSyncAt?.minus(Duration.ofDays(1)))
                return count + backfill(type, client, history)
            }
            val upserts = response.changes.filterIsInstance<UpsertionChange>().map { it.record }
            val deletions = response.changes.filterIsInstance<DeletionChange>()
                .map { DeletedDto(type.key, it.recordId) }
            count += upload(type, upserts, deletions, client)
            next = response.nextChangesToken
            prefs.setChangesToken(type, next) // saved per page, after the server accepted it
        } while (response.hasMore)
        return count
    }

    /** Converts records to JSON DTOs and uploads them in size-limited batches. */
    private suspend fun upload(
        type: DataType,
        records: List<Record>,
        deletions: List<DeletedDto>,
        client: BackendClient,
    ): Int {
        if (deletions.isNotEmpty()) client.ingest(IngestPayload(deleted = deletions))
        when (type) {
            DataType.SLEEP -> records.filterIsInstance<SleepSessionRecord>().map { it.toDto() }
                .chunked(200).forEach { client.ingest(IngestPayload(sleepSessions = it)) }
            DataType.STEPS -> records.filterIsInstance<StepsRecord>().map { it.toDto() }
                .chunked(1000).forEach { client.ingest(IngestPayload(steps = it)) }
            DataType.EXERCISE -> records.filterIsInstance<ExerciseSessionRecord>().map { it.toDto() }
                .chunked(200).forEach { client.ingest(IngestPayload(exerciseSessions = it)) }
            DataType.HEART_RATE -> {
                // Heart-rate records can hold many samples, so batch by sample count.
                val batch = mutableListOf<HeartRateDto>()
                var samples = 0
                for (dto in records.filterIsInstance<HeartRateRecord>().map { it.toDto() }) {
                    batch += dto
                    samples += dto.samples.size
                    if (samples >= MAX_HR_SAMPLES_PER_POST) {
                        client.ingest(IngestPayload(heartRate = batch.toList()))
                        batch.clear()
                        samples = 0
                    }
                }
                if (batch.isNotEmpty()) client.ingest(IngestPayload(heartRate = batch))
            }
        }
        return records.size + deletions.size
    }

    companion object {
        /** Only one sync at a time (manual and periodic share state). */
        private val lock = Mutex()

        /** Health Connect only allows ~30 days back without the history permission. */
        const val DEFAULT_BACKFILL_DAYS = 30L
        const val HISTORY_BACKFILL_DAYS = 365L
        private val WINDOW: Duration = Duration.ofDays(7)
        private const val MAX_HR_SAMPLES_PER_POST = 5000

        /** Short, data-free error text for the UI. */
        fun describe(e: Throwable): String = when (e) {
            is SecurityException ->
                "Health Connect refused the read. Check permissions; background sync also " +
                    "needs the 'Access data in background' permission."
            is IllegalStateException -> e.message ?: "Configuration error"
            is java.io.IOException -> "Network: ${e.message ?: e.javaClass.simpleName}"
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
    }
}
