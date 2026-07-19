package dev.rodolphe.syeksodemo.feature.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.Invitation
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InvitationSection(viewModel: InvitationViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("Créer une invitation", style = MaterialTheme.typography.titleMedium)
        Text(
            "Un accès nommé, valable sur une période, réutilisable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { sheetOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Créer une invitation")
        }

        uiState.created?.let { inv -> Spacer(Modifier.height(12.dp)); InvitationCard(inv) }

        if (uiState.invitations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Invitations actives", style = MaterialTheme.typography.titleSmall)
            uiState.invitations.forEach { inv -> Spacer(Modifier.height(8.dp)); InvitationCard(inv) }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            InvitationForm(
                uiState = uiState,
                onTitleChange = viewModel::onTitleChange,
                onSelectDoor = viewModel::selectDoor,
                onWindowChange = viewModel::onWindowChange,
                onCreate = viewModel::create,
            )
        }
        // Close the sheet as soon as a code was produced.
        if (uiState.created != null) sheetOpen = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InvitationForm(
    uiState: InvitationUiState,
    onTitleChange: (String) -> Unit,
    onSelectDoor: (DoorId) -> Unit,
    onWindowChange: (Long, Long) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Nouvelle invitation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.title,
            onValueChange = onTitleChange,
            label = { Text("Titre (ex. Anniversaire de Coralie)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
        DateField("Début", uiState.validFromEpochMs) { onWindowChange(it, uiState.validUntilEpochMs) }
        Spacer(Modifier.height(8.dp))
        DateField("Fin", uiState.validUntilEpochMs) { onWindowChange(uiState.validFromEpochMs, it) }

        if (uiState.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(uiState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate, enabled = uiState.canCreate, modifier = Modifier.fillMaxWidth()) {
            if (uiState.isCreating) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
            else Text("Créer")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, epochMs: Long, onPicked: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE) }
    val text = if (epochMs > 0) fmt.format(java.util.Date(epochMs)) else "Choisir une date"
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label : $text") }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = if (epochMs > 0) epochMs else System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onPicked)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun InvitationCard(inv: Invitation) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(inv.title, style = MaterialTheme.typography.titleMedium)
            Text("${inv.code} · ${inv.doorName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Du ${fmt.format(java.util.Date(inv.validFromEpochMs))} au ${fmt.format(java.util.Date(inv.validUntilEpochMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
