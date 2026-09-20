package com.cpaphone.data.repository

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.CredentialStatus
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

    fun getSecretKey(credentialId: String): String? {
        return secureStorage.getSecret(credentialId)
    }

    suspend fun updateStatus(id: String, status: CredentialStatus, message: String = "") {
        dao.updateStatus(id, status, message)
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

    private fun CredentialEntity.toDomain(): AuthCredential {
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

        return AuthCredential(
            id = id,
            alias = alias,
            provider = provider,
            prefix = prefix,
            weight = weight,
            status = status,
            statusMessage = statusMessage,
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

    private fun AuthCredential.toEntity(): CredentialEntity {
        return CredentialEntity(
            id = id,
            alias = alias,
            provider = provider,
            prefix = prefix,
            weight = weight,
            status = status,
            statusMessage = statusMessage,
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
