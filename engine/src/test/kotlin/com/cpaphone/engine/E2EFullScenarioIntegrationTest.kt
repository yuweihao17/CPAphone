package com.cpaphone.engine

import com.cpaphone.core.disguise.ClientCloakInterceptor
import com.cpaphone.core.disguise.CloakObfuscator
import com.cpaphone.core.model.*
import com.cpaphone.core.rules.PayloadConfig
import com.cpaphone.core.rules.PayloadModelRule
import com.cpaphone.core.rules.PayloadRule
import com.cpaphone.core.rules.PayloadRuleEngine
import com.cpaphone.core.signature.SignatureDecisionAction
import com.cpaphone.core.signature.SignatureProvider
import com.cpaphone.core.signature.SignatureSniffer
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.SseStreamConverter
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.home.HomeClusterClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * CPAphone 端到端全链路业务场景闭环集成测试
 * 覆盖：
 * 1. 客户端请求进入 -> 动态 Payload 规则参数改写；
 * 2. 敏感词零宽字符插值风控防御 (\u200B)；
 * 3. 跨模型思考签名嗅探与决策矩阵；
 * 4. 平滑加权调度器选定凭据与单模型局部冷却防误伤；
 * 5. 全双工跨协议转译 (OpenAI -> Claude)；
 * 6. 上游 Claude 流式事件实时重构为 OpenAI chat.completion.chunk 数据帧；
 * 7. 节点在途并发追踪与累计释放刷新器 (ReleaseFlusher)。
 */
class E2EFullScenarioIntegrationTest {

