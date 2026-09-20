package com.cpaphone.engine

import com.cpaphone.core.model.ModelCatalog
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.session.OAuthSessionManager
import com.cpaphone.core.translator.UnifiedChatRequest
import com.cpaphone.core.translator.UnifiedContentPart
import com.cpaphone.core.translator.UnifiedMessage
import com.cpaphone.core.translator.UnifiedRole
import com.cpaphone.engine.oauth.OAuthCallbackServer
import com.cpaphone.engine.oauth.OAuthTokenClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 本机端到端 OAuth + 真实推理冒烟测试（需人工参与首次授权，CI 自动跳过）
 *
 * 运行方式（本机验证时）：
 *   $env:OAUTH_SMOKE = "1"
 *   .\gradlew.bat :engine:testDebugUnitTest --tests "*AntigravityOAuthSmokeTest*"
 *
 * token 持久化：首次浏览器授权后 access/refresh token 存入 .trae/oauth-token.json，
 * 后续运行直接复用（401/403 时自动 refresh），无需重复打扰授权
 *
 * 全链路验证：授权 → 兑换 → email → 模型目录热更新 → 真实上游推理
 * （POST https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent）
 */
class AntigravityOAuthSmokeTest {

    private val workDir = java.io.File("D:\\Projects\\CPAphone\\.trae")
    private val manualFile = java.io.File(workDir, "oauth-manual.txt")
    private val tokenFile = java.io.File(workDir, "oauth-token.json")
    private val client = OAuthTokenClient()

    @Test
    fun antigravityEndToEnd() {
        Assume.assumeTrue(System.getenv("OAUTH_SMOKE") == "1")

        val saved = loadSavedTokens()
        if (saved != null) {
            println(">>> 复用已保存的凭据（token 文件存在），跳过浏览器授权")
            runBlocking { verifyInference(saved.first, saved.second, allowRefresh = true) }
        } else {
            val (accessToken, refreshToken) = browserLoginFlow()
            runBlocking { verifyInference(accessToken, refreshToken, allowRefresh = false) }
        }
        println(">>> 冒烟测试全部通过")
    }

