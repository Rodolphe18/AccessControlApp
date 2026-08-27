# WebRTC video call (iteration 4b) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Git note:** the repo owner commits/pushes. Do NOT run `git commit`/`git push`. "Checkpoint" = tell the owner it's ready to commit.

**Goal:** When a visitor rings, the resident answers, sees live video of the visitor with two-way audio, and can open the door during the call — WebRTC media P2P, negotiated over the 4a signaling hub.

**Architecture:** Extend the sealed `SignalingMessage` and `SignalingHub` (4a) with `Accept/Offer/Answer/IceCandidate/Hangup`, relayed by `callId` between the intercom (caller/offerer) and resident (callee/answerer); the call stays `IN_CALL` while media flows and `OPEN` still works. A new `core:webrtc` module hides `stream-webrtc-android` behind a `WebRtcSession` interface. One-way video (intercom→resident) + two-way audio; STUN only (same LAN).

**Tech Stack:** Kotlin; Ktor 3.5.1 (`ktor-server-websockets`); `io.getstream:stream-webrtc-android` (org.webrtc API); Compose + Hilt + Coroutines; tests JUnit4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-19-webrtc-video-call-design.md`
**Builds on:** `docs/superpowers/plans/2026-07-19-remote-open-websocket.md` (4a, done).

---

## File structure

**Backend** (`AccessControllerServer`): `signaling/SignalingMessages.kt`, `signaling/SignalingHub.kt` (extend); `api/Routing.kt` (`/ws` dispatch).
**App `core:network`**: `model/NetworkModels.kt` (add the same DTOs).
**App `core:webrtc`** (new module): `WebRtcSession.kt`, `WebRtcSessionImpl.kt`, `WebRtcSessionFactory.kt`, `WebRtcVideoView.kt`, `di/WebRtcModule.kt`, `AndroidManifest.xml`, `build.gradle.kts`.
**App `feature:intercomcall`**: `IncomingCallViewModel.kt` (extend to own the callee `WebRtcSession`), `IncomingCallOverlay.kt` (Répondre/Ignorer + call screen); tests.
**App `intercom`**: `call/CallViewModel.kt` (extend: caller `WebRtcSession`), `IntercomHomeScreen.kt`/`ContactPanel` (in-call state); tests.

---

## Task 1: Backend — signaling messages + hub for the call (IN_CALL)

**Files:** `signaling/SignalingMessages.kt`, `signaling/SignalingHub.kt`, `api/Routing.kt`; Test `signaling/SignalingMessagesTest.kt`, `signaling/SignalingHubTest.kt`.

- [ ] **Step 1: Add the new subtypes to `SignalingMessages.kt`** (inside the sealed interface, before `signalingJson`):

```kotlin
    @Serializable @SerialName("accept")
    data class Accept(val callId: String) : SignalingMessage

    @Serializable @SerialName("offer")
    data class Offer(val callId: String, val sdp: String) : SignalingMessage

    @Serializable @SerialName("answer")
    data class Answer(val callId: String, val sdp: String) : SignalingMessage

    @Serializable @SerialName("ice")
    data class IceCandidate(val callId: String, val sdp: String, val sdpMid: String? = null, val sdpMLineIndex: Int = 0) : SignalingMessage

    @Serializable @SerialName("hangup")
    data class Hangup(val callId: String) : SignalingMessage
```

- [ ] **Step 2: Extend the serialization test** `SignalingMessagesTest.kt` — add to `all subtypes round-trip`:

```kotlin
        roundTrip(SignalingMessage.Accept("c1"))
        roundTrip(SignalingMessage.Offer("c1", "v=0..."))
        roundTrip(SignalingMessage.Answer("c1", "v=0..."))
        roundTrip(SignalingMessage.IceCandidate("c1", "candidate:...", "0", 0))
        roundTrip(SignalingMessage.Hangup("c1"))