    @Test
    fun testEndToEndFullPipeline() = runBlocking {
        // =====================================================================
        // Step 1: 客户端原始请求与动态 Payload 规则重写
        // =====================================================================
        val rawClientRequest = """
            {
                "model": "claude-3-7-sonnet",
                "messages": [
                    {"role": "user", "content": "Hello Anthropic, how to write clean code?"}
                ],
                "stream": true
            }
        """.trimIndent()

        // 配置 Payload 规则：针对 claude-* 模型，若缺失 temperature 则补齐 0.7，强制注入 max_tokens 4096
        val payloadConfig = PayloadConfig(
            default = listOf(
                PayloadRule(
                    models = listOf(PayloadModelRule(name = "claude-*")),
                    params = mapOf("temperature" to JsonPrimitive(0.7))
                )
            ),
            override = listOf(
                PayloadRule(
                    models = listOf(PayloadModelRule(name = "*")),
                    params = mapOf("max_tokens" to JsonPrimitive(4096))
                )
            )
        )

        val rewrittenPayload = PayloadRuleEngine.apply(
            payloadJson = rawClientRequest,
            model = "claude-3-7-sonnet",
            config = payloadConfig
        )
        assertTrue(rewrittenPayload.contains("\"temperature\":0.7"))
        assertTrue(rewrittenPayload.contains("\"max_tokens\":4096"))

        // =====================================================================
        // Step 2: 敏感词零宽字符插入混淆 (Cloak Obfuscator)
        // =====================================================================
        val cloakObfuscator = CloakObfuscator(listOf("Anthropic", "Claude"))
        val obfuscatedContent = cloakObfuscator.obfuscateText("Hello Anthropic, how to write clean code?")
        assertTrue(obfuscatedContent.contains("A\u200Bnthropic"))

        // =====================================================================
        // Step 3: 思考签名识别与跨模型兼容决策
        // =====================================================================
        val mockSignature = "C081234567890abcdef"
        val detectedProvider = SignatureSniffer.detectSignatureProvider(mockSignature)
        assertEquals(SignatureProvider.CLAUDE, detectedProvider)

        val decision = SignatureSniffer.decideAction(
            targetProvider = "claude",
            detectedSignatureProvider = detectedProvider
        )
        assertEquals(SignatureDecisionAction.PRESERVE, decision)

        // =====================================================================
        // Step 4: 凭据调度协同器（平滑加权与单模型局部冷却隔离）
        // =====================================================================
        val credClaude = AuthCredential(
            id = "cred-claude-primary",
            alias = "Claude-Team-VIP",
            provider = ProviderType.CLAUDE,
            weight = 10,
            status = CredentialStatus.ACTIVE
        )
        val coordinator = CredentialCoordinator(
            credentialRepository = MockRepo(listOf(credClaude))
        )

        // 挑选凭据
        val acquired = coordinator.acquireCredential("claude-3-7-sonnet")
        assertNotNull(acquired)
        assertEquals("cred-claude-primary", acquired?.first?.id)

        // 模拟上游返回 429 限流：触发针对 3-7-sonnet 的单模型冷却
        coordinator.reportFailure("cred-claude-primary", "429 Rate Limit", 429, model = "claude-3-7-sonnet")
        assertTrue(coordinator.cooldownManager.isCooling("cred-claude-primary", "claude-3-7-sonnet"))
        // 关键隔离验证：同一凭据下的 3-5-haiku 依然健康可用
        assertFalse(coordinator.cooldownManager.isCooling("cred-claude-primary", "claude-3-5-haiku"))

        // =====================================================================
        // Step 5: 跨协议转译为目标厂商原生报文 (OpenAI -> Claude)
        // =====================================================================
        val parsedRequest = ProtocolTranslatorEngine.parseOpenAiChatRequest(rewrittenPayload)
        val claudeNativeJson = ProtocolTranslatorEngine.toClaudeMessagesJson(parsedRequest, "claude-3-7-sonnet")
        assertTrue(claudeNativeJson.contains("claude-3-7-sonnet"))
        assertTrue(claudeNativeJson.contains("\"role\":\"user\""))

        // 应用客户端指纹伪装披风
        val rawHeaders = mapOf("Authorization" to "Bearer test-key", "X-Forwarded-For" to "10.0.0.1")
        val cloakedHeaders = ClientCloakInterceptor.applyHeaders(ProviderType.CLAUDE, rawHeaders, enableCloak = true)
        assertFalse(cloakedHeaders.containsKey("X-Forwarded-For"))
        assertTrue(cloakedHeaders["User-Agent"]!!.contains("claude-cli"))

        // =====================================================================
        // Step 6: 上游 Claude SSE 流式数据帧实时重构为 OpenAI 规范 Chunk
        // =====================================================================
        val streamConverter = SseStreamConverter.ClaudeToOpenAi("claude-3-7-sonnet")
        val reconstructedOpenAiChunk = streamConverter.convert(
            """{"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Writing clean code requires discipline."}}"""
        )
        assertNotNull(reconstructedOpenAiChunk)
        assertTrue(reconstructedOpenAiChunk!!.contains("chat.completion.chunk"))
        assertTrue(reconstructedOpenAiChunk.contains("Writing clean code requires discipline."))

        // =====================================================================
        // Step 7: Home 集群在途并发快照打包与累计并发释放
        // =====================================================================
        val homeClient = HomeClusterClient()
        val inFlightFrame = homeClient.packInFlightSnapshot(
            activeRequests = listOf(Pair("cred-claude-primary", "claude-3-7-sonnet")),
            maxGroups = 100
        )
        assertEquals("part", inFlightFrame.kind)
        assertEquals(1, inFlightFrame.aggregates.size)

        // 请求结束，递增释放序列号
        val releaseFrame = homeClient.markReleaseDirty("cred-claude-primary", "claude-3-7-sonnet")
        assertEquals(1L, releaseFrame.releaseSeq)

        // 服务端确认
        homeClient.ackRelease("cred-claude-primary", "claude-3-7-sonnet", 1L)
        assertTrue(homeClient.getPendingReleaseFrames().isEmpty())
    }

    private class MockRepo(private val list: List<AuthCredential>) :
        com.cpaphone.data.repository.CredentialRepository(
            dao = MockDao(),
            secureStorage = MockSecureStorage()
        ) {
        override suspend fun getAllCredentials(): List<AuthCredential> = list
        override fun getSecretKey(credentialId: String): String = "mock-sk"
        override suspend fun recordRequestMetrics(id: String, success: Boolean) {}
        override suspend fun recordError(id: String, message: String) {}
        override suspend fun updateModelCooldown(id: String, model: String, cooldownUntilTimestamp: Long) {}
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

    private class MockSecureStorage : com.cpaphone.data.security.SecureCredentialStorage(
        context = null
    ) {
        override fun getSecret(credentialId: String): String = "mock-secret"
    }
}
