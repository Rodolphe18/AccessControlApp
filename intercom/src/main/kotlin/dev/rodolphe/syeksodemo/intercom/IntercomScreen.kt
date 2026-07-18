package dev.rodolphe.syeksodemo.intercom

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IntercomRoute(viewModel: IntercomViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    IntercomScreen(
        entered = uiState.entered,
        status = uiState.status,
        onDigit = viewModel::onDigit,
        onClear = viewModel::onClear,
        onValidate = {
            permissionLauncher.launch(permissions)
            viewModel.validate()
        },
    )
}

@Composable
fun IntercomScreen(
    entered: String,
    status: IntercomStatus,
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onValidate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Résidence Montmartre", style = MaterialTheme.typography.titleLarge)
        Text(
            "Entrez votre code d'accès",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Text(
            text = "•".repeat(entered.length).padEnd(IntercomUiState.PIN_LENGTH, '◦'),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(status.message(), color = status.color(), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "OK"),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .padding(4.dp),
                    ) {
                        when (key) {
                            "C" -> OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxSize()) { Text("C") }
                            "OK" -> Button(onClick = onValidate, modifier = Modifier.fillMaxSize()) { Text("OK") }
                            else -> Button(onClick = { onDigit(key) }, modifier = Modifier.fillMaxSize()) {
                                Text(key, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun IntercomStatus.message(): String = when (this) {
    IntercomStatus.Idle -> " "
    IntercomStatus.Checking -> "Vérification…"
    IntercomStatus.Opening -> "Ouverture…"
    IntercomStatus.Granted -> "Accès autorisé, porte ouverte ✓"
    is IntercomStatus.Denied -> reason
    is IntercomStatus.Error -> message
}

@Composable
private fun IntercomStatus.color() = when (this) {
    IntercomStatus.Granted -> MaterialTheme.colorScheme.primary
    is IntercomStatus.Denied, is IntercomStatus.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
