# Remote door open via WebSocket relay (iteration 4a) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Git note:** the repo owner commits/pushes. Do NOT run `git commit`/`git push`. "Checkpoint" = tell the owner it's ready to commit.

**Goal:** A visitor at the intercom picks a resident and rings; the resident's phone shows an incoming call in real time and taps **Ouvrir**; the intercom opens the ESP32 over BLE and the real result flows back — all over a backend WebSocket relay.

**Architecture:** Both apps hold a WebSocket to the Ktor backend, which authenticates, registers connections, and routes/correlates signaling messages (`RING → OPEN → OPEN_RESULT`) between the selected resident and the intercom. The intercom performs the BLE open (reusing `core:ble`); the resident never touches BLE. This is the signaling foundation iteration 4b (WebRTC) will reuse.

**Tech Stack:** Kotlin; backend Ktor 3.5.1 + `ktor-server-websockets` + MongoDB; app Compose + Hilt + OkHttp (WebSocket) + Coroutines; tests JUnit4 + kotlinx-coroutines-test + `ktor-server-test-host`.

**Spec:** `docs/superpowers/specs/2026-07-19-remote-open-websocket-design.md`

---

## File structure

**Backend** (`AccessControllerServer`, package `dev.rodolphe.accesscontrol`):
- Create `signaling/SignalingMessages.kt` — sealed wire types + shared `Json`.
- Create `signaling/SignalingHub.kt` — `ClientConnection`, `CallState`, registry + state machine + routing.
- Modify `api/Dto.kt` — `DirectoryResponse` + `DirectoryEntry`.
- Modify `api/Routing.kt` — `GET /intercom/directory` + the `webSocket("/ws")` route.
- Modify `Application.kt` — `install(WebSockets)`, build the hub, pass it to routing.
- Modify `security/JwtService.kt` — add `userIdFromToken(token): String?`.
- Modify `build.gradle.kts` — add `ktor-server-websockets`.

**App** (package `dev.rodolphe.syeksodemo`):
- Modify `core/network/.../model/NetworkModels.kt` — signaling DTOs + `DirectoryResponseNetwork`.
- Create `core/network/.../signaling/SignalingTransport.kt` — transport interface + OkHttp impl.
- Create `core/network/.../signaling/SignalingClient.kt` — message (de)serialization + `Flow` + reconnect.
- Modify `core/network/.../SyeksoApiService.kt` — `getDirectory`.
- Create module `feature:intercomcall` — `IncomingCallViewModel`, `IncomingCallUiState`, `IncomingCallOverlay.kt`; tests.
- Modify `intercom/.../IntercomHomeScreen.kt` (new) + `intercom/.../call/CallViewModel.kt` (new) + tests; keep the existing keypad.
- Modify `app/.../navigation` + `settings.gradle.kts` — register `feature:intercomcall`, host the overlay.

---

## Task 1: Backend — signaling wire types + directory endpoint

**Files:** Create `signaling/SignalingMessages.kt`; Modify `api/Dto.kt`, `api/Routing.kt`.

- [ ] **Step 1: Create `signaling/SignalingMessages.kt`.**

```kotlin
package dev.rodolphe.accesscontrol.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire messages exchanged over /ws. `type` is the JSON discriminator. Kept identical on the app side
 *  (core:network). Fields are nullable where a role/direction doesn't use them. */
@Serializable
sealed interface SignalingMessage {
    @Serializable @SerialName("hello")
    data class Hello(
        val role: String,                 // "resident" | "intercom"
        val jwt: String? = null,          // resident
        val intercomKey: String? = null,  // intercom
        val buildingId: String? = null,   // intercom
    ) : SignalingMessage

    @Serializable @SerialName("ring")
    data class Ring(
        val callId: String,
        val targetUserId: String? = null, // set by intercom
        val doorName: String? = null,     // set by intercom, forwarded to resident
    ) : SignalingMessage

    @Serializable @SerialName("open")
    data class Open(val callId: String) : SignalingMessage

    @Serializable @SerialName("decline")
    data class Decline(val callId: String) : SignalingMessage

    @Serializable @SerialName("open_result")
    data class OpenResult(val callId: String, val success: Boolean, val reason: String? = null) : SignalingMessage

    @Serializable @SerialName("error")
    data class ErrorMsg(val callId: String? = null, val message: String) : SignalingMessage
}

val signalingJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}
```

- [ ] **Step 2: Write the failing serialization test** `src/test/kotlin/dev/rodolphe/accesscontrol/signaling/SignalingMessagesTest.kt`.

```kotlin
package dev.rodolphe.accesscontrol.signaling

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalingMessagesTest {
    private fun roundTrip(msg: SignalingMessage) {
        val json = signalingJson.encodeToString(SignalingMessage.serializer(), msg)
        val back = signalingJson.decodeFromString(SignalingMessage.serializer(), json)
        assertEquals(msg, back)
    }

    @Test fun `ring round-trips with type discriminator`() {
        val json = signalingJson.encodeToString(
            SignalingMessage.serializer(),
            SignalingMessage.Ring("c1", targetUserId = "u1", doorName = "Porte d'entrée"),
        )
        assertEquals(true, json.contains("\"type\":\"ring\""))
        roundTrip(SignalingMessage.Ring("c1", "u1", "Porte d'entrée"))
    }

    @Test fun `all subtypes round-trip`() {
        roundTrip(SignalingMessage.Hello(role = "intercom", intercomKey = "k", buildingId = "b"))
        roundTrip(SignalingMessage.Open("c1"))
        roundTrip(SignalingMessage.Decline("c1"))
        roundTrip(SignalingMessage.OpenResult("c1", success = false, reason = "NotFound"))
        roundTrip(SignalingMessage.ErrorMsg(message = "boom"))
    }
}
```

- [ ] **Step 3: Run it to verify it fails.** `./gradlew test --tests "*SignalingMessagesTest"` → FAIL (unresolved `SignalingMessage` if Step 1 not saved) or PASS once Step 1 is in. If it fails to compile, fix imports; if it passes, proceed.

