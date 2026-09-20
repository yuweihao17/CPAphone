package com.cpaphone.engine.oauth

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.oauth.OAuthFlowKind
import com.cpaphone.core.session.OAuthSession
import com.cpaphone.core.session.OAuthSessionManager
import com.cpaphone.data.repository.CredentialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 发起登录的结果（CODE 流返回授权链接；DEVICE 流返回验证页与设备码） */
data class LoginStartResult(
    val state: String,
    val flowKind: OAuthFlowKind,
    val authorizeUrl: String? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null
)

/**
 * OAuth 一键登录编排器
 * 串联：会话生成 → 本地回调监听/设备码轮询 → token 兑换 → 凭据加密落库
 * 全局唯一实例（CpaApplication 组装），同时服务 UI 层与管理平面端点
 */
class OAuthLoginManager(
    private val sessionManager: OAuthSessionManager,
    private val credentialRepository: CredentialRepository,
    private val tokenClient: OAuthTokenClient = OAuthTokenClient()
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackServer = OAuthCallbackServer(backgroundScope)

    /** 会话状态流（供 UI 观察渲染登录进度） */
    val sessionsFlow: StateFlow<List<OAuthSession>>
        get() = sessionManager.sessionsFlow

    /**
     * 发起某提供商的登录流程
     */
    suspend fun startLogin(provider: ProviderType): LoginStartResult {
        val spec = PROVIDER_SPECS[provider]
            ?: throw IllegalArgumentException("Provider $provider does not support OAuth login")

        // 单飞保护：同提供商存在进行中会话时先取消
        sessionManager.findActiveByProvider(provider)?.let { cancelLogin(it.state) }

        return when (spec.flowKind) {
            OAuthFlowKind.CODE_PKCE, OAuthFlowKind.CODE_SECRET -> {
                val session = sessionManager.startSession(provider, spec)
                callbackServer.start(spec.callbackPort) { code, state, error ->
                    if (state == null || state == session.state) {
                        backgroundScope.launch {
                            handleCallbackCode(session.state, code, error)
                        }
                    }
                }
                // 超时兜底：5 分钟未完成授权自动结束会话并释放回调端口
                backgroundScope.launch {
                    kotlinx.coroutines.delay(5 * 60_000L)
                    val pending = sessionManager.peek(session.state)
                    if (pending != null && pending.status == com.cpaphone.core.session.OAuthFlowStatus.WAIT) {
                        sessionManager.failSession(session.state, "授权超时，请重新发起登录")
                        callbackServer.stop()
                    }
                }
                LoginStartResult(
                    state = session.state,
                    flowKind = spec.flowKind,
                    authorizeUrl = session.authorizeUrl
                )
            }
            OAuthFlowKind.DEVICE_CODE -> {
                val device = tokenClient.requestDeviceCode(provider, spec)
                val state = "${spec.statePrefix ?: provider.identifier}-${System.nanoTime()}"
                sessionManager.startDeviceSession(
                    provider = provider,
                    state = state,
                    deviceCode = device.deviceCode,
                    verificationUrl = device.verificationUrl,
                    userCode = device.userCode
                )
                backgroundScope.launch {
                    try {
                        val tokens = tokenClient.pollDeviceToken(provider, spec, device.deviceCode, device.intervalMs)
                        finalizeLogin(state, provider, tokens)
                    } catch (e: Exception) {
                        sessionManager.failSession(state, e.message ?: "device flow failed")
                    }
                }
                LoginStartResult(
                    state = state,
                    flowKind = spec.flowKind,
                    verificationUrl = device.verificationUrl,
                    userCode = device.userCode
                )
            }
        }
    }

    /**
     * 处理回调授权码（本地回调服务器 / 管理端点 POST /oauth-callback 共用）
     * 兑换成功后落库并标记会话完成
     */
    suspend fun handleCallbackCode(state: String, code: String?, error: String?) {
        if (!error.isNullOrBlank()) {
            sessionManager.failSession(state, error)
            callbackServer.stop()
            return
        }
        if (code.isNullOrBlank()) return
        val session = sessionManager.peek(state) ?: return
        val provider = session.provider
        val spec = PROVIDER_SPECS[provider] ?: return
        val verifier = sessionManager.takeVerifier(state)
        try {
            val tokens = tokenClient.exchangeCode(provider, spec, code, verifier)
            finalizeLogin(state, provider, tokens)
        } catch (e: Exception) {
            sessionManager.failSession(state, e.message ?: "token exchange failed")
        } finally {
            callbackServer.stop()
        }
    }

    /**
     * 取消进行中的登录（停回调监听、移除会话）
     */
    fun cancelLogin(state: String) {
        sessionManager.cancelSession(state)
        callbackServer.stop()
    }

    /**
     * 手动提交回调链接（浏览器被代理拦截导致 localhost 回跳超时时的兜底通道）
     * 用户从浏览器地址栏复制完整回跳 URL（含 code/state），粘贴后在此解析并完成兑换
     * 对齐 CLIProxyAPI 管理端点 /v0/management/oauth-callback 的手动 redirect_url 模式
     *
     * @return 是否成功受理（找到会话且参数合法）
     */
    suspend fun handleManualCallback(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false

        val (code, state, error) = try {
            val uri = android.net.Uri.parse(trimmed)
            Triple(
                uri.getQueryParameter("code"),
                uri.getQueryParameter("state"),
                uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
            )
        } catch (_: Exception) {
            return false
        }
        if (state.isNullOrBlank()) return false

        val session = sessionManager.peek(state) ?: return false
        if (session.status != com.cpaphone.core.session.OAuthFlowStatus.WAIT) return false

        handleCallbackCode(state, code, error)
        return true
    }

    /**
     * 用 refresh_token 刷新某凭据的 access_token（供 CredentialCoordinator 自动续期）
     * @return 是否刷新成功
     */
    suspend fun refreshCredential(credential: AuthCredential): Boolean {
        val spec = PROVIDER_SPECS[credential.provider] ?: return false
        val refreshToken = credentialRepository.getRefreshToken(credential.id) ?: return false
        return try {
            val tokens = tokenClient.refresh(credential.provider, spec, refreshToken)
            val expiresAt = tokens.expiresIn?.let { System.currentTimeMillis() + it * 1000 - 60_000L } ?: credential.expiresAt
            // secret 由 repository 覆盖，refresh_token 仅在响应携带新值时轮换
            credentialRepository.saveCredential(credential.copy(expiresAt = expiresAt), tokens.accessToken)
            tokens.refreshToken?.let { credentialRepository.saveRefreshToken(credential.id, it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 凭据落库：别名优先使用账号 email 本地部分，兜底按提供商自动编号
     */
    private suspend fun finalizeLogin(state: String, provider: ProviderType, tokens: OAuthTokens) {
        val email = tokens.email ?: tokenClient.fetchEmail(provider, tokens.accessToken, tokens.idToken)
        val existing = credentialRepository.getAllCredentials()
        val alias = email?.takeIf { it.isNotBlank() }
            ?.let { "${aliasPrefix(provider)}-${it.substringBefore("@")}" }
            ?: "${aliasPrefix(provider)}-${existing.count { it.provider == provider } + 1}"

        val id = UUID.randomUUID().toString().substring(0, 8)
        val expiresAt = tokens.expiresIn?.let { System.currentTimeMillis() + it * 1000 - 60_000L } ?: 0L
        val credential = AuthCredential(
            id = id,
            alias = alias,
            provider = provider,
            authType = AuthType.OAUTH,
            weight = 1,
            expiresAt = expiresAt
        )
        credentialRepository.saveCredential(credential, tokens.accessToken)
        tokens.refreshToken?.let { credentialRepository.saveRefreshToken(id, it) }
        sessionManager.completeSession(state, alias, email)
    }

    /** 与 UI 层预设一致的服务商别名前缀 */
    private fun aliasPrefix(provider: ProviderType): String = when (provider) {
        ProviderType.CLAUDE -> "Claude"
        ProviderType.OPENAI_CODEX -> "Codex"
        ProviderType.GEMINI -> "Gemini"
        ProviderType.ANTIGRAVITY -> "Antigravity"
        ProviderType.VERTEX_AI -> "Vertex"
        ProviderType.XAI -> "Grok"
        ProviderType.KIMI -> "Kimi"
        ProviderType.OPENAI_COMPATIBLE -> "Compat"
    }
}
