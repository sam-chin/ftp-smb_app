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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.example.lanmediaplayer.controller.MediaController
import com.example.lanmediaplayer.controller.MediaFile
import com.example.lanmediaplayer.controller.NetworkProtocol
import com.example.lanmediaplayer.ui.theme.LanMediaPlayerTheme

class MainActivity : ComponentActivity() {
    private lateinit var mediaController: MediaController
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaController = MediaController(this)
        mediaController.initializePlayer()
        
        setContent {
            LanMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(mediaController)
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
fun MainScreen(mediaController: MediaController) {
    var currentScreen by remember { mutableStateOf(Screen.Connection) }
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var currentPath by remember { mutableStateOf("/") }
    var selectedProtocol by remember { mutableStateOf<NetworkProtocol>(NetworkProtocol.FTP) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    when (currentScreen) {
        Screen.Connection -> ConnectionScreen(
            onConnect = { protocol, host, port, username, password, share, domain ->
                selectedProtocol = protocol
                isLoading = true
                errorMessage = null
                
                rememberCoroutineScope().launch {
                    val success = when (protocol) {
                        is NetworkProtocol.FTP -> {
                            mediaController.connectToFtp(host, port, username, password)
                        }
                        is NetworkProtocol.SMB -> {
                            mediaController.connectToSmb(host, share, username, password, domain)
                        }
                    }
                    
                    isLoading = false
                    if (success) {
                        currentScreen = Screen.FileBrowser
                        mediaController.browseFiles("/", protocol, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                files = loadedFiles
                            }
                            
                            override fun onError(error: String) {
                                errorMessage = error
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
                    } else {
                        errorMessage = "Connection failed"
                    }
                }
            },
            isLoading = isLoading
        )
        
        Screen.FileBrowser -> FileBrowserScreen(
            files = files,
            currentPath = currentPath,
            onFileClick = { file ->
                if (file.isDirectory) {
                    currentPath = file.path
                    isLoading = true
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
    onConnect: (NetworkProtocol, String, Int, String, String, String, String) -> Unit,
    isLoading: Boolean
) {
    var selectedProtocol by remember { mutableStateOf<NetworkProtocol>(NetworkProtocol.FTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    
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
                selected = selectedProtocol is NetworkProtocol.FTP,
                onClick = { selectedProtocol = NetworkProtocol.FTP },
                label = { Text("FTP") }
            )
            FilterChip(
                selected = selectedProtocol is NetworkProtocol.SMB,
                onClick = { selectedProtocol = NetworkProtocol.SMB },
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
                val portInt = port.toIntOrNull() ?: 21
                onConnect(selectedProtocol, host, portInt, username, password, share, domain)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && host.isNotEmpty() && username.isNotEmpty()
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
    onFileClick: (MediaFile) -> Unit,
    onBackClick: () -> Unit,
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
            }
        )
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
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
