package com.cpaphone.core.translator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * 跨协议流式 SSE 实时转译器（对齐 CLIProxyAPI internal/translator 六方向流式转换器）
 *
 * 每个连接创建一个转换器实例（有状态）：工具调用参数在流中累积、块切换状态跟踪、
 * 终止帧与 usage 在流结束时补发（flushTail）。协议一致时不需要转换器（零拷贝透传）。
 *
 * 统一约定：
 * - convert(dataPayload)：输入 `data:` 之后的 JSON 载荷（[DONE] 传 "[DONE]"），返回目标协议 SSE 帧字符串
 *   （可含多帧，每帧形如 "data: {...}\n\n"）；无需下发返回 null
 * - flushTail()：上游流结束时调用，补发累积的工具块/终止事件；无待发内容返回 null
 */
sealed class SseStreamConverter {
    abstract fun convert(dataPayload: String): String?
    abstract fun flushTail(): String?

    companion object {
        /** 按方向解析 SSE 单行为 `data:` 载荷（供调用方统一使用） */
        fun dataPayloadOf(sseLine: String): String? {
            val trimmed = sseLine.trim()
            if (!trimmed.startsWith("data:")) return null
            return trimmed.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
        }

        internal fun parse(payload: String): JsonObject? = try {
            jsonParser.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            null
        }

        internal fun JsonObject.intOf(key: String): Int? =
            (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

        internal fun JsonObject.longOf(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        internal fun JsonObject.strOf(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        internal fun JsonObject.nameOrEmpty(): String = strOf("name") ?: ""

        internal fun JsonObject.idOrRandom(): String =
            strOf("id") ?: "tc_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    // =========================================================================
    // 1. Claude SSE → OpenAI chunks（对齐 claude_openai_response.go:91-283）
    // =========================================================================

    class ClaudeToOpenAi(private val model: String) : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val chatcmplId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        private var started = false
        private var finishSent = false
        private var doneSent = false
        private val toolBlocks = HashMap<Int, ToolAcc>()
        private var lastUsage: JsonObject? = null

        private class ToolAcc(val id: String, val name: String) {
            var argsAcc = ""
        }

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") {
                return if (!doneSent) {
                    doneSent = true
                    "data: [DONE]\n\n"
                } else null
            }
            val root = parse(dataPayload) ?: return null
            val sb = StringBuilder()
            when (root["type"]?.jsonPrimitive?.content) {
                "message_start" -> {
                    lastUsage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
                    sb.append(openAiChunk(delta = buildJsonObject { put("role", "assistant") }, finish = null))
                    started = true
                }
                "content_block_start" -> {
                    val block = root["content_block"] as? JsonObject ?: return null
                    if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                        val index = root.intOf("index") ?: 0
                        toolBlocks[index] = ToolAcc(
                            id = block.idOrRandom(),
                            name = block.nameOrEmpty()
                        )
                    }
                }
                "content_block_delta" -> {
                    val delta = root["delta"] as? JsonObject ?: return null
                    when (delta["type"]?.jsonPrimitive?.content) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                            sb.append(openAiChunk(buildJsonObject { put("content", it) }, null))
                        }
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                            sb.append(openAiChunk(buildJsonObject { put("reasoning_content", it) }, null))
                        }
                        "input_json_delta" -> {
                            val index = root.intOf("index") ?: 0
                            toolBlocks[index]?.argsAcc += delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                    }
                }
                "content_block_stop" -> {
                    val index = root.intOf("index") ?: 0
                    toolBlocks.remove(index)?.let { acc ->
                        sb.append(
                            openAiChunk(
                                buildJsonObject {
                                    putJsonArray("tool_calls") {
                                        addJsonObject {
                                            put("index", index)
                                            put("id", acc.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", acc.name)
                                                put("arguments", acc.argsAcc.ifBlank { "{}" })
                                            }
                                        }
                                    }
                                },
                                finish = null
                            )
                        )
                    }
                }
                "message_delta" -> {
                    val delta = root["delta"] as? JsonObject
                    val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                    (root["usage"] as? JsonObject)?.let { lastUsage = it }
                    if (stopReason != null && !finishSent) {
                        finishSent = true
                        sb.append(openAiChunk(buildJsonObject { }, finish = mapStop(stopReason)))
                    }
                }
                // ping / 其他事件忽略
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? {
            // Claude 流一般以 message_stop 收尾；若中途断流则补 [DONE]
            return if (!doneSent && started) {
                doneSent = true
                "data: [DONE]\n\n"
            } else null
        }

        /** Claude stop_reason → OpenAI finish_reason */
        private fun mapStop(stop: String): String = when (stop) {
            "end_turn", "stop_sequence" -> "stop"
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            "refusal", "sensitive" -> "content_filter"
            else -> "stop"
        }

        private fun openAiChunk(delta: JsonObject, finish: String?): String {
            val chunk = buildJsonObject {
                put("id", chatcmplId)
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000)
                put("model", model)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        if (finish != null) put("finish_reason", finish) else put("finish_reason", kotlinx.serialization.json.JsonNull)
                    }
                }
                lastUsage?.let { usage ->
                    putJsonObject("usage") {
                        val input = usage.longOf("input_tokens") ?: 0L
                        val cacheCreation = usage.longOf("cache_creation_input_tokens") ?: 0L
                        val cacheRead = usage.longOf("cache_read_input_tokens") ?: 0L
                        val output = usage.longOf("output_tokens") ?: 0L
                        put("prompt_tokens", input + cacheCreation + cacheRead)
                        put("completion_tokens", output)
                        put("total_tokens", input + cacheCreation + cacheRead + output)
                    }
                }
            }
            return "data: $chunk\n\n"
        }
    }

    // =========================================================================
    // 2. Gemini SSE → OpenAI chunks（对齐 gemini_openai_response.go:50-274）
    // =========================================================================

    class GeminiToOpenAi(private val model: String) : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val chatcmplId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        private var toolCounter = 0
        private var sawToolCall = false
        private var sawPayload = false
        private var lastFinishReason: String? = null

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") return null
            val root = parse(dataPayload) ?: return null
            sawPayload = true
            val sb = StringBuilder()

            (root["candidates"] as? kotlinx.serialization.json.JsonArray)?.forEach { candElem ->
                val candidate = candElem as? JsonObject ?: return@forEach
                val choiceIndex = candidate.intOf("index") ?: 0
                val parts = (candidate["content"] as? JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray

                parts?.forEach { partElem ->
                    val part = partElem as? JsonObject ?: return@forEach
                    val fn = part["functionCall"] as? JsonObject
                    if (fn != null) {
                        sawToolCall = true
                        sb.append(
                            chunk(choiceIndex, buildJsonObject {
                                putJsonArray("tool_calls") {
                                    addJsonObject {
                                        put("index", toolCounter)
                                        put("id", "${fn.nameOrEmpty()}-${System.nanoTime() + toolCounter}")
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", fn.nameOrEmpty())
                                            put("arguments", fn["args"]?.toString() ?: "{}")
                                        }
                                    }
                                }
                                toolCounter++
                            }, finish = null, nativeFinish = null)
                        )
                        return@forEach
                    }
                    val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject
                    if (inline != null) {
                        sb.append(chunk(choiceIndex, buildJsonObject {
                            putJsonArray("images") {
                                addJsonObject {
                                    putJsonObject("image_url") {
                                        val mime = inline.strOf("mimeType") ?: inline.strOf("mime_type") ?: "image/png"
                                        put("url", "data:$mime;base64,${inline.strOf("data") ?: ""}")
                                    }
                                }
                            }
                        }, finish = null, nativeFinish = null))
                        return@forEach
                    }
                    val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                    sb.append(
                        chunk(choiceIndex, buildJsonObject {
                            if (isThought) put("reasoning_content", text) else put("content", text)
                        }, finish = null, nativeFinish = null)
                    )
                }

                candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishRaw ->
                    lastFinishReason = finishRaw
                    val usage = root["usageMetadata"] as? JsonObject
                    sb.append(
                        chunk(choiceIndex, buildJsonObject { }, finish = mapFinish(finishRaw, sawToolCall), nativeFinish = finishRaw, usage = usage)
                    )
                }
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? =
            // 对齐 CLIProxyAPI：见过 payload 但始终未见 finishReason 时在流结束合成终帧
            if (sawPayload && lastFinishReason == null) {
                chunk(0, buildJsonObject { }, finish = if (sawToolCall) "tool_calls" else "stop", nativeFinish = "stop", usage = null)
            } else null

        private fun mapFinish(finish: String, sawTool: Boolean): String = when {
            sawTool -> "tool_calls"
            finish == "MAX_TOKENS" -> "max_tokens"
            finish == "SAFETY" || finish == "PROHIBITED_CONTENT" -> "content_filter"
            else -> "stop"
        }

        private fun chunk(choiceIndex: Int, delta: JsonObject, finish: String?, nativeFinish: String?, usage: JsonObject? = null): String {
            val chunk = buildJsonObject {
                put("id", chatcmplId)
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000)
                put("model", "")
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", choiceIndex)
                        put("delta", delta)
                        if (finish != null) put("finish_reason", finish) else put("finish_reason", kotlinx.serialization.json.JsonNull)
                        nativeFinish?.let { put("native_finish_reason", it.lowercase()) }
                    }
                }
                usage?.let { u ->
                    putJsonObject("usage") {
                        val prompt = u.longOf("promptTokenCount") ?: 0L
                        val candidates = u.longOf("candidatesTokenCount") ?: 0L
                        val thoughts = u.longOf("thoughtsTokenCount") ?: 0L
                        val cached = u.longOf("cachedContentTokenCount") ?: 0L
                        put("prompt_tokens", prompt)
                        put("completion_tokens", candidates + thoughts)
                        put("total_tokens", u.longOf("totalTokenCount") ?: (prompt + candidates + thoughts))
                        if (thoughts > 0) {
                            putJsonObject("completion_tokens_details") { put("reasoning_tokens", thoughts) }
                        }
                        if (cached > 0) {
                            putJsonObject("prompt_tokens_details") { put("cached_tokens", cached) }
                        }
                    }
                }
            }
            return "data: $chunk\n\n"
        }
    }

    // =========================================================================
    // 3. OpenAI chunks → Claude SSE 事件（对齐 openai_claude_response.go:140-355）
    // =========================================================================

    class OpenAiToClaude(private val model: String) : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        private var started = false
        private var closed = false
        private var blockCounter = 0
        private var currentBlockType: String? = null  // text | thinking | tool_use
        private val toolAccs = LinkedHashMap<Int, ToolAcc>()  // openai tool index → acc
        private var finishReason: String? = null
        private var usageJson: JsonObject? = null

        private class ToolAcc(val openAiIndex: Int) {
            var id = ""
            var name = ""
            var argsAcc = ""
            var blockIndex = -1
        }

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") return flushTail()
            val root = parse(dataPayload) ?: return null
            val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                ?: return null
            val sb = StringBuilder()
            ensureStarted(sb)

            (choice["delta"] as? JsonObject)?.let { delta ->
                // thinking 块：reasoning_content
                delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { reasoning ->
                    if (currentBlockType != "thinking") {
                        sb.append(closeCurrentBlock())
                        sb.append(openBlock("thinking", buildJsonObject {
                            put("type", "thinking")
                            put("thinking", "")
                        }))
                    }
                    sb.append(claudeEvent("content_block_delta", buildJsonObject {
                        put("index", blockCounter)
                        putJsonObject("delta") {
                            put("type", "thinking_delta")
                            put("thinking", reasoning)
                        }
                    }))
                }
                // text 块：content
                delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (currentBlockType != "text") {
                        sb.append(closeCurrentBlock())
                        sb.append(openBlock("text", buildJsonObject {
                            put("type", "text")
                            put("text", "")
                        }))
                    }
                    sb.append(claudeEvent("content_block_delta", buildJsonObject {
                        put("index", blockCounter)
                        putJsonObject("delta") {
                            put("type", "text_delta")
                            put("text", text)
                        }
                    }))
                }
                // tool_calls 增量累积（id+name 齐备才开 tool_use 块，参数流结束统一下发）
                (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { tcElem ->
                    val tc = tcElem as? JsonObject ?: return@forEach
                    val openAiIdx = tc.intOf("index") ?: 0
                    val acc = toolAccs.getOrPut(openAiIdx) { ToolAcc(openAiIdx) }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { acc.argsAcc += it }
                    }
                    if (acc.blockIndex == -1 && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                        sb.append(closeCurrentBlock())
                        sb.append(openBlock("tool_use", buildJsonObject {
                            put("type", "tool_use")
                            put("id", acc.id)
                            put("name", acc.name)
                            put("input", buildJsonObject { })
                        }))
                        acc.blockIndex = blockCounter
                    }
                }
            }

            choice["finish_reason"]?.let {
                if (it != kotlinx.serialization.json.JsonNull) {
                    finishReason = (it as? JsonPrimitive)?.contentOrNull
                }
            }
            (root["usage"] as? JsonObject)?.let { usageJson = it }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? {
            if (!started || closed) return null
            closed = true
            val sb = StringBuilder()

            // 已开 tool_use 块：统一补发累积参数（FixJSON 语义：空补 "{}"）
            toolAccs.values.filter { it.blockIndex != -1 }.forEach { acc ->
                sb.append(claudeEvent("content_block_delta", buildJsonObject {
                    put("index", acc.blockIndex)
                    putJsonObject("delta") {
                        put("type", "input_json_delta")
                        put("partial_json", acc.argsAcc.ifBlank { "{}" })
                    }
                }))
            }
            // 关闭当前块
            if (currentBlockType != null) {
                sb.append(claudeEvent("content_block_stop", buildJsonObject { put("index", blockCounter) }))
            }
            val usage = usageJson
            sb.append(claudeEvent("message_delta", buildJsonObject {
                putJsonObject("delta") {
                    put("stop_reason", mapFinish(finishReason))
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                }
                usage?.let { u ->
                    putJsonObject("usage") {
                        val prompt = u.longOf("prompt_tokens") ?: 0L
                        val cached = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                        put("input_tokens", (prompt - cached).coerceAtLeast(0L))
                        put("output_tokens", u.longOf("completion_tokens") ?: 0L)
                        if (cached > 0) put("cache_read_input_tokens", cached)
                    }
                }
            }))
            sb.append(claudeEvent("message_stop", buildJsonObject { }))
            return sb.toString()
        }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            sb.append(claudeEvent("message_start", buildJsonObject {
                putJsonObject("message") {
                    put("id", messageId)
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    putJsonArray("content") { }
                    put("stop_reason", kotlinx.serialization.json.JsonNull)
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                    putJsonObject("usage") {
                        put("input_tokens", 0)
                        put("output_tokens", 0)
                    }
                }
            }))
        }

        /** 关闭当前块（若有） */
        private fun closeCurrentBlock(): String =
            if (currentBlockType != null) {
                claudeEvent("content_block_stop", buildJsonObject { put("index", blockCounter) })
            } else ""

        /** 递增 index 并开启新块 */
        private fun openBlock(type: String, contentBlock: JsonObject): String {
            blockCounter++
            currentBlockType = type
            return claudeEvent("content_block_start", buildJsonObject {
                put("index", blockCounter)
                put("content_block", contentBlock)
            })
        }

        private fun mapFinish(finish: String?): String = when (finish) {
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            "content_filter" -> "refusal"
            else -> "end_turn"
        }

        private fun claudeEvent(type: String, payload: JsonObject): String {
            val event = buildJsonObject {
                put("type", type)
                payload.forEach { (k, v) -> put(k, v) }
            }
            return "data: $event\n\n"
        }
    }

    // =========================================================================
    // 4. Gemini SSE → Claude SSE 事件（对齐 gemini_claude_response.go:54-284）
    // =========================================================================

    class GeminiToClaude(private val model: String) : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        private var started = false
        private var closed = false
        private var blockCounter = -1
        private var currentBlockType: String? = null
        private var toolCounter = 0
        private var lastFinishReason: String? = null
        private var usageJson: JsonObject? = null

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") return flushTail()
            val root = parse(dataPayload) ?: return null
            val candidate = (root["candidates"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                ?: return null
            val sb = StringBuilder()
            ensureStarted(sb)

            ((candidate["content"] as? JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray)?.forEach { partElem ->
                val part = partElem as? JsonObject ?: return@forEach
                val fn = part["functionCall"] as? JsonObject
                if (fn != null) {
                    // tool_use 块：start + input 整体作为 partial_json + stop（对齐 CLIProxyAPI :388-400）
                    sb.append(closeCurrentBlock())
                    sb.append(openBlock("tool_use", buildJsonObject {
                        put("type", "tool_use")
                        put("id", sanitizeClaudeToolId("${fn.nameOrEmpty()}-${toolCounter++}"))
                        put("name", fn.nameOrEmpty())
                        put("input", buildJsonObject { })
                    }))
                    sb.append(claudeEvent("content_block_delta", buildJsonObject {
                        put("index", blockCounter)
                        putJsonObject("delta") {
                            put("type", "input_json_delta")
                            put("partial_json", fn["args"]?.toString() ?: "{}")
                        }
                    }))
                    sb.append(closeCurrentBlock())
                    currentBlockType = null
                    return@forEach
                }
                val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                val targetType = if (isThought) "thinking" else "text"
                if (currentBlockType != targetType) {
                    sb.append(closeCurrentBlock())
                    sb.append(
                        openBlock(targetType, buildJsonObject {
                            put("type", targetType)
                            if (isThought) put("thinking", "") else put("text", "")
                        })
                    )
                }
                sb.append(claudeEvent("content_block_delta", buildJsonObject {
                    put("index", blockCounter)
                    putJsonObject("delta") {
                        if (isThought) {
                            put("type", "thinking_delta")
                            put("thinking", text)
                        } else {
                            put("type", "text_delta")
                            put("text", text)
                        }
                    }
                }))
                // thoughtSignature → signature_delta（对齐 CLIProxyAPI :79-86）
                part["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let { sig ->
                    sb.append(claudeEvent("content_block_delta", buildJsonObject {
                        put("index", blockCounter)
                        putJsonObject("delta") {
                            put("type", "signature_delta")
                            put("signature", sig)
                        }
                    }))
                }
            }

            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { lastFinishReason = it }
            (root["usageMetadata"] as? JsonObject)?.let { usageJson = it }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? {
            if (!started || closed) return null
            closed = true
            val sb = StringBuilder()
            if (currentBlockType != null) {
                sb.append(claudeEvent("content_block_stop", buildJsonObject { put("index", blockCounter) }))
            }
            sb.append(claudeEvent("message_delta", buildJsonObject {
                putJsonObject("delta") {
                    put("stop_reason", if (toolCounter > 0) "tool_use" else mapFinish(lastFinishReason))
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                }
                usageJson?.let { u ->
                    putJsonObject("usage") {
                        // 对齐 CLIProxyAPI：output=candidates+thoughts，input=prompt-cached
                        val prompt = u.longOf("promptTokenCount") ?: 0L
                        val cached = u.longOf("cachedContentTokenCount") ?: 0L
                        put("input_tokens", (prompt - cached).coerceAtLeast(0L))
                        put("output_tokens", (u.longOf("candidatesTokenCount") ?: 0L) + (u.longOf("thoughtsTokenCount") ?: 0L))
                        if (cached > 0) put("cache_read_input_tokens", cached)
                    }
                }
            }))
            sb.append(claudeEvent("message_stop", buildJsonObject { }))
            return sb.toString()
        }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            sb.append(claudeEvent("message_start", buildJsonObject {
                putJsonObject("message") {
                    put("id", messageId)
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    putJsonArray("content") { }
                    put("stop_reason", kotlinx.serialization.json.JsonNull)
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                    putJsonObject("usage") { put("input_tokens", 0); put("output_tokens", 0) }
                }
            }))
        }

        /** 关闭当前块（若有） */
        private fun closeCurrentBlock(): String =
            if (currentBlockType != null) {
                claudeEvent("content_block_stop", buildJsonObject { put("index", blockCounter) })
            } else ""

        /** 递增 index 并开启新块 */
        private fun openBlock(type: String, contentBlock: JsonObject): String {
            blockCounter++
            currentBlockType = type
            return claudeEvent("content_block_start", buildJsonObject {
                put("index", blockCounter)
                put("content_block", contentBlock)
            })
        }

        private fun mapFinish(finish: String?): String = when (finish) {
            "MAX_TOKENS" -> "max_tokens"
            "SAFETY" -> "refusal"
            else -> "end_turn"
        }

        private fun sanitizeClaudeToolId(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

        private fun claudeEvent(type: String, payload: JsonObject): String {
            val event = buildJsonObject {
                put("type", type)
                payload.forEach { (k, v) -> put(k, v) }
            }
            return "data: $event\n\n"
        }
    }

    // =========================================================================
    // 5. Claude SSE → Gemini 帧（对齐 claude_gemini_response.go:94-294）
    // =========================================================================

    class ClaudeToGemini : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private var closed = false
        private val toolBlocks = HashMap<Int, ToolAcc>()
        private var finishSent = false

        private class ToolAcc(val id: String?, val name: String) {
            var argsAcc = ""
        }

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") return null
            val root = parse(dataPayload) ?: return null
            val sb = StringBuilder()
            when (root["type"]?.jsonPrimitive?.content) {
                "content_block_delta" -> {
                    val delta = root["delta"] as? JsonObject ?: return null
                    when (delta["type"]?.jsonPrimitive?.content) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                            sb.append(geminiFrame(buildJsonArray { addJsonObject { put("text", it) } }, null, null))
                        }
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                            sb.append(geminiFrame(buildJsonArray {
                                addJsonObject { put("text", it); put("thought", true) }
                            }, null, null))
                        }
                        "input_json_delta" -> {
                            val index = root.intOf("index") ?: 0
                            toolBlocks[index]?.argsAcc += delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                    }
                }
                "content_block_start" -> {
                    val block = root["content_block"] as? JsonObject ?: return null
                    if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                        val index = root.intOf("index") ?: 0
                        toolBlocks[index] = ToolAcc(block.strOf("id"), block.nameOrEmpty())
                    }
                }
                "content_block_stop" -> {
                    val index = root.intOf("index") ?: 0
                    toolBlocks.remove(index)?.let { acc ->
                        sb.append(geminiFrame(buildJsonArray {
                            addJsonObject {
                                putJsonObject("functionCall") {
                                    acc.id?.let { put("id", it) }
                                    put("name", acc.name)
                                    put("args", ProtocolTranslatorEngine.parseJsonLenient(acc.argsAcc.ifBlank { "{}" }))
                                }
                            }
                        }, null, null))
                    }
                }
                "message_delta" -> {
                    val stopReason = (root["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                    val usage = root["usage"] as? JsonObject
                    if (!finishSent) {
                        finishSent = true
                        sb.append(geminiFrame(emptyList(), mapFinish(stopReason), usage))
                    }
                }
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? =
            if (!finishSent) {
                finishSent = true
                geminiFrame(emptyList(), "STOP", null)
            } else null

        private fun mapFinish(stop: String?): String = when (stop) {
            "max_tokens" -> "MAX_TOKENS"
            "refusal", "sensitive" -> "SAFETY"
            else -> "STOP"
        }

        private fun geminiFrame(parts: List<kotlinx.serialization.json.JsonElement>, finishReason: String?, usage: JsonObject? = null): String {
            val frame = buildJsonObject {
                putJsonArray("candidates") {
                    addJsonObject {
                        if (parts.isNotEmpty()) {
                            putJsonObject("content") {
                                put("role", "model")
                                putJsonArray("parts") { parts.forEach { add(it) } }
                            }
                        }
                        finishReason?.let { put("finishReason", it) }
                    }
                }
                usage?.let { u ->
                    putJsonObject("usageMetadata") {
                        val input = u.longOf("input_tokens") ?: 0L
                        val output = u.longOf("output_tokens") ?: 0L
                        val cacheCreation = u.longOf("cache_creation_input_tokens") ?: 0L
                        val cacheRead = u.longOf("cache_read_input_tokens") ?: 0L
                        put("promptTokenCount", input)
                        put("candidatesTokenCount", output)
                        put("totalTokenCount", input + output)
                        if (cacheCreation + cacheRead > 0) put("cachedContentTokenCount", cacheCreation + cacheRead)
                    }
                }
            }
            return "data: $frame\n\n"
        }
    }

    // =========================================================================
    // 6. OpenAI chunks → Gemini 帧（对齐 openai_gemini_response.go:49-243）
    // =========================================================================

    class OpenAiToGemini : SseStreamConverter() {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private var closed = false
        private val toolAccs = LinkedHashMap<Int, ToolAcc>()
        private var finishReason: String? = null
        private var usageJson: JsonObject? = null

        private class ToolAcc {
            var id = ""
            var name = ""
            var argsAcc = ""
        }

        override fun convert(dataPayload: String): String? {
            if (dataPayload == "[DONE]") return flushTail()
            val root = parse(dataPayload) ?: return null
            val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                ?: return null
            (root["usage"] as? JsonObject)?.let { usageJson = it }
            val sb = StringBuilder()

            (choice["delta"] as? JsonObject)?.let { delta ->
                delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { reasoning ->
                    sb.append(geminiFrame(buildJsonArray {
                        addJsonObject { put("text", reasoning); put("thought", true) }
                    }, null))
                }
                delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
                    sb.append(geminiFrame(buildJsonArray { addJsonObject { put("text", text) } }, null))
                }
                (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { tcElem ->
                    val tc = tcElem as? JsonObject ?: return@forEach
                    val idx = tc.intOf("index") ?: 0
                    val acc = toolAccs.getOrPut(idx) { ToolAcc() }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { acc.argsAcc += it }
                    }
                }
            }
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishReason = it }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        override fun flushTail(): String? {
            if (closed) return null
            closed = true
            val sb = StringBuilder()
            // 工具调用在流结束时组装 functionCall（args 字符串 → 对象，对齐 CLIProxyAPI :219）
            toolAccs.values.forEach { acc ->
                sb.append(geminiFrame(buildJsonArray {
                    addJsonObject {
                        putJsonObject("functionCall") {
                            if (acc.id.isNotBlank()) put("id", acc.id)
                            put("name", acc.name)
                            put("args", ProtocolTranslatorEngine.parseJsonLenient(acc.argsAcc.ifBlank { "{}" }))
                        }
                    }
                }, null))
            }
            sb.append(
                geminiFrame(emptyList(), when (finishReason) {
                    "length" -> "MAX_TOKENS"
                    "content_filter" -> "SAFETY"
                    "tool_calls" -> "STOP"
                    else -> "STOP"
                }, usageJson)
            )
            return sb.toString()
        }

        private fun geminiFrame(parts: List<kotlinx.serialization.json.JsonElement>, finishReason: String?, usage: JsonObject? = null): String {
            val frame = buildJsonObject {
                putJsonArray("candidates") {
                    addJsonObject {
                        if (parts.isNotEmpty()) {
                            putJsonObject("content") {
                                put("role", "model")
                                putJsonArray("parts") { parts.forEach { add(it) } }
                            }
                        }
                        finishReason?.let { put("finishReason", it) }
                    }
                }
                usage?.let { u ->
                    putJsonObject("usageMetadata") {
                        val prompt = u.longOf("prompt_tokens") ?: 0L
                        val completion = u.longOf("completion_tokens") ?: 0L
                        val reasoning = ((u["completion_tokens_details"] as? JsonObject)?.get("reasoning_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                        val cached = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                        put("promptTokenCount", prompt)
                        put("candidatesTokenCount", completion)
                        put("thoughtsTokenCount", reasoning)
                        put("totalTokenCount", prompt + completion + reasoning)
                        if (cached > 0) put("cachedContentTokenCount", cached)
                    }
                }
            }
            return "data: $frame\n\n"
        }
    }

    // =========================================================================
    // 内部工具（见 companion object）
    // =========================================================================
}
