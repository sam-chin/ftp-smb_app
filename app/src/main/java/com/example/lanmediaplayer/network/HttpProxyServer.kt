package com.lanmedia.player.network

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class HttpProxyServer(private val logCallback: ((String) -> Unit)? = null) {
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var currentPort: Int = 0
    
    private fun log(message: String) {
        println(message)
        logCallback?.invoke(message)
    }
    
    interface FileProvider {
        suspend fun getFileStream(path: String, startOffset: Long = 0): InputStream?
        suspend fun getFileSize(path: String): Long
    }
    
    fun start(port: Int = 0, fileProvider: FileProvider): Int {
        return try {
            // ✅ 显式获取本地IPv4地址并绑定（解决小米澎湃OS问题）
            val localIpv4Address = java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
                ?.find { 
                    it is java.net.Inet4Address && 
                    !it.isLoopbackAddress && 
                    !it.isAnyLocalAddress &&
                    it.hostAddress.startsWith("192.168.")  // 优先选择局域网IP
                } ?: java.net.InetAddress.getByName("0.0.0.0")
            
            log("[HTTP Proxy] Binding to IPv4 address: ${localIpv4Address.hostAddress}")
            
            // ✅ 如果指定端口失败，尝试随机端口
            serverSocket = try {
                if (port > 0) {
                    ServerSocket(port, 50, localIpv4Address)
                } else {
                    ServerSocket(0, 50, localIpv4Address)
                }
            } catch (e: java.net.BindException) {
                log("[HTTP Proxy] Port $port is in use, trying random port...")
                ServerSocket(0, 50, localIpv4Address)
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
                            handleClient(clientSocket, fileProvider)
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
    
    private suspend fun handleClient(clientSocket: Socket, fileProvider: FileProvider) {
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
                handleFullRequest(outputStream, fileProvider, filePath, fileSize, contentType, isHeadRequest)
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
        isHead: Boolean = false  // ✅ 支持HEAD请求
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
        try {
            fileStream = fileProvider.getFileStream(filePath)
            if (fileStream == null) {
                log("[HTTP Proxy] Failed to get file stream")
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }
            
            log("[HTTP Proxy] File stream opened, size: $fileSize, starting to send data...")
            
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            outputStream.write(responseHeader.toByteArray())
            outputStream.flush()
            
            // Stream the file in chunks for progressive playback
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var bytesRead: Int
            var totalBytesRead = 0L
            while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush()
                totalBytesRead += bytesRead
            }
            
            log("[HTTP Proxy] Sent $totalBytesRead bytes")
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
            
            val responseHeader = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Content-Range: bytes $start-$end/$fileSize\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            outputStream.write(responseHeader.toByteArray())
            outputStream.flush()
            
            // Stream the requested range
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var remainingBytes = contentLength
            var totalSent = 0L
            while (remainingBytes > 0) {
                val bytesRead = fileStream.read(buffer)
                if (bytesRead == -1) break
                
                val bytesToWrite = minOf(bytesRead.toLong(), remainingBytes).toInt()
                outputStream.write(buffer, 0, bytesToWrite)
                outputStream.flush()
                remainingBytes -= bytesToWrite
                totalSent += bytesToWrite
            }
            
            log("[HTTP Proxy] Range request completed, sent $totalSent bytes")
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
        
        // ✅ 不进行URL编码，让Kodi直接访问原始路径
        // HTTP代理会自动处理路径解析
        val url = "http://127.0.0.1:$currentPort/$cleanPath"
        log("[HTTP Proxy] Generated URL: $url")
        return url
    }
}
