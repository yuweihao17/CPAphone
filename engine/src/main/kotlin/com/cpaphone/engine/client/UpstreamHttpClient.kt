package com.cpaphone.engine.client

import com.cpaphone.core.disguise.ClientCloakInterceptor
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.ProviderType
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * 上游执行器结果
 */
sealed interface UpstreamCallResult {
    data class Success(
        val statusCode: HttpStatusCode,
        val headers: Headers,
        val responseChannel: ByteReadChannel,
        val isStreaming: Boolean
    ) : UpstreamCallResult

    data class Error(
        val statusCode: HttpStatusCode,
        val errorBody: String,
        val isRetryable: Boolean
    ) : UpstreamCallResult
}

/**
 * 工业级上游 HTTP 请求转发客户端
 * 具备请求指纹伪装（Cloak Mode）、首包隐式错误侦测（Stream Bootstrap Buffering）与低延迟管道透传特性
 */
class UpstreamHttpClient(
    private val connectTimeoutMs: Long = 15_000L,
    private val requestTimeoutMs: Long = 120_000L,
    private val enableCloaking: Boolean = true
) {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = requestTimeoutMs
            endpoint {
                connectTimeout = connectTimeoutMs
                keepAliveTime = 30_000L
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = 60_000L
        }
    }

    /**
     * 发起向上游的代理转发调用
     */
    suspend fun execute(
        credential: AuthCredential,
        secretKey: String,
        targetPath: String,
        method: HttpMethod,
        requestBody: String,
        headers: Map<String, String>,
        isStreaming: Boolean
    ): UpstreamCallResult = withContext(Dispatchers.IO) {
        val targetBaseUrl = resolveBaseUrl(credential)
        val fullUrl = targetBaseUrl.trimEnd('/') + "/" + targetPath.trimStart('/')

        try {
            // 1. 应用客户端安全指纹与请求披风规范
            val cloakedHeaders = ClientCloakInterceptor.applyHeaders(
                provider = credential.provider,
                incomingHeaders = headers,
                enableCloak = enableCloaking
            )

            val response = client.request(fullUrl) {
                this.method = method
                setBody(requestBody)
                contentType(ContentType.Application.Json)

                // 注入官方鉴权头
                injectAuthHeaders(this, credential.provider, secretKey)

                // 注入规范化 Header
                cloakedHeaders.forEach { (k, v) ->
                    if (!k.equals("Authorization", ignoreCase = true) &&
                        !k.equals("Host", ignoreCase = true) &&
                        !k.equals("Content-Length", ignoreCase = true)
                    ) {
                        header(k, v)
                    }
                }
            }

            if (response.status.isSuccess()) {
                val rawChannel = response.bodyAsChannel()

                // 2. 若为流式传输，执行首包缓冲探测 (Stream Bootstrap Buffering)
                // 拦截上游在 HTTP 200 流建立后立即吐出的 server_is_overloaded / quota_exceeded 伪成功错误
                if (isStreaming) {
                    val (inspectedChannel, hiddenError) = inspectStreamBootstrap(rawChannel)
                    if (hiddenError != null) {
                        return@withContext UpstreamCallResult.Error(
                            statusCode = HttpStatusCode.ServiceUnavailable,
                            errorBody = hiddenError,
                            isRetryable = true
                        )
                    }
                    UpstreamCallResult.Success(
                        statusCode = response.status,
                        headers = response.headers,
                        responseChannel = inspectedChannel,
                        isStreaming = true
                    )
                } else {
                    UpstreamCallResult.Success(
                        statusCode = response.status,
                        headers = response.headers,
                        responseChannel = rawChannel,
                        isStreaming = false
                    )
                }
            } else {
                val errorBody = response.bodyAsText()
                val code = response.status.value
                val isRetryable = code == 429 || code in 500..599 || code == 408
                UpstreamCallResult.Error(
                    statusCode = response.status,
                    errorBody = errorBody,
                    isRetryable = isRetryable
                )
            }
        } catch (e: Exception) {
            UpstreamCallResult.Error(
                statusCode = HttpStatusCode.ServiceUnavailable,
                errorBody = e.message ?: "Upstream connection failed",
                isRetryable = true
            )
        }
    }

    /**
     * 流首包缓冲探测器
     * 预读首行或头部数据，若发现隐藏故障字符串则捕获并触发透明重试；
     * 若正常，则返回拼接好的 ByteReadChannel 供下游零拷贝管道消费。
     */
    private suspend fun inspectStreamBootstrap(channel: ByteReadChannel): Pair<ByteReadChannel, String?> {
        val firstLine = channel.readUTF8Line(limit = 4096) ?: return Pair(channel, null)
        val lower = firstLine.lowercase()

        val isHiddenError = lower.contains("server_is_overloaded") ||
                lower.contains("quota_exceeded") ||
                lower.contains("insufficient_quota") ||
                lower.contains("model_overloaded")

        if (isHiddenError) {
            return Pair(channel, firstLine)
        }

        // 正常流：构建一个先下发 firstLine，再透传后续字节的无缝管道
        val synthesizedChannel = ByteChannel(autoFlush = true)
        val lineBytes = (firstLine + "\n").toByteArray(StandardCharsets.UTF_8)
        synthesizedChannel.writeFully(lineBytes, 0, lineBytes.size)

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).run {
            kotlinx.coroutines.launch {
                try {
                    channel.copyTo(synthesizedChannel)
                } finally {
                    synthesizedChannel.close()
                }
            }
        }

        return Pair(synthesizedChannel, null)
    }

    private fun resolveBaseUrl(credential: AuthCredential): String {
        if (!credential.customBaseUrl.isNullOrBlank()) {
            return credential.customBaseUrl
        }
        return when (credential.provider) {
            ProviderType.CLAUDE -> "https://api.anthropic.com"
            ProviderType.OPENAI_CODEX -> "https://api.openai.com"
            ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> "https://generativelanguage.googleapis.com"
            ProviderType.XAI -> "https://api.x.ai"
            ProviderType.KIMI -> "https://api.moonshot.cn"
            ProviderType.VERTEX_AI -> "https://us-central1-aiplatform.googleapis.com"
            ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com"
        }
    }

    private fun injectAuthHeaders(
        builder: HttpRequestBuilder,
        provider: ProviderType,
        secretKey: String
    ) {
        if (secretKey.isBlank()) return
        when (provider) {
            ProviderType.CLAUDE -> {
                builder.header("x-api-key", secretKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> {
                builder.header("x-goog-api-key", secretKey)
            }
            else -> {
                builder.header("Authorization", "Bearer $secretKey")
            }
        }
    }

    fun close() {
        client.close()
    }
}