    /** 浏览器授权流程：回调/手动粘贴 → 兑换 → 持久化 token */
    private fun browserLoginFlow(): Pair<String, String?> {
        val spec = PROVIDER_SPECS[ProviderType.ANTIGRAVITY]
            ?: error("antigravity spec missing")
        val sessionManager = OAuthSessionManager()
        val session = sessionManager.startSession(ProviderType.ANTIGRAVITY, spec)
        val authorizeUrl = session.authorizeUrl ?: error("authorize url missing")

        val server = OAuthCallbackServer(CoroutineScope(Dispatchers.IO))
        var callbackMode = true
        try {
            server.start(spec.callbackPort) { code, _, _ ->
                if (!code.isNullOrBlank()) {
                    manualFile.parentFile?.mkdirs()
                    manualFile.writeText("state=${session.state}&code=$code")
                }
            }
        } catch (e: Exception) {
            callbackMode = false
            println(">>> 自动回调端口不可用（${e.message}），降级为手动粘贴模式")
        }

        try {
            manualFile.delete()
            println("================================================================")
            println("请在 PC 浏览器打开以下链接并完成 Google 登录：")
            println(authorizeUrl)
            println("================================================================")
            if (callbackMode) {
                println("等待 localhost:${spec.callbackPort} 自动回调（最长 5 分钟）...")
            } else {
                println("授权完成后把浏览器地址栏完整网址复制保存到：$manualFile")
            }

            var code: String? = null
            val start = System.currentTimeMillis()
            val deadline = if (callbackMode) 5L else 10L
            while (System.currentTimeMillis() - start < deadline * 60_000L) {
                if (manualFile.exists()) {
                    val raw = manualFile.readText()
                    val content = raw.substringAfter('?', raw)
                    val params = content.split("&").filter { it.contains('=') }.associate {
                        it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    if (params["state"] == session.state && !params["code"].isNullOrBlank()) {
                        code = params["code"]
                        break
                    }
                }
                Thread.sleep(5000)
            }
            assertTrue(!code.isNullOrBlank(), "${deadline} 分钟内未取得授权码")

            println(">>> 收到授权码（前 12 位）: ${code!!.take(12)}...")
            return runBlocking {
                val tokens = client.exchangeCode(ProviderType.ANTIGRAVITY, spec, code, null)
                assertTrue(tokens.accessToken.isNotBlank(), "兑换结果缺少 access_token")
                println(">>> access_token 兑换成功（前 12 位）: ${tokens.accessToken.take(12)}...")
                saveTokens(tokens.accessToken, tokens.refreshToken)
                Pair(tokens.accessToken, tokens.refreshToken)
            }
        } finally {
            server.stop()
            manualFile.delete()
        }
    }

    /** 真实推理验证：project_id → generateContent → 模型目录；401/403 时 refresh 后重试一次 */
    private suspend fun verifyInference(accessToken: String, refreshToken: String?, allowRefresh: Boolean) {
        val antigravityClient = com.cpaphone.engine.client.AntigravityClient()
        val runInference: suspend (String) -> com.cpaphone.engine.client.AntigravityInference = { token ->
            val projectId = antigravityClient.ensureProjectId("smoke-test", token)
            println(">>> 云码 project_id: $projectId")
            assertTrue(projectId.isNotBlank(), "云码 project_id 为空")

            val unified = UnifiedChatRequest(
                model = "gemini-3-flash",
                messages = listOf(
                    UnifiedMessage(UnifiedRole.USER, listOf(UnifiedContentPart.Text("只回复两个字：成功")))
                ),
                isStreaming = false
            )
            antigravityClient.generateContent(token, projectId, "gemini-3-flash", unified)
        }

        var token = accessToken
        val inference = try {
            println(">>> [真实推理] POST https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent")
            runInference(token)
        } catch (e: com.cpaphone.engine.client.AntigravityInferenceException) {
            if (allowRefresh && refreshToken != null && (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true)) {
                println(">>> access_token 已失效，使用 refresh_token 自动续期后重试...")
                val refreshed = client.refresh(ProviderType.ANTIGRAVITY, PROVIDER_SPECS[ProviderType.ANTIGRAVITY]!!, refreshToken)
                saveTokens(refreshed.accessToken, refreshed.refreshToken ?: refreshToken)
                token = refreshed.accessToken
                runInference(token)
            } else {
                throw e
            }
        }

        println(">>> 推理返回内容: ${inference.contentText.take(120)}")
        inference.reasoningText?.let { println(">>> 思考链（前 80 字）: ${it.take(80)}") }
        println(">>> usage: prompt=${inference.promptTokens} completion=${inference.completionTokens} total=${inference.totalTokens}")
        println(">>> OpenAI 规范输出: ${antigravityClient.toOpenAiCompletionJson(inference, "gemini-3-flash").take(220)}")
        assertTrue(inference.contentText.isNotBlank(), "真实推理返回内容为空")
        println(">>> 真实推理验证通过")

        // 模型目录热更新验证
        val remoteCatalog = client.fetchRemoteModelCatalog()
        val applied = remoteCatalog?.let { ModelCatalog.applyRemoteCatalog(it) } ?: false
        println(">>> 模型目录热更新: ${if (applied) "已应用远程最新目录" else "拉取失败，回退内嵌目录"}")
        val models = ModelCatalog.aggregateForProviders(setOf(ProviderType.ANTIGRAVITY)).map { it.first }
        println(">>> Antigravity 可用模型（${models.size} 个）: ${models.joinToString(", ")}")
    }

    private fun loadSavedTokens(): Pair<String, String?>? {
        if (!tokenFile.exists()) return null
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(tokenFile.readText()).jsonObject
            val access = obj["access_token"]?.jsonPrimitive?.content ?: return null
            val refresh = obj["refresh_token"]?.jsonPrimitive?.content
            Pair(access, refresh)
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
