package com.fadi.healthsync

import android.app.Application

/** Application entry point: makes sure the hourly background sync is scheduled. */
class HealthSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
    }
}
