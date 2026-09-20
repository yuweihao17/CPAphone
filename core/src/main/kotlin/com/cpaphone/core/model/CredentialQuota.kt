package com.cpaphone.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 单个配额时序窗口领域模型（如 5 小时限额、周度限额、月度限额等）
 */
@Serializable
data class QuotaWindow(
    val name: String,
    val windowType: String = "other", // 5h, weekly, monthly, other
    val remainingFraction: Double = 1.0, // 0.0 ~ 1.0
    val resetTimeMs: Long = 0L,
    val formattedCountdown: String = ""
) {
    /** 剩余百分比 (0 ~ 100) */
    val remainingPercent: Int
        get() = (remainingFraction * 100).coerceIn(0.0, 100.0).roundToInt()
}

/**
 * 配额逻辑分组（如 Antigravity 下的 "GEMINI 模型"、"CLAUDE 和 GPT 模型"）
 */
@Serializable
data class QuotaGroup(
    val groupName: String,
    val description: String? = null,
    val windows: List<QuotaWindow> = emptyList()
)

/**
 * 凭据级配额监控聚合领域模型（对齐 CLIProxyAPI CPAMC 配额管理规范）
 */
@Serializable
data class CredentialQuota(
    val credentialId: String,
    val provider: ProviderType,
    val planType: String? = null, // Free, Pro, Plus, Team, Ultra 等
    val resetCredits: Int? = null, // 主动重置限额可用次数（如 Codex）
    val groups: List<QuotaGroup> = emptyList(),
    val statusMessage: String? = null,
    val isSupported: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
