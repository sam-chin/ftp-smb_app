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

class FtpClient {
    private var controlSocket: Socket? = null
    private var dataSocket: Socket? = null
    private var controlOutputStream: OutputStream? = null
    private var controlInputStream: InputStream? = null
    
    private var host: String = ""
    private var port: Int = 21
    private var username: String = ""
    private var password: String = ""
    private var passiveMode: Boolean = true
    
    suspend fun connect(host: String, port: Int = 21): Boolean = withContext(Dispatchers.IO) {
        this@FtpClient.host = host
        this@FtpClient.port = port
        
        return@withContext try {
            println("[FTP] Connecting to $host:$port...")
            controlSocket = Socket()
            controlSocket?.soTimeout = 10000 // 10 seconds timeout
            controlSocket?.connect(java.net.InetSocketAddress(host, port), 10000)
            
            println("[FTP] Socket connected, getting streams...")
            controlOutputStream = controlSocket?.getOutputStream()
            controlInputStream = controlSocket?.getInputStream()
            
            println("[FTP] Reading server response...")
            val response = readResponse()
            println("[FTP] Server response: ${response.code} ${response.message}")
            
            val success = response.code in 200..299
            if (success) {
                println("[FTP] Connection successful")
            } else {
                println("[FTP] Connection failed with code: ${response.code}")
            }
            success
        } catch (e: Exception) {
            println("[FTP] Connection error: ${e.message}")
            e.printStackTrace()
            disconnect()
            false
        }
    }
    
    suspend fun login(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        this@FtpClient.username = username
        this@FtpClient.password = password
        
        return@withContext try {
            println("[FTP] Logging in as $username...")
            sendCommand("USER $username")
            val userResponse = readResponse()
            println("[FTP] USER response: ${userResponse.code} ${userResponse.message}")
            if (userResponse.code !in 200..399) {
                println("[FTP] USER command failed")
                return@withContext false
            }
            
            sendCommand("PASS $password")
            val passResponse = readResponse()
            println("[FTP] PASS response: ${passResponse.code} ${passResponse.message}")
            
            val success = passResponse.code in 200..299
            if (success) {
                println("[FTP] Login successful")
            } else {
                println("[FTP] Login failed with code: ${passResponse.code}")
            }
            success
        } catch (e: Exception) {
            println("[FTP] Login error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    suspend fun listFiles(remotePath: String = "/"): List<FtpFileInfo> = withContext(Dispatchers.IO) {
        try {
            println("[FTP] Listing files in: $remotePath")
            
            sendCommand("TYPE I")
            readResponse()
            
            sendCommand("PASV")
            val pasvResponse = readResponse()
            println("[FTP] PASV response: ${pasvResponse.code} ${pasvResponse.message}")
            
            if (pasvResponse.code != 227) {
                println("[FTP] PASV failed with code: ${pasvResponse.code}")
                return@withContext emptyList()
            }
            
            val dataPort = parsePasvPort(pasvResponse.message)
            // Use the same host as control connection
            val dataHost = host
            println("[FTP] Connecting to data socket: $dataHost:$dataPort")
            
            dataSocket = Socket(dataHost, dataPort)
            
            sendCommand("LIST $remotePath")
            val listResponse = readResponse()
            println("[FTP] LIST response: ${listResponse.code} ${listResponse.message}")
            
            if (listResponse.code !in 100..199) {
                println("[FTP] LIST command failed with code: ${listResponse.code}")
                return@withContext emptyList()
            }
            
            val files = mutableListOf<FtpFileInfo>()
            dataSocket?.let { socket ->
                val inputStream = socket.getInputStream()
                
                // Read all bytes first
                val bytes = inputStream.readBytes()
                println("[FTP] Read ${bytes.size} bytes from data connection")
                
                // Try multiple encodings for Chinese filenames
                var lines = listOf<String>()
                val encodings = listOf("UTF-8", "GBK", "GB2312", "ISO-8859-1")
                
                for (encoding in encodings) {
                    try {
                        val text = String(bytes, charset(encoding))
                        val tempLines = text.lines().filter { it.isNotBlank() }
                        if (tempLines.isNotEmpty()) {
                            lines = tempLines
                            println("[FTP] Successfully decoded with encoding: $encoding (${lines.size} lines)")
                            break
                        }
                    } catch (e: Exception) {
                        println("[FTP] Failed with encoding $encoding: ${e.message}")
                    }
                }
                
                // Parse all collected lines
                for (line in lines) {
                    println("[FTP] Raw line: $line")
                    parseFtpLine(line)?.let { fileInfo ->
                        println("[FTP] Parsed file: ${fileInfo.name}, isDir: ${fileInfo.isDirectory}")
                        files.add(fileInfo)
                    }
                }
            }
            
            println("[FTP] Total files found: ${files.size}")
            readResponse()
            files
        } catch (e: Exception) {
            println("[FTP] Error listing files: ${e.message}")
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
            controlInputStream?.close()
            controlOutputStream?.close()
            controlSocket?.close()
            controlInputStream = null
            controlOutputStream = null
            controlSocket = null
        }
    }
    
    private fun sendCommand(command: String) {
        controlOutputStream?.write("${command}\r\n".toByteArray())
        controlOutputStream?.flush()
    }
    
    private fun readResponse(): FtpResponse {
        val reader = BufferedReader(InputStreamReader(controlInputStream))
        val firstLine = reader.readLine() ?: throw IOException("No response from server")
        
        val code = firstLine.substring(0, 3).toInt()
        val message = firstLine.substring(4)
        
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
