package com.cpaphone.engine.client

import com.cpaphone.core.translator.UnifiedChatRequest
import com.cpaphone.core.translator.UnifiedContentPart
import com.cpaphone.core.translator.UnifiedRole
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** Antigravity 云码推理结果（已聚合 parts 文本与用量） */
data class AntigravityInference(
    val contentText: String,
    val reasoningText: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val finishReason: String,
    val rawJson: String
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
        val envelope = buildJsonObject {
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
            }
        }

        val response = client.post("$INFERENCE_BASE/v1internal:generateContent") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(envelope.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AntigravityInferenceException("云码推理失败 HTTP ${response.status.value}: ${text.take(300)}")
        }
        return parseInference(text)
    }

    /** 云码 contents 构造（camelCase，role: user/model） */
    private fun buildContents(unified: UnifiedChatRequest): JsonArray = buildJsonArray {
        unified.messages.forEach { msg ->
            if (msg.role == UnifiedRole.SYSTEM) return@forEach
            addJsonObject {
                put("role", if (msg.role == UnifiedRole.ASSISTANT) "model" else "user")
                putJsonArray("parts") {
                    msg.parts.forEach { part ->
                        when (part) {
                            is UnifiedContentPart.Text -> addJsonObject { put("text", part.text) }
                            is UnifiedContentPart.Image -> addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", part.mimeType)
                                    put("data", part.base64Data)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    /**
     * 云码响应 → OpenAI chat.completion JSON（含 reasoning_content 与 usage）
     */
    fun toOpenAiCompletionJson(inference: AntigravityInference, model: String): String {
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", inference.contentText)
            inference.reasoningText?.let { put("reasoning_content", it) }
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
                    put("finish_reason", "stop")
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", inference.promptTokens)
                put("completion_tokens", inference.completionTokens)
                put("total_tokens", inference.totalTokens)
            }
        }.toString()
    }

    /**
     * 云码响应 → OpenAI 流式单 chunk SSE（流式请求降级非流式调用时的规范包装）
     */
    fun toOpenAiStreamSse(inference: AntigravityInference, model: String): String {
        val chunk = buildJsonObject {
            put("id", "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {
                        put("role", "assistant")
                        put("content", inference.contentText)
                        inference.reasoningText?.let { put("reasoning_content", it) }
                    }
                    put("finish_reason", "stop")
                }
            }
        }
        return "data: $chunk\n\ndata: [DONE]\n\n"
    }

    private fun parseInference(text: String): AntigravityInference {
        val root = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
            ?: throw AntigravityInferenceException("云码响应非 JSON: ${text.take(200)}")
        val response = root["response"] as? JsonObject
            ?: throw AntigravityInferenceException("云码响应缺少 response 节点: ${text.take(200)}")

        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = candidate?.get("content") as? JsonObject
        val parts = content?.get("parts") as? JsonArray

        var contentText = ""
        var reasoningText: String? = null
        parts?.forEach { partElement ->
            val part = partElement as? JsonObject ?: return@forEach
            val thought = (part["thought"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
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
            promptTokens = usage?.longOf("promptTokenCount") ?: 0L,
            completionTokens = usage?.longOf("candidatesTokenCount") ?: 0L,
            totalTokens = usage?.longOf("totalTokenCount") ?: 0L,
            finishReason = finishRaw,
            rawJson = text
        )
    }

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
