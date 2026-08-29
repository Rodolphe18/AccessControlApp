package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin presenter over [IncomingCallStore].
 *
 * Deliberately holds no call state of its own: a door call outlives the screen that displays it —
 * it can start while the app is in the background and must still be there when the resident opens
 * the app from the notification. Note the absence of an `onCleared` closing the session, which
 * would hang up the call every time the Activity went away.
 */
@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    private val store: IncomingCallStore,
) : ViewModel() {

    val uiState: StateFlow<IncomingCallUiState> = store.uiState
    val eglContext get() = store.eglContext
    val liveSession: WebRtcSession? get() = store.liveSession

    fun onAnswer() = store.onAnswer()
    fun onOpen() = store.onOpen()
    fun onDecline() = store.onDecline()
    fun onHangup() = store.onHangup()
}
