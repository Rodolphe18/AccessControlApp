package dev.rodolphe.syeksodemo.intercom.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSession
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CallViewModel(
    private val signaling: Signaling,
    private val bleController: SyeksoBleController,
    private val directoryProvider: DirectoryProvider,
    private val config: IntercomConfig,
    private val sessionProvider: () -> WebRtcSession,
) : ViewModel() {

    @Inject constructor(
        signaling: Signaling,
        bleController: SyeksoBleController,
        directoryProvider: DirectoryProvider,
        config: IntercomConfig,
        factory: WebRtcSessionFactory,
    ) : this(signaling, bleController, directoryProvider, config, { factory.create() })

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var currentCallId: String? = null
    private var session: WebRtcSession? = null

    init {
        viewModelScope.launch {
            val directory = runCatching { directoryProvider.residents() }.getOrDefault(emptyList())
            _uiState.update { it.copy(directory = directory, selectedUserId = directory.firstOrNull()?.userId) }
        }
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Accept -> if (msg.callId == currentCallId) startCaller(msg.callId)
                    is SignalingMessage.Answer -> if (msg.callId == currentCallId) session?.onRemoteSdp(msg.sdp, "answer")
                    is SignalingMessage.IceCandidate -> if (msg.callId == currentCallId) session?.addRemoteIce(msg.sdp, msg.sdpMid, msg.sdpMLineIndex)
                    is SignalingMessage.Open -> if (msg.callId == currentCallId) doOpen(msg.callId)
                    is SignalingMessage.Hangup -> if (msg.callId == currentCallId) endCall("Terminé", isFailure = false)
                    is SignalingMessage.ErrorMsg -> if (msg.callId == currentCallId) endCall(msg.message, isFailure = true)
                    is SignalingMessage.Decline -> if (msg.callId == currentCallId) endCall("Refusé", isFailure = true)
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

    /**
     * Clears a finished call's outcome so the visitor can ring again.
     *
     * [CallStatus.Ended] is otherwise a terminal state: `canRing` requires [CallStatus.Idle], and
     * nothing else ever returns there — so without this the Sonner button stays disabled for the
     * rest of the session once a call is declined, times out, or the resident is offline.
     */
    fun reset() {
        if (_uiState.value.status !is CallStatus.Ended) return
        _uiState.update { it.copy(status = CallStatus.Idle) }
    }

    fun hangup() {
        currentCallId?.let { signaling.send(SignalingMessage.Hangup(it)) }
        endCall("Terminé", isFailure = false)
    }

    private fun startCaller(callId: String) {
        val s = sessionProvider().also { session = it }
        viewModelScope.launch {
            s.events.collect { e ->
                when (e) {
                    is WebRtcEvent.LocalSdp -> signaling.send(SignalingMessage.Offer(callId, e.sdp))
                    is WebRtcEvent.LocalIce -> signaling.send(SignalingMessage.IceCandidate(callId, e.sdp, e.sdpMid, e.sdpMLineIndex))
                    else -> {}
                }
            }
        }
        s.startAsCaller()
        _uiState.update { it.copy(status = CallStatus.InCall) }
    }

    /** Open the door during a call. Does NOT end the call — talk continues. */
    private fun doOpen(callId: String) {
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
        }
    }

    private fun endCall(message: String, isFailure: Boolean) {
        session?.close()
        session = null
        currentCallId = null
        _uiState.update { it.copy(status = CallStatus.Ended(message, isFailure)) }
    }

    override fun onCleared() { session?.close() }
}
