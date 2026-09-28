package com.fadi.healthsync

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlin.reflect.KClass

/** The Health Connect record types this app syncs. `key` is also the JSON field name. */
enum class DataType(val key: String, val recordClass: KClass<out Record>, val required: Boolean) {
    SLEEP("sleep_sessions", SleepSessionRecord::class, required = true),
    HEART_RATE("heart_rate", HeartRateRecord::class, required = true),
    STEPS("steps", StepsRecord::class, required = true),
    // Not yet verified that Mi Fitness writes these, so it is optional.
    EXERCISE("exercise_sessions", ExerciseSessionRecord::class, required = false);

    val readPermission: String get() = HealthPermission.getReadPermission(recordClass)
}
