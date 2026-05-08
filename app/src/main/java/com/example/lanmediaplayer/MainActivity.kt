@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.lanmediaplayer

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.example.lanmediaplayer.controller.CastController
import com.example.lanmediaplayer.controller.CastDevice
import com.example.lanmediaplayer.controller.MediaController
import com.example.lanmediaplayer.controller.MediaFile
import com.example.lanmediaplayer.controller.NetworkProtocol
import com.example.lanmediaplayer.ui.theme.LanMediaPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaController: MediaController
    private lateinit var castController: CastController
    private lateinit var connectionPrefs: ConnectionPreferences
    private val debugLogs = mutableListOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create log callback function
        val logCallback: (String) -> Unit = { message ->
            runOnUiThread {
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                debugLogs.add("$timestamp - $message")
                // Keep only last 100 logs to avoid memory issues
                if (debugLogs.size > 100) {
                    debugLogs.removeAt(0)
                }
            }
        }
        
        mediaController = MediaController(this, logCallback)
        mediaController.initializePlayer()
        castController = CastController(this)
        connectionPrefs = ConnectionPreferences(this)
        
        setContent {
            LanMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        mediaController = mediaController,
                        castController = castController,
                        connectionPrefs = connectionPrefs,
                        getDebugLogs = { debugLogs.toList() },
                        onDownloadComplete = { path ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Downloaded: $path", Toast.LENGTH_LONG).show()
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaController.release()
        castController.release()
    }
}

