package com.cpaphone.core.session

import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.OAuthFlowKind
import com.cpaphone.core.oauth.OAuthProviderSpec
import com.cpaphone.core.oauth.Pkce
import com.cpaphone.core.oauth.generateOAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class OAuthFlowStatus {
    WAIT,    // 正在等待用户浏览器授权或设备码确认
    OK,      // 授权成功并已获取 Token
    ERROR    // 授权失败或超时
}

data class OAuthSession(
    val state: String,
    val provider: ProviderType,
    val kind: OAuthFlowKind,
    val authorizeUrl: String? = null,      // CODE 流：浏览器授权链接
    val verificationUrl: String? = null,   // DEVICE 流：用户验证页
    val userCode: String? = null,          // DEVICE 流：设备确认码
    var authCode: String? = null,
    var status: OAuthFlowStatus = OAuthFlowStatus.WAIT,
    var errorMessage: String? = null,
    var resultAlias: String? = null,       // 登录成功后落库的凭据别名
    var resultEmail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var expiresAt: Long = System.currentTimeMillis() + 600_000L // 10 分钟有效
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt
}

/**
 * 移动端 OAuth 会话生命周期管理器
 * 生成真实 PKCE 授权 URL、维护 state 会话池、响应回调写入与状态轮询
 * 实例必须全局唯一（由 CpaApplication 组装并注入 ManagementRoutes 与 UI）
 */
class OAuthSessionManager {

    private val sessions = ConcurrentHashMap<String, OAuthSession>()
    private val verifiers = ConcurrentHashMap<String, String>()
    private val deviceCodes = ConcurrentHashMap<String, String>()

    private val _sessionsFlow = MutableStateFlow<List<OAuthSession>>(emptyList())
    val sessionsFlow: StateFlow<List<OAuthSession>> = _sessionsFlow.asStateFlow()

    /**
     * 发起 CODE 流会话（PKCE 或 client_secret），生成可用的浏览器授权 URL
     */
    fun startSession(provider: ProviderType, spec: OAuthProviderSpec): OAuthSession {
        cleanExpired()
        val state = generateOAuthState()
        val url: String? = if (spec.flowKind == OAuthFlowKind.CODE_PKCE || spec.flowKind == OAuthFlowKind.CODE_SECRET) {
            val verifier = Pkce.generateVerifier()
            verifiers[state] = verifier
            buildAuthorizeUrl(provider, spec, state, verifier)
        } else {
            null
        }
        val session = OAuthSession(
            state = state,
            provider = provider,
            kind = spec.flowKind,
            authorizeUrl = url
        )
        sessions[state] = session
        publish()
        return session
    }

    /**
     * 发起 DEVICE 流会话（设备码已由 engine 层向提供商申请完毕）
     */
    fun startDeviceSession(
        provider: ProviderType,
        state: String,
        deviceCode: String,
        verificationUrl: String,
        userCode: String?
    ): OAuthSession {
        cleanExpired()
        val session = OAuthSession(
            state = state,
            provider = provider,
            kind = OAuthFlowKind.DEVICE_CODE,
            verificationUrl = verificationUrl,
            userCode = userCode
        )
        sessions[state] = session
        deviceCodes[state] = deviceCode
        publish()
        return session
    }

    private fun buildAuthorizeUrl(
        provider: ProviderType,
        spec: OAuthProviderSpec,
        state: String,
        verifier: String
    ): String {
        val base = spec.authorizeUrl ?: return "https://oauth.example.com/authorize?state=$state"
        val params = buildList {
            add("client_id=${enc(spec.clientId)}")
            add("response_type=code")
            add("redirect_uri=${enc(spec.redirectUri ?: "")}")
            spec.scope?.let { add("scope=${enc(it)}") }
            if (spec.flowKind == OAuthFlowKind.CODE_PKCE) {
                add("code_challenge=${enc(Pkce.generateChallenge(verifier))}")
                add("code_challenge_method=S256")
            }
            add("state=${enc(state)}")
            spec.extraAuthorizeParams.forEach { (k, v) -> add("${enc(k)}=${enc(v)}") }
        }
        return "$base?${params.joinToString("&")}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 取走 PKCE verifier（一次性，取后即焚） */
    fun takeVerifier(state: String): String? = verifiers.remove(state)

    /** 取走设备码（一次性） */
    fun takeDeviceCode(state: String): String? = deviceCodes.remove(state)

    /**
     * 接收回调授权码（本地回调服务器或管理端点调用）
     */
    fun completeWithCode(state: String, code: String?, error: String?): Boolean {
        val session = sessions[state] ?: return false
        if (session.status != OAuthFlowStatus.WAIT) return false
        if (!error.isNullOrBlank()) {
            session.status = OAuthFlowStatus.ERROR
            session.errorMessage = error
            shrinkTtl(session)
            publish()
            return false
        }
        if (code.isNullOrBlank()) return false
        // Claude 的 code 可能携带 "#state" 片段，需拆分
        session.authCode = code.substringBefore("#")
        publish()
        return true
    }

    /** 标记会话失败（兑换异常、设备码超时等） */
    fun failSession(state: String, message: String) {
        val session = sessions[state] ?: return
        session.status = OAuthFlowStatus.ERROR
        session.errorMessage = message
        shrinkTtl(session)
        publish()
    }

    /** 标记会话成功（凭据已落库） */
    fun completeSession(state: String, alias: String, email: String?) {
        val session = sessions[state] ?: return
        session.status = OAuthFlowStatus.OK
        session.resultAlias = alias
        session.resultEmail = email
        verifiers.remove(state)
        deviceCodes.remove(state)
        // 成功状态保留 60 秒供 UI 与管理端最后轮询
        session.expiresAt = System.currentTimeMillis() + 60_000L
        publish()
    }

    /**
     * 轮询查询会话状态（过期自动清理）
     */
    fun pollStatus(state: String): OAuthSession? {
        val session = sessions[state] ?: return null
        if (session.isExpired) {
            if (session.status == OAuthFlowStatus.WAIT) {
                session.status = OAuthFlowStatus.ERROR
                session.errorMessage = "OAuth session expired"
            }
            sessions.remove(state)
            publish()
        }
        return session
    }

    /** 查找某提供商进行中的会话（用于单飞控制） */
    fun findActiveByProvider(provider: ProviderType): OAuthSession? {
        cleanExpired()
        return sessions.values.firstOrNull {
            it.provider == provider && it.status == OAuthFlowStatus.WAIT && !it.isExpired
        }
    }

    fun peek(state: String): OAuthSession? {
        cleanExpired()
        return sessions[state]
    }

    fun cancelSession(state: String): Boolean {
        val removed = sessions.remove(state) != null
        verifiers.remove(state)
        deviceCodes.remove(state)
        publish()
        return removed
    }

    fun cleanExpired() {
        val now = System.currentTimeMillis()
        val expired = sessions.entries.removeIf { it.value.expiresAt <= now }
        if (expired) publish()
    }

    private fun shrinkTtl(session: OAuthSession) {
        session.expiresAt = System.currentTimeMillis() + 60_000L
    }

    private fun publish() {
        _sessionsFlow.value = sessions.values.sortedByDescending { it.createdAt }
    }
}
