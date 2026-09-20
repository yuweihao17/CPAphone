package com.cpaphone.core.translator

import kotlinx.serialization.json.*

/**
 * 跨协议全矩阵转译核心引擎
 * 负责在 OpenAI Chat Completions, Anthropic Claude Messages, Google Gemini 规范之间
 * 提供高保真、双向的统一 AST 解析与生成支持。
 */
object ProtocolTranslatorEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // =========================================================================
    // 1. 输入反序列化解析器 (Input Parsers)
    // =========================================================================

    /**
     * 将标准 OpenAI Chat Completions JSON 请求转换为 UnifiedChatRequest
     */
    fun parseOpenAiChatRequest(rawJson: String): UnifiedChatRequest {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val model = root["model"]?.jsonPrimitive?.content ?: "unknown"
        val isStream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        val temperature = root["temperature"]?.jsonPrimitive?.doubleOrNull
        val topP = root["top_p"]?.jsonPrimitive?.doubleOrNull
        val maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull

        var systemPrompt: String? = null
        val messages = mutableListOf<UnifiedMessage>()

        root["messages"]?.jsonArray?.forEach { msgElem ->
            val msgObj = msgElem.jsonObject
            val roleStr = msgObj["role"]?.jsonPrimitive?.content ?: "user"
            val contentElem = msgObj["content"]

            val parts = mutableListOf<UnifiedContentPart>()

            // 提取思考内容 (DeepSeek / OpenAI Reasoning)
            msgObj["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { reasoning ->
                if (reasoning.isNotBlank()) {
                    parts.add(UnifiedContentPart.Thinking(reasoning = reasoning))
                }
            }

            // 处理普通文本或多模态数组
            when (contentElem) {
                is JsonPrimitive -> {
                    val text = contentElem.content
                    parts.add(UnifiedContentPart.Text(text))
                }
                is JsonArray -> {
                    contentElem.forEach { partElem ->
                        if (partElem is JsonObject) {
                            val type = partElem["type"]?.jsonPrimitive?.content
                            if (type == "text") {
                                partElem["text"]?.jsonPrimitive?.content?.let {
                                    parts.add(UnifiedContentPart.Text(it))
                                }
                            } else if (type == "image_url") {
                                partElem["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content?.let { url ->
                                    if (url.startsWith("data:")) {
                                        val commaIndex = url.indexOf(",")
                                        if (commaIndex != -1) {
                                            val meta = url.substring(5, commaIndex)
                                            val mime = meta.split(";")[0]
                                            val base64 = url.substring(commaIndex + 1)
                                            parts.add(UnifiedContentPart.Image(mime, base64))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }

            // 处理 Tool Calls
            msgObj["tool_calls"]?.jsonArray?.forEach { toolCallElem ->
                val tcObj = toolCallElem.jsonObject
                val id = tcObj["id"]?.jsonPrimitive?.content ?: ""
                val fnObj = tcObj["function"]?.jsonObject
                val name = fnObj?.get("name")?.jsonPrimitive?.content ?: ""
                val args = fnObj?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                parts.add(UnifiedContentPart.ToolCall(id = id, name = name, argumentsJson = args))
            }

            when (roleStr) {
                "system" -> {
                    systemPrompt = parts.filterIsInstance<UnifiedContentPart.Text>()
                        .joinToString("\n") { it.text }
                }
                "assistant" -> messages.add(UnifiedMessage(UnifiedRole.ASSISTANT, parts))
                "tool" -> {
                    val toolCallId = msgObj["tool_call_id"]?.jsonPrimitive?.content ?: ""
                    val content = parts.filterIsInstance<UnifiedContentPart.Text>()
                        .joinToString("\n") { it.text }
                    messages.add(
                        UnifiedMessage(
                            UnifiedRole.TOOL,
                            listOf(UnifiedContentPart.ToolResult(toolCallId = toolCallId, content = content))
                        )
                    )
                }
                else -> messages.add(UnifiedMessage(UnifiedRole.USER, parts))
            }
        }

        return UnifiedChatRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            isStreaming = isStream
        )
    }

    /**
     * 将 Anthropic Claude Messages 原生 JSON 请求反序列化转换为 UnifiedChatRequest
     */
    fun parseClaudeMessagesRequest(rawJson: String): UnifiedChatRequest {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val model = root["model"]?.jsonPrimitive?.content ?: "unknown"
        val isStream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull
        val temperature = root["temperature"]?.jsonPrimitive?.doubleOrNull
        val topP = root["top_p"]?.jsonPrimitive?.doubleOrNull

        var systemPrompt: String? = null
        val sysElem = root["system"]
        if (sysElem is JsonPrimitive) {
            systemPrompt = sysElem.content
        } else if (sysElem is JsonArray) {
            systemPrompt = sysElem.joinToString("\n") {
                it.jsonObject["text"]?.jsonPrimitive?.content ?: ""
            }
        }

        val messages = mutableListOf<UnifiedMessage>()
        root["messages"]?.jsonArray?.forEach { msgElem ->
            val msgObj = msgElem.jsonObject
            val roleStr = msgObj["role"]?.jsonPrimitive?.content ?: "user"
            val contentElem = msgObj["content"]
            val parts = mutableListOf<UnifiedContentPart>()

            when (contentElem) {
                is JsonPrimitive -> parts.add(UnifiedContentPart.Text(contentElem.content))
                is JsonArray -> {
                    contentElem.forEach { partElem ->
                        val pObj = partElem.jsonObject
                        when (pObj["type"]?.jsonPrimitive?.content) {
                            "text" -> {
                                pObj["text"]?.jsonPrimitive?.content?.let {
                                    parts.add(UnifiedContentPart.Text(it))
                                }
                            }
                            "thinking" -> {
                                val reasoning = pObj["thinking"]?.jsonPrimitive?.content ?: ""
                                val signature = pObj["signature"]?.jsonPrimitive?.content
                                parts.add(UnifiedContentPart.Thinking(reasoning, signature))
                            }
                            "image" -> {
                                val src = pObj["source"]?.jsonObject
                                val mime = src?.get("media_type")?.jsonPrimitive?.content ?: "image/jpeg"
                                val data = src?.get("data")?.jsonPrimitive?.content ?: ""
                                parts.add(UnifiedContentPart.Image(mime, data))
                            }
                            "tool_use" -> {
                                val id = pObj["id"]?.jsonPrimitive?.content ?: ""
                                val name = pObj["name"]?.jsonPrimitive?.content ?: ""
                                val input = pObj["input"]?.toString() ?: "{}"
                                parts.add(UnifiedContentPart.ToolCall(id, name, input))
                            }
                            "tool_result" -> {
                                val id = pObj["tool_use_id"]?.jsonPrimitive?.content ?: ""
                                val content = pObj["content"]?.let {
                                    if (it is JsonPrimitive) it.content else it.toString()
                                } ?: ""
                                val isError = pObj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                                parts.add(UnifiedContentPart.ToolResult(id, content, isError))
                            }
                        }
                    }
                }
                else -> {}
            }

            val role = if (roleStr == "assistant") UnifiedRole.ASSISTANT else UnifiedRole.USER
            messages.add(UnifiedMessage(role, parts))
        }

        return UnifiedChatRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            isStreaming = isStream
        )
    }

    // =========================================================================
    // 2. 目标输出生成器 (Output Encoders)
    // =========================================================================

    /**
     * 将 UnifiedChatRequest 转译为标准 OpenAI Chat Completions 规范 JSON
     */
    fun toOpenAiChatJson(unified: UnifiedChatRequest, targetModel: String? = null): String {
        val root = buildJsonObject {
            put("model", targetModel ?: unified.model)
            put("stream", unified.isStreaming)
            unified.temperature?.let { put("temperature", it) }
            unified.topP?.let { put("top_p", it) }
            unified.maxTokens?.let { put("max_tokens", it) }

            putJsonArray("messages") {
                // 若包含系统提示词，前置注入 system 消息
                unified.systemPrompt?.let { sysPrompt ->
                    addJsonObject {
                        put("role", "system")
                        put("content", sysPrompt)
                    }
                }

                unified.messages.forEach { msg ->
                    addJsonObject {
                        put("role", when (msg.role) {
                            UnifiedRole.SYSTEM -> "system"
                            UnifiedRole.ASSISTANT -> "assistant"
                            UnifiedRole.TOOL -> "tool"
                            UnifiedRole.USER -> "user"
                        })

                        // 思考链提取注入 reasoning_content
                        msg.extractThinking()?.let { reasoning ->
                            put("reasoning_content", reasoning)
                        }

                        // 文本内容
                        put("content", msg.extractPlainText())

                        // 工具调用转译
                        val toolCalls = msg.parts.filterIsInstance<UnifiedContentPart.ToolCall>()
                        if (toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                toolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    /**
     * 将 UnifiedChatRequest 转译为 Anthropic Claude Messages 原生 JSON
     */
    fun toClaudeMessagesJson(unified: UnifiedChatRequest, targetModel: String? = null): String {
        val root = buildJsonObject {
            put("model", targetModel ?: unified.model)
            put("stream", unified.isStreaming)
            put("max_tokens", unified.maxTokens ?: 4096)

            unified.systemPrompt?.let { put("system", it) }
            unified.temperature?.let { put("temperature", it) }
            unified.topP?.let { put("top_p", it) }

            // 思考预算控制 (Claude Thinking)
            unified.thinkingBudgetTokens?.let { budget ->
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }

            putJsonArray("messages") {
                unified.messages.forEach { msg ->
                    if (msg.role == UnifiedRole.SYSTEM) return@forEach

                    addJsonObject {
                        put("role", if (msg.role == UnifiedRole.ASSISTANT) "assistant" else "user")
                        putJsonArray("content") {
                            msg.parts.forEach { part ->
                                when (part) {
                                    is UnifiedContentPart.Text -> {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        }
                                    }
                                    is UnifiedContentPart.Thinking -> {
                                        addJsonObject {
                                            put("type", "thinking")
                                            put("thinking", part.reasoning)
                                            part.signature?.let { put("signature", it) }
                                        }
                                    }
                                    is UnifiedContentPart.Image -> {
                                        addJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", part.mimeType)
                                                put("data", part.base64Data)
                                            }
                                        }
                                    }
                                    is UnifiedContentPart.ToolCall -> {
                                        addJsonObject {
                                            put("type", "tool_use")
                                            put("id", part.id)
                                            put("name", part.name)
                                            put("input", json.parseToJsonElement(part.argumentsJson))
                                        }
                                    }
                                    is UnifiedContentPart.ToolResult -> {
                                        addJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", part.toolCallId)
                                            put("content", part.content)
                                            if (part.isError) put("is_error", true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    /**
     * 将 UnifiedChatRequest 转译为 Google Gemini GenerateContent 原生 JSON
     */
    fun toGeminiGenerateContentJson(unified: UnifiedChatRequest): String {
        val root = buildJsonObject {
            unified.systemPrompt?.let { sysPrompt ->
                putJsonObject("system_instruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", sysPrompt) }
                    }
                }
            }

            putJsonArray("contents") {
                unified.messages.forEach { msg ->
                    if (msg.role == UnifiedRole.SYSTEM) return@forEach

                    addJsonObject {
                        put("role", if (msg.role == UnifiedRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") {
                            msg.parts.forEach { part ->
                                when (part) {
                                    is UnifiedContentPart.Text -> {
                                        addJsonObject { put("text", part.text) }
                                    }
                                    is UnifiedContentPart.Thinking -> {
                                        addJsonObject { put("text", "<thought>\n${part.reasoning}\n</thought>") }
                                    }
                                    is UnifiedContentPart.Image -> {
                                        addJsonObject {
                                            putJsonObject("inline_data") {
                                                put("mime_type", part.mimeType)
                                                put("data", part.base64Data)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }

            putJsonObject("generationConfig") {
                unified.temperature?.let { put("temperature", it) }
                unified.topP?.let { put("topP", it) }
                unified.maxTokens?.let { put("maxOutputTokens", it) }
            }
        }
        return root.toString()
    }
}
