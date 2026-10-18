package com.vcbridge.app

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Local WebSocket server on 127.0.0.1:<port>. Minecraft Bedrock connects to it with
 *     /connect 127.0.0.1:3000
 * We subscribe to PlayerMessage events (add-on -> app) and send `scriptevent` commands (app -> add-on).
 */
class GameServer(port: Int, private val l: Listener) :
    WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    interface Listener {
        fun onGameOpen()
        fun onGameClose()
        fun onGameJson(o: JSONObject)
        fun onGameError(msg: String)
    }

    @Volatile private var conn: WebSocket? = null

    init {
        isReuseAddr = true
        connectionLostTimeout = 0
    }

    override fun onOpen(c: WebSocket, handshake: ClientHandshake) {
        conn = c
        subscribe("PlayerMessage")
        command("getlocalplayername")
        l.onGameOpen()
    }

    override fun onClose(c: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (conn === c) {
            conn = null
            l.onGameClose()
        }
    }

    override fun onMessage(c: WebSocket, message: String) {
        try {
            l.onGameJson(JSONObject(message))
        } catch (e: Exception) {
            // ignore malformed frames
        }
    }

    override fun onError(c: WebSocket?, ex: Exception) {
        l.onGameError(ex.message ?: ex.javaClass.simpleName)
    }

    override fun onStart() {}

    private fun subscribe(event: String) {
        val c = conn ?: return
        val o = JSONObject()
            .put("body", JSONObject().put("eventName", event))
            .put(
                "header", JSONObject()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("messagePurpose", "subscribe")
                    .put("version", 1)
                    .put("messageType", "commandRequest")
            )
        c.send(o.toString())
    }

    /** Runs a slash command (without the leading "/") in the connected game. */
    fun command(line: String) {
        val c = conn ?: return
        if (!c.isOpen) return
        val o = JSONObject()
            .put(
                "header", JSONObject()
                    .put("version", 1)
                    .put("requestId", UUID.randomUUID().toString())
                    .put("messageType", "commandRequest")
                    .put("messagePurpose", "commandRequest")
            )
            .put(
                "body", JSONObject()
                    .put("version", 1)
                    .put("commandLine", line)
                    .put("origin", JSONObject().put("type", "player"))
            )
        c.send(o.toString())
    }
}
