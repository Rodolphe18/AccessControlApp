# Personalized invitation (windowed, multi-use PIN) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Git note:** the repo owner commits/pushes. Do NOT run `git commit`/`git push`. "Checkpoint" = tell the owner it's ready to commit.

**Goal:** A resident creates a titled invitation for one door, valid over a start→end window, that a guest uses any number of times on the intercom during that window.

**Architecture:** Generalize the existing one-time-PIN model instead of adding a parallel one — extend `PinCodeDoc` with a validity window + a `singleUse` flag + a `title`, add `POST/GET /me/invitations`, and generalize `/intercom/validate` (window check; consume only when `singleUse`). Resident app gets a "Créer une invitation" form in the existing Invitations tab. Intercom & BLE are untouched.

**Tech Stack:** Kotlin, Compose (Material3 date/time pickers), Hilt, Retrofit, Coroutines, JUnit4 + kotlinx-coroutines-test; Ktor + MongoDB backend.

**Spec:** `docs/superpowers/specs/2026-07-18-personalized-invitation-design.md`

---

## File structure

**Backend** (`AccessControllerServer`): `db/Documents.kt`, `api/Dto.kt`, `api/Routing.kt`
**core:network**: `model/NetworkModels.kt`, `OskeysApiService.kt`
**core:model**: new `Invitation.kt`
**core:data**: new `repository/InvitationRepository.kt` + `InvitationRepositoryImpl.kt`, `di/DataModule.kt`
**feature:sharing**: new `InvitationUiState.kt`, `InvitationViewModel.kt`, `InvitationSection.kt`; edit `SharingScreen.kt`; tests

---

## Task 1: Backend — windowed multi-use codes + invitation endpoints

**Files:** `db/Documents.kt`, `api/Dto.kt`, `api/Routing.kt`

- [ ] **Step 1: Extend `PinCodeDoc`** — add three fields after `expiresAtEpochMs`:

```kotlin
    val validFromEpochMs: Long = 0L,
    val singleUse: Boolean = true,
    val title: String? = null,
```

- [ ] **Step 2: Set the new fields when creating a one-time code.** In `POST /me/pin-codes`, change the `PinCodeDoc(...)` construction to include `validFromEpochMs = now` (leave `singleUse`/`title` at their defaults):

```kotlin
        storage.pinCodes.insertOne(
            PinCodeDoc(
                pin = pin,
                issuedByUserId = userId,
                buildingId = building.id,
                doorId = door.id,
                doorName = door.name,
                doorBleLocalName = door.bleLocalName,
                createdAtEpochMs = now,
                validFromEpochMs = now,
                expiresAtEpochMs = expiresAt,
            ),
        )
```

- [ ] **Step 3: Add DTOs to `api/Dto.kt`**:

```kotlin
@Serializable
data class CreateInvitationRequest(
    val title: String,
    val doorId: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationDto(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationsResponse(val invitations: List<InvitationDto>)
```

- [ ] **Step 4: Add the two invitation endpoints inside `meRoutes`** (after `GET /me/pin-codes`):

