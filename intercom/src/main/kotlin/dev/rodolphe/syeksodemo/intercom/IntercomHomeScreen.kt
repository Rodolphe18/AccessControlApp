package dev.rodolphe.syeksodemo.intercom

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.intercom.call.CallStatus
import dev.rodolphe.syeksodemo.intercom.call.CallViewModel
import androidx.compose.material3.Button
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

private enum class Panel { HOME, CONTACT, CODE }

/**
 * The lobby panel a visitor faces: identify the building, then either call a resident or type an
 * access code.
 *
 * MainActivity draws edge-to-edge, so every panel here pads itself with [WindowInsets.safeDrawing]
 * rather than a hardcoded vertical inset — the latter only happens to look right on one device.
 */
@Composable
fun IntercomHomeScreen(
    @Suppress("UNUSED_PARAMETER") connection: IntercomConnectionViewModel = hiltViewModel(),
) {
    var panel by remember { mutableStateOf(Panel.HOME) }
    when (panel) {
        Panel.HOME -> HomePanel(
            onContact = { panel = Panel.CONTACT },
            onCode = { panel = Panel.CODE },
        )
        Panel.CONTACT -> ContactPanel(onBack = { panel = Panel.HOME })
        Panel.CODE -> IntercomRoute(onBack = { panel = Panel.HOME })
    }
}

@Composable
private fun HomePanel(onContact: () -> Unit, onCode: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 32.dp),
    ) {
        BuildingHeader()

        // A weighted spacer — not a weighted Row — is what pushes the actions down. Putting the
        // weight on the content itself makes it swallow the free space, which then leaves the
        // parent's verticalArrangement nothing to distribute.
        Spacer(Modifier.height(24.dp))

        ActionCard(
            icon = Icons.Filled.Person,
            title = "CONTACT",
            subtitle = "Appeler un résident",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onContact,
        )
        Spacer(Modifier.height(16.dp))
        ActionCard(
            icon = Icons.Filled.Dialpad,
            title = "CODE",
            subtitle = "Saisir un code d'accès",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onCode,
        )
    }
}

@Composable
private fun BuildingHeader() {
    Column(Modifier.fillMaxWidth(),horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "INTERPHONE",
            textAlign = TextAlign.Center,
            fontSize = 40.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Résidence Montmartre",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "19 Rue Parmentier, 75008 Paris",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

}

/**
 * One of the two primary choices. [Card]'s click overload gives it button semantics and a ripple,
 * so the whole surface is the touch target rather than a small label in the middle of it.
 */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The icon sits on a translucent disc of the content colour so it reads as a badge and
            // stays legible whatever container colour the theme resolves to.
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(52.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = contentColor)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ContactPanel(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> if (results.values.all { it }) viewModel.ring() }

    // Camera and mic are needed before the call can start, not once it is answered.
    val requestRing = {
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) viewModel.ring() else permissionLauncher.launch(permissions)
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        // Like the keypad's back button: this ViewModel is Activity-scoped, so without clearing it
        // the previous call's outcome would still be on screen when the visitor comes back.
        OutlinedButton(
            onClick = {
                viewModel.reset()
                onBack()
            },
        ) { Text("← Retour") }
        Spacer(Modifier.height(24.dp))
        Text("Choisissez un résident", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.directory.forEach { entry ->
                FilterChip(
                    selected = state.selectedUserId == entry.userId,
                    onClick = { viewModel.select(entry.userId) },
                    label = { Text(entry.displayName) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = requestRing,
            enabled = state.canRing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sonner") }
        Spacer(Modifier.height(16.dp))
        when (val s = state.status) {
            CallStatus.Ringing -> Text("Sonnerie en cours…")
            CallStatus.InCall -> {
                Text("En communication…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::hangup) { Text("Raccrocher") }
            }
            // The message comes from the hub — "Résident indisponible" when nobody is connected,
            // "Refusé", "Pas de réponse"… Only a failed call invites a retry; "Terminé" after a
            // real conversation does not. The retry points back at Sonner, which
            // CallUiState.canRing keeps enabled in this state.
            is CallStatus.Ended -> Text(
                text = if (s.isFailure) "${s.message}. Réessayer" else s.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (s.isFailure) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            CallStatus.Idle -> {}
        }
    }
}
