package dev.rodolphe.syeksodemo.core.webrtc

import kotlinx.coroutines.flow.Flow
import org.webrtc.SurfaceViewRenderer

/** One WebRTC call. Hides the org.webrtc SDK; the ViewModels only touch this interface + [events]. */
interface WebRtcSession {
    val events: Flow<WebRtcEvent>
    fun startAsCaller()                                   // intercom: capture camera+mic, create the offer
    fun startAsCallee()                                   // resident: capture mic, ready to receive video
    fun onRemoteSdp(sdp: String, type: String)            // set remote description ("offer"/"answer")
    fun createAnswer()                                    // resident: after the remote offer → emit the answer
    fun addRemoteIce(sdp: String, sdpMid: String?, sdpMLineIndex: Int)
    fun attachRemoteVideo(renderer: SurfaceViewRenderer)  // resident: bind the remote video track
    fun close()
}

sealed interface WebRtcEvent {
    data class LocalSdp(val sdp: String, val type: String) : WebRtcEvent   // → Offer/Answer via signaling
    data class LocalIce(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int) : WebRtcEvent
    data object RemoteVideoReady : WebRtcEvent
    data class ConnectionState(val state: String) : WebRtcEvent            // connected/failed/disconnected
}
