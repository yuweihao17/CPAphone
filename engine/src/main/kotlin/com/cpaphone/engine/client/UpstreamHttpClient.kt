package com.cpaphone.engine.client

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
 * 支持管道化字节流直通，支持首包侦测与超时控制
 */
class UpstreamHttpClient(
    private val proxyUrl: String? = null
) {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 120_000L
            endpoint {
                connectTimeout = 15_000L
                keepAliveTime = 30_000L
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 15_000L
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
            val response = client.request(fullUrl) {
                this.method = method
                setBody(requestBody)
                contentType(ContentType.Application.Json)

                // 注入鉴权头
                injectAuthHeaders(this, credential.provider, secretKey)

                // 注入自定义 Header
                headers.forEach { (k, v) ->
                    if (!k.equals("Authorization", ignoreCase = true) &&
                        !k.equals("Host", ignoreCase = true)
                    ) {
                        header(k, v)
                    }
                }
            }

            if (response.status.isSuccess()) {
                val channel = response.bodyAsChannel()
                UpstreamCallResult.Success(
                    statusCode = response.status,
                    headers = response.headers,
                    responseChannel = channel,
                    isStreaming = isStreaming
                )
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
