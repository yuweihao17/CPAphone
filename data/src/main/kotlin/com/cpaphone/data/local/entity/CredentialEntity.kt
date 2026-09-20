package com.cpaphone.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType

/**
 * 凭据本地持久化实体，严格对齐 core 模块强化后的 AuthCredential 领域实体
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val provider: ProviderType,
    val authType: AuthType,
    val prefix: String,
    val weight: Int,
    val status: CredentialStatus,
    val statusMessage: String,
    val cooldownUntilTimestamp: Long,
    val modelCooldownsJson: String,  // 单模型冷却映射字典 JSON
    val expiresAt: Long,             // OAuth Token 到期时间戳
    val customBaseUrl: String?,
    val modelAliasesJson: String,    // 模型别名 JSON
    val headersJson: String,         // 自定义请求头 JSON
    val totalRequests: Long,
    val successfulRequests: Long,
    val lastErrorTimestamp: Long,
    val lastErrorMessage: String?,
    val createdAt: Long
)
