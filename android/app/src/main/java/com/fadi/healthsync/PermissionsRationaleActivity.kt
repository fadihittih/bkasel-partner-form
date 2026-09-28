package com.fadi.healthsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Privacy policy screen. Health Connect requires an app to have one and opens it
 * from its permission screen ("Read privacy policy").
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text("Privacy", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Health Sync is a personal app. It reads your sleep, heart rate, steps " +
                                "and exercise sessions from Health Connect and uploads them over HTTPS " +
                                "only to the server you configure, authenticated with your own token. " +
                                "No data is shared with anyone else, and no health values are logged.",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