- [ ] **Step 4: Add directory DTOs to `api/Dto.kt`** (after `PinCodesResponse`):

```kotlin
@Serializable
data class DirectoryEntry(val userId: String, val displayName: String)

@Serializable
data class DirectoryResponse(val residents: List<DirectoryEntry>)
```

- [ ] **Step 5: Add the directory endpoint to `intercomRoutes` in `api/Routing.kt`** (inside `Route.intercomRoutes`, after the `/intercom/validate` post). It reuses the intercom-key guard pattern:

```kotlin
    // GET /intercom/directory?buildingId=… — residents of a building, for the intercom's CONTACT list.
    get("/intercom/directory") {
        if (call.request.headers["X-Intercom-Key"] != intercomKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
            return@get
        }
        val buildingId = call.request.queryParameters["buildingId"]
        if (buildingId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("buildingId requis"))
            return@get
        }
        val residents = storage.users.find(Filters.`in`("buildingIds", buildingId)).toList()
        call.respond(DirectoryResponse(residents.map { DirectoryEntry(it.id, it.displayName) }))
    }
```

Add imports if missing: `io.ktor.server.routing.get` is already imported.

- [ ] **Step 6: Compile + curl-verify the directory** (server restarted by the owner).

```bash
B=http://localhost:8080
curl -s "$B/intercom/directory?buildingId=bld-montmartre" -H 'X-Intercom-Key: syekso-demo-intercom-key'; echo
```

Expected: `{"residents":[{"userId":"user-rodolphe","displayName":"…"}]}` (a single resident). Wrong/absent key → `401`.

- [ ] **Step 7: Checkpoint.**

---

## Task 2: Backend — `SignalingHub` (registry + state machine), unit-tested

**Files:** Create `signaling/SignalingHub.kt`; Test `src/test/kotlin/dev/rodolphe/accesscontrol/signaling/SignalingHubTest.kt`.

- [ ] **Step 1: Create `signaling/SignalingHub.kt`.** `ClientConnection` takes a `rawSend` lambda so the hub is testable without a real WebSocket.

```kotlin
package dev.rodolphe.accesscontrol.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One connected client (resident or intercom). [rawSend] serializes+sends one message; the mutex
 *  serializes concurrent sends to the same socket so frames don't interleave. */
class ClientConnection(
    val id: String,
    private val rawSend: suspend (SignalingMessage) -> Unit,
) {
    private val sendMutex = Mutex()
    suspend fun send(msg: SignalingMessage) = sendMutex.withLock { rawSend(msg) }
}

enum class CallStatus { RINGING, OPENING }

class CallState(
    val buildingId: String,
    val residentUserId: String,
    var status: CallStatus,
    var timeoutJob: Job? = null,
)

/** In-memory relay. Not persisted: on backend restart, clients reconnect. */
class SignalingHub(
    private val scope: CoroutineScope,
    private val ringTimeoutMs: Long = 30_000,
) {
    private val residents = ConcurrentHashMap<String, ClientConnection>()   // userId -> conn
    private val intercoms = ConcurrentHashMap<String, ClientConnection>()   // buildingId -> conn
    private val calls = ConcurrentHashMap<String, CallState>()              // callId -> state

    fun registerResident(userId: String, conn: ClientConnection) { residents[userId] = conn }
    fun registerIntercom(buildingId: String, conn: ClientConnection) { intercoms[buildingId] = conn }

    /** Remove a dropped connection and cancel any call that depended on it. */
    fun unregister(conn: ClientConnection) {
        residents.entries.removeIf { it.value === conn }
        intercoms.entries.removeIf { it.value === conn }
        calls.entries.removeIf { (_, state) ->
            val involved = residents[state.residentUserId] == null || intercoms[state.buildingId] == null
            if (involved) state.timeoutJob?.cancel()
            involved
        }
    }

    suspend fun onRingCall(buildingId: String, msg: SignalingMessage.Ring) {
        val targetUserId = msg.targetUserId ?: return
        // One call at a time per intercom.
        if (calls.values.any { it.buildingId == buildingId }) {
            intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel déjà en cours"))
            return
        }
        val resident = residents[targetUserId]
        if (resident == null) {
            intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Résident indisponible"))
            return
        }
        val state = CallState(buildingId, targetUserId, CallStatus.RINGING)
        calls[msg.callId] = state
        state.timeoutJob = scope.launch {
            delay(ringTimeoutMs)
            if (calls[msg.callId]?.status == CallStatus.RINGING) {
                calls.remove(msg.callId)
                intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Pas de réponse"))
                residents[targetUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "TIMED_OUT"))
            }
        }
        resident.send(SignalingMessage.Ring(msg.callId, targetUserId, msg.doorName))
    }

    suspend fun onOpenCall(residentUserId: String, msg: SignalingMessage.Open) {
        val state = calls[msg.callId]
        if (state == null || state.residentUserId != residentUserId || state.status != CallStatus.RINGING) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel expiré"))
            return
        }
        val intercom = intercoms[state.buildingId]
        if (intercom == null) {
            state.timeoutJob?.cancel(); calls.remove(msg.callId)
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Interphone hors ligne"))
            return
        }
        state.status = CallStatus.OPENING
        state.timeoutJob?.cancel()
        intercom.send(SignalingMessage.Open(msg.callId))
    }

    suspend fun onDeclineCall(residentUserId: String, msg: SignalingMessage.Decline) {
        val state = calls.remove(msg.callId) ?: return
        state.timeoutJob?.cancel()
        intercoms[state.buildingId]?.send(SignalingMessage.Decline(msg.callId))
    }

    suspend fun onOpenResultReported(buildingId: String, msg: SignalingMessage.OpenResult) {
        val state = calls.remove(msg.callId) ?: return
        state.timeoutJob?.cancel()
        residents[state.residentUserId]?.send(msg)
    }
}
```

- [ ] **Step 2: Write the failing test `SignalingHubTest.kt`.** A fake `ClientConnection` records sent messages.

