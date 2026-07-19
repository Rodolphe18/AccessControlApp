package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject

class SignalingClient @Inject constructor(
    private val transport: SignalingTransport,
) : Signaling {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = false }
    private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    override val incoming: SharedFlow<SignalingMessage> = _incoming

    private var hello: SignalingMessage.Hello? = null

    /** Connect and (re)send [hello] on every (re)open until [stop]. */
    override fun start(url: String, hello: SignalingMessage.Hello) {
        this.hello = hello
        transport.connect(url, object : SignalingTransport.Listener {
            override fun onOpen() { this@SignalingClient.hello?.let { send(it) } }
            override fun onText(text: String) {
                runCatching { json.decodeFromString(SignalingMessage.serializer(), text) }
                    .getOrNull()?.let { _incoming.tryEmit(it) }
            }
            override fun onClosed() {}
            override fun onFailure(t: Throwable) { /* reconnect handled by the foreground lifecycle */ }
        })
    }

    override fun send(msg: SignalingMessage) =
        transport.send(json.encodeToString(SignalingMessage.serializer(), msg))

    override fun stop() = transport.close()
}