@Composable
fun MainScreen(
    mediaController: MediaController,
    castController: CastController,
    connectionPrefs: ConnectionPreferences,
    getDebugLogs: () -> List<String>,
    onDownloadComplete: (String) -> Unit,
    onError: (String) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.Connection) }
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var currentPath by remember { mutableStateOf("/") }
    var selectedProtocol by remember { mutableStateOf<NetworkProtocol>(NetworkProtocol.FTP) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    var availableShares by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAtSmbRoot by remember { mutableStateOf(false) }
    var browserTitle by remember { mutableStateOf("/") }
    
    var imageFiles by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var initialImageIndex by remember { mutableStateOf(0) }
    
    var selectedFile by remember { mutableStateOf<MediaFile?>(null) }
    var showFileMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showLoadingDialog by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    // Use logs from Activity instead of local state
    var debugLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Update logs periodically
    LaunchedEffect(Unit) {
        while (true) {
            debugLogs = getDebugLogs()
            kotlinx.coroutines.delay(500)
        }
    }
    
    fun addLog(message: String) {
        debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
    }
    
    suspend fun downloadFile(file: MediaFile) {
        addLog("Starting download: ${file.name}")
        try {
            val fileName = file.name
            val downloadDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            val localFile = java.io.File(downloadDir, fileName)
            
            val inputStream = mediaController.getFileStream(file.path, selectedProtocol)
            
            if (inputStream == null) {
                onError("Failed to get file stream")
                return
            }
            
            java.io.FileOutputStream(localFile).use { output ->
                inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            
            addLog("Download complete: ${localFile.absolutePath}")
            onDownloadComplete(localFile.absolutePath)
        } catch (e: Exception) {
            addLog("Download error: ${e.message}")
            onError("Download failed: ${e.message}")
        }
    }
    
    suspend fun renameFile(file: MediaFile, newName: String) {
        addLog("Renaming: ${file.name} -> $newName")
        try {
            val success = mediaController.renameFile(file.path, newName, selectedProtocol)
            
            if (success) {
                addLog("Rename successful")
                val parentPath = file.path.substringBeforeLast("/", "")
                val newPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
                val index = files.indexOfFirst { it.path == file.path }
                if (index >= 0) {
                    val updatedFile = MediaFile(
                        name = newName,
                        path = newPath,
                        size = file.size,
                        isDirectory = file.isDirectory,
                        protocol = file.protocol
                    )
                    files = files.toMutableList().apply {
                        set(index, updatedFile)
                    }
                }
            } else {
                addLog("Rename failed")
                onError("Rename failed")
            }
        } catch (e: Exception) {
            addLog("Rename error: ${e.message}")
            onError("Rename error: ${e.message}")
        }
    }
    
    fun openWithSystemApp(file: MediaFile) {
        addLog("Opening with system app: ${file.name}")
        try {
            coroutineScope.launch {
                // 使用外部存储目录，让文件持久化保存
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir == null) {
                    onError("External storage not available")
                    return@launch
                }
                
                // 创建子目录存放下载的文件
                val downloadDir = java.io.File(externalDir, "downloads")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                
                val destFile = java.io.File(downloadDir, file.name)
                
                // 如果文件已存在，先删除
                if (destFile.exists()) {
                    destFile.delete()
                }
                
                addLog("Downloading file to: ${destFile.absolutePath}")
                
                val inputStream = mediaController.getFileStream(file.path, selectedProtocol)
                
                if (inputStream == null) {
                    onError("Failed to get file stream")
                    return@launch
                }
                
                // 下载文件到外部存储
                java.io.FileOutputStream(destFile).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                
                addLog("File downloaded successfully: ${destFile.absolutePath}")
                addLog("File size: ${destFile.length()} bytes")
                
                val mimeType = getMimeType(file.name)
                
                // 使用 FileProvider 获取 content:// URI（兼容 Android 7.0+）
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destFile
                )
                
                addLog("Content URI: $contentUri")
                
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                try {
                    context.startActivity(intent)
                    addLog("System app launched successfully")
                } catch (e: Exception) {
                    addLog("No app found to handle this file type: ${e.message}")
                    onError("No app found to open this file type")
                }
            }
        } catch (e: Exception) {
            addLog("Open error: ${e.message}")
            e.printStackTrace()
            onError("Failed to open file: ${e.message}")
        }
    }
    
    // Load saved connection info separately for FTP and SMB
    val savedFtpHost = remember { connectionPrefs.getFtpHost() }
    val savedFtpPort = remember { connectionPrefs.getFtpPort() }
    val savedFtpUsername = remember { connectionPrefs.getFtpUsername() }
    val savedFtpPassword = remember { connectionPrefs.getFtpPassword() }
    
    val savedSmbHost = remember { connectionPrefs.getSmbHost() }
    val savedSmbPort = remember { connectionPrefs.getSmbPort() }
    val savedSmbUsername = remember { connectionPrefs.getSmbUsername() }
    val savedSmbPassword = remember { connectionPrefs.getSmbPassword() }
    val savedSmbShare = remember { connectionPrefs.getSmbShare() }
    val savedSmbDomain = remember { connectionPrefs.getSmbDomain() }
    
    when (currentScreen) {
        Screen.Connection -> ConnectionScreen(
            selectedProtocol = selectedProtocol,
            savedFtpHost = savedFtpHost,
            savedFtpPort = savedFtpPort,
            savedFtpUsername = savedFtpUsername,
            savedFtpPassword = savedFtpPassword,
            savedSmbHost = savedSmbHost,
            savedSmbPort = savedSmbPort,
            savedSmbUsername = savedSmbUsername,
            savedSmbPassword = savedSmbPassword,
            savedSmbShare = savedSmbShare,
            savedSmbDomain = savedSmbDomain,
            debugLogs = debugLogs,
            onConnect = { protocol, host, port, username, password, share, domain ->
                selectedProtocol = protocol
                isLoading = true
                errorMessage = null
                isAtSmbRoot = false
                // Don't clear debug logs, keep them for troubleshooting
                
                // Log connection parameters (hide password)
                when (protocol) {
                    is NetworkProtocol.FTP -> {
                        addLog("FTP Connection: host=$host, port=$port, user=$username")
                        connectionPrefs.saveFtpConnection(host, port, username, password)
                        // 保存连接历史
                        connectionPrefs.saveConnectionHistory("FTP", host, port, username, password)
                    }
                    is NetworkProtocol.SMB -> {
                        addLog("SMB Connection: host=$host, share=$share, user=$username, domain=$domain")
                        connectionPrefs.saveSmbConnection(host, port, username, password, share, domain)
                        // 保存连接历史
                        connectionPrefs.saveConnectionHistory("SMB", host, port, username, password, share, domain)
                    }
                }
                
                coroutineScope.launch {
                    val (success, message) = when (protocol) {
                        is NetworkProtocol.FTP -> {
                            addLog("Attempting FTP connection...")
                            mediaController.connectToFtp(host, port, username, password)
                        }
                        is NetworkProtocol.SMB -> {
                            addLog("Attempting SMB connection...")
                            mediaController.connectToSmb(host, share, username, password, domain)
                        }
                    }
                    
                    isLoading = false
                    addLog(message)
                    
                    if (success) {
                        addLog("Connection successful!")
                        addLog("=== Protocol Switch Debug ===")
                        addLog("Current protocol: ${protocol::class.simpleName}")
                        addLog("Previous files count: ${files.size}")
                        
                        currentScreen = Screen.FileBrowser
                        files = emptyList()
                        addLog("Files cleared: ${files.size}")
                        
                        currentPath = "/"
                        addLog("Display currentPath: '$currentPath'")
                        
                        if (protocol is NetworkProtocol.SMB) {
                            // 获取可用共享目录
                            availableShares = mediaController.getAvailableShares()
                            isAtSmbRoot = true
                            browserTitle = "选择共享目录"
                            
                            if (availableShares.isNotEmpty()) {
                                // 在根目录显示所有共享目录为文件夹列表
                                files = availableShares.map { shareName ->
                                    MediaFile(
                                        name = shareName,
                                        path = "/$shareName",
                                        size = 0,
                                        isDirectory = true,
                                        protocol = NetworkProtocol.SMB
                                    )
                                }
                                addLog("Showing ${files.size} shares at SMB root")
                            } else {
                                // 如果没有共享目录，尝试浏览根目录
                                isAtSmbRoot = false
                                browserTitle = "/"
                                mediaController.browseFiles("", protocol, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                        files = loadedFiles
                                        addLog("Loaded ${loadedFiles.size} files")
                                    }
                                    override fun onError(error: String) {
                                        errorMessage = error
                                        addLog("Error: $error")
                                    }
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                            }
                        } else {
                            isAtSmbRoot = false
                            browserTitle = "/"
                            mediaController.browseFiles("/", protocol, object : MediaController.MediaCallback {
                                override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                    files = loadedFiles
                                    addLog("Loaded ${loadedFiles.size} files")
                                }
                                override fun onError(error: String) {
                                    errorMessage = error
                                    addLog("Error: $error")
                                }
                                override fun onPlaybackStateChanged(state: Int) {}
                            })
                        }
                    } else {
                        addLog("Connection failed: $message")
                        errorMessage = message
                    }
                }
            },
            isLoading = isLoading,
            connectionPrefs = connectionPrefs  // 传递connectionPrefs以支持自动填充
        )
        
        Screen.FileBrowser -> FileBrowserScreen(
            files = files,
            currentPath = currentPath,
            title = browserTitle,
            debugLogs = debugLogs,
            showBackButton = when (selectedProtocol) {
                is NetworkProtocol.SMB -> !isAtSmbRoot  // SMB: 不在根目录时显示
                is NetworkProtocol.FTP -> currentPath != "/"  // FTP: 不在根目录时显示
            },
            isAtSmbRoot = isAtSmbRoot,
            selectedProtocol = selectedProtocol,
            onFileClick = { file ->
                if (file.isDirectory) {
                    addLog("Clicking directory: ${file.name}, path: ${file.path}")
                    currentPath = file.path
                    isLoading = true
                    addLog("Starting to browse: ${file.path}")
                    coroutineScope.launch {
                        try {
                            if (isAtSmbRoot && selectedProtocol is NetworkProtocol.SMB) {
                                val shareName = file.name
                                addLog("Selecting SMB share: $shareName")
                                val success = mediaController.selectShare(shareName)
                                if (success) {
                                    isAtSmbRoot = false
                                    browserTitle = shareName
                                    mediaController.browseFiles("", selectedProtocol, object : MediaController.MediaCallback {
                                        override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                            addLog("Browse completed: ${loadedFiles.size} files loaded")
                                            files = loadedFiles
                                            isLoading = false
                                        }
                                        
                                        override fun onError(error: String) {
                                            addLog("Browse error: $error")
                                            errorMessage = error
                                            isLoading = false
                                        }
                                        
                                        override fun onPlaybackStateChanged(state: Int) {}
                                    })
                                } else {
                                    addLog("Failed to select share: $shareName")
                                    errorMessage = "Failed to access share: $shareName"
                                    isLoading = false
                                }
                            } else {
                                mediaController.browseFiles(file.path, selectedProtocol, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                        addLog("Browse completed: ${loadedFiles.size} files loaded")
                                        files = loadedFiles
                                        browserTitle = file.name
                                        isLoading = false
                                    }
                                    
                                    override fun onError(error: String) {
                                        addLog("Browse error: $error")
                                        errorMessage = error
                                        isLoading = false
                                    }
                                    
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                            }
                        } catch (e: Exception) {
                            addLog("Exception in browseFiles: ${e.message}")
                            e.printStackTrace()
                            isLoading = false
                        }
                    }
                } else {
                    val extension = file.name.substringAfterLast('.', "").lowercase()
                    val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                    
                    if (isImage) {
                        val allImageFiles = files.filter { f ->
                            val ext = f.name.substringAfterLast('.', "").lowercase()
                            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                        }
                        val index = allImageFiles.indexOfFirst { it.path == file.path }
                        imageFiles = allImageFiles
                        initialImageIndex = if (index >= 0) index else 0
                        currentScreen = Screen.ImageViewer
                    } else {
                        mediaController.playMedia(file, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(files: List<MediaFile>) {}
                            
                            override fun onError(error: String) {
                                errorMessage = error
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
                        currentScreen = Screen.Player
                    }
                }
            },
            onFileLongClick = { file ->
                selectedFile = file
                showFileMenu = true
            },
            onBackClick = {
                if (selectedProtocol is NetworkProtocol.SMB) {
                    // SMB协议的返回逻辑
                    addLog("=== SMB Back Click ===")
                    addLog("Current path: '$currentPath'")
                    addLog("isAtSmbRoot: $isAtSmbRoot")
                    
                    if (currentPath == "/" || currentPath.matches(Regex("^/[^/]+$"))) {
                        // 当前在SMB根目录("/")或共享根目录("/shareName")，返回到SMB根目录显示所有共享
                        if (!isAtSmbRoot) {
                            addLog("Returning to SMB root from share: $currentPath")
                            isAtSmbRoot = true
                            currentPath = "/"
                            browserTitle = "选择共享目录"
                            files = availableShares.map { shareName ->
                                MediaFile(
                                    name = shareName,
                                    path = "/$shareName",
                                    size = 0,
                                    isDirectory = true,
                                    protocol = NetworkProtocol.SMB
                                )
                            }
                            addLog("Returned to SMB root, showing ${files.size} shares")
                        } else {
                            addLog("Already at SMB root, ignoring back click")
                        }
                    } else {
                        // 当前在子目录中，返回上级目录
                        val parentPath = currentPath.substringBeforeLast("/")
                        val newPath = if (parentPath.isEmpty()) "/" else parentPath
                        addLog("Navigating to parent directory: '$newPath'")
                        
                        currentPath = newPath
                        browserTitle = if (newPath == "/") {
                            "选择共享目录"
                        } else {
                            newPath.substringAfterLast("/")
                        }
                        
                        isLoading = true
                        coroutineScope.launch {
                            mediaController.browseFiles(newPath, selectedProtocol, object : MediaController.MediaCallback {
                                override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                    files = loadedFiles
                                    isLoading = false
                                    addLog("Loaded ${loadedFiles.size} files in $newPath")
                                }
                                
                                override fun onError(error: String) {
                                    errorMessage = error
                                    isLoading = false
                                    addLog("Error browsing $newPath: $error")
                                }
                                
                                override fun onPlaybackStateChanged(state: Int) {}
                            })
                        }
                    }
                } else if (currentPath != "/") {
                    // FTP等其他协议的返回逻辑
                    val parentPath = currentPath.substringBeforeLast("/")
                    currentPath = if (parentPath.isEmpty()) "/" else parentPath
                    browserTitle = currentPath
                    isLoading = true
                    coroutineScope.launch {
                        mediaController.browseFiles(currentPath, selectedProtocol, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                files = loadedFiles
                                isLoading = false
                            }
                            
                            override fun onError(error: String) {
                                errorMessage = error
                                isLoading = false
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
                    }
                }
            },
            onRefreshShares = if (selectedProtocol is NetworkProtocol.SMB) {
                {
                    addLog("Refreshing SMB shares...")
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            // 强制刷新共享目录列表
                            val host = connectionPrefs.getSmbHost()
                            val port = connectionPrefs.getSmbPort()
                            val username = connectionPrefs.getSmbUsername()
                            val password = connectionPrefs.getSmbPassword()
                            val domain = connectionPrefs.getSmbDomain()
                            
                            addLog("Reconnecting to SMB server: $host")
                            val (success, message) = mediaController.connectToSmb(
                                host, "", username, password, domain, forceRefresh = true
                            )
                            
                            isLoading = false
                            addLog(message)
                            
                            if (success) {
                                // 重新获取共享列表
                                val shares = mediaController.getAvailableShares()
                                availableShares = shares
                                isAtSmbRoot = true
                                browserTitle = "选择共享目录"
                                files = shares.map { shareName ->
                                    MediaFile(
                                        name = shareName,
                                        path = "/$shareName",
                                        size = 0,
                                        isDirectory = true,
                                        protocol = NetworkProtocol.SMB
                                    )
                                }
                                addLog("Refreshed ${files.size} shares")
                            } else {
                                errorMessage = message
                            }
                        } catch (e: Exception) {
                            isLoading = false
                            errorMessage = "Failed to refresh shares: ${e.message}"
                            addLog(errorMessage!!)
                        }
                    }
                }
            } else null,
            onDisconnect = {
                isAtSmbRoot = false
                mediaController.release()
                mediaController.initializePlayer()
                currentScreen = Screen.Connection
                addLog("Disconnected from server")
            },
            isLoading = isLoading
        )
        
        Screen.Player -> PlayerScreen(
            mediaController = mediaController,
            castController = castController,
            onBackClick = {
                mediaController.stopPlayback()
                currentScreen = Screen.FileBrowser
            },
            onError = onError
        )
        
        Screen.ImageViewer -> ImageViewerScreen(
            imageFiles = imageFiles,
            initialIndex = initialImageIndex,
            currentProtocol = selectedProtocol,
            getImageUrl = { path -> mediaController.getImageUrl(path, selectedProtocol) },
            castController = castController,
            onBackClick = {
                currentScreen = Screen.FileBrowser
            },
            onError = onError
        )
    }
    
    if (showFileMenu && selectedFile != null) {
        FileOperationMenu(
            file = selectedFile!!,
            onDismiss = { showFileMenu = false },
            onRename = {
                showFileMenu = false
                renameText = selectedFile!!.name
                showRenameDialog = true
            },
            onCopyPath = {
                clipboardManager.setText(AnnotatedString(selectedFile!!.path))
                showFileMenu = false
            },
            onDownload = {
                showFileMenu = false
                showLoadingDialog = true
                loadingMessage = "Downloading ${selectedFile!!.name}..."
                coroutineScope.launch {
                    downloadFile(selectedFile!!)
                    showLoadingDialog = false
                }
            },
            onOpenWith = {
                showFileMenu = false
                openWithSystemApp(selectedFile!!)
            }
        )
    }
    
    if (showRenameDialog && selectedFile != null) {
        RenameDialog(
            originalName = selectedFile!!.name,
            newName = renameText,
            onNameChange = { renameText = it },
            onConfirm = {
                showRenameDialog = false
                coroutineScope.launch {
                    renameFile(selectedFile!!, renameText)
                }
            },
            onDismiss = {
                showRenameDialog = false
                renameText = ""
            }
        )
    }
    
    if (showLoadingDialog) {
        LoadingDialog(
            message = loadingMessage,
            onDismiss = { showLoadingDialog = false }
        )
    }
    
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }
}

