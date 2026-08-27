# Resident Home + Activation + BLE Door Open — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Git note:** the repo owner commits/pushes themselves. Do NOT run `git commit`/`git push`. Where a task ends with a "Checkpoint", stop and tell the owner it's ready to commit.

**Goal:** Ship the first real end-to-end resident flow — log in, activate a building by code, see its doors, and open a door over BLE against an ESP32.

**Architecture:** Two new modules. `core:ble` hides Android BLE behind an `OskeysBleController` interface returning a `Flow<DoorOpenState>`. `feature:home` has a `HomeViewModel` that observes the existing `DoorsRepository`, redeems activation codes, and delegates opening to the controller; `HomeScreen` renders door cards + an activation bottom sheet. The app wires `feature:home` into the Accueil tab. An ESP32 sketch implements the matching GATT contract.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, Android BLE (`BluetoothLeScanner`/`BluetoothGatt`), JUnit4 + kotlinx-coroutines-test. Firmware: Arduino ESP32 (NimBLE-Arduino).

**Spec:** `docs/superpowers/specs/2026-07-18-resident-home-ble-design.md`

---

## File structure

**New module `core:ble`** (namespace `dev.rodolphe.oskeysdemo.core.ble`)
- `core/ble/build.gradle.kts` — android library + hilt + ksp
- `core/ble/src/main/AndroidManifest.xml` — BLE permissions
- `.../core/ble/BleContract.kt` — service/characteristic UUIDs + open command bytes
- `.../core/ble/DoorOpenState.kt` — `DoorOpenState` sealed interface + `DoorOpenError` enum
- `.../core/ble/OskeysBleController.kt` — the interface
- `.../core/ble/AndroidOskeysBleController.kt` — Android implementation
- `.../core/ble/di/BleModule.kt` — Hilt `@Binds`

**New module `feature:home`** (namespace `dev.rodolphe.oskeysdemo.feature.home`)
- `feature/home/build.gradle.kts` — android library + compose + hilt + ksp + test deps
- `.../feature/home/HomeUiState.kt` — `HomeUiState`, `ActivationUiState`
- `.../feature/home/HomeViewModel.kt`
- `.../feature/home/HomeScreen.kt` — `HomeRoute`, `HomeScreen`, door card, activation sheet
- `feature/home/src/test/kotlin/.../feature/home/FakeDoorsRepository.kt`
- `feature/home/src/test/kotlin/.../feature/home/FakeOskeysBleController.kt`
- `feature/home/src/test/kotlin/.../feature/home/MainDispatcherRule.kt`
- `feature/home/src/test/kotlin/.../feature/home/HomeViewModelTest.kt`

**Modified**
- `settings.gradle.kts` — add `:core:ble`, `:feature:home`
- `app/build.gradle.kts` — add `implementation(projects.feature.home)`
- `app/.../navigation/OskeysNavHost.kt` — Accueil tab → `HomeRoute()`

**New (firmware, not built by Gradle)**
- `hardware/esp32-door/esp32-door.ino`
- `hardware/esp32-door/README.md`

**Backend (separate repo `C:/Users/rodol/IdeaProjects/AccessControllerServer`)**
- Verify/add idempotent seed data (a building + door + activation code).

---

## Task 1: `core:ble` module skeleton — contract, states, interface

**Files:**
- Create: `core/ble/build.gradle.kts`
- Create: `core/ble/src/main/AndroidManifest.xml`
- Create: `core/ble/src/main/kotlin/dev/rodolphe/oskeysdemo/core/ble/BleContract.kt`
- Create: `core/ble/src/main/kotlin/dev/rodolphe/oskeysdemo/core/ble/DoorOpenState.kt`
- Create: `core/ble/src/main/kotlin/dev/rodolphe/oskeysdemo/core/ble/OskeysBleController.kt`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Register the module in `settings.gradle.kts`**

Add after the existing `include(":core:data")` line:

```kotlin
include(":core:ble")
```

