package com.cpaphone.core.translator

import kotlinx.serialization.json.*

/**
 * 流式 SSE 数据块（Chunk）实时协议转译器
 * 负责将上游（如 Anthropic Claude 流式事件）实时重构转译为下游标准的
 * OpenAI `chat.completion.chunk` 规范，实现无延迟、跨协议流式平滑渲染。
 */
object StreamChunkTranslator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 将 Claude 原生 SSE 数据行转译为 OpenAI 兼容的 chunk 数据行
     * @param claudeEventLine 完整的 Claude SSE 单行，形如 `data: {"type": "content_block_delta", ...}`
     * @param modelName 呈现给客户端的模型名称
     * @return OpenAI 兼容的 SSE 消息行（形如 `data: {...}\n\n`）；若该事件无需下发则返回 null
     */
    fun translateClaudeToOpenAiChunk(
        claudeEventLine: String,
        modelName: String,
        chunkId: String = "chatcmpl-stream"
    ): String? {
        val trimmed = claudeEventLine.trim()
        if (!trimmed.startsWith("data:")) return null

        val dataPayload = trimmed.removePrefix("data:").trim()
        if (dataPayload.isEmpty() || dataPayload == "[DONE]") return "data: [DONE]\n\n"

        val root = try {
            json.parseToJsonElement(dataPayload).jsonObject
        } catch (_: Exception) {
            return null
        }

        val eventType = root["type"]?.jsonPrimitive?.content ?: return null

        return when (eventType) {
            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject ?: return null
                val deltaType = delta["type"]?.jsonPrimitive?.content

                when (deltaType) {
                    "text_delta" -> {
                        val text = delta["text"]?.jsonPrimitive?.content ?: ""
                        buildOpenAiChunk(chunkId, modelName, content = text, reasoning = null)
                    }
                    "thinking_delta" -> {
                        val thinking = delta["thinking"]?.jsonPrimitive?.content ?: ""
                        buildOpenAiChunk(chunkId, modelName, content = null, reasoning = thinking)
                    }
                    else -> null
                }
            }
            "message_delta" -> {
                val stopReason = root["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                if (stopReason != null) {
                    val finishReason = if (stopReason == "end_turn") "stop" else stopReason
                    buildOpenAiFinishChunk(chunkId, modelName, finishReason)
                } else null
            }
            "message_stop" -> {
                "data: [DONE]\n\n"
            }
            else -> null
        }
    }

    private fun buildOpenAiChunk(
        id: String,
        model: String,
        content: String?,
        reasoning: String?
    ): String {
        val chunkJson = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {
                        content?.let { put("content", it) }
                        reasoning?.let { put("reasoning_content", it) }
                    }
                    put("finish_reason", JsonNull)
                }
            }
        }.toString()

        return "data: $chunkJson\n\n"
    }

    private fun buildOpenAiFinishChunk(
        id: String,
        model: String,
        finishReason: String
    ): String {
        val chunkJson = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {}
                    put("finish_reason", finishReason)
                }
            }
        }.toString()

        return "data: $chunkJson\n\n"
    }
}
