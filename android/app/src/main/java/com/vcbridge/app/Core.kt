package com.vcbridge.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class Member(val num: Int, val name: String)

/**
 * Glue between the three parts:
 *   Minecraft add-on  <-(local WebSocket)->  this app  <-(WebSocket)->  relay server
 * All state changes run on the main thread.
 */
object Core : GameServer.Listener, RelayClient.Listener {
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<Runnable>()

    var running = false
    var gameConnected = false
    var gameError: String? = null
    var localName: String? = null
    var relayState = 0 // 0 = off, 1 = connecting, 2 = connected

    var code = ""
    var maxMembers = 0
    var ownerNum = -1
    var myNum = -1
    var members: List<Member> = emptyList()
    var speaking: Set<Int> = emptySet()

    var muted = true
    var deaf = false
    var master = 100
    var gain = 0
    var thr = -50
    var lastMsg: String? = null

    private val userVol = HashMap<Int, Int>()
    private var game: GameServer? = null
    private var relay: RelayClient? = null
    private var audio: AudioEngine? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            pushState() // heartbeat so the add-on knows the app is alive
            main.postDelayed(this, 10000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        running = true
        gameError = null
        audio = AudioEngine { frame -> relay?.sendBin(frame) }
        applyAudio()
        try {
            game = GameServer(Prefs.port, this).also { it.start() }
        } catch (e: Exception) {
            gameError = e.message ?: "error"
        }
        connectRelay()
        main.postDelayed(tick, 10000)
        notifyUi()
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        relay?.close()
        relay = null
        audio?.stop()
        audio = null
        val g = game
        game = null
        if (g != null) {
            Thread {
                try { g.stop(300) } catch (e: Exception) { }
            }.start()
        }
        gameConnected = false
        localName = null
        relayState = 0
        clearRoom()
        lastMsg = null
        notifyUi()
    }

    private fun connectRelay() {
        val url = Prefs.server.trim()
        if (url.isEmpty()) {
            relayState = 0
            return
        }
        relayState = 1
        try {
            relay = RelayClient(url, this).also { it.connect() }
        } catch (e: Exception) {
            relayState = 0
            relay = null
            lastMsg = "no_server"
        }
    }

    private fun displayName(): String =
        Prefs.name.trim().ifEmpty { localName ?: "Player" }

    private fun sendHello() {
        relay?.sendJson(
            JSONObject().put("t", "hello").put("id", Prefs.deviceId).put("name", displayName())
        )
    }

    // ---------------------------------------------------------------- public actions

    fun createRoom(max: Int) = sendRelay(JSONObject().put("t", "create").put("max", max))
    fun joinRoom(code: String) = sendRelay(JSONObject().put("t", "join").put("code", code.trim().uppercase()))
    fun leaveRoom() = sendRelay(JSONObject().put("t", "leave"))
    fun kick(num: Int) = sendRelay(JSONObject().put("t", "kick").put("u", num))
    fun ban(num: Int) = sendRelay(JSONObject().put("t", "ban").put("u", num))

    @JvmName("applyMuted") fun setMuted(b: Boolean) {
        muted = b
        applyAudio()
        pushState()
        notifyUi()
    }

    @JvmName("applyDeaf") fun setDeaf(b: Boolean) {
        deaf = b
        applyAudio()
        pushState()
        notifyUi()
    }

    fun setAudioSettings(vol: Int, g: Int, t: Int) {
        master = vol.coerceIn(0, 200)
        gain = g.coerceIn(-10, 10)
        thr = t.coerceIn(-127, 0)
        applyAudio()
        pushState()
    }

    fun setUserVol(num: Int, pct: Int) {
        userVol[num] = pct.coerceIn(0, 200)
        applyAudio()
        pushState()
    }

    private fun sendRelay(o: JSONObject) {
        if (relayState != 2) {
            report("no_server")
            return
        }
        relay?.sendJson(o)
    }

    private fun applyAudio() {
        val a = audio ?: return
        a.muted = muted
        a.deaf = deaf
        a.master = master
        a.gainDb = gain
        a.thrDb = thr
        for ((k, v) in userVol) a.userVol[k] = v
    }

    private fun startAudio() {
        try {
            audio?.start()
        } catch (e: Exception) {
            report("mic_denied")
        }
    }

    private fun clearRoom() {
        code = ""
        maxMembers = 0
        ownerNum = -1
        myNum = -1
        members = emptyList()
        speaking = emptySet()
        audio?.stop()
        pushState()
    }

    // ---------------------------------------------------------------- game side (add-on)

    override fun onGameOpen() { main.post { gameConnected = true; notifyUi() } }
    override fun onGameClose() { main.post { gameConnected = false; localName = null; notifyUi() } }
    override fun onGameError(msg: String) { main.post { gameError = msg; notifyUi() } }
    override fun onGameJson(o: JSONObject) { main.post { handleGameJson(o) } }