```kotlin
package dev.rodolphe.accesscontrol.signaling

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalingHubTest {
    private fun recordingConn(id: String, sink: MutableList<SignalingMessage>) =
        ClientConnection(id) { sink.add(it) }

    @Test fun `ring to a connected resident is routed`() = runTest {
        val hub = SignalingHub(scope = this)
        val residentSink = mutableListOf<SignalingMessage>()
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))

        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte d'entrée"))

        assertEquals(listOf<SignalingMessage>(SignalingMessage.Ring("c1", "u1", "Porte d'entrée")), residentSink)
    }

    @Test fun `ring to an absent resident errors back to the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))

        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))

        assertEquals(SignalingMessage.ErrorMsg("c1", "Résident indisponible"), intercomSink.single())
    }

    @Test fun `open forwards to the intercom only from a ringing call`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        intercomSink.clear(); residentSink.clear()

        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.Open("c1"), intercomSink.single())

        // A second OPEN on the now-OPENING call is rejected.
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.ErrorMsg("c1", "Appel expiré"), residentSink.single())
    }

    @Test fun `open_result routes the real BLE outcome to the resident`() = runTest {
        val hub = SignalingHub(scope = this)
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", mutableListOf()))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        residentSink.clear()

        hub.onOpenResultReported("b1", SignalingMessage.OpenResult("c1", success = false, reason = "NotFound"))
        assertEquals(SignalingMessage.OpenResult("c1", false, "NotFound"), residentSink.single())
    }

    @Test fun `decline forwards to the intercom and drops the call`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        intercomSink.clear()

        hub.onDeclineCall("u1", SignalingMessage.Decline("c1"))
        assertTrue(intercomSink.contains(SignalingMessage.Decline("c1")))
    }
}
```

- [ ] **Step 3: Run to verify it fails, then passes.** `./gradlew test --tests "*SignalingHubTest"`. If `SignalingHub` compiles (Step 1 saved), tests should PASS. If any fail, fix `SignalingHub` logic until green. Expected final: PASS (5 tests).

- [ ] **Step 4: Checkpoint.**

---

## Task 3: Backend — `/ws` route + JWT verify + wire the hub

**Files:** Modify `security/JwtService.kt`, `Application.kt`, `api/Routing.kt`, `build.gradle.kts`; Test `src/test/kotlin/dev/rodolphe/accesscontrol/signaling/WebSocketRouteTest.kt`.

- [ ] **Step 1: Add the WebSockets dependency to `build.gradle.kts`** (next to the other ktor-server deps):

```kotlin
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
```

- [ ] **Step 2: Add `userIdFromToken` to `security/JwtService.kt`.** Open the file; it already builds tokens with a verifier/secret. Add a method that verifies a raw token and returns the user-id claim, mirroring how tokens are generated (reuse the existing `algorithm`/`verifier` field — adapt the field name to what the class already holds):

```kotlin
    /** Verify a raw JWT and return its user-id claim, or null if invalid/expired. */
    fun userIdFromToken(token: String): String? = try {
        verifier.verify(token).getClaim(CLAIM_USER_ID).asString()
    } catch (e: Exception) {
        null
    }
```

If the class exposes the auth0 `JWTVerifier` under a different name, use that; if it only holds the `Algorithm`, build `JWT.require(algorithm).build()` once and reuse it.

- [ ] **Step 3: Install WebSockets + build the hub in `Application.kt`.** In the `Application.module()` (where `install(...)` calls and `apiRoutes(...)` live) add:

```kotlin
    install(io.ktor.server.websocket.WebSockets)
```

and create the hub with the application scope, passing it into routing:

```kotlin
    val signalingHub = dev.rodolphe.accesscontrol.signaling.SignalingHub(scope = this)
    routing { apiRoutes(storage, jwtService, intercomKey, signalingHub) }
```

(Adapt to the existing `routing { apiRoutes(...) }` call — add `signalingHub` as the new last argument. `this` inside `Application.module` is a `CoroutineScope`.)

- [ ] **Step 4: Thread the hub through `apiRoutes` and add the `/ws` route in `api/Routing.kt`.** Change the signature:

```kotlin
fun Route.apiRoutes(storage: MongoStorage, jwt: JwtService, intercomKey: String, hub: SignalingHub) {
    authRoutes(storage, jwt)
    authenticate("auth-jwt") { meRoutes(storage) }
    intercomRoutes(storage, intercomKey)
    signalingRoute(jwt, intercomKey, hub)
}
```

Add the route function (bottom of file) and imports:

```kotlin
import dev.rodolphe.accesscontrol.signaling.ClientConnection
import dev.rodolphe.accesscontrol.signaling.SignalingHub
import dev.rodolphe.accesscontrol.signaling.SignalingMessage
import dev.rodolphe.accesscontrol.signaling.signalingJson
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

