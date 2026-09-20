package com.cpaphone.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType

/**
 * 凭据本地持久化实体
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val provider: ProviderType,
    val prefix: String,
    val weight: Int,
    val status: CredentialStatus,
    val statusMessage: String,
    val customBaseUrl: String?,
    val modelAliasesJson: String,  // JSON 存储
    val headersJson: String,       // JSON 存储
    val totalRequests: Long,
    val successfulRequests: Long,
    val lastErrorTimestamp: Long,
    val lastErrorMessage: String?,
    val createdAt: Long
)
