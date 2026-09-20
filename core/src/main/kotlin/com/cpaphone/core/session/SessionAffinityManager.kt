package com.cpaphone.core.session

import com.cpaphone.core.model.AuthCredential
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局会话粘性管理器 (Universal Session Affinity)
 * 将客户端的会话特征（Session ID、prompt_cache_key、Trace Session）与分配的凭据持久化绑定在内存中，
 * 最大化利用 Anthropic / OpenAI 的官方 Prompt Cache 机制，显著减少首字延迟与 Token 消耗。
 */
class SessionAffinityManager(
    private val defaultTtlMs: Long = 3600_000L // 默认 1 小时
) {
    private data class SessionBinding(
        val credentialId: String,
        val expireAtTimestamp: Long
    )

    private val bindings = ConcurrentHashMap<String, SessionBinding>()

    /**
     * 根据会话键查询绑定的凭据；若绑定不存在、已过期或绑定的凭据已不可用，返回 null
     */
    fun getAffinity(
        sessionKey: String,
        availableCredentials: List<AuthCredential>
    ): AuthCredential? {
        val now = System.currentTimeMillis()
        val binding = bindings[sessionKey] ?: return null

        if (binding.expireAtTimestamp <= now) {
            bindings.remove(sessionKey)
            return null
        }

        val boundCredential = availableCredentials.find { it.id == binding.credentialId }
        if (boundCredential == null || !boundCredential.isAvailable) {
            // 原绑定凭据不可用或进入冷却，移除失效绑定并促发重新绑定
            bindings.remove(sessionKey)
            return null
        }

        // 延长租约
        bindings[sessionKey] = binding.copy(expireAtTimestamp = now + defaultTtlMs)
        return boundCredential
    }

    /**
     * 记录会话绑定
     */
    fun bind(sessionKey: String, credentialId: String, customTtlMs: Long? = null) {
        val ttl = customTtlMs ?: defaultTtlMs
        bindings[sessionKey] = SessionBinding(
            credentialId = credentialId,
            expireAtTimestamp = System.currentTimeMillis() + ttl
        )
    }

    /**
     * 清除过期的会话绑定，保持内存轻量干净
     */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        bindings.entries.removeIf { it.value.expireAtTimestamp <= now }
    }

    /**
     * 解除特定会话绑定
     */
    fun invalidate(sessionKey: String) {
        bindings.remove(sessionKey)
    }

    /**
     * 获取当前活跃会话数
     */
    fun activeSessionCount(): Int {
        cleanExpired()
        return bindings.size
    }
}