private fun Route.signalingRoute(jwt: JwtService, intercomKey: String, hub: SignalingHub) {
    webSocket("/ws") {
        // First frame MUST be HELLO; authenticate before registering.
        val first = (incoming.receive() as? Frame.Text)?.readText() ?: return@webSocket
        val hello = signalingJson.decodeFromString(SignalingMessage.serializer(), first) as? SignalingMessage.Hello
            ?: return@webSocket close()

        val conn: ClientConnection
        val onMessage: suspend (SignalingMessage) -> Unit

        when (hello.role) {
            "resident" -> {
                val userId = hello.jwt?.let(jwt::userIdFromToken)
                    ?: return@webSocket close()
                conn = ClientConnection(userId) { send(Frame.Text(signalingJson.encodeToString(SignalingMessage.serializer(), it))) }
                hub.registerResident(userId, conn)
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Open -> hub.onOpenCall(userId, msg)
                        is SignalingMessage.Decline -> hub.onDeclineCall(userId, msg)
                        else -> {}
                    }
                }
            }
            "intercom" -> {
                val buildingId = hello.buildingId
                if (hello.intercomKey != intercomKey || buildingId.isNullOrBlank()) return@webSocket close()
                conn = ClientConnection(buildingId) { send(Frame.Text(signalingJson.encodeToString(SignalingMessage.serializer(), it))) }
                hub.registerIntercom(buildingId, conn)
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Ring -> hub.onRingCall(buildingId, msg)
                        is SignalingMessage.OpenResult -> hub.onOpenResultReported(buildingId, msg)
                        else -> {}
                    }
                }
            }
            else -> return@webSocket close()
        }

        try {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val msg = runCatching {
                    signalingJson.decodeFromString(SignalingMessage.serializer(), text)
                }.getOrNull() ?: continue
                onMessage(msg)
            }
        } finally {
            hub.unregister(conn)
        }
    }
}
```

- [ ] **Step 4b: Add the required serialization import** at the top of `Routing.kt` if not present: `import kotlinx.serialization.encodeToString` is not needed (we pass the serializer explicitly). No extra import beyond Step 4.

- [ ] **Step 5: Manually verify the `/ws` route** (the hub routing/state-machine logic is already covered by Task 2's unit tests; the route is thin auth + dispatch glue). Owner starts the server. Get a resident JWT:

```bash
curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"rodolphe@example.com","password":"password"}'   # copy the "token"
```

Then use `websocat` in two terminals (install: a release binary, or `cargo install websocat`):

Terminal A — resident:
```
websocat ws://localhost:8080/ws
{"type":"hello","role":"resident","jwt":"<paste token>"}
```
Terminal B — intercom:
```
websocat ws://localhost:8080/ws
{"type":"hello","role":"intercom","intercomKey":"syekso-demo-intercom-key","buildingId":"bld-montmartre"}
{"type":"ring","callId":"c1","targetUserId":"user-rodolphe","doorName":"Porte d'entrée"}
```
Expected: Terminal A receives `{"type":"ring","callId":"c1",…}`. Then in A send `{"type":"open","callId":"c1"}` → B receives `{"type":"open","callId":"c1"}`. Then in B send `{"type":"open_result","callId":"c1","success":true}` → A receives it. Also check: a wrong `intercomKey` in the intercom HELLO closes its socket; a `ring` while no resident is connected returns `{"type":"error",…,"message":"Résident indisponible"}` to B.

- [ ] **Step 6: Checkpoint.** (A `testApplication` integration test is deferred: the backend has no test harness yet and talks to live MongoDB Atlas — adding a Mongo test double is out of scope for 4a. The hub's routing/state-machine logic is fully unit-tested in Task 2.)

---

## Task 4: App `core:network` — signaling DTOs, transport, `SignalingClient`, directory API

**Files:** Modify `core/network/.../model/NetworkModels.kt`, `.../SyeksoApiService.kt`; Create `core/network/.../signaling/SignalingTransport.kt`, `.../signaling/SignalingClient.kt`; Test `core/network/src/test/.../signaling/SignalingClientTest.kt`.

- [ ] **Step 1: Add the signaling DTOs + directory DTO to `NetworkModels.kt`** (identical wire shape to the backend):

```kotlin
@Serializable
sealed interface SignalingMessage {
    @Serializable @SerialName("hello")
    data class Hello(val role: String, val jwt: String? = null, val intercomKey: String? = null, val buildingId: String? = null) : SignalingMessage
    @Serializable @SerialName("ring")
    data class Ring(val callId: String, val targetUserId: String? = null, val doorName: String? = null) : SignalingMessage
    @Serializable @SerialName("open")
    data class Open(val callId: String) : SignalingMessage
    @Serializable @SerialName("decline")
    data class Decline(val callId: String) : SignalingMessage
    @Serializable @SerialName("open_result")
    data class OpenResult(val callId: String, val success: Boolean, val reason: String? = null) : SignalingMessage
    @Serializable @SerialName("error")
    data class ErrorMsg(val callId: String? = null, val message: String) : SignalingMessage
}

@Serializable
data class DirectoryEntryNetwork(val userId: String, val displayName: String)

@Serializable
data class DirectoryResponseNetwork(val residents: List<DirectoryEntryNetwork>)
```

Add imports at the top if missing: `import kotlinx.serialization.SerialName`.

- [ ] **Step 2: Create `core/network/.../signaling/SignalingTransport.kt`** — a thin transport interface (testable) + an OkHttp-backed impl. Reuses the OkHttp already present via Retrofit.

```kotlin
package dev.rodolphe.syeksodemo.core.network.signaling

/** Minimal text-frame WebSocket transport, so SignalingClient is testable with a fake. */
interface SignalingTransport {
    fun connect(url: String, listener: Listener)
    fun send(text: String)
    fun close()

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed()
        fun onFailure(t: Throwable)
    }
}
```

```kotlin
package dev.rodolphe.syeksodemo.core.network.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject

class OkHttpSignalingTransport @Inject constructor(
    private val client: OkHttpClient,
) : SignalingTransport {
    private var webSocket: WebSocket? = null

    override fun connect(url: String, listener: SignalingTransport.Listener) {
        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(ws: WebSocket, text: String) = listener.onText(text)
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = listener.onClosed()
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = listener.onFailure(t)
            },
        )
    }

    override fun send(text: String) { webSocket?.send(text) }
    override fun close() { webSocket?.close(1000, null); webSocket = null }
}
```

- [ ] **Step 3: Create `core/network/.../signaling/SignalingClient.kt`** — maps `SignalingMessage` ↔ text, exposes an inbound `SharedFlow`, and reconnects while `start()`ed.

```kotlin
package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject

