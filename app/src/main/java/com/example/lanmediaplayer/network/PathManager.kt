package com.lanmedia.player.network

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 统一路径管理器
 * 
 * 职责：
 * - HTTP URL 编码/解码
 * - SMB/FTP 路径转换
 * - 路径验证和规范化
 * 
 * 设计原则：
 * - 纯工具类，无状态
 * - 所有方法都是静态的
 * - 不依赖任何外部组件
 */
object PathManager {
    
    /**
     * 将原始文件路径编码为 HTTP URL 安全格式
     * 
     * 示例：
     *   输入: /文件夹/图片.jpg
     *   输出: %E6%96%87%E4%BB%B6%E5%A4%B9/%E5%9B%BE%E7%89%87.jpg
     */
    fun encodeForHttp(rawPath: String): String {
        val cleanPath = if (rawPath.startsWith("/")) rawPath.substring(1) else rawPath
        
        return cleanPath.split("/")
            .map { segment -> URLEncoder.encode(segment, "UTF-8").replace("+", "%20") }
            .joinToString("/")
    }
    
    /**
     * 将 HTTP URL 路径解码为原始路径
     * 
     * 示例：
     *   输入: %E6%96%87%E4%BB%B6%E5%A4%B9/%E5%9B%BE%E7%89%87.jpg
     *   输出: /文件夹/图片.jpg
     */
    fun decodeFromHttp(encodedPath: String): String {
        val decoded = URLDecoder.decode(encodedPath, "UTF-8")
        return if (decoded.startsWith("/")) decoded else "/$decoded"
    }
    
    /**
     * 将原始路径转换为 SMB 相对路径（去掉共享名前缀）
     * 
     * 示例：
     *   输入: /share/文件夹/文件.txt, shareName=share
     *   输出: 文件夹/文件.txt
     */
    fun toSmbRelativePath(rawPath: String, shareName: String): String {
        var path = if (rawPath.startsWith("/")) rawPath.substring(1) else rawPath
        
        // 去掉共享名前缀
        if (shareName.isNotEmpty()) {
            when {
                path.startsWith("$shareName/") -> path = path.substring(shareName.length + 1)
                path == shareName -> path = ""  // 根目录用空字符串
            }
        }
        
        return path
    }
    
    /**
     * 将原始路径转换为 FTP 路径（保持绝对路径）
     * 
     * 示例：
     *   输入: /文件夹/文件.txt
     *   输出: /文件夹/文件.txt
     */
    fun toFtpPath(rawPath: String): String {
        return if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    }
    
    /**
     * 验证路径合法性（检查非法字符）
     */
    fun isValidPath(path: String): Boolean {
        val invalidChars = setOf('\\', ':', '*', '?', '"', '<', '>', '|')
        return path.isNotEmpty() && path.none { it in invalidChars }
    }
    
    /**
     * 规范化路径（去除多余的 / 和 ..）
     * 
     * 示例：
     *   输入: /folder/../file.txt
     *   输出: /file.txt
     */
    fun normalizePath(path: String): String {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        val normalized = mutableListOf<String>()
        
        for (part in parts) {
            if (part == "..") {
                if (normalized.isNotEmpty()) normalized.removeAt(normalized.size - 1)
            } else {
                normalized.add(part)
            }
        }
        
        return "/" + normalized.joinToString("/")
    }
}
