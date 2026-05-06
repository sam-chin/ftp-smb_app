package com.example.lanmediaplayer

import android.content.Context
import android.content.SharedPreferences

class ConnectionPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("connection_prefs", Context.MODE_PRIVATE)
    
    companion object {
        const val KEY_PROTOCOL = "protocol"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_SHARE = "share"
        const val KEY_DOMAIN = "domain"
        const val KEY_AUTO_CONNECT = "auto_connect"
    }
    
    fun saveConnection(
        protocol: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        share: String = "",
        domain: String = ""
    ) {
        prefs.edit().apply {
            putString(KEY_PROTOCOL, protocol)
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putString(KEY_SHARE, share)
            putString(KEY_DOMAIN, domain)
            putBoolean(KEY_AUTO_CONNECT, true)
            apply()
        }
    }
    
    fun getProtocol(): String = prefs.getString(KEY_PROTOCOL, "FTP") ?: "FTP"
    fun getHost(): String = prefs.getString(KEY_HOST, "") ?: ""
    fun getPort(): Int = prefs.getInt(KEY_PORT, 21)
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun getShare(): String = prefs.getString(KEY_SHARE, "") ?: ""
    fun getDomain(): String = prefs.getString(KEY_DOMAIN, "") ?: ""
    fun shouldAutoConnect(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT, false)
    
    fun clear() {
        prefs.edit().clear().apply()
    }
}
