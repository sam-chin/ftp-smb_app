@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.lanmediaplayer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.example.lanmediaplayer.controller.MediaController
import com.example.lanmediaplayer.controller.MediaFile
import com.example.lanmediaplayer.controller.NetworkProtocol
import com.example.lanmediaplayer.ui.theme.LanMediaPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaController: MediaController
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
        connectionPrefs = ConnectionPreferences(this)
        
        setContent {
            LanMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        mediaController = mediaController,
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
    }
}

@Composable
fun MainScreen(
    mediaController: MediaController,
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
    var pendingShareSelection by remember { mutableStateOf(false) }
    var pendingSmbParams by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    
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
            
            val inputStream: java.io.InputStream? = when (selectedProtocol) {
                is NetworkProtocol.FTP -> mediaController.ftpClient?.getFileStream(file.path, 0)
                is NetworkProtocol.SMB -> mediaController.smbClient?.getFileStream(file.path, 0)
                else -> null
            }
            
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
            val success = when (selectedProtocol) {
                is NetworkProtocol.FTP -> mediaController.ftpClient?.rename(file.path, newName) ?: false
                is NetworkProtocol.SMB -> mediaController.smbClient?.rename(file.path, newName) ?: false
                else -> false
            }
            
            if (success) {
                addLog("Rename successful")
                val parentPath = file.path.substringBeforeLast("/", "")
                val newPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
                val index = files.indexOfFirst { it.path == file.path }
                if (index >= 0) {
                    files = files.toMutableList().apply {
                        set(index, MediaFile(newName, file.size, file.isDirectory, newPath, file.protocol))
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
                val tempDir = context.cacheDir
                val tempFile = java.io.File(tempDir, file.name)
                
                val inputStream: java.io.InputStream? = when (selectedProtocol) {
                    is NetworkProtocol.FTP -> mediaController.ftpClient?.getFileStream(file.path, 0)
                    is NetworkProtocol.SMB -> mediaController.smbClient?.getFileStream(file.path, 0)
                    else -> null
                }
                
                if (inputStream == null) {
                    onError("Failed to get file stream")
                    return@launch
                }
                
                java.io.FileOutputStream(tempFile).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                
                val mimeType = getMimeType(file.name)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(tempFile), mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            addLog("Open error: ${e.message}")
            onError("Failed to open file: ${e.message}")
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
                // Don't clear debug logs, keep them for troubleshooting
                
                // Log connection parameters (hide password)
                when (protocol) {
                    is NetworkProtocol.FTP -> {
                        addLog("FTP Connection: host=$host, port=$port, user=$username")
                        connectionPrefs.saveFtpConnection(host, port, username, password)
                    }
                    is NetworkProtocol.SMB -> {
                        addLog("SMB Connection: host=$host, share=$share, user=$username, domain=$domain")
                        connectionPrefs.saveSmbConnection(host, port, username, password, share, domain)
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
                        
                        val rootPath = if (protocol is NetworkProtocol.SMB) "" else "/"
                        currentPath = "/"
                        
                        addLog("Root path for browseFiles: '$rootPath' (length: ${rootPath.length})")
                        addLog("Display currentPath: '$currentPath'")
                        addLog("Calling browseFiles...")
                        
                        mediaController.browseFiles(rootPath, protocol, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                addLog("=== BrowseFiles Callback ===")
                                addLog("Received ${loadedFiles.size} files")
                                if (loadedFiles.isNotEmpty()) {
                                    addLog("First file: ${loadedFiles[0].name}, path: ${loadedFiles[0].path}")
                                    addLog("Protocol: ${loadedFiles[0].protocol::class.simpleName}")
                                }
                                files = loadedFiles
                                addLog("Files updated: ${files.size}")
                                if (loadedFiles.isEmpty()) {
                                    addLog("WARNING: Directory is empty!")
                                }
                                addLog("=== Protocol Switch Complete ===")
                            }
                            
                            override fun onError(error: String) {
                                errorMessage = error
                                addLog("Error: $error")
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
                    } else if (message.startsWith("SHARES:")) {
                        val shares = message.substringAfter("SHARES:").split(",")
                        addLog("Shares found: ${shares.joinToString(", ")}")
                        availableShares = shares
                        pendingShareSelection = true
                        pendingSmbParams = Triple(host, "$username:$password", domain)
                    } else {
                        addLog("Connection failed: $message")
                        errorMessage = message
                    }
                }
            },
            isLoading = isLoading
        )
        
        Screen.FileBrowser -> FileBrowserScreen(
            files = files,
            currentPath = currentPath,
            debugLogs = debugLogs,
            onFileClick = { file ->
                if (file.isDirectory) {
                    addLog("Clicking directory: ${file.name}, path: ${file.path}")
                    currentPath = file.path
                    isLoading = true
                    addLog("Starting to browse: ${file.path}")
                    coroutineScope.launch {
                        try {
                            mediaController.browseFiles(file.path, selectedProtocol, object : MediaController.MediaCallback {
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
                if (currentPath != "/") {
                    val parentPath = currentPath.substringBeforeLast("/")
                    currentPath = if (parentPath.isEmpty()) "/" else parentPath
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
            onDisconnect = {
                mediaController.release()
                mediaController.initializePlayer()
                currentScreen = Screen.Connection
                addLog("Disconnected from server")
            },
            isLoading = isLoading
        )
        
        Screen.Player -> PlayerScreen(
            mediaController = mediaController,
            onBackClick = {
                mediaController.stopPlayback()
                currentScreen = Screen.FileBrowser
            }
        )
        
        Screen.ImageViewer -> ImageViewerScreen(
            imageFiles = imageFiles,
            initialIndex = initialImageIndex,
            currentProtocol = selectedProtocol,
            getImageUrl = { path -> mediaController.getImageUrl(path, selectedProtocol) },
            onBackClick = {
                currentScreen = Screen.FileBrowser
            }
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
    
    if (pendingShareSelection && availableShares.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Select Share") },
            text = {
                Column {
                    Text("Available shares on server:")
                    Spacer(modifier = Modifier.height(8.dp))
                    availableShares.forEach { shareName ->
                        TextButton(
                            onClick = {
                                pendingShareSelection = false
                                isLoading = true
                                pendingSmbParams?.let { (host, credentials, domain) ->
                                    val (username, password) = credentials.split(":", limit = 2)
                                    coroutineScope.launch {
                                        val success = mediaController.selectShare(shareName)
                                        if (success) {
                                            currentScreen = Screen.FileBrowser
                                            files = emptyList()
                                            currentPath = "/"
                                            mediaController.browseFiles("", NetworkProtocol.SMB, object : MediaController.MediaCallback {
                                                override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                                    files = loadedFiles
                                                }
                                                override fun onError(error: String) {
                                                    errorMessage = error
                                                }
                                                override fun onPlaybackStateChanged(state: Int) {}
                                            })
                                        } else {
                                            errorMessage = "Failed to access share: $shareName"
                                        }
                                        isLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(shareName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    pendingShareSelection = false
                    availableShares = emptyList()
                    pendingSmbParams = null
                }) {
                    Text("Cancel")
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
    isLoading: Boolean
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
    
    // Current active fields based on protocol
    val host = if (protocol is NetworkProtocol.FTP) ftpHost else smbHost
    val port = if (protocol is NetworkProtocol.FTP) ftpPort else smbPort
    val username = if (protocol is NetworkProtocol.FTP) ftpUsername else smbUsername
    val password = if (protocol is NetworkProtocol.FTP) ftpPassword else smbPassword
    val share = smbShare
    val domain = smbDomain
    
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
            },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth()
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
    debugLogs: List<String>,
    onFileClick: (MediaFile) -> Unit,
    onFileLongClick: (MediaFile) -> Unit,
    onBackClick: () -> Unit,
    onDisconnect: () -> Unit,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(currentPath) },
            navigationIcon = {
                if (currentPath != "/") {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
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
    onBackClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = mediaController.getPlayer()
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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

@Composable
fun ImageViewerScreen(
    imageFiles: List<MediaFile>,
    initialIndex: Int,
    currentProtocol: NetworkProtocol,
    getImageUrl: (String) -> String,
    onBackClick: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, maxOf(0, imageFiles.size - 1)),
        pageCount = { imageFiles.size }
    )
    
    Box(modifier = Modifier.fillMaxSize()) {
        if (imageFiles.isEmpty()) {
            Text(
                text = "No images to display",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val currentFile = imageFiles[page]
                val imageUrl = remember(currentFile) {
                    getImageUrl(currentFile.path)
                }
                
                ImageLoader(
                    imageUrl = imageUrl,
                    contentDescription = currentFile.name
                )
            }
            
            Text(
                text = "${pagerState.currentPage + 1} / ${imageFiles.size}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
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
            
            HorizontalDivider()
            
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
