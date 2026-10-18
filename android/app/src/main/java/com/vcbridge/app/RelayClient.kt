package com.vcbridge.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WebSocket link to the online relay server (rooms + audio). */
class RelayClient(private val url: String, private val l: Listener) {
    interface Listener {
        fun onRelayOpen()
        fun onRelayText(text: String)
        fun onRelayBinary(data: ByteArray)
        fun onRelayClosed(reason: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var closedNotified = false

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                l.onRelayOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                l.onRelayText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                l.onRelayBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                notifyClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                notifyClosed(t.message ?: "failure")
            }
        })
    }

    private fun notifyClosed(reason: String) {
        if (closedNotified) return
        closedNotified = true
        l.onRelayClosed(reason)
    }

    fun sendJson(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    fun sendBin(b: ByteArray): Boolean = ws?.send(b.toByteString(0, b.size)) ?: false

    fun close() {
        closedNotified = true // we closed it on purpose
        try { ws?.close(1000, null) } catch (e: Exception) { }
        client.dispatcher.executorService.shutdown()
    }
}
