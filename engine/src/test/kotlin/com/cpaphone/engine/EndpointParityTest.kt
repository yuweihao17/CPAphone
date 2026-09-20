package com.cpaphone.engine

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.server.LocalProxyServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全端点对齐路由层集成测试（真实启动 LocalProxyServer 在 127.0.0.1 随机端口）
 * 覆盖：/v1/models 协议分流、count_tokens 估算、鉴权层 5 处凭据校验、Gemini 未知 method 404
 */
class EndpointParityTest {

    private val port = 18791

    private fun newServer(port: Int, apiKeys: Set<String> = emptySet()): LocalProxyServer {
        val repo = object : com.cpaphone.data.repository.CredentialRepository(
            dao = MockDao(),
            secureStorage = MockSecureStorage()
        ) {
            override suspend fun getAllCredentials(): List<AuthCredential> = listOf(
                AuthCredential(
                    id = "c1", alias = "Mock-Claude", provider = ProviderType.CLAUDE,
                    authType = AuthType.API_KEY, weight = 1, status = CredentialStatus.ACTIVE
                )
            )
            override fun getSecretKey(credentialId: String): String = "mock-sk"
        }
        val server = LocalProxyServer(
            coordinator = CredentialCoordinator(repo),
            traceLogDao = MockTraceLogDao(),
            credentialRepository = repo
        )
        server.setApiKeysProvider { apiKeys }
        server.start(port, bindAllInterfaces = false)
        return server
    }

    /** 等待端口真正可连（绑定验证为异步，最多 3 秒） */
    private fun awaitPort(port: Int) {
        repeat(60) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun testModels分流countTokens与未知Method() {
        val port = 18791
        val server = newServer(port)
        try {
            awaitPort(port)
            runBlocking {
                val client = HttpClient(CIO)

                // 1. OpenAI 格式（默认）
                val openAiModels = client.get("http://127.0.0.1:$port/v1/models").bodyAsText()
                assertTrue(openAiModels.contains("\"object\":\"list\""), "默认应返回 OpenAI 格式: $openAiModels")

                // 2. Claude 分流（Anthropic-Version 头）
                val claudeModels = client.get("http://127.0.0.1:$port/v1/models") {
                    header("Anthropic-Version", "2023-06-01")
                }.bodyAsText()
                assertTrue(claudeModels.contains("\"display_name\""), "Claude 客户端应得 Claude 格式: $claudeModels")
                assertTrue(claudeModels.contains("\"type\":\"model\""))

                // 3. count_tokens：凭据池仅有 mock Claude（上游不可达则走估算兜底）
                val countResp = client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"model":"claude-3-5-sonnet","messages":[{"role":"user","content":"hello world token count"}]}""")
                }.bodyAsText()
                assertTrue(countResp.contains("input_tokens"), "count_tokens 应返回 token 计数: $countResp")

                // 4. Gemini 未知 method → 404（不复刻 CLIProxyAPI 静默 200 的瑕疵）
                val unknown = client.post("http://127.0.0.1:$port/v1beta/models/gemini-2.5-flash:embedContents") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("{}")
                }
                assertEquals(404, unknown.status.value, "实际响应: ${unknown.bodyAsText().take(200)}")

                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun test鉴权层五处凭据校验() {
        val port = 18793
        // 动态可变 keys：测试中途启用鉴权
        val keys = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())
        val server = newServer(port)
        server.setApiKeysProvider { keys.get() }
        try {
            awaitPort(port)
            runBlocking {
                val client = HttpClient(CIO)

                // 未配置 → 放行（得到业务响应而非 401）
                val open = client.get("http://127.0.0.1:$port/v1/models").bodyAsText()
                assertTrue(open.contains("data"), "未配置 keys 时应放行: $open")

                // 启用鉴权：无 key → 401 Missing
                keys.set(setOf("sk-secret-1"))
                val missing = client.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"model":"gpt-x","messages":[]}""")
                }
                assertEquals(401, missing.status.value, "无凭据应 401")
                assertTrue(missing.bodyAsText().contains("Missing API key"))

                // 错误 key → 401 Invalid（x-api-key 候选）
                val invalid = client.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header("X-Api-Key", "sk-wrong")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"model":"gpt-x","messages":[]}""")
                }
                assertEquals(401, invalid.status.value)
                assertTrue(invalid.bodyAsText().contains("Invalid API key"))

                // 正确 key（Bearer）→ 穿过鉴权层（mock 凭据的上游 401 是 upstream_error 格式，非鉴权格式）
                val valid = client.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer sk-secret-1")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"model":"gpt-x","messages":[{"role":"user","content":"hi"}]}""")
                }
                assertTrue(
                    !valid.bodyAsText().contains("Invalid API key") && !valid.bodyAsText().contains("Missing API key"),
                    "正确 key 应穿过鉴权层，实际 ${valid.status.value}: ${valid.bodyAsText().take(120)}"
                )

                // query key 候选（?key=）
                val queryKey = client.post("http://127.0.0.1:$port/v1/chat/completions?key=sk-secret-1") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"model":"gpt-x","messages":[{"role":"user","content":"hi"}]}""")
                }
                assertTrue(
                    !queryKey.bodyAsText().contains("Invalid API key"),
                    "query key 候选应可放行，实际 ${queryKey.status.value}"
                )

                client.close()
            }
        } finally {
            server.stop()
        }
    }

    private class MockTraceLogDao : com.cpaphone.data.local.dao.TraceLogDao {
        override fun getRecentLogsFlow() = kotlinx.coroutines.flow.flowOf(emptyList<com.cpaphone.data.local.entity.TraceLogEntity>())
        override suspend fun getRecentLogs(limit: Int) = emptyList<com.cpaphone.data.local.entity.TraceLogEntity>()
        override suspend fun insert(entity: com.cpaphone.data.local.entity.TraceLogEntity) {}
        override suspend fun pruneOldLogs() {}
        override suspend fun clearAll() {}
    }

    private class MockDao : com.cpaphone.data.local.dao.CredentialDao {
        override fun getAllFlow() = kotlinx.coroutines.flow.flowOf(emptyList<com.cpaphone.data.local.entity.CredentialEntity>())
        override fun getByProviderFlow(provider: ProviderType) = kotlinx.coroutines.flow.flowOf(emptyList<com.cpaphone.data.local.entity.CredentialEntity>())
        override suspend fun getAll() = emptyList<com.cpaphone.data.local.entity.CredentialEntity>()
        override suspend fun getById(id: String) = null
        override suspend fun insertOrUpdate(entity: com.cpaphone.data.local.entity.CredentialEntity) {}
        override suspend fun insertAll(entities: List<com.cpaphone.data.local.entity.CredentialEntity>) {}
        override suspend fun updateStatus(id: String, status: CredentialStatus, message: String) {}
        override suspend fun updateGlobalCooldown(id: String, timestamp: Long) {}
        override suspend fun updateModelCooldowns(id: String, modelCooldownsJson: String) {}
        override suspend fun updateExpiresAt(id: String, expiresAt: Long) {}
        override suspend fun recordRequestMetrics(id: String, successIncrement: Int) {}
        override suspend fun recordError(id: String, timestamp: Long, errorMessage: String) {}
        override suspend fun delete(entity: com.cpaphone.data.local.entity.CredentialEntity) {}
        override suspend fun deleteById(id: String) {}
    }

    private class MockSecureStorage : com.cpaphone.data.security.SecureCredentialStorage(context = null) {
        override fun getSecret(credentialId: String): String = "mock-secret"
    }
}
