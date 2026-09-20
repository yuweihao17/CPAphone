package com.cpaphone.engine

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.translator.StreamChunkTranslator
import com.cpaphone.engine.coordinator.CredentialCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnginePipelineTest {

    @Test
    fun testStreamChunkReconstructionPipeline() {
        // 模拟上游返回的 Claude 原生流事件
        val claudeThinkingEvent = "data: {\"type\": \"content_block_delta\", \"delta\": {\"type\": \"thinking_delta\", \"thinking\": \"Step 1: Parse input\"}}"
        val claudeTextEvent = "data: {\"type\": \"content_block_delta\", \"delta\": {\"type\": \"text_delta\", \"text\": \"Quicksort is fast.\"}}"
        val claudeEndEvent = "data: {\"type\": \"message_delta\", \"delta\": {\"stop_reason\": \"end_turn\"}}"

        val chunk1 = StreamChunkTranslator.translateClaudeToOpenAiChunk(claudeThinkingEvent, "claude-3-7-sonnet")
        val chunk2 = StreamChunkTranslator.translateClaudeToOpenAiChunk(claudeTextEvent, "claude-3-7-sonnet")
        val chunk3 = StreamChunkTranslator.translateClaudeToOpenAiChunk(claudeEndEvent, "claude-3-7-sonnet")

        assertNotNull(chunk1)
        assertTrue(chunk1!!.contains("reasoning_content"))
        assertTrue(chunk1.contains("Step 1: Parse input"))

        assertNotNull(chunk2)
        assertTrue(chunk2!!.contains("Quicksort is fast."))

        assertNotNull(chunk3)
        assertTrue(chunk3!!.contains("\"finish_reason\":\"stop\""))
    }

    @Test
    fun testCoordinatorPerModelCooldownRouting() {
        // 创建一个包含两个可用凭据的模拟仓储
        val cred1 = AuthCredential(
            id = "c1",
            alias = "Claude-Pro-1",
            provider = ProviderType.CLAUDE,
            authType = AuthType.API_KEY,
            weight = 10,
            status = CredentialStatus.ACTIVE
        )
        val cred2 = AuthCredential(
            id = "c2",
            alias = "Claude-Pro-2",
            provider = ProviderType.CLAUDE,
            authType = AuthType.API_KEY,
            weight = 5,
            status = CredentialStatus.ACTIVE
        )

        val coordinator = CredentialCoordinator(
            credentialRepository = MockCredentialRepository(listOf(cred1, cred2))
        )

        runBlocking {
            // 正常情况下，c1 权重 10，优先命中 c1
            val result1 = coordinator.acquireCredential("claude-3-7-sonnet")
            assertNotNull(result1)
            assertEquals("c1", result1?.first?.id)

            // 模拟上游对 c1 的 claude-3-7-sonnet 返回 429 配额耗尽
            coordinator.reportFailure("c1", "429 Rate Limit", 429, model = "claude-3-7-sonnet")

            // 再次针对 claude-3-7-sonnet 获取凭据，c1 该模型处于冷却，自动平滑故障转移至 c2
            val result2 = coordinator.acquireCredential("claude-3-7-sonnet")
            assertNotNull(result2)
            assertEquals("c2", result2?.first?.id)

            // 关键验证：若请求同一个 c1 账号的另一个健康模型 claude-3-5-haiku，c1 依然可用！
            val resultHaiku = coordinator.acquireCredential("claude-3-5-haiku")
            assertNotNull(resultHaiku)
            assertEquals("c1", resultHaiku?.first?.id)
        }
    }

    private class MockCredentialRepository(private val credentials: List<AuthCredential>) :
        com.cpaphone.data.repository.CredentialRepository(
            dao = MockDao(),
            secureStorage = MockSecureStorage()
        ) {
        override suspend fun getAllCredentials(): List<AuthCredential> = credentials
        override fun getSecretKey(credentialId: String): String = "mock-secret-key"
        override suspend fun recordRequestMetrics(id: String, success: Boolean) {}
        override suspend fun recordError(id: String, message: String) {}
        override suspend fun updateModelCooldown(id: String, model: String, cooldownUntilTimestamp: Long) {}
        override suspend fun updateGlobalCooldown(id: String, timestamp: Long) {}
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
        context = createMockContext()
    ) {
        override fun getSecret(credentialId: String): String? = "test-mock-secret"
        override fun saveSecret(credentialId: String, secretKeyOrToken: String) {}
    }

    companion object {
        private fun createMockContext(): android.content.Context {
            return java.lang.reflect.Proxy.newProxyInstance(
                android.content.Context::class.java.classLoader,
                arrayOf(android.content.Context::class.java)
            ) { _, _, _ -> null } as android.content.Context
        }
    }
}
