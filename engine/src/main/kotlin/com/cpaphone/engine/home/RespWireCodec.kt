package com.cpaphone.engine.home

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 轻量级 Redis RESP (REdis Serialization Protocol) 编解码器
 * 规范支持：
 * Simple Strings: "+OK\r\n"
 * Errors: "-ERR ...\r\n"
 * Integers: ":1000\r\n"
 * Bulk Strings: "$6\r\nfoobar\r\n" (空字符串: "$-1\r\n")
 * Arrays: "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n"
 */
object RespWireCodec {

    private const val CRLF = "\r\n"

    /**
     * 编码命令数组为标准 RESP 二进制字节流
     */
    fun encodeCommand(args: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val header = "*${args.size}$CRLF".toByteArray(StandardCharsets.UTF_8)
        out.write(header)

        for (arg in args) {
            val bytes = arg.toByteArray(StandardCharsets.UTF_8)
            val lenHeader = "$${bytes.size}$CRLF".toByteArray(StandardCharsets.UTF_8)
            out.write(lenHeader)
            out.write(bytes)
            out.write(CRLF.toByteArray(StandardCharsets.UTF_8))
        }

        return out.toByteArray()
    }

    /**
     * 解析基础 RESP 文本响应 (单条)
     */
    fun decodeResponse(respText: String): RespMessage {
        val trimmed = respText.trimEnd('\r', '\n')
        if (trimmed.isEmpty()) return RespMessage.NullBulk

        return when (trimmed[0]) {
            '+' -> RespMessage.SimpleString(trimmed.substring(1))
            '-' -> RespMessage.Error(trimmed.substring(1))
            ':' -> RespMessage.IntegerNumber(trimmed.substring(1).toLongOrNull() ?: 0L)
            '$' -> {
                val lines = respText.split(CRLF)
                val len = lines[0].substring(1).toIntOrNull() ?: -1
                if (len == -1) RespMessage.NullBulk else RespMessage.BulkString(lines.getOrNull(1) ?: "")
            }
            '*' -> {
                val lines = respText.split(CRLF).filter { it.isNotEmpty() }
                val arraySize = lines[0].substring(1).toIntOrNull() ?: 0
                val items = mutableListOf<String>()
                var i = 1
                while (i < lines.size) {
                    if (lines[i].startsWith("$")) {
                        val valStr = lines.getOrNull(i + 1) ?: ""
                        items.add(valStr)
                        i += 2
                    } else {
                        i++
                    }
                }
                RespMessage.ArrayElements(items)
            }
            else -> RespMessage.SimpleString(trimmed)
        }
    }

    sealed interface RespMessage {
        data class SimpleString(val value: String) : RespMessage
        data class Error(val message: String) : RespMessage
        data class IntegerNumber(val value: Long) : RespMessage
        data class BulkString(val value: String) : RespMessage
        data class ArrayElements(val elements: List<String>) : RespMessage
        object NullBulk : RespMessage
    }
}
