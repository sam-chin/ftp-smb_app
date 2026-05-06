package com.example.lanmediaplayer.controller

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.lanmediaplayer.network.FtpClient
import com.example.lanmediaplayer.network.HttpProxyServer
import com.example.lanmediaplayer.network.SmbClient
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream

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
    private var httpProxy: HttpProxyServer? = null
    private var ftpClient: FtpClient? = null
    private var smbClient: SmbClient? = null
    
    // Separate scopes for different operations
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
        httpProxy = HttpProxyServer()
    }
    
    fun getPlayer(): ExoPlayer? = exoPlayer
    
    suspend fun connectToFtp(host: String, port: Int, username: String, password: String): Pair<Boolean, String> {
        return try {
            log("[Controller] === Protocol Switch: Connecting to FTP ===")
            log("[Controller] Before switch - ftpClient: ${if (ftpClient == null) "null" else "exists"}, smbClient: ${if (smbClient == null) "null" else "exists"}")
            
            // Cancel and recreate browse scope to ensure clean state
            log("[Controller] Cancelling old browseScope...")
            browseScope.cancel()
            browseScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            log("[Controller] New browseScope created, isActive: ${browseScope.isActive}")
            
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
    
    suspend fun connectToSmb(host: String, share: String, username: String, password: String, domain: String = ""): Pair<Boolean, String> {
        return try {
            log("[Controller] === Protocol Switch: Connecting to SMB ===")
            log("[Controller] Before switch - ftpClient: ${if (ftpClient == null) "null" else "exists"}, smbClient: ${if (smbClient == null) "null" else "exists"}")
            
            // Cancel and recreate browse scope to ensure clean state
            log("[Controller] Cancelling old browseScope...")
            browseScope.cancel()
            browseScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            log("[Controller] New browseScope created, isActive: ${browseScope.isActive}")
            
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
            
            log("[Controller] === SMB Connection Start ===")
            log("[Controller] Received parameters:")
            log("[Controller]   Host: '$host' (length: ${host.length})")
            log("[Controller]   Share: '$share'")
            log("[Controller]   Username: '$username' (length: ${username.length})")
            log("[Controller]   Password length: ${password.length}")
            log("[Controller]   Domain: '$domain'")
            
            if (username.isEmpty()) {
                log("[Controller] WARNING: Username is empty!")
            }
            if (password.isEmpty()) {
                log("[Controller] WARNING: Password is empty!")
            }
            
            smbClient = SmbClient(logCallback)
            
            // Connect without share first if share is empty
            val connectShare = if (share.isEmpty()) "" else share
            val connected = smbClient?.connect(host, connectShare, username, password, domain) ?: false
            
            if (!connected) {
                log("[Controller] === SMB connection failed ===")
                return Pair(false, "Failed to connect to SMB server (network/auth error)")
            }
            
            log("[Controller] SMB connection successful")
            
            // If no share was specified, list available shares
            if (share.isEmpty()) {
                log("[Controller] No share specified, listing available shares...")
                val shares = smbClient?.listShares() ?: emptyList()
                if (shares.isNotEmpty()) {
                    val sharesList = shares.joinToString(", ")
                    log("[Controller] Available shares: $sharesList")
                    Pair(true, "Connected! Available shares: $sharesList")
                } else {
                    log("[Controller] No shares found")
                    Pair(true, "Connected but no shares found")
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
    
    suspend fun browseFiles(path: String = "/", protocol: NetworkProtocol, callback: MediaCallback) {
        log("[Controller] === BrowseFiles START ===")
        log("[Controller] Path: $path")
        log("[Controller] Protocol: ${protocol::class.simpleName}")
        log("[Controller] ftpClient is null: ${ftpClient == null}")
        log("[Controller] smbClient is null: ${smbClient == null}")
        log("[Controller] BrowseScope isActive: ${browseScope.isActive}")
        log("[Controller] BrowseScope job isCancelled: ${browseScope.coroutineContext[Job]?.isCancelled}")
        
        browseScope.launch {
            log("[Controller] browseFiles coroutine started")
            try {
                val files = when (protocol) {
                    is NetworkProtocol.FTP -> {
                        if (ftpClient == null) {
                            log("[Controller] ERROR: ftpClient is null!")
                            withContext(Dispatchers.Main) {
                                callback.onError("FTP client not initialized")
                            }
                            return@launch
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
                            withContext(Dispatchers.Main) {
                                callback.onError("SMB client not initialized")
                            }
                            return@launch
                        }
                        log("[Controller] Calling smbClient.listFiles($path)")
                        smbClient?.listFiles(path)?.map { smbFile ->
                            log("[Controller] SMB file: ${smbFile.name}, path: ${smbFile.path}")
                            MediaFile(
                                name = smbFile.name,
                                path = smbFile.path,
                                size = smbFile.size,
                                isDirectory = smbFile.isDirectory,
                                protocol = NetworkProtocol.SMB
                            )
                        } ?: emptyList()
                    }
                }
                
                log("[Controller] Loaded ${files.size} files")
                withContext(Dispatchers.Main) {
                    callback.onFilesLoaded(files)
                }
                log("[Controller] === BrowseFiles END ===")
            } catch (e: Exception) {
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
        
        browseScope.launch {
            try {
                log("[Controller] HTTP Proxy port: ${httpProxy?.getPort()}")
                
                if (httpProxy?.getPort() == 0) {
                    log("[Controller] Starting HTTP proxy...")
                    val port = httpProxy?.start(0, object : HttpProxyServer.FileProvider {
                        override suspend fun getFileStream(path: String): InputStream? {
                            log("[Controller] getFileStream called for: $path")
                            return when (mediaFile.protocol) {
                                is NetworkProtocol.FTP -> {
                                    log("[Controller] Streaming via FTP...")
                                    ftpClient?.getFileStream(path)
                                }
                                is NetworkProtocol.SMB -> {
                                    log("[Controller] Streaming via SMB...")
                                    smbClient?.getFileStream(path)
                                }
                            }
                        }
                        
                        override suspend fun getFileSize(path: String): Long {
                            log("[Controller] getFileSize called for: $path")
                            return when (mediaFile.protocol) {
                                is NetworkProtocol.FTP -> {
                                    ftpClient?.getFileSize(path) ?: mediaFile.size
                                }
                                is NetworkProtocol.SMB -> {
                                    smbClient?.getFileSize(path) ?: mediaFile.size
                                }
                            }
                        }
                    }) ?: -1
                    
                    log("[Controller] HTTP Proxy started on port: $port")
                    
                    if (port <= 0) {
                        log("[Controller] ERROR: Failed to start proxy server")
                        withContext(Dispatchers.Main) {
                            callback.onError("Failed to start proxy server")
                        }
                        return@launch
                    }
                }
                
                val proxyUrl = httpProxy?.getUrl(mediaFile.path) ?: ""
                log("[Controller] Proxy URL: $proxyUrl")
                
                withContext(Dispatchers.Main) {
                    // Create a DefaultHttpDataSource.Factory for HTTP streaming
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(10000)
                        .setReadTimeoutMs(10000)
                    
                    // Create media source
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(proxyUrl))
                    
                    log("[Controller] Setting media source...")
                    
                    // Add error listener before setting media source
                    exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            log("[Controller] Player error: ${error.message}")
                            log("[Controller] Error code: ${error.errorCode}")
                            log("[Controller] Error code name: ${error.errorCodeName}")
                            error.printStackTrace()
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
    
    fun release() {
        connectionScope.cancel()
        browseScope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        httpProxy?.stop()
        httpProxy = null
        ftpClient?.disconnect()
        ftpClient = null
        smbClient?.disconnect()
        smbClient = null
    }
}