class SignalingClient @Inject constructor(
    private val transport: SignalingTransport,
) {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = false }
    private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val incoming: SharedFlow<SignalingMessage> = _incoming

    private var hello: SignalingMessage.Hello? = null
    private var url: String? = null

    /** Connect and (re)send [hello] on every (re)open until [stop]. */
    fun start(url: String, hello: SignalingMessage.Hello) {
        this.url = url; this.hello = hello
        transport.connect(url, object : SignalingTransport.Listener {
            override fun onOpen() { hello?.let { send(it) } }
            override fun onText(text: String) {
                runCatching { json.decodeFromString(SignalingMessage.serializer(), text) }
                    .getOrNull()?.let { _incoming.tryEmit(it) }
            }
            override fun onClosed() {}
            override fun onFailure(t: Throwable) { /* reconnect handled by caller/lifecycle in Task 7 */ }
        })
    }

    fun send(msg: SignalingMessage) =
        transport.send(json.encodeToString(SignalingMessage.serializer(), msg))

    fun stop() = transport.close()
}
```

- [ ] **Step 4: Add `getDirectory` to `SyeksoApiService.kt`** + imports:

```kotlin
    @GET("intercom/directory")
    suspend fun getDirectory(
        @Header("X-Intercom-Key") intercomKey: String,
        @Query("buildingId") buildingId: String,
    ): DirectoryResponseNetwork
```

Add imports: `import dev.rodolphe.syeksodemo.core.network.model.DirectoryResponseNetwork` and `import retrofit2.http.Query`.

- [ ] **Step 5: Write the failing `SignalingClientTest.kt`** (test source) with a fake transport.

```kotlin
package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingClientTest {
    private class FakeTransport : SignalingTransport {
        var listener: SignalingTransport.Listener? = null
        val sent = mutableListOf<String>()
        override fun connect(url: String, listener: SignalingTransport.Listener) { this.listener = listener; listener.onOpen() }
        override fun send(text: String) { sent.add(text) }
        override fun close() {}
    }

    @Test fun `sends hello on open`() = runTest {
        val t = FakeTransport()
        SignalingClient(t).start("ws://x/ws", SignalingMessage.Hello(role = "resident", jwt = "j"))
        assertEquals(true, t.sent.single().contains("\"type\":\"hello\""))
    }

    @Test fun `parses inbound ring into the flow`() = runTest {
        val t = FakeTransport()
        val client = SignalingClient(t)
        val received = mutableListOf<SignalingMessage>()
        val job = kotlinx.coroutines.launch { client.incoming.collect { received.add(it) } }
        client.start("ws://x/ws", SignalingMessage.Hello(role = "resident", jwt = "j"))
        t.listener?.onText("""{"type":"ring","callId":"c1","targetUserId":"u1","doorName":"Porte"}""")
        kotlinx.coroutines.yield()
        assertEquals(SignalingMessage.Ring("c1", "u1", "Porte"), received.single())
        job.cancel()
    }
}
```

- [ ] **Step 6: Run tests to fail, then pass.** `./gradlew :core:network:testDebugUnitTest --tests "*SignalingClientTest"` → PASS (2). Fix compile/imports as needed.

- [ ] **Step 7: Provide OkHttp + transport binding via Hilt.** In `core:network`'s DI module (where `OkHttpClient`/Retrofit are provided), add a `@Binds` (or `@Provides`) so `SignalingTransport` resolves to `OkHttpSignalingTransport` and `SignalingClient` is injectable. Mirror the existing provider style in that module.

- [ ] **Step 8: Compile.** `./gradlew :core:network:compileDebugKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 9: Checkpoint.**

---

## Task 5: `feature:intercomcall` — resident incoming-call ViewModel (TDD) + overlay

**Files:** Create module `feature/intercomcall/` (`build.gradle.kts`, `src/main/.../IncomingCallUiState.kt`, `IncomingCallViewModel.kt`, `IncomingCallOverlay.kt`); Test `src/test/.../IncomingCallViewModelTest.kt`, `FakeSignalingClientRule` helpers; Modify `settings.gradle.kts`.

- [ ] **Step 1: Register the module in `settings.gradle.kts`:** add `include(":feature:intercomcall")`.

- [ ] **Step 2: Create `feature/intercomcall/build.gradle.kts`** by copying `feature/sharing/build.gradle.kts` and changing the namespace to `dev.rodolphe.syeksodemo.feature.intercomcall`. Dependencies: `:core:network`, `:core:model`, `:core:designsystem`, `:core:datastore` (for the JWT), Hilt, Compose, coroutines-test (test).

- [ ] **Step 3: Make `SignalingClient` mockable — extract an interface.** In `:core:network`, add:

```kotlin
package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.flow.SharedFlow

interface Signaling {
    val incoming: SharedFlow<SignalingMessage>
    fun start(url: String, hello: SignalingMessage.Hello)
    fun send(msg: SignalingMessage)
    fun stop()
}
```

Make `SignalingClient : Signaling` (add `override` to its members) and bind `Signaling` → `SignalingClient` in the `core:network` DI module.

- [ ] **Step 4: Create `IncomingCallUiState.kt`:**

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

sealed interface IncomingCallUiState {
    data object None : IncomingCallUiState
    data class Ringing(val callId: String, val doorName: String) : IncomingCallUiState
    data object Opening : IncomingCallUiState
    data class Result(val success: Boolean, val message: String) : IncomingCallUiState
}
```

- [ ] **Step 5: Write the failing test `IncomingCallViewModelTest.kt`** with a fake `Signaling`.

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
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
        var started = false
        override fun start(url: String, hello: SignalingMessage.Hello) { started = true }
        override fun send(msg: SignalingMessage) { sent.add(msg) }
        override fun stop() {}
    }

    private fun vm(sig: FakeSignaling) = IncomingCallViewModel(sig)

    @Test fun `ring shows the ringing state`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte d'entrée")); runCurrent()
        assertEquals(IncomingCallUiState.Ringing("c1", "Porte d'entrée"), viewModel.uiState.value)
    }

    @Test fun `open sends OPEN and goes to Opening`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        assertEquals(SignalingMessage.Open("c1"), sig.sent.single())
        assertEquals(IncomingCallUiState.Opening, viewModel.uiState.value)
    }

    @Test fun `open_result success shows a success result`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        sig.flow.emit(SignalingMessage.OpenResult("c1", success = true)); runCurrent()
        assertTrue(viewModel.uiState.value is IncomingCallUiState.Result)
        assertEquals(true, (viewModel.uiState.value as IncomingCallUiState.Result).success)
    }

    @Test fun `decline sends DECLINE and clears`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onDecline(); runCurrent()
        assertEquals(SignalingMessage.Decline("c1"), sig.sent.single())
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }

    @Test fun `error message after ring shows a failure result`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        sig.flow.emit(SignalingMessage.ErrorMsg("c1", "Interphone hors ligne")); runCurrent()
        assertEquals(IncomingCallUiState.Result(false, "Interphone hors ligne"), viewModel.uiState.value)
    }
}
```

