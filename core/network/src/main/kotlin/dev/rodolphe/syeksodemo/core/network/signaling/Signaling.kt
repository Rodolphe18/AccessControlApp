package dev.rodolphe.syeksodemo.core.network.signaling

import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import kotlinx.coroutines.flow.SharedFlow

/** Abstraction over the signaling socket, so ViewModels can be unit-tested with a fake. */
interface Signaling {
    val incoming: SharedFlow<SignalingMessage>
    fun start(url: String, hello: SignalingMessage.Hello)
    fun send(msg: SignalingMessage)
    fun stop()
}
