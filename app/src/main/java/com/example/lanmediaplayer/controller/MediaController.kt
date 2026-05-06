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

class MediaController(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private var httpProxy: HttpProxyServer? = null
    private var ftpClient: FtpClient? = null
    private var smbClient: SmbClient? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
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
    
    suspend fun connectToFtp(host: String, port: Int, username: String, password: String): Boolean {
        return try {
            ftpClient = FtpClient()
            val connected = ftpClient?.connect(host, port) ?: false
            if (connected) {
                ftpClient?.login(username, password) ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun connectToSmb(host: String, share: String, username: String, password: String, domain: String = ""): Boolean {
        return try {
            smbClient = SmbClient()
            smbClient?.connect(host, share, username, password, domain) ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun browseFiles(path: String = "/", protocol: NetworkProtocol, callback: MediaCallback) {
        scope.launch {
            try {
                val files = when (protocol) {
                    is NetworkProtocol.FTP -> {
                        ftpClient?.listFiles(path)?.map { ftpFile ->
                            MediaFile(
                                name = ftpFile.name,
                                path = if (path.endsWith("/")) "$path${ftpFile.name}" else "$path/${ftpFile.name}",
                                size = ftpFile.size,
                                isDirectory = ftpFile.isDirectory,
                                protocol = NetworkProtocol.FTP
                            )
                        } ?: emptyList()
                    }
                    is NetworkProtocol.SMB -> {
                        smbClient?.listFiles(path)?.map { smbFile ->
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
                
                withContext(Dispatchers.Main) {
                    callback.onFilesLoaded(files)
                }
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
