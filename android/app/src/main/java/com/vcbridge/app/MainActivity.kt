package com.vcbridge.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var dyn: LinearLayout
    private lateinit var serverEt: EditText
    private lateinit var nameEt: EditText
    private lateinit var portEt: EditText
    private lateinit var maxEt: EditText
    private lateinit var codeEt: EditText
    private val refresh = Runnable { render() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startSvc() else toast(I18n.t("perm_needed"))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)

        serverEt = edit(I18n.t("server_url"), Prefs.server)
        serverEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        nameEt = edit(I18n.t("name"), Prefs.name)
        portEt = edit(I18n.t("port_hint"), Prefs.port.toString())
        portEt.inputType = InputType.TYPE_CLASS_NUMBER
        maxEt = edit(I18n.t("max_members"), "8")
        maxEt.inputType = InputType.TYPE_CLASS_NUMBER
        codeEt = edit(I18n.t("room_code"), "")
        codeEt.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))

        root.addView(tv(I18n.t("app_title"), 26f, true))
        root.addView(tv(I18n.t("lang"), 13f, false))

        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, I18n.langs.map { it.second }
        )
        sp.setSelection(I18n.langs.indexOfFirst { it.first == Prefs.lang }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val code = I18n.langs[pos].first
                if (code != Prefs.lang) {
                    saveFields()
                    Prefs.lang = code
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(sp)
        root.addView(serverEt)
        root.addView(nameEt)
        root.addView(portEt)

        dyn = LinearLayout(this)
        dyn.orientation = LinearLayout.VERTICAL
        root.addView(dyn)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
        render()
    }

    override fun onStart() {
        super.onStart()
        Core.listeners.add(refresh)
        render()
    }

    override fun onStop() {
        Core.listeners.remove(refresh)
        saveFields()
        super.onStop()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        dyn.removeAllViews()

        // start / stop
        val startBtn = btn(if (Core.running) I18n.t("stop") else I18n.t("start")) {
            saveFields()
            if (Core.running) stopService(Intent(this, VoiceService::class.java)) else requestStart()
        }
        dyn.addView(startBtn)

        if (!Core.running) return

        // status
        val gameOk = Core.gameConnected
        dyn.addView(
            tv(
                (if (gameOk) "● " + I18n.t("game_on") else "○ " + I18n.t("game_off")) +
                    (Core.localName?.let { "  ($it)" } ?: ""),
                15f, true, if (gameOk) Color.rgb(46, 160, 67) else Color.rgb(200, 80, 60)
            )
        )
        if (!gameOk) {
            dyn.addView(tv(I18n.t("game_hint"), 13f, false))
            val cmd = tv("/connect 127.0.0.1:${Prefs.port}", 16f, true)
            cmd.setTextIsSelectable(true)
            dyn.addView(cmd)
            Core.gameError?.let { dyn.addView(tv(it, 12f, false, Color.rgb(200, 80, 60))) }
        }
        val srvText = when (Core.relayState) {
            2 -> "● " + I18n.t("server_on")
            1 -> "◌ " + I18n.t("server_connecting")
            else -> "○ " + I18n.t("server_off")
        }
        dyn.addView(
            tv(
                srvText, 15f, true,
                if (Core.relayState == 2) Color.rgb(46, 160, 67) else Color.rgb(200, 80, 60)
            )
        )
        Core.lastMsg?.let { dyn.addView(tv(I18n.t("m_$it"), 14f, true, Color.rgb(200, 80, 60))) }

        if (Core.relayState != 2) return

        // mic controls
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            btn(if (Core.muted) I18n.t("unmute") else I18n.t("mute")) { Core.setMuted(!Core.muted) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            btn(if (Core.deaf) I18n.t("undeafen") else I18n.t("deafen")) { Core.setDeaf(!Core.deaf) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        dyn.addView(row)

        if (Core.code.isEmpty()) renderLobby() else renderRoom()
    }

    private fun renderLobby() {
        dyn.addView(tv(I18n.t("create_room"), 18f, true))
        dyn.addView(maxEt)
        dyn.addView(btn(I18n.t("create")) {
            Core.createRoom(maxEt.text.toString().toIntOrNull() ?: 8)
        })
        dyn.addView(tv(I18n.t("join_room"), 18f, true))
        dyn.addView(codeEt)
        dyn.addView(btn(I18n.t("join")) { Core.joinRoom(codeEt.text.toString()) })
    }

    private fun renderRoom() {
        val isOwner = Core.myNum >= 0 && Core.myNum == Core.ownerNum
        dyn.addView(tv(I18n.t("room_code"), 13f, false))
        val code = tv(Core.code, 34f, true)
        code.setTextIsSelectable(true)
        dyn.addView(code)
        dyn.addView(tv("${I18n.t("members")} ${Core.members.size}/${Core.maxMembers}", 16f, true))

        for (m in Core.members) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val dot = if (Core.speaking.contains(m.num)) "🟢" else "⚪"
            var label = "$dot ${m.name}"
            if (m.num == Core.ownerNum) label += " ★"
            if (m.num == Core.myNum) label += " (${I18n.t("you")})"
            r.addView(tv(label, 16f, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (isOwner && m.num != Core.myNum) {
                r.addView(btn(I18n.t("kick")) { Core.kick(m.num) })
                r.addView(btn(I18n.t("ban")) {
                    AlertDialog.Builder(this)
                        .setMessage("${I18n.t("ban_confirm")}\n${m.name}")
                        .setPositiveButton(I18n.t("yes")) { _, _ -> Core.ban(m.num) }
                        .setNegativeButton(I18n.t("no"), null)
                        .show()
                })
            }
            dyn.addView(r)
        }
        dyn.addView(btn(I18n.t("leave")) { Core.leaveRoom() })
    }

    // ------------------------------------------------------------------ start / permissions

    private fun requestStart() {
        val need = ArrayList<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) startSvc() else permLauncher.launch(need.toTypedArray())
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startSvc() {
        ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
    }

    private fun saveFields() {
        if (!::serverEt.isInitialized) return
        Prefs.server = serverEt.text.toString().trim()
        Prefs.name = nameEt.text.toString().trim()
        val p = portEt.text.toString().toIntOrNull()
        if (p != null && p in 1024..65535) Prefs.port = p
    }

    // ------------------------------------------------------------------ small view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun edit(hintText: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(value)
        e.setSingleLine()
        return e
    }

    private fun tv(text: String, size: Float, bold: Boolean, color: Int? = null): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        if (bold) t.setTypeface(null, Typeface.BOLD)
        if (color != null) t.setTextColor(color)
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }
}
