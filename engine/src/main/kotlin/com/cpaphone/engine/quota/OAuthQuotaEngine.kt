package com.cpaphone.engine.quota

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.CredentialQuota
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.QuotaGroup
import com.cpaphone.core.model.QuotaWindow
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.engine.client.AntigravityClient
import com.cpaphone.engine.oauth.OAuthTokenClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * 各大主流 AI 平台 OAuth 剩余额度与配额时序窗口采集引擎
 * 精确对齐 CLIProxyAPI CPAMC（管理控制台）配额规范：
 * - Google Antigravity: loadCodeAssist 套餐 + retrieveUserQuotaSummary 模型分组配额
 * - OpenAI Codex: /backend-api/wham/usage 5小时/月度限额与主动重置次数
 * - Anthropic Claude: /api/oauth/usage 5小时与周度限额
 * - Moonshot Kimi: /coding/v1/usages
 * - xAI Grok: /v1/billing credits
 * 支持 401 令牌过期自动触发 OAuthTokenClient.refresh 续期
 */
class OAuthQuotaEngine(
    private val antigravityClient: AntigravityClient = AntigravityClient(),
    private val oauthTokenClient: OAuthTokenClient = OAuthTokenClient()
) {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 拉取指定凭据的最新配额信息
     * @param onTokenRefreshed 当发生 401 自动续期时回调新 token 以便持久化回写
     */
    suspend fun fetchQuota(
        credential: AuthCredential,
        accessToken: String,
        refreshToken: String? = null,
        onTokenRefreshed: (suspend (newAccessToken: String, newRefreshToken: String?) -> Unit)? = null
    ): CredentialQuota = withContext(Dispatchers.IO) {
        var currentToken = accessToken
        var currentRefresh = refreshToken

        suspend fun executeWithRetry(action: suspend (String) -> CredentialQuota): CredentialQuota {
            return try {
                action(currentToken)
            } catch (e: QuotaAuthExpiredException) {
                if (!currentRefresh.isNullOrBlank()) {
                    val spec = PROVIDER_SPECS[credential.provider]
                    if (spec != null) {
                        try {
                            val refreshed = oauthTokenClient.refresh(credential.provider, spec, currentRefresh!!)
                            currentToken = refreshed.accessToken
                            refreshed.refreshToken?.let { currentRefresh = it }
                            onTokenRefreshed?.invoke(currentToken, currentRefresh)
                            return action(currentToken)
                        } catch (refreshErr: Exception) {
                            return CredentialQuota(
                                credentialId = credential.id,
                                provider = credential.provider,
                                statusMessage = "令牌已过期且刷新失败: ${refreshErr.message?.take(80)}",
                                isSupported = true
                            )
                        }
                    }
                }
                CredentialQuota(
                    credentialId = credential.id,
                    provider = credential.provider,
                    statusMessage = "凭据已失效，请重新授权",
                    isSupported = true
                )
            } catch (e: Exception) {
                CredentialQuota(
                    credentialId = credential.id,
                    provider = credential.provider,
                    statusMessage = "获取配额失败: ${e.message?.take(80)}",
                    isSupported = true
                )
            }
        }

        when (credential.provider) {
            ProviderType.ANTIGRAVITY -> executeWithRetry { token -> fetchAntigravityQuota(credential, token) }
            ProviderType.OPENAI_CODEX -> executeWithRetry { token -> fetchCodexQuota(credential, token) }
            ProviderType.CLAUDE -> executeWithRetry { token -> fetchClaudeQuota(credential, token) }
            ProviderType.KIMI -> executeWithRetry { token -> fetchKimiQuota(credential, token) }
            ProviderType.XAI -> executeWithRetry { token -> fetchXaiQuota(credential, token) }
            else -> CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "当前提供商暂无官方配额接口",
                isSupported = false
            )
        }
    }

    // =========================================================================
    // 1. Google Antigravity 配额获取与计算
    // =========================================================================

    private suspend fun fetchAntigravityQuota(credential: AuthCredential, token: String): CredentialQuota {
        // 1. 获取项目 ID 与套餐标识
        val projectId = antigravityClient.ensureProjectId(credential.id, token)

        // 2. 调用 retrieveUserQuotaSummary 获取模型分组配额窗口
        val primaryUrl = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary"
        val fallbackUrl = "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary"
        val requestBody = """{"project":"$projectId"}"""

        var response = client.post(primaryUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, "antigravity/hub/2.9.1 darwin/arm64")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw QuotaAuthExpiredException("Antigravity 401 Unauthorized")
        }

        if (!response.status.isSuccess()) {
            response = client.post(fallbackUrl) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.UserAgent, "antigravity/hub/2.9.1 darwin/arm64")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "HTTP ${response.status.value}: ${text.take(80)}",
                isSupported = true
            )
        }

        return parseAntigravityQuotaResponse(credential.id, text)
    }

    internal fun parseAntigravityQuotaResponse(credentialId: String, jsonText: String): CredentialQuota {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val groupsArray = root["groups"]?.jsonArray ?: JsonArray(emptyList())

        val quotaGroups = mutableListOf<QuotaGroup>()
        val now = System.currentTimeMillis()

        groupsArray.forEach { groupElem ->
            val groupObj = groupElem.jsonObject
            val displayName = groupObj["displayName"]?.jsonPrimitive?.content ?: "模型组"
            val description = groupObj["description"]?.jsonPrimitive?.content

            val buckets = groupObj["buckets"]?.jsonArray ?: JsonArray(emptyList())
            val windows = mutableListOf<QuotaWindow>()

            buckets.forEach { bucketElem ->
                val bucketObj = bucketElem.jsonObject
                val bucketName = bucketObj["displayName"]?.jsonPrimitive?.content ?: "限额"
                val fraction = bucketObj["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                val resetTimeStr = bucketObj["resetTime"]?.jsonPrimitive?.content ?: ""
                val resetMs = parseIsoTimestamp(resetTimeStr)

                val countdown = formatRelativeCountdown(resetMs, now)
                val type = when {
                    bucketName.contains("5", ignoreCase = true) || bucketName.contains("hour", ignoreCase = true) -> "5h"
                    bucketName.contains("week", ignoreCase = true) -> "weekly"
                    bucketName.contains("day", ignoreCase = true) -> "daily"
                    else -> "other"
                }

                windows.add(
                    QuotaWindow(
                        name = bucketName,
                        windowType = type,
                        remainingFraction = fraction.coerceIn(0.0, 1.0),
                        resetTimeMs = resetMs,
                        formattedCountdown = countdown
                    )
                )
            }

            quotaGroups.add(
                QuotaGroup(
                    groupName = displayName,
                    description = description,
                    windows = windows
                )
            )
        }

        // 推导套餐类型（若无则默认 Pro）
        val planType = if (jsonText.contains("ultra", ignoreCase = true)) "Ultra" else "Pro"

        return CredentialQuota(
            credentialId = credentialId,
            provider = ProviderType.ANTIGRAVITY,
            planType = planType,
            groups = quotaGroups,
            isSupported = true,
            updatedAt = now
        )
    }

    // =========================================================================
    // 2. OpenAI Codex 配额获取与计算
    // =========================================================================

    private suspend fun fetchCodexQuota(credential: AuthCredential, token: String): CredentialQuota {
        val response = client.get("https://chatgpt.com/backend-api/wham/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, "codex-tui/0.149.1 (Mac OS 26.5.2; arm64) iTerm.app/3.6.11 (codex-tui; 0.149.1)")
            header(HttpHeaders.Accept, "application/json")
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw QuotaAuthExpiredException("Codex 401 Unauthorized")
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "HTTP ${response.status.value}: ${text.take(80)}",
                isSupported = true
            )
        }

        return parseCodexQuotaResponse(credential.id, text)
    }

    internal fun parseCodexQuotaResponse(credentialId: String, jsonText: String): CredentialQuota {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val planRaw = root["plan_type"]?.jsonPrimitive?.content ?: "free"
        val planType = planRaw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        val resetCredits = root["rate_limit_reset_credits"]?.jsonObject?.get("available_count")?.jsonPrimitive?.intOrNull

        val now = System.currentTimeMillis()
        val rateLimitObj = root["rate_limit"]?.jsonObject

        val windows = mutableListOf<QuotaWindow>()
        if (rateLimitObj != null) {
            // primary_window: 5小时限额
            rateLimitObj["primary_window"]?.jsonObject?.let { primary ->
                val used = primary["used_percent"]?.jsonPrimitive?.intOrNull ?: 0
                val remainingFraction = max(0.0, (100 - used) / 100.0)
                val resetSec = primary["reset_after_seconds"]?.jsonPrimitive?.longOrNull
                val resetAt = primary["reset_at"]?.jsonPrimitive?.longOrNull
                val resetMs = when {
                    resetAt != null && resetAt > 0 -> resetAt * 1000
                    resetSec != null -> now + resetSec * 1000
                    else -> 0L
                }
                windows.add(
                    QuotaWindow(
                        name = "5小时限额",
                        windowType = "5h",
                        remainingFraction = remainingFraction,
                        resetTimeMs = resetMs,
                        formattedCountdown = formatRelativeCountdown(resetMs, now)
                    )
                )
            }

            // secondary_window: 周或月度限额
            rateLimitObj["secondary_window"]?.jsonObject?.let { secondary ->
                val used = secondary["used_percent"]?.jsonPrimitive?.intOrNull ?: 0
                val remainingFraction = max(0.0, (100 - used) / 100.0)
                val windowSeconds = secondary["limit_window_seconds"]?.jsonPrimitive?.longOrNull ?: 0L
                val isMonthly = windowSeconds >= 2419200
                val name = if (isMonthly) "月度限额" else "周限额"
                val resetSec = secondary["reset_after_seconds"]?.jsonPrimitive?.longOrNull
                val resetAt = secondary["reset_at"]?.jsonPrimitive?.longOrNull
                val resetMs = when {
                    resetAt != null && resetAt > 0 -> resetAt * 1000
                    resetSec != null -> now + resetSec * 1000
                    else -> 0L
                }
                windows.add(
                    QuotaWindow(
                        name = name,
                        windowType = if (isMonthly) "monthly" else "weekly",
                        remainingFraction = remainingFraction,
                        resetTimeMs = resetMs,
                        formattedCountdown = formatDateWithRelativeCountdown(resetMs, now)
                    )
                )
            }
        }

        val groups = if (windows.isNotEmpty()) {
            listOf(QuotaGroup(groupName = "模型调用限额", windows = windows))
        } else {
            emptyList()
        }

        return CredentialQuota(
            credentialId = credentialId,
            provider = ProviderType.OPENAI_CODEX,
            planType = planType,
            resetCredits = resetCredits,
            groups = groups,
            isSupported = true,
            updatedAt = now
        )
    }

    // =========================================================================
    // 3. Anthropic Claude 配额获取
    // =========================================================================

    private suspend fun fetchClaudeQuota(credential: AuthCredential, token: String): CredentialQuota {
        val response = client.get("https://api.anthropic.com/api/oauth/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("anthropic-beta", "oauth-2025-04-20")
            contentType(ContentType.Application.Json)
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw QuotaAuthExpiredException("Claude 401 Unauthorized")
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "HTTP ${response.status.value}: ${text.take(80)}",
                isSupported = true
            )
        }

        return parseClaudeQuotaResponse(credential.id, text)
    }

    internal fun parseClaudeQuotaResponse(credentialId: String, jsonText: String): CredentialQuota {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val now = System.currentTimeMillis()
        val windows = mutableListOf<QuotaWindow>()

        root["five_hour"]?.jsonObject?.let { fh ->
            val util = fh["utilization"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val resetStr = fh["reset"]?.jsonPrimitive?.content ?: ""
            val resetMs = parseIsoTimestamp(resetStr)
            windows.add(
                QuotaWindow(
                    name = "Five Hour Limit",
                    windowType = "5h",
                    remainingFraction = max(0.0, 1.0 - util),
                    resetTimeMs = resetMs,
                    formattedCountdown = formatRelativeCountdown(resetMs, now)
                )
            )
        }

        root["seven_day"]?.jsonObject?.let { sd ->
            val util = sd["utilization"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val resetStr = sd["reset"]?.jsonPrimitive?.content ?: ""
            val resetMs = parseIsoTimestamp(resetStr)
            windows.add(
                QuotaWindow(
                    name = "Weekly Limit",
                    windowType = "weekly",
                    remainingFraction = max(0.0, 1.0 - util),
                    resetTimeMs = resetMs,
                    formattedCountdown = formatRelativeCountdown(resetMs, now)
                )
            )
        }

        return CredentialQuota(
            credentialId = credentialId,
            provider = ProviderType.CLAUDE,
            planType = "OAuth Pro",
            groups = listOf(QuotaGroup(groupName = "速率与周期限额", windows = windows)),
            isSupported = true,
            updatedAt = now
        )
    }

    // =========================================================================
    // 4. Moonshot Kimi 配额获取
    // =========================================================================

    private suspend fun fetchKimiQuota(credential: AuthCredential, token: String): CredentialQuota {
        val response = client.get("https://api.kimi.com/coding/v1/usages") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw QuotaAuthExpiredException("Kimi 401 Unauthorized")
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "HTTP ${response.status.value}: ${text.take(80)}",
                isSupported = true
            )
        }

        val root = json.parseToJsonElement(text).jsonObject
        val limits = root["limits"]?.jsonArray ?: JsonArray(emptyList())
        val windows = mutableListOf<QuotaWindow>()
        val now = System.currentTimeMillis()

        limits.forEach { limitElem ->
            val obj = limitElem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "限额窗口"
            val remaining = obj["remaining"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            val total = obj["limit"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            val fraction = if (total > 0) remaining / total else 1.0
            val resetAt = obj["reset_at"]?.jsonPrimitive?.longOrNull ?: 0L

            windows.add(
                QuotaWindow(
                    name = name,
                    remainingFraction = fraction.coerceIn(0.0, 1.0),
                    resetTimeMs = resetAt * 1000,
                    formattedCountdown = formatRelativeCountdown(resetAt * 1000, now)
                )
            )
        }

        return CredentialQuota(
            credentialId = credential.id,
            provider = ProviderType.KIMI,
            planType = "Coding Plan",
            groups = listOf(QuotaGroup(groupName = "调用额度", windows = windows)),
            isSupported = true,
            updatedAt = now
        )
    }

    // =========================================================================
    // 5. xAI Grok 配额获取
    // =========================================================================

    private suspend fun fetchXaiQuota(credential: AuthCredential, token: String): CredentialQuota {
        val response = client.get("https://cli-chat-proxy.grok.com/v1/billing?format=credits") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("x-xai-token-auth", "xai-grok-cli")
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw QuotaAuthExpiredException("xAI 401 Unauthorized")
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return CredentialQuota(
                credentialId = credential.id,
                provider = credential.provider,
                statusMessage = "HTTP ${response.status.value}: ${text.take(80)}",
                isSupported = true
            )
        }

        val root = json.parseToJsonElement(text).jsonObject
        val usedPercent = root["credit_usage_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val remainingFraction = max(0.0, (100.0 - usedPercent) / 100.0)

        val window = QuotaWindow(
            name = "月度用量额度",
            windowType = "monthly",
            remainingFraction = remainingFraction,
            formattedCountdown = "自动结转"
        )

        return CredentialQuota(
            credentialId = credential.id,
            provider = ProviderType.XAI,
            planType = "Grok CLI",
            groups = listOf(QuotaGroup(groupName = "账户额度", windows = listOf(window))),
            isSupported = true,
            updatedAt = System.currentTimeMillis()
        )
    }

    // =========================================================================
    // 辅助格式化函数
    // =========================================================================

    private fun parseIsoTimestamp(isoStr: String): Long {
        if (isoStr.isBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(isoStr.take(19))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatRelativeCountdown(targetMs: Long, currentMs: Long): String {
        if (targetMs <= 0) return ""
        val diffMs = targetMs - currentMs
        if (diffMs <= 0) return "额度可用"

        val totalMinutes = max(1L, diffMs / 60_000L)
        val days = totalMinutes / 1440
        val hours = (totalMinutes % 1440) / 60
        val minutes = totalMinutes % 60

        return when {
            days > 0 -> "${days}天 ${hours}小时 后刷新"
            hours > 0 -> "${hours}小时 ${minutes}分钟 后刷新"
            else -> "${minutes}分钟后刷新"
        }
    }

    private fun formatDateWithRelativeCountdown(targetMs: Long, currentMs: Long): String {
        if (targetMs <= 0) return ""
        val dateStr = try {
            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(targetMs))
        } catch (_: Exception) {
            ""
        }
        val diffMs = targetMs - currentMs
        if (diffMs <= 0) return "$dateStr · 额度可用"

        val days = diffMs / 86400_000L
        return if (days > 0) {
            "$dateStr · ${days}天后"
        } else {
            val hours = max(1L, diffMs / 3600_000L)
            "$dateStr · ${hours}小时后"
        }
    }
}

class QuotaAuthExpiredException(message: String) : Exception(message)
