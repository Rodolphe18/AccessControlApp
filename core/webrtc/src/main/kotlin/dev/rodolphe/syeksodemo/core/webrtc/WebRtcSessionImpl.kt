package dev.rodolphe.syeksodemo.core.webrtc

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.IceCandidate as RtcIceCandidate

/**
 * Native WebRTC session (org.webrtc, exposed by stream-webrtc-android). Everything the SDK produces
 * (local SDP/ICE, remote video, connection state) is turned into a [WebRtcEvent]; everything the
 * signaling brings in is fed back through the plain methods.
 */
class WebRtcSessionImpl(
    private val appContext: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val iceServers: List<PeerConnection.IceServer>,
) : WebRtcSession {

    private val _events = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<WebRtcEvent> = _events

    private var peer: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var pendingRenderer: SurfaceViewRenderer? = null

    private fun createPeer() {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: RtcIceCandidate) {
                _events.tryEmit(WebRtcEvent.LocalIce(c.sdp, c.sdpMid, c.sdpMLineIndex))
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    // The renderer may have been attached before the track arrived (and vice-versa);
                    // bind here as soon as both are present.
                    pendingRenderer?.let { track.addSink(it) }
                    _events.tryEmit(WebRtcEvent.RemoteVideoReady)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                _events.tryEmit(WebRtcEvent.ConnectionState(newState.name))
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out RtcIceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun addAudio() {

        val source = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio0", source)
        peer?.addTrack(audioTrack, listOf("stream0"))
        audioSource = source
    }

    private fun addCameraVideo() {
        val enumerator = Camera2Enumerator(appContext)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.first()
        val capturer = enumerator.createCapturer(frontName, null)
        val helper = SurfaceTextureHelper.create("captureThread", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val videoTrack = factory.createVideoTrack("video0", source)
        peer?.addTrack(videoTrack, listOf("stream0"))
        videoCapturer = capturer
        videoSource = source
        surfaceHelper = helper
    }

    /** Route call audio to the loudspeaker: an active mic flips Android to communication mode, which
     *  otherwise plays through the quiet earpiece. */
    private fun routeAudioToSpeaker() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
    }

    override fun startAsCaller() {
        routeAudioToSpeaker()
        createPeer(); addAudio(); addCameraVideo()
        peer?.createOffer(sdpObserver { desc ->
            peer?.setLocalDescription(plainSdpObserver(), desc)
            _events.tryEmit(WebRtcEvent.LocalSdp(desc.description, "offer"))
        }, MediaConstraints())
    }

    override fun startAsCallee() {
        routeAudioToSpeaker()
        createPeer()
        addAudio()
    }

    override fun onRemoteSdp(sdp: String, type: String) {
        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        peer?.setRemoteDescription(plainSdpObserver(), SessionDescription(sdpType, sdp))
    }

    override fun createAnswer() {
        peer?.createAnswer(sdpObserver { desc ->
            peer?.setLocalDescription(plainSdpObserver(), desc)
            _events.tryEmit(WebRtcEvent.LocalSdp(desc.description, "answer"))
        }, MediaConstraints())
    }

    override fun addRemoteIce(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        peer?.addIceCandidate(RtcIceCandidate(sdpMid, sdpMLineIndex, sdp))
    }

    override fun attachRemoteVideo(renderer: SurfaceViewRenderer) {
        pendingRenderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    override fun close() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        }
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceHelper?.dispose()
        peer?.close()
        peer?.dispose()
        peer = null
        videoCapturer = null
        videoSource = null
        audioSource = null
        surfaceHelper = null
        remoteVideoTrack = null
    }

    private fun sdpObserver(onCreated: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreated(desc)
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun plainSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
