@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.lanmediaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
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
                    MainScreen(mediaController, connectionPrefs) { debugLogs.toList() }
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
    getDebugLogs: () -> List<String>
) {
    var currentScreen by remember { mutableStateOf(Screen.Connection) }
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var currentPath by remember { mutableStateOf("/") }
    var selectedProtocol by remember { mutableStateOf<NetworkProtocol>(NetworkProtocol.FTP) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
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
    
    val coroutineScope = rememberCoroutineScope()
    
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
                        currentScreen = Screen.FileBrowser
                        // Clear files before browsing to avoid showing old protocol's files
                        files = emptyList()
                        currentPath = "/"
                        addLog("Browsing root directory...")
                        mediaController.browseFiles("/", protocol, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                files = loadedFiles
                                addLog("Loaded ${loadedFiles.size} files")
                                if (loadedFiles.isEmpty()) {
                                    addLog("WARNING: Directory is empty!")
                                }
                            }
                            
                            override fun onError(error: String) {
                                errorMessage = error
                                addLog("Error: $error")
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
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
                    mediaController.playMedia(file, object : MediaController.MediaCallback {
                        override fun onFilesLoaded(files: List<MediaFile>) {}
                        
                        override fun onError(error: String) {
                            errorMessage = error
                        }
                        
                        override fun onPlaybackStateChanged(state: Int) {}
                    })
                    currentScreen = Screen.Player
                }
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
                currentScreen = Screen.FileBrowser
            }
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
    Player
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
                            onClick = { onFileClick(file) }
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
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick
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
