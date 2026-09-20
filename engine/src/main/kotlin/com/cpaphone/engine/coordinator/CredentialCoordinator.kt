package com.cpaphone.engine.coordinator

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.RoutingStrategyType
import com.cpaphone.core.routing.FillFirstLoadBalancer
import com.cpaphone.core.routing.LoadBalancer
import com.cpaphone.core.routing.RoundRobinLoadBalancer
import com.cpaphone.core.routing.SmoothWeightedRoundRobinLoadBalancer
import com.cpaphone.core.session.CooldownManager
import com.cpaphone.core.session.SessionAffinityManager
import com.cpaphone.data.repository.CredentialRepository
import com.cpaphone.engine.oauth.OAuthLoginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 核心凭据调度协同器
 * 融合凭据池读取、会话粘性、加权/轮询负载均衡算法、单模型局部冷却与 OAuth 时效管控
 */
class CredentialCoordinator(
    private val credentialRepository: CredentialRepository,
    private val oauthLoginManager: OAuthLoginManager? = null,
    private val affinityManager: SessionAffinityManager = SessionAffinityManager(),
    val cooldownManager: CooldownManager = CooldownManager()
) {
    private var strategyType: RoutingStrategyType = RoutingStrategyType.WEIGHTED_ROUND_ROBIN
    private var currentBalancer: LoadBalancer = SmoothWeightedRoundRobinLoadBalancer()
    private val backgroundScope = CoroutineScope(Dispatchers.IO)
    private val oauthRefreshMutex = Mutex()

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
     * 为一次新请求挑选可用凭据（支持指定模型的局部冷却校验与 OAuth 到期过滤）
     * @param requestedModel 客户端请求的目标模型名称
     * @param sessionKey 可选的会话特征键（用于 Prompt Caching 会话粘性）
     */
    suspend fun acquireCredential(
        requestedModel: String,
        sessionKey: String? = null
    ): Pair<AuthCredential, String>? {
        // 定期清理过期内存会话，防止对象泄漏与膨胀
        affinityManager.cleanExpired()

        val allCredentials = credentialRepository.getAllCredentials()

        // 1. 过滤：可用性判定（包含全局状态、OAuth 到期时间）+ 模型局部冷却隔离
        val available = allCredentials.filter { cred ->
            cred.isAvailableForModel(requestedModel) && !cooldownManager.isCooling(cred.id, requestedModel)
        }
        if (available.isEmpty()) return null

        // 2. 优先检查全局会话粘性
        if (!sessionKey.isNullOrBlank()) {
            val bound = affinityManager.getAffinity(sessionKey, available)
            if (bound != null) {
                val secretKey = ensureFreshSecret(bound)
                return Pair(bound, secretKey)
            }
        }

        // 3. 根据模型别名或厂商协议特征进行智能调度筛选
        val matched = available.filter { cred ->
            cred.modelAliases.containsKey(requestedModel) ||
                    isProviderMatchingModel(cred.provider, requestedModel) ||
                    cred.provider == ProviderType.OPENAI_COMPATIBLE
        }.ifEmpty { available }

        // 4. 执行负载均衡算法选择最优凭据
        val selected = currentBalancer.select(matched) ?: return null
        val secretKey = ensureFreshSecret(selected)

        // 5. 若传入会话，建立绑定关联
        if (!sessionKey.isNullOrBlank()) {
            affinityManager.bind(sessionKey, selected.id)
        }

        return Pair(selected, secretKey)
    }

    /**
     * OAuth 凭据 proactive 续期：过期前 5 分钟内用 refresh_token 刷新，
     * 刷新失败时保留原 token 照常调度（由后续 401 冷却兜底）
     */
    private suspend fun ensureFreshSecret(credential: AuthCredential): String {
        val secret = credentialRepository.getSecretKey(credential.id) ?: ""
        val manager = oauthLoginManager ?: return secret
        if (credential.authType != AuthType.OAUTH) return secret
        if (credential.expiresAt <= 0L || credential.expiresAt - System.currentTimeMillis() > 5 * 60_000L) return secret

        oauthRefreshMutex.withLock {
            // 双重检查：并发请求可能已完成刷新
            val latest = credentialRepository.getCredentialById(credential.id)
            if (latest != null &&
                latest.expiresAt > 0L &&
                latest.expiresAt - System.currentTimeMillis() <= 5 * 60_000L
            ) {
                manager.refreshCredential(latest)
            }
        }
        return credentialRepository.getSecretKey(credential.id) ?: secret
    }

    /**
     * 针对请求失败进行细粒度熔断与冷却
     * 429 或模型不可用时，触发特定模型的局部冷却，避免连带熔断该账号的其他模型；
     * 401/403 等账号鉴权失败时，触发账号全局冷却。
     */
    suspend fun reportFailure(
        credentialId: String,
        errorMessage: String,
        statusCode: Int,
        model: String? = null
    ) {
        val isModelSpecificError = statusCode == 429 || (statusCode in 500..599 && !model.isNullOrBlank())
        val cooldownDuration = when (statusCode) {
            429 -> 60_000L
            401, 403 -> 120_000L
            500, 502, 503, 504 -> 30_000L
            else -> 45_000L
        }

        if (isModelSpecificError && !model.isNullOrBlank()) {
            // 触发单模型局部冷却
            cooldownManager.triggerModelCooldown(credentialId, model, cooldownDuration)
            backgroundScope.launch {
                val expireTimestamp = System.currentTimeMillis() + cooldownDuration
                credentialRepository.updateModelCooldown(credentialId, model, expireTimestamp)
            }
        } else {
            // 触发账号全局冷却
            cooldownManager.triggerCooldown(credentialId, cooldownDuration)
            backgroundScope.launch {
                val expireTimestamp = System.currentTimeMillis() + cooldownDuration
                credentialRepository.updateGlobalCooldown(credentialId, expireTimestamp)
            }
        }

        credentialRepository.recordError(credentialId, "[$statusCode] $errorMessage")
        credentialRepository.recordRequestMetrics(credentialId, success = false)
    }

    /**
     * 记录请求成功与指标上报
     */
    suspend fun reportSuccess(credentialId: String) {
        credentialRepository.recordRequestMetrics(credentialId, success = true)
    }

    private fun isProviderMatchingModel(provider: ProviderType, model: String): Boolean {
        // 优先查静态目录反查（对齐 CLIProxyAPI GetProviderName），未知模型再走特征匹配兜底
        val owners = com.cpaphone.core.model.ModelCatalog.ownersForModel(model)
        if (owners != null) return provider in owners
        return isProviderMatchingByHeuristic(provider, model)
    }

    private fun isProviderMatchingByHeuristic(provider: ProviderType, model: String): Boolean {
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