    private fun handleGameJson(o: JSONObject) {
        val h = o.optJSONObject("header") ?: return
        val b = o.optJSONObject("body") ?: return
        when (h.optString("messagePurpose")) {
            "commandResponse" -> {
                val n = b.optString("localplayername")
                if (n.isNotEmpty()) setLocal(n)
            }
            "event" -> {
                if (h.optString("eventName") != "PlayerMessage") return
                // add-on sends:  VC|<player name>|<command>|<args...>
                val msg = b.optString("message")
                val i = msg.indexOf("VC|")
                if (i < 0) return
                val parts = msg.substring(i + 3).trim().split("|")
                if (parts.size < 2) return
                if (localName == null) setLocal(parts[0])
                if (parts[0] != localName) return
                handleCmd(parts.drop(1))
            }
        }
    }

    private fun setLocal(n: String) {
        if (localName == n) return
        localName = n
        if (relayState == 2 && Prefs.name.isBlank()) {
            relay?.sendJson(JSONObject().put("t", "name").put("n", n))
        }
        pushState()
        notifyUi()
    }

    private fun handleCmd(a: List<String>) {
        val arg1 = a.getOrNull(1)
        when (a[0]) {
            "sync", "hello" -> pushState()
            "create" -> createRoom(arg1?.toIntOrNull() ?: 8)
            "join" -> joinRoom(arg1 ?: "")
            "leave" -> leaveRoom()
            "kick" -> arg1?.toIntOrNull()?.let { kick(it) }
            "ban" -> arg1?.toIntOrNull()?.let { ban(it) }
            "mute" -> setMuted(arg1 == "1")
            "deaf" -> setDeaf(arg1 == "1")
            "set" -> setAudioSettings(
                arg1?.toIntOrNull() ?: master,
                a.getOrNull(2)?.toIntOrNull() ?: gain,
                a.getOrNull(3)?.toIntOrNull() ?: thr
            )
            "uvol" -> {
                val n = arg1?.toIntOrNull()
                val p = a.getOrNull(2)?.toIntOrNull()
                if (n != null && p != null) setUserVol(n, p)
            }
        }
    }

    /** Sends the whole state to the add-on (it renders the menus from this). */
    fun pushState() {
        val g = game ?: return
        val p = localName ?: return
        if (!gameConnected) return
        val mem = JSONArray()
        for (m in members) mem.put(JSONArray().put(m.num).put(m.name))
        val uv = JSONObject()
        for ((k, v) in userVol) uv.put(k.toString(), v)
        val o = JSONObject()
            .put("p", p)
            .put("srv", if (relayState == 2) 1 else 0)
            .put("room", code)
            .put("max", maxMembers)
            .put("ow", ownerNum)
            .put("me", myNum)
            .put("mem", mem)
            .put("mute", if (muted) 1 else 0)
            .put("deaf", if (deaf) 1 else 0)
            .put("vol", master)
            .put("gain", gain)
            .put("thr", thr)
            .put("uv", uv)
        g.command("scriptevent vc:state $o")
    }

    private fun pushMsg(key: String) {
        val g = game ?: return
        val p = localName ?: return
        if (!gameConnected) return
        g.command("scriptevent vc:msg " + JSONObject().put("p", p).put("k", key))
    }

    private fun report(key: String) {
        lastMsg = key
        pushMsg(key)
        notifyUi()
    }

    // ---------------------------------------------------------------- relay side (server)

    override fun onRelayOpen() {
        main.post {
            if (!running) return@post
            relayState = 2
            sendHello()
            notifyUi()
            pushState()
        }
    }

    override fun onRelayClosed(reason: String) {
        main.post {
            if (!running) return@post
            relayState = 0
            relay = null
            clearRoom()
            notifyUi()
            main.postDelayed({
                if (running && relayState == 0) {
                    connectRelay()
                    notifyUi()
                }
            }, 5000)
        }
    }

    override fun onRelayBinary(data: ByteArray) {
        if (data.size < 3) return
        val num = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        audio?.play(num, data.copyOfRange(2, data.size))
    }

    override fun onRelayText(text: String) {
        main.post {
            try {
                handleServer(JSONObject(text))
            } catch (e: Exception) {
                // ignore malformed frames
            }
        }
    }

    private fun handleServer(o: JSONObject) {
        when (o.optString("t")) {
            "room" -> {
                val prev = code
                code = o.optString("code")
                maxMembers = o.optInt("max")
                ownerNum = o.optInt("owner")
                myNum = o.optInt("me")
                val arr = o.optJSONArray("members") ?: JSONArray()
                val list = ArrayList<Member>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    list.add(Member(m.getInt("u"), m.getString("n")))
                }
                members = list
                if (prev != code) speaking = emptySet()
                if (code.isNotEmpty()) startAudio()
                applyAudio()
                pushState()
                notifyUi()
            }
            "speak" -> {
                val arr = o.optJSONArray("s") ?: JSONArray()
                val s = HashSet<Int>()
                for (i in 0 until arr.length()) s.add(arr.getInt(i))
                speaking = s
                notifyUi()
            }
            "err" -> report(o.optString("k"))
            "kicked" -> { clearRoom(); report("kicked") }
            "banned" -> { clearRoom(); report("you_banned") }
            "left" -> { clearRoom(); notifyUi() }
        }
    }

    private fun notifyUi() {
        main.post { for (r in listeners) r.run() }
    }
}
