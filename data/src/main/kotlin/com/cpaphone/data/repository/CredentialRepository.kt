package com.cpaphone.data.repository

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.data.local.dao.CredentialDao
import com.cpaphone.data.local.entity.CredentialEntity
import com.cpaphone.data.security.SecureCredentialStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CredentialRepository(
    private val dao: CredentialDao,
    private val secureStorage: SecureCredentialStorage
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllCredentialsFlow(): Flow<List<AuthCredential>> {
        return dao.getAllFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getCredentialsByProviderFlow(provider: ProviderType): Flow<List<AuthCredential>> {
        return dao.getByProviderFlow(provider).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getAllCredentials(): List<AuthCredential> {
        return dao.getAll().map { it.toDomain() }
    }

    suspend fun getCredentialById(id: String): AuthCredential? {
        return dao.getById(id)?.toDomain()
    }

    suspend fun saveCredential(credential: AuthCredential, secretKeyOrToken: String? = null) {
        dao.insertOrUpdate(credential.toEntity())
        if (!secretKeyOrToken.isNullOrBlank()) {
            secureStorage.saveSecret(credential.id, secretKeyOrToken)
        }
    }

    suspend fun saveCredentialsBatch(credentials: List<AuthCredential>) {
        dao.insertAll(credentials.map { it.toEntity() })
    }

    fun getSecretKey(credentialId: String): String? {
        return secureStorage.getSecret(credentialId)
    }

    /** 保存 OAuth refresh token（与 access token 分槽加密存储） */
    suspend fun saveRefreshToken(credentialId: String, refreshToken: String) {
        secureStorage.saveRefreshToken(credentialId, refreshToken)
    }

    /** 读取 OAuth refresh token */
    fun getRefreshToken(credentialId: String): String? {
        return secureStorage.getRefreshToken(credentialId)
    }

    suspend fun updateStatus(id: String, status: CredentialStatus, message: String = "") {
        dao.updateStatus(id, status, message)
    }

    suspend fun updateGlobalCooldown(id: String, timestamp: Long) {
        dao.updateGlobalCooldown(id, timestamp)
    }

    suspend fun updateModelCooldown(id: String, model: String, cooldownUntilTimestamp: Long) {
        val existing = dao.getById(id) ?: return
        val currentMap = try {
            json.decodeFromString<Map<String, Long>>(existing.modelCooldownsJson).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
        currentMap[model] = cooldownUntilTimestamp
        dao.updateModelCooldowns(id, json.encodeToString(currentMap))
    }

    suspend fun updateExpiresAt(id: String, expiresAt: Long) {
        dao.updateExpiresAt(id, expiresAt)
    }

    suspend fun recordRequestMetrics(id: String, success: Boolean) {
        dao.recordRequestMetrics(id, if (success) 1 else 0)
    }

    suspend fun recordError(id: String, message: String) {
        dao.recordError(id, System.currentTimeMillis(), message)
    }

    suspend fun deleteCredential(id: String) {
        dao.deleteById(id)
        secureStorage.deleteSecrets(id)
    }

    fun CredentialEntity.toDomain(): AuthCredential {
        val modelAliases = try {
            json.decodeFromString<Map<String, String>>(modelAliasesJson)
        } catch (_: Exception) {
            emptyMap()
        }

        val headers = try {
            json.decodeFromString<Map<String, String>>(headersJson)
        } catch (_: Exception) {
            emptyMap()
        }

        val modelCooldowns = try {
            json.decodeFromString<Map<String, Long>>(modelCooldownsJson)
        } catch (_: Exception) {
            emptyMap()
        }

        return AuthCredential(
            id = id,
            alias = alias,
            provider = provider,
            authType = authType,
            prefix = prefix,
            weight = weight,
            status = status,
            statusMessage = statusMessage,
            cooldownUntilTimestamp = cooldownUntilTimestamp,
            modelCooldowns = modelCooldowns,
            expiresAt = expiresAt,
            customBaseUrl = customBaseUrl,
            modelAliases = modelAliases,
            headers = headers,
            totalRequests = totalRequests,
            successfulRequests = successfulRequests,
            lastErrorTimestamp = lastErrorTimestamp,
            lastErrorMessage = lastErrorMessage,
            createdAt = createdAt
        )
    }

    fun AuthCredential.toEntity(): CredentialEntity {
        return CredentialEntity(
            id = id,
            alias = alias,
            provider = provider,
            authType = authType,
            prefix = prefix,
            weight = weight,
            status = status,
            statusMessage = statusMessage,
            cooldownUntilTimestamp = cooldownUntilTimestamp,
            modelCooldownsJson = json.encodeToString(modelCooldowns),
            expiresAt = expiresAt,
            customBaseUrl = customBaseUrl,
            modelAliasesJson = json.encodeToString(modelAliases),
            headersJson = json.encodeToString(headers),
            totalRequests = totalRequests,
            successfulRequests = successfulRequests,
            lastErrorTimestamp = lastErrorTimestamp,
            lastErrorMessage = lastErrorMessage,
            createdAt = createdAt
        )
    }
}
