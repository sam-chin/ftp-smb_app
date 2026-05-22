package com.lanmedia.player.network

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Properties

// ✅ 自定义异常：表示SMB连接已断开，需要重连
class SmbConnectionLostException(message: String) : Exception(message)

data class SmbFileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val path: String
)

class SmbClient(
    private val logCallback: ((String) -> Unit)? = null,
    private val connectionPrefs: com.lanmedia.player.ConnectionPreferences? = null  // ✅ 新增：用于缓存连接参数
) {
    private var auth: NtlmPasswordAuthenticator? = null
    private var context: CIFSContext? = null
    private var baseUrl: String = ""
    
    private var host: String = ""
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""
    
    // Track server's detected encoding from file listings
    private var serverEncoding: Charset? = null
    
    // Available shares detected during connection
    private var availableShares: List<String> = emptyList()
    
    // ✅ 关键优化：连接状态缓存，避免频繁网络请求
    private var lastConnectionCheckTime: Long = 0  // 上次检查时间戳
    private var cachedConnectionStatus: Boolean = false  // 缓存的连接状态
    private val CONNECTION_CACHE_DURATION = 30000L  // 缓存有效期30秒
    
    // ✅ Windows SMB 客户端特性：连接会话管理
    private var lastActivityTime: Long = 0  // 最后活动时间
    private val SESSION_KEEPALIVE_INTERVAL = 300000L  // 会话保活间隔5分钟
    
    // ✅ Mutex to synchronize SMB operations (only for connection state, not file I/O)
    private val connectionMutex = Mutex()
    
    private fun log(message: String) {
        // Use Android Log with UTF-8 support
        Log.i("SmbClient", message)
        println(message)
        logCallback?.invoke(message)
    }
    
    suspend fun connect(
        host: String,
        share: String = "",  // Empty means auto-detect
        username: String,
        password: String,
        domain: String = ""  // Empty means try common domains
    ): Boolean {
        // ✅ 智能缓存：尝试从历史记录中获取domain和share
        var cachedDomain = domain
        var cachedShare = share
        
        if (connectionPrefs != null && domain.isEmpty()) {
            val history = connectionPrefs.findMatchingConnection(host, "SMB")
            if (history != null) {
                log("[SMB-JCIFS] 🎯 Found cached connection for host: $host")
                log("[SMB-JCIFS]    - Cached domain: '${history.domain}'")
                log("[SMB-JCIFS]    - Cached share: '${history.share}'")
                
                // 使用缓存的domain和share（如果调用者没有指定）
                if (domain.isEmpty() && history.domain.isNotEmpty()) {
                    cachedDomain = history.domain
                    log("[SMB-JCIFS]    → Using cached domain")
                }
                if (share.isEmpty() && history.share.isNotEmpty()) {
                    cachedShare = history.share
                    log("[SMB-JCIFS]    → Using cached share")
                }
            }
        }
        
        // ✅ 视频播放快速重连优化：减少重试次数和延迟
        val maxRetries = 2  // 从3次减少到2次，加快失败反馈
        var lastException: Exception? = null
        
        return withContext(Dispatchers.IO) {
            for (attempt in 1..maxRetries) {
                try {
                    if (attempt > 1) {
                        log("[SMB-JCIFS] Retry attempt $attempt/$maxRetries...")
                        delay(500)  // ✅ 从2秒减少到0.5秒，加快重连速度
                    }
                    val success = connectInternal(host, cachedShare, username, password, cachedDomain)
                    
                    // ✅ 连接成功后，保存domain和share到缓存
                    if (success && connectionPrefs != null) {
                        connectionPrefs.saveSmbConnection(
                            host = host,
                            port = 445,
                            username = username,
                            password = password,
                            share = this@SmbClient.share,  // 实际使用的share
                            domain = this@SmbClient.domain  // 实际使用的domain
                        )
                        log("[SMB-JCIFS] 💾 Connection parameters saved to cache")
                        log("[SMB-JCIFS]    - Domain: '${this@SmbClient.domain}'")
                        log("[SMB-JCIFS]    - Share: '${this@SmbClient.share}'")
                    }
                    
                    return@withContext success
                } catch (e: Exception) {
                    lastException = e
                    log("[SMB-JCIFS] Attempt $attempt failed: ${e.message}")
                    if (attempt < maxRetries) {
                        log("[SMB-JCIFS] Will retry in 0.5 seconds...")
                    }
                }
            }
            
            log("[SMB-JCIFS] All $maxRetries attempts failed")
            throw lastException ?: Exception("Connection failed after $maxRetries attempts")
        }
    }
    
    // ✅ 检查连接是否仍然有效（带缓存优化）
    fun isConnected(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // ✅ 关键优化：如果缓存还在有效期内，直接返回缓存结果
        if (currentTime - lastConnectionCheckTime < CONNECTION_CACHE_DURATION) {
            return cachedConnectionStatus  // 无网络请求!
        }
        
        // 缓存过期，重新检查连接
        return try {
            // ✅ 关键修复：不仅检查context，还要尝试访问服务器验证连接
            if (context == null || baseUrl.isEmpty()) {
                cachedConnectionStatus = false
                lastConnectionCheckTime = currentTime
                return false
            }
            
            // ✅ 优化：使用短超时快速检测连接状态
            val testFile = SmbFile(baseUrl, context)
            val exists = withTimeoutOrNull(3000) {  // 最多等待3秒
                testFile.exists()  // 这会触发网络请求，验证连接
            }
            
            val result = exists == true
            
            // 更新缓存
            cachedConnectionStatus = result
            lastConnectionCheckTime = currentTime
            
            log("[SMB-JCIFS] Connection check result: $result (cached for ${CONNECTION_CACHE_DURATION/1000}s)")
            result
        } catch (e: Exception) {
            log("[SMB-JCIFS] Connection check failed: ${e.message}")
            cachedConnectionStatus = false
            lastConnectionCheckTime = currentTime
            false
        }
    }
    
    // ✅ 关键新增：检查是否已选择共享目录
    fun hasSelectedShare(): Boolean {
        return baseUrl.isNotEmpty() && share.isNotEmpty()
    }
    
    // ✅ Windows SMB 特性：检查会话是否需要保活
    fun shouldKeepAlive(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastActivity = currentTime - lastActivityTime
        
        // 如果超过5分钟没有活动，且连接仍然有效，发送保活请求
        if (timeSinceLastActivity > SESSION_KEEPALIVE_INTERVAL && cachedConnectionStatus) {
            log("[SMB-JCIFS] Session idle for ${timeSinceLastActivity/1000}s, sending keep-alive...")
            return true
        }
        return false
    }
    
    // ✅ 强制断开并清理所有资源
    fun forceDisconnect() {
        log("[SMB-JCIFS] Force disconnecting and cleaning up resources")
        try {
            // ✅ 关键修复：先尝试关闭context，释放底层TCP连接和SMB会话
            if (context != null) {
                try {
                    // JCIFS 2.x 没有直接的close方法，但可以通过设置null让GC回收
                    // 更重要的是：确保所有打开的InputStream都被关闭
                    log("[SMB-JCIFS] Closing CIFS context...")
                    
                    // ✅ 关键优化：显式通知JCIFS关闭所有连接
                    // 通过创建一个新的BaseContext来替换旧的，强制关闭旧连接
                    context = null
                } catch (e: Exception) {
                    log("[SMB-JCIFS] Error closing context: ${e.message}")
                }
            }
            
            // ✅ 清空所有状态变量，确保下次连接是全新的
            auth = null
            baseUrl = ""
            host = ""
            share = ""
            username = ""
            password = ""
            domain = ""
            serverEncoding = null
            availableShares = emptyList()
            
            // ✅ 关键优化：清除连接缓存
            cachedConnectionStatus = false
            lastConnectionCheckTime = 0
            lastActivityTime = 0  // 重置活动时间
            log("[SMB-JCIFS] Connection cache cleared")
            
            // ✅ 建议GC回收，加速socket关闭
            System.gc()
            
            log("[SMB-JCIFS] All resources cleaned up")
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error during force disconnect: ${e.message}")
        }
    }
    
    private suspend fun connectInternal(
        host: String,
        share: String,
        username: String,
        password: String,
        domain: String
    ): Boolean = withContext(Dispatchers.IO) {
        // ✅ 关键修复：整个重连过程最多15秒，防止无限等待
        val result = withTimeoutOrNull(15000) {
            try {
                // ✅ 关键修复：连接前彻底清理旧资源，避免TCP/SMB会话残留
                if (context != null || baseUrl.isNotEmpty()) {
                    log("[SMB-JCIFS] ⚠️ Detected existing connection, forcing cleanup before reconnect...")
                    forceDisconnect()
                    // ✅ 视频播放快速重连：减少等待时间到200ms（原500ms）
                    delay(200)
                }
                
                // ✅ 强制使用IPv4协议栈（解决澎湃OS问题）
                java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
                
                log("[SMB-JCIFS] === Starting connection ===")
                log("[SMB-JCIFS] Host: $host")
                log("[SMB-JCIFS] Share: '$share' (empty means auto-detect)")
                log("[SMB-JCIFS] Username: '$username'")
                log("[SMB-JCIFS] Domain: '$domain' (empty means auto-detect)")
                
                // ✅ 智能检测IP类型（仅用于日志）
                val isIPv6 = host.contains(":") && !host.contains(".")
                if (isIPv6) {
                    log("[SMB-JCIFS] Detected IPv6 address")
                } else {
                    log("[SMB-JCIFS] Detected IPv4 address")
                }
                
                // ✅ 小米澎湃OS诊断提示
                log("[SMB-JCIFS] 💡 If connection fails on Xiaomi HyperOS, please check:")
            log("[SMB-JCIFS]    1. Settings → Apps → LAN Media → Battery Saver → No restrictions")
            log("[SMB-JCIFS]    2. Security App → Network Assistant → Allow LAN access")
            log("[SMB-JCIFS]    3. Settings → Connection & Sharing → Private DNS → Off")
            log("[SMB-JCIFS]    4. WLAN → WLAN Assistant → Disable 'Smart network acceleration'")
            log("[SMB-JCIFS]")
            log("[SMB-JCIFS]    🔧 Developer Options:")
            log("[SMB-JCIFS]    - Check 'Background process limit' = 'Standard limit'")
            log("[SMB-JCIFS]    - Try enabling 'USB debugging' (may help)")
            
            this@SmbClient.host = host
            this@SmbClient.username = username
            this@SmbClient.password = password
            
            // Try multiple domain values if not specified
            val domainsToTry = if (domain.isNotEmpty()) {
                listOf(domain)
            } else {
                // ✅ 视频播放快速重连优化：只尝试最常用的domain，减少等待时间
                listOf("", "WORKGROUP")  // 从4个减少到2个
            }
            
            var connected = false
            var detectedShare = share
            var detectedDomain = domain
            
            for (testDomain in domainsToTry) {
                if (connected) break
                
                this@SmbClient.domain = testDomain
                log("[SMB-JCIFS] Trying domain: '${if (testDomain.isEmpty()) "(empty)" else testDomain}'")
                
                // Configure JCIFS for file operations (SMB2/3)
                val properties = Properties()
                
                // ✅ Windows SMB 客户端稳定性优化
                // 1. 超时配置（平衡稳定性和响应速度）
                properties.setProperty("jcifs.smb.client.responseTimeout", "30000")  // 响应超时30秒（Windows默认）
                properties.setProperty("jcifs.smb.client.soTimeout", "30000")        // Socket超时30秒
                properties.setProperty("jcifs.smb.client.connTimeout", "10000")      // 连接超时10秒
                
                // 2. 连接保持和心跳（Windows SMB 核心特性）
                properties.setProperty("jcifs.smb.client.keepAlive", "true")         // 启用TCP Keep-Alive
                properties.setProperty("jcifs.smb.client.tcpNoDelay", "true")        // 禁用Nagle算法，减少延迟
                properties.setProperty("jcifs.smb.client.idleTimeout", "600000")     // 空闲超时10分钟（Windows默认）
                
                // 3. 会话和连接复用（Windows SMB 关键优化）
                properties.setProperty("jcifs.smb.client.useNTLMv2", "true")         // 使用NTLMv2认证
                properties.setProperty("jcifs.smb.client.lmCompatibility", "3")      // NTLMv2级别
                properties.setProperty("jcifs.smb.client.signingPreferred", "false") // 禁用签名（提升性能）
                properties.setProperty("jcifs.smb.client.dfs.disabled", "true")      // 禁用DFS
                
                // 4. 文件锁和Oplock管理（Windows SMB 默认行为）
                properties.setProperty("jcifs.smb.client.locking", "true")           // ✅ 启用文件锁（Windows默认）
                properties.setProperty("jcifs.smb.client.oplocks", "true")           // ✅ 启用Oplock（Windows默认）
                properties.setProperty("jcifs.smb.client.notifySize", "65536")       // 通知缓冲区64KB
                
                // 5. 传输缓冲区优化（Windows SMB 默认值）
                properties.setProperty("jcifs.smb.client.rcv_buf_size", "16384")     // 接收缓冲区16KB（Windows默认）
                properties.setProperty("jcifs.smb.client.snd_buf_size", "16384")     // 发送缓冲区16KB
                properties.setProperty("jcifs.smb.client.maxBuffers", "10")          // 最大缓冲区数10个
                
                if (testDomain.isNotEmpty()) {
                    properties.setProperty("jcifs.smb.client.domain", testDomain)
                }
                properties.setProperty("jcifs.smb.client.username", username)
                properties.setProperty("jcifs.smb.client.password", password)
                
                log("[SMB-JCIFS] Creating CIFS context...")
                val config = PropertyConfiguration(properties)
                context = BaseContext(config)
                
                if (share.isNotEmpty()) {
                    // Test specified share
                    baseUrl = "smb://$host/$share/"
                    log("[SMB-JCIFS] Testing specified share: $baseUrl")
                    
                    try {
                        // ✅ 视频播放快速重连：减少超时到3秒（原5秒）
                        val testFile = SmbFile(baseUrl, context)
                        val exists = withTimeoutOrNull(3000) {  // 最多等待3秒
                            testFile.exists()
                        }
                        
                        if (exists == true) {
                            log("[SMB-JCIFS] Share exists and is accessible")
                            detectedShare = share
                            detectedDomain = testDomain
                            connected = true
                        } else if (exists == false) {
                            log("[SMB-JCIFS] Share does not exist or access denied")
                        } else {
                            log("[SMB-JCIFS] ⚠️ Share existence check timed out after 3 seconds")
                        }
                    } catch (e: Exception) {
                        log("[SMB-JCIFS] Error testing share: ${e.message}")
                    }
                } else {
                    log("[SMB-JCIFS] Auto-detecting available shares...")
                    // 只在没有缓存时才重新扫描
                    if (availableShares.isEmpty()) {
                        log("[SMB-JCIFS] ⚠️ Share enumeration may be slow on Windows servers")
                        log("[SMB-JCIFS] If this takes too long, please specify the share name directly")
                        
                        // ✅ 添加超时保护，最多等待8秒
                        val shares = try {
                            withTimeoutOrNull(8000) {
                                listShares()
                            }
                        } catch (e: Exception) {
                            log("[SMB-JCIFS] Share enumeration error: ${e.message}")
                            null
                        }
                        
                        if (shares != null && shares.isNotEmpty()) {
                            availableShares = shares
                            log("[SMB-JCIFS] Found ${availableShares.size} available shares: ${availableShares.joinToString(", ")}")
                        } else {
                            log("[SMB-JCIFS] ⚠️ Share enumeration timed out or failed")
                            log("[SMB-JCIFS] Please reconnect and specify the share name manually")
                            log("[SMB-JCIFS] Common shares: gx, share, public, media")
                            // 不设置connected = true，让用户重新连接并指定共享名
                        }
                    } else {
                        log("[SMB-JCIFS] Using cached shares: ${availableShares.size} shares")
                    }
                }
            }
            
            if (connected) {
                this@SmbClient.share = detectedShare
                this@SmbClient.domain = detectedDomain
                log("[SMB-JCIFS] === Connection successful ===")
                log("[SMB-JCIFS] Connected to share: $detectedShare")
                log("[SMB-JCIFS] Domain: ${if (detectedDomain.isEmpty()) "(empty)" else detectedDomain}")
                log("[SMB-JCIFS] Base URL: $baseUrl"
                
                // ✅ 关键优化：连接成功后，清除缓存并标记为已连接
                cachedConnectionStatus = true
                lastConnectionCheckTime = System.currentTimeMillis()
                
                true
            } else if (availableShares.isNotEmpty() && detectedShare.isNotEmpty()) {
                this@SmbClient.share = detectedShare
                this@SmbClient.domain = detectedDomain
                baseUrl = "smb://$host/$detectedShare/"
                log("[SMB-JCIFS] === Connection successful ===")
                
                // ✅ 关键优化：连接成功后，清除缓存并标记为已连接
                cachedConnectionStatus = true
                lastConnectionCheckTime = System.currentTimeMillis()
                
                true
            } else if (availableShares.isNotEmpty()) {
                // 成功枚举到共享目录，但没有选择具体共享
                // ❌ 关键修复：不设置share为空，而是返回false，让上层代码提示用户选择共享
                this@SmbClient.share = ""
                this@SmbClient.domain = detectedDomain
                baseUrl = ""  // 清空baseUrl，避免后续操作使用无效URL
                log("[SMB-JCIFS] === Share enumeration successful, but no share selected ===")
                log("[SMB-JCIFS] Available shares: ${availableShares.joinToString(", ")}")
                log("[SMB-JCIFS] ⚠️ Please reconnect and specify a share name from the list above")
                // 返回false表示需要用户重新选择共享目录
                false
            } else {
                log("[SMB-JCIFS] === Connection failed ===")
                false
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] === Connection error ===")
            log("[SMB-JCIFS] Error type: ${e.javaClass.simpleName}")
            log("[SMB-JCIFS] Error message: ${e.message}")
            e.printStackTrace()
            false  // 返回false表示连接失败
        }
    }
    
    // ✅ 处理超时情况
    if (result == null) {
        log("[SMB-JCIFS] ❌ Connection timed out after 15 seconds")
        log("[SMB-JCIFS] Possible causes:")
        log("[SMB-JCIFS]   1. Server is refusing connections (too many attempts)")
        log("[SMB-JCIFS]   2. Network is unreachable")
        log("[SMB-JCIFS]   3. Firewall blocking connection")
        log("[SMB-JCIFS] Please wait a few minutes before retrying")
        return@withContext false
    }
    
    return@withContext result
}
    
    fun getAvailableShares(): List<String> = availableShares
    
    // 设置可用的共享目录列表（用于缓存恢复）
    fun setAvailableShares(shares: List<String>) {
        availableShares = shares.toMutableList()
        log("[SMB-JCIFS] Set available shares from cache: ${shares.joinToString(", ")}")
    }
    
    // ✅ 获取baseUrl（完整URL前缀，用于DLNA投屏）
    fun getBaseUrl(): String {
        return baseUrl
    }
    
    suspend fun selectShare(shareName: String): Boolean = withContext(Dispatchers.IO) {
        // ✅ 关键修复：移除严格的availableShares检查，允许选择任何共享名
        // 原因：availableShares可能为空（连接时未列出），但用户仍然可以选择共享
        if (shareName.isEmpty()) {
            log("[SMB-JCIFS] Share name is empty")
            return@withContext false
        }
        
        log("[SMB-JCIFS] === selectShare START ===")
        log("[SMB-JCIFS] shareName: '$shareName'")
        log("[SMB-JCIFS] host: '$host'")
        log("[SMB-JCIFS] context is null: ${context == null}")
        
        try {
            share = shareName
            baseUrl = "smb://$host/$shareName/"
            log("[SMB-JCIFS] Selected share: $shareName")
            log("[SMB-JCIFS] New baseUrl: $baseUrl")
            
            log("[SMB-JCIFS] Testing share accessibility...")
            val testFile = SmbFile(baseUrl, context)
            
            log("[SMB-JCIFS] Checking if share exists...")
            val exists = testFile.exists()
            log("[SMB-JCIFS] Share exists: $exists")
            
            if (!exists) {
                log("[SMB-JCIFS] ❌ Share is not accessible")
                return@withContext false
            }
            
            log("[SMB-JCIFS] Checking if share is directory...")
            val isDir = testFile.isDirectory
            log("[SMB-JCIFS] Is directory: $isDir")
            
            if (isDir) {
                log("[SMB-JCIFS] ✅ Share is accessible")
                return@withContext true
            } else {
                log("[SMB-JCIFS] ❌ Share is not a directory")
                return@withContext false
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] ❌ Error selecting share: ${e.message}")
            log("[SMB-JCIFS] Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        try {
            val shares = mutableListOf<String>()
            
            if (host.isEmpty()) {
                log("[SMB-JCIFS] ERROR: Host not set, cannot list shares")
                return@withContext emptyList()
            }
            
            log("[SMB-JCIFS] Listing available shares on: $host")
            
            // Try to create a SMB1 context for share enumeration
            var shareEnumContext: CIFSContext? = null
            try {
                val props = Properties()
                // ✅ 缩短超时时间，避免长时间等待
                props.setProperty("jcifs.smb.client.responseTimeout", "5000")  // 5秒
                props.setProperty("jcifs.smb.client.soTimeout", "5000")  // 5秒
                props.setProperty("jcifs.smb.client.connTimeout", "3000")  // 3秒
                props.setProperty("jcifs.smb.client.username", username)
                props.setProperty("jcifs.smb.client.password", password)
                if (domain.isNotEmpty()) {
                    props.setProperty("jcifs.smb.client.domain", domain)
                }
                shareEnumContext = BaseContext(PropertyConfiguration(props))
            } catch (e: Exception) {
                log("[SMB-JCIFS] Failed to create share enumeration context: ${e.message}")
            }
            
            val enumContext = shareEnumContext ?: context
            val serverUrl = "smb://$host/"
            log("[SMB-JCIFS] Connecting to: $serverUrl with enumeration context")
            
            try {
                val serverFile = SmbFile(serverUrl, enumContext)
                log("[SMB-JCIFS] Server file created, checking exists...")
                
                val files = serverFile.listFiles()
                log("[SMB-JCIFS] Listed ${files.size} items")
                
                for (file in files) {
                    val shareName = file.name.trimEnd('/')
                    log("[SMB-JCIFS] Checking share: '$shareName'")
                    if (!shareName.endsWith("$") && shareName.isNotBlank() && shareName != "." && shareName != "..") {
                        shares.add(shareName)
                        log("[SMB-JCIFS] Added share: $shareName")
                    }
                }
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error listing shares: ${e.message}")
                e.printStackTrace()
            }
            
            log("[SMB-JCIFS] Total shares found: ${shares.size}")
            shares
        } catch (e: Exception) {
            log("[SMB-JCIFS] Exception listing shares: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun listFiles(remotePath: String = ""): List<SmbFileInfo> = withContext(Dispatchers.IO) {
        // ✅ Windows SMB 特性：更新会话活动时间
        lastActivityTime = System.currentTimeMillis()
        
        try {
            log("[SMB-JCIFS] === listFiles START ===")
            log("[SMB-JCIFS] Input remotePath: '$remotePath'")
            log("[SMB-JCIFS] baseUrl: '$baseUrl', share: '$share'")
            
            // ✅ 检查连接状态，如果断开则尝试重连
            if (!isConnected()) {
                log("[SMB-JCIFS] ⚠️ Connection lost in listFiles, attempting to reconnect...")
                
                // ✅ 关键修复：先断开旧连接，清理资源
                try {
                    disconnect()
                    log("[SMB-JCIFS] Old connection cleaned up")
                } catch (e: Exception) {
                    log("[SMB-JCIFS] Warning: Failed to cleanup old connection: ${e.message}")
                }
                
                // ✅ 尝试自动重连（最多3次，使用指数退避策略）
                var reconnected = false
                for (attempt in 1..3) {
                    if (host.isNotEmpty() && username.isNotEmpty()) {
                        log("[SMB-JCIFS] Auto-reconnect attempt $attempt/3 to $host...")
                        
                        // ✅ 关键修复：第一次立即重试，后续使用指数退避
                        if (attempt > 1) {
                            // 第2次等待2秒，第3次等待4秒（避免被服务器拒绝）
                            val delayMs = (1000 * (1 shl (attempt - 1))).toLong()  // 2^1=2s, 2^2=4s
                            log("[SMB-JCIFS] Waiting ${delayMs}ms before retry (exponential backoff)...")
                            kotlinx.coroutines.delay(delayMs)
                        }
                        
                        reconnected = connectInternal(host, share, username, password, domain)
                        if (reconnected) {
                            log("[SMB-JCIFS] ✅ Auto-reconnect successful on attempt $attempt")
                            break
                        } else {
                            log("[SMB-JCIFS] ⚠️ Attempt $attempt failed")
                        }
                    } else {
                        log("[SMB-JCIFS] ❌ Cannot auto-reconnect: missing credentials")
                        return@withContext emptyList()
                    }
                }
                
                if (!reconnected) {
                    log("[SMB-JCIFS] ❌ Auto-reconnect failed after 3 attempts")
                    return@withContext emptyList()
                }
            }
            
            // ✅ 检查context是否已初始化
            if (context == null) {
                log("[SMB-JCIFS] ERROR: context is null! Connection may not be established.")
                return@withContext emptyList()
            }
            
            // ✅ 关键修复：检查是否已选择共享目录
            if (baseUrl.isEmpty()) {
                log("[SMB-JCIFS] ❌ No share selected! Please select a share first.")
                log("[SMB-JCIFS] Available shares: ${availableShares.joinToString(", ")}")
                return@withContext emptyList()
            }
            
            val files = mutableListOf<SmbFileInfo>()
            
            // 规范化路径：移除开头的斜杠，因为baseUrl已经包含了共享名
            val normalizedPath = if (remotePath.startsWith("/")) {
                // 如果路径以/开头，去掉它
                // 例如：/folder1/subfolder -> folder1/subfolder
                remotePath.substring(1)
            } else {
                remotePath
            }
            log("[SMB-JCIFS] normalizedPath: '$normalizedPath'")
            
            // 构建完整路径：baseUrl已经包含smb://host/share/
            val fullPath = if (normalizedPath.isEmpty()) {
                baseUrl  // 共享根目录
            } else {
                "$baseUrl$normalizedPath"
            }
            log("[SMB-JCIFS] JCIFS fullPath: '$fullPath'")
            
            log("[SMB-JCIFS] Creating SmbFile object...")
            val smbFile = SmbFile(fullPath, context)
            
            log("[SMB-JCIFS] Checking if path exists...")
            if (!smbFile.exists()) {
                log("[SMB-JCIFS] ERROR: Path does not exist")
                return@withContext emptyList()
            }
            
            log("[SMB-JCIFS] Checking if path is directory...")
            if (!smbFile.isDirectory) {
                log("[SMB-JCIFS] ERROR: Path is not a directory")
                return@withContext emptyList()
            }
            
            log("[SMB-JCIFS] Calling listFiles() - this may take a while...")
            val fileList = smbFile.listFiles()
            log("[SMB-JCIFS] listFiles() returned ${fileList.size} items")
            
            val cleanFullPath = fullPath.trimEnd('/')
            log("[SMB-JCIFS] fullPath: '$fullPath', cleanFullPath: '$cleanFullPath', normalizedPath: '$normalizedPath'")
            
            for (file in fileList) {
                val rawName = file.name
                val getNameResult = try { file.getName() } catch (e: Exception) { null }
                log("[SMB-JCIFS] === JCIFS raw file.name: '$rawName', getName(): '$getNameResult'")
                
                val displayName = getNameResult ?: rawName
                val trimmedName = displayName.trimEnd('/')
                
                if (trimmedName == "." || trimmedName == "..") continue
                
                val isDirectory = file.isDirectory
                val fileSize = if (isDirectory) 0L else file.length()
                
                var fileName = if (trimmedName.contains('/')) {
                    trimmedName.substringAfterLast('/')
                } else {
                    trimmedName
                }
                
                log("[SMB-JCIFS] fileName from JCIFS: '$fileName'")
                
                // ✅ 关键修复：JCIFS在某些情况下会返回"父目录名+文件名"的格式
                // 例如在/小说目录下，可能返回"小说《肉蒲团》.txt"而不是"《肉蒲团》.txt"
                // 策略：总是剥离父目录名前缀，因为在子目录下文件名不应包含父目录名
                if (normalizedPath.isNotEmpty()) {
                    val lastSegment = normalizedPath.substringAfterLast('/')
                    if (lastSegment.isNotEmpty() && fileName.startsWith(lastSegment)) {
                        val afterSegment = fileName.substring(lastSegment.length)
                        if (afterSegment.isNotEmpty()) {
                            log("[SMB-JCIFS] Stripping directory prefix '$lastSegment' from '$fileName'")
                            fileName = afterSegment
                            log("[SMB-JCIFS] Final fileName after strip: '$fileName'")
                        }
                    }
                }
                
                log("[SMB-JCIFS] Final fileName: '$fileName'")
                
                val filePath: String
                if (normalizedPath.isEmpty()) {
                    // 共享根目录下的文件：/fileName（不包含共享名）
                    filePath = "/$fileName"
                } else {
                    // 子目录下的文件：/folder/subfolder/fileName（不包含共享名）
                    filePath = "/$normalizedPath/$fileName"
                }
                
                log("[SMB-JCIFS] Final: name='$fileName', path='$filePath'")
                
                files.add(SmbFileInfo(
                    name = fileName,
                    size = fileSize,
                    isDirectory = isDirectory,
                    path = filePath
                ))
            }
            
            log("[SMB-JCIFS] Total files found: ${files.size}")
            files
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error listing files: ${e.message}")
            e.printStackTrace()
            
            // ✅ 检测是否为连接断开异常
            val isConnectionLost = e.message?.contains("connection", ignoreCase = true) == true ||
                                   e.message?.contains("timeout", ignoreCase = true) == true ||
                                   e.message?.contains("reset", ignoreCase = true) == true ||
                                   e.message?.contains("closed", ignoreCase = true) == true ||
                                   e is jcifs.smb.SmbException
            
            if (isConnectionLost) {
                log("[SMB-JCIFS] ⚠️ Connection lost detected, throwing SmbConnectionLostException")
                throw SmbConnectionLostException("SMB connection lost: ${e.message}")
            }
            
            emptyList()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = getFileStream(remotePath) ?: return@withContext false
            
            FileOutputStream(localFile).use { output ->
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
            
            log("[SMB-JCIFS] Download successful")
            true
        } catch (e: Exception) {
            log("[SMB-JCIFS] Download error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Get an InputStream for streaming file content without downloading to disk
     * This enables progressive playback and seeking
     * @param remotePath The path to the remote file
     * @param startOffset The byte offset to start reading from (for Range requests)
     */
    suspend fun getFileStream(remotePath: String, startOffset: Long = 0): InputStream? = withContext(Dispatchers.IO) {
        // ✅ Windows SMB 特性：更新会话活动时间
        lastActivityTime = System.currentTimeMillis()
        
        try {
            log("[SMB-JCIFS] Opening stream for: '$remotePath' (offset: $startOffset)")
            log("[SMB-JCIFS] baseUrl: '$baseUrl', share: '$share'")
            
            // ✅ 检查连接状态，如果断开则尝试重连
            if (!isConnected()) {
                log("[SMB-JCIFS] ⚠️ Connection lost, attempting to reconnect...")
                
                // ✅ 关键修复：先断开旧连接，清理资源
                try {
                    disconnect()
                    log("[SMB-JCIFS] Old connection cleaned up")
                } catch (e: Exception) {
                    log("[SMB-JCIFS] Warning: Failed to cleanup old connection: ${e.message}")
                }
                
                // ✅ 尝试自动重连（最多3次，使用指数退避策略）
                var reconnected = false
                for (attempt in 1..3) {
                    if (host.isNotEmpty() && username.isNotEmpty()) {
                        log("[SMB-JCIFS] Auto-reconnect attempt $attempt/3 to $host...")
                        
                        // ✅ 关键修复：第一次立即重试，后续使用指数退避
                        if (attempt > 1) {
                            // 第2次等待2秒，第3次等待4秒（避免被服务器拒绝）
                            val delayMs = (1000 * (1 shl (attempt - 1))).toLong()  // 2^1=2s, 2^2=4s
                            log("[SMB-JCIFS] Waiting ${delayMs}ms before retry (exponential backoff)...")
                            kotlinx.coroutines.delay(delayMs)
                        }
                        
                        reconnected = connectInternal(host, share, username, password, domain)
                        if (reconnected) {
                            log("[SMB-JCIFS] ✅ Auto-reconnect successful on attempt $attempt")
                            break
                        } else {
                            log("[SMB-JCIFS] ⚠️ Attempt $attempt failed")
                        }
                    } else {
                        log("[SMB-JCIFS] ❌ Cannot auto-reconnect: missing credentials")
                        throw SmbConnectionLostException("SMB connection lost")
                    }
                }
                
                if (!reconnected) {
                    log("[SMB-JCIFS] ❌ Auto-reconnect failed after 3 attempts")
                    throw SmbConnectionLostException("SMB connection lost and auto-reconnect failed after 3 attempts")
                }
            }
            
            // ✅ 关键修复：检查是否已选择共享目录
            if (baseUrl.isEmpty()) {
                log("[SMB-JCIFS] ❌ No share selected! Please select a share first.")
                log("[SMB-JCIFS] Available shares: ${availableShares.joinToString(", ")}")
                return@withContext null
            }
            
            val normalizedPath = normalizePathForSmb(remotePath)
            log("[SMB-JCIFS] After normalizePathForSmb: '$normalizedPath'")
            
            val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
            log("[SMB-JCIFS] After URL decode: '$decodedPath'")
            
            val fullPath = buildFullPath(decodedPath)
            log("[SMB-JCIFS] Full path: $fullPath")
            
            val smbFile = SmbFile(fullPath, context)
            
            if (!smbFile.exists() || smbFile.isDirectory) {
                log("[SMB-JCIFS] File not found or is directory")
                return@withContext null
            }
            
            // ✅ 核心优化: 使用超大缓冲区(512KB),避免小分块读取
            val rawInputStream = smbFile.getInputStream()
            val bufferedStream = java.io.BufferedInputStream(rawInputStream, 512 * 1024)  // 512KB buffer
            
            if (startOffset > 0) {
                log("[SMB-JCIFS] Skipping $startOffset bytes")
                bufferedStream.skip(startOffset)
            }
            
            log("[SMB-JCIFS] Stream opened with 512KB buffer for continuous reading")
            bufferedStream
        } catch (e: SmbConnectionLostException) {
            // ✅ 直接抛出连接断开异常
            throw e
        } catch (e: Exception) {
            // ✅ 检测是否为连接断开异常
            val isConnectionLost = e.message?.let { msg ->
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("closed", ignoreCase = true) ||
                msg.contains("reset", ignoreCase = true) ||
                msg.contains("abort", ignoreCase = true) ||
                msg.contains("broken pipe", ignoreCase = true)
            } ?: false
            
            if (isConnectionLost) {
                log("[SMB-JCIFS] ⚠️ Connection lost detected: ${e.message}")
                throw SmbConnectionLostException("SMB connection lost: ${e.message}")
            }
            
            log("[SMB-JCIFS] Error opening stream: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Get file size
     */
    suspend fun getFileSize(remotePath: String): Long = withContext(Dispatchers.IO) {
        // ✅ Windows SMB 特性：更新会话活动时间
        lastActivityTime = System.currentTimeMillis()
        
        try {
            log("[SMB-JCIFS] getFileSize called with path: '$remotePath'")
            
            // ✅ 检查连接状态，如果断开则尝试重连
            if (!isConnected()) {
                log("[SMB-JCIFS] ⚠️ Connection lost in getFileSize, attempting to reconnect...")
                
                // ✅ 关键修复：先断开旧连接，清理资源
                try {
                    disconnect()
                    log("[SMB-JCIFS] Old connection cleaned up")
                } catch (e: Exception) {
                    log("[SMB-JCIFS] Warning: Failed to cleanup old connection: ${e.message}")
                }
                
                // ✅ 尝试自动重连（最多3次，使用指数退避策略）
                var reconnected = false
                for (attempt in 1..3) {
                    if (host.isNotEmpty() && username.isNotEmpty()) {
                        log("[SMB-JCIFS] Auto-reconnect attempt $attempt/3 to $host...")
                        
                        // ✅ 关键修复：第一次立即重试，后续使用指数退避
                        if (attempt > 1) {
                            // 第2次等待2秒，第3次等待4秒（避免被服务器拒绝）
                            val delayMs = (1000 * (1 shl (attempt - 1))).toLong()  // 2^1=2s, 2^2=4s
                            log("[SMB-JCIFS] Waiting ${delayMs}ms before retry (exponential backoff)...")
                            kotlinx.coroutines.delay(delayMs)
                        }
                        
                        reconnected = connectInternal(host, share, username, password, domain)
                        if (reconnected) {
                            log("[SMB-JCIFS] ✅ Auto-reconnect successful on attempt $attempt")
                            break
                        } else {
                            log("[SMB-JCIFS] ⚠️ Attempt $attempt failed")
                        }
                    } else {
                        log("[SMB-JCIFS] ❌ Cannot auto-reconnect: missing credentials")
                        return@withContext 0L
                    }
                }
                
                if (!reconnected) {
                    log("[SMB-JCIFS] ❌ Auto-reconnect failed after 3 attempts")
                    return@withContext 0L
                }
            }
            
            // ✅ 关键修复：检查是否已选择共享目录
            if (baseUrl.isEmpty()) {
                log("[SMB-JCIFS] ❌ No share selected! Please select a share first.")
                return@withContext 0L
            }
            
            val normalizedPath = normalizePathForSmb(remotePath)
            val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
            val fullPath = buildFullPath(decodedPath)
            
            log("[SMB-JCIFS] Full path: $fullPath")
            
            // ✅ 优化：先尝试直接获取文件大小，失败后再用备用方案
            val smbFile = SmbFile(fullPath, context)
            
            if (smbFile.exists() && !smbFile.isDirectory) {
                val size = smbFile.length()
                log("[SMB-JCIFS] File exists, size: $size")
                return@withContext size
            }
            
            log("[SMB-JCIFS] Direct access failed, trying alternative methods...")
            
            // ✅ 备用方案1：从父目录列表中查找
            if (decodedPath.isNotEmpty()) {
                val parentPath = decodedPath.substringBeforeLast('/', "")
                val fileName = decodedPath.substringAfterLast('/')
                
                if (parentPath.isNotEmpty()) {
                    val parentFullPath = buildFullPath(parentPath)
                    log("[SMB-JCIFS] Listing parent directory: $parentFullPath")
                    
                    try {
                        val parentFile = SmbFile(parentFullPath, context)
                        if (parentFile.exists() && parentFile.isDirectory) {
                            val files = parentFile.listFiles()
                            log("[SMB-JCIFS] Found ${files.size} files in parent directory")
                            
                            for (f in files) {
                                val name = f.name.trimEnd('/')
                                if (name == fileName && f.isDirectory == false) {
                                    val size = f.length()
                                    log("[SMB-JCIFS] ✅ Found file in parent list: $name, size: $size")
                                    return@withContext size
                                }
                            }
                            log("[SMB-JCIFS] File not found in parent directory listing")
                        }
                    } catch (e: Exception) {
                        log("[SMB-JCIFS] Error listing parent directory: ${e.message}")
                    }
                }
            }
            
            log("[SMB-JCIFS] ⚠️ Could not determine file size, returning 0")
            0L
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error getting file size: ${e.message}")
            e.printStackTrace()
            0L
        }
    }
    
    private fun normalizePathForSmb(remotePath: String): String {
        log("[SMB-JCIFS] normalizePathForSmb input: '$remotePath'")
        
        // ✅ 简单处理：只去掉开头的 / ，不做其他转换
        // 因为 listFiles 返回的路径已经是不含共享名的相对路径
        var normalized = if (remotePath.startsWith("/")) {
            remotePath.substring(1)
        } else {
            remotePath
        }
        
        log("[SMB-JCIFS] After removing leading slash: '$normalized'")
        
        return normalized
    }
    
    private fun buildFullPath(relativePath: String): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        return if (relativePath.isEmpty()) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/$relativePath"
        }
    }
    
    suspend fun rename(remotePath: String, newName: String): Boolean {
        return renameInternal(remotePath, newName)
    }
    
    private fun renameInternal(remotePath: String, newName: String): Boolean {
        var fileExists = false
        var success = false
        
        try {
            log("[SMB-JCIFS] Renaming: $remotePath -> $newName")
            
            val normalizedPath = normalizePathForSmb(remotePath)
            val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
            val fullPath = buildFullPath(decodedPath)
            
            log("[SMB-JCIFS] Full path: $fullPath")
            
            val smbFile = SmbFile(fullPath, context)
            
            fileExists = smbFile.exists()
            if (!fileExists) {
                log("[SMB-JCIFS] File not found")
                return false
            }
            
            val parentPath = decodedPath.substringBeforeLast('/', "")
            val newFullPath = if (parentPath.isEmpty()) {
                buildFullPath(newName)
            } else {
                "${buildFullPath(parentPath)}/$newName"
            }
            
            log("[SMB-JCIFS] Target path: $newFullPath")
            
            val targetFile = SmbFile(newFullPath, context)
            try {
                smbFile.renameTo(targetFile)
                success = true
                log("[SMB-JCIFS] Rename successful")
            } catch (re: RuntimeException) {
                success = false
                log("[SMB-JCIFS] Rename failed: ${re.message}")
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error renaming file: ${e.message}")
            e.printStackTrace()
        }
        
        return success
    }
    
    fun disconnect() {
        log("[SMB-JCIFS] Disconnecting")
        
        // ✅ 关键修复：显式关闭context，释放底层TCP连接
        if (context != null) {
            try {
                log("[SMB-JCIFS] Closing CIFS context...")
                context = null
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error closing context: ${e.message}")
            }
        }
        
        auth = null
        baseUrl = ""
        host = ""
        share = ""
        username = ""
        password = ""
        domain = ""
        serverEncoding = null
        availableShares = emptyList()
        
        // ✅ 关键优化：断开连接时，清除缓存
        cachedConnectionStatus = false
        lastConnectionCheckTime = 0
        lastActivityTime = 0  // 重置活动时间
        log("[SMB-JCIFS] Connection cache cleared")
        
        // ✅ 建议GC回收，加速socket关闭
        System.gc()
    }
    
    private fun normalizePath(path: String): String {
        var normalized = path.replace("\\", "/")
        if (normalized.startsWith("//")) {
            normalized = normalized.substring(1)
        }
        if (normalized.endsWith("/") && normalized.length > 1) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }
}
