package com.fadi.healthsync

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Everything the screen shows. */
data class UiState(
    val sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val granted: Set<String> = emptySet(),
    val backgroundAvailable: Boolean = false,
    val historyAvailable: Boolean = false,
    val counts: List<Pair<String, String>> = emptyList(),
    val countsError: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Instant? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val serverUrl: String = "",
    val tokenSet: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val hc = HealthConnectManager(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
        // Watch the "Sync now" job so the button shows progress and the status updates after.
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.MANUAL_WORK)
                .collect { infos ->
                    val running = infos.any { !it.state.isFinished }
                    val wasRunning = _state.value.syncing
                    _state.update { it.copy(syncing = running) }
                    if (wasRunning && !running) refresh()
                    if (infos.any { it.state == WorkInfo.State.FAILED }) loadStatus()
                }
        }
    }

    /** Reload permissions, 7-day counts and sync status. */
    fun refresh() {
        loadStatus()
        val status = hc.sdkStatus()
        _state.update { it.copy(sdkStatus = status) }
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        viewModelScope.launch {
            try {
                val granted = hc.grantedPermissions()
                _state.update {
                    it.copy(
                        granted = granted,
                        backgroundAvailable = hc.backgroundReadAvailable(),
                        historyAvailable = hc.historyReadAvailable(),
                    )
                }
                val counts = hc.countsLast7Days(granted)
                _state.update { it.copy(counts = counts, countsError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(countsError = SyncEngine.describe(e)) }
            }
        }
    }

    private fun loadStatus() {
        _state.update {
            it.copy(
                lastSyncAt = prefs.lastSyncAt,
                lastResult = prefs.lastResult,
                lastError = prefs.lastError,
                serverUrl = prefs.serverUrl,
                tokenSet = prefs.apiToken.isNotBlank(),
            )
        }
    }

    fun saveServer(url: String, token: String) {
        prefs.serverUrl = url
        if (token.isNotBlank()) prefs.apiToken = token // blank = keep the existing token
        loadStatus()
    }

    fun syncNow() {
        _state.update { it.copy(syncing = true) }
        SyncWorker.syncNow(getApplication())
    }
}
