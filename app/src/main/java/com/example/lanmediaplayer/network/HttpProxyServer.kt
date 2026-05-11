package com.lanmedia.player.network

import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import kotlin.text.Charsets

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
     * ✅ 新增: 生成缩略图 URL (只读取前 256KB)
     */
    fun getThumbnailUrl(filePath: String): String {
        val encodedPath = PathManager.encodeForHttp(filePath)
        
        val host = if (allowExternalConnections) {
            getLocalIpAddress()?.hostAddress ?: "127.0.0.1"
        } else {
            "127.0.0.1"
        }
        
        // ✅ 添加 thumbnail=1 参数标识缩略图请求
        return "http://$host:$currentPort/$encodedPath?thumbnail=1"
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
                val fullPath = parts[1].substring(1)  // 去掉前导 /
                
                // ✅ 解析 URL 参数 (如 ?thumbnail=1)
                val (encodedPath, isThumbnail) = if (fullPath.contains("?")) {
                    val pathAndQuery = fullPath.split("?", limit = 2)
                    val queryParams = pathAndQuery.getOrNull(1) ?: ""
                    val isThumb = queryParams.contains("thumbnail=1")
                    Pair(pathAndQuery[0], isThumb)
                } else {
                    Pair(fullPath, false)
                }
                
                val filePath = PathManager.decodeFromHttp(encodedPath)
                
                if (isThumbnail) {
                    log("🖼️ Thumbnail request: $filePath")
                }
                
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
                    // ✅ 如果是缩略图请求,传递 isThumbnail=true
                    handleFullRequest(socket.getOutputStream(), fileProvider, filePath, fileSize, contentType, method == "HEAD", isThumbnail)
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
        isHead: Boolean,
        isThumbnail: Boolean = false  // ✅ 新增参数: 是否为缩略图请求
    ) {
        // HEAD 请求只返回头信息
        if (isHead) {
            sendHeadersOnly(outputStream, 200, fileSize, contentType)
            return
        }
        
        // ✅ 缩略图模式: 直接流式读取前 256KB,不等待缓存
        if (isThumbnail) {
            log("🖼️ Thumbnail mode: reading first 256KB of $filePath")
            handleThumbnailRequest(outputStream, fileProvider, filePath, contentType)
            return
        }
        
        // 优先级1：检查外部缓存（预加载的图片）
        val cache = externalImageCacheProvider?.invoke()
        log("🔍 Cache check: cache=${cache != null}, size=${cache?.size ?: 0}, looking for: '$filePath'")
        
        // ✅ 调试：打印缓存中的所有键
        cache?.keys?.let { keys ->
            if (keys.isNotEmpty()) {
                log("🔍 Cache keys sample: ${keys.take(3).joinToString(", ")}")
            }
        }
        
        val cachedData = cache?.get(filePath)
        if (cachedData != null) {
            log("✅ Cache HIT: $filePath (${cachedData.size / 1024}KB) - NO WAIT")
            sendCachedData(outputStream, cachedData, contentType)
            return
        } else {
            log("❌ Cache MISS: $filePath (cache size: ${cache?.size ?: 0})")
            
            // ✅ FTP/SMB 友好：如果缓存未命中，等待 800ms 让预加载完成
            if (filePath.endsWith(".jpg", true) || filePath.endsWith(".jpeg", true) || 
                filePath.endsWith(".png", true) || filePath.endsWith(".gif", true)) {
                log("⏳ Waiting 800ms for preload to complete...")
                val waitStart = System.currentTimeMillis()
                kotlinx.coroutines.delay(800)
                val waitEnd = System.currentTimeMillis()
                
                // 再次检查缓存
                val cacheAfterWait = externalImageCacheProvider?.invoke()
                val cachedDataAfterWait = cacheAfterWait?.get(filePath)
                if (cachedDataAfterWait != null) {
                    log("✅ Cache HIT after ${waitEnd - waitStart}ms wait: $filePath (${cachedDataAfterWait.size / 1024}KB)")
                    sendCachedData(outputStream, cachedDataAfterWait, contentType)
                    return
                }
                log("❌ Still cache MISS after ${waitEnd - waitStart}ms wait, FALLBACK to streaming")
            }
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
     * ✅ 新增: 处理缩略图请求 (只读取前 256KB)
     */
    private suspend fun handleThumbnailRequest(
        outputStream: OutputStream,
        fileProvider: FileProvider,
        filePath: String,
        contentType: String
    ) {
        val thumbnailSize = 256 * 1024L  // 256KB = 262144 bytes
        
        log("🖼️ Thumbnail request: reading first $thumbnailSize bytes of $filePath")
        
        val fileStream = fileProvider.getFileStream(filePath)
        if (fileStream == null) {
            sendError(outputStream, 404, "File Not Found")
            return
        }
        
        try {
            // 发送响应头 (使用固定大小 256KB)
            val responseHeader = buildResponseHeader(200, "OK", thumbnailSize, contentType)
            outputStream.write(responseHeader.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            
            // ✅ 只读取前 256KB
            streamLimitedBytes(outputStream, fileStream, thumbnailSize)
            
            log("✅ Thumbnail sent: ${thumbnailSize / 1024}KB from $filePath")
            
        } catch (e: IOException) {
            handleIoException(e, "Thumbnail stream")
        } finally {
            try { fileStream.close() } catch (_: Exception) {}
        }
    }
    
    /**
     * ✅ 新增: 流式传输指定字节数 (用于缩略图)
     */
    private suspend fun streamLimitedBytes(outputStream: OutputStream, inputStream: InputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)  // 64KB 缓冲区
        var totalBytesRead = 0L
        var bytesRead: Int
        
        inputStream.use { input ->
            while (totalBytesRead < maxBytes) {
                // 计算本次最多读取多少字节
                val remaining = maxBytes - totalBytesRead
                val readSize = minOf(buffer.size.toLong(), remaining).toInt()
                
                bytesRead = input.read(buffer, 0, readSize)
                if (bytesRead == -1) break  // 文件结束
                
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush()
                totalBytesRead += bytesRead
            }
        }
        
        log("📤 Limited stream completed: $totalBytesRead bytes sent (max: $maxBytes)")
    }
    
    /**
     * 流式传输文件（边读边发）
     */
    private suspend fun streamFile(outputStream: OutputStream, inputStream: InputStream, expectedSize: Long) {
        val buffer = ByteArray(64 * 1024)  // 64KB 缓冲区
        var totalBytesRead = 0L
        var bytesRead: Int
        
        log("📤 Starting stream: expected $expectedSize bytes")
        
        inputStream.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush()
                totalBytesRead += bytesRead
            }
        }
        
        log("✅ Streamed $totalBytesRead bytes (expected: $expectedSize)")
        
        // ✅ 验证完整性
        if (totalBytesRead != expectedSize) {
            log("⚠️ WARNING: Size mismatch! Read $totalBytesRead but expected $expectedSize")
        }
    }
    
    private fun getFileSizeWithCache(filePath: String, fileProvider: FileProvider): Long {
        // ✅ 优先级1：检查外部缓存（预加载的图片），直接从缓存获取大小
        val cache = externalImageCacheProvider?.invoke()
        cache?.get(filePath)?.let { cachedData ->
            val size = cachedData.size.toLong()
            log("📏 Cache size hit: $filePath ($size bytes)")
            fileSizeCache[filePath] = size  // 也存入文件尺寸缓存
            return size
        }
        
        // 优先级2：检查文件尺寸缓存
        fileSizeCache[filePath]?.let {
            return it
        }
        
        // 优先级3：查询并缓存
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