```

- [ ] **Step 3: Run serialization test.** `./gradlew test --tests "*SignalingMessagesTest"` → PASS.

- [ ] **Step 4: Change `CallStatus` + rework the state-affecting hub methods in `SignalingHub.kt`.** Replace `enum class CallStatus { RINGING, OPENING }` with:

```kotlin
enum class CallStatus { RINGING, IN_CALL }
```

Replace `onOpenCall` and `onOpenResultReported`, and add `onAcceptCall`, `onHangupCall`, and two relay helpers. The full replacement for the methods block (keep `onRingCall` and `onDeclineCall` as they are, but see Step 5 for the ring path):

```kotlin
    /** Resident answered: RINGING -> IN_CALL, relay Accept to the intercom (which then creates the offer). */
    suspend fun onAcceptCall(residentUserId: String, msg: SignalingMessage.Accept) {
        val state = calls[msg.callId]
        if (state == null || state.residentUserId != residentUserId || state.status != CallStatus.RINGING) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel expiré"))
            return
        }
        state.status = CallStatus.IN_CALL
        state.timeoutJob?.cancel()
        intercoms[state.buildingId]?.send(SignalingMessage.Accept(msg.callId))
    }

    /** Open the door during a call: relay OPEN to the intercom. Does NOT end the call. */
    suspend fun onOpenCall(residentUserId: String, msg: SignalingMessage.Open) {
        val state = calls[msg.callId]
        if (state == null || state.residentUserId != residentUserId) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel expiré"))
            return
        }
        val intercom = intercoms[state.buildingId]
        if (intercom == null) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Interphone hors ligne"))
            return
        }
        intercom.send(SignalingMessage.Open(msg.callId))
    }

    /** Real BLE result relayed back to the resident. Call stays alive (talk continues). */
    suspend fun onOpenResultReported(buildingId: String, msg: SignalingMessage.OpenResult) {
        val state = calls[msg.callId] ?: return
        if (state.buildingId != buildingId) return
        residents[state.residentUserId]?.send(msg)
    }

    /** Hangup from either side: end the call, relay to the other peer. */
    suspend fun onHangupCall(callId: String, fromResident: Boolean) {
        val state = calls.remove(callId) ?: return
        state.timeoutJob?.cancel()
        val target = if (fromResident) intercoms[state.buildingId] else residents[state.residentUserId]
        target?.send(SignalingMessage.Hangup(callId))
    }

    /** Pure media pass-through from the resident to the intercom (Answer, IceCandidate). */
    suspend fun relayFromResident(residentUserId: String, msg: SignalingMessage) {
        val callId = callIdOf(msg) ?: return
        val state = calls[callId] ?: return
        if (state.residentUserId != residentUserId) return
        intercoms[state.buildingId]?.send(msg)
    }

    /** Pure media pass-through from the intercom to the resident (Offer, IceCandidate). */
    suspend fun relayFromIntercom(buildingId: String, msg: SignalingMessage) {
        val callId = callIdOf(msg) ?: return
        val state = calls[callId] ?: return
        if (state.buildingId != buildingId) return
        residents[state.residentUserId]?.send(msg)
    }

    private fun callIdOf(msg: SignalingMessage): String? = when (msg) {
        is SignalingMessage.Offer -> msg.callId
        is SignalingMessage.Answer -> msg.callId
        is SignalingMessage.IceCandidate -> msg.callId
        else -> null
    }
```

- [ ] **Step 5: Keep the 30 s ring timeout tied to `RINGING`.** `onRingCall` is unchanged from 4a (creates `RINGING` + a 30 s timeout that fires only `if (calls[callId]?.status == CallStatus.RINGING)`). Since `onAcceptCall` flips to `IN_CALL` and cancels the job, no further change is needed — verify the existing `onRingCall` still reads `CallStatus.RINGING` (it does).

- [ ] **Step 6: Rewrite the affected 4a hub tests + add call tests in `SignalingHubTest.kt`.** Replace the two tests `open forwards to the intercom only from a ringing call` and `open_result routes the real BLE outcome to the resident` with the 4b behavior, and add accept/hangup/relay tests:

```kotlin
    @Test fun `accept moves the call to in-call and relays to the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        intercomSink.clear()

        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        assertEquals(SignalingMessage.Accept("c1"), intercomSink.single())
    }

    @Test fun `open during a call relays and keeps the call alive`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear(); residentSink.clear()

        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.Open("c1"), intercomSink.single())
        // Result relayed, call still alive -> a second open still works.
        hub.onOpenResultReported("b1", SignalingMessage.OpenResult("c1", true))
        assertEquals(SignalingMessage.OpenResult("c1", true, null), residentSink.single())
        intercomSink.clear()
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.Open("c1"), intercomSink.single())
    }

    @Test fun `offer relays intercom to resident, answer relays resident to intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear(); residentSink.clear()

        hub.relayFromIntercom("b1", SignalingMessage.Offer("c1", "OFFER"))
        assertEquals(SignalingMessage.Offer("c1", "OFFER"), residentSink.single())
        hub.relayFromResident("u1", SignalingMessage.Answer("c1", "ANSWER"))
        assertEquals(SignalingMessage.Answer("c1", "ANSWER"), intercomSink.single())
    }

    @Test fun `hangup from resident ends the call and reaches the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear()

        hub.onHangupCall("c1", fromResident = true)
        assertEquals(SignalingMessage.Hangup("c1"), intercomSink.single())
        // Call gone: a later open errors.
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.ErrorMsg("c1", "Appel expiré"), residentSink.single())
    }
```