Copy `feature/sharing/src/test/.../MainDispatcherRule.kt` into this module's test source (same package-relative path).

- [ ] **Step 6: Run to verify failure.** `./gradlew :feature:intercomcall:testDebugUnitTest` → FAIL (`IncomingCallViewModel` unresolved).

- [ ] **Step 7: Implement `IncomingCallViewModel.kt`:**

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    private val signaling: Signaling,
) : ViewModel() {

    private val _uiState = MutableStateFlow<IncomingCallUiState>(IncomingCallUiState.None)
    val uiState: StateFlow<IncomingCallUiState> = _uiState.asStateFlow()

    private var currentCallId: String? = null

    init {
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Ring -> {
                        currentCallId = msg.callId
                        _uiState.value = IncomingCallUiState.Ringing(msg.callId, msg.doorName ?: "Porte")
                    }
                    is SignalingMessage.OpenResult ->
                        if (msg.callId == currentCallId) {
                            _uiState.value = IncomingCallUiState.Result(
                                msg.success,
                                if (msg.success) "Porte ouverte" else "Échec de l'ouverture",
                            )
                            currentCallId = null
                        }
                    is SignalingMessage.ErrorMsg ->
                        if (msg.callId == currentCallId) {
                            _uiState.value = IncomingCallUiState.Result(false, msg.message)
                            currentCallId = null
                        }
                    else -> {}
                }
            }
        }
    }

    fun onOpen() {
        val id = currentCallId ?: return
        signaling.send(SignalingMessage.Open(id))
        _uiState.value = IncomingCallUiState.Opening
    }

    fun onDecline() {
        val id = currentCallId ?: return
        signaling.send(SignalingMessage.Decline(id))
        currentCallId = null
        _uiState.value = IncomingCallUiState.None
    }

    fun dismiss() { _uiState.value = IncomingCallUiState.None }
}
```

- [ ] **Step 8: Run tests to pass.** `./gradlew :feature:intercomcall:testDebugUnitTest` → PASS (5).

- [ ] **Step 9: Create `IncomingCallOverlay.kt`** — a full-screen overlay shown when state ≠ None.

```kotlin
package dev.rodolphe.syeksodemo.feature.intercomcall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IncomingCallOverlay(viewModel: IncomingCallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state is IncomingCallUiState.None) return

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is IncomingCallUiState.Ringing -> {
                    Text("Appel entrant", style = MaterialTheme.typography.headlineSmall)
                    Text("Quelqu'un sonne à « ${s.doorName} »", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = viewModel::onDecline) { Text("Ignorer") }
                        Button(onClick = viewModel::onOpen) { Text("Ouvrir") }
                    }
                }
                IncomingCallUiState.Opening -> {
                    CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Ouverture…")
                }
                is IncomingCallUiState.Result -> {
                    Text(if (s.success) "✓ ${s.message}" else "✗ ${s.message}",
                        style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = viewModel::dismiss) { Text("Fermer") }
                }
                IncomingCallUiState.None -> {}
            }
        }
    }
}
```

- [ ] **Step 10: Assemble.** `./gradlew :feature:intercomcall:assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 11: Checkpoint.**

---

## Task 6: Intercom app — CONTACT/CODE home + call ViewModel (TDD) reusing `core:ble`

**Files:** Create `intercom/.../call/CallViewModel.kt`, `.../call/CallUiState.kt`, `.../IntercomHomeScreen.kt`; Modify the intercom's `MainActivity`/nav to show CONTACT/CODE; Test `intercom/src/test/.../call/CallViewModelTest.kt`. Keep the existing keypad (`IntercomScreen.kt`) as the CODE screen.

- [ ] **Step 1: Add deps to `intercom/build.gradle.kts`** if missing: `:core:network` (already), `:core:ble` (already). Ensure `buildConfigField` `INTERCOM_KEY` + `BASE_URL` are available (they are).

- [ ] **Step 2: Create `call/CallUiState.kt`:**

```kotlin
package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork

data class CallUiState(
    val directory: List<DirectoryEntryNetwork> = emptyList(),
    val selectedUserId: String? = null,
    val status: CallStatus = CallStatus.Idle,
) {
    val canRing: Boolean get() = selectedUserId != null && status == CallStatus.Idle
}

sealed interface CallStatus {
    data object Idle : CallStatus
    data object Ringing : CallStatus
    data object Opening : CallStatus
    data class Ended(val message: String) : CallStatus
}
```

- [ ] **Step 3: Write the failing `call/CallViewModelTest.kt`** with a fake `Signaling`, a fake `SyeksoBleController`, and a stubbed directory loader. (Reuse a `MainDispatcherRule` copied into the intercom test source.)

