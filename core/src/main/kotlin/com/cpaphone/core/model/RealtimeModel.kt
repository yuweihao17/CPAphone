package com.cpaphone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 实时 WebRTC / 语音会话状态
 */
@Serializable
enum class RealtimeSessionStatus {
    CONNECTING, // 正在建立 WebRTC / ICE 协商
    ACTIVE,     // 通话中，双向 RTP 音频流就绪
    MUTED,      // 客户端静音
    CLOSED      // 通话已挂断或超时
}

/**
 * 实时音视频会话实体，对齐 CLIProxyAPI liveSession
 */
@Serializable
data class RealtimeSession(
    val callId: String,
    val credentialId: String,
    val credentialAlias: String,
    val model: String,
    val status: RealtimeSessionStatus = RealtimeSessionStatus.CONNECTING,
    val clientSecretToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 3600_000L // 默认 1 小时 TTL
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt
}

/**
 * WebRTC SDP Offer 协商请求体
 */
@Serializable
data class SdpOfferRequest(
    val sdp: String,
    val model: String = "gpt-4o-realtime-preview",
    val voice: String = "alloy",
    val instructions: String? = null
)

/**
 * WebRTC SDP Answer 协商响应体
 */
@Serializable
data class SdpAnswerResponse(
    val sdp: String,
    val callId: String
)

/**
 * 临时凭据 (Client Secret) 签发请求
 */
@Serializable
data class ClientSecretRequest(
    val model: String = "gpt-4o-realtime-preview",
    val voice: String = "alloy",
    val expiresAfterSeconds: Int = 600
)

/**
 * 临时凭据 (Client Secret) 签发响应
 */
@Serializable
data class ClientSecretResponse(
    val value: String, // 形如 "ek_..."
    val expiresAt: Long,
    val sessionId: String
)

/**
 * 实时 DataChannel / WebSocket 事件标准模型 (oai-events)
 */
@Serializable
sealed interface RealtimeEvent {
    val type: String

    @Serializable
    data class TextDelta(
        override val type: String = "response.audio_transcript.delta",
        val delta: String
    ) : RealtimeEvent

    @Serializable
    data class SpeechStarted(
        override val type: String = "input_audio_buffer.speech_started",
        val audioStartMs: Long = 0L
    ) : RealtimeEvent

    @Serializable
    data class SpeechStopped(
        override val type: String = "input_audio_buffer.speech_stopped",
        val audioEndMs: Long = 0L
    ) : RealtimeEvent

    @Serializable
    data class CancelResponse(
        override val type: String = "response.cancel"
    ) : RealtimeEvent

    @Serializable
    data class Generic(
        override val type: String,
        val payload: JsonObject? = null
    ) : RealtimeEvent
}
