package com.cpaphone.engine.client

import com.cpaphone.core.translator.GeminiSchemaCleaner
import com.cpaphone.core.translator.UnifiedChatRequest
import com.cpaphone.core.translator.UnifiedContentPart
import com.cpaphone.core.translator.UnifiedRole
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Antigravity 云码 functionCall part 转译结果 */
data class AntigravityToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** Antigravity 云码推理结果（已聚合 parts 文本/函数调用与用量） */
data class AntigravityInference(
    val contentText: String,
    val reasoningText: String? = null,
    val toolCalls: List<AntigravityToolCall> = emptyList(),
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val finishReason: String = "STOP",
    val rawJson: String = ""
)

class AntigravityInferenceException(message: String) : Exception(message)

/**
 * Antigravity（Google 云码 OAuth）推理协议客户端
 * 精确对齐 CLIProxyAPI internal/runtime/executor/antigravity_executor*.go：
 * - 推理端点 https://cloudcode-pa.googleapis.com/v1internal:generateContent（Bearer 鉴权，非 API Key）
 * - 请求体云码信封（project / userAgent / requestType=agent / requestId=agent-* / request.contents）
 * - project_id 经 loadCodeAssist 获取、onboardUser 兜底轮询
 * - 响应 response.candidates[].parts[] 解析（thought:true 片段归入 reasoning）
 */
class AntigravityClient {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val projectCache = HashMap<String, String>()

    private companion object {
        // 推理走 daily 端点（对齐 CLIProxyAPI 消费者凭据默认行为，prod 为企业/内部分发）
        const val INFERENCE_BASE = "https://daily-cloudcode-pa.googleapis.com"
        const val ASSIST_BASE = "https://cloudcode-pa.googleapis.com"
        const val USER_AGENT = "antigravity/hub/2.9.1 darwin/arm64"
    }

    /**
     * 获取并缓存云码 project_id（loadCodeAssist → onboardUser 兜底）
     */
    suspend fun ensureProjectId(credentialId: String, accessToken: String): String {
        synchronized(projectCache) {
            projectCache[credentialId]?.let { return it }
        }

        val assist = try {
            val response = client.post("$ASSIST_BASE/v1internal:loadCodeAssist") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.UserAgent, USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody("""{"metadata":{"ideType":"ANTIGRAVITY"}}""")
            }
            parseBody(response)
        } catch (_: Exception) {
            null
        }
        val direct = assist?.extractProject()
        if (!direct.isNullOrBlank()) {
            synchronized(projectCache) { projectCache[credentialId] = direct }
            return direct
        }

