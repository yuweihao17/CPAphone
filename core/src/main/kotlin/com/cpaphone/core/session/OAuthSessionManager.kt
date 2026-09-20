package com.cpaphone.core.session

import com.cpaphone.core.model.ProviderType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class OAuthFlowStatus {
    WAIT,    // 正在等待用户浏览器授权
    OK,      // 授权成功并已获取 Token
    ERROR    // 授权失败或超时
}

data class OAuthSession(
    val state: String,
    val provider: ProviderType,
    var status: OAuthFlowStatus = OAuthFlowStatus.WAIT,
    var authCode: String? = null,
    var errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 600_000L // 10 分钟有效
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt
}

/**
 * 移动端 OAuth 会话生命周期与轮询管理器
 * 负责生成厂商授权 URL、维护 state 会话池、响应 get-auth-status 轮询与回调解析
 */
class OAuthSessionManager {

    private val sessions = ConcurrentHashMap<String, OAuthSession>()

    /**
     * 发起新的 OAuth 会话
     */
    fun startSession(provider: ProviderType): Pair<String, String> {
        cleanExpired()
        val state = UUID.randomUUID().toString().replace("-", "")
        val session = OAuthSession(state = state, provider = provider)
        sessions[state] = session

        val authUrl = when (provider) {
            ProviderType.CLAUDE -> "https://claude.ai/oauth/authorize?client_id=claude-code&response_type=code&state=$state"
            ProviderType.OPENAI_CODEX -> "https://auth0.openai.com/authorize?client_id=codex&response_type=code&state=$state"
            ProviderType.ANTIGRAVITY -> "https://accounts.google.com/o/oauth2/v2/auth?client_id=antigravity&response_type=code&state=$state"
            ProviderType.KIMI -> "https://kimi.moonshot.cn/oauth/authorize?client_id=kimi-cli&state=$state"
            ProviderType.XAI -> "https://x.ai/oauth/authorize?client_id=grok-cli&state=$state"
            else -> "https://oauth.example.com/authorize?state=$state"
        }

        return Pair(state, authUrl)
    }

    /**
     * 轮询查询 OAuth 状态
     */
    fun pollStatus(state: String): OAuthSession? {
        val session = sessions[state] ?: return null
        if (session.isExpired) {
            session.status = OAuthFlowStatus.ERROR
            session.errorMessage = "OAuth session expired"
            sessions.remove(state)
        }
        return session
    }

    /**
     * 接收并处理回调 Code
     */
    fun handleCallback(state: String, code: String): Boolean {
        val session = sessions[state] ?: return false
        if (session.isExpired) return false

        session.authCode = code
        session.status = OAuthFlowStatus.OK
        return true
    }

    /**
     * 取消进行中的会话
     */
    fun cancelSession(state: String): Boolean {
        return sessions.remove(state) != null
    }

    fun cleanExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
    }
}
