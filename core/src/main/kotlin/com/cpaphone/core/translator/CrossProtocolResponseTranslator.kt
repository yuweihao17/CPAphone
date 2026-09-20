package com.cpaphone.core.translator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * 跨协议非流式响应转译器（对齐 CLIProxyAPI internal/translator 六方向转换器）
 *
 * 三大协议响应在凭据提供商与入站客户端协议不一致时转译：
 * - OpenAI chat.completion ↔ Claude message ↔ Gemini GenerateContent
 * - thinking 链等价映射：OpenAI reasoning_content ↔ Claude thinking block ↔ Gemini part{thought:true}
 * - 工具调用等价映射：OpenAI tool_calls(function.arguments 字符串) ↔ Claude tool_use(input 对象) ↔ Gemini functionCall(args 对象)
 * - usage 映射照抄 CLIProxyAPI（含 cached/reasoning token 细节）
 */
object CrossProtocolResponseTranslator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 入站/出站协议面 */
    enum class Protocol { OPENAI, CLAUDE, GEMINI }

    // =========================================================================
    // 终止原因集中映射表
    // =========================================================================

    /** Claude stop_reason → OpenAI finish_reason（对齐 claude_openai_response.go:286-301） */
    private fun claudeStopToOpenAi(stop: String?): String = when (stop) {
        "end_turn", "stop_sequence" -> "stop"
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        "refusal", "sensitive" -> "content_filter"
        null -> "stop"
        else -> "stop"
    }

    /** OpenAI finish_reason → Claude stop_reason */
    private fun openAiFinishToClaude(finish: String?): String = when (finish) {
        "stop" -> "end_turn"
        "tool_calls" -> "tool_use"
        "length" -> "max_tokens"
        "content_filter" -> "refusal"
        null -> "end_turn"
        else -> "end_turn"
    }

    /** OpenAI finish_reason → Gemini finishReason（对齐 gemini_openai_response.go:250-261） */
    private fun openAiFinishToGemini(finish: String?): String = when (finish) {
        "length" -> "MAX_TOKENS"
        "content_filter" -> "SAFETY"
        else -> "STOP"
    }

    /** Gemini finishReason → OpenAI finish_reason */
    private fun geminiFinishToOpenAi(finish: String?, sawToolCall: Boolean): String = when {
        sawToolCall -> "tool_calls"
        finish == "MAX_TOKENS" -> "max_tokens"
        finish == "SAFETY" || finish == "PROHIBITED_CONTENT" -> "content_filter"
        else -> "stop"
    }

    /** Gemini finishReason → Claude stop_reason */
    private fun geminiFinishToClaude(finish: String?, sawToolCall: Boolean): String = when {
        sawToolCall -> "tool_use"
        finish == "MAX_TOKENS" -> "max_tokens"
        else -> "end_turn"
    }

    // =========================================================================
    // 统一入口
    // =========================================================================

    fun translate(upstreamJson: String, from: Protocol, to: Protocol, model: String): String = when {
        from == to -> upstreamJson
        from == Protocol.CLAUDE && to == Protocol.OPENAI -> claudeToOpenAi(upstreamJson, model)
        from == Protocol.GEMINI && to == Protocol.OPENAI -> geminiToOpenAi(upstreamJson, model)
        from == Protocol.OPENAI && to == Protocol.CLAUDE -> openAiToClaude(upstreamJson, model)
        from == Protocol.OPENAI && to == Protocol.GEMINI -> openAiToGemini(upstreamJson, model)
        from == Protocol.CLAUDE && to == Protocol.GEMINI -> claudeToGemini(upstreamJson, model)
        from == Protocol.GEMINI && to == Protocol.CLAUDE -> geminiToClaude(upstreamJson, model)
        else -> upstreamJson
    }

    // =========================================================================
    // Claude → OpenAI
    // =========================================================================

    fun claudeToOpenAi(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val contentArray = root["content"] as? kotlinx.serialization.json.JsonArray

        var contentText = ""
        var reasoningText: String? = null
        val toolCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        contentArray?.forEach { elem ->
            val block = elem as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> contentText += block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                "thinking" -> reasoningText = (reasoningText ?: "") + (block["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" -> toolCalls.add(block)
            }
        }

        val usage = root["usage"]?.jsonObject
        val inputTokens = usage.longOf("input_tokens") ?: 0L
        val outputTokens = usage.longOf("output_tokens") ?: 0L
        val cacheCreation = usage.longOf("cache_creation_input_tokens") ?: 0L
        val cacheRead = usage.longOf("cache_read_input_tokens") ?: 0L
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("id", "chatcmpl-${root.idOrRandom()}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", contentText)
                        reasoningText?.let { put("reasoning_content", it) }
                        if (toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                toolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.idOrRandom())
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.nameOrEmpty())
                                            put("arguments", tc["input"]?.toString() ?: "{}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    put("finish_reason", claudeStopToOpenAi(stopReason))
                }
            }
            putJsonObject("usage") {
                // 对齐 CLIProxyAPI：prompt=input+cache_creation+cache_read，cached=cache_read
                put("prompt_tokens", inputTokens + cacheCreation + cacheRead)
                put("completion_tokens", outputTokens)
                put("total_tokens", inputTokens + cacheCreation + cacheRead + outputTokens)
                if (cacheRead > 0) {
                    putJsonObject("prompt_tokens_details") { put("cached_tokens", cacheRead) }
                }
            }
        }.toString()
    }

    // =========================================================================
    // Gemini → OpenAI
    // =========================================================================

    fun geminiToOpenAi(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val candidate = (root["candidates"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            as? JsonObject
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray
        val finishRaw = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull

        var contentText = ""
        var reasoningText: String? = null
        val toolCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val images = mutableListOf<kotlinx.serialization.json.JsonObject>()
        parts?.forEach { elem ->
            val part = elem as? JsonObject ?: return@forEach
            val fn = part["functionCall"] as? JsonObject
            if (fn != null) {
                toolCalls.add(fn)
                return@forEach
            }
            val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject
            if (inline != null) {
                images.add(inline)
                return@forEach
            }
            val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                reasoningText = (reasoningText ?: "") + text
            } else {
                contentText += text
            }
        }

        val usage = root["usageMetadata"]?.jsonObject
        val promptTokens = usage.longOf("promptTokenCount") ?: 0L
        val candidatesTokens = usage.longOf("candidatesTokenCount") ?: 0L
        val thoughtsTokens = usage.longOf("thoughtsTokenCount") ?: 0L
        val cachedTokens = usage.longOf("cachedContentTokenCount") ?: 0L
        val completionTotal = candidatesTokens + thoughtsTokens

        return buildJsonObject {
            put("id", "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", contentText)
                        reasoningText?.let { put("reasoning_content", it) }
                        if (toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                toolCalls.forEach { fn ->
                                    addJsonObject {
                                        // 对齐 CLIProxyAPI：无上游 id 时本地合成 {name}-{unixnano}
                                        put("id", fn.idOrNull() ?: "${fn.nameOrEmpty()}-${System.nanoTime()}")
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", fn.nameOrEmpty())
                                            put("arguments", fn["args"]?.toString() ?: "{}")
                                        }
                                    }
                                }
                            }
                        }
                        // 对齐 CLIProxyAPI gemini→openai 流式 delta.images：多模态以 data-URI 下发
                        if (images.isNotEmpty()) {
                            putJsonArray("images") {
                                images.forEach { inline ->
                                    addJsonObject {
                                        putJsonObject("image_url") {
                                            val mime = inline["mimeType"]?.jsonPrimitive?.content
                                                ?: inline["mime_type"]?.jsonPrimitive?.content ?: "image/png"
                                            val data = inline["data"]?.jsonPrimitive?.content ?: ""
                                            put("url", "data:$mime;base64,$data")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    put("finish_reason", geminiFinishToOpenAi(finishRaw, toolCalls.isNotEmpty()))
                    put("native_finish_reason", finishRaw?.lowercase() ?: "stop")
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTotal)
                put("total_tokens", usage.longOf("totalTokenCount") ?: (promptTokens + completionTotal))
                if (thoughtsTokens > 0) {
                    putJsonObject("completion_tokens_details") { put("reasoning_tokens", thoughtsTokens) }
                }
                if (cachedTokens > 0) {
                    putJsonObject("prompt_tokens_details") { put("cached_tokens", cachedTokens) }
                }
            }
        }.toString()
    }

    // =========================================================================
    // OpenAI → Claude
    // =========================================================================

    fun openAiToClaude(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val message = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
        val finish = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("finish_reason")?.jsonPrimitive?.contentOrNull }

        val usage = root["usage"]?.jsonObject
        val promptTokens = usage.longOf("prompt_tokens") ?: 0L
        val cachedTokens = usage.promptDetails().longOf("cached_tokens") ?: 0L
        val completionTokens = usage.longOf("completion_tokens") ?: 0L

        return buildJsonObject {
            put("id", root.idOrNull() ?: "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}")
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            putJsonArray("content") {
                message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", reasoning)
                    }
                }
                message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                (message?.get("tool_calls") as? kotlinx.serialization.json.JsonArray)?.forEach { tcElem ->
                    val tc = tcElem as? JsonObject ?: return@forEach
                    val fn = tc["function"] as? JsonObject
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", tc.idOrRandom())
                        put("name", fn?.nameOrEmpty() ?: "")
                        put("input", ProtocolTranslatorEngine.parseJsonLenient(fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"))
                    }
                }
            }
            put("stop_reason", openAiFinishToClaude(finish))
            put("stop_sequence", kotlinx.serialization.json.JsonNull)
            putJsonObject("usage") {
                // 对齐 CLIProxyAPI：input=prompt-cached（下限 0），cache_read_input_tokens=cached
                put("input_tokens", (promptTokens - cachedTokens).coerceAtLeast(0L))
                put("output_tokens", completionTokens)
                if (cachedTokens > 0) put("cache_read_input_tokens", cachedTokens)
            }
        }.toString()
    }

    // =========================================================================
    // OpenAI → Gemini
    // =========================================================================

    fun openAiToGemini(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

        val usage = root["usage"]?.jsonObject
        val promptTokens = usage.longOf("prompt_tokens") ?: 0L
        val completionTokens = usage.longOf("completion_tokens") ?: 0L
        val reasoningTokens = usage.completionDetails().longOf("reasoning_tokens") ?: 0L
        val cachedTokens = usage.promptDetails().longOf("cached_tokens") ?: 0L

        return buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") {
                            message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { reasoning ->
                                addJsonObject {
                                    put("text", reasoning)
                                    put("thought", true)
                                }
                            }
                            message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                                addJsonObject { put("text", text) }
                            }
                            (message?.get("tool_calls") as? kotlinx.serialization.json.JsonArray)?.forEach { tcElem ->
                                val tc = tcElem as? JsonObject ?: return@forEach
                                val fn = tc["function"] as? JsonObject
                                addJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", fn?.nameOrEmpty() ?: "")
                                        // OpenAI arguments 是 JSON 字符串 → Gemini args 是 JSON 对象
                                        put("args", ProtocolTranslatorEngine.parseJsonLenient(fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"))
                                    }
                                }
                            }
                        }
                    }
                    put("finishReason", openAiFinishToGemini(finish))
                }
            }
            putJsonObject("usageMetadata") {
                put("promptTokenCount", promptTokens)
                put("candidatesTokenCount", completionTokens)
                put("thoughtsTokenCount", reasoningTokens)
                put("totalTokenCount", promptTokens + completionTokens + reasoningTokens)
                if (cachedTokens > 0) put("cachedContentTokenCount", cachedTokens)
            }
        }.toString()
    }

    // =========================================================================
    // Claude → Gemini
    // =========================================================================

    fun claudeToGemini(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val contentArray = root["content"] as? kotlinx.serialization.json.JsonArray
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        val sawTool = contentArray?.any {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tool_use"
        } == true

        val usage = root["usage"]?.jsonObject
        val inputTokens = usage.longOf("input_tokens") ?: 0L
        val outputTokens = usage.longOf("output_tokens") ?: 0L
        val cacheCreation = usage.longOf("cache_creation_input_tokens") ?: 0L
        val cacheRead = usage.longOf("cache_read_input_tokens") ?: 0L

        return buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") {
                            contentArray?.forEach { elem ->
                                val block = elem as? JsonObject ?: return@forEach
                                when (block["type"]?.jsonPrimitive?.content) {
                                    "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                                        addJsonObject { put("text", text) }
                                    }
                                    "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.let { thinking ->
                                        addJsonObject {
                                            put("text", thinking)
                                            put("thought", true)
                                        }
                                    }
                                    "tool_use" -> addJsonObject {
                                        putJsonObject("functionCall") {
                                            block["id"]?.jsonPrimitive?.contentOrNull?.let { put("id", it) }
                                            put("name", block.nameOrEmpty())
                                            put("args", block["input"] ?: buildJsonObject { })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    put("finishReason", if (sawTool) "STOP" else openAiFinishToGemini(claudeStopToOpenAi(stopReason)))
                }
            }
            putJsonObject("usageMetadata") {
                put("promptTokenCount", inputTokens)
                put("candidatesTokenCount", outputTokens)
                put("totalTokenCount", inputTokens + outputTokens)
                // 对齐 CLIProxyAPI：cachedContentTokenCount=cache_creation+cache_read
                if (cacheCreation + cacheRead > 0) put("cachedContentTokenCount", cacheCreation + cacheRead)
            }
        }.toString()
    }

    // =========================================================================
    // Gemini → Claude
    // =========================================================================

    fun geminiToClaude(rawJson: String, model: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val candidate = (root["candidates"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray
        val finishRaw = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        var sawTool = false

        val usage = root["usageMetadata"]?.jsonObject
        val promptTokens = usage.longOf("promptTokenCount") ?: 0L
        val candidatesTokens = usage.longOf("candidatesTokenCount") ?: 0L
        val thoughtsTokens = usage.longOf("thoughtsTokenCount") ?: 0L
        val cachedTokens = usage.longOf("cachedContentTokenCount") ?: 0L

        return buildJsonObject {
            put("id", "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}")
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            putJsonArray("content") {
                parts?.forEach { elem ->
                    val part = elem as? JsonObject ?: return@forEach
                    val fn = part["functionCall"] as? JsonObject
                    if (fn != null) {
                        sawTool = true
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", fn.idOrNull() ?: sanitizeClaudeToolId("${fn.nameOrEmpty()}-${System.nanoTime()}"))
                            put("name", fn.nameOrEmpty())
                            put("input", fn["args"] ?: buildJsonObject { })
                        }
                        return@forEach
                    }
                    val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                        addJsonObject {
                            put("type", "thinking")
                            put("thinking", text)
                            part["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let { put("signature", it) }
                        }
                    } else {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }
            put("stop_reason", geminiFinishToClaude(finishRaw, sawTool))
            put("stop_sequence", kotlinx.serialization.json.JsonNull)
            putJsonObject("usage") {
                // 对齐 CLIProxyAPI：output=candidates+thoughts，input=prompt-cached（下限 0）
                put("input_tokens", (promptTokens - cachedTokens).coerceAtLeast(0L))
                put("output_tokens", candidatesTokens + thoughtsTokens)
                if (cachedTokens > 0) put("cache_read_input_tokens", cachedTokens)
            }
        }.toString()
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    /** Claude 工具名有 64 字符正则限制（对齐 CLIProxyAPI SanitizeClaudeToolID） */
    private fun sanitizeClaudeToolId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    private fun JsonObject.idOrRandom(): String =
        idOrNull() ?: "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"

    private fun JsonObject.idOrNull(): String? = this["id"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.nameOrEmpty(): String = this["name"]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject?.longOf(key: String): Long? =
        this?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }

    private fun JsonObject?.promptDetails(): JsonObject? =
        this?.get("prompt_tokens_details") as? JsonObject

    private fun JsonObject?.completionDetails(): JsonObject? =
        this?.get("completion_tokens_details") as? JsonObject
}
