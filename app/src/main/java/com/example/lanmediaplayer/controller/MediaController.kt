package com.example.lanmediaplayer.controller

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private fun log(message: String) {
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
            // Disconnect any existing connections
            smbClient?.disconnect()
            smbClient = null
            
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
            // Disconnect any existing connections
            ftpClient?.disconnect()
            ftpClient = null
            
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
        
        scope.launch {
            try {
                val files = when (protocol) {
                    is NetworkProtocol.FTP -> {
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
        scope.launch {
            try {
                if (httpProxy?.getPort() == 0) {
                    val port = httpProxy?.start(0, object : HttpProxyServer.FileProvider {
                        override suspend fun getFileStream(path: String): InputStream? {
                            return when (mediaFile.protocol) {
                                is NetworkProtocol.FTP -> {
                                    val tempFile = File.createTempFile("media_", ".tmp", context.cacheDir)
                                    val success = ftpClient?.downloadFile(path, tempFile) ?: false
                                    if (success) tempFile.inputStream() else null
                                }
                                is NetworkProtocol.SMB -> {
                                    val tempFile = File.createTempFile("media_", ".tmp", context.cacheDir)
                                    val success = smbClient?.downloadFile(path, tempFile) ?: false
                                    if (success) tempFile.inputStream() else null
                                }
                            }
                        }
                        
                        override suspend fun getFileSize(path: String): Long {
                            return mediaFile.size
                        }
                    }) ?: -1
                    
                    if (port <= 0) {
                        withContext(Dispatchers.Main) {
                            callback.onError("Failed to start proxy server")
                        }
                        return@launch
                    }
                }
                
                val proxyUrl = httpProxy?.getUrl(mediaFile.path) ?: return@launch
                val mediaItem = MediaItem.fromUri(proxyUrl)
                
                withContext(Dispatchers.Main) {
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Playback failed")
                }
            }
        }
    }
    
    fun release() {
        scope.cancel()
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
