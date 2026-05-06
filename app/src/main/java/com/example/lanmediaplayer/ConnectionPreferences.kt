package com.example.lanmediaplayer

import android.content.Context
import android.content.SharedPreferences

class ConnectionPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("connection_prefs", Context.MODE_PRIVATE)
    
    companion object {
        const val KEY_FTP_PROTOCOL = "ftp_protocol"
        const val KEY_FTP_HOST = "ftp_host"
        const val KEY_FTP_PORT = "ftp_port"
        const val KEY_FTP_USERNAME = "ftp_username"
        const val KEY_FTP_PASSWORD = "ftp_password"
        
        const val KEY_SMB_PROTOCOL = "smb_protocol"
        const val KEY_SMB_HOST = "smb_host"
        const val KEY_SMB_PORT = "smb_port"
        const val KEY_SMB_USERNAME = "smb_username"
        const val KEY_SMB_PASSWORD = "smb_password"
        const val KEY_SMB_SHARE = "smb_share"
        const val KEY_SMB_DOMAIN = "smb_domain"
    }
    
    fun saveFtpConnection(
        host: String,
        port: Int,
        username: String,
        password: String
    ) {
        prefs.edit().apply {
            putString(KEY_FTP_HOST, host)
            putInt(KEY_FTP_PORT, port)
            putString(KEY_FTP_USERNAME, username)
            putString(KEY_FTP_PASSWORD, password)
            apply()
        }
    }
    
    fun saveSmbConnection(
        host: String,
        port: Int,
        username: String,
        password: String,
        share: String = "",
        domain: String = ""
    ) {
        prefs.edit().apply {
            putString(KEY_SMB_HOST, host)
            putInt(KEY_SMB_PORT, port)
            putString(KEY_SMB_USERNAME, username)
            putString(KEY_SMB_PASSWORD, password)
            putString(KEY_SMB_SHARE, share)
            putString(KEY_SMB_DOMAIN, domain)
            apply()
        }
    }
    
    // FTP getters
    fun getFtpHost(): String = prefs.getString(KEY_FTP_HOST, "") ?: ""
    fun getFtpPort(): Int = prefs.getInt(KEY_FTP_PORT, 21)
    fun getFtpUsername(): String = prefs.getString(KEY_FTP_USERNAME, "") ?: ""
    fun getFtpPassword(): String = prefs.getString(KEY_FTP_PASSWORD, "") ?: ""
    
    // SMB getters
    fun getSmbHost(): String = prefs.getString(KEY_SMB_HOST, "") ?: ""
    fun getSmbPort(): Int = prefs.getInt(KEY_SMB_PORT, 445)
    fun getSmbUsername(): String = prefs.getString(KEY_SMB_USERNAME, "") ?: ""
    fun getSmbPassword(): String = prefs.getString(KEY_SMB_PASSWORD, "") ?: ""
    fun getSmbShare(): String = prefs.getString(KEY_SMB_SHARE, "") ?: ""
    fun getSmbDomain(): String = prefs.getString(KEY_SMB_DOMAIN, "") ?: ""
    
    fun clear() {
        prefs.edit().clear().apply()
    }
}