```kotlin
    // POST /me/invitations — a titled, windowed, multi-use code for one door.
    post("/me/invitations") {
        val userId = call.userId()
        val body = call.receive<CreateInvitationRequest>()
        if (body.title.isBlank() || body.validUntilEpochMs <= body.validFromEpochMs) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Titre ou fenêtre invalide"))
            return@post
        }
        val user = storage.users.find(Filters.eq("_id", userId)).firstOrNull()
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Utilisateur introuvable"))
            return@post
        }
        val buildings = storage.buildings.find(Filters.`in`("_id", user.buildingIds)).toList()
        val match = buildings.firstNotNullOfOrNull { b -> b.doors.find { it.id == body.doorId }?.let { b to it } }
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Porte introuvable"))
            return@post
        }
        val (building, door) = match
        val code = generateUniquePin(storage)
        storage.pinCodes.insertOne(
            PinCodeDoc(
                pin = code,
                issuedByUserId = userId,
                buildingId = building.id,
                doorId = door.id,
                doorName = door.name,
                doorBleLocalName = door.bleLocalName,
                createdAtEpochMs = System.currentTimeMillis(),
                validFromEpochMs = body.validFromEpochMs,
                expiresAtEpochMs = body.validUntilEpochMs,
                singleUse = false,
                title = body.title.trim(),
            ),
        )
        call.respond(
            InvitationDto(
                code = code,
                title = body.title.trim(),
                doorName = door.name,
                validFromEpochMs = body.validFromEpochMs,
                validUntilEpochMs = body.validUntilEpochMs,
            ),
        )
    }

    // GET /me/invitations — the caller's invitations whose window hasn't ended yet.
    get("/me/invitations") {
        val userId = call.userId()
        val now = System.currentTimeMillis()
        val codes = storage.pinCodes.find(
            Filters.and(
                Filters.eq("issuedByUserId", userId),
                Filters.eq("singleUse", false),
                Filters.gt("expiresAtEpochMs", now),
            ),
        ).toList()
        call.respond(
            InvitationsResponse(
                codes.map {
                    InvitationDto(
                        code = it.pin,
                        title = it.title ?: "",
                        doorName = it.doorName,
                        validFromEpochMs = it.validFromEpochMs,
                        validUntilEpochMs = it.expiresAtEpochMs,
                    )
                },
            ),
        )
    }
```

- [ ] **Step 5: Generalize `POST /intercom/validate`** — replace the `when` block in `intercomRoutes` with a window-aware version that consumes only single-use codes:

```kotlin
        val pin = call.receive<IntercomValidateRequest>().pin.trim()
        val now = System.currentTimeMillis()
        val doc = storage.pinCodes.find(Filters.eq("_id", pin)).firstOrNull()
        when {
            doc == null ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code inconnu"))
            now < doc.validFromEpochMs ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Invitation pas encore active"))
            now > doc.expiresAtEpochMs ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code expiré"))
            doc.singleUse && doc.redeemedAtEpochMs != null ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
            doc.singleUse -> {
                val claimed = storage.pinCodes.updateOne(
                    Filters.and(Filters.eq("_id", pin), Filters.eq("redeemedAtEpochMs", null)),
                    Updates.set("redeemedAtEpochMs", now),
                )
                if (claimed.modifiedCount == 0L) {
                    call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
                } else {
                    call.respond(IntercomValidateResponse(allowed = true, doorName = doc.doorName, doorBleLocalName = doc.doorBleLocalName))
                }
            }
            else -> // multi-use invitation: allowed, not consumed
                call.respond(IntercomValidateResponse(allowed = true, doorName = doc.doorName, doorBleLocalName = doc.doorBleLocalName))
        }
```

- [ ] **Step 6: Compile and verify via curl** (server restarted).

```bash
B=http://localhost:8080
TOKEN=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' -d '{"email":"rodolphe@example.com","password":"password"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
NOW=$(($(date +%s)*1000)); END=$((NOW+1800000))
# Create an invitation active now for 30 min
INV=$(curl -s -X POST $B/me/invitations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"title\":\"Test\",\"doorId\":\"door-hall\",\"validFromEpochMs\":$NOW,\"validUntilEpochMs\":$END}")
echo "$INV"; CODE=$(echo "$INV" | sed -E 's/.*"code":"([0-9]+)".*/\1/')
# Validate twice (multi-use → both allowed)
curl -s -X POST $B/intercom/validate -H 'X-Intercom-Key: oskeys-demo-intercom-key' -H 'Content-Type: application/json' -d "{\"pin\":\"$CODE\"}"; echo
curl -s -X POST $B/intercom/validate -H 'X-Intercom-Key: oskeys-demo-intercom-key' -H 'Content-Type: application/json' -d "{\"pin\":\"$CODE\"}"; echo
# Future invitation → not yet active
FUT=$((NOW+3600000)); FEND=$((NOW+7200000))
INV2=$(curl -s -X POST $B/me/invitations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"title\":\"Futur\",\"doorId\":\"door-hall\",\"validFromEpochMs\":$FUT,\"validUntilEpochMs\":$FEND}")
CODE2=$(echo "$INV2" | sed -E 's/.*"code":"([0-9]+)".*/\1/')
curl -s -X POST $B/intercom/validate -H 'X-Intercom-Key: oskeys-demo-intercom-key' -H 'Content-Type: application/json' -d "{\"pin\":\"$CODE2\"}"; echo
```

