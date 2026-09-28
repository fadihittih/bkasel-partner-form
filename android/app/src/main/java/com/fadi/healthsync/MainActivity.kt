package com.fadi.healthsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The single screen: permissions, server settings, sync button, status, and 7-day counts. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

/** One line per permission, with what it's for. */
private val permissionHelp = listOf(
    DataType.SLEEP.readPermission to "Sleep: sessions and light/deep/REM stages",
    DataType.HEART_RATE.readPermission to "Heart rate: all-day samples",
    DataType.STEPS.readPermission to "Steps",
    DataType.EXERCISE.readPermission to "Exercise sessions (optional)",
    HealthConnectManager.PERMISSION_BACKGROUND to "Access data in background: hourly sync while the app is closed",
    HealthConnectManager.PERMISSION_HISTORY to "Access past data: import history older than 30 days",
)

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val s by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health Sync", style = MaterialTheme.typography.headlineSmall)

        when (s.sdkStatus) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                Text("Health Connect is not available on this device.")
                return@Column
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Text("Health Connect needs to be installed or updated.")
                OpenPlayStoreButton()
                return@Column
            }
        }

        // --- Permissions ---
        Section("Permissions")
        permissionHelp.forEach { (perm, label) ->
            val unsupported = (perm == HealthConnectManager.PERMISSION_BACKGROUND && !s.backgroundAvailable) ||
                (perm == HealthConnectManager.PERMISSION_HISTORY && !s.historyAvailable)
            val mark = when {
                unsupported -> "–"
                perm in s.granted -> "✓"
                else -> "✗"
            }
            Text("$mark  $label" + if (unsupported) " (not supported on this device)" else "")
        }
        Button(onClick = { permissionLauncher.launch(vm.hc.permissionsToRequest()) }) {
            Text("Grant permissions")
        }

        // --- Server ---
        Section("Server")
        ServerSettings(s.serverUrl, s.tokenSet, vm::saveServer)

        // --- Sync ---
        Section("Sync")
        Button(onClick = vm::syncNow, enabled = !s.syncing, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.syncing) "Syncing…" else "Sync now")
        }
        Text("Last sync: " + (s.lastSyncAt?.let(timeFormat::format) ?: "never"))
        s.lastResult?.let { Text("Last upload: $it") }
        Text(
            "Last error: " + (s.lastError ?: "none"),
            color = if (s.lastError != null) Color(0xFFB00020) else Color.Unspecified,
        )

        // --- Counts ---
        Section("Last 7 days in Health Connect")
        s.counts.forEach { (type, text) -> Text("$type: $text") }
        s.countsError?.let { Text(it, color = Color(0xFFB00020)) }
        OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, fontWeight = FontWeight.Bold)
}

@Composable
private fun ServerSettings(savedUrl: String, tokenSet: Boolean, onSave: (String, String) -> Unit) {
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf("") }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Server URL (https://…)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(if (tokenSet) "API token (saved — leave blank to keep)" else "API token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        Button(onClick = { onSave(url, token); token = "" }) { Text("Save") }
    }
}

@Composable
private fun OpenPlayStoreButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Button(onClick = {
        // Official deep link to install/update Health Connect.
        val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse(uri)
                putExtra("overlay", true)
                putExtra("callerId", context.packageName)
            }
        )
    }) { Text("Open Play Store") }
}
