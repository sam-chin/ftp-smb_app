package com.example.lanmediaplayer.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet

data class SmbFileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val path: String
)

class SmbClient {
    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    
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
            println("[SMB] Starting connection to: $host")
            println("[SMB] Share: $share, User: $username, Domain: $domain")
            
            this@SmbClient.host = host
            this@SmbClient.share = share
            this@SmbClient.username = username
            this@SmbClient.password = password
            this@SmbClient.domain = domain
            
            println("[SMB] Creating SMBClient...")
            smbClient = SMBClient()
            
            println("[SMB] Connecting to host...")
            connection = smbClient?.connect(host)
            if (connection == null) {
                println("[SMB] Failed to create connection")
                return@withContext false
            }
            println("[SMB] Connection established")
            
            val authContext = if (domain.isNotEmpty()) {
                println("[SMB] Creating auth context with domain: $domain")
                AuthenticationContext(username, password.toCharArray(), domain)
            } else {
                println("[SMB] Creating auth context without domain")
                AuthenticationContext(username, password.toCharArray(), null)
            }
            
            println("[SMB] Authenticating...")
            session = connection?.authenticate(authContext)
            if (session == null) {
                println("[SMB] Authentication failed")
                return@withContext false
            }
            println("[SMB] Authentication successful")
            
            println("[SMB] Connecting to share: $share")
            val connectedShare = session?.connectShare(share)
            diskShare = if (connectedShare is DiskShare) connectedShare else null
            if (diskShare == null) {
                println("[SMB] Failed to connect to share: $share")
                return@withContext false
            }
            println("[SMB] Successfully connected to share")
            
            true
        } catch (e: Exception) {
            println("[SMB] Connection error: ${e.message}")
            e.printStackTrace()
            disconnect()
            false
        }
    }
    
    suspend fun listFiles(remotePath: String = ""): List<SmbFileInfo> = withContext(Dispatchers.IO) {
        try {
            // Normalize path - convert "/" to empty string for SMB root
            val normalizedPath = normalizePath(remotePath)
            val smbPath = if (normalizedPath == "/" || normalizedPath.isEmpty()) "" else normalizedPath
            
            println("[SMB] Listing files - original path: '$remotePath', normalized: '$normalizedPath', smbPath: '$smbPath'")
            val files = mutableListOf<SmbFileInfo>()
            
            diskShare?.let { share ->
                println("[SMB] Calling share.list('$smbPath')...")
                val fileInfos = share.list(smbPath)
                println("[SMB] Raw file count: ${fileInfos.size}")
                
                if (fileInfos.isEmpty()) {
                    println("[SMB] WARNING: share.list() returned empty list!")
                }
                
                for (fileInfo in fileInfos) {
                    val fileName = fileInfo.fileName
                    println("[SMB] Raw entry: '$fileName'")
                    if (fileName == "." || fileName == "..") {
                        println("[SMB] Skipping: $fileName")
                        continue
                    }
                    
                    // Check if directory using Java interop
                    val attrs = fileInfo.fileAttributes
                    val isDirectory = (attrs as java.util.Collection<FileAttributes>).contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    println("[SMB] File: $fileName, isDir: $isDirectory, size: ${fileInfo.endOfFile}")
                    
                    files.add(SmbFileInfo(
                        name = fileName,
                        size = fileInfo.endOfFile,
                        isDirectory = isDirectory,
                        path = if (smbPath.isEmpty()) "/$fileName" else if (smbPath.endsWith("/")) "$smbPath$fileName" else "$smbPath/$fileName"
                    ))
                }
            } ?: run {
                println("[SMB] ERROR: diskShare is null! Connection may not be established.")
            }
            
            println("[SMB] Total files found: ${files.size}")
            files
        } catch (e: Exception) {
            println("[SMB] Error listing files: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedPath = normalizePath(remotePath)
            
            diskShare?.let { share ->
                val file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                
                val inputStream = file.inputStream
                val outputStream = FileOutputStream(localFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                file.close()
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun disconnect() {
        try {
            diskShare?.close()
            session?.close()
            connection?.close()
            smbClient?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            diskShare = null
            session = null
            connection = null
            smbClient = null
        }
    }
    
    private fun normalizePath(path: String): String {
        var normalized = path.replace("\\", "/")
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1)
        }
        return normalized
    }
}