```kotlin
package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakeSignaling : Signaling {
        val flow = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<SignalingMessage> = flow
        val sent = mutableListOf<SignalingMessage>()
        override fun start(url: String, hello: SignalingMessage.Hello) {}
        override fun send(msg: SignalingMessage) { sent.add(msg) }
        override fun stop() {}
    }
    private class FakeBle(private val result: DoorOpenState) : SyeksoBleController {
        var openedName: String? = null
        override fun open(bleLocalName: String): Flow<DoorOpenState> { openedName = bleLocalName; return flowOf(result) }
    }

    private val door = "OSKEY-HALL-01"
    private val fakeDirectory = object : DirectoryProvider {
        override suspend fun residents() = listOf(DirectoryEntryNetwork("user-rodolphe", "Rodolphe"))
    }
    private fun vm(sig: FakeSignaling, ble: FakeBle) = CallViewModel(
        signaling = sig, bleController = ble,
        directoryProvider = fakeDirectory,
        config = IntercomConfig(buildingId = "bld-montmartre", doorName = "Porte d'entrée", doorBleLocalName = door),
    )

    @Test fun `loads directory and preselects the single resident`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig, FakeBle(DoorOpenState.Opened))
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        assertEquals("user-rodolphe", viewModel.uiState.value.selectedUserId)
        assertTrue(viewModel.uiState.value.canRing)
    }

    @Test fun `ring sends RING with target and door`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig, FakeBle(DoorOpenState.Opened))
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val ring = sig.sent.single() as SignalingMessage.Ring
        assertEquals("user-rodolphe", ring.targetUserId)
        assertEquals("Porte d'entrée", ring.doorName)
        assertEquals(CallStatus.Ringing, viewModel.uiState.value.status)
    }

    @Test fun `incoming OPEN triggers BLE open and reports success`() = runTest {
        val sig = FakeSignaling(); val ble = FakeBle(DoorOpenState.Opened); val viewModel = vm(sig, ble)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Open(callId)); runCurrent()
        assertEquals(door, ble.openedName)
        val result = sig.sent.last() as SignalingMessage.OpenResult
        assertEquals(true, result.success)
    }

    @Test fun `incoming OPEN with BLE failure reports failure`() = runTest {
        val sig = FakeSignaling()
        val ble = FakeBle(DoorOpenState.Error(dev.rodolphe.syeksodemo.core.ble.DoorOpenError.NotFound))
        val viewModel = vm(sig, ble)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Open(callId)); runCurrent()
        val result = sig.sent.last() as SignalingMessage.OpenResult
        assertEquals(false, result.success)
    }
}
```

- [ ] **Step 4: Run to verify failure.** `./gradlew :intercom:testDebugUnitTest --tests "*CallViewModelTest"` → FAIL (unresolved `CallViewModel`).

- [ ] **Step 5: Define `DirectoryProvider` + `IntercomConfig`, then implement `call/CallViewModel.kt`.** `CallViewModel` is `@HiltViewModel` (so `hiltViewModel()` works in `ContactPanel`) with only injectable deps; tests still construct it directly with a fake `DirectoryProvider` + a literal `IntercomConfig`.

Create `call/DirectoryProvider.kt`:

```kotlin
package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork

/** Loads the building's resident directory. Interface so CallViewModel is unit-testable without Retrofit. */
interface DirectoryProvider {
    suspend fun residents(): List<DirectoryEntryNetwork>
}

/** Static per-intercom config (demo: entrance of Résidence Montmartre). Provided via Hilt. */
data class IntercomConfig(
    val buildingId: String,
    val doorName: String,
    val doorBleLocalName: String,
)
```

Create `call/CallViewModel.kt`:

```kotlin
package dev.rodolphe.syeksodemo.intercom.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val signaling: Signaling,
    private val bleController: SyeksoBleController,
    private val directoryProvider: DirectoryProvider,
    private val config: IntercomConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var currentCallId: String? = null

    init {
        viewModelScope.launch {
            val directory = runCatching { directoryProvider.residents() }.getOrDefault(emptyList())
            _uiState.update { it.copy(directory = directory, selectedUserId = directory.firstOrNull()?.userId) }
        }
        viewModelScope.launch {
            signaling.incoming.collect { msg ->
                when (msg) {
                    is SignalingMessage.Open -> if (msg.callId == currentCallId) doOpen(msg.callId)
                    is SignalingMessage.Decline -> if (msg.callId == currentCallId) end("Refusé")
                    is SignalingMessage.ErrorMsg -> if (msg.callId == currentCallId) end(msg.message)
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

    private fun doOpen(callId: String) {
        _uiState.update { it.copy(status = CallStatus.Opening) }
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
            end(if (success) "Ouvert" else "Ouverture impossible")
        }
    }

    private fun end(message: String) {
        currentCallId = null
        _uiState.update { it.copy(status = CallStatus.Ended(message)) }
    }
}
```

- [ ] **Step 6: Provide `DirectoryProvider` + `IntercomConfig` via Hilt** so `CallViewModel` (already `@HiltViewModel`) resolves through `hiltViewModel()`. In the intercom module's Hilt module (or a new `di/IntercomModule.kt`):

```kotlin
package dev.rodolphe.syeksodemo.intercom.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.intercom.BuildConfig
import dev.rodolphe.syeksodemo.intercom.call.DirectoryProvider
import dev.rodolphe.syeksodemo.intercom.call.IntercomConfig
import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntercomModule {

    @Provides @Singleton
    fun provideIntercomConfig(): IntercomConfig =
        IntercomConfig(buildingId = "bld-montmartre", doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")

    @Provides @Singleton
    fun provideDirectoryProvider(api: SyeksoApiService, config: IntercomConfig): DirectoryProvider =
        object : DirectoryProvider {
            override suspend fun residents(): List<DirectoryEntryNetwork> =
                api.getDirectory(BuildConfig.INTERCOM_KEY, config.buildingId).residents
        }
}
```

`Signaling` and `SyeksoBleController` are already bound (Task 4 + `core:ble`). `CallViewModel` now injects cleanly.

- [ ] **Step 7: Run tests to pass.** `./gradlew :intercom:testDebugUnitTest --tests "*CallViewModelTest"` → PASS (4).

- [ ] **Step 8: Create `IntercomHomeScreen.kt`** — the panel-style home with **CONTACT** and **CODE**, modeled on the Intercom+ screenshot (two large buttons). CONTACT opens the directory + Sonner (driven by `CallViewModel`); CODE opens the existing keypad (`IntercomRoute`).

```kotlin
package dev.rodolphe.syeksodemo.intercom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Panel { HOME, CONTACT, CODE }

@Composable
fun IntercomHomeScreen() {
    var panel by remember { mutableStateOf(Panel.HOME) }
    when (panel) {
        Panel.HOME -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Résidence Montmartre", style = MaterialTheme.typography.headlineSmall)
            Text("19 Rue Parmentier, 75008 Paris", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { panel = Panel.CONTACT }, modifier = Modifier.weight(1f).fillMaxSize()) { Text("CONTACT") }
                Button(onClick = { panel = Panel.CODE }, modifier = Modifier.weight(1f).fillMaxSize()) { Text("CODE") }
            }
        }
        Panel.CONTACT -> ContactPanel(onBack = { panel = Panel.HOME })
        Panel.CODE -> IntercomRoute() // existing keypad
    }
}
```

