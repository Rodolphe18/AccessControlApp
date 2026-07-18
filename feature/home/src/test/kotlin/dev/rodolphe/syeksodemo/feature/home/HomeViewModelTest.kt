package dev.rodolphe.syeksodemo.feature.home

import dev.rodolphe.syeksodemo.core.ble.DoorOpenError
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.data.repository.ActivationResult
import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import kotlinx.coroutines.CoroutineScope
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
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val door = Door(
        id = DoorId("d1"),
        name = "Porte principale",
        buildingName = "Résidence Victor Hugo",
        bleLocalName = "SYEKSO-DOOR-01",
    )

    private fun buildViewModel(
        repo: FakeDoorsRepository = FakeDoorsRepository(),
        ble: FakeSyeksoBleController = FakeSyeksoBleController(),
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
        val ble = FakeSyeksoBleController()
        val vm = buildViewModel(repo = repo, ble = ble)
        backgroundScope.launchCollect(vm)

        vm.open(door)
        runCurrent()

        assertEquals(listOf("SYEKSO-DOOR-01"), ble.openedLocalNames)
        assertEquals(DoorOpenState.Opened, vm.uiState.value.opening[door.id])
    }

    @Test
    fun `open surfaces an error state`() = runTest {
        val repo = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val ble = FakeSyeksoBleController().apply {
            states = listOf(DoorOpenState.Scanning, DoorOpenState.Error(DoorOpenError.NotFound))
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
private fun CoroutineScope.launchCollect(vm: HomeViewModel) {
    launch { vm.uiState.collect() }
}
