package dev.rodolphe.syeksodemo.intercom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.intercom.call.CallStatus
import dev.rodolphe.syeksodemo.intercom.call.CallViewModel

private enum class Panel { HOME, CONTACT, CODE }

@Composable
fun IntercomHomeScreen(
    @Suppress("UNUSED_PARAMETER") connection: IntercomConnectionViewModel = hiltViewModel(),
) {
    var panel by remember { mutableStateOf(Panel.HOME) }
    when (panel) {
        Panel.HOME -> Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Résidence Montmartre", style = MaterialTheme.typography.headlineSmall)
            Text("19 Rue Parmentier, 75008 Paris", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = { panel = Panel.CONTACT },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) { Text("CONTACT") }
                Button(
                    onClick = { panel = Panel.CODE },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) { Text("CODE") }
            }
        }
        Panel.CONTACT -> ContactPanel(onBack = { panel = Panel.HOME })
        Panel.CODE -> IntercomRoute() // existing keypad
    }
}

@Composable
private fun ContactPanel(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("← Retour") }
        Spacer(Modifier.height(16.dp))
        Text("Choisissez un résident", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.directory.forEach { entry ->
            FilterChip(
                selected = state.selectedUserId == entry.userId,
                onClick = { viewModel.select(entry.userId) },
                label = { Text(entry.displayName) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::ring, enabled = state.canRing, modifier = Modifier.fillMaxWidth()) {
            Text("Sonner")
        }
        Spacer(Modifier.height(16.dp))
        when (val s = state.status) {
            CallStatus.Ringing -> Text("Sonnerie en cours…")
            CallStatus.Opening -> Text("Ouverture…")
            is CallStatus.Ended -> Text(s.message)
            CallStatus.Idle -> {}
        }
    }
}
