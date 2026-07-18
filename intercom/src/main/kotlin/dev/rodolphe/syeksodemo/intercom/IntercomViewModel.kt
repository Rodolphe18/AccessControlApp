package dev.rodolphe.syeksodemo.intercom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.network.IntercomApiService
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateRequestNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IntercomViewModel @Inject constructor(
    private val api: IntercomApiService,
    private val bleController: SyeksoBleController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntercomUiState())
    val uiState: StateFlow<IntercomUiState> = _uiState.asStateFlow()

    fun onDigit(digit: String) = _uiState.update {
        if (it.entered.length >= IntercomUiState.PIN_LENGTH) it
        else it.copy(entered = it.entered + digit, status = IntercomStatus.Idle)
    }

    fun onClear() = _uiState.update { it.copy(entered = "", status = IntercomStatus.Idle) }

    fun validate() {
        val state = _uiState.value
        if (!state.canValidate) return
        val pin = state.entered
        _uiState.update { it.copy(status = IntercomStatus.Checking) }
        viewModelScope.launch {
            val response = try {
                api.validate(BuildConfig.INTERCOM_KEY, IntercomValidateRequestNetwork(pin))
            } catch (e: Exception) {
                _uiState.update { it.copy(status = IntercomStatus.Error("Interphone hors ligne"), entered = "") }
                return@launch
            }
            val bleLocalName = response.doorBleLocalName
            if (!response.allowed || bleLocalName == null) {
                _uiState.update { it.copy(status = IntercomStatus.Denied(response.reason ?: "Code refusé"), entered = "") }
                return@launch
            }
            _uiState.update { it.copy(status = IntercomStatus.Opening) }
            bleController.open(bleLocalName).collect { openState ->
                val status = when (openState) {
                    DoorOpenState.Opened -> IntercomStatus.Granted
                    is DoorOpenState.Error -> IntercomStatus.Error("Ouverture impossible")
                    else -> IntercomStatus.Opening
                }
                _uiState.update { it.copy(status = status) }
            }
            _uiState.update { it.copy(entered = "") }
        }
    }
}
