package com.example.lanmediaplayer.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class CastDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val type: String = "DLNA",
    val controlUrl: String? = null  // DLNA控制URL
)

class CastController(private val context: Context, private val logCallback: ((String) -> Unit)? = null) {
    private val devices = CopyOnWriteArrayList<CastDevice>()
    private var isSearching = false
    private var searchJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun log(message: String) {
        Log.i("CastController", message)
        logCallback?.invoke(message)
    }
    
    fun searchDevices(onDevicesFound: (List<CastDevice>) -> Unit) {
        if (isSearching) return
        
        isSearching = true
        devices.clear()
        
        searchJob = scope.launch {
            try {
                searchSsdpDevices()
                
                delay(5000)
                
                mainHandler.post {
                    isSearching = false
                    onDevicesFound(devices.toList())
                }
            } catch (e: Exception) {
                log("Search error: ${e.message}")
                mainHandler.post {
                    isSearching = false
                    onDevicesFound(devices.toList())
                }
            }
        }
    }
    
    private suspend fun searchSsdpDevices() {
        suspendCoroutine<Unit> { continuation ->
            scope.launch {
                try {
                    val ssdpSearch = 
                        "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "\r\n"
                    
                    val searchBytes = ssdpSearch.toByteArray()
                    
                    val multicastAddress = InetAddress.getByName("239.255.255.250")
                    val packet = DatagramPacket(searchBytes, searchBytes.size, multicastAddress, 1900)
                    
                    val socket = DatagramSocket()
                    socket.soTimeout = 3000
                    socket.send(packet)
                    
                    val buffer = ByteArray(4096)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    
                    var foundCount = 0
                    val endTime = System.currentTimeMillis() + 5000
                    
                    while (System.currentTimeMillis() < endTime && foundCount < 20) {
                        try {
                            socket.receive(responsePacket)
                            val response = String(responsePacket.data, 0, responsePacket.length)
                            parseSsdpResponse(response, responsePacket.address.hostAddress, responsePacket.port)
                            foundCount++
                        } catch (e: Exception) {
                            break
                        }
                    }
                    
                    socket.close()
                } catch (e: Exception) {
                    log("SSDP search error: ${e.message}")
                }
                continuation.resume(Unit)
            }
        }
    }
    
