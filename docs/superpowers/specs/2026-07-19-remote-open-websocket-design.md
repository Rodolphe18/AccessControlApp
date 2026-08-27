# Iteration 4a — Remote door open via WebSocket relay (design)

**Date:** 2026-07-19
**Status:** Approved (brainstorm)
**Scope:** Iteration 4a of the Syekso resident demo. Decomposed from the WebRTC video-intercom
feature (item #6): **4a = remote door open (no media)**; **4b = WebRTC video/audio call** (later,
builds on this signaling foundation).

## Goal

A visitor at the intercom selects a resident from the directory and rings. The resident, on their
phone, receives an incoming-call notification in real time, taps **Ouvrir**, and the intercom opens
the door — surfacing the real result back to the resident. No BLE from the resident's phone: the
resident authorizes over the internet; the **intercom** (which is physically at the door) performs the
BLE open of the ESP32.

This mirrors the real Oskeys **Intercom+** product: a wall-mounted, always-on, internet-connected
touchscreen panel (very likely embedded Android/AOSP in kiosk mode) with **CONTACT** (directory → call)
and **CODE** (PIN keypad) actions, wired to the door strike. Our Android intercom app is a faithful
stand-in for that panel's kiosk app; the wired relay is stood in by **BLE → ESP32**.

## Topology

The door itself is never directly online. The always-online device *at* the door (the intercom)
actuates it, on an internet command:

```
Résident (app, n'importe où) ──internet──▶ Backend ──internet──▶ Interphone ──local (BLE)──▶ ESP32
```

The internet hop is resident → backend → intercom; the last hop (intercom → door) is local. Real
product: intercom → wired relay/strike. Demo: intercom → `core:ble` → ESP32.

## Architecture — Backend WebSocket relay hub (Approach 1)

Both apps hold a persistent WebSocket to the backend Ktor server. The backend is the **hub**: it
authenticates, registers connections, and **routes + correlates** signaling messages between the
selected resident and the intercom. This is real-time, works across networks, reuses the existing
backend and `core:ble`, and is the exact signaling foundation that WebRTC (4b) will reuse (SDP/ICE will
flow through the same hub).

Rejected alternatives: HTTP polling (laggy, not real-time, builds no WebRTC foundation); REST+WS hybrid
(two mechanisms for no benefit).

## Section 1 — Connection & pairing model

Single WebSocket endpoint `GET /ws` (`ktor-server-websockets`). Auth via a first `HELLO` message
(header auth is awkward on Android WS clients; a first frame is clean and reused by WebRTC):

- **Resident:** `HELLO { role: "resident", jwt }` → backend verifies the JWT (existing `JwtService`),
  resolves `userId`, registers in `residents: Map<userId, ClientConnection>`.
