package com.lanmedia.player.controller

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lanmedia.player.network.FtpClient
import com.lanmedia.player.network.HttpProxyServer
import com.lanmedia.player.network.SmbClient
import com.lanmedia.player.service.DlnaCastingService
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.net.NetworkInterface

sealed class NetworkProtocol {
    object FTP : NetworkProtocol()
    object SMB : NetworkProtocol()
}

data class MediaFile(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val protocol: NetworkProtocol
)

class MediaController(private val context: Context, private val logCallback: ((String) -> Unit)? = null) {
    private var exoPlayer: ExoPlayer? = null
    
    // ✅ 双代理架构：本地预览和DLNA投屏完全隔离
    private var localProxy: HttpProxyServer? = null   // 本地预览专用（127.0.0.1）
    private var dlnaProxy: HttpProxyServer? = null    // DLNA投屏专用（局域网IP）
    
    private var ftpClient: FtpClient? = null
    private var smbClient: SmbClient? = null
    private var currentMediaFile: MediaFile? = null
    private var currentVideoUrl: String = ""
    private var currentSmbShare: String = ""  // ✅ 保存当前SMB共享目录
    private var currentSmbBaseUrl: String = ""  // ✅ 保存SMB baseUrl（完整URL前缀）
    private var localIpAddress: String = "127.0.0.1"  // ✅ 保存局域网IP地址
    
    // ✅ 保存连接参数（用于自动重连）
    private var currentFtpHost: String = ""
    private var currentFtpPort: Int = 21
    private var currentFtpUsername: String = ""
    private var currentFtpPassword: String = ""
    
    private var currentSmbHost: String = ""
    private var currentSmbShareParam: String = ""
    private var currentSmbUsername: String = ""
    private var currentSmbPassword: String = ""
    private var currentSmbDomain: String = ""
    
    // SMB共享目录缓存：key=host, value=shares list
    private val smbSharesCache = mutableMapOf<String, List<String>>()
    private val connectionPrefs = com.lanmedia.player.ConnectionPreferences(context)
    
    // ✅ 跟踪用于HTTP代理的客户端实例（检测是否需要重启代理）
    private var lastFtpClientForProxy: FtpClient? = null
    private var lastSmbClientForProxy: SmbClient? = null
    
    // ✅ HTTP代理重启回调（通知上层清空URL缓存）
    var onProxyRestarted: (() -> Unit)? = null
    
    // ✅ 本地预览图片缓存（LRU，最多10张）
    private val localImageCache = mutableMapOf<String, ByteArray>()
    private val maxLocalCacheSize = 200 * 1024 * 1024  // 200MB
    private var currentLocalCacheSize = 0L
    
    // ✅ 预加载任务管理（防止累积）
    private var isPreloading = false
    
