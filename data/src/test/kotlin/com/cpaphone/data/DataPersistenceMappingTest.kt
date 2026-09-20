package com.cpaphone.data

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.data.local.Converters
import com.cpaphone.data.local.entity.CredentialEntity
import com.cpaphone.data.repository.CredentialRepository
import com.cpaphone.data.security.SecureCredentialStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DataPersistenceMappingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val converters = Converters()

    @Test
    fun testRoomTypeConverters() {
        // ProviderType 互转
        val provider = ProviderType.CLAUDE
        val providerStr = converters.fromProviderType(provider)
        assertEquals("claude", providerStr)
        assertEquals(ProviderType.CLAUDE, converters.toProviderType(providerStr))

        // AuthType 互转
        val authType = AuthType.OAUTH
        val authTypeStr = converters.fromAuthType(authType)
        assertEquals("OAUTH", authTypeStr)
        assertEquals(AuthType.OAUTH, converters.toAuthType(authTypeStr))

        // CredentialStatus 互转
        val status = CredentialStatus.COOLDOWN
        val statusStr = converters.fromCredentialStatus(status)
        assertEquals("COOLDOWN", statusStr)
        assertEquals(CredentialStatus.COOLDOWN, converters.toCredentialStatus(statusStr))
    }

    @Test
    fun testDomainAndEntityMappingBidirectional() {
        val now = System.currentTimeMillis()
        val modelCooldowns = mapOf(
            "claude-3-7-sonnet" to now + 3600_000L,
            "claude-3-5-sonnet" to now + 7200_000L
        )
        val modelAliases = mapOf(
            "gpt-4" to "claude-3-5-sonnet",
            "gpt-3.5-turbo" to "claude-3-5-haiku"
        )
        val headers = mapOf(
            "anthropic-beta" to "prompt-caching-2024-07-31"
        )

        val domainCred = AuthCredential(
            id = "test-cred-01",
            alias = "Team-Claude-Prod",
            provider = ProviderType.CLAUDE,
            authType = AuthType.OAUTH,
            prefix = "team-a/",
            weight = 10,
            status = CredentialStatus.ACTIVE,
            statusMessage = "All systems operational",
            cooldownUntilTimestamp = 0L,
            modelCooldowns = modelCooldowns,
            expiresAt = now + 86400_000L,
            customBaseUrl = "https://custom.anthropic.proxy",
            modelAliases = modelAliases,
            headers = headers,
            totalRequests = 1500L,
            successfulRequests = 1490L,
            lastErrorTimestamp = 1726800000000L,
            lastErrorMessage = "429 Too Many Requests",
            createdAt = 1726700000000L
        )

        // 模拟转换为 Entity
        val entity = CredentialEntity(
            id = domainCred.id,
            alias = domainCred.alias,
            provider = domainCred.provider,
            authType = domainCred.authType,
            prefix = domainCred.prefix,
            weight = domainCred.weight,
            status = domainCred.status,
            statusMessage = domainCred.statusMessage,
            cooldownUntilTimestamp = domainCred.cooldownUntilTimestamp,
            modelCooldownsJson = json.encodeToString(domainCred.modelCooldowns),
            expiresAt = domainCred.expiresAt,
            customBaseUrl = domainCred.customBaseUrl,
            modelAliasesJson = json.encodeToString(domainCred.modelAliases),
            headersJson = json.encodeToString(domainCred.headers),
            totalRequests = domainCred.totalRequests,
            successfulRequests = domainCred.successfulRequests,
            lastErrorTimestamp = domainCred.lastErrorTimestamp,
            lastErrorMessage = domainCred.lastErrorMessage,
            createdAt = domainCred.createdAt
        )

        // 验证 Entity 字段
        assertEquals("test-cred-01", entity.id)
        assertEquals(AuthType.OAUTH, entity.authType)
        assertEquals(domainCred.expiresAt, entity.expiresAt)
        assertTrue(entity.modelCooldownsJson.contains("claude-3-7-sonnet"))
        assertTrue(entity.modelAliasesJson.contains("gpt-4"))

        // 反向映射回 Domain
        val revertedDomain = AuthCredential(
            id = entity.id,
            alias = entity.alias,
            provider = entity.provider,
            authType = entity.authType,
            prefix = entity.prefix,
            weight = entity.weight,
            status = entity.status,
            statusMessage = entity.statusMessage,
            cooldownUntilTimestamp = entity.cooldownUntilTimestamp,
            modelCooldowns = json.decodeFromString(entity.modelCooldownsJson),
            expiresAt = entity.expiresAt,
            customBaseUrl = entity.customBaseUrl,
            modelAliases = json.decodeFromString(entity.modelAliasesJson),
            headers = json.decodeFromString(entity.headersJson),
            totalRequests = entity.totalRequests,
            successfulRequests = entity.successfulRequests,
            lastErrorTimestamp = entity.lastErrorTimestamp,
            lastErrorMessage = entity.lastErrorMessage,
            createdAt = entity.createdAt
        )

        assertEquals(domainCred, revertedDomain)
        assertTrue(revertedDomain.isAvailableForModel("claude-3-5-haiku"))
        assertFalse(revertedDomain.isAvailableForModel("claude-3-7-sonnet"))
    }
}
