package com.example.lanmediaplayer.network

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Properties

data class SmbFileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val path: String
)

class SmbClient(private val logCallback: ((String) -> Unit)? = null) {
    private var auth: NtlmPasswordAuthenticator? = null
    private var context: CIFSContext? = null
    private var baseUrl: String = ""
    
    private var host: String = ""
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""
    
    // Track server's detected encoding from file listings
    private var serverEncoding: Charset? = null
    
    // Mutex to synchronize SMB operations
    private val operationMutex = Mutex()
    
    private fun log(message: String) {
        // Use Android Log with UTF-8 support
        Log.i("SmbClient", message)
        println(message)
        logCallback?.invoke(message)
    }
    
    suspend fun connect(
        host: String,
        share: String = "",  // Empty means auto-detect
        username: String,
        password: String,
        domain: String = ""  // Empty means try common domains
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            log("[SMB-JCIFS] === Starting connection ===")
            log("[SMB-JCIFS] Host: $host")
            log("[SMB-JCIFS] Share: '$share' (empty means auto-detect)")
            log("[SMB-JCIFS] Username: '$username'")
            log("[SMB-JCIFS] Domain: '$domain' (empty means auto-detect)")
            
            this@SmbClient.host = host
            this@SmbClient.username = username
            this@SmbClient.password = password
            
            // Try multiple domain values if not specified
            val domainsToTry = if (domain.isNotEmpty()) {
                listOf(domain)
            } else {
                // Try common domain values
                listOf("", ".", "WORKGROUP", "workgroup")
            }
            
            var connected = false
            var detectedShare = share
            var detectedDomain = domain
            
            for (testDomain in domainsToTry) {
                if (connected) break
                
                this@SmbClient.domain = testDomain
                log("[SMB-JCIFS] Trying domain: '${if (testDomain.isEmpty()) "(empty)" else testDomain}'")
                
                // Configure JCIFS to use SMB2/SMB3 only (disable SMB1)
                val properties = Properties()
                properties.setProperty("jcifs.smb.client.minVersion", "SMB202")
                properties.setProperty("jcifs.smb.client.maxVersion", "SMB311")
                properties.setProperty("jcifs.smb.client.dfs.disabled", "true")
                properties.setProperty("jcifs.smb.client.responseTimeout", "30000")
                properties.setProperty("jcifs.smb.client.soTimeout", "30000")
                
                // Set authentication in properties
                if (testDomain.isNotEmpty()) {
                    properties.setProperty("jcifs.smb.client.domain", testDomain)
                }
                properties.setProperty("jcifs.smb.client.username", username)
                properties.setProperty("jcifs.smb.client.password", password)
                
                log("[SMB-JCIFS] Creating configuration...")
                val config = PropertyConfiguration(properties)
                context = BaseContext(config)
                
                if (share.isNotEmpty()) {
                    // Test specified share
                    baseUrl = "smb://$host/$share/"
                    log("[SMB-JCIFS] Testing specified share: $baseUrl")
                    
                    try {
                        val testFile = SmbFile(baseUrl, context)
                        if (testFile.exists()) {
                            log("[SMB-JCIFS] Share exists and is accessible")
                            detectedShare = share
                            detectedDomain = testDomain
                            connected = true
                        } else {
                            log("[SMB-JCIFS] Share does not exist or access denied")
                        }
                    } catch (e: Exception) {
                        log("[SMB-JCIFS] Error testing share: ${e.message}")
                    }
                } else {
                    // Auto-detect shares using listShares method
                    log("[SMB-JCIFS] Auto-detecting available shares using listShares()...")
                    try {
                        val availableShares = listShares()
                        log("[SMB-JCIFS] Found ${availableShares.size} available shares: ${availableShares.joinToString(", ")}")
                        
                        if (availableShares.isNotEmpty()) {
                            // Try each share until we find one that's accessible
                            for (shareName in availableShares) {
                                log("[SMB-JCIFS] Testing share: $shareName")
                                val shareUrl = "smb://$host/$shareName/"
                                
                                try {
                                    val shareFile = SmbFile(shareUrl, context)
                                    if (shareFile.exists() && shareFile.isDirectory) {
                                        log("[SMB-JCIFS] Found accessible share: $shareName")
                                        detectedShare = shareName
                                        detectedDomain = testDomain
                                        baseUrl = shareUrl
                                        connected = true
                                        break
                                    } else {
                                        log("[SMB-JCIFS] Share $shareName exists but is not accessible or not a directory")
                                    }
                                } catch (e: Exception) {
                                    log("[SMB-JCIFS] Error testing share $shareName: ${e.message}")
                                }
                            }
                        } else {
                            log("[SMB-JCIFS] No shares found on server")
                            log("[SMB-JCIFS] TIP: Please specify share name manually (e.g., 'gx', 'shared', etc.)")
                        }
                    } catch (e: Exception) {
                        log("[SMB-JCIFS] Error listing shares: ${e.message}")
                        log("[SMB-JCIFS] Exception type: ${e.javaClass.simpleName}")
                        log("[SMB-JCIFS] TIP: Auto-detection failed. Please specify share name manually.")
                        e.printStackTrace()
                    }
                }
            }
            
            if (connected) {
                this@SmbClient.share = detectedShare
                this@SmbClient.domain = detectedDomain
                log("[SMB-JCIFS] === Connection successful ===")
                log("[SMB-JCIFS] Connected to share: $detectedShare")
                log("[SMB-JCIFS] Domain: ${if (detectedDomain.isEmpty()) "(empty)" else detectedDomain}")
                log("[SMB-JCIFS] Base URL: $baseUrl")
                true
            } else {
                log("[SMB-JCIFS] === Connection failed ===")
                false
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] === Connection error ===")
            log("[SMB-JCIFS] Error type: ${e.javaClass.simpleName}")
            log("[SMB-JCIFS] Error message: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        try {
            val shares = mutableListOf<String>()
            
            if (host.isEmpty()) {
            log("[SMB-JCIFS] ERROR: Host not set, cannot list shares")
                return@withContext emptyList()
            }
            
            log("[SMB-JCIFS] Listing available shares on: $host")
            
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
            log("[SMB-JCIFS] Found share: $shareName")
                    }
                }
            } catch (e: Exception) {
            log("[SMB-JCIFS] Error listing shares: ${e.message}")
                e.printStackTrace()
            }
            
            shares
        } catch (e: Exception) {
            log("[SMB-JCIFS] Exception while listing shares: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun listFiles(remotePath: String = ""): List<SmbFileInfo> = withContext(Dispatchers.IO) {
        try {
            log("[SMB-JCIFS] Listing files in: '$remotePath'")
            val files = mutableListOf<SmbFileInfo>()
            
            // Normalize path: remove leading slash for JCIFS-NG
            val normalizedPath = if (remotePath.startsWith("/") && remotePath.length > 1) {
                remotePath.substring(1)
            } else if (remotePath == "/") {
                ""
            } else {
                remotePath
            }
            
            val fullPath = "$baseUrl$normalizedPath"
            
            log("[SMB-JCIFS] Full path: $fullPath")
            
            val smbFile = SmbFile(fullPath, context)
            
            if (!smbFile.exists()) {
            log("[SMB-JCIFS] ERROR: Path does not exist: $fullPath")
                return@withContext emptyList()
            }
            
            if (!smbFile.isDirectory) {
            log("[SMB-JCIFS] ERROR: Path is not a directory: $fullPath")
                return@withContext emptyList()
            }
            
            val fileList = smbFile.listFiles()
            log("[SMB-JCIFS] Found ${fileList.size} items")
            
            // Detect server encoding from file names if not already determined
            if (serverEncoding == null && fileList.isNotEmpty()) {
                for (file in fileList) {
                    val fileName = file.name.trimEnd('/')
                    // Check if filename contains non-ASCII characters
                    if (fileName.any { it.code > 127 }) {
                        // Detected non-ASCII characters in filename
                        // For Chinese SMB servers, typically UTF-8 or GBK is used
                        val hasChinese = fileName.any { it in '\u4e00'..'\u9fff' }
                        
                        if (hasChinese) {
                            // Try to determine encoding by checking if the name makes sense
                            // JCIFS-NG typically uses the system default encoding
                            // For most modern systems, this is UTF-8
                            serverEncoding = java.nio.charset.StandardCharsets.UTF_8
                            log("[SMB-JCIFS] Detected Chinese characters in filenames, using UTF-8 encoding")
                        } else {
                            // Other non-ASCII characters, default to UTF-8
                            serverEncoding = java.nio.charset.StandardCharsets.UTF_8
                            log("[SMB-JCIFS] Detected non-ASCII characters in filenames, using UTF-8 encoding")
                        }
                        break
                    }
                }
            }
            
            for (file in fileList) {
                val fileName = file.name.trimEnd('/')
                if (fileName == "." || fileName == "..") continue
                
                val isDirectory = file.isDirectory
                val fileSize = if (isDirectory) 0L else file.length()
                
            log("[SMB-JCIFS] File: $fileName, isDir: $isDirectory, size: $fileSize")
                
                files.add(SmbFileInfo(
                    name = fileName,
                    size = fileSize,
                    isDirectory = isDirectory,
                    path = if (normalizedPath == "") {
                        "/$fileName"
                    } else if (normalizedPath.endsWith("/")) {
                        "/$normalizedPath$fileName"
                    } else {
                        "/$normalizedPath/$fileName"
                    }
                ))
            }
            
            log("[SMB-JCIFS] Total files found: ${files.size}")
            files
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error listing files: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = getFileStream(remotePath) ?: return@withContext false
            
            FileOutputStream(localFile).use { output ->
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
            
            log("[SMB-JCIFS] Download successful")
            true
        } catch (e: Exception) {
            log("[SMB-JCIFS] Download error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Get an InputStream for streaming file content without downloading to disk
     * This enables progressive playback and seeking
     */
    suspend fun getFileStream(remotePath: String): InputStream? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                // Normalize path: remove leading slash for JCIFS-NG
                val normalizedPath = if (remotePath.startsWith("/") && remotePath.length > 1) {
                    remotePath.substring(1)
                } else if (remotePath == "/") {
                    ""
                } else {
                    remotePath
                }
                
                val fullPath = "$baseUrl$normalizedPath"
                val smbFile = SmbFile(fullPath, context)
                
                if (!smbFile.exists() || smbFile.isDirectory) {
                    return@withContext null
                }
                
                smbFile.getInputStream()
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error opening stream: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Get file size
     */
    suspend fun getFileSize(remotePath: String): Long = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                log("[SMB-JCIFS] getFileSize called with path: '$remotePath'")
                
                // Normalize path
                val normalizedPath = if (remotePath.startsWith("/") && remotePath.length > 1) {
                    remotePath.substring(1)
                } else if (remotePath == "/") {
                    ""
                } else {
                    remotePath
                }
                
                val fullPath = "$baseUrl$normalizedPath"
                log("[SMB-JCIFS] Constructed full path: '$fullPath'")
                log("[SMB-JCIFS] Base URL: '$baseUrl'")
                
                val smbFile = SmbFile(fullPath, context)
                
                if (smbFile.exists() && !smbFile.isDirectory) {
                    val size = smbFile.length()
                    log("[SMB-JCIFS] File exists, size: $size")
                    size
                } else {
                    log("[SMB-JCIFS] File does not exist or is directory")
                    0L
                }
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error getting file size: ${e.message}")
                0L
            }
        }
    }
    
    fun disconnect() {
            log("[SMB-JCIFS] Disconnecting")
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
