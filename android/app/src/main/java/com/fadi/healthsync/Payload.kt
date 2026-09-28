package com.fadi.healthsync

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * JSON shapes sent to POST /api/v1/ingest. This is the contract with the backend.
 * All times are UTC ISO-8601 instants (e.g. "2026-09-27T21:14:00Z").
 * `id` is the Health Connect record ID, used by the backend for idempotent upserts.
 * `origin` is the package that wrote the record (e.g. Mi Fitness).
 */

@Serializable
data class IngestPayload(
    @SerialName("sleep_sessions") val sleepSessions: List<SleepSessionDto> = emptyList(),
    @SerialName("heart_rate") val heartRate: List<HeartRateDto> = emptyList(),
    val steps: List<StepsDto> = emptyList(),
    @SerialName("exercise_sessions") val exerciseSessions: List<ExerciseDto> = emptyList(),
    /** Records deleted in Health Connect since the last sync. */
    val deleted: List<DeletedDto> = emptyList(),
)

@Serializable
data class SleepSessionDto(
    val id: String,
    val start: String,
    val end: String,
    val title: String? = null,
    val origin: String,
    @SerialName("last_modified") val lastModified: String,
    val stages: List<SleepStageDto>,
)

@Serializable
data class SleepStageDto(
    val stage: String,
    @SerialName("stage_code") val stageCode: Int,
    val start: String,
    val end: String,
)

@Serializable
data class HeartRateDto(
    val id: String,
    val start: String,
    val end: String,
    val origin: String,
    @SerialName("last_modified") val lastModified: String,
    val samples: List<HeartRateSampleDto>,
)

@Serializable
data class HeartRateSampleDto(val time: String, val bpm: Long)

@Serializable
data class StepsDto(
    val id: String,
    val start: String,
    val end: String,
    val count: Long,
    val origin: String,
    @SerialName("last_modified") val lastModified: String,
)

@Serializable
data class ExerciseDto(
    val id: String,
    val start: String,
    val end: String,
    /** Health Connect ExerciseSessionRecord.EXERCISE_TYPE_* integer. */
    @SerialName("exercise_type") val exerciseType: Int,
    val title: String? = null,
    val origin: String,
    @SerialName("last_modified") val lastModified: String,
)

@Serializable
data class DeletedDto(val type: String, val id: String)

// --- Mapping from Health Connect records ---

private fun stageName(code: Int): String = when (code) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    else -> "unknown"
}

fun SleepSessionRecord.toDto() = SleepSessionDto(
    id = metadata.id,
    start = startTime.toString(),
    end = endTime.toString(),
    title = title,
    origin = metadata.dataOrigin.packageName,
    lastModified = metadata.lastModifiedTime.toString(),
    stages = stages.map {
        SleepStageDto(stageName(it.stage), it.stage, it.startTime.toString(), it.endTime.toString())
    },
)

fun HeartRateRecord.toDto() = HeartRateDto(
    id = metadata.id,
    start = startTime.toString(),
    end = endTime.toString(),
    origin = metadata.dataOrigin.packageName,
    lastModified = metadata.lastModifiedTime.toString(),
    samples = samples.map { HeartRateSampleDto(it.time.toString(), it.beatsPerMinute) },
)

fun StepsRecord.toDto() = StepsDto(
    id = metadata.id,
    start = startTime.toString(),
    end = endTime.toString(),
    count = count,
    origin = metadata.dataOrigin.packageName,
    lastModified = metadata.lastModifiedTime.toString(),
)

fun ExerciseSessionRecord.toDto() = ExerciseDto(
    id = metadata.id,
    start = startTime.toString(),
    end = endTime.toString(),
    exerciseType = exerciseType,
    title = title,
    origin = metadata.dataOrigin.packageName,
    lastModified = metadata.lastModifiedTime.toString(),
)
