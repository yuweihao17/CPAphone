package com.cpaphone.engine.coordinator

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.RoutingStrategyType
import com.cpaphone.core.routing.FillFirstLoadBalancer
import com.cpaphone.core.routing.LoadBalancer
import com.cpaphone.core.routing.RoundRobinLoadBalancer
import com.cpaphone.core.routing.SmoothWeightedRoundRobinLoadBalancer
import com.cpaphone.core.session.CooldownManager
import com.cpaphone.core.session.SessionAffinityManager
import com.cpaphone.data.repository.CredentialRepository

/**
 * 核心凭据调度协同器
 * 融合凭据池读取、会话粘性、加权/轮询负载均衡算法与动态故障冷却
 */
class CredentialCoordinator(
    private val credentialRepository: CredentialRepository,
    private val affinityManager: SessionAffinityManager = SessionAffinityManager(),
    val cooldownManager: CooldownManager = CooldownManager()
) {
    private var strategyType: RoutingStrategyType = RoutingStrategyType.WEIGHTED_ROUND_ROBIN
    private var currentBalancer: LoadBalancer = SmoothWeightedRoundRobinLoadBalancer()

    fun updateStrategy(newStrategy: RoutingStrategyType) {
        if (strategyType != newStrategy) {
            strategyType = newStrategy
            currentBalancer = when (newStrategy) {
                RoutingStrategyType.ROUND_ROBIN -> RoundRobinLoadBalancer()
                RoutingStrategyType.WEIGHTED_ROUND_ROBIN -> SmoothWeightedRoundRobinLoadBalancer()
                RoutingStrategyType.FILL_FIRST -> FillFirstLoadBalancer()
            }
        }
    }

    /**
     * 为一次新请求挑选可用凭据
     * @param requestedModel 客户端请求的模型名称
     * @param sessionKey 可选的会话标识（如 X-Claude-Code-Session-Id 或 session_id）
     */
    suspend fun acquireCredential(
        requestedModel: String,
        sessionKey: String? = null
    ): Pair<AuthCredential, String>? {
        val allCredentials = credentialRepository.getAllCredentials()
        // 过滤可用且未处于冷却中的凭据
        val available = allCredentials.filter { cred ->
            cred.isAvailable && !cooldownManager.isCooling(cred.id)
        }
        if (available.isEmpty()) return null

        // 1. 优先检查会话粘性
        if (!sessionKey.isNullOrBlank()) {
            val bound = affinityManager.getAffinity(sessionKey, available)
            if (bound != null) {
                val secretKey = credentialRepository.getSecretKey(bound.id) ?: ""
                return Pair(bound, secretKey)
            }
        }

        // 2. 根据模型别名或提供商进行匹配筛选
        val matched = available.filter { cred ->
            // 如果凭据显式配置了该模型别名，或者属于通用兼容提供商
            cred.modelAliases.containsKey(requestedModel) ||
                    isProviderMatchingModel(cred.provider, requestedModel) ||
                    cred.provider == ProviderType.OPENAI_COMPATIBLE
        }.ifEmpty { available } // 若无专门匹配，则在全体可用凭据中调度

        // 3. 负载均衡策略选择
        val selected = currentBalancer.select(matched) ?: return null
        val secretKey = credentialRepository.getSecretKey(selected.id) ?: ""

        // 4. 若传入会话，建立绑定关系
        if (!sessionKey.isNullOrBlank()) {
            affinityManager.bind(sessionKey, selected.id)
        }

        return Pair(selected, secretKey)
    }

    /**
     * 当请求遭遇 403/429/5xx 时触发自动熔断冷却
     */
    suspend fun reportFailure(credentialId: String, errorMessage: String, statusCode: Int) {
        // 限流 429 冷却 60 秒，服务器故障 503 冷却 30 秒，鉴权错误 401/403 冷却 120 秒
        val cooldownMs = when (statusCode) {
            429 -> 60_000L
            401, 403 -> 120_000L
            500, 502, 503, 504 -> 30_000L
            else -> 45_000L
        }
        cooldownManager.triggerCooldown(credentialId, cooldownMs)
        credentialRepository.recordError(credentialId, "[$statusCode] $errorMessage")
        credentialRepository.recordRequestMetrics(credentialId, success = false)
    }

    /**
     * 记录请求成功
     */
    suspend fun reportSuccess(credentialId: String) {
        credentialRepository.recordRequestMetrics(credentialId, success = true)
    }

    private fun isProviderMatchingModel(provider: ProviderType, model: String): Boolean {
        val lower = model.lowercase()
        return when (provider) {
            ProviderType.CLAUDE -> lower.contains("claude")
            ProviderType.OPENAI_CODEX -> lower.contains("gpt") || lower.contains("o1") || lower.contains("o3") || lower.contains("codex")
            ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> lower.contains("gemini")
            ProviderType.XAI -> lower.contains("grok")
            ProviderType.KIMI -> lower.contains("kimi") || lower.contains("moonshot")
            else -> false
        }
    }
}
