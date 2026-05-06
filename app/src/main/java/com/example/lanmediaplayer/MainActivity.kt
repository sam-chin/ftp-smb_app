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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.example.lanmediaplayer.controller.MediaController
import com.example.lanmediaplayer.controller.MediaFile
import com.example.lanmediaplayer.controller.NetworkProtocol
import com.example.lanmediaplayer.ui.theme.LanMediaPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaController: MediaController
    private lateinit var connectionPrefs: ConnectionPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaController = MediaController(this)
        mediaController.initializePlayer()
        connectionPrefs = ConnectionPreferences(this)
        
        setContent {
            LanMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(mediaController, connectionPrefs)
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
fun MainScreen(mediaController: MediaController, connectionPrefs: ConnectionPreferences) {
    var currentScreen by remember { mutableStateOf(Screen.Connection) }
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var currentPath by remember { mutableStateOf("/") }
    var selectedProtocol by remember { mutableStateOf<NetworkProtocol>(NetworkProtocol.FTP) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var debugLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    
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
            onConnect = { protocol, host, port, username, password, share, domain ->
                selectedProtocol = protocol
                isLoading = true
                errorMessage = null
                debugLogs = emptyList()
                addLog("Connecting to ${protocol::class.simpleName}://$host:$port...")
                
                // Save connection info separately
                when (protocol) {
                    is NetworkProtocol.FTP -> {
                        connectionPrefs.saveFtpConnection(host, port, username, password)
                    }
                    is NetworkProtocol.SMB -> {
                        connectionPrefs.saveSmbConnection(host, port, username, password, share, domain)
                    }
                }
                
                coroutineScope.launch {
                    val success = when (protocol) {
                        is NetworkProtocol.FTP -> {
                            addLog("Attempting FTP connection...")
                            mediaController.connectToFtp(host, port, username, password)
                        }
                        is NetworkProtocol.SMB -> {
                            addLog("Attempting SMB connection to share: $share...")
                            mediaController.connectToSmb(host, share, username, password, domain)
                        }
                    }
                    
                    isLoading = false
                    if (success) {
                        addLog("Connection successful!")
                        currentScreen = Screen.FileBrowser
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
                        addLog("Connection failed!")
                        errorMessage = "Connection failed"
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
                    currentPath = file.path
                    isLoading = true
                    coroutineScope.launch {
                        mediaController.browseFiles(file.path, selectedProtocol, object : MediaController.MediaCallback {
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
    onConnect: (NetworkProtocol, String, Int, String, String, String, String) -> Unit,
    isLoading: Boolean
) {
    var protocol by remember { mutableStateOf(selectedProtocol) }
    var host by remember { 
        mutableStateOf(if (protocol is NetworkProtocol.FTP) savedFtpHost else savedSmbHost)
    }
    var port by remember { 
        mutableStateOf((if (protocol is NetworkProtocol.FTP) savedFtpPort else savedSmbPort).toString())
    }
    var username by remember { 
        mutableStateOf(if (protocol is NetworkProtocol.FTP) savedFtpUsername else savedSmbUsername)
    }
    var password by remember { 
        mutableStateOf(if (protocol is NetworkProtocol.FTP) savedFtpPassword else savedSmbPassword)
    }
    var share by remember { mutableStateOf(savedSmbShare) }
    var domain by remember { mutableStateOf(savedSmbDomain) }
    
    // Update fields when protocol changes
    LaunchedEffect(protocol) {
        if (protocol is NetworkProtocol.FTP) {
            host = savedFtpHost
            port = savedFtpPort.toString()
            username = savedFtpUsername
            password = savedFtpPassword
        } else {
            host = savedSmbHost
            port = savedSmbPort.toString()
            username = savedSmbUsername
            password = savedSmbPassword
        }
    }
    
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
            onValueChange = { host = it },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )
        
        if (selectedProtocol is NetworkProtocol.SMB) {
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = share,
                onValueChange = { share = it },
                label = { Text("Share") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Domain (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                val portInt = port.toIntOrNull() ?: (if (protocol is NetworkProtocol.FTP) 21 else 445)
                onConnect(protocol, host, portInt, username, password, share, domain)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && host.isNotEmpty()
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
                    Text(
                        text = "Debug Logs",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
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
