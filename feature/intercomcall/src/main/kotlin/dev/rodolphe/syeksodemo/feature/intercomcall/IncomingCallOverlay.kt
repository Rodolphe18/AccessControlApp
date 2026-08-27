package dev.rodolphe.syeksodemo.feature.intercomcall

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcVideoView

@Composable
fun IncomingCallOverlay(viewModel: IncomingCallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onAnswer() }
    val answerWithMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onAnswer()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
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
                        Button(onClick = { answerWithMic() }) { Text("Répondre") }
                    }
                }
                is IncomingCallUiState.InCall -> {
                    viewModel.eglContext?.let { egl ->
                        WebRtcVideoView(
                            eglContext = egl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                            onRenderer = { r -> viewModel.liveSession?.attachRemoteVideo(r) },
                        )
                    }
                    s.openMessage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = viewModel::onHangup) { Text("Raccrocher") }
                        Button(onClick = viewModel::onOpen) { Text("Ouvrir") }
                    }
                }
                is IncomingCallUiState.Result -> {
                    Text(s.message, style = MaterialTheme.typography.headlineSmall)
                }
                IncomingCallUiState.None -> {}
            }
        }
    }
}
