package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.PinCode
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
