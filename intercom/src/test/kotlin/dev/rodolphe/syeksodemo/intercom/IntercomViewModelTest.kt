package dev.rodolphe.syeksodemo.intercom

import dev.rodolphe.syeksodemo.core.ble.DoorOpenError
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.network.model.IntercomOpenResultRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateResponseNetwork
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

    private fun vm(api: FakeIntercomApiService, ble: FakeSyeksoBleController) = IntercomViewModel(api, ble)

    private fun enterPin(viewModel: IntercomViewModel, pin: String) = pin.forEach { viewModel.onDigitClicked(it.toString()) }

    @Test
    fun `digits accumulate up to six`() {
        val viewModel = vm(FakeIntercomApiService(), FakeSyeksoBleController())
        enterPin(viewModel, "1234567")
        assertEquals("123456", viewModel.uiState.value.entered)
    }

    @Test
    fun `allowed pin opens the returned door and ends Granted`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = true, doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")
        }
        val ble = FakeSyeksoBleController()
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "483920")

        viewModel.validateByCodePin()
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
        val ble = FakeSyeksoBleController()
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "111111")

        viewModel.validateByCodePin()
        runCurrent()

        assertEquals(IntercomStatus.Denied("Code déjà utilisé"), viewModel.uiState.value.status)
        assertTrue(ble.openedLocalNames.isEmpty())
    }

    @Test
    fun `a failed open hands the claimed code back and names the real cause`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = true, doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")
        }
        // What happens with no powered door in range.
        val ble = FakeSyeksoBleController().apply {
            states = listOf(DoorOpenState.Scanning, DoorOpenState.Error(DoorOpenError.NotFound))
        }
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "483920")

        viewModel.validateByCodePin()
        runCurrent()

        assertEquals(IntercomStatus.Error("Porte introuvable"), viewModel.uiState.value.status)
        assertEquals(
            IntercomOpenResultRequestNetwork(pin = "483920", success = false),
            api.openResults.single(),
        )
    }

    @Test
    fun `a successful open keeps the code consumed`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = true, doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")
        }
        val viewModel = vm(api, FakeSyeksoBleController())
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "483920")

        viewModel.validateByCodePin()
        runCurrent()

        assertEquals(
            IntercomOpenResultRequestNetwork(pin = "483920", success = true),
            api.openResults.single(),
        )
    }

    @Test
    fun `OK during the BLE phase does not start a second validation`() = runTest {
        val api = FakeIntercomApiService().apply {
            response = IntercomValidateResponseNetwork(allowed = true, doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")
        }
        val ble = FakeSyeksoBleController()
        val viewModel = vm(api, ble)
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "483920")

        viewModel.validateByCodePin()
        viewModel.validateByCodePin()
        runCurrent()

        // A second claim on an already-claimed code would come back as "Code déjà utilisé".
        assertEquals(1, ble.openedLocalNames.size)
    }

    @Test
    fun `network error shows Error`() = runTest {
        val api = FakeIntercomApiService().apply { throwable = RuntimeException("offline") }
        val viewModel = vm(api, FakeSyeksoBleController())
        backgroundScope.launch { viewModel.uiState.collect() }
        enterPin(viewModel, "222222")

        viewModel.validateByCodePin()
        runCurrent()

        assertTrue(viewModel.uiState.value.status is IntercomStatus.Error)
    }
}
