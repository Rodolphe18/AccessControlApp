package dev.rodolphe.syeksodemo.feature.intercomcall

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.core.webrtc.WebRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    private fun vm(sig: FakeSignaling, rtc: FakeWebRtcSession) =
        IncomingCallViewModel(sig, { rtc })

    private suspend fun TestScope.ringAndAnswer(sig: FakeSignaling, viewModel: IncomingCallViewModel) {
        sig.flow.emit(SignalingMessage.Ring("c1", "u1", "Porte")); runCurrent()
        viewModel.onAnswer(); runCurrent()
    }

    @Test fun `answer sends Accept and starts the callee session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        assertEquals(SignalingMessage.Accept("c1"), sig.sent.single())
        assertTrue(rtc.calls.contains("startAsCallee"))
        assertTrue(viewModel.uiState.value is IncomingCallUiState.InCall)
    }

    @Test fun `incoming offer creates an answer and sends it`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        sig.flow.emit(SignalingMessage.Offer("c1", "OFFER")); runCurrent()
        assertTrue(rtc.calls.contains("onRemoteSdp:offer"))
        assertTrue(rtc.calls.contains("createAnswer"))
        rtc.flow.emit(WebRtcEvent.LocalSdp("ANSWER", "answer")); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Answer("c1", "ANSWER") })
    }

    @Test fun `local ice is sent as IceCandidate`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        rtc.flow.emit(WebRtcEvent.LocalIce("cand", "0", 0)); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.IceCandidate("c1", "cand", "0", 0) })
    }

    @Test fun `incoming ice is added to the session`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        sig.flow.emit(SignalingMessage.IceCandidate("c1", "cand", "0", 0)); runCurrent()
        assertTrue(rtc.calls.contains("addRemoteIce"))
    }

    @Test fun `open during call sends OPEN and open_result shows the message, call stays`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        viewModel.onOpen(); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Open("c1") })
        sig.flow.emit(SignalingMessage.OpenResult("c1", true)); runCurrent()
        val s = viewModel.uiState.value
        assertTrue(s is IncomingCallUiState.InCall && s.openMessage == "Porte ouverte")
    }

    @Test fun `hangup sends Hangup, closes the session and clears`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        viewModel.onHangup(); runCurrent()
        assertTrue(sig.sent.any { it == SignalingMessage.Hangup("c1") })
        assertTrue(rtc.calls.contains("close"))
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }

    @Test fun `remote hangup closes and clears`() = runTest {
        val sig = FakeSignaling(); val rtc = FakeWebRtcSession(); val viewModel = vm(sig, rtc)
        backgroundScope.launch { viewModel.uiState.collect() }; runCurrent()
        ringAndAnswer(sig, viewModel)
        sig.flow.emit(SignalingMessage.Hangup("c1")); runCurrent()
        assertTrue(rtc.calls.contains("close"))
        assertEquals(IncomingCallUiState.None, viewModel.uiState.value)
    }
}