Expected: both validations of the active code → `allowed:true`; the future code → `allowed:false,"Invitation pas encore active"`.

- [ ] **Step 7: Checkpoint.**

---

## Task 2: core:network — invitation DTOs + API

**Files:** `core/network/.../model/NetworkModels.kt`, `.../OskeysApiService.kt`

- [ ] **Step 1: Add DTOs to `NetworkModels.kt`**:

```kotlin
@Serializable
data class CreateInvitationRequestNetwork(
    val title: String,
    val doorId: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationNetwork(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationsResponseNetwork(val invitations: List<InvitationNetwork>)
```

- [ ] **Step 2: Add methods + imports to `OskeysApiService.kt`** (import the three new types alongside the existing model imports):

```kotlin
    @POST("me/invitations")
    suspend fun createInvitation(
        @Header("Authorization") bearer: String,
        @Body body: CreateInvitationRequestNetwork,
    ): InvitationNetwork

    @GET("me/invitations")
    suspend fun getInvitations(@Header("Authorization") bearer: String): InvitationsResponseNetwork
```

- [ ] **Step 3: Compile.** `./gradlew :core:network:compileDebugKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Checkpoint.**

---

## Task 3: core:model + core:data — Invitation + repository

**Files:** new `core/model/.../Invitation.kt`, new `core/data/.../repository/InvitationRepository.kt` + `InvitationRepositoryImpl.kt`, `core/data/.../di/DataModule.kt`

- [ ] **Step 1: Create `core/model/.../core/model/Invitation.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.model

/** A titled, windowed, multi-use access invitation for a door. */
data class Invitation(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)
```

- [ ] **Step 2: Create `core/data/.../repository/InvitationRepository.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.model.Invitation
import kotlinx.coroutines.flow.Flow

interface InvitationRepository {
    val activeInvitations: Flow<List<Invitation>>

    suspend fun refreshInvitations(): Result<Unit>

    suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation>
}
```

- [ ] **Step 3: Create `core/data/.../repository/InvitationRepositoryImpl.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.datastore.SessionDataSource
import dev.rodolphe.oskeysdemo.core.model.Invitation
import dev.rodolphe.oskeysdemo.core.network.OskeysApiService
import dev.rodolphe.oskeysdemo.core.network.model.CreateInvitationRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.InvitationNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class InvitationRepositoryImpl @Inject constructor(
    private val api: OskeysApiService,
    private val sessionDataSource: SessionDataSource,
) : InvitationRepository {

    private val _activeInvitations = MutableStateFlow<List<Invitation>>(emptyList())
    override val activeInvitations: Flow<List<Invitation>> = _activeInvitations.asStateFlow()

    override suspend fun refreshInvitations(): Result<Unit> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = api.getInvitations(bearer(jwt))
            _activeInvitations.value = response.invitations.map { it.asExternalModel() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val invitation = api.createInvitation(
                bearer(jwt),
                CreateInvitationRequestNetwork(title, doorId, validFromEpochMs, validUntilEpochMs),
            ).asExternalModel()
            _activeInvitations.update { (it + invitation).sortedBy { i -> i.validUntilEpochMs } }
            Result.success(invitation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun currentJwt(): String? =
        sessionDataSource.session.first().jwt.ifEmpty { null }

    private fun bearer(jwt: String): String = "Bearer $jwt"
}

private fun InvitationNetwork.asExternalModel() = Invitation(
    code = code,
    title = title,
    doorName = doorName,
    validFromEpochMs = validFromEpochMs,
    validUntilEpochMs = validUntilEpochMs,
)
```

- [ ] **Step 4: Bind it in `di/DataModule.kt`** — add the import and:

```kotlin
    @Binds
    @Singleton
    abstract fun bindInvitationRepository(impl: InvitationRepositoryImpl): InvitationRepository
```

- [ ] **Step 5: Compile.** `./gradlew :core:data:compileDebugKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: Checkpoint.**

---

## Task 4: feature:sharing — InvitationViewModel (TDD) + creation UI

**Files:** new `InvitationUiState.kt`, `InvitationViewModel.kt`, `InvitationSection.kt`; edit `SharingScreen.kt`; tests `FakeInvitationRepository.kt`, `InvitationViewModelTest.kt`.

- [ ] **Step 1: Create `InvitationUiState.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.Invitation

data class InvitationUiState(
    val doors: List<Door> = emptyList(),
    val selectedDoorId: DoorId? = null,
    val title: String = "",
    val validFromEpochMs: Long = 0L,
    val validUntilEpochMs: Long = 0L,
    val isCreating: Boolean = false,
    val error: String? = null,
    val created: Invitation? = null,
    val invitations: List<Invitation> = emptyList(),
) {
    val canCreate: Boolean
        get() = title.isNotBlank() && selectedDoorId != null &&
            validUntilEpochMs > validFromEpochMs && !isCreating
}
```

- [ ] **Step 2: Create the test double `FakeInvitationRepository.kt`** (test source):

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.data.repository.InvitationRepository
import dev.rodolphe.oskeysdemo.core.model.Invitation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeInvitationRepository : InvitationRepository {
    val flow = MutableStateFlow<List<Invitation>>(emptyList())
    override val activeInvitations: Flow<List<Invitation>> = flow

    var refreshCalls = 0
    override suspend fun refreshInvitations(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }

    var createResult: Result<Invitation> = Result.success(Invitation("000000", "T", "Porte", 0L, 1L))
    var lastArgs: List<Any?>? = null
    override suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation> {
        lastArgs = listOf(title, doorId, validFromEpochMs, validUntilEpochMs)
        createResult.getOrNull()?.let { flow.value = flow.value + it }
        return createResult
    }
}
```

(Reuse the existing `FakeDoorsRepository` and `MainDispatcherRule` already in this test source set.)

- [ ] **Step 3: Write the failing tests `InvitationViewModelTest.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.Invitation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val door = Door(DoorId("door-hall"), "Porte d'entrée", "Résidence Montmartre", "OSKEY-HALL-01")

    private fun vm(
        doors: FakeDoorsRepository = FakeDoorsRepository(),
        invitations: FakeInvitationRepository = FakeInvitationRepository(),
    ) = InvitationViewModel(doors, invitations)

    @Test
    fun `init refreshes invitations and selects first door`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository()
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(1, inv.refreshCalls)
        assertEquals(door.id, viewModel.uiState.value.selectedDoorId)
    }

    @Test
    fun `canCreate requires title, door and a positive window`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val viewModel = vm(doors = doors)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertFalse(viewModel.uiState.value.canCreate)      // empty title
        viewModel.onTitleChange("Anniversaire")
        viewModel.onWindowChange(1_000L, 2_000L)
        runCurrent()
        assertTrue(viewModel.uiState.value.canCreate)
    }

    @Test
    fun `create success surfaces the invitation`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository().apply {
            createResult = Result.success(Invitation("483920", "Anniversaire", "Porte d'entrée", 1L, 2L))
        }
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        viewModel.onTitleChange("Anniversaire")
        viewModel.onWindowChange(1L, 2L)
        runCurrent()

        viewModel.create()
        runCurrent()

        assertEquals("483920", viewModel.uiState.value.created?.code)
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("Anniversaire", "door-hall", 1L, 2L), inv.lastArgs)
        assertEquals(1, viewModel.uiState.value.invitations.size)
    }

    @Test
    fun `create failure sets an error`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository().apply { createResult = Result.failure(RuntimeException("boom")) }
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        viewModel.onTitleChange("X")
        viewModel.onWindowChange(1L, 2L)
        runCurrent()

        viewModel.create()
        runCurrent()

        assertEquals("Impossible de créer l'invitation. Réessayez.", viewModel.uiState.value.error)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail.** `./gradlew :feature:sharing:testDebugUnitTest` → FAIL (`InvitationViewModel` unresolved).