- [ ] **Step 9: Create the `ContactPanel` composable** (in the same file or `call/ContactPanel.kt`) — the directory list + Sonner, driven by `CallViewModel`. Show the selected resident and a « Sonner » button; reflect `status` (Ringing/Opening/Ended).

```kotlin
package dev.rodolphe.syeksodemo.intercom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.syeksodemo.intercom.call.CallStatus
import dev.rodolphe.syeksodemo.intercom.call.CallViewModel

@Composable
fun ContactPanel(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        OutlinedButton(onClick = onBack) { Text("← Retour") }
        Spacer(Modifier.height(16.dp))
        Text("Choisissez un résident", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.directory.forEach { entry ->
            FilterChip(
                selected = state.selectedUserId == entry.userId,
                onClick = { viewModel.select(entry.userId) },
                label = { Text(entry.displayName) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::ring, enabled = state.canRing, modifier = Modifier.fillMaxWidth()) {
            Text("Sonner")
        }
        Spacer(Modifier.height(16.dp))
        when (val s = state.status) {
            CallStatus.Ringing -> Text("Sonnerie en cours…")
            CallStatus.Opening -> Text("Ouverture…")
            is CallStatus.Ended -> Text(s.message)
            CallStatus.Idle -> {}
        }
    }
}
```

Note: `CallViewModel` here needs a `hiltViewModel()`-compatible form; use the Hilt wrapper from Step 6. Set the intercom app's start destination to `IntercomHomeScreen()`.

- [ ] **Step 10: Assemble + install on the intercom device.** `./gradlew :intercom:assembleDebug` → `BUILD SUCCESSFUL`, then install on the intercom phone.

- [ ] **Step 11: Checkpoint.**

---

## Task 7: Resident WebSocket lifecycle + overlay wiring + manual E2E

**Files:** Modify the resident app (`app/.../MainActivity` or the root nav composable) and DI; no new logic beyond wiring.

- [ ] **Step 1: Hold the resident WebSocket while the app is foreground.** In the resident app's root composable (where `OskeysNavHost`/`SyeksoNavHost` is shown once logged in), start the `Signaling` connection with a resident `HELLO` built from the current JWT (`SessionDataSource`), and `stop()` it on dispose. Concretely, add a small `@Composable` effect:

```kotlin
val jwt = /* current session jwt from a ViewModel exposing SessionDataSource */
LaunchedEffect(jwt) {
    if (jwt.isNotEmpty()) signaling.start(
        url = BuildConfig.BASE_URL.replace("http", "ws") + "ws",
        hello = SignalingMessage.Hello(role = "resident", jwt = jwt),
    )
}
DisposableEffect(Unit) { onDispose { signaling.stop() } }
```

Inject `Signaling` via a small root ViewModel (Hilt), reusing the app's existing session plumbing.

- [ ] **Step 2: Render `IncomingCallOverlay()` above the nav host.** Wrap the logged-in content in a `Box { SyeksoNavHost(...); IncomingCallOverlay() }` so the overlay draws over everything when a call rings.

- [ ] **Step 3: Add `app` dependency on `:feature:intercomcall`** in `app/build.gradle.kts`.

- [ ] **Step 4: Reconnect on drop while foreground.** In `OkHttpSignalingTransport.onFailure`/`onClosed`, or in the root effect, add a simple retry (e.g., re-call `start` after a short delay) guarded by the foreground lifecycle. Keep it minimal: a `while (isActive) { connect; await close; delay(2s) }` loop in the root effect is acceptable.

- [ ] **Step 5: Full manual E2E** (backend restarted by owner, ESP32 powered, both devices on the same Wi-Fi):
  1. Resident app open + logged in (holds the WebSocket).
  2. Intercom → **CONTACT** → rodolphe preselected → **Sonner**.
  3. Resident phone shows the **incoming-call overlay** → **Ouvrir**.
  4. ESP32 pulses blue; resident sees **« Porte ouverte »**.
  5. Repeat, tapping **Ignorer** → intercom shows "Refusé".
  6. With the resident app closed → **Sonner** → intercom shows "Résident indisponible".
  7. Turn the ESP32 off → ring → Ouvrir → resident sees **« Échec de l'ouverture »** (real BLE result relayed).

- [ ] **Step 6: Checkpoint** — iteration 4a done; ready to commit (both repos: `AccessControllerServer` + Syekso).

---

## Self-review notes

- **Spec coverage:** connection/pairing + directory (T1, T3), message protocol + state machine (T1 types, T2 hub), backend WS route + auth (T3), app signaling client + directory API (T4), resident incoming-call VM + overlay (T5), intercom CONTACT/CODE + call VM reusing `core:ble` (T6), lifecycle wiring + full E2E incl. decline/offline/BLE-failure (T7). All spec sections mapped.
- **Type consistency:** `SignalingMessage` subtypes (`Hello/Ring/Open/Decline/OpenResult/ErrorMsg`) and their fields are identical across backend `signaling/SignalingMessages.kt` and app `NetworkModels.kt`, and used identically in the hub, `SignalingClient`, `Signaling` fake, and both ViewModels. `ClientConnection(id, rawSend)`, `SignalingHub.onRingCall/onOpenCall/onDeclineCall/onOpenResultReported(...)`, `Signaling.start/send/stop/incoming`, `DirectoryResponse(residents=[DirectoryEntry(userId, displayName)])` used consistently. `SyeksoBleController.open(bleLocalName): Flow<DoorOpenState>` reused unchanged.
- **Deferred (out of scope, per spec):** WebRTC media (4b), FCM push, multi-device resident sessions, mid-call resume, NFC/badge, building-info display.
```
