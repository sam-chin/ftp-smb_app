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
        const val KEY_SMB_SHARES_CACHE = "smb_shares_cache"  // 缓存的共享目录列表
        
        // 连接历史记录
        const val KEY_CONNECTION_HISTORY = "connection_history"  // 格式: protocol|host|port|username|password|share|domain
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
    
    // 保存 SMB 共享目录缓存
    fun saveSmbSharesCache(shares: List<String>) {
        val sharesStr = shares.joinToString(",")
        prefs.edit().putString(KEY_SMB_SHARES_CACHE, sharesStr).apply()
    }
    
    // 获取 SMB 共享目录缓存
    fun getSmbSharesCache(): List<String> {
        val sharesStr = prefs.getString(KEY_SMB_SHARES_CACHE, "") ?: ""
        return if (sharesStr.isNotEmpty()) {
            sharesStr.split(",").filter { it.isNotBlank() }
        } else {
            emptyList()
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
    
    // 保存连接历史（最多保存10条）
    fun saveConnectionHistory(protocol: String, host: String, port: Int, username: String, password: String, share: String = "", domain: String = "") {
        val history = getConnectionHistory().toMutableList()
        
        // 移除相同的记录（如果存在）
        history.removeAll { it.host == host && it.protocol == protocol }
        
        // 添加新记录到开头
        history.add(0, ConnectionRecord(protocol, host, port, username, password, share, domain))
        
        // 只保留最近10条
        val limitedHistory = history.take(10)
        
        // 保存到 SharedPreferences
        val historyStr = limitedHistory.joinToString(";;") { record ->
            "${record.protocol}|${record.host}|${record.port}|${record.username}|${record.password}|${record.share}|${record.domain}"
        }
        prefs.edit().putString(KEY_CONNECTION_HISTORY, historyStr).apply()
    }
    
    // 获取连接历史
    fun getConnectionHistory(): List<ConnectionRecord> {
        val historyStr = prefs.getString(KEY_CONNECTION_HISTORY, "") ?: ""
        return if (historyStr.isNotEmpty()) {
            historyStr.split(";;").filter { it.isNotBlank() }.mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 7) {
                    ConnectionRecord(
                        protocol = parts[0],
                        host = parts[1],
                        port = parts[2].toIntOrNull() ?: 0,
                        username = parts[3],
                        password = parts[4],
                        share = parts[5],
                        domain = parts[6]
                    )
                } else null
            }
        } else {
            emptyList()
        }
    }
    
    // 根据主机名查找匹配的连接记录
    fun findMatchingConnection(host: String, protocol: String): ConnectionRecord? {
        val history = getConnectionHistory()
        return history.find { it.host.equals(host, ignoreCase = true) && it.protocol == protocol }
    }
    
    // 连接记录数据类
    data class ConnectionRecord(
        val protocol: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val share: String = "",
        val domain: String = ""
    )
}
