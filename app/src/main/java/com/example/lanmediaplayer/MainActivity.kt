@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lanmedia.player

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
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
import com.lanmedia.player.controller.CastController
import com.lanmedia.player.controller.CastDevice
import com.lanmedia.player.controller.MediaController
import com.lanmedia.player.controller.MediaFile
import com.lanmedia.player.controller.NetworkProtocol
import com.lanmedia.player.ui.theme.LanMediaPlayerTheme
import com.lanmedia.player.ui.theme.DarkBackgroundGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding

class MainActivity : ComponentActivity() {
    private lateinit var mediaController: MediaController
    private lateinit var castController: CastController
    private lateinit var connectionPrefs: ConnectionPreferences
    private val debugLogs = mutableListOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ✅ 关键修复：在应用启动时就强制使用IPv4（解决小米澎湃OS问题）
        try {
            java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
            android.util.Log.d("LAN Media", "Set preferIPv4Stack=true")
        } catch (e: Exception) {
            android.util.Log.e("LAN Media", "Failed to set preferIPv4Stack", e)
        }
        
        // ✅ 关键修复：设置全屏模式，消除顶部和底部空白
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        
        // ✅ 设置窗口标志，确保内容延伸到系统栏后面
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        
        // ✅ 检查网络状态（针对小米澎湃OS优化）
        checkNetworkStatus()
        
        // Create log callback function
        val logCallback: (String) -> Unit = { message ->
            runOnUiThread {
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                debugLogs.add("$timestamp - $message")
                // Keep only last 500 logs to avoid memory issues (increased from 100)
                if (debugLogs.size > 500) {
                    debugLogs.removeAt(0)
                }
            }
        }
        
        mediaController = MediaController(this, logCallback)
        mediaController.initializePlayer()
        
        // ✅ 设置HTTP代理重启回调，清空URL缓存
        mediaController.onProxyRestarted = {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
            debugLogs.add("$timestamp - [MainActivity] HTTP proxy restarted, clearing URL cache...")
            // 注意：这里无法直接访问imageCache，因为它在Composable中
            // 所以我们在ImageViewerScreen中通过LaunchedEffect监听
        }
        
        castController = CastController(this, logCallback)
        connectionPrefs = ConnectionPreferences(this)
        
        setContent {
            // ✅ 主题状态管理
            var isDarkTheme by remember { mutableStateOf(true) }
            
            LanMediaPlayerTheme(darkTheme = isDarkTheme) {
                // ✅ 关键修复：使用纯色背景，避免渐变渲染延迟
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F1E))  // ✅ 深色纯色背景，与DarkBackgroundGradient起始色相同
                ) {
                    // ✅ 启动画面状态
                    var showSplash by remember { mutableStateOf(true) }
                    
                    if (showSplash) {
                        SplashScreen(onSplashFinished = {
                            showSplash = false
                        })
                    } else {
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
                            },
                            isDarkTheme = isDarkTheme,
                            onThemeToggle = { isDarkTheme = !isDarkTheme }
                        )
                    }
                }
            }
        }
    }
    
    // ✅ 检查网络状态（针对小米澎湃OS）
    private fun checkNetworkStatus() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (capabilities == null) {
            debugLogs.add("⚠️ No active network connection!")
            debugLogs.add("Please check WiFi or mobile data is enabled")
        } else {
            val hasInternet = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            debugLogs.add("Network status: Internet=${hasInternet}, Validated=${hasValidated}")
            
            if (!hasInternet) {
                debugLogs.add("⚠️ Network has no internet capability")
            }
            
            // ✅ 小米澎湃OS特殊提示
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            if (manufacturer.contains("xiaomi") || manufacturer.contains("mi")) {
                debugLogs.add("")
                debugLogs.add("📱 Xiaomi HyperOS detected! If FTP/SMB fails:")
                debugLogs.add("   1. Settings → Apps → LAN Media → Battery Saver → No restrictions")
                debugLogs.add("   2. Security App → Network Assistant → Allow LAN access")
                debugLogs.add("   3. Settings → Connection & Sharing → Private DNS → Off")
                debugLogs.add("   4. WLAN → WLAN Assistant → Disable 'Smart network acceleration'")
                debugLogs.add("")
                debugLogs.add("🔧 Developer Options to check:")
                debugLogs.add("   - Settings → Additional Settings → Developer Options")
                debugLogs.add("   - Check 'Background process limit' = 'Standard limit'")
                debugLogs.add("   - Ensure 'Don't keep activities' is OFF")
                debugLogs.add("   - Try enabling 'USB debugging' (may help with network permissions)")
            }
        }
    }
    
    // ✅ 跟踪是否有活跃的投屏
    private var hasActiveCasting = false
    
    // ✅ 设置投屏状态（由 Compose UI 调用）
    fun setCastingState(active: Boolean) {
        hasActiveCasting = active
    }
    
    override fun onPause() {
        super.onPause()
        // ✅ 当App进入后台且有活跃投屏时，启动前台服务
        if (hasActiveCasting) {
            android.util.Log.d("MainActivity", "App going to background with active casting, starting service")
            mediaController.startDlnaService("Casting")
        }
    }
    
    override fun onResume() {
        super.onResume()
        // ✅ 当App回到前台时，停止前台服务（不再需要后台保活）
        if (hasActiveCasting) {
            android.util.Log.d("MainActivity", "App returning to foreground, stopping service")
            mediaController.stopDlnaService()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaController.release()
        castController.release()
    }
}

