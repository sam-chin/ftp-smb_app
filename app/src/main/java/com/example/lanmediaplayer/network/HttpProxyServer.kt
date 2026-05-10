package com.lanmedia.player.network

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class HttpProxyServer(private val logCallback: ((String) -> Unit)? = null, private val allowExternalConnections: Boolean = false) {
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var currentPort: Int = 0
    
    // ✅ 公开属性，让外部可以检查当前模式
    fun isAllowingExternalConnections(): Boolean = allowExternalConnections
    
    // ✅ 图片数据缓存（用于加速重复访问）
    private val imageCache = mutableMapOf<String, ByteArray>()
    private val maxCacheSize = 100 * 1024 * 1024  // ✅ 增加缓存到100MB
    private var currentCacheSize = 0L
    
    // ✅ 引用外部图片数据缓存（由MediaController提供，用于本地预览）
    var externalImageCacheProvider: (() -> Map<String, ByteArray>)? = null
    
    private fun log(message: String) {
        println(message)
        logCallback?.invoke(message)
    }
    
    interface FileProvider {
        suspend fun getFileStream(path: String, startOffset: Long = 0): InputStream?
        suspend fun getFileSize(path: String): Long
    }
    
    // ✅ 缓存管理方法
    private fun addToCache(path: String, data: ByteArray) {
        val dataSize = data.size.toLong()
        
        // 如果超过最大缓存，清理旧数据（简单策略：清空所有）
        if (currentCacheSize + dataSize > maxCacheSize) {
            log("[HTTP Proxy] Cache full ($currentCacheSize bytes), clearing...")
            imageCache.clear()
            currentCacheSize = 0
        }
        
        imageCache[path] = data
        currentCacheSize += dataSize
        log("[HTTP Proxy] Cached image: $path (${dataSize / 1024}KB), total cache: ${currentCacheSize / 1024}KB")
    }
    
    private fun getFromCache(path: String): ByteArray? {
        val cached = imageCache[path]
        if (cached != null) {
            log("[HTTP Proxy] ✅ Cache hit for: $path")
        }
        return cached
    }
    
    fun clearCache() {
        imageCache.clear()
        currentCacheSize = 0
        log("[HTTP Proxy] Cache cleared")
    }
    
    // ✅ 生成HTTP代理URL（用于视频播放和图片预览）
    fun getUrl(filePath: String): String {
        val cleanPath = if (filePath.startsWith("/")) filePath.substring(1) else filePath
        val encodedPath = try {
            java.net.URLEncoder.encode(cleanPath, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            cleanPath
        }
        // ✅ 本地模式使用127.0.0.1，DLNA模式使用局域网IP
        val host = if (allowExternalConnections) {
            // DLNA模式：需要获取局域网IP
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
                ?.find { 
                    it is java.net.Inet4Address && 
                    !it.isLoopbackAddress && 
                    !it.isAnyLocalAddress &&
                    it.hostAddress.startsWith("192.168.")
                }?.hostAddress ?: "127.0.0.1"
        } else {
            // 本地模式：使用127.0.0.1
            "127.0.0.1"
        }
        return "http://$host:$currentPort/$encodedPath"
    }
    
    fun start(port: Int = 0, fileProvider: FileProvider): Int {
        return try {
            // ✅ 如果已经在运行，先停止旧的服务
            if (serverSocket != null && !serverSocket?.isClosed!!) {
                log("[HTTP Proxy] Server already running on port $currentPort, reusing...")
                return currentPort
            }
            
            // ✅ 显式获取本地IPv4地址并绑定（解决小米澎湃OS问题）
            // ✅ 根据allowExternalConnections选择监听地址
            val bindAddress = if (allowExternalConnections) {
                // DLNA投屏模式：监听局域网IP，允许外部设备连接
                java.net.NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
                    ?.find { 
                        it is java.net.Inet4Address && 
                        !it.isLoopbackAddress && 
                        !it.isAnyLocalAddress &&
                        it.hostAddress.startsWith("192.168.")  // 优先选择局域网IP
                    } ?: java.net.InetAddress.getByName("0.0.0.0")
            } else {
                // 本地预览模式：只监听回环地址，禁止外部连接
                java.net.InetAddress.getByName("127.0.0.1")
            }
            
            log("[HTTP Proxy] Binding to: ${bindAddress.hostAddress} (${if (allowExternalConnections) "DLNA mode" else "Local only"})")
            
            // ✅ 如果指定端口失败，尝试随机端口
            serverSocket = try {
                if (port > 0) {
                    ServerSocket(port, 50, bindAddress)
                } else {
                    ServerSocket(0, 50, bindAddress)
                }
            } catch (e: java.net.BindException) {
                log("[HTTP Proxy] Port $port is in use, trying random port...")
                ServerSocket(0, 50, bindAddress)
            }
            
            currentPort = serverSocket?.localPort ?: 0
            val localAddr = serverSocket?.inetAddress?.hostAddress
            log("[HTTP Proxy] ===== SERVER STARTED =====")
            log("[HTTP Proxy] Bound to: $localAddr:$currentPort (IPv4)")
            log("[HTTP Proxy] Accepting connections from: ALL interfaces")
            log("[HTTP Proxy] Localhost URL: http://127.0.0.1:$currentPort/")
            log("[HTTP Proxy] Network URL: http://$localAddr:$currentPort/")
            log("[HTTP Proxy] ===========================")
            log("[HTTP Proxy]")
            log("[HTTP Proxy] ⚠️ IMPORTANT: If external devices cannot connect:")
            log("[HTTP Proxy] For Xiaomi HyperOS/MIUI users:")
            log("[HTTP Proxy] 1. Settings → Additional Settings → Developer Options → Disable 'MIUI Optimization'")
            log("[HTTP Proxy] 2. Settings → Apps → Manage Apps → Your App → Battery Saver → No restrictions")
            log("[HTTP Proxy] 3. Settings → Connection & Sharing → Private DNS → Off")
            log("[HTTP Proxy] 4. Security App → Network Assistant → Allow LAN access")
            log("[HTTP Proxy] 5. WLAN → WLAN Assistant → Disable 'Smart network acceleration'")
            log("[HTTP Proxy]")
            log("[HTTP Proxy] For other Android devices:")
            log("[HTTP Proxy] 1. Check Android firewall settings")
            log("[HTTP Proxy] 2. Disable Private DNS (Settings → Network → Private DNS → Off)")
            log("[HTTP Proxy] 3. Check if any third-party firewall app is blocking port $currentPort")
            log("[HTTP Proxy] 4. Try disabling 'USB debugging network restrictions' in Developer Options")
            log("[HTTP Proxy]")
            serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            serverScope?.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        val remoteAddress = clientSocket.remoteSocketAddress
                        val localAddress = clientSocket.localSocketAddress
                        
                        // ✅ 检查是否是外部设备连接
                        val remoteIp = remoteAddress.toString().split(":")[0].substring(1)
                        val isExternalConnection = !remoteIp.startsWith("127.0.0.1") && !remoteIp.startsWith("::1")
                        
                        log("[HTTP Proxy] ===== NEW CONNECTION =====")
                        log("[HTTP Proxy] Remote: $remoteAddress")
                        log("[HTTP Proxy] Local: $localAddress")
                        if (isExternalConnection) {
                            log("[HTTP Proxy] ✅ EXTERNAL device connected: $remoteIp")
                            log("[HTTP Proxy] This means HTTP proxy is accessible from network!")
                        } else {
                            log("[HTTP Proxy] Local connection (self-test)")
                        }
                        log("[HTTP Proxy] ==========================")
                        launch {
                            handleClient(clientSocket, fileProvider, isExternalConnection)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            log("[HTTP Proxy] Error accepting connection: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            currentPort
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    private suspend fun handleClient(clientSocket: Socket, fileProvider: FileProvider, isExternalConnection: Boolean = false) {
        try {
            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            val requestLine = reader.readLine() ?: return
            
            log("[HTTP Proxy] Received request: $requestLine")
            
            // ✅ 支持 GET 和 HEAD 方法
            val isHeadRequest = requestLine.startsWith("HEAD")
            val isGetRequest = requestLine.startsWith("GET")
            
            if (!isGetRequest && !isHeadRequest) {
                log("[HTTP Proxy] Method not allowed: $requestLine")
                sendErrorResponse(outputStream, 405, "Method Not Allowed")
                clientSocket.close()
                return
            }
            
            if (isHeadRequest) {
                log("[HTTP Proxy] Handling HEAD request (for URL accessibility test)")
            }
            
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendErrorResponse(outputStream, 400, "Bad Request")
                clientSocket.close()
                return
            }
            
            // Extract and decode URL-encoded path
            val encodedPath = parts[1].substring(1) // Remove leading /
            log("[HTTP Proxy] Encoded path: $encodedPath")
            
            val filePath = try {
                val decodedPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                log("[HTTP Proxy] Decoded file path: $decodedPath")
                decodedPath
            } catch (e: Exception) {
                log("[HTTP Proxy] URL decode failed: ${e.message}")
                encodedPath // Fallback to original if decoding fails
            }
            
            log("[HTTP Proxy] Final file path passed to provider: '$filePath'")
            
            // Read all headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            do {
                line = reader.readLine()
                if (line?.isNotEmpty() == true) {
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].trim()] = headerParts[1].trim()
                    }
                }
            } while (line?.isNotEmpty() == true)
            
            log("[HTTP Proxy] Calling getFileSize for path: '$filePath'")
            val fileSize = fileProvider.getFileSize(filePath)
            log("[HTTP Proxy] File size result: $fileSize")
            
            if (fileSize <= 0) {
                log("[HTTP Proxy] File not found or size is 0")
                sendErrorResponse(outputStream, 404, "File Not Found")
                clientSocket.close()
                return
            }
            
            // Detect content type based on file extension
            val contentType = detectContentType(filePath)
            log("[HTTP Proxy] Content type: $contentType")
            
            // Check for Range request (for seeking support)
            val rangeHeader = headers["Range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // Handle range request
                handleRangeRequest(outputStream, fileProvider, filePath, fileSize, rangeHeader, contentType, isHeadRequest)
            } else {
                // Handle full file request
                handleFullRequest(outputStream, fileProvider, filePath, fileSize, contentType, isHeadRequest, isExternalConnection)
            }
            
            clientSocket.close()
            
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 500, "Internal Server Error")
            } catch (e2: Exception) {
                e2.printStackTrace()
            } finally {
                clientSocket.close()
            }
        }
    }
    
    private suspend fun handleFullRequest(
        outputStream: OutputStream,
        fileProvider: FileProvider,
        filePath: String,
        fileSize: Long,
        contentType: String,
        isHead: Boolean = false,
        isExternalConnection: Boolean = false  // ✅ 区分本地和外部连接
    ) {
        log("[HTTP Proxy] Handling ${if (isHead) "HEAD" else "full"} request for: $filePath")
        
        // ✅ HEAD请求只返回头信息，不获取文件流
        if (isHead) {
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            outputStream.write(responseHeader.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            log("[HTTP Proxy] HEAD response sent (headers only)")
            return
        }
        
        var fileStream: InputStream? = null
        var totalBytesRead = 0L
        try {
            // ✅ 优先级1：检查外部缓存（MediaController预加载的本地缓存）
            // ⚠️ 关键修复：只对本地连接使用预加载缓存，避免DLNA设备占用SMB连接
            val externalCache = if (!isExternalConnection) externalImageCacheProvider?.invoke() else null
            var externalCachedData: ByteArray? = null
            
            if (contentType.startsWith("image/") && externalCache != null) {
                // 尝试两种路径格式（带/和不带/）
                externalCachedData = externalCache[filePath]
                if (externalCachedData == null && filePath.startsWith("/")) {
                    externalCachedData = externalCache[filePath.substring(1)]
                } else if (externalCachedData == null && !filePath.startsWith("/")) {
                    externalCachedData = externalCache["/$filePath"]
                }
            }
            
            if (externalCachedData != null) {
                log("[HTTP Proxy] 🚀 Sending from external cache (preloaded): ${externalCachedData.size} bytes")
                
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${externalCachedData.size}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.write(externalCachedData)
                outputStream.flush()
                
                // ✅ 同时存入内部缓存，避免重复从externalCache读取
                if (contentType.startsWith("image/")) {
                    addToCache(filePath, externalCachedData)
                }
                
                log("[HTTP Proxy] ✅ Sent from external cache successfully")
                return
            }
            
            // ✅ 优先级2：检查内部缓存
            val cachedData = if (contentType.startsWith("image/")) getFromCache(filePath) else null
            
            if (cachedData != null) {
                // 从缓存发送
                log("[HTTP Proxy] Sending from cache: ${cachedData.size} bytes")
                
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${cachedData.size}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.write(cachedData)
                outputStream.flush()
                
                log("[HTTP Proxy] ✅ Sent from cache successfully")
                return
            }
            
            // 没有缓存，从FTP/SMB读取
            fileStream = fileProvider.getFileStream(filePath)
            if (fileStream == null) {
                log("[HTTP Proxy] Failed to get file stream")
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }
            
            // ✅ 关键修复：对于小文件，先完整读取到内存再发送，避免并发冲突
            val shouldReadFully = fileSize <= 20 * 1024 * 1024  // <=20MB的文件完整读取
            
            if (shouldReadFully) {
                // 策略A：完整读取到内存（适用于小图片）
                log("[HTTP Proxy] Reading entire file to memory (${fileSize / 1024}KB) to avoid concurrent conflicts")
                val fileData = fileStream!!.readBytes()
                fileStream!!.close()  // 立即关闭FTP/SMB连接
                fileStream = null
                
                log("[HTTP Proxy] File loaded to memory, sending ${fileData.size} bytes")
                
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${fileData.size}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.write(fileData)
                outputStream.flush()
                
                totalBytesRead = fileData.size.toLong()
                
                // ✅ 加入缓存
                if (contentType.startsWith("image/")) {
                    addToCache(filePath, fileData)
                }
                
                log("[HTTP Proxy] ✅ Sent $totalBytesRead bytes from memory")
            } else {
                // 策略B：流式传输（适用于大视频）
                log("[HTTP Proxy] Using streaming mode for large file (${fileSize / 1024 / 1024}MB)")
                
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: $fileSize\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.flush()
                
                // ✅ 根据文件类型动态调整缓冲区大小
                val bufferSize = when {
                    contentType.startsWith("video/") -> 256 * 1024  // 视频：256KB
                    contentType.startsWith("audio/") -> 128 * 1024  // 音频：128KB
                    else -> 64 * 1024                                // 其他：64KB
                }
                
                log("[HTTP Proxy] Using buffer size: ${bufferSize / 1024}KB for $contentType")
                
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                while (fileStream!!.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                    totalBytesRead += bytesRead
                }
                
                log("[HTTP Proxy] ✅ Sent $totalBytesRead bytes via streaming")
            }
            
            // ✅ 验证是否发送了完整文件
            if (totalBytesRead == fileSize) {
                log("[HTTP Proxy] ✅ File sent completely ($totalBytesRead/$fileSize bytes)")
            } else {
                log("[HTTP Proxy] ⚠️ WARNING: File incomplete! Sent $totalBytesRead but expected $fileSize bytes")
            }
        } catch (e: Exception) {
            // Ignore connection reset and broken pipe errors - these are normal when client disconnects
            if (e.message?.contains("Connection reset", ignoreCase = true) == true ||
                e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                log("[HTTP Proxy] Client disconnected (normal behavior)")
            } else {
                log("[HTTP Proxy] Error in handleFullRequest: ${e.message}")
                e.printStackTrace()
            }
        } finally {
            // IMPORTANT: Close the file stream to release FTP/SMB resources
            try {
                fileStream?.close()
                log("[HTTP Proxy] File stream closed")
            } catch (e: Exception) {
                log("[HTTP Proxy] Error closing stream: ${e.message}")
            }
        }
    }
    
    private suspend fun handleRangeRequest(
        outputStream: OutputStream,
        fileProvider: FileProvider,
        filePath: String,
        fileSize: Long,
        rangeHeader: String,
        contentType: String,
        isHead: Boolean = false  // ✅ 支持HEAD请求
    ) {
        var fileStream: InputStream? = null
        var totalSent = 0L  // ✅ 在函数开始时声明，供所有分支使用
        try {
            // Parse range: "bytes=start-end" or "bytes=start-"
            val range = rangeHeader.substring(6) // Remove "bytes="
            val rangeParts = range.split("-")
            
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (fileSize - 1)
            } else {
                fileSize - 1
            }
            
            // Validate range
            if (start >= fileSize || start > end) {
                sendErrorResponse(outputStream, 416, "Range Not Satisfiable")
                return
            }
            
            val contentLength = end - start + 1
            log("[HTTP Proxy] Range request: bytes $start-$end/$fileSize (content length: $contentLength)")
            
            // Use startOffset parameter for efficient seeking (FTP REST command)
            fileStream = fileProvider.getFileStream(filePath, startOffset = start)
            if (fileStream == null) {
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }
            
            log("[HTTP Proxy] File stream opened at offset $start")
            
            // ✅ 对于小文件，先完整读取再发送，避免并发冲突
            val shouldReadFully = contentLength <= 20 * 1024 * 1024  // <=20MB
            
            if (shouldReadFully) {
                log("[HTTP Proxy] Reading range to memory (${contentLength / 1024}KB)")
                val fileData = fileStream!!.readBytes()
                fileStream!!.close()
                fileStream = null
                
                log("[HTTP Proxy] Range data loaded, sending ${fileData.size} bytes")
                
                val responseHeader = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${fileData.size}\r\n" +
                        "Content-Range: bytes $start-${start + fileData.size - 1}/$fileSize\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.write(fileData)
                outputStream.flush()
                
                totalSent = fileData.size.toLong()
                log("[HTTP Proxy] ✅ Range request sent from memory ($totalSent bytes)")
            } else {
                // 流式传输大文件
                log("[HTTP Proxy] Using streaming mode for large range request")
                
                val responseHeader = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Content-Range: bytes $start-$end/$fileSize\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                
                outputStream.write(responseHeader.toByteArray())
                outputStream.flush()
                
                val bufferSize = when {
                    contentType.startsWith("video/") -> 256 * 1024
                    contentType.startsWith("audio/") -> 128 * 1024
                    else -> 64 * 1024
                }
                
                val buffer = ByteArray(bufferSize)
                var remainingBytes = contentLength
                while (remainingBytes > 0) {
                    val bytesRead = fileStream!!.read(buffer)
                    if (bytesRead == -1) break
                    
                    val bytesToWrite = minOf(bytesRead.toLong(), remainingBytes).toInt()
                    outputStream.write(buffer, 0, bytesToWrite)
                    outputStream.flush()
                    remainingBytes -= bytesToWrite
                    totalSent += bytesToWrite
                }
                
                log("[HTTP Proxy] ✅ Range request sent via streaming ($totalSent bytes)")
            }
            
            // ✅ 验证Range请求是否发送完整
            if (totalSent == contentLength) {
                log("[HTTP Proxy] ✅ Range request sent completely ($totalSent/$contentLength bytes)")
            } else {
                log("[HTTP Proxy] ⚠️ WARNING: Range request incomplete! Sent $totalSent but expected $contentLength bytes")
            }
        } catch (e: Exception) {
            // Ignore connection reset and broken pipe errors - these are normal when client disconnects
            if (e.message?.contains("Connection reset", ignoreCase = true) == true ||
                e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                log("[HTTP Proxy] Client disconnected during range request (normal behavior)")
            } else {
                log("[HTTP Proxy] Error in handleRangeRequest: ${e.message}")
                e.printStackTrace()
            }
        } finally {
            // IMPORTANT: Close the file stream to release FTP/SMB resources
            try {
                fileStream?.close()
                log("[HTTP Proxy] Range request file stream closed")
            } catch (e: Exception) {
                log("[HTTP Proxy] Error closing stream: ${e.message}")
            }
        }
    }
    
    private fun detectContentType(filePath: String): String {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // Video formats
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            // Audio formats
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "wma" -> "audio/x-ms-wma"
            "m4a" -> "audio/mp4"
            // Image formats
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
            else -> "application/octet-stream"
        }
    }
    
    private fun sendErrorResponse(outputStream: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${message.length}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    message
            
            outputStream.write(response.toByteArray())
            outputStream.flush()
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun stop() {
        try {
            serverScope?.cancel()
            serverSocket?.close()
            serverSocket = null
            serverScope = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getPort(): Int = currentPort
    fun getUrl(path: String): String {
        // Remove leading slash from path to avoid double slashes
        val cleanPath = if (path.startsWith("/")) path.substring(1) else path
        
        // ✅ URL编码文件路径（处理中文和特殊字符）
        val encodedPath = try {
            java.net.URLEncoder.encode(cleanPath, "UTF-8")
                .replace("+", "%20")  // URLEncoder将空格编码为+，需要替换为%20
        } catch (e: Exception) {
            cleanPath
        }
        
        // ✅ 获取本地IPv4地址用于生成URL（解决ExoPlayer无法访问127.0.0.1的问题）
        val localIpv4Address = java.net.NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
            ?.find { 
                it is java.net.Inet4Address && 
                !it.isLoopbackAddress && 
                !it.isAnyLocalAddress &&
                it.hostAddress.startsWith("192.168.")
            }?.hostAddress ?: "127.0.0.1"
        
        // ✅ 使用局域网IP而不是127.0.0.1
        val url = "http://$localIpv4Address:$currentPort/$encodedPath"
        log("[HTTP Proxy] Generated URL: $url")
        return url
    }
}
