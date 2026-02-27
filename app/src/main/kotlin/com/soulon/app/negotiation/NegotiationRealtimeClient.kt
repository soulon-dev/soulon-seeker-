package com.soulon.app.negotiation

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class NegotiationRealtimeClient(
    private val okHttp: OkHttpClient = OkHttpClient(),
) {
    fun observe(url: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val ws =
            okHttp.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        trySend(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        close()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        close(t)
                    }
                },
            )

        awaitClose { ws.close(1000, "closed") }
    }
}

