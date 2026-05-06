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
    
    fun connect(host: String, port: Int = 21): Boolean {
        this.host = host
        this.port = port
        
        return try {
            controlSocket = Socket(host, port)
            controlOutputStream = controlSocket?.getOutputStream()
            controlInputStream = controlSocket?.getInputStream()
            
            val response = readResponse()
            response.code in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }
    
    suspend fun login(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        this@FtpClient.username = username
        this@FtpClient.password = password
        
        try {
            sendCommand("USER $username")
            val userResponse = readResponse()
            if (userResponse.code !in 200..399) return@withContext false
            
            sendCommand("PASS $password")
            val passResponse = readResponse()
            passResponse.code in 200..299
        } catch (e: Exception) {
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
                // Use UTF-8 encoding to handle Chinese filenames
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    println("[FTP] Raw line: $line")
                    line?.let {
                        parseFtpLine(it)?.let { fileInfo ->
                            println("[FTP] Parsed file: ${fileInfo.name}, isDir: ${fileInfo.isDirectory}")
                            files.add(fileInfo)
                        }
                    }
                }
                reader.close()
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