Delete the obsolete 4a tests `open forwards to the intercom only from a ringing call` and `open_result routes the real BLE outcome to the resident` (their RINGING-open semantics no longer hold).

- [ ] **Step 7: Run the hub tests.** `./gradlew test --tests "*SignalingHubTest"` → PASS. Fix hub logic if any fail.

- [ ] **Step 8: Wire the new messages in the `/ws` dispatch (`api/Routing.kt`).** In `signalingRoute`, extend the two `onMessage` when-blocks:

```kotlin
                // resident branch:
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Open -> hub.onOpenCall(userId, msg)
                        is SignalingMessage.Decline -> hub.onDeclineCall(userId, msg)
                        is SignalingMessage.Accept -> hub.onAcceptCall(userId, msg)
                        is SignalingMessage.Answer -> hub.relayFromResident(userId, msg)
                        is SignalingMessage.IceCandidate -> hub.relayFromResident(userId, msg)
                        is SignalingMessage.Hangup -> hub.onHangupCall(msg.callId, fromResident = true)
                        else -> {}
                    }
                }
                // intercom branch:
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Ring -> hub.onRingCall(buildingId, msg)
                        is SignalingMessage.OpenResult -> hub.onOpenResultReported(buildingId, msg)
                        is SignalingMessage.Offer -> hub.relayFromIntercom(buildingId, msg)
                        is SignalingMessage.IceCandidate -> hub.relayFromIntercom(buildingId, msg)
                        is SignalingMessage.Hangup -> hub.onHangupCall(msg.callId, fromResident = false)
                        else -> {}
                    }
                }
```

- [ ] **Step 9: Compile.** `./gradlew compileKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 10: Manual `/ws` verify.** Extend `scratchpad/ws_verify.py` (or a new script): after `Accept`, send an `Offer` from the intercom and assert the resident receives it; send an `Answer` + `IceCandidate` from the resident and assert the intercom receives them; send `Hangup` and confirm the other side receives it. Owner restarts the server first. Expected: all relays arrive.

- [ ] **Step 11: Checkpoint.**

---

## Task 2: App `core:network` — add the same signaling DTOs

**Files:** `core/network/.../model/NetworkModels.kt`.

- [ ] **Step 1: Add the identical subtypes to the app's sealed `SignalingMessage`** (inside it):

```kotlin
    @Serializable @SerialName("accept")
    data class Accept(val callId: String) : SignalingMessage
    @Serializable @SerialName("offer")
    data class Offer(val callId: String, val sdp: String) : SignalingMessage
    @Serializable @SerialName("answer")
    data class Answer(val callId: String, val sdp: String) : SignalingMessage
    @Serializable @SerialName("ice")
    data class IceCandidate(val callId: String, val sdp: String, val sdpMid: String? = null, val sdpMLineIndex: Int = 0) : SignalingMessage
    @Serializable @SerialName("hangup")
    data class Hangup(val callId: String) : SignalingMessage
```

`SignalingClient` already (de)serializes any `SignalingMessage`, so no other change.

- [ ] **Step 2: Compile.** `./gradlew :core:network:compileDebugKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Checkpoint.**

---

## Task 3: New module `core:webrtc` — WebRtcSession + factory + DI + video view

