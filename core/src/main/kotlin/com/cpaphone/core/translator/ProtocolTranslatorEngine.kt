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

        // 解析工具定义（function calling）：tools[].function.{name,description,parameters}
        val tools = root["tools"]?.jsonArray?.mapNotNull { toolElem ->
            val toolObj = toolElem.jsonObject
            val fn = toolObj["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            UnifiedToolDefinition(
                name = name,
                description = fn["description"]?.jsonPrimitive?.content ?: "",
                parametersSchemaJson = fn["parameters"] ?: buildJsonObject { }
            )
        } ?: emptyList()

        return UnifiedChatRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            tools = tools,
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

        // 解析工具定义（function calling）：tools[].{name,description,input_schema}
        val tools = root["tools"]?.jsonArray?.mapNotNull { toolElem ->
            val toolObj = toolElem.jsonObject
            val name = toolObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            UnifiedToolDefinition(
                name = name,
                description = toolObj["description"]?.jsonPrimitive?.content ?: "",
                parametersSchemaJson = toolObj["input_schema"] ?: buildJsonObject { }
            )
        } ?: emptyList()

        return UnifiedChatRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            tools = tools,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            isStreaming = isStream
        )
    }

    /**
     * 将 Google Gemini GenerateContent 原生 JSON 请求反序列化转换为 UnifiedChatRequest
     * 支持 camelCase 与 snake_case 双拼写；contents 兼容缺省 role（默认 user）
     */
    fun parseGeminiRequest(rawJson: String, model: String): UnifiedChatRequest {
        val root = json.parseToJsonElement(rawJson).jsonObject

        // systemInstruction：{parts:[{text}]} 或 {role,parts}，兼容 system_instruction 拼写
        var systemPrompt: String? = null
        (root["systemInstruction"] ?: root["system_instruction"])?.let { sysElem ->
            val sysObj = sysElem as? JsonObject
            val partsElem = sysObj?.get("parts") ?: (sysElem as? JsonArray)
            if (partsElem is JsonArray) {
                systemPrompt = partsElem.joinToString("\n") { partElem ->
                    (partElem as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: ""
                }.takeIf { it.isNotBlank() }
            }
        }

        val generationConfig = root["generationConfig"]?.jsonObject
            ?: root["generation_config"]?.jsonObject
        val temperature = generationConfig?.get("temperature")?.jsonPrimitive?.doubleOrNull
        val topP = generationConfig?.get("topP")?.jsonPrimitive?.doubleOrNull
            ?: generationConfig?.get("top_p")?.jsonPrimitive?.doubleOrNull
        val maxTokens = generationConfig?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull
            ?: generationConfig?.get("max_output_tokens")?.jsonPrimitive?.intOrNull
        val thinkingBudget = (generationConfig?.get("thinkingConfig")
            ?: generationConfig?.get("thinking_config"))?.jsonObject?.get("thinkingBudget")
            ?.jsonPrimitive?.intOrNull

        // tools[].functionDeclarations
        val tools = root["tools"]?.jsonArray?.flatMap { toolElem ->
            (toolElem.jsonObject["functionDeclarations"]
                ?: toolElem.jsonObject["function_declarations"])?.jsonArray?.mapNotNull { declElem ->
                val decl = declElem.jsonObject
                val name = decl["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                UnifiedToolDefinition(
                    name = name,
                    description = decl["description"]?.jsonPrimitive?.content ?: "",
                    parametersSchemaJson = decl["parameters"] ?: buildJsonObject { }
                )
            } ?: emptyList()
        } ?: emptyList()

        val messages = mutableListOf<UnifiedMessage>()
        root["contents"]?.jsonArray?.forEach { contentElem ->
            val contentObj = contentElem.jsonObject
            // Gemini role 只有 user/model；缺省视为 user，"model" 即 assistant
            val role = if (contentObj["role"]?.jsonPrimitive?.content == "model") UnifiedRole.ASSISTANT else UnifiedRole.USER
            val parts = mutableListOf<UnifiedContentPart>()

            contentObj["parts"]?.jsonArray?.forEach { partElem ->
                val part = partElem.jsonObject
                val text = part["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) {
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (isThought) {
                        parts.add(UnifiedContentPart.Thinking(reasoning = text))
                    } else {
                        parts.add(UnifiedContentPart.Text(text))
                    }
                    return@forEach
                }
                val inlineData = (part["inlineData"] ?: part["inline_data"])?.jsonObject
                if (inlineData != null) {
                    parts.add(
                        UnifiedContentPart.Image(
                            mimeType = inlineData["mimeType"]?.jsonPrimitive?.content
                                ?: inlineData["mime_type"]?.jsonPrimitive?.content ?: "application/octet-stream",
                            base64Data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                        )
                    )
                    return@forEach
                }
                val functionCall = (part["functionCall"] ?: part["function_call"])?.jsonObject
                if (functionCall != null) {
                    parts.add(
                        UnifiedContentPart.ToolCall(
                            id = functionCall["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            name = functionCall["name"]?.jsonPrimitive?.content ?: "",
                            argumentsJson = functionCall["args"]?.toString() ?: "{}"
                        )
                    )
                    return@forEach
                }
                val functionResponse = (part["functionResponse"] ?: part["function_response"])?.jsonObject
                if (functionResponse != null) {
                    // CLIProxyAPI 语义：response.result 是字符串（工具输出文本）；对象则序列化保真
                    val responseObj = functionResponse["response"] as? JsonObject
                    val content = when {
                        responseObj == null -> ""
                        responseObj["result"] is JsonPrimitive ->
                            responseObj["result"]?.jsonPrimitive?.contentOrNull ?: responseObj["result"].toString()
                        responseObj["result"] != null -> responseObj["result"].toString()
                        else -> responseObj.toString()
                    }
                    parts.add(
                        UnifiedContentPart.ToolResult(
                            toolCallId = functionResponse["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            content = content
                        )
                    )
                }
            }
            if (parts.isNotEmpty()) messages.add(UnifiedMessage(role, parts))
        }

        return UnifiedChatRequest(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            tools = tools,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            isStreaming = false,
            thinkingBudgetTokens = thinkingBudget
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

                        // 文本内容：tool 消息以 ToolResult 内容为正文；assistant 带 tool_calls 时 content 允许为空
                        val toolCalls = msg.parts.filterIsInstance<UnifiedContentPart.ToolCall>()
                        val toolResults = msg.parts.filterIsInstance<UnifiedContentPart.ToolResult>()
                        val textContent = when {
                            msg.role == UnifiedRole.TOOL && toolResults.isNotEmpty() ->
                                toolResults.joinToString("\n") { it.content }
                            else -> msg.extractPlainText()
                        }
                        when {
                            textContent.isNotEmpty() -> put("content", textContent)
                            toolCalls.isEmpty() && msg.role != UnifiedRole.ASSISTANT -> put("content", "")
                            else -> put("content", JsonNull)
                        }

                        // 工具调用转译
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
     * 完整支持工具定义/调用链（functionCall/functionResponse）与思考预算（thinkingConfig）
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
                                    is UnifiedContentPart.ToolCall -> {
                                        addJsonObject {
                                            putJsonObject("functionCall") {
                                                if (part.id.isNotBlank()) put("id", part.id)
                                                put("name", part.name)
                                                put("args", parseJsonLenient(part.argumentsJson))
                                            }
                                            // 与 AntigravityClient 同源约定：历史 functionCall 需签名占位
                                            put("thoughtSignature", "skip_thought_signature_validator")
                                        }
                                    }
                                    is UnifiedContentPart.ToolResult -> {
                                        addJsonObject {
                                            putJsonObject("functionResponse") {
                                                if (part.toolCallId.isNotBlank()) put("id", part.toolCallId)
                                                // name 从历史 ToolCall 反查（functionResponse 必须携带工具名）
                                                put("name", toolNameOf(unified, part.toolCallId))
                                                putJsonObject("response") {
                                                    put("result", part.content)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (unified.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            unified.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    if (tool.description.isNotBlank()) put("description", tool.description)
                                    put("parameters", tool.parametersSchemaJson)
                                }
                            }
                        }
                    }
                }
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") { put("mode", "AUTO") }
                }
            }

            putJsonObject("generationConfig") {
                unified.temperature?.let { put("temperature", it) }
                unified.topP?.let { put("topP", it) }
                unified.maxTokens?.let { put("maxOutputTokens", it) }
                unified.thinkingBudgetTokens?.let { budget ->
                    putJsonObject("thinkingConfig") { put("thinkingBudget", budget) }
                }
            }
        }
        return root.toString()
    }

    /** 从消息历史反查工具调用 id → name（functionResponse 必须携带工具名） */
    internal fun toolNameOf(unified: UnifiedChatRequest, toolCallId: String): String =
        unified.messages.asSequence()
            .flatMap { it.parts }
            .filterIsInstance<UnifiedContentPart.ToolCall>()
            .firstOrNull { it.id == toolCallId }?.name ?: toolCallId

    /** 宽容 JSON 解析：失败回退为字符串字面量，保证恒为合法 JSON */
    internal fun parseJsonLenient(raw: String): JsonElement = try {
        json.parseToJsonElement(raw.ifBlank { "{}" })
    } catch (_: Exception) {
        JsonPrimitive(raw)
    }
}