- [ ] **Step 2: Create `core/ble/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.core.ble"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

- [ ] **Step 3: Create `core/ble/src/main/AndroidManifest.xml` with BLE permissions**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Android 12+ (API 31): granular BLE permissions. neverForLocation lets us skip the location
         permission on 31+ since we don't derive location from BLE scans. -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <!-- Below API 31: legacy Bluetooth + fine location (required for BLE scan results pre-12). -->
    <uses-permission
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />
</manifest>
```

- [ ] **Step 4: Create `BleContract.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.core.ble

import java.util.UUID

/**
 * The GATT contract shared between this app and the ESP32 firmware. The firmware MUST advertise the
 * door's bleLocalName, expose [SERVICE_UUID], and accept a write of [OPEN_COMMAND] on
 * [COMMAND_CHARACTERISTIC_UUID] to trigger the relay.
 *
 * Iteration 1 uses a fixed command with no cryptographic auth (see spec: challenge-response is a
 * later iteration).
 */
object BleContract {
    val SERVICE_UUID: UUID = UUID.fromString("0000a100-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb")
    val OPEN_COMMAND: ByteArray = "OPEN".toByteArray(Charsets.US_ASCII)

    const val SCAN_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
}
```

- [ ] **Step 5: Create `DoorOpenState.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.core.ble

/** Progress of a single door-open attempt, emitted in order until a terminal state. */
sealed interface DoorOpenState {
    data object Scanning : DoorOpenState
    data object Connecting : DoorOpenState
    data object Sending : DoorOpenState
    data object Opened : DoorOpenState
    data class Error(val reason: DoorOpenError) : DoorOpenState
}

enum class DoorOpenError {
    BluetoothOff,
    PermissionMissing,
    NotFound,
    ConnectionFailed,
    WriteFailed,
    Timeout,
}
```

- [ ] **Step 6: Create `OskeysBleController.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.core.ble

import kotlinx.coroutines.flow.Flow

interface OskeysBleController {
    /**
     * Scans for a peripheral advertising [bleLocalName], connects, writes the open command, and
     * emits [DoorOpenState] progress. The flow completes after a terminal state
     * ([DoorOpenState.Opened] or [DoorOpenState.Error]). Cancelling the collection aborts the scan
     * and disconnects.
     */
    fun open(bleLocalName: String): Flow<DoorOpenState>
}
```

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew :core:ble:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Checkpoint** — tell the owner `core:ble` skeleton is ready to commit.

---

## Task 2: `AndroidOskeysBleController` implementation + Hilt binding

No unit test (Android BLE framework code); verified manually on device in Task 8.

**Files:**
- Create: `core/ble/src/main/kotlin/dev/rodolphe/oskeysdemo/core/ble/AndroidOskeysBleController.kt`
- Create: `core/ble/src/main/kotlin/dev/rodolphe/oskeysdemo/core/ble/di/BleModule.kt`

- [ ] **Step 1: Create `AndroidOskeysBleController.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Real BLE implementation. Everything runs inside a single [callbackFlow] per open() call: scan ->
 * connect -> discover -> write, translating each Android callback into a [DoorOpenState]. The flow
 * closes itself on the first terminal state; [awaitClose] guarantees the scan is stopped and the
 * GATT is closed even if the collector cancels.
 */
class AndroidOskeysBleController @Inject constructor(
    @ApplicationContext private val context: Context,
) : OskeysBleController {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @SuppressLint("MissingPermission") // permission is checked in hasPermissions() before any call
    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            return flowOf(DoorOpenState.Error(DoorOpenError.BluetoothOff))
        }
        if (!hasScanConnectPermissions()) {
            return flowOf(DoorOpenState.Error(DoorOpenError.PermissionMissing))
        }

        return callbackFlow {
            trySend(DoorOpenState.Scanning)

            var gatt: BluetoothGatt? = null
            val scanner = adapter.bluetoothLeScanner

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        trySend(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                        close()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val characteristic = g.getService(BleContract.SERVICE_UUID)
                        ?.getCharacteristic(BleContract.COMMAND_CHARACTERISTIC_UUID)
                    if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                        trySend(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                        close()
                        return
                    }
                    trySend(DoorOpenState.Sending)
                    writeOpen(g, characteristic)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        trySend(DoorOpenState.Opened)
                    } else {
                        trySend(DoorOpenState.Error(DoorOpenError.WriteFailed))
                    }
                    close()
                }
            }

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device: BluetoothDevice = result.device
                    if (result.scanRecord?.deviceName == bleLocalName || device.name == bleLocalName) {
                        scanner?.stopScan(this)
                        trySend(DoorOpenState.Connecting)
                        gatt = device.connectGatt(context, false, gattCallback)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    trySend(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                    close()
                }
            }

            scanner?.startScan(scanCallback)

            awaitClose {
                runCatching { scanner?.stopScan(scanCallback) }
                runCatching { gatt?.close() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeOpen(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                BleContract.OPEN_COMMAND,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = BleContract.OPEN_COMMAND
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun hasScanConnectPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
```