**Files:** create `core/webrtc/build.gradle.kts`, `core/webrtc/src/main/AndroidManifest.xml`, and under `core/webrtc/src/main/kotlin/dev/rodolphe/syeksodemo/core/webrtc/`: `WebRtcSession.kt`, `WebRtcSessionImpl.kt`, `WebRtcSessionFactory.kt`, `WebRtcVideoView.kt`, `di/WebRtcModule.kt`; Modify `settings.gradle.kts`, `gradle/libs.versions.toml`.

- [ ] **Step 1: Register the module** in `settings.gradle.kts`: add `include(":core:webrtc")`.

- [ ] **Step 2: Add the dependency to the version catalog** `gradle/libs.versions.toml` under `[libraries]`:

```toml
stream-webrtc-android = { group = "io.getstream", name = "stream-webrtc-android", version = "1.3.8" }
```

- [ ] **Step 3: Create `core/webrtc/build.gradle.kts`** (copy `core/ble/build.gradle.kts` structure; add compose for the video view):

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.syeksodemo.core.webrtc"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.stream.webrtc.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

- [ ] **Step 4: Create `core/webrtc/src/main/AndroidManifest.xml`** with the media permissions (merged into both apps):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
</manifest>
```

- [ ] **Step 5: Create `WebRtcSession.kt`** (the interface + events, exactly as the spec):

```kotlin
package dev.rodolphe.syeksodemo.core.webrtc

import kotlinx.coroutines.flow.Flow
import org.webrtc.SurfaceViewRenderer

interface WebRtcSession {
    val events: Flow<WebRtcEvent>
    fun startAsCaller()
    fun startAsCallee()
    fun onRemoteSdp(sdp: String, type: String)
    fun createAnswer()
    fun addRemoteIce(sdp: String, sdpMid: String?, sdpMLineIndex: Int)
    fun attachRemoteVideo(renderer: SurfaceViewRenderer)
    fun close()
}

