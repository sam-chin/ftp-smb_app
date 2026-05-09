package com.lanmedia.player.network

import java.nio.charset.Charset

/**
 * 智能编码检测和处理工具
 * 用于 FTP/SMB 等协议的中文文件名处理
 */
object EncodingUtils {
    
    /**
     * 检测字节数组的编码
     * 优先尝试常见中文编码，返回最可能的编码
     */
    fun detectEncoding(bytes: ByteArray): Charset {
        // 尝试多种编码，选择能正确解码中文的编码
        val encodings = listOf("GBK", "GB2312", "UTF-8", "ISO-8859-1")
        
        for (encodingName in encodings) {
            try {
                val charset = Charset.forName(encodingName)
                val text = String(bytes, charset)
                
                // 检查是否包含有效的中文字符
                if (containsValidChinese(text)) {
                    return charset
                }
            } catch (e: Exception) {
                // 跳过无法使用的编码
            }
        }
        
        // 默认返回 UTF-8
        return Charsets.UTF_8
    }
    
    /**
     * 检查文本是否包含有效的中文字符
     */
    private fun containsValidChinese(text: String): Boolean {
        var chineseCount = 0
        var totalNonAscii = 0
        
        for (char in text) {
            if (char.code > 127) {
                totalNonAscii++
                // 检查是否是常见的中文字符范围
                if (char in '\u4e00'..'\u9fff' ||  // CJK统一汉字
                    char in '\u3000'..'\u303f' ||  // CJK标点符号
                    char in '\uff00'..'\uffef') {  // 全角ASCII、全角标点
                    chineseCount++
                }
            }
        }
        
        // 如果非ASCII字符中有超过50%是中文字符，认为是有效中文
        return totalNonAscii > 0 && chineseCount.toDouble() / totalNonAscii > 0.5
    }
    
    /**
     * 将字节数组转换为UTF-8字符串
     * 自动检测源编码并转换
     */
    fun bytesToUtf8String(bytes: ByteArray): String {
        val sourceCharset = detectEncoding(bytes)
        val text = String(bytes, sourceCharset)
        
        // 如果源编码不是UTF-8，需要转换
        return if (sourceCharset != Charsets.UTF_8) {
            text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        } else {
            text
        }
    }
    
    /**
     * 将字符串转换为适合发送的字节数组
     * 检测字符串内容，选择合适的编码
     */
    fun stringToBytes(text: String): ByteArray {
        // 检查是否包含非ASCII字符
        val hasNonAscii = text.any { it.code > 127 }
        
        return if (hasNonAscii) {
            // 包含中文等非ASCII字符，使用UTF-8
            text.toByteArray(Charsets.UTF_8)
        } else {
            // 纯ASCII，使用ASCII编码
            text.toByteArray(Charsets.US_ASCII)
        }
    }
    
    /**
     * 尝试用多种编码解码，返回第一个成功的结果
     * 用于FTP数据连接的灵活解码
     */
    fun decodeWithFallback(bytes: ByteArray, preferredEncodings: List<String> = listOf("GBK", "UTF-8", "GB2312", "ISO-8859-1")): Pair<String, String> {
        for (encodingName in preferredEncodings) {
            try {
                val charset = Charset.forName(encodingName)
                val text = String(bytes, charset)
                
                // 验证解码是否合理
                if (isReasonableDecode(text)) {
                    return Pair(text, encodingName)
                }
            } catch (e: Exception) {
                // 尝试下一个编码
            }
        }
        
        // 所有编码都失败，使用UTF-8作为最后手段
        return Pair(String(bytes, Charsets.UTF_8), "UTF-8 (fallback)")
    }
    
    /**
     * 检查解码结果是否合理
     */
    private fun isReasonableDecode(text: String): Boolean {
        // 检查是否包含不可打印字符（除了正常的空白字符）
        val hasInvalidChars = text.any { 
            it.code < 32 && it != '\n' && it != '\r' && it != '\t' 
        }
        
        // 如果不包含无效字符，认为解码合理
        return !hasInvalidChars
    }
}
