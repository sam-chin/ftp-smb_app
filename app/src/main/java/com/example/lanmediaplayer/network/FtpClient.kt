package com.example.lanmediaplayer.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class FtpFileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val modifiedTime: String? = null
)

class FtpClient(private val logCallback: ((String) -> Unit)? = null) {
    private var controlSocket: Socket? = null
    private var dataSocket: Socket? = null
    private var controlOutputStream: OutputStream? = null
    private var controlInputStream: InputStream? = null
    private var controlReader: BufferedReader? = null
    
    private var host: String = ""
    private var port: Int = 21
    private var username: String = ""
    private var password: String = ""
    private var passiveMode: Boolean = true
    
    // Track server's detected encoding from responses
    private var serverEncoding: Charset? = null
    
    // Mutex to synchronize FTP control connection commands
    private val commandMutex = Mutex()
    
    private fun log(message: String) {
        // Use Android Log with UTF-8 support
        Log.i("FtpClient", message)
        println(message)
        logCallback?.invoke(message)
    }
    
    suspend fun connect(host: String, port: Int = 21): Boolean = withContext(Dispatchers.IO) {
        this@FtpClient.host = host
        this@FtpClient.port = port
        
        return@withContext try {
            log("[FTP] === Starting connection ===")
            log("[FTP] Target: $host:$port")
            
            controlSocket = Socket()
            controlSocket?.soTimeout = 30000 // 30 seconds timeout
            controlSocket?.keepAlive = true
            
            log("[FTP] Attempting socket connection...")
            controlSocket?.connect(java.net.InetSocketAddress(host, port), 30000)
            
            log("[FTP] Socket connected successfully")
            log("[FTP] Local address: ${controlSocket?.localAddress}")
            log("[FTP] Remote address: ${controlSocket?.remoteSocketAddress}")
            
            controlOutputStream = controlSocket?.getOutputStream()
            controlInputStream = controlSocket?.getInputStream()
            // Use ISO-8859-1 as default for control connection (FTP standard)
            // Response messages will be handled separately if they contain Chinese
            controlReader = BufferedReader(InputStreamReader(controlInputStream, Charsets.ISO_8859_1))
            
            log("[FTP] Streams initialized, waiting for server greeting...")
            val response = readResponse()
            log("[FTP] Server greeting: ${response.code} ${response.message}")
            
            val success = response.code in 200..299
            if (success) {
                log("[FTP] === Connection established ===")
                
                // Try to enable UTF-8 support on the server
                try {
                    sendCommand("OPTS UTF8 ON")
                    val utf8Response = readResponse()
                    if (utf8Response.code == 200 || utf8Response.code == 202) {
                        log("[FTP] Server supports UTF-8 encoding")
                        serverEncoding = StandardCharsets.UTF_8
                    } else {
                        log("[FTP] Server does not support UTF-8, will use GBK for Chinese")
                        serverEncoding = Charset.forName("GBK")
                    }
                } catch (e: Exception) {
                    log("[FTP] Failed to query UTF-8 support, defaulting to GBK: ${e.message}")
                    serverEncoding = Charset.forName("GBK")
                }
            } else {
            log("[FTP] === Connection failed with code: ${response.code} ===")
            }
            success
        } catch (e: Exception) {
            log("[FTP] === Connection error ===")
            log("[FTP] Error type: ${e.javaClass.simpleName}")
            log("[FTP] Error message: ${e.message}")
            e.printStackTrace()
            val writer = java.io.PrintWriter(java.io.StringWriter())
            e.printStackTrace(writer)
            log("[FTP] Full stack trace:\n${writer.toString()}")
            disconnect()
            false
        }
    }
    
    suspend fun login(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        this@FtpClient.username = username
        this@FtpClient.password = password
        
        return@withContext try {
            log("[FTP] === Starting login ===")
            log("[FTP] Username: $username")
            log("[FTP] Password length: ${password.length}")
            
            log("[FTP] Sending USER command...")
            sendCommand("USER $username")
            val userResponse = readResponse()
            log("[FTP] USER response: ${userResponse.code} ${userResponse.message}")
            
            if (userResponse.code !in 200..399) {
                log("[FTP] === USER command failed ===")
                return@withContext false
            }
            
            log("[FTP] Sending PASS command...")
            sendCommand("PASS $password")
            val passResponse = readResponse()
            log("[FTP] PASS response: ${passResponse.code} ${passResponse.message}")
            
            val success = passResponse.code in 200..299
            if (success) {
            log("[FTP] === Login successful ===")
            } else {
            log("[FTP] === Login failed with code: ${passResponse.code} ===")
            }
            success
        } catch (e: Exception) {
            log("[FTP] === Login error ===")
            log("[FTP] Error type: ${e.javaClass.simpleName}")
            log("[FTP] Error message: ${e.message}")
            e.printStackTrace()
            val writer = java.io.PrintWriter(java.io.StringWriter())
            e.printStackTrace(writer)
            log("[FTP] Full stack trace:\n${writer.toString()}")
            false
        }
    }
    