    private fun parseSsdpResponse(response: String, ip: String?, port: Int) {
        try {
            val lines = response.split("\r\n", "\n")
            var location: String? = null
            var server: String? = null
            var st: String? = null
            
            for (line in lines) {
                val lowerLine = line.lowercase()
                if (lowerLine.startsWith("location:")) {
                    location = line.substringAfter(":").trim()
                } else if (lowerLine.startsWith("server:")) {
                    server = line.substringAfter(":").trim()
                } else if (lowerLine.startsWith("st:") || lowerLine.startsWith("ext:")) {
                    st = line.substringAfter(":").trim()
                }
            }
            
            if (location != null && ip != null) {
                val deviceName = server ?: "DLNA Device"
                val existingDevice = devices.find { it.ip == ip }
                if (existingDevice == null) {
                    // ✅ 从 location URL 中解析正确的控制URL和端口
                    val (controlUrl, httpPort) = extractControlInfo(location)
                    
                    // ✅ 对于Kodi设备，尝试多个常见的control URL路径
                    var finalControlUrl = controlUrl
                    if (deviceName.contains("Kodi", ignoreCase = true)) {
                        // Kodi可能使用这些路径之一
                        val kodiPaths = listOf(
                            "upnp/control/avtransport",
                            "AVTransport/control",
                            "MediaRenderer/AVTransport/Control",
                            "ctl/AVTransport",
                            "control/AVTransport"
                        )
                        
                        // 尝试第一个路径，如果失败可以在日志中看到并手动切换
                        finalControlUrl = "http://$ip:$httpPort/${kodiPaths[0]}"
                        log("Kodi device detected")
                        log("Trying control URL: $finalControlUrl")
                        log("Alternative paths: ${kodiPaths.drop(1).joinToString(", ")}")
                    }
                    
                    log("Found DLNA device: $deviceName at $ip:$httpPort")
                    log("Location: $location")
                    log("ControlURL: $finalControlUrl")
                    devices.add(CastDevice(deviceName, ip, httpPort, "DLNA", finalControlUrl))
                }
            }
        } catch (e: Exception) {
            log("Parse error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractControlInfo(location: String): Pair<String?, Int> {
        return try {
            // 从 location URL 提取主机和端口
            if (location.contains("://")) {
                val urlParts = location.split("://")
                if (urlParts.size >= 2) {
                    val hostAndPath = urlParts[1].split("/", limit = 2)
                    if (hostAndPath.isNotEmpty()) {
                        val hostPart = hostAndPath[0]
                        // 解析端口
                        val port = if (hostPart.contains(":")) {
                            hostPart.split(":")[1].toIntOrNull() ?: 80
                        } else {
                            80  // 默认HTTP端口
                        }
                        
                        // 构建基URL
                        val baseUrl = if (hostAndPath.size >= 2) {
                            "http://$hostPart/${hostAndPath[1]}"
                        } else {
                            "http://$hostPart/"
                        }
                        
                        Pair(baseUrl, port)
                    } else {
                        Pair(location, 80)
                    }
                } else {
                    Pair(location, 80)
                }
            } else {
                Pair(location, 80)
            }
        } catch (e: Exception) {
            log("Extract control info error: ${e.message}")
            Pair(null, 80)
        }
    }
    
    fun stopSearch() {
        searchJob?.cancel()
        isSearching = false
    }
    
    fun castImage(device: CastDevice, imageUrl: String, title: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                log("=== Casting image to ${device.name} ===")
                log("Device IP: ${device.ip}")
                log("Device Port: ${device.port}")
                log("Image URL: $imageUrl")
                
                val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$imageUrl</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata:1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata:1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:image/jpeg:*"&gt;$imageUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
                
                val setUriSuccess = sendSoapRequest(device, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                if (!setUriSuccess) {
                    mainHandler.post {
                        onResult(false, "Failed to set image URI")
                    }
                    return@launch
                }
                
                delay(500)
                
                val playSuccess = sendPlay(device)
                
                mainHandler.post {
                    if (playSuccess) {
                        onResult(true, "Image sent to ${device.name}")
                    } else {
                        onResult(false, "Failed to display image")
                    }
                }
            } catch (e: Exception) {
                log("Cast error: ${e.message}")
                e.printStackTrace()
                mainHandler.post {
                    onResult(false, "Error: ${e.message}")
                }
            }
        }
    }
    
    fun castVideo(device: CastDevice, videoUrl: String, title: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                log("=== Casting video to ${device.name} ===")
                log("Device IP: ${device.ip}")
                log("Device Port: ${device.port}")
                log("Control URL: ${device.controlUrl}")
                log("Video URL: $videoUrl")
                
                // 首先设置URI
                val setUriSuccess = sendSetAVTransportURI(device, videoUrl, title)
                if (!setUriSuccess) {
                    log("Failed to set URI")
                    mainHandler.post {
                        onResult(false, "Failed to set video URI")
                    }
                    return@launch
                }
                
                delay(500)
                
                // 然后播放
                val playSuccess = sendPlay(device)
                
                mainHandler.post {
                    if (playSuccess) {
                        log("Video casting successful!")
                        onResult(true, "Video playing on ${device.name}")
                    } else {
                        log("Failed to play video")
                        onResult(false, "Failed to start playback")
                    }
                }
            } catch (e: Exception) {
                log("Cast error: ${e.message}")
                e.printStackTrace()
                mainHandler.post {
                    onResult(false, "Error: ${e.message}")
                }
            }
        }
    }
    
    // 构建真实的SMB/FTP URL（用于DLNA投屏）
    fun buildRealMediaUrl(path: String, protocol: Any, host: String, port: Int, username: String, password: String, share: String = ""): String {
        val protocolName = protocol::class.simpleName
        return when (protocolName) {
            "SMB" -> {
                // SMB格式: smb://username:password@host:port/share/path
                val cleanPath = if (path.startsWith("/")) path.substring(1) else path
                // 如果有共享目录，添加到路径中
                val fullPath = if (share.isNotEmpty()) {
                    "$share/$cleanPath"
                } else {
                    cleanPath
                }
                "smb://$username:$password@$host:$port/$fullPath"
            }
            "FTP" -> {
                // FTP格式: ftp://username:password@host:port/path
                "ftp://$username:$password@$host:$port$path"
            }
            else -> {
                // 默认返回原始路径
                path
            }
        }
    }
    
    private suspend fun sendSetAVTransportURI(device: CastDevice, videoUrl: String, title: String): Boolean {
        return try {
            log("=== Preparing SetAVTransportURI ===")
            log("Original videoUrl: $videoUrl")
            
            // ✅ 测试URL是否可访问（使用GET方法）
            try {
                val testUrl = java.net.URL(videoUrl)
                val connection = testUrl.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"  // ✅ 使用GET而不是HEAD
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                
                // 只读取响应头，不读取body
                val responseCode = connection.responseCode
                log("URL accessibility test: HTTP $responseCode")
                
                if (responseCode == 200 || responseCode == 206) {
                    log("✅ URL is accessible! Kodi should be able to reach this URL.")
                } else {
                    log("WARNING: URL returned HTTP $responseCode")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                log("URL test failed: ${e.message}")
                log("This suggests Kodi cannot access the URL!")
            }
            
            // ✅ 对URL和标题进行XML转义
            val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val escapedVideoUrl = videoUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            
            // ✅ 使用最简单的DIDL-Lite格式（参考B站等成功的应用）
            val didlLite = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata:1-0/DIDL-Lite/\"&gt;" +
                    "&lt;item id=\"1\" parentID=\"0\" restricted=\"1\"&gt;" +
                    "&lt;dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\"&gt;$escapedTitle&lt;/dc:title&gt;" +
                    "&lt;upnp:class xmlns:upnp=\"urn:schemas-upnp-org:metadata:1-0/upnp/\"&gt;object.item.videoItem&lt;/upnp:class&gt;" +
                    "&lt;res protocolInfo=\"http-get:*:video/mp4:*\"&gt;$escapedVideoUrl&lt;/res&gt;" +
                    "&lt;/item&gt;" +
                    "&lt;/DIDL-Lite&gt;"
            
            log("DIDL-Lite metadata (encoded): ${didlLite.take(200)}...")
            
            // ✅ 尝试不发送CurrentURIMetaData（某些设备如Kodi可能更喜欢这样）
            val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$escapedVideoUrl</CurrentURI>
<CurrentURIMetaData></CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
            
            log("SOAP request length: ${soapBody.length} bytes")
            log("Sending SetAVTransportURI request...")
            sendSoapRequest(device, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
        } catch (e: Exception) {
            log("SetAVTransportURI error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun sendPlay(device: CastDevice): Boolean {
        return try {
            val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""
            
            log("Sending Play request...")
            sendSoapRequest(device, "urn:schemas-upnp-org:service:AVTransport:1#Play", soapBody)
        } catch (e: Exception) {
            log("Play error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun sendSoapRequest(device: CastDevice, soapAction: String, body: String): Boolean {
        return suspendCoroutine { continuation ->
            scope.launch {
                var socket: java.net.Socket? = null
                try {
                    log("=== Sending SOAP Request ===")
                    log("Action: $soapAction")
                    log("Target: ${device.ip}:${device.port}")
                    
                    // ✅ 使用controlUrl或默认路径
                    val controlPath = device.controlUrl?.let {
                        if (it.contains("://")) {
                            // 从完整URL中提取path部分
                            // 例如: http://192.168.11.9:1363/upnp/control/avtransport -> upnp/control/avtransport
                            it.substringAfter("://").substringAfter("/")
                        } else {
                            it
                        }
                    } ?: "upnp/control/AVTransport"
                    
                    log("Control path: $controlPath")
                    
                    val contentLength = body.toByteArray(Charsets.UTF_8).size
                    val request = "POST /$controlPath HTTP/1.1\r\n" +
                            "Host: ${device.ip}:${device.port}\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "SOAPACTION: \"$soapAction\"\r\n" +
                            "Content-Length: $contentLength\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            body
                    
                    log("Request length: ${request.length} bytes")
                    log("Request preview: ${request.take(300)}...")
                    
                    socket = java.net.Socket(device.ip, device.port)
                    socket.soTimeout = 5000
                    socket.outputStream.write(request.toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                    
                    log("Request sent, waiting for response...")
                    
                    val responseBuffer = ByteArray(8192)
                    val responseStream = socket.getInputStream()
                    var response = ""
                    var totalBytes = 0
                    
                    try {
                        while (true) {
                            val bytesRead = responseStream.read(responseBuffer)
                            if (bytesRead <= 0) break
                            response += String(responseBuffer, 0, bytesRead, Charsets.UTF_8)
                            totalBytes += bytesRead
                            // 如果响应包含结束标记，提前退出
                            if (response.contains("</s:Envelope>") || response.contains("</s:Body>")) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        log("Read response partial: ${e.message}")
                    }
                    
                    log("Response received ($totalBytes bytes): ${response.take(200)}...")
                    
                    socket.close()
                    
                    // 检查响应是否成功
                    val success = response.contains("200 OK") || 
                                 response.contains("HTTP/1.1 200") ||
                                 (!response.contains("500") && !response.contains("400"))
                    
                    log("SOAP request ${if (success) "SUCCESS" else "FAILED"}")
                    continuation.resume(success)
                } catch (e: Exception) {
                    log("SOAP request error: ${e.message}")
                    e.printStackTrace()
                    socket?.close()
                    continuation.resume(false)
                }
            }
        }
    }
    
    fun stopCast(device: CastDevice, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>"""
                
                val success = sendSoapRequest(device, "urn:schemas-upnp-org:service:AVTransport:1#Stop", soapBody)
                mainHandler.post { onResult(success) }
            } catch (e: Exception) {
                log("Stop cast error: ${e.message}")
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }
    }
    
    fun release() {
        scope.cancel()
    }
}
