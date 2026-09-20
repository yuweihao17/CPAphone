package com.cpaphone.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cpaphone.core.model.ProviderType

/**
 * 请求 Trace 日志实体
 */
@Entity(tableName = "trace_logs")
data class TraceLogEntity(
    @PrimaryKey val traceId: String,
    val clientIp: String,
    val requestMethod: String,
    val requestPath: String,
    val inboundProtocol: String,
    val requestedModel: String,
    val mappedModel: String,
    val targetProvider: ProviderType,
    val credentialId: String,
    val credentialAlias: String,
    val statusCode: Int,
    val durationMs: Long,
    val ttftMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val isStreaming: Boolean,
    val retryCount: Int,
    val wasCooldownTriggered: Boolean,
    val errorMessage: String?,
    val timestamp: Long
)
