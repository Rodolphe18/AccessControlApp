package dev.rodolphe.syeksodemo.feature.sharing

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.PinCode

@Composable
fun SharingRoute(viewModel: SharingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SharingScreen(uiState = uiState, onSelectDoor = viewModel::selectDoor, onGenerate = viewModel::generate)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SharingScreen(
    uiState: SharingUiState,
    onSelectDoor: (DoorId) -> Unit,
    onGenerate: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Mes invitations") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Générer un code rapide", style = MaterialTheme.typography.titleMedium)
            Text(
                "Un code à usage unique pour vos invités ou livreurs. Il expire à l'utilisation ou au bout de 15 min.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.doors.forEach { door ->
                    FilterChip(
                        selected = uiState.selectedDoorId == door.id,
                        onClick = { onSelectDoor(door.id) },
                        label = { Text(door.name) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = onGenerate, enabled = uiState.canGenerate, modifier = Modifier.fillMaxWidth()) {
                if (uiState.isGenerating) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text("Générer un code rapide")
                }
            }
            if (uiState.error != null) {
                Text(
                    uiState.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            uiState.generatedPin?.let { pin ->
                Spacer(Modifier.height(16.dp))
                GeneratedPinCard(pin)
            }

            Spacer(Modifier.height(24.dp))
            Text("Envoyées", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(uiState.activePins, key = { it.pin }) { pin -> ActivePinRow(pin) }
            }
        }
    }
}

@Composable
private fun GeneratedPinCard(pin: PinCode) {
    val context = LocalContext.current
    val remaining = rememberCountdown(pin.expiresAtEpochMs)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(pin.doorName, style = MaterialTheme.typography.bodyMedium)
            Text(pin.pin, style = MaterialTheme.typography.displaySmall)
            Text(
                "Expire dans $remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val message = "Votre code d'accès Syekso pour ${pin.doorName} : ${pin.pin} (valable 15 min, usage unique)."
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    context.startActivity(Intent.createChooser(send, "Partager le code"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Partager") }
        }
    }
}

@Composable
private fun ActivePinRow(pin: PinCode) {
    val remaining = rememberCountdown(pin.expiresAtEpochMs)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${pin.pin} · ${pin.doorName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Expire dans $remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Recomputes a mm:ss string once per second from an absolute expiry. */
@Composable
private fun rememberCountdown(expiresAtEpochMs: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val secs = ((expiresAtEpochMs - now) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}
