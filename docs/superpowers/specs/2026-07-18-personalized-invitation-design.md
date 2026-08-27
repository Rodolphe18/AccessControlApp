# Iteration 3 — Personalized invitation (windowed, multi-use PIN)

**Date:** 2026-07-18
**Status:** Approved, ready for implementation plan
**Context:** Resident app for a senior Android interview at Oskeys. Live demo on 2 Android devices + an
ESP32. Backend: `C:/Users/rodol/IdeaProjects/AccessControllerServer` (Ktor + MongoDB Atlas + JWT).
Builds on iteration 2 (one-time PIN + intercom app), spec
`2026-07-18-access-sharing-one-time-code-design.md`.

Covers **only the "Invitation personnalisée" mode, in its lean form**: a titled, windowed,
multi-use access code used on the intercom. Multi-door, intra-day time slots, limited-use counters,
and permanent access ("Mes proches") are later iterations.

## Goal

Let a resident create a **named invitation** for one door, valid over a **date/time window**, that a
guest uses **as many times as they want** during that window by typing its code on the intercom
(exactly like the one-time PIN, but not consumed and bounded by a start/end instead of a 15-min TTL).

```
Resident (Invitations tab) → "Créer une invitation"
    → title + door + start(datetime) + end(datetime)
    → POST /me/invitations → backend stores a windowed, multi-use code → returns code + title + window
    → app shows the code + window + "Partager"

Guest types the code on the intercom, any number of times within [start, end]
    → POST /intercom/validate {pin} → backend checks NOW ∈ window, does NOT consume → returns door
    → intercom opens the ESP32 over BLE. Outside the window → refused.
```

## Key decision: unify, don't duplicate

An invitation is the **same access-code concept** as the one-time PIN, differing only in its
**validity window** and its **use policy**. So we **extend the existing `PinCodeDoc`** rather than
introduce a parallel model. Interview framing: *one access-grant abstraction, two creation flows*
(quick single-use code vs. windowed multi-use invitation). The intercom and BLE layers are untouched —
the generalization is entirely server-side plus a new resident creation screen.

## Existing foundation to reuse (from iteration 2)

- **Backend** `PinCodeDoc`, `MongoStorage.pinCodes`, `POST /me/pin-codes`, `GET /me/pin-codes`,
  `POST /intercom/validate` (currently: lookup by pin → check expiry → atomic single-use redeem →
  return door). This spec generalizes the validate logic.
- **`core:network`** `OskeysApiService` (pin endpoints), `IntercomApiService`; DTOs in `NetworkModels`.
- **`core:data`** `PinCodeRepository`; **`core:model`** `PinCode`.
- **`feature:sharing`** Invitations tab (`SharingViewModel` + `SharingScreen`, door chips, quick-code
  generation, "Envoyées" list with live countdown).
- **`:intercom`** app — unchanged; it validates any code and opens via `core:ble`.

## Backend changes (`AccessControllerServer`)

### 1. Extend `PinCodeDoc` (`db/Documents.kt`)

Add three fields with backward-compatible defaults (old stored docs deserialize fine):
```kotlin
    val validFromEpochMs: Long = 0L,      // window start; 0 = "always started" (one-time codes)
    val singleUse: Boolean = true,        // one-time code = true; invitation = false
    val title: String? = null,            // invitation label, e.g. "Anniversaire de Coralie"
```
`expiresAtEpochMs` now means "window end" for both kinds. `POST /me/pin-codes` keeps its current
behaviour (sets `validFromEpochMs = now`, `expiresAtEpochMs = now + 15min`, `singleUse = true`,
`title = null`).

### 2. Endpoints (`api/Routing.kt` + `api/Dto.kt`)

- `POST /me/invitations` (JWT) — body `{ title, doorId, validFromEpochMs, validUntilEpochMs }`.
  Validates the door belongs to the user (same helper as pin creation); rejects an empty title or a
  window where `validUntil <= validFrom` (400). Generates a unique 6-digit code, stores a `PinCodeDoc`
  with `singleUse = false`, the title, and the window. Returns
  `InvitationDto { code, title, doorName, validFromEpochMs, validUntilEpochMs }`.
- `GET /me/invitations` (JWT) — the caller's invitations whose window hasn't ended
  (`expiresAtEpochMs > now` AND `singleUse == false`), as `InvitationDto` list.
