package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 支持的各大 AI 提供商类型枚举，对齐 CLIProxyAPI 架构
 */
@Serializable
enum class ProviderType(val identifier: String, val displayName: String) {
    OPENAI_CODEX("codex", "OpenAI Codex"),
    CLAUDE("claude", "Anthropic Claude"),
    GEMINI("gemini", "Google Gemini"),
    ANTIGRAVITY("antigravity", "Google Antigravity"),
    VERTEX_AI("vertex", "Google Vertex AI"),
    XAI("xai", "xAI Grok"),
    KIMI("kimi", "Moonshot Kimi"),
    OPENAI_COMPATIBLE("openai-compatibility", "OpenAI Compatible");

    companion object {
        fun fromIdentifier(id: String): ProviderType {
            return entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
                ?: OPENAI_COMPATIBLE
        }
    }
}

/**
 * 凭据的运行态生命周期状态
 */
@Serializable
enum class CredentialStatus {
    ACTIVE,      // 健康活跃
    COOLDOWN,    // 熔断冷却中
    EXPIRED,     // 凭据已失效，需重新授权或换 Key
    DISABLED     // 用户手动禁用
}

/**
 * 调度与分发策略
 */
@Serializable
enum class RoutingStrategyType {
    ROUND_ROBIN,          // 普通轮询
    WEIGHTED_ROUND_ROBIN, // 平滑加权轮询
    FILL_FIRST            // 深度优先（优先打满单号）
}

/**
 * 凭据核心领域实体
 */
@Serializable
data class AuthCredential(
    val id: String,
    val alias: String,
    val provider: ProviderType,
    val prefix: String = "",
    val weight: Int = 1,
    val status: CredentialStatus = CredentialStatus.ACTIVE,
    val statusMessage: String = "",
    val cooldownUntilTimestamp: Long = 0L,
    val customBaseUrl: String? = null,
    val modelAliases: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val totalRequests: Long = 0L,
    val successfulRequests: Long = 0L,
    val lastErrorTimestamp: Long = 0L,
    val lastErrorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAvailable: Boolean
        get() = status == CredentialStatus.ACTIVE &&
                (cooldownUntilTimestamp <= System.currentTimeMillis())
}
