package dev.rodolphe.syeksodemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.rodolphe.syeksodemo.call.CallSignalingService
import dev.rodolphe.syeksodemo.core.designsystem.theme.SyeksoTheme
import dev.rodolphe.syeksodemo.feature.intercomcall.IncomingCallOverlay
import dev.rodolphe.syeksodemo.feature.onboarding.login.LoginRoute
import dev.rodolphe.syeksodemo.navigation.SyeksoNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyeksoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyeksoApp()
                }
            }
        }
    }
}

@Composable
private fun SyeksoApp(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Declined only costs the ring its notification: the socket and the in-app overlay work either
    // way, so there is nothing to block on here.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    // Started from a visible Activity on purpose — Android forbids launching a foreground service
    // from the background, so login is the moment we are allowed to.
    LaunchedEffect(uiState) {
        when (uiState) {
            MainUiState.LoggedIn -> {
                val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                CallSignalingService.start(context)
            }
            MainUiState.LoggedOut -> CallSignalingService.stop(context)
            MainUiState.Loading -> Unit
        }
    }

    when (uiState) {
        MainUiState.Loading -> LoadingScreen()
        MainUiState.LoggedOut -> LoginRoute()
        MainUiState.LoggedIn -> Box(Modifier.fillMaxSize()) {
            SyeksoNavHost()
            IncomingCallOverlay()
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
