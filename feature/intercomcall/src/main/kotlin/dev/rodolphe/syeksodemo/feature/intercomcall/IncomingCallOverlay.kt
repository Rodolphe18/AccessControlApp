package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IncomingCallOverlay(viewModel: IncomingCallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state is IncomingCallUiState.None) return

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is IncomingCallUiState.Ringing -> {
                    Text("Appel entrant", style = MaterialTheme.typography.headlineSmall)
                    Text("Quelqu'un sonne à « ${s.doorName} »", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = viewModel::onDecline) { Text("Ignorer") }
                        Button(onClick = viewModel::onOpen) { Text("Ouvrir") }
                    }
                }
                IncomingCallUiState.Opening -> {
                    CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Ouverture…")
                }
                is IncomingCallUiState.Result -> {
                    Text(
                        if (s.success) "✓ ${s.message}" else "✗ ${s.message}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = viewModel::dismiss) { Text("Fermer") }
                }
                IncomingCallUiState.None -> {}
            }
        }
    }
}
