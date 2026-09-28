package com.fadi.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Everything that talks to Health Connect: availability, permissions, and reads.
 */
class HealthConnectManager(private val context: Context) {

    /** SDK_AVAILABLE, SDK_UNAVAILABLE or SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** Background/history reads are separate features that older Health Connect versions lack. */
    fun isFeatureAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun backgroundReadAvailable() =
        isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    fun historyReadAvailable() =
        isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    /** All permissions we ask for. Background/history only when the device supports them. */
    fun permissionsToRequest(): Set<String> = buildSet {
        DataType.entries.forEach { add(it.readPermission) }
        if (backgroundReadAvailable()) add(PERMISSION_BACKGROUND)
        if (historyReadAvailable()) add(PERMISSION_HISTORY)
    }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Reads every record of type T in [start, end), following pagination. */
    suspend inline fun <reified T : Record> readAll(start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                )
            )
            out += response.records
            // Some Health Connect versions return "" instead of null on the last page.
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    /** Reads records of one DataType (used by the sync engine). */
    suspend fun readType(type: DataType, start: Instant, end: Instant): List<Record> = when (type) {
        DataType.SLEEP -> readAll<SleepSessionRecord>(start, end)
        DataType.HEART_RATE -> readAll<HeartRateRecord>(start, end)
        DataType.STEPS -> readAll<StepsRecord>(start, end)
        DataType.EXERCISE -> readAll<ExerciseSessionRecord>(start, end)
    }

    /** Counts per type for the last 7 days, shown on the main screen. Skips ungranted types. */
    suspend fun countsLast7Days(granted: Set<String>): List<Pair<String, String>> {
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        val rows = mutableListOf<Pair<String, String>>()
        for (type in DataType.entries) {
            if (type.readPermission !in granted) {
                rows += type.key to "no permission"
                continue
            }
            val text = when (type) {
                DataType.SLEEP -> {
                    val s = readAll<SleepSessionRecord>(start, end)
                    "${s.size} sessions, ${s.sumOf { it.stages.size }} stages"
                }
                DataType.HEART_RATE -> {
                    val r = readAll<HeartRateRecord>(start, end)
                    "${r.size} records, ${r.sumOf { it.samples.size }} samples"
                }
                DataType.STEPS -> {
                    val r = readAll<StepsRecord>(start, end)
                    "${r.size} records, ${r.sumOf { it.count }} steps"
                }
                DataType.EXERCISE -> {
                    val r = readAll<ExerciseSessionRecord>(start, end)
                    if (r.isEmpty()) "none found (optional)" else "${r.size} sessions"
                }
            }
            rows += type.key to text
        }
        return rows
    }

    companion object {
        val PERMISSION_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        val PERMISSION_HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    }
}