Note: the `SCAN_TIMEOUT_MS`/`CONNECT_TIMEOUT_MS` timeouts are applied by the caller (`HomeViewModel`
wraps the flow with `kotlinx.coroutines.flow.timeout` equivalent). See Task 3 Step 6.

- [ ] **Step 2: Create `di/BleModule.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.core.ble.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.oskeysdemo.core.ble.AndroidOskeysBleController
import dev.rodolphe.oskeysdemo.core.ble.OskeysBleController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindOskeysBleController(impl: AndroidOskeysBleController): OskeysBleController
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:ble:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Checkpoint** — ready to commit.

---

## Task 3: `feature:home` module + `HomeViewModel` (TDD)

**Files:**
- Create: `feature/home/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `feature/home/src/main/kotlin/dev/rodolphe/oskeysdemo/feature/home/HomeUiState.kt`
- Create: `feature/home/src/main/kotlin/dev/rodolphe/oskeysdemo/feature/home/HomeViewModel.kt`
- Create: `feature/home/src/test/kotlin/dev/rodolphe/oskeysdemo/feature/home/FakeDoorsRepository.kt`
- Create: `feature/home/src/test/kotlin/dev/rodolphe/oskeysdemo/feature/home/FakeOskeysBleController.kt`
- Create: `feature/home/src/test/kotlin/dev/rodolphe/oskeysdemo/feature/home/MainDispatcherRule.kt`
- Create: `feature/home/src/test/kotlin/dev/rodolphe/oskeysdemo/feature/home/HomeViewModelTest.kt`

- [ ] **Step 1: Register module + add coroutines-test to the version catalog usage**

In `settings.gradle.kts` add after `include(":feature:onboarding")`:

```kotlin
include(":feature:home")
```

- [ ] **Step 2: Create `feature/home/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.feature.home"
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
    implementation(projects.core.ble)

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

- [ ] **Step 3: Create `HomeUiState.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId

/** Everything the Home screen renders. [opening] holds the in-flight/last open state per door. */
data class HomeUiState(
    val doors: List<Door> = emptyList(),
    val opening: Map<DoorId, DoorOpenState> = emptyMap(),
    val activation: ActivationUiState = ActivationUiState(),
)

