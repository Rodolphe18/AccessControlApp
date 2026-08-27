# Iteration 4b — WebRTC video call (design)

**Date:** 2026-07-19
**Status:** Approved (brainstorm)
**Scope:** Iteration 4b of the Syekso resident demo — the **WebRTC video/audio call** layered on the
4a remote-open signaling foundation (`docs/superpowers/specs/2026-07-19-remote-open-websocket-design.md`).
4a already delivers: the backend WebSocket relay hub (`SignalingHub`, `/ws`), `feature:intercomcall`
(incoming-call overlay), the intercom CONTACT/CODE panel, `CallViewModel`, and door open over `core:ble`.

## Goal

When a visitor rings, the resident answers and sees live video of the visitor with two-way audio, talks
to them, and can open the door **during** the call. This matches the real Oskeys Intercom+ panel (a
wall-mounted, always-on Android/AOSP kiosk with camera + mic, wired to the door).

## WebRTC in one paragraph (for context)

Two devices want to send audio/video **directly** (peer-to-peer). First they negotiate two things via
signaling (relayed by our backend): **(1) what media & codecs** — described in **SDP** (Session
Description Protocol) text, exchanged as an **offer** (caller) and **answer** (callee); **(2) how to
reach each other on the network** — via **ICE**, where each side gathers **candidates** (possible
addresses: its LAN IP, its public IP via **STUN**, or a relay via **TURN**) and they test candidate
pairs until one connects. `Local*` = what my device produced and must send; `onRemote*`/`addRemoteIce`
= what the other side sent, fed into my connection. Once ICE connects, media flows **directly**,
bypassing the server.

## Media scope (decision A)

- **One-way video** (intercom camera → resident) **+ two-way audio** (both talk). The resident's camera
  is NOT sent. Faithful to a real intercom; lighter on bandwidth and permissions (resident = mic only).
- **STUN only** (`stun:stun.l.google.com:19302`): both devices on the **same LAN**, so host candidates
  connect P2P directly. No TURN server to deploy. Demo constraint = "same Wi-Fi"; TURN is the production
  evolution.

## Roles

- **Intercom = caller / offerer:** captures **camera (front) + microphone**, creates the SDP **offer**,
  sends 1 video + 1 audio stream.
- **Resident = callee / answerer:** captures **microphone only**, creates the SDP **answer**, renders
  the remote video and sends its audio.

## Section 1 — Architecture & signaling extension

Media flows **P2P** between the intercom and resident over the LAN; the backend only **relays signaling**
(SDP + ICE), correlated by the **existing `callId`** from 4a. No media transits the server.

New messages added to the sealed `SignalingMessage` (backend `signaling/SignalingMessages.kt` **and** app
`core:network`), all carrying `callId`:

```
Accept(callId)                                   // resident: "I'm answering" → triggers the offer on the intercom
Offer(callId, sdp)                               // intercom → resident
Answer(callId, sdp)                              // resident → intercom
IceCandidate(callId, sdp, sdpMid, sdpMLineIndex) // both directions
Hangup(callId)                                   // both directions: end of call
```

The `SignalingHub` **relays** these between the two peers of a call (it already knows
`{buildingId, residentUserId}` per `callId`): `Accept`/`Answer`/resident `IceCandidate` → intercom;
`Offer`/intercom `IceCandidate` → resident; `Hangup` → the other peer. The 4a `OPEN`/`OPEN_RESULT`
**remain** and work **during** the call (opening does not interrupt the conversation).

## Section 2 — `core:webrtc` module

An isolated module hiding the **`stream-webrtc-android`** SDK (the maintained Android fork of libwebrtc)
behind an interface, like `core:ble` hides BLE.

```kotlin
interface WebRtcSession {
    val events: Flow<WebRtcEvent>
    fun startAsCaller()                                   // intercom: capture camera+mic, create the offer
    fun startAsCallee()                                   // resident: capture mic, ready to receive video
    fun onRemoteSdp(sdp: String, type: String)            // set remote description
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
```

**Provided by Hilt (singletons):** `EglBase` (shared GPU/GL context so the camera capturer, hardware
codecs, and the on-screen renderer all share video-frame textures without copies), `PeerConnectionFactory`
(built once with the `EglBase` context), and a `WebRtcSessionFactory` that makes one `WebRtcSession` per
call (with the STUN `iceServers`). The caller adds camera + mic tracks; the callee adds a mic track and
receives the remote video track.

