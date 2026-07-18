package dev.rodolphe.syeksodemo.feature.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.data.repository.PinCodeRepository
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.PinCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharingViewModel @Inject constructor(
    doorsRepository: DoorsRepository,
    private val pinCodeRepository: PinCodeRepository,
) : ViewModel() {

    private data class Local(
        val selectedDoorId: DoorId? = null,
        val generatedPin: PinCode? = null,
        val isGenerating: Boolean = false,
        val error: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<SharingUiState> =
        combine(doorsRepository.doors, pinCodeRepository.activePins, local) { doors, pins, l ->
            SharingUiState(
                doors = doors,
                selectedDoorId = l.selectedDoorId ?: doors.firstOrNull()?.id,
                activePins = pins,
                generatedPin = l.generatedPin,
                isGenerating = l.isGenerating,
                error = l.error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SharingUiState(),
        )

    init {
        viewModelScope.launch { pinCodeRepository.refreshPins() }
    }

    fun selectDoor(doorId: DoorId) = local.update { it.copy(selectedDoorId = doorId) }

    fun generate() {
        val doorId = uiState.value.selectedDoorId ?: return
        if (uiState.value.isGenerating) return
        local.update { it.copy(isGenerating = true, error = null, generatedPin = null) }
        viewModelScope.launch {
            pinCodeRepository.createPin(doorId.value).fold(
                onSuccess = { pin -> local.update { it.copy(isGenerating = false, generatedPin = pin) } },
                onFailure = {
                    local.update { it.copy(isGenerating = false, error = "Impossible de générer le code. Réessayez.") }
                },
            )
        }
    }
}
