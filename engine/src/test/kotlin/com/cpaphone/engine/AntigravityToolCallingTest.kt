package com.cpaphone.engine

import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.UnifiedChatRequest
import com.cpaphone.core.translator.UnifiedContentPart
import com.cpaphone.core.translator.UnifiedMessage
import com.cpaphone.core.translator.UnifiedRole
import com.cpaphone.engine.client.AntigravityClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Antigravity 云码函数调用（function calling）全链路协议测试
 * 对齐 CLIProxyAPI antigravity 协议事实：
 * - 请求端 tools → request.tools[].functionDeclarations + toolConfig(AUTO)
 * - assistant tool_calls → functionCall part + thoughtSignature=skip_thought_signature_validator
 * - 工具结果 → functionResponse part 且 role=model（Antigravity 特殊约定）
 * - 响应 functionCall part → OpenAI tool_calls，finish_reason=tool_calls（丢弃即 0 字符 bug）
 */
class AntigravityToolCallingTest {

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // 1. 入站解析：OpenAI tools 定义必须进入统一模型
    // =========================================================================

    @Test
    fun `OpenAI 入站解析保留 tools 定义`() {
        val raw = """
            {
              "model": "gemini-3.8-flash-high",
              "messages": [{"role": "user", "content": "查找硅谷最近的科技新闻"}],
              "stream": true,
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "web_search",
                    "description": "搜索互联网新闻",
                    "parameters": {"type": "object", "properties": {"query": {"type": "string"}}}
                  }
                }
              ]
            }
        """.trimIndent()

        val unified = ProtocolTranslatorEngine.parseOpenAiChatRequest(raw)

        assertEquals(1, unified.tools.size)
        val tool = unified.tools.first()
        assertEquals("web_search", tool.name)
        assertEquals("搜索互联网新闻", tool.description)
        assertEquals("string", tool.parametersSchemaJson.jsonObject["properties"]!!
            .jsonObject["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(unified.isStreaming)
    }

    @Test
    fun `Claude 入站解析保留 tools 定义`() {
        val raw = """
            {
              "model": "gemini-3.8-flash-high",
              "max_tokens": 1024,
              "messages": [{"role": "user", "content": "hi"}],
              "tools": [
                {"name": "get_weather", "description": "查天气",
                 "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}
              ]
            }
        """.trimIndent()

        val unified = ProtocolTranslatorEngine.parseClaudeMessagesRequest(raw)

        assertEquals(1, unified.tools.size)
        assertEquals("get_weather", unified.tools.first().name)
        assertEquals("object", unified.tools.first().parametersSchemaJson.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenAI 入站解析多轮工具调用消息`() {
        val raw = """
            {
              "model": "gemini-3.8-flash-high",
              "messages": [
                {"role": "user", "content": "查新闻"},
                {"role": "assistant", "tool_calls": [
                  {"id": "call_1", "type": "function",
                   "function": {"name": "web_search", "arguments": "{\"query\":\"硅谷新闻\"}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_1", "content": "今日科技头条：AI 芯片创新"},
                {"role": "assistant", "content": "今日头条是 AI 芯片创新"},
                {"role": "user", "content": "谢谢"}
              ]
            }
        """.trimIndent()

        val unified = ProtocolTranslatorEngine.parseOpenAiChatRequest(raw)

        assertEquals(5, unified.messages.size)
        val toolCallMsg = unified.messages[1]
        val toolCall = toolCallMsg.parts.filterIsInstance<UnifiedContentPart.ToolCall>().firstOrNull()
            ?: fail("assistant 消息应包含 ToolCall part")
        assertEquals("call_1", toolCall.id)
        assertEquals("web_search", toolCall.name)
        assertEquals("{\"query\":\"硅谷新闻\"}", toolCall.argumentsJson)

        val toolResultMsg = unified.messages[2]
        assertEquals(UnifiedRole.TOOL, toolResultMsg.role)
        val toolResult = toolResultMsg.parts.filterIsInstance<UnifiedContentPart.ToolResult>().firstOrNull()
            ?: fail("tool 消息应包含 ToolResult part")
        assertEquals("call_1", toolResult.toolCallId)
        assertEquals("今日科技头条：AI 芯片创新", toolResult.content)
    }

    // =========================================================================
    // 2. 出站信封：tools 上传云码协议
    // =========================================================================

    @Test
    fun `信封携带 functionDeclarations 与 toolConfig`() {
        val unified = buildUnifiedWithTools()
        val envelope = AntigravityClient().buildRequestEnvelope("proj-1", "gemini-3.8-flash-high", unified)

        val request = envelope["request"]!!.jsonObject
        val declarations = request["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, declarations.size)
        val decl = declarations[0].jsonObject
        assertEquals("web_search", decl["name"]!!.jsonPrimitive.content)
        assertEquals("搜索互联网新闻", decl["description"]!!.jsonPrimitive.content)
        assertNotNull(decl["parameters"]!!.jsonObject["properties"])

        val mode = request["toolConfig"]!!.jsonObject["functionCallingConfig"]!!
            .jsonObject["mode"]!!.jsonPrimitive.content
        assertEquals("AUTO", mode)
    }

    @Test
    fun `无工具时信封不含 tools 节点`() {
        val unified = UnifiedChatRequest(
            model = "gemini-3.8-flash-high",
            messages = listOf(UnifiedMessage(UnifiedRole.USER, listOf(UnifiedContentPart.Text("你好"))))
        )
        val envelope = AntigravityClient().buildRequestEnvelope("proj-1", "gemini-3.8-flash-high", unified)
        assertEquals(null, envelope["request"]!!.jsonObject["tools"])
        assertEquals(null, envelope["request"]!!.jsonObject["toolConfig"])
    }

    // =========================================================================
    // 3. 出站 contents：多轮函数调用历史
    // =========================================================================

    @Test
    fun `assistant tool_calls 转 functionCall 并携带签名`() {
        val unified = buildUnifiedWithTools().copy(
            messages = listOf(
                UnifiedMessage(UnifiedRole.USER, listOf(UnifiedContentPart.Text("查新闻"))),
                UnifiedMessage(
                    UnifiedRole.ASSISTANT,
                    listOf(
                        UnifiedContentPart.ToolCall(id = "call_1", name = "web_search", argumentsJson = "{\"query\":\"硅谷新闻\"}")
                    )
                )
            )
        )
        val contents = AntigravityClient().buildContents(unified)

        assertEquals(2, contents.size)
        val assistantContent = contents[1].jsonObject
        assertEquals("model", assistantContent["role"]!!.jsonPrimitive.content)
        val part = assistantContent["parts"]!!.jsonArray[0].jsonObject
        val functionCall = part["functionCall"]!!.jsonObject
        assertEquals("call_1", functionCall["id"]!!.jsonPrimitive.content)
        assertEquals("web_search", functionCall["name"]!!.jsonPrimitive.content)
        assertEquals("硅谷新闻", functionCall["args"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals(
            "skip_thought_signature_validator",
            part["thoughtSignature"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `工具结果转 functionResponse 且 role 固定为 model`() {
        val unified = buildUnifiedWithTools().copy(
            messages = listOf(
                UnifiedMessage(UnifiedRole.USER, listOf(UnifiedContentPart.Text("查新闻"))),
                UnifiedMessage(
                    UnifiedRole.ASSISTANT,
                    listOf(UnifiedContentPart.ToolCall(id = "call_9", name = "web_search", argumentsJson = "{}"))
                ),
                UnifiedMessage(
                    UnifiedRole.TOOL,
                    listOf(UnifiedContentPart.ToolResult(toolCallId = "call_9", content = "科技头条"))
                )
            )
        )
        val contents = AntigravityClient().buildContents(unified)

        // 用户消息 + functionCall content + functionResponse content
        assertEquals(3, contents.size)
        val responseContent = contents[2].jsonObject
        assertEquals("model", responseContent["role"]!!.jsonPrimitive.content)
        val fr = responseContent["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("call_9", fr["id"]!!.jsonPrimitive.content)
        assertEquals("web_search", fr["name"]!!.jsonPrimitive.content)
        // result 必须保持字符串（解析为 JSON 对象会导致上游 400）
        assertEquals("科技头条", fr["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // 4. 入站响应：functionCall part 转译
    // =========================================================================

    @Test
    fun `响应 functionCall part 转为 tool_calls 且 finish_reason 为 tool_calls`() {
        val upstream = """
            {
              "response": {
                "candidates": [{
                  "content": {"role": "model", "parts": [
                    {"functionCall": {"name": "web_search", "args": {"query": "硅谷新闻"}, "id": "call_524881"}}
                  ]},
                  "finishReason": "STOP"
                }],
                "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 8, "totalTokenCount": 20}
              }
            }
        """.trimIndent()

        val inference = AntigravityClient().parseInference(upstream)

        assertEquals(1, inference.toolCalls.size)
        val tc = inference.toolCalls.first()
        assertEquals("call_524881", tc.id)
        assertEquals("web_search", tc.name)
        assertEquals("硅谷新闻", json.parseToJsonElement(tc.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals("", inference.contentText)

        // 非流式输出：message.tool_calls + finish_reason=tool_calls
        val completion = json.parseToJsonElement(
            AntigravityClient().toOpenAiCompletionJson(inference, "gemini-3.8-flash-high")
        ).jsonObject
        val choice = completion["choices"]!!.jsonArray[0].jsonObject
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        val messageToolCalls = choice["message"]!!.jsonObject["tool_calls"]!!.jsonArray
        assertEquals(1, messageToolCalls.size)
        assertEquals("function", messageToolCalls[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(12, completion["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `响应无上游 id 时本地合成 tool call id`() {
        val upstream = """
            {
              "response": {
                "candidates": [{
                  "content": {"role": "model", "parts": [{"functionCall": {"name": "bash", "args": {"command": "ls"}}}]},
                  "finishReason": "STOP"
                }]
              }
            }
        """.trimIndent()

        val inference = AntigravityClient().parseInference(upstream)

        val id = inference.toolCalls.firstOrNull()?.id ?: fail("应合成 tool call id")
        assertTrue(id.startsWith("bash-"), "合成 id 应以工具名为前缀: $id")
    }

    @Test
    fun `纯文本响应 finish_reason 仍为 stop`() {
        val upstream = """
            {
              "response": {
                "candidates": [{
                  "content": {"role": "model", "parts": [{"text": "你好！"}]},
                  "finishReason": "STOP"
                }]
              }
            }
        """.trimIndent()

        val inference = AntigravityClient().parseInference(upstream)
        assertEquals("你好！", inference.contentText)
        assertEquals(0, inference.toolCalls.size)

        val completion = json.parseToJsonElement(
            AntigravityClient().toOpenAiCompletionJson(inference, "m")
        ).jsonObject
        assertEquals(
            "stop",
            completion["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content
        )
    }

    // =========================================================================
    // 5. 流式 SSE 拆帧规范
    // =========================================================================

    @Test
    fun `流式 SSE 按首帧工具帧终帧拆分`() {
        val inference = AntigravityClient().parseInference(
            """
            {
              "response": {
                "candidates": [{
                  "content": {"role": "model", "parts": [
                    {"text": "正在搜索"},
                    {"functionCall": {"name": "web_search", "args": {"query": "硅谷"}, "id": "c1"}}
                  ]},
                  "finishReason": "STOP"
                }]
              }
            }
            """.trimIndent()
        )

        val sse = AntigravityClient().toOpenAiStreamSse(inference, "gemini-3.8-flash-high")
        val frames = sse.split("\n\n").filter { it.startsWith("data: ") }
        // 首帧 + 工具帧 + 终帧 + [DONE]
        assertEquals(4, frames.size)
        assertTrue(frames.last().contains("[DONE]"))

        val firstDelta = frames[0].removePrefix("data: ")
            .let { json.parseToJsonElement(it).jsonObject["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject }
        assertEquals("assistant", firstDelta["role"]!!.jsonPrimitive.content)
        assertEquals("正在搜索", firstDelta["content"]!!.jsonPrimitive.content)

        val deltaToolCalls = frames[1].removePrefix("data: ")
            .let { json.parseToJsonElement(it).jsonObject["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["tool_calls"]!!.jsonArray }
        assertEquals(0, deltaToolCalls[0].jsonObject["index"]!!.jsonPrimitive.content.toLong())
        assertEquals("c1", deltaToolCalls[0].jsonObject["id"]!!.jsonPrimitive.content)

        val finalFinish = frames[2].removePrefix("data: ")
            .let { json.parseToJsonElement(it).jsonObject["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content }
        assertEquals("tool_calls", finalFinish)
    }

    private fun buildUnifiedWithTools(): UnifiedChatRequest {
        val openAiRaw = """
            {
              "model": "gemini-3.8-flash-high",
              "messages": [{"role": "user", "content": "查找硅谷最近的科技新闻"}],
              "tools": [{"type": "function", "function": {
                "name": "web_search", "description": "搜索互联网新闻",
                "parameters": {"type": "object", "properties": {"query": {"type": "string"}}}
              }}]
            }
        """.trimIndent()
        return ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiRaw)
    }
}