    // ✅ 预加载图片数据到本地缓存（并行加载，最多2个并发）
    suspend fun preloadImageData(imageFiles: List<MediaFile>, startIndex: Int, count: Int) {
        log("[Controller] === Preloading image data to LOCAL cache ===")
        
        val endIndex = minOf(startIndex + count, imageFiles.size)
        if (endIndex <= startIndex) {
            log("[Controller] No images to preload")
            return
        }
        
        log("[Controller] Preloading $count images from index $startIndex")
        
        var successCount = 0
        var failCount = 0
        
        // ✅ FTP 不支持并发，必须串行；SMB 可以并行
        val isFtp = imageFiles.firstOrNull()?.protocol is NetworkProtocol.FTP
        
        if (isFtp) {
            // ✅ FTP 串行加载（避免 dataSocket 冲突）
            log("[Controller] 🔄 FTP mode: sequential loading")
            
            // ✅ 在 IO 线程执行所有网络操作
            withContext(Dispatchers.IO) {
                for (i in startIndex until endIndex) {
                    try {
                        val imageFile = imageFiles[i]
                        val path = imageFile.path
                        
                        if (localImageCache.containsKey(path)) {
                            log("[Controller] ✅ Already cached: $path")
                            successCount++
                            continue
                        }
                        
                        log("[Controller] Preloading image ${i - startIndex + 1}/${endIndex - startIndex}: $path")
                        
                        val ftpPath = if (path.startsWith("/")) path else "/$path"
                        log("[Controller] 📡 FTP getFileStream: $ftpPath")
                        var inputStream: java.io.InputStream? = null
                        try {
                            inputStream = ftpClient?.getFileStream(ftpPath)
                            
                            if (inputStream == null) {
                                log("[Controller] ❌ FTP getFileStream returned null for: $ftpPath")
                                throw Exception("FTP stream is null")
                            }
                            
                            log("[Controller] 📖 Reading data from FTP stream...")
                            val fileData = inputStream.readBytes()
                            log("[Controller] 📊 Read ${fileData.size} bytes")
                            
                            if (fileData.isNotEmpty()) {
                                localImageCache[path] = fileData
                                currentLocalCacheSize += fileData.size
                                successCount++
                                log("[Controller] ✅ Cached: $path (${fileData.size / 1024}KB)")
                            } else {
                                log("[Controller] ⚠️ FTP readBytes returned empty data")
                                throw Exception("FTP readBytes returned empty data")
                            }
                        } finally {
                            inputStream?.close()  // ✅ 立即关闭
                        }
                    } catch (e: Exception) {
                        failCount++
                        // ✅ 详细异常信息
                        val errorMsg = e.message ?: "null message"
                        val errorCause = e.cause?.message ?: "no cause"
                        val errorClass = e.javaClass.simpleName
                        log("[Controller] ⚠️ Failed: [$errorClass] $errorMsg (cause: $errorCause)")
                        e.printStackTrace()
                    }
                }
            }
        } else {
            // ✅ SMB 并行加载（最多2个并发）
            log("[Controller] 🔄 SMB mode: parallel loading (max 2 concurrent)")
            
            // ✅ 使用协程并行加载，最多同时2个请求（平衡速度和SMB负载）
            val maxConcurrent = 2
            val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrent)
        
        // ✅ 使用coroutineScope创建协程作用域
        kotlinx.coroutines.coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            
            for (i in startIndex until endIndex) {
                val job = launch {
                    semaphore.acquire()
                    try {
                        val imageFile = imageFiles[i]
                        val path = imageFile.path
                        
                        // 检查是否已缓存
                        if (localImageCache.containsKey(path)) {
                            log("[Controller] Image already cached: $path")
                            successCount++
                            return@launch
                        }
                        
                        log("[Controller] Preloading image ${i - startIndex + 1}/${endIndex - startIndex} (attempt 1/3): $path")
                        
                        var loaded = false
                        var lastError: Exception? = null
                        
                        // ✅ 重试机制：最多尝试3次
                        for (attempt in 1..3) {
                            try {
                                val fileData = when (imageFile.protocol) {
                                    is NetworkProtocol.FTP -> {
                                        // ✅ FTP 路径处理：确保以 / 开头
                                        val ftpPath = if (path.startsWith("/")) path else "/$path"
                                        
                                        // ✅ 在 IO 线程执行网络操作
                                        withContext(Dispatchers.IO) {
                                            var ftpInputStream: java.io.InputStream? = null
                                            try {
                                                ftpInputStream = ftpClient?.getFileStream(ftpPath)
                                                
                                                if (ftpInputStream == null) {
                                                    log("[Controller] ❌ FTP getFileStream returned null for: $ftpPath")
                                                    throw Exception("FTP stream is null")
                                                }
                                                
                                                log("[Controller] 📖 Reading data from FTP stream...")
                                                val data = ftpInputStream.readBytes()
                                                log("[Controller] 📊 Read ${data.size} bytes")
                                                data
                                            } finally {
                                                ftpInputStream?.close()
                                            }
                                        }
                                    }
                                    is NetworkProtocol.SMB -> {
                                        // ✅ SMB 路径处理：listFiles 返回的路径已经不含共享名
                                        log("[Controller] 📡 SMB getFileStream: $path")
                                        
                                        // ✅ 在 IO 线程执行网络操作
                                        withContext(Dispatchers.IO) {
                                            var smbInputStream: java.io.InputStream? = null
                                            try {
                                                smbInputStream = smbClient?.getFileStream(path)
                                                
                                                if (smbInputStream == null) {
                                                    log("[Controller] ❌ SMB getFileStream returned null for: $path")
                                                    throw Exception("SMB stream is null")
                                                }
                                                
                                                log("[Controller] 📖 Reading data from SMB stream...")
                                                val data = smbInputStream.readBytes()
                                                log("[Controller] 📊 Read ${data.size} bytes")
                                                data
                                            } finally {
                                                smbInputStream?.close()
                                            }
                                        }
                                    }
                                }
                                
                                if (fileData != null && fileData.isNotEmpty()) {
                                    // ✅ 检查缓存大小限制
                                    if (currentLocalCacheSize + fileData.size > maxLocalCacheSize) {
                                        // 清除最旧的缓存（简单策略：清空一半）
                                        val keysToRemove = localImageCache.keys.take(localImageCache.size / 2).toList()
                                        keysToRemove.forEach { key ->
                                            localImageCache.remove(key)?.let { removedData ->
                                                currentLocalCacheSize -= removedData.size
                                            }
                                        }
                                        log("[Controller] Cache full, cleared ${keysToRemove.size} old entries")
                                    }
                                    
                                    // ✅ 存入本地缓存
                                    localImageCache[path] = fileData
                                    currentLocalCacheSize += fileData.size
                                    loaded = true
                                    successCount++
                                    log("[Controller] ✅ Cached image $path (${fileData.size} bytes)")
                                    break
                                }
                            } catch (e: Exception) {
                                lastError = e
                                // ✅ 详细异常信息：message + cause + class name
                                val errorMsg = e.message ?: "null message"
                                val errorCause = e.cause?.message ?: "no cause"
                                val errorClass = e.javaClass.simpleName
                                log("[Controller] ⚠️ Attempt $attempt failed for image ${i - startIndex}: [$errorClass] $errorMsg (cause: $errorCause)")
                                e.printStackTrace()  // ✅ 打印完整堆栈
                                
                                // ✅ 如果是协程取消异常，立即退出
                                if (e is kotlinx.coroutines.CancellationException) {
                                    log("[Controller] Preload cancelled, stopping...")
                                    throw e  // 重新抛出取消异常
                                }
                                
                                if (attempt < 3) {
                                    kotlinx.coroutines.delay(500)  // ✅ 重试间隔500ms，平衡速度和稳定性
                                }
                            }
                        }
                        
                        if (!loaded) {
                            failCount++
                            log("[Controller] ❌ Error preloading image ${i - startIndex}: ${lastError?.message}")
                        }
                    } finally {
                        semaphore.release()
                    }
                }
                jobs.add(job)
            }
            
            // ✅ 等待所有协程完成
            jobs.forEach { it.join() }
        }
        }  // ✅ 结束 else 块（SMB并行加载）
        
        log("[Controller] === Preload Summary: Success=$successCount, Failed=$failCount ===")
    }
    
    // ✅ 获取本地缓存的图片数据
    fun getCachedImageData(path: String): ByteArray? {
        return localImageCache[path]
    }
    
    // ✅ 清空本地缓存
    fun clearLocalImageCache() {
        localImageCache.clear()
        currentLocalCacheSize = 0L
        log("[Controller] Local image cache cleared")
    }
    
    init {
        // 从 ConnectionPreferences 加载缓存的共享目录列表
        loadSmbSharesCache()
    }
    
    private fun loadSmbSharesCache() {
        try {
            val cachedShares = connectionPrefs.getSmbSharesCache()
            if (cachedShares.isNotEmpty()) {
                // 使用主机名作为 key（从保存的连接信息中获取）
                val host = connectionPrefs.getSmbHost()
                if (host.isNotEmpty()) {
                    smbSharesCache[host] = cachedShares
                    log("[Controller] Loaded cached shares from preferences: ${cachedShares.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            log("[Controller] Error loading SMB cache: ${e.message}")
        }
    }
    
    private fun saveSmbSharesCache(host: String, shares: List<String>) {
        try {
            // 更新内存缓存
            smbSharesCache[host] = shares
            // ✅ 保存到 ConnectionPreferences（持久化）
            connectionPrefs.saveSmbSharesCache(shares)
            log("[Controller] Saved cached shares to preferences: ${shares.joinToString(", ")}")
        } catch (e: Exception) {
            log("[Controller] Error saving SMB cache: ${e.message}")
        }
    }
    
    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var browseScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private fun log(message: String) {
        // Use Android Log with UTF-8 support
        Log.i("MediaController", message)
        println(message)
        logCallback?.invoke(message)
    }
    
    interface MediaCallback {
        fun onFilesLoaded(files: List<MediaFile>)
        fun onError(error: String)
        fun onPlaybackStateChanged(state: Int)
    }
    
    fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build()
    }
    
    // ✅ 清理当前连接状态（用于协议切换）
    fun clearConnectionState() {
        log("[Controller] === Clearing connection state ===")
        
        // ✅ 取消并重建browseScope（确保新的浏览操作能正常执行）
        log("[Controller] Cancelling old browseScope...")
        browseScope.cancel()
        browseScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        log("[Controller] New browseScope created")
        
        // 停止HTTP代理
        try {
            localProxy?.stop()
            dlnaProxy?.stop()
            localProxy = null
            dlnaProxy = null
            log("[Controller] HTTP proxy stopped")
        } catch (e: Exception) {
            log("[Controller] Error stopping HTTP proxy: ${e.message}")
        }
        
        // 断开FTP连接
        try {
            ftpClient?.disconnect()
            ftpClient = null
            log("[Controller] FTP disconnected")
        } catch (e: Exception) {
            log("[Controller] Error disconnecting FTP: ${e.message}")
        }
        
        // 断开SMB连接
        try {
            smbClient?.disconnect()
            smbClient = null
            currentSmbShare = ""
            currentSmbBaseUrl = ""
            log("[Controller] SMB disconnected")
        } catch (e: Exception) {
            log("[Controller] Error disconnecting SMB: ${e.message}")
        }
        
        // 清空当前文件信息
        currentMediaFile = null
        currentVideoUrl = ""
        
        // ✅ 清空保存的连接参数（避免自动重连到错误的服务器）
        currentFtpHost = ""
        currentFtpPort = 21
        currentFtpUsername = ""
        currentFtpPassword = ""
        
        currentSmbHost = ""
        currentSmbShareParam = ""
        currentSmbUsername = ""
        currentSmbPassword = ""
        currentSmbDomain = ""
        
        // ✅ 清空SMB状态变量（协议切换时必须重置）
        currentSmbShare = ""
        currentSmbBaseUrl = ""
        
        log("[Controller] Connection state cleared")
    }
    
    fun getPlayer(): ExoPlayer? = exoPlayer
    
    suspend fun connectToFtp(host: String, port: Int, username: String, password: String): Pair<Boolean, String> {
        return try {
            log("[Controller] === Protocol Switch: Connecting to FTP ===")
            log("[Controller] Before switch - ftpClient: ${if (ftpClient == null) "null" else "exists"}, smbClient: ${if (smbClient == null) "null" else "exists"}")
            
            // ✅ 不要cancel browseScope，这会取消正在进行的连接操作
            // browseScope只用于目录浏览，不应该在连接时取消
            
            // Disconnect any existing connections
            if (smbClient != null) {
                log("[Controller] Disconnecting SMB client...")
                smbClient?.disconnect()
                smbClient = null
                log("[Controller] SMB client disconnected and set to null")
            }
            if (ftpClient != null) {
                log("[Controller] Disconnecting old FTP client...")
                ftpClient?.disconnect()
                ftpClient = null
                log("[Controller] Old FTP client disconnected and set to null")
            }
            
            // ✅ 保存连接参数（用于自动重连）
            currentFtpHost = host
            currentFtpPort = port
            currentFtpUsername = username
            currentFtpPassword = password
            
            log("[Controller] === FTP Connection Start ===")
            log("[Controller] Received parameters:")
            log("[Controller]   Host: '$host' (length: ${host.length})")
            log("[Controller]   Port: $port")
            log("[Controller]   Username: '$username' (length: ${username.length})")
            log("[Controller]   Password length: ${password.length}")
            
            if (username.isEmpty()) {
                log("[Controller] WARNING: Username is empty!")
            }
            if (password.isEmpty()) {
                log("[Controller] WARNING: Password is empty!")
            }
            
            ftpClient = FtpClient(logCallback)
            
            val connected = ftpClient?.connect(host, port) ?: false
            if (!connected) {
                log("[Controller] FTP socket connection failed")
                return Pair(false, "Failed to connect to FTP server (network error)")
            }
            
            log("[Controller] FTP socket connected, attempting login...")
            val loggedIn = ftpClient?.login(username, password) ?: false
            if (loggedIn) {
                log("[Controller] === FTP Login successful ===")
                Pair(true, "Success")
            } else {
                log("[Controller] === FTP Login failed ===")
                Pair(false, "Login failed (check username/password)")
            }
        } catch (e: Exception) {
            log("[Controller] === FTP Error ===")
            log("[Controller] Error type: ${e.javaClass.simpleName}")
            log("[Controller] Error message: ${e.message}")
            e.printStackTrace()
            val writer = java.io.PrintWriter(java.io.StringWriter())
            e.printStackTrace(writer)
            log("[Controller] Full stack trace:\n${writer.toString()}")
            Pair(false, "Error: ${e.message}")
        }
    }
    
    suspend fun connectToSmb(host: String, share: String, username: String, password: String, domain: String = "", forceRefresh: Boolean = false): Pair<Boolean, String> {
        return try {
            log("[Controller] === Protocol Switch: Connecting to SMB ===")
            log("[Controller] Before switch - ftpClient: ${if (ftpClient == null) "null" else "exists"}, smbClient: ${if (smbClient == null) "null" else "exists"}")
            
            // ✅ 不要cancel browseScope，这会取消正在进行的连接操作
            // browseScope只用于目录浏览，不应该在连接时取消
            
            // Disconnect any existing connections
            if (ftpClient != null) {
                log("[Controller] Disconnecting FTP client...")
                ftpClient?.disconnect()
                ftpClient = null
                log("[Controller] FTP client disconnected and set to null")
            }
            if (smbClient != null) {
                log("[Controller] Disconnecting old SMB client...")
                smbClient?.disconnect()
                smbClient = null
                log("[Controller] Old SMB client disconnected and set to null")
            }
            
            // ✅ 保存连接参数（用于自动重连）
            currentSmbHost = host
            currentSmbShareParam = share
            currentSmbUsername = username
            currentSmbPassword = password
            currentSmbDomain = domain
            
            log("[Controller] === SMB Connection Start ===")
            log("[Controller] Received parameters:")
            log("[Controller]   Host: '$host' (length: ${host.length})")
            log("[Controller]   Share: '$share'")
            log("[Controller]   Username: '$username' (length: ${username.length})")
            log("[Controller]   Password length: ${password.length}")
            log("[Controller]   Domain: '$domain'")
            log("[Controller]   Force Refresh: $forceRefresh")
            
            if (username.isEmpty()) {
                log("[Controller] WARNING: Username is empty!")
            }
            if (password.isEmpty()) {
                log("[Controller] WARNING: Password is empty!")
            }
            
            smbClient = SmbClient(logCallback)
            
            // 保存当前共享目录（用于DLNA投屏）
            currentSmbShare = share
            log("[Controller] Saved current SMB share: '$share'")
            
            // Connect without share first if share is empty
            val connectShare = if (share.isEmpty()) "" else share
            val connected = smbClient?.connect(host, connectShare, username, password, domain) ?: false
            
            // ✅ 保存SMB baseUrl（从SmbClient获取完整URL前缀）
            currentSmbBaseUrl = smbClient?.getBaseUrl() ?: ""
            log("[Controller] Saved current SMB baseUrl: '$currentSmbBaseUrl'")
            
            if (!connected) {
                log("[Controller] === SMB connection failed ===")
                return Pair(false, "Failed to connect to SMB server (network/auth error)")
            }
            
            log("[Controller] SMB connection successful")
            
            // If no share was specified, list available shares
            if (share.isEmpty()) {
                log("[Controller] No share specified, listing available shares...")
                
                // 检查是否有缓存且不需要强制刷新
                val cachedShares = smbSharesCache[host]
                if (cachedShares != null && !forceRefresh) {
                    log("[Controller] Using cached shares for host: $host")
                    log("[Controller] Cached shares: ${cachedShares.joinToString(", ")}")
                    // 使用缓存的共享列表
                    smbClient?.setAvailableShares(cachedShares)
                    val sharesList = cachedShares.joinToString(", ")
                    Pair(true, "Connected! Available shares: $sharesList (cached)")
                } else {
                    // 重新获取共享列表
                    log("[Controller] Fetching fresh shares list...")
                    val shares = smbClient?.listShares() ?: emptyList()
                    if (shares.isNotEmpty()) {
                        // 更新内存缓存
                        smbSharesCache[host] = shares
                        // ✅ 保存到 SharedPreferences（持久化）
                        saveSmbSharesCache(host, shares)
                        log("[Controller] Cached ${shares.size} shares for host: $host")
                        val sharesList = shares.joinToString(", ")
                        log("[Controller] Available shares: $sharesList")
                        Pair(true, "Connected! Available shares: $sharesList")
                    } else {
                        log("[Controller] No shares found")
                        Pair(true, "Connected but no shares found")
                    }
                }
            } else {
                log("[Controller] === SMB Connection established to share ===")
                Pair(true, "Success")
            }
        } catch (e: Exception) {
            log("[Controller] === SMB Error ===")
            log("[Controller] Error type: ${e.javaClass.simpleName}")
            log("[Controller] Error message: ${e.message}")
            e.printStackTrace()
            val writer = java.io.PrintWriter(java.io.StringWriter())
            e.printStackTrace(writer)
            log("[Controller] Full stack trace:\n${writer.toString()}")
            Pair(false, "Error: ${e.message}")
        }
    }
    
    suspend fun selectShare(shareName: String): Boolean {
        log("[Controller] Selecting share: $shareName")
        return smbClient?.selectShare(shareName) ?: false
    }

    suspend fun getSharesList(): List<MediaFile> {
        val shares = smbClient?.getAvailableShares() ?: emptyList()
        return shares.map { shareName ->
            MediaFile(
                name = shareName,
                path = "/$shareName",
                size = 0,
                isDirectory = true,
                protocol = NetworkProtocol.SMB
            )
        }
    }

    fun getAvailableShares(): List<String> {
        return smbClient?.getAvailableShares() ?: emptyList()
    }
    
    suspend fun browseFiles(path: String = "/", protocol: NetworkProtocol, callback: MediaCallback) {
        log("[Controller] === BrowseFiles START ===")
        log("[Controller] Path: $path")
        log("[Controller] Protocol: ${protocol::class.simpleName}")
        log("[Controller] ftpClient is null: ${ftpClient == null}")
        log("[Controller] smbClient is null: ${smbClient == null}")
        log("[Controller] BrowseScope isActive: ${browseScope.isActive}")
        log("[Controller] BrowseScope job isCancelled: ${browseScope.coroutineContext[Job]?.isCancelled}")
        
        // ✅ 如果browseScope被取消，重新创建
        if (!browseScope.isActive) {
            log("[Controller] ⚠️ BrowseScope is not active, recreating...")
            browseScope.cancel()
            browseScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            log("[Controller] ✅ New browseScope created")
        }
        
        browseScope.launch {
            log("[Controller] browseFiles coroutine started")
            try {
                // ✅ 检查连接状态，如果断开则自动重连
                val connectionValid = when (protocol) {
                    is NetworkProtocol.FTP -> {
                        val connected = ftpClient?.isConnected() == true
                        if (!connected && currentFtpHost.isNotEmpty()) {
                            log("[Controller] ⚠️ FTP connection lost, attempting to reconnect...")
                            val (success, message) = connectToFtp(currentFtpHost, currentFtpPort, currentFtpUsername, currentFtpPassword)
                            if (success) {
                                log("[Controller] ✅ FTP reconnection successful")
                                true
                            } else {
                                log("[Controller] ❌ FTP reconnection failed: $message")
                                false
                            }
                        } else {
                            connected
                        }
                    }
                    is NetworkProtocol.SMB -> {
                        val connected = smbClient?.isConnected() == true
                        if (!connected && currentSmbHost.isNotEmpty()) {
                            log("[Controller] ⚠️ SMB connection lost, attempting to reconnect...")
                            val (success, message) = connectToSmb(currentSmbHost, currentSmbShareParam, currentSmbUsername, currentSmbPassword, currentSmbDomain)
                            if (success) {
                                log("[Controller] ✅ SMB reconnection successful")
                                true
                            } else {
                                log("[Controller] ❌ SMB reconnection failed: $message")
                                false
                            }
                        } else {
                            connected
                        }
                    }
                }
                
                if (!connectionValid) {
                    log("[Controller] ❌ Connection not valid, cannot browse files")
                    withContext(Dispatchers.Main) {
                        callback.onError("Connection lost. Please reconnect.")
                    }
                    return@launch
                }
                
                // ✅ 添加超时保护
                val files = withTimeoutOrNull(30000) {  // 30秒超时
                    try {
                        when (protocol) {
                            is NetworkProtocol.FTP -> {
                                if (ftpClient == null) {
                                    log("[Controller] ERROR: ftpClient is null!")
                                    return@withTimeoutOrNull null
                                }
                                log("[Controller] Calling ftpClient.listFiles($path)")
                                ftpClient?.listFiles(path)?.map { ftpFile ->
                                    // Build proper path: ensure no double slashes
                                    val filePath = if (path == "/") {
                                        "/${ftpFile.name}"
                                    } else if (path.endsWith("/")) {
                                        "$path${ftpFile.name}"
                                    } else {
                                        "$path/${ftpFile.name}"
                                    }
                                    log("[Controller] FTP file: ${ftpFile.name}, path: $filePath")
                                    MediaFile(
                                        name = ftpFile.name,
                                        path = filePath,
                                        size = ftpFile.size,
                                        isDirectory = ftpFile.isDirectory,
                                        protocol = NetworkProtocol.FTP
                                    )
                                } ?: emptyList()
                            }
                            is NetworkProtocol.SMB -> {
                                if (smbClient == null) {
                                    log("[Controller] ERROR: smbClient is null!")
                                    return@withTimeoutOrNull null
                                }
                                log("[Controller] Calling smbClient.listFiles($path)")
                                smbClient?.listFiles(path)?.map { smbFile ->
                                    log("[Controller] SmbFileInfo - name: '${smbFile.name}', path: '${smbFile.path}'")
                                    MediaFile(
                                        name = smbFile.name,
                                        path = smbFile.path,
                                        size = smbFile.size,
                                        isDirectory = smbFile.isDirectory,
                                        protocol = NetworkProtocol.SMB
                                    )
                                }?.also { mediaFiles ->
                                    mediaFiles.forEach { mf ->
                                        log("[Controller] MediaFile created - name: '${mf.name}', path: '${mf.path}'")
                                    }
                                } ?: emptyList()
                            }
                        }
                    } catch (e: com.lanmedia.player.network.FtpConnectionLostException) {
                        // ✅ 捕获FTP连接断开异常，尝试重连
                        log("[Controller] ⚠️ FTP connection lost during browse, attempting reconnect...")
                        
                        if (currentFtpHost.isNotEmpty()) {
                            try {
                                // 断开旧连接
                                ftpClient?.disconnect()
                                ftpClient = null
                                
                                // 创建新客户端并重连
                                ftpClient = com.lanmedia.player.network.FtpClient(logCallback)
                                val reconnected = ftpClient?.connect(currentFtpHost, currentFtpPort) ?: false
                                if (reconnected) {
                                    val loginSuccess = ftpClient?.login(currentFtpUsername, currentFtpPassword) ?: false
                                    if (loginSuccess) {
                                        log("[Controller] ✅ FTP reconnection successful, retrying browse...")
                                        // 重试浏览操作
                                        ftpClient?.listFiles(path)?.map { ftpFile ->
                                            val filePath = if (path == "/") {
                                                "/${ftpFile.name}"
                                            } else if (path.endsWith("/")) {
                                                "$path${ftpFile.name}"
                                            } else {
                                                "$path/${ftpFile.name}"
                                            }
                                            MediaFile(
                                                name = ftpFile.name,
                                                path = filePath,
                                                size = ftpFile.size,
                                                isDirectory = ftpFile.isDirectory,
                                                protocol = NetworkProtocol.FTP
                                            )
                                        } ?: emptyList()
                                    } else {
                                        log("[Controller] ❌ FTP reconnection failed: login error")
                                        null
                                    }
                                } else {
                                    log("[Controller] ❌ FTP reconnection failed: connection error")
                                    null
                                }
                            } catch (reconnectEx: Exception) {
                                log("[Controller] ❌ FTP reconnection exception: ${reconnectEx.message}")
                                reconnectEx.printStackTrace()
                                null
                            }
                        } else {
                            log("[Controller] ❌ FTP connection parameters not saved")
                            null
                        }
                    } catch (e: com.lanmedia.player.network.SmbConnectionLostException) {
                        // ✅ 捕获SMB连接断开异常，尝试重连
                        log("[Controller] ⚠️ SMB connection lost during browse, attempting reconnect...")
                        
                        if (currentSmbHost.isNotEmpty()) {
                            try {
                                // 断开旧连接
                                smbClient?.disconnect()
                                smbClient = null
                                
                                // 创建新客户端并重连
                                smbClient = com.lanmedia.player.network.SmbClient(logCallback)
                                val smbUrl = "smb://${currentSmbHost}/${currentSmbShareParam}"
                                val reconnected = smbClient?.connect(smbUrl, currentSmbUsername, currentSmbPassword, currentSmbDomain)
                                if (reconnected == true) {
                                    log("[Controller] ✅ SMB reconnection successful, retrying browse...")
                                    // 重试浏览操作
                                    smbClient?.listFiles(path)?.map { smbFile ->
                                        MediaFile(
                                            name = smbFile.name,
                                            path = smbFile.path,
                                            size = smbFile.size,
                                            isDirectory = smbFile.isDirectory,
                                            protocol = NetworkProtocol.SMB
                                        )
                                    } ?: emptyList()
                                } else {
                                    log("[Controller] ❌ SMB reconnection failed")
                                    null
                                }
                            } catch (reconnectEx: Exception) {
                                log("[Controller] ❌ SMB reconnection exception: ${reconnectEx.message}")
                                reconnectEx.printStackTrace()
                                null
                            }
                        } else {
                            log("[Controller] ❌ SMB connection parameters not saved")
                            null
                        }
                    } catch (e: Exception) {
                        log("[Controller] Error in withTimeoutOrNull: ${e.message}")
                        null
                    }
                }  // withTimeoutOrNull end
                
                if (files == null) {
                    log("[Controller] ERROR: browseFiles timed out after 30 seconds!")
                    withContext(Dispatchers.Main) {
                        callback.onError("Operation timed out. Please check network connection.")
                    }
                } else {
                    log("[Controller] Loaded ${files.size} files")
                    withContext(Dispatchers.Main) {
                        callback.onFilesLoaded(files)
                    }
                    log("[Controller] === BrowseFiles END ===")
                }
            } catch (e: Exception) {
                log("[Controller] ERROR in browseFiles: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    fun playMedia(mediaFile: MediaFile, callback: MediaCallback) {
        log("[Controller] === PlayMedia START ===")
        log("[Controller] File: ${mediaFile.name}")
        log("[Controller] Path: ${mediaFile.path}")
        log("[Controller] Protocol: ${mediaFile.protocol::class.simpleName}")
        log("[Controller] Size: ${mediaFile.size}")
        
        // ✅ 检查FTP/SMB连接是否仍然有效，如果断开则自动重连
        browseScope.launch {
            try {
                when (mediaFile.protocol) {
                    is NetworkProtocol.FTP -> {
                        if (ftpClient?.isConnected() != true) {
                            log("[Controller] ⚠️ FTP connection lost, attempting auto-reconnect...")
                            
                            if (currentFtpHost.isNotEmpty()) {
                                try {
                                    // ✅ 先断开旧连接（清理失效的socket）
                                    ftpClient?.disconnect()
                                    ftpClient = null
                                    
                                    // 创建新的FTP客户端
                                    ftpClient = FtpClient(logCallback)
                                    
                                    val reconnected = ftpClient?.connect(currentFtpHost, currentFtpPort) ?: false
                                    if (reconnected) {
                                        val loginSuccess = ftpClient?.login(currentFtpUsername, currentFtpPassword) ?: false
                                        if (loginSuccess) {
                                            log("[Controller] ✅ FTP auto-reconnect successful")
                                        } else {
                                            log("[Controller] ❌ FTP auto-reconnect failed: login error")
                                            withContext(Dispatchers.Main) {
                                                callback.onError("FTP reconnection failed. Please reconnect manually.")
                                            }
                                            return@launch
                                        }
                                    } else {
                                        log("[Controller] ❌ FTP auto-reconnect failed: connection error")
                                        withContext(Dispatchers.Main) {
                                            callback.onError("FTP reconnection failed. Please reconnect manually.")
                                        }
                                        return@launch
                                    }
                                } catch (e: Exception) {
                                    log("[Controller] ❌ FTP auto-reconnect exception: ${e.message}")
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        callback.onError("FTP reconnection failed: ${e.message}")
                                    }
                                    return@launch
                                }
                            } else {
                                log("[Controller] ❌ FTP connection parameters not saved")
                                withContext(Dispatchers.Main) {
                                    callback.onError("FTP connection lost. Please reconnect manually.")
                                }
                                return@launch
                            }
                        }
                    }
                    is NetworkProtocol.SMB -> {
                        if (smbClient?.isConnected() != true) {
                            log("[Controller] ⚠️ SMB connection lost, attempting auto-reconnect...")
                            
                            if (currentSmbHost.isNotEmpty()) {
                                try {
                                    // ✅ 先断开旧连接（清理失效的context）
                                    smbClient?.disconnect()
                                    smbClient = null
                                    
                                    // 创建新的SMB客户端
                                    smbClient = SmbClient(logCallback)
                                    
                                    val smbUrl = "smb://${currentSmbHost}/${currentSmbShareParam}"
                                    val reconnected = smbClient?.connect(smbUrl, currentSmbUsername, currentSmbPassword, currentSmbDomain)
                                    if (reconnected == true) {
                                        log("[Controller] ✅ SMB auto-reconnect successful")
                                    } else {
                                        log("[Controller] ❌ SMB auto-reconnect failed")
                                        withContext(Dispatchers.Main) {
                                            callback.onError("SMB reconnection failed. Please reconnect manually.")
                                        }
                                        return@launch
                                    }
                                } catch (e: Exception) {
                                    log("[Controller] ❌ SMB auto-reconnect exception: ${e.message}")
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        callback.onError("SMB reconnection failed: ${e.message}")
                                    }
                                    return@launch
                                }
                            } else {
                                log("[Controller] ❌ SMB connection parameters not saved")
                                withContext(Dispatchers.Main) {
                                    callback.onError("SMB connection lost. Please reconnect manually.")
                                }
                                return@launch
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("[Controller] Connection check error: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
                return@launch
            }
        }
        
        val extension = mediaFile.name.substringAfterLast('.', "").lowercase()
        val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        
        if (isImage) {
            log("[Controller] WARNING: ExoPlayer does not support image files.")
        }
        
        browseScope.launch {
            try {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                
                currentMediaFile = mediaFile
                
                // ✅ 使用本地代理
                ensureLocalProxy()
                
                val proxyUrl = localProxy?.getUrl(mediaFile.path) ?: ""
                currentVideoUrl = proxyUrl
                log("[Controller] Proxy URL: $proxyUrl")
                log("[Controller] Media file path: ${mediaFile.path}")
                log("[Controller] Media file name: ${mediaFile.name}")
                
                withContext(Dispatchers.Main) {
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(10000)
                        .setReadTimeoutMs(10000)
                    
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(proxyUrl))
                    
                    log("[Controller] Setting media source...")
                    
                    exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            log("[Controller] Player error: ${error.message}")
                            log("[Controller] Error code: ${error.errorCode}")
                        }
                        
                        override fun onPlaybackStateChanged(state: Int) {
                            val stateStr = when (state) {
                                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                                androidx.media3.common.Player.STATE_READY -> "READY"
                                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                            log("[Controller] Playback state: $stateStr")
                        }
                    })
                    
                    exoPlayer?.setMediaSource(mediaSource)
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    log("[Controller] Player started")
                }
                
                log("[Controller] === PlayMedia END ===")
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Playback failed")
                }
            }
        }
    }
    
    fun stopPlayback() {
        log("[Controller] Stopping playback...")
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        localProxy?.stop()
        localProxy = null
        log("[Controller] Playback stopped")
    }
    
    // ✅ 获取本地预览URL（127.0.0.1）
    fun getLocalImageUrl(path: String): String {
        ensureLocalProxy()
        return localProxy!!.getUrl(path)
    }
    
    // ✅ 获取DLNA投屏URL（局域网IP）
    suspend fun getDlnaImageUrl(imageFile: MediaFile, callback: MediaCallback): String? {
        // 确保连接有效
        if (!ensureConnection(imageFile.protocol)) {
            log("❌ Connection not available for DLNA")
            callback.onError("Connection lost. Please reconnect.")
            return null
        }
        
        // 启动 DLNA 代理
        ensureDlnaProxy()
        return dlnaProxy?.getUrl(imageFile.path)
    }
    
    fun getVideoUrl(): String {
        // ✅ 本地播放模式：直接返回currentVideoUrl（已经是127.0.0.1）
        log("[Controller] getVideoUrl: $currentVideoUrl")
        return currentVideoUrl
    }
    
    // ✅ 启动DLNA前台服务（保持后台运行）
    fun startDlnaService(fileName: String) {
        try {
            val serviceIntent = Intent(context, DlnaCastingService::class.java).apply {
                action = DlnaCastingService.ACTION_START_CASTING
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                log("[Controller] ✅ DLNA foreground service started")
            } else {
                context.startService(serviceIntent)
                log("[Controller] ✅ DLNA service started")
            }
            
            // 更新通知内容
            DlnaCastingService.getInstance()?.updateNotification("正在投屏: $fileName")
        } catch (e: Exception) {
            log("[Controller] ⚠️ Failed to start DLNA service: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // ✅ 停止DLNA前台服务
    fun stopDlnaService() {
        try {
            val serviceIntent = Intent(context, DlnaCastingService::class.java).apply {
                action = DlnaCastingService.ACTION_STOP_CASTING
            }
            context.startService(serviceIntent)
            log("[Controller] ✅ DLNA service stopped")
        } catch (e: Exception) {
            log("[Controller] ⚠️ Failed to stop DLNA service: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // ✅ 关键修复：停止投屏后切换HTTP代理回本地模式
    
    // 获取当前媒体的真实路径（用于DLNA投屏）
    fun getCurrentMediaPath(): String? {
        return currentMediaFile?.path
    }
    
    // 获取当前协议
    fun getCurrentProtocol(): NetworkProtocol? {
        return currentMediaFile?.protocol
    }
    
    // 获取当前SMB共享目录（用于DLNA投屏）
    fun getCurrentSmbShare(): String {
        return currentSmbShare
    }
    
    // ✅ 获取完整的SMB URL（用于DLNA投屏）
    fun getSmbFullUrl(relativePath: String, username: String, password: String): String {
        if (currentSmbBaseUrl.isEmpty()) {
            // 如果baseUrl为空，使用旧方法拼接
            val cleanPath = if (relativePath.startsWith("/")) relativePath.substring(1) else relativePath
            val fullPath = if (currentSmbShare.isNotEmpty()) {
                "$currentSmbShare/$cleanPath"
            } else {
                cleanPath
            }
            val host = connectionPrefs.getSmbHost()
            val port = connectionPrefs.getSmbPort()
            return "smb://$username:$password@$host:$port/$fullPath"
        } else {
            // ✅ 使用baseUrl构建完整URL
            // baseUrl格式: smb://host/share/
            // relativePath格式: /folder/file.mp4
            val cleanPath = if (relativePath.startsWith("/")) relativePath.substring(1) else relativePath
            // 替换baseUrl中的主机部分，添加用户名密码
            val urlWithoutProtocol = currentSmbBaseUrl.substringAfter("://")
            val hostAndPath = urlWithoutProtocol.split("/", limit = 2)
            if (hostAndPath.size >= 2) {
                val hostPart = hostAndPath[0]  // host:port
                val pathPart = hostAndPath[1]  // share/
                return "smb://$username:$password@$hostPart/$pathPart$cleanPath"
            } else {
                //  fallback
                return "smb://$username:$password@${currentSmbBaseUrl.substringAfter("://")}$cleanPath"
            }
        }
    }
    
    fun release() {
        connectionScope.cancel()
        browseScope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        releaseAll()
    }

    suspend fun renameFile(path: String, newName: String, protocol: NetworkProtocol): Boolean {
        return when (protocol) {
            is NetworkProtocol.FTP -> ftpClient?.rename(path, newName) ?: false
            is NetworkProtocol.SMB -> smbClient?.rename(path, newName) ?: false
        }
    }

    suspend fun getFileStream(path: String, protocol: NetworkProtocol): InputStream? {
        return when (protocol) {
            is NetworkProtocol.FTP -> {
                // ✅ FTP 路径处理：确保以 / 开头
                val ftpPath = if (path.startsWith("/")) path else "/$path"
                log("[Controller] 📡 FTP getFileStream: $ftpPath")
                ftpClient?.getFileStream(ftpPath, 0)
            }
            is NetworkProtocol.SMB -> smbClient?.getFileStream(path, 0)
        }
    }
    
    // ✅ 获取手机的局域网IP地址
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // 查找IPv4地址且不是回环地址
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        log("[Controller] Found local IP: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            log("[Controller] Error getting local IP: ${e.message}")
            e.printStackTrace()
        }
        return "127.0.0.1"  // fallback
    }
    
    // ==================== 双代理管理 ====================
    
    /**
     * 确保本地代理运行
     */
    private fun ensureLocalProxy() {
        if (localProxy == null) {
            localProxy = HttpProxyServer(logCallback, allowExternalConnections = false)
            localProxy?.externalImageCacheProvider = { localImageCache }
            
            val port = localProxy?.start(8080, createFileProvider()) ?: -1
            if (port > 0) {
                log("[Controller] ✅ Local proxy started on port $port")
            }
        }
    }
    
    /**
     * 确保DLNA代理运行
     */
    private suspend fun ensureDlnaProxy() {
        if (dlnaProxy == null) {
            dlnaProxy = HttpProxyServer(logCallback, allowExternalConnections = true)
            dlnaProxy?.externalImageCacheProvider = { localImageCache }  // 共享只读
            
            val port = dlnaProxy?.start(8081, createFileProvider()) ?: -1
            if (port > 0) {
                log("[Controller] ✅ DLNA proxy started on port $port")
            }
        }
    }
    
    /**
     * 停止投屏（不影响本地预览）
     */
    fun stopCasting() {
        dlnaProxy?.stop()
        dlnaProxy = null
        log("[Controller] 🛑 DLNA proxy stopped, local preview unaffected")
    }
    
    /**
     * 释放所有资源
     */
    fun releaseAll() {
        // ✅ 重置预加载状态
        isPreloading = false
        
        localProxy?.stop()
        dlnaProxy?.stop()
        localProxy = null
        dlnaProxy = null
        
        ftpClient?.disconnect()
        smbClient?.disconnect()
        
        localImageCache.clear()
        log("[Controller] 🗑️ All resources released")
    }
    
    // ==================== 智能预加载 ====================
    
    /**
     * 智能预加载：当前图片 ±2 张
     */
    suspend fun smartPreload(currentIndex: Int, allImages: List<MediaFile>) {
        if (allImages.isEmpty()) return
        
        // ✅ 如果正在预加载，直接返回（防止重复调用）
        if (isPreloading) {
            log("[Controller] ⚠️ Preload already in progress, skipping")
            return
        }
        
        val start = maxOf(0, currentIndex - 2)
        val end = minOf(allImages.size - 1, currentIndex + 2)
        
        log("[Controller] 🔄 Smart preload: indices $start to $end (current: $currentIndex)")
        
        isPreloading = true
        try {
            coroutineScope {
                val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                
                // ✅ 启动所有预加载任务并等待完成
                val jobs = (start..end).map { index ->
                    async {
                        semaphore.acquire()
                        try {
                            preloadSingleImage(allImages[index])
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                
                // ✅ 等待所有任务完成
                jobs.forEach { it.await() }
            }
        } finally {
            isPreloading = false
        }
    }
    
    /**
     * 预加载单张图片
     */
    private suspend fun preloadSingleImage(imageFile: MediaFile) {
        val path = imageFile.path
        
        if (localImageCache.containsKey(path)) {
            log("[Controller] ✅ Already cached: $path")
            return
        }
        
        var inputStream: java.io.InputStream? = null
        try {
            // ✅ 获取文件流
            inputStream = when (imageFile.protocol) {
                is NetworkProtocol.FTP -> {
                    // ✅ FTP 路径处理：确保以 / 开头
                    val ftpPath = if (path.startsWith("/")) path else "/$path"
                    log("[Controller] 📡 FTP preload: $ftpPath")
                    ftpClient?.getFileStream(ftpPath)
                }
                is NetworkProtocol.SMB -> {
                    // ✅ SMB 路径处理：listFiles 返回的路径已经不含共享名
                    log("[Controller] 📡 SMB preload: $path")
                    smbClient?.getFileStream(path)
                }
            }
            
            if (inputStream != null) {
                // ✅ 读取数据
                val fileData = inputStream.readBytes()
                
                if (fileData.isNotEmpty()) {
                    // ✅ LRU 缓存管理（最多10张）
                    while (localImageCache.size >= 10) {
                        val oldestKey = localImageCache.keys.first()
                        localImageCache.remove(oldestKey)
                        log("[Controller] 🗑️ Evicted oldest: $oldestKey")
                    }
                    
                    localImageCache[path] = fileData
                    log("[Controller] 📦 Cached: $path (${fileData.size / 1024}KB)")
                }
            }
        } catch (e: Exception) {
            log("[Controller] ⚠️ Preload failed: ${e.message}")
            e.printStackTrace()
        } finally {
            // ✅ 确保关闭 InputStream，防止资源泄漏
            try {
                inputStream?.close()
            } catch (e: Exception) {
                log("[Controller] ⚠️ Error closing stream: ${e.message}")
            }
        }
    }
    
    // ==================== 统一重连逻辑 ====================
    
    /**
     * 确保连接有效（自动重连）
     */
    private suspend fun ensureConnection(protocol: NetworkProtocol): Boolean {
        return when (protocol) {
            is NetworkProtocol.FTP -> ensureFtpConnection()
            is NetworkProtocol.SMB -> ensureSmbConnection()
        }
    }
    
    /**
     * 确保FTP连接有效
     */
    private suspend fun ensureFtpConnection(): Boolean {
        if (ftpClient?.isConnected() == true) return true
        
        if (currentFtpHost.isEmpty()) {
            log("[Controller] ❌ FTP config not saved")
            return false
        }
        
        log("[Controller] 🔄 FTP reconnecting...")
        
        try {
            ftpClient?.disconnect()
            ftpClient = FtpClient(logCallback)
            
            val connected = ftpClient?.connect(currentFtpHost, currentFtpPort) ?: false
            if (!connected) return false
            
            val loggedIn = ftpClient?.login(currentFtpUsername, currentFtpPassword) ?: false
            if (loggedIn) {
                log("[Controller] ✅ FTP reconnected")
                return true
            }
        } catch (e: Exception) {
            log("[Controller] ❌ FTP reconnect failed: ${e.message}")
        }
        
        return false
    }
    
    /**
     * 确保SMB连接有效
     */
    private suspend fun ensureSmbConnection(): Boolean {
        if (smbClient?.isConnected() == true) return true
        
        if (currentSmbHost.isEmpty()) {
            log("[Controller] ❌ SMB config not saved")
            return false
        }
        
        log("[Controller] 🔄 SMB reconnecting...")
        
        try {
            smbClient?.disconnect()
            smbClient = SmbClient(logCallback)
            
            val smbUrl = "smb://${currentSmbHost}/${currentSmbShareParam}"
            val connected = smbClient?.connect(smbUrl, currentSmbUsername, currentSmbPassword, currentSmbDomain)
            
            if (connected == true) {
                log("[Controller] ✅ SMB reconnected")
                return true
            }
        } catch (e: Exception) {
            log("[Controller] ❌ SMB reconnect failed: ${e.message}")
        }
        
        return false
    }
    
    // ==================== FileProvider 创建 ====================
    
    /**
     * 创建统一的 FileProvider
     */
    private fun createFileProvider(): HttpProxyServer.FileProvider {
        return object : HttpProxyServer.FileProvider {
            override suspend fun getFileStream(path: String, startOffset: Long): InputStream? {
                return when (currentMediaFile?.protocol) {
                    is NetworkProtocol.FTP -> {
                        // ✅ FTP 路径处理：确保以 / 开头
                        val ftpPath = if (path.startsWith("/")) path else "/$path"
                        log("[Controller] 📡 FTP getFileStream: $ftpPath")
                        ftpClient?.getFileStream(ftpPath, startOffset)
                    }
                    is NetworkProtocol.SMB -> {
                        // ✅ SMB 路径处理：listFiles 返回的路径已经不含共享名
                        // 直接使用，不需要额外处理
                        log("[Controller] 📡 SMB getFileStream: $path")
                        smbClient?.getFileStream(path, startOffset)
                    }
                    else -> null
                }
            }
            
            override suspend fun getFileSize(path: String): Long {
                return when (currentMediaFile?.protocol) {
                    is NetworkProtocol.FTP -> {
                        // ✅ FTP 路径处理：确保以 / 开头
                        val ftpPath = if (path.startsWith("/")) path else "/$path"
                        log("[Controller] 📡 FTP getFileSize: $ftpPath")
                        ftpClient?.getFileSize(ftpPath) ?: 0L
                    }
                    is NetworkProtocol.SMB -> {
                        // ✅ SMB 路径处理：listFiles 返回的路径已经不含共享名
                        log("[Controller] 📡 SMB getFileSize: $path")
                        smbClient?.getFileSize(path) ?: 0L
                    }
                    else -> 0L
                }
            }
        }
    }
}