sealed interface WebRtcEvent {
    data class LocalSdp(val sdp: String, val type: String) : WebRtcEvent
    data class LocalIce(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int) : WebRtcEvent
    data object RemoteVideoReady : WebRtcEvent
    data class ConnectionState(val state: String) : WebRtcEvent
}
```

- [ ] **Step 6: Create `WebRtcSessionImpl.kt`** — the native implementation (org.webrtc API exposed by stream-webrtc-android). Note: exact class/method names can shift slightly between SDK versions; adjust imports if the compiler flags a signature.

```kotlin
package dev.rodolphe.syeksodemo.core.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

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
                if (track is VideoTrack) { remoteVideoTrack = track; _events.tryEmit(WebRtcEvent.RemoteVideoReady) }
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
        audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio0", audioSource)
        peer?.addTrack(audioTrack, listOf("stream0"))
    }

    private fun addCameraVideo() {
        val enumerator = Camera2Enumerator(appContext)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.first()
        val capturer = enumerator.createCapturer(frontName, null)
        surfaceHelper = SurfaceTextureHelper.create("captureThread", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(surfaceHelper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val videoTrack = factory.createVideoTrack("video0", source)
        peer?.addTrack(videoTrack, listOf("stream0"))
        videoCapturer = capturer; videoSource = source
    }

    override fun startAsCaller() {
        createPeer(); addAudio(); addCameraVideo()
        peer?.createOffer(sdpObserver { desc ->
            peer?.setLocalDescription(plainSdpObserver(), desc)
            _events.tryEmit(WebRtcEvent.LocalSdp(desc.description, "offer"))
        }, MediaConstraints())
    }

    override fun startAsCallee() { createPeer(); addAudio() }

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
        remoteVideoTrack?.addSink(renderer)
    }

    override fun close() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose(); videoSource?.dispose(); audioSource?.dispose()
        surfaceHelper?.dispose(); peer?.close(); peer?.dispose()
        peer = null; videoCapturer = null; videoSource = null; audioSource = null; surfaceHelper = null; remoteVideoTrack = null
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
```

- [ ] **Step 7: Create `WebRtcSessionFactory.kt`** — makes one session per call:

```kotlin
package dev.rodolphe.syeksodemo.core.webrtc

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject

class WebRtcSessionFactory @Inject constructor(
    private val appContext: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
) {
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )
    fun create(): WebRtcSession = WebRtcSessionImpl(appContext, factory, eglBase, iceServers)
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext
}
```

- [ ] **Step 8: Create `di/WebRtcModule.kt`** — provide the singletons:

```kotlin
package dev.rodolphe.syeksodemo.core.webrtc.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {

    @Provides @Singleton
    fun provideEglBase(): EglBase = EglBase.create()

    @Provides @Singleton
    fun providePeerConnectionFactory(@ApplicationContext context: Context, eglBase: EglBase): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
```

- [ ] **Step 9: Create `WebRtcVideoView.kt`** — Compose wrapper for the renderer:

```kotlin
package dev.rodolphe.syeksodemo.core.webrtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

@Composable
fun WebRtcVideoView(
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    onRenderer: (SurfaceViewRenderer) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setEnableHardwareScaler(true)
                onRenderer(this)
            }
        },
    )
    DisposableEffect(Unit) { onDispose { /* renderer released with the call session */ } }
}
```

- [ ] **Step 10: Compile the module.** `./gradlew :core:webrtc:compileDebugKotlin` → `BUILD SUCCESSFUL`. If the SDK flags an API signature (observer method, factory call), adjust the import/signature to the resolved `stream-webrtc-android` version — the shape above is the standard org.webrtc contract.

- [ ] **Step 11: Checkpoint.**

---

## Task 4: Resident — answer + callee session (TDD) + call UI

**Files:** `feature/intercomcall/build.gradle.kts` (add `:core:webrtc`), `IncomingCallUiState.kt`, `IncomingCallViewModel.kt`, `IncomingCallOverlay.kt`; Test `IncomingCallViewModelTest.kt` (+ `FakeWebRtcSession.kt`).

- [ ] **Step 1: Add deps** to `feature/intercomcall/build.gradle.kts`: `implementation(projects.core.webrtc)`.

- [ ] **Step 2: Extend `IncomingCallUiState.kt`** — add an in-call state:

```kotlin
sealed interface IncomingCallUiState {
    data object None : IncomingCallUiState
    data class Ringing(val callId: String, val doorName: String) : IncomingCallUiState
    data class InCall(val doorName: String, val openMessage: String? = null) : IncomingCallUiState
    data class Result(val success: Boolean, val message: String) : IncomingCallUiState
}
```

(Removes `Opening`; the open feedback now shows inside `InCall.openMessage`.)

- [ ] **Step 3: Create the test double `FakeWebRtcSession.kt`** (test source):

```kotlin
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
```

- [ ] **Step 4: Write the failing tests `IncomingCallViewModelTest.kt`** (replace the file; the ViewModel now takes a `WebRtcSessionFactory`-like provider — inject a `() -> WebRtcSession` lambda for testability). Add a `FakeSignaling` as in 4a.

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakeSignaling : Signaling {
        val flow = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<SignalingMessage> = flow
        val sent = mutableListOf<SignalingMessage>()
        override fun start(url: String, hello: SignalingMessage.Hello) {}
        override fun send(msg: SignalingMessage) { sent.add(msg) }
        override fun stop() {}
    }

    private fun vm(sig: FakeSignaling, rtc: FakeWebRtcSession) =
        IncomingCallViewModel(sig) { rtc }

    @Test fun `answer sends Accept and starts the callee session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        assertEquals(SignalingMessage.Accept("c1"), sig.sent.single())
        assertTrue(rtc.calls.contains("startAsCallee"))
        assertTrue(viewModel.uiState.value is IncomingCallUiState.InCall)
    }

    @Test fun `incoming offer creates an answer and sends it`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        sig.flow.emit(SignalingMessage.Offer("c1", "OFFER")); runCurrent()
        assertTrue(rtc.calls.contains("onRemoteSdp:offer"))
        assertTrue(rtc.calls.contains("createAnswer"))
        rtc.flow.emit(WebRtcEvent.LocalSdp("ANSWER", "answer")); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Answer("c1", "ANSWER") })
    }

    @Test fun `local ice is sent as IceCandidate`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        rtc.flow.emit(WebRtcEvent.LocalIce("cand", "0", 0)); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.IceCandidate("c1", "cand", "0", 0) })
    }

    @Test fun `incoming ice is added to the session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        sig.flow.emit(SignalingMessage.IceCandidate("c1", "cand", "0", 0)); runCurrent()
        assertTrue(rtc.calls.contains("addRemoteIce"))
    }

    @Test fun `open during call sends OPEN and open_result shows the message, call stays`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        viewModel.onOpen(); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Open("c1") })
        sig.flow.emit(SignalingMessage.OpenResult("c1", true)); runCurrent()
        val s = viewModel.uiState.value
        assertTrue(s is IncomingCallUiState.InCall && s.openMessage == "Porte ouverte")
    }

    @Test fun `hangup sends Hangup, closes the session and clears`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        viewModel.onHangup(); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Hangup("c1") })
        assertTrue(rtc.calls.contains("close"))
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }

    @Test fun `remote hangup closes and clears`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
        sig.flow.emit(SignalingMessage.Hangup("c1")); runCurrent()
        assertTrue(rtc.calls.contains("close"))
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }
}
```

