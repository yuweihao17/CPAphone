package com.cpaphone.engine.oauth

import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.OAuthProviderSpec
import com.cpaphone.core.oauth.parseJwtEmail
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** OAuth token 兑换结果 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresIn: Long? = null,
    val email: String? = null
)

/** 设备码申请结果（RFC 8628 §3.2） */
data class DeviceCodeInfo(
    val deviceCode: String,
    val verificationUrl: String,
    val userCode: String?,
    val intervalMs: Long
)

class OAuthTokenException(message: String, val errorCode: String? = null) : Exception(message)

/**
 * 提供商 OAuth Token 客户端
 * 精确对齐 CLIProxyAPI 各认证器的兑换/轮询/刷新协议（form vs JSON、伪装头、client_secret）
 */
class OAuthTokenClient(
    private val client: HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000L
        }
        expectSuccess = false
    }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val randomDeviceId: String = java.util.UUID.randomUUID().toString()

    // xAI OIDC 发现缓存
    @Volatile
    private var xaiDeviceAuthEndpoint: String? = null

    @Volatile
    private var xaiTokenEndpoint: String? = null

    /**
     * 授权码兑换 access_token（CODE_PKCE / CODE_SECRET 流）
     */
    suspend fun exchangeCode(provider: ProviderType, spec: OAuthProviderSpec, code: String, verifier: String?): OAuthTokens {
        val response: HttpResponse = when (provider) {
            ProviderType.CLAUDE -> {
                // Claude 用 JSON 请求体并伪装 Claude Code 客户端（axios）指纹
                val body = buildJsonObject {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("redirect_uri", spec.redirectUri ?: "")
                    put("client_id", spec.clientId)
                    put("code_verifier", verifier ?: "")
                    put("state", "")
                }.toString()
                client.post(spec.tokenUrl) {
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    header(HttpHeaders.UserAgent, "axios/1.15.2")
                    header(HttpHeaders.AcceptEncoding, "gzip, compress, deflate, br")
                    header(HttpHeaders.Connection, "close")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            else -> {
                val form = buildList {
                    add("grant_type=authorization_code")
                    add("code=${enc(code)}")
                    spec.redirectUri?.let { add("redirect_uri=${enc(it)}") }
                    add("client_id=${enc(spec.clientId)}")
                    verifier?.let { add("code_verifier=${enc(it)}") }
                    spec.clientSecret?.let { add("client_secret=${enc(it)}") }
                }.joinToString("&")
                client.post(spec.tokenUrl) {
                    header(HttpHeaders.Accept, "application/json")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form)
                }
            }
        }
        val obj = parseResponse(response, "token exchange")
        return OAuthTokens(
            accessToken = obj.str("access_token") ?: throw OAuthTokenException("token response missing access_token"),
            refreshToken = obj.str("refresh_token"),
            idToken = obj.str("id_token"),
            expiresIn = obj.long("expires_in")
        )
    }

    /**
     * 申请设备码（DEVICE_CODE 流）。xAI 先经 OIDC 发现端点
     */
    suspend fun requestDeviceCode(provider: ProviderType, spec: OAuthProviderSpec): DeviceCodeInfo {
        val (authEndpoint, tokenEndpoint) = if (provider == ProviderType.XAI) {
            discoverXaiEndpoints(spec)
            Pair(xaiDeviceAuthEndpoint ?: spec.deviceAuthUrl!!, xaiTokenEndpoint ?: spec.tokenUrl)
        } else {
            Pair(spec.deviceAuthUrl!!, spec.tokenUrl)
        }
        rememberXaiTokenEndpoint(provider, tokenEndpoint)

        val form = buildList {
            add("client_id=${enc(spec.clientId)}")
            spec.scope?.takeIf { provider == ProviderType.XAI }?.let { add("scope=${enc(it)}") }
        }.joinToString("&")

        val response = client.post(authEndpoint) {
            applyProviderHeaders(provider, spec)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form)
        }
        val obj = parseResponse(response, "device authorization")
        return DeviceCodeInfo(
            deviceCode = obj.str("device_code") ?: throw OAuthTokenException("device response missing device_code"),
            verificationUrl = obj.str("verification_uri_complete") ?: obj.str("verification_uri")
                ?: throw OAuthTokenException("device response missing verification_uri"),
            userCode = obj.str("user_code"),
            intervalMs = (obj.long("interval") ?: 5L) * 1000
        )
    }

    /**
     * 设备码轮询换取 token：authorization_pending 继续、slow_down 加速间隔，上限 30 分钟
     */
    suspend fun pollDeviceToken(provider: ProviderType, spec: OAuthProviderSpec, deviceCode: String, startIntervalMs: Long): OAuthTokens {
        val tokenEndpoint = if (provider == ProviderType.XAI) xaiTokenEndpoint ?: spec.tokenUrl else spec.tokenUrl
        var interval = startIntervalMs
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val form = buildList {
                add("grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}")
                add("device_code=${enc(deviceCode)}")
                add("client_id=${enc(spec.clientId)}")
            }.joinToString("&")
            val response = client.post(tokenEndpoint) {
                applyProviderHeaders(provider, spec)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }
            val text = response.bodyAsText()
            val obj = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
            val error = obj?.str("error")
            when {
                response.status.isSuccess() && obj?.str("access_token") != null -> {
                    return OAuthTokens(
                        accessToken = obj.str("access_token")!!,
                        refreshToken = obj.str("refresh_token"),
                        idToken = obj.str("id_token"),
                        expiresIn = obj.long("expires_in")
                    )
                }
                error == "authorization_pending" -> Unit // 继续轮询
                error == "slow_down" -> interval += 5_000L
                error != null -> throw OAuthTokenException("device flow failed: $error", error)
                else -> throw OAuthTokenException("device flow unexpected response: HTTP ${response.status.value}")
            }
        }
        throw OAuthTokenException("device flow timed out")
    }

    /**
     * 刷新 access_token（各家请求格式不同，对齐 CLIProxyAPI）
     */
    suspend fun refresh(provider: ProviderType, spec: OAuthProviderSpec, refreshToken: String): OAuthTokens {
        val tokenEndpoint = if (provider == ProviderType.XAI) xaiTokenEndpoint ?: spec.tokenUrl else spec.tokenUrl
        val response: HttpResponse = when (provider) {
            ProviderType.CLAUDE -> {
                val body = buildJsonObject {
                    put("client_id", spec.clientId)
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("scope", spec.scope ?: "")
                }.toString()
                client.post(tokenEndpoint) {
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    header(HttpHeaders.UserAgent, "axios/1.15.2")
                    header(HttpHeaders.Connection, "close")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            else -> {
                val form = buildList {
                    add("grant_type=refresh_token")
                    add("refresh_token=${enc(refreshToken)}")
                    add("client_id=${enc(spec.clientId)}")
                    spec.clientSecret?.let { add("client_secret=${enc(it)}") }
                    spec.refreshScope?.let { add("scope=${enc(it)}") }
                }.joinToString("&")
                client.post(tokenEndpoint) {
                    header(HttpHeaders.Accept, "application/json")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form)
                }
            }
        }
        val obj = parseResponse(response, "token refresh")
        return OAuthTokens(
            accessToken = obj.str("access_token") ?: throw OAuthTokenException("refresh response missing access_token"),
            refreshToken = obj.str("refresh_token"),
            idToken = obj.str("id_token"),
            expiresIn = obj.long("expires_in")
        )
    }

    /**
     * 补齐账号 email：Antigravity 走 Google userinfo，Claude 走 OAuth profile，其余从 id_token JWT 解析
     */
    suspend fun fetchEmail(provider: ProviderType, accessToken: String, idToken: String?): String? {
        return when (provider) {
            ProviderType.ANTIGRAVITY -> try {
                val response = client.get("https://www.googleapis.com/oauth2/v2/userinfo?alt=json") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                val body = response.bodyAsText()
                json.parseToJsonElement(body).jsonObject.str("email")
            } catch (_: Exception) { null }
            ProviderType.CLAUDE -> try {
                val response = client.get("https://api.anthropic.com/api/oauth/profile") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header(HttpHeaders.Accept, "application/json")
                }
                val body = response.bodyAsText()
                json.parseToJsonElement(body).jsonObject.let { it.str("email") ?: it.str("email_address") }
            } catch (_: Exception) { null }
            else -> idToken?.let { parseJwtEmail(it) }
        }
    }

    private suspend fun discoverXaiEndpoints(spec: OAuthProviderSpec) {
        if (xaiDeviceAuthEndpoint != null) return
        val url = spec.oidcDiscoveryUrl ?: return
        val response = client.get(url)
        val obj = parseResponse(response, "OIDC discovery")
        xaiDeviceAuthEndpoint = obj.str("device_authorization_endpoint")
        xaiTokenEndpoint = obj.str("token_endpoint")
    }

    private fun rememberXaiTokenEndpoint(provider: ProviderType, tokenEndpoint: String) {
        if (provider == ProviderType.XAI) xaiTokenEndpoint = tokenEndpoint
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyProviderHeaders(provider: ProviderType, spec: OAuthProviderSpec) {
        if (provider == ProviderType.KIMI) {
            header("X-Msh-Platform", "CPAphone")
            header("X-Msh-Version", "1.1.0")
            header("X-Msh-Device-Name", "android")
            header("X-Msh-Device-Model", android.os.Build.MODEL ?: "Android")
            header("X-Msh-Device-Id", randomDeviceId)
        }
        header(HttpHeaders.Accept, "application/json")
    }

    private suspend fun parseResponse(response: HttpResponse, action: String): JsonObject {
        val text = response.bodyAsText()
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
        if (!response.status.isSuccess()) {
            val error = obj?.str("error")
            throw OAuthTokenException(
                "$action failed: HTTP ${response.status.value}${error?.let { " ($it)" } ?: ""} ${if (text.length > 200) "..." else text.take(200)}",
                error
            )
        }
        return obj ?: throw OAuthTokenException("$action response is not a JSON object")
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { try { it.jsonPrimitive.content } catch (_: Exception) { null } }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.let { try { it.jsonPrimitive.content.toLongOrNull() } catch (_: Exception) { null } }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
