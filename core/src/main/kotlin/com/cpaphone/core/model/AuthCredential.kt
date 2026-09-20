package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 支持的各大 AI 提供商类型枚举，对齐 CLIProxyAPI 架构
 */
@Serializable
enum class ProviderType(val identifier: String, val displayName: String, val defaultBaseUrl: String) {
    OPENAI_CODEX("codex", "OpenAI Codex", "https://api.openai.com"),
    CLAUDE("claude", "Anthropic Claude", "https://api.anthropic.com"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com"),
    ANTIGRAVITY("antigravity", "Google Antigravity", "https://generativelanguage.googleapis.com"),
    VERTEX_AI("vertex", "Google Vertex AI", "https://us-central1-aiplatform.googleapis.com"),
    XAI("xai", "xAI Grok", "https://api.x.ai"),
    KIMI("kimi", "Moonshot Kimi", "https://api.moonshot.cn"),
    OPENAI_COMPATIBLE("openai-compatibility", "OpenAI Compatible", "https://api.openai.com");

    companion object {
        fun fromIdentifier(id: String): ProviderType {
            return entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
                ?: OPENAI_COMPATIBLE
        }
    }
}

/**
 * 凭据的底层认证形式
 */
@Serializable
enum class AuthType {
    API_KEY,         // 原生 API 密钥
    OAUTH,           // OAuth 2.0 访问令牌
    SERVICE_ACCOUNT  // GCP / 云端服务账号 JSON
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
    val authType: AuthType = AuthType.API_KEY,
    val prefix: String = "",
    val weight: Int = 1,
    val status: CredentialStatus = CredentialStatus.ACTIVE,
    val statusMessage: String = "",
    val cooldownUntilTimestamp: Long = 0L,
    val modelCooldowns: Map<String, Long> = emptyMap(), // 模型维度的局部冷却隔离
    val expiresAt: Long = 0L,                            // OAuth Token 到期时间戳 (0 表示不过期或未知)
    val customBaseUrl: String? = null,
    val modelAliases: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val totalRequests: Long = 0L,
    val successfulRequests: Long = 0L,
    val lastErrorTimestamp: Long = 0L,
    val lastErrorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 检查全局可用性及特定模型的局部冷却状态
     */
    fun isAvailableForModel(model: String? = null): Boolean {
        val now = System.currentTimeMillis()
        if (status != CredentialStatus.ACTIVE) return false
        if (cooldownUntilTimestamp > now) return false
        if (expiresAt > 0 && expiresAt <= now) return false

        if (!model.isNullOrBlank()) {
            val modelCooldown = modelCooldowns[model]
            if (modelCooldown != null && modelCooldown > now) {
                return false
            }
        }
        return true
    }

    val isAvailable: Boolean
        get() = isAvailableForModel(null)
}