- [ ] **Step 5: Run tests to verify they fail.** `./gradlew :feature:intercomcall:testDebugUnitTest` → FAIL (new signature / states unresolved).

- [ ] **Step 6: Implement `IncomingCallViewModel.kt`** — owns the callee session, wires signaling↔session:

```kotlin
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
import javax.inject.Inject

@HiltViewModel
class IncomingCallViewModel(
    private val signaling: Signaling,
    private val sessionProvider: () -> WebRtcSession,
    val eglContext: org.webrtc.EglBase.Context? = null,   // null in tests (no rendering)
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
                        callId = msg.callId; doorName = msg.doorName ?: "Porte"
                        _uiState.value = IncomingCallUiState.Ringing(msg.callId, doorName)
                    }
                    is SignalingMessage.Offer -> if (msg.callId == callId) {
                        session?.onRemoteSdp(msg.sdp, "offer"); session?.createAnswer()
                    }
                    is SignalingMessage.IceCandidate -> if (msg.callId == callId) {
                        session?.addRemoteIce(msg.sdp, msg.sdpMid, msg.sdpMLineIndex)
                    }
                    is SignalingMessage.OpenResult -> if (msg.callId == callId) {
                        _uiState.value = IncomingCallUiState.InCall(
                            doorName, if (msg.success) "Porte ouverte" else "Échec de l'ouverture",
                        )
                    }
                    is SignalingMessage.Hangup -> if (msg.callId == callId) endCall()
                    is SignalingMessage.ErrorMsg -> if (msg.callId == callId) {
                        _uiState.value = IncomingCallUiState.Result(false, msg.message); endSession()
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
```

- [ ] **Step 7: Run tests to verify they pass.** `./gradlew :feature:intercomcall:testDebugUnitTest` → PASS.

- [ ] **Step 8: Update `IncomingCallOverlay.kt`** — Ringing → Répondre/Ignorer; InCall → video + Ouvrir/Raccrocher; Result → message + Fermer. Render the remote video via `WebRtcVideoView`, attaching the renderer to `viewModel.liveSession`.

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcVideoView

@Composable
fun IncomingCallOverlay(viewModel: IncomingCallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state is IncomingCallUiState.None) return

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = state) {
                is IncomingCallUiState.Ringing -> {
                    Text("Appel entrant", style = MaterialTheme.typography.headlineSmall)
                    Text("Quelqu'un sonne à « ${s.doorName} »", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = viewModel::onDecline) { Text("Ignorer") }
                        Button(onClick = viewModel::onAnswer) { Text("Répondre") }
                    }
                }
                is IncomingCallUiState.InCall -> {
                    viewModel.eglContext?.let { egl ->
                        WebRtcVideoView(
                            eglContext = egl,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onRenderer = { r -> viewModel.liveSession?.attachRemoteVideo(r) },
                        )
                    }
                    s.openMessage?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = viewModel::onHangup) { Text("Raccrocher") }
                        Button(onClick = viewModel::onOpen) { Text("Ouvrir") }
                    }
                }
                is IncomingCallUiState.Result -> {
                    Text(s.message, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { }) { Text("Fermer") }
                }
                IncomingCallUiState.None -> {}
            }
        }
    }
}
```

Note: the video renderer's GL context comes from `viewModel.eglContext` (exposed by the ViewModel from the injected `WebRtcSessionFactory`), so the overlay needs no extra factory injection; the renderer binds to `viewModel.liveSession` directly.

- [ ] **Step 9: Assemble.** `./gradlew :feature:intercomcall:assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 10: Checkpoint.**

