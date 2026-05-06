package com.example.lanmediaplayer.network

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

data class SmbFileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val path: String
)

class SmbClient {
    private var auth: NtlmPasswordAuthenticator? = null
    private var context: CIFSContext? = null
    private var baseUrl: String = ""
    
    private var host: String = ""
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""
    
    suspend fun connect(
        host: String,
        share: String,
        username: String,
        password: String,
        domain: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            println("[SMB-JCIFS] === Starting connection ===")
            println("[SMB-JCIFS] Host: $host")
            println("[SMB-JCIFS] Share: '$share'")
            println("[SMB-JCIFS] Username: '$username'")
            println("[SMB-JCIFS] Domain: '$domain'")
            
            this@SmbClient.host = host
            this@SmbClient.share = share
            this@SmbClient.username = username
            this@SmbClient.password = password
            this@SmbClient.domain = domain
            
            // Configure JCIFS to use SMB2/SMB3 only (disable SMB1)
            val properties = Properties()
            properties.setProperty("jcifs.smb.client.minVersion", "SMB200")
            properties.setProperty("jcifs.smb.client.maxVersion", "SMB311")
            properties.setProperty("jcifs.smb.client.dfs.disabled", "true")
            properties.setProperty("jcifs.smb.client.responseTimeout", "30000")
            properties.setProperty("jcifs.smb.client.soTimeout", "30000")
            
            // Set authentication in properties
            if (domain.isNotEmpty()) {
                properties.setProperty("jcifs.smb.client.domain", domain)
                println("[SMB-JCIFS] Domain set: '$domain'")
            }
            properties.setProperty("jcifs.smb.client.username", username)
            properties.setProperty("jcifs.smb.client.password", password)
            println("[SMB-JCIFS] Username set: '$username' (length: ${username.length})")
            println("[SMB-JCIFS] Password length: ${password.length}")
            
            println("[SMB-JCIFS] Creating configuration...")
            val config = PropertyConfiguration(properties)
            context = BaseContext(config)
            
            println("[SMB-JCIFS] Configuration created with domain: '$domain', user: '$username'")
            
            // Build base URL
            baseUrl = if (share.isEmpty()) {
                "smb://$host/"
            } else {
                "smb://$host/$share/"
            }
            
            println("[SMB-JCIFS] Base URL: $baseUrl")
            
            // If no share specified, just test basic connectivity
            if (share.isEmpty()) {
                println("[SMB-JCIFS] No share specified, connection setup successful")
                println("[SMB-JCIFS] === Connection established ===")
                return@withContext true
            }
            
            // Test connection by checking if share exists
            val testUrl = "smb://$host/$share/"
            println("[SMB-JCIFS] Testing connection to: $testUrl")
            val testFile = SmbFile(testUrl, context)
            
            println("[SMB-JCIFS] Checking if share exists...")
            val exists = testFile.exists()
            
            if (exists) {
                println("[SMB-JCIFS] Share exists and is accessible")
                println("[SMB-JCIFS] === Connection successful ===")
                true
            } else {
                println("[SMB-JCIFS] === ERROR: Share does not exist or access denied ===")
                false
            }
        } catch (e: Exception) {
            println("[SMB-JCIFS] === Connection error ===")
            println("[SMB-JCIFS] Error type: ${e.javaClass.simpleName}")
            println("[SMB-JCIFS] Error message: ${e.message}")
            e.printStackTrace()
            // Print full stack trace for debugging
            val writer = java.io.PrintWriter(java.io.StringWriter())
            e.printStackTrace(writer)
            println("[SMB-JCIFS] Full stack trace:\n${writer.toString()}")
            false
        }
    }
    
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        try {
            val shares = mutableListOf<String>()
            
            if (host.isEmpty()) {
                println("[SMB-JCIFS] ERROR: Host not set, cannot list shares")
                return@withContext emptyList()
            }
            
            println("[SMB-JCIFS] Listing available shares on: $host")
            
            // Connect to server root to enumerate shares
            val serverUrl = "smb://$host/"
            val serverFile = SmbFile(serverUrl, context)
            
            try {
                val files = serverFile.listFiles()
                for (file in files) {
                    val shareName = file.name.trimEnd('/')
                    // Skip hidden shares and administrative shares
                    if (!shareName.endsWith("$") && shareName.isNotBlank()) {
                        shares.add(shareName)
                        println("[SMB-JCIFS] Found share: $shareName")
                    }
                }
            } catch (e: Exception) {
                println("[SMB-JCIFS] Error listing shares: ${e.message}")
                e.printStackTrace()
            }
            
            shares
        } catch (e: Exception) {
            println("[SMB-JCIFS] Exception while listing shares: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun listFiles(remotePath: String = ""): List<SmbFileInfo> = withContext(Dispatchers.IO) {
        try {
            println("[SMB-JCIFS] Listing files in: '$remotePath'")
            val files = mutableListOf<SmbFileInfo>()
            
            val fullPath = if (remotePath.startsWith("/")) {
                "$baseUrl${remotePath.substring(1)}"
            } else {
                "$baseUrl$remotePath"
            }
            
            println("[SMB-JCIFS] Full path: $fullPath")
            
            val smbFile = SmbFile(fullPath, context)
            
            if (!smbFile.exists()) {
                println("[SMB-JCIFS] ERROR: Path does not exist: $fullPath")
                return@withContext emptyList()
            }
            
            if (!smbFile.isDirectory) {
                println("[SMB-JCIFS] ERROR: Path is not a directory: $fullPath")
                return@withContext emptyList()
            }
            
            val fileList = smbFile.listFiles()
            println("[SMB-JCIFS] Found ${fileList.size} items")
            
            for (file in fileList) {
                val fileName = file.name.trimEnd('/')
                if (fileName == "." || fileName == "..") continue
                
                val isDirectory = file.isDirectory
                val fileSize = if (isDirectory) 0L else file.length()
                
                println("[SMB-JCIFS] File: $fileName, isDir: $isDirectory, size: $fileSize")
                
                files.add(SmbFileInfo(
                    name = fileName,
                    size = fileSize,
                    isDirectory = isDirectory,
                    path = if (remotePath.endsWith("/")) "$remotePath$fileName" else "$remotePath/$fileName"
                ))
            }
            
            println("[SMB-JCIFS] Total files found: ${files.size}")
            files
        } catch (e: Exception) {
            println("[SMB-JCIFS] Error listing files: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            println("[SMB-JCIFS] Downloading: $remotePath to ${localFile.absolutePath}")
            
            val fullPath = if (remotePath.startsWith("/")) {
                "$baseUrl${remotePath.substring(1)}"
            } else {
                "$baseUrl$remotePath"
            }
            
            val smbFile = SmbFile(fullPath, context)
            
            if (!smbFile.exists()) {
                println("[SMB-JCIFS] ERROR: Remote file does not exist: $fullPath")
                return@withContext false
            }
            
            smbFile.getInputStream().use { input ->
                FileOutputStream(localFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            
            println("[SMB-JCIFS] Download successful")
            true
        } catch (e: Exception) {
            println("[SMB-JCIFS] Download error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    fun disconnect() {
        println("[SMB-JCIFS] Disconnecting")
        auth = null
        context = null
        baseUrl = ""
    }
    
    private fun normalizePath(path: String): String {
        var normalized = path.replace("\\", "/")
        if (normalized.startsWith("//")) {
            normalized = normalized.substring(1)
        }
        if (normalized.endsWith("/") && normalized.length > 1) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }
}
