package dev.rodolphe.syeksodemo.intercom.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val signaling: Signaling,
    private val bleController: SyeksoBleController,
    private val directoryProvider: DirectoryProvider,
    private val config: IntercomConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var currentCallId: String? = null

    init {
        viewModelScope.launch {
            val directory = runCatching { directoryProvider.residents() }.getOrDefault(emptyList())
            _uiState.update { it.copy(directory = directory, selectedUserId = directory.firstOrNull()?.userId) }
        }
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Open -> if (msg.callId == currentCallId) doOpen(msg.callId)
                    is SignalingMessage.Decline -> if (msg.callId == currentCallId) end("Refusé")
                    is SignalingMessage.ErrorMsg -> if (msg.callId == currentCallId) end(msg.message)
                    else -> {}
                }
            }
        }
    }

    fun select(userId: String) = _uiState.update { it.copy(selectedUserId = userId) }

    fun ring() {
        val target = _uiState.value.selectedUserId ?: return
        if (!_uiState.value.canRing) return
        val callId = UUID.randomUUID().toString()
        currentCallId = callId
        signaling.send(SignalingMessage.Ring(callId, target, config.doorName))
        _uiState.update { it.copy(status = CallStatus.Ringing) }
    }

    private fun doOpen(callId: String) {
        _uiState.update { it.copy(status = CallStatus.Opening) }
        viewModelScope.launch {
            var success = false
            var reason: String? = null
            bleController.open(config.doorBleLocalName).collect { s ->
                when (s) {
                    DoorOpenState.Opened -> success = true
                    is DoorOpenState.Error -> reason = s.reason.name
                    else -> {}
                }
            }
            signaling.send(SignalingMessage.OpenResult(callId, success, reason))
            end(if (success) "Ouvert" else "Ouverture impossible")
        }
    }

    private fun end(message: String) {
        currentCallId = null
        _uiState.update { it.copy(status = CallStatus.Ended(message)) }
    }
}
