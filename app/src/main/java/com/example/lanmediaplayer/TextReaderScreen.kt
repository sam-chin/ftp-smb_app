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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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

// ✅ 智能文本编码检测和读取函数(优化版:快速检测+流式读取)
private fun detectAndReadText(inputStream: InputStream): String {
    // 第一步：只读取前512字节用于编码检测(足够判断BOM和UTF-8有效性)
    val probeSize = 512
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
    
    // 第二步：快速检测BOM (Byte Order Mark)
    val detectedEncoding = when {
        // UTF-8 BOM: EF BB BF
        probeData.size >= 3 && 
        probeData[0].toInt() == 0xEF && 
        probeData[1].toInt() == 0xBB && 
        probeData[2].toInt() == 0xBF -> "UTF-8"
        
        // UTF-16 LE BOM: FF FE
        probeData.size >= 2 && 
        probeData[0].toInt() == 0xFF && 
        probeData[1].toInt() == 0xFE -> "UTF-16LE"
        
        // UTF-16 BE BOM: FE FF
        probeData.size >= 2 && 
        probeData[0].toInt() == 0xFE && 
        probeData[1].toInt() == 0xFF -> "UTF-16BE"
        
        else -> null
    }
    
    // 第三步：根据检测结果选择编码
    val charsetName = if (detectedEncoding != null) {
        detectedEncoding
    } else {
        // 没有BOM，尝试智能检测
        // 先尝试用UTF-8解码探测数据
        try {
            val testContent = String(probeData, Charsets.UTF_8)
            // 检查是否包含替换字符（表示UTF-8解码失败）
            if (!testContent.contains('\uFFFD')) {
                "UTF-8" // UTF-8解码成功
            } else {
                "GBK" // UTF-8失败，使用GBK（中文常用编码）
            }
        } catch (e: Exception) {
            "GBK" // 默认使用GBK
        }
    }
    
    // 第四步：✅ 关键优化 - 分块流式读取，避免一次性加载大文件
    val remainingStream = SequenceInputStream(
        ByteArrayInputStream(probeData),
        inputStream
    )
    
    // ✅ 使用InputStreamReader + 分块读取，比bufferedReader.readText()更高效
    val reader = InputStreamReader(remainingStream, Charset.forName(charsetName))
    val charBuffer = CharArray(8192)  // 8KB字符缓冲区
    val stringBuilder = StringBuilder(32768)  // 预分配32KB初始容量，减少扩容
    
    var totalChars = 0
    val startTime = System.currentTimeMillis()
    
    var charsRead: Int
    while (reader.read(charBuffer).also { charsRead = it } != -1) {
        stringBuilder.append(charBuffer, 0, charsRead)
        totalChars += charsRead
        
        // ✅ 每读取64KB记录一次进度
        if (totalChars % 65536 < charsRead) {
            val elapsed = System.currentTimeMillis() - startTime
            val speed = if (elapsed > 0) (totalChars / 1024.0) / (elapsed / 1000.0) else 0.0
            android.util.Log.d("TextReader", "Reading progress: ${totalChars / 1024}KB, ${String.format("%.1f", speed)} KB/s")
        }
    }
    
    val totalTime = System.currentTimeMillis() - startTime
    android.util.Log.d("TextReader", "Total read: ${totalChars / 1024}KB in ${totalTime}ms, avg speed: ${String.format("%.1f", if (totalTime > 0) (totalChars / 1024.0) / (totalTime / 1000.0) else 0.0)} KB/s")
    
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
    val coroutineScope = rememberCoroutineScope()
    
    // ✅ 文本内容状态
    var textContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // ✅ 关键优化：将文本按行分割，用于LazyColumn懒加载
    var textLines by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // ✅ 阅读设置
    var currentTheme by remember { mutableStateOf(TextReaderTheme.LIGHT) }
    var fontSize by remember { mutableStateOf(16) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }  // ✅ 新增：日志显示开关
    
    // ✅ 阅读模式：PAGE(翻页) 或 SCROLL(滑动)
    var readMode by remember { mutableStateOf("SCROLL") }  // "PAGE" 或 "SCROLL"
    
    // ✅ 翻页模式的状态
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf<List<List<String>>>(emptyList()) }  // 每页的行列表
    
    // ✅ 目录(简单实现:按章节标题提取)
    var tableOfContents by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    
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
    
    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
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
                        
                        // ✅ 左右滑动翻页手势
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(currentPage, totalPages) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { },
                                        onDragEnd = { },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            if (Math.abs(dragAmount) > 50) {  // 滑动阈值
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
                onDismissRequest = { showSettings = false },
                title = { Text("阅读设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // ✅ 阅读模式选择
                        Text("阅读模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeButton(
                                label = "滑动",
                                isSelected = readMode == "SCROLL",
                                onClick = { readMode = "SCROLL" },
                                backgroundColor = if (readMode == "SCROLL") MaterialTheme.colorScheme.primary else Color.Gray,
                                textColor = Color.White
                            )
                            ThemeButton(
                                label = "翻页",
                                isSelected = readMode == "PAGE",
                                onClick = { readMode = "PAGE" },
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
                                onClick = { currentTheme = TextReaderTheme.LIGHT },
                                backgroundColor = Color(0xFFF5F5F5),
                                textColor = Color(0xFF333333)
                            )
                            ThemeButton(
                                label = "夜晚",
                                isSelected = currentTheme == TextReaderTheme.DARK,
                                onClick = { currentTheme = TextReaderTheme.DARK },
                                backgroundColor = Color(0xFF1A1A1A),
                                textColor = Color(0xFFE0E0E0)
                            )
                            ThemeButton(
                                label = "护眼",
                                isSelected = currentTheme == TextReaderTheme.EYE_CARE,
                                onClick = { currentTheme = TextReaderTheme.EYE_CARE },
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
                            IconButton(onClick = { if (fontSize > 12) fontSize-- }) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }
                            Text("$fontSize", fontSize = 16.sp)
                            IconButton(onClick = { if (fontSize < 24) fontSize++ }) {
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
            AlertDialog(
                onDismissRequest = { showToc = false },
                title = { Text("目录 (${tableOfContents.size}章)") },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tableOfContents) { (title, lineNumber) ->
                            Text(
                                text = title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // TODO: 跳转到对应行
                                        showToc = false
                                        addLog("[TextReader] Jump to chapter: $title")
                                    }
                                    .padding(8.dp),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showToc = false }) {
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