    suspend fun listFiles(remotePath: String = "/"): List<FtpFileInfo> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            listFilesInternal(remotePath)
        }
    }
    
    private suspend fun listFilesInternal(remotePath: String = "/"): List<FtpFileInfo> {
        return try {
            log("[FTP] === Listing files START ===")
            log("[FTP] Listing files in: '$remotePath'")
            log("[FTP] Server encoding: $serverEncoding")
            log("[FTP] Control socket connected: ${controlSocket?.isConnected}")
            log("[FTP] Data socket status: ${if (dataSocket == null) "null" else "exists"}")
            
            clearPendingResponses()
            
            sendCommand("TYPE I")
            readResponse()
            
            sendCommand("PASV")
            var pasvResponse = readResponse()
            log("[FTP] PASV response: ${pasvResponse.code} ${pasvResponse.message}")
            
            if (pasvResponse.code != 227) {
                log("[FTP] PASV failed with code: ${pasvResponse.code}, attempting reconnect...")
                if (reconnectControlConnection()) {
                    sendCommand("TYPE I")
                    readResponse()
                    sendCommand("PASV")
                    pasvResponse = readResponse()
                    if (pasvResponse.code != 227) {
                        log("[FTP] PASV still failing after reconnect")
                        return emptyList()
                    }
                } else {
                    return emptyList()
                }
            }
            
            val dataPort = parsePasvPort(pasvResponse.message)
            val dataHost = host
            log("[FTP] Connecting to data socket: $dataHost:$dataPort")
            
            dataSocket = Socket(dataHost, dataPort)
            
            sendCommand("LIST $remotePath")
            log("[FTP] Sent: LIST $remotePath")
            val listResponse = readResponse()
            log("[FTP] LIST response: ${listResponse.code} ${listResponse.message}")
            
            if (listResponse.code !in 100..199) {
                log("[FTP] LIST command failed with code: ${listResponse.code}")
                return emptyList()
            }
            
            val files = mutableListOf<FtpFileInfo>()
            dataSocket?.let { socket ->
                val inputStream = socket.getInputStream()
                
                val bytes = inputStream.readBytes()
                log("[FTP] Read ${bytes.size} bytes from data connection")
                
                val (text, detectedEncoding) = EncodingUtils.decodeWithFallback(bytes)
                log("[FTP] Detected encoding: $detectedEncoding")
                
                val preview = text.take(200).replace("\n", "\\n").replace("\r", "\\r")
                log("[FTP] Decoded text preview: $preview")
                
                val lines = text.lines().filter { it.isNotBlank() }
                log("[FTP] Decoded ${lines.size} lines")
                
                if (lines.isNotEmpty()) {
                    log("[FTP] First line sample: ${lines[0].take(100)}")
                }
                
                for (line in lines) {
                    log("[FTP] Raw line: $line")
                    parseFtpLine(line)?.let { fileInfo ->
                        log("[FTP] Parsed file: ${fileInfo.name}, isDir: ${fileInfo.isDirectory}")
                        files.add(fileInfo)
                    }
                }
            }
            
            log("[FTP] Total files found: ${files.size}")
            val finalResponse = readResponse()
            log("[FTP] Final response: ${finalResponse.code} ${finalResponse.message}")
            log("[FTP] === Listing files END ===")
            files
        } catch (e: Exception) {
            log("[FTP] Error listing files: ${e.message}")
            e.printStackTrace()
            emptyList()
        } finally {
            closeDataConnection()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = getFileStream(remotePath) ?: return@withContext false
            
            val outputStream = FileOutputStream(localFile)
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Get an InputStream for streaming file content without downloading to disk
     * This enables progressive playback and seeking
     * @param remotePath The path to the remote file
     * @param startOffset The byte offset to start reading from (for Range requests)
     */
    suspend fun getFileStream(remotePath: String, startOffset: Long = 0): InputStream? = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            try {
                log("[FTP] Opening stream for: $remotePath (offset: $startOffset)")
                
                // Clear any pending responses before sending new commands
                clearPendingResponses()
                
                sendCommand("TYPE I")
                // Read response and verify it's for TYPE command (code 200)
                var typeResponse: FtpResponse?
                do {
                    typeResponse = readResponse()
                    if (typeResponse.code == 200) {
                        break
                    }
                    log("[FTP] Ignoring unexpected response for TYPE: ${typeResponse.code} ${typeResponse.message}")
                } while (typeResponse != null)
                
                sendCommand("PASV")
                // Read response and verify it's for PASV command (code 227)
                var pasvResponse: FtpResponse?
                do {
                    pasvResponse = readResponse()
                    if (pasvResponse.code == 227) {
                        break
                    }
                    log("[FTP] Ignoring unexpected response for PASV: ${pasvResponse.code} ${pasvResponse.message}")
                } while (pasvResponse != null)
                
                if (pasvResponse?.code != 227) {
                    log("[FTP] PASV failed: ${pasvResponse?.code}")
                    return@withContext null
                }
                
                val dataPort = parsePasvPort(pasvResponse.message)
                // Use the control connection host instead of parsing from PASV response
                // The PASV response may contain encoding issues in the host part
                val dataHost = this@FtpClient.host
                
                log("[FTP] Connecting to data socket: $dataHost:$dataPort")
                dataSocket = Socket(dataHost, dataPort)
                
                // If startOffset > 0, use REST command to resume from that position
                if (startOffset > 0) {
                    sendCommand("REST $startOffset")
                    val restResponse = readResponse()
                    if (restResponse.code !in 300..399) {
                        log("[FTP] REST failed: ${restResponse.code} ${restResponse.message}")
                        closeDataConnection()
                        return@withContext null
                    }
                    log("[FTP] REST successful, will start from byte $startOffset")
                }
                
                sendCommand("RETR $remotePath")
                // Read response and verify it's for RETR command (code 1xx)
                var retrResponse: FtpResponse?
                do {
                    retrResponse = readResponse()
                    if (retrResponse.code in 100..199) {
                        break
                    }
                    log("[FTP] Ignoring unexpected response for RETR: ${retrResponse.code} ${retrResponse.message}")
                } while (retrResponse != null)
                
                if (retrResponse?.code !in 100..199) {
                    log("[FTP] RETR failed: ${retrResponse?.code}")
                    closeDataConnection()
                    return@withContext null
                }
                
                log("[FTP] Stream opened successfully")
                dataSocket?.getInputStream()
            } catch (e: Exception) {
                log("[FTP] Error opening stream: ${e.message}")
                e.printStackTrace()
                closeDataConnection()
                null
            }
        }
    }
    
    /**
     * Get file size using SIZE command
     */
    suspend fun getFileSize(remotePath: String): Long = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            try {
                log("[FTP] Getting file size for: '$remotePath'")
                
                // Clear any pending responses before sending new commands
                clearPendingResponses()
                
                sendCommand("SIZE $remotePath")
                
                // Read responses until we get the SIZE response (code 213)
                var response: FtpResponse?
                do {
                    response = readResponse()
                    if (response.code == 213) {
                        val fileSize = response.message.trim().toLongOrNull() ?: 0L
                        log("[FTP] File size: $fileSize bytes")
                        return@withContext fileSize
                    }
                    // If we get a different response, it might be from a previous command
                    log("[FTP] Ignoring unexpected response for SIZE: ${response.code} ${response.message}")
                } while (response != null)
                
                log("[FTP] SIZE command failed - no valid response")
                0L
            } catch (e: Exception) {
                log("[FTP] Error getting file size: ${e.message}")
                e.printStackTrace()
                0L
            }
        }
    }
    
    fun disconnect() {
        try {
            sendCommand("QUIT")
            readResponse()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            closeDataConnection()
            controlReader?.close()
            controlInputStream?.close()
            controlOutputStream?.close()
            controlSocket?.close()
            controlReader = null
            controlInputStream = null
            controlOutputStream = null
            controlSocket = null
        }
    }
    
    private fun sendCommand(command: String) {
        // Use smart encoding selection based on content and server capabilities
        val charset = EncodingConverter.selectEncoding(command, serverEncoding)
        
        if (EncodingConverter.containsNonAscii(command)) {
            log("[FTP] Sending command with non-ASCII chars, using encoding: ${charset.name()}")
        }
        
        val commandBytes = EncodingConverter.encode("$command\r\n", charset)
        controlOutputStream?.write(commandBytes)
        controlOutputStream?.flush()
        log("[FTP] Sent: $command")
    }
    
    private fun readResponse(): FtpResponse {
        val firstLine = controlReader?.readLine() ?: throw IOException("No response from server")
        
        val code = firstLine.substring(0, 3).toInt()
        var message = if (firstLine.length > 4) firstLine.substring(4) else ""
        
        log("[FTP] Received: $firstLine")
        
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            val codePrefix = firstLine.substring(0, 3)
            var line: String?
            while (controlReader?.readLine().also { line = it } != null) {
                log("[FTP] Received (multiline): $line")
                if (line!!.length >= 4 && line!!.substring(0, 3) == codePrefix && line!![3] == ' ') {
                    message += " " + line!!.substring(4)
                    break
                }
            }
        }
        
        return FtpResponse(code, message)
    }
    
    private suspend fun reconnectControlConnection(): Boolean {
        log("[FTP] Attempting to reconnect control connection...")
        try {
            controlReader?.close()
            controlInputStream?.close()
            controlOutputStream?.close()
            
            controlOutputStream = controlSocket?.getOutputStream()
            controlInputStream = controlSocket?.getInputStream()
            controlReader = BufferedReader(InputStreamReader(controlInputStream, Charsets.ISO_8859_1))
            
            log("[FTP] Reinitializing server encoding...")
            try {
                sendCommand("OPTS UTF8 ON")
                val utf8Response = readResponse()
                if (utf8Response.code == 200 || utf8Response.code == 202) {
                    serverEncoding = StandardCharsets.UTF_8
                } else {
                    serverEncoding = Charset.forName("GBK")
                }
            } catch (e: Exception) {
                serverEncoding = Charset.forName("GBK")
            }
            
            log("[FTP] Control connection reinitialized")
            return true
        } catch (e: Exception) {
            log("[FTP] Failed to reconnect: ${e.message}")
            return false
        }
    }
    
    /**
     * Clear any pending responses from the control connection
     * This prevents response mixing when commands are sent in quick succession
     */
    private fun clearPendingResponses() {
        var originalTimeout = 30000
        
        try {
            val reader = controlReader ?: return
            if (controlSocket == null) return
            
            originalTimeout = controlSocket?.soTimeout ?: 30000
            
            val totalTimeout = 1000
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < totalTimeout) {
                controlSocket?.soTimeout = 100
                
                try {
                    if (!reader.ready()) {
                        kotlinx.coroutines.delay(50)
                        if (!reader.ready()) {
                            break
                        }
                    }
                    val line = reader.readLine()
                    if (line == null) break
                    log("[FTP] Cleared stale response: $line")
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            log("[FTP] Error clearing pending responses: ${e.message}")
        } finally {
            try {
                controlSocket?.soTimeout = originalTimeout
            } catch (e: Exception) {
                log("[FTP] Failed to restore timeout: ${e.message}")
            }
        }
    }
    
    private fun closeDataConnection() {
        dataSocket?.close()
        dataSocket = null
    }
    
    private fun parsePasvPort(message: String): Int {
        val pattern = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
        val matcher = pattern.matcher(message)
        
        if (matcher.find()) {
            val p1 = matcher.group(5)?.toInt() ?: 0
            val p2 = matcher.group(6)?.toInt() ?: 0
            return p1 * 256 + p2
        }
        return 0
    }
    
    private fun parseFtpLine(line: String): FtpFileInfo? {
        val parts = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (parts.size < 9) return null
        
        val permissions = parts[0]
        val size = parts[4].toLongOrNull() ?: 0L
        val name = parts.subList(8, parts.size).joinToString(" ")
        
        val isDirectory = permissions.startsWith("d")
        
        return FtpFileInfo(
            name = name,
            size = size,
            isDirectory = isDirectory,
            modifiedTime = "${parts[5]} ${parts[6]} ${parts[7]}"
        )
    }
}

data class FtpResponse(val code: Int, val message: String)