- [ ] **Step 5: Implement `InvitationViewModel.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.data.repository.InvitationRepository
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.Invitation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InvitationViewModel @Inject constructor(
    doorsRepository: DoorsRepository,
    private val invitationRepository: InvitationRepository,
) : ViewModel() {

    private data class Local(
        val selectedDoorId: DoorId? = null,
        val title: String = "",
        val validFromEpochMs: Long = 0L,
        val validUntilEpochMs: Long = 0L,
        val isCreating: Boolean = false,
        val error: String? = null,
        val created: Invitation? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<InvitationUiState> =
        combine(doorsRepository.doors, invitationRepository.activeInvitations, local) { doors, invitations, l ->
            InvitationUiState(
                doors = doors,
                selectedDoorId = l.selectedDoorId ?: doors.firstOrNull()?.id,
                title = l.title,
                validFromEpochMs = l.validFromEpochMs,
                validUntilEpochMs = l.validUntilEpochMs,
                isCreating = l.isCreating,
                error = l.error,
                created = l.created,
                invitations = invitations,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InvitationUiState(),
        )

    init {
        viewModelScope.launch { invitationRepository.refreshInvitations() }
    }

    fun selectDoor(doorId: DoorId) = local.update { it.copy(selectedDoorId = doorId) }
    fun onTitleChange(value: String) = local.update { it.copy(title = value, error = null) }
    fun onWindowChange(fromMs: Long, untilMs: Long) =
        local.update { it.copy(validFromEpochMs = fromMs, validUntilEpochMs = untilMs) }

    fun create() {
        val s = uiState.value
        val doorId = s.selectedDoorId ?: return
        if (!s.canCreate) return
        local.update { it.copy(isCreating = true, error = null, created = null) }
        viewModelScope.launch {
            invitationRepository.createInvitation(s.title.trim(), doorId.value, s.validFromEpochMs, s.validUntilEpochMs).fold(
                onSuccess = { inv -> local.update { it.copy(isCreating = false, created = inv) } },
                onFailure = { local.update { it.copy(isCreating = false, error = "Impossible de créer l'invitation. Réessayez.") } },
            )
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass.** `./gradlew :feature:sharing:testDebugUnitTest` → PASS (4 new + 4 existing sharing tests).

- [ ] **Step 7: Create `InvitationSection.kt`** — the "Créer une invitation" button, a bottom-sheet form with a title field, door chips, two date+time fields, and the active-invitation list. Self-contained (own ViewModel).

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.oskeysdemo.core.model.Invitation
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InvitationSection(viewModel: InvitationViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("Créer une invitation", style = MaterialTheme.typography.titleMedium)
        Text(
            "Un accès nommé, valable sur une période, réutilisable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { sheetOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Créer une invitation")
        }

        uiState.created?.let { inv -> Spacer(Modifier.height(12.dp)); InvitationCard(inv) }

        if (uiState.invitations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Invitations actives", style = MaterialTheme.typography.titleSmall)
            uiState.invitations.forEach { inv -> Spacer(Modifier.height(8.dp)); InvitationCard(inv) }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            InvitationForm(
                uiState = uiState,
                onTitleChange = viewModel::onTitleChange,
                onSelectDoor = viewModel::selectDoor,
                onWindowChange = viewModel::onWindowChange,
                onCreate = viewModel::create,
            )
        }
        // Close the sheet as soon as a code was produced.
        if (uiState.created != null) sheetOpen = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InvitationForm(
    uiState: InvitationUiState,
    onTitleChange: (String) -> Unit,
    onSelectDoor: (dev.rodolphe.oskeysdemo.core.model.DoorId) -> Unit,
    onWindowChange: (Long, Long) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Nouvelle invitation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.title,
            onValueChange = onTitleChange,
            label = { Text("Titre (ex. Anniversaire de Coralie)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            uiState.doors.forEach { door ->
                FilterChip(
                    selected = uiState.selectedDoorId == door.id,
                    onClick = { onSelectDoor(door.id) },
                    label = { Text(door.name) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        DateField("Début", uiState.validFromEpochMs) { onWindowChange(it, uiState.validUntilEpochMs) }
        Spacer(Modifier.height(8.dp))
        DateField("Fin", uiState.validUntilEpochMs) { onWindowChange(uiState.validFromEpochMs, it) }

        if (uiState.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(uiState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate, enabled = uiState.canCreate, modifier = Modifier.fillMaxWidth()) {
            if (uiState.isCreating) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
            else Text("Créer")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, epochMs: Long, onPicked: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE) }
    val text = if (epochMs > 0) fmt.format(java.util.Date(epochMs)) else "Choisir une date"
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label : $text") }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = if (epochMs > 0) epochMs else System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onPicked)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun InvitationCard(inv: Invitation) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(inv.title, style = MaterialTheme.typography.titleMedium)
            Text("${inv.code} · ${inv.doorName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Du ${fmt.format(java.util.Date(inv.validFromEpochMs))} au ${fmt.format(java.util.Date(inv.validUntilEpochMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Note: this DateField uses **date granularity** (the window is `[start-of-selected-day, selected-day]`).
The date-picker returns UTC midnight millis, which is sufficient for the demo window; a full time
picker is deferred. If day-precision proves too coarse during verification, add a Material3 `TimePicker`
dialog after date selection — but do not block Task 4 on it.

- [ ] **Step 8: Insert `InvitationSection()` into `SharingScreen.kt`.** Make the content Column
  scrollable and append the section. Add the import:

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
```