- **Intercom:** `HELLO { role: "intercom", intercomKey, buildingId }` → backend validates the intercom
  key, registers in `intercoms: Map<buildingId, ClientConnection>` (demo: one intercom per building, so
  `buildingId` is the intercom's routing key).

**Directory (targeted ring, not building broadcast).** A real intercom has a directory: the visitor
**selects** the resident (apartment) they are visiting, and only that resident is rung. The intercom
fetches the directory via `GET /intercom/directory?buildingId=…` (auth: intercom key) →
`[{ userId, displayName }]`. For the demo, `bld-montmartre`'s directory has a single resident,
**rodolphe**, preselected.

**Targeted ring.** On confirm, the intercom sends `RING { callId, targetUserId }`. The backend routes
the `RING` **only** to that `targetUserId`'s session. If that resident is not connected → the backend
replies to the intercom `ERROR("Résident indisponible")`.

**Correlation.** A `callId` (UUID) ties `RING → OPEN → RESULT` for one exchange.

**Lifecycle.** Registries are purged when a WebSocket closes; an `OPEN` toward an intercom absent from
`intercoms` → `ERROR("Interphone hors ligne")` to the resident.

## Section 2 — Message protocol & call state machine

Each message is JSON with a `type` discriminator (kotlinx.serialization sealed classes on both the Ktor
and app sides, `Json { classDiscriminator = "type" }`), correlated by `callId`.

```
Intercom → Backend               Backend → Resident
  HELLO(intercom,key,bldg)         RING(callId, doorName, visitorLabel?)
  RING(callId, targetUserId,       OPEN_RESULT(callId, success, reason?)
       doorName)                   ERROR(callId?, message)
  OPEN_RESULT(callId, ok, …)

Resident → Backend            Backend → Intercom
  HELLO(resident, jwt)          OPEN(callId)
  OPEN(callId)                  DECLINE(callId)
  DECLINE(callId)               ERROR(callId?, message)
```

The backend relays and correlates: `RING` intercom→targeted resident, `OPEN`/`DECLINE`
resident→intercom, `OPEN_RESULT` (the real BLE outcome) intercom→resident. The intercom knows its own
door locally (configured `doorName` + `doorBleLocalName`, e.g. Porte d'entrée / `OSKEY-HALL-01`): it
puts `doorName` in the `RING` for the resident's display, and on `OPEN` it opens that known door — so
the `OPEN` message carries no BLE name.

State machine per `callId` (tracked in memory on the backend):

```
        RING(intercom)              OPEN(resident)         OPEN_RESULT ok
 IDLE ───────────────▶ RINGING ───────────────▶ OPENING ───────────────▶ OPENED ✓
                          │  │                      │
             DECLINE ─────┘  │ timeout 30s          └── OPEN_RESULT fail ─▶ FAILED ✗
                DECLINED ✗    └────────▶ TIMED_OUT ✗
```

The backend keeps `Map<callId, CallState(buildingId, residentUserId, status)>` to validate that an
`OPEN` matches a real, still-`RINGING` call (not already opened/declined/expired) and to route the
result. Terminal states purge the entry.

**Timeout:** if the resident does not answer within **30 s**, the backend moves the call to
`TIMED_OUT` and notifies both (intercom shows "Pas de réponse"; the resident's incoming-call screen is
dismissed).

**Disconnect mid-call:** if either WebSocket drops during `RINGING`/`OPENING`, the call is cancelled
and the other party receives an `ERROR`.

## Section 3 — Backend components (`AccessControllerServer`, package `dev.rodolphe.accesscontrol`)

**Dependency:** add `io.ktor:ktor-server-websockets` + `install(WebSockets)` in `Application.kt`.

**`signaling/SignalingHub.kt`** — the in-memory hub, core of Approach 1:

```
residents : ConcurrentHashMap<userId, ClientConnection>
intercoms : ConcurrentHashMap<buildingId, ClientConnection>   // demo: one intercom per building
calls     : ConcurrentHashMap<callId, CallState>              // CallState(buildingId, residentUserId, status)

class ClientConnection(val session: WebSocketSession, val sendMutex: Mutex)
```

Methods: `registerResident/registerIntercom`, `unregister` (purge + cancel that connection's active
calls), `onRing/onOpen/onDecline/onOpenResult` (validate the Section 2 state transition, then route).
Each `ClientConnection` wraps its `WebSocketSession` with a **`Mutex`** to serialize sends (several
coroutines may write to the same session → otherwise interleaved frames). The 30 s timeout is one
`launch { delay(30s); if RINGING → onTimeout }` coroutine per call.

**`signaling/SignalingMessages.kt`** — the sealed `SignalingMessage` with `@SerialName`-ed subtypes
(`Hello`, `Ring`, `Open`, `Decline`, `OpenResult`, `Error`), (de)serialized as text frames. **These
same DTOs are replicated in the app's `core:network`** (like the other wire types).

**`/ws` route** (in `Routing.kt`): accept the connection → read the first `HELLO` frame → authenticate
(resident: `JwtService.verify`; intercom: compare `intercomKey`) → `register…` → loop over `incoming`:
parse and dispatch to the hub. `finally { hub.unregister(connection) }` on close/exception.

**`GET /intercom/directory?buildingId=…`** (auth: intercom key): users whose `buildingIds` contains
the building → `DirectoryResponse([{ userId, displayName }])`. Feeds Section 1's directory.

**Files:** new `signaling/SignalingHub.kt`, `signaling/SignalingMessages.kt`; edited `Application.kt`
(install + route), `api/Routing.kt` (directory), `build.gradle.kts` (dependency).

## Section 4 — Android components (package `dev.rodolphe.syeksodemo`)

**`core:network`** — replicate the signaling DTOs (`SignalingMessage` sealed + subtypes,
`DirectoryResponse`) as wire types, and a **`SignalingClient`**: opens/maintains the WebSocket (Ktor
client `WebSockets`), exposes an inbound `Flow<SignalingMessage>` + `suspend fun send(msg)`, with
**auto-reconnect** while the app is foreground. Sits behind a transport interface so it is unit-testable
with a fake transport.

**New module `feature:intercomcall`** — the incoming-call experience (4b/video will graft onto it):
- **`IncomingCallViewModel`** (resident) subscribes to `SignalingClient`: on `RING` → "incoming call"
  state (door name, target resident); `Ouvrir` → sends `OPEN(callId)`; `Ignorer` → `DECLINE(callId)`;
  then shows `OPEN_RESULT` ("Porte ouverte ✓" / "Échec").
- A **full-screen incoming-call overlay** (over the app content) with **Ouvrir / Ignorer**. The
  resident WebSocket is held by a foreground-lifecycle-bound component.

**Intercom app (`intercom`)** — home screen modeled on the Intercom+ panel, with two actions:
- **CONTACT** → the **directory** (fetched via `GET /intercom/directory`; demo = single resident
  *rodolphe*, preselected) → **« Sonner »** → `RING(callId, targetUserId)`. On `OPEN(callId)` →
  **`bleController.open(doorBleLocalName)`** (reuses `core:ble`) → sends `OPEN_RESULT(callId, success,
  reason)` from the real BLE result. On `DECLINE`/timeout → "Refusé" / "Pas de réponse".
- **CODE** → the **existing PIN keypad** screen (local validation, unchanged). CONTACT and CODE
  coexist, matching the real panel's two buttons. Visual details (clock, address, building info) are
  refined at implementation time; the retained direction is the CONTACT/CODE home.

**Reused as-is:** `core:ble` (open), the intercom key, `SessionDataSource` (resident JWT for `HELLO`).

## Section 5 — Error handling & edge cases

| Case | Behavior |
|---|---|
| Resident not connected at `RING` | backend → intercom `ERROR("Résident indisponible")` → "Résident injoignable" |
| Intercom disconnected between `RING` and `OPEN` | backend can't route → resident `ERROR("Interphone hors ligne")` |
| Timeout 30 s (no answer) | `TIMED_OUT` → intercom "Pas de réponse"; resident's incoming-call screen dismissed |
| Resident declines (`DECLINE`) | intercom "Refusé" |
| BLE open fails (ESP32 not found…) | intercom sends `OPEN_RESULT(success=false, reason)` → the **real BLE result** reaches the resident: "Échec de l'ouverture" |
| Duplicate/late `OPEN` (call already opened/declined/expired) | state machine rejects → `ERROR("Appel expiré")`; exactly one valid `OPEN` per call |

**Connection/protocol robustness**
- Invalid `HELLO` auth (expired JWT or wrong intercom key) → backend **closes the WebSocket** with a
  reason; client shows an error, does not register.
- Malformed/unknown message → defensive parsing: ignored (or `ERROR`); the socket does not drop.
- WebSocket drop → auto-reconnect while foreground, re-sending `HELLO`. Any in-flight call tied to the
  dropped socket is **cancelled** (hub `unregister` purges that connection's active calls) — no
  mid-call resume in 4a (kept simple).

**Concurrency rules (demo)**
- **One call at a time per intercom:** "Sonner" disabled while a call is active (and the backend rejects
  a second `RING` from an intercom already in a call).
- **One session per `userId`:** a new resident `HELLO` replaces/closes the previous session (no
  multi-device in 4a).

**Accepted limitation:** resident app **backgrounded → WebSocket closed → no ring** (transport
trade-off; FCM is the production evolution). To mention verbally.

## Section 6 — Testing strategy

Follows the project's existing style (JUnit4 + `kotlinx-coroutines-test`, fakes, ViewModel TDD,
curl/manual E2E).

**Backend**
- **`SignalingHub` unit tests** (highest value): the state machine (`RING→RINGING`, `OPEN` valid only in
  `RINGING`, `DECLINE`, timeout, `OPEN_RESULT` routing), the registry (register/unregister, routing to
  the correct `userId`), and errors (resident unavailable, duplicate `OPEN`). Injected **fake sessions**
  (a test double of the send channel) — no real WebSocket.
- **`/ws` route integration test** via `ktor-server-test-host` (WebSocket client): `HELLO` auth
  (valid/invalid) + a full `RING→OPEN→OPEN_RESULT` round trip between two test clients.
- **Serialization round-trip** of the sealed `SignalingMessage` (each subtype encodes/decodes) —
  guards the shared wire format backend↔app.

**Android**
- **`SignalingClient`** behind a transport interface → tested with a fake transport (inbound parsing →
  `Flow`, outbound serialization).
- **ViewModels in TDD** with `FakeSignalingClient`: resident side, `RING` → "incoming call" state,
  `Ouvrir` → correct message, `OPEN_RESULT` → success/failure, timeout/decline; intercom side, "Sonner"
  → `RING`, `OPEN` → calls `bleController.open` (fake) → sends `OPEN_RESULT` with the real result.

**Manual E2E** (2 devices + backend + ESP32): rodolphe selected → Sonner → incoming call → Ouvrir →
ESP32 blue → "Porte ouverte"; plus the decline, timeout, resident-offline, and BLE-failure paths.

## Out of scope (future)

- **Iteration 4b — WebRTC video/audio call:** camera/mic media, SDP/ICE over this same hub, STUN
  (same-LAN P2P; TURN as production evolution), incoming-call video UI. Grafts onto `feature:intercomcall`.
- FCM push (ring while backgrounded/closed); multi-device resident sessions; mid-call resume;
  NFC/badge; building-info display; multilingual UI.
