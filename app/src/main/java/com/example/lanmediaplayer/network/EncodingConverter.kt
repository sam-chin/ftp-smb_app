package com.lanmedia.player.network

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 智能编码转换器
 * 
 * 功能：
 * 1. 自动检测字符串中的字符类型（ASCII / 中文 / 其他）
 * 2. 根据目标编码转换字符串为字节数组
 * 3. 从字节数组解码时尝试多种编码并选择最合适的
 */
object EncodingConverter {
    
    /**
     * 检测字符串是否包含中文字符
     */
    fun containsChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }
    
    /**
     * 检测字符串是否包含非 ASCII 字符
     */
    fun containsNonAscii(text: String): Boolean {
        return text.any { it.code > 127 }
    }
    
    /**
     * 将字符串转换为指定编码的字节数组
     * 
     * @param text 要转换的文本
     * @param targetCharset 目标编码（服务器的编码）
     * @return 编码后的字节数组
     */
    fun encode(text: String, targetCharset: Charset = StandardCharsets.UTF_8): ByteArray {
        return text.toByteArray(targetCharset)
    }
    
    /**
     * 将字节数组解码为字符串，尝试多种编码
     * 
     * @param bytes 要解码的字节数组
     * @param preferredCharset 首选编码（通常是服务器编码）
     * @param fallbackCharsets 备选编码列表
     * @return 解码后的字符串
     */
    fun decode(
        bytes: ByteArray,
        preferredCharset: Charset = StandardCharsets.UTF_8,
        fallbackCharsets: List<Charset> = listOf(
            Charset.forName("GBK"),
            Charset.forName("GB2312"),
            StandardCharsets.ISO_8859_1
        )
    ): String {
        // 首先尝试首选编码
        try {
            val result = String(bytes, preferredCharset)
            // 如果结果合理（没有替换字符），直接返回
            if (!result.contains('\uFFFD')) {
                return result
            }
        } catch (e: Exception) {
            // 继续尝试备选编码
        }
        
        // 尝试备选编码
        for (charset in fallbackCharsets) {
            try {
                val result = String(bytes, charset)
                if (!result.contains('\uFFFD')) {
                    return result
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        // 如果所有编码都失败，使用首选编码（可能会有替换字符）
        return String(bytes, preferredCharset)
    }
    
    /**
     * 智能选择编码
     * 
     * @param text 要发送的文本
     * @param serverEncoding 服务器编码
     * @return 应该使用的编码
     */
    fun selectEncoding(text: String, serverEncoding: Charset?): Charset {
        // 如果服务器明确支持 UTF-8，优先使用
        if (serverEncoding == StandardCharsets.UTF_8) {
            return StandardCharsets.UTF_8
        }
        
        // 如果包含中文或非标 ASCII，使用服务器编码或 UTF-8
        if (containsNonAscii(text)) {
            return serverEncoding ?: StandardCharsets.UTF_8
        }
        
        // 纯 ASCII，使用 ASCII 编码（更高效）
        return StandardCharsets.US_ASCII
    }
}
