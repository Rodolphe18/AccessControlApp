package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    private val signaling: Signaling,
) : ViewModel() {

    private val _uiState = MutableStateFlow<IncomingCallUiState>(IncomingCallUiState.None)
    val uiState: StateFlow<IncomingCallUiState> = _uiState.asStateFlow()

    private var currentCallId: String? = null

    init {
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Ring -> {
                        currentCallId = msg.callId
                        _uiState.value = IncomingCallUiState.Ringing(msg.callId, msg.doorName ?: "Porte")
                    }
                    is SignalingMessage.OpenResult ->
                        if (msg.callId == currentCallId) {
                            _uiState.value = IncomingCallUiState.Result(
                                msg.success,
                                if (msg.success) "Porte ouverte" else "Échec de l'ouverture",
                            )
                            currentCallId = null
                        }
                    is SignalingMessage.ErrorMsg ->
                        if (msg.callId == currentCallId) {
                            _uiState.value = IncomingCallUiState.Result(false, msg.message)
                            currentCallId = null
                        }
                    else -> {}
                }
            }
        }
    }

    fun onOpen() {
        val id = currentCallId ?: return
        signaling.send(SignalingMessage.Open(id))
        _uiState.value = IncomingCallUiState.Opening
    }

    fun onDecline() {
        val id = currentCallId ?: return
        signaling.send(SignalingMessage.Decline(id))
        currentCallId = null
        _uiState.value = IncomingCallUiState.None
    }

    fun dismiss() { _uiState.value = IncomingCallUiState.None }
}
