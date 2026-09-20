package com.cpaphone.engine

import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.engine.client.AntigravityClient
import com.cpaphone.engine.client.AntigravityInferenceException
import com.cpaphone.engine.oauth.OAuthTokenClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 本机真实端到端函数调用冒烟测试（复现外部客户端"搜索类请求回复 0 字符"场景，CI 自动跳过）
 *
 * 运行方式（本机验证时）：
 *   $env:OAUTH_SMOKE = "1"
 *   .\gradlew.bat :engine:testDebugUnitTest --tests "*AntigravityToolCallSmokeTest*"
 *
 * 复用 .trae/oauth-token.json 持久凭据（401/403 自动 refresh）
 * 验证目标：
 *   1. 云码上游接受 tools 透传（request.tools[].functionDeclarations）不返回 400
 *   2. 搜索类请求触发 functionCall 时，转译输出 OpenAI tool_calls（而非 0 字符空回复）
 */
class AntigravityToolCallSmokeTest {

    private val tokenFile = java.io.File("D:\\Projects\\CPAphone\\.trae\\oauth-token.json")
    private val client = OAuthTokenClient()
    private val antigravity = AntigravityClient()

    @Test
    fun toolCallEndToEnd() {
        Assume.assumeTrue(System.getenv("OAUTH_SMOKE") == "1")
        val saved = loadSavedTokens() ?: error("缺少 .trae/oauth-token.json，请先运行 AntigravityOAuthSmokeTest 完成授权")
        runBlocking {
            // access_token 有效期通常 1 小时：持久凭据跨日复用时先主动续期，避免 loadCodeAssist 401 被吞
            var token = saved.first
            if (saved.second != null) {
                try {
                    val refreshed = client.refresh(ProviderType.ANTIGRAVITY, PROVIDER_SPECS[ProviderType.ANTIGRAVITY]!!, saved.second!!)
                    token = refreshed.accessToken
                    saveTokens(refreshed.accessToken, refreshed.refreshToken ?: saved.second)
                    println(">>> 已通过 refresh_token 续期 access_token")
                } catch (e: Exception) {
                    println(">>> refresh 续期失败（沿用旧 token 尝试）: ${e.message?.take(120)}")
                }
            }
            verifyToolCallInference(token, saved.second)
        }
        println(">>> 函数调用冒烟测试全部通过")
    }

    private suspend fun verifyToolCallInference(accessToken: String, refreshToken: String?) {
        // 模拟外部 AI 客户端（dd）的请求形态：用户要求搜索新闻 + 客户端自带 web_search 工具
        val openAiRequest = """
            {
              "model": "gemini-3-flash",
              "messages": [{"role": "user", "content": "查找硅谷最近的科技新闻"}],
              "stream": false,
              "tools": [{
                "type": "function",
                "function": {
                  "name": "web_search",
                  "description": "Search the web for recent news and information",
                  "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string", "description": "Search keywords"}},
                    "required": ["query"]
                  }
                }
              }]
            }
        """.trimIndent()
        val unified = ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiRequest)
        assertTrue(unified.tools.size == 1, "tools 解析失败")

        val runInference: suspend (String) -> com.cpaphone.engine.client.AntigravityInference = { token ->
            val projectId = antigravity.ensureProjectId("smoke-toolcall", token)
            println(">>> 云码 project_id: $projectId")
            antigravity.generateContent(token, projectId, unified.model, unified)
        }

        var token = accessToken
        val inference = try {
            println(">>> [真实函数调用] POST https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent (stream=false)")
            runInference(token)
        } catch (e: AntigravityInferenceException) {
            if (refreshToken != null && (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true)) {
                println(">>> access_token 已失效，refresh 后重试...")
                val refreshed = client.refresh(ProviderType.ANTIGRAVITY, PROVIDER_SPECS[ProviderType.ANTIGRAVITY]!!, refreshToken)
                saveTokens(refreshed.accessToken, refreshed.refreshToken ?: refreshToken)
                token = refreshed.accessToken
                runInference(token)
            } else {
                throw e
            }
        }

        println(">>> 上游原始响应（前 500 字）: ${inference.rawJson.take(500)}")
        println(">>> 解析结果: content=${inference.contentText.take(80)} toolCalls=${inference.toolCalls.size}")
        inference.toolCalls.forEach { tc ->
            println(">>>   tool_call: id=${tc.id} name=${tc.name} args=${tc.argumentsJson.take(120)}")
        }

        // 核心断言：回复不得为 0 字符——要么有文本，要么有工具调用
        assertTrue(
            inference.contentText.isNotBlank() || inference.toolCalls.isNotEmpty(),
            "0 字符复现：上游既无文本也无 functionCall（原始响应: ${inference.rawJson.take(300)}）"
        )

        // 转译输出验证
        val completionJson = antigravity.toOpenAiCompletionJson(inference, unified.model)
        println(">>> OpenAI 非流式输出（前 300 字）: ${completionJson.take(300)}")
        val sse = antigravity.toOpenAiStreamSse(inference, unified.model)
        println(">>> OpenAI 流式输出帧数: ${sse.split("\n\n").count { it.startsWith("data: ") }}")

        if (inference.toolCalls.isNotEmpty()) {
            assertTrue(completionJson.contains("\"tool_calls\""), "非流式输出缺少 tool_calls")
            assertTrue(completionJson.contains("\"finish_reason\":\"tool_calls\""), "finish_reason 应为 tool_calls")
            assertTrue(sse.contains("tool_calls"), "流式输出缺少 tool_calls 帧")
            println(">>> functionCall → OpenAI tool_calls 转译验证通过")
        } else {
            println(">>> 本次未触发 functionCall（模型直接回答），文本转译链路验证通过")
        }
    }

    private fun loadSavedTokens(): Pair<String, String?>? {
        if (!tokenFile.exists()) return null
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(tokenFile.readText()).jsonObject
            val access = obj["access_token"]?.jsonPrimitive?.content ?: return null
            Pair(access, obj["refresh_token"]?.jsonPrimitive?.content)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveTokens(accessToken: String, refreshToken: String?) {
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(
            kotlinx.serialization.json.buildJsonObject {
                put("access_token", accessToken)
                refreshToken?.let { put("refresh_token", it) }
                put("saved_at", System.currentTimeMillis())
            }.toString()
        )
    }
}