// ✅ 启动画面 - 极速模式，不增加启动时间
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        // ✅ 超快速淡入动画（150ms）
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
        // ✅ 立即消失，总耗时约150-200ms
        onSplashFinished()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackgroundGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Logo图标 (使用mipmap中的ic_launcher)
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer(alpha = alpha.value),
                contentScale = ContentScale.Fit
            )
            
            // App名称
            Text(
                text = "LAN Media Player",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.graphicsLayer(alpha = alpha.value)
            )
        }
    }
}

// ✅ 主题切换按钮组件
@Composable
fun ThemeToggleButton(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onThemeToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
            contentDescription = if (isDarkTheme) "切换到浅色模式" else "切换到深色模式",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun MainScreen(
    mediaController: MediaController,
    castController: CastController,
    connectionPrefs: ConnectionPreferences,
    getDebugLogs: () -> List<String>,
    onDownloadComplete: (String) -> Unit,
    onError: (String) -> Unit,
    isDarkTheme: Boolean = true,
    onThemeToggle: () -> Unit = {}
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
    
    // ✅ 文本阅读器状态
    var currentTextFile by remember { mutableStateOf<MediaFile?>(null) }
    
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
        
        // ✅ 关键修复：保存context引用，避免在协程中失效
        val currentContext = context
        
        try {
            coroutineScope.launch {
                // 使用外部存储目录，让文件持久化保存
                val externalDir = currentContext.getExternalFilesDir(null)
                if (externalDir == null) {
                    addLog("❌ External storage not available")
                    onError("External storage not available")
                    return@launch
                }
                
                // 创建子目录存放下载的文件
                val downloadDir = java.io.File(externalDir, "downloads")
                if (!downloadDir.exists()) {
                    val created = downloadDir.mkdirs()
                    if (!created) {
                        addLog("❌ Failed to create download directory")
                        onError("Failed to create download directory")
                        return@launch
                    }
                }
                
                val destFile = java.io.File(downloadDir, file.name)
                
                // 如果文件已存在，先删除
                if (destFile.exists()) {
                    val deleted = destFile.delete()
                    if (!deleted) {
                        addLog("⚠️ Failed to delete existing file")
                    }
                }
                
                addLog("Downloading file to: ${destFile.absolutePath}")
                
                val inputStream = mediaController.getFileStream(file.path, selectedProtocol)
                
                if (inputStream == null) {
                    addLog("❌ Failed to get file stream")
                    onError("Failed to get file stream")
                    return@launch
                }
                
                // 下载文件到外部存储
                try {
                    java.io.FileOutputStream(destFile).use { output ->
                        inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ Download failed: ${e.message}")
                    e.printStackTrace()
                    onError("Download failed: ${e.message}")
                    return@launch
                }
                
                addLog("✅ File downloaded successfully: ${destFile.absolutePath}")
                addLog("File size: ${destFile.length()} bytes")
                
                val mimeType = getMimeType(file.name)
                addLog("MIME type: $mimeType")
                
                // 使用 FileProvider 获取 content:// URI（兼容 Android 7.0+）
                val contentUri = try {
                    androidx.core.content.FileProvider.getUriForFile(
                        currentContext,
                        "${currentContext.packageName}.fileprovider",
                        destFile
                    )
                } catch (e: Exception) {
                    addLog("❌ FileProvider error: ${e.message}")
                    e.printStackTrace()
                    onError("Failed to create file URI: ${e.message}")
                    return@launch
                }
                
                addLog("Content URI: $contentUri")
                
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                try {
                    currentContext.startActivity(intent)
                    addLog("✅ System app launched successfully")
                } catch (e: Exception) {
                    addLog("❌ No app found to handle this file type: ${e.message}")
                    e.printStackTrace()
                    onError("No app found to open this file type")
                }
            }
        } catch (e: Exception) {
            addLog("❌ Open error: ${e.message}")
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
                // ✅ 切换协议前，先清理之前的连接状态
                if (selectedProtocol != null && selectedProtocol::class != protocol::class) {
                    addLog("=== Protocol switch detected ===")
                    addLog("Switching from ${selectedProtocol::class.simpleName} to ${protocol::class.simpleName}")
                    mediaController.clearConnectionState()
                    
                    // ✅ 清空文件列表和路径
                    files = emptyList()
                    currentPath = ""
                    isAtSmbRoot = false
                    browserTitle = "选择共享目录"
                }
                
                selectedProtocol = protocol
                isLoading = true
                errorMessage = null
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
            connectionPrefs = connectionPrefs,  // 传递connectionPrefs以支持自动填充
            addLog = { message ->
                debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
            }
        )
        
        Screen.FileBrowser -> FileBrowserScreen(
            files = files,
            currentPath = currentPath,
            title = browserTitle,
            debugLogs = debugLogs,
            addLog = { message ->
                debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
            },
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
                                
                                // ✅ 添加超时保护
                                val success = withTimeoutOrNull(15000) {
                                    mediaController.selectShare(shareName)
                                }
                                
                                if (success == null) {
                                    addLog("ERROR: selectShare timed out after 15 seconds")
                                    errorMessage = "Failed to access share: timeout"
                                    isLoading = false
                                } else if (success) {
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
                    // ✅ 支持更多图片格式
                    val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg", "ico", "heic", "heif", "raw", "cr2", "nef", "arw", "dng")
                    
                    // ✅ 支持文本文件
                    val isText = extension in listOf("txt")
                    
                    if (isImage) {
                        val allImageFiles = files.filter { f ->
                            val ext = f.name.substringAfterLast('.', "").lowercase()
                            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg", "ico", "heic", "heif", "raw", "cr2", "nef", "arw", "dng")
                        }
                        val index = allImageFiles.indexOfFirst { it.path == file.path }
                        imageFiles = allImageFiles
                        initialImageIndex = if (index >= 0) index else 0
                        
                        // ✅ 设置 currentMediaFile，让 HTTP 代理能正常工作
                        mediaController.setCurrentMediaFile(file)
                        addLog("Set currentMediaFile for image preview: ${file.name}")
                        
                        currentScreen = Screen.ImageViewer
                    } else if (isText) {
                        // ✅ 打开文本阅读器
                        currentTextFile = file
                        currentScreen = Screen.TextReader
                        addLog("Opening text file: ${file.name}")
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
                    
                    if (isAtSmbRoot) {
                        // ✅ 当前在SMB共享列表页面，忽略返回
                        addLog("Already at SMB root (isAtSmbRoot=true), ignoring back click")
                    } else if (currentPath.isEmpty()) {
                        // ✅ 当前在共享内的根目录，返回到共享列表页面
                        addLog("Returning from share root to share list")
                        isLoading = true
                        coroutineScope.launch {
                            try {
                                // 重新获取共享列表
                                val shares = mediaController.getAvailableShares()
                                availableShares = shares
                                isAtSmbRoot = true
                                browserTitle = "选择共享目录"
                                files = shares.map { shareName ->
                                    MediaFile(
                                        name = shareName,
                                        path = "",
                                        size = 0L,
                                        isDirectory = true,
                                        protocol = selectedProtocol
                                    )
                                }
                                isLoading = false
                                addLog("Loaded ${shares.size} shares")
                            } catch (e: Exception) {
                                errorMessage = "Failed to load shares: ${e.message}"
                                isLoading = false
                                addLog("Error loading shares: ${e.message}")
                            }
                        }
                    } else {
                        // 当前在共享目录或子目录中，返回上级目录
                        val parentPath = if (currentPath.indexOf('/', startIndex = 1) != -1) {
                            // 有父目录：/folder1/subfolder -> /folder1
                            currentPath.substringBeforeLast("/")
                        } else {
                            // 没有父目录：/folder1 -> "" (共享根目录)
                            ""
                        }
                        
                        addLog("Navigating to parent directory: '$currentPath' -> '$parentPath'")
                        
                        if (parentPath.isEmpty()) {
                            // 返回到共享根目录（某个共享下的根目录，不是共享列表）
                            addLog("Returning to share root")
                            currentPath = ""  // ✅ 使用空字符串而不是"/"
                            isAtSmbRoot = false  // ✅ 修正：这是在共享内的根目录，不是共享列表
                            // browserTitle保持不变，仍然是当前共享名
                            isLoading = true
                            coroutineScope.launch {
                                mediaController.browseFiles("", selectedProtocol, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                        files = loadedFiles
                                        isLoading = false
                                        addLog("Loaded ${loadedFiles.size} files in share root")
                                    }
                                    
                                    override fun onError(error: String) {
                                        errorMessage = error
                                        isLoading = false
                                        addLog("Error browsing share root: $error")
                                    }
                                    
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                            }
                        } else {
                            // 返回到子目录的父目录
                            currentPath = parentPath
                            browserTitle = parentPath.substringAfterLast("/")
                            
                            isLoading = true
                            coroutineScope.launch {
                                mediaController.browseFiles(parentPath, selectedProtocol, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(loadedFiles: List<MediaFile>) {
                                        files = loadedFiles
                                        isLoading = false
                                        addLog("Loaded ${loadedFiles.size} files in $parentPath")
                                    }
                                    
                                    override fun onError(error: String) {
                                        errorMessage = error
                                        isLoading = false
                                        addLog("Error browsing $parentPath: $error")
                                    }
                                    
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                            }
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
            isLoading = isLoading,
            isDarkTheme = isDarkTheme,  // ✅ 传递主题状态
            onThemeToggle = onThemeToggle  // ✅ 传递主题切换回调
        )
        
        Screen.Player -> PlayerScreen(
            mediaController = mediaController,
            castController = castController,
            connectionPrefs = connectionPrefs,
            onBackClick = {
                mediaController.stopPlayback()
                currentScreen = Screen.FileBrowser
            },
            addLog = { message ->
                debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
            }
        )
        
        Screen.ImageViewer -> ImageViewerScreen(
            imageFiles = imageFiles,
            initialIndex = initialImageIndex,
            currentProtocol = selectedProtocol,
            connectionPrefs = connectionPrefs,
            mediaController = mediaController,  // ✅ 传递
            getImageUrl = { path -> mediaController.getLocalImageUrl(path) },
            castController = castController,
            onBackClick = {
                currentScreen = Screen.FileBrowser
            },
            onError = onError,
            addLog = { message ->
                debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
            }
        )
        
        // ✅ 文本阅读器
        Screen.TextReader -> TextReaderScreen(
            textFile = currentTextFile,
            mediaController = mediaController,
            selectedProtocol = selectedProtocol,
            onBackClick = {
                currentScreen = Screen.FileBrowser
            },
            onError = onError,
            addLog = { message ->
                debugLogs = debugLogs + "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} - $message"
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
}

enum class Screen {
    Connection,
    FileBrowser,
    Player,
    ImageViewer,
    TextReader  // ✅ 新增：文本阅读器
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
    connectionPrefs: ConnectionPreferences? = null,  // 新增参数
    addLog: (String) -> Unit = {}  // ✅ 添加日志回调
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
                addLog("[UI DEBUG] Protocol: ${protocol::class.simpleName}")
                addLog("[UI DEBUG] Host: '$host' (length: ${host.length})")
                addLog("[UI DEBUG] Port: $portInt")
                addLog("[UI DEBUG] Username: '$username' (length: ${username.length})")
                addLog("[UI DEBUG] Password length: ${password.length}")
                if (protocol is NetworkProtocol.SMB) {
                    addLog("[UI DEBUG] Share: '$share'")
                    addLog("[UI DEBUG] Domain: '$domain'")
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
    addLog: (String) -> Unit = {},  // ✅ 添加日志回调
    showBackButton: Boolean,
    isAtSmbRoot: Boolean,
    selectedProtocol: NetworkProtocol,
    onFileClick: (MediaFile) -> Unit,
    onFileLongClick: (MediaFile) -> Unit,
    onBackClick: () -> Unit,
    onRefreshShares: (() -> Unit)? = null,  // SMB根目录时显示刷新按钮
    onDisconnect: () -> Unit,
    isLoading: Boolean,
    isDarkTheme: Boolean = true,  // ✅ 新增：主题状态
    onThemeToggle: () -> Unit = {}  // ✅ 新增：主题切换回调
) {
    // ✅ 关键修复：拦截系统返回手势，实现逐级返回
    BackHandler(enabled = showBackButton) {
        onBackClick()
    }
    
    // ✅ 关键修复：使用WindowInsets添加安全区域，避免内容被系统栏遮挡
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
    ) {
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
                // ✅ 主题切换按钮
                ThemeToggleButton(
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle
                )
                
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
    // ✅ 悬停和点击动画状态
    var isHovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = tween(durationMillis = 200)
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isHovered) 
                MaterialTheme.colorScheme.surfaceVariant 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isHovered) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ 根据文件类型显示不同图标
            val iconVector = when {
                file.isDirectory -> Icons.Default.Folder
                file.name.endsWith(".mp4", ignoreCase = true) || 
                file.name.endsWith(".mkv", ignoreCase = true) ||
                file.name.endsWith(".avi", ignoreCase = true) -> Icons.Default.PlayArrow
                file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".png", ignoreCase = true) ||
                file.name.endsWith(".jpeg", ignoreCase = true) -> Icons.Default.Image
                else -> Icons.Default.PlayArrow
            }
            
            val iconTint = when {
                file.isDirectory -> Color(0xFFFFB74D) // 文件夹用橙色
                file.name.endsWith(".mp4", ignoreCase = true) || 
                file.name.endsWith(".mkv", ignoreCase = true) -> Color(0xFF6C63FF) // 视频用紫色
                file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".png", ignoreCase = true) -> Color(0xFF00D9FF) // 图片用青色
                else -> MaterialTheme.colorScheme.primary
            }
            
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = iconTint
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal
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
    connectionPrefs: ConnectionPreferences,
    onBackClick: () -> Unit,
    addLog: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showCastDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }  // 控制按钮显示状态
    var showErrorDialog by remember { mutableStateOf<String?>(null) }  // ✅ 错误提示对话框
    
    // ✅ 关键修复：拦截系统返回手势，实现逐级返回
    BackHandler(enabled = true) {
        mediaController.stopPlayback()
        onBackClick()
    }
    
    // 添加状态用于显示拖动进度提示
    var isDragging by remember { mutableStateOf(false) }
    var dragStartPosition by remember { mutableStateOf(0f) }  // ✅ 记录起始位置
    var dragStartPositionTime by remember { mutableStateOf(0L) }  // ✅ 记录起始时间
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
            // ✅ 停止DLNA前台服务
            mediaController.stopDlnaService()
        }
    }
    
    // ✅ 关键修复：使用WindowInsets添加安全区域，避免内容被系统栏遮挡
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
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
                                dragStartPosition = event.x  // ✅ 记录起始位置
                                showControls = true
                                // 强制显示 ExoPlayer 控制器
                                showController()
                                true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val player = getPlayer()
                                if (player != null && player.duration > 0) {
                                    // 检测水平滑动
                                    val deltaX = event.x - dragStartPosition
                                    
                                    // ✅ 关键修改：每滑动50像素快进/快退15秒（平衡灵敏度和精确控制）
                                    val pixelsPerStep = 50f  // ✅ 每50像素为一个步进
                                    val secondsPerStep = 15L  // 每个步进15秒
                                    
                                    if (Math.abs(deltaX) > pixelsPerStep / 2) {  // 至少滑动25像素才触发
                                        isDragging = true
                                        val duration = player.duration
                                        
                                        // 计算应该调整的时间：根据滑动距离计算步进数
                                        val steps = (deltaX / pixelsPerStep).toInt()
                                        val adjustMs = (steps * secondsPerStep * 1000).toLong()
                                        
                                        // 基于起始时间计算新位置（避免累积误差）
                                        val startPositionTime = if (dragStartPositionTime == 0L) {
                                            // 第一次计算时记录起始时间
                                            dragStartPositionTime = player.currentPosition
                                            player.currentPosition
                                        } else {
                                            dragStartPositionTime
                                        }
                                        
                                        val newPosition = (startPositionTime + adjustMs).coerceIn(0, duration)
                                        
                                        player.seekTo(newPosition)
                                        
                                        // 更新拖动提示文字
                                        val newTime = formatTime(newPosition)
                                        val totalTime = formatTime(duration)
                                        dragPositionText = "$newTime / $totalTime"
                                    }
                                }
                                true
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                if (isDragging) {
                                    isDragging = false
                                    dragStartPosition = 0f
                                    dragStartPositionTime = 0L
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
        
        // ✅ 显示拖动进度提示（在屏幕中央）- 毛玻璃效果
        if (isDragging && dragPositionText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = dragPositionText,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "正在快进/快退",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
        
        if (showCastDialog) {
            CastDeviceDialog(
                castController = castController,
                onDismiss = { showCastDialog = false },
                onDeviceSelected = { device: CastDevice ->
                    showCastDialog = false
                    
                    // 获取当前媒体的真实路径和协议
                    val mediaPath = mediaController.getCurrentMediaPath()
                    val protocol = mediaController.getCurrentProtocol()
                    
                    if (mediaPath != null && protocol != null) {
                        // ✅ 使用HTTP代理URL（DLNA设备通过HTTP访问）
                        val videoUrl = mediaController.getVideoUrl()
                        addLog("Casting with HTTP URL: $videoUrl")
                        castController.castVideo(device, videoUrl, mediaPath.split("/").lastOrNull() ?: "Video") { success, message ->
                            if (success) {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                // ✅ 设置投屏状态为活跃（不立即启动服务）
                                (context as? MainActivity)?.setCastingState(true)
                                addLog("[VideoPlayer] Cast successful, will start service when app goes to background")
                            } else {
                                showErrorDialog = message  // ✅ 显示错误对话框
                            }
                        }
                    } else {
                        showErrorDialog = "No media playing"  // ✅ 显示错误对话框
                    }
                }
            )
        }
        
        // ✅ 错误提示对话框
        showErrorDialog?.let { errorMessage ->
            AlertDialog(
                onDismissRequest = { showErrorDialog = null },
                title = { Text("播放错误") },
                text = { 
                    Column {
                        Text(errorMessage)
                        Spacer(modifier = Modifier.height(8.dp))
                        // ✅ 如果是SMB断连，显示自动重连提示
                        if (errorMessage.contains("SMB连接已断开")) {
                            Text(
                                text = "\nApp正在尝试自动重连...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ✅ 重试按钮
                        TextButton(onClick = { 
                            showErrorDialog = null
                            // ✅ 重新播放当前媒体（会触发自动重连）
                            val currentFile = mediaController.getCurrentMediaFile()
                            if (currentFile != null) {
                                addLog("Retrying playback after error...")
                                mediaController.playMedia(currentFile, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(files: List<MediaFile>) {}
                                    override fun onError(message: String) {
                                        showErrorDialog = message
                                    }
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                            }
                        }) {
                            Text("重试")
                        }
                        // ✅ 返回按钮
                        TextButton(onClick = { 
                            showErrorDialog = null
                            mediaController.stopPlayback()
                            onBackClick()
                        }) {
                            Text("返回")
                        }
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
        "tiff", "tif" -> "image/tiff"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "heic", "heif" -> "image/heic"
        "raw" -> "image/x-raw"
        "cr2" -> "image/x-canon-cr2"
        "nef" -> "image/x-nikon-nef"
        "arw" -> "image/x-sony-arw"
        "dng" -> "image/x-adobe-dng"
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
    connectionPrefs: ConnectionPreferences,
    mediaController: MediaController,  // ✅ 新增参数
    getImageUrl: (String) -> String,
    castController: CastController,
    onBackClick: () -> Unit,
    onError: (String) -> Unit,
    addLog: (String) -> Unit = {}  // ✅ 添加日志回调
) {
    val initialPage = initialIndex.coerceIn(0, maxOf(0, imageFiles.size - 1))
    val pagerState = rememberPagerState(initialPage = initialPage)
    var isSlideshowPlaying by remember { mutableStateOf(false) }
    var slideshowInterval by remember { mutableStateOf(2) }  // ✅ 默认2秒
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    
    // ✅ 背景音乐功能
    var backgroundMusicUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isMusicPlaying by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // ✅ 关键修复：拦截系统返回手势，实现逐级返回
    BackHandler(enabled = true) {
        onBackClick()
    }
    
    // ✅ MediaPlayer实例
    val mediaPlayer = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
    
    // ✅ 音频文件选择器
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: android.net.Uri? ->
            if (uri != null) {
                backgroundMusicUri = uri
                // 配置MediaPlayer
                try {
                    // 获取持久化权限
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    
                    // 设置数据源并准备播放
                    val mediaItem = androidx.media3.common.MediaItem.fromUri(uri.toString())
                    mediaPlayer.setMediaItem(mediaItem)
                    mediaPlayer.prepare()
                    mediaPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE  // 单曲循环
                    mediaPlayer.playWhenReady = true
                    isMusicPlaying = true
                    
                    addLog("[Slideshow] Background music loaded successfully")
                } catch (e: Exception) {
                    addLog("[Slideshow] Failed to load music: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    )
    
    // ✅ 保存当前投屏设备（用于幻灯片模式自动切换）
    var castingDevice by remember { mutableStateOf<CastDevice?>(null) }
    
    // ✅ 全新的预加载机制：直接缓存图片数据，不是URL
    var preloadTriggered by remember { mutableStateOf(false) }  // 是否已触发初始预加载
    var lastPreloadIndex by remember { mutableStateOf(-1) }  // 上次预加载的起始索引
    
    // ✅ 初始预加载：异步加载前30张图片数据（不阻塞UI）
    LaunchedEffect(Unit) {
        if (imageFiles.isEmpty() || preloadTriggered) return@LaunchedEffect
        
        addLog("[ImageViewer] === Initial preload START (async) ===")
        preloadTriggered = true
        lastPreloadIndex = 0
        
        // ✅ 立即启动预加载，不要延迟（确保HTTP请求时缓存已就绪）
        // kotlinx.coroutines.delay(500)  ← 已移除
            
        // ✅ 移除初始预加载,由LaunchedEffect在第0张时自动触发(避免重复)
        // launch {
        //     mediaController.smartPreload(0, imageFiles)
        //     addLog("[ImageViewer] === Initial smartPreload END ===")
        // }
        addLog("[ImageViewer] === Initial preload will be triggered by LaunchedEffect ===")
    }
    
    // ✅ 监听页面变化,智能预加载(带防抖,避免快速滑动时频繁取消)
    var lastPreloadTime = 0L
    var preloadDebounceJob: Job? = null
    
    LaunchedEffect(pagerState.currentPage) {
        if (imageFiles.isEmpty()) return@LaunchedEffect
        
        val currentPage = pagerState.currentPage
        val currentTime = System.currentTimeMillis()
        
        // ✅ 防抖策略: 如果距离上次预加载<500ms,延迟触发
        val timeSinceLastPreload = currentTime - lastPreloadTime
        
        // 取消之前的延迟任务
        preloadDebounceJob?.cancel()
        
        if (timeSinceLastPreload < 500) {
            // 快速滑动: 延迟500ms再触发,等待用户停止滑动
            preloadDebounceJob = launch {
                delay(500)  // 等待500ms
                if (pagerState.currentPage == currentPage) {  // 确认用户停在这个位置
                    mediaController.smartPreload(currentPage, imageFiles)
                    lastPreloadTime = System.currentTimeMillis()
                }
            }
        } else {
            // 正常滑动: 立即触发
            mediaController.smartPreload(currentPage, imageFiles)
            lastPreloadTime = currentTime
        }
    }
    
    LaunchedEffect(isSlideshowPlaying, slideshowInterval) {
        if (isSlideshowPlaying && imageFiles.isNotEmpty()) {
            // ✅ 如果选择了背景音乐且未在播放，开始播放
            if (backgroundMusicUri != null && !isMusicPlaying && !mediaPlayer.isPlaying) {
                try {
                    mediaPlayer.play()
                    isMusicPlaying = true
                    addLog("[Slideshow] Background music started")
                } catch (e: Exception) {
                    addLog("[Slideshow] Failed to start music: ${e.message}")
                }
            }
            
            while (isSlideshowPlaying) {
                delay(slideshowInterval * 1000L)
                if (isSlideshowPlaying) {
                    val nextPage = if (pagerState.currentPage < imageFiles.size - 1) pagerState.currentPage + 1 else 0
                    pagerState.animateScrollToPage(nextPage)
                    
                    // ✅ 如果正在投屏，自动投屏新图片
                    if (castingDevice != null) {
                        val newImage = imageFiles[nextPage]
                        coroutineScope.launch {
                            try {
                                val imageUrl = mediaController.getDlnaImageUrl(newImage, object : MediaController.MediaCallback {
                                    override fun onFilesLoaded(files: List<MediaFile>) {}
                                    override fun onError(error: String) {
                                        addLog("[Slideshow] Failed to get image URL: $error")
                                    }
                                    override fun onPlaybackStateChanged(state: Int) {}
                                })
                                
                                if (imageUrl != null && imageUrl.isNotEmpty()) {
                                    castingDevice?.let { device ->
                                        castController.castImage(device, imageUrl, newImage.name) { success, message ->
                                            if (success) {
                                                addLog("[Slideshow] Casted: ${newImage.name}")
                                                
                                                // ✅ 预加载下一张图片到HTTP代理缓存
                                                val nextIndex = if (nextPage < imageFiles.size - 1) nextPage + 1 else 0
                                                val nextImage = imageFiles[nextIndex]
                                                launch {
                                                    try {
                                                        addLog("[Slideshow] Preloading next image: ${nextImage.name}")
                                                        mediaController.getDlnaImageUrl(nextImage, object : MediaController.MediaCallback {
                                                            override fun onFilesLoaded(files: List<MediaFile>) {}
                                                            override fun onError(error: String) {
                                                                println("[Slideshow] Failed to preload: $error")
                                                            }
                                                            override fun onPlaybackStateChanged(state: Int) {}
                                                        })
                                                        println("[Slideshow] Next image preloaded successfully")
                                                    } catch (e: Exception) {
                                                        println("[Slideshow] Preload error: ${e.message}")
                                                    }
                                                }
                                            } else {
                                                println("[Slideshow] Cast failed: $message")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                println("[Slideshow] Cast error: ${e.message}")
                            }
                        }
                    }
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
            // ✅ 停止DLNA前台服务
            mediaController.stopDlnaService()
            // ✅ 关键修复：切换HTTP代理回本地模式（恢复本地预览）
            mediaController.stopCasting()
            // ✅ 清除投屏设备状态
            castingDevice = null
            // ✅ 清除投屏活跃状态
            (context as? MainActivity)?.setCastingState(false)
            
            // ✅ 停止背景音乐
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer.release()
        }
    }
    
    // ✅ 跟踪当前页面的缩放状态
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    
    // ✅ 关键修复：使用WindowInsets添加安全区域，避免内容被系统栏遮挡
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
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
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isCurrentPageZoomed  // ✅ 放大时禁用滑动
            ) { page ->
                // ✅ 关键修复：不缓存URL，每次动态生成以确保URL与HTTP代理状态一致
                val imageUrl = remember(page, imageFiles[page].path) {
                    getImageUrl(imageFiles[page].path)
                }
                
                ImageLoader(
                    imageUrl = imageUrl,
                    contentDescription = imageFiles[page].name,
                    onZoomChange = { zoomed ->
                        // ✅ 只有当前页面才更新全局状态
                        if (page == pagerState.currentPage) {
                            isCurrentPageZoomed = zoomed
                        }
                    }
                )
            }
            
            // ✅ 底部控制栏 - 毛玻璃效果
            AnimatedVisibility(
                visible = !isCurrentPageZoomed,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(
                            Color.Black.copy(alpha = 0.4f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 页码指示器
                    Text(
                        text = "${pagerState.currentPage + 1} / ${imageFiles.size}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // 分隔线
                    Divider(
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.height(24.dp).width(1.dp)
                    )
                    
                    // 播放/暂停按钮
                    IconButton(
                        onClick = {
                            isSlideshowPlaying = !isSlideshowPlaying
                            // ✅ 停止幻灯片时清除投屏设备并切换回本地模式
                            if (!isSlideshowPlaying) {
                                castingDevice = null
                                mediaController.stopDlnaService()
                                mediaController.stopCasting()  // ✅ 关键修复：恢复本地预览
                                (context as? MainActivity)?.setCastingState(false)
                                
                                // ✅ 停止背景音乐
                                if (mediaPlayer.isPlaying) {
                                    mediaPlayer.pause()
                                    isMusicPlaying = false
                                    addLog("[Slideshow] Background music paused")
                                }
                            } else {
                                // ✅ 开始幻灯片时，如果有音乐则播放
                                if (backgroundMusicUri != null && !mediaPlayer.isPlaying) {
                                    mediaPlayer.play()
                                    isMusicPlaying = true
                                    addLog("[Slideshow] Background music resumed")
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isSlideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isSlideshowPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    // 设置按钮
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    // ✅ 背景音乐按钮
                    IconButton(
                        onClick = {
                            if (backgroundMusicUri == null) {
                                // 选择音乐文件
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            } else {
                                // 切换播放/暂停
                                if (isMusicPlaying) {
                                    mediaPlayer.pause()
                                    isMusicPlaying = false
                                } else {
                                    mediaPlayer.play()
                                    isMusicPlaying = true
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                backgroundMusicUri == null -> Icons.Default.Add
                                isMusicPlaying -> Icons.Default.MusicNote
                                else -> Icons.Default.MusicNote
                            },
                            contentDescription = "Background Music",
                            tint = if (backgroundMusicUri != null) Color(0xFF00D9FF) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
        
        // ✅ 返回按钮 - 毛玻璃效果
        AnimatedVisibility(
            visible = !isCurrentPageZoomed,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }
        
        // ✅ 投屏按钮 - 毛玻璃效果
        AnimatedVisibility(
            visible = !isCurrentPageZoomed,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
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
                castingDevice = device  // ✅ 保存投屏设备
                
                val currentImage = imageFiles[pagerState.currentPage]
                
                // ✅ 启动HTTP代理并获取HTTP URL（DLNA设备通过HTTP访问）
                coroutineScope.launch {
                    try {
                        val imageUrl = mediaController.getDlnaImageUrl(currentImage, object : MediaController.MediaCallback {
                            override fun onFilesLoaded(files: List<MediaFile>) {}
                            
                            override fun onError(errorMsg: String) {
                                println("[ImageViewer] Get image URL error: $errorMsg")
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {}
                        })
                        
                        if (imageUrl != null && imageUrl.isNotEmpty()) {
                            castController.castImage(device, imageUrl, currentImage.name) { success, message ->
                                if (success) {
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    // ✅ 设置投屏状态为活跃
                                    (context as? MainActivity)?.setCastingState(true)
                                    println("[ImageViewer] Cast successful, will start service when app goes to background")
                                } else {
                                    println("[ImageViewer] Cast failed: $message")
                                }
                            }
                        } else {
                            println("[ImageViewer] Failed to get image URL")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        println("[ImageViewer] Cast error: ${e.message}")
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
    
    // ✅ 使用Dialog代替AlertDialog，以便更好地控制尺寸
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 600.dp)
                .heightIn(min = 300.dp, max = 600.dp),  // ✅ 增加最大高度到600dp
            shape = MaterialTheme.shapes.large,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // 标题
                Text(
                    text = "投屏设备",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 内容区域 - 可滚动
                Column(
                    modifier = Modifier
                        .weight(1f)  // ✅ 占据剩余空间
                        .verticalScroll(rememberScrollState())
                ) {
                    if (isSearching) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("搜索设备中...", style = MaterialTheme.typography.bodyLarge)
                        }
                    } else if (devices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Cast,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "未找到可投屏设备",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请确保电视或设备在同一局域网",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "找到 ${devices.size} 个设备",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        devices.forEach { device: CastDevice ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable { onDeviceSelected(device) },
                                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Cast,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = device.ip,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = "选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 底部按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            isSearching = true
                            castController.searchDevices { foundDevices: List<CastDevice> ->
                                devices = foundDevices
                                isSearching = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("刷新")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@Composable
fun ImageLoader(
    imageUrl: String,
    contentDescription: String,
    onZoomChange: (Boolean) -> Unit = {}  // ✅ 通知父组件缩放状态变化
) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // ✅ 缩放状态
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    
    // ✅ 监听scale变化,通知父组件
    LaunchedEffect(scale) {
        onZoomChange(scale > 1.01f)
    }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // ✅ 图片层(始终显示)
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
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
        
        // ✅ 手势层1: 放大状态 - 拦截所有手势
        if (scale > 1.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            
                            if (newScale > 1.01f) {
                                // ✅ 放大状态: 处理拖拽(3倍灵敏度)
                                val maxX = (size.width * (newScale - 1)) / 2
                                val maxY = (size.height * (newScale - 1)) / 2
                                
                                offsetX = (offsetX + pan.x * 3f).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y * 3f).coerceIn(-maxY, maxY)
                            } else if (newScale <= 1f && scale > 1f) {
                                // ✅ 缩小到1倍: 重置位置
                                offsetX = 0f
                                offsetY = 0f
                            }
                            
                            scale = newScale
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                // ✅ 双击还原
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        )
                    }
            )
        }
        
        // ✅ 手势层2: 正常状态 - 仅双击放大,不拦截滑动
        if (scale <= 1.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                // ✅ 双击放大
                                scale = 2.5f
                            }
                        )
                    }
            )
        }
        
        // ✅ 加载指示器 - 带动画
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // ✅ 错误提示 - 毛玻璃效果
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            error?.let { errorMsg ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = Color(0xFFFF4757),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "加载失败",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
            }
        }
        
        // ✅ 显示当前缩放倍数 - 毛玻璃效果
        AnimatedVisibility(
            visible = scale > 1.1f,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${String.format("%.1f", scale)}x",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
    val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg", "ico", "heic", "heif", "raw", "cr2", "nef", "arw", "dng")
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
