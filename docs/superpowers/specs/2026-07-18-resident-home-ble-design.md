# Iteration 1 — Resident Home + activation + BLE door open

**Date:** 2026-07-18
**Status:** Approved, ready for implementation plan
**Context:** Portfolio/demo app for a senior Android interview at Oskeys (https://www.oskey.io/).
Resident-facing app. Delivery format: **live demo on a physical phone**. BLE hardware available:
a phone + an **ESP32** (so BLE is real, not mocked). Backend is real:
`C:/Users/rodol/IdeaProjects/AccessControllerServer` (Ktor + MongoDB + JWT).

This spec covers **only iteration 1**. The full resident roadmap lives in the project memory
(`oskeys-resident-roadmap`) and in the "Roadmap context" section below.

## Goal

Deliver the first fully real, end-to-end resident flow:

```
Login (done) ─▶ Home (empty) ─▶ enter activation code ─▶ POST /me/activations
                                        │
                                        ▼
                           doors persisted in Room ─▶ doors list
                                        │
                                [Ouvrir] ─▶ core:ble scans bleLocalName
                                        ─▶ connects ESP32 ─▶ writes OPEN ─▶ relay/LED
```

No mocks in the demo path: server → app → hardware.

## Existing foundation (do not rebuild)

- Multi-module NiA-style architecture; 13 modules wired in `settings.gradle.kts`.
- Auth works end-to-end: `LoginScreen` → `AuthRepository.login` → `POST /auth/login` → encrypted
  session in proto DataStore → `MainViewModel` gate flips Loading/LoggedOut/LoggedIn.
- Doors **data layer already exists** and is unused by any screen:
  - `core:model` → `Door(id: DoorId, name, buildingName, bleLocalName)`
  - `core:database` → `DoorDao.observeDoors(): Flow<List<DoorEntity>>`, `upsertDoors`, `clearDoors`
  - `core:network` → `OskeysApiService` with `getDoors`, `activate`; DTOs match the server exactly
    (`DoorNetwork.bleLocalName` ↔ server `DoorDto.bleLocalName`)
  - `core:data` → `DoorsRepository`:
    - `val doors: Flow<List<Door>>` (Room-backed, offline-capable)
    - `suspend fun activate(code: String): ActivationResult`
      (`Success | InvalidCode | AlreadyUsed | Unauthorized | NetworkError | ServerError`)
    - `suspend fun refreshDoors(): Result<Unit>` (GET /me/doors → Room)
- `OskeysNavHost` has 4 placeholder tabs: Accueil, Invitations, Historique, Menu.

Backend endpoints already present (no server code change needed for iter 1):
`POST /auth/login`, `POST /me/activations` (JWT), `GET /me/doors` (JWT).

## Components to build

### 1. `core:ble` (new module)

Hides Android BLE behind a testable interface. No `feature`/`app` code touches the framework APIs.

```kotlin
sealed interface DoorOpenState {
    data object Scanning : DoorOpenState
    data object Connecting : DoorOpenState
    data object Sending : DoorOpenState
    data object Opened : DoorOpenState
    data class Error(val reason: DoorOpenError) : DoorOpenState
}

enum class DoorOpenError { BluetoothOff, PermissionMissing, NotFound, ConnectionFailed, WriteFailed, Timeout }

interface OskeysBleController {
    /** Cold flow: scans for a peripheral advertising [bleLocalName], connects, writes the open
     *  command, emits progress, then completes (or emits Error and completes). */
    fun open(bleLocalName: String): Flow<DoorOpenState>
}
```

- Impl `AndroidOskeysBleController` uses `BluetoothLeScanner` (filter on device name = `bleLocalName`),
  `BluetoothGatt`, writes the fixed command to the command characteristic. BLE callbacks bridged to
  the flow via `callbackFlow`. Scan timeout ~10 s, connection timeout ~10 s.
- GATT contract (ours, shared with firmware) — fixed UUIDs in a `BleContract` object:
  - Service UUID (constant)
  - Command characteristic UUID (write) — payload = ASCII `"OPEN"` (iteration 1)
- Provided via Hilt (`@Binds` interface → impl) in a `BleModule`.
- Permission handling: the module exposes a check for the required permissions; the **runtime request
  is triggered from the feature layer** (Compose `rememberLauncherForActivityResult`). Required:
  - Android 12+ (API 31): `BLUETOOTH_SCAN` (+ `neverForLocation` flag), `BLUETOOTH_CONNECT`
  - Below 31: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`
  - Declared in `core:ble`'s manifest so they merge into the app.

### 2. `feature:home` (new module)

- `HomeViewModel`:
  - Exposes `uiState: StateFlow<HomeUiState>` = doors list (from `DoorsRepository.doors`) + per-door
    open state (`Map<DoorId, DoorOpenState?>`) + activation state.
  - On first collection, calls `refreshDoors()` (best-effort; Room is the source of truth so failure
    just leaves cached doors).
  - `open(door)`: collects `bleController.open(door.bleLocalName)` and folds each `DoorOpenState`
    into that door's slot. Success auto-clears back to idle after a short delay.
  - `activate(code)`: calls `DoorsRepository.activate`; maps `ActivationResult` to a French message;
    on `Success` the sheet closes and the new doors flow in automatically via `doors`.
- `HomeScreen`:
  - Populated state: `LazyColumn` of door cards (name + building) each with an **Ouvrir** button
    reflecting its `DoorOpenState` (idle button / spinner during scan-connect-send / brief check on
    Opened / inline error with retry).
  - Empty state: illustration + text + **"Activer un immeuble"** CTA.
  - Top-bar **"+"** action → activation bottom sheet (`OutlinedTextField` for the code + submit,
    showing progress and the mapped error).
  - Triggers the BLE permission request before the first open; if denied, shows guidance.

### 3. Firmware `hardware/esp32-door/`

- Arduino/NimBLE sketch: advertises local name = the door's `bleLocalName` (e.g. `OSKEYS-DOOR-01`),
  registers the GATT service + write command characteristic; on receiving `"OPEN"` pulses a GPIO
  (relay or the onboard LED) for ~3 s. README with wiring + flashing steps and how the `bleLocalName`
  and UUIDs must match `BleContract`.

### 4. `app` wiring

- Replace the Accueil placeholder in `OskeysNavHost` with `feature:home`'s `HomeRoute`.
- Add `:core:ble` and `:feature:home` to `settings.gradle.kts` and the relevant `build.gradle.kts`
  dependencies.

## Data flow

- **Activation:** `HomeViewModel.activate(code)` → `DoorsRepository.activate` → `POST /me/activations`
  → doors upserted to Room → `doors` Flow emits → list updates. Result → FR message.
- **Doors:** `HomeViewModel` observes `DoorsRepository.doors`; `refreshDoors()` syncs from server.
- **Open:** `HomeViewModel.open(door)` → `OskeysBleController.open(bleLocalName)` → `Flow<DoorOpenState>`
  → per-door UI state.

## BLE ↔ ESP32 protocol (iteration 1)

Fixed-command write: app writes ASCII `"OPEN"` to the command characteristic; ESP32 pulses the GPIO.
**Security note:** no cryptographic auth at this stage (anyone who knows the payload/UUIDs can open).
A challenge-response upgrade (ESP32 sends a nonce, app returns an HMAC over it with a shared secret,
ESP32 verifies before opening) is deferred to a later iteration and explicitly out of scope here.

## Demo prerequisites (backend seed data)

Mongo must contain: a building with at least one door whose `bleLocalName` matches the ESP32
(e.g. `OSKEYS-DOOR-01`), and an unredeemed activation code pointing at that building. During
implementation, inspect the server's seeding (`db/Documents.kt`, `db/Mongo.kt`, `Application.kt`);
if no seeder exists, add a small idempotent one so the demo is reproducible.

## Error handling

- Bluetooth disabled → `Error(BluetoothOff)` → UI prompts to enable Bluetooth.
- Permission denied → `Error(PermissionMissing)` / pre-check guidance.
- Peripheral not found within timeout → `Error(NotFound)` with retry.
- Connect/write failure → `Error(ConnectionFailed/WriteFailed)` with retry.
- Activation offline/server → mapped FR messages from `ActivationResult`.

## Testing

- `HomeViewModel` unit tests with a **fake `DoorsRepository`** and a **fake `OskeysBleController`**:
  - open happy path folds states to `Opened`; error states surface per-door.
  - `activate` maps each `ActivationResult` to the right message; success closes the sheet.
  - empty vs populated `uiState`.
- The Android BLE impl (`AndroidOskeysBleController`) is validated **manually on device** against the
  ESP32 (framework code; not unit-tested for this demo).

## Out of scope (next sub-projects, each its own spec)

Access sharing (one-time code / invitation / permanent), WebRTC remote open, History, Menu/profile,
BLE challenge-response security.

## Roadmap context (persisted separately in memory `oskeys-resident-roadmap`)

| # | Feature | State |
|---|---------|-------|
| 1 | Onboarding / Login | done |
| 2 | **Home: doors list + BLE open + activation** | ← this spec |
| 3 | Access sharing (Invitations) | later (needs backend work) |
| 4 | Access history | later |
| 5 | Menu / profile / logout | later |
| 6 | Remote open (WebRTC) | later (needs backend signaling) |
| — | BLE challenge-response security | later |