enum class Screen {
    Connection,
    FileBrowser,
    Player,
    ImageViewer
}

@Composable
fun ConnectionScreen(
    selectedProtocol: NetworkProtocol,
    savedFtpHost: String,
    savedFtpPort: Int,
    savedFtpUsername: String,
    savedFtpPassword: String,
    savedSmbHost: String,
    savedSmbPort: Int,
    savedSmbUsername: String,
    savedSmbPassword: String,
    savedSmbShare: String,
    savedSmbDomain: String,
    debugLogs: List<String>,
    onConnect: (NetworkProtocol, String, Int, String, String, String, String) -> Unit,
    isLoading: Boolean,
    connectionPrefs: ConnectionPreferences? = null  // 新增参数
) {
    var protocol by remember { mutableStateOf(selectedProtocol) }
    
    // Use separate state variables for FTP and SMB
    var ftpHost by remember { mutableStateOf(savedFtpHost) }
    var ftpPort by remember { mutableStateOf(savedFtpPort.toString()) }
    var ftpUsername by remember { mutableStateOf(savedFtpUsername) }
    var ftpPassword by remember { mutableStateOf(savedFtpPassword) }
    
    var smbHost by remember { mutableStateOf(savedSmbHost) }
    var smbPort by remember { mutableStateOf(savedSmbPort.toString()) }
    var smbUsername by remember { mutableStateOf(savedSmbUsername) }
    var smbPassword by remember { mutableStateOf(savedSmbPassword) }
    var smbShare by remember { mutableStateOf(savedSmbShare) }
    var smbDomain by remember { mutableStateOf(savedSmbDomain) }
    
    // 当前 active fields based on protocol
    val host = if (protocol is NetworkProtocol.FTP) ftpHost else smbHost
    val port = if (protocol is NetworkProtocol.FTP) ftpPort else smbPort
    val username = if (protocol is NetworkProtocol.FTP) ftpUsername else smbUsername
    val password = if (protocol is NetworkProtocol.FTP) ftpPassword else smbPassword
    val share = smbShare
    val domain = smbDomain
    
    // 匹配的连接记录
    var matchedConnection by remember { mutableStateOf<ConnectionPreferences.ConnectionRecord?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "LAN Media Player",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = protocol is NetworkProtocol.FTP,
                onClick = { protocol = NetworkProtocol.FTP },
                label = { Text("FTP") }
            )
            FilterChip(
                selected = protocol is NetworkProtocol.SMB,
                onClick = { protocol = NetworkProtocol.SMB },
                label = { Text("SMB") }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = host,
            onValueChange = { 
                if (protocol is NetworkProtocol.FTP) ftpHost = it else smbHost = it
                // 检查是否有匹配的连接记录
                if (it.isNotEmpty() && connectionPrefs != null) {
                    val protocolStr = if (protocol is NetworkProtocol.FTP) "FTP" else "SMB"
                    matchedConnection = connectionPrefs.findMatchingConnection(it, protocolStr)
                } else {
                    matchedConnection = null
                }
            },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                // 如果有匹配的连接，显示提示图标
                if (matchedConnection != null) {
                    IconButton(onClick = {
                        // 自动填充匹配的连接信息
                        matchedConnection?.let { record ->
                            if (protocol is NetworkProtocol.FTP) {
                                ftpPort = record.port.toString()
                                ftpUsername = record.username
                                ftpPassword = record.password
                            } else {
                                smbPort = record.port.toString()
                                smbUsername = record.username
                                smbPassword = record.password
                                smbShare = record.share
                                smbDomain = record.domain
                            }
                            matchedConnection = null  // 清除匹配
                        }
                    }) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Auto-fill saved connection",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = port,
            onValueChange = { 
                if (protocol is NetworkProtocol.FTP) ftpPort = it else smbPort = it
            },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = username,
            onValueChange = { 
                if (protocol is NetworkProtocol.FTP) ftpUsername = it else smbUsername = it
            },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { 
                if (protocol is NetworkProtocol.FTP) ftpPassword = it else smbPassword = it
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )
        
        if (protocol is NetworkProtocol.SMB) {
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = share,
                onValueChange = { smbShare = it },
                label = { Text("Share") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g., Media, Documents, Public") }
            )
            
            // Quick select common share names
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val commonShares = listOf("Media", "Documents", "Public", "Users", "Share")
                for (shareName in commonShares) {
                    AssistChip(
                        onClick = { smbShare = shareName },
                        label = { Text(shareName, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = domain,
                onValueChange = { smbDomain = it },
                label = { Text("Domain (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                val portInt = port.toIntOrNull() ?: (if (protocol is NetworkProtocol.FTP) 21 else 445)
                
                // Debug: Log actual values being passed
                println("[UI DEBUG] Protocol: ${protocol::class.simpleName}")
                println("[UI DEBUG] Host: '$host' (length: ${host.length})")
                println("[UI DEBUG] Port: $portInt")
                println("[UI DEBUG] Username: '$username' (length: ${username.length})")
                println("[UI DEBUG] Password length: ${password.length}")
                if (protocol is NetworkProtocol.SMB) {
                    println("[UI DEBUG] Share: '$share'")
                    println("[UI DEBUG] Domain: '$domain'")
                }
                
                onConnect(protocol, host, portInt, username, password, share, domain)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Connect")
            }
        }
        
        // Debug logs section
        if (debugLogs.isNotEmpty()) {
            val clipboardManager = LocalClipboardManager.current
            
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection Logs",
                            style = MaterialTheme.typography.titleSmall
                        )
                        IconButton(
                            onClick = {
                                val logText = debugLogs.joinToString("\n")
                                clipboardManager.setText(AnnotatedString(logText))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy logs",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(debugLogs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileBrowserScreen(
    files: List<MediaFile>,
    currentPath: String,
    title: String,
    debugLogs: List<String>,
    showBackButton: Boolean,
    isAtSmbRoot: Boolean,
    selectedProtocol: NetworkProtocol,
    onFileClick: (MediaFile) -> Unit,
    onFileLongClick: (MediaFile) -> Unit,
    onBackClick: () -> Unit,
    onRefreshShares: (() -> Unit)? = null,  // SMB根目录时显示刷新按钮
    onDisconnect: () -> Unit,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (showBackButton) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                // SMB根目录时显示刷新按钮
                if (isAtSmbRoot && selectedProtocol is NetworkProtocol.SMB && onRefreshShares != null) {
                    IconButton(onClick = onRefreshShares) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Shares",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                IconButton(onClick = onDisconnect) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Disconnect",
                        modifier = Modifier.graphicsLayer(rotationZ = 180f)
                    )
                }
            }
        )
        
        // Debug logs section
        if (debugLogs.isNotEmpty()) {
            val clipboardManager = LocalClipboardManager.current
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .heightIn(max = 200.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Debug Logs",
                            style = MaterialTheme.typography.titleSmall
                        )
                        IconButton(
                            onClick = {
                                val logText = debugLogs.joinToString("\n")
                                clipboardManager.setText(AnnotatedString(logText))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy logs",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(debugLogs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            if (files.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No files found", style = MaterialTheme.typography.bodyLarge)
                        Text("Check debug logs above for details", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(files) { file ->
                        FileListItem(
                            file = file,
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileListItem(
    file: MediaFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!file.isDirectory) {
                    Text(
                        text = formatFileSize(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    mediaController: MediaController,
    castController: CastController,
    onBackClick: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var showCastDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }  // 控制按钮显示状态
    
    // 添加状态用于显示拖动进度提示
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionText by remember { mutableStateOf("") }
    
    // 格式化时间为 mm:ss 格式
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
    
    // 自动隐藏控制按钮：无操作3秒后隐藏
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(3000)  // 3秒后隐藏
            showControls = false
        }
    }
    
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        // 设置全屏
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // 保持屏幕常亮，防止锁屏
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.decorView?.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = mediaController.getPlayer()
                    useController = true  // 使用 ExoPlayer 内置控制器（支持进度条拖动）
                    controllerShowTimeoutMs = 3000  // 控制器3秒后自动隐藏
                    
                    // 监听控制器可见性变化，同步自定义按钮的显示状态
                    setControllerVisibilityListener(object : androidx.media3.ui.PlayerControlView.VisibilityListener {
                        override fun onVisibilityChange(visibility: Int) {
                            showControls = (visibility == android.view.View.VISIBLE)
                        }
                    })
                    
                    // 在 PlayerView 上添加触摸监听，实现全局滑动手势
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                isDragging = false
                                showControls = true
                                // 强制显示 ExoPlayer 控制器
                                showController()
                                true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val player = getPlayer()
                                if (player != null && player.duration > 0) {
                                    // 检测水平滑动
                                    val historySize = event.historySize
                                    if (historySize > 0) {
                                        val currentX = event.x
                                        val previousX = event.getHistoricalX(0)
                                        val deltaX = currentX - previousX
                                        
                                        // 只有当水平移动距离足够大时才调整进度
                                        if (Math.abs(deltaX) > 5) {
                                            isDragging = true
                                            val currentPosition = player.currentPosition
                                            val duration = player.duration
                                            
                                            // 计算进度调整量：每像素调整15毫秒（更灵敏）
                                            val adjustMs = (deltaX * 15).toLong()
                                            val newPosition = (currentPosition + adjustMs).coerceIn(0, duration)
                                            
                                            player.seekTo(newPosition)
                                            
                                            // 更新拖动提示文字
                                            val newTime = formatTime(newPosition)
                                            val totalTime = formatTime(duration)
                                            dragPositionText = "$newTime / $totalTime"
                                        }
                                    }
                                }
                                true
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                if (isDragging) {
                                    isDragging = false
                                    dragPositionText = ""
                                    // 拖动结束后启动自动隐藏计时器
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 返回按钮 - 优化样式
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .padding(8.dp)
                    .size(40.dp)  // 固定大小，更紧凑
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // 投屏按钮 - 优化样式
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            IconButton(
                onClick = { showCastDialog = true },
                modifier = Modifier
                    .padding(8.dp)
                    .size(40.dp)  // 固定大小，更紧凑
            ) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = "Cast",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // 显示拖动进度提示（在屏幕中央）
        if (isDragging && dragPositionText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = dragPositionText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        if (showCastDialog) {
            CastDeviceDialog(
                castController = castController,
                onDismiss = { showCastDialog = false },
                onDeviceSelected = { device: CastDevice ->
                    showCastDialog = false
                    val videoUrl = mediaController.getVideoUrl()
                    if (videoUrl.isNotEmpty()) {
                        castController.castVideo(device, videoUrl, "Video") { success, message ->
                            if (success) {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            } else {
                                onError(message)
                            }
                        }
                    } else {
                        onError("No video URL available")
                    }
                }
            )
        }
    }
}

fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

fun getMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "txt" -> "text/plain"
        else -> "*/*"
    }
}

@Composable
fun ImageViewerScreen(
    imageFiles: List<MediaFile>,
    initialIndex: Int,
    currentProtocol: NetworkProtocol,
    getImageUrl: (String) -> String,
    castController: CastController,
    onBackClick: () -> Unit,
    onError: (String) -> Unit
) {
    val initialPage = initialIndex.coerceIn(0, maxOf(0, imageFiles.size - 1))
    val pagerState = rememberPagerState(initialPage = initialPage)
    var isSlideshowPlaying by remember { mutableStateOf(false) }
    var slideshowInterval by remember { mutableStateOf(6) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 使用 LinkedHashMap 保持插入顺序，便于管理缓存
    val imageCache = remember { mutableStateMapOf<Int, String>() }
    val preloadBatchSize = 10 // 每批预加载10张图片
    var currentBatchIndex by remember { mutableStateOf(0) } // 当前批次索引（0=第1批，1=第2批...）
    
    // 计算某张图片属于哪个批次
    fun getBatchIndex(imageIndex: Int): Int {
        return imageIndex / preloadBatchSize
    }
    
    // 预加载指定批次的图片（带重试机制确保加载成功）
    fun preloadBatch(batchIndex: Int) {
        if (imageFiles.isEmpty()) return
        
        val startIndex = batchIndex * preloadBatchSize
        val endIndex = minOf(imageFiles.size - 1, startIndex + preloadBatchSize - 1)
        
        coroutineScope.launch {
            for (index in startIndex..endIndex) {
                // 如果已经缓存成功，跳过
                if (imageCache.containsKey(index)) continue
                
                // 最多重试3次
                var loaded = false
                for (attempt in 1..3) {
                    try {
                        val imagePath = imageFiles[index].path
                        println("[ImageViewer] Loading image $index: path='$imagePath', protocol=${currentProtocol::class.simpleName}")
                        val url = getImageUrl(imagePath)
                        println("[ImageViewer] Generated URL for image $index: $url")
                        imageCache[index] = url
                        loaded = true
                        break // 加载成功，跳出重试循环
                    } catch (e: Exception) {
                        println("Failed to load image at index $index (attempt $attempt): ${e.message}")
                        e.printStackTrace()
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(500) // 等待0.5秒后重试
                        }
                    }
                }
                
                if (!loaded) {
                    println("Failed to load image at index $index after 3 attempts")
                }
            }
        }
    }
    
    // 快速预加载：优先加载当前页及附近几张（并行加载）
    fun quickPreload(currentIndex: Int) {
        if (imageFiles.isEmpty()) return
        
        // 需要立即加载的索引：当前页、前一页、后一页、前两页、后两页
        val urgentIndices = listOf(
            currentIndex,
            currentIndex + 1,
            currentIndex - 1,
            currentIndex + 2,
            currentIndex - 2
        ).filter { it in 0 until imageFiles.size && !imageCache.containsKey(it) }
        
        // 并行加载这些图片
        urgentIndices.forEach { index ->
            coroutineScope.launch {
                try {
                    val imagePath = imageFiles[index].path
                    println("[ImageViewer] Quick loading image $index")
                    val url = getImageUrl(imagePath)
                    imageCache[index] = url
                    println("[ImageViewer] Quick loaded image $index successfully")
                } catch (e: Exception) {
                    println("Failed to quick load image at index $index: ${e.message}")
                }
            }
        }
    }
    
    // 清理旧批次缓存（保留当前批次和前一批次）
    fun cleanupOldBatches(currentBatch: Int) {
        val indicesToRemove = imageCache.keys.filter { index ->
            val batchIndex = getBatchIndex(index)
            // 删除距离当前批次超过1的批次（即保留当前批次和前一批次）
            batchIndex < currentBatch - 1
        }
        
        indicesToRemove.forEach { index ->
            imageCache.remove(index)
        }
        
        if (indicesToRemove.isNotEmpty()) {
            println("Cleaned up ${indicesToRemove.size} old cached images")
        }
    }
    
    // 初始加载：优先快速加载当前页及附近图片
    LaunchedEffect(Unit) {
        val initialBatch = getBatchIndex(initialPage)
        currentBatchIndex = initialBatch
        
        // 第一步：快速并行加载当前页及附近5张图片（优先级最高）
        quickPreload(initialPage)
        
        // 第二步：后台顺序加载整个批次（补充剩余图片）
        preloadBatch(initialBatch)
        
        // 如果初始页面不是批次的第一张，也预加载下一批
        if (initialPage % preloadBatchSize > preloadBatchSize / 2 && initialBatch + 1 < (imageFiles.size + preloadBatchSize - 1) / preloadBatchSize) {
            preloadBatch(initialBatch + 1)
        }
    }
    
    // 当页面改变时，检查是否需要加载新批次
    LaunchedEffect(pagerState.currentPage) {
        if (imageFiles.isEmpty()) return@LaunchedEffect
        
        val currentPage = pagerState.currentPage
        val totalImages = imageFiles.size
        val pageBatch = getBatchIndex(currentPage)
        
        // 第一步：立即并行加载当前页及附近图片（最高优先级）
        quickPreload(currentPage)
        
        // 如果进入了新的批次
        if (pageBatch != currentBatchIndex) {
            currentBatchIndex = pageBatch
            
            // 第二步：后台顺序加载整个批次（补充剩余图片）
            preloadBatch(pageBatch)
            
            // 预加载下一批次（如果存在）
            val nextBatch = pageBatch + 1
            val totalBatches = (totalImages + preloadBatchSize - 1) / preloadBatchSize
            if (nextBatch < totalBatches) {
                preloadBatch(nextBatch)
            }
            
            // 清理旧批次缓存（当加载第3批及以后时，清理第1批）
            if (pageBatch >= 2) {
                cleanupOldBatches(pageBatch)
            }
        }
    }
    
    LaunchedEffect(isSlideshowPlaying, slideshowInterval) {
        if (isSlideshowPlaying && imageFiles.isNotEmpty()) {
            while (isSlideshowPlaying) {
                delay(slideshowInterval * 1000L)
                if (isSlideshowPlaying) {
                    val nextPage = if (pagerState.currentPage < imageFiles.size - 1) pagerState.currentPage + 1 else 0
                    pagerState.animateScrollToPage(nextPage)
                }
            }
        }
    }
    
    // 保持屏幕常亮，防止播放幻灯片时锁屏
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        // 设置全屏，隐藏状态栏和导航栏
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.decorView?.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (imageFiles.isEmpty()) {
            Text(
                text = "No images to display",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            HorizontalPager(
                state = pagerState,
                pageCount = imageFiles.size,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val cachedUrl = imageCache[page]
                if (cachedUrl != null) {
                    ImageLoader(
                        imageUrl = cachedUrl,
                        contentDescription = imageFiles[page].name
                    )
                } else {
                    // 如果缓存中没有，立即加载并显示加载中
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    // 异步加载当前图片（带重试）
                    LaunchedEffect(page) {
                        // 再次检查是否已被其他协程加载成功
                        if (imageCache.containsKey(page)) return@LaunchedEffect
                        
                        // 最多重试2次
                        for (attempt in 1..2) {
                            // 每次重试前都检查是否已被加载
                            if (imageCache.containsKey(page)) break
                            
                            try {
                                val url = getImageUrl(imageFiles[page].path)
                                imageCache[page] = url
                                break // 加载成功
                            } catch (e: Exception) {
                                println("Failed to load image on demand at index $page (attempt $attempt): ${e.message}")
                                if (attempt < 2) {
                                    kotlinx.coroutines.delay(500) // 等待0.5秒后重试
                                }
                            }
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${imageFiles.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                IconButton(
                    onClick = { isSlideshowPlaying = !isSlideshowPlaying }
                ) {
                    Icon(
                        imageVector = if (isSlideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isSlideshowPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                
                IconButton(
                    onClick = { showSettingsDialog = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
            }
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        ) {
            IconButton(onClick = { showCastDialog = true }) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = "Cast",
                    tint = Color.White
                )
            }
        }
    }
    
    if (showSettingsDialog) {
        SlideshowSettingsDialog(
            currentInterval = slideshowInterval,
            onIntervalChange = { slideshowInterval = it },
            onDismiss = { showSettingsDialog = false }
        )
    }
    
    if (showCastDialog) {
        CastDeviceDialog(
            castController = castController,
            onDismiss = { showCastDialog = false },
            onDeviceSelected = { device: CastDevice ->
                showCastDialog = false
                val currentImage = imageFiles[pagerState.currentPage]
                val imageUrl = getImageUrl(currentImage.path)
                castController.castImage(device, imageUrl, currentImage.name) { success, message ->
                    if (success) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    } else {
                        onError(message)
                    }
                }
            }
        )
    }
}

@Composable
fun SlideshowSettingsDialog(
    currentInterval: Int,
    onIntervalChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var customInterval by remember { mutableStateOf(currentInterval.toString()) }
    var showCustomInput by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("幻灯片设置") },
        text = {
            Column {
                Text(
                    text = "切换时间",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showCustomInput,
                        onClick = { showCustomInput = false },
                        label = { Text("预设") }
                    )
                    FilterChip(
                        selected = showCustomInput,
                        onClick = { showCustomInput = true },
                        label = { Text("自定义") }
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (showCustomInput) {
                    OutlinedTextField(
                        value = customInterval,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                customInterval = newValue
                            }
                        },
                        label = { Text("秒") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        suffix = { Text("秒") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val interval = customInterval.toIntOrNull()
                            if (interval != null && interval > 0) {
                                onIntervalChange(interval)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用")
                    }
                } else {
                     Column(
                         horizontalAlignment = Alignment.CenterHorizontally
                     ) {
                         Row(
                             horizontalArrangement = Arrangement.spacedBy(8.dp)
                         ) {
                             listOf(2, 3, 5, 6).forEach { seconds ->
                                 FilterChip(
                                     selected = currentInterval == seconds,
                                     onClick = { onIntervalChange(seconds) },
                                     label = { Text("${seconds}秒") }
                                 )
                             }
                         }
                         Spacer(modifier = Modifier.height(8.dp))
                         Row(
                             horizontalArrangement = Arrangement.spacedBy(8.dp)
                         ) {
                             listOf(10, 15, 30).forEach { seconds ->
                                 FilterChip(
                                     selected = currentInterval == seconds,
                                     onClick = { onIntervalChange(seconds) },
                                     label = { Text("${seconds}秒") }
                                 )
                             }
                         }
                     }
                 }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "当前: ${currentInterval}秒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
fun CastDeviceDialog(
    castController: CastController,
    onDismiss: () -> Unit,
    onDeviceSelected: (CastDevice) -> Unit
) {
    var devices by remember { mutableStateOf(emptyList<CastDevice>()) }
    var isSearching by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        castController.searchDevices { foundDevices: List<CastDevice> ->
            devices = foundDevices
            isSearching = false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投屏设备") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (isSearching) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("搜索设备中...")
                    }
                } else if (devices.isEmpty()) {
                    Text(
                        text = "未找到可投屏设备\n请确保电视或设备在同一局域网",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "选择设备",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    devices.forEach { device: CastDevice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onDeviceSelected(device) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Cast,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = device.ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            isSearching = true
                            castController.searchDevices { foundDevices: List<CastDevice> ->
                                devices = foundDevices
                                isSearching = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("刷新设备")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun ImageLoader(
    imageUrl: String,
    contentDescription: String
) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> isLoading = true
                    is AsyncImagePainter.State.Success -> {
                        isLoading = false
                        error = null
                    }
                    is AsyncImagePainter.State.Error -> {
                        isLoading = false
                        error = state.result.throwable.message
                    }
                    else -> {}
                }
            }
        )
        
        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
        }

        error?.let { errorMsg ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Error loading image", color = Color.White)
                Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun FileOperationMenu(
    file: MediaFile,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
    onDownload: () -> Unit,
    onOpenWith: () -> Unit
) {
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    val isVideo = extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm")
    val isAudio = extension in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a")
    val isDocument = extension in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")
    val canOpenWith = isImage || isVideo || isAudio || isDocument
    
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            
            if (!file.isDirectory) {
                ListItem(
                    headlineContent = { Text("Rename") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.clickable { onDismiss(); onRename() }
                )
                
                ListItem(
                    headlineContent = { Text("Copy Path") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable { onDismiss(); onCopyPath() }
                )
                
                ListItem(
                    headlineContent = { Text("Download") },
                    leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                    modifier = Modifier.clickable { onDismiss(); onDownload() }
                )
                
                if (canOpenWith) {
                    ListItem(
                        headlineContent = { Text("Open with...") },
                        leadingContent = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                        modifier = Modifier.clickable { onDismiss(); onOpenWith() }
                    )
                }
            } else {
                ListItem(
                    headlineContent = { Text("Rename") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.clickable { onDismiss(); onRename() }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun RenameDialog(
    originalName: String,
    newName: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = onNameChange,
                label = { Text("New name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = newName.isNotBlank() && newName != originalName) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LoadingDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (false) onDismiss() },
        title = { Text("Please wait") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(message)
            }
        },
        confirmButton = {}
    )
}