- `POST /intercom/validate` — **generalized**:
  1. Lookup by code.
  2. If `now < validFromEpochMs` → `allowed=false, reason="Invitation pas encore active"`.
  3. If `now > expiresAtEpochMs` → `allowed=false, reason="Code expiré"`.
  4. If `singleUse`: atomic redeem on `redeemedAtEpochMs == null` (unchanged one-time behaviour;
     already-redeemed → "Code déjà utilisé").
  5. If not `singleUse` (invitation): allow **without consuming** (multi-use).
  6. Return `allowed=true, doorName, doorBleLocalName`.

  `GET /me/pin-codes` stays scoped to one-time codes (`singleUse == true`, unredeemed, not expired) so
  the quick-code list is unchanged.

## Resident app changes (`feature:sharing`)

- **`core:network`**: add `createInvitation` / `getInvitations` to `OskeysApiService`; DTOs
  `CreateInvitationRequestNetwork`, `InvitationNetwork`, `InvitationsResponseNetwork`.
- **`core:model`**: `Invitation(code, title, doorName, validFromEpochMs, validUntilEpochMs)`.
- **`core:data`**: extend `PinCodeRepository` (or a sibling `InvitationRepository`) with
  `activeInvitations: Flow<List<Invitation>>`, `refreshInvitations()`, and
  `createInvitation(title, doorId, validFrom, validUntil): Result<Invitation>`. Decision: a **separate
  `InvitationRepository`** keeps each screen's concerns isolated and mirrors the existing
  `PinCodeRepository` shape.
- **`SharingScreen`**: the Invitations tab presents two clearly separated sections/actions —
  "Générer un code rapide" (existing) and **"Créer une invitation"** (new). The invitation flow is a
  form (a `ModalBottomSheet` or a sub-screen):
  - Title `OutlinedTextField`.
  - Door selection (reuse the `FilterChip` row, single select).
  - Start and End pickers — Material3 `DatePickerDialog` + `TimePicker`; the screen composes each
    date+time into an epoch-millis value.
  - "Créer" → calls the ViewModel → shows the created invitation (title, code, window, **Partager**).
  - A list of active invitations, each with its window (start → end) and a share action.
- A dedicated **`InvitationViewModel`** (in `feature:sharing`, alongside `SharingViewModel`) exposes
  the invitation form state (title, selectedDoorId, validFrom, validUntil, isCreating, error, created)
  and `createInvitation()`; it validates locally that title is non-blank and `validUntil > validFrom`
  before enabling "Créer". Keeping it separate from `SharingViewModel` keeps the quick-code and
  invitation flows independently testable.

## Intercom & BLE

Unchanged. The intercom types a code and calls `/intercom/validate`; for an invitation the server
answers `allowed=true` throughout the window without consuming, so the same code opens the door
repeatedly. The keypad, permission handling, and `core:ble` open flow are identical to iteration 2.

## Error handling

- Resident form: empty title or `end <= start` → "Créer" disabled with inline guidance; network error
  → French message; list refresh failure → keep cache.
- Intercom (reused messages): "Invitation pas encore active" (before start), "Code expiré" (after end),
  BLE errors as before.

## Testing

- **Backend**: `POST /me/invitations` (rejects empty title / inverted window; stores singleUse=false),
  and `POST /intercom/validate` for an invitation — before window (refused), during window (allowed,
  and **allowed again** on a second call = multi-use), after window (refused). Plus a regression check
  that a one-time code still single-uses.
- **ViewModel** (`InvitationViewModel`): fake repository — create success surfaces the invitation and
  clears error; empty title / inverted window disables create; error path maps to a message; active
  list renders. The date/time-picker composition is validated on device.

## Verification (manual, on device)

- Create an invitation for "Porte d'entrée" with a window starting now and ending in ~30 min, titled
  "Test". On the intercom, type its code → opens (LED). Type it **again** → opens **again** (multi-use).
- Create an invitation whose window starts in the future → intercom shows "Invitation pas encore
  active". Let a short window elapse → "Code expiré".

## Out of scope (later iterations, each its own spec)

Multiple authorized doors per invitation, intra-day time slots, limited-use (N-openings) invitations,
permanent access / "Mes proches", a map/address on the invitation, BLE challenge-response security.
