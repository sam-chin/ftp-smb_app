package com.lanmedia.player.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
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
                log("=== Starting DLNA device discovery (optimized) ===")
                
                // ✅ 只搜索MediaRenderer（投屏设备），不搜ssdp:all
                val searchType = "urn:schemas-upnp-org:device:MediaRenderer:1"
                log("📡 Searching for: $searchType")
                
                // ✅ 快速发送3次M-SEARCH广播，间隔100ms
                for (i in 1..3) {
                    log("Sending M-SEARCH #$i/3...")
                    sendSsdpBroadcast(searchType)
                    if (i < 3) delay(100)  // 100ms间隔
                }
                
                // ✅ 开启2.5秒限时收包窗口
                log("⏱️ Starting 2.5s receive window...")
                val startTime = System.currentTimeMillis()
                val receiveTimeout = 2500L  // 2.5秒
                
                while (System.currentTimeMillis() - startTime < receiveTimeout && isSearching) {
                    // 异步接收和解析响应
                    receiveAndParseResponse(searchType, onDevicesFound)
                }
                
                log("✅ Search completed in ${System.currentTimeMillis() - startTime}ms")
                mainHandler.post {
                    isSearching = false
                    log("🎉 Found ${devices.size} devices")
                    onDevicesFound(devices.toList())
                }
            } catch (e: Exception) {
                log("❌ Search error: ${e.message}")
                e.printStackTrace()
                mainHandler.post {
                    isSearching = false
                    onDevicesFound(devices.toList())
                }
            }
        }
    }
    
    // ✅ 发送单次M-SEARCH广播
    private suspend fun sendSsdpBroadcast(searchType: String) {
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                val ssdpMessage = 
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +  // ✅ MX固定为2秒
                    "ST: $searchType\r\n" +
                    "\r\n"
                
                val multicastAddress = InetAddress.getByName("239.255.255.250")
                socket = DatagramSocket()
                socket.broadcast = true
                
                val packet = DatagramPacket(
                    ssdpMessage.toByteArray(Charsets.UTF_8),
                    ssdpMessage.length,
                    multicastAddress,
                    1900
                )
                socket.send(packet)
                log("✓ Broadcast sent")
                
            } catch (e: Exception) {
                log("Send error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }
    
    // ✅ 异步接收并解析响应
    private suspend fun receiveAndParseResponse(searchType: String, onDevicesFound: (List<CastDevice>) -> Unit) {
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.soTimeout = 500  // 500ms超时，快速循环
                
                val buffer = ByteArray(8192)
                val packet = DatagramPacket(buffer, buffer.size)
                
                try {
                    socket.receive(packet)
                    
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address.hostAddress
                    
                    // ✅ 提取Location并去重
                    val location = extractLocationFromResponse(response)
                    if (location != null && !isDuplicateDevice(location)) {
                        log("📍 New device from: $senderIp")
                        log("   Location: $location")
                        
                        // ✅ 异步获取XML并解析controlURL
                        processDeviceDiscovery(location, senderIp, onDevicesFound)
                    }
                    
                } catch (e: java.net.SocketTimeoutException) {
                    // 超时是正常的，继续循环
                }
                
            } catch (e: Exception) {
                // 忽略错误，继续尝试
            } finally {
                socket?.close()
            }
        }
    }
    
    // ✅ 从SSDP响应中提取Location
    private fun extractLocationFromResponse(response: String): String? {
        val lines = response.split("\r\n", "\n")
        for (line in lines) {
            if (line.lowercase().startsWith("location:")) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }
    
    // ✅ 设备去重（基于Location）
    private val discoveredLocations = mutableSetOf<String>()
    
    private fun isDuplicateDevice(location: String): Boolean {
        return !discoveredLocations.add(location)
    }
    
    // ✅ 异步处理设备发现（获取XML + 解析controlURL）
    private suspend fun processDeviceDiscovery(
        location: String,
        senderIp: String?,
        onDevicesFound: (List<CastDevice>) -> Unit
    ) {
        scope.launch {
            try {
                // 第3步：拉取设备描述XML
                val xmlContent = fetchDeviceDescription(location)
                if (xmlContent != null) {
                    // 第4步：解析XML获取AVTransport controlURL
                    val controlUrl = parseAvTransportControlUrl(xmlContent, location)
                    
                    if (controlUrl != null) {
                        // 从controlURL或location提取IP和端口
                        val (ip, port) = extractIpAndPort(controlUrl)
                        val deviceName = extractDeviceNameFromXml(xmlContent) ?: "DLNA Device"
                        
                        log("✅ Device found: $deviceName")
                        log("   IP: $ip, Port: $port")
                        log("   ControlURL: $controlUrl")
                        
                        val device = CastDevice(deviceName, ip, port, "DLNA", controlUrl)
                        
                        mainHandler.post {
                            devices.add(device)
                            // ✅ 实时回调，立即更新UI
                            onDevicesFound(devices.toList())
                        }
                    }
                }
            } catch (e: Exception) {
                log("Process device error: ${e.message}")
            }
        }
    }
    
    // ✅ 从URL提取IP和端口
    private fun extractIpAndPort(url: String): Pair<String, Int> {
        return try {
            if (url.contains("://")) {
                val hostPart = url.split("://")[1].split(":")[1].split("/")[0]
                val ip = url.split("://")[1].split(":")[0]
                val port = hostPart.toIntOrNull() ?: 80
                Pair(ip, port)
            } else {
                Pair("127.0.0.1", 80)
            }
        } catch (e: Exception) {
            Pair("127.0.0.1", 80)
        }
    }
    
    // ✅ 从XML提取设备名称
    private fun extractDeviceNameFromXml(xmlContent: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "friendlyName") {
                    return parser.nextText()
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    
    
    // ✅ 第3步：拉取设备描述XML文件
    private suspend fun fetchDeviceDescription(locationUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                log("📥 Fetching device description from: $locationUrl")
                val url = URL(locationUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    log("✅ Device description fetched (${content.length} bytes)")
                    content
                } else {
                    log("❌ Failed to fetch XML: HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                log("❌ Fetch error: ${e.message}")
                null
            }
        }
    }
    
    // ✅ 第4步：解析XML获取AVTransport controlURL
    private fun parseAvTransportControlUrl(xmlContent: String, baseUrl: String): String? {
        return try {
            log("🔍 Parsing device description XML...")
            
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))
            
            var eventType = parser.eventType
            var inServiceList = false
            var inService = false
            var serviceType = ""
            var controlUrlPath = ""
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        
                        if (tagName == "serviceList") {
                            inServiceList = true
                        } else if (inServiceList && tagName == "service") {
                            inService = true
                            serviceType = ""
                            controlUrlPath = ""
                        } else if (inService && tagName == "serviceType") {
                            serviceType = parser.nextText()
                        } else if (inService && tagName == "controlURL") {
                            controlUrlPath = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name
                        
                        if (tagName == "service" && inService) {
                            // 检查是否是AVTransport服务
                            if (serviceType.contains("AVTransport", ignoreCase = true)) {
                                log("✅ Found AVTransport service")
                                log("   Service Type: $serviceType")
                                log("   Control URL Path: $controlUrlPath")
                                
                                // 构建完整的controlURL
                                val fullControlUrl = buildAbsoluteUrl(baseUrl, controlUrlPath)
                                log("   Full Control URL: $fullControlUrl")
                                
                                return fullControlUrl
                            }
                            inService = false
                        } else if (tagName == "serviceList") {
                            inServiceList = false
                        }
                    }
                }
                eventType = parser.next()
            }
            
            log("⚠️ AVTransport service not found in XML")
            null
        } catch (e: Exception) {
            log("❌ XML parsing error: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // 构建绝对URL（处理相对路径）
    private fun buildAbsoluteUrl(baseUrl: String, relativePath: String): String {
        return try {
            if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
                // 已经是绝对URL
                relativePath
            } else {
                // 相对路径，需要拼接
                val base = baseUrl.substringBeforeLast("/")
                val path = if (relativePath.startsWith("/")) {
                    relativePath
                } else {
                    "/$relativePath"
                }
                "$base$path"
            }
        } catch (e: Exception) {
            log("Build URL error: ${e.message}")
            relativePath
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
                log("Control URL: ${device.controlUrl}")
                log("Image URL: $imageUrl")
                
                // ✅ 对URL和标题进行XML转义（和视频投屏保持一致）
                val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val escapedImageUrl = imageUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                
                // ✅ 根据文件扩展名确定MIME类型
                val mimeType = when {
                    imageUrl.endsWith(".jpg", ignoreCase = true) || imageUrl.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    imageUrl.endsWith(".png", ignoreCase = true) -> "image/png"
                    imageUrl.endsWith(".gif", ignoreCase = true) -> "image/gif"
                    imageUrl.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
                    imageUrl.endsWith(".webp", ignoreCase = true) -> "image/webp"
                    else -> "image/jpeg"  // 默认JPEG
                }
                
                log("Image MIME type: $mimeType")
                
                // ✅ 构建DIDL-Lite元数据（和视频格式一致）
                val didlLite = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"&gt;" +
                        "&lt;item id=\"1\" parentID=\"0\" restricted=\"1\"&gt;" +
                        "&lt;dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\"&gt;$escapedTitle&lt;/dc:title&gt;" +
                        "&lt;upnp:class xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;object.item.imageItem&lt;/upnp:class&gt;" +
                        "&lt;res protocolInfo=\"http-get:*:$mimeType:*\"&gt;$escapedImageUrl&lt;/res&gt;" +
                        "&lt;/item&gt;" +
                        "&lt;/DIDL-Lite&gt;"
                
                log("DIDL-Lite metadata (encoded): ${didlLite.take(200)}...")
                
                // ✅ 完整的SOAP请求（包含CurrentURIMetaData）
                val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$escapedImageUrl</CurrentURI>
<CurrentURIMetaData>$didlLite</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
                
                log("SOAP request length: ${soapBody.length} bytes")
                
                val setUriSuccess = sendSoapRequest(device, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                if (!setUriSuccess) {
                    log("Failed to set image URI")
                    mainHandler.post {
                        onResult(false, "Failed to set image URI")
                    }
                    return@launch
                }
                
                delay(500)
                
                val playSuccess = sendPlay(device)
                
                mainHandler.post {
                    if (playSuccess) {
                        log("Image casting successful!")
                        onResult(true, "Image displayed on ${device.name}")
                    } else {
                        log("Failed to display image")
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
                log("")
                            
                // 从 videoUrl 中提取手机IP和端口
                val phoneIpAndPort = try {
                    val url = java.net.URL(videoUrl)
                    "${url.host}:${url.port}"
                } catch (e: Exception) {
                    "unknown"
                }
                            
                log("⚠️ IMPORTANT: Kodi device at ${device.ip} must be able to access:")
                log("   $videoUrl")
                log("   Please ensure:")
                log("   1. Phone ($phoneIpAndPort) and Kodi (${device.ip}) are on the same network")
                log("   2. No firewall blocking port ${videoUrl.split(":")[2].split("/")[0]}")
                log("   3. Android allows external connections to HTTP proxy")
                log("")
                
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
            // 注意：这里只是测试HTTP代理是否工作，不代表Kodi能访问
            try {
                val testUrl = java.net.URL(videoUrl)
                log("Testing URL accessibility from local device...")
                val connection = testUrl.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                
                // 只读取响应头，不读取body
                val responseCode = connection.responseCode
                log("Local URL test result: HTTP $responseCode")
                
                if (responseCode == 200 || responseCode == 206) {
                    log("✅ HTTP proxy is working locally")
                    log("⚠️ Note: This doesn't guarantee Kodi can access the URL")
                    log("   Kodi must be able to reach: $videoUrl")
                } else {
                    log("❌ WARNING: HTTP proxy returned HTTP $responseCode")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                log("❌ Local URL test failed: ${e.message}")
                log("This suggests HTTP proxy may not be working correctly")
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
            
            // ✅ 关键修复：必须包含CurrentURIMetaData字段
            // Kodi需要这个字段才能正确解析和播放媒体
            val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$escapedVideoUrl</CurrentURI>
<CurrentURIMetaData>$didlLite</CurrentURIMetaData>
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
                    
                    // ✅ 计算body的字节长度（不是整个request）
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val contentLength = bodyBytes.size
                    
                    val request = "POST /$controlPath HTTP/1.1\r\n" +
                            "Host: ${device.ip}:${device.port}\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "SOAPACTION: \"$soapAction\"\r\n" +
                            "Content-Length: $contentLength\r\n" +
                            "Connection: close\r\n" +
                            "User-Agent: DLNA/1.50 UPnP/1.0 LanMediaPlayer/1.0\r\n" +  // ✅ 添加User-Agent
                            "\r\n"
                    
                    log("Request headers length: ${request.length} bytes")
                    log("Body length: $contentLength bytes")
                    log("Request preview: ${request.take(300)}...")
                    
                    // ✅ 打印完整的SOAP body用于调试
                    if (soapAction.contains("SetAVTransportURI")) {
                        log("=== Full SOAP Body ===")
                        log(body)
                        log("=== End of SOAP Body ===")
                    }
                    
                    socket = java.net.Socket(device.ip, device.port)
                    socket.soTimeout = 5000
                    
                    // ✅ 先发送headers，再发送body
                    socket.outputStream.write(request.toByteArray(Charsets.UTF_8))
                    socket.outputStream.write(bodyBytes)
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
                    
                    // ✅ 打印完整响应以便调试
                    if (!response.contains("200 OK")) {
                        log("Full response: $response")
                        log("")
                        log("❌ DLNA投屏失败！可能的原因：")
                        log("1. Kodi无法访问HTTP代理URL")
                        log("   - 请确认Kodi设备和手机在同一局域网")
                        log("   - ⚠️ 小米澎湃OS用户请特别注意：")
                        log("     a. 设置 → 更多设置 → 开发者选项 → 关闭'MIUI优化'")
                        log("     b. 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 → 无限制")
                        log("     c. 设置 → 连接与共享 → 私人DNS → 关闭")
                        log("     d. 手机管家 → 网络助手 → 允许局域网访问")
                        log("   - 尝试在Kodi设备上用浏览器访问该URL")
                        log("2. Kodi的DLNA渲染器配置问题")
                        log("   - 打开Kodi → 设置 → 服务 → 控制")
                        log("   - 确保'允许通过UPnP远程控制'已启用")
                        log("3. SOAP请求格式问题")
                        log("   - 已尝试简化SOAP请求（移除CurrentURIMetaData）")
                        log("   - 如果仍然失败，可能需要抓包分析B站App的请求")
                        log("")
                    }
                    
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
