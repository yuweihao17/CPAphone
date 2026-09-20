package com.cpaphone.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 凭据配额快照持久化实体（支持本地秒开与无网离线查看）
 */
@Entity(tableName = "credential_quotas")
data class CredentialQuotaEntity(
    @PrimaryKey val credentialId: String,
    val provider: String,
    val planType: String?,
    val resetCredits: Int?,
    val groupsJson: String,       // List<QuotaGroup> 的 JSON
    val statusMessage: String?,
    val isSupported: Boolean,
    val updatedAt: Long
)