Change the content `Column(...)` modifier to add `.verticalScroll(rememberScrollState())`, replace the
trailing `LazyColumn(...) { items(...) { ActivePinRow(it) } }` with a plain loop, and append the
section. Concretely, replace this tail of `SharingScreen`:

```kotlin
            Spacer(Modifier.height(24.dp))
            Text("Envoyées", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(uiState.activePins, key = { it.pin }) { pin -> ActivePinRow(pin) }
            }
        }
    }
```

with:

```kotlin
            Spacer(Modifier.height(24.dp))
            Text("Envoyées", style = MaterialTheme.typography.titleMedium)
            uiState.activePins.forEach { pin ->
                Spacer(Modifier.height(8.dp))
                ActivePinRow(pin)
            }

            Spacer(Modifier.height(32.dp))
            InvitationSection()
        }
    }
```

And change the content column header from:

```kotlin
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
```

to:

```kotlin
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
```

Remove the now-unused `LazyColumn`/`items` imports if the linter flags them.

- [ ] **Step 9: Assemble.** `./gradlew :feature:sharing:assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 10: Install the resident app.** `./gradlew :app:installDebug` → `Installed on 1 device`.

- [ ] **Step 11: Checkpoint.**

---

## Task 5: End-to-end verification (manual)

- [ ] **Step 1:** Backend restarted, ESP32 on, both devices on LAN Wi-Fi.
- [ ] **Step 2:** Resident app → Invitations → **Créer une invitation** → title "Test", door "Porte d'entrée", start = today, end = today (or tomorrow) → **Créer**. Note the code.
- [ ] **Step 3:** Intercom (2nd device) → type the code → **OK** → opens (LED). Type it **again** → opens **again** (multi-use, not consumed).
- [ ] **Step 4:** Create an invitation with a start date in the future → intercom shows "Invitation pas encore active".
- [ ] **Step 5:** Regression — a one-time quick code still opens once then "Code déjà utilisé".
- [ ] **Step 6: Checkpoint** — iteration 3 done; ready to commit.

---

## Self-review notes

- **Spec coverage:** windowed multi-use model (T1 doc+validate), invitation endpoints (T1), network (T2), model+repo (T3), resident creation UI + ViewModel (T4), E2E incl. multi-use + not-yet-active + one-time regression (T5). All mapped.
- **Type consistency:** `InvitationDto/Network(code, title, doorName, validFromEpochMs, validUntilEpochMs)` and `Invitation(...)` identical across backend, network, model, repo, fakes, ViewModel. `InvitationRepository(activeInvitations, refreshInvitations, createInvitation)` used identically in impl, fake, and ViewModel. `PinCodeDoc` new fields (`validFromEpochMs`, `singleUse`, `title`) used consistently in creation and validate.
- **Deferred:** multi-door, time slots, limited-use, "Mes proches", map/address — no task, per spec.
```
