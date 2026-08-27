package org.openflight.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.openflight.mobile.stream.ShotFeedState
import org.openflight.mobile.stream.ShotFeedStatus

@Composable
fun OpenFlightApp(
    state: StateFlow<ShotFeedState>,
    onRequestNetworkPermission: () -> Unit,
) {
    val feed by state.collectAsState()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("OpenFlight", style = MaterialTheme.typography.headlineLarge)
                Status(feed.status)

                val shot = feed.latestShot
                if (shot == null) {
                    Text("Waiting for a shot from the mock server…")
                } else {
                    Text(shot.displayClub, style = MaterialTheme.typography.titleLarge)
                    Text("${shot.ballSpeedMph} mph ball speed")
                    Text("${shot.estimatedCarryYards.toInt()} yd estimated carry")
                    Text("Mobile schema V${shot.schemaVersion}")
                }

                if (feed.status == ShotFeedStatus.PermissionRequired) {
                    Button(onClick = onRequestNetworkPermission) {
                        Text("Allow local network")
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(status: ShotFeedStatus) {
    val message = when (status) {
        ShotFeedStatus.Idle -> "Not connected"
        ShotFeedStatus.PermissionRequired -> "Local network access is required"
        ShotFeedStatus.Connecting -> "Connecting to local mock server…"
        ShotFeedStatus.Connected -> "Connected to local mock server"
        is ShotFeedStatus.Error -> "Connection error: ${status.message} — retrying…"
    }
    Text(message, style = MaterialTheme.typography.bodyMedium)
}
