package dev.rodolphe.syeksodemo.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.data.repository.ActivationResult
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val doorsRepository: DoorsRepository,
    private val bleController: SyeksoBleController,
) : ViewModel() {

    private val opening = MutableStateFlow<Map<DoorId, DoorOpenState>>(emptyMap())
    private val activation = MutableStateFlow(ActivationUiState())

    val uiState: StateFlow<HomeUiState> =
        combine(doorsRepository.doors, opening, activation) { doors, opening, activation ->
            HomeUiState(doors = doors, opening = opening, activation = activation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    init {
        // Best-effort sync; Room is the source of truth so a failure just keeps cached doors.
        viewModelScope.launch { doorsRepository.refreshDoors() }
    }

    fun open(door: Door) {
        viewModelScope.launch {
            bleController.open(door.bleLocalName).collect { state ->
                opening.update { it + (door.id to state) }
            }
        }
    }

    fun onActivateClicked() = activation.update { ActivationUiState(isSheetOpen = true) }

    fun dismissActivationSheet() = activation.update { ActivationUiState(isSheetOpen = false) }

    fun onActivationCodeChange(value: String) =
        activation.update { it.copy(code = value, error = null) }

    fun submitActivation() {
        val current = activation.value
        if (!current.canSubmit) return
        activation.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = doorsRepository.activate(current.code.trim())) {
                ActivationResult.Success -> activation.update { ActivationUiState(isSheetOpen = false) }
                else -> activation.update { it.copy(isSubmitting = false, error = result.toMessage()) }
            }
        }
    }
}

private fun ActivationResult.toMessage(): String = when (this) {
    ActivationResult.Success -> ""
    ActivationResult.InvalidCode -> "Code d'activation inconnu"
    ActivationResult.AlreadyUsed -> "Code déjà utilisé"
    ActivationResult.Unauthorized -> "Session expirée, reconnectez-vous"
    ActivationResult.NetworkError -> "Impossible de joindre le serveur. Vérifiez votre connexion."
    ActivationResult.ServerError -> "Une erreur est survenue. Réessayez."
}
