package dev.rodolphe.syeksodemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = authRepository.session
        .map { if (it.isLoggedIn) MainUiState.LoggedIn else MainUiState.LoggedOut }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState.Loading,
        )

    // The signaling socket used to be opened here. It now belongs to CallSignalingService: held by
    // a ViewModel it died with the screen, so the intercom could only reach a resident who happened
    // to be looking at the app. Opening it in both places would race for the same connection.
}
