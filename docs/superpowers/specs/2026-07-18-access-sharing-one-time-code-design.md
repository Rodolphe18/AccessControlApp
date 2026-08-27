# Iteration 2 — Access sharing: one-time PIN code + intercom app

**Date:** 2026-07-18
**Status:** Approved, ready for implementation plan
**Context:** Resident app for a senior Android interview at Oskeys. Live demo on physical Android
device(s) + an ESP32 (real BLE lock). Backend: `C:/Users/rodol/IdeaProjects/AccessControllerServer`
(Ktor + MongoDB Atlas + JWT). Builds on iteration 1 (Home + BLE open),
`2026-07-18-resident-home-ble-design.md`.

Covers **only the "Code à usage unique" mode, realized as a numeric PIN typed on the intercom** (per
Oskeys' two access methods: contactless BLE, and PIN on the intercom keypad). Personalized invitations
and permanent access are separate later iterations.

> **Supersedes an earlier draft of this spec** that assumed the one-time code opened a door via a
> Web Bluetooth guest web page (requiring VPS + Nginx + Let's Encrypt + HTTPS). That was a
> misunderstanding: the one-time code is a **PIN entered on the intercom**, not a browser link. All of
> that hosting/Web-Bluetooth machinery is therefore **dropped**.

## Goal

A resident generates an instant, single-use, time-limited **numeric PIN** for one of their doors and
shares it (plain text) with a guest/delivery person. The guest types the PIN on the building
**intercom**; the intercom validates it with the backend and, if valid, opens the real lock (ESP32)
over BLE. The PIN expires when used or when its TTL elapses.

```
Resident (Invitations tab) → pick a door → "Générer un code rapide"
    → POST /me/pin-codes → backend stores PIN (numeric, TTL, single-use) → returns PIN + expiry
    → app shows PIN + live countdown + "Partager" (Android share sheet, plain text)

Guest (at the door) types PIN on the Intercom app (2nd Android device)
    → POST /intercom/validate {pin} (device key) → backend validates + consumes → returns door bleLocalName
    → intercom opens the ESP32 over BLE (reuses core:ble) → LED pulses → "Accès autorisé"
```

Both access methods now open the **same physical lock**: method 1 (resident app, BLE) from iteration 1,
and method 2 (guest PIN → intercom → BLE) here.

## Why this shape (decisions)

- **Intercom is a 2nd Android app**, not the ESP32 with a keypad: avoids new hardware (keypad, ESP32
  WiFi/firmware). The user has a 2nd Android device with BLE.
- **Intercom opens the real ESP32 over BLE** after validating the PIN (reuses `core:ble`), so the PIN
  path opens a real door (LED), not a fake animation. The ESP32 firmware is unchanged.
- **No VPS/HTTPS/tunnel**: the intercom app calls the backend over plain HTTP on the LAN. Secure
  context was only needed for browser Web Bluetooth, which we no longer use.

## Existing foundation to reuse

- **`core:ble`** (`OskeysBleController.open(bleLocalName): Flow<DoorOpenState>`, `BleContract`) — the
  intercom app depends on it to open the lock. ESP32 firmware in `hardware/esp32-door/` unchanged.
- **Doors**: `DoorsRepository.doors` (Room) lists the resident's doors with `bleLocalName`; the
  generate screen picks from these.
- **Backend** patterns: `Dto.kt`, `Routing.kt` (`authenticate("auth-jwt")`, `call.userId()`),
  `MongoStorage`, `Documents.kt`. Seed: doors "Porte d'entrée"/`OSKEY-HALL-01`, "Garage"/`OSKEY-GARAGE-01`,
  building "Résidence Montmartre", user `rodolphe@example.com`/`password`.
- **Navigation**: `OskeysNavHost` `Invitations` tab currently a placeholder — filled here.

## Components

### 1. Backend (`AccessControllerServer`)

**Document** (`db/Documents.kt`) + `MongoStorage.pinCodes = db.getCollection<PinCodeDoc>("pin_codes")`:
```kotlin
@Serializable
data class PinCodeDoc(
    @SerialName("_id") val pin: String,        // 6 digits, e.g. "483920"
    val issuedByUserId: String,
    val buildingId: String,
    val doorId: String,
    val doorName: String,
    val doorBleLocalName: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val redeemedAtEpochMs: Long? = null,
)
```

**Endpoints** (`api/Routing.kt` + `api/Dto.kt`):
- `POST /me/pin-codes` (JWT) — body `{ doorId }`. Verifies the door is in a building the user belongs
  to; generates a **unique 6-digit** PIN (retry on collision among non-expired codes); TTL 15 min;
  stores; returns `CreatePinResponse { pin, doorName, expiresAtEpochMs }`.
- `GET /me/pin-codes` (JWT) — the caller's non-expired, non-redeemed PINs (`PinCodeDto` list) so the
  app shows active codes with countdowns.
- `POST /intercom/validate` — header `X-Intercom-Key: <shared secret>` (rejects without it, 401);
  body `{ pin }`. Atomically redeems the PIN **only if** it exists, is not expired, and is not already
  redeemed. 200 → `IntercomValidateResponse { allowed = true, doorName, doorBleLocalName }`; on
  invalid/expired/used → 200 `{ allowed = false, reason }` (or 4xx — pick one; see below). The atomic
  update guarantees single-use even under concurrent taps.

Config: `INTERCOM_KEY` env (shared secret). PIN is numeric because it is typed on a numeric keypad.

Response convention: `POST /intercom/validate` returns **HTTP 200 with `allowed:false`** for a
well-formed but invalid/expired/used PIN (so the intercom shows "Code refusé"), and 401 only for a
missing/bad `X-Intercom-Key`.

### 2. Android — resident app: `feature:sharing` (new module, Invitations tab)

- Deps mirror `feature:home` (compose, hilt, core:data, core:model, core:designsystem, activity-compose).
- `core:network`: add `POST /me/pin-codes`, `GET /me/pin-codes` to `OskeysApiService` + DTOs.
- `core:data`: `PinCodeRepository` (interface + impl) — `createPin(doorId): Result<PinCode>`,
  `refreshPins()` (suspend, fetches into a `MutableStateFlow`), `activePins: Flow<List<PinCode>>`.
  Domain model `PinCode(pin, doorName, expiresAt)` in `core:model`.
- `SharingViewModel` + `SharingScreen` (Invitations tab):
  - Header "Mes invitations".
  - Door picker (from `DoorsRepository.doors`) + "Générer un code rapide".
  - On generate: show the **PIN** large, a **live countdown** (from `expiresAt`, ticking each second),
    and **"Partager"** → `Intent.ACTION_SEND` (plain-text message with the PIN + door + validity).
  - List of active PINs ("Envoyées") each with its countdown; expired ones drop on refresh.
- Wire into `OskeysNavHost`: `Invitations` → `SharingRoute()`.

### 3. Android — new Intercom app (`:intercom` application module, 2nd device)

A separate `com.android.application` module in the same Gradle project (own `applicationId`
`dev.rodolphe.oskeysdemo.intercom`, own `@HiltAndroidApp`). Represents the wall intercom. It is its own
APK with its own launcher icon — built/installed with `./gradlew :intercom:installDebug` and launched
by tapping its icon (or `adb shell am start -n dev.rodolphe.oskeysdemo.intercom/.MainActivity`). Runs
on the 2nd Android device.
- Depends on `core:ble` (to open the lock), `core:designsystem`; has its own thin Retrofit setup (or
  reuses `core:network`) exposing `POST /intercom/validate` with the `X-Intercom-Key` header
  (key + base URL from `BuildConfig`).
- `IntercomViewModel` + `IntercomScreen`:
  - A numeric **keypad** (0–9, clear, validate) building up the entered PIN, masked/shown as dots or
    digits.
  - On validate: `POST /intercom/validate {pin}` → if `allowed`, call
    `OskeysBleController.open(doorBleLocalName)` and reflect the `DoorOpenState`
    (Recherche→…→"Accès autorisé, porte ouverte"); if not allowed → "Code refusé".
  - BLE runtime permission handling (same as the resident Home screen).
- Needs the BLE permissions in its manifest (merged from `core:ble`).

### 4. ESP32

Unchanged. Still advertises `OSKEY-HALL-01`, opens on `"OPEN"` write. The intercom app is just another
BLE central that writes `OPEN` after a valid PIN.

## Security / expiry semantics

- Single-use: `POST /intercom/validate` redeems atomically (conditional update on
  `redeemedAtEpochMs == null && not expired`), so a second attempt returns `allowed:false`.
- PIN space: 6 digits = 1,000,000 combos; codes are short-lived (15 min) and single-use. The
  `X-Intercom-Key` gates who may call validate, mitigating brute force. Demo-level; a real system would
  rate-limit and lock out.
- Documented limitation (unchanged from iter 1): the BLE open at the ESP32 is itself unauthenticated —
  physical proximity + the lock's `bleLocalName` suffices. Challenge-response BLE is a later iteration.

## Error handling

- Resident app: no door selected → generate disabled; network error on create → French message; list
  refresh failure → keep cache, no crash.
- Intercom app: empty PIN → validate disabled; `allowed:false` → "Code refusé"; network error →
  "Interphone hors ligne"; BLE errors reuse `DoorOpenError` messages (Bluetooth off, not found,
  connection failed, timeout).

## Testing

- **Backend**: PIN generation (6 digits, uniqueness), `GET /me/pin-codes` filtering (only active),
  `POST /intercom/validate` states (valid→allowed+consumed, second call→not allowed, expired→not
  allowed, missing key→401).
- **`SharingViewModel`** (resident): fake `PinCodeRepository` + fake `DoorsRepository` — generate
  success populates PIN + countdown; error→message; active list renders; share payload contains the PIN.
- **`IntercomViewModel`**: fake intercom API + fake `OskeysBleController` — allowed PIN triggers a BLE
  open and success state; refused PIN shows "Code refusé" and does NOT call BLE; network error path.
- **End-to-end (manual, stage B)**: resident generates a PIN → type it on the intercom app (2nd
  device) near the ESP32 → LED pulses → "Accès autorisé"; re-entering the same PIN → "Code refusé".

## Two-stage delivery

- **Stage A — PIN lifecycle (resident + backend), local:** `PinCodeDoc` + the three endpoints +
  `feature:sharing` UI. Verifiable locally: generate a PIN in the app, see the countdown + share sheet,
  and validate/consume via `curl` to `/intercom/validate`.
- **Stage B — Intercom app + real open:** build the `:intercom` app, run it on the 2nd device, and
  verify end-to-end that a valid PIN opens the ESP32 (LED) and is then single-use.

## Out of scope (later iterations, each its own spec)

Personalized invitations (date/time-slot/door selection, named guest), permanent access / "Mes
proches", BLE challenge-response security, and a real hardware keypad/standalone intercom firmware.
