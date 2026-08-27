package dev.rodolphe.syeksodemo.feature.intercomcall

import dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.webrtc.SurfaceViewRenderer

class FakeWebRtcSession : WebRtcSession {
    val flow = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 16)
    override val events: Flow<WebRtcEvent> = flow
    val calls = mutableListOf<String>()
    override fun startAsCaller() { calls += "startAsCaller" }
    override fun startAsCallee() { calls += "startAsCallee" }
    override fun onRemoteSdp(sdp: String, type: String) { calls += "onRemoteSdp:$type" }
    override fun createAnswer() { calls += "createAnswer" }
    override fun addRemoteIce(sdp: String, sdpMid: String?, sdpMLineIndex: Int) { calls += "addRemoteIce" }
    override fun attachRemoteVideo(renderer: SurfaceViewRenderer) { calls += "attachRemoteVideo" }
    override fun close() { calls += "close" }
}
