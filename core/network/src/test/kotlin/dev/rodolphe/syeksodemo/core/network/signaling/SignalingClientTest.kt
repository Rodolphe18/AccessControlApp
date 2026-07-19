package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingClientTest {
    private class FakeTransport : SignalingTransport {
        var listener: SignalingTransport.Listener? = null
        val sent = mutableListOf<String>()
        override fun connect(url: String, listener: SignalingTransport.Listener) {
            this.listener = listener; listener.onOpen()
        }
        override fun send(text: String) { sent.add(text) }
        override fun close() {}
    }

    @Test fun `sends hello on open`() = runTest {
        val t = FakeTransport()
        SignalingClient(t).start("ws://x/ws", SignalingMessage.Hello(role = "resident", jwt = "j"))
        assertTrue(t.sent.single().contains("\"type\":\"hello\""))
    }

    @Test fun `parses inbound ring into the flow`() = runTest {
        val t = FakeTransport()
        val client = SignalingClient(t)
        val received = mutableListOf<SignalingMessage>()
        val job = launch { client.incoming.collect { received.add(it) } }
        runCurrent() // let the collector subscribe before we emit (SharedFlow has no replay)

        client.start("ws://x/ws", SignalingMessage.Hello(role = "resident", jwt = "j"))
        t.listener?.onText("""{"type":"ring","callId":"c1","targetUserId":"u1","doorName":"Porte"}""")
        runCurrent()

        assertEquals(SignalingMessage.Ring("c1", "u1", "Porte"), received.single())
        job.cancel()
    }
}
