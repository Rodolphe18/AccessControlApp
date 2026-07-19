package dev.rodolphe.syeksodemo.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.core.ble.DoorOpenError
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.model.Door

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    // The door awaiting a permission result, so we open it only once the user has granted access.
    var pendingDoor by remember { mutableStateOf<Door?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val door = pendingDoor
        pendingDoor = null
        if (door != null && results.values.all { it }) viewModel.open(door)
    }

    HomeScreen(
        uiState = uiState,
        onOpenDoor = { door ->
            val granted = permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                viewModel.open(door)
            } else {
                pendingDoor = door
                permissionLauncher.launch(permissions)
            }
        },
        onActivateClicked = viewModel::onActivateClicked,
        onActivationCodeChange = viewModel::onActivationCodeChange,
        onSubmitActivation = viewModel::submitActivation,
        onDismissActivation = viewModel::dismissActivationSheet,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenDoor: (Door) -> Unit,
    onActivateClicked: () -> Unit,
    onActivationCodeChange: (String) -> Unit,
    onSubmitActivation: () -> Unit,
    onDismissActivation: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes portes") },
                actions = {
                    IconButton(onClick = onActivateClicked) {
                        Icon(Icons.Filled.Add, contentDescription = "Activer un immeuble")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.doors.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onActivateClicked = onActivateClicked,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.doors, key = { it.id.value }) { door ->
                    DoorCard(
                        door = door,
                        openState = uiState.opening[door.id],
                        onOpen = { onOpenDoor(door) },
                    )
                }
            }
        }
    }

    if (uiState.activation.isSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissActivation) {
            ActivationSheetContent(
                state = uiState.activation,
                onCodeChange = onActivationCodeChange,
                onSubmit = onSubmitActivation,
            )
        }
    }
}

@Composable
private fun DoorCard(door: Door, openState: DoorOpenState?, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(door.name, style = MaterialTheme.typography.titleMedium)
            Text(
                door.buildingName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OpenButton(openState = openState, onOpen = onOpen)
            val error = openState as? DoorOpenState.Error
            if (error != null) {
                Text(
                    text = error.reason.toMessage(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OpenButton(openState: DoorOpenState?, onOpen: () -> Unit) {
    when (openState) {
        DoorOpenState.Scanning, DoorOpenState.Connecting, DoorOpenState.Sending -> {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text(openState.label())
            }
        }
        DoorOpenState.Opened -> {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Ouverte")
            }
        }
        is DoorOpenState.Error, null -> {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.LockOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (openState is DoorOpenState.Error) "Réessayer" else "Ouvrir")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onActivateClicked: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Aucune porte pour l'instant",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Saisissez un code d'activation pour ajouter votre immeuble.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onActivateClicked) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Activer un immeuble")
        }
    }
}

@Composable
private fun ActivationSheetContent(
    state: ActivationUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Activer un immeuble", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text("Code d'activation") },
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Valider")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun DoorOpenState.label(): String = when (this) {
    DoorOpenState.Scanning -> "Recherche…"
    DoorOpenState.Connecting -> "Connexion…"
    DoorOpenState.Sending -> "Ouverture…"
    DoorOpenState.Opened -> "Ouverte"
    is DoorOpenState.Error -> "Erreur"
}

private fun DoorOpenError.toMessage(): String = when (this) {
    DoorOpenError.BluetoothOff -> "Activez le Bluetooth."
    DoorOpenError.PermissionMissing -> "Autorisation Bluetooth requise."
    DoorOpenError.NotFound -> "Porte introuvable à proximité."
    DoorOpenError.ConnectionFailed -> "Échec de connexion à la porte."
    DoorOpenError.WriteFailed -> "La commande n'a pas abouti."
    DoorOpenError.Timeout -> "Délai dépassé. Réessayez."
}
