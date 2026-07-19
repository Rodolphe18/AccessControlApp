package dev.rodolphe.syeksodemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepository
import dev.rodolphe.syeksodemo.core.network.BuildConfig
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Top-level gate: reads the persisted session and tells the root whether to show login or the app.
 * Because it observes the session Flow, logging in (which saves the session) or out (which clears it)
 * flips the whole app automatically — no manual navigation.
 */
sealed interface MainUiState {
    data object Loading : MainUiState
    data object LoggedOut : MainUiState
    data object LoggedIn : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val signaling: Signaling,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = authRepository.session
        .map { if (it.isLoggedIn) MainUiState.LoggedIn else MainUiState.LoggedOut }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState.Loading,
        )

    init {
        // Hold the signaling WebSocket whenever the resident is logged in, so a ring can reach them.
        // http(s)://host:port/ -> ws(s)://host:port/ws
        viewModelScope.launch {
            authRepository.session
                .map { it.jwt }
                .distinctUntilChanged()
                .collect { jwt ->
                    if (jwt.isNotEmpty()) {
                        val wsUrl = BuildConfig.BASE_URL.replace("http", "ws") + "ws"
                        signaling.start(wsUrl, SignalingMessage.Hello(role = "resident", jwt = jwt))
                    } else {
                        signaling.stop()
                    }
                }
        }
    }
}