data class ActivationUiState(
    val isSheetOpen: Boolean = false,
    val code: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = code.isNotBlank() && !isSubmitting
}
```

- [ ] **Step 4: Create the test doubles**

`FakeDoorsRepository.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import dev.rodolphe.oskeysdemo.core.data.repository.ActivationResult
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.model.Door
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDoorsRepository : DoorsRepository {
    val doorsFlow = MutableStateFlow<List<Door>>(emptyList())
    override val doors: Flow<List<Door>> = doorsFlow

    var refreshResult: Result<Unit> = Result.success(Unit)
    var refreshCalls = 0
    override suspend fun refreshDoors(): Result<Unit> {
        refreshCalls++
        return refreshResult
    }

    // The next activate() returns this; doorsToEmitOnSuccess is pushed into doorsFlow on Success.
    var activateResult: ActivationResult = ActivationResult.Success
    var doorsToEmitOnSuccess: List<Door> = emptyList()
    override suspend fun activate(code: String): ActivationResult {
        if (activateResult is ActivationResult.Success) {
            doorsFlow.value = doorsToEmitOnSuccess
        }
        return activateResult
    }
}
```

`FakeOskeysBleController.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.ble.OskeysBleController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeOskeysBleController : OskeysBleController {
    // Each open() replays this sequence of states.
    var states: List<DoorOpenState> = listOf(
        DoorOpenState.Scanning,
        DoorOpenState.Connecting,
        DoorOpenState.Sending,
        DoorOpenState.Opened,
    )
    val openedLocalNames = mutableListOf<String>()

    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        openedLocalNames += bleLocalName
        return states.asFlow()
    }
}
```

`MainDispatcherRule.kt`:

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: StandardTestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 5: Write the failing tests (`HomeViewModelTest.kt`)**

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.data.repository.ActivationResult
import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val door = Door(
        id = DoorId("d1"),
        name = "Porte principale",
        buildingName = "Résidence Victor Hugo",
        bleLocalName = "OSKEYS-DOOR-01",
    )

    private fun buildViewModel(
        repo: FakeDoorsRepository = FakeDoorsRepository(),
        ble: FakeOskeysBleController = FakeOskeysBleController(),
    ) = HomeViewModel(repo, ble)

    @Test
    fun `doors from repository appear in uiState`() = runTest {
        val repo = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val vm = buildViewModel(repo = repo)

        backgroundScope.launchCollect(vm)
        runCurrent()

        assertEquals(listOf(door), vm.uiState.value.doors)
    }

    @Test
    fun `init refreshes doors`() = runTest {
        val repo = FakeDoorsRepository()
        buildViewModel(repo = repo)
        runCurrent()

        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `open folds ble states into that door's slot and ends Opened`() = runTest {
        val repo = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val ble = FakeOskeysBleController()
        val vm = buildViewModel(repo = repo, ble = ble)
        backgroundScope.launchCollect(vm)

        vm.open(door)
        runCurrent()

        assertEquals(listOf("OSKEYS-DOOR-01"), ble.openedLocalNames)
        assertEquals(DoorOpenState.Opened, vm.uiState.value.opening[door.id])
    }

    @Test
    fun `open surfaces an error state`() = runTest {
        val repo = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val ble = FakeOskeysBleController().apply {
            states = listOf(DoorOpenState.Scanning, DoorOpenState.Error(dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.NotFound))
        }
        val vm = buildViewModel(repo = repo, ble = ble)
        backgroundScope.launchCollect(vm)

        vm.open(door)
        runCurrent()

        assertTrue(vm.uiState.value.opening[door.id] is DoorOpenState.Error)
    }

    @Test
    fun `successful activation closes the sheet and clears error`() = runTest {
        val repo = FakeDoorsRepository().apply {
            activateResult = ActivationResult.Success
            doorsToEmitOnSuccess = listOf(door)
        }
        val vm = buildViewModel(repo = repo)
        backgroundScope.launchCollect(vm)

        vm.onActivateClicked()
        vm.onActivationCodeChange("ABC123")
        vm.submitActivation()
        runCurrent()

        assertFalse(vm.uiState.value.activation.isSheetOpen)
        assertNull(vm.uiState.value.activation.error)
        assertEquals(listOf(door), vm.uiState.value.doors)
    }

    @Test
    fun `invalid code keeps the sheet open with a message`() = runTest {
        val repo = FakeDoorsRepository().apply { activateResult = ActivationResult.InvalidCode }
        val vm = buildViewModel(repo = repo)
        backgroundScope.launchCollect(vm)

        vm.onActivateClicked()
        vm.onActivationCodeChange("nope")
        vm.submitActivation()
        runCurrent()

        assertTrue(vm.uiState.value.activation.isSheetOpen)
        assertEquals("Code d'activation inconnu", vm.uiState.value.activation.error)
    }
}

