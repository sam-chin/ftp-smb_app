@file:OptIn(ExperimentalMaterial3Api::class)

package com.lanmedia.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanmedia.player.controller.MediaController
import com.lanmedia.player.controller.MediaFile
import com.lanmedia.player.controller.NetworkProtocol
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import org.mozilla.universalchardet.UniversalDetector

// ✅ 中文数字转阿拉伯数字
private fun chineseToArabic(chineseNum: String): Int {
    val charMap = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '十' to 10, '百' to 100, '千' to 1000, '万' to 10000
    )
    
    var result = 0
    var temp = 0
    var unit = 1
    
    for (char in chineseNum.reversed()) {
        when (char) {
            '十', '百', '千', '万' -> {
                unit = charMap[char] ?: 1
                if (temp == 0) temp = 1
            }
            else -> {
                val digit = charMap[char] ?: 0
                temp += digit
                if (unit > 1) {
                    result += temp * unit
                    temp = 0
                    unit = 1
                }
            }
        }
    }
    result += temp
    return result
}

// ✅ 从章节标题中提取数字(支持中文和阿拉伯数字)
private fun extractChapterNumber(title: String): Int? {
    // 尝试提取阿拉伯数字
    val arabicMatch = Regex("(\\d+)").find(title)
    if (arabicMatch != null) {
        return arabicMatch.groupValues[1].toIntOrNull()
    }
    
    // 尝试提取中文数字(第X章格式)
    val chineseMatch = Regex("第([零一二三四五六七八九十百千万]+)章").find(title)
    if (chineseMatch != null) {
        return chineseToArabic(chineseMatch.groupValues[1])
    }
    
    return null
}

// ✅ 智能文本编码检测和读取函数(使用专业编码检测库)
private fun detectAndReadText(inputStream: InputStream): String {
    // 第一步：读取前10KB用于编码检测
    val probeSize = 10240
    val probeBuffer = ByteArrayOutputStream()
    val tempBuffer = ByteArray(probeSize)
    var totalBytesRead = 0
    
    while (totalBytesRead < probeSize) {
        val bytesRead = inputStream.read(tempBuffer, 0, minOf(probeSize - totalBytesRead, tempBuffer.size))
        if (bytesRead <= 0) break
        probeBuffer.write(tempBuffer, 0, bytesRead)
        totalBytesRead += bytesRead
    }
    
    val probeData = probeBuffer.toByteArray()
    
    if (probeData.isEmpty()) {
        return ""
    }
    
    // 第二步：✅ 使用 UniversalDetector 准确检测编码
    val detector = UniversalDetector(null)
    detector.handleData(probeData, 0, probeData.size)
    detector.dataEnd()
    
    val detectedCharset = detector.detectedCharset
    detector.reset()
    
    val charsetName = if (detectedCharset != null) {
        android.util.Log.d("TextReader", "Detected encoding: $detectedCharset")
        detectedCharset
    } else {
        // 如果检测失败，默认使用GBK（中文环境最常见）
        android.util.Log.w("TextReader", "Detection failed, default to GBK")
        "GBK"
    }
    
    // 第三步：分块流式读取
    val remainingStream = SequenceInputStream(
        ByteArrayInputStream(probeData),
        inputStream
    )
    
    val reader = InputStreamReader(remainingStream, Charset.forName(charsetName))
    val charBuffer = CharArray(8192)
    val stringBuilder = StringBuilder(32768)
    
    var charsRead: Int
    while (reader.read(charBuffer).also { charsRead = it } != -1) {
        stringBuilder.append(charBuffer, 0, charsRead)
    }
    
    reader.close()
    return stringBuilder.toString()
}

// ✅ 文本阅读器主题枚举
enum class TextReaderTheme {
    LIGHT,      // 白天模式
    DARK,       // 夜晚模式
    EYE_CARE    // 护眼模式(黄褐色)
}

