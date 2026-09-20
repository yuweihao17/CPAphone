package com.cpaphone.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * 凭据故障冷却熔断管理器 (Cooldown Manager)
 * 支持全局账号级冷却以及细粒度的单模型维度局部冷却隔离 (Per-Model Cooldown)，
 * 避免单个前沿大模型限流导致该账号的其他健康模型（如 haiku / mini）被误熔断。
 */
class CooldownManager(
    private val defaultCooldownMs: Long = 60_000L // 默认 60 秒
) {
    // credentialId -> 全局恢复时间戳
    private val globalCooldownMap = ConcurrentHashMap<String, Long>()

    // "credentialId#modelName" -> 局部模型恢复时间戳
    private val modelCooldownMap = ConcurrentHashMap<String, Long>()

    /**
     * 标记凭据进入全局冷却（例如整个 API Key 无效、欠费或 401/403）
     */
    fun triggerCooldown(credentialId: String, durationMs: Long? = null) {
        val duration = durationMs ?: defaultCooldownMs
        globalCooldownMap[credentialId] = System.currentTimeMillis() + duration
    }

    /**
     * 标记特定凭据下的特定模型进入局部冷却（例如该模型遇到 429 配额用尽）
     */
    fun triggerModelCooldown(credentialId: String, model: String, durationMs: Long? = null) {
        val duration = durationMs ?: defaultCooldownMs
        val key = makeModelKey(credentialId, model)
        modelCooldownMap[key] = System.currentTimeMillis() + duration
    }

    /**
     * 检查凭据在特定模型下是否处于冷却中（全局或局部冷却中任意一个生效则返回 true）
     */
    fun isCooling(credentialId: String, model: String? = null): Boolean {
        val now = System.currentTimeMillis()

        // 1. 检查全局冷却
        val globalExpire = globalCooldownMap[credentialId]
        if (globalExpire != null) {
            if (now < globalExpire) {
                return true
            } else {
                globalCooldownMap.remove(credentialId)
            }
        }

        // 2. 检查局部单模型冷却
        if (!model.isNullOrBlank()) {
            val key = makeModelKey(credentialId, model)
            val modelExpire = modelCooldownMap[key]
            if (modelExpire != null) {
                if (now < modelExpire) {
                    return true
                } else {
                    modelCooldownMap.remove(key)
                }
            }
        }

        return false
    }

    /**
     * 获取特定凭据与模型的剩余冷却时间（毫秒）
     */
    fun getRemainingCooldownMs(credentialId: String, model: String? = null): Long {
        val now = System.currentTimeMillis()
        var remaining = 0L

        val globalExpire = globalCooldownMap[credentialId]
        if (globalExpire != null && globalExpire > now) {
            remaining = (globalExpire - now).coerceAtLeast(remaining)
        }

        if (!model.isNullOrBlank()) {
            val key = makeModelKey(credentialId, model)
            val modelExpire = modelCooldownMap[key]
            if (modelExpire != null && modelExpire > now) {
                remaining = (modelExpire - now).coerceAtLeast(remaining)
            }
        }

        return remaining
    }

    /**
     * 手动清除特定凭据的全部冷却（包括全局和局部模型）
     */
    fun resetCooldown(credentialId: String) {
        globalCooldownMap.remove(credentialId)
        val prefix = "$credentialId#"
        modelCooldownMap.keys.removeIf { it.startsWith(prefix) }
    }

    /**
     * 获取当前处于全局冷却中的凭据总数
     */
    fun totalCoolingCount(): Int {
        val now = System.currentTimeMillis()
        globalCooldownMap.entries.removeIf { it.value <= now }
        modelCooldownMap.entries.removeIf { it.value <= now }
        return globalCooldownMap.size
    }

    private fun makeModelKey(credentialId: String, model: String): String {
        return "$credentialId#$model"
    }
}