// Collects uiState in the background so a WhileSubscribed StateFlow stays active during the test.
private fun kotlinx.coroutines.CoroutineScope.launchCollect(vm: HomeViewModel) {
    kotlinx.coroutines.launch { vm.uiState.collect() }
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: FAIL — `HomeViewModel` and its members don't exist yet (unresolved references).

- [ ] **Step 7: Implement `HomeViewModel.kt` to pass**

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.ble.OskeysBleController
import dev.rodolphe.oskeysdemo.core.data.repository.ActivationResult
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val doorsRepository: DoorsRepository,
    private val bleController: OskeysBleController,
) : ViewModel() {

    private val opening = MutableStateFlow<Map<DoorId, DoorOpenState>>(emptyMap())
    private val activation = MutableStateFlow(ActivationUiState())

    val uiState: StateFlow<HomeUiState> =
        combine(doorsRepository.doors, opening, activation) { doors, opening, activation ->
            HomeUiState(doors = doors, opening = opening, activation = activation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    init {
        // Best-effort sync; Room is the source of truth so a failure just keeps cached doors.
        viewModelScope.launch { doorsRepository.refreshDoors() }
    }

    fun open(door: Door) {
        viewModelScope.launch {
            bleController.open(door.bleLocalName).collect { state ->
                opening.update { it + (door.id to state) }
            }
        }
    }

    fun onActivateClicked() = activation.update { ActivationUiState(isSheetOpen = true) }

    fun dismissActivationSheet() = activation.update { ActivationUiState(isSheetOpen = false) }

    fun onActivationCodeChange(value: String) =
        activation.update { it.copy(code = value, error = null) }

    fun submitActivation() {
        val current = activation.value
        if (!current.canSubmit) return
        activation.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = doorsRepository.activate(current.code.trim())) {
                ActivationResult.Success -> activation.update { ActivationUiState(isSheetOpen = false) }
                else -> activation.update { it.copy(isSubmitting = false, error = result.toMessage()) }
            }
        }
    }
}

