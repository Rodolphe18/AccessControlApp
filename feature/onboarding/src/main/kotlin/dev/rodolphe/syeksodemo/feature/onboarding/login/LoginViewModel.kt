package dev.rodolphe.syeksodemo.feature.onboarding.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepository
import dev.rodolphe.syeksodemo.core.data.repository.AuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    // Prefilled with the seed credentials so the demo is one tap. Harmless for a personal demo app.
    val email: String = "rodolphe@example.com",
    val password: String = "password",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // Guards against a second login being launched while one is in flight — a second line of defense
    // alongside canSubmit (which only reflects the state set inside the coroutine).
    private var loginJob: Job? = null

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    /**
     * Attempts login. On success we do NOT navigate here: saving the session flips the app-level
     * session Flow, and the root swaps to Accueil on its own. Failures map to a French message.
     */
    fun submit() {
        if (loginJob?.isActive == true) return
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        loginJob = viewModelScope.launch {
            val message = when (authRepository.login(state.email.trim(), state.password)) {
                AuthResult.Success -> null
                AuthResult.InvalidCredentials -> "Email ou mot de passe incorrect"
                AuthResult.NetworkError -> "Impossible de joindre le serveur. Vérifiez votre connexion."
            }
            _uiState.update { it.copy(isSubmitting = false, error = message) }
        }
    }
}
