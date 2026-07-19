package dev.rodolphe.syeksodemo.core.network.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject

/** Minimal text-frame WebSocket transport, so [SignalingClient] is testable with a fake. */
interface SignalingTransport {
    fun connect(url: String, listener: Listener)
    fun send(text: String)
    fun close()

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed()
        fun onFailure(t: Throwable)
    }
}

/** OkHttp-backed transport, reusing the client already configured for Retrofit. */
class OkHttpSignalingTransport @Inject constructor(
    private val client: OkHttpClient,
) : SignalingTransport {
    private var webSocket: WebSocket? = null

    override fun connect(url: String, listener: SignalingTransport.Listener) {
        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(ws: WebSocket, text: String) = listener.onText(text)
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = listener.onClosed()
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = listener.onFailure(t)
            },
        )
    }

    override fun send(text: String) { webSocket?.send(text) }
    override fun close() { webSocket?.close(1000, null); webSocket = null }
}
