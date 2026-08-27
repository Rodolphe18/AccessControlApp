package dev.rodolphe.syeksodemo.core.webrtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject

/** Creates one [WebRtcSession] per call and exposes the shared GL context for the video renderer. */
class WebRtcSessionFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
) {
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )

    fun create(): WebRtcSession = WebRtcSessionImpl(appContext, factory, eglBase, iceServers)

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext
}
