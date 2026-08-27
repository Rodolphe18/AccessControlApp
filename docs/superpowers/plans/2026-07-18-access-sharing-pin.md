# Access sharing — one-time PIN + intercom app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Git note:** the repo owner commits/pushes. Do NOT run `git commit`/`git push`. "Checkpoint" = tell the owner it's ready to commit.

**Goal:** A resident generates a single-use, 15-min numeric PIN for a door; a guest types it on a separate Intercom Android app which validates it with the backend and opens the real ESP32 lock over BLE.

**Architecture:** Backend adds PIN documents + `POST /me/pin-codes`, `GET /me/pin-codes` (resident, JWT) and `POST /intercom/validate` (intercom, shared key). Resident app gets a `feature:sharing` Invitations tab. A new `:intercom` application module validates PINs and reuses `core:ble` to open the lock. No VPS/HTTPS.

**Tech Stack:** Kotlin, Compose, Hilt, Retrofit, Coroutines, JUnit4 + kotlinx-coroutines-test; Ktor + MongoDB backend.

**Spec:** `docs/superpowers/specs/2026-07-18-access-sharing-one-time-code-design.md`

---

## File structure

**Backend** (`C:/Users/rodol/IdeaProjects/AccessControllerServer`)
- Modify `db/Documents.kt`, `db/Mongo.kt`, `api/Dto.kt`, `api/Routing.kt`, `Application.kt`

**core:network** — `OskeysApiService.kt`, new `IntercomApiService.kt`, `model/NetworkModels.kt`, `di/NetworkModule.kt`
**core:model** — new `PinCode.kt`
**core:data** — new `repository/PinCodeRepository.kt` + `PinCodeRepositoryImpl.kt`, `di/DataModule.kt`
**feature:sharing** (new lib) — `SharingUiState.kt`, `SharingViewModel.kt`, `SharingScreen.kt` + tests
**app** — `build.gradle.kts`, `navigation/OskeysNavHost.kt`
**:intercom** (new application) — full module
**settings.gradle.kts** — add `:feature:sharing`, `:intercom`

---

## Task 1: Backend — PIN documents, endpoints, intercom key

**Files (in `AccessControllerServer`):** `db/Documents.kt`, `db/Mongo.kt`, `api/Dto.kt`, `api/Routing.kt`, `Application.kt`

- [ ] **Step 1: Add `PinCodeDoc` to `db/Documents.kt`** (append):

```kotlin
@Serializable
data class PinCodeDoc(
    @SerialName("_id") val pin: String,
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

- [ ] **Step 2: Add the collection in `db/Mongo.kt`** — inside `class MongoStorage`, next to the others:

```kotlin
    val pinCodes = db.getCollection<PinCodeDoc>("pin_codes")
```

- [ ] **Step 3: Add DTOs to `api/Dto.kt`** (append):

```kotlin
@Serializable
data class CreatePinRequest(val doorId: String)

@Serializable
data class PinCodeDto(val pin: String, val doorName: String, val expiresAtEpochMs: Long)

@Serializable
data class PinCodesResponse(val codes: List<PinCodeDto>)

@Serializable
data class IntercomValidateRequest(val pin: String)

@Serializable
data class IntercomValidateResponse(
    val allowed: Boolean,
    val doorName: String? = null,
    val doorBleLocalName: String? = null,
    val reason: String? = null,
)
```

- [ ] **Step 4: Add endpoints in `api/Routing.kt`.** Change the entrypoint to take the intercom key and register the intercom route outside the JWT block:

```kotlin
fun Route.apiRoutes(storage: MongoStorage, jwt: JwtService, intercomKey: String) {
    authRoutes(storage, jwt)
    authenticate("auth-jwt") {
        meRoutes(storage)
    }
    intercomRoutes(storage, intercomKey)
}
```

Inside `meRoutes`, add the two resident endpoints:

```kotlin
    // POST /me/pin-codes — issue a single-use numeric PIN for one of the user's doors.
    post("/me/pin-codes") {
        val userId = call.userId()
        val doorId = call.receive<CreatePinRequest>().doorId
        val user = storage.users.find(Filters.eq("_id", userId)).firstOrNull()
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Utilisateur introuvable"))
            return@post
        }
        val buildings = storage.buildings.find(Filters.`in`("_id", user.buildingIds)).toList()
        val match = buildings.firstNotNullOfOrNull { b -> b.doors.find { it.id == doorId }?.let { b to it } }
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Porte introuvable"))
            return@post
        }
        val (building, door) = match
        val pin = generateUniquePin(storage)
        val now = System.currentTimeMillis()
        val expiresAt = now + 15 * 60 * 1000L
        storage.pinCodes.insertOne(
            PinCodeDoc(
                pin = pin,
                issuedByUserId = userId,
                buildingId = building.id,
                doorId = door.id,
                doorName = door.name,
                doorBleLocalName = door.bleLocalName,
                createdAtEpochMs = now,
                expiresAtEpochMs = expiresAt,
            ),
        )
        call.respond(PinCodeDto(pin = pin, doorName = door.name, expiresAtEpochMs = expiresAt))
    }

    // GET /me/pin-codes — the caller's still-valid PINs.
    get("/me/pin-codes") {
        val userId = call.userId()
        val now = System.currentTimeMillis()
        val codes = storage.pinCodes.find(
            Filters.and(
                Filters.eq("issuedByUserId", userId),
                Filters.eq("redeemedAtEpochMs", null),
                Filters.gt("expiresAtEpochMs", now),
            ),
        ).toList()
        call.respond(PinCodesResponse(codes.map { PinCodeDto(it.pin, it.doorName, it.expiresAtEpochMs) }))
    }