private fun ActivationResult.toMessage(): String = when (this) {
    ActivationResult.Success -> ""
    ActivationResult.InvalidCode -> "Code d'activation inconnu"
    ActivationResult.AlreadyUsed -> "Code déjà utilisé"
    ActivationResult.Unauthorized -> "Session expirée, reconnectez-vous"
    ActivationResult.NetworkError -> "Impossible de joindre le serveur. Vérifiez votre connexion."
    ActivationResult.ServerError -> "Une erreur est survenue. Réessayez."
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (6 tests).

- [ ] **Step 9: Checkpoint** — ready to commit.

---

## Task 4: `HomeScreen` UI — door cards, open states, activation sheet, permissions

No unit test (Compose UI validated on device in Task 8).

**Files:**
- Create: `feature/home/src/main/kotlin/dev/rodolphe/oskeysdemo/feature/home/HomeScreen.kt`

- [ ] **Step 1: Create `HomeScreen.kt`**

```kotlin
package dev.rodolphe.oskeysdemo.feature.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rodolphe.oskeysdemo.core.ble.DoorOpenState
import dev.rodolphe.oskeysdemo.core.model.Door

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result handled by the controller's own re-check on the next open() */ }

    HomeScreen(
        uiState = uiState,
        onOpenDoor = { door ->
            permissionLauncher.launch(permissions)
            viewModel.open(door)
        },
        onActivateClicked = viewModel::onActivateClicked,
        onActivationCodeChange = viewModel::onActivationCodeChange,
        onSubmitActivation = viewModel::submitActivation,
        onDismissActivation = viewModel::dismissActivationSheet,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenDoor: (Door) -> Unit,
    onActivateClicked: () -> Unit,
    onActivationCodeChange: (String) -> Unit,
    onSubmitActivation: () -> Unit,
    onDismissActivation: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes portes") },
                actions = {
                    IconButton(onClick = onActivateClicked) {
                        Icon(Icons.Filled.Add, contentDescription = "Activer un immeuble")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.doors.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onActivateClicked = onActivateClicked,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.doors, key = { it.id.value }) { door ->
                    DoorCard(
                        door = door,
                        openState = uiState.opening[door.id],
                        onOpen = { onOpenDoor(door) },
                    )
                }
            }
        }
    }

    if (uiState.activation.isSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissActivation) {
            ActivationSheetContent(
                state = uiState.activation,
                onCodeChange = onActivationCodeChange,
                onSubmit = onSubmitActivation,
            )
        }
    }
}

@Composable
private fun DoorCard(door: Door, openState: DoorOpenState?, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(door.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(
                door.buildingName,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OpenButton(openState = openState, onOpen = onOpen)
            val error = openState as? DoorOpenState.Error
            if (error != null) {
                Text(
                    text = error.reason.toMessage(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OpenButton(openState: DoorOpenState?, onOpen: () -> Unit) {
    when (openState) {
        DoorOpenState.Scanning, DoorOpenState.Connecting, DoorOpenState.Sending -> {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text(openState.label())
            }
        }
        DoorOpenState.Opened -> {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Ouverte")
            }
        }
        is DoorOpenState.Error, null -> {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.LockOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (openState is DoorOpenState.Error) "Réessayer" else "Ouvrir")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onActivateClicked: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Aucune porte pour l'instant",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Saisissez un code d'activation pour ajouter votre immeuble.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onActivateClicked) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Activer un immeuble")
        }
    }
}

@Composable
private fun ActivationSheetContent(
    state: ActivationUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Activer un immeuble", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text("Code d'activation") },
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Text(
                state.error,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Valider")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun DoorOpenState.label(): String = when (this) {
    DoorOpenState.Scanning -> "Recherche…"
    DoorOpenState.Connecting -> "Connexion…"
    DoorOpenState.Sending -> "Ouverture…"
    DoorOpenState.Opened -> "Ouverte"
    is DoorOpenState.Error -> "Erreur"
}

private fun dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.toMessage(): String = when (this) {
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.BluetoothOff -> "Activez le Bluetooth."
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.PermissionMissing -> "Autorisation Bluetooth requise."
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.NotFound -> "Porte introuvable à proximité."
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.ConnectionFailed -> "Échec de connexion à la porte."
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.WriteFailed -> "La commande n'a pas abouti."
    dev.rodolphe.oskeysdemo.core.ble.DoorOpenError.Timeout -> "Délai dépassé. Réessayez."
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:home:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Checkpoint** — ready to commit.

---

## Task 5: Wire `feature:home` into the app

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block)
- Modify: `app/src/main/java/dev/rodolphe/oskeysdemo/navigation/OskeysNavHost.kt`

- [ ] **Step 1: Add the module dependency in `app/build.gradle.kts`**

In the `dependencies { }` block, after `implementation(projects.feature.onboarding)`:

```kotlin
    implementation(projects.feature.home)
```

- [ ] **Step 2: Swap the Accueil placeholder for `HomeRoute` in `OskeysNavHost.kt`**

Add the import next to the other `dev.rodolphe.oskeysdemo` imports:

```kotlin
import dev.rodolphe.oskeysdemo.feature.home.HomeRoute
```

Replace this line:

```kotlin
            composable<TopLevelDestination.Home> { PlaceholderScreen(R.string.placeholder_home) }
```

with:

```kotlin
            composable<TopLevelDestination.Home> { HomeRoute() }
```

- [ ] **Step 3: Build and install the app**

Run: `./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL` and `Installed on 1 device`.

- [ ] **Step 4: Checkpoint** — ready to commit.

---

## Task 6: ESP32 firmware

**Files:**
- Create: `hardware/esp32-door/esp32-door.ino`
- Create: `hardware/esp32-door/README.md`

- [ ] **Step 1: Create `hardware/esp32-door/esp32-door.ino`**

```cpp
// Oskeys demo door — ESP32 BLE peripheral.
// Advertises as OSKEYS-DOOR-01, exposes one writable characteristic; on receiving "OPEN" it pulses
// the relay/LED pin for 3 seconds. UUIDs and command MUST match core:ble/BleContract.kt.
//
// Board: any ESP32 dev board. Library: "NimBLE-Arduino" (install via Library Manager).

#include <NimBLEDevice.h>

static const char* DEVICE_NAME   = "OSKEYS-DOOR-01";
static const char* SERVICE_UUID  = "0000a100-0000-1000-8000-00805f9b34fb";
static const char* COMMAND_UUID  = "0000a101-0000-1000-8000-00805f9b34fb";
static const int   RELAY_PIN     = 2;      // onboard LED on most ESP32 devkits; swap for a relay pin
static const uint32_t PULSE_MS   = 3000;

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    std::string value = c->getValue();
    if (value == "OPEN") {
      digitalWrite(RELAY_PIN, HIGH);
      delay(PULSE_MS);
      digitalWrite(RELAY_PIN, LOW);
    }
  }
};

