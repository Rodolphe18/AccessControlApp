package dev.rodolphe.syeksodemo.feature.intercomcall

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakeSignaling : Signaling {
        val flow = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<SignalingMessage> = flow
        val sent = mutableListOf<SignalingMessage>()
        override fun start(url: String, hello: SignalingMessage.Hello) {}
        override fun send(msg: SignalingMessage) { sent.add(msg) }
        override fun stop() {}
    }

    private fun vm(sig: FakeSignaling) = IncomingCallViewModel(sig)

    @Test fun `ring shows the ringing state`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte d'entrée")); runCurrent()
        assertEquals(IncomingCallUiState.Ringing("c1", "Porte d'entrée"), viewModel.uiState.value)
    }

    @Test fun `open sends OPEN and goes to Opening`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        assertEquals(SignalingMessage.Open("c1"), sig.sent.single())
        assertEquals(IncomingCallUiState.Opening, viewModel.uiState.value)
    }

    @Test fun `open_result success shows a success result`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        sig.flow.emit(SignalingMessage.OpenResult("c1", success = true)); runCurrent()
        assertTrue(viewModel.uiState.value is IncomingCallUiState.Result)
        assertEquals(true, (viewModel.uiState.value as IncomingCallUiState.Result).success)
    }

    @Test fun `decline sends DECLINE and clears`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onDecline(); runCurrent()
        assertEquals(SignalingMessage.Decline("c1"), sig.sent.single())
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }

    @Test fun `error message after ring shows a failure result`() = runTest {
        val sig = FakeSignaling(); val viewModel = vm(sig)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onOpen(); runCurrent()
        sig.flow.emit(SignalingMessage.ErrorMsg("c1", "Interphone hors ligne")); runCurrent()
        assertEquals(IncomingCallUiState.Result(false, "Interphone hors ligne"), viewModel.uiState.value)
    }
}
