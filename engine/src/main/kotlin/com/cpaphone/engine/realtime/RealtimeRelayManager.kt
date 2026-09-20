package com.cpaphone.engine.realtime

import com.cpaphone.core.model.*
import com.cpaphone.engine.coordinator.CredentialCoordinator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 实时音视频与 WebRTC 会话中继管理器
 * 对齐 CLIProxyAPI internal/client/codex/live/ 架构体系：
 * 1. 负责生成与鉴权临时客户端凭据 (Client Secret ek_...)；
 * 2. 负责管理活跃 WebRTC 通话会话池 (sessionStore)，提供排他 Claim 互斥认领；
 * 3. 负责协作处理 SDP Offer 协商、SDP Answer 交付与通话挂断 (Hangup)。
 */
class RealtimeRelayManager(
    private val coordinator: CredentialCoordinator
) {
    // callId -> RealtimeSession
    private val sessionStore = ConcurrentHashMap<String, RealtimeSession>()

    // token(ek_...) -> ClientSecretResponse
    private val clientSecrets = ConcurrentHashMap<String, ClientSecretResponse>()

    // 伴生信令通道当前占用互斥标记 (callId -> isClaimed)
    private val sidebandClaims = ConcurrentHashMap<String, Boolean>()

    /**
     * 生成短期临时客户端凭据 (Client Secret)
     */
    fun createClientSecret(request: ClientSecretRequest): ClientSecretResponse {
        cleanExpired()
        val token = "ek_" + UUID.randomUUID().toString().replace("-", "")
        val sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 12)
        val expiresAt = System.currentTimeMillis() + (request.expiresAfterSeconds * 1000L)

        val response = ClientSecretResponse(
            value = token,
            expiresAt = expiresAt,
            sessionId = sessionId
        )
        clientSecrets[token] = response
        return response
    }

    /**
     * 验证 Client Secret 是否合法且未过期
     */
    fun validateClientSecret(token: String): Boolean {
        val secret = clientSecrets[token] ?: return false
        if (System.currentTimeMillis() >= secret.expiresAt) {
            clientSecrets.remove(token)
            return false
        }
        return true
    }

    /**
     * 创建或注册新的 WebRTC 通话会话
     */
    fun registerSession(
        callId: String,
        credential: AuthCredential,
        model: String,
        clientSecret: String? = null
    ): RealtimeSession {
        cleanExpired()
        val session = RealtimeSession(
            callId = callId,
            credentialId = credential.id,
            credentialAlias = credential.alias,
            model = model,
            status = RealtimeSessionStatus.CONNECTING,
            clientSecretToken = clientSecret
        )
        sessionStore[callId] = session
        return session
    }

    /**
     * 伴生信令通道互斥认领 (Claim)
     * 若会话不存在返回 null，若已被其他连接抢占则返回 null (对应 HTTP 409 Conflict)
     */
    fun claimSideband(callId: String): RealtimeSession? {
        val session = sessionStore[callId] ?: return null
        if (session.isExpired) {
            sessionStore.remove(callId)
            return null
        }

        // 原子抢占
        val alreadyClaimed = sidebandClaims.putIfAbsent(callId, true)
        if (alreadyClaimed == true) {
            return null // 已被占用
        }
        return session
    }

    /**
     * 释放伴生信令通道占用
     */
    fun releaseSideband(callId: String) {
        sidebandClaims.remove(callId)
    }

    /**
     * 挂断通话 (Hangup)，清理媒体与信令通道
     */
    fun hangup(callId: String): Boolean {
        val session = sessionStore.remove(callId) ?: return false
        sidebandClaims.remove(callId)
        return true
    }

    /**
     * 获取指定活跃通话
     */
    fun getSession(callId: String): RealtimeSession? {
        val session = sessionStore[callId] ?: return null
        if (session.isExpired) {
            sessionStore.remove(callId)
            return null
        }
        return session
    }

    /**
     * 生成标准合规的 WebRTC SDP Answer (用于本地代理仿真及无上游直连时的自愈响应)
     */
    fun generateSyntheticSdpAnswer(offerSdp: String): String {
        return """
            v=0
            o=- 1740000000 2 IN IP4 127.0.0.1
            s=CPAphone-WebRTC
            t=0 0
            a=group:BUNDLE 0 1
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 127.0.0.1
            a=rtcp:9 IN IP4 127.0.0.1
            a=ice-ufrag:cpaphone
            a=ice-pwd:cpaphonepassword1234
            a=fingerprint:sha-256 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00
            a=setup:active
            a=mid:0
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
            a=sendrecv
            m=application 9 DTLS/SCTP 5000
            c=IN IP4 127.0.0.1
            a=mid:1
            a=sctp-port:5000
        """.trimIndent()
    }

    /**
     * 定期自动清理超时与失效的会话与 Token
     */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        sessionStore.entries.removeIf { it.value.expiresAt <= now }
        clientSecrets.entries.removeIf { it.value.expiresAt <= now }
        sidebandClaims.keys.removeIf { !sessionStore.containsKey(it) }
    }

    fun activeCallCount(): Int {
        cleanExpired()
        return sessionStore.size
    }
}
