package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSession
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import javax.inject.Inject

@HiltViewModel
class IncomingCallViewModel(
    private val signaling: Signaling,
    private val sessionProvider: () -> WebRtcSession,
    val eglContext: EglBase.Context? = null,   // null in tests (no rendering)
) : ViewModel() {

    @Inject constructor(signaling: Signaling, factory: WebRtcSessionFactory) :
        this(signaling, { factory.create() }, factory.eglContext())

    private val _uiState = MutableStateFlow<IncomingCallUiState>(IncomingCallUiState.None)
    val uiState: StateFlow<IncomingCallUiState> = _uiState.asStateFlow()

    private var callId: String? = null
    private var doorName: String = "Porte"
    private var session: WebRtcSession? = null

    init {
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Ring -> {
                        callId = msg.callId
                        doorName = msg.doorName ?: "Porte"
                        _uiState.value = IncomingCallUiState.Ringing(msg.callId, doorName)
                    }
                    is SignalingMessage.Offer -> if (msg.callId == callId) {
                        session?.onRemoteSdp(msg.sdp, "offer")
                        session?.createAnswer()
                    }
                    is SignalingMessage.IceCandidate -> if (msg.callId == callId) {
                        session?.addRemoteIce(msg.sdp, msg.sdpMid, msg.sdpMLineIndex)
                    }
                    is SignalingMessage.OpenResult -> if (msg.callId == callId) {
                        _uiState.value = IncomingCallUiState.InCall(
                            doorName,
                            if (msg.success) "Porte ouverte" else "Échec de l'ouverture",
                        )
                    }
                    is SignalingMessage.Hangup -> if (msg.callId == callId) endCall()
                    is SignalingMessage.ErrorMsg -> if (msg.callId == callId) {
                        _uiState.value = IncomingCallUiState.Result(false, msg.message)
                        endSession()
                    }
                    else -> {}
                }
            }
        }
    }

    fun onAnswer() {
        val id = callId ?: return
        val s = sessionProvider().also { session = it }
        viewModelScope.launch {
            s.events.collect { e ->
                when (e) {
                    is WebRtcEvent.LocalSdp -> signaling.send(SignalingMessage.Answer(id, e.sdp))
                    is WebRtcEvent.LocalIce -> signaling.send(SignalingMessage.IceCandidate(id, e.sdp, e.sdpMid, e.sdpMLineIndex))
                    else -> {}
                }
            }
        }
        s.startAsCallee()
        signaling.send(SignalingMessage.Accept(id))
        _uiState.value = IncomingCallUiState.InCall(doorName)
    }

    fun onOpen() { callId?.let { signaling.send(SignalingMessage.Open(it)) } }

    fun onDecline() { callId?.let { signaling.send(SignalingMessage.Decline(it)) }; clear() }

    fun onHangup() { callId?.let { signaling.send(SignalingMessage.Hangup(it)) }; endCall() }

    val liveSession: WebRtcSession? get() = session

    private fun endCall() { endSession(); clear() }
    private fun endSession() { session?.close(); session = null }
    private fun clear() { callId = null; _uiState.value = IncomingCallUiState.None }

    override fun onCleared() { endSession() }
}