**Video rendering:** a Compose `WebRtcVideoView(renderer)` = an `AndroidView` wrapping the native
`SurfaceViewRenderer`, initialized with the `EglBase` context.

**Permissions** (declared in `core:webrtc`'s manifest, merged into both apps): `CAMERA` + `RECORD_AUDIO`.

## Section 3 — Call flow & state machine

The 4a overlay changes: instead of `Ouvrir / Ignorer`, it offers **`Répondre` / `Ignorer`**. On
`Répondre`, it switches to a full-screen **call screen** (visitor video + audio) with **`Ouvrir`**
(reuses the 4a mechanism) and **`Raccrocher`**.

Full sequence (R = resident, I = intercom; hub relays everything by `callId`):

```
I: CONTACT → Sonner ──RING──▶ R (overlay "Répondre / Ignorer")
R: Répondre ──Accept──▶ I
I: startAsCaller() → LocalSdp(offer) ──Offer──▶ R
R: onRemoteSdp(offer); startAsCallee(); createAnswer() → LocalSdp(answer) ──Answer──▶ I
I & R: LocalIce(...) ──IceCandidate──▶ other → addRemoteIce(...)   (in parallel)
→ P2P media: R sees the video + two-way audio  🎥🔊
   During the call:
     R: Ouvrir ──OPEN──▶ I → bleController.open() → OPEN_RESULT──▶ R ("Porte ouverte")  [call CONTINUES]
     R or I: Raccrocher ──Hangup──▶ other → both close their WebRtcSession → end
```

Backend hub state machine (extends 4a):

```
                RING            Accept                 Hangup / timeout / disconnect
   IDLE ───────────▶ RINGING ───────────▶ IN_CALL ──────────────────────────▶ ENDED
                        │                    │
              Decline/timeout ✗              └── OPEN → relayed, OPEN_RESULT relayed, stays IN_CALL
```

Key change vs 4a: **`OPEN` during `IN_CALL` no longer terminates the call** (in 4a it consumed it) —
opening the door and continuing to talk is the real intercom behavior. The call ends only on `Raccrocher`
(or a setup timeout / disconnect). During `IN_CALL` the hub relays
`Offer/Answer/IceCandidate/Open/OpenResult/Hangup` between the two peers.

**Teardown:** on `Hangup` (or a peer's WebSocket dropping), **both** close their `WebRtcSession` (camera/mic
released, `PeerConnection.close()`), and the overlay/call screen disappears.

**Timeouts:** if the resident does not `Répondre` within **30 s** → `TIMED_OUT` (as in 4a). A second,
shorter media-setup timeout (~15 s): if the `Answer`/ICE never completes → the call fails cleanly
("Connexion impossible").

## Section 4 — Android components

**`core:webrtc`** (Section 2): `WebRtcSession` + `WebRtcSessionFactory` + DI (`EglBase`,
`PeerConnectionFactory`, STUN) + the `WebRtcVideoView` composable.

**`core:network` + backend:** add `Accept/Offer/Answer/IceCandidate/Hangup` to the sealed
`SignalingMessage` (both sides); the `SignalingHub` relays them to the other peer of the `callId` (like
`Open`/`Decline`) and keeps the call in `IN_CALL` (Section 3).

**Resident (`feature:intercomcall`):**
- The `Ringing` overlay → **`Répondre` / `Ignorer`** buttons. On `Répondre`: request **microphone**
  permission, send `Accept`, create a `WebRtcSession` as **callee**.
- A full-screen **call screen**: `WebRtcVideoView` (visitor video) + **`Ouvrir`** (sends `OPEN`, reuses
  4a, shows "Porte ouverte" without ending the call) + **`Raccrocher`** (sends `Hangup`, closes the
  session).
- Media logic in the call ViewModel: `incoming Offer` → `onRemoteSdp` + `createAnswer`; `event LocalSdp`
  → send `Answer`; `event LocalIce` → send `IceCandidate`; `incoming IceCandidate` → `addRemoteIce`;
  `RemoteVideoReady` → `attachRemoteVideo`.

**Intercom (`intercom`):**
- On `incoming Accept`: request **camera + microphone**, create a `WebRtcSession` as **caller**,
  `startAsCaller()`. `event LocalSdp` → `Offer`; `event LocalIce` → `IceCandidate`; `incoming Answer` →
  `onRemoteSdp`; `incoming IceCandidate` → `addRemoteIce`; `Hangup` → `close`. `OPEN` still triggers
  `bleController.open()` (unchanged).
- `ContactPanel` shows an **"En communication…"** state + **`Raccrocher`** during the call (no
  self-preview — out of scope).

**Permissions:** reuse the 4a **gate pattern** (check → request → act in the callback). Resident:
`RECORD_AUDIO`. Intercom: `CAMERA` + `RECORD_AUDIO`.

**Testability:** the ViewModels talk only to `WebRtcSession` (interface) + `Signaling` (interface) →
**fakeable**; the events↔messages mapping is **unit-testable**; the native media is **manual E2E**.

## Section 5 — Error handling & edge cases

Layered on 4a's handling; media-specific cases:

| Case | Behavior |
|---|---|
| Resident denies microphone | no two-way audio → "Micro requis pour répondre"; stays on the overlay (can Ignore) |
| Intercom denies camera/mic | cannot be caller → sends `Hangup`/`Error` → resident sees "Appel indisponible" |
| Media-setup timeout (~15 s): `Answer`/ICE never completes (often: not the same LAN) | "Connexion impossible", teardown, end call |
| ICE fails / connection lost mid-call: `ConnectionState(failed/disconnected)` | treated as a hangup → both tear down, "Appel interrompu" |
| A peer disconnects (WebSocket drops) during the call | the hub cancels the call → `Hangup`/`Error` to the other → media closed |
| `Raccrocher` (either side) | **both** close `WebRtcSession`: camera/mic **released**, `PeerConnection.close()`, UI returns |
| `Ouvrir` fails (BLE) during the call | `OPEN_RESULT(success=false)` relayed → "Échec de l'ouverture", **the call continues** |
| App backgrounded during the call | demo: simplified — the WebSocket may drop → the call ends. Accepted limitation (a real intercom uses a foreground service) |
| Re-ring / double call | 4a's "one call at a time per intercom" holds: no new call while `IN_CALL` |

**Resource leaks (critical):** guarantee `WebRtcSession.close()` **always** runs at call end
(camera/mic/renderer/`PeerConnection`) — otherwise the camera or mic stays locked. Tie it to the
lifecycle (teardown on `Hangup`, `Error`, disconnect, and the ViewModel's `onCleared`).

**Audio echo:** enable WebRTC's built-in **acoustic echo cancellation (AEC)** (default audio constraints
of the SDK) — otherwise feedback with two nearby mics/speakers.

## Section 6 — Testing strategy

The native WebRTC media is not unit-testable (GPU/camera/device). Test all the surrounding logic; the
media is manual E2E — same approach as BLE in 4a.

**Backend**
- **`SignalingHub` (unit)** — extend: the hub relays `Accept/Offer/Answer/IceCandidate/Hangup` to the
  correct peer of the `callId`; the call **stays `IN_CALL`** after an `OPEN`/`OPEN_RESULT`; it ends on
  `Hangup` (and on a peer disconnect). Fake sessions as in 4a.
- **Serialization** — extend the round-trip to the new `SignalingMessage` subtypes.

**Android (highest value)**
- **`FakeWebRtcSession`** (implements `WebRtcSession`, records calls, emits `WebRtcEvent` via a flow) +
  `FakeSignaling` → the call ViewModels are **unit-testable** on the whole messages↔session wiring:
  - Resident: `Répondre` → sends `Accept` + creates the session as **callee**; `incoming Offer` →
    `onRemoteSdp` + `createAnswer`; `event LocalSdp` → sends `Answer`; `event LocalIce` → sends
    `IceCandidate`; `incoming IceCandidate` → `addRemoteIce`; `Raccrocher`/`Hangup` → `close` + state.
  - Intercom: `incoming Accept` → creates the session as **caller** + `startAsCaller`; `event LocalSdp`
    → `Offer`; `incoming Answer` → `onRemoteSdp`; `Hangup` → `close`.
- **`core:webrtc`**: the real implementation wraps the native SDK → **not unit-testable**; an
  instrumented test is **deferred**. The interface + factory are thin.

**Manual E2E** (2 devices + ESP32): Sonner → Répondre → the resident **sees the video + talks** →
**Ouvrir** (ESP32 blue, the call **continues**) → **Raccrocher** (both release camera/mic). Plus:
mic denied, intercom camera denied, **not the same LAN** (→ "Connexion impossible" timeout), hangup from
the intercom, disconnect mid-call.

## Out of scope (future / production)

- TURN server (cross-NAT/internet); two-way video; a foreground service so calls survive backgrounding;
  a self-preview on the intercom; call history/recording; FCM push to ring a closed resident app;
  the BLE challenge-response security upgrade (nonce + HMAC).