```

Add these top-level helpers at the bottom of `Routing.kt`:

```kotlin
private suspend fun generateUniquePin(storage: MongoStorage): String {
    repeat(10) {
        val pin = (100000..999999).random().toString()
        if (storage.pinCodes.find(Filters.eq("_id", pin)).firstOrNull() == null) return pin
    }
    error("Could not allocate a unique PIN")
}

private fun Route.intercomRoutes(storage: MongoStorage, intercomKey: String) {
    // POST /intercom/validate — the intercom device checks a PIN and consumes it (single-use).
    post("/intercom/validate") {
        if (call.request.headers["X-Intercom-Key"] != intercomKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
            return@post
        }
        val pin = call.receive<IntercomValidateRequest>().pin.trim()
        val now = System.currentTimeMillis()
        val doc = storage.pinCodes.find(Filters.eq("_id", pin)).firstOrNull()
        when {
            doc == null -> call.respond(IntercomValidateResponse(allowed = false, reason = "Code inconnu"))
            doc.redeemedAtEpochMs != null -> call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
            doc.expiresAtEpochMs <= now -> call.respond(IntercomValidateResponse(allowed = false, reason = "Code expiré"))
            else -> {
                val claimed = storage.pinCodes.updateOne(
                    Filters.and(Filters.eq("_id", pin), Filters.eq("redeemedAtEpochMs", null)),
                    Updates.set("redeemedAtEpochMs", now),
                )
                if (claimed.modifiedCount == 0L) {
                    call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
                } else {
                    call.respond(
                        IntercomValidateResponse(
                            allowed = true,
                            doorName = doc.doorName,
                            doorBleLocalName = doc.doorBleLocalName,
                        ),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Pass the intercom key in `Application.kt`** — change the `apiRoutes` call:

```kotlin
        apiRoutes(storage, jwt, System.getenv("INTERCOM_KEY") ?: "oskeys-demo-intercom-key")
```

- [ ] **Step 6: Run the server and verify with curl.**

Start the server (existing run config). Then:

```bash
# Log in to get a token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"rodolphe@example.com","password":"password"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
# Find a doorId
curl -s http://localhost:8080/me/doors -H "Authorization: Bearer $TOKEN"
# Create a PIN for the entrance door (doorId "door-hall")
PIN=$(curl -s -X POST http://localhost:8080/me/pin-codes -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"doorId":"door-hall"}' | sed -E 's/.*"pin":"([0-9]+)".*/\1/')
echo "PIN=$PIN"
# Validate at the intercom (should be allowed, returns OSKEY-HALL-01)
curl -s -X POST http://localhost:8080/intercom/validate -H 'X-Intercom-Key: oskeys-demo-intercom-key' \
  -H 'Content-Type: application/json' -d "{\"pin\":\"$PIN\"}"
# Validate again (should be allowed:false "Code déjà utilisé")
curl -s -X POST http://localhost:8080/intercom/validate -H 'X-Intercom-Key: oskeys-demo-intercom-key' \
  -H 'Content-Type: application/json' -d "{\"pin\":\"$PIN\"}"
```

Expected: first validate → `{"allowed":true,"doorName":"Porte d'entrée","doorBleLocalName":"OSKEY-HALL-01"}`; second → `{"allowed":false,..."Code déjà utilisé"}`.

- [ ] **Step 7: Checkpoint** — backend ready to commit (server repo).

---

## Task 2: core:network — PIN + intercom API

**Files:** `core/network/.../model/NetworkModels.kt`, `.../OskeysApiService.kt`, new `.../IntercomApiService.kt`, `.../di/NetworkModule.kt`

- [ ] **Step 1: Add wire DTOs to `NetworkModels.kt`** (append):

```kotlin
@Serializable
data class CreatePinRequestNetwork(val doorId: String)

@Serializable
data class PinCodeNetwork(val pin: String, val doorName: String, val expiresAtEpochMs: Long)

@Serializable
data class PinCodesResponseNetwork(val codes: List<PinCodeNetwork>)

@Serializable
data class IntercomValidateRequestNetwork(val pin: String)

@Serializable
data class IntercomValidateResponseNetwork(
    val allowed: Boolean,
    val doorName: String? = null,
    val doorBleLocalName: String? = null,
    val reason: String? = null,
)
```

- [ ] **Step 2: Add the resident calls to `OskeysApiService.kt`** (inside the interface, with the needed imports for the new types):

```kotlin
    @POST("me/pin-codes")
    suspend fun createPinCode(
        @Header("Authorization") bearer: String,
        @Body body: CreatePinRequestNetwork,
    ): PinCodeNetwork

    @GET("me/pin-codes")
    suspend fun getPinCodes(@Header("Authorization") bearer: String): PinCodesResponseNetwork
```

- [ ] **Step 3: Create `IntercomApiService.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.network

import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateResponseNetwork
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** The intercom device's single call: check a PIN and (on success) get the door to open. */
interface IntercomApiService {

    @POST("intercom/validate")
    suspend fun validate(
        @Header("X-Intercom-Key") key: String,
        @Body body: IntercomValidateRequestNetwork,
    ): IntercomValidateResponseNetwork
}
```

- [ ] **Step 4: Provide `IntercomApiService` in `di/NetworkModule.kt`** — add:

```kotlin
    @Provides
    @Singleton
    fun provideIntercomApiService(retrofit: Retrofit): IntercomApiService =
        retrofit.create(IntercomApiService::class.java)
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Checkpoint.**

---

## Task 3: core:model + core:data — PinCode + repository

**Files:** new `core/model/.../PinCode.kt`, new `core/data/.../repository/PinCodeRepository.kt` + `PinCodeRepositoryImpl.kt`, `core/data/.../di/DataModule.kt`

- [ ] **Step 1: Create `core/model/.../core/model/PinCode.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.model

/** A single-use access PIN issued by a resident for a door. */
data class PinCode(
    val pin: String,
    val doorName: String,
    val expiresAtEpochMs: Long,
)
```

- [ ] **Step 2: Create `core/data/.../repository/PinCodeRepository.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.model.PinCode
import kotlinx.coroutines.flow.Flow

interface PinCodeRepository {
    /** The resident's still-valid PINs (refreshed by [refreshPins], updated by [createPin]). */
    val activePins: Flow<List<PinCode>>

    suspend fun refreshPins(): Result<Unit>

    suspend fun createPin(doorId: String): Result<PinCode>
}
```

- [ ] **Step 3: Create `core/data/.../repository/PinCodeRepositoryImpl.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.datastore.SessionDataSource
import dev.rodolphe.oskeysdemo.core.model.PinCode
import dev.rodolphe.oskeysdemo.core.network.OskeysApiService
import dev.rodolphe.oskeysdemo.core.network.model.CreatePinRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.PinCodeNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class PinCodeRepositoryImpl @Inject constructor(
    private val api: OskeysApiService,
    private val sessionDataSource: SessionDataSource,
) : PinCodeRepository {

    private val _activePins = MutableStateFlow<List<PinCode>>(emptyList())
    override val activePins: Flow<List<PinCode>> = _activePins.asStateFlow()

    override suspend fun refreshPins(): Result<Unit> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = api.getPinCodes(bearer(jwt))
            _activePins.value = response.codes.map { it.asExternalModel() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createPin(doorId: String): Result<PinCode> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val pin = api.createPinCode(bearer(jwt), CreatePinRequestNetwork(doorId)).asExternalModel()
            _activePins.update { (it + pin).sortedBy { p -> p.expiresAtEpochMs } }
            Result.success(pin)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun currentJwt(): String? =
        sessionDataSource.session.first().jwt.ifEmpty { null }

    private fun bearer(jwt: String): String = "Bearer $jwt"
}

private fun PinCodeNetwork.asExternalModel() =
    PinCode(pin = pin, doorName = doorName, expiresAtEpochMs = expiresAtEpochMs)
```

- [ ] **Step 4: Bind it in `di/DataModule.kt`** — add inside the `DataModule` abstract class:

```kotlin
    @Binds
    @Singleton
    abstract fun bindPinCodeRepository(impl: PinCodeRepositoryImpl): PinCodeRepository
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :core:data:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Checkpoint.**

---

## Task 4: feature:sharing — Invitations tab (TDD ViewModel + screen)

**Files:** `settings.gradle.kts`, new `feature/sharing/build.gradle.kts`, `SharingUiState.kt`, `SharingViewModel.kt`, `SharingScreen.kt`, and tests `FakePinCodeRepository.kt`, `FakeDoorsRepository.kt`, `MainDispatcherRule.kt`, `SharingViewModelTest.kt`.

- [ ] **Step 1: Register the module** — in `settings.gradle.kts` after `include(":feature:home")`:

```kotlin
include(":feature:sharing")
```

- [ ] **Step 2: Create `feature/sharing/build.gradle.kts`** (identical to feature:home's, different namespace):

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.feature.sharing"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.model)

    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Create `SharingUiState.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.PinCode

data class SharingUiState(
    val doors: List<Door> = emptyList(),
    val selectedDoorId: DoorId? = null,
    val activePins: List<PinCode> = emptyList(),
    val generatedPin: PinCode? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
) {
    val canGenerate: Boolean get() = selectedDoorId != null && !isGenerating
}
```

- [ ] **Step 4: Create the test doubles.**

`FakePinCodeRepository.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.data.repository.PinCodeRepository
import dev.rodolphe.oskeysdemo.core.model.PinCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePinCodeRepository : PinCodeRepository {
    val pinsFlow = MutableStateFlow<List<PinCode>>(emptyList())
    override val activePins: Flow<List<PinCode>> = pinsFlow

    var refreshCalls = 0
    override suspend fun refreshPins(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }

    // The next createPin returns this; a success is also appended to pinsFlow.
    var createResult: Result<PinCode> = Result.success(PinCode("000000", "Porte", 0L))
    override suspend fun createPin(doorId: String): Result<PinCode> {
        createResult.getOrNull()?.let { pinsFlow.value = pinsFlow.value + it }
        return createResult
    }
}
```

`FakeDoorsRepository.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.data.repository.ActivationResult
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.model.Door
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDoorsRepository : DoorsRepository {
    val doorsFlow = MutableStateFlow<List<Door>>(emptyList())
    override val doors: Flow<List<Door>> = doorsFlow
    override suspend fun refreshDoors(): Result<Unit> = Result.success(Unit)
    override suspend fun activate(code: String): ActivationResult = ActivationResult.Success
}
```

`MainDispatcherRule.kt` (identical to feature:home's, in this package):

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 5: Write the failing tests `SharingViewModelTest.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.PinCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val door = Door(DoorId("door-hall"), "Porte d'entrée", "Résidence Montmartre", "OSKEY-HALL-01")

    private fun vm(
        doors: FakeDoorsRepository = FakeDoorsRepository(),
        pins: FakePinCodeRepository = FakePinCodeRepository(),
    ) = SharingViewModel(doors, pins)

    @Test
    fun `first door is selected by default`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val viewModel = vm(doors = doors)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(door.id, viewModel.uiState.value.selectedDoorId)
    }

    @Test
    fun `init refreshes pins`() = runTest {
        val pins = FakePinCodeRepository()
        vm(pins = pins)
        runCurrent()
        assertEquals(1, pins.refreshCalls)
    }

    @Test
    fun `generate publishes the pin and clears error`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val pins = FakePinCodeRepository().apply {
            createResult = Result.success(PinCode("483920", "Porte d'entrée", 999L))
        }
        val viewModel = vm(doors = doors, pins = pins)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        viewModel.generate()
        runCurrent()

        assertEquals("483920", viewModel.uiState.value.generatedPin?.pin)
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.activePins.size)
    }

    @Test
    fun `generate failure sets an error message`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val pins = FakePinCodeRepository().apply { createResult = Result.failure(RuntimeException("boom")) }
        val viewModel = vm(doors = doors, pins = pins)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        viewModel.generate()
        runCurrent()

        assertEquals("Impossible de générer le code. Réessayez.", viewModel.uiState.value.error)
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `./gradlew :feature:sharing:testDebugUnitTest`
Expected: FAIL — `SharingViewModel` unresolved.

- [ ] **Step 7: Implement `SharingViewModel.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.data.repository.PinCodeRepository
import dev.rodolphe.oskeysdemo.core.model.DoorId
import dev.rodolphe.oskeysdemo.core.model.PinCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharingViewModel @Inject constructor(
    doorsRepository: DoorsRepository,
    private val pinCodeRepository: PinCodeRepository,
) : ViewModel() {

    private data class Local(
        val selectedDoorId: DoorId? = null,
        val generatedPin: PinCode? = null,
        val isGenerating: Boolean = false,
        val error: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<SharingUiState> =
        combine(doorsRepository.doors, pinCodeRepository.activePins, local) { doors, pins, l ->
            SharingUiState(
                doors = doors,
                selectedDoorId = l.selectedDoorId ?: doors.firstOrNull()?.id,
                activePins = pins,
                generatedPin = l.generatedPin,
                isGenerating = l.isGenerating,
                error = l.error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SharingUiState(),
        )

    init {
        viewModelScope.launch { pinCodeRepository.refreshPins() }
    }

    fun selectDoor(doorId: DoorId) = local.update { it.copy(selectedDoorId = doorId) }

    fun generate() {
        val doorId = uiState.value.selectedDoorId ?: return
        if (uiState.value.isGenerating) return
        local.update { it.copy(isGenerating = true, error = null, generatedPin = null) }
        viewModelScope.launch {
            pinCodeRepository.createPin(doorId.value).fold(
                onSuccess = { pin -> local.update { it.copy(isGenerating = false, generatedPin = pin) } },
                onFailure = {
                    local.update { it.copy(isGenerating = false, error = "Impossible de générer le code. Réessayez.") }
                },
            )
        }
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :feature:sharing:testDebugUnitTest`
Expected: PASS (4 tests).

- [ ] **Step 9: Create `SharingScreen.kt`** (UI; verified on device later):

```kotlin
package dev.rodolphe.oskeysdemo.feature.sharing

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.oskeysdemo.core.model.PinCode

@Composable
fun SharingRoute(viewModel: SharingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SharingScreen(uiState = uiState, onSelectDoor = viewModel::selectDoor, onGenerate = viewModel::generate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    uiState: SharingUiState,
    onSelectDoor: (dev.rodolphe.oskeysdemo.core.model.DoorId) -> Unit,
    onGenerate: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Mes invitations") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Générer un code rapide", style = MaterialTheme.typography.titleMedium)
            Text(
                "Un code à usage unique pour vos invités ou livreurs. Il expire à l'utilisation ou au bout de 15 min.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            Button(onClick = onGenerate, enabled = uiState.canGenerate, modifier = Modifier.fillMaxWidth()) {
                if (uiState.isGenerating) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                } else {
                    Text("Générer un code rapide")
                }
            }
            if (uiState.error != null) {
                Text(
                    uiState.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            uiState.generatedPin?.let { pin ->
                Spacer(Modifier.height(16.dp))
                GeneratedPinCard(pin)
            }

            Spacer(Modifier.height(24.dp))
            Text("Envoyées", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(uiState.activePins, key = { it.pin }) { pin -> ActivePinRow(pin) }
            }
        }
    }
}

@Composable
private fun GeneratedPinCard(pin: PinCode) {
    val context = LocalContext.current
    val remaining = rememberCountdown(pin.expiresAtEpochMs)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(pin.doorName, style = MaterialTheme.typography.bodyMedium)
            Text(pin.pin, style = MaterialTheme.typography.displaySmall)
            Text("Expire dans $remaining", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val message = "Votre code d'accès Oskeys pour ${pin.doorName} : ${pin.pin} (valable 15 min, usage unique)."
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    context.startActivity(Intent.createChooser(send, "Partager le code"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Partager") }
        }
    }
}

@Composable
private fun ActivePinRow(pin: PinCode) {
    val remaining = rememberCountdown(pin.expiresAtEpochMs)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${pin.pin} · ${pin.doorName}", style = MaterialTheme.typography.bodyLarge)
            Text("Expire dans $remaining", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Recomputes a mm:ss string once per second from an absolute expiry. */
@Composable
private fun rememberCountdown(expiresAtEpochMs: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val secs = ((expiresAtEpochMs - now) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}
```

- [ ] **Step 10: Verify it compiles**

Run: `./gradlew :feature:sharing:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Checkpoint.**

---

## Task 5: Wire feature:sharing into the resident app

**Files:** `app/build.gradle.kts`, `app/.../navigation/OskeysNavHost.kt`

- [ ] **Step 1: Add the dependency** — in `app/build.gradle.kts` after `implementation(projects.feature.home)`:

```kotlin
    implementation(projects.feature.sharing)
```

- [ ] **Step 2: Swap the Invitations placeholder** — in `OskeysNavHost.kt` add the import:

```kotlin
import dev.rodolphe.oskeysdemo.feature.sharing.SharingRoute
```

Replace:

```kotlin
            composable<TopLevelDestination.Invitations> { PlaceholderScreen(R.string.placeholder_invitations) }
```

with:

```kotlin
            composable<TopLevelDestination.Invitations> { SharingRoute() }
```

- [ ] **Step 3: Build and install**

Run: `./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL`, `Installed on 1 device`.

- [ ] **Step 4: Checkpoint.**

---

## Task 6: `:intercom` application module — skeleton

**Files:** `settings.gradle.kts`, new `intercom/build.gradle.kts`, `intercom/src/main/AndroidManifest.xml`, `res/xml/network_security_config.xml`, `res/values/strings.xml`, `IntercomApplication.kt`, `MainActivity.kt`

- [ ] **Step 1: Register the module** — in `settings.gradle.kts` (end):

```kotlin
include(":intercom")
```

- [ ] **Step 2: Create `intercom/build.gradle.kts`**:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.intercom"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.rodolphe.oskeysdemo.intercom"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "INTERCOM_KEY", "\"oskeys-demo-intercom-key\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "INTERCOM_KEY", "\"oskeys-demo-intercom-key\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ble)
    implementation(projects.core.network)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Create `intercom/src/main/res/xml/network_security_config.xml`** (same LAN host as the resident app):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.1.104</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 4: Create `intercom/src/main/res/values/strings.xml`**:

```xml
<resources>
    <string name="app_name">Oskeys Interphone</string>
</resources>
```

- [ ] **Step 5: Create `intercom/src/main/AndroidManifest.xml`** (BLE permissions come from core:ble via merge):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".IntercomApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Oskeys">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.Oskeys">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Note: `@style/Theme.Oskeys` and the launcher icons resolve from `core:designsystem`/defaults; if the
build reports the theme unresolved, add a minimal `res/values/themes.xml` with
`<style name="Theme.Oskeys" parent="android:Theme.Material.Light.NoActionBar" />` in the intercom module.

- [ ] **Step 6: Create `IntercomApplication.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IntercomApplication : Application()
```

- [ ] **Step 7: Create a minimal `MainActivity.kt`** (fleshed out in Task 7):

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.rodolphe.oskeysdemo.core.designsystem.theme.OskeysTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OskeysTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IntercomRoute()
                }
            }
        }
    }
}
```

- [ ] **Step 8: Add a temporary `IntercomRoute` stub** so the module compiles (replaced in Task 7). Create `IntercomScreen.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun IntercomRoute() {
    Text("Interphone")
}
```

- [ ] **Step 9: Build and install**

Run: `./gradlew :intercom:installDebug`
Expected: `BUILD SUCCESSFUL`, `Installed on 1 device`.

- [ ] **Step 10: Checkpoint.**

---

## Task 7: Intercom keypad + validate + BLE open (TDD ViewModel + screen)

**Files:** `intercom/.../IntercomUiState.kt`, `IntercomViewModel.kt`, replace `IntercomScreen.kt`; tests `FakeIntercomApiService.kt`, `FakeOskeysBleController.kt`, `MainDispatcherRule.kt`, `IntercomViewModelTest.kt`.

- [ ] **Step 1: Create `IntercomUiState.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

data class IntercomUiState(
    val entered: String = "",
    val status: IntercomStatus = IntercomStatus.Idle,
) {
    val canValidate: Boolean get() = entered.length == PIN_LENGTH && status != IntercomStatus.Checking
    companion object { const val PIN_LENGTH = 6 }
}

sealed interface IntercomStatus {
    data object Idle : IntercomStatus
    data object Checking : IntercomStatus
    data object Opening : IntercomStatus
    data object Granted : IntercomStatus
    data class Denied(val reason: String) : IntercomStatus
    data class Error(val message: String) : IntercomStatus
}
```

- [ ] **Step 2: Create the test doubles.**

`FakeIntercomApiService.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import dev.rodolphe.oskeysdemo.core.network.IntercomApiService
import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateResponseNetwork

class FakeIntercomApiService : IntercomApiService {
    var response: IntercomValidateResponseNetwork = IntercomValidateResponseNetwork(allowed = false)
    var throwable: Throwable? = null
    var lastPin: String? = null

    override suspend fun validate(key: String, body: IntercomValidateRequestNetwork): IntercomValidateResponseNetwork {
        lastPin = body.pin
        throwable?.let { throw it }
        return response
    }
}
```

`FakeOskeysBleController.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.ble.OskeysBleController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeOskeysBleController : OskeysBleController {
    var states: List<DoorOpenState> = listOf(DoorOpenState.Scanning, DoorOpenState.Opened)
    val openedLocalNames = mutableListOf<String>()
    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        openedLocalNames += bleLocalName
        return states.asFlow()
    }
}
```

`MainDispatcherRule.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 3: Write the failing tests `IntercomViewModelTest.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateResponseNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntercomViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun vm(api: FakeIntercomApiService, ble: FakeOskeysBleController) = IntercomViewModel(api, ble)

    private fun enterPin(viewModel: IntercomViewModel, pin: String) = pin.forEach { viewModel.onDigit(it.toString()) }

    @Test
    fun `digits accumulate up to six`() {
        val viewModel = vm(FakeIntercomApiService(), FakeOskeysBleController())
        enterPin(viewModel, "1234567")
        assertEquals("123456", viewModel.uiState.value.entered)
    }

    @Test
    fun `allowed pin opens the returned door and ends Granted`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = true, doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")
        }
        val ble = FakeOskeysBleController()
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "483920")

        viewModel.validate()
        runCurrent()

        assertEquals("483920", api.lastPin)
        assertEquals(listOf("OSKEY-HALL-01"), ble.openedLocalNames)
        assertEquals(IntercomStatus.Granted, viewModel.uiState.value.status)
    }

    @Test
    fun `refused pin shows Denied and does not open BLE`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = false, reason = "Code déjà utilisé")
        }
        val ble = FakeOskeysBleController()
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "111111")

        viewModel.validate()
        runCurrent()

        assertEquals(IntercomStatus.Denied("Code déjà utilisé"), viewModel.uiState.value.status)
        assertTrue(ble.openedLocalNames.isEmpty())
    }

    @Test
    fun `network error shows Error`() = runTest {
        val api = FakeIntercomApiService().apply { throwable = RuntimeException("offline") }
        val viewModel = vm(api, FakeOskeysBleController())
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "222222")

        viewModel.validate()
        runCurrent()

        assertTrue(viewModel.uiState.value.status is IntercomStatus.Error)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :intercom:testDebugUnitTest`
Expected: FAIL — `IntercomViewModel` unresolved.

- [ ] **Step 5: Implement `IntercomViewModel.kt`**:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.ble.OskeysBleController
import dev.rodolphe.oskeysdemo.core.network.IntercomApiService
import dev.rodolphe.oskeysdemo.core.network.model.IntercomValidateRequestNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IntercomViewModel @Inject constructor(
    private val api: IntercomApiService,
    private val bleController: OskeysBleController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntercomUiState())
    val uiState: StateFlow<IntercomUiState> = _uiState.asStateFlow()

    fun onDigit(digit: String) = _uiState.update {
        if (it.entered.length >= IntercomUiState.PIN_LENGTH) it
        else it.copy(entered = it.entered + digit, status = IntercomStatus.Idle)
    }

    fun onClear() = _uiState.update { it.copy(entered = "", status = IntercomStatus.Idle) }

    fun validate() {
        val state = _uiState.value
        if (!state.canValidate) return
        val pin = state.entered
        _uiState.update { it.copy(status = IntercomStatus.Checking) }
        viewModelScope.launch {
            val response = try {
                api.validate(BuildConfig.INTERCOM_KEY, IntercomValidateRequestNetwork(pin))
            } catch (e: Exception) {
                _uiState.update { it.copy(status = IntercomStatus.Error("Interphone hors ligne"), entered = "") }
                return@launch
            }
            if (!response.allowed || response.doorBleLocalName == null) {
                _uiState.update { it.copy(status = IntercomStatus.Denied(response.reason ?: "Code refusé"), entered = "") }
                return@launch
            }
            _uiState.update { it.copy(status = IntercomStatus.Opening) }
            bleController.open(response.doorBleLocalName!!).collect { openState ->
                val status = when (openState) {
                    DoorOpenState.Opened -> IntercomStatus.Granted
                    is DoorOpenState.Error -> IntercomStatus.Error("Ouverture impossible")
                    else -> IntercomStatus.Opening
                }
                _uiState.update { it.copy(status = status) }
            }
            _uiState.update { it.copy(entered = "") }
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :intercom:testDebugUnitTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Replace `IntercomScreen.kt` with the keypad UI**:

```kotlin
package dev.rodolphe.oskeysdemo.intercom

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IntercomRoute(viewModel: IntercomViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    IntercomScreen(
        entered = uiState.entered,
        status = uiState.status,
        onDigit = viewModel::onDigit,
        onClear = viewModel::onClear,
        onValidate = {
            permissionLauncher.launch(permissions)
            viewModel.validate()
        },
    )
}

@Composable
fun IntercomScreen(
    entered: String,
    status: IntercomStatus,
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onValidate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Résidence Montmartre", style = MaterialTheme.typography.titleLarge)
        Text("Entrez votre code d'accès", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        Text(
            text = "•".repeat(entered.length).padEnd(IntercomUiState.PIN_LENGTH, '◦'),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(status.message(), color = status.color(), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "OK"))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Box(Modifier.weight(1f).aspectRatio(1.6f).padding(4.dp)) {
                        when (key) {
                            "C" -> OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxSize()) { Text("C") }
                            "OK" -> Button(onClick = onValidate, modifier = Modifier.fillMaxSize()) { Text("OK") }
                            else -> Button(onClick = { onDigit(key) }, modifier = Modifier.fillMaxSize()) {
                                Text(key, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntercomStatus.message(): String = when (this) {
    IntercomStatus.Idle -> " "
    IntercomStatus.Checking -> "Vérification…"
    IntercomStatus.Opening -> "Ouverture…"
    IntercomStatus.Granted -> "Accès autorisé, porte ouverte ✓"
    is IntercomStatus.Denied -> reason
    is IntercomStatus.Error -> message
}

@Composable
private fun IntercomStatus.color() = when (this) {
    IntercomStatus.Granted -> MaterialTheme.colorScheme.primary
    is IntercomStatus.Denied, is IntercomStatus.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
```

- [ ] **Step 8: Build and install**

Run: `./gradlew :intercom:installDebug`
Expected: `BUILD SUCCESSFUL`, `Installed on 1 device`.

- [ ] **Step 9: Checkpoint.**

---

## Task 8: End-to-end verification (manual)

- [ ] **Step 1:** Start the backend (with `INTERCOM_KEY` unset → default `oskeys-demo-intercom-key`, matching the intercom `BuildConfig`). Ensure the ESP32 is powered and advertising `OSKEY-HALL-01`, and both Android devices are on the LAN Wi-Fi.

- [ ] **Step 2:** Resident app → **Invitations** tab → select "Porte d'entrée" → **Générer un code rapide**. Note the 6-digit PIN and confirm the countdown ticks. Optionally tap **Partager**.

- [ ] **Step 3:** Intercom app (2nd device) → type the PIN → **OK**. Grant the Bluetooth permission on first use. Expect "Vérification…" → "Ouverture…" → **"Accès autorisé, porte ouverte ✓"**, and the ESP32 LED pulses ~3 s.

- [ ] **Step 4:** Re-enter the same PIN on the intercom → expect **"Code déjà utilisé"** and no LED (single-use enforced).

- [ ] **Step 5:** Wrong/expired PIN → "Code inconnu"/"Code expiré". Backend stopped → "Interphone hors ligne".

- [ ] **Step 6: Checkpoint** — iteration 2 (one-time PIN) done; ready to commit.

---

## Self-review notes

- **Spec coverage:** PIN doc + endpoints (T1), network layer (T2), model+repo (T3), resident generate UI (T4) wired (T5), intercom app skeleton (T6) + keypad/validate/BLE-open (T7), manual E2E incl. single-use + error paths (T8). All spec sections mapped.
- **Type consistency:** `IntercomValidateResponse(allowed, doorName, doorBleLocalName, reason)` matches across backend, `core:network`, fakes, and the ViewModel. `PinCode(pin, doorName, expiresAtEpochMs)` and `PinCodeRepository(activePins, refreshPins, createPin)` used identically in repo, fakes, and `SharingViewModel`. `X-Intercom-Key` header value (`oskeys-demo-intercom-key`) matches backend default and intercom `BuildConfig.INTERCOM_KEY`.
- **Deferred:** personalized invitations, permanent access, challenge-response BLE — no task, per spec.
```
