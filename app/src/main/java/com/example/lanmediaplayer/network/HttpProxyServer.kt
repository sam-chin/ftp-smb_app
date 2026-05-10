package com.lanmedia.player.network

import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.Charsets
import java.util.Collections

/**
 * HTTP 代理服务器
 * 
 * 职责：
 * - 将 SMB/FTP 文件流转换为 HTTP 流
 * - 支持本地预览和 DLNA 投屏两种模式
 * - 流式传输（边读边发，不阻塞）
 * 
 * 设计原则：
 * - 单一职责：只做流转发，不缓存内容
 * - 低耦合：通过 FileProvider 接口获取文件
 * - 线程安全：使用 synchronized 保护共享状态
 */
class HttpProxyServer(
    private val logCallback: ((String) -> Unit)? = null,
    private val allowExternalConnections: Boolean = false
) {
    
    interface FileProvider {
        suspend fun getFileStream(path: String, startOffset: Long = 0): InputStream?
        suspend fun getFileSize(path: String): Long
    }
    
    // 服务器状态
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var currentPort: Int = 0
    
    // 文件尺寸缓存（避免重复查询）
    private val fileSizeCache = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // 外部图片缓存提供者（由 MediaController 提供，只读）
    var externalImageCacheProvider: (() -> Map<String, ByteArray>)? = null
    
    /**
     * 启动 HTTP 代理服务器
     * @param port 端口号（0=自动分配）
     * @param fileProvider 文件提供者
     * @return 实际监听的端口号，-1 表示失败
     */
    fun start(port: Int = 0, fileProvider: FileProvider): Int {
        return try {
            // 如果已经在运行，直接返回
            if (serverSocket != null && !serverSocket?.isClosed!!) {
                log("⚠️ Server already running on port $currentPort")
                return currentPort
            }
            
            // 选择监听地址
            val bindAddress = if (allowExternalConnections) {
                getLocalIpAddress() ?: InetAddress.getByName("0.0.0.0")
            } else {
                InetAddress.getByName("127.0.0.1")
            }
            
            // 创建 ServerSocket
            serverSocket = ServerSocket(port, 50, bindAddress)
            currentPort = serverSocket?.localPort ?: 0
            
            log("✅ HTTP Proxy started on ${bindAddress.hostAddress}:$currentPort")
            log("   Mode: ${if (allowExternalConnections) "DLNA" else "Local"}")
            
            // 启动接受连接的协程
            serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            serverScope?.launch {
                acceptConnections(fileProvider)
            }
            
            currentPort
        } catch (e: Exception) {
            log("❌ Failed to start proxy: ${e.message}")
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * 停止 HTTP 代理服务器
     */
    fun stop() {
        try {
            serverScope?.cancel()
            serverSocket?.close()
            serverSocket = null
            serverScope = null
            fileSizeCache.clear()
            log("🛑 HTTP Proxy stopped")
        } catch (e: Exception) {
            log("❌ Stop error: ${e.message}")
        }
    }
    
    /**
     * 生成 HTTP URL
     */
    fun getUrl(filePath: String): String {
        val encodedPath = PathManager.encodeForHttp(filePath)
        
        val host = if (allowExternalConnections) {
            getLocalIpAddress()?.hostAddress ?: "127.0.0.1"
        } else {
            "127.0.0.1"
        }
        
        return "http://$host:$currentPort/$encodedPath"
    }
    
    /**
     * 检查是否允许外部连接
     */
    fun isAllowingExternalConnections(): Boolean = allowExternalConnections
    
    /**
     * 获取当前端口
     */
    fun getPort(): Int = currentPort
    
    // ==================== 内部实现 ====================
    
    private suspend fun acceptConnections(fileProvider: FileProvider) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(clientSocket, fileProvider)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        log("❌ Accept error: ${e.message}")
                    }
                }
            }
        }.join()
    }
    
    private suspend fun handleClient(clientSocket: Socket, fileProvider: FileProvider) {
        clientSocket.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return@use
                
                log("📨 Request: $requestLine")
                
                // 解析请求行
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendError(socket.getOutputStream(), 400, "Bad Request")
                    return@use
                }
                
                val method = parts[0]
                val encodedPath = parts[1].substring(1)  // 去掉前导 /
                val filePath = PathManager.decodeFromHttp(encodedPath)
                
                // 读取 Headers
                val headers = readHeaders(reader)
                
                // 获取文件大小（带缓存）
                val fileSize = getFileSizeWithCache(filePath, fileProvider)
                if (fileSize <= 0) {
                    sendError(socket.getOutputStream(), 404, "File Not Found")
                    return@use
                }
                
                val contentType = detectContentType(filePath)
                
                // 处理 Range 请求或完整请求
                val rangeHeader = headers["Range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    handleRangeRequest(socket.getOutputStream(), fileProvider, filePath, fileSize, rangeHeader, contentType)
                } else {
                    handleFullRequest(socket.getOutputStream(), fileProvider, filePath, fileSize, contentType, method == "HEAD")
                }
                
            } catch (e: Exception) {
                log("❌ Handle client error: ${e.message}")
                e.printStackTrace()
                try {
                    sendError(socket.getOutputStream(), 500, "Internal Server Error")
                } catch (_: Exception) {}
            }
        }
    }
    
    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var line: String?
        
        do {
            line = reader.readLine()
            if (line?.isNotEmpty() == true) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].trim()] = parts[1].trim()
                }
            }
        } while (line?.isNotEmpty() == true)
        
        return headers
    }
    
    private suspend fun handleFullRequest(
        outputStream: OutputStream,
        fileProvider: FileProvider,
        filePath: String,
        fileSize: Long,
        contentType: String,
        isHead: Boolean
    ) {
        // HEAD 请求只返回头信息
        if (isHead) {
            sendHeadersOnly(outputStream, 200, fileSize, contentType)
            return
        }
        
        // 优先级1：检查外部缓存（预加载的图片）
        val cachedData = externalImageCacheProvider?.invoke()?.get(filePath)
        if (cachedData != null) {
            log("🚀 Cache hit: $filePath (${cachedData.size / 1024}KB)")
            sendCachedData(outputStream, cachedData, contentType)
            return
        }
        
        // 优先级2：流式传输（边读边发）
        log("📡 Streaming: $filePath (${fileSize / 1024}KB)")
        
        val fileStream = fileProvider.getFileStream(filePath)
        if (fileStream == null) {
            sendError(outputStream, 404, "File Not Found")
            return
        }
        
        try {
            // 发送响应头
            val responseHeader = buildResponseHeader(200, "OK", fileSize, contentType)
            outputStream.write(responseHeader.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            
            // 流式传输（64KB 缓冲区）
            streamFile(outputStream, fileStream, fileSize)
            
        } catch (e: IOException) {
            handleIoException(e, "Stream")
        } finally {
            try { fileStream.close() } catch (_: Exception) {}
        }
    }
    
    private suspend fun handleRangeRequest(
        outputStream: OutputStream,
        fileProvider: FileProvider,
        filePath: String,
        fileSize: Long,
        rangeHeader: String,
        contentType: String
    ) {
        // 解析 Range: bytes=start-end
        val range = rangeHeader.substring(6)
        val parts = range.split("-")
        
        val start = parts[0].toLongOrNull() ?: 0L
        val end = if (parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: (fileSize - 1)
        } else {
            fileSize - 1
        }
        
        // 验证范围
        if (start >= fileSize || start > end) {
            sendError(outputStream, 416, "Range Not Satisfiable")
            return
        }
        
        val contentLength = end - start + 1
        
        // 获取文件流（从指定偏移开始）
        val fileStream = fileProvider.getFileStream(filePath, startOffset = start)
        if (fileStream == null) {
            sendError(outputStream, 404, "File Not Found")
            return
        }
        
        try {
            // 发送 206 Partial Content 响应
            val responseHeader = buildPartialContentHeader(contentLength, start, end, fileSize, contentType)
            outputStream.write(responseHeader.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            
            // 流式传输
            streamFile(outputStream, fileStream, contentLength)
            
        } catch (e: IOException) {
            handleIoException(e, "Range stream")
        } finally {
            try { fileStream.close() } catch (_: Exception) {}
        }
    }
    
    /**
     * 流式传输文件（边读边发）
     */
    private suspend fun streamFile(outputStream: OutputStream, inputStream: InputStream, expectedSize: Long) {
        val buffer = ByteArray(64 * 1024)  // 64KB 缓冲区
        var totalBytesRead = 0L
        var bytesRead: Int
        
        inputStream.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush()
                totalBytesRead += bytesRead
            }
        }
        
        log("✅ Streamed $totalBytesRead bytes")
    }
    
    private fun getFileSizeWithCache(filePath: String, fileProvider: FileProvider): Long {
        // 检查缓存
        fileSizeCache[filePath]?.let {
            return it
        }
        
        // 查询并缓存
        val size = runBlocking { fileProvider.getFileSize(filePath) }
        if (size > 0) {
            fileSizeCache[filePath] = size
        }
        return size
    }
    
    private fun sendCachedData(outputStream: OutputStream, data: ByteArray, contentType: String) {
        val header = buildResponseHeader(200, "OK", data.size.toLong(), contentType)
        outputStream.write(header.toByteArray(Charsets.UTF_8))
        outputStream.write(data)
        outputStream.flush()
    }
    
    private fun sendHeadersOnly(outputStream: OutputStream, code: Int, fileSize: Long, contentType: String) {
        val header = buildResponseHeader(code, "OK", fileSize, contentType)
        outputStream.write(header.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }
    
    private fun sendError(outputStream: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${message.length}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    message
            
            outputStream.write(response.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        } catch (_: Exception) {}
    }
    
    private fun buildResponseHeader(code: Int, status: String, contentLength: Long, contentType: String): String {
        return "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n" +
                "\r\n"
    }
    
    private fun buildPartialContentHeader(
        contentLength: Long,
        start: Long,
        end: Long,
        totalSize: Long,
        contentType: String
    ): String {
        return "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Content-Range: bytes $start-$end/$totalSize\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n" +
                "\r\n"
    }
    
    private fun handleIoException(e: IOException, context: String) {
        if (e.message?.contains("Connection reset", ignoreCase = true) == true ||
            e.message?.contains("Broken pipe", ignoreCase = true) == true) {
            log("⚠️ Client disconnected during $context (normal)")
        } else {
            log("❌ $context error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun detectContentType(filePath: String): String {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
    
    private fun getLocalIpAddress(): InetAddress? {
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
            ?.find { 
                it is Inet4Address && 
                !it.isLoopbackAddress && 
                !it.isAnyLocalAddress &&
                it.hostAddress.startsWith("192.168.")
            }
    }
    
    private fun log(message: String) {
        println("[HTTP Proxy] $message")
        logCallback?.invoke(message)
    }
}