@Composable
fun TextReaderScreen(
    textFile: MediaFile?,
    mediaController: MediaController,
    selectedProtocol: NetworkProtocol,
    onBackClick: () -> Unit,
    onError: (String) -> Unit,
    addLog: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // ✅ 加载用户设置
    val prefs = context.getSharedPreferences("text_reader_prefs", android.content.Context.MODE_PRIVATE)
    val savedFontSize = prefs.getInt("font_size", 20)  // 默认20sp
    val savedTheme = prefs.getString("theme", "LIGHT") ?: "LIGHT"
    val savedReadMode = prefs.getString("read_mode", "SCROLL") ?: "SCROLL"
    
    // ✅ 文本内容状态
    var textContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // ✅ 关键优化：将文本按行分割，用于LazyColumn懒加载
    var textLines by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // ✅ 阅读设置（从保存的设置加载）
    var currentTheme by remember { mutableStateOf(TextReaderTheme.valueOf(savedTheme)) }
    var fontSize by remember { mutableStateOf(savedFontSize) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    
    // ✅ 阅读模式：PAGE(翻页) 或 SCROLL(滑动)（从保存的设置加载）
    var readMode by remember { mutableStateOf(savedReadMode) }
    
    // ✅ 翻页模式的状态
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf<List<List<String>>>(emptyList()) }  // 每页的行列表
    
    // ✅ 目录(简单实现:按章节标题提取)
    var tableOfContents by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    
    // ✅ 目录分页状态
    var tocCurrentPage by remember { mutableStateOf(0) }  // 当前目录页
    val tocPageSize = 100  // 每页显示100章
    var tocJumpInput by remember { mutableStateOf("") }  // 快速跳转输入
    var searchedChapterIndex by remember { mutableStateOf<Int?>(null) }  // ✅ 搜索到的章节索引
    
    // ✅ 本地日志列表（用于在界面上显示）
    var localLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // ✅ 添加日志的辅助函数
    fun addLocalLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        val logEntry = "$timestamp - $message"
        localLogs = localLogs + logEntry
        // 保持最近100条日志
        if (localLogs.size > 100) {
            localLogs = localLogs.drop(localLogs.size - 100)
        }
        // 同时发送到全局日志
        addLog(message)
    }
    
    // ✅ 保存用户设置
    fun saveSettings() {
        prefs.edit()
            .putInt("font_size", fontSize)
            .putString("theme", currentTheme.name)
            .putString("read_mode", readMode)
            .apply()
        addLocalLog("[TextReader] Settings saved: fontSize=$fontSize, theme=${currentTheme.name}, mode=$readMode")
    }
    
    // ✅ 关键修复：拦截系统返回手势，实现逐级返回
    BackHandler(enabled = true) {
        // 如果有对话框打开，先关闭对话框
        when {
            showSettings -> {
                saveSettings()
                showSettings = false
            }
            showToc -> showToc = false
            showLogs -> showLogs = false
            else -> onBackClick()  // 否则返回上一级
        }
    }
    
    // 加载文本文件
    LaunchedEffect(textFile) {
        if (textFile == null) {
            errorMessage = "No file selected"
            isLoading = false
            addLocalLog("[TextReader] ❌ textFile is null")
            return@LaunchedEffect
        }
        
        isLoading = true
        errorMessage = null
        
        try {
            addLocalLog("[TextReader] Loading file: ${textFile.name}")
            addLocalLog("[TextReader] File path: ${textFile.path}")
            addLocalLog("[TextReader] Protocol: $selectedProtocol")
            
            // ✅ 关键修复：检查SMB连接状态
            if (selectedProtocol is com.lanmedia.player.controller.NetworkProtocol.SMB) {
                val smbClient = mediaController.getSmbClient()
                if (smbClient == null) {
                    errorMessage = "SMB client not initialized"
                    isLoading = false
                    addLocalLog("[TextReader] ❌ SMB client is null")
                    return@LaunchedEffect
                }
                
                if (!smbClient.isConnected()) {
                    errorMessage = "SMB connection lost, please reconnect"
                    isLoading = false
                    addLocalLog("[TextReader] ❌ SMB connection lost")
                    return@LaunchedEffect
                }
                
                // ✅ 检查是否已选择共享目录
                if (!smbClient.hasSelectedShare()) {
                    errorMessage = "No share selected. Please select a shared folder first."
                    isLoading = false
                    addLocalLog("[TextReader] ❌ No SMB share selected")
                    return@LaunchedEffect
                }
            }
            
            // ✅ 关键修复：在IO线程执行网络操作，避免NetworkOnMainThreadException
            val result = withContext(Dispatchers.IO) {
                try {
                    // 获取文件流
                    val startTime = System.currentTimeMillis()
                    addLocalLog("[TextReader] Calling getFileStream with path: ${textFile.path}")
                    val inputStream = mediaController.getFileStream(textFile.path, selectedProtocol)
                    val streamTime = System.currentTimeMillis() - startTime
                    addLocalLog("[TextReader] getFileStream took ${streamTime}ms")
                    
                    if (inputStream == null) {
                        null to "Failed to open file: ${textFile.name}\nPath: ${textFile.path}"
                    } else {
                        // ✅ 智能检测编码：先尝试UTF-8，失败则尝试GBK
                        val readStartTime = System.currentTimeMillis()
                        addLocalLog("[TextReader] Detecting file encoding...")
                        val content = detectAndReadText(inputStream)
                        val readTime = System.currentTimeMillis() - readStartTime
                        addLocalLog("[TextReader] File read successfully in ${readTime}ms, size: ${content.length} chars")
                        content to null
                    }
                } catch (e: Exception) {
                    addLocalLog("[TextReader] Exception: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                    null to "Error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            
            val (content, error) = result
            
            if (error != null) {
                errorMessage = error
                isLoading = false
                addLocalLog("[TextReader] ❌ $error")
                return@LaunchedEffect
            }
            
            if (content != null) {
                textContent = content
                
                // ✅ 关键优化：按行分割，用于LazyColumn懒加载
                val lines = content.lines()
                textLines = lines
                
                // ✅ 计算分页（每页约20行，根据屏幕高度动态调整）
                val linesPerPage = 20  // 默认每页20行
                val calculatedPages = lines.chunked(linesPerPage)
                pages = calculatedPages
                totalPages = calculatedPages.size
                currentPage = 0  // 重置到第一页
                
                // 提取目录(简单实现:查找以数字开头的行)
                val toc = mutableListOf<Pair<String, Int>>()
                for ((index, line) in lines.withIndex()) {
                    if (line.matches(Regex("^\\s*第[一二三四五六七八九十百千万0-9]+章.*")) ||
                        line.matches(Regex("^\\s*Chapter\\s+\\d+.*")) ||
                        line.matches(Regex("^\\s*\\d+[\\.、].*"))) {
                        toc.add(Pair(line.trim(), index))
                    }
                }
                tableOfContents = toc
                
                isLoading = false
                addLocalLog("[TextReader] File loaded: ${lines.size} lines, ${toc.size} chapters")
            }
            
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error (${e.javaClass.simpleName})"
            errorMessage = "Error loading file: $errorMsg\nFile: ${textFile.name}"
            isLoading = false
            addLocalLog("[TextReader] Error: $errorMsg")
            addLocalLog("[TextReader] Exception type: ${e.javaClass.name}")
            addLocalLog("[TextReader] File path: ${textFile.path}")
            e.printStackTrace()
        }
    }
    
    // ✅ 根据主题获取颜色
    val backgroundColor = when (currentTheme) {
        TextReaderTheme.LIGHT -> Color(0xFFF5F5F5)
        TextReaderTheme.DARK -> Color(0xFF1A1A1A)
        TextReaderTheme.EYE_CARE -> Color(0xFFF5E6D3)
    }
    
    val textColor = when (currentTheme) {
        TextReaderTheme.LIGHT -> Color(0xFF333333)
        TextReaderTheme.DARK -> Color(0xFFE0E0E0)
        TextReaderTheme.EYE_CARE -> Color(0xFF5C4B37)
    }
    
    // ✅ 关键修复：使用WindowInsets添加安全区域，避免内容被系统栏遮挡
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(
                top = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
    ) {
        if (isLoading) {
            // ✅ 加载指示器 - 添加返回按钮
            Column(modifier = Modifier.fillMaxSize()) {
                // TopAppBar with back button
                TopAppBar(
                    title = { 
                        Text(
                            textFile?.name ?: "文本阅读",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = backgroundColor
                    )
                )
                
                // Loading content
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("加载中...", color = textColor, fontSize = 14.sp)
                    }
                }
            }
        } else if (errorMessage != null) {
            // ✅ 错误提示 - 添加返回按钮
            Column(modifier = Modifier.fillMaxSize()) {
                // TopAppBar with back button
                TopAppBar(
                    title = { 
                        Text(
                            "错误",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = backgroundColor
                    )
                )
                
                // Error content
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(errorMessage!!, color = Color.Red, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 32.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onBackClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("返回")
                        }
                    }
                }
            }
        } else {
            // 文本内容
            Column(modifier = Modifier.fillMaxSize()) {
                // TopAppBar
                TopAppBar(
                    title = { 
                        Text(
                            textFile?.name ?: "文本阅读",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                        }
                    },
                    actions = {
                        // ✅ 日志按钮
                        IconButton(onClick = { showLogs = !showLogs }) {
                            Icon(
                                imageVector = if (showLogs) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showLogs) "Hide logs" else "Show logs",
                                tint = textColor
                            )
                        }
                        
                        // 目录按钮
                        if (tableOfContents.isNotEmpty()) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(Icons.Default.List, contentDescription = "Table of Contents", tint = textColor)
                            }
                        }
                        
                        // 设置按钮
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = textColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = backgroundColor
                    )
                )
                
                // ✅ 根据阅读模式显示不同UI
                if (readMode == "PAGE") {
                    // ✅ 翻页模式：显示当前页，支持左右滑动翻页
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // 当前页内容
                        if (pages.isNotEmpty() && currentPage < pages.size) {
                            val currentPageLines = pages[currentPage]
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                currentPageLines.forEach { line ->
                                    Text(
                                        text = if (line.isEmpty()) " " else line,
                                        color = textColor,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.8).sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        // ✅ 左右滑动翻页手势（避开系统手势区域）
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(currentPage, totalPages) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            // ✅ 关键修复：检测手势起始位置
                                            val screenWidth = size.width
                                            val edgeThreshold = screenWidth * 0.2f  // 边缘20%区域
                                            
                                            // 如果在屏幕边缘，不消费事件，让系统处理返回手势
                                            if (offset.x < edgeThreshold || offset.x > screenWidth - edgeThreshold) {
                                                // 在边缘区域，不拦截手势
                                                return@detectHorizontalDragGestures
                                            }
                                        },
                                        onDragEnd = { },
                                        onHorizontalDrag = { change, dragAmount ->
                                            // ✅ 只在中间80%区域响应翻页
                                            val screenWidth = size.width
                                            val edgeThreshold = screenWidth * 0.2f
                                            val currentX = change.position.x
                                            
                                            // 如果当前位置在边缘区域，不处理
                                            if (currentX < edgeThreshold || currentX > screenWidth - edgeThreshold) {
                                                return@detectHorizontalDragGestures
                                            }
                                            
                                            change.consume()
                                            if (Math.abs(dragAmount) > 30) {  // ✅ 降低阈值到30，更灵敏
                                                if (dragAmount > 0 && currentPage > 0) {
                                                    // 向右滑动 → 上一页
                                                    currentPage--
                                                } else if (dragAmount < 0 && currentPage < totalPages - 1) {
                                                    // 向左滑动 → 下一页
                                                    currentPage++
                                                }
                                            }
                                        }
                                    )
                                }
                        )
                        
                        // 页码指示器
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${currentPage + 1} / $totalPages",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    // ✅ 滑动模式：使用LazyColumn上下滑动
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(textLines) { line ->
                            Text(
                                text = if (line.isEmpty()) " " else line,
                                color = textColor,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.8).sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                // ✅ 日志显示面板
                if (showLogs && localLogs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.Black.copy(alpha = 0.8f))
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            items(localLogs) { log ->
                                Text(
                                    text = log,
                                    color = Color.Green,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                
                // 底部信息栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "字数: ${textContent.length}",
                        color = textColor.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // ✅ 显示阅读模式
                        Text(
                            text = if (readMode == "PAGE") "翻页" else "滑动",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        
                        Text(
                            text = when (currentTheme) {
                                TextReaderTheme.LIGHT -> "白天"
                                TextReaderTheme.DARK -> "夜晚"
                                TextReaderTheme.EYE_CARE -> "护眼"
                            },
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        
        // ✅ 设置对话框
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { 
                    saveSettings()  // ✅ 关闭时保存设置
                    showSettings = false 
                },
                title = { Text("阅读设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // ✅ 阅读模式选择
                        Text("阅读模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeButton(
                                label = "滑动",
                                isSelected = readMode == "SCROLL",
                                onClick = { 
                                    readMode = "SCROLL"
                                    saveSettings()  // ✅ 实时保存
                                },
                                backgroundColor = if (readMode == "SCROLL") MaterialTheme.colorScheme.primary else Color.Gray,
                                textColor = Color.White
                            )
                            ThemeButton(
                                label = "翻页",
                                isSelected = readMode == "PAGE",
                                onClick = { 
                                    readMode = "PAGE"
                                    saveSettings()  // ✅ 实时保存
                                },
                                backgroundColor = if (readMode == "PAGE") MaterialTheme.colorScheme.primary else Color.Gray,
                                textColor = Color.White
                            )
                        }
                        
                        // 主题选择
                        Text("主题模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeButton(
                                label = "白天",
                                isSelected = currentTheme == TextReaderTheme.LIGHT,
                                onClick = { 
                                    currentTheme = TextReaderTheme.LIGHT
                                    saveSettings()  // ✅ 实时保存
                                },
                                backgroundColor = Color(0xFFF5F5F5),
                                textColor = Color(0xFF333333)
                            )
                            ThemeButton(
                                label = "夜晚",
                                isSelected = currentTheme == TextReaderTheme.DARK,
                                onClick = { 
                                    currentTheme = TextReaderTheme.DARK
                                    saveSettings()  // ✅ 实时保存
                                },
                                backgroundColor = Color(0xFF1A1A1A),
                                textColor = Color(0xFFE0E0E0)
                            )
                            ThemeButton(
                                label = "护眼",
                                isSelected = currentTheme == TextReaderTheme.EYE_CARE,
                                onClick = { 
                                    currentTheme = TextReaderTheme.EYE_CARE
                                    saveSettings()  // ✅ 实时保存
                                },
                                backgroundColor = Color(0xFFF5E6D3),
                                textColor = Color(0xFF5C4B37)
                            )
                        }
                        
                        // 字体大小
                        Text("字体大小: ${fontSize}sp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { 
                                if (fontSize > 12) {
                                    fontSize--
                                    saveSettings()  // ✅ 实时保存
                                }
                            }) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }
                            Text("$fontSize", fontSize = 16.sp)
                            IconButton(onClick = { 
                                if (fontSize < 32) {  // ✅ 扩大范围到32sp
                                    fontSize++
                                    saveSettings()  // ✅ 实时保存
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Increase")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("关闭")
                    }
                }
            )
        }
        
        // ✅ 目录对话框
        if (showToc && tableOfContents.isNotEmpty()) {
            // ✅ 计算总页数和当前页的章节
            val totalTocPages = (tableOfContents.size + tocPageSize - 1) / tocPageSize
            val startIndex = tocCurrentPage * tocPageSize
            val endIndex = minOf(startIndex + tocPageSize, tableOfContents.size)
            val currentPageChapters = tableOfContents.subList(startIndex, endIndex)
            
            AlertDialog(
                onDismissRequest = { showToc = false },
                title = { Text("目录 (${tableOfContents.size}章)") },
                text = {
                    Column {
                        // ✅ 模糊搜索章节
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = tocJumpInput,
                                onValueChange = { 
                                    // ✅ 允许输入任意字符，用于模糊搜索
                                    tocJumpInput = it
                                },
                                label = { Text("搜索章节") },
                                placeholder = { Text("输入章节名或编号") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                trailingIcon = {
                                    if (tocJumpInput.isNotEmpty()) {
                                        IconButton(onClick = { tocJumpInput = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                                        }
                                    }
                                }
                            )
                            Button(
                                onClick = {
                                    if (tocJumpInput.isEmpty()) return@Button
                                    
                                    // ✅ 智能搜索：支持阿拉伯数字、中文数字、章节名
                                    val searchQuery = tocJumpInput.trim()
                                    var foundIndex = -1
                                    
                                    // 1. 尝试精确匹配编号(阿拉伯数字)
                                    val chapterNum = searchQuery.toIntOrNull()
                                    if (chapterNum != null && chapterNum in 1..tableOfContents.size) {
                                        foundIndex = chapterNum - 1
                                    } else {
                                        // 2. 智能匹配：提取章节标题中的数字进行比较
                                        val searchNum = searchQuery.toIntOrNull()
                                        if (searchNum != null) {
                                            // 用户输入的是数字，尝试匹配所有章节的数字
                                            for ((index, chapter) in tableOfContents.withIndex()) {
                                                val chapterNumInTitle = extractChapterNumber(chapter.first)
                                                if (chapterNumInTitle == searchNum) {
                                                    foundIndex = index
                                                    break
                                                }
                                            }
                                        }
                                        
                                        // 3. 如果还没找到，尝试模糊匹配章节名
                                        if (foundIndex < 0) {
                                            for ((index, chapter) in tableOfContents.withIndex()) {
                                                if (chapter.first.contains(searchQuery, ignoreCase = true)) {
                                                    foundIndex = index
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    
                                    if (foundIndex >= 0) {
                                        val (title, lineNumber) = tableOfContents[foundIndex]
                                        // ✅ 计算该章节在目录的哪一页
                                        val targetTocPage = foundIndex / tocPageSize
                                        tocCurrentPage = targetTocPage
                                        searchedChapterIndex = foundIndex  // ✅ 保存搜索结果
                                        
                                        addLog("[TextReader] Found chapter '$title' at page ${targetTocPage + 1}")
                                        
                                        // ✅ 不进入正文，只是定位到目录页
                                        // 用户需要手动点击章节标题才能跳转
                                    } else {
                                        searchedChapterIndex = null  // ✅ 清空搜索结果
                                        addLog("[TextReader] Chapter not found: '$searchQuery'")
                                    }
                                },
                                enabled = tocJumpInput.isNotEmpty()
                            ) {
                                Text("搜索")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // ✅ 目录分页指示器
                        if (totalTocPages > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 ${tocCurrentPage + 1} / $totalTocPages 页",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { if (tocCurrentPage > 0) tocCurrentPage-- },
                                        enabled = tocCurrentPage > 0
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "上一页")
                                    }
                                    IconButton(
                                        onClick = { if (tocCurrentPage < totalTocPages - 1) tocCurrentPage++ },
                                        enabled = tocCurrentPage < totalTocPages - 1
                                    ) {
                                        Icon(Icons.Default.ArrowForward, contentDescription = "下一页")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(currentPageChapters) { (title, lineNumber) ->
                                // ✅ 计算当前章节的全局索引
                                val globalIndex = startIndex + currentPageChapters.indexOf(Pair(title, lineNumber))
                                val isSearched = searchedChapterIndex == globalIndex
                                
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSearched) FontWeight.Bold else FontWeight.Normal,  // ✅ 搜索结果加粗
                                    color = if (isSearched) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,  // ✅ 搜索结果用不同颜色
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSearched) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else Color.Transparent  // ✅ 搜索结果添加背景
                                        )
                                        .clickable {
                                            // ✅ 跳转到对应章节
                                            if (readMode == "PAGE") {
                                                // 翻页模式：计算章节所在页
                                                val targetPage = lineNumber / 20  // 每页20行
                                                currentPage = targetPage.coerceIn(0, totalPages - 1)
                                                addLog("[TextReader] Jump to chapter '$title' at page ${currentPage + 1}")
                                            } else {
                                                // TODO: 滑动模式需要LazyColumn支持滚动到指定位置
                                                addLog("[TextReader] Jump to chapter '$title' at line $lineNumber")
                                            }
                                            showToc = false
                                            searchedChapterIndex = null  // ✅ 清空搜索结果
                                        }
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { 
                        showToc = false
                        tocCurrentPage = 0  // 重置目录页
                        tocJumpInput = ""   // 清空输入
                        searchedChapterIndex = null  // ✅ 清空搜索结果
                    }) {
                        Text("关闭")
                    }
                }
            )
        }
    }
}

@Composable
fun ThemeButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color
) {
    Card(
        modifier = Modifier
            .width(80.dp)
            .height(60.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else backgroundColor
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) Color.White else textColor,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