void setup() {
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEServer* server = NimBLEDevice::createServer();
  NimBLEService* service = server->createService(SERVICE_UUID);
  NimBLECharacteristic* command = service->createCharacteristic(
      COMMAND_UUID, NIMBLE_PROPERTY::WRITE);
  command->setCallbacks(new CommandCallbacks());
  service->start();

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setName(DEVICE_NAME);
  advertising->start();
}

void loop() {
  delay(1000);
}
```

- [ ] **Step 2: Create `hardware/esp32-door/README.md`**

```markdown
# Oskeys demo door — ESP32 firmware

BLE peripheral that plays the role of an Oskeys lock for the demo.

## Requirements
- An ESP32 dev board
- Arduino IDE with the ESP32 board package
- Library: **NimBLE-Arduino** (Library Manager)

## Flash
1. Open `esp32-door.ino`.
2. Select your ESP32 board and port.
3. Upload.

## How it works
- Advertises as `OSKEYS-DOOR-01` — this MUST equal the door's `bleLocalName` in the backend seed data.
- On a write of `OPEN` to characteristic `0000a101-…`, pulses `RELAY_PIN` (GPIO 2 = onboard LED by
  default) for 3 s. Wire a relay to that pin to drive a real strike.
- The UUIDs and command string mirror `core/ble/.../BleContract.kt`. Change them in both places together.
```

- [ ] **Step 3: Checkpoint** — ready to commit. (Flashing is a manual owner step; covered in Task 8.)

---

## Task 7: Backend seed data (repo `AccessControllerServer`)

Ensures the demo has a building with a BLE-named door and a redeemable code. **First read the existing
schema**, then add an idempotent seeder if none exists.

**Files (in `C:/Users/rodol/IdeaProjects/AccessControllerServer`):**
- Read: `src/main/kotlin/dev/rodolphe/accesscontrol/db/Documents.kt`
- Read: `src/main/kotlin/dev/rodolphe/accesscontrol/db/Mongo.kt`
- Read/Modify: `src/main/kotlin/dev/rodolphe/accesscontrol/Application.kt`

- [ ] **Step 1: Read the three files above** to learn the exact document classes and the `MongoStorage`
  API (collection names, insert helpers, id fields). Confirm field names against what the routes use:
  `UserDoc(email, passwordHash, displayName, buildingIds)`, `BuildingDoc(id, name, doors[bleLocalName])`,
  `activationCodes` doc keyed by `_id = code` with `buildingId`, `redeemedByUserId`, `redeemedAtEpochMs`.

- [ ] **Step 2: Add an idempotent seeder invoked at startup**

Add a `suspend fun seedDemoData(storage: MongoStorage)` that, only if the demo user is absent,
inserts: one user (`rodolphe@example.com` / bcrypt of `password`), one building
`Résidence Victor Hugo` with a door `Porte principale` whose `bleLocalName = "OSKEYS-DOOR-01"`, and an
unredeemed activation code `VICTOR-HUGO`. Call it from `Application.kt` after `MongoStorage` is built.
Match the exact constructor/field names discovered in Step 1 (adjust the sketch below to fit them):

```kotlin
suspend fun seedDemoData(storage: MongoStorage) {
    val existing = storage.users.find(Filters.eq("email", "rodolphe@example.com")).firstOrNull()
    if (existing != null) return

    val buildingId = "bld-victor-hugo"
    storage.buildings.insertOne(
        BuildingDoc(
            id = buildingId,
            name = "Résidence Victor Hugo",
            doors = listOf(DoorDoc(id = "door-1", name = "Porte principale", bleLocalName = "OSKEYS-DOOR-01")),
        ),
    )
    storage.users.insertOne(
        UserDoc(
            id = "user-1",
            email = "rodolphe@example.com",
            passwordHash = BCrypt.hashpw("password", BCrypt.gensalt()),
            displayName = "Rodolphe",
            buildingIds = emptyList(),
        ),
    )
    storage.activationCodes.insertOne(
        ActivationCodeDoc(id = "VICTOR-HUGO", buildingId = buildingId, redeemedByUserId = null, redeemedAtEpochMs = null),
    )
}
```

- [ ] **Step 3: Run the server and confirm the seed**

Run the server (its existing run config / `./gradlew run`). Confirm startup logs show it listening on
`:8080` and that the seed ran once (no duplicate on a second start).

- [ ] **Step 4: Checkpoint** — ready to commit (in the server repo).

---

## Task 8: End-to-end device verification (manual)

No code. This is the acceptance test for the whole iteration.

- [ ] **Step 1: Flash the ESP32** with `hardware/esp32-door/esp32-door.ino`; confirm it advertises
  `OSKEYS-DOOR-01` (e.g. with a BLE scanner app).

- [ ] **Step 2: Start the backend**, ensure the phone and dev machine are on the same LAN, and that
  `BASE_URL` in `core/network/build.gradle.kts` matches the dev machine's current LAN IP (update
  `network_security_config.xml` too if the IP changed).

- [ ] **Step 3: Launch the app**, log in (`rodolphe@example.com` / `password`). Home shows the empty
  state.

- [ ] **Step 4: Tap "Activer un immeuble"**, enter `VICTOR-HUGO`, validate. The sheet closes and
  "Porte principale — Résidence Victor Hugo" appears.

- [ ] **Step 5: Tap "Ouvrir".** Observe the button cycle Recherche… → Connexion… → Ouverture… →
  Ouverte, and the ESP32's LED/relay pulse for 3 s.

- [ ] **Step 6: Error paths** — with Bluetooth off, tap Ouvrir → "Activez le Bluetooth."; power off
  the ESP32 → "Porte introuvable à proximité." after the scan window.

- [ ] **Step 7: Checkpoint** — iteration 1 done; ready to commit and demo.

---

## Self-review notes

- **Spec coverage:** core:ble (T1–T2), feature:home doors+open (T3–T4), activation (T3–T4), app wiring
  (T5), firmware (T6), seed prerequisite (T7), manual BLE validation (T8), error handling (T2 states +
  T4 messages + T8). All spec sections mapped.
- **Type consistency:** `OskeysBleController.open(bleLocalName): Flow<DoorOpenState>`,
  `DoorOpenState`/`DoorOpenError`, `HomeUiState`/`ActivationUiState`, `HomeViewModel` methods
  (`open`, `onActivateClicked`, `dismissActivationSheet`, `onActivationCodeChange`, `submitActivation`)
  are used identically across tasks. `Door(id: DoorId, name, buildingName, bleLocalName)` and
  `DoorsRepository`/`ActivationResult` match the existing code.
- **Deferred/out of scope:** sharing, WebRTC, history, menu, BLE challenge-response — not in any task,
  per spec.
```
