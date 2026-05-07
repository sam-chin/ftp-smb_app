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
    val type: String = "DLNA"
)

class CastController(private val context: Context) {
    private val logCallback: ((String) -> Unit)? = null
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
                val existingDevice = devices.find { it.ip == ip && it.port == port }
                if (existingDevice == null) {
                    devices.add(CastDevice(deviceName, ip, port, "DLNA"))
                    log("Found DLNA device: $deviceName at $ip:$port")
                }
            }
        } catch (e: Exception) {
            log("Parse error: ${e.message}")
        }
    }
    
    fun stopSearch() {
        searchJob?.cancel()
        isSearching = false
    }
    
    fun castImage(device: CastDevice, imageUrl: String, title: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                log("Casting image to ${device.name} at ${device.ip}")
                
                val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
                val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$imageUrl</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata:1-0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata:1-0"&gt;&lt;item id="1" parentID="-1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;res protocolInfo="http-get:*:image/jpeg:*"&gt;$imageUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
                
                sendSoapRequest(device.ip, device.port, soapAction, soapBody)
                
                delay(500)
                
                val playAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
                val playBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""
                
                val success = sendSoapRequest(device.ip, device.port, playAction, playBody)
                
                mainHandler.post {
                    if (success) {
                        onResult(true, "Image sent to ${device.name}")
                    } else {
                        onResult(false, "Failed to cast image")
                    }
                }
            } catch (e: Exception) {
                log("Cast error: ${e.message}")
                mainHandler.post {
                    onResult(false, "Error: ${e.message}")
                }
            }
        }
    }
    
    fun castVideo(device: CastDevice, videoUrl: String, title: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                log("Casting video to ${device.name} at ${device.ip}")
                
                val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
                val contentType = if (videoUrl.contains(".mp4")) "video/mp4" else "video/x-matroska"
                val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$videoUrl</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata:1-0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata:1-0"&gt;&lt;item id="1" parentID="-1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;res protocolInfo="http-get:*:$contentType:*"&gt;$videoUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
                
                sendSoapRequest(device.ip, device.port, soapAction, soapBody)
                
                delay(500)
                
                val playAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
                val playBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""
                
                val success = sendSoapRequest(device.ip, device.port, playAction, playBody)
                
                mainHandler.post {
                    if (success) {
                        onResult(true, "Video playing on ${device.name}")
                    } else {
                        onResult(false, "Failed to cast video")
                    }
                }
            } catch (e: Exception) {
                log("Cast error: ${e.message}")
                mainHandler.post {
                    onResult(false, "Error: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun sendSoapRequest(ip: String, port: Int, soapAction: String, body: String): Boolean {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
${body.substringAfter("<s:Body>").substringBefore("</s:Body>")}
</s:Body>
</s:Envelope>"""
                    
                    val contentLength = body.toByteArray().size
                    val request = "POST /upnp/control/AVTransport HTTP/1.1\r\n" +
                            "Host: $ip:$port\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "SOAPACTION: \"$soapAction\"\r\n" +
                            "Content-Length: $contentLength\r\n" +
                            "\r\n" +
                            body
                    
                    val socket = java.net.Socket(ip, port)
                    socket.soTimeout = 5000
                    socket.outputStream.write(request.toByteArray())
                    socket.outputStream.flush()
                    
                    val responseBuffer = ByteArray(4096)
                    val responseStream = socket.getInputStream()
                    var response = ""
                    try {
                        val bytesRead = responseStream.read(responseBuffer)
                        if (bytesRead > 0) {
                            response = String(responseBuffer, 0, bytesRead)
                        }
                    } catch (e: Exception) {
                        log("Read response error: ${e.message}")
                    }
                    
                    socket.close()
                    
                    val success = response.contains("200 OK") || response.contains("AVTransport")
                    continuation.resume(success)
                } catch (e: Exception) {
                    log("SOAP request error: ${e.message}")
                    continuation.resume(false)
                }
            }
        }
    }
    
    fun stopCast(device: CastDevice, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val stopAction = "urn:schemas-upnp-org:service:AVTransport:1#Stop"
                val stopBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>"""
                
                val success = sendSoapRequest(device.ip, device.port, stopAction, stopBody)
                mainHandler.post { onResult(success) }
            } catch (e: Exception) {
                mainHandler.post { onResult(false) }
            }
        }
    }
    
    fun release() {
        scope.cancel()
    }
}
