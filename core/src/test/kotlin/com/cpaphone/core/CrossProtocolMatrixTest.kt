package com.cpaphone.core

import com.cpaphone.core.translator.CrossProtocolResponseTranslator
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.SseStreamConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 跨协议全矩阵转译测试（对齐 CLIProxyAPI translator 六方向映射表）
 * 覆盖：Gemini 入站解析、Gemini 生成器工具链、非流式 6 方向、有状态流式转换器
 */
class CrossProtocolMatrixTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // =========================================================================
    // 1. Gemini 入站解析
    // =========================================================================

    @Test
    fun `Gemini 入站解析 contents 工具与思考`() {
        val raw = """
            {
              "contents": [
                {"role": "user", "parts": [{"text": "查天气"}]},
                {"role": "model", "parts": [{"functionCall": {"name": "get_weather", "args": {"city": "北京"}, "id": "c1"}}]},
                {"role": "user", "parts": [{"functionResponse": {"id": "c1", "name": "get_weather", "response": {"result": "晴 25 度"}}}]},
                {"role": "user", "parts": [{"text": "谢谢"}, {"inlineData": {"mimeType": "image/png", "data": "aGk="}}]}
              ],
              "systemInstruction": {"parts": [{"text": "你是助手"}]},
              "generationConfig": {"temperature": 0.5, "topP": 0.9, "maxOutputTokens": 2048,
                                   "thinkingConfig": {"thinkingBudget": 1024}},
              "tools": [{"functionDeclarations": [{"name": "get_weather", "description": "查天气",
                        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}]}]
            }
        """.trimIndent()

        val unified = ProtocolTranslatorEngine.parseGeminiRequest(raw, "gemini-2.5-flash")

        assertEquals("gemini-2.5-flash", unified.model)
        assertEquals("你是助手", unified.systemPrompt)
        assertEquals(4, unified.messages.size)
        assertEquals(1024, unified.thinkingBudgetTokens)
        assertEquals(1, unified.tools.size)
        assertEquals("get_weather", unified.tools.first().name)

        // model 角色 → ASSISTANT，functionCall → ToolCall（args 对象转字符串）
        val assistant = unified.messages[1]
        assertEquals(com.cpaphone.core.translator.UnifiedRole.ASSISTANT, assistant.role)
        val toolCall = assistant.parts.filterIsInstance<com.cpaphone.core.translator.UnifiedContentPart.ToolCall>().firstOrNull()
            ?: fail("functionCall 应解析为 ToolCall")
        assertEquals("get_weather", toolCall.name)
        assertTrue(toolCall.argumentsJson.contains("北京"))

        // functionResponse → ToolResult
        val toolResult = unified.messages[2].parts.filterIsInstance<com.cpaphone.core.translator.UnifiedContentPart.ToolResult>().firstOrNull()
            ?: fail("functionResponse 应解析为 ToolResult")
        assertEquals("c1", toolResult.toolCallId)
        assertTrue(toolResult.content.contains("晴"))

        // inlineData → Image
        val image = unified.messages[3].parts.filterIsInstance<com.cpaphone.core.translator.UnifiedContentPart.Image>().firstOrNull()
            ?: fail("inlineData 应解析为 Image")
        assertEquals("image/png", image.mimeType)
        assertEquals("aGk=", image.base64Data)
    }

    @Test
    fun `Gemini 生成器输出完整工具链与 thinkingConfig`() {
        val unified = ProtocolTranslatorEngine.parseGeminiRequest(
            """
            {
              "contents": [
                {"role": "user", "parts": [{"text": "查天气"}]},
                {"role": "model", "parts": [{"functionCall": {"name": "get_weather", "args": {"city": "北京"}, "id": "c1"}}]},
                {"role": "user", "parts": [{"functionResponse": {"id": "c1", "name": "get_weather", "response": {"result": "晴"}}}]}
              ],
              "generationConfig": {"thinkingConfig": {"thinkingBudget": 512}},
              "tools": [{"functionDeclarations": [{"name": "get_weather", "parameters": {"type": "object"}}]}]
            }
            """.trimIndent(),
            "gemini-2.5-flash"
        )
        val out = json.parseToJsonElement(ProtocolTranslatorEngine.toGeminiGenerateContentJson(unified)).jsonObject

        // tools + toolConfig
        assertEquals("get_weather", out["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0]
            .jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("AUTO", out["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(512, out["generationConfig"]!!.jsonObject["thinkingConfig"]!!
            .jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())

        // functionCall part 保留 + 签名占位；functionResponse name 反查
        val contents = out["contents"]!!.jsonArray
        val callPart = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", callPart["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("skip_thought_signature_validator", callPart["thoughtSignature"]!!.jsonPrimitive.content)
        val respPart = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_weather", respPart["name"]!!.jsonPrimitive.content)
        assertEquals("晴", respPart["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // 2. 非流式 6 方向
    // =========================================================================

    private val claudeResponse = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
         "content":[{"type":"thinking","thinking":"先想想"},{"type":"text","text":"今天晴"},
                    {"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"北京"}}],
         "stop_reason":"tool_use",
         "usage":{"input_tokens":10,"output_tokens":5,"cache_creation_input_tokens":3,"cache_read_input_tokens":2}}
    """.trimIndent()

    private val geminiResponse = """
        {"candidates":[{"content":{"role":"model","parts":[
            {"text":"想想","thought":true}, {"text":"今天晴"},
            {"functionCall":{"name":"get_weather","args":{"city":"北京"},"id":"fc1"}}]},
            "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":6,"thoughtsTokenCount":2,
                          "totalTokenCount":18,"cachedContentTokenCount":4}}
    """.trimIndent()

    private val openAiResponse = """
        {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-x",
         "choices":[{"index":0,"message":{"role":"assistant","content":"今天晴","reasoning_content":"想想",
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}]},
            "finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18,
                  "prompt_tokens_details":{"cached_tokens":4},"completion_tokens_details":{"reasoning_tokens":2}}}
    """.trimIndent()

    @Test
    fun `非流式 Claude 转 OpenAI`() {
        val out = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(claudeResponse, CrossProtocolResponseTranslator.Protocol.CLAUDE, CrossProtocolResponseTranslator.Protocol.OPENAI, "m")
        ).jsonObject
        val message = out["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("今天晴", message["content"]!!.jsonPrimitive.content)
        assertEquals("先想想", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("tool_calls", out["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
        val tc = message["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("tu_1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("北京", json.parseToJsonElement(tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            .jsonObject["city"]!!.jsonPrimitive.content)
        // usage：prompt=input+cache_creation+cache_read=15
        assertEquals(15, out["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `非流式 Gemini 转 OpenAI`() {
        val out = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(geminiResponse, CrossProtocolResponseTranslator.Protocol.GEMINI, CrossProtocolResponseTranslator.Protocol.OPENAI, "m")
        ).jsonObject
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        assertEquals("今天晴", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("想想", choice["message"]!!.jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("fc1", choice["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        // usage：reasoning_tokens=thoughtsTokenCount
        assertEquals(2, out["usage"]!!.jsonObject["completion_tokens_details"]!!
            .jsonObject["reasoning_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `非流式 OpenAI 转 Claude`() {
        val out = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(openAiResponse, CrossProtocolResponseTranslator.Protocol.OPENAI, CrossProtocolResponseTranslator.Protocol.CLAUDE, "m")
        ).jsonObject
        assertEquals("message", out["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", out["stop_reason"]!!.jsonPrimitive.content)
        val content = out["content"]!!.jsonArray
        assertEquals("thinking", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val toolUse = content[2].jsonObject
        assertEquals("get_weather", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals("北京", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        // usage：input=prompt-cached
        assertEquals(6, out["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `非流式 OpenAI 转 Gemini`() {
        val out = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(openAiResponse, CrossProtocolResponseTranslator.Protocol.OPENAI, CrossProtocolResponseTranslator.Protocol.GEMINI, "m")
        ).jsonObject
        val parts = out["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(true, parts[0].jsonObject["thought"]!!.jsonPrimitive.content == "true" || parts[0].jsonObject["thought"] != null)
        assertEquals("今天晴", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("get_weather", parts[2].jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("北京", parts[2].jsonObject["functionCall"]!!.jsonObject["args"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(2, out["usageMetadata"]!!.jsonObject["thoughtsTokenCount"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `非流式 Claude 转 Gemini 与 Gemini 转 Claude`() {
        val toGemini = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(claudeResponse, CrossProtocolResponseTranslator.Protocol.CLAUDE, CrossProtocolResponseTranslator.Protocol.GEMINI, "m")
        ).jsonObject
        val parts = toGemini["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(true, parts[0].jsonObject["thought"] != null)
        assertEquals("get_weather", parts[2].jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(5, toGemini["usageMetadata"]!!.jsonObject["candidatesTokenCount"]!!.jsonPrimitive.content.toLong())

        val toClaude = json.parseToJsonElement(
            CrossProtocolResponseTranslator.translate(geminiResponse, CrossProtocolResponseTranslator.Protocol.GEMINI, CrossProtocolResponseTranslator.Protocol.CLAUDE, "m")
        ).jsonObject
        val content = toClaude["content"]!!.jsonArray
        assertEquals("thinking", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", toClaude["stop_reason"]!!.jsonPrimitive.content)
        // output=candidates+thoughts=8
        assertEquals(8, toClaude["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.content.toLong())
    }

    // =========================================================================
    // 3. 有状态流式转换器
    // =========================================================================

    @Test
    fun `流式 Claude 转 OpenAI 工具块累积后整块下发`() {
        val converter = SseStreamConverter.ClaudeToOpenAi("m")
        val frames = StringBuilder()
        listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"今天"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"get_weather","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"北京\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
            """{"type":"message_stop"}"""
        ).forEach { converter.convert(it)?.let { f -> frames.append(f) } }
        converter.flushTail()?.let { frames.append(it) }

        val all = frames.toString()
        println(">>> ClaudeToOpenAi 实际输出:\n$all")
        val dataFrames = all.split("\n\n").filter { it.startsWith("data: ") }
        // 首 chunk + text + tool_calls 完整块 + finish + [DONE]
        assertTrue(all.contains("\"role\":\"assistant\""), "应有首帧 role")
        assertTrue(all.contains("今天"), "应有文本帧")
        val toolFrame = dataFrames.first { it.contains("tool_calls") }
        println(">>> tool_calls 帧: $toolFrame")
        assertEquals(
            "{\"city\":\"北京\"}",
            json.parseToJsonElement(toolFrame.removePrefix("data: ")).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["delta"]!!.jsonObject["tool_calls"]!!.jsonArray[0]
                .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
            "参数分段累积后应拼为完整 JSON 字符串"
        )
        assertTrue(all.contains("\"finish_reason\":\"tool_calls\""), "stop_reason tool_use 应映射 tool_calls")
        assertTrue(all.endsWith("data: [DONE]\n\n"), "message_stop 应收尾 [DONE]")
    }

    @Test
    fun `流式 OpenAI 转 Claude 块状态机与 tool_use 补发`() {
        val converter = SseStreamConverter.OpenAiToClaude("m")
        val frames = StringBuilder()

        // 用构建器构造测试帧，杜绝手写转义错误
        fun chunk(delta: JsonObject, finish: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull, usage: JsonObject? = null): String =
            kotlinx.serialization.json.buildJsonObject {
                put("choices", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        put("finish_reason", finish)
                    })
                })
                usage?.let { put("usage", it) }
            }.toString()

        val framesInput = listOf(
            chunk(kotlinx.serialization.json.buildJsonObject {
                put("role", "assistant"); put("content", "今天")
            }),
            chunk(kotlinx.serialization.json.buildJsonObject {
                put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("index", 0); put("id", "call_1"); put("type", "function")
                        put("function", kotlinx.serialization.json.buildJsonObject {
                            put("name", "get_weather"); put("arguments", "{\"ci")
                        })
                    })
                })
            }),
            chunk(kotlinx.serialization.json.buildJsonObject {
                put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("index", 0)
                        put("function", kotlinx.serialization.json.buildJsonObject { put("arguments", "ty\":\"北京\"}") })
                    })
                })
            }),
            chunk(kotlinx.serialization.json.buildJsonObject { },
                finish = kotlinx.serialization.json.JsonPrimitive("tool_calls"),
                usage = kotlinx.serialization.json.buildJsonObject {
                    put("prompt_tokens", 10); put("completion_tokens", 9)
                })
        )
        framesInput.forEach {
            val r = converter.convert(it)
            r?.let { f -> frames.append(f) }
        }
        // [DONE] 触发收尾（flushTail 语义），返回值即收尾事件
        converter.convert("[DONE]")?.let { frames.append(it) }
        converter.flushTail()?.let { frames.append(it) }

        val all = frames.toString()
        assertTrue(all.contains("\"type\":\"message_start\""), "应有 message_start")
        assertTrue(all.contains("\"type\":\"text_delta\""), "应有 text_delta")
        assertTrue(all.contains("\"type\":\"tool_use\""), "应有 tool_use 块")
        // input_json_delta 补发完整参数
        val inputDelta = all.split("\n\n").first { it.contains("input_json_delta") }
        assertEquals(
            "{\"city\":\"北京\"}",
            json.parseToJsonElement(inputDelta.removePrefix("data: ")).jsonObject["delta"]!!
                .jsonObject["partial_json"]!!.jsonPrimitive.content,
            "分段参数应在流结束补发为完整 JSON"
        )
        assertTrue(all.contains("\"stop_reason\":\"tool_use\""), "finish_reason tool_calls 应映射 tool_use")
        assertTrue(all.contains("\"type\":\"message_stop\""), "应以 message_stop 收尾")
    }

    @Test
    fun `流式 Gemini 转 OpenAI functionCall 即时下发`() {
        val converter = SseStreamConverter.GeminiToOpenAi("m")
        val frame1 = converter.convert(
            """{"candidates":[{"content":{"parts":[{"text":"今天晴"}]}}],"usageMetadata":{"promptTokenCount":5}}"""
        ) ?: fail("应产出文本帧")
        val frame2 = converter.convert(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"北京"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"thoughtsTokenCount":1,"totalTokenCount":9}}"""
        ) ?: fail("应产出工具帧")
        val tail = converter.flushTail()

        assertTrue(frame1.contains("今天晴"))
        val toolChunk = json.parseToJsonElement(frame2.split("\n\n").first { it.contains("tool_calls") }.removePrefix("data: ")).jsonObject
        assertEquals("get_weather", toolChunk["choices"]!!.jsonArray[0].jsonObject["delta"]!!
            .jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // functionCall 帧同帧携带 finishReason → tool 优先映射 tool_calls（对齐 CLIProxyAPI）
        assertTrue(frame2.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(tail == null || !tail.contains("finish_reason"), "已有 finishReason 时 flushTail 不应重复终帧")
    }

    @Test
    fun `流式 OpenAI 转 Gemini 与 Claude 转 Gemini`() {
        val o2g = SseStreamConverter.OpenAiToGemini()
        val out = StringBuilder()
        listOf(
            """{"choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
            "[DONE]"
        ).forEach { out.append(o2g.convert(it)) }
        o2g.flushTail()?.let { out.append(it) }
        val geminiFrames = out.toString()
        assertTrue(geminiFrames.contains("你好"))
        assertTrue(geminiFrames.contains("\"functionCall\""), "tool_calls 应组装为 functionCall")
        assertTrue(geminiFrames.contains("\"finishReason\":\"STOP\""))

        val c2g = SseStreamConverter.ClaudeToGemini()
        val out2 = StringBuilder()
        listOf(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想想"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"f","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"a\":1}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""
        ).forEach { out2.append(c2g.convert(it)) }
        c2g.flushTail()?.let { out2.append(it) }
        assertTrue(out2.contains("\"thought\":true"), "thinking_delta 应映射 thought part")
        assertTrue(out2.contains("\"functionCall\""), "tool_use 应组装 functionCall")
        assertTrue(out2.contains("\"finishReason\":\"MAX_TOKENS\""), "max_tokens 应映射 MAX_TOKENS")
    }

    @Test
    fun `流式 Gemini 转 Claude 状态机`() {
        val converter = SseStreamConverter.GeminiToClaude("m")
        val out = StringBuilder()
        listOf(
            """{"candidates":[{"content":{"parts":[{"text":"想想","thought":true}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"今天晴"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"北京"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":6,"thoughtsTokenCount":2}}"""
        ).forEach { out.append(converter.convert(it)) }
        converter.flushTail()?.let { out.append(it) }

        val all = out.toString()
        assertTrue(all.contains("\"type\":\"message_start\""))
        assertTrue(all.contains("\"type\":\"thinking_delta\""))
        assertTrue(all.contains("\"type\":\"text_delta\""))
        assertTrue(all.contains("\"type\":\"tool_use\""))
        assertTrue(all.contains("\"stop_reason\":\"tool_use\""))
        // output=candidates+thoughts=8
        assertTrue(all.contains("\"output_tokens\":8"))
    }
}