---

## Task 5: Intercom — caller session (TDD) + in-call UI

**Files:** `intercom/build.gradle.kts` (add `:core:webrtc`), `call/CallUiState.kt`, `call/CallViewModel.kt`, `IntercomHomeScreen.kt`; Test `call/CallViewModelTest.kt` (+ reuse `FakeWebRtcSession`).

- [ ] **Step 1: Add deps** to `intercom/build.gradle.kts`: `implementation(projects.core.webrtc)`.

- [ ] **Step 2: Extend `CallStatus` in `call/CallUiState.kt`** — add `InCall`:

```kotlin
sealed interface CallStatus {
    data object Idle : CallStatus
    data object Ringing : CallStatus
    data object InCall : CallStatus
    data class Ended(val message: String) : CallStatus
}
```

(`Opening` is dropped; a door open during a call keeps `InCall`.)

- [ ] **Step 3: Create `call/FakeWebRtcSession.kt`** in the intercom test source (identical to Task 4's, package `dev.rodolphe.syeksodemo.intercom.call`).

- [ ] **Step 4: Write the failing tests `call/CallViewModelTest.kt`** — extend the 4a test with the caller webrtc wiring (inject a `() -> WebRtcSession` provider):

```kotlin
    // new tests (keep the 4a directory/ring tests, updating vm() to pass the session provider):

    @Test fun `incoming Accept starts the caller session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, FakeSyeksoBleController(), rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Accept(callId)); runCurrent()
        assertTrue(rtc.calls.contains("startAsCaller"))
        assertEquals(CallStatus.InCall, viewModel.uiState.value.status)
    }

    @Test fun `local sdp is sent as Offer, incoming Answer is applied`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, FakeSyeksoBleController(), rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Accept(callId)); runCurrent()
        rtc.flow.emit(dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent.LocalSdp("OFFER", "offer")); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Offer(callId, "OFFER") })
        sig.flow.emit(SignalingMessage.Answer(callId, "ANSWER")); runCurrent()
        assertTrue(rtc.calls.contains("onRemoteSdp:answer"))
    }

    @Test fun `hangup closes the caller session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, FakeSyeksoBleController(), rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Accept(callId)); runCurrent()
        sig.flow.emit(SignalingMessage.Hangup(callId)); runCurrent()
        assertTrue(rtc.calls.contains("close"))
    }
```

Update the test's `vm(...)` helper to `CallViewModel(signaling, ble, directoryProvider, config) { rtc }` and add a `FakeSignaling` that also exposes an inbound `flow` (as in Task 4).

- [ ] **Step 5: Run tests to verify they fail.** `./gradlew :intercom:testDebugUnitTest` → FAIL.

- [ ] **Step 6: Extend `call/CallViewModel.kt`** — add the caller session. Inject a session provider and, on incoming `Accept`, start as caller and wire events; handle `Answer`/`IceCandidate`/`Hangup`; keep `Open` → BLE. Constructor:

```kotlin
@HiltViewModel
class CallViewModel(
    private val signaling: Signaling,
    private val bleController: SyeksoBleController,
    private val directoryProvider: DirectoryProvider,
    private val config: IntercomConfig,
    private val sessionProvider: () -> WebRtcSession,
) : ViewModel() {

    @Inject constructor(
        signaling: Signaling, bleController: SyeksoBleController,
        directoryProvider: DirectoryProvider, config: IntercomConfig, factory: WebRtcSessionFactory,
    ) : this(signaling, bleController, directoryProvider, config, { factory.create() })
```

In the `signaling.incoming.collect` when-block add:

```kotlin
                    is SignalingMessage.Accept -> if (msg.callId == currentCallId) startCaller(msg.callId)
                    is SignalingMessage.Answer -> if (msg.callId == currentCallId) session?.onRemoteSdp(msg.sdp, "answer")
                    is SignalingMessage.IceCandidate -> if (msg.callId == currentCallId) session?.addRemoteIce(msg.sdp, msg.sdpMid, msg.sdpMLineIndex)
                    is SignalingMessage.Hangup -> if (msg.callId == currentCallId) endCall()
```

