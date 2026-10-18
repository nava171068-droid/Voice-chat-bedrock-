package com.vcbridge.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) {
            sp = c.applicationContext.getSharedPreferences("vc", Context.MODE_PRIVATE)
        }
    }

    var server: String
        get() = sp.getString("server", BuildConfig.DEFAULT_SERVER) ?: ""
        set(v) { sp.edit().putString("server", v).apply() }

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }

    var port: Int
        get() = sp.getInt("port", 3000)
        set(v) { sp.edit().putInt("port", v).apply() }

    /** UI language. English is the default. */
    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) { sp.edit().putString("lang", v).apply() }

    val deviceId: String
        get() {
            var id = sp.getString("did", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString("did", id).apply()
            }
            return id
        }
}
