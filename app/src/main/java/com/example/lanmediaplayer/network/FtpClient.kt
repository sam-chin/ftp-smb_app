package com.example.lanmediaplayer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
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
    
    private fun log(message: String) {
            log(message)
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
            controlReader = BufferedReader(InputStreamReader(controlInputStream))
            
            log("[FTP] Streams initialized, waiting for server greeting...")
            val response = readResponse()
            log("[FTP] Server greeting: ${response.code} ${response.message}")
            
            val success = response.code in 200..299
            if (success) {
            log("[FTP] === Connection established ===")
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
    
    suspend fun login(username: String, password: String): Boolean {
        this@FtpClient.username = username
        this@FtpClient.password = password
        
        return try {
            log("[FTP] === Starting login ===")
            log("[FTP] Username: $username")
            log("[FTP] Password length: ${password.length}")
            
            log("[FTP] Sending USER command...")
            sendCommand("USER $username")
            val userResponse = readResponse()
            log("[FTP] USER response: ${userResponse.code} ${userResponse.message}")
            
            if (userResponse.code !in 200..399) {
            log("[FTP] === USER command failed ===")
                return false
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
        try {
            log("[FTP] Listing files in: $remotePath")
            
            sendCommand("TYPE I")
            readResponse()
            
            sendCommand("PASV")
            val pasvResponse = readResponse()
            log("[FTP] PASV response: ${pasvResponse.code} ${pasvResponse.message}")
            
            if (pasvResponse.code != 227) {
            log("[FTP] PASV failed with code: ${pasvResponse.code}")
                return@withContext emptyList()
            }
            
            val dataPort = parsePasvPort(pasvResponse.message)
            // Use the same host as control connection
            val dataHost = host
            log("[FTP] Connecting to data socket: $dataHost:$dataPort")
            
            dataSocket = Socket(dataHost, dataPort)
            
            sendCommand("LIST $remotePath")
            val listResponse = readResponse()
            log("[FTP] LIST response: ${listResponse.code} ${listResponse.message}")
            
            if (listResponse.code !in 100..199) {
            log("[FTP] LIST command failed with code: ${listResponse.code}")
                return@withContext emptyList()
            }
            
            val files = mutableListOf<FtpFileInfo>()
            dataSocket?.let { socket ->
                val inputStream = socket.getInputStream()
                
                // Read all bytes first
                val bytes = inputStream.readBytes()
            log("[FTP] Read ${bytes.size} bytes from data connection")
                
                // Try multiple encodings for Chinese filenames
                var lines = listOf<String>()
                val encodings = listOf("UTF-8", "GBK", "GB2312", "ISO-8859-1")
                
                for (encoding in encodings) {
                    try {
                        val text = String(bytes, charset(encoding))
                        val tempLines = text.lines().filter { it.isNotBlank() }
                        if (tempLines.isNotEmpty()) {
                            lines = tempLines
            log("[FTP] Successfully decoded with encoding: $encoding (${lines.size} lines)")
                            break
                        }
                    } catch (e: Exception) {
            log("[FTP] Failed with encoding $encoding: ${e.message}")
                    }
                }
                
                // Parse all collected lines
                for (line in lines) {
            log("[FTP] Raw line: $line")
                    parseFtpLine(line)?.let { fileInfo ->
            log("[FTP] Parsed file: ${fileInfo.name}, isDir: ${fileInfo.isDirectory}")
                        files.add(fileInfo)
                    }
                }
            }
            
            log("[FTP] Total files found: ${files.size}")
            readResponse()
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
            sendCommand("TYPE I")
            readResponse()
            
            sendCommand("PASV")
            val pasvResponse = readResponse()
            
            if (pasvResponse.code != 227) {
                return@withContext false
            }
            
            val dataPort = parsePasvPort(pasvResponse.message)
            val dataHost = pasvResponse.message.substringBefore("(").trim()
            
            dataSocket = Socket(dataHost, dataPort)
            
            sendCommand("RETR $remotePath")
            val retrResponse = readResponse()
            
            if (retrResponse.code !in 100..199) {
                return@withContext false
            }
            
            dataSocket?.let { socket ->
                val inputStream = socket.getInputStream()
                val outputStream = FileOutputStream(localFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }
            
            val finalResponse = readResponse()
            finalResponse.code in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            closeDataConnection()
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
        // FTP protocol requires ASCII encoding for commands
        val bytes = "${command}\r\n".toByteArray(Charsets.US_ASCII)
        controlOutputStream?.write(bytes)
        controlOutputStream?.flush()
            log("[FTP] Sent: $command")
    }
    
    private fun readResponse(): FtpResponse {
        val firstLine = controlReader?.readLine() ?: throw IOException("No response from server")
        
        val code = firstLine.substring(0, 3).toInt()
        val message = if (firstLine.length > 4) firstLine.substring(4) else ""
        
            log("[FTP] Received: $firstLine")
        return FtpResponse(code, message)
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
