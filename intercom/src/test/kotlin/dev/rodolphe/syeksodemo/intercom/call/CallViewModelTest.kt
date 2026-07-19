package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.ble.DoorOpenError
import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.intercom.FakeSyeksoBleController
import dev.rodolphe.syeksodemo.intercom.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private val door = "OSKEY-HALL-01"
    private val fakeDirectory = object : DirectoryProvider {
        override suspend fun residents() = listOf(DirectoryEntryNetwork("user-rodolphe", "Rodolphe"))
    }
    private fun vm(sig: FakeSignaling, ble: FakeSyeksoBleController) = CallViewModel(
        signaling = sig, bleController = ble,
        directoryProvider = fakeDirectory,
        config = IntercomConfig(buildingId = "bld-montmartre", doorName = "Porte d'entrée", doorBleLocalName = door),
    )

    @Test fun `loads directory and preselects the single resident`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig, FakeSyeksoBleController())
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        assertEquals("user-rodolphe", viewModel.uiState.value.selectedUserId)
        assertEquals(true, viewModel.uiState.value.canRing)
    }

    @Test fun `ring sends RING with target and door`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig, FakeSyeksoBleController())
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val ring = sig.sent.single() as SignalingMessage.Ring
        assertEquals("user-rodolphe", ring.targetUserId)
        assertEquals("Porte d'entrée", ring.doorName)
        assertEquals(CallStatus.Ringing, viewModel.uiState.value.status)
    }

    @Test fun `incoming OPEN triggers BLE open and reports success`() = runTest {
        val sig = FakeSignaling()
        val ble = FakeSyeksoBleController().apply { states = listOf(DoorOpenState.Opened) }
        val viewModel = vm(sig, ble)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Open(callId)); runCurrent()
        assertEquals(door, ble.openedLocalNames.single())
        val result = sig.sent.last() as SignalingMessage.OpenResult
        assertEquals(true, result.success)
    }

    @Test fun `incoming OPEN with BLE failure reports failure`() = runTest {
        val sig = FakeSignaling()
        val ble = FakeSyeksoBleController().apply { states = listOf(DoorOpenState.Error(DoorOpenError.NotFound)) }
        val viewModel = vm(sig, ble)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        viewModel.ring(); runCurrent()
        val callId = (sig.sent.single() as SignalingMessage.Ring).callId
        sig.flow.emit(SignalingMessage.Open(callId)); runCurrent()
        val result = sig.sent.last() as SignalingMessage.OpenResult
        assertEquals(false, result.success)
    }
}
