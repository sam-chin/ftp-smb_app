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
import java.net.URLDecoder
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
    
    // Available shares detected during connection
    private var availableShares: List<String> = emptyList()
    
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
                
                // Configure JCIFS for file operations (SMB2/3)
                val properties = Properties()
                properties.setProperty("jcifs.smb.client.responseTimeout", "30000")
                properties.setProperty("jcifs.smb.client.soTimeout", "30000")
                properties.setProperty("jcifs.smb.client.dfs.disabled", "true")
                if (testDomain.isNotEmpty()) {
                    properties.setProperty("jcifs.smb.client.domain", testDomain)
                }
                properties.setProperty("jcifs.smb.client.username", username)
                properties.setProperty("jcifs.smb.client.password", password)
                
                log("[SMB-JCIFS] Creating CIFS context...")
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
                    log("[SMB-JCIFS] Auto-detecting available shares...")
                    availableShares = listShares()
                    log("[SMB-JCIFS] Found ${availableShares.size} available shares: ${availableShares.joinToString(", ")}")
                }
            }
            
            if (connected) {
                this@SmbClient.share = detectedShare
                this@SmbClient.domain = detectedDomain
                log("[SMB-JCIFS] === Connection successful ===")
                log("[SMB-JCIFS] Connected to share: $detectedShare")
                log("[SMB-JCIFS] Domain: ${if (detectedDomain.isEmpty()) "(empty)" else detectedDomain}")
                log("[SMB-JCIFS] Base URL: $baseUrl")
                return@withContext true
            } else if (availableShares.isNotEmpty() && detectedShare.isNotEmpty()) {
                this@SmbClient.share = detectedShare
                this@SmbClient.domain = detectedDomain
                baseUrl = "smb://$host/$detectedShare/"
                log("[SMB-JCIFS] === Connection successful ===")
                return@withContext true
            } else if (availableShares.isNotEmpty()) {
                this@SmbClient.share = ""
                this@SmbClient.domain = detectedDomain
                log("[SMB-JCIFS] === Share enumeration successful, no share selected ===")
                log("[SMB-JCIFS] Available shares: ${availableShares.joinToString(", ")}")
                return@withContext false
            } else {
                log("[SMB-JCIFS] === Connection failed ===")
                return@withContext false
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] === Connection error ===")
            log("[SMB-JCIFS] Error type: ${e.javaClass.simpleName}")
            log("[SMB-JCIFS] Error message: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    fun getAvailableShares(): List<String> = availableShares
    
    suspend fun selectShare(shareName: String): Boolean = withContext(Dispatchers.IO) {
        if (shareName !in availableShares) {
            log("[SMB-JCIFS] Share '$shareName' not in available shares")
            return@withContext false
        }
        
        try {
            share = shareName
            baseUrl = "smb://$host/$shareName/"
            log("[SMB-JCIFS] Selected share: $shareName")
            log("[SMB-JCIFS] New baseUrl: $baseUrl")
            
            val testFile = SmbFile(baseUrl, context)
            if (testFile.exists() && testFile.isDirectory) {
                log("[SMB-JCIFS] Share is accessible")
                return@withContext true
            } else {
                log("[SMB-JCIFS] Share is not accessible")
                return@withContext false
            }
        } catch (e: Exception) {
            log("[SMB-JCIFS] Error selecting share: ${e.message}")
            return@withContext false
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
            
            // Try to create a SMB1 context for share enumeration
            var shareEnumContext: CIFSContext? = null
            try {
                val props = Properties()
                props.setProperty("jcifs.smb.client.responseTimeout", "30000")
                props.setProperty("jcifs.smb.client.soTimeout", "30000")
                props.setProperty("jcifs.smb.client.username", username)
                props.setProperty("jcifs.smb.client.password", password)
                if (domain.isNotEmpty()) {
                    props.setProperty("jcifs.smb.client.domain", domain)
                }
                shareEnumContext = BaseContext(PropertyConfiguration(props))
            } catch (e: Exception) {
                log("[SMB-JCIFS] Failed to create share enumeration context: ${e.message}")
            }
            
            val enumContext = shareEnumContext ?: context
            val serverUrl = "smb://$host/"
            log("[SMB-JCIFS] Connecting to: $serverUrl with enumeration context")
            
            try {
                val serverFile = SmbFile(serverUrl, enumContext)
                log("[SMB-JCIFS] Server file created, checking exists...")
                
                val files = serverFile.listFiles()
                log("[SMB-JCIFS] Listed ${files.size} items")
                
                for (file in files) {
                    val shareName = file.name.trimEnd('/')
                    log("[SMB-JCIFS] Checking share: '$shareName'")
                    if (!shareName.endsWith("$") && shareName.isNotBlank() && shareName != "." && shareName != "..") {
                        shares.add(shareName)
                        log("[SMB-JCIFS] Added share: $shareName")
                    }
                }
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error listing shares: ${e.message}")
                e.printStackTrace()
            }
            
            log("[SMB-JCIFS] Total shares found: ${shares.size}")
            shares
        } catch (e: Exception) {
            log("[SMB-JCIFS] Exception listing shares: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun listFiles(remotePath: String = ""): List<SmbFileInfo> = withContext(Dispatchers.IO) {
        try {
            log("[SMB-JCIFS] === listFiles START ===")
            log("[SMB-JCIFS] Input remotePath: '$remotePath'")
            log("[SMB-JCIFS] baseUrl: '$baseUrl', share: '$share'")
            val files = mutableListOf<SmbFileInfo>()
            
            val normalizedPath = if (remotePath.startsWith("/") && remotePath.length > 1) {
                remotePath.substring(1)
            } else if (remotePath == "/") {
                ""
            } else {
                remotePath
            }
            log("[SMB-JCIFS] normalizedPath: '$normalizedPath'")
            
            val fullPath = "$baseUrl$normalizedPath"
            log("[SMB-JCIFS] JCIFS fullPath: '$fullPath'")
            
            val smbFile = SmbFile(fullPath, context)
            
            if (!smbFile.exists()) {
                log("[SMB-JCIFS] ERROR: Path does not exist")
                return@withContext emptyList()
            }
            
            if (!smbFile.isDirectory) {
                log("[SMB-JCIFS] ERROR: Path is not a directory")
                return@withContext emptyList()
            }
            
            val fileList = smbFile.listFiles()
            log("[SMB-JCIFS] Found ${fileList.size} items")
            
            val cleanFullPath = fullPath.trimEnd('/')
            log("[SMB-JCIFS] fullPath: '$fullPath', cleanFullPath: '$cleanFullPath', normalizedPath: '$normalizedPath'")
            
            for (file in fileList) {
                val rawName = file.name
                val getNameResult = try { file.getName() } catch (e: Exception) { null }
                log("[SMB-JCIFS] === JCIFS raw file.name: '$rawName', getName(): '$getNameResult'")
                
                val displayName = getNameResult ?: rawName
                val trimmedName = displayName.trimEnd('/')
                
                if (trimmedName == "." || trimmedName == "..") continue
                
                val isDirectory = file.isDirectory
                val fileSize = if (isDirectory) 0L else file.length()
                
                var fileName = if (trimmedName.contains('/')) {
                    trimmedName.substringAfterLast('/')
                } else {
                    trimmedName
                }
                
                log("[SMB-JCIFS] fileName from JCIFS: '$fileName'")
                
                if (normalizedPath.isNotEmpty()) {
                    val lastSegment = normalizedPath.substringAfterLast('/')
                    if (lastSegment.isNotEmpty() && fileName.startsWith(lastSegment)) {
                        val afterSegment = fileName.substring(lastSegment.length)
                        if (afterSegment.isNotEmpty() && !afterSegment.startsWith("/")) {
                            log("[SMB-JCIFS] JCIFS returned filename with lastSegment prefix! Stripping '$lastSegment' from '$fileName'")
                            fileName = afterSegment
                            log("[SMB-JCIFS] After strip: '$fileName'")
                        }
                    }
                }
                
                log("[SMB-JCIFS] Final fileName: '$fileName'")
                
                val filePath: String
                if (normalizedPath.isEmpty()) {
                    filePath = "/$fileName"
                } else {
                    filePath = "/$normalizedPath/$fileName"
                }
                
                log("[SMB-JCIFS] Final: name='$fileName', path='$filePath'")
                
                files.add(SmbFileInfo(
                    name = fileName,
                    size = fileSize,
                    isDirectory = isDirectory,
                    path = filePath
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
     * @param remotePath The path to the remote file
     * @param startOffset The byte offset to start reading from (for Range requests)
     */
    suspend fun getFileStream(remotePath: String, startOffset: Long = 0): InputStream? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                log("[SMB-JCIFS] Opening stream for: '$remotePath' (offset: $startOffset)")
                log("[SMB-JCIFS] baseUrl: '$baseUrl', share: '$share'")
                
                val normalizedPath = normalizePathForSmb(remotePath)
                log("[SMB-JCIFS] After normalizePathForSmb: '$normalizedPath'")
                
                val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
                log("[SMB-JCIFS] After URL decode: '$decodedPath'")
                
                val fullPath = buildFullPath(decodedPath)
                log("[SMB-JCIFS] Full path: $fullPath")
                
                val smbFile = SmbFile(fullPath, context)
                
                if (!smbFile.exists() || smbFile.isDirectory) {
                    log("[SMB-JCIFS] File not found or is directory")
                    return@withContext null
                }
                
                val inputStream = smbFile.getInputStream()
                
                if (startOffset > 0) {
                    log("[SMB-JCIFS] Skipping $startOffset bytes")
                    inputStream.skip(startOffset)
                }
                
                inputStream
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
                
                val normalizedPath = normalizePathForSmb(remotePath)
                val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
                val fullPath = buildFullPath(decodedPath)
                
                log("[SMB-JCIFS] Full path: $fullPath")
                
                val smbFile = SmbFile(fullPath, context)
                
                if (smbFile.exists() && !smbFile.isDirectory) {
                    val size = smbFile.length()
                    log("[SMB-JCIFS] File exists, size: $size")
                    return@withContext size
                }
                
                log("[SMB-JCIFS] File not found, trying alternatives...")
                
                if (decodedPath.isNotEmpty()) {
                    val parentPath = decodedPath.substringBeforeLast('/', "")
                    val fileName = decodedPath.substringAfterLast('/')
                    
                    if (parentPath.isNotEmpty()) {
                        val parentFullPath = buildFullPath(parentPath)
                        log("[SMB-JCIFS] Listing parent: $parentFullPath")
                        
                        try {
                            val parentFile = SmbFile(parentFullPath, context)
                            if (parentFile.exists() && parentFile.isDirectory) {
                                for (f in parentFile.listFiles()) {
                                    val name = f.name.trimEnd('/')
                                    if (name == fileName && f.isDirectory == false) {
                                        val size = f.length()
                                        log("[SMB-JCIFS] Found file in parent: $name, size: $size")
                                        return@withContext size
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log("[SMB-JCIFS] Error listing parent: ${e.message}")
                        }
                    }
                }
                
                0L
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error getting file size: ${e.message}")
                0L
            }
        }
    }
    
    private fun normalizePathForSmb(remotePath: String): String {
        log("[SMB-JCIFS] normalizePathForSmb input: '$remotePath', share: '$share'")
        
        var normalized = if (remotePath.startsWith("/") && remotePath.length > 1) {
            remotePath.substring(1)
        } else if (remotePath == "/") {
            ""
        } else {
            remotePath
        }
        
        log("[SMB-JCIFS] After removing leading slash: '$normalized'")
        
        if (share.isNotEmpty()) {
            if (normalized.startsWith("$share/")) {
                normalized = normalized.substring(share.length + 1)
                log("[SMB-JCIFS] Removed share prefix, new path: '$normalized'")
            } else if (normalized == share) {
                normalized = ""
                log("[SMB-JCIFS] Path is just share name, set to empty")
            }
        }
        
        return normalized
    }
    
    private fun buildFullPath(relativePath: String): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        return if (relativePath.isEmpty()) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/$relativePath"
        }
    }
    
    suspend fun rename(remotePath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                log("[SMB-JCIFS] Renaming: $remotePath -> $newName")
                
                val normalizedPath = normalizePathForSmb(remotePath)
                val decodedPath = URLDecoder.decode(normalizedPath, "UTF-8")
                val fullPath = buildFullPath(decodedPath)
                
                log("[SMB-JCIFS] Full path: $fullPath")
                
                val smbFile = SmbFile(fullPath, context)
                
                if (!smbFile.exists()) {
                    log("[SMB-JCIFS] File not found")
                    return@withContext false
                }
                
                val parentPath = decodedPath.substringBeforeLast('/', "")
                val newFullPath = if (parentPath.isEmpty()) {
                    buildFullPath(newName)
                } else {
                    "${buildFullPath(parentPath)}/$newName"
                }
                
                log("[SMB-JCIFS] Target path: $newFullPath")
                
                val targetFile = SmbFile(newFullPath, context)
                val success = smbFile.renameTo(targetFile)
                
                if (success) {
                    log("[SMB-JCIFS] Rename successful")
                } else {
                    log("[SMB-JCIFS] Rename failed")
                }
                
                return@withContext success
            } catch (e: Exception) {
                log("[SMB-JCIFS] Error renaming file: ${e.message}")
                e.printStackTrace()
                return@withContext false
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
