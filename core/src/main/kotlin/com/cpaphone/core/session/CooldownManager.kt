package com.cpaphone.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * 凭据故障冷却熔断管理器 (Cooldown Manager)
 * 支持基于错误状态码与异常模式，将凭据打入动态倒计时冷却池，并在冷却期满后自动恢复。
 */
class CooldownManager(
    private val defaultCooldownMs: Long = 60_000L // 默认 60 秒
) {
    // credentialId -> 恢复时间戳
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    /**
     * 标记凭据进入冷却
     */
    fun triggerCooldown(credentialId: String, durationMs: Long? = null) {
        val duration = durationMs ?: defaultCooldownMs
        cooldownMap[credentialId] = System.currentTimeMillis() + duration
    }

    /**
     * 检查凭据当前是否处于冷却中
     */
    fun isCooling(credentialId: String): Boolean {
        val expireTime = cooldownMap[credentialId] ?: return false
        if (System.currentTimeMillis() >= expireTime) {
            cooldownMap.remove(credentialId)
            return false
        }
        return true
    }

    /**
     * 获取剩余冷却时间（毫秒）
     */
    fun getRemainingCooldownMs(credentialId: String): Long {
        val expireTime = cooldownMap[credentialId] ?: return 0L
        val remaining = expireTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else {
            cooldownMap.remove(credentialId)
            0L
        }
    }

    /**
     * 手动清除冷却，恢复凭据可用
     */
    fun resetCooldown(credentialId: String) {
        cooldownMap.remove(credentialId)
    }

    /**
     * 获取当前处于冷却中的凭据总数
     */
    fun totalCoolingCount(): Int {
        val now = System.currentTimeMillis()
        cooldownMap.entries.removeIf { it.value <= now }
        return cooldownMap.size
    }
}
