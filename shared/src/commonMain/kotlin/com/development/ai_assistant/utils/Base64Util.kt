package com.development.ai_assistant.utils

/**
 * 跨平台 Base64 编解码工具类 (Cross-platform Base64 Utility)
 *
 * 将本地系统图库拾取的 ByteArray 图像流，转换为可供大模型网络协议传输的 Base64 文本串。
 */
object Base64Util {
    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * 将二进制字节流编码为标准 Base64 字符串
     *
     * 该算法通过位移运算 (Bitwise shift) 处理底层数据，避免引入沉重的外部依赖。
     *
     * @param bytes 待转换的底层图像二进制字节数组 (由 Peekaboo 等跨端选择器返回)
     * @return 转换后的 Base64 文本数据，可直接拼接 "data:image/jpeg;base64," 组装进 JSON 请求体
     */
    fun encode(bytes: ByteArray): String {
        val result = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            i++
            if (i < bytes.size) {
                val b1 = bytes[i].toInt() and 0xFF
                i++
                if (i < bytes.size) {
                    val b2 = bytes[i].toInt() and 0xFF
                    i++
                    result.append(BASE64_CHARS[b0 shr 2])
                    result.append(BASE64_CHARS[((b0 and 0x03) shl 4) or (b1 shr 4)])
                    result.append(BASE64_CHARS[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    result.append(BASE64_CHARS[b2 and 0x3F])
                } else {
                    result.append(BASE64_CHARS[b0 shr 2])
                    result.append(BASE64_CHARS[((b0 and 0x03) shl 4) or (b1 shr 4)])
                    result.append(BASE64_CHARS[(b1 and 0x0F) shl 2])
                    result.append('=')
                }
            } else {
                result.append(BASE64_CHARS[b0 shr 2])
                result.append(BASE64_CHARS[(b0 and 0x03) shl 4])
                result.append("==")
            }
        }
        return result.toString()
    }
}