Add:

```kotlin
    private var session: WebRtcSession? = null

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

    fun hangup() { currentCallId?.let { signaling.send(SignalingMessage.Hangup(it)) }; endCall() }

    private fun endCall() { session?.close(); session = null; currentCallId = null; _uiState.update { it.copy(status = CallStatus.Ended("Terminé")) } }

    override fun onCleared() { session?.close() }
```

The existing `doOpen` (from 4a) stays, but on `OpenResult` it should **not** end the call — since 4b keeps the call, remove the `end(...)` call inside `doOpen` and instead just leave the status `InCall` (the resident sees the result; the intercom keeps talking).

- [ ] **Step 7: Run tests to verify they pass.** `./gradlew :intercom:testDebugUnitTest` → PASS.

- [ ] **Step 8: Update `ContactPanel` in `IntercomHomeScreen.kt`** — show the in-call state + Raccrocher, and request **camera + microphone** before ringing (gate pattern). When `status == CallStatus.InCall`, show "En communication…" and a **Raccrocher** button (`viewModel::hangup`). Add a `rememberLauncherForActivityResult` requesting `CAMERA`+`RECORD_AUDIO`, and only call `viewModel.ring()` once granted (mirror the 4a BLE gate in `IntercomRoute`).

- [ ] **Step 9: Assemble + install on the intercom device.** `./gradlew :intercom:assembleDebug` → `BUILD SUCCESSFUL`; install.

- [ ] **Step 10: Checkpoint.**

---

## Task 6: End-to-end verification (manual)

- [ ] **Step 1:** Backend restarted; ESP32 on; both devices on the same Wi-Fi; latest resident app on device 1, latest intercom on device 2; grant CAMERA/RECORD_AUDIO (intercom) and RECORD_AUDIO (resident).
- [ ] **Step 2:** Intercom → CONTACT → rodolphe → **Sonner**. Resident overlay → **Répondre**.
- [ ] **Step 3:** Resident sees **live video** of the visitor and **two-way audio** works (talk both ways).
- [ ] **Step 4:** Resident taps **Ouvrir** → ESP32 pulses blue, "Porte ouverte" shown, **the call continues**.
- [ ] **Step 5:** **Raccrocher** (either side) → video stops, camera/mic released on both, overlays dismissed.
- [ ] **Step 6:** Edge cases — deny mic (resident) → "Micro requis"; deny camera (intercom) → "Appel indisponible"; put a device on a different network → media setup times out → "Connexion impossible".
- [ ] **Step 7: Checkpoint** — iteration 4b done; ready to commit (both repos).

---

## Self-review notes

- **Spec coverage:** signaling extension + hub IN_CALL relay (T1), app DTOs (T2), `core:webrtc` module incl. `WebRtcSession`/factory/DI/video view (T3), resident answerer VM + call UI (T4), intercom caller VM + in-call UI (T5), full E2E incl. mic/cam-denied + not-same-LAN (T6). All spec sections mapped.
- **Type consistency:** the sealed `SignalingMessage` subtypes (`Accept/Offer/Answer/IceCandidate/Hangup`, fields `callId/sdp/sdpMid/sdpMLineIndex`) are identical across backend `SignalingMessages.kt` and app `NetworkModels.kt`, and used identically in the hub, both ViewModels, and the fakes. `WebRtcSession` (`startAsCaller/startAsCallee/onRemoteSdp/createAnswer/addRemoteIce/attachRemoteVideo/close`, `events: Flow<WebRtcEvent>`) and `WebRtcEvent` (`LocalSdp/LocalIce/RemoteVideoReady/ConnectionState`) match across `core:webrtc`, both ViewModels, and `FakeWebRtcSession`. `WebRtcSessionFactory.create()/eglContext()` used consistently. Hub methods `onAcceptCall/onOpenCall/onOpenResultReported/onHangupCall/relayFromResident/relayFromIntercom` match the `/ws` dispatch.
- **Deferred (out of scope, per spec):** TURN, two-way video, foreground service for backgrounded calls, intercom self-preview, call history/recording, FCM push, BLE challenge-response.
```