        // loadCodeAssist 未携带 project：onboardUser 轮询注册（最多 5 次 × 2s）
        repeat(5) {
            val onboard = try {
                val response = client.post("$INFERENCE_BASE/v1internal:onboardUser") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header("X-Goog-Api-Client", "gl-node/22.21.1")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tier_id":"free-tier","metadata":{"ide_type":"ANTIGRAVITY","ide_version":"2.9.1","ide_name":"antigravity"}}"""
                    )
                }
                parseBody(response)
            } catch (_: Exception) {
                null
            }
            val project = onboard?.get("response")?.let { (it as? JsonObject)?.extractProject() }
                ?: onboard?.extractProject()
            if (!project.isNullOrBlank()) {
                synchronized(projectCache) { projectCache[credentialId] = project }
                return project
            }
            Thread.sleep(2000)
        }
        throw AntigravityInferenceException("Antigravity project_id 获取失败（loadCodeAssist/onboardUser 均未返回）")
    }

    /**
     * 发起云码推理（非流式 generateContent）
     */
    suspend fun generateContent(
        accessToken: String,
        projectId: String,
        model: String,
        unified: UnifiedChatRequest
    ): AntigravityInference {
        val response = client.post("$INFERENCE_BASE/v1internal:generateContent") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(buildRequestEnvelope(projectId, model, unified).toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AntigravityInferenceException("云码推理失败 HTTP ${response.status.value}: ${text.take(300)}")
        }
        return parseInference(text)
    }

    /**
     * 发起云码真流式推理（对齐 CLIProxyAPI ExecuteStream，全链路零缓冲边读边下发）
     * POST /v1internal:streamGenerateContent?alt=sse，请求体与非流式完全相同。
     * 采用 preparePost 保持底层 HTTP 连接为实时流通道，避免 client.post 全量缓冲入内存导致一次性爆发输出。
     * 上游 SSE 字节流帧格式：data: {"response":{candidates...},"traceId":...}，无 [DONE]，连接关闭即结束。
     */
    suspend fun <T> streamGenerateContent(
        accessToken: String,
        projectId: String,
        model: String,
        unified: UnifiedChatRequest,
        block: suspend (io.ktor.utils.io.ByteReadChannel) -> T
    ): T {
        val statement = client.preparePost("$INFERENCE_BASE/v1internal:streamGenerateContent?alt=sse") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(buildRequestEnvelope(projectId, model, unified).toString())
        }
        return statement.execute { response ->
            if (!response.status.isSuccess()) {
                val text = response.bodyAsText()
                throw AntigravityInferenceException("云码流式推理失败 HTTP ${response.status.value}: ${text.take(300)}")
            }
            block(response.bodyAsChannel())
        }
    }

    /**
     * 云码请求信封构造（对齐 CLIProxyAPI geminiToAntigravity）：
     * - tools → request.tools[].functionDeclarations（camelCase，parameters 直接透传）
     * - 有工具时注入 request.toolConfig.functionCallingConfig.mode=AUTO
     */
    internal fun buildRequestEnvelope(projectId: String, model: String, unified: UnifiedChatRequest): JsonObject = buildJsonObject {
        put("project", projectId)
        put("userAgent", "antigravity")
        put("requestType", "agent")
        put("requestId", "agent-${UUID.randomUUID()}")
        put("model", model)
        putJsonObject("request") {
            put("sessionId", "-" + random19Digits())
            put("contents", buildContents(unified))
            unified.systemPrompt?.let { systemPrompt ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPrompt) }
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
                                    // MCP/OpenAI schema 含上游 proto 不认识的扩展字段（x-mcp-header 等），必须清洗
                                    put("parameters", GeminiSchemaCleaner.clean(tool.parametersSchemaJson))
                                }
                            }
                        }
                    }
                }
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") { put("mode", "AUTO") }
                }
            }
            // generationConfig：对齐 CLIProxyAPI buildRequest——非 Claude 模型不设 maxOutputTokens
            // （上游不限长，思考模型的思考+输出不会被配额截断）；temperature/topP/thinkingBudget 透传
            putJsonObject("generationConfig") {
                unified.temperature?.let { put("temperature", it) }
                unified.topP?.let { put("topP", it) }
                unified.thinkingBudgetTokens?.let { budget ->
                    putJsonObject("thinkingConfig") { put("thinkingBudget", budget) }
                }
            }
        }
    }

    /**
     * 云码 contents 构造（camelCase，role: user/model）
     * 对齐 CLIProxyAPI openai→antigravity 请求转译：
     * - assistant 的 tool_calls → role=model 的 functionCall part，必须带
     *   thoughtSignature="skip_thought_signature_validator"（否则上游签名校验拒绝）
     * - 工具结果 → functionResponse part，且 Antigravity 特殊约定 role=model
     *   （CLIProxyAPI normalizeAntigravityGeminiFunctionResponseRoles 的行为）
     * - functionResponse.response.result 保持字符串，不解析为 JSON（解析会导致上游 400）
     */
    internal fun buildContents(unified: UnifiedChatRequest): JsonArray {
        // 工具调用 id → name 映射：functionResponse 必须携带工具名
        val toolNames = HashMap<String, String>()
        unified.messages.forEach { msg ->
            msg.parts.filterIsInstance<UnifiedContentPart.ToolCall>().forEach { tc ->
                if (tc.id.isNotBlank()) toolNames[tc.id] = tc.name
            }
        }
        return buildJsonArray {
            unified.messages.forEach { msg ->
                // 主体 parts（ToolResult 单独拆出为 role=model 的独立 content）
                val bodyParts = msg.parts.filter { it !is UnifiedContentPart.ToolResult }
                if (bodyParts.isNotEmpty() && msg.role != UnifiedRole.TOOL) {
                    addJsonObject {
                        put("role", if (msg.role == UnifiedRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") {
                            bodyParts.forEach { part ->
                                when (part) {
                                    is UnifiedContentPart.Text -> addJsonObject { put("text", part.text) }
                                    is UnifiedContentPart.Image -> addJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", part.mimeType)
                                            put("data", part.base64Data)
                                        }
                                    }
                                    is UnifiedContentPart.ToolCall -> addJsonObject {
                                        putJsonObject("functionCall") {
                                            if (part.id.isNotBlank()) put("id", part.id)
                                            put("name", part.name)
                                            put("args", parseJsonLenient(part.argumentsJson))
                                        }
                                        put("thoughtSignature", "skip_thought_signature_validator")
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                // 工具结果 → functionResponse（role 固定为 model，Antigravity 协议特殊约定）
                msg.parts.filterIsInstance<UnifiedContentPart.ToolResult>().forEach { result ->
                    addJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("id", result.toolCallId)
                                    put("name", toolNames[result.toolCallId] ?: result.toolCallId)
                                    putJsonObject("response") {
                                        put("result", result.content)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 宽容 JSON 解析：失败时回退为字符串字面量包装，保证 args 恒为合法 JSON 对象/值 */
    private fun parseJsonLenient(raw: String): JsonElement = try {
        json.parseToJsonElement(raw.ifBlank { "{}" })
    } catch (_: Exception) {
        kotlinx.serialization.json.JsonPrimitive(raw)
    }

    /**
     * 云码响应 → OpenAI chat.completion JSON（含 reasoning_content、tool_calls 与 usage）
     */
    fun toOpenAiCompletionJson(inference: AntigravityInference, model: String): String {
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", inference.contentText)
            inference.reasoningText?.let { put("reasoning_content", it) }
            if (inference.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    inference.toolCalls.forEach { tc ->
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
        return buildJsonObject {
            put("id", "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("message", message)
                    put("finish_reason", resolveOpenAiFinishReason(inference))
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", inference.promptTokens)
                put("completion_tokens", inference.completionTokens)
                put("total_tokens", inference.totalTokens)
                if (inference.reasoningTokens > 0) {
                    putJsonObject("completion_tokens_details") {
                        put("reasoning_tokens", inference.reasoningTokens)
                    }
                }
            }
        }.toString()
    }

    /**
     * 云码响应 → OpenAI 流式 SSE（流式请求降级非流式调用时的规范包装）。
     * 按 OpenAI 流式惯例拆帧：首帧 role/content → 工具调用帧 → 终帧 finish_reason
     */
    fun toOpenAiStreamSse(inference: AntigravityInference, model: String): String {
        val chatcmplId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val created = System.currentTimeMillis() / 1000
        val sb = StringBuilder()

        fun emitChunk(delta: JsonObject, finishReason: String?) {
            val chunk = buildJsonObject {
                put("id", chatcmplId)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", model)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        finishReason?.let { put("finish_reason", it) }
                    }
                }
            }
            sb.append("data: ").append(chunk).append("\n\n")
        }

        // 首帧：角色 + 文本/思考内容
        emitChunk(
            buildJsonObject {
                put("role", "assistant")
                if (inference.contentText.isNotEmpty()) put("content", inference.contentText)
                inference.reasoningText?.let { put("reasoning_content", it) }
            },
            finishReason = null
        )

        // 工具调用帧（delta.tool_calls，携带 index 供客户端聚合）
        inference.toolCalls.forEachIndexed { index, tc ->
            emitChunk(
                buildJsonObject {
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("index", index)
                            put("id", tc.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson)
                            }
                        }
                    }
                },
                finishReason = null
            )
        }

        // 终帧：finish_reason（有工具调用 → tool_calls，对齐 OpenAI 规范）
        emitChunk(buildJsonObject { }, resolveOpenAiFinishReason(inference))
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }

    /** finish_reason 映射（对齐 CLIProxyAPI resolveOpenAIFinishReason）：有工具调用 → tool_calls */
    private fun resolveOpenAiFinishReason(inference: AntigravityInference): String = when {
        inference.toolCalls.isNotEmpty() -> "tool_calls"
        else -> "stop"
    }

    internal fun parseInference(text: String): AntigravityInference {
        val root = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
            ?: throw AntigravityInferenceException("云码响应非 JSON: ${text.take(200)}")
        val response = root["response"] as? JsonObject
            ?: throw AntigravityInferenceException("云码响应缺少 response 节点: ${text.take(200)}")

        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = candidate?.get("content") as? JsonObject
        val parts = content?.get("parts") as? JsonArray

        var contentText = ""
        var reasoningText: String? = null
        val toolCalls = mutableListOf<AntigravityToolCall>()
        parts?.forEach { partElement ->
            val part = partElement as? JsonObject ?: return@forEach
            val thought = (part["thought"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
            // functionCall part：转译为 OpenAI tool_calls（丢弃会导致客户端收到 0 字符空回复）
            (part["functionCall"] as? JsonObject)?.let { fn ->
                val name = (fn["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val args = fn["args"] ?: buildJsonObject { }
                val id = (fn["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: syntheticToolCallId(name)
                toolCalls.add(AntigravityToolCall(id = id, name = name, argumentsJson = args.toString()))
                return@forEach
            }
            val piece = (part["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
            if (thought) {
                reasoningText = (reasoningText ?: "") + piece
            } else {
                contentText += piece
            }
        }

        val usage = response["usageMetadata"] as? JsonObject
        val finishRaw = (candidate?.get("finishReason") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "STOP"

        return AntigravityInference(
            contentText = contentText,
            reasoningText = reasoningText,
            toolCalls = toolCalls,
            promptTokens = usage?.longOf("promptTokenCount") ?: 0L,
            completionTokens = usage?.longOf("candidatesTokenCount") ?: 0L,
            totalTokens = usage?.longOf("totalTokenCount") ?: 0L,
            reasoningTokens = usage?.longOf("thoughtsTokenCount") ?: 0L,
            finishReason = finishRaw,
            rawJson = text
        )
    }

    /** 无上游 id 时本地合成（对齐 CLIProxyAPI "<name>-<纳秒>" 约定） */
    private fun syntheticToolCallId(name: String): String = "$name-${System.nanoTime()}"

    private suspend fun parseBody(response: HttpResponse): JsonObject? {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) return null
        return try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
    }

    private fun JsonObject.extractProject(): String? {
        val direct = this["cloudaicompanionProject"] ?: this["projectId"] ?: this["project"] ?: return null
        val primitive = direct as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.longOf(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    private fun random19Digits(): String {
        // 19 位数字会超出 Long 上限（9.22e18），拆成 1 位前缀 + 18 位
        val first = (1..9).random()
        val rest = (1..18).map { ('0'..'9').random() }.joinToString("")
        return "$first$rest"
    }
}
