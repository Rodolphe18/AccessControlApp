package dev.rodolphe.syeksodemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
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
