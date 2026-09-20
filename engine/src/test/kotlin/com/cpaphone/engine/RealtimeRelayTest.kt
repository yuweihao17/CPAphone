package com.cpaphone.engine

import com.cpaphone.core.model.*
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.realtime.RealtimeRelayManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RealtimeRelayTest {

    @Test
    fun testClientSecretGenerationAndValidation() {
        val coordinator = CredentialCoordinator(
            credentialRepository = MockRepo()
        )
        val manager = RealtimeRelayManager(coordinator)

        val request = ClientSecretRequest(model = "gpt-4o-realtime-preview", voice = "alloy", expiresAfterSeconds = 300)
        val secret = manager.createClientSecret(request)

        assertNotNull(secret)
        assertTrue(secret.value.startsWith("ek_"))
        assertTrue(secret.sessionId.startsWith("sess_"))
        assertTrue(manager.validateClientSecret(secret.value))

        // 验证假密钥或过期密钥
        assertFalse(manager.validateClientSecret("ek_invalid_token_123"))
    }

    @Test
    fun testRealtimeSessionRegistrationAndClaim() {
        val coordinator = CredentialCoordinator(
            credentialRepository = MockRepo()
        )
        val manager = RealtimeRelayManager(coordinator)

        val cred = AuthCredential(
            id = "c-realtime",
            alias = "Codex-Realtime-01",
            provider = ProviderType.OPENAI_CODEX
        )

        val callId = "call_abc123456"
        val session = manager.registerSession(callId, cred, "gpt-4o-realtime-preview")

        assertEquals(callId, session.callId)
        assertEquals(RealtimeSessionStatus.CONNECTING, session.status)
        assertEquals(1, manager.activeCallCount())

        // 首次认领伴生信令通道成功
        val claimed = manager.claimSideband(callId)
        assertNotNull(claimed)
        assertEquals(callId, claimed?.callId)

        // 互斥测试：再次并发认领同个会话应被阻断 (对应 HTTP 409 Conflict)
        val duplicateClaim = manager.claimSideband(callId)
        assertNull(duplicateClaim)

        // 挂断通话
        val hungUp = manager.hangup(callId)
        assertTrue(hungUp)
        assertEquals(0, manager.activeCallCount())
    }

    @Test
    fun testSyntheticSdpAnswerGeneration() {
        val coordinator = CredentialCoordinator(credentialRepository = MockRepo())
        val manager = RealtimeRelayManager(coordinator)

        val mockOffer = "v=0\r\no=- 123 456 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
        val answer = manager.generateSyntheticSdpAnswer(mockOffer)

        assertNotNull(answer)
        assertTrue(answer.contains("a=rtpmap:111 opus/48000/2"))
        assertTrue(answer.contains("a=mid:0"))
        assertTrue(answer.contains("a=mid:1"))
    }

    private class MockRepo : com.cpaphone.data.repository.CredentialRepository(
        dao = MockDao(),
        secureStorage = MockSecureStorage()
    ) {
        override suspend fun getAllCredentials(): List<AuthCredential> = emptyList()
        override fun getSecretKey(credentialId: String): String = "mock"
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
        override fun getSecret(credentialId: String): String? = "mock"
    }
}
