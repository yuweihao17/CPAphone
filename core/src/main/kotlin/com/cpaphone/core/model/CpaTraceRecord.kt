package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 请求审计与链路追踪记录模型，对应 CLIProxyAPI CPA-Trace
 */
@Serializable
data class CpaTraceRecord(
    val traceId: String,
    val clientIp: String,
    val requestMethod: String,
    val requestPath: String,
    val inboundProtocol: String,          // openai, claude, gemini
    val requestedModel: String,
    val mappedModel: String,
    val targetProvider: ProviderType,
    val credentialId: String,
    val credentialAlias: String,
    val statusCode: Int,
    val durationMs: Long,
    val ttftMs: Long = 0L,                 // 首字时间 (Time to First Token)
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val isStreaming: Boolean = false,
    val retryCount: Int = 0,
    val wasCooldownTriggered: Boolean = false,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
