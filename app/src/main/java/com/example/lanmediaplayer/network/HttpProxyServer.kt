package com.example.lanmediaplayer.network

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class HttpProxyServer {
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var currentPort: Int = 0
    
    interface FileProvider {
        suspend fun getFileStream(path: String): InputStream?
        suspend fun getFileSize(path: String): Long
    }
    
    fun start(port: Int = 0, fileProvider: FileProvider): Int {
        return try {
            serverSocket = if (port > 0) {
                ServerSocket(port)
            } else {
                ServerSocket(0)
            }
            
            currentPort = serverSocket?.localPort ?: 0
            serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            serverScope?.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch {
                            handleClient(clientSocket, fileProvider)
                        }
                    } catch (e: Exception) {
                        if (isActive) e.printStackTrace()
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
            
            if (!requestLine.startsWith("GET")) {
                sendErrorResponse(outputStream, 405, "Method Not Allowed")
                return
            }
            
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendErrorResponse(outputStream, 400, "Bad Request")
                return
            }
            
            // Extract and decode URL-encoded path
            val encodedPath = parts[1].substring(1) // Remove leading /
            val filePath = try {
                java.net.URLDecoder.decode(encodedPath, "UTF-8")
            } catch (e: Exception) {
                encodedPath // Fallback to original if decoding fails
            }
            
            var line: String?
            do {
                line = reader.readLine()
            } while (line?.isNotEmpty() == true)
            
            val fileSize = fileProvider.getFileSize(filePath)
            if (fileSize <= 0) {
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }
            
            val fileStream = fileProvider.getFileStream(filePath)
            if (fileStream == null) {
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }
            
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            outputStream.write(responseHeader.toByteArray())
            outputStream.flush()
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush()
            }
            
            fileStream.close()
            outputStream.close()
            inputStream.close()
            clientSocket.close()
            
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 500, "Internal Server Error")
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
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
    fun getUrl(path: String): String = "http://127.0.0.1:$currentPort/$path"
}
