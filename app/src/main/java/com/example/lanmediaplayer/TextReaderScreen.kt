@file:OptIn(ExperimentalMaterial3Api::class)

package com.lanmedia.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    
    // ✅ 阅读设置
    var currentTheme by remember { mutableStateOf(TextReaderTheme.LIGHT) }
    var fontSize by remember { mutableStateOf(16) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    
    // ✅ 目录(简单实现:按章节标题提取)
    var tableOfContents by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    
    // 加载文本文件
    LaunchedEffect(textFile) {
        if (textFile == null) return@LaunchedEffect
        
        isLoading = true
        errorMessage = null
        
        try {
            addLog("[TextReader] Loading file: ${textFile.name}")
            addLog("[TextReader] File path: ${textFile.path}")
            
            // ✅ 关键修复：检查SMB连接状态
            if (selectedProtocol is com.lanmedia.player.controller.NetworkProtocol.SMB) {
                val smbClient = mediaController.getSmbClient()
                if (smbClient == null) {
                    errorMessage = "SMB client not initialized"
                    isLoading = false
                    addLog("[TextReader] ❌ SMB client is null")
                    return@LaunchedEffect
                }
                
                if (!smbClient.isConnected()) {
                    errorMessage = "SMB connection lost, please reconnect"
                    isLoading = false
                    addLog("[TextReader] ❌ SMB connection lost")
                    return@LaunchedEffect
                }
                
                // ✅ 检查是否已选择共享目录
                if (!smbClient.hasSelectedShare()) {
                    errorMessage = "No share selected. Please select a shared folder first."
                    isLoading = false
                    addLog("[TextReader] ❌ No SMB share selected")
                    return@LaunchedEffect
                }
            }
            
            // 获取文件流
            val inputStream = mediaController.getFileStream(textFile.path, selectedProtocol)
            
            if (inputStream == null) {
                errorMessage = "Failed to open file (returned null stream)"
                isLoading = false
                addLog("[TextReader] ❌ getFileStream returned null")
                return@LaunchedEffect
            }
            
            // 读取文本内容(支持UTF-8)
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            textContent = content
            
            // 提取目录(简单实现:查找以数字开头的行)
            val toc = mutableListOf<Pair<String, Int>>()
            val lines = content.lines()
            for ((index, line) in lines.withIndex()) {
                if (line.matches(Regex("^\\s*第[一二三四五六七八九十百千万0-9]+章.*")) ||
                    line.matches(Regex("^\\s*Chapter\\s+\\d+.*")) ||
                    line.matches(Regex("^\\s*\\d+[\\.、].*"))) {
                    toc.add(Pair(line.trim(), index))
                }
            }
            tableOfContents = toc
            
            isLoading = false
            addLog("[TextReader] File loaded: ${lines.size} lines, ${toc.size} chapters")
            
        } catch (e: Exception) {
            errorMessage = "Error loading file: ${e.message}"
            isLoading = false
            addLog("[TextReader] Error: ${e.message}")
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
            // 加载指示器
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
        } else if (errorMessage != null) {
            // 错误提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMessage!!, color = Color.Red, fontSize = 16.sp)
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
                
                // 文本内容区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = textContent,
                        color = textColor,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.8).sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    )
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
        
        // ✅ 设置对话框
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("阅读设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